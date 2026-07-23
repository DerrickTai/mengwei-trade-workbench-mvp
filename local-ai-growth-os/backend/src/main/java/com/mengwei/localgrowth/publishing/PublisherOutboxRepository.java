package com.mengwei.localgrowth.publishing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database-backed transactional outbox claim and retry operations. */
@Repository
public class PublisherOutboxRepository {
  private final NamedParameterJdbcTemplate db;

  public PublisherOutboxRepository(NamedParameterJdbcTemplate db) { this.db = db; }

  public void enqueue(UUID outboxId, UUID tenantId, UUID merchantId, UUID jobId,
      String eventType, String payloadJson, String deduplicationKey,
      OffsetDateTime nextAttemptAt, OffsetDateTime now) {
    db.update("""
        insert into publisher_outbox(
          id,tenant_id,merchant_id,job_id,event_type,payload,deduplication_key,
          status,attempt_count,next_attempt_at,created_at,updated_at)
        values(:id,:tenantId,:merchantId,:jobId,:eventType,cast(:payloadJson as jsonb),:deduplicationKey,
          'PENDING',0,:nextAttemptAt,:now,:now)
        """, params(tenantId, merchantId).addValue("id", outboxId).addValue("jobId", jobId)
        .addValue("eventType", eventType).addValue("payloadJson", payloadJson == null ? "{}" : payloadJson)
        .addValue("deduplicationKey", deduplicationKey).addValue("nextAttemptAt", nextAttemptAt).addValue("now", now));
  }

  /** Claims one due command. Caller must invoke inside a transaction. */
  public Map<String, Object> claimOne(String workerId, int leaseSeconds) {
    List<Map<String, Object>> rows = db.queryForList("""
        select o.id outbox_id,o.tenant_id,o.merchant_id,o.job_id,o.attempt_count outbox_attempt_count,
          j.status job_status,j.account_id,j.platform,j.publish_mode,j.title_snapshot,j.body_snapshot,
          j.structured_content_snapshot,j.topics_snapshot,j.content_hash,j.evidence_pack_hash,
          j.claimed_by,j.lease_until,j.last_callback_sequence,
          a.profile_ref,a.credential_ref,a.external_account_id,a.display_name
        from publisher_outbox o join publisher_jobs j on j.id=o.job_id
          join publisher_accounts a on a.id=j.account_id
        where ((o.status='PENDING' and o.next_attempt_at <= now())
            or (o.status='PROCESSING' and o.lease_until is not null and o.lease_until < now()))
          and a.status='ACTIVE' and a.deleted=false
          and j.status in ('QUEUED','CLAIMED','RUNNING','WAITING_LOGIN','FAILED_RETRYABLE')
          and not exists (
            select 1 from publisher_jobs active_job
            where active_job.account_id=j.account_id
              and active_job.id<>j.id
              and active_job.claimed_by is not null
              and active_job.lease_until > now()
              and active_job.status in ('CLAIMED','RUNNING','WAITING_LOGIN','WAITING_HUMAN')
          )
        order by o.next_attempt_at,o.created_at
        for update of o,j,a skip locked
        limit 1
        """, new MapSqlParameterSource());
    if (rows.isEmpty()) return null;
    Map<String, Object> row = rows.getFirst();
    // Serialize all claims for an account across workers. The joined SKIP LOCKED
    // query selects the candidate, while this explicit row lock closes the
    // snapshot race between two different jobs sharing the same account.
    List<Map<String, Object>> accountLock = db.queryForList(
        "select id from publisher_accounts where id=:accountId for update skip locked",
        new MapSqlParameterSource().addValue("accountId", row.get("account_id")));
    if (accountLock.isEmpty()) return null;
    Integer activeLeases = db.queryForObject("""
        select count(*) from publisher_jobs
        where account_id=:accountId and id<>:jobId
          and claimed_by is not null and lease_until > now()
          and status in ('CLAIMED','RUNNING','WAITING_LOGIN','WAITING_HUMAN')
        """, new MapSqlParameterSource().addValue("accountId", row.get("account_id")).addValue("jobId", row.get("job_id")), Integer.class);
    if (activeLeases != null && activeLeases > 0) return null;
    OffsetDateTime now = OffsetDateTime.now();
    OffsetDateTime lease = now.plusSeconds(Math.max(1, leaseSeconds));
    db.update("""
        update publisher_outbox set status='PROCESSING',claimed_by=:workerId,lease_until=:lease,
          attempt_count=attempt_count+1,updated_at=:now
        where id=:outboxId
        """, new MapSqlParameterSource().addValue("workerId", workerId).addValue("lease", lease)
        .addValue("now", now).addValue("outboxId", row.get("outbox_id")));
    db.update("""
        update publisher_jobs set claimed_by=:workerId,worker_id=:workerId,lease_until=:lease,heartbeat_at=:now,
          attempt_count=attempt_count+1,updated_at=:now,version_lock=version_lock+1
        where id=:jobId
        """, new MapSqlParameterSource().addValue("workerId", workerId).addValue("lease", lease)
        .addValue("now", now).addValue("jobId", row.get("job_id")));
    row.put("lease_until", lease);
    row.put("claimed_by", workerId);
    row.put("dispatch_sequence", ((Number) row.getOrDefault("last_callback_sequence", 0)).longValue());
    return row;
  }

  public int markSent(UUID outboxId, OffsetDateTime now) {
    return db.update("update publisher_outbox set status='SENT',lease_until=null,updated_at=:now where id=:id and status='PROCESSING'",
        new MapSqlParameterSource().addValue("id", outboxId).addValue("now", now));
  }

  public int markRetry(UUID outboxId, OffsetDateTime nextAttemptAt, String error, OffsetDateTime now) {
    return db.update("update publisher_outbox set status='PENDING',lease_until=null,next_attempt_at=:next,last_error=:error,updated_at=:now where id=:id and status in ('PROCESSING','SENT')",
        new MapSqlParameterSource().addValue("id", outboxId).addValue("next", nextAttemptAt)
            .addValue("error", error).addValue("now", now).addValue("id", outboxId));
  }

  public int markFailed(UUID outboxId, String error, OffsetDateTime now) {
    return db.update("update publisher_outbox set status='FAILED',lease_until=null,last_error=:error,updated_at=:now where id=:id and status='PROCESSING'",
        new MapSqlParameterSource().addValue("id", outboxId).addValue("error", error).addValue("now", now));
  }

  private MapSqlParameterSource params(UUID tenantId, UUID merchantId) {
    return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("merchantId", merchantId);
  }
}
