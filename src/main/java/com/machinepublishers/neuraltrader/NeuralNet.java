package com.machinepublishers.neuraltrader;

import java.io.File;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

public class NeuralNet implements Serializable {

  private static final Random rand = new SecureRandom();
  private static final File DATA = new File("./data");

  static {
    DATA.mkdirs();
  }

  private final double[] buffer1;
  private final double[] buffer2;
  private final long generation;
  private final int id;
  private final File file;
  private final float[][][] weights;

  private NeuralNet(long generation, int id, File saveTo, float[][][] weights) {
    this.generation = generation;
    this.id = id;
    this.file = saveTo;
    this.weights = weights;
    this.buffer1 = new double[this.weights[0].length];
    this.buffer2 = new double[this.weights[0].length];
  }

  private NeuralNet(long generation, int id, File saveTo, int layers, int len, int inputLen) {
    this.generation = generation;
    this.id = id;
    this.file = saveTo;
    layers += 1;
    this.weights = mutate(initWeights(layers, len, inputLen), true, 1_000_000);
    this.buffer1 = new double[this.weights[0].length];
    this.buffer2 = new double[this.weights[0].length];
  }

  private NeuralNet(int id, File readFrom, File saveTo) {
    this.id = id;
    this.file = saveTo;
    String[] lines = LockedFile.read(readFrom).split("\n");

    String[] dimensions = lines[0].split("/");
    int layers = Integer.parseInt(dimensions[0]);
    int len = Integer.parseInt(dimensions[1]);
    int inputLen = Integer.parseInt(dimensions[2]);
    this.generation = Long.parseLong(dimensions[3]);

    float[][][] weights = initWeights(layers, len, inputLen);
    String[] weightTokens = lines[1].split(",");

    for (int i = 0, token = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        for (int k = 0; k < weights[i][j].length; k++) {
          weights[i][j][k] = Float.parseFloat(weightTokens[token++]);
        }
      }
    }
    this.weights = weights;
    this.buffer1 = new double[this.weights[0].length];
    this.buffer2 = new double[this.weights[0].length];
  }

  public static NeuralNet create(int idFrom, int idTo) {
    return new NeuralNet(idTo, new File(DATA, "n" + idFrom), new File(DATA, "n" + idTo));
  }

  public static NeuralNet create(long generation, int id, int layers, int len, int inputLen) {
    File file = new File(DATA, "n" + id);
    if (LockedFile.exists(file)) {
      return new NeuralNet(id, file, file);
    }
    NeuralNet net = new NeuralNet(generation, id, file, layers, len, inputLen);
    net.save();
    return net;
  }

  private static float[][][] initWeights(int layers, int len, int inputLen) {
    float[][][] weights = new float[layers][][];
    weights[layers - 1] = new float[2][len];

    for (int i = 0; i < layers - 1; i++) {
      weights[i] = new float[len][];
      for (int j = 0; j < len; j++) {
        weights[i][j] = new float[i == 0 ? inputLen / 2 : (i == 1 ? len / 2 : len)];
      }
    }
    return weights;
  }

  private static float[][][] copy(float[][][] array) {
    float[][][] copy = new float[array.length][][];
    for (int i = 0; i < array.length; i++) {
      copy[i] = new float[array[i].length][];
      for (int j = 0; j < array[i].length; j++) {
        copy[i][j] = Arrays.copyOf(array[i][j], array[i][j].length);
      }
    }
    return copy;
  }

  private static float[][][] merge(int mergesPercent, float[][][] array1, float[][][] array2) {
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

  private static float[][][] mutate(float[][][] weights, boolean init, int mutationsPerMillion) {
    if (mutationsPerMillion > 0) {
      for (int i = 0; i < weights.length; i++) {
        for (int j = 0; j < weights[i].length; j++) {
          for (int k = 0; k < weights[i][j].length; k++) {
            if (rand.nextInt(1_000_000) < mutationsPerMillion) {
              double newVal;
              if (init) {
                newVal = (rand.nextBoolean() ? 1d : -1d) * rand.nextDouble();
              } else {
                newVal = rand.nextDouble(.00001d, .5d);
                newVal = rand.nextDouble(newVal * newVal * newVal);
                double weight = weights[i][j][k];
                double sign = (weight == 1d ? -1d
                    : (weight == -1d ? 1d : (rand.nextBoolean() ? 1d : -1d)));
                newVal = sign * newVal + weight;
              }
              newVal = newVal > 1d ? 1d : newVal;
              newVal = newVal < -1d ? -1d : newVal;
              newVal = Math.abs(newVal) < .000001d ? 0d : newVal;
              weights[i][j][k] = (float) newVal;
            }
          }
        }
      }
    }
    return weights;
  }

  public NeuralNet clone(int newId, boolean newGeneration) {
    return new NeuralNet(newGeneration ? rand.nextLong() : generation, newId,
        new File(DATA, "n" + newId), weights);
  }

  public NeuralNet mergeAndMutate(NeuralNet other, int mergesPercent, int mutationsPerMillion) {
    if (generation == other.generation) {
      return new NeuralNet(generation, id, file,
          mutate(merge(mergesPercent, copy(weights), other.weights), false, mutationsPerMillion));
    }
    return other.clone(id, false);
  }

  public Decision decide(int[] input, int offset) {
    double[] buffer = processDecision(input, offset);
    if (buffer[0] > 0d && buffer[1] <= 0d) {
      return Decision.BUY;
    }
    if (buffer[0] <= 0d && buffer[1] > 0d) {
      return Decision.SELL;
    }
    return Decision.HOLD;
  }

  private double[] processDecision(int[] input, int offset) {
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
    double[] prev = buffer1;
    double[] next = buffer2;
    for (int i = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        double sum = 0d;
        if (i == 0) {
          if (j % 2 == 0) {
            for (int k = 0; k < weights[i][j].length; k++) {
              sum += (double) weights[i][j][k] * (double) (input[k * 2 + offset] - minPrice)
                  / scalePrice;
            }

          } else {
            for (int k = 0; k < weights[i][j].length; k++) {
              sum += (double) weights[i][j][k] * (double) (input[k * 2 + 1 + offset] - minVolume)
                  / scaleVolume;
            }
          }
        } else if (i == 1) {
          if (j % 2 == 0) {
            for (int k = 0; k < weights[i][j].length; k++) {
              sum += (double) weights[i][j][k] * prev[k * 2];
            }
          } else {
            for (int k = 0; k < weights[i][j].length; k++) {
              sum += (double) weights[i][j][k] * prev[k * 2 + 1];
            }
          }
        } else {
          for (int k = 0; k < weights[i][j].length; k++) {
            sum += (double) weights[i][j][k] * prev[k];
          }
        }

        next[j] = sum > 0d ? sum : 0d;
      }
      double[] tmp = prev;
      prev = next;
      next = tmp;
    }
    return prev;
  }

  @Override
  public boolean equals(Object obj) {
    return obj == this || (obj instanceof NeuralNet other && Arrays.deepEquals(weights,
        other.weights));
  }

  @Override
  public int hashCode() {
    return Arrays.deepHashCode(new Object[]{weights});
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append(weights.length).append("/");
    builder.append(weights[0].length).append("/");
    builder.append(weights[0][0].length * 2).append("/");
    builder.append(generation).append("/");
    builder.append("\n");

    for (int i = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        for (int k = 0; k < weights[i][j].length; k++) {
          builder.append(weights[i][j][k]).append(",");
        }
      }
    }
    return builder.toString();
  }

  public void save() {
    LockedFile.write(file, toString());
  }
}