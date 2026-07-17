package com.mengwei.localgrowth.observationautomation;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CollectorRegistry {
  private final List<OfficialAiCollector> collectors;

  public CollectorRegistry(List<OfficialAiCollector> collectors) {
    this.collectors = List.copyOf(collectors);
  }

  public OfficialAiCollector require(String providerCode) {
    String normalized = providerCode == null
        ? ""
        : providerCode.trim().toUpperCase(Locale.ROOT);
    return collectors.stream()
        .filter(collector -> collector.providerCode().equalsIgnoreCase(normalized))
        .findFirst()
        .orElseThrow(() ->
            new IllegalArgumentException("Unsupported providerCode: " + providerCode));
  }
}
