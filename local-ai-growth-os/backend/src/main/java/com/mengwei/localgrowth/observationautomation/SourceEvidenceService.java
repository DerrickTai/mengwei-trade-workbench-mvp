package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceEvidenceService {
  private static final Set<String> OWNERSHIP =
      Set.of("OWNED", "COMPETITOR", "THIRD_PARTY", "UNKNOWN");
  private static final Set<String> GRADES =
      Set.of("A", "B", "C", "D", "CONFLICTED", "UNAVAILABLE");
  private static final Set<String> REVIEWS =
      Set.of("UNREVIEWED", "VERIFIED", "DISPUTED");
  private static final Set<String> FACT_STATUS =
      Set.of("NOT_CHECKED", "CONSISTENT", "CONFLICTED", "PARTIAL");

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final UrlCanonicalizer canonicalizer;
  private final PublicWebMetadataFetcher fetcher;

  public SourceEvidenceService(
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper mapper,
      UrlCanonicalizer canonicalizer,
      PublicWebMetadataFetcher fetcher) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.canonicalizer = canonicalizer;
    this.fetcher = fetcher;
  }

  public List<Map<String, Object>> list(Identity identity, UUID merchantId) {
    merchant(identity, merchantId);
    return jdbc.queryForList(
        """
        select * from geo_source_evidence
         where tenant_id=:tenantId and merchant_id=:merchantId
         order by coalesce(fetched_at,created_at) desc
        """,
        base(identity, merchantId));
  }

  @Transactional
  public Map<String, Object> refresh(
      Identity identity,
      UUID merchantId,
      String url,
      String ownershipType,
      String sourceType) {
    merchant(identity, merchantId);
    UrlCanonicalizer.NormalizedUrl normalized = canonicalizer.normalize(url);
    PublicWebMetadataFetcher.FetchResult fetched = fetcher.fetch(normalized.url());
    UUID id = existingId(identity, merchantId, normalized.url());
    if (id == null) id = UUID.randomUUID();

    String ownership = normalize(ownershipType, OWNERSHIP, "THIRD_PARTY");
    String source = sourceType == null || sourceType.isBlank()
        ? classifyDomain(normalized.domain())
        : sourceType.trim().toUpperCase();
    OffsetDateTime now = OffsetDateTime.now();

    Map<String, Object> metadata = Map.of(
        "errorCode", fetched.errorCode() == null ? "" : fetched.errorCode(),
        "errorMessage", fetched.errorMessage() == null ? "" : fetched.errorMessage());

    jdbc.update(
        """
        insert into geo_source_evidence(
          id,tenant_id,merchant_id,normalized_url,canonical_url,final_url,domain,
          source_type,ownership_type,evidence_grade,fetch_status,http_status,title,
          author,publisher,fetched_at,content_sha256,extraction_method,
          fact_consistency_status,cross_source_count,manual_review_status,metadata,
          created_at,updated_at,created_by,updated_by,version
        ) values(
          :id,:tenantId,:merchantId,:url,:canonical,:finalUrl,:domain,
          :sourceType,:ownership,:grade,:fetchStatus,:httpStatus,:title,
          :author,:publisher,:fetchedAt,:contentHash,'HTML_META_V1',
          'NOT_CHECKED',0,'UNREVIEWED',cast(:metadata as jsonb),
          :now,:now,:userId,:userId,0
        )
        on conflict(tenant_id,merchant_id,normalized_url) do update set
          canonical_url=excluded.canonical_url,
          final_url=excluded.final_url,
          domain=excluded.domain,
          source_type=excluded.source_type,
          ownership_type=excluded.ownership_type,
          evidence_grade=case
            when geo_source_evidence.manual_review_status='UNREVIEWED'
              then excluded.evidence_grade
            else geo_source_evidence.evidence_grade
          end,
          fetch_status=excluded.fetch_status,
          http_status=excluded.http_status,
          title=excluded.title,
          author=excluded.author,
          publisher=excluded.publisher,
          fetched_at=excluded.fetched_at,
          content_sha256=excluded.content_sha256,
          extraction_method=excluded.extraction_method,
          metadata=excluded.metadata,
          updated_at=excluded.updated_at,
          updated_by=excluded.updated_by,
          version=geo_source_evidence.version+1
        """,
        base(identity, merchantId)
            .addValue("id", id)
            .addValue("url", normalized.url())
            .addValue("canonical", fetched.canonicalUrl())
            .addValue("finalUrl", fetched.finalUrl())
            .addValue("domain", normalized.domain())
            .addValue("sourceType", source)
            .addValue("ownership", ownership)
            .addValue("grade", fetched.success() ? defaultGrade(ownership) : "UNAVAILABLE")
            .addValue("fetchStatus", fetched.success() ? "FETCHED" : "FAILED")
            .addValue("httpStatus", fetched.httpStatus())
            .addValue("title", fetched.title())
            .addValue("author", fetched.author())
            .addValue("publisher", fetched.publisher())
            .addValue("fetchedAt", fetched.fetchedAt())
            .addValue("contentHash", fetched.contentSha256())
            .addValue("metadata", json(metadata))
            .addValue("now", now)
            .addValue("userId", identity.userId()));
    return evidence(identity, merchantId, id);
  }

  @Transactional
  public void linkResultSources(
      Identity identity,
      UUID merchantId,
      UUID resultId,
      List<Map<String, Object>> sources) {
    if (sources == null) return;
    int order = 0;
    for (Map<String, Object> source : sources) {
      order++;
      Object raw = source.get("url");
      if (raw == null || String.valueOf(raw).isBlank()) continue;
      try {
        UrlCanonicalizer.NormalizedUrl normalized =
            canonicalizer.normalize(String.valueOf(raw));
        UUID evidenceId = ensurePending(identity, merchantId, normalized, source);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
            """
            insert into geo_observation_source_links(
              id,tenant_id,merchant_id,collection_result_id,source_evidence_id,
              citation_order,first_seen_at,last_seen_at,created_at,created_by
            ) values(
              :id,:tenantId,:merchantId,:resultId,:evidenceId,:citationOrder,
              :now,:now,:now,:userId
            )
            on conflict do nothing
            """,
            base(identity, merchantId)
                .addValue("id", UUID.randomUUID())
                .addValue("resultId", resultId)
                .addValue("evidenceId", evidenceId)
                .addValue("citationOrder", order)
                .addValue("now", now)
                .addValue("userId", identity.userId()));
      } catch (Exception ignored) {
        // Raw cited_sources remain in staging; malformed URLs must not fail collection.
      }
    }
  }

  @Transactional
  public void copyLinksToObservation(
      Identity identity,
      UUID merchantId,
      UUID resultId,
      UUID observationId) {
    List<Map<String, Object>> links = jdbc.queryForList(
        """
        select source_evidence_id,citation_order,first_seen_at,last_seen_at
          from geo_observation_source_links
         where tenant_id=:tenantId and merchant_id=:merchantId
           and collection_result_id=:resultId
        """,
        base(identity, merchantId).addValue("resultId", resultId));
    OffsetDateTime now = OffsetDateTime.now();
    for (Map<String, Object> link : links) {
      jdbc.update(
          """
          insert into geo_observation_source_links(
            id,tenant_id,merchant_id,observation_id,source_evidence_id,citation_order,
            first_seen_at,last_seen_at,created_at,created_by
          ) values(
            :id,:tenantId,:merchantId,:observationId,:evidenceId,:citationOrder,
            :firstSeen,:lastSeen,:now,:userId
          )
          on conflict do nothing
          """,
          base(identity, merchantId)
              .addValue("id", UUID.randomUUID())
              .addValue("observationId", observationId)
              .addValue("evidenceId", link.get("source_evidence_id"))
              .addValue("citationOrder", link.get("citation_order"))
              .addValue("firstSeen", link.get("first_seen_at"))
              .addValue("lastSeen", link.get("last_seen_at"))
              .addValue("now", now)
              .addValue("userId", identity.userId()));
    }
  }

  @Transactional
  public Map<String, Object> review(
      Identity identity,
      UUID merchantId,
      UUID evidenceId,
      String review,
      String grade,
      String factStatus,
      String notes) {
    evidence(identity, merchantId, evidenceId);
    String normalizedReview = normalize(review, REVIEWS, null);
    String normalizedGrade = normalize(grade, GRADES, null);
    String normalizedFact = normalize(factStatus, FACT_STATUS, null);
    if (normalizedReview == null || normalizedGrade == null || normalizedFact == null) {
      throw bad("INVALID_EVIDENCE_REVIEW", "信源复核字段不合法");
    }
    jdbc.update(
        """
        update geo_source_evidence set
          manual_review_status=:review,evidence_grade=:grade,
          fact_consistency_status=:fact,
          metadata=jsonb_set(
            coalesce(metadata,'{}'::jsonb),'{reviewNotes}',
            to_jsonb(cast(:notes as text)),true
          ),
          updated_at=:now,updated_by=:userId,version=version+1
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId)
            .addValue("id", evidenceId)
            .addValue("review", normalizedReview)
            .addValue("grade", normalizedGrade)
            .addValue("fact", normalizedFact)
            .addValue("notes", notes == null ? "" : notes)
            .addValue("now", OffsetDateTime.now())
            .addValue("userId", identity.userId()));
    return evidence(identity, merchantId, evidenceId);
  }

  private UUID ensurePending(
      Identity identity,
      UUID merchantId,
      UrlCanonicalizer.NormalizedUrl normalized,
      Map<String, Object> source) {
    UUID existing = existingId(identity, merchantId, normalized.url());
    if (existing != null) return existing;
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.update(
        """
        insert into geo_source_evidence(
          id,tenant_id,merchant_id,normalized_url,domain,source_type,ownership_type,
          evidence_grade,fetch_status,fact_consistency_status,cross_source_count,
          manual_review_status,metadata,created_at,updated_at,created_by,updated_by,version
        ) values(
          :id,:tenantId,:merchantId,:url,:domain,:sourceType,'THIRD_PARTY',
          'D','PENDING','NOT_CHECKED',0,'UNREVIEWED',cast(:metadata as jsonb),
          :now,:now,:userId,:userId,0
        )
        on conflict(tenant_id,merchant_id,normalized_url) do nothing
        """,
        base(identity, merchantId)
            .addValue("id", id)
            .addValue("url", normalized.url())
            .addValue("domain", normalized.domain())
            .addValue("sourceType", classifyDomain(normalized.domain()))
            .addValue("metadata", json(source))
            .addValue("now", now)
            .addValue("userId", identity.userId()));
    UUID loaded = existingId(identity, merchantId, normalized.url());
    return loaded == null ? id : loaded;
  }

  private UUID existingId(
      Identity identity, UUID merchantId, String normalizedUrl) {
    List<UUID> ids = jdbc.query(
        """
        select id from geo_source_evidence
         where tenant_id=:tenantId and merchant_id=:merchantId
           and normalized_url=:url
        """,
        base(identity, merchantId).addValue("url", normalizedUrl),
        (rs, rowNum) -> rs.getObject("id", UUID.class));
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private Map<String, Object> evidence(
      Identity identity, UUID merchantId, UUID evidenceId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select * from geo_source_evidence
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId).addValue("id", evidenceId));
    if (rows.isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "信源证据不存在");
    }
    return rows.getFirst();
  }

  private String classifyDomain(String domain) {
    if (domain.endsWith("xiaohongshu.com")
        || domain.endsWith("zhihu.com")
        || domain.endsWith("douban.com")) return "COMMUNITY";
    if (domain.endsWith("dianping.com")
        || domain.endsWith("meituan.com")
        || domain.endsWith("amap.com")) return "LOCAL_PLATFORM";
    return "UNKNOWN";
  }

  private String defaultGrade(String ownership) {
    return "OWNED".equals(ownership) ? "A" : "D";
  }

  private String normalize(String value, Set<String> accepted, String fallback) {
    if (value == null || value.isBlank()) return fallback;
    String normalized = value.trim().toUpperCase();
    return accepted.contains(normalized) ? normalized : fallback;
  }

  private void merchant(Identity identity, UUID merchantId) {
    Long count = jdbc.queryForObject(
        "select count(*) from merchants "
            + "where id=:merchantId and tenant_id=:tenantId and not deleted",
        base(identity, merchantId), Long.class);
    if (count == null || count == 0) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "商家不存在");
    }
  }

  private MapSqlParameterSource base(Identity identity, UUID merchantId) {
    return new MapSqlParameterSource()
        .addValue("tenantId", identity.tenantId())
        .addValue("merchantId", merchantId);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON serialization failed", e);
    }
  }

  private ApiException bad(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }
}
