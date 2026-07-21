package com.mengwei.localgrowth.observationautomation.retest;

import java.util.UUID;

public record RetestObservationExecutionRequest(
    UUID tenantId,
    UUID merchantId,
    UUID systemActorId,
    UUID experimentId,
    UUID schedulePointId,
    UUID questionId,
    RetestCohort cohort,
    UUID collectorConfigId,
    int repetitionNo) {}
