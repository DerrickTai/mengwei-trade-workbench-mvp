package com.mengwei.localgrowth.publishing;

public enum PublishJobStatus {
  PENDING,
  QUEUED,
  CLAIMED,
  RUNNING,
  WAITING_LOGIN,
  WAITING_HUMAN,
  DRAFT_SAVED,
  PUBLISHED,
  FAILED_RETRYABLE,
  FAILED_FINAL,
  CANCELLED,
  EXPIRED;

  public boolean terminal() {
    return this == DRAFT_SAVED || this == PUBLISHED || this == FAILED_FINAL
        || this == CANCELLED || this == EXPIRED;
  }
}
