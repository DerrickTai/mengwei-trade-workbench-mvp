package com.mengwei.localgrowth.publishing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Scoped job reads and append-only event writes; orchestration is intentionally deferred. */
@Repository
public class PublisherJobRepository {
  private final NamedParameterJdbcTemplate db;

  public PublisherJobRepository(NamedParameterJdbcTemplate db) {
    this.db = db;
  }

  public Map<String, Object> find(UUID tenantId, UUID merchantId, UUID jobId) {
    List<Map<String, Object>> rows = db.queryForList("""
        select * from publisher_jobs
        where id=:jobId and tenant_id=:tenantId and merchant_id=:merchantId
        """, scope(tenantId, merchantId).addValue("jobId", jobId));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Map<String, Object> findByIdempotencyKey(UUID tenantId, UUID merchantId, String idempotencyKey) {
    List<Map<String, Object>> rows = db.queryForList("""
        select * from publisher_jobs
        where tenant_id=:tenantId and merchant_id=:merchantId and idempotency_key=:idempotencyKey
        """, scope(tenantId, merchantId).addValue("idempotencyKey", idempotencyKey));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void appendEvent(UUID eventId, UUID tenantId, UUID merchantId, UUID jobId,
      long sequenceNo, String eventType, PublishJobStatus status, String message, String payloadJson,
      java.time.OffsetDateTime occurredAt, UUID actorId) {
    db.update("""
        insert into publisher_job_events(
          id,tenant_id,merchant_id,job_id,sequence_no,event_type,status,message,payload,occurred_at,created_at,created_by)
        values(:eventId,:tenantId,:merchantId,:jobId,:sequenceNo,:eventType,:status,:message,
          cast(:payloadJson as jsonb),:occurredAt,:occurredAt,:actorId)
        """, scope(tenantId, merchantId).addValue("eventId", eventId).addValue("jobId", jobId)
        .addValue("sequenceNo", sequenceNo).addValue("eventType", eventType)
        .addValue("status", status.name()).addValue("message", message)
        .addValue("payloadJson", payloadJson == null ? "{}" : payloadJson)
        .addValue("occurredAt", occurredAt).addValue("actorId", actorId));
  }

  private MapSqlParameterSource scope(UUID tenantId, UUID merchantId) {
    return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("merchantId", merchantId);
  }
}
