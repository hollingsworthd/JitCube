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
  private final byte[][][] weights;
  private final byte[][] thresholds;

  private NeuralNet(File saveTo, byte[][][] weights, byte[][] thresholds) {
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

    byte[][][] weights = initWeights(layers, len, inputLen);
    byte[][] thresholds = initThresholds(layers, len);
    String[] weightTokens = lines[1].split(",");
    String[] thresholdTokens = lines[2].split(",");

    for (int i = 0, token = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        for (int k = 0; k < weights[i][j].length; k++) {
          weights[i][j][k] = Byte.parseByte(weightTokens[token++]);
        }
      }
    }
    for (int i = 0, token = 0; i < thresholds.length; i++) {
      for (int j = 0; j < thresholds[i].length; j++) {
        thresholds[i][j] = Byte.parseByte(thresholdTokens[token++]);
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

  public NeuralNet clone(int newId) {
    return new NeuralNet(new File(DATA, "n" + newId), weights, thresholds);
  }

  private static File initPrev(File file) {
    return new File(DATA, "." + file.getName() + ".prev");
  }

  private static byte[][][] initWeights(int layers, int len, int inputLen) {
    byte[][][] weights = new byte[layers][][];
    weights[layers - 1] = new byte[2][len];

    for (int i = 0; i < layers - 1; i++) {
      weights[i] = new byte[len][];
      for (int j = 0; j < len; j++) {
        weights[i][j] = new byte[i == 0 && j == 0 ? inputLen : len];
      }
    }
    return weights;
  }

  private static byte[][] initThresholds(int layers, int len) {
    byte[][] thresholds = new byte[layers][];
    thresholds[layers - 1] = new byte[2];
    for (int i = 0; i < layers - 1; i++) {
      thresholds[i] = new byte[len];
    }
    return thresholds;
  }

  private static byte[][][] copy(byte[][][] array) {
    byte[][][] copy = new byte[array.length][][];
    for (int i = 0; i < array.length; i++) {
      copy[i] = new byte[array[i].length][];
      for (int j = 0; j < array[i].length; j++) {
        copy[i][j] = Arrays.copyOf(array[i][j], array[i][j].length);
      }
    }
    return copy;
  }

  private static byte[][] copy(byte[][] array) {
    byte[][] copy = new byte[array.length][];
    for (int i = 0; i < array.length; i++) {
      copy[i] = Arrays.copyOf(array[i], array[i].length);
    }
    return copy;
  }

  private static byte[][][] merge(int mergesPercent, byte[][][] array1, byte[][][] array2) {
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

  private static byte[][] merge(int mergesPercent, byte[][] array1, byte[][] array2) {
    for (int i = 0; i < array1.length; i++) {
      for (int j = 0; j < array1[i].length; j++) {
        if (rand.nextInt(100) < mergesPercent) {
          array1[i][j] = array2[i][j];
        }
      }
    }
    return array1;
  }

  private static byte[][][] mutate(byte[][][] weights, int margin, int mutationsPerMillion) {
    assert validateMutation(margin, mutationsPerMillion);
    for (int i = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        for (int k = 0; k < weights[i][j].length; k++) {
          if (rand.nextInt(1_000_000) < mutationsPerMillion) {
            int sign = rand.nextBoolean() ? 1 : -1;
            int newVal = sign * rand.nextInt(margin + 1) + (int) weights[i][j][k];
            newVal = newVal > Byte.MAX_VALUE ? Byte.MAX_VALUE : newVal;
            newVal = newVal < Byte.MIN_VALUE ? Byte.MIN_VALUE : newVal;
            weights[i][j][k] = (byte) newVal;
          }
        }
      }
    }
    return weights;
  }

  private static byte[][] mutate(byte[][] thresholds, int margin, int mutationsPerMillion) {
    assert validateMutation(margin, mutationsPerMillion);
    for (int i = 0; i < thresholds.length; i++) {
      for (int j = 0; j < thresholds[i].length; j++) {
        if (rand.nextInt(1_000_000) < mutationsPerMillion) {
          int sign = rand.nextBoolean() ? 1 : -1;
          int newVal = sign * rand.nextInt(margin + 1) + (int) thresholds[i][j];
          newVal = newVal > Byte.MAX_VALUE ? Byte.MAX_VALUE : newVal;
          newVal = newVal < Byte.MIN_VALUE ? Byte.MIN_VALUE : newVal;
          thresholds[i][j] = (byte) newVal;
        }
      }
    }
    return thresholds;
  }

  private static boolean validateMutation(int margin, int mutationsPerMillion) {
    return margin <= Byte.MAX_VALUE && margin >= 0 && mutationsPerMillion <= 1_000_000
        && mutationsPerMillion >= 0;
  }

  public NeuralNet mutate(int margin, int mutationsPerMillion) {
    return new NeuralNet(this.file, mutate(copy(weights), margin, mutationsPerMillion),
        mutate(copy(thresholds), margin, mutationsPerMillion));
  }

  public NeuralNet mergeAndMutate(NeuralNet other, int mergesPercent, int margin,
      int mutationsPerMillion) {
    return new NeuralNet(this.file,
        mutate(merge(mergesPercent, copy(weights), other.weights), margin, mutationsPerMillion),
        mutate(merge(mergesPercent, copy(thresholds), other.thresholds), margin,
            mutationsPerMillion));
  }

  public Decision decide(short[] input) {
    boolean[] result = processDecision(input);
    if (result[0] && !result[1]) {
      return Decision.BUY;
    }
    if (!result[0] && result[1]) {
      return Decision.SELL;
    }
    return Decision.HOLD;
  }

  private boolean[] processDecision(short[] input) {
    assert input.length == weights[0][0].length;
    boolean[] prev = new boolean[weights[0].length];
    boolean[] cur = new boolean[weights[0].length];
    for (int i = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        long sum = 0;
        for (int k = 0; k < weights[i][j].length; k++) {
          if (j != 0 || i != 0) {
            if (prev[k]) {
              sum += weights[i][j][k];
            }
          } else {
            sum += (int) weights[i][j][k] * (int) input[k];
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

  public NeuralNet getPrev() {
    if (prev.exists()) {
      return new NeuralNet(prev, file);
    }
    return null;
  }
}
