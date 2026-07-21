package com.mengwei.localgrowth.observationautomation.retest;

import java.util.ArrayList;
import java.util.List;

public class RetestAttributionEngine {
  private final SafeAttributionNarrative narrative = new SafeAttributionNarrative();

  public AttributionAssessment assess(Context context) {
    RetestComparison comparison = context.comparison();
    List<String> reasons = new ArrayList<>();
    int score = 0;

    if (context.directCitationVerified()) {
      score += 60;
      reasons.add("VERIFIED_DIRECT_PUBLICATION_CITATION");
      if (comparison.status() == RetestResultStatus.IMPROVED) score += 10;
      if (context.consecutiveImprovedCycles() >= 2) score += 10;
      return assessment(RetestAttributionLevel.DIRECT_CITATION, score, reasons,
          narrative.directCitation(comparison));
    }

    if (comparison.status() == RetestResultStatus.INSUFFICIENT_SAMPLE) {
      reasons.add("INSUFFICIENT_SAMPLE");
      return assessment(RetestAttributionLevel.INSUFFICIENT_EVIDENCE, 10, reasons,
          narrative.insufficient(comparison));
    }
    if (comparison.status() == RetestResultStatus.HIGH_VOLATILITY) {
      reasons.add("HIGH_VOLATILITY");
      return assessment(RetestAttributionLevel.INSUFFICIENT_EVIDENCE, 15, reasons,
          narrative.insufficient(comparison));
    }
    if (comparison.contextDrift() || context.modelContextChanged()) {
      reasons.add("MODEL_OR_COLLECTION_CONTEXT_CHANGED");
      return assessment(RetestAttributionLevel.INSUFFICIENT_EVIDENCE, 15, reasons,
          narrative.insufficient(comparison));
    }

    if (comparison.status() == RetestResultStatus.IMPROVED) {
      score += 25;
      reasons.add("TARGET_METRIC_IMPROVED");
      if (comparison.hasControl()) {
        score += 15;
        reasons.add("CONTROL_GROUP_AVAILABLE");
      }
      if (comparison.adjustedDelta() >= context.strongAssociationMinimumDelta()) {
        score += 15;
        reasons.add("ADJUSTED_DELTA_THRESHOLD_MET");
      }
      if (context.consecutiveImprovedCycles() >= 2) {
        score += 15;
        reasons.add("SUSTAINED_FOR_MULTIPLE_CYCLES");
      }
      if (!context.concurrentInterventions()) {
        score += 10;
        reasons.add("NO_KNOWN_CONCURRENT_INTERVENTION");
      } else {
        reasons.add("CONCURRENT_INTERVENTION_PRESENT");
      }
      if (context.relatedSourceEvidence()) {
        score += 10;
        reasons.add("RELATED_SOURCE_EVIDENCE");
      }

      boolean strong = comparison.hasControl()
          && comparison.adjustedDelta() >= context.strongAssociationMinimumDelta()
          && context.consecutiveImprovedCycles() >= 2
          && !context.concurrentInterventions();
      return strong
          ? assessment(RetestAttributionLevel.STRONG_ASSOCIATION, score, reasons,
              narrative.strongAssociation(comparison))
          : assessment(RetestAttributionLevel.TEMPORAL_ASSOCIATION, score, reasons,
              narrative.temporalAssociation(comparison));
    }

    reasons.add("NO_RELIABLE_POSITIVE_CHANGE");
    return assessment(RetestAttributionLevel.INSUFFICIENT_EVIDENCE, 20, reasons,
        narrative.insufficient(comparison));
  }

  private AttributionAssessment assessment(
      RetestAttributionLevel level, int score, List<String> reasons, String summary) {
    return new AttributionAssessment(level, Math.max(0, Math.min(100, score)),
        reasons, summary, true);
  }

  public record Context(
      RetestComparison comparison,
      boolean directCitationVerified,
      boolean relatedSourceEvidence,
      int consecutiveImprovedCycles,
      boolean modelContextChanged,
      boolean concurrentInterventions,
      double strongAssociationMinimumDelta) {}
}
