package com.machinepublishers.neuraltrader;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class Main {

  private static final int TRIES = 53;
  private static final int CHANCE = 10_000;
  private static final float MARGIN = .01f;
  private static final int PRICE_HISTORY = 12 * 60;
  private static final int WINDOW = 30;
  private static final int GROUPS = 4;
  private static final int GROUP_SIZE = 4;
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
        for (int i = 0; i < WINDOW; i++) {
          int sell = data[2 * (i + offset)];
          for (int j = i + 1; j < i + 1 + WINDOW; j++) {
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
        allTimeScale = Math.min(100, Math.max(0, allTimeScale / scaleHistory.length * 100));
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
    return new Thread(() -> {
      NeuralNet net = getNet(index);
      net = net.exists() ? net : save(net.mutate(0, 0), index, true);
      NeuralNet prev = null;
      boolean saveToDisk = false;
      for (long i = 0; i < Long.MAX_VALUE; i++) {
        saveToDisk = saveToDisk || i % 10_000 == 0;
        net = eval(net, index, true);
        net = eval(net, index, false);
        if (net != prev) {
          save(net, index, saveToDisk);
          prev = net;
          saveToDisk = false;
        }
      }
    });
  }

  private static NeuralNet getNet(int index) {
    return nets.get(index);
  }

  private static NeuralNet initNet(int index) {
    return NeuralNet.create(index, 7, 33, PRICE_HISTORY * 2);
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
    int randGroup = sameGroup ? index / GROUP_SIZE : rand.nextInt(GROUPS);
    int randIndex = rand.nextInt(GROUP_SIZE) + (GROUP_SIZE * randGroup);
    boolean avoidSelf = sameGroup && randIndex >= index;
    boolean overflow = (randIndex + 1) % GROUP_SIZE == 0;
    randIndex = avoidSelf && overflow ? randIndex + 1 - GROUP_SIZE
        : (avoidSelf && !overflow ? randIndex + 1 : randIndex);
    return nets.get(randIndex);
  }

  private static int randTime(int[] data) {
    return rand.nextInt((data.length - BUFFER_LEN) / 2);
  }

  private static NeuralNet eval(NeuralNet orig, int index, boolean normalize) {
    int factor = index + 1;
    NeuralNet[] nets = new NeuralNet[3];
    nets[0] = (nets[0] = prev(index)) == orig ? null : nets[0];
    nets[1] = orig;
    nets[2] =
        rand.nextInt(2) == 0 ? orig.mergeAndMutate(randOther(index, true), 50, factor * MARGIN,
            factor * CHANCE) : (rand.nextInt(1_000) == 0 ? randOther(index, true).clone(index)
            : (!normalize && rand.nextInt(10_000) == 0 ? randOther(index, false).clone(index)
                : orig.mutate(factor * MARGIN, factor * CHANCE)));
    return normalize ? evalNormalized(nets) : evalScaled(nets);
  }

  private static NeuralNet evalNormalized(NeuralNet[] nets) {
    int[] noramlizedProfits = new int[nets.length];
    for (int i = 0; i < TRIES; i++) {
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

  private static NeuralNet evalScaled(NeuralNet[] nets) {
    double[][] scaledProfits = new double[nets.length][TRIES];
    for (int i = 0; i < TRIES; i++) {
      int[] data = prices.getData(true);
      int offset = randTime(data);
      int maxProfit = Integer.MIN_VALUE;
      int maxLoss = 0;
      for (int j = 0; j < WINDOW; j++) {
        int sell = data[2 * (j + offset)];
        for (int k = j + 1; k < j + 1 + WINDOW; k++) {
          int buy = data[2 * (k + offset)];
          maxProfit = Math.max(maxProfit, sell - buy);
          maxLoss = Math.min(maxLoss, sell - buy);
        }
      }
      double scale = maxProfit - maxLoss;
      for (int n = 0; n < nets.length; n++) {
        if (nets[n] != null) {
          scaledProfits[n][i] = Math.min(1d,
              Math.max(0d, (double) (profit(nets[n], data, offset, false) - maxLoss) / scale));
        }
      }
    }
    for (int n = 0; n < nets.length; n++) {
      Arrays.sort(scaledProfits[n]);
    }
    double best = -Double.MAX_VALUE;
    int bestIndex = -1;
    for (int n = 0; n < nets.length; n++) {
      if (nets[n] != null) {
        double cur = scaledProfits[n][TRIES / 2];
        if (cur >= best) {
          best = cur;
          bestIndex = n;
        }
      }
    }
    return nets[bestIndex];
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