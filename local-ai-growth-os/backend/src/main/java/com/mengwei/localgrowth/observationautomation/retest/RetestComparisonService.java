package com.mengwei.localgrowth.observationautomation.retest;

public class RetestComparisonService {
  public RetestComparison compare(
      RetestMetricName metric,
      RetestWindowMetrics targetBefore,
      RetestWindowMetrics targetAfter,
      RetestWindowMetrics controlBefore,
      RetestWindowMetrics controlAfter,
      int minimumSamplesPerWindow,
      double minimumEffect,
      double highVolatilityThreshold) {

    MetricEstimate tb = targetBefore.metric(metric);
    MetricEstimate ta = targetAfter.metric(metric);
    boolean hasControl = controlBefore != null && controlAfter != null;
    MetricEstimate cb = hasControl ? controlBefore.metric(metric) : null;
    MetricEstimate ca = hasControl ? controlAfter.metric(metric) : null;

    double targetDelta = ta.value() - tb.value();
    Double controlDelta = hasControl ? ca.value() - cb.value() : null;
    double adjustedDelta = targetDelta - (controlDelta == null ? 0d : controlDelta);
    int samples = targetBefore.validSamples() + targetAfter.validSamples()
        + (hasControl ? controlBefore.validSamples() + controlAfter.validSamples() : 0);
    double maxVolatility = Math.max(tb.volatilityScore(), ta.volatilityScore());
    if (hasControl) {
      maxVolatility = Math.max(maxVolatility,
          Math.max(cb.volatilityScore(), ca.volatilityScore()));
    }
    boolean contextDrift = targetBefore.contextDrift() || targetAfter.contextDrift()
        || (hasControl && (controlBefore.contextDrift() || controlAfter.contextDrift()));

    boolean insufficient = targetBefore.validSamples() < minimumSamplesPerWindow
        || targetAfter.validSamples() < minimumSamplesPerWindow
        || (hasControl && (controlBefore.validSamples() < minimumSamplesPerWindow
            || controlAfter.validSamples() < minimumSamplesPerWindow));

    RetestResultStatus status;
    if (insufficient) status = RetestResultStatus.INSUFFICIENT_SAMPLE;
    else if (maxVolatility >= highVolatilityThreshold) status = RetestResultStatus.HIGH_VOLATILITY;
    else if (adjustedDelta >= minimumEffect) status = RetestResultStatus.IMPROVED;
    else if (adjustedDelta <= -minimumEffect) status = RetestResultStatus.DECLINED;
    else status = RetestResultStatus.STABLE;

    return new RetestComparison(metric, status, tb.value(), ta.value(),
        hasControl ? cb.value() : null, hasControl ? ca.value() : null,
        targetDelta, controlDelta, adjustedDelta, samples, maxVolatility,
        contextDrift, hasControl);
  }
}
