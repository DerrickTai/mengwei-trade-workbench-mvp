package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class DoubaoOfficialCollector extends AbstractRetryingCollector {
  private final ObjectMapper mapper;

  public DoubaoOfficialCollector(
      HttpJsonTransport transport,
      ProviderResponseParser parser,
      ObjectMapper mapper) {
    super(transport, parser);
    this.mapper = mapper;
  }

  @Override
  public String providerCode() {
    return "DOUBAO_OFFICIAL";
  }

  @Override
  public CollectorResponse collect(CollectorRequest request) {
    ObjectNode body = mapper.createObjectNode();
    body.put("model", request.model());

    ArrayNode input = body.putArray("input");
    ObjectNode message = input.addObject();
    message.put("role", "user");
    message.putArray("content")
        .addObject()
        .put("type", "input_text")
        .put("text", request.questionText());

    if (request.webSearchEnabled()) {
      body.putArray("tools").addObject().put("type", "web_search");
    }

    body.put("temperature", decimalOption(request, "temperature", 0.2d));
    body.put("max_output_tokens", intOption(request, "maxTokens", 4096));

    return execute(
        request,
        URI.create(trimSlash(request.apiBaseUrl()) + "/responses"),
        body,
        ResponseMode.RESPONSES);
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
