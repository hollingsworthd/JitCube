package com.machinepublishers.neuraltrader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Prices {

  private record Data(int[] prices, long[] volumes) {

  }

  private static final Random rand = new SecureRandom();

  private final int[][] pricesTraining;
  private final long[][] volumesTraining;
  private final int[][] pricesTesting;
  private final long[][] volumesTesting;

  public Prices() {
    List<Data> data = Stream.of(new File("./prices/training").listFiles())
        .filter(f -> !f.getName().startsWith(".")).map(Prices::load).collect(Collectors.toList());
    pricesTraining = data.stream().map(d -> d.prices()).toArray(int[][]::new);
    volumesTraining = data.stream().map(d -> d.volumes()).toArray(long[][]::new);
    data = Stream.of(new File("./prices/testing").listFiles())
        .filter(f -> !f.getName().startsWith(".")).map(Prices::load).collect(Collectors.toList());
    pricesTesting = data.stream().map(d -> d.prices()).toArray(int[][]::new);
    volumesTesting = data.stream().map(d -> d.volumes()).toArray(long[][]::new);
  }

  private static Data load(File file) {
    List<Integer> prices = new ArrayList<>();
    List<Long> volumes = new ArrayList<>();
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
        prices.add(Double.isFinite(priceTmp) ? (int) Math.rint(priceTmp * 100d) : -1);
        volumes.add(Double.isFinite(volumeTmp) ? (long) Math.rint(volumeTmp * 100d) : 0L);
      }
      if (!ascending) {
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
      return new Data(prices.stream().mapToInt(i -> i).toArray(),
          volumes.stream().mapToLong(i -> i).toArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void getData(short[] buffer, boolean training) {
    int[][] prices = training ? pricesTraining : pricesTesting;
    long[][] volumes = training ? volumesTraining : volumesTesting;
    int length = buffer.length / 2;
    int dataset = rand.nextInt(prices.length);
    int offset = length + rand.nextInt(prices[dataset].length - length);
    double maxPrice = 0;
    double maxVolume = 0;
    double minPrice = Integer.MAX_VALUE;
    double minVolume = Double.MAX_VALUE;
    for (int i = offset; i > offset - length; i--) {
      maxPrice = Math.max(prices[dataset][i], maxPrice);
      maxVolume = Math.max(volumes[dataset][i], maxVolume);
      minPrice = Math.min(prices[dataset][i], minPrice);
      minVolume = Math.min(volumes[dataset][i], minVolume);
    }
    maxPrice *= (1d + rand.nextDouble(.5));
    maxVolume *= (1d + rand.nextDouble(.5));
    minPrice *= (.5d + rand.nextDouble(.5));
    minVolume *= (.5d + rand.nextDouble(.5));
    for (int i = offset, d = 0; i > offset - length; i--) {
      buffer[d++] = (short) Math.rint(
          (prices[dataset][i] - minPrice) / (maxPrice - minPrice) * (double) Short.MAX_VALUE);
      buffer[d++] = (short) Math.rint(
          (volumes[dataset][i] - minVolume) / (maxVolume - minVolume) * (double) Short.MAX_VALUE);
    }
  }
}
