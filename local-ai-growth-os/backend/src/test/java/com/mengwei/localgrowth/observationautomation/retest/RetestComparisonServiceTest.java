package com.mengwei.localgrowth.observationautomation.retest;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetestComparisonServiceTest {
  @Test void calculatesDifferenceInDifferences() {
    var calculator = new RetestStatisticsCalculator();
    var beforeTarget = calculator.aggregate(signals(2,10));
    var afterTarget = calculator.aggregate(signals(7,10));
    var beforeControl = calculator.aggregate(signals(3,10));
    var afterControl = calculator.aggregate(signals(4,10));
    var comparison = new RetestComparisonService().compare(
        RetestMetricName.BRAND_MENTION_RATE,beforeTarget,afterTarget,beforeControl,afterControl,
        3,0.15,1.01);
    assertEquals(0.4d, comparison.adjustedDelta(), 1e-9);
    assertEquals(RetestResultStatus.IMPROVED, comparison.status());
  }

  private List<RetestSampleSignal> signals(int positive, int total) {
    List<RetestSampleSignal> list = new ArrayList<>();
    for (int i=0;i<total;i++) list.add(new RetestSampleSignal(true,i<positive,false,null,false,false,"ctx"));
    return list;
  }
}
