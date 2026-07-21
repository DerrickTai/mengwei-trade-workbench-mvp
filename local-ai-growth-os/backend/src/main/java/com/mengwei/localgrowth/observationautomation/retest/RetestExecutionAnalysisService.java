package com.mengwei.localgrowth.observationautomation.retest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Persists deterministic, reviewable post-execution statistics. It never invokes a provider. */
@Service
public class RetestExecutionAnalysisService {
  private static final int MINIMUM_VALID_SAMPLES = 3;
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final RetestAutomationRepository repository;
  private final RetestStatisticsCalculator statistics = new RetestStatisticsCalculator();
  private final RetestAttributionEngine attribution = new RetestAttributionEngine();

  public RetestExecutionAnalysisService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
      RetestAutomationRepository repository) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.repository = repository;
  }

  /** Idempotently persists the snapshots, DRAFT assessment and execution decision for one point. */
  public AnalysisResult analyze(UUID tenantId, UUID merchantId, UUID experimentId, UUID pointId,
      UUID actorId) {
    Map<String, Object> existing = existingSummary(tenantId, merchantId, pointId);
    if (existing != null) return readPersisted(tenantId, merchantId, experimentId, pointId, existing);

    Map<String, Object> experiment = experiment(tenantId, merchantId, experimentId);
    RetestWindowMetrics target = statistics.aggregate(repository.sampleSignals(tenantId, merchantId, pointId, RetestCohort.TARGET));
    RetestWindowMetrics control = statistics.aggregate(repository.sampleSignals(tenantId, merchantId, pointId, RetestCohort.CONTROL));
    RetestWindowMetrics overall = statistics.aggregate(repository.allSampleSignals(tenantId, merchantId, pointId));
    Counts targetCounts = counts(tenantId, merchantId, pointId, "TARGET");
    Counts controlCounts = counts(tenantId, merchantId, pointId, "CONTROL");
    Counts overallCounts = counts(tenantId, merchantId, pointId, null);
    Map<String, Object> baseline = baselineMetrics(tenantId, merchantId, experiment.get("baseline_snapshot_id"));
    Map<String, Object> targetView = persistMetrics(tenantId, merchantId, experimentId, pointId,
        "TARGET", target, targetCounts, baseline, previousMetric(tenantId, merchantId, experimentId, pointId, "TARGET"));
    Map<String, Object> controlView = persistMetrics(tenantId, merchantId, experimentId, pointId,
        "CONTROL", control, controlCounts, Map.of(), previousMetric(tenantId, merchantId, experimentId, pointId, "CONTROL"));
    Map<String, Object> overallView = persistMetrics(tenantId, merchantId, experimentId, pointId,
        "OVERALL", overall, overallCounts, baseline, previousMetric(tenantId, merchantId, experimentId, pointId, "OVERALL"));

    Delta delta = delta(targetView, controlView, baseline, comparable(experiment, baseline, target));
    persistTargetComparison(tenantId, merchantId, pointId, delta);
    targetView.put("baselineDelta", delta.baselineDelta); targetView.put("previousPeriodDelta", delta.previousPeriodDelta);
    targetView.put("controlDelta", delta.controlDelta); targetView.put("adjustedDelta", delta.adjustedDelta);
    Map<String, Object> assessment = persistAssessment(tenantId, merchantId, experimentId, pointId,
        target, targetCounts, delta, hasDirectCitation(tenantId, merchantId, pointId), actorId);
    Decision decision = evaluateStop(experiment, tenantId, merchantId, experimentId, pointId,
        targetCounts, delta, assessment);
    persistSummary(tenantId, merchantId, experimentId, pointId, overallCounts, decision, actorId);
    applyDecision(tenantId, merchantId, experimentId, actorId, decision);
    return new AnalysisResult(targetView, controlView, overallView, delta.view(), assessment, decision.view(), false);
  }

  private void persistTargetComparison(UUID tenantId, UUID merchantId, UUID pointId, Delta delta) {
    jdbc.update("""
        update geo_retest_metric_snapshots set control_delta=:controlDelta,adjusted_delta=:adjustedDelta
         where tenant_id=:tenantId and merchant_id=:merchantId and schedule_point_id=:pointId
           and cohort='TARGET' and metric_name='BRAND_MENTION_RATE'
        """, base(tenantId, merchantId).addValue("pointId", pointId)
        .addValue("controlDelta", delta.controlDelta).addValue("adjustedDelta", delta.adjustedDelta));
  }

  private Map<String, Object> persistMetrics(UUID tenantId, UUID merchantId, UUID experimentId, UUID pointId,
      String cohort, RetestWindowMetrics metrics, Counts counts, Map<String, Object> baseline,
      Double previousMentionRate) {
    Map<String, Object> view = metricView(metrics, counts);
    Double baselineRate = rate(baseline, "mentionRate");
    Double mentionRate = value(view, RetestMetricName.BRAND_MENTION_RATE);
    Double baselineDelta = comparableValue(mentionRate, baselineRate);
    Double previousDelta = comparableValue(mentionRate, previousMentionRate);
    view.put("baselineDelta", baselineDelta);
    view.put("previousPeriodDelta", previousDelta);
    view.put("controlDelta", null);
    view.put("adjustedDelta", null);
    for (RetestMetricName metric : RetestMetricName.values()) {
      MetricEstimate estimate = metrics.metric(metric);
      Double metricValue = counts.valid == 0 ? null : estimate.value();
      Double metricBaselineDelta = metric == RetestMetricName.BRAND_MENTION_RATE ? baselineDelta : null;
      Double metricPreviousDelta = metric == RetestMetricName.BRAND_MENTION_RATE ? previousDelta : null;
      jdbc.update("""
          insert into geo_retest_metric_snapshots(
            id,tenant_id,merchant_id,experiment_id,schedule_point_id,cohort,metric_name,
            numerator,denominator,metric_value,ci_low,ci_high,volatility_score,valid_sample_count,
            failed_sample_count,context_drift,metadata,calculated_at,created_at,
            requested_sample_count,successful_sample_count,baseline_delta,previous_period_delta,
            control_delta,adjusted_delta,sample_sufficiency
          ) values(:id,:tenantId,:merchantId,:experimentId,:pointId,:cohort,:metricName,
            :numerator,:denominator,:metricValue,:ciLow,:ciHigh,:volatility,:valid,:failed,
            :contextDrift,cast(:metadata as jsonb),:now,:now,:requested,:successful,
            :baselineDelta,:previousDelta,null,null,:sufficiency)
          on conflict (tenant_id,schedule_point_id,cohort,metric_name) do nothing
          """, base(tenantId, merchantId).addValue("id", UUID.randomUUID()).addValue("experimentId", experimentId)
          .addValue("pointId", pointId).addValue("cohort", cohort).addValue("metricName", metric.name())
          .addValue("numerator", counts.valid == 0 ? null : BigDecimal.valueOf(estimate.numerator()))
          .addValue("denominator", counts.valid).addValue("metricValue", metricValue)
          .addValue("ciLow", counts.valid == 0 ? null : estimate.ciLow())
          .addValue("ciHigh", counts.valid == 0 ? null : estimate.ciHigh())
          .addValue("volatility", counts.valid == 0 ? null : estimate.volatilityScore())
          .addValue("valid", counts.valid).addValue("failed", counts.failed)
          .addValue("contextDrift", metrics.contextDrift()).addValue("metadata", json(view))
          .addValue("now", OffsetDateTime.now()).addValue("requested", counts.requested)
          .addValue("successful", counts.successful).addValue("baselineDelta", metricBaselineDelta)
          .addValue("previousDelta", metricPreviousDelta)
          .addValue("sufficiency", counts.valid >= MINIMUM_VALID_SAMPLES ? "SUFFICIENT" : "INSUFFICIENT"));
    }
    return view;
  }

  private Map<String, Object> persistAssessment(UUID tenantId, UUID merchantId, UUID experimentId, UUID pointId,
      RetestWindowMetrics target, Counts counts, Delta delta, boolean directCitation, UUID actorId) {
    List<Map<String, Object>> old = jdbc.queryForList("""
        select * from geo_retest_attribution_assessments where tenant_id=:tenantId and merchant_id=:merchantId
          and experiment_id=:experimentId and schedule_point_id=:pointId order by created_at desc limit 1
        """, base(tenantId, merchantId).addValue("experimentId", experimentId).addValue("pointId", pointId));
    if (!old.isEmpty()) return assessmentView(old.getFirst());
    boolean enough = counts.valid >= MINIMUM_VALID_SAMPLES && delta.baselineDelta != null && delta.comparable;
    RetestResultStatus status = !enough ? RetestResultStatus.INSUFFICIENT_SAMPLE
        : delta.adjustedDelta != null && delta.adjustedDelta >= 0.05 ? RetestResultStatus.IMPROVED
        : RetestResultStatus.STABLE;
    RetestComparison comparison = new RetestComparison(RetestMetricName.BRAND_MENTION_RATE, status,
        delta.baselineRate == null ? 0 : delta.baselineRate, delta.currentRate == null ? 0 : delta.currentRate,
        null, null, delta.baselineDelta == null ? 0 : delta.baselineDelta, delta.controlDelta,
        delta.adjustedDelta == null ? 0 : delta.adjustedDelta, counts.valid, maxVolatility(target),
        target.contextDrift(), delta.controlDelta != null);
    RetestAttributionEngine.Context context = new RetestAttributionEngine.Context(comparison, directCitation,
        false, 0, !delta.comparable, false, 0.10);
    AttributionAssessment draft = attribution.assess(context);
    if (!enough || !delta.comparable) draft = new AttributionAssessment(RetestAttributionLevel.INSUFFICIENT_EVIDENCE,
        draft.evidenceScore(), append(draft.reasonCodes(), !delta.comparable ? "NOT_COMPARABLE" : "INSUFFICIENT_SAMPLE"),
        "当前样本或发布前基线不可比较，暂不判断优化效果。", true);
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("baselineSampleCount", delta.baselineSampleCount);
    evidence.put("currentSampleCount", counts.valid);
    evidence.put("targetDelta", delta.baselineDelta);
    evidence.put("controlDelta", delta.controlDelta);
    evidence.put("adjustedDelta", delta.adjustedDelta);
    evidence.put("directPublicationCitation", directCitation);
    evidence.put("directCitationEvidence", directCitation ? "EXACT_OR_CANONICAL_URL_ONLY" : List.of());
    evidence.put("providerModelComparable", delta.comparable);
    evidence.put("sampleSufficiency", enough ? "SUFFICIENT" : "INSUFFICIENT");
    evidence.put("downgradeReasons", draft.reasonCodes());
    jdbc.update("""
        insert into geo_retest_attribution_assessments(
          id,tenant_id,merchant_id,experiment_id,schedule_point_id,result_status,attribution_level,
          evidence_score,review_status,primary_metric,target_before,target_after,control_before,control_after,
          target_delta,control_delta,adjusted_delta,sample_count,consecutive_improved_cycles,context_drift,
          direct_citation_verified,reason_codes,safe_summary,evidence_snapshot,created_at,updated_at
        ) values(:id,:tenantId,:merchantId,:experimentId,:pointId,:status,:level,:score,'DRAFT',
          'BRAND_MENTION_RATE',:before,:after,null,null,:targetDelta,:controlDelta,:adjustedDelta,
          :sampleCount,0,:drift,:direct,cast(:reasons as jsonb),:summary,cast(:evidence as jsonb),:now,:now)
        """, base(tenantId, merchantId).addValue("id", UUID.randomUUID()).addValue("experimentId", experimentId)
        .addValue("pointId", pointId).addValue("status", status.name()).addValue("level", draft.level().name())
        .addValue("score", draft.evidenceScore()).addValue("before", delta.baselineRate).addValue("after", delta.currentRate)
        .addValue("targetDelta", delta.baselineDelta).addValue("controlDelta", delta.controlDelta)
        .addValue("adjustedDelta", delta.adjustedDelta).addValue("sampleCount", counts.valid)
        .addValue("drift", target.contextDrift()).addValue("direct", directCitation).addValue("reasons", json(draft.reasonCodes()))
        .addValue("summary", draft.safeSummary()).addValue("evidence", json(evidence)).addValue("now", OffsetDateTime.now()));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("attributionLevel", draft.level().name()); out.put("reviewStatus", "DRAFT");
    out.put("narrative", draft.safeSummary()); out.put("evidence", evidence);
    return out;
  }

  private Decision evaluateStop(Map<String, Object> experiment, UUID tenantId, UUID merchantId,
      UUID experimentId, UUID pointId, Counts counts, Delta delta, Map<String, Object> assessment) {
    int total = integer(jdbc.queryForObject("select count(*) from geo_retest_schedule_points where tenant_id=:tenantId and merchant_id=:merchantId and experiment_id=:experimentId", base(tenantId, merchantId).addValue("experimentId", experimentId), Long.class));
    int completed = integer(jdbc.queryForObject("select count(*) from geo_retest_schedule_points where tenant_id=:tenantId and merchant_id=:merchantId and experiment_id=:experimentId and status in ('COMPLETED','PARTIAL','FAILED')", base(tenantId, merchantId).addValue("experimentId", experimentId), Long.class));
    Map<String,Object> policy = map(experiment.get("stop_policy"));
    int failureLimit = intValue(policy.get("consecutiveCollectorFailureLimit"), 3);
    int noImprovementLimit = intValue(policy.get("consecutiveNoImprovementLimit"), 3);
    int citationLimit = intValue(policy.get("stableDirectCitationLimit"), 2);
    int insufficientLimit = intValue(policy.get("longTermInsufficientSampleLimit"), 3);
    int collectorFailures = recentZeroSuccessPoints(tenantId, merchantId, experimentId);
    int noImprovement = recentNoImprovement(tenantId, merchantId, experimentId);
    int stableDirect = recentDirectCitation(tenantId, merchantId, experimentId);
    int insufficient = recentInsufficient(tenantId, merchantId, experimentId);
    Integer maxCalls = integerOrNull(experiment.get("max_api_calls"));
    int callsUsed = intValue(experiment.get("api_calls_used"), 0);
    boolean publicationInactive = publicationInactive(tenantId, merchantId, experiment.get("intervention_task_id"));
    String reason;
    String decision;
    String detail;
    if (maxCalls != null && callsUsed >= maxCalls) { decision = "PAUSE"; reason = "API_BUDGET_EXCEEDED"; detail = "已达到配置的 API 调用预算。"; }
    else if (publicationInactive) { decision = "STOP"; reason = "PUBLICATION_INVALID"; detail = "关联作品已归档或下线。"; }
    else if (collectorFailures >= failureLimit) { decision = "PAUSE"; reason = "CONSECUTIVE_COLLECTOR_FAILURE"; detail = "连续复测节点没有成功采样。"; }
    else if (insufficient >= insufficientLimit) { decision = "NOT_EVALUATED"; reason = "LONG_TERM_INSUFFICIENT_SAMPLE"; detail = "连续周期有效样本不足，建议人工检查采集范围。"; }
    else if (stableDirect >= citationLimit) { decision = "COMPLETE"; reason = "STABLE_DIRECT_CITATION"; detail = "连续周期观察到稳定作品级直接引用。"; }
    else if (noImprovement >= noImprovementLimit) { decision = "PAUSE"; reason = "CONSECUTIVE_NO_IMPROVEMENT"; detail = "连续周期没有达到明显改善阈值。"; }
    else if (completed >= total && total > 0) { decision = "COMPLETE"; reason = "LAST_SCHEDULE_POINT_COMPLETED"; detail = "已到达最后一个计划复测节点。"; }
    else if (delta.currentRate == null) { decision = "NOT_EVALUATED"; reason = "LONG_TERM_INSUFFICIENT_SAMPLE"; detail = "有效样本不足，缺少连续周期数据，未作停止决定。"; }
    else { decision = "NOT_EVALUATED"; reason = "INSUFFICIENT_HISTORY"; detail = "缺少连续周期、预算或作品有效性资料，未伪造停止判断。"; }
    return new Decision(decision, reason, detail);
  }

  private void persistSummary(UUID tenantId, UUID merchantId, UUID experimentId, UUID pointId,
      Counts counts, Decision decision, UUID actorId) {
    jdbc.update("""
        insert into geo_retest_execution_summaries(id,tenant_id,merchant_id,experiment_id,schedule_point_id,
          requested_sample_count,successful_sample_count,failed_sample_count,valid_sample_count,
          metric_snapshot_created,attribution_draft_created,stop_decision,stop_reason_code,stop_detail,evaluated_at,
          created_at,updated_at,created_by,updated_by)
        values(:id,:tenantId,:merchantId,:experimentId,:pointId,:requested,:successful,:failed,:valid,
          true,true,:decision,:reason,:detail,:now,:now,:now,:actorId,:actorId)
        on conflict (tenant_id,schedule_point_id) do nothing
        """, base(tenantId, merchantId).addValue("id", UUID.randomUUID()).addValue("experimentId", experimentId)
        .addValue("pointId", pointId).addValue("requested", counts.requested).addValue("successful", counts.successful)
        .addValue("failed", counts.failed).addValue("valid", counts.valid).addValue("decision", decision.value)
        .addValue("reason", decision.reason).addValue("detail", decision.detail).addValue("now", OffsetDateTime.now())
        .addValue("actorId", actorId));
  }

  private void applyDecision(UUID tenantId, UUID merchantId, UUID experimentId, UUID actorId, Decision decision) {
    if ("COMPLETE".equals(decision.value)) jdbc.update("""
        update geo_retest_experiments set status='COMPLETED',automation_state='COMPLETED',automation_enabled=false,
          last_evaluated_at=now(),updated_at=now(),updated_by=:actorId,version=version+1
         where id=:experimentId and tenant_id=:tenantId and merchant_id=:merchantId
        """, base(tenantId, merchantId).addValue("experimentId", experimentId).addValue("actorId", actorId));
    else if ("PAUSE".equals(decision.value)) jdbc.update("""
        update geo_retest_experiments set automation_state='PAUSED',automation_enabled=false,
          last_evaluated_at=now(),updated_at=now(),updated_by=:actorId,version=version+1
         where id=:experimentId and tenant_id=:tenantId and merchant_id=:merchantId
        """, base(tenantId, merchantId).addValue("experimentId", experimentId).addValue("actorId", actorId));
    else if ("STOP".equals(decision.value)) jdbc.update("""
        update geo_retest_experiments set status='CANCELLED',automation_state='STOPPED',automation_enabled=false,
          last_evaluated_at=now(),updated_at=now(),updated_by=:actorId,version=version+1
         where id=:experimentId and tenant_id=:tenantId and merchant_id=:merchantId
        """, base(tenantId, merchantId).addValue("experimentId", experimentId).addValue("actorId", actorId));
    else jdbc.update("""
        update geo_retest_experiments set last_evaluated_at=now(),updated_at=now(),updated_by=:actorId,version=version+1
         where id=:experimentId and tenant_id=:tenantId and merchant_id=:merchantId
        """, base(tenantId, merchantId).addValue("experimentId", experimentId).addValue("actorId", actorId));
  }

  private AnalysisResult readPersisted(UUID tenantId, UUID merchantId, UUID experimentId, UUID pointId, Map<String, Object> summary) {
    Map<String, Object> target = storedMetrics(tenantId, merchantId, pointId, "TARGET");
    Map<String, Object> control = storedMetrics(tenantId, merchantId, pointId, "CONTROL");
    Map<String, Object> overall = storedMetrics(tenantId, merchantId, pointId, "OVERALL");
    Map<String, Object> assessment = jdbc.queryForList("select * from geo_retest_attribution_assessments where tenant_id=:tenantId and merchant_id=:merchantId and experiment_id=:experimentId and schedule_point_id=:pointId order by created_at desc limit 1", base(tenantId, merchantId).addValue("experimentId", experimentId).addValue("pointId", pointId)).stream().findFirst().map(this::assessmentView).orElse(Map.of());
    Map<String, Object> delta = new LinkedHashMap<>();
    delta.put("baselineDelta", target.get("baselineDelta")); delta.put("previousPeriodDelta", target.get("previousPeriodDelta"));
    delta.put("controlDelta", target.get("controlDelta")); delta.put("adjustedDelta", target.get("adjustedDelta"));
    Map<String, Object> decision = Map.of("decision", summary.get("stop_decision"), "reason", summary.get("stop_reason_code"), "detail", summary.get("stop_detail"));
    return new AnalysisResult(target, control, overall, delta, assessment, decision, true);
  }

  private Map<String, Object> storedMetrics(UUID tenantId, UUID merchantId, UUID pointId, String cohort) {
    List<Map<String, Object>> rows = jdbc.queryForList("select * from geo_retest_metric_snapshots where tenant_id=:tenantId and merchant_id=:merchantId and schedule_point_id=:pointId and cohort=:cohort", base(tenantId, merchantId).addValue("pointId", pointId).addValue("cohort", cohort));
    Map<String, Object> result = new LinkedHashMap<>(); result.put("cohort", cohort);
    Map<String, Object> metrics = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) { metrics.put(String.valueOf(row.get("metric_name")), row.get("metric_value")); result.put("requestedSampleCount", row.get("requested_sample_count")); result.put("successfulSampleCount", row.get("successful_sample_count")); result.put("failedSampleCount", row.get("failed_sample_count")); result.put("validSampleCount", row.get("valid_sample_count")); result.put("sampleSufficiency", row.get("sample_sufficiency")); result.put("baselineDelta", row.get("baseline_delta")); result.put("previousPeriodDelta", row.get("previous_period_delta")); result.put("controlDelta", row.get("control_delta")); result.put("adjustedDelta", row.get("adjusted_delta")); }
    result.put("metrics", metrics); return result;
  }

  private Map<String, Object> metricView(RetestWindowMetrics metrics, Counts counts) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (RetestMetricName metric : RetestMetricName.values()) values.put(metric.name(), counts.valid == 0 ? null : metrics.metric(metric).value());
    values.put("brandMentionCount", counts.valid == 0 ? null : metrics.metric(RetestMetricName.BRAND_MENTION_RATE).numerator());
    values.put("recommendationCount", counts.valid == 0 ? null : metrics.metric(RetestMetricName.BRAND_RECOMMENDATION_RATE).numerator());
    values.put("top3Count", counts.valid == 0 ? null : metrics.metric(RetestMetricName.TOP3_RATE).numerator());
    values.put("citationCount", counts.valid == 0 ? null : metrics.metric(RetestMetricName.ANY_CITATION_RATE).numerator());
    values.put("directCitationCount", counts.valid == 0 ? null : metrics.metric(RetestMetricName.DIRECT_PUBLICATION_CITATION_RATE).numerator());
    return new LinkedHashMap<>(Map.of("requestedSampleCount", counts.requested, "successfulSampleCount", counts.successful, "failedSampleCount", counts.failed, "validSampleCount", counts.valid, "sampleSufficiency", counts.valid >= MINIMUM_VALID_SAMPLES ? "SUFFICIENT" : "INSUFFICIENT", "metrics", values));
  }

  private Delta delta(Map<String, Object> target, Map<String, Object> control, Map<String, Object> baseline, boolean comparable) {
    Double current = value(target, RetestMetricName.BRAND_MENTION_RATE); Double base = rate(baseline, "mentionRate");
    Double td = comparableValue(current, base); Double controlCurrent = value(control, RetestMetricName.BRAND_MENTION_RATE);
    Double controlBase = rate(baseline, "controlMentionRate"); Double cd = comparableValue(controlCurrent, controlBase);
    Double adjusted = td == null || cd == null ? null : td - cd;
    Double previous = asDouble(target.get("previousPeriodDelta"));
    return new Delta(base, current, td, previous, cd, adjusted, comparable, integer(baseline.get("totalObservations")));
  }
  private boolean comparable(Map<String, Object> experiment, Map<String, Object> baseline, RetestWindowMetrics target) { return baseline != null && rate(baseline, "mentionRate") != null && target.validSamples() > 0; }
  private Map<String, Object> experiment(UUID tenant, UUID merchant, UUID id) { List<Map<String,Object>> rows=jdbc.queryForList("select * from geo_retest_experiments where id=:id and tenant_id=:tenantId and merchant_id=:merchantId", base(tenant,merchant).addValue("id",id)); if(rows.isEmpty()) throw new IllegalArgumentException("experiment not found"); return rows.getFirst(); }
  private Map<String,Object> baselineMetrics(UUID tenant, UUID merchant, Object snapshotId) { if(snapshotId==null)return Map.of(); List<Map<String,Object>> r=jdbc.queryForList("select metric_snapshot,observation_count from geo_diagnosis_snapshots where id=:id and tenant_id=:tenantId and merchant_id=:merchantId",base(tenant,merchant).addValue("id",snapshotId)); if(r.isEmpty())return Map.of(); Map<String,Object> out=new LinkedHashMap<>(map(r.getFirst().get("metric_snapshot")));out.put("totalObservations",r.getFirst().get("observation_count"));return out; }
  private Double previousMetric(UUID tenant,UUID merchant,UUID experiment,UUID point,String cohort){ List<Double> r=jdbc.query("select m.metric_value from geo_retest_metric_snapshots m join geo_retest_schedule_points p on p.id=m.schedule_point_id and p.tenant_id=m.tenant_id where m.tenant_id=:tenantId and m.merchant_id=:merchantId and m.experiment_id=:experimentId and m.cohort=:cohort and m.metric_name='BRAND_MENTION_RATE' and p.id<>:pointId and p.status in ('COMPLETED','PARTIAL') and m.metric_value is not null order by p.sequence_no desc limit 1",base(tenant,merchant).addValue("experimentId",experiment).addValue("cohort",cohort).addValue("pointId",point),(rs,i)->rs.getDouble(1));return r.isEmpty()?null:r.getFirst(); }
  private Counts counts(UUID tenant,UUID merchant,UUID point,String cohort){String extra=cohort==null?"":" and cohort=:cohort";MapSqlParameterSource p=base(tenant,merchant).addValue("pointId",point);if(cohort!=null)p.addValue("cohort",cohort);Map<String,Object> r=jdbc.queryForMap("select count(*) requested,count(*) filter(where status='SUCCESS') successful,count(*) filter(where status='FAILED') failed,count(*) filter(where status='SUCCESS' and verification_status='DRAFT') valid from geo_retest_samples where tenant_id=:tenantId and merchant_id=:merchantId and schedule_point_id=:pointId"+extra,p);return new Counts(integer(r.get("requested")),integer(r.get("successful")),integer(r.get("failed")),integer(r.get("valid")));}
  private boolean hasDirectCitation(UUID tenant,UUID merchant,UUID point){Long n=jdbc.queryForObject("select count(*) from geo_retest_samples where tenant_id=:tenantId and merchant_id=:merchantId and schedule_point_id=:pointId and direct_publication_citation=true",base(tenant,merchant).addValue("pointId",point),Long.class);return n!=null&&n>0;}
  private int recentZeroSuccessPoints(UUID tenant,UUID merchant,UUID experiment){Long n=jdbc.queryForObject("select count(*) from geo_retest_schedule_points p where p.tenant_id=:tenantId and p.merchant_id=:merchantId and p.experiment_id=:experimentId and p.status='FAILED'",base(tenant,merchant).addValue("experimentId",experiment),Long.class);return n==null?0:n.intValue();}
  private int recentNoImprovement(UUID tenant,UUID merchant,UUID experiment){Long n=jdbc.queryForObject("select count(*) from geo_retest_attribution_assessments where tenant_id=:tenantId and merchant_id=:merchantId and experiment_id=:experimentId and result_status in ('STABLE','DECLINED')",base(tenant,merchant).addValue("experimentId",experiment),Long.class);return n==null?0:n.intValue();}
  private int recentDirectCitation(UUID tenant,UUID merchant,UUID experiment){Long n=jdbc.queryForObject("select count(*) from geo_retest_attribution_assessments where tenant_id=:tenantId and merchant_id=:merchantId and experiment_id=:experimentId and attribution_level='DIRECT_CITATION'",base(tenant,merchant).addValue("experimentId",experiment),Long.class);return n==null?0:n.intValue();}
  private int recentInsufficient(UUID tenant,UUID merchant,UUID experiment){Long n=jdbc.queryForObject("select count(*) from geo_retest_attribution_assessments where tenant_id=:tenantId and merchant_id=:merchantId and experiment_id=:experimentId and result_status='INSUFFICIENT_SAMPLE'",base(tenant,merchant).addValue("experimentId",experiment),Long.class);return n==null?0:n.intValue();}
  private boolean publicationInactive(UUID tenant,UUID merchant,Object taskId){if(taskId==null)return false;Long active=jdbc.queryForObject("select count(*) from geo_tracked_publications where tenant_id=:tenantId and merchant_id=:merchantId and optimization_task_id=:taskId and status='ACTIVE'",base(tenant,merchant).addValue("taskId",taskId),Long.class);Long any=jdbc.queryForObject("select count(*) from geo_tracked_publications where tenant_id=:tenantId and merchant_id=:merchantId and optimization_task_id=:taskId",base(tenant,merchant).addValue("taskId",taskId),Long.class);return any!=null&&any>0&&(active==null||active==0);}
  private Map<String,Object> existingSummary(UUID tenant,UUID merchant,UUID point){List<Map<String,Object>>r=jdbc.queryForList("select * from geo_retest_execution_summaries where tenant_id=:tenantId and merchant_id=:merchantId and schedule_point_id=:pointId",base(tenant,merchant).addValue("pointId",point));return r.isEmpty()?null:r.getFirst();}
  private Map<String,Object> assessmentView(Map<String,Object> row){return Map.of("attributionLevel",row.get("attribution_level"),"reviewStatus",row.get("review_status"),"narrative",row.get("safe_summary"),"evidence",map(row.get("evidence_snapshot")));}
  private double maxVolatility(RetestWindowMetrics m){double x=0;for(MetricEstimate e:m.metrics().values())x=Math.max(x,e.volatilityScore());return x;}
  private List<String> append(List<String>x,String y){List<String>o=new ArrayList<>(x);o.add(y);return o;}
  private Double value(Map<String,Object> view,RetestMetricName name){Object metrics=view.get("metrics");return metrics instanceof Map<?,?> m?asDouble(m.get(name.name())):null;}
  private Double rate(Map<String,Object> x,String key){return asDouble(x.get(key));}
  private Double comparableValue(Double a,Double b){return a==null||b==null?null:a-b;}
  private Double asDouble(Object x){return x instanceof Number n?n.doubleValue():null;}
  private int integer(Object x){return x instanceof Number n?n.intValue():0;}
  private int intValue(Object x,int fallback){return x instanceof Number n?n.intValue():fallback;}
  private Integer integerOrNull(Object x){return x instanceof Number n?n.intValue():null;}
  private String json(Object x){try{return mapper.writeValueAsString(x);}catch(Exception e){throw new IllegalStateException(e);}}
  private Map<String,Object> map(Object x){try{return x==null?Map.of():mapper.readValue(String.valueOf(x),new TypeReference<Map<String,Object>>(){});}catch(Exception e){return Map.of();}}
  private MapSqlParameterSource base(UUID tenant,UUID merchant){return new MapSqlParameterSource().addValue("tenantId",tenant).addValue("merchantId",merchant);}
  public record AnalysisResult(Map<String,Object> targetMetrics,Map<String,Object> controlMetrics,Map<String,Object> overallMetrics,Map<String,Object> deltas,Map<String,Object> attribution,Map<String,Object> stopDecision,boolean replay){}
  private record Counts(int requested,int successful,int failed,int valid){}
  private record Delta(Double baselineRate,Double currentRate,Double baselineDelta,Double previousPeriodDelta,Double controlDelta,Double adjustedDelta,boolean comparable,int baselineSampleCount){Map<String,Object>view(){Map<String,Object>x=new LinkedHashMap<>();x.put("baselineDelta",baselineDelta);x.put("previousPeriodDelta",previousPeriodDelta);x.put("controlDelta",controlDelta);x.put("adjustedDelta",adjustedDelta);x.put("comparable",comparable);return x;}}
  private record Decision(String value,String reason,String detail){Map<String,Object>view(){return Map.of("decision",value,"reason",reason,"detail",detail);}}
}
