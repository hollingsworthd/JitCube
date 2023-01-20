package com.machinepublishers.neuraltrader;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class Main {

  private static final int TRIES = 73;
  private static final int CHANCE = 10_000;
  private static final int MARGIN = 8;
  private static final int PRICE_HISTORY = 3 * 24 * 60;
  private static final int WINDOW = 60;
  private static final int GROUPS = 4;
  private static final int NETS_PER_GROUP = 4;
  private static final int NETS = GROUPS * NETS_PER_GROUP;
  private static final Prices prices = new Prices();
  private static final Random rand = new SecureRandom();
  private static final AtomicReferenceArray<NeuralNet> nets = new AtomicReferenceArray<>(NETS);
  private static final AtomicReferenceArray<NeuralNet> prevNets = new AtomicReferenceArray<>(NETS);
  private static final short[][] buffer = new short[NETS + 1][2 * (PRICE_HISTORY + WINDOW * 2)];

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
        getNet(n).save();
      }
      Log.info("\nSaved.");
    }));
    for (int n = 0; n < NETS; n++) {
      initThread(n).start();
    }
    new Thread(() -> {
      while (true) {
        prices.getData(buffer[buffer.length - 1], false);
        for (int n = 0; n < NETS; n++) {
          NeuralNet net = getNet(n);
          long profit = profit(net, buffer.length - 1);
          Log.info("=> N%d: %d", n, (int) Math.rint(profit / 100d));
        }
        Log.info("");
        try {
          Thread.sleep(4_000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }).start();
  }

  private static Thread initThread(int index) {
    return new Thread(() -> {
      NeuralNet net = getNet(index);
      net = net.exists() ? net : save(net.mutate(Byte.MAX_VALUE, 1_000_000), index, true);
      NeuralNet prev = null;
      boolean saveToDisk = false;
      for (long i = 0; i < Long.MAX_VALUE; i++) {
        saveToDisk = saveToDisk || i % 10_000 == 0;
        net = eval(net, index, true);
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
    int randGroup = sameGroup ? index / NETS_PER_GROUP : rand.nextInt(GROUPS);
    return nets.get(rand.nextInt(NETS_PER_GROUP) + (NETS_PER_GROUP * randGroup));
  }

  private static NeuralNet eval(NeuralNet orig, int index, boolean training) {
    NeuralNet[] nets = new NeuralNet[3];
    nets[0] = (nets[0] = prev(index)) == orig ? null : nets[0];
    nets[1] = orig;
    nets[2] = (nets[2] =
        rand.nextInt(100) == 0 ? orig.mergeAndMutate(randOther(index, true), 50, MARGIN, CHANCE)
            : (rand.nextInt(1_000) == 0 ? randOther(index, true).clone(index)
                : (rand.nextInt(10_000) == 0 ? randOther(index, false).clone(index)
                    : orig.mutate(MARGIN, CHANCE)))) == orig ? null : nets[2];
    int[][] profits = new int[nets.length][TRIES];
    for (int i = 0; i < TRIES; i++) {
      prices.getData(buffer[index], training);
      for (int n = 0; n < nets.length; n++) {
        if (nets[n] != null) {
          profits[n][i] = profit(nets[n], index);
        }
      }
    }
    for (int n = 0; n < profits.length; n++) {
      if (nets[n] != null) {
        Arrays.sort(profits[n]);
      }
    }
    int best = Integer.MIN_VALUE;
    int bestIndex = -1;
    for (int n = 0; n < profits.length; n++) {
      if (nets[n] != null) {
        int cur = profits[n][TRIES / 2];
        if (cur >= best) {
          best = cur;
          bestIndex = n;
        }
      }
    }
    return nets[bestIndex];
  }

  private static int profit(NeuralNet net, int index) {
    int buyIndex = -1;
    int defaultPrice = -1;
    for (int t = WINDOW * 2 - 1; t > WINDOW - 1; t--) {
      if (defaultPrice < 0) {
        defaultPrice = buffer[index][t * 2];
      }
      if (Decision.BUY == net.decide(buffer[index], t * 2)) {
        buyIndex = t;
        break;
      }
    }
    if (buyIndex > -1) {
      for (int t = buyIndex - 1; t > buyIndex - 1 - WINDOW; t--) {
        if (Decision.SELL == net.decide(buffer[index], t * 2)) {
          return buffer[index][0] - buffer[index][t * 2];
        }
      }
    }
    return -defaultPrice;
  }
}