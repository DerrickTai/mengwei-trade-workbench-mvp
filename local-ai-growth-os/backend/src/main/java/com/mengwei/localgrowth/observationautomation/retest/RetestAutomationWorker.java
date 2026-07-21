package com.mengwei.localgrowth.observationautomation.retest;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.retest-automation", name = "worker-enabled", havingValue = "true")
public class RetestAutomationWorker {
  private static final Logger log = LoggerFactory.getLogger(RetestAutomationWorker.class);
  private final RetestAutomationRepository repository;
  private final RetestObservationExecutionPort executionPort;
  private final RetestAutomationProperties properties;
  private final String workerId;

  public RetestAutomationWorker(RetestAutomationRepository repository,
      RetestObservationExecutionPort executionPort, RetestAutomationProperties properties) {
    this.repository = repository;
    this.executionPort = executionPort;
    this.properties = properties;
    this.workerId = resolveWorkerId();
  }

  @Scheduled(fixedDelayString = "${app.retest-automation.poll-interval-ms:60000}")
  public void poll() {
    List<RetestAutomationRepository.ClaimedPoint> points = repository.claimDue(
        workerId, properties.claimLimit(), properties.leaseSeconds());
    for (RetestAutomationRepository.ClaimedPoint point : points) {
      execute(point);
    }
  }

  private void execute(RetestAutomationRepository.ClaimedPoint point) {
    int failures = 0;
    try {
      List<RetestAutomationRepository.ExecutionCell> cells = repository.executionCells(
          point, properties.maxCellsPerPoint());
      for (RetestAutomationRepository.ExecutionCell cell : cells) {
        RetestObservationExecutionRequest request = new RetestObservationExecutionRequest(
            point.tenantId(), point.merchantId(), systemActorId(), point.experimentId(), point.id(),
            cell.questionId(), cell.cohort(), cell.collectorConfigId(), cell.repetitionNo());
        RetestObservationExecutionResult result = executionPort.execute(request);
        repository.saveSample(point, cell, result);
        if (!result.success()) failures++;
      }
      repository.completePoint(point, failures > 0,
          failures == 0 ? null : failures + " sample(s) failed");
      // Codex: invoke metric projection, comparison, attribution draft and stop-policy evaluation here.
    } catch (Exception e) {
      log.warn("M5.2 retest point failed pointId={} code={}", point.id(), e.getClass().getSimpleName());
      repository.failPoint(point, safeMessage(e));
    }
  }

  private UUID systemActorId() {
    // Codex: replace with the repository's explicit system actor mechanism.
    return new UUID(0L, 0L);
  }

  private String safeMessage(Exception e) {
    String message = e.getMessage();
    if (message == null) return e.getClass().getSimpleName();
    return message.length() > 500 ? message.substring(0, 500) : message;
  }

  private String resolveWorkerId() {
    try { return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID(); }
    catch (Exception ignored) { return "retest-worker-" + UUID.randomUUID(); }
  }
}
