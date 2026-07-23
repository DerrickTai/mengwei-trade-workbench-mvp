package com.mengwei.localgrowth.publishing;

import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Authentication for the internal publisher worker protocol. */
@Service
public class WorkerSignatureService {
  private final String secret;
  private final Set<String> allowlist;
  private final long windowSeconds;

  @Autowired
  public WorkerSignatureService(
      @Value("${app.publisher-worker.hmac-secret:}") String secret,
      @Value("${app.publisher-worker.id-allowlist:}") String allowlist,
      @Value("${app.publisher-worker.timestamp-window-seconds:300}") long windowSeconds) {
    this(secret, allowlist, windowSeconds, true);
  }

  private WorkerSignatureService(String secret, String allowlist, long windowSeconds, boolean ignored) {
    this.secret = secret == null ? "" : secret.trim();
    this.allowlist = Arrays.stream((allowlist == null ? "" : allowlist).split(","))
        .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    this.windowSeconds = windowSeconds <= 0 ? 300 : windowSeconds;
  }

  static WorkerSignatureService forTest(String secret, String allowlist, long windowSeconds) {
    return new WorkerSignatureService(secret, allowlist, windowSeconds, true);
  }

  public Verified verify(String method, String path, String body, String workerId, String requestId,
      String timestamp, String nonce, String bodyHash, String signature) {
    return verify(method, path, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
        workerId, requestId, timestamp, nonce, bodyHash, signature);
  }

  /** Verifies the exact bytes received on the wire; callers must not re-serialize JSON. */
  public Verified verify(String method, String path, byte[] body, String workerId, String requestId,
      String timestamp, String nonce, String bodyHash, String signature) {
    if (secret.isBlank()) throw fail("PUBLISHER_WORKER_UNAVAILABLE", "Worker 签名密钥未配置");
    if (blank(workerId) || blank(requestId) || blank(timestamp) || blank(nonce)
        || blank(bodyHash) || blank(signature)) throw fail("PUBLISHER_WORKER_AUTH_INVALID", "Worker 请求认证字段不完整");
    if (allowlist.isEmpty()) throw fail("PUBLISHER_WORKER_UNAVAILABLE", "Worker 允许列表未配置");
    if (!allowlist.contains(workerId)) throw fail("PUBLISHER_WORKER_NOT_ALLOWED", "Worker 不在允许列表");
    long epoch;
    try { epoch = Long.parseLong(timestamp); } catch (NumberFormatException e) {
      throw fail("PUBLISHER_WORKER_AUTH_INVALID", "Worker 时间戳无效");
    }
    long now = Instant.now().getEpochSecond();
    if (epoch < now - windowSeconds || epoch > now + windowSeconds)
      throw fail("PUBLISHER_WORKER_REQUEST_EXPIRED", "Worker 请求已过期");
    String actualBodyHash = sha(body == null ? new byte[0] : body);
    if (!constant(actualBodyHash, bodyHash.toLowerCase()))
      throw fail("PUBLISHER_WORKER_BODY_HASH_INVALID", "Worker 请求体摘要不匹配");
    String canonical = String.join("\n", method.toUpperCase(), path, workerId, requestId, timestamp, nonce, actualBodyHash);
    if (!constant(hmac(canonical), signature)) throw fail("PUBLISHER_WORKER_SIGNATURE_INVALID", "Worker 签名无效");
    return new Verified(workerId, requestId, nonce, epoch, actualBodyHash);
  }

  public String bodyHash(String body) { return sha(body == null ? "" : body); }
  private boolean constant(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
  private String hmac(String value) {
    try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  private String sha(String value) {
    return sha(value.getBytes(StandardCharsets.UTF_8));
  }
  private String sha(byte[] value) {
    try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  private boolean blank(String value) { return value == null || value.isBlank(); }
  private ApiException fail(String code, String message) { return new ApiException(HttpStatus.UNAUTHORIZED, code, message); }
  public record Verified(String workerId, String requestId, String nonce, long timestamp, String bodyHash) {}
}
