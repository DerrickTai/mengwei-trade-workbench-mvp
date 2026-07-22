package com.mengwei.localgrowth.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import org.junit.jupiter.api.Test;

class PublishJobStateMachineTest {
  @Test
  void waitingHumanCanReachOnlyAllowedHumanOutcomes() {
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.WAITING_HUMAN,
        PublishJobStatus.PUBLISHED)).isTrue();
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.WAITING_HUMAN,
        PublishJobStatus.FAILED_RETRYABLE)).isTrue();
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.WAITING_HUMAN,
        PublishJobStatus.CANCELLED)).isTrue();
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.WAITING_HUMAN,
        PublishJobStatus.EXPIRED)).isTrue();
  }

  @Test
  void loginRecoveryReturnsToQueuedAndNeverDirectlyToRunning() {
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.WAITING_LOGIN,
        PublishJobStatus.QUEUED)).isTrue();
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.WAITING_LOGIN,
        PublishJobStatus.RUNNING)).isFalse();
  }

  @Test
  void retryableFailureMayRequeueButCannotPublishDirectly() {
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.FAILED_RETRYABLE,
        PublishJobStatus.QUEUED)).isTrue();
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.FAILED_RETRYABLE,
        PublishJobStatus.PUBLISHED)).isFalse();
  }

  @Test
  void pendingMustQueueBeforeItCanBeClaimed() {
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.PENDING,
        PublishJobStatus.CLAIMED)).isFalse();
    assertThat(PublishJobStateMachine.requireTransition(PublishJobStatus.PENDING,
        PublishJobStatus.QUEUED)).isEqualTo(PublishJobStatus.QUEUED);
    assertThat(PublishJobStateMachine.requireTransition(PublishJobStatus.QUEUED,
        PublishJobStatus.CLAIMED)).isEqualTo(PublishJobStatus.CLAIMED);
    assertThat(PublishJobStateMachine.requireTransition(PublishJobStatus.CLAIMED,
        PublishJobStatus.RUNNING)).isEqualTo(PublishJobStatus.RUNNING);
  }

  @Test
  void allTerminalStatesCannotMoveOrBeConfused() {
    for (PublishJobStatus terminal : new PublishJobStatus[] {
        PublishJobStatus.DRAFT_SAVED, PublishJobStatus.PUBLISHED,
        PublishJobStatus.FAILED_FINAL, PublishJobStatus.CANCELLED, PublishJobStatus.EXPIRED}) {
      assertThat(terminal.terminal()).isTrue();
      for (PublishJobStatus target : PublishJobStatus.values()) {
        assertThat(PublishJobStateMachine.canTransition(terminal, target)).isFalse();
      }
    }
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.DRAFT_SAVED,
        PublishJobStatus.PUBLISHED)).isFalse();
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.PUBLISHED,
        PublishJobStatus.RUNNING)).isFalse();
  }

  @Test
  void illegalTransitionsReturnStableBusinessError() {
    assertThatThrownBy(() -> PublishJobStateMachine.requireTransition(
        PublishJobStatus.PUBLISHED, PublishJobStatus.RUNNING))
        .isInstanceOf(ApiException.class)
        .extracting(error -> ((ApiException) error).code())
        .isEqualTo("INVALID_PUBLISH_JOB_STATUS_TRANSITION");
  }

  @Test
  void normalDispatchFlowDoesNotAllowStatusRegression() {
    assertThat(PublishJobStateMachine.canTransition(PublishJobStatus.RUNNING,
        PublishJobStatus.QUEUED)).isFalse();
  }
}
