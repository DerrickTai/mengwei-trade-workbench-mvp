package com.mengwei.localgrowth.observationautomation.retest;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WilsonIntervalCalculatorTest {
  @Test void intervalStaysWithinBounds() {
    var interval = WilsonIntervalCalculator.calculate(7, 10);
    assertTrue(interval.low() >= 0d);
    assertTrue(interval.high() <= 1d);
    assertTrue(interval.low() < 0.7d && interval.high() > 0.7d);
  }

  @Test void zeroTotalIsExplicitlyUncertain() {
    var interval = WilsonIntervalCalculator.calculate(0, 0);
    assertEquals(0d, interval.low());
    assertEquals(1d, interval.high());
  }
}
