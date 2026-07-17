package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.audit.AuditService;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationTrackingService {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final UrlCanonicalizer canonicalizer;
  private final AuditService audit;

  public PublicationTrackingService(
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper mapper,
      UrlCanonicalizer canonicalizer,
      AuditService audit) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.canonicalizer = canonicalizer;
    this.audit = audit;
  }

  public List<Map<String, Object>> list(Identity identity, UUID merchantId) {
    merchant(identity, merchantId);
    return jdbc.queryForList(
        """
        select p.*,
          count(e.id) filter(where e.direct_citation) as direct_citation_count,
          count(e.id) as all_match_count,
          max(e.last_seen_at) as last_cited_at
          from geo_tracked_publications p
          left join geo_publication_citation_events e
            on e.publication_id=p.id and e.tenant_id=p.tenant_id
         where p.tenant_id=:tenantId and p.merchant_id=:merchantId
         group by p.id
         order by p.created_at desc
        """,
        base(identity, merchantId));
  }

  @Transactional
  public Map<String, Object> create(
      Identity identity,
      UUID merchantId,
      PublicationTrackingController.PublicationInput input) {
    merchant(identity, merchantId);
    task(identity, merchantId, input.optimizationTaskId());
    UrlCanonicalizer.NormalizedUrl normalized =
        canonicalizer.normalize(input.url());
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    String status = input.status() == null || input.status().isBlank()
        ? "ACTIVE"
        : input.status().trim().toUpperCase();

    jdbc.update(
        """
        insert into geo_tracked_publications(
          id,tenant_id,merchant_id,optimization_task_id,platform,title,
          normalized_url,domain,published_at,content_sha256,status,metadata,
          created_at,updated_at,created_by,updated_by,version
        ) values(
          :id,:tenantId,:merchantId,:taskId,:platform,:title,
          :url,:domain,:publishedAt,:contentHash,:status,cast(:metadata as jsonb),
          :now,:now,:userId,:userId,0
        )
        """,
        base(identity, merchantId)
            .addValue("id", id)
            .addValue("taskId", input.optimizationTaskId())
            .addValue("platform", input.platform().trim().toUpperCase())
            .addValue("title", input.title().trim())
            .addValue("url", normalized.url())
            .addValue("domain", normalized.domain())
            .addValue("publishedAt", input.publishedAt())
            .addValue("contentHash", input.contentSha256())
            .addValue("status", status)
            .addValue("metadata", json(
                input.metadata() == null ? Map.of() : input.metadata()))
            .addValue("now", now)
            .addValue("userId", identity.userId()));

    audit.log(identity.tenantId(), identity.userId(),
        "GEO_TRACKED_PUBLICATION_CREATED", "GeoTrackedPublication", id,
        "登记待追踪发布作品");
    return publication(identity, merchantId, id);
  }

  @Transactional
  public Map<String, Object> scan(Identity identity, UUID merchantId) {
    merchant(identity, merchantId);
    List<Map<String, Object>> publications = list(identity, merchantId);
    List<Candidate> candidates = candidates(identity, merchantId);
    int direct = 0;
    int weak = 0;

    for (Map<String, Object> publication : publications) {
      UUID publicationId = UUID.fromString(text(publication.get("id")));
      String url = text(publication.get("normalized_url"));
      String canonical = text(publication.get("canonical_url"));
      String domain = text(publication.get("domain"));
      String hash = text(publication.get("content_sha256"));

      for (Candidate candidate : candidates) {
        Match match = match(url, canonical, domain, hash, candidate);
        if (match == null) continue;
        upsertEvent(identity, merchantId, publicationId, candidate, match);
        if (match.direct()) direct++; else weak++;
      }
    }

    return Map.of(
        "directMatchesProcessed", direct,
        "domainOnlyMatchesProcessed", weak,
        "notice",
        "DOMAIN_ONLY 不算作品直接引用；匹配表示可观测证据，不证明因果。");
  }

  public List<Map<String, Object>> events(Identity identity, UUID merchantId) {
    merchant(identity, merchantId);
    return jdbc.queryForList(
        """
        select e.*,p.title,p.normalized_url as publication_url,q.question_text
          from geo_publication_citation_events e
          join geo_tracked_publications p
            on p.id=e.publication_id and p.tenant_id=e.tenant_id
          left join consumer_questions q
            on q.id=e.question_id and q.tenant_id=e.tenant_id
         where e.tenant_id=:tenantId and e.merchant_id=:merchantId
         order by e.last_seen_at desc
        """,
        base(identity, merchantId));
  }

  private List<Candidate> candidates(Identity identity, UUID merchantId) {
    List<Candidate> output = new ArrayList<>();
    List<Map<String, Object>> observations = jdbc.queryForList(
        """
        select id,question_id,ai_platform,observed_at,cited_sources
          from ai_observations
         where tenant_id=:tenantId and merchant_id=:merchantId
           and not deleted and demo=false
        """,
        base(identity, merchantId));
    for (Map<String, Object> row : observations) {
      append(output,
          uuid(row.get("id")), null, uuid(row.get("question_id")),
          text(row.get("ai_platform")), time(row.get("observed_at")),
          mapList(row.get("cited_sources")));
    }

    List<Map<String, Object>> staged = jdbc.queryForList(
        """
        select id,question_id,ai_platform,observed_at,cited_sources
          from geo_collection_results
         where tenant_id=:tenantId and merchant_id=:merchantId
           and status='SUCCESS'
        """,
        base(identity, merchantId));
    for (Map<String, Object> row : staged) {
      append(output,
          null, uuid(row.get("id")), uuid(row.get("question_id")),
          text(row.get("ai_platform")), time(row.get("observed_at")),
          mapList(row.get("cited_sources")));
    }
    return output;
  }

  private void append(
      List<Candidate> target,
      UUID observationId,
      UUID resultId,
      UUID questionId,
      String platform,
      OffsetDateTime seenAt,
      List<Map<String, Object>> sources) {
    for (Map<String, Object> source : sources) {
      Object raw = source.get("url");
      if (raw == null) continue;
      try {
        UrlCanonicalizer.NormalizedUrl normalized =
            canonicalizer.normalize(String.valueOf(raw));
        target.add(new Candidate(
            observationId,
            resultId,
            questionId,
            platform,
            normalized.url(),
            normalized.domain(),
            text(source.get("canonicalUrl")),
            text(source.get("finalUrl")),
            text(source.get("contentSha256")),
            seenAt == null ? OffsetDateTime.now() : seenAt));
      } catch (Exception ignored) {
      }
    }
  }

  private Match match(
      String publicationUrl,
      String publicationCanonical,
      String publicationDomain,
      String publicationHash,
      Candidate candidate) {
    if (!publicationUrl.isBlank() && publicationUrl.equals(candidate.url())) {
      return new Match("EXACT_URL", true, new BigDecimal("1.0000"));
    }
    if (!publicationUrl.isBlank() && publicationUrl.equals(candidate.finalUrl())) {
      return new Match("REDIRECTED_URL", true, new BigDecimal("0.9800"));
    }
    if (!publicationCanonical.isBlank()
        && publicationCanonical.equals(candidate.canonicalUrl())) {
      return new Match("CANONICAL_URL", true, new BigDecimal("0.9500"));
    }
    if (!publicationHash.isBlank()
        && publicationHash.equals(candidate.contentHash())) {
      return new Match("CONTENT_FINGERPRINT", true, new BigDecimal("0.9000"));
    }
    if (!publicationDomain.isBlank()
        && publicationDomain.equals(candidate.domain())) {
      return new Match("DOMAIN_ONLY", false, new BigDecimal("0.2000"));
    }
    return null;
  }

  private void upsertEvent(
      Identity identity,
      UUID merchantId,
      UUID publicationId,
      Candidate candidate,
      Match match) {
    MapSqlParameterSource params = base(identity, merchantId)
        .addValue("id", UUID.randomUUID())
        .addValue("publicationId", publicationId)
        .addValue("observationId", candidate.observationId())
        .addValue("resultId", candidate.resultId())
        .addValue("questionId", candidate.questionId())
        .addValue("platform", candidate.platform())
        .addValue("matchType", match.type())
        .addValue("confidence", match.confidence())
        .addValue("direct", match.direct())
        .addValue("seenAt", candidate.seenAt())
        .addValue("evidence", json(Map.of(
            "citedUrl", candidate.url(),
            "citedDomain", candidate.domain())))
        .addValue("now", OffsetDateTime.now())
        .addValue("userId", identity.userId());

    String conflict = candidate.observationId() != null
        ? "(tenant_id,publication_id,observation_id,match_type) "
            + "where observation_id is not null"
        : "(tenant_id,publication_id,collection_result_id,match_type) "
            + "where collection_result_id is not null";

    jdbc.update(
        """
        insert into geo_publication_citation_events(
          id,tenant_id,merchant_id,publication_id,observation_id,
          collection_result_id,question_id,ai_platform,match_type,
          confidence,direct_citation,citation_count,first_seen_at,last_seen_at,
          evidence,created_at,updated_at,created_by,updated_by
        ) values(
          :id,:tenantId,:merchantId,:publicationId,:observationId,
          :resultId,:questionId,:platform,:matchType,:confidence,:direct,
          1,:seenAt,:seenAt,cast(:evidence as jsonb),:now,:now,:userId,:userId
        )
        on conflict %s do update set
          citation_count=geo_publication_citation_events.citation_count+1,
          last_seen_at=greatest(
            geo_publication_citation_events.last_seen_at,
            excluded.last_seen_at
          ),
          updated_at=excluded.updated_at,
          updated_by=excluded.updated_by
        """.formatted(conflict),
        params);
  }

  private Map<String, Object> publication(
      Identity identity, UUID merchantId, UUID id) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select * from geo_tracked_publications
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId).addValue("id", id));
    if (rows.isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "发布作品不存在");
    }
    return rows.getFirst();
  }

  private void task(Identity identity, UUID merchantId, UUID taskId) {
    if (taskId == null) return;
    Long count = jdbc.queryForObject(
        """
        select count(*) from optimization_tasks
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId).addValue("id", taskId),
        Long.class);
    if (count == null || count == 0) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "TASK_NOT_FOUND", "整改任务不存在");
    }
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

  private List<Map<String, Object>> mapList(Object value) {
    if (value == null) return List.of();
    try {
      return mapper.convertValue(
          value, new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception ignored) {
      try {
        return mapper.readValue(
            String.valueOf(value),
            new TypeReference<List<Map<String, Object>>>() {});
      } catch (Exception e) {
        return List.of();
      }
    }
  }

  private UUID uuid(Object value) {
    return value == null ? null : UUID.fromString(String.valueOf(value));
  }

  private OffsetDateTime time(Object value) {
    if (value instanceof OffsetDateTime offset) return offset;
    if (value == null) return null;
    try {
      return OffsetDateTime.parse(String.valueOf(value));
    } catch (Exception e) {
      return null;
    }
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON serialization failed", e);
    }
  }

  private record Candidate(
      UUID observationId,
      UUID resultId,
      UUID questionId,
      String platform,
      String url,
      String domain,
      String canonicalUrl,
      String finalUrl,
      String contentHash,
      OffsetDateTime seenAt) {}

  private record Match(
      String type,
      boolean direct,
      BigDecimal confidence) {}
}
