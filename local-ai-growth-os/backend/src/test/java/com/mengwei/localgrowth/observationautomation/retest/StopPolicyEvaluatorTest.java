package com.mengwei.localgrowth.observationautomation.retest;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StopPolicyEvaluatorTest {
  @Test void stopsAtCostBudget() {
    var input = new StopPolicyEvaluator.Input(false,false,null,0,1000L,1000L,
        0,3,0,2,0,3,1,4);
    var decision = new StopPolicyEvaluator().evaluate(input);
    assertTrue(decision.stop());
    assertEquals("COST_BUDGET_REACHED",decision.reasonCode());
  }
}
