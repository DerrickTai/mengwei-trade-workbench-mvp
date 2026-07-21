package com.mengwei.localgrowth.observationautomation.retest;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RetestObservationExecutionResult(
    boolean success,
    UUID collectionRunId,
    UUID collectionResultId,
    UUID observationId,
    String verificationStatus,
    String aiPlatform,
    String providerCode,
    String providerModel,
    String collectionChannel,
    String locationText,
    String contextSha256,
    Boolean merchantMentioned,
    Boolean merchantRecommended,
    Integer recommendationRank,
    Boolean anyCitation,
    Boolean directPublicationCitation,
    Long latencyMs,
    Long costMicros,
    String errorCode,
    String errorMessage,
    OffsetDateTime observedAt) {

  public RetestObservationExecutionResult {
    if ("VERIFIED".equalsIgnoreCase(verificationStatus)) {
      throw new IllegalArgumentException("Automated retest must not create VERIFIED observations");
    }
  }
}
