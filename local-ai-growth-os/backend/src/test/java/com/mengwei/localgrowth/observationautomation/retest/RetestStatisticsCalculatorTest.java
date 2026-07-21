package com.mengwei.localgrowth.observationautomation.retest;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetestStatisticsCalculatorTest {
  @Test void aggregatesRatesAndMrr() {
    var samples = List.of(
        new RetestSampleSignal(true,true,true,1,true,true,"ctx"),
        new RetestSampleSignal(true,true,true,2,false,false,"ctx"),
        new RetestSampleSignal(true,false,false,null,false,false,"ctx"));
    var metrics = new RetestStatisticsCalculator().aggregate(samples);
    assertEquals(2d/3d, metrics.metric(RetestMetricName.BRAND_MENTION_RATE).value(), 1e-9);
    assertEquals(0.5d, metrics.metric(RetestMetricName.MEAN_RECIPROCAL_RANK).value(), 1e-9);
    assertFalse(metrics.contextDrift());
  }

  @Test void detectsContextDrift() {
    var metrics = new RetestStatisticsCalculator().aggregate(List.of(
        new RetestSampleSignal(true,true,false,null,false,false,"a"),
        new RetestSampleSignal(true,true,false,null,false,false,"b")));
    assertTrue(metrics.contextDrift());
  }

  @Test void providerFailuresAreNotPartOfTheRateDenominator() {
    var metrics = new RetestStatisticsCalculator().aggregate(List.of(
        new RetestSampleSignal(true, true, false, null, false, false, "a"),
        new RetestSampleSignal(false, false, false, null, false, false, null)));
    assertEquals(1, metrics.validSamples());
    assertEquals(1, metrics.failedSamples());
    assertEquals(1d, metrics.metric(RetestMetricName.BRAND_MENTION_RATE).value(), 1e-9);
  }

  @Test void domainOnlyDoesNotBecomeDirectPublicationCitation() {
    var metrics = new RetestStatisticsCalculator().aggregate(List.of(
        new RetestSampleSignal(true, true, false, null, true, false, "a")));
    assertEquals(1d, metrics.metric(RetestMetricName.ANY_CITATION_RATE).value(), 1e-9);
    assertEquals(0d, metrics.metric(RetestMetricName.DIRECT_PUBLICATION_CITATION_RATE).value(), 1e-9);
  }
}
