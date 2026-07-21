package com.mengwei.localgrowth.observationautomation.retest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;

@Repository
public class RetestAutomationRepository {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public RetestAutomationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional
  public UUID createExperiment(CreateExperiment command) {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.update("""
        insert into geo_retest_experiments(
          id,tenant_id,merchant_id,name,baseline_snapshot_id,intervention_task_id,status,
          question_ids,ai_platforms,collection_channels,location_text,repetitions,
          comparison_options,automation_enabled,timezone,schedule_template,
          sample_count_per_cell,collector_config_ids,success_criteria,stop_policy,
          max_api_calls,max_cost_micros,automation_state,baseline_quality,
          created_at,updated_at,created_by,updated_by,version
        ) values(
          :id,:tenantId,:merchantId,:name,:baselineSnapshotId,:interventionTaskId,'PLANNED',
          cast(:questionIds as jsonb),'[]','[]',:locationText,:sampleCount,
          '{}',:automationEnabled,:timezone,cast(:scheduleTemplate as jsonb),
          :sampleCount,cast(:collectorConfigIds as jsonb),cast(:successCriteria as jsonb),
          cast(:stopPolicy as jsonb),:maxApiCalls,:maxCostMicros,:automationState,:baselineQuality,
          :now,:now,:actorId,:actorId,0
        )
        """, base(command.tenantId(), command.merchantId())
        .addValue("id", id).addValue("name", command.name())
        .addValue("baselineSnapshotId", command.baselineSnapshotId())
        .addValue("interventionTaskId", command.interventionTaskId())
        .addValue("questionIds", json(command.allQuestionIds()))
        .addValue("locationText", command.locationText())
        .addValue("sampleCount", command.sampleCountPerCell())
        .addValue("automationEnabled", command.automationEnabled())
        .addValue("timezone", command.timezone())
        .addValue("scheduleTemplate", json(command.scheduleTemplate()))
        .addValue("collectorConfigIds", json(command.collectorConfigIds()))
        .addValue("successCriteria", json(command.successCriteria()))
        .addValue("stopPolicy", json(command.stopPolicy()))
        .addValue("maxApiCalls", command.maxApiCalls())
        .addValue("maxCostMicros", command.maxCostMicros())
        .addValue("automationState", command.baselineSnapshotId() == null ? "NEEDS_BASELINE" : "READY")
        .addValue("baselineQuality", command.baselineQuality())
        .addValue("now", now).addValue("actorId", command.actorId()));

    insertQuestions(id, command, now);
    return id;
  }

  private void insertQuestions(UUID experimentId, CreateExperiment command, OffsetDateTime now) {
    for (UUID questionId : command.targetQuestionIds()) {
      insertQuestion(experimentId, command, questionId, RetestCohort.TARGET, now);
    }
    for (UUID questionId : command.controlQuestionIds()) {
      insertQuestion(experimentId, command, questionId, RetestCohort.CONTROL, now);
    }
  }

  private void insertQuestion(UUID experimentId, CreateExperiment command, UUID questionId,
      RetestCohort cohort, OffsetDateTime now) {
    jdbc.update("""
        insert into geo_retest_experiment_questions(
          id,tenant_id,merchant_id,experiment_id,question_id,cohort,weight,enabled,created_at,created_by
        ) values(:id,:tenantId,:merchantId,:experimentId,:questionId,:cohort,1,true,:now,:actorId)
        """, base(command.tenantId(), command.merchantId())
        .addValue("id", UUID.randomUUID()).addValue("experimentId", experimentId)
        .addValue("questionId", questionId).addValue("cohort", cohort.name())
        .addValue("now", now).addValue("actorId", command.actorId()));
  }

