package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekOfficialCollector extends AbstractRetryingCollector {
  private final ObjectMapper mapper;

  public DeepSeekOfficialCollector(
      HttpJsonTransport transport,
      ProviderResponseParser parser,
      ObjectMapper mapper) {
    super(transport, parser);
    this.mapper = mapper;
  }

  @Override
  public String providerCode() {
    return "DEEPSEEK_OFFICIAL";
  }

  @Override
  public CollectorResponse collect(CollectorRequest request) {
    ObjectNode body = mapper.createObjectNode();
    body.put("model", request.model());
    body.putArray("messages")
        .addObject()
        .put("role", "user")
        .put("content", request.questionText());
    body.put("temperature", decimalOption(request, "temperature", 0.2d));
    body.put("max_tokens", intOption(request, "maxTokens", 4096));
    return execute(
        request,
        URI.create(trimSlash(request.apiBaseUrl()) + "/chat/completions"),
        body,
        ResponseMode.CHAT_COMPLETIONS);
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private int intOption(CollectorRequest request, String key, int fallback) {
    Object raw = request.options().get(key);
    try {
      return raw == null ? fallback : Integer.parseInt(String.valueOf(raw));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private double decimalOption(CollectorRequest request, String key, double fallback) {
    Object raw = request.options().get(key);
    try {
      return raw == null ? fallback : Double.parseDouble(String.valueOf(raw));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
