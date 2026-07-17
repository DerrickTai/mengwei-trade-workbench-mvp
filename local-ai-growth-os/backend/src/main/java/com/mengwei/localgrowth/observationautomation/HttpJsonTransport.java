package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface HttpJsonTransport {
  TransportResponse postJson(
      URI uri,
      Map<String, String> headers,
      JsonNode body,
      Duration timeout,
      int maxResponseBytes);

  record TransportResponse(
      int statusCode,
      Map<String, List<String>> headers,
      JsonNode body,
      long latencyMs) {
  }
}
