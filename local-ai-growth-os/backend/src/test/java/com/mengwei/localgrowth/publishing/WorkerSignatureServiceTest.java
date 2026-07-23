package com.mengwei.localgrowth.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WorkerSignatureServiceTest {
  private static final String SECRET = "test-worker-secret";

  @Test
  void validSignatureIsAcceptedAndBodyHashIsBound() throws Exception {
    var service = WorkerSignatureService.forTest(SECRET, "worker-a", 300);
    String body = "{}"; String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String hash = service.bodyHash(body);
    String signature = sign("POST\n/api/internal/publisher-worker/claim\nworker-a\nr-1\n" + timestamp + "\nn-1\n" + hash);
    assertThat(service.verify("POST", "/api/internal/publisher-worker/claim", body,
        "worker-a", "r-1", timestamp, "n-1", hash, signature).workerId()).isEqualTo("worker-a");
  }

  @Test
  void badSignatureAndExpiredRequestsAreRejected() throws Exception {
    var service = WorkerSignatureService.forTest(SECRET, "worker-a", 300);
    String body = "{}"; String timestamp = String.valueOf(Instant.now().getEpochSecond());
    assertThatThrownBy(() -> service.verify("POST", "/x", body, "worker-a", "r-1", timestamp,
        "n-1", service.bodyHash(body), "bad")).isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code()).isEqualTo("PUBLISHER_WORKER_SIGNATURE_INVALID");
    assertThatThrownBy(() -> service.verify("POST", "/x", body, "worker-a", "r-2", "1",
        "n-2", service.bodyHash(body), "bad")).isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code()).isEqualTo("PUBLISHER_WORKER_REQUEST_EXPIRED");
  }

  @Test
  void missingSecretDisablesWorkerApi() {
    var service = WorkerSignatureService.forTest("", "", 300);
    assertThatThrownBy(() -> service.verify("POST", "/x", "", "worker", "r", "1", "n", service.bodyHash(""), "s"))
        .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code())
        .isEqualTo("PUBLISHER_WORKER_UNAVAILABLE");
  }

  @Test
  void workerIdIsBoundToSignature() throws Exception {
    var service = WorkerSignatureService.forTest(SECRET, "worker-a,worker-b", 300);
    String body = "{}"; String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String hash = service.bodyHash(body);
    String signature = sign("POST\n/x\nworker-a\nr-1\n" + timestamp + "\nn-1\n" + hash);
    assertThatThrownBy(() -> service.verify("POST", "/x", body, "worker-b", "r-1", timestamp,
        "n-1", hash, signature)).isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code()).isEqualTo("PUBLISHER_WORKER_SIGNATURE_INVALID");
  }

  @Test
  void emptyAllowlistFailsClosed() throws Exception {
    var service = WorkerSignatureService.forTest(SECRET, "", 300);
    String body = "{}"; String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String hash = service.bodyHash(body);
    String signature = sign("POST\n/x\nworker-a\nr-1\n" + timestamp + "\nn-1\n" + hash);
    assertThatThrownBy(() -> service.verify("POST", "/x", body, "worker-a", "r-1", timestamp,
        "n-1", hash, signature)).isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code()).isEqualTo("PUBLISHER_WORKER_UNAVAILABLE");
  }

  @Test
  void extremeTimestampsAreExpired() {
    var service = WorkerSignatureService.forTest(SECRET, "worker-a", 300);
    String hash = service.bodyHash("");
    assertThatThrownBy(() -> service.verify("POST", "/x", "", "worker-a", "r-min", Long.toString(Long.MIN_VALUE),
        "n-min", hash, "bad")).isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code()).isEqualTo("PUBLISHER_WORKER_REQUEST_EXPIRED");
    assertThatThrownBy(() -> service.verify("POST", "/x", "", "worker-a", "r-max", Long.toString(Long.MAX_VALUE),
        "n-max", hash, "bad")).isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code()).isEqualTo("PUBLISHER_WORKER_REQUEST_EXPIRED");
  }

  private String sign(String canonical) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
  }
}
