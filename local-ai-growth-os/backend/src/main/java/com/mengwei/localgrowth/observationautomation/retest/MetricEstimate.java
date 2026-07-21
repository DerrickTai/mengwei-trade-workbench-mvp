package com.mengwei.localgrowth.observationautomation.retest;

public record MetricEstimate(
    double numerator,
    int denominator,
    double value,
    double ciLow,
    double ciHigh,
    double volatilityScore) {

  public static MetricEstimate binary(int successes, int total) {
    if (total <= 0) return new MetricEstimate(0d, 0, 0d, 0d, 1d, 1d);
    double p = successes / (double) total;
    WilsonIntervalCalculator.Interval interval = WilsonIntervalCalculator.calculate(successes, total);
    double volatility = Math.min(1d, 2d * Math.sqrt(p * (1d - p)));
    return new MetricEstimate(successes, total, p, interval.low(), interval.high(), volatility);
  }

  public static MetricEstimate average(double sum, int total) {
    if (total <= 0) return new MetricEstimate(0d, 0, 0d, 0d, 1d, 1d);
    double value = Math.max(0d, Math.min(1d, sum / total));
    return new MetricEstimate(sum, total, value, value, value, 0d);
  }
}
