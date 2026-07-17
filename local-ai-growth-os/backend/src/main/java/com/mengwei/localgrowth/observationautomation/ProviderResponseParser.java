package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ProviderResponseParser {

  public ParsedProviderResponse parseChatCompletions(JsonNode root) {
    String answer = root.path("choices").path(0).path("message").path("content").asText("");
    return new ParsedProviderResponse(
        root.path("id").asText(null),
        root.path("model").asText(null),
        answer,
        collectSources(root));
  }

  public ParsedProviderResponse parseResponsesApi(JsonNode root) {
    StringBuilder answer = new StringBuilder();
    JsonNode output = root.path("output");
    if (output.isArray()) {
      for (JsonNode item : output) {
        JsonNode content = item.path("content");
        if (!content.isArray()) continue;
        for (JsonNode part : content) {
          String text = part.path("text").asText("");
          if (!text.isBlank()) {
            if (!answer.isEmpty()) answer.append('\n');
            answer.append(text);
          }
        }
      }
    }
    if (answer.isEmpty()) answer.append(root.path("output_text").asText(""));
    return new ParsedProviderResponse(
        root.path("id").asText(null),
        root.path("model").asText(null),
        answer.toString(),
        collectSources(root));
  }

  public List<Map<String, Object>> collectSources(JsonNode root) {
    Set<String> seen = new LinkedHashSet<>();
    List<Map<String, Object>> sources = new ArrayList<>();
    walk(root, null, seen, sources);
    return sources;
  }

  private void walk(
      JsonNode node,
      String parentField,
      Set<String> seen,
      List<Map<String, Object>> sources) {
    if (node == null || node.isNull()) return;
    if (node.isObject()) {
      String url = firstText(node, "url", "uri", "href");
      if (url != null && looksLikeHttpUrl(url) && seen.add(url)) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("url", url);
        String title = firstText(node, "title", "name");
        if (title != null) source.put("name", title);
        if (parentField != null) source.put("sourceType", parentField);
        sources.add(source);
      }
      node.fields().forEachRemaining(
          entry -> walk(entry.getValue(), entry.getKey(), seen, sources));
      return;
    }
    if (node.isArray()) {
      node.forEach(value -> walk(value, parentField, seen, sources));
      return;
    }
    if (node.isTextual()) {
      String value = node.asText();
      if (looksLikeHttpUrl(value) && seen.add(value)) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("url", value);
        if (parentField != null) source.put("sourceType", parentField);
        sources.add(source);
      }
    }
  }

  private String firstText(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.get(name);
      if (value != null && value.isTextual() && !value.asText().isBlank()) {
        return value.asText().trim();
      }
    }
    return null;
  }

  private boolean looksLikeHttpUrl(String value) {
    return value != null
        && (value.startsWith("https://") || value.startsWith("http://"));
  }

  public record ParsedProviderResponse(
      String requestId,
      String model,
      String rawAnswer,
      List<Map<String, Object>> citedSources) {
  }
}
