package com.mengwei.localgrowth.publishing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class WorkerReplayGuardTest {
  @Test
  void redisAtomicReservationAcceptsFirstRequest() {
    StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
    doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), anyList(), eq("900"), eq("1"));
    new WorkerReplayGuard(redis, 900).reserve("worker-a", "request-1", "nonce-1");
  }

  @Test
  void existingRequestIsRejected() {
    StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
    doReturn(0L).when(redis).execute(any(DefaultRedisScript.class), anyList(), eq("900"), eq("1"));
    assertThatThrownBy(() -> new WorkerReplayGuard(redis, 900).reserve("worker-a", "request-1", "nonce-1"))
        .hasMessageContaining("requestId");
  }

  @Test
  void redisFailureFailsClosed() {
    StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
    doThrow(new DataAccessResourceFailureException("redis unavailable"))
        .when(redis).execute(any(DefaultRedisScript.class), anyList(), eq("900"), eq("1"));
    assertThatThrownBy(() -> new WorkerReplayGuard(redis, 900).reserve("worker-a", "request-1", "nonce-1"))
        .hasMessageContaining("防重放服务不可用");
  }
}
