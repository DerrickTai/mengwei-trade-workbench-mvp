package com.mengwei.localgrowth.observationautomation.retest;

import java.util.List;

public record AttributionAssessment(
    RetestAttributionLevel level,
    int evidenceScore,
    List<String> reasonCodes,
    String safeSummary,
    boolean requiresHumanReview) {
  public AttributionAssessment {
    reasonCodes = List.copyOf(reasonCodes);
  }
}
