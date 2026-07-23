package com.mengwei.localgrowth.publishing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/publisher-worker")
public class PublisherWorkerController {
  private final WorkerSignatureService signatures;
  private final PublisherWorkerService service;
  private final ObjectMapper json;
  public PublisherWorkerController(WorkerSignatureService signatures, PublisherWorkerService service, ObjectMapper json) {
    this.signatures = signatures; this.service = service; this.json = json;
  }
  @PostMapping(value = "/claim", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> claim(@RequestBody(required = false) byte[] body, HttpServletRequest request,
      @RequestHeader("X-Worker-Id") String workerId, @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-Timestamp") String timestamp, @RequestHeader("X-Nonce") String nonce,
      @RequestHeader("X-Body-Sha256") String bodyHash, @RequestHeader("X-Signature") String signature) {
    rejectQuery(request);
    signatures.verify("POST", "/api/internal/publisher-worker/claim", body,
        workerId, requestId, timestamp, nonce, bodyHash, signature);
    return service.claim(workerId, requestId, nonce);
  }
  @PostMapping(value = "/jobs/{jobId}/heartbeat", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> heartbeat(@PathVariable UUID jobId, @RequestBody(required = false) byte[] body, HttpServletRequest request,
      @RequestHeader("X-Worker-Id") String workerId, @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-Timestamp") String timestamp, @RequestHeader("X-Nonce") String nonce,
      @RequestHeader("X-Body-Sha256") String bodyHash, @RequestHeader("X-Signature") String signature) {
    rejectQuery(request);
    signatures.verify("POST", "/api/internal/publisher-worker/jobs/" + jobId + "/heartbeat", body,
        workerId, requestId, timestamp, nonce, bodyHash, signature);
    return service.heartbeat(jobId, workerId, requestId, nonce);
  }
  @PostMapping(value = "/jobs/{jobId}/events", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> event(@PathVariable UUID jobId, @RequestBody byte[] body, HttpServletRequest request,
      @RequestHeader("X-Worker-Id") String workerId, @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-Timestamp") String timestamp, @RequestHeader("X-Nonce") String nonce,
      @RequestHeader("X-Body-Sha256") String bodyHash, @RequestHeader("X-Signature") String signature) {
    rejectQuery(request);
    signatures.verify("POST", "/api/internal/publisher-worker/jobs/" + jobId + "/events", body,
        workerId, requestId, timestamp, nonce, bodyHash, signature);
    long sequenceNo;
    String status;
    String payload;
    UUID eventId;
    String bodyText = new String(body, java.nio.charset.StandardCharsets.UTF_8);
    try {
      var node = json.readTree(bodyText);
      if (node == null || !node.isObject()) throw new IllegalArgumentException("event body must be an object");
      sequenceNo = node.path("sequenceNo").asLong(0);
      status = node.path("status").asText("");
      payload = node.path("payload").isMissingNode() ? "{}" : node.path("payload").toString();
      eventId = UUID.fromString(node.path("eventId").asText(""));
    } catch (Exception e) {
      throw new com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
          "INVALID_PUBLISHER_EVENT", "Worker 事件格式无效");
    }
    return service.event(jobId, workerId, requestId, nonce, eventId, sequenceNo, status, payload,
        bodyText, bodyHash);
  }
  private void rejectQuery(HttpServletRequest request) {
    if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
      throw new com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException(
          HttpStatus.BAD_REQUEST, "PUBLISHER_WORKER_QUERY_NOT_ALLOWED", "Worker 请求不允许携带查询参数");
    }
  }
}
