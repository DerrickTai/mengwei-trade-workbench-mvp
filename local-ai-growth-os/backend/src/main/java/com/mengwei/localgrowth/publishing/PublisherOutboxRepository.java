package com.mengwei.localgrowth.publishing;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Transactional outbox persistence boundary. Dispatching remains out of scope for Checkpoint A. */
@Repository
public class PublisherOutboxRepository {
  private final NamedParameterJdbcTemplate db;

  public PublisherOutboxRepository(NamedParameterJdbcTemplate db) {
    this.db = db;
  }

  public void enqueue(UUID outboxId, UUID tenantId, UUID merchantId, UUID jobId,
      String eventType, String payloadJson, String deduplicationKey,
      OffsetDateTime nextAttemptAt, OffsetDateTime now) {
    db.update("""
        insert into publisher_outbox(
          id,tenant_id,merchant_id,job_id,event_type,payload,deduplication_key,
          status,attempt_count,next_attempt_at,created_at,updated_at)
        values(:id,:tenantId,:merchantId,:jobId,:eventType,cast(:payloadJson as jsonb),:deduplicationKey,
          'PENDING',0,:nextAttemptAt,:now,:now)
        """, new MapSqlParameterSource().addValue("id", outboxId).addValue("tenantId", tenantId)
        .addValue("merchantId", merchantId).addValue("jobId", jobId).addValue("eventType", eventType)
        .addValue("payloadJson", payloadJson == null ? "{}" : payloadJson)
        .addValue("deduplicationKey", deduplicationKey)
        .addValue("nextAttemptAt", nextAttemptAt).addValue("now", now));
  }
}
