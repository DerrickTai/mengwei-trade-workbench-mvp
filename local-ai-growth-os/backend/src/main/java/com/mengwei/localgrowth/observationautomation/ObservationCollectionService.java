package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.audit.AuditService;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.observation.AiObservationController;
import com.mengwei.localgrowth.observation.AiObservationService;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
public class ObservationCollectionService {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final AuditService audit;
  private final CollectorRegistry registry;
  private final ObservationHeuristics heuristics;
  private final AiObservationService observations;
  private final SourceEvidenceService evidence;

  public ObservationCollectionService(
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper mapper,
      AuditService audit,
      CollectorRegistry registry,
      ObservationHeuristics heuristics,
      AiObservationService observations,
      SourceEvidenceService evidence) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.audit = audit;
    this.registry = registry;
    this.heuristics = heuristics;
    this.observations = observations;
    this.evidence = evidence;
  }

  public List<Map<String, Object>> configs(Identity identity, UUID merchantId) {
    merchant(identity, merchantId);
    return jdbc.queryForList(
        """
        select id,name,ai_platform,collection_channel,provider_code,api_base_url,
               model_name,secret_env_name,web_search_enabled,location_country,
               location_text,request_options,auto_create_draft,enabled,schedule_cron,
               created_at,updated_at,version
          from geo_collector_configs
         where tenant_id=:tenantId and merchant_id=:merchantId
         order by created_at desc
        """,
        base(identity, merchantId));
  }

  @Transactional
  public Map<String, Object> createConfig(
      Identity identity,
      UUID merchantId,
      ObservationCollectionController.CollectorConfigInput input) {
    merchant(identity, merchantId);
    validateConfig(input);
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.update(
        """
        insert into geo_collector_configs(
          id,tenant_id,merchant_id,name,ai_platform,collection_channel,provider_code,
          api_base_url,model_name,secret_env_name,web_search_enabled,location_country,
          location_text,request_options,auto_create_draft,enabled,schedule_cron,
          created_at,updated_at,created_by,updated_by,version
        ) values(
          :id,:tenantId,:merchantId,:name,:platform,:channel,:provider,
          :baseUrl,:model,:secretEnv,:webSearch,:country,:location,
          cast(:options as jsonb),:autoDraft,:enabled,:cron,
          :now,:now,:userId,:userId,0
        )
        """,
        configParams(identity, merchantId, id, input, now));
    audit.log(identity.tenantId(), identity.userId(),
        "GEO_COLLECTOR_CONFIG_CREATED", "GeoCollectorConfig", id,
        "新增官方AI采集配置；仅保存密钥环境变量名称");
    return config(identity, merchantId, id);
  }

  @Transactional
  public Map<String, Object> updateConfig(
      Identity identity,
      UUID merchantId,
      UUID configId,
      ObservationCollectionController.CollectorConfigInput input) {
    config(identity, merchantId, configId);
    validateConfig(input);
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.update(
        """
        update geo_collector_configs set
          name=:name,ai_platform=:platform,collection_channel=:channel,
          provider_code=:provider,api_base_url=:baseUrl,model_name=:model,
          secret_env_name=:secretEnv,web_search_enabled=:webSearch,
          location_country=:country,location_text=:location,
          request_options=cast(:options as jsonb),auto_create_draft=:autoDraft,
          enabled=:enabled,schedule_cron=:cron,updated_at=:now,
          updated_by=:userId,version=version+1
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        configParams(identity, merchantId, configId, input, now));
    return config(identity, merchantId, configId);
  }

  public Map<String, Object> run(
      Identity identity,
      UUID merchantId,
      ObservationCollectionController.RunInput input) {
    return run(identity, merchantId, input, "MANUAL");
  }

  /** Internal callers may preserve the same staging pipeline while declaring their trigger. */
  public Map<String, Object> run(
      Identity identity,
      UUID merchantId,
      ObservationCollectionController.RunInput input,
      String triggerType) {
    if (!Set.of("MANUAL", "RETEST").contains(triggerType)) {
      throw bad("INVALID_TRIGGER_TYPE", "不支持的采集触发来源");
    }
    merchant(identity, merchantId);
    List<Map<String, Object>> configRows =
        selectedConfigs(identity, merchantId, input.collectorConfigIds());
    List<Map<String, Object>> questionRows =
        selectedQuestions(identity, merchantId, input.questionIds());
    if (configRows.size() != input.collectorConfigIds().size()) {
      throw bad("COLLECTOR_CONFIG_NOT_FOUND", "部分采集配置不存在或未启用");
    }
    if (questionRows.size() != input.questionIds().size()) {
      throw bad("QUESTION_NOT_FOUND", "部分消费者问题不存在或未启用");
    }

    UUID runId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.update(
        """
        insert into geo_collection_runs(
          id,tenant_id,merchant_id,collector_config_ids,question_ids,
          trigger_type,status,input_snapshot,total_count,success_count,
          failure_count,started_at,created_at,created_by
        ) values(
          :id,:tenantId,:merchantId,cast(:configs as jsonb),cast(:questions as jsonb),
          :triggerType,'RUNNING',cast(:input as jsonb),0,0,0,:now,:now,:userId
        )
        """,
        base(identity, merchantId)
            .addValue("id", runId)
            .addValue("configs", json(input.collectorConfigIds()))
            .addValue("questions", json(input.questionIds()))
            .addValue("input", json(Map.of(
                "autoCreateDraft", Boolean.TRUE.equals(input.autoCreateDraft()))))
            .addValue("triggerType", triggerType)
            .addValue("now", now)
            .addValue("userId", identity.userId()));

    List<String> brandTerms = strategyTerms(identity, merchantId, "brand_terms");
    List<String> ambiguousTerms = strategyTerms(identity, merchantId, "ambiguous_terms");
    List<UUID> resultIds = new ArrayList<>();
    int success = 0;
    int failure = 0;

    for (Map<String, Object> cfg : configRows) {
      for (Map<String, Object> question : questionRows) {
        UUID resultId = executeOne(
            identity, merchantId, runId, cfg, question,
            brandTerms, ambiguousTerms,
            Boolean.TRUE.equals(input.autoCreateDraft()));
        resultIds.add(resultId);
        if ("SUCCESS".equals(result(identity, merchantId, resultId).get("status"))) {
          success++;
        } else {
          failure++;
        }
      }
    }

    int total = success + failure;
    String status = failure == 0 ? "COMPLETED" : success == 0 ? "FAILED" : "PARTIAL";
    jdbc.update(
        """
        update geo_collection_runs set
          status=:status,total_count=:total,success_count=:success,
          failure_count=:failure,finished_at=:finished
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId)
            .addValue("id", runId)
            .addValue("status", status)
            .addValue("total", total)
            .addValue("success", success)
            .addValue("failure", failure)
            .addValue("finished", OffsetDateTime.now()));
    audit.log(identity.tenantId(), identity.userId(),
        "GEO_COLLECTION_RUN_FINISHED", "GeoCollectionRun", runId,
        "官方AI采集完成，成功=" + success + "，失败=" + failure);

    Map<String, Object> output = new LinkedHashMap<>(run(identity, merchantId, runId));
    output.put("resultIds", resultIds);
    return output;
  }

  private UUID executeOne(
      Identity identity,
      UUID merchantId,
      UUID runId,
      Map<String, Object> cfg,
      Map<String, Object> question,
      List<String> brandTerms,
      List<String> ambiguousTerms,
      boolean forceAutoDraft) {
    CollectorResponse response;
    String secretEnv = text(cfg.get("secret_env_name"));
    String apiKey = System.getenv(secretEnv);
    if (apiKey == null || apiKey.isBlank()) {
      response = CollectorResponse.failed(
          null, 0, "SECRET_NOT_CONFIGURED",
          "Required API secret environment variable is not configured");
    } else {
      try {
        CollectorRequest request = new CollectorRequest(
            merchantId,
            uuidOrNull(question.get("storefront_id")),
            UUID.fromString(text(question.get("id"))),
            text(question.get("question_text")),
            text(cfg.get("ai_platform")),
            CollectionChannel.valueOf(text(cfg.get("collection_channel"))),
            text(cfg.get("provider_code")),
            text(cfg.get("api_base_url")),
            text(cfg.get("model_name")),
            apiKey,
            bool(cfg.get("web_search_enabled")),
            text(cfg.get("location_country")),
            text(cfg.get("location_text")),
            objectMap(cfg.get("request_options")));
        response = registry.require(request.providerCode()).collect(request);
      } catch (Exception e) {
        response = CollectorResponse.failed(
            null, 0, "COLLECTOR_EXECUTION_FAILED", safeMessage(e));
      }
    }

    ObservationHeuristics.Extraction extraction = response.success()
        ? heuristics.extract(response.rawAnswer(), brandTerms, ambiguousTerms)
        : new ObservationHeuristics.Extraction(false, false, null, Map.of());

    UUID resultId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.update(
        """
        insert into geo_collection_results(
          id,tenant_id,merchant_id,run_id,collector_config_id,storefront_id,
          question_id,ai_platform,collection_channel,provider_code,provider_model,
          provider_request_id,location_text,observed_at,latency_ms,status,
          raw_answer,raw_response,cited_sources,extraction_snapshot,
          merchant_mentioned,merchant_recommended,recommendation_rank,
          fact_check_status,verification_status,promotion_status,response_sha256,
          error_code,error_message,created_at,updated_at,created_by,updated_by,version
        ) values(
          :id,:tenantId,:merchantId,:runId,:configId,:storefrontId,
          :questionId,:platform,:channel,:provider,:providerModel,
          :requestId,:location,:observedAt,:latency,:status,
          :answer,cast(:rawResponse as jsonb),cast(:sources as jsonb),
          cast(:extraction as jsonb),:mentioned,:recommended,:rank,
          'NOT_CHECKED','DRAFT','PENDING',:sha256,:errorCode,:errorMessage,
          :now,:now,:userId,:userId,0
        )
        """,
        base(identity, merchantId)
            .addValue("id", resultId)
            .addValue("runId", runId)
            .addValue("configId", cfg.get("id"))
            .addValue("storefrontId", question.get("storefront_id"))
            .addValue("questionId", question.get("id"))
            .addValue("platform", cfg.get("ai_platform"))
            .addValue("channel", cfg.get("collection_channel"))
            .addValue("provider", cfg.get("provider_code"))
            .addValue("providerModel",
                response.model() == null ? cfg.get("model_name") : response.model())
            .addValue("requestId", response.requestId())
            .addValue("location", cfg.get("location_text"))
            .addValue("observedAt", response.observedAt())
            .addValue("latency", response.latencyMs())
            .addValue("status", response.success() ? "SUCCESS" : "FAILED")
            .addValue("answer", response.rawAnswer())
            .addValue("rawResponse", response.rawResponse() == null
                ? "{}"
                : response.rawResponse().toString())
            .addValue("sources", json(response.citedSources()))
            .addValue("extraction", json(extraction.details()))
            .addValue("mentioned", extraction.merchantMentioned())
            .addValue("recommended", extraction.merchantRecommended())
            .addValue("rank", extraction.recommendationRank())
            .addValue("sha256", sha256(response.rawAnswer()))
            .addValue("errorCode", response.errorCode())
            .addValue("errorMessage", response.errorMessage())
            .addValue("now", now)
            .addValue("userId", identity.userId()));

    if (response.success()) {
      evidence.linkResultSources(identity, merchantId, resultId, response.citedSources());
      if (forceAutoDraft || bool(cfg.get("auto_create_draft"))) {
        promote(identity, merchantId, resultId);
      }
    }
    return resultId;
  }

  @Transactional
  public Map<String, Object> promote(
      Identity identity,
      UUID merchantId,
      UUID resultId) {
    Map<String, Object> staged = result(identity, merchantId, resultId);
    if (!"SUCCESS".equals(staged.get("status"))) {
      throw bad("RESULT_NOT_SUCCESSFUL", "失败结果不能创建观察");
    }
    if ("REJECTED".equals(staged.get("promotion_status"))) {
      throw bad("RESULT_REJECTED", "已拒绝结果不能创建观察");
    }
    if (staged.get("promoted_observation_id") != null) return staged;

    AiObservationController.ObservationInput input =
        new AiObservationController.ObservationInput(
            uuidOrNull(staged.get("storefront_id")),
            UUID.fromString(text(staged.get("question_id"))),
            text(staged.get("ai_platform")),
            text(staged.get("collection_channel")),
            time(staged.get("observed_at")),
            text(staged.get("location_text")),
            text(staged.get("raw_answer")),
            null,
            bool(staged.get("merchant_mentioned")),
            bool(staged.get("merchant_recommended")),
            integer(staged.get("recommendation_rank")),
            "NOT_CHECKED",
            mapList(staged.get("cited_sources")),
            List.of(),
            "DRAFT",
            false,
            "自动采集 staging result=" + resultId);

    Map<String, Object> created =
        observations.createObservation(identity, merchantId, input);
    UUID observationId = UUID.fromString(text(created.get("id")));

    jdbc.update(
        """
        update ai_observations set
          collection_result_id=:resultId,provider_code=:provider,
          provider_model=:model,provider_request_id=:requestId,
          response_sha256=:sha256
         where id=:observationId and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId)
            .addValue("resultId", resultId)
            .addValue("observationId", observationId)
            .addValue("provider", staged.get("provider_code"))
            .addValue("model", staged.get("provider_model"))
            .addValue("requestId", staged.get("provider_request_id"))
            .addValue("sha256", staged.get("response_sha256")));

    int updated = jdbc.update(
        """
        update geo_collection_results set
          promotion_status='PROMOTED',promoted_observation_id=:observationId,
          updated_at=:now,updated_by=:userId,version=version+1
         where id=:resultId and tenant_id=:tenantId and merchant_id=:merchantId
           and promotion_status='PENDING'
        """,
        base(identity, merchantId)
            .addValue("resultId", resultId)
            .addValue("observationId", observationId)
            .addValue("now", OffsetDateTime.now())
            .addValue("userId", identity.userId()));
    if (updated != 1) {
      throw bad("RESULT_ALREADY_PROCESSED", "采集结果已被处理");
    }

    evidence.copyLinksToObservation(identity, merchantId, resultId, observationId);
    audit.log(identity.tenantId(), identity.userId(),
        "GEO_COLLECTION_RESULT_PROMOTED", "GeoCollectionResult", resultId,
        "自动采集结果创建为DRAFT观察");
    return result(identity, merchantId, resultId);
  }

  @Transactional
  public Map<String, Object> reject(
      Identity identity,
      UUID merchantId,
      UUID resultId) {
    Map<String, Object> staged = result(identity, merchantId, resultId);
    if (staged.get("promoted_observation_id") != null) {
      throw bad("RESULT_ALREADY_PROMOTED", "已创建观察的结果不能拒绝");
    }
    jdbc.update(
        """
        update geo_collection_results set
          promotion_status='REJECTED',updated_at=:now,
          updated_by=:userId,version=version+1
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId)
            .addValue("id", resultId)
            .addValue("now", OffsetDateTime.now())
            .addValue("userId", identity.userId()));
    return result(identity, merchantId, resultId);
  }

  public List<Map<String, Object>> runs(Identity identity, UUID merchantId) {
    merchant(identity, merchantId);
    return jdbc.queryForList(
        """
        select * from geo_collection_runs
         where tenant_id=:tenantId and merchant_id=:merchantId
         order by created_at desc
        """,
        base(identity, merchantId));
  }

  public List<Map<String, Object>> results(
      Identity identity, UUID merchantId, UUID runId) {
    run(identity, merchantId, runId);
    return jdbc.queryForList(
        """
        select r.*,q.question_text
          from geo_collection_results r
          join consumer_questions q
            on q.id=r.question_id and q.tenant_id=r.tenant_id
         where r.tenant_id=:tenantId and r.merchant_id=:merchantId
           and r.run_id=:runId
         order by r.created_at
        """,
        base(identity, merchantId).addValue("runId", runId));
  }

  private List<Map<String, Object>> selectedConfigs(
      Identity identity, UUID merchantId, List<UUID> ids) {
    return jdbc.queryForList(
        """
        select * from geo_collector_configs
         where tenant_id=:tenantId and merchant_id=:merchantId
           and enabled=true and id in (:ids)
        """,
        base(identity, merchantId).addValue("ids", ids));
  }

  private List<Map<String, Object>> selectedQuestions(
      Identity identity, UUID merchantId, List<UUID> ids) {
    return jdbc.queryForList(
        """
        select * from consumer_questions
         where tenant_id=:tenantId and merchant_id=:merchantId
           and enabled=true and not deleted and id in (:ids)
        """,
        base(identity, merchantId).addValue("ids", ids));
  }

  private Map<String, Object> config(
      Identity identity, UUID merchantId, UUID id) {
    return one(
        "select * from geo_collector_configs "
            + "where id=:id and tenant_id=:tenantId and merchant_id=:merchantId",
        base(identity, merchantId).addValue("id", id));
  }

  private Map<String, Object> run(
      Identity identity, UUID merchantId, UUID id) {
    return one(
        "select * from geo_collection_runs "
            + "where id=:id and tenant_id=:tenantId and merchant_id=:merchantId",
        base(identity, merchantId).addValue("id", id));
  }

  private Map<String, Object> result(
      Identity identity, UUID merchantId, UUID id) {
    return one(
        "select * from geo_collection_results "
            + "where id=:id and tenant_id=:tenantId and merchant_id=:merchantId",
        base(identity, merchantId).addValue("id", id));
  }

  private List<String> strategyTerms(
      Identity identity, UUID merchantId, String column) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select " + column + " from geo_strategy_configs "
            + "where tenant_id=:tenantId and merchant_id=:merchantId",
        base(identity, merchantId));
    return rows.isEmpty() ? List.of() : strings(rows.getFirst().get(column));
  }

  private void validateConfig(
      ObservationCollectionController.CollectorConfigInput input) {
    if (input.collectionChannel() != CollectionChannel.OFFICIAL_API
        && input.collectionChannel() != CollectionChannel.AUTHORIZED_SEARCH_API) {
      throw bad("INVALID_AUTOMATED_CHANNEL",
          "自动 collector 只允许 OFFICIAL_API 或 AUTHORIZED_SEARCH_API");
    }
    registry.require(input.providerCode());
    if (!input.secretEnvName().matches("[A-Z][A-Z0-9_]{2,119}")) {
      throw bad("INVALID_SECRET_ENV_NAME", "密钥环境变量名称格式不正确");
    }
    URI endpoint;
    try {
      endpoint = URI.create(input.apiBaseUrl());
    } catch (IllegalArgumentException e) {
      throw bad("INVALID_PROVIDER_URL", "Provider URL 格式不正确");
    }
    if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
      throw bad("INVALID_PROVIDER_URL", "正式 provider URL 必须使用 HTTPS");
    }
    String provider = input.providerCode().trim().toUpperCase();
    String host = endpoint.getHost().toLowerCase();
    boolean approved = ("DEEPSEEK_OFFICIAL".equals(provider) && "api.deepseek.com".equals(host))
        || ("DOUBAO_OFFICIAL".equals(provider) && "ark.cn-beijing.volces.com".equals(host));
    if (!approved) {
      throw bad("UNAPPROVED_PROVIDER_URL", "Provider URL 不在当前官方地址白名单内");
    }
  }

  private MapSqlParameterSource configParams(
      Identity identity,
      UUID merchantId,
      UUID id,
      ObservationCollectionController.CollectorConfigInput input,
      OffsetDateTime now) {
    return base(identity, merchantId)
        .addValue("id", id)
        .addValue("name", input.name().trim())
        .addValue("platform", input.aiPlatform().trim().toUpperCase())
        .addValue("channel", input.collectionChannel().name())
        .addValue("provider", input.providerCode().trim().toUpperCase())
        .addValue("baseUrl", input.apiBaseUrl().trim())
        .addValue("model", input.modelName().trim())
        .addValue("secretEnv", input.secretEnvName().trim())
        .addValue("webSearch", Boolean.TRUE.equals(input.webSearchEnabled()))
        .addValue("country", input.locationCountry())
        .addValue("location", input.locationText())
        .addValue("options", json(
            input.requestOptions() == null ? Map.of() : input.requestOptions()))
        .addValue("autoDraft",
            input.autoCreateDraft() == null || input.autoCreateDraft())
        .addValue("enabled", input.enabled() == null || input.enabled())
        .addValue("cron", input.scheduleCron())
        .addValue("now", now)
        .addValue("userId", identity.userId());
  }

  private void merchant(Identity identity, UUID merchantId) {
    Long count = jdbc.queryForObject(
        "select count(*) from merchants "
            + "where id=:merchantId and tenant_id=:tenantId and not deleted",
        base(identity, merchantId), Long.class);
    if (count == null || count == 0) {
      throw new ApiException(
          HttpStatus.NOT_FOUND, "NOT_FOUND", "商家不存在或无权访问");
    }
  }

  private Map<String, Object> one(
      String sql, MapSqlParameterSource params) {
    List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
    if (rows.isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "记录不存在");
    }
    return rows.getFirst();
  }

  private MapSqlParameterSource base(
      Identity identity, UUID merchantId) {
    return new MapSqlParameterSource()
        .addValue("tenantId", identity.tenantId())
        .addValue("merchantId", merchantId);
  }

  private ApiException bad(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON serialization failed", e);
    }
  }

  private Map<String, Object> objectMap(Object value) {
    if (value == null) return Map.of();
    try {
      return mapper.convertValue(
          value, new TypeReference<Map<String, Object>>() {});
    } catch (Exception ignored) {
      try {
        return mapper.readValue(
            String.valueOf(value),
            new TypeReference<Map<String, Object>>() {});
      } catch (Exception e) {
        return Map.of();
      }
    }
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

  private List<String> strings(Object value) {
    if (value == null) return List.of();
    try {
      return mapper.convertValue(value, new TypeReference<List<String>>() {});
    } catch (Exception ignored) {
      try {
        return mapper.readValue(
            String.valueOf(value), new TypeReference<List<String>>() {});
      } catch (Exception e) {
        return List.of();
      }
    }
  }

  private boolean bool(Object value) {
    return value instanceof Boolean b
        ? b
        : Boolean.parseBoolean(String.valueOf(value));
  }

  private UUID uuidOrNull(Object value) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    return UUID.fromString(String.valueOf(value));
  }

  private Integer integer(Object value) {
    if (value == null) return null;
    return Integer.valueOf(String.valueOf(value));
  }

  private OffsetDateTime time(Object value) {
    if (value instanceof OffsetDateTime offset) return offset;
    try {
      return value == null
          ? OffsetDateTime.now()
          : OffsetDateTime.parse(String.valueOf(value));
    } catch (Exception e) {
      return OffsetDateTime.now();
    }
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String sha256(String value) {
    if (value == null) return null;
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private String safeMessage(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message.length() > 1000 ? message.substring(0, 1000) : message;
  }
}
