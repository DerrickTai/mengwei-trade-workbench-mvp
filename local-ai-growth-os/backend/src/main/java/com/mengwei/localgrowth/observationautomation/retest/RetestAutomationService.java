package com.mengwei.localgrowth.observationautomation.retest;

import com.mengwei.localgrowth.identity.AuthService.Identity;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetestAutomationService {
  private final RetestAutomationRepository repository;
  private final RetestSchedulePlanner planner;
  private final RetestSchedulePointExecutionService execution;

  public RetestAutomationService(RetestAutomationRepository repository,
      RetestSchedulePointExecutionService execution) {
    this.repository = repository;
    this.execution = execution;
    this.planner = new RetestSchedulePlanner();
  }

  @Transactional
  public Map<String, Object> create(Identity identity, UUID merchantId, CreateInput input) {
    validate(input);
    String timezone = input.timezone() == null ? "Asia/Shanghai" : input.timezone();
    List<Integer> offsets = input.dayOffsets() == null || input.dayOffsets().isEmpty()
        ? List.of(3, 7, 14, 30) : input.dayOffsets();
    LocalTime localTime = input.localTime() == null ? LocalTime.of(9, 0) : LocalTime.parse(input.localTime());
    int samples = input.sampleCountPerCell() == null ? 5 : input.sampleCountPerCell();
    boolean enabled = Boolean.TRUE.equals(input.automationEnabled());

    var command = new RetestAutomationRepository.CreateExperiment(identity.tenantId(), merchantId,
        identity.userId(), input.name().trim(), input.baselineSnapshotId(), input.interventionTaskId(),
        List.copyOf(input.targetQuestionIds()),
        input.controlQuestionIds() == null ? List.of() : List.copyOf(input.controlQuestionIds()),
        List.copyOf(input.collectorConfigIds()), input.locationText(), timezone, samples, enabled,
        "SNAPSHOT_PRE_PUBLICATION",
        Map.of("dayOffsets", offsets, "localTime", localTime.toString()),
        input.successCriteria() == null ? Map.of() : input.successCriteria(),
        input.stopPolicy() == null ? Map.of() : input.stopPolicy(),
        input.maxApiCalls(), input.maxCostMicros());
    UUID experimentId = repository.createExperiment(command);

    List<RetestSchedulePlanner.PlannedPoint> points = planner.planRetests(
        input.publicationTime(), ZoneId.of(timezone), localTime, offsets);
    repository.insertSchedulePoints(identity.tenantId(), merchantId, experimentId,
        identity.userId(), points);
    if (enabled) {
      repository.setAutomationState(identity.tenantId(), merchantId, experimentId,
          identity.userId(), "ACTIVE", true);
    }
    return Map.of("experimentId", experimentId, "schedulePoints", points,
        "automationState", enabled ? "ACTIVE" : "READY");
  }

  public List<Map<String, Object>> list(Identity identity, UUID merchantId) {
    return repository.listExperiments(identity.tenantId(), merchantId);
  }

  public List<Map<String, Object>> report(Identity identity, UUID merchantId, UUID experimentId) {
    return repository.report(identity.tenantId(), merchantId, experimentId);
  }

  public void activate(Identity identity, UUID merchantId, UUID experimentId) {
    repository.setAutomationState(identity.tenantId(), merchantId, experimentId,
        identity.userId(), "ACTIVE", true);
  }

  public void pause(Identity identity, UUID merchantId, UUID experimentId) {
    repository.setAutomationState(identity.tenantId(), merchantId, experimentId,
        identity.userId(), "PAUSED", false);
  }

  public Map<String, Object> executeSchedulePoint(Identity identity, UUID merchantId,
      UUID experimentId, UUID schedulePointId) {
    return execution.execute(identity, merchantId, experimentId, schedulePointId);
  }

  private void validate(CreateInput input) {
    if (input == null || input.name() == null || input.name().isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    if (input.publicationTime() == null) throw new IllegalArgumentException("publicationTime is required");
    if (input.baselineSnapshotId() == null) {
      throw new IllegalArgumentException("baselineSnapshotId is required; post-publication data cannot be represented as a baseline");
    }
    if (input.targetQuestionIds() == null || input.targetQuestionIds().isEmpty()) {
      throw new IllegalArgumentException("targetQuestionIds is required");
    }
    if (input.collectorConfigIds() == null || input.collectorConfigIds().isEmpty()) {
      throw new IllegalArgumentException("collectorConfigIds is required");
    }
    int samples = input.sampleCountPerCell() == null ? 5 : input.sampleCountPerCell();
    if (samples < 1 || samples > 20) throw new IllegalArgumentException("sampleCountPerCell must be 1..20");
  }

  public record CreateInput(String name, UUID baselineSnapshotId, UUID interventionTaskId,
      OffsetDateTime publicationTime, List<UUID> targetQuestionIds, List<UUID> controlQuestionIds,
      List<UUID> collectorConfigIds, String locationText, String timezone, String localTime,
      List<Integer> dayOffsets, Integer sampleCountPerCell, Boolean automationEnabled,
      Integer maxApiCalls, Long maxCostMicros, Map<String,Object> successCriteria,
      Map<String,Object> stopPolicy) {}
}
