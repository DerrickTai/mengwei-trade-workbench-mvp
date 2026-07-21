package com.mengwei.localgrowth.observationautomation.retest;

public class StopPolicyEvaluator {
  public Decision evaluate(Input input) {
    if (input.cancelled()) return new Decision(true, "MANUALLY_CANCELLED");
    if (input.publicationInactive()) return new Decision(true, "PUBLICATION_INACTIVE");
    if (input.maxApiCalls() != null && input.apiCallsUsed() >= input.maxApiCalls()) {
      return new Decision(true, "API_CALL_BUDGET_REACHED");
    }
    if (input.maxCostMicros() != null && input.costMicrosUsed() >= input.maxCostMicros()) {
      return new Decision(true, "COST_BUDGET_REACHED");
    }
    if (input.consecutiveProviderFailures() >= input.stopAfterProviderFailures()) {
      return new Decision(true, "PROVIDER_FAILURE_LIMIT_REACHED");
    }
    if (input.consecutiveStableDirectCitationCycles() >= input.stopAfterStableCitationCycles()) {
      return new Decision(true, "STABLE_DIRECT_CITATION_REACHED");
    }
    if (input.consecutiveNoChangeCycles() >= input.stopAfterNoChangeCycles()) {
      return new Decision(true, "NO_CHANGE_LIMIT_REACHED");
    }
    if (input.completedSchedulePoints() >= input.totalSchedulePoints()) {
      return new Decision(true, "SCHEDULE_COMPLETED");
    }
    return new Decision(false, "CONTINUE");
  }

  public record Input(
      boolean cancelled,
      boolean publicationInactive,
      Integer maxApiCalls,
      int apiCallsUsed,
      Long maxCostMicros,
      long costMicrosUsed,
      int consecutiveProviderFailures,
      int stopAfterProviderFailures,
      int consecutiveStableDirectCitationCycles,
      int stopAfterStableCitationCycles,
      int consecutiveNoChangeCycles,
      int stopAfterNoChangeCycles,
      int completedSchedulePoints,
      int totalSchedulePoints) {}

  public record Decision(boolean stop, String reasonCode) {}
}
