package com.machinepublishers.neuraltrader;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Prices {

  private static final Random rand = new SecureRandom();

  private final int[][] training;
  private final int[][] alt;
  private final int[] oddsTraining;
  private final int[] oddsAlt;

  public Prices() {
    new File("./prices/training").mkdirs();
    new File("./prices/alt").mkdirs();
    for (File file : new File("./prices/training").listFiles(
        (dir, name) -> !name.startsWith("."))) {
      process(file);
    }
    for (File file : new File("./prices/alt").listFiles((dir, name) -> !name.startsWith("."))) {
      process(file);
    }
    List<int[]> data = Stream.of(new File("./prices/training-dist").listFiles())
        .filter(f -> !f.getName().startsWith(".")).map(Prices::load).collect(Collectors.toList());
    training = data.toArray(new int[0][]);
    data = Stream.of(new File("./prices/alt-dist").listFiles())
        .filter(f -> !f.getName().startsWith(".")).map(Prices::load).collect(Collectors.toList());
    alt = data.toArray(new int[0][]);

    oddsTraining = initOdds(training);
    oddsAlt = initOdds(alt);
  }

  private static int[] initOdds(int[][] data) {
    int[] odds = new int[1000];
    double k = 1000d / (double) Stream.of(data).map(a -> a.length).reduce(0, Integer::sum);
    int pos = 0;
    for (int i = 0; i < data.length; i++) {
      int chances = (int) Math.rint((double) data[i].length * k);
      for (int j = 0; j < chances && pos < odds.length; j++, pos++) {
        odds[pos] = i;
      }
    }
    return Arrays.copyOf(odds, pos);
  }

  private static void process(File file) {
    File dist = new File(file.getParentFile() + "-dist", file.getName());
    if (!dist.exists()) {
      List<Integer> prices = new ArrayList<>();
      List<Integer> volumes = new ArrayList<>();
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(new FileInputStream(file)))) {
        String[] cols = reader.readLine().split(",");
        reader.readLine();
        boolean ascending = "a".equalsIgnoreCase(cols[0]);
        int priceCol = Integer.parseInt(cols[1]);
        int volumeCol = Integer.parseInt(cols[2]);
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          String[] tokens = line.split(",");
          double priceTmp = Double.parseDouble(tokens[priceCol]);
          double volumeTmp = Double.parseDouble(tokens[volumeCol]);
          priceTmp = Double.isFinite(priceTmp) ? Math.rint(priceTmp * 100d) : -1;
          volumeTmp = Double.isFinite(volumeTmp) ? Math.rint(volumeTmp) : 0L;
          if (priceTmp > Integer.MAX_VALUE || priceTmp < Integer.MIN_VALUE) {
            throw new IllegalStateException(
                String.format("Price overflow '%s' (%f) in %s", tokens[priceCol], priceTmp,
                    file.getAbsolutePath()));
          }
          if (volumeTmp > Integer.MAX_VALUE || volumeTmp < Integer.MIN_VALUE) {
            throw new IllegalStateException(
                String.format("Volume overflow '%s' (%f) in %s", tokens[volumeCol], volumeTmp,
                    file.getAbsolutePath()));
          }
          prices.add((int) priceTmp);
          volumes.add((int) volumeTmp);
        }
        if (ascending) {
          Collections.reverse(prices);
          Collections.reverse(volumes);
        }
        int prevPrice = 0;
        for (int i = 0; i < prices.size(); i++) {
          int price = prices.get(i);
          if (price == -1) {
            prices.set(i, prevPrice);
          } else {
            prevPrice = price;
          }
        }
        int[] data = new int[prices.size() * 2];
        for (int i = 0, d = 0; i < prices.size(); i++) {
          data[d++] = prices.get(i);
          data[d++] = volumes.get(i);
        }
        dist.getParentFile().mkdirs();
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream(); DataOutputStream dataOut = new DataOutputStream(
            bytesOut); FileOutputStream fileOut = new FileOutputStream(dist, false)) {
          for (int i = 0; i < data.length; ++i) {
            dataOut.writeInt(data[i]);
          }
          fileOut.write(bytesOut.toByteArray());
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static int[] load(File file) {
    List<Integer> data = new ArrayList<>();
    try (FileInputStream fileIn = new FileInputStream(
        file); ByteArrayInputStream bytesIn = new ByteArrayInputStream(
        fileIn.readAllBytes()); DataInputStream dataIn = new DataInputStream(bytesIn)) {
      while (true) {
        data.add(dataIn.readInt());
      }
    } catch (EOFException e) {
      return data.stream().mapToInt(i -> i).toArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public int[] getData(boolean training) {
    int[][] data = training ? this.training : this.alt;
    int[] odds = training ? this.oddsTraining : this.oddsAlt;
    return data[odds[rand.nextInt(odds.length)]];
  }
}