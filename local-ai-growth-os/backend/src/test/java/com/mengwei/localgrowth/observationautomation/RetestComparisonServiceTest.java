package com.mengwei.localgrowth.observationautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RetestComparisonServiceTest {
  private final RetestComparisonService service = new RetestComparisonService();

  @Test
  void insufficientSampleWins() {
    var result = service.compare(
        Map.of("totalObservations", 2, "mentionRate", 0.0),
        Map.of("totalObservations", 2, "mentionRate", 1.0),
        0.0,
        false,
        false);
    assertThat(result.status()).isEqualTo("INSUFFICIENT_SAMPLE");
    assertThat(result.attributionLevel()).isEqualTo("INSUFFICIENT_EVIDENCE");
  }

  @Test
  void directCitationIsExplicitButNotCausality() {
    var result = service.compare(
        Map.of(
            "totalObservations", 10,
            "mentionRate", 0.2,
            "recommendationRate", 0.1,
            "top3Rate", 0.1,
            "factAccuracyRate", 0.8),
        Map.of(
            "totalObservations", 10,
            "mentionRate", 0.7,
            "recommendationRate", 0.6,
            "top3Rate", 0.4,
            "factAccuracyRate", 0.9),
        0.1,
        true,
        true);
    assertThat(result.status()).isEqualTo("IMPROVED");
    assertThat(result.attributionLevel()).isEqualTo("DIRECT_CITATION");
    assertThat(result.notice()).contains("不主张确定因果");
  }
}
