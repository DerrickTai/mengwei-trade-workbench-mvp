package com.mengwei.localgrowth.strategy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyDiagnosisServiceTest {
  private final StrategyDiagnosisService service = new StrategyDiagnosisService(null, new ObjectMapper(), null);

  @Test
  void metricsUseNullWhenThereIsNoValidDenominator() {
    Map<String, Object> metrics = service.metrics(List.of());

    assertEquals(0L, metrics.get("totalObservations"));
    assertNull(metrics.get("mentionRate"));
    assertNull(metrics.get("recommendationRate"));
    assertNull(metrics.get("top3Rate"));
    assertNull(metrics.get("factAccuracyRate"));
  }

  @Test
  void metricsAndSourceClassificationAreDeterministic() {
    Map<String, Object> metrics = service.metrics(List.of(
        Map.of("merchant_mentioned", true, "merchant_recommended", true,
            "recommendation_rank", 1, "fact_check_status", "CORRECT"),
        Map.of("merchant_mentioned", false, "merchant_recommended", false,
            "fact_check_status", "ERROR")));

    assertEquals(0.5d, metrics.get("mentionRate"));
    assertEquals(0.5d, metrics.get("recommendationRate"));
    assertEquals(0.5d, metrics.get("top3Rate"));
    assertEquals(0.5d, metrics.get("factAccuracyRate"));
    assertEquals("OWNED", service.sourceCategory("https://www.example.com/page", "WEB", List.of("example.com")));
    assertEquals("LOCAL_PLATFORM", service.sourceCategory("", "DIANPING", List.of()));
    assertEquals("SOCIAL_PLATFORM", service.sourceCategory("", "XIAOHONGSHU", List.of()));
    assertEquals("UNKNOWN", service.sourceCategory("not a valid url", "MANUAL", List.of()));
  }
}
