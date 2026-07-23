package com.mengwei.localgrowth.publishing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublisherWorkerService {
  private final NamedParameterJdbcTemplate db;
  private final PublisherOutboxRepository outbox;
  private final PublisherWorkerProperties properties;
  private final WorkerReplayGuard replayGuard;
  private final ObjectMapper json;

  public PublisherWorkerService(NamedParameterJdbcTemplate db, PublisherOutboxRepository outbox,
      PublisherWorkerProperties properties, WorkerReplayGuard replayGuard, ObjectMapper json) {
    this.db = db; this.outbox = outbox; this.properties = properties; this.replayGuard = replayGuard; this.json = json;
  }

  @Transactional
  public Map<String, Object> claim(String workerId, String requestId, String nonce) {
    ensureEnabled();
    replayGuard.reserve(workerId, requestId, nonce);
    if (seenAuth(requestId, nonce)) throw bad("PUBLISHER_WORKER_REPLAY", "Worker requestId 或 nonce 已使用");
    Map<String, Object> row = outbox.claimOne(workerId, (int) properties.leaseSeconds());
    if (row == null) return Map.of("claimed", false);
    UUID tenantId = uuid(row.get("tenant_id")); UUID merchantId = uuid(row.get("merchant_id")); UUID jobId = uuid(row.get("job_id"));
    PublishJobStatus previous = PublishJobStatus.valueOf(String.valueOf(row.get("job_status")));
    if (previous != PublishJobStatus.QUEUED) {
      PublishJobStateMachine.requireTransition(previous, PublishJobStatus.QUEUED);
      db.update("update publisher_jobs set status='QUEUED',updated_at=:now,version_lock=version_lock+1 where id=:id",
          new MapSqlParameterSource().addValue("now", OffsetDateTime.now()).addValue("id", jobId));
      appendEvent(tenantId, merchantId, jobId, nextJobSequence(jobId), "WORKER_REQUEUED",
          PublishJobStatus.QUEUED, "旧租约已过期，任务重新排队", Map.of("workerId", workerId));
    }
    PublishJobStateMachine.requireTransition(PublishJobStatus.QUEUED, PublishJobStatus.CLAIMED);
    db.update("update publisher_jobs set status='CLAIMED',updated_at=:now,version_lock=version_lock+1 where id=:id",
        new MapSqlParameterSource().addValue("now", OffsetDateTime.now()).addValue("id", jobId));
    row.put("job_status", PublishJobStatus.CLAIMED.name());
    long sequence = nextJobSequence(jobId);
    appendEvent(tenantId, merchantId, jobId, sequence, "WORKER_CLAIMED", PublishJobStatus.valueOf(String.valueOf(row.get("job_status"))),
        "Worker 已领取任务", Map.of("requestId", requestId, "nonce", nonce, "workerId", workerId));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("claimed", true); result.put("jobId", jobId); result.put("accountId", row.get("account_id"));
    result.put("platform", row.get("platform")); result.put("publishMode", row.get("publish_mode"));
    result.put("title", row.get("title_snapshot")); result.put("body", row.get("body_snapshot"));
    result.put("structuredContent", row.get("structured_content_snapshot")); result.put("topics", row.get("topics_snapshot"));
    result.put("contentHash", row.get("content_hash")); result.put("evidencePackHash", row.get("evidence_pack_hash"));
    result.put("profileRef", row.get("profile_ref")); result.put("credentialRef", row.get("credential_ref"));
    result.put("externalAccountId", row.get("external_account_id")); result.put("displayName", row.get("display_name"));
    result.put("leaseUntil", row.get("lease_until")); result.put("dispatchSequence", row.get("dispatch_sequence"));
    result.put("assets", assets(tenantId, merchantId, jobId));
    return result;
  }

  @Transactional
  public Map<String, Object> heartbeat(UUID jobId, String workerId, String requestId, String nonce) {
    ensureEnabled();
    replayGuard.reserve(workerId, requestId, nonce);
    Map<String, Object> job = lockJob(jobId);
    if (seenWorkerRequest(requestId, nonce)) throw bad("PUBLISHER_WORKER_REPLAY", "Worker requestId 或 nonce 已使用");
    verifyLease(job, workerId);
    OffsetDateTime now = OffsetDateTime.now(); OffsetDateTime lease = now.plusSeconds(properties.leaseSeconds());
    db.update("update publisher_jobs set lease_until=:lease,heartbeat_at=:now,updated_at=:now,version_lock=version_lock+1 where id=:id",
        new MapSqlParameterSource().addValue("lease", lease).addValue("now", now).addValue("id", jobId));
    appendEvent(uuid(job.get("tenant_id")), uuid(job.get("merchant_id")), jobId, nextJobSequence(jobId),
        "WORKER_HEARTBEAT", PublishJobStatus.valueOf(String.valueOf(job.get("status"))), "Worker 心跳",
        Map.of("requestId", requestId, "nonce", nonce, "workerId", workerId));
    return Map.of("jobId", jobId, "status", job.get("status"), "leaseUntil", lease);
  }

  @Transactional
  public Map<String, Object> event(UUID jobId, String workerId, String requestId, String nonce,
      UUID eventId, long sequence, String requestedStatus, String payload, String rawBody, String bodyHash) {
    ensureEnabled();
    replayGuard.reserve(workerId, requestId, nonce);
    Map<String, Object> job = lockJob(jobId);
    verifyLease(job, workerId);
    if (sequence <= asLong(job.get("last_callback_sequence"))) throw bad("PUBLISHER_CALLBACK_SEQUENCE_REPLAY", "回调 sequence 已处理");
    if (seenCallback(requestId, nonce, jobId, sequence)) throw bad("PUBLISHER_CALLBACK_REPLAY", "回调已处理");
    PublishJobStatus to;
    try { to = PublishJobStatus.valueOf(requestedStatus); } catch (Exception e) { throw bad("INVALID_PUBLISH_JOB_STATUS", "不支持的 Worker 状态"); }
    if (to == PublishJobStatus.PUBLISHED) throw bad("PUBLISHER_STATUS_NOT_ALLOWED", "B2A 不允许 Worker 直接发布");
    PublishJobStatus from = PublishJobStatus.valueOf(String.valueOf(job.get("status")));
    OffsetDateTime now = OffsetDateTime.now();
    insertCallback(job, eventId, workerId, requestId, nonce, sequence, to, rawBody, payload, bodyHash, now);
    PublishJobStateMachine.requireTransition(from, to);
    long eventSequence = nextJobSequence(jobId);
    appendEvent(uuid(job.get("tenant_id")), uuid(job.get("merchant_id")), jobId, eventSequence, "WORKER_STATUS", to,
        "Worker 状态回报", safePayload(payload));
    db.update("""
        update publisher_jobs set status=:status,last_callback_sequence=:sequence,heartbeat_at=:now,
          updated_at=:now,version_lock=version_lock+1,
          lease_until=case when :terminal or :clearLease then null else lease_until end,
          claimed_by=case when :clearLease then null else claimed_by end
        where id=:id
        """, new MapSqlParameterSource().addValue("status", to.name()).addValue("sequence", sequence)
        .addValue("now", now).addValue("terminal", to.terminal())
        .addValue("clearLease", to == PublishJobStatus.FAILED_RETRYABLE).addValue("id", jobId));
    if (to == PublishJobStatus.RUNNING || to == PublishJobStatus.DRAFT_SAVED) outbox.markSent(outboxId(jobId), now);
    if (to == PublishJobStatus.FAILED_RETRYABLE) {
      OffsetDateTime next = now.plusSeconds(backoff(asLong(job.get("attempt_count"))));
      outbox.markRetry(outboxId(jobId), next, "WORKER_FAILED_RETRYABLE", now);
    }
    if (to == PublishJobStatus.FAILED_FINAL) outbox.markFailed(outboxId(jobId), "WORKER_FAILED_FINAL", now);
    return Map.of("jobId", jobId, "status", to.name(), "sequence", sequence);
  }

  private List<Map<String, Object>> assets(UUID tenant, UUID merchant, UUID job) {
    return db.queryForList("""
        select id,asset_id,sort_order,object_key_snapshot,mime_type_snapshot,size_bytes_snapshot,sha256_snapshot
        from publisher_job_assets where tenant_id=:tenant and merchant_id=:merchant and job_id=:job order by sort_order
        """, new MapSqlParameterSource().addValue("tenant", tenant).addValue("merchant", merchant).addValue("job", job));
  }

  private Map<String, Object> lockJob(UUID jobId) {
    List<Map<String, Object>> rows = db.queryForList("select *, extract(epoch from lease_until) as lease_until_epoch from publisher_jobs where id=:id for update", new MapSqlParameterSource().addValue("id", jobId));
    if (rows.isEmpty()) throw bad("PUBLISH_JOB_NOT_FOUND", "发布任务不存在");
    return rows.getFirst();
  }

  private void verifyLease(Map<String, Object> job, String workerId) {
    Object rawEpoch = job.get("lease_until_epoch");
    double leaseEpoch = rawEpoch instanceof Number number ? number.doubleValue() : -1;
    if (!workerId.equals(String.valueOf(job.get("claimed_by")))
        || leaseEpoch <= java.time.Instant.now().toEpochMilli() / 1000d)
      throw bad("PUBLISH_JOB_LEASE_LOST", "Worker 租约已失效或不属于当前 Worker");
  }

  private boolean seenAuth(String requestId, String nonce) {
    return seenWorkerRequest(requestId, nonce, "WORKER_CLAIMED");
  }

  private boolean seenWorkerRequest(String requestId, String nonce) {
    return seenWorkerRequest(requestId, nonce, null);
  }

  private boolean seenWorkerRequest(String requestId, String nonce, String eventType) {
    String typeClause = eventType == null ? "" : " and event_type=:eventType";
    String sql = "select count(*) from publisher_job_events where ((payload->>'requestId')=:requestId or (payload->>'nonce')=:nonce)" + typeClause;
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("requestId", requestId).addValue("nonce", nonce);
    if (eventType != null) params.addValue("eventType", eventType);
    Integer count = db.queryForObject(sql,
        params, Integer.class);
    return count != null && count > 0;
  }

  private boolean seenCallback(String requestId, String nonce, UUID jobId, long sequence) {
    Integer count = db.queryForObject("select count(*) from publisher_callback_events where signature_valid=true and (request_id=:requestId or nonce=:nonce or (job_id=:job and sequence_no=:sequence))",
        new MapSqlParameterSource().addValue("requestId", requestId).addValue("nonce", nonce).addValue("job", jobId).addValue("sequence", sequence), Integer.class);
    return count != null && count > 0;
  }

  private void insertCallback(Map<String, Object> job, UUID eventId, String workerId, String requestId, String nonce,
      long sequence, PublishJobStatus status, String rawBody, String payload, String bodyHash, OffsetDateTime now) {
    db.update("""
        insert into publisher_callback_events(id,event_id,request_id,nonce,tenant_id,merchant_id,job_id,sequence_no,status,
          raw_body,payload,body_hash,signature_valid,signature_error,sent_at,received_at,processed_at)
        values(:id,:eventId,:requestId,:nonce,:tenant,:merchant,:job,:sequence,:status,:raw,cast(:payload as jsonb),
          :hash,true,null,:now,:now,:now)
        """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("eventId", eventId)
        .addValue("requestId", requestId).addValue("nonce", nonce).addValue("tenant", job.get("tenant_id"))
        .addValue("merchant", job.get("merchant_id")).addValue("job", job.get("id")).addValue("sequence", sequence)
        .addValue("status", status.name()).addValue("raw", rawBody == null ? "" : rawBody)
        .addValue("payload", safePayloadJson(payload)).addValue("hash", bodyHash).addValue("now", now));
  }

  private long nextJobSequence(UUID jobId) {
    Long value = db.queryForObject("select coalesce(max(sequence_no),0)+1 from publisher_job_events where job_id=:id",
        new MapSqlParameterSource().addValue("id", jobId), Long.class);
    return value == null ? 1 : value;
  }
  private void appendEvent(UUID tenantId, UUID merchantId, UUID jobId, long sequence, String type,
      PublishJobStatus status, String message, Map<String, ?> payload) {
    try {
      db.update("""
          insert into publisher_job_events(id,tenant_id,merchant_id,job_id,sequence_no,event_type,status,message,payload,occurred_at,created_at)
          values(:id,:tenant,:merchant,:job,:sequence,:type,:status,:message,cast(:payload as jsonb),:now,:now)
          """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("tenant", tenantId)
          .addValue("merchant", merchantId).addValue("job", jobId).addValue("sequence", sequence)
          .addValue("type", type).addValue("status", status.name()).addValue("message", message)
          .addValue("payload", json.writeValueAsString(payload)).addValue("now", OffsetDateTime.now()));
    } catch (Exception e) { throw new IllegalStateException("无法追加发布任务事件", e); }
  }
  private UUID outboxId(UUID jobId) {
    return db.queryForObject("select id from publisher_outbox where job_id=:job order by created_at limit 1",
        new MapSqlParameterSource().addValue("job", jobId), UUID.class);
  }
  private long backoff(long attempt) { return Math.min(3600, 30L * (1L << Math.min(6, Math.max(0, attempt - 1)))); }
  private long asLong(Object value) { return value instanceof Number n ? n.longValue() : 0; }
  private UUID uuid(Object value) { return value instanceof UUID u ? u : UUID.fromString(String.valueOf(value)); }
  private Map<String, Object> safePayload(String value) { try { return json.readValue(value == null || value.isBlank() ? "{}" : value, Map.class); } catch (JsonProcessingException e) { return Map.of("parseError", "INVALID_JSON"); } }
  private String safePayloadJson(String value) { try { return json.writeValueAsString(safePayload(value)); } catch (JsonProcessingException e) { return "{}"; } }
  private ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
  private void ensureEnabled() {
    if (!properties.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
        "PUBLISHER_WORKER_UNAVAILABLE", "Publisher Worker 当前未启用");
  }
}
