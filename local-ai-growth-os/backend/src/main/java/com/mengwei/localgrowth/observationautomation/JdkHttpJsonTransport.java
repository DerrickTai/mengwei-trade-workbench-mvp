package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JdkHttpJsonTransport implements HttpJsonTransport {
  private final ObjectMapper mapper;
  private final HttpClient client;

  public JdkHttpJsonTransport(ObjectMapper mapper) {
    this.mapper = mapper;
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public TransportResponse postJson(
      URI uri,
      Map<String, String> headers,
      JsonNode body,
      Duration timeout,
      int maxResponseBytes) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
          .timeout(timeout)
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
      headers.forEach(builder::header);
      long started = System.nanoTime();
      HttpResponse<byte[]> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      long latencyMs = (System.nanoTime() - started) / 1_000_000L;
      byte[] bytes = response.body() == null ? new byte[0] : response.body();
      if (bytes.length > maxResponseBytes) {
        throw new ProviderTransportException(
            "RESPONSE_TOO_LARGE",
            "Provider response exceeded " + maxResponseBytes + " bytes");
      }
      JsonNode json = bytes.length == 0
          ? mapper.createObjectNode()
          : mapper.readTree(bytes);
      return new TransportResponse(
          response.statusCode(), response.headers().map(), json, latencyMs);
    } catch (ProviderTransportException e) {
      throw e;
    } catch (IOException e) {
      throw new ProviderTransportException("PROVIDER_IO_ERROR", safeMessage(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderTransportException(
          "PROVIDER_INTERRUPTED", "Provider call interrupted", e);
    } catch (Exception e) {
      throw new ProviderTransportException(
          "PROVIDER_TRANSPORT_ERROR", safeMessage(e), e);
    }
  }

  private static String safeMessage(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank()
        ? e.getClass().getSimpleName()
        : message;
  }

  public static final class ProviderTransportException extends RuntimeException {
    private final String code;

    public ProviderTransportException(String code, String message) {
      super(message);
      this.code = code;
    }

    public ProviderTransportException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
