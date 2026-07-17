package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

abstract class AbstractRetryingCollector implements OfficialAiCollector {
  protected final HttpJsonTransport transport;
  protected final ProviderResponseParser parser;

  AbstractRetryingCollector(
      HttpJsonTransport transport,
      ProviderResponseParser parser) {
    this.transport = transport;
    this.parser = parser;
  }

  protected CollectorResponse execute(
      CollectorRequest request,
      URI endpoint,
      JsonNode requestBody,
      ResponseMode responseMode) {
    int maxAttempts = intOption(request.options(), "maxAttempts", 4, 1, 6);
    int timeoutSeconds = intOption(request.options(), "timeoutSeconds", 90, 5, 180);
    int maxBytes = intOption(
        request.options(), "maxResponseBytes", 2_000_000, 10_000, 8_000_000);

    JsonNode lastBody = null;
    long lastLatency = 0L;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        HttpJsonTransport.TransportResponse response = transport.postJson(
            endpoint,
            Map.of(
                "Content-Type", "application/json",
                "Accept", "application/json",
                "Authorization", "Bearer " + request.apiKey()),
            requestBody,
            Duration.ofSeconds(timeoutSeconds),
            maxBytes);
        lastBody = response.body();
        lastLatency = response.latencyMs();

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          ProviderResponseParser.ParsedProviderResponse parsed =
              responseMode == ResponseMode.RESPONSES
                  ? parser.parseResponsesApi(response.body())
                  : parser.parseChatCompletions(response.body());
          if (parsed.rawAnswer() == null || parsed.rawAnswer().isBlank()) {
            return CollectorResponse.failed(
                response.body(),
                response.latencyMs(),
                "EMPTY_PROVIDER_ANSWER",
                "Provider returned no answer text");
          }
          return new CollectorResponse(
              true,
              parsed.requestId(),
              parsed.model(),
              parsed.rawAnswer(),
              parsed.citedSources(),
              response.body(),
              response.latencyMs(),
              OffsetDateTime.now(),
              null,
              null);
        }

        if (!retryable(response.statusCode()) || attempt == maxAttempts) {
          return CollectorResponse.failed(
              response.body(),
              response.latencyMs(),
              "PROVIDER_HTTP_" + response.statusCode(),
              providerError(response.body(), response.statusCode()));
        }
        sleep(backoffMs(response, attempt));
      } catch (JdkHttpJsonTransport.ProviderTransportException e) {
        if (attempt == maxAttempts) {
          return CollectorResponse.failed(lastBody, lastLatency, e.code(), e.getMessage());
        }
        sleep(baseBackoff(attempt));
      }
    }
    return CollectorResponse.failed(
        lastBody, lastLatency, "PROVIDER_UNKNOWN_FAILURE", "Provider request failed");
  }

  private boolean retryable(int status) {
    return status == 408 || status == 429 || status >= 500;
  }

  private long backoffMs(HttpJsonTransport.TransportResponse response, int attempt) {
    String retryAfter = response.headers().entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase("retry-after"))
        .flatMap(e -> e.getValue().stream())
        .findFirst()
        .orElse(null);
    if (retryAfter != null) {
      try {
        return Math.min(30_000L, Long.parseLong(retryAfter) * 1000L);
      } catch (NumberFormatException ignored) {
      }
    }
    return baseBackoff(attempt);
  }

  private long baseBackoff(int attempt) {
    long base = Math.min(8_000L, 500L * (1L << Math.min(attempt, 4)));
    return base + ThreadLocalRandom.current().nextLong(250L, 750L);
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JdkHttpJsonTransport.ProviderTransportException(
          "PROVIDER_INTERRUPTED", "Provider retry interrupted", e);
    }
  }

  private int intOption(
      Map<String, Object> options,
      String key,
      int defaultValue,
      int min,
      int max) {
    Object raw = options == null ? null : options.get(key);
    if (raw == null) return defaultValue;
    try {
      int value = Integer.parseInt(String.valueOf(raw));
      return Math.max(min, Math.min(max, value));
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  private String providerError(JsonNode body, int status) {
    String message = body == null ? "" : body.path("error").path("message").asText("");
    if (message.isBlank() && body != null) message = body.path("message").asText("");
    if (message.isBlank()) message = "Provider returned HTTP " + status;
    return message.length() > 1000 ? message.substring(0, 1000) : message;
  }

  enum ResponseMode {
    CHAT_COMPLETIONS,
    RESPONSES
  }
}
