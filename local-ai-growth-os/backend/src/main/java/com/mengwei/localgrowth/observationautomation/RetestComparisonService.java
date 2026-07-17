package com.mengwei.localgrowth.observationautomation;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RetestComparisonService {
  private static final int MIN_SAMPLE = 3;
  private static final double STABLE_THRESHOLD = 0.03d;
  private static final double HIGH_VOLATILITY_THRESHOLD = 0.30d;

  public Comparison compare(
      Map<String, Object> baseline,
      Map<String, Object> retest,
      double volatility,
      boolean directCitation,
      boolean relatedNewSource) {
    int baselineSamples = integer(baseline.get("totalObservations"));
    int retestSamples = integer(retest.get("totalObservations"));

    Map<String, Object> deltas = new LinkedHashMap<>();
    deltas.put("mentionRateDelta",
        delta(baseline.get("mentionRate"), retest.get("mentionRate")));
    deltas.put("recommendationRateDelta",
        delta(baseline.get("recommendationRate"), retest.get("recommendationRate")));
    deltas.put("top3RateDelta",
        delta(baseline.get("top3Rate"), retest.get("top3Rate")));
    deltas.put("factAccuracyRateDelta",
        delta(baseline.get("factAccuracyRate"), retest.get("factAccuracyRate")));

    String status;
    if (baselineSamples < MIN_SAMPLE || retestSamples < MIN_SAMPLE) {
      status = "INSUFFICIENT_SAMPLE";
    } else if (volatility > HIGH_VOLATILITY_THRESHOLD) {
      status = "HIGH_VOLATILITY";
    } else {
      double score = weightedScore(deltas);
      if (score > STABLE_THRESHOLD) status = "IMPROVED";
      else if (score < -STABLE_THRESHOLD) status = "DECLINED";
      else status = "STABLE";
    }

    String attribution;
    if (directCitation) {
      attribution = "DIRECT_CITATION";
    } else if ("IMPROVED".equals(status) && relatedNewSource) {
      attribution = "STRONG_ASSOCIATION";
    } else if ("IMPROVED".equals(status)) {
      attribution = "TEMPORAL_ASSOCIATION";
    } else {
      attribution = "INSUFFICIENT_EVIDENCE";
    }

    return new Comparison(
        status,
        attribution,
        deltas,
        volatility,
        baselineSamples,
        retestSamples,
        "比较只反映固定口径下的可观测变化；除直接引用外，不主张确定因果。");
  }

  private double weightedScore(Map<String, Object> deltas) {
    return number(deltas.get("mentionRateDelta")) * 0.35d
        + number(deltas.get("recommendationRateDelta")) * 0.35d
        + number(deltas.get("top3RateDelta")) * 0.20d
        + number(deltas.get("factAccuracyRateDelta")) * 0.10d;
  }

  private Double delta(Object baseline, Object retest) {
    if (!(baseline instanceof Number) || !(retest instanceof Number)) return null;
    return ((Number) retest).doubleValue() - ((Number) baseline).doubleValue();
  }

  private int integer(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private double number(Object value) {
    return value instanceof Number number ? number.doubleValue() : 0d;
  }

  public record Comparison(
      String status,
      String attributionLevel,
      Map<String, Object> deltas,
      double volatility,
      int baselineSamples,
      int retestSamples,
      String notice) {}
}
