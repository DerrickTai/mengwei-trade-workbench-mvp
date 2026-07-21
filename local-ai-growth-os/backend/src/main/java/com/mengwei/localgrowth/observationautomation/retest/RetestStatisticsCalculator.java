package com.mengwei.localgrowth.observationautomation.retest;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RetestStatisticsCalculator {
  public RetestWindowMetrics aggregate(List<RetestSampleSignal> samples) {
    int valid = 0;
    int failed = 0;
    int mention = 0;
    int recommended = 0;
    int top3 = 0;
    int anyCitation = 0;
    int directCitation = 0;
    double reciprocalRankSum = 0d;
    Set<String> contexts = new HashSet<>();

    for (RetestSampleSignal sample : samples == null ? List.<RetestSampleSignal>of() : samples) {
      if (sample == null || !sample.valid()) {
        failed++;
        continue;
      }
      valid++;
      if (sample.merchantMentioned()) mention++;
      if (sample.merchantRecommended()) recommended++;
      if (sample.recommendationRank() != null && sample.recommendationRank() <= 3) top3++;
      if (sample.anyCitation()) anyCitation++;
      if (sample.directPublicationCitation()) directCitation++;
      reciprocalRankSum += sample.reciprocalRank();
      if (sample.contextSha256() != null && !sample.contextSha256().isBlank()) {
        contexts.add(sample.contextSha256());
      }
    }

    Map<RetestMetricName, MetricEstimate> metrics = new EnumMap<>(RetestMetricName.class);
    metrics.put(RetestMetricName.BRAND_MENTION_RATE, MetricEstimate.binary(mention, valid));
    metrics.put(RetestMetricName.BRAND_RECOMMENDATION_RATE, MetricEstimate.binary(recommended, valid));
    metrics.put(RetestMetricName.TOP3_RATE, MetricEstimate.binary(top3, valid));
    metrics.put(RetestMetricName.ANY_CITATION_RATE, MetricEstimate.binary(anyCitation, valid));
    metrics.put(RetestMetricName.DIRECT_PUBLICATION_CITATION_RATE,
        MetricEstimate.binary(directCitation, valid));
    metrics.put(RetestMetricName.MEAN_RECIPROCAL_RANK,
        MetricEstimate.average(reciprocalRankSum, valid));
    return new RetestWindowMetrics(valid, failed, contexts.size() > 1, metrics);
  }
}
