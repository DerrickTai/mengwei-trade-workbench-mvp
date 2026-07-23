package com.mengwei.localgrowth.publishing;

import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Cross-process request/nonce replay guard. Redis failures fail closed. */
@Service
public class WorkerReplayGuard {
  private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
      if redis.call('exists', KEYS[1]) == 1 or redis.call('exists', KEYS[2]) == 1 then return 0 end
      redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[1], 'NX')
      redis.call('set', KEYS[2], ARGV[2], 'EX', ARGV[1], 'NX')
      return 1
      """, Long.class);

  private final StringRedisTemplate redis;
  private final Duration ttl;

  public WorkerReplayGuard(StringRedisTemplate redis,
      @org.springframework.beans.factory.annotation.Value("${app.publisher-worker.replay-ttl-seconds:900}") long ttlSeconds) {
    this.redis = redis;
    this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
  }

  public void reserve(String workerId, String requestId, String nonce) {
    if (workerId == null || requestId == null || nonce == null
        || workerId.isBlank() || requestId.isBlank() || nonce.isBlank()) {
      throw fail("PUBLISHER_WORKER_AUTH_INVALID", "Worker 请求认证字段不完整");
    }
    try {
      Long accepted = redis.execute(RESERVE,
          List.of(key("request", workerId, requestId), key("nonce", workerId, nonce)),
          String.valueOf(ttl.getSeconds()), "1");
      if (!Long.valueOf(1L).equals(accepted)) {
        throw fail("PUBLISHER_WORKER_REPLAY", "Worker requestId 或 nonce 已使用");
      }
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLISHER_WORKER_REPLAY_GUARD_UNAVAILABLE", "Worker 防重放服务不可用");
    }
  }

  private String key(String kind, String workerId, String value) {
    return "localgrowth:publisher-worker:replay:" + kind + ":" + workerId + ":" + value;
  }

  private ApiException fail(String code, String message) {
    return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
  }
}
