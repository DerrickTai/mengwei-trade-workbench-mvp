package com.mengwei.localgrowth.observationautomation.retest;

import java.util.List;

/**
 * Pure projection component. Repository persistence is intentionally separated so the statistical
 * rules can be tested without Spring or PostgreSQL.
 */
public class RetestMetricProjector {
  private final RetestStatisticsCalculator calculator = new RetestStatisticsCalculator();

  public Projection project(List<RetestSampleSignal> target, List<RetestSampleSignal> control) {
    RetestWindowMetrics targetMetrics = calculator.aggregate(target);
    RetestWindowMetrics controlMetrics = control == null || control.isEmpty()
        ? null : calculator.aggregate(control);
    return new Projection(targetMetrics, controlMetrics);
  }

  public record Projection(RetestWindowMetrics target, RetestWindowMetrics control) {}
}
