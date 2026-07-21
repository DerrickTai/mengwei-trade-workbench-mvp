package com.mengwei.localgrowth.observationautomation.retest;

import java.util.EnumMap;
import java.util.Map;

public record RetestWindowMetrics(
    int validSamples,
    int failedSamples,
    boolean contextDrift,
    Map<RetestMetricName, MetricEstimate> metrics) {

  public RetestWindowMetrics {
    metrics = Map.copyOf(new EnumMap<>(metrics));
  }

  public MetricEstimate metric(RetestMetricName name) {
    return metrics.getOrDefault(name, MetricEstimate.binary(0, 0));
  }
}
