package com.machinepublishers.neuraltrader;

public class Log {

  public static void info(String template, Object... args) {
    System.out.printf(template + "\n", args);
  }
}