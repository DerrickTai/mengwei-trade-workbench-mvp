package com.mengwei.localgrowth.observationautomation.retest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.retest-automation")
public record RetestAutomationProperties(
    boolean workerEnabled,
    long pollIntervalMs,
    int claimLimit,
    int leaseSeconds,
    int maxCellsPerPoint) {

  public RetestAutomationProperties {
    if (pollIntervalMs <= 0) pollIntervalMs = 60_000L;
    if (claimLimit <= 0) claimLimit = 3;
    if (leaseSeconds <= 0) leaseSeconds = 300;
    if (maxCellsPerPoint <= 0) maxCellsPerPoint = 200;
  }
}
