package com.machinepublishers.neuraltrader;

import java.io.File;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

public class NeuralNet {

  private static final Random rand = new SecureRandom();
  private static final File DATA = new File("./data");

  static {
    DATA.mkdirs();
  }

  private final boolean[] buffer;
  private final int id;
  private final File file;
  private final File prev;
  private final double[][][] weights;
  private final double[][] thresholds;

  private NeuralNet(int id, File saveTo, double[][][] weights, double[][] thresholds) {
    this.id = id;
    this.file = saveTo;
    this.prev = initPrev(saveTo);
    this.weights = weights;
    this.thresholds = thresholds;
    this.buffer = new boolean[this.weights[0].length];
  }

  private NeuralNet(int id, File saveTo, int layers, int len, int inputLen) {
    this.id = id;
    this.file = saveTo;
    this.prev = initPrev(saveTo);
    layers += 1;
    this.weights = initWeights(layers, len, inputLen);
    this.thresholds = initThresholds(layers, len);
    this.buffer = new boolean[this.weights[0].length];
  }

  private NeuralNet(int id, File readFrom, File saveTo) {
    this.id = id;
    this.file = saveTo;
    this.prev = initPrev(saveTo);
    String[] lines = LockedFile.read(readFrom).split("\n");

    String[] dimensions = lines[0].split("/");
    int layers = Integer.parseInt(dimensions[0]);
    int len = Integer.parseInt(dimensions[1]);
    int inputLen = Integer.parseInt(dimensions[2]);

    double[][][] weights = initWeights(layers, len, inputLen);
    double[][] thresholds = initThresholds(layers, len);
    String[] weightTokens = lines[1].split(",");
    String[] thresholdTokens = lines[2].split(",");

    for (int i = 0, token = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        for (int k = 0; k < weights[i][j].length; k++) {
          weights[i][j][k] = Double.parseDouble(weightTokens[token++]);
        }
      }
    }
    for (int i = 0, token = 0; i < thresholds.length; i++) {
      for (int j = 0; j < thresholds[i].length; j++) {
        thresholds[i][j] = Double.parseDouble(thresholdTokens[token++]);
      }
    }
    this.weights = weights;
    this.thresholds = thresholds;
    this.buffer = new boolean[this.weights[0].length];
  }

  public static NeuralNet create(int idFrom, int idTo) {
    return new NeuralNet(idTo, new File(DATA, "n" + idFrom), new File(DATA, "n" + idTo));
  }

  public static NeuralNet create(int id, int layers, int len, int inputLen) {
    File file = new File(DATA, "n" + id);
    if (LockedFile.exists(file)) {
      return new NeuralNet(id, file, file);
    }
    NeuralNet net = new NeuralNet(id, file, layers, len, inputLen);
    net.save();
    net.savePrev();
    return net;
  }

  private static File initPrev(File file) {
    return new File(DATA, file.getName() + ".prev");
  }

  private static double[][][] initWeights(int layers, int len, int inputLen) {
    double[][][] weights = new double[layers][][];
    weights[layers - 1] = new double[2][len];

    for (int i = 0; i < layers - 1; i++) {
      weights[i] = new double[len][];
      for (int j = 0; j < len; j++) {
        weights[i][j] = new double[i == 0 && j == 0 ? inputLen : len];
      }
    }
    return weights;
  }

  private static double[][] initThresholds(int layers, int len) {
    double[][] thresholds = new double[layers][];
    thresholds[layers - 1] = new double[2];
    for (int i = 0; i < layers - 1; i++) {
      thresholds[i] = new double[len];
    }
    return thresholds;
  }

  private static double[][][] copy(double[][][] array) {
    double[][][] copy = new double[array.length][][];
    for (int i = 0; i < array.length; i++) {
      copy[i] = new double[array[i].length][];
      for (int j = 0; j < array[i].length; j++) {
        copy[i][j] = Arrays.copyOf(array[i][j], array[i][j].length);
      }
    }
    return copy;
  }

  private static double[][] copy(double[][] array) {
    double[][] copy = new double[array.length][];
    for (int i = 0; i < array.length; i++) {
      copy[i] = Arrays.copyOf(array[i], array[i].length);
    }
    return copy;
  }

  private static double[][][] merge(int mergesPercent, double[][][] array1, double[][][] array2) {
    for (int i = 0; i < array1.length; i++) {
      for (int j = 0; j < array1[i].length; j++) {
        for (int k = 0; k < array1[i][j].length; k++) {
          if (rand.nextInt(100) < mergesPercent) {
            array1[i][j][k] = array2[i][j][k];
          }
        }
      }
    }
    return array1;
  }

  private static double[][] merge(int mergesPercent, double[][] array1, double[][] array2) {
    for (int i = 0; i < array1.length; i++) {
      for (int j = 0; j < array1[i].length; j++) {
        if (rand.nextInt(100) < mergesPercent) {
          array1[i][j] = array2[i][j];
        }
      }
    }
    return array1;
  }

  private static double[][][] mutate(double[][][] weights, double margin, int mutationsPerMillion) {
    if (mutationsPerMillion > 0) {
      for (int i = 0; i < weights.length; i++) {
        for (int j = 0; j < weights[i].length; j++) {
          for (int k = 0; k < weights[i][j].length; k++) {
            if (rand.nextInt(1_000_000) < mutationsPerMillion) {
              double sign = rand.nextBoolean() ? 1d : -1d;
              double newVal = sign * rand.nextDouble(0, margin) + weights[i][j][k];
              newVal = newVal > 1d ? 1d : newVal;
              newVal = newVal < -1d ? -1d : newVal;
              weights[i][j][k] = newVal;
            }
          }
        }
      }
    }
    return weights;
  }

  private static double[][] mutate(double[][] thresholds, double margin, int mutationsPerMillion) {
    if (mutationsPerMillion > 0) {
      for (int i = 0; i < thresholds.length; i++) {
        for (int j = 0; j < thresholds[i].length; j++) {
          if (rand.nextInt(1_000_000) < mutationsPerMillion) {
            double sign = rand.nextBoolean() ? 1d : -1d;
            double newVal = sign * rand.nextDouble(0, margin) + thresholds[i][j];
            newVal = newVal > 1d ? 1d : newVal;
            newVal = newVal < -1d ? -1d : newVal;
            thresholds[i][j] = newVal;
          }
        }
      }
    }
    return thresholds;
  }

  public NeuralNet clone(int newId) {
    return new NeuralNet(newId, new File(DATA, "n" + newId), weights, thresholds);
  }

  public NeuralNet mutate(double margin, int mutationsPerMillion) {
    return new NeuralNet(id, file, mutate(copy(weights), margin, mutationsPerMillion),
        mutate(copy(thresholds), margin, mutationsPerMillion));
  }

  public NeuralNet mergeAndMutate(NeuralNet other, int mergesPercent, double margin,
      int mutationsPerMillion) {
    return new NeuralNet(id, file,
        mutate(merge(mergesPercent, copy(weights), other.weights), margin, mutationsPerMillion),
        mutate(merge(mergesPercent, copy(thresholds), other.thresholds), margin,
            mutationsPerMillion));
  }

  public Decision decide(int[] input, int offset) {
    processDecision(input, offset);
    if (buffer[0] && !buffer[1]) {
      return Decision.BUY;
    }
    if (!buffer[0] && buffer[1]) {
      return Decision.SELL;
    }
    return Decision.HOLD;
  }

  private void processDecision(int[] input, int offset) {
    int maxPrice = Integer.MIN_VALUE;
    int minPrice = Integer.MAX_VALUE;
    int maxVolume = Integer.MIN_VALUE;
    int minVolume = Integer.MAX_VALUE;

    for (int i = offset, max = i + weights[0][0].length; i < max; i += 2) {
      maxPrice = Math.max(maxPrice, input[i]);
      minPrice = Math.min(minPrice, input[i]);
      maxVolume = Math.max(maxVolume, input[i + 1]);
      minVolume = Math.min(minVolume, input[i + 1]);
    }
    double scalePrice = maxPrice - minPrice;
    double scaleVolume = maxVolume - minVolume;
    for (int i = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        double sum = 0d;
        for (int k = 0; k < weights[i][j].length; k++) {
          if (j != 0 || i != 0) {
            if (buffer[k]) {
              sum += weights[i][j][k];
            }
          } else if (k % 2 == 0) {
            sum += weights[i][j][k] * (input[k + offset] - minPrice) / scalePrice;
          } else {
            sum += weights[i][j][k] * (input[k + offset] - minVolume) / scaleVolume;
          }
        }
        buffer[j] = sum > thresholds[i][j];
      }
    }
  }

  @Override
  public boolean equals(Object obj) {
    return obj == this || (obj instanceof NeuralNet other && Arrays.deepEquals(weights,
        other.weights) && Arrays.deepEquals(thresholds, other.thresholds));
  }

  @Override
  public int hashCode() {
    return Arrays.deepHashCode(new Object[]{weights, thresholds});
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append(weights.length).append("/");
    builder.append(weights[0].length).append("/");
    builder.append(weights[0][0].length).append("/");
    builder.append("\n");

    for (int i = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        for (int k = 0; k < weights[i][j].length; k++) {
          builder.append(weights[i][j][k]).append(",");
        }
      }
    }
    builder.append("\n");
    for (int i = 0; i < thresholds.length; i++) {
      for (int j = 0; j < thresholds[i].length; j++) {
        builder.append(thresholds[i][j]).append(",");
      }
    }
    return builder.toString();
  }

  public void save() {
    LockedFile.write(file, toString());
  }

  public void savePrev() {
    LockedFile.write(prev, toString());
  }

  public NeuralNet getPrev() {
    return new NeuralNet(id, prev, file);
  }
}