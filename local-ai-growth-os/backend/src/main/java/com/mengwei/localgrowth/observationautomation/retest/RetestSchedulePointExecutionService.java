package com.mengwei.localgrowth.observationautomation.retest;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Shared orchestration boundary for a manually started schedule point.
 */
@Service
public class RetestSchedulePointExecutionService {
  private final RetestAutomationRepository repository;
  private final RetestObservationExecutionPort executionPort;
  private final RetestAutomationProperties properties;
  private final RetestExecutionAnalysisService analysis;

  public RetestSchedulePointExecutionService(RetestAutomationRepository repository,
      RetestObservationExecutionPort executionPort, RetestAutomationProperties properties,
      RetestExecutionAnalysisService analysis) {
    this.repository = repository;
    this.executionPort = executionPort;
    this.properties = properties;
    this.analysis = analysis;
  }

  public Map<String, Object> execute(Identity identity, UUID merchantId, UUID experimentId,
      UUID schedulePointId) {
    String leaseOwner = "manual-" + identity.userId() + "-" + UUID.randomUUID();
    RetestAutomationRepository.ManualClaim claim = repository.claimManual(identity.tenantId(), merchantId,
        experimentId, schedulePointId, leaseOwner, properties.leaseSeconds());
    if (claim.alreadyExecuted()) {
      RetestExecutionAnalysisService.AnalysisResult persisted = analysis.analyze(identity.tenantId(), merchantId,
          experimentId, schedulePointId, identity.userId());
      return response(claim.point(), repository.sampleSummary(identity.tenantId(), merchantId, schedulePointId),
          persisted, true, claim.recoveredLease());
    }

    List<RetestAutomationRepository.ExecutionCell> cells = repository.reserveCells(claim.point(), leaseOwner,
        properties.leaseSeconds(), properties.maxCellsPerPoint());
    for (RetestAutomationRepository.ExecutionCell cell : cells) {
      RetestObservationExecutionResult result;
      try {
        result = executionPort.execute(new RetestObservationExecutionRequest(identity.tenantId(), merchantId,
            identity.userId(), experimentId, schedulePointId, cell.questionId(), cell.cohort(),
            cell.collectorConfigId(), cell.repetitionNo()));
      } catch (Exception error) {
        result = failed(error);
      }
      repository.saveSample(claim.point(), cell, result);
    }
    RetestAutomationRepository.SampleSummary summary = repository.sampleSummary(
        identity.tenantId(), merchantId, schedulePointId);
    String errorSummary = summary.failed() == 0 ? null : summary.failed() + " sample(s) failed";
    repository.finishManual(claim.point(), summary.requested(), summary.successful(), summary.failed(), errorSummary);
    RetestExecutionAnalysisService.AnalysisResult persisted = analysis.analyze(identity.tenantId(), merchantId,
        experimentId, schedulePointId, identity.userId());
    return response(claim.point(), summary, persisted, false, claim.recoveredLease());
  }

  private Map<String, Object> response(RetestAutomationRepository.ClaimedPoint point,
      RetestAutomationRepository.SampleSummary summary, RetestExecutionAnalysisService.AnalysisResult analysis,
      boolean replay, boolean recoveredLease) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("experimentId", point.experimentId());
    result.put("schedulePointId", point.id());
    result.put("status", repository.pointStatus(point.tenantId(), point.merchantId(), point.experimentId(), point.id()));
    result.put("requestedSampleCount", summary.requested());
    result.put("successfulSampleCount", summary.successful());
    result.put("failedSampleCount", summary.failed());
    result.put("validSampleCount", summary.valid());
    result.put("metricSnapshotCreated", true);
    result.put("targetMetrics", analysis.targetMetrics());
    result.put("controlMetrics", analysis.controlMetrics());
    result.put("overallMetrics", analysis.overallMetrics());
    result.putAll(analysis.deltas());
    result.put("attributionDraftCreated", !analysis.attribution().isEmpty());
    result.put("attributionLevel", analysis.attribution().get("attributionLevel"));
    result.put("attributionNarrative", analysis.attribution().get("narrative"));
    result.put("stopDecision", analysis.stopDecision().get("decision"));
    result.put("stopReason", analysis.stopDecision().get("reason"));
    result.put("alreadyExecuted", replay);
    result.put("idempotentReplay", replay);
    result.put("recoveredLease", recoveredLease);
    return result;
  }

  private RetestObservationExecutionResult failed(Exception error) {
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    if (message.length() > 500) message = message.substring(0, 500);
    return new RetestObservationExecutionResult(false, null, null, null, "DRAFT", null, null,
        null, null, null, null, false, false, null, false, false, null, null,
        error.getClass().getSimpleName(), message, OffsetDateTime.now());
  }
}
