package com.machinepublishers.neuraltrader;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class Prices {

  private final int[] prices;
  private final long[] volumes;

  public Prices() {
    List<Integer> prices = new ArrayList<>();
    List<Long> volumes = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(new FileInputStream("./btc.csv")))) {
      reader.readLine();
      int prevPrice = 0;
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        String[] tokens = line.split(",");
        if (tokens.length != 8) {
          throw new IllegalArgumentException();
        }
        double weightedPriceUsdTmp = Double.parseDouble(tokens[7]);
        double volumeUsdTmp = Double.parseDouble(tokens[6]);
        volumeUsdTmp = Double.isFinite(volumeUsdTmp) ? volumeUsdTmp * 100d : 0d;
        long volumeUsd = (long) Math.rint(volumeUsdTmp);
        if (Double.isFinite(weightedPriceUsdTmp)) {
          int weightedPriceUsd = (int) Math.rint(weightedPriceUsdTmp * 100d);
          prevPrice = weightedPriceUsd;
          prices.add(weightedPriceUsd);
          volumes.add(volumeUsd);
        } else {
          prices.add(prevPrice);
          volumes.add(volumeUsd);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    this.prices = prices.stream().mapToInt(i -> i).toArray();
    this.volumes = volumes.stream().mapToLong(i -> i).toArray();
  }

  public int size() {
    return prices.length;
  }

  public void getData(short[] buffer, int offset) {
    int maxPrice = 0;
    long maxVolume = 0;
    int minPrice = Integer.MAX_VALUE;
    long minVolume = Long.MAX_VALUE;
    int length = buffer.length / 2;
    for (int i = offset; i > offset - length; i--) {
      maxPrice = Math.max(prices[i], maxPrice);
      maxVolume = Math.max(volumes[i], maxVolume);
      minPrice = Math.min(prices[i], minPrice);
      minVolume = Math.min(volumes[i], minVolume);
    }
    for (int i = offset, d = 0; i > offset - length; i--) {
      buffer[d++] = (short) Math.rint(
          (double) (prices[i] - minPrice) / (double) (maxPrice - minPrice)
              * (double) Short.MAX_VALUE);
      buffer[d++] = (short) Math.rint(
          (double) (volumes[i] - minVolume) / (double) (maxVolume - minVolume)
              * (double) Short.MAX_VALUE);
    }
  }
}
