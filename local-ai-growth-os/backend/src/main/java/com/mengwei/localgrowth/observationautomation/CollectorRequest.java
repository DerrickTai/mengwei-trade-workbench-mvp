package com.mengwei.localgrowth.observationautomation;

import java.util.Map;
import java.util.UUID;

public record CollectorRequest(
    UUID merchantId,
    UUID storefrontId,
    UUID questionId,
    String questionText,
    String aiPlatform,
    CollectionChannel channel,
    String providerCode,
    String apiBaseUrl,
    String model,
    String apiKey,
    boolean webSearchEnabled,
    String locationCountry,
    String locationText,
    Map<String, Object> options) {
}
