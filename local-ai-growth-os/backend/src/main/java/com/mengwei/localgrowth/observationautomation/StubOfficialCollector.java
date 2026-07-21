package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local acceptance-only collector. It is absent unless APP_OBSERVATION_STUB_ENABLED=true. */
@Component
@ConditionalOnProperty(prefix = "app.observation", name = "stub-enabled", havingValue = "true")
public class StubOfficialCollector implements OfficialAiCollector {
  private final ObjectMapper mapper;
  public StubOfficialCollector(ObjectMapper mapper) { this.mapper = mapper; }
  @Override public String providerCode() { return "STUB"; }
  @Override public CollectorResponse collect(CollectorRequest request) {
    boolean target = request.questionText() != null && request.questionText().contains("[TARGET]");
    String answer = target
        ? String.valueOf(request.options().getOrDefault("stubTargetAnswer", "测试品牌值得考虑。"))
        : String.valueOf(request.options().getOrDefault("stubControlAnswer", "未提及该商家。"));
    @SuppressWarnings("unchecked") List<Map<String,Object>> sources = target && request.options().get("stubSources") instanceof List<?> raw
        ? (List<Map<String,Object>>) raw : List.of();
    return new CollectorResponse(true, "stub-" + UUID.randomUUID(), "stub-local", answer, sources,
        mapper.valueToTree(Map.of("stub", true)), 1L, OffsetDateTime.now(), null, null);
  }
}
