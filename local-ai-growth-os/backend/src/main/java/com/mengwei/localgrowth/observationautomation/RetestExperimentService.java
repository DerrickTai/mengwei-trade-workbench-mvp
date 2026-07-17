package com.mengwei.localgrowth.observationautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.audit.AuditService;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetestExperimentService {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final RetestComparisonService comparisonService;
  private final AuditService audit;

  public RetestExperimentService(
      NamedParameterJdbcTemplate jdbc,
      ObjectMapper mapper,
      RetestComparisonService comparisonService,
      AuditService audit) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.comparisonService = comparisonService;
    this.audit = audit;
  }

  public List<Map<String, Object>> list(Identity identity, UUID merchantId) {
    merchant(identity, merchantId);
    return jdbc.queryForList(
        """
        select e.*,
          (select r.comparison_snapshot
             from geo_retest_runs r
            where r.experiment_id=e.id and r.tenant_id=e.tenant_id
              and r.phase='RETEST'
            order by r.run_at desc limit 1) as latest_comparison
          from geo_retest_experiments e
         where e.tenant_id=:tenantId and e.merchant_id=:merchantId
         order by e.created_at desc
        """,
        base(identity, merchantId));
  }

  @Transactional
  public Map<String, Object> create(
      Identity identity,
      UUID merchantId,
      RetestExperimentController.ExperimentInput input) {
    merchant(identity, merchantId);
    Map<String, Object> baseline =
        snapshot(identity, merchantId, input.baselineSnapshotId());
    task(identity, merchantId, input.interventionTaskId());

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    int repetitions = input.repetitions() == null ? 3 : input.repetitions();
    List<String> channels = input.collectionChannels() == null
        ? List.of()
        : input.collectionChannels().stream().map(Enum::name).toList();

    jdbc.update(
        """
        insert into geo_retest_experiments(
          id,tenant_id,merchant_id,name,baseline_snapshot_id,intervention_task_id,
          status,question_ids,ai_platforms,collection_channels,location_text,
          repetitions,comparison_options,created_at,updated_at,created_by,updated_by,version
        ) values(
          :id,:tenantId,:merchantId,:name,:baselineId,:taskId,
          'PLANNED',cast(:questionIds as jsonb),cast(:platforms as jsonb),
          cast(:channels as jsonb),:location,:repetitions,cast(:options as jsonb),
          :now,:now,:userId,:userId,0
        )
        """,
        base(identity, merchantId)
            .addValue("id", id)
            .addValue("name", input.name().trim())
            .addValue("baselineId", input.baselineSnapshotId())
            .addValue("taskId", input.interventionTaskId())
            .addValue("questionIds", json(
                input.questionIds() == null ? List.of() : input.questionIds()))
            .addValue("platforms", json(
                input.aiPlatforms() == null ? List.of() : input.aiPlatforms()))
            .addValue("channels", json(channels))
            .addValue("location", input.locationText())
            .addValue("repetitions", repetitions)
            .addValue("options", json(
                input.comparisonOptions() == null
                    ? Map.of()
                    : input.comparisonOptions()))
            .addValue("now", now)
            .addValue("userId", identity.userId()));

    jdbc.update(
        """
        insert into geo_retest_runs(
          id,tenant_id,merchant_id,experiment_id,phase,diagnosis_snapshot_id,
          sample_count,metrics_snapshot,source_snapshot,comparison_snapshot,
          run_at,created_at,created_by
        ) values(
          :id,:tenantId,:merchantId,:experimentId,'BASELINE',:snapshotId,
          :sampleCount,cast(:metrics as jsonb),cast(:sources as jsonb),'{}',
          :now,:now,:userId
        )
        """,
        base(identity, merchantId)
            .addValue("id", UUID.randomUUID())
            .addValue("experimentId", id)
            .addValue("snapshotId", input.baselineSnapshotId())
            .addValue("sampleCount", baseline.get("observation_count"))
            .addValue("metrics", jsonValue(baseline.get("metric_snapshot")))
            .addValue("sources", jsonValue(baseline.get("source_snapshot")))
            .addValue("now", now)
            .addValue("userId", identity.userId()));

    audit.log(identity.tenantId(), identity.userId(),
        "GEO_RETEST_EXPERIMENT_CREATED", "GeoRetestExperiment", id,
        "创建M5复测实验");
    return experiment(identity, merchantId, id);
  }

  @Transactional
  public Map<String, Object> compare(
      Identity identity,
      UUID merchantId,
      UUID experimentId,
      UUID retestSnapshotId,
      Double volatilityInput) {
    Map<String, Object> experiment =
        experiment(identity, merchantId, experimentId);
    UUID baselineId =
        UUID.fromString(String.valueOf(experiment.get("baseline_snapshot_id")));
    Map<String, Object> baseline = snapshot(identity, merchantId, baselineId);
    Map<String, Object> retest = snapshot(identity, merchantId, retestSnapshotId);

    assertComparable(experiment, baseline, retest);

    boolean directCitation = hasDirectCitation(
        identity, merchantId, experiment.get("intervention_task_id"));
    boolean relatedNewSource =
        listOfMaps(retest.get("source_snapshot")).size()
            > listOfMaps(baseline.get("source_snapshot")).size();
    double volatility = volatilityInput == null
        ? 0d
        : Math.max(0d, volatilityInput);

    RetestComparisonService.Comparison comparison = comparisonService.compare(
        objectMap(baseline.get("metric_snapshot")),
        objectMap(retest.get("metric_snapshot")),
        volatility,
        directCitation,
        relatedNewSource);

    OffsetDateTime now = OffsetDateTime.now();
    jdbc.update(
        """
        insert into geo_retest_runs(
          id,tenant_id,merchant_id,experiment_id,phase,diagnosis_snapshot_id,
          sample_count,metrics_snapshot,source_snapshot,comparison_snapshot,
          result_status,attribution_level,run_at,created_at,created_by
        ) values(
          :id,:tenantId,:merchantId,:experimentId,'RETEST',:snapshotId,
          :sampleCount,cast(:metrics as jsonb),cast(:sources as jsonb),
          cast(:comparison as jsonb),:status,:attribution,:now,:now,:userId
        )
        """,
        base(identity, merchantId)
            .addValue("id", UUID.randomUUID())
            .addValue("experimentId", experimentId)
            .addValue("snapshotId", retestSnapshotId)
            .addValue("sampleCount", retest.get("observation_count"))
            .addValue("metrics", jsonValue(retest.get("metric_snapshot")))
            .addValue("sources", jsonValue(retest.get("source_snapshot")))
            .addValue("comparison", json(comparison))
            .addValue("status", comparison.status())
            .addValue("attribution", comparison.attributionLevel())
            .addValue("now", now)
            .addValue("userId", identity.userId()));

    jdbc.update(
        """
        update geo_retest_experiments set
          status='COMPLETED',updated_at=:now,
          updated_by=:userId,version=version+1
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId)
            .addValue("id", experimentId)
            .addValue("now", now)
            .addValue("userId", identity.userId()));

    return Map.of(
        "experimentId", experimentId,
        "baselineSnapshotId", baselineId,
        "retestSnapshotId", retestSnapshotId,
        "comparison", comparison);
  }

  private void assertComparable(
      Map<String, Object> experiment,
      Map<String, Object> baseline,
      Map<String, Object> retest) {
    List<String> required = strings(experiment.get("ai_platforms"));
    if (required.isEmpty()) return;

    List<String> baselinePlatforms =
        strings(objectMap(baseline.get("config_snapshot")).get("targetPlatforms"));
    List<String> retestPlatforms =
        strings(objectMap(retest.get("config_snapshot")).get("targetPlatforms"));
    if (!baselinePlatforms.containsAll(required)
        || !retestPlatforms.containsAll(required)) {
      throw bad("RETEST_SCOPE_MISMATCH",
          "基线和复测快照的平台口径不满足实验配置");
    }
  }

  private boolean hasDirectCitation(
      Identity identity, UUID merchantId, Object taskId) {
    if (taskId == null) return false;
    Long count = jdbc.queryForObject(
        """
        select count(*)
          from geo_publication_citation_events e
          join geo_tracked_publications p
            on p.id=e.publication_id and p.tenant_id=e.tenant_id
         where e.tenant_id=:tenantId and e.merchant_id=:merchantId
           and p.optimization_task_id=:taskId
           and e.direct_citation=true
        """,
        base(identity, merchantId).addValue("taskId", taskId),
        Long.class);
    return count != null && count > 0;
  }

  private Map<String, Object> experiment(
      Identity identity, UUID merchantId, UUID id) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select * from geo_retest_experiments
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId).addValue("id", id));
    if (rows.isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "复测实验不存在");
    }
    return rows.getFirst();
  }

  private Map<String, Object> snapshot(
      Identity identity, UUID merchantId, UUID id) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select * from geo_diagnosis_snapshots
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """,
        base(identity, merchantId).addValue("id", id));
    if (rows.isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "诊断快照不存在");
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
      throw bad("TASK_NOT_FOUND", "整改任务不存在");
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

  private List<Map<String, Object>> listOfMaps(Object value) {
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
    } catch (Exception e) {
      return List.of();
    }
  }

  private String jsonValue(Object value) {
    if (value == null) return "{}";
    return value instanceof String text ? text : json(value);
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
