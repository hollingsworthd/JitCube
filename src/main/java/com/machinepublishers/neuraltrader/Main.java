package com.machinepublishers.neuraltrader;

import com.machinepublishers.neuraltrader.Prices.Marker;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class Main {

  private static final boolean SERVE = Boolean.parseBoolean(System.getProperty("serve"));
  private static final String SERVER = System.getProperty("server");
  private static final int GROUP = Integer.parseInt(System.getProperty("group"));
  private static final int NETS = Integer.parseInt(System.getProperty("nets"));
  private static final int GROUPS = Integer.parseInt(System.getProperty("groups"));
  private static final long INTERVAL = 1000L * Integer.parseInt(System.getProperty("interval"));
  private static final int TRIES = 48;
  private static final int PRICE_HISTORY = 6 * 60;
  private static final int WINDOW = 30;
  private static final Prices prices = new Prices(PRICE_HISTORY + (2 * WINDOW));
  private static final Random rand = new SecureRandom();
  private static final AtomicReferenceArray<NeuralNet> nets = new AtomicReferenceArray<>(NETS);
  private static final AtomicLongArray evolutions = new AtomicLongArray(NETS);
  private static final Server server;

  static {
    System.setProperty("java.rmi.server.hostname", SERVER);
  }

  static {
    Server serverTmp;
    try {
      if (SERVE) {
        serverTmp = startServer();
      } else {
        serverTmp = getServer(SERVER);
      }
    } catch (RemoteException | NotBoundException e) {
      e.printStackTrace();
      serverTmp = null;
    }
    server = serverTmp;
  }

  public static void main(String[] args) {
    for (int n = 0; n < NETS * GROUPS; n++) {
      initNet(n);
    }
    for (int n = 0; n < NETS; n++) {
      NeuralNet net = initNet(GROUP * NETS + n);
      nets.set(n, net);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (int n = 0; n < NETS; n++) {
        nets.get(n).save();
      }
      if (server != null) {
        for (int n = 0; n < NETS; n++) {
          try {
            server.upload(getKey(), nets.get(n));
          } catch (RemoteException e) {
            e.printStackTrace();
          }
        }
      }
      Log.info("\nSaved.");
    }));

    for (int n = 0; n < NETS; n++) {
      startEval(nets.get(n), n);
    }
    startTestLogs();
    startAutoSave();
  }

  private static Server startServer() throws RemoteException {
    Registry registry = LocateRegistry.createRegistry(18384);
    Server server = new ServerImpl(prices);
    Server stub = (Server) UnicastRemoteObject.exportObject(server, 18384);
    registry.rebind(Server.class.getSimpleName(), stub);
    return server;
  }

  private static Server getServer(String serverHost) throws RemoteException, NotBoundException {
    Registry registry = LocateRegistry.getRegistry(serverHost, 18384);
    return (Server) registry.lookup(Server.class.getSimpleName());
  }

  private static void startEval(NeuralNet net, int index) {
    new Thread(() -> {
//      long dur = 0, iter = 0; //
      while (true) {
//        ++iter; //
//        long start = System.nanoTime(); //
        eval(index);
//        dur += System.nanoTime() - start; //
//        if (iter % 10 == 0) { //
//          System.out.println((int) (dur / (iter * 1_000_000d))); //
//        } //
      }
    }, "n" + (GROUP * NETS + index)).start();
  }

  private static void startTestLogs() {
    new Thread(() -> {
      final int day = 48;
      final int recent = 336;
      final int allTime = 4383;
      int[] profitHistory = new int[allTime];
      int[][] profitHistoryDetail = new int[NETS][allTime];
      Marker marker;
      for (long x = 0; x < Long.MAX_VALUE; x++) {
        try {
          long now = System.currentTimeMillis();
          long sleep = INTERVAL - (now % INTERVAL);
          try {
            marker = server.randPriceMarker(getKey(), false, sleep + now);
          } catch (RemoteException e) {
            e.printStackTrace();
            marker = prices.rand(false);
          }
          Thread.sleep(INTERVAL - (now % INTERVAL));
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }

        int[] data = prices.getData(marker);
        int totalProfit = 0;
        int cur = (int) (x % profitHistory.length);
        Log.info("=======================================================");
        for (int n = 0; n < NETS; n++) {
          NeuralNet net = nets.get(n);
          int profit = profit(net, data, marker.offset());
          totalProfit += profit;
          profitHistoryDetail[n][cur] = profit;
          int detailTotal = 0;
          int detailTotalRecent = 0;
          int detailTotalDay = 0;
          for (int i = 0; i < profitHistoryDetail[n].length; i++) {
            detailTotal += profitHistoryDetail[n][i];
          }
          for (int i = 0; i < recent; i++) {
            int index = cur - i;
            index = index < 0 ? allTime + index : index;
            detailTotalRecent += profitHistoryDetail[n][index];
          }
          for (int i = 0, start = cur - (cur % day); i < day; i++) {
            int index = start - i - 1;
            index = index < 0 ? index + allTime : index;
            detailTotalDay += profitHistoryDetail[n][index];
          }
          long evolution = evolutions.get(n);
          if (evolution >= 1_000_000) {
            evolutions.set(n, 0);
            evolution = 0;
          }
          Log.info(">N%02d %06d: %.2f (%.2f) (%.2f) (%.2f)", GROUP * NETS + n, evolution,
              profit / 100d, detailTotal / 100d, detailTotalRecent / 100d, detailTotalDay / 100d);
        }
        profitHistory[cur] = totalProfit;
        int profit = 0;
        int profitRecent = 0;
        int profitDay = 0;
        for (int i = 0; i < profitHistory.length; i++) {
          profit += profitHistory[i];
        }
        for (int i = 0; i < recent; i++) {
          int index = cur - i;
          index = index < 0 ? allTime + index : index;
          profitRecent += profitHistory[index];
        }
        for (int i = 0, start = cur - (cur % day); i < day; i++) {
          int index = start - i - 1;
          index = index < 0 ? index + allTime : index;
          profitDay += profitHistory[index];
        }
        Log.info("=======================================================");
        Log.info("===== (%.2f) (%.2f) (%.2f)", profit / 100d, profitRecent / 100d,
            profitDay / 100d);
        Log.info("=======================================================");
      }
    }).start();
  }

  private static void startAutoSave() {
    new Thread(() -> {
      while (true) {
        for (int n = 0; n < NETS; n++) {
          NeuralNet cur = nets.get(n);
          if (server != null) {
            try {
              server.upload(getKey(), cur);
            } catch (RemoteException e) {
              e.printStackTrace();
            }
          }
        }
        try {
          Thread.sleep(60 * 1000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }).start();
  }

  private static NeuralNet initNet(int index) {
    return NeuralNet.create(index / NETS, index, 5, 45, PRICE_HISTORY);
  }

  private static NeuralNet save(NeuralNet next, int index) {
    nets.set(index, next);
    return next;
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
    if (server != null) {
      try {
        return server.download(getKey(), randGroup * NETS + randItem, GROUP * NETS + index);
      } catch (RemoteException e) {
        e.printStackTrace();
      }
    }
    return randOther(index, true);
  }

  private static void eval(int index) {
    NeuralNet orig = nets.get(index);
    NeuralNet next;
    if (rand.nextInt(20_000) == 0) {
      next = randOther(index, false).clone(GROUP * NETS + index, true);
    } else {
      next = orig.mergeAndMutate(randOther(index, true), 25, 125_000);
    }
    NeuralNet best = evalScaled(orig, next, index);
    if (best != orig) {
      save(best, index);
    }
  }

  private static NeuralNet evalScaled(NeuralNet cur, NeuralNet next, int index) {
    long curProfitTotal = 0;
    long nextProfitTotal = 0;
    for (int i = 0; i < TRIES; i++) {
      Marker offset = prices.rand(true);
      int[] data = prices.getData(offset);
      long curProfit = profit(cur, data, offset.offset());
      long nextProfit = profit(next, data, offset.offset());
      curProfitTotal += curProfit;
      nextProfitTotal += nextProfit;
    }
    if (nextProfitTotal < (curProfitTotal < 0 ? .97f * curProfitTotal : 1.03f * curProfitTotal)) {
      return cur;
    }
    evolutions.incrementAndGet(index);
    return next;
  }

  private static int profit(NeuralNet net, int[] data, int offset) {
    int time = -1;
    int buyOffset = -1;
    double shares = 0d;
    for (int t = 0; t < WINDOW; t++) {
      if (Decision.BUY == net.decide(data, offset + t)) {
        time = t;
        buyOffset = offset + t + PRICE_HISTORY - 1;
        shares = 100_000d / (float) data[buyOffset];
        break;
      }
    }
    if (time > -1) {
      for (int t = time + 1; t < time + 1 + WINDOW; t++) {
        if (Decision.SELL == net.decide(data, offset + t)) {
          return (int) Math.rint(
              (shares * data[offset + t + PRICE_HISTORY - 1]) - (shares * data[buyOffset]));
        }
      }
      return (int) Math.rint(
          (shares * data[offset + time + WINDOW + PRICE_HISTORY - 1]) - (shares * data[buyOffset]));
    }
    return 0;
  }

  public static String getKey() {
    return "v1^1k?UV(8R,4.a92IsnH6g";
  }
}