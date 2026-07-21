package com.mengwei.localgrowth.observationautomation.retest;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RetestAttributionEngineTest {
  @Test void directCitationRequiresExplicitVerifiedFlag() {
    var comparison = comparison(RetestResultStatus.IMPROVED,true,0.4);
    var context = new RetestAttributionEngine.Context(comparison,true,true,1,false,false,0.15);
    var result = new RetestAttributionEngine().assess(context);
    assertEquals(RetestAttributionLevel.DIRECT_CITATION,result.level());
    assertTrue(result.safeSummary().contains("不能单独证明"));
  }

  @Test void noControlCannotBecomeStrongAssociation() {
    var comparison = comparison(RetestResultStatus.IMPROVED,false,0.4);
    var context = new RetestAttributionEngine.Context(comparison,false,true,3,false,false,0.15);
    var result = new RetestAttributionEngine().assess(context);
    assertEquals(RetestAttributionLevel.TEMPORAL_ASSOCIATION,result.level());
  }

  @Test void insufficientSampleCannotBecomeStrongAssociation() {
    var comparison = comparison(RetestResultStatus.INSUFFICIENT_SAMPLE,true,0.4);
    var result = new RetestAttributionEngine().assess(
        new RetestAttributionEngine.Context(comparison,false,true,3,false,false,0.15));
    assertEquals(RetestAttributionLevel.INSUFFICIENT_EVIDENCE,result.level());
  }

  private RetestComparison comparison(RetestResultStatus status, boolean control, double adjusted) {
    return new RetestComparison(RetestMetricName.BRAND_MENTION_RATE,status,0.2,0.7,
        control?0.3:null,control?0.4:null,0.5,control?0.1:null,adjusted,40,0.4,false,control);
  }
}
