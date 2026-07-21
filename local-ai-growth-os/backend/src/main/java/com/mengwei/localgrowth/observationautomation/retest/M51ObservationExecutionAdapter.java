package com.mengwei.localgrowth.observationautomation.retest;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.observationautomation.ObservationCollectionController.RunInput;
import com.mengwei.localgrowth.observationautomation.ObservationCollectionService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Reuses the M5.1 collector pipeline; it never creates a VERIFIED observation. */
@Component
public class M51ObservationExecutionAdapter implements RetestObservationExecutionPort {
  private final ObservationCollectionService collections;
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public M51ObservationExecutionAdapter(ObservationCollectionService collections,
      NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
    this.collections = collections;
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public RetestObservationExecutionResult execute(RetestObservationExecutionRequest request) {
    Identity identity = new Identity(request.tenantId(), request.systemActorId());
    Map<String, Object> run = collections.run(identity, request.merchantId(),
        new RunInput(List.of(request.collectorConfigId()), List.of(request.questionId()), true), "RETEST");
    UUID runId = UUID.fromString(String.valueOf(run.get("id")));
    Map<String, Object> result = collections.results(identity, request.merchantId(), runId).getFirst();
    boolean success = "SUCCESS".equals(String.valueOf(result.get("status")));
    return new RetestObservationExecutionResult(success, runId,
        UUID.fromString(String.valueOf(result.get("id"))), uuid(result.get("promoted_observation_id")),
        "DRAFT", string(result.get("ai_platform")), string(result.get("provider_code")),
        string(result.get("provider_model")), string(result.get("collection_channel")),
        string(result.get("location_text")), string(result.get("response_sha256")),
        bool(result.get("merchant_mentioned")), bool(result.get("merchant_recommended")),
        integer(result.get("recommendation_rank")), hasCitation(result.get("cited_sources")),
        directCitation(request.tenantId(), request.merchantId(), uuid(result.get("id")), result.get("cited_sources")),
        number(result.get("latency_ms")), null, string(result.get("error_code")),
        string(result.get("error_message")), time(result.get("observed_at")));
  }

  private boolean hasCitation(Object value) { return value != null && !"[]".equals(String.valueOf(value)); }
  private boolean directCitation(UUID tenantId, UUID merchantId, UUID collectionResultId, Object citedSources) {
    Long eventCount = collectionResultId == null ? 0L : jdbc.queryForObject("""
        select count(*) from geo_publication_citation_events e
         where e.tenant_id=:tenantId and e.merchant_id=:merchantId
           and e.collection_result_id=:collectionResultId and e.direct_citation=true
           and e.match_type in ('EXACT_URL','REDIRECTED_URL','CANONICAL_URL','MANUAL_CONFIRMED')
        """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("merchantId", merchantId)
        .addValue("collectionResultId", collectionResultId), Long.class);
    if (eventCount != null && eventCount > 0) return true;
    for (Map<String,Object> source : sources(citedSources)) {
      Object raw = source.get("url");
      if (raw == null || String.valueOf(raw).isBlank()) continue;
      Long publication = jdbc.queryForObject("""
          select count(*) from geo_tracked_publications
           where tenant_id=:tenantId and merchant_id=:merchantId and status='ACTIVE'
             and (:url=normalized_url or :url=canonical_url)
          """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("merchantId", merchantId)
          .addValue("url", String.valueOf(raw)), Long.class);
      if (publication != null && publication > 0) return true;
    }
    return false;
  }
  private List<Map<String,Object>> sources(Object value) { try { return value == null ? List.of() : mapper.readValue(String.valueOf(value), new TypeReference<List<Map<String,Object>>>(){}); } catch (Exception ignored) { return List.of(); } }
  private UUID uuid(Object value) { try { return value == null ? null : UUID.fromString(String.valueOf(value)); } catch (Exception ignored) { return null; } }
  private String string(Object value) { return value == null ? null : String.valueOf(value); }
  private Boolean bool(Object value) { return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)); }
  private Integer integer(Object value) { return value instanceof Number n ? n.intValue() : null; }
  private Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }
  private OffsetDateTime time(Object value) { return value instanceof OffsetDateTime t ? t : OffsetDateTime.now(); }
}
