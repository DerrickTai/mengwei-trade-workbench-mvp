package com.mengwei.localgrowth.observationautomation.retest;

/** Adapter boundary to the already merged M5.1 ObservationCollectionService. */
public interface RetestObservationExecutionPort {
  RetestObservationExecutionResult execute(RetestObservationExecutionRequest request);
}
