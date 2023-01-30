package com.machinepublishers.neuraltrader;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class Main {

  private static final boolean SERVE = Boolean.parseBoolean(System.getProperty("serve"));
  private static final String SERVER = System.getProperty("server");
  private static final int GROUP = Integer.parseInt(System.getProperty("group"));
  private static final int NETS = Integer.parseInt(System.getProperty("nets"));
  private static final int GROUPS = Integer.parseInt(System.getProperty("groups"));
  private static final long INTERVAL = 1000L * Integer.parseInt(System.getProperty("interval"));
  static{
    System.setProperty( "java.rmi.server.hostname", SERVER ) ;
  }

  private static final int TRIES = 32;
  private static final int CHANCE = 80_000;
  private static final double MARGIN = .04d;
  private static final int PRICE_HISTORY = 24 * 60;
  private static final int WINDOW = 30;
  private static final int BUFFER_LEN = 2 * (PRICE_HISTORY + WINDOW * 2);
  private static final int EVAL_CHILDREN = 61;

  private static final Prices prices = new Prices();
  private static final Random rand = new SecureRandom();
  private static final AtomicReferenceArray<NeuralNet> nets = new AtomicReferenceArray<>(NETS);
  private static final AtomicReferenceArray<NeuralNet> prevNets = new AtomicReferenceArray<>(NETS);
  private static final NeuralNet[][] evalNets = new NeuralNet[NETS][EVAL_CHILDREN + 2];
  private static final NeuralNet[][] evalNetsExt = new NeuralNet[NETS][EVAL_CHILDREN + 3];
  private static final int[][] firstPlaceFinishes = new int[NETS][evalNetsExt[0].length];
  private static final int[][] curProfits = new int[NETS][evalNetsExt[0].length];
  private static final Server server;

  static {
    if (SERVE) {
      server = startServer();
    } else {
      server = getServer(SERVER);
    }
  }

  public static void main(String[] args) {
    for (int n = 0; n < NETS * GROUPS; n++) {
      initNet(n);
    }
    for (int n = 0; n < NETS; n++) {
      NeuralNet net = initNet(GROUP * NETS + n);
      NeuralNet prev = net.getPrev();
      nets.set(n, net);
      prevNets.set(n, prev == null ? net : prev);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (int n = 0; n < NETS; n++) {
        nets.get(n).save();
        prevNets.get(n).savePrev();
      }
      Log.info("\nSaved.");
    }));

    for (int n = 0; n < NETS; n++) {
      startEval(getNet(n), n);
    }
    startTestLogs();
    startAutoSave();
  }

  private static Server startServer() {
    try {
      Registry registry = LocateRegistry.createRegistry(18384);
      Server server = new ServerImpl();
      Server stub = (Server) UnicastRemoteObject.exportObject(server, 18384);
      registry.rebind(Server.class.getSimpleName(), stub);
      return server;
    } catch (RemoteException e) {
      throw new RuntimeException(e);
    }
  }

  private static Server getServer(String serverHost) {
    try {
      Registry registry = LocateRegistry.getRegistry(serverHost, 18384);
      return (Server) registry.lookup(Server.class.getSimpleName());
    } catch (RemoteException | NotBoundException e) {
      throw new RuntimeException(e);
    }
  }

  private static void startEval(NeuralNet net, int index) {
    new Thread(() -> {
      NeuralNet cur = net;
      while (true) {
        NeuralNet next = eval(cur, index);
        if (next != cur) {
          save(next, index);
          cur = next;
        }
      }
    }, "n" + (GROUP * NETS + index)).start();
  }

  private static void startTestLogs() {
    new Thread(() -> {
      int[] profitHistory = new int[30];
      int[][] profitHistoryDetail = new int[NETS][30];
      double[] scaleHistory = new double[30];
      for (long x = 0; x < Long.MAX_VALUE; x++) {
        int[] data = prices.getData(false);
        int offset = randTime(data);
        double maxProfit = Integer.MIN_VALUE;
        double maxLoss = 0;
        for (int i = 0; i < WINDOW * 2 - 1; i++) {
          int sell = data[2 * (i + offset)];
          for (int start = Math.max(WINDOW, i + 1), j = start;
              j < WINDOW * 2 && j - start <= WINDOW; j++) {
            int buy = data[2 * (j + offset)];
            maxProfit = Math.max(maxProfit, sell - buy);
            maxLoss = Math.min(maxLoss, sell - buy);
          }
        }
        int scale = (int) Math.rint(maxProfit - maxLoss);
        int center = (int) Math.rint(maxLoss);
        maxProfit /= 100d;
        maxLoss /= 100d;
        Log.info("========================================");
        Log.info("===== $%.2f : %.2f ", maxProfit, maxLoss);
        Log.info("========================================");
        int totalProfit = 0;
        int totalScale = 0;
        for (int n = 0; n < NETS; n++) {
          NeuralNet net = getNet(n);
          int profit = profit(net, data, offset);
          totalProfit += profit;
          totalScale += profit - center;
          profitHistoryDetail[n][(int) (x % profitHistoryDetail[n].length)] = profit;
          int detailTotal = 0;
          for (int i = 0; i < profitHistoryDetail[n].length; i++) {
            detailTotal += profitHistoryDetail[n][i];
          }
          Log.info("=> N%02d: %.2f (%.2f)", GROUP * NETS + n, profit / 100d, detailTotal / 100d);
        }
        profitHistory[(int) (x % profitHistory.length)] = totalProfit;
        scaleHistory[(int) (x % scaleHistory.length)] = Math.min(1,
            Math.max(0, (double) totalScale / (double) (scale * NETS)));
        int allTimeProfit = 0;
        for (int i = 0; i < profitHistory.length; i++) {
          allTimeProfit += profitHistory[i];
        }
        double allTimeScale = 0;
        for (int i = 0; i < scaleHistory.length; i++) {
          allTimeScale += scaleHistory[i];
        }
        allTimeScale = Math.max(0, allTimeScale);
        allTimeScale = Math.min(100,
            Math.max(0, allTimeScale / Math.min(x + 1, scaleHistory.length) * 100));
        Log.info("========================================");
        Log.info("===== %s %.3f%% ($%.2f)", allTimeProfit > 0 ? "WINNING" : "LOSING", allTimeScale,
            Math.abs(allTimeProfit / 100d));
        Log.info("========================================");
        try {
          long now = System.currentTimeMillis();
          Thread.sleep(INTERVAL - (now % INTERVAL));
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }).start();
  }

  private static void startAutoSave() {
    new Thread(() -> {
      while (true) {
        for (int n = 0; n < NETS; n++) {
          NeuralNet cur = nets.get(n);
          NeuralNet prev = prevNets.get(n);
          cur.save();
          prev.savePrev();
          try {
            server.upload(getKey(), cur, prev);
          } catch (RemoteException e) {
            e.printStackTrace();
          }
        }
        try {
          Thread.sleep(10 * 60 * 1000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }).start();
  }

  private static NeuralNet getNet(int index) {
    return nets.get(index);
  }

  private static NeuralNet initNet(int index) {
    return NeuralNet.create(index, 33, 48, PRICE_HISTORY * 2);
  }

  private static NeuralNet save(NeuralNet next, int index) {
    prevNets.set(index, nets.getAndSet(index, next));
    return next;
  }

  private static NeuralNet prev(int index) {
    return prevNets.get(index);
  }

  private static NeuralNet randOther(int index, boolean sameGroup) {
    if (sameGroup) {
      int randItem = rand.nextInt(NETS - 1);
      randItem += randItem < index ? 0 : 1;
      return nets.get(randItem);
    }
    int randItem = rand.nextInt(NETS);
    int randGroup = rand.nextInt(GROUPS - 1);
    randGroup += randGroup < GROUP ? 0 : 1;
    try {
      return server.download(getKey(), randGroup * NETS + randItem, GROUP * NETS + index);
    } catch (RemoteException e) {
      e.printStackTrace();
      return randOther(index, true);
    }
  }

  private static int randTime(int[] data) {
    return rand.nextInt((data.length - BUFFER_LEN) / 2);
  }

  private static NeuralNet eval(NeuralNet orig, int index) {
    int factor = GROUP + 1;
    NeuralNet prev = prev(index);
    NeuralNet[] nets = prev == orig ? evalNets[index] : evalNetsExt[index];
    int i = 0;
    NeuralNet sibling = randOther(index, true);
    if (rand.nextInt(300) == 0) {
      nets[i++] = randOther(index, false);
    } else if (rand.nextInt(10) == 0) {
      nets[i++] = sibling.clone(GROUP * NETS + index);
    } else {
      nets[i++] = orig.mutate(factor * MARGIN, factor * CHANCE);
    }
    for (int x = 0; x < EVAL_CHILDREN; x++) {
      nets[i++] = orig.mergeAndMutate(sibling, 25, factor * MARGIN, factor * CHANCE);
    }
    nets[i++] = orig;
    if (prev != orig) {
      nets[i] = prev;
    }
    return evalScaled(nets, index);
  }

  private static NeuralNet evalScaled(NeuralNet[] nets, int index) {
    Arrays.fill(curProfits[index], 0);
    Arrays.fill(firstPlaceFinishes[index], 0);
    for (int i = 0; i < TRIES; i++) {
      int[] data = prices.getData(true);
      int offset = randTime(data);
      int bestProfit = Integer.MIN_VALUE;
      for (int n = 0; n < nets.length; n++) {
        int profit = profit(nets[n], data, offset);
        curProfits[index][n] = profit;
        if (profit > bestProfit) {
          bestProfit = profit;
        }
      }
      for (int n = 0; n < nets.length; n++) {
        if (curProfits[index][n] == bestProfit) {
          ++firstPlaceFinishes[index][n];
        }
      }
    }
    int bestPlace = Integer.MIN_VALUE;
    for (int n = 0; n < nets.length; n++) {
      if (firstPlaceFinishes[index][n] > bestPlace) {
        bestPlace = firstPlaceFinishes[index][n];
      }
    }
    for (int n = nets.length - 1; n > -1; n--) {
      if (firstPlaceFinishes[index][n] == bestPlace) {
        return nets[n];
      }
    }
    return nets[0];
  }

  private static int profit(NeuralNet net, int[] data, int offset) {
    int buyTime = -1;
    int buyOffset = -1;
    for (int t = WINDOW * 2; t > WINDOW; t--) {
      int cur = (offset + t - 1) * 2;
      if (Decision.BUY == net.decide(data, cur)) {
        buyTime = t;
        buyOffset = cur;
        break;
      }
    }
    if (buyTime > -1) {
      for (int t = buyTime - 1; t > buyTime - 1 - WINDOW; t--) {
        int cur = (offset + t - 1) * 2;
        if (Decision.SELL == net.decide(data, cur)) {
          return data[cur] - data[buyOffset];
        }
      }
      return data[(offset + buyTime - WINDOW - 1) * 2] - data[buyOffset];
    }
    return 0;
  }

  public static String getKey() {
    return "v1^1k?UV(8R,4.a92IsnH6g";
  }
}