package com.mengwei.localgrowth.observationautomation.retest;

public final class WilsonIntervalCalculator {
  private WilsonIntervalCalculator() {}

  public static Interval calculate(int successes, int total) {
    return calculate(successes, total, 1.959963984540054d);
  }

  static Interval calculate(int successes, int total, double z) {
    if (total <= 0) return new Interval(0d, 1d);
    if (successes < 0 || successes > total) {
      throw new IllegalArgumentException("successes must be between 0 and total");
    }
    double n = total;
    double p = successes / n;
    double z2 = z * z;
    double denominator = 1d + z2 / n;
    double center = (p + z2 / (2d * n)) / denominator;
    double margin = z * Math.sqrt((p * (1d - p) + z2 / (4d * n)) / n) / denominator;
    return new Interval(clamp(center - margin), clamp(center + margin));
  }

  private static double clamp(double value) {
    return Math.max(0d, Math.min(1d, value));
  }

  public record Interval(double low, double high) {}
}
