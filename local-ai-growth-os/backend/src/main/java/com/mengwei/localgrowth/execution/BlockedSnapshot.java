package com.mengwei.localgrowth.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BlockedSnapshot {
    private BlockedSnapshot() {}

    static String storefrontRequiredSnapshot(UUID merchant) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotVersion", "fact-center-v1");
        snapshot.put("resolutionStatus", "BLOCKED_BEFORE_STOREFRONT_RESOLUTION");
        snapshot.put("merchantId", merchant);
        snapshot.put("storefrontId", null);
        snapshot.put("factVersionIds", List.of());
        snapshot.put("resolvedFacts", List.of());
        snapshot.put("prohibitedClaims", List.of());
        snapshot.put("blockingReasons", List.of("STOREFRONT_REQUIRED"));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成门店选择阻断快照", e);
        }
    }
}
