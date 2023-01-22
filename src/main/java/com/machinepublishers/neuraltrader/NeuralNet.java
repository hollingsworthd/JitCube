package com.machinepublishers.neuraltrader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

public class NeuralNet {

  private static final Random rand = new SecureRandom();
  private static final File DATA = new File("./data");

  static {
    DATA.mkdirs();
  }

  private final File file;
  private final File prev;
  private final float[][][] weights;
  private final float[][] thresholds;

  private NeuralNet(File saveTo, float[][][] weights, float[][] thresholds) {
    this.file = saveTo;
    this.prev = initPrev(saveTo);
    this.weights = weights;
    this.thresholds = thresholds;
  }

  private NeuralNet(File saveTo, int layers, int len, int inputLen) {
    this.file = saveTo;
    this.prev = initPrev(saveTo);
    layers += 1;
    weights = initWeights(layers, len, inputLen);
    thresholds = initThresholds(layers, len);
  }

  private NeuralNet(File readFrom, File saveTo) {
    this.file = saveTo;
    this.prev = initPrev(saveTo);
    String[] lines;
    synchronized (NeuralNet.class) {
      try {
        lines = Files.readAllLines(readFrom.toPath()).toArray(new String[0]);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    String[] dimensions = lines[0].split("/");
    int layers = Integer.parseInt(dimensions[0]);
    int len = Integer.parseInt(dimensions[1]);
    int inputLen = Integer.parseInt(dimensions[2]);

    float[][][] weights = initWeights(layers, len, inputLen);
    float[][] thresholds = initThresholds(layers, len);
    String[] weightTokens = lines[1].split(",");
    String[] thresholdTokens = lines[2].split(",");

    for (int i = 0, token = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        for (int k = 0; k < weights[i][j].length; k++) {
          weights[i][j][k] = Float.parseFloat(weightTokens[token++]);
        }
      }
    }
    for (int i = 0, token = 0; i < thresholds.length; i++) {
      for (int j = 0; j < thresholds[i].length; j++) {
        thresholds[i][j] = Float.parseFloat(thresholdTokens[token++]);
      }
    }
    this.weights = weights;
    this.thresholds = thresholds;
  }

  public static NeuralNet create(int id, int layers, int len, int inputLen) {
    File file = new File(DATA, "n" + id);
    if (file.exists()) {
      return new NeuralNet(file, file);
    }
    return new NeuralNet(file, layers, len, inputLen);
  }

  private static File initPrev(File file) {
    return new File(DATA, "." + file.getName() + ".prev");
  }

  private static float[][][] initWeights(int layers, int len, int inputLen) {
    float[][][] weights = new float[layers][][];
    weights[layers - 1] = new float[2][len];

    for (int i = 0; i < layers - 1; i++) {
      weights[i] = new float[len][];
      for (int j = 0; j < len; j++) {
        weights[i][j] = new float[i == 0 && j == 0 ? inputLen : len];
      }
    }
    return weights;
  }

  private static float[][] initThresholds(int layers, int len) {
    float[][] thresholds = new float[layers][];
    thresholds[layers - 1] = new float[2];
    for (int i = 0; i < layers - 1; i++) {
      thresholds[i] = new float[len];
    }
    return thresholds;
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

  private static float[][] copy(float[][] array) {
    float[][] copy = new float[array.length][];
    for (int i = 0; i < array.length; i++) {
      copy[i] = Arrays.copyOf(array[i], array[i].length);
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

  private static float[][] merge(int mergesPercent, float[][] array1, float[][] array2) {
    for (int i = 0; i < array1.length; i++) {
      for (int j = 0; j < array1[i].length; j++) {
        if (rand.nextInt(100) < mergesPercent) {
          array1[i][j] = array2[i][j];
        }
      }
    }
    return array1;
  }

  private static float[][][] mutate(float[][][] weights, float margin, int mutationsPerMillion) {
    assert validateMutation(margin, mutationsPerMillion);
    for (int i = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        for (int k = 0; k < weights[i][j].length; k++) {
          if (rand.nextInt(1_000_000) < mutationsPerMillion) {
            float sign = rand.nextBoolean() ? 1 : -1;
            float newVal = sign * rand.nextFloat(0, margin) + weights[i][j][k];
            newVal = newVal > 1 ? 1 : newVal;
            newVal = newVal < -1 ? -1 : newVal;
            weights[i][j][k] = newVal;
          }
        }
      }
    }
    return weights;
  }

  private static float[][] mutate(float[][] thresholds, float margin, int mutationsPerMillion) {
    assert validateMutation(margin, mutationsPerMillion);
    for (int i = 0; i < thresholds.length; i++) {
      for (int j = 0; j < thresholds[i].length; j++) {
        if (rand.nextInt(1_000_000) < mutationsPerMillion) {
          float sign = rand.nextBoolean() ? 1 : -1;
          float newVal = sign * rand.nextFloat(0, margin) + thresholds[i][j];
          newVal = newVal > 1 ? 1 : newVal;
          newVal = newVal < -1 ? -1 : newVal;
          thresholds[i][j] = newVal;
        }
      }
    }
    return thresholds;
  }

  private static boolean validateMutation(float margin, int mutationsPerMillion) {
    return margin <= 1 && margin >= 0 && mutationsPerMillion <= 1_000_000
        && mutationsPerMillion >= 0;
  }

  private static void write(NeuralNet net, File file) {
    try {
      Path tmp = new File(file.getParentFile(),
          (file.getName().startsWith(".") ? "" : ".") + file.getName() + ".tmp").toPath();
      Files.writeString(tmp, net.toString(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING);
      Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public NeuralNet clone(int newId) {
    return new NeuralNet(new File(DATA, "n" + newId), weights, thresholds);
  }

  public NeuralNet mutate(float margin, int mutationsPerMillion) {
    return new NeuralNet(this.file, mutate(copy(weights), margin, mutationsPerMillion),
        mutate(copy(thresholds), margin, mutationsPerMillion));
  }

  public NeuralNet mergeAndMutate(NeuralNet other, int mergesPercent, float margin,
      int mutationsPerMillion) {
    return new NeuralNet(this.file,
        mutate(merge(mergesPercent, copy(weights), other.weights), margin, mutationsPerMillion),
        mutate(merge(mergesPercent, copy(thresholds), other.thresholds), margin,
            mutationsPerMillion));
  }

  public Decision decide(int[] input, int offset) {
    boolean[] result = processDecision(input, offset);
    if (result[0] && !result[1]) {
      return Decision.BUY;
    }
    if (!result[0] && result[1]) {
      return Decision.SELL;
    }
    return Decision.HOLD;
  }

  private boolean[] processDecision(int[] input, int offset) {
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
    float scalePrice = 10_000f / (float) (maxPrice - minPrice);
    float scaleVolume = 10_000f / (float) (maxVolume - minVolume);
    boolean[] prev = new boolean[weights[0].length];
    boolean[] cur = new boolean[weights[0].length];
    for (int i = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        float sum = 0;
        for (int k = 0; k < weights[i][j].length; k++) {
          if (j != 0 || i != 0) {
            if (prev[k]) {
              sum += weights[i][j][k];
            }
          } else if (k % 2 == 0) {
            sum += weights[i][j][k] * ((float) (input[k + offset] - minPrice) * scalePrice);
          } else {
            sum += weights[i][j][k] * ((float) (input[k + offset] - minVolume) * scaleVolume);
          }
        }
        cur[j] = sum > thresholds[i][j];
      }
      boolean[] tmp = prev;
      prev = cur;
      cur = tmp;
    }
    return Arrays.copyOf(prev, weights[weights.length - 1].length);
  }

  public boolean exists() {
    return file.exists();
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
    write(this, file);
  }

  public void savePrev() {
    write(this, prev);
  }

  public NeuralNet getPrev() {
    if (prev.exists()) {
      return new NeuralNet(prev, file);
    }
    return null;
  }
}