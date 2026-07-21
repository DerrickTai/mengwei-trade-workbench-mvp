package com.mengwei.localgrowth.observationautomation.retest;

import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class RetestObservationExecutionResultTest {
  @Test void rejectsAutomaticVerifiedResult() {
    assertThrows(IllegalArgumentException.class, () -> new RetestObservationExecutionResult(
        true,null,null,null,"VERIFIED",null,null,null,null,null,null,
        true,true,1,true,true,1L,1L,null,null,OffsetDateTime.now()));
  }
}