  public void insertSchedulePoints(UUID tenantId, UUID merchantId, UUID experimentId,
      UUID actorId, List<RetestSchedulePlanner.PlannedPoint> points) {
    OffsetDateTime now = OffsetDateTime.now();
    for (RetestSchedulePlanner.PlannedPoint point : points) {
      jdbc.update("""
          insert into geo_retest_schedule_points(
            id,tenant_id,merchant_id,experiment_id,phase,day_offset,sequence_no,due_at,
            status,attempt_count,max_attempts,created_at,updated_at,created_by,updated_by,version
          ) values(
            :id,:tenantId,:merchantId,:experimentId,'RETEST',:dayOffset,:sequenceNo,:dueAt,
            'PLANNED',0,3,:now,:now,:actorId,:actorId,0
          ) on conflict (tenant_id,experiment_id,phase,day_offset) do nothing
          """, base(tenantId, merchantId).addValue("id", UUID.randomUUID())
          .addValue("experimentId", experimentId).addValue("dayOffset", point.dayOffset())
          .addValue("sequenceNo", point.sequenceNo()).addValue("dueAt", point.dueAt())
          .addValue("now", now).addValue("actorId", actorId));
    }
    refreshNextDue(tenantId, merchantId, experimentId);
  }

  @Transactional
  public List<ClaimedPoint> claimDue(String workerId, int limit, int leaseSeconds) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select p.id,p.tenant_id,p.merchant_id,p.experiment_id,p.day_offset,p.sequence_no
          from geo_retest_schedule_points p
          join geo_retest_experiments e on e.id=p.experiment_id and e.tenant_id=p.tenant_id
         where p.status='PLANNED' and p.due_at<=now()
           and (p.lease_until is null or p.lease_until<now())
           and e.automation_enabled=true and e.automation_state='ACTIVE'
         order by p.due_at,p.sequence_no
         for update skip locked
         limit :limit
        """, new MapSqlParameterSource("limit", limit));
    List<ClaimedPoint> claimed = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      UUID id = (UUID) row.get("id");
      int updated = jdbc.update("""
          update geo_retest_schedule_points
             set status='RUNNING',lease_owner=:workerId,
                 lease_until=now()+(:leaseSeconds || ' seconds')::interval,
                 started_at=coalesce(started_at,now()),attempt_count=attempt_count+1,
                 updated_at=now(),version=version+1
           where id=:id and status='PLANNED'
          """, new MapSqlParameterSource().addValue("id", id)
          .addValue("workerId", workerId).addValue("leaseSeconds", leaseSeconds));
      if (updated == 1) {
        claimed.add(new ClaimedPoint(id, (UUID) row.get("tenant_id"),
            (UUID) row.get("merchant_id"), (UUID) row.get("experiment_id"),
            ((Number) row.get("day_offset")).intValue(),
            ((Number) row.get("sequence_no")).intValue()));
      }
    }
    return List.copyOf(claimed);
  }

  /** Claims a point for an interactive request. Terminal points are idempotent replays. */
  @Transactional
  public ManualClaim claimManual(UUID tenantId, UUID merchantId, UUID experimentId, UUID pointId,
      String leaseOwner, int leaseSeconds) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select id,tenant_id,merchant_id,experiment_id,day_offset,sequence_no,status,lease_until
          from geo_retest_schedule_points
         where id=:pointId and tenant_id=:tenantId and merchant_id=:merchantId
           and experiment_id=:experimentId
         for update
        """, base(tenantId, merchantId).addValue("experimentId", experimentId)
        .addValue("pointId", pointId));
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "复测节点不存在或无权访问");
    Map<String, Object> row = rows.getFirst();
    String status = String.valueOf(row.get("status"));
    if ("COMPLETED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status)) {
      return new ManualClaim(point(row), true, false);
    }
    if (!"PLANNED".equals(status) && !"RUNNING".equals(status)) {
      throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_POINT_NOT_EXECUTABLE", "复测节点当前不可执行");
    }
    OffsetDateTime leaseUntil = time(row.get("lease_until"));
    if ("RUNNING".equals(status) && leaseUntil != null && leaseUntil.isAfter(OffsetDateTime.now())) {
      throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_POINT_LEASED", "复测节点正在由其他执行者处理");
    }
    boolean resumed = "RUNNING".equals(status);
    jdbc.update("""
        update geo_retest_schedule_points
           set status='RUNNING',lease_owner=:leaseOwner,
               lease_until=now()+(:leaseSeconds || ' seconds')::interval,
               started_at=coalesce(started_at,now()),attempt_count=attempt_count+1,
               updated_at=now(),version=version+1
         where id=:pointId and tenant_id=:tenantId and merchant_id=:merchantId
           and experiment_id=:experimentId
        """, base(tenantId, merchantId).addValue("experimentId", experimentId)
        .addValue("pointId", pointId).addValue("leaseOwner", leaseOwner)
        .addValue("leaseSeconds", leaseSeconds));
    return new ManualClaim(point(row), false, resumed);
  }

  public List<ExecutionCell> executionCells(ClaimedPoint point, int hardLimit) {
    return jdbc.query("""
        select q.question_id,q.cohort,c.id collector_config_id,
               generate_series(1,e.sample_count_per_cell) repetition_no
          from geo_retest_experiments e
          join geo_retest_experiment_questions q
            on q.experiment_id=e.id and q.tenant_id=e.tenant_id and q.enabled=true
          join geo_collector_configs c
            on c.tenant_id=e.tenant_id and c.merchant_id=e.merchant_id
           and c.id in (select value::uuid from jsonb_array_elements_text(e.collector_config_ids))
           and c.enabled=true
         where e.id=:experimentId and e.tenant_id=:tenantId and e.merchant_id=:merchantId
         order by q.cohort,q.question_id,c.id,repetition_no
         limit :limit
        """, base(point.tenantId(), point.merchantId())
        .addValue("experimentId", point.experimentId()).addValue("limit", hardLimit),
        (rs, i) -> new ExecutionCell(UUID.fromString(rs.getString("question_id")),
            RetestCohort.valueOf(rs.getString("cohort")),
            UUID.fromString(rs.getString("collector_config_id")),
            rs.getInt("repetition_no")));
  }

  /** Reserve unique sampling cells before a provider call. Expired reservations can be reclaimed. */
  @Transactional
  public List<ExecutionCell> reserveCells(ClaimedPoint point, String leaseOwner,
      int leaseSeconds, int hardLimit) {
    List<ExecutionCell> reserved = new ArrayList<>();
    for (ExecutionCell cell : executionCells(point, hardLimit)) {
      List<ExecutionCell> claimed = jdbc.query("""
          insert into geo_retest_samples(
            id,tenant_id,merchant_id,experiment_id,schedule_point_id,question_id,cohort,
            collector_config_id,repetition_no,status,verification_status,execution_state,
            execution_lease_owner,execution_lease_until,created_at,updated_at
          ) values(
            :id,:tenantId,:merchantId,:experimentId,:pointId,:questionId,:cohort,
            :collectorConfigId,:repetitionNo,'SKIPPED','DRAFT','RESERVED',
            :leaseOwner,now()+(:leaseSeconds || ' seconds')::interval,now(),now()
          ) on conflict (tenant_id,schedule_point_id,question_id,collector_config_id,repetition_no)
          do update set execution_state='RESERVED',execution_lease_owner=:leaseOwner,
              execution_lease_until=now()+(:leaseSeconds || ' seconds')::interval,updated_at=now()
            where geo_retest_samples.execution_state='RESERVED'
              and geo_retest_samples.execution_lease_until < now()
          returning question_id,cohort,collector_config_id,repetition_no
          """, base(point.tenantId(), point.merchantId()).addValue("id", UUID.randomUUID())
          .addValue("experimentId", point.experimentId()).addValue("pointId", point.id())
          .addValue("questionId", cell.questionId()).addValue("cohort", cell.cohort().name())
          .addValue("collectorConfigId", cell.collectorConfigId()).addValue("repetitionNo", cell.repetitionNo())
          .addValue("leaseOwner", leaseOwner).addValue("leaseSeconds", leaseSeconds),
          (rs, i) -> new ExecutionCell(UUID.fromString(rs.getString("question_id")),
              RetestCohort.valueOf(rs.getString("cohort")),
              UUID.fromString(rs.getString("collector_config_id")), rs.getInt("repetition_no")));
      reserved.addAll(claimed);
    }
    return List.copyOf(reserved);
  }

  public void saveSample(ClaimedPoint point, ExecutionCell cell,
      RetestObservationExecutionResult result) {
    OffsetDateTime now = OffsetDateTime.now();
    jdbc.update("""
        insert into geo_retest_samples(
          id,tenant_id,merchant_id,experiment_id,schedule_point_id,question_id,cohort,
          collector_config_id,repetition_no,collection_run_id,collection_result_id,observation_id,
          status,verification_status,ai_platform,provider_code,provider_model,collection_channel,
          location_text,context_sha256,merchant_mentioned,merchant_recommended,recommendation_rank,
          any_citation,direct_publication_citation,latency_ms,cost_micros,error_code,error_message,
          observed_at,created_at,updated_at
        ) values(
          :id,:tenantId,:merchantId,:experimentId,:pointId,:questionId,:cohort,
          :collectorConfigId,:repetitionNo,:collectionRunId,:collectionResultId,:observationId,
          :status,:verificationStatus,:aiPlatform,:providerCode,:providerModel,:channel,
          :location,:contextSha,:mentioned,:recommended,:rank,:anyCitation,:directCitation,
          :latencyMs,:costMicros,:errorCode,:errorMessage,:observedAt,:now,:now
        ) on conflict (tenant_id,schedule_point_id,question_id,collector_config_id,repetition_no)
          do update set collection_run_id=excluded.collection_run_id,
            collection_result_id=excluded.collection_result_id,observation_id=excluded.observation_id,
            status=excluded.status,verification_status=excluded.verification_status,
            merchant_mentioned=excluded.merchant_mentioned,
            merchant_recommended=excluded.merchant_recommended,
            recommendation_rank=excluded.recommendation_rank,
            any_citation=excluded.any_citation,
            direct_publication_citation=excluded.direct_publication_citation,
            latency_ms=excluded.latency_ms,cost_micros=excluded.cost_micros,
            error_code=excluded.error_code,error_message=excluded.error_message,
            observed_at=excluded.observed_at,execution_state='COMPLETED',
            execution_lease_owner=null,execution_lease_until=null,updated_at=excluded.updated_at
        """, base(point.tenantId(), point.merchantId()).addValue("id", UUID.randomUUID())
        .addValue("experimentId", point.experimentId()).addValue("pointId", point.id())
        .addValue("questionId", cell.questionId()).addValue("cohort", cell.cohort().name())
        .addValue("collectorConfigId", cell.collectorConfigId())
        .addValue("repetitionNo", cell.repetitionNo())
        .addValue("collectionRunId", result.collectionRunId())
        .addValue("collectionResultId", result.collectionResultId())
        .addValue("observationId", result.observationId())
        .addValue("status", result.success() ? "SUCCESS" : "FAILED")
        .addValue("verificationStatus", result.verificationStatus() == null ? "DRAFT" : result.verificationStatus())
        .addValue("aiPlatform", result.aiPlatform()).addValue("providerCode", result.providerCode())
        .addValue("providerModel", result.providerModel()).addValue("channel", result.collectionChannel())
        .addValue("location", result.locationText()).addValue("contextSha", result.contextSha256())
        .addValue("mentioned", result.merchantMentioned()).addValue("recommended", result.merchantRecommended())
        .addValue("rank", result.recommendationRank()).addValue("anyCitation", result.anyCitation())
        .addValue("directCitation", result.directPublicationCitation())
        .addValue("latencyMs", result.latencyMs()).addValue("costMicros", result.costMicros())
        .addValue("errorCode", result.errorCode()).addValue("errorMessage", result.errorMessage())
        .addValue("observedAt", result.observedAt()).addValue("now", now));
  }

  public List<RetestSampleSignal> sampleSignals(UUID tenantId, UUID merchantId,
      UUID pointId, RetestCohort cohort) {
    return jdbc.query("""
        select status,merchant_mentioned,merchant_recommended,recommendation_rank,
               any_citation,direct_publication_citation,context_sha256
          from geo_retest_samples
         where tenant_id=:tenantId and merchant_id=:merchantId
           and schedule_point_id=:pointId and cohort=:cohort
        """, base(tenantId, merchantId).addValue("pointId", pointId)
        .addValue("cohort", cohort.name()),
        (rs, i) -> new RetestSampleSignal("SUCCESS".equals(rs.getString("status")),
            rs.getBoolean("merchant_mentioned"),rs.getBoolean("merchant_recommended"),
            (Integer) rs.getObject("recommendation_rank"),rs.getBoolean("any_citation"),
            rs.getBoolean("direct_publication_citation"),rs.getString("context_sha256")));
  }

  public List<RetestSampleSignal> allSampleSignals(UUID tenantId, UUID merchantId, UUID pointId) {
    return jdbc.query("""
        select status,merchant_mentioned,merchant_recommended,recommendation_rank,
               any_citation,direct_publication_citation,context_sha256
          from geo_retest_samples
         where tenant_id=:tenantId and merchant_id=:merchantId and schedule_point_id=:pointId
        """, base(tenantId, merchantId).addValue("pointId", pointId),
        (rs, i) -> new RetestSampleSignal("SUCCESS".equals(rs.getString("status")),
            rs.getBoolean("merchant_mentioned"),rs.getBoolean("merchant_recommended"),
            (Integer) rs.getObject("recommendation_rank"),rs.getBoolean("any_citation"),
            rs.getBoolean("direct_publication_citation"),rs.getString("context_sha256")));
  }

  public void completePoint(ClaimedPoint point, boolean partial, String errorSummary) {
    jdbc.update("""
        update geo_retest_schedule_points set status=:status,finished_at=now(),
          lease_owner=null,lease_until=null,error_summary=:errorSummary,updated_at=now(),version=version+1
         where id=:id
        """, new MapSqlParameterSource().addValue("id", point.id())
        .addValue("status", partial ? "PARTIAL" : "COMPLETED")
        .addValue("errorSummary", errorSummary));
    refreshNextDue(point.tenantId(), point.merchantId(), point.experimentId());
  }

  public void finishManual(ClaimedPoint point, int requested, int successful, int failed,
      String errorSummary) {
    String status = successful == 0 && requested > 0 ? "FAILED" : failed > 0 ? "PARTIAL" : "COMPLETED";
    jdbc.update("""
        update geo_retest_schedule_points set status=:status,finished_at=now(),
          lease_owner=null,lease_until=null,error_summary=:errorSummary,updated_at=now(),version=version+1
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId and experiment_id=:experimentId
        """, base(point.tenantId(), point.merchantId()).addValue("id", point.id())
        .addValue("experimentId", point.experimentId()).addValue("status", status)
        .addValue("errorSummary", errorSummary));
    refreshNextDue(point.tenantId(), point.merchantId(), point.experimentId());
  }

  public SampleSummary sampleSummary(UUID tenantId, UUID merchantId, UUID pointId) {
    Map<String, Object> row = jdbc.queryForMap("""
        select count(*) requested,
               count(*) filter (where status='SUCCESS') successful,
               count(*) filter (where status='FAILED') failed,
               count(*) filter (where status='SUCCESS' and verification_status='DRAFT') valid
          from geo_retest_samples
         where tenant_id=:tenantId and merchant_id=:merchantId and schedule_point_id=:pointId
        """, base(tenantId, merchantId).addValue("pointId", pointId));
    return new SampleSummary(number(row.get("requested")), number(row.get("successful")),
        number(row.get("failed")), number(row.get("valid")));
  }

  public String pointStatus(UUID tenantId, UUID merchantId, UUID experimentId, UUID pointId) {
    List<String> rows = jdbc.query("""
        select status from geo_retest_schedule_points
         where id=:pointId and tenant_id=:tenantId and merchant_id=:merchantId and experiment_id=:experimentId
        """, base(tenantId, merchantId).addValue("experimentId", experimentId).addValue("pointId", pointId),
        (rs, i) -> rs.getString(1));
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "复测节点不存在或无权访问");
    return rows.getFirst();
  }

  public void failPoint(ClaimedPoint point, String errorSummary) {
    jdbc.update("""
        update geo_retest_schedule_points
           set status=case when attempt_count>=max_attempts then 'FAILED' else 'PLANNED' end,
               lease_owner=null,lease_until=null,error_summary=:errorSummary,
               updated_at=now(),version=version+1
         where id=:id
        """, new MapSqlParameterSource().addValue("id", point.id())
        .addValue("errorSummary", errorSummary));
  }

  public List<Map<String, Object>> listExperiments(UUID tenantId, UUID merchantId) {
    return jdbc.queryForList("""
        select e.*,
          (select count(*) from geo_retest_schedule_points p where p.experiment_id=e.id) schedule_count,
          (select count(*) from geo_retest_schedule_points p where p.experiment_id=e.id and p.status='COMPLETED') completed_schedule_count
          from geo_retest_experiments e
         where e.tenant_id=:tenantId and e.merchant_id=:merchantId
         order by e.created_at desc
        """, base(tenantId, merchantId));
  }

  public List<Map<String, Object>> report(UUID tenantId, UUID merchantId, UUID experimentId) {
    return jdbc.queryForList("""
        select p.sequence_no,p.day_offset,p.due_at,p.status,m.cohort,m.metric_name,
               m.metric_value,m.ci_low,m.ci_high,m.volatility_score,m.valid_sample_count,
               m.failed_sample_count,m.context_drift
          from geo_retest_schedule_points p
          left join geo_retest_metric_snapshots m on m.schedule_point_id=p.id and m.tenant_id=p.tenant_id
         where p.tenant_id=:tenantId and p.merchant_id=:merchantId and p.experiment_id=:experimentId
         order by p.sequence_no,m.cohort,m.metric_name
        """, base(tenantId, merchantId).addValue("experimentId", experimentId));
  }

  public void setAutomationState(UUID tenantId, UUID merchantId, UUID experimentId,
      UUID actorId, String state, boolean enabled) {
    jdbc.update("""
        update geo_retest_experiments set automation_state=:state,automation_enabled=:enabled,
          updated_at=now(),updated_by=:actorId,version=version+1
         where id=:id and tenant_id=:tenantId and merchant_id=:merchantId
        """, base(tenantId, merchantId).addValue("id", experimentId)
        .addValue("actorId", actorId).addValue("state", state).addValue("enabled", enabled));
  }

  private void refreshNextDue(UUID tenantId, UUID merchantId, UUID experimentId) {
    jdbc.update("""
        update geo_retest_experiments e set next_due_at=(
          select min(p.due_at) from geo_retest_schedule_points p
           where p.experiment_id=e.id and p.status='PLANNED'
        ),updated_at=now(),version=version+1
         where e.id=:id and e.tenant_id=:tenantId and e.merchant_id=:merchantId
        """, base(tenantId, merchantId).addValue("id", experimentId));
  }

  private MapSqlParameterSource base(UUID tenantId, UUID merchantId) {
    return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("merchantId", merchantId);
  }

  private ClaimedPoint point(Map<String, Object> row) {
    return new ClaimedPoint((UUID) row.get("id"), (UUID) row.get("tenant_id"),
        (UUID) row.get("merchant_id"), (UUID) row.get("experiment_id"),
        ((Number) row.get("day_offset")).intValue(), ((Number) row.get("sequence_no")).intValue());
  }
  private OffsetDateTime time(Object value) {
    if (value instanceof OffsetDateTime result) return result;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
    return null;
  }
  private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot serialize JSON", e); }
  }

  public record CreateExperiment(UUID tenantId, UUID merchantId, UUID actorId, String name,
      UUID baselineSnapshotId, UUID interventionTaskId, List<UUID> targetQuestionIds,
      List<UUID> controlQuestionIds, List<UUID> collectorConfigIds, String locationText,
      String timezone, int sampleCountPerCell, boolean automationEnabled,
      String baselineQuality, Map<String,Object> scheduleTemplate,
      Map<String,Object> successCriteria, Map<String,Object> stopPolicy,
      Integer maxApiCalls, Long maxCostMicros) {
    public List<UUID> allQuestionIds() {
      List<UUID> ids = new ArrayList<>(targetQuestionIds);
      ids.addAll(controlQuestionIds);
      return List.copyOf(ids);
    }
  }
  public record ClaimedPoint(UUID id, UUID tenantId, UUID merchantId, UUID experimentId,
      int dayOffset, int sequenceNo) {}
  public record ExecutionCell(UUID questionId, RetestCohort cohort,
      UUID collectorConfigId, int repetitionNo) {}
  public record ManualClaim(ClaimedPoint point, boolean alreadyExecuted, boolean recoveredLease) {}
  public record SampleSummary(int requested, int successful, int failed, int valid) {}
}
