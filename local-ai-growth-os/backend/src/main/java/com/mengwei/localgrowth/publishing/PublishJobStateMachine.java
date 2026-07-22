package com.mengwei.localgrowth.publishing;

import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Deterministic server-side state transition policy for M6.2 jobs. */
public final class PublishJobStateMachine {
  private static final Map<PublishJobStatus, Set<PublishJobStatus>> ALLOWED = transitions();

  private PublishJobStateMachine() {}

  public static PublishJobStatus requireTransition(PublishJobStatus from, PublishJobStatus to) {
    if (from == null || to == null || !ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PUBLISH_JOB_STATUS_TRANSITION",
          "发布任务状态不允许从 " + from + " 变更为 " + to);
    }
    return to;
  }

  public static boolean canTransition(PublishJobStatus from, PublishJobStatus to) {
    return from != null && to != null && ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }

  private static Map<PublishJobStatus, Set<PublishJobStatus>> transitions() {
    Map<PublishJobStatus, Set<PublishJobStatus>> map = new EnumMap<>(PublishJobStatus.class);
    map.put(PublishJobStatus.PENDING, EnumSet.of(PublishJobStatus.QUEUED,
        PublishJobStatus.CANCELLED, PublishJobStatus.EXPIRED, PublishJobStatus.FAILED_FINAL));
    map.put(PublishJobStatus.QUEUED, EnumSet.of(PublishJobStatus.CLAIMED, PublishJobStatus.CANCELLED,
        PublishJobStatus.EXPIRED, PublishJobStatus.FAILED_RETRYABLE, PublishJobStatus.FAILED_FINAL));
    map.put(PublishJobStatus.CLAIMED, EnumSet.of(PublishJobStatus.RUNNING,
        PublishJobStatus.WAITING_LOGIN, PublishJobStatus.FAILED_RETRYABLE,
        PublishJobStatus.FAILED_FINAL, PublishJobStatus.CANCELLED, PublishJobStatus.EXPIRED));
    map.put(PublishJobStatus.RUNNING, EnumSet.of(PublishJobStatus.WAITING_LOGIN,
        PublishJobStatus.WAITING_HUMAN, PublishJobStatus.DRAFT_SAVED, PublishJobStatus.PUBLISHED,
        PublishJobStatus.FAILED_RETRYABLE, PublishJobStatus.FAILED_FINAL,
        PublishJobStatus.CANCELLED, PublishJobStatus.EXPIRED));
    map.put(PublishJobStatus.WAITING_LOGIN, EnumSet.of(PublishJobStatus.QUEUED,
        PublishJobStatus.FAILED_FINAL, PublishJobStatus.CANCELLED, PublishJobStatus.EXPIRED));
    map.put(PublishJobStatus.WAITING_HUMAN, EnumSet.of(PublishJobStatus.PUBLISHED,
        PublishJobStatus.FAILED_RETRYABLE, PublishJobStatus.CANCELLED, PublishJobStatus.EXPIRED));
    map.put(PublishJobStatus.FAILED_RETRYABLE, EnumSet.of(PublishJobStatus.QUEUED,
        PublishJobStatus.FAILED_FINAL,
        PublishJobStatus.CANCELLED, PublishJobStatus.EXPIRED));
    return Map.copyOf(map);
  }
}
