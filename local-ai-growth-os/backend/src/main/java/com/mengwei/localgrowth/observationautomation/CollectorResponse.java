package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record CollectorResponse(
    boolean success,
    String requestId,
    String model,
    String rawAnswer,
    List<Map<String, Object>> citedSources,
    JsonNode rawResponse,
    long latencyMs,
    OffsetDateTime observedAt,
    String errorCode,
    String errorMessage) {

  public static CollectorResponse failed(
      JsonNode rawResponse,
      long latencyMs,
      String errorCode,
      String errorMessage) {
    return new CollectorResponse(
        false, null, null, null, List.of(), rawResponse, latencyMs,
        OffsetDateTime.now(), errorCode, errorMessage);
  }
}
