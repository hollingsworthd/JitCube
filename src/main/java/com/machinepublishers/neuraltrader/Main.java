package com.machinepublishers.neuraltrader;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class Main {

  private static final int TRIES = 64;
  private static final int CHANCE = 100_000;
  private static final float MARGIN = .04f;
  private static final int PRICE_HISTORY = 2 * 24 * 60;
  private static final int WINDOW = 60;
  private static final int GROUPS = 4;
  private static final int GROUP_SIZE = 2;
  private static final int NETS = GROUPS * GROUP_SIZE;
  private static final AtomicReferenceArray<NeuralNet> nets = new AtomicReferenceArray<>(NETS);
  private static final AtomicReferenceArray<NeuralNet> prevNets = new AtomicReferenceArray<>(NETS);
  private static final Prices prices = new Prices();
  private static final Random rand = new SecureRandom();
  private static final int BUFFER_LEN = 2 * (PRICE_HISTORY + WINDOW * 2);

  static {
    for (int n = 0; n < NETS; n++) {
      NeuralNet net = initNet(n);
      NeuralNet prev = net.getPrev();
      nets.set(n, net);
      prevNets.set(n, prev == null ? net : prev);
    }
  }

  public static void main(String[] args) {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (int n = 0; n < NETS; n++) {
        nets.get(n).save();
        prevNets.get(n).savePrev();
      }
      Log.info("\nSaved.");
    }));
    new Thread(() -> {
      while (true) {
        for (int n = 0; n < NETS; n++) {
          NeuralNet net = getNet(n);
          if (net != null) {
            net.save();
          }
        }
        try {
          Thread.sleep(60 * 1000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }).start();
    for (int n = 0; n < NETS; n++) {
      initThread(n).start();
    }
    new Thread(() -> {
      int[] profitHistory = new int[30];
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
        Log.info("===== $%.2f : -$%.2f ", maxProfit, Math.abs(maxLoss));
        Log.info("========================================");
        int totalProfit = 0;
        int totalScale = 0;
        for (int n = 0; n < NETS; n++) {
          NeuralNet net = getNet(n);
          int profit = profit(net, data, offset, false);
          totalProfit += profit;
          totalScale += profit - center;
          Log.info("=> N%02d: %.2f", n, profit / 100d);
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
          Thread.sleep(2_000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }).start();
  }

  private static Thread initThread(int index) {
    Thread thread = new Thread(() -> {
      NeuralNet net = getNet(index);
      net = net.exists() ? net : save(net.mutate(0, 0), index, true);
      NeuralNet prev = null;
      for (long x = 0; x < Long.MAX_VALUE; x++) {
        net = eval(net, index, false);
        if (net != prev) {
          save(net, index, false);
          prev = net;
        }
        net = eval(net, index, true);
        if (net != prev) {
          save(net, index, false);
          prev = net;
        }
      }
    });
    thread.setName("n" + index);
    return thread;
  }

  private static NeuralNet getNet(int index) {
    return nets.get(index);
  }

  private static NeuralNet initNet(int index) {
    return NeuralNet.create(index, 5, 33, PRICE_HISTORY * 2);
  }

  private static NeuralNet save(NeuralNet next, int index, boolean toDisk) {
    NeuralNet prev = prevNets.getAndSet(index, nets.getAndSet(index, next));
    if (toDisk) {
      next.save();
      prev.savePrev();
    }
    return next;
  }

  private static NeuralNet prev(int index) {
    return prevNets.get(index);
  }

  private static NeuralNet randOther(int index, boolean sameGroup) {
    int myGroup = index / GROUP_SIZE;
    int randGroup = sameGroup ? myGroup : rand.nextInt(GROUPS - 1);
    randGroup += sameGroup || randGroup < myGroup ? 0 : 1;
    int randItem = rand.nextInt(sameGroup ? GROUP_SIZE - 1 : GROUP_SIZE);
    randItem += !sameGroup || randItem < index % GROUP_SIZE ? 0 : 1;
    return nets.get(randGroup * GROUP_SIZE + randItem);
  }

  private static int randTime(int[] data) {
    return rand.nextInt((data.length - BUFFER_LEN) / 2);
  }

  private static NeuralNet eval(NeuralNet orig, int index, boolean compete) {
    int factor = index / GROUP_SIZE + 1;
    NeuralNet[] nets = new NeuralNet[5];
    nets[0] = (nets[0] = prev(index)) == orig ? null : nets[0];
    nets[1] = nets[0] == null ? null : nets[0].mutate(factor * MARGIN, factor * CHANCE);
    nets[2] = orig;
    nets[3] = orig.mergeAndMutate(randOther(index, true), 50, factor * MARGIN, factor * CHANCE);
    int tries = TRIES;
    if (rand.nextInt(5) == 0) {
      nets[4] = orig.mergeAndMutate(randOther(index, true), 50, factor * MARGIN, factor * CHANCE);
    } else if (compete && rand.nextInt(500) == 0) {
      tries *= 50;
      nets[4] = randOther(index, false).clone(index);
    } else if (compete && rand.nextInt(50) == 0) {
      tries *= 10;
      nets[4] = randOther(index, true).clone(index);
    } else {
      nets[4] = orig.mutate(factor * MARGIN, factor * CHANCE);
    }
    return compete ? evalScaled(nets, orig, tries) : evalNormalized(nets, tries);
  }

  private static NeuralNet evalNormalized(NeuralNet[] nets, int tries) {
    int[] noramlizedProfits = new int[nets.length];
    for (int i = 0; i < tries; i++) {
      int[] data = prices.getData(true);
      int offset = randTime(data);
      for (int n = 0; n < nets.length; n++) {
        if (nets[n] != null) {
          noramlizedProfits[n] += profit(nets[n], data, offset, true);
        }
      }
    }
    int best = Integer.MIN_VALUE;
    int bestIndex = -1;
    for (int n = 0; n < noramlizedProfits.length; n++) {
      if (nets[n] != null) {
        int cur = noramlizedProfits[n];
        if (cur >= best) {
          best = cur;
          bestIndex = n;
        }
      }
    }
    return nets[bestIndex];
  }

  private static NeuralNet evalScaled(NeuralNet[] nets, NeuralNet defaultBest, int tries) {
    int[] profits = new int[nets.length];
    int[] firstPlaceFinishes = new int[profits.length];
    for (int i = 0; i < tries; i++) {
      int[] data = prices.getData(true);
      int offset = randTime(data);
      int best = Integer.MIN_VALUE;
      int bestIndex = -1;
      for (int n = 0; n < nets.length; n++) {
        if (nets[n] != null) {
          int profit = profit(nets[n], data, offset, false);
          profits[n] += profit;
          if (profit >= best) {
            best = profit;
            bestIndex = n;
          }
        }
      }
      ++firstPlaceFinishes[bestIndex];
    }
    int bestProfit = Integer.MIN_VALUE;
    int bestProfitIndex = -1;
    int bestPlace = Integer.MIN_VALUE;
    int bestPlaceIndex = -1;
    for (int i = 0; i < profits.length; i++) {
      if (nets[i] != null) {
        if (profits[i] >= bestProfit) {
          bestProfit = profits[i];
          bestProfitIndex = i;
        }
        if (firstPlaceFinishes[i] >= bestPlace) {
          bestPlace = firstPlaceFinishes[i];
          bestPlaceIndex = i;
        }
      }
    }
    if (bestProfitIndex == bestPlaceIndex) {
      return nets[bestProfitIndex];
    }
    return defaultBest;
  }

  private static int profit(NeuralNet net, int[] data, int offset, boolean normalize) {
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
          int profit = data[cur] - data[buyOffset];
          return normalize ? (profit > 0 ? 1 : -1) : data[cur] - data[buyOffset];
        }
      }
      return normalize ? -3 : data[(offset + buyTime - WINDOW - 1) * 2] - data[buyOffset];
    }
    return normalize ? -2 : 0;
  }
}