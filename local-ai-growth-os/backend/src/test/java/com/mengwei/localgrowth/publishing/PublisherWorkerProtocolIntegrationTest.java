package com.mengwei.localgrowth.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PublisherWorkerProtocolIntegrationTest {
  private static final String SECRET = "b2a-test-hmac-secret";
  private static final String WORKER_A = "worker-a";
  private static final String WORKER_B = "worker-b";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("publisher_protocol_test").withUsername("publisher_test").withPassword("publisher_test");
  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;

  private UUID tenantId, userId, merchantId, accountId, jobId;
  private String claimPath() { return "/api/internal/publisher-worker/claim"; }
  private String jobPath(String suffix) { return "/api/internal/publisher-worker/jobs/" + jobId + suffix; }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("app.auth-token", () -> "b2a-test-token");
    r.add("app.storage.endpoint", () -> "http://localhost:9000");
    r.add("app.storage.access-key", () -> "test-access");
    r.add("app.storage.secret-key", () -> "test-secret");
    r.add("app.storage.bucket", () -> "test-bucket");
    r.add("app.publisher-worker.hmac-secret", () -> SECRET);
    r.add("app.publisher-worker.enabled", () -> true);
    r.add("app.publisher-worker.id-allowlist", () -> WORKER_A + "," + WORKER_B);
    r.add("app.publisher-worker.lease-seconds", () -> 30);
    r.add("app.publisher-worker.replay-ttl-seconds", () -> 900);
  }

  @BeforeEach
  void setUp() {
    // Isolate each fixture so claimOne cannot select a pending job from an earlier test.
    db.update("update publisher_outbox set status='SENT', lease_until=null where status <> 'SENT'");
    db.update("update publisher_jobs set status='CANCELLED', claimed_by=null, worker_id=null, lease_until=null where status in ('QUEUED','CLAIMED','RUNNING','WAITING_LOGIN','WAITING_HUMAN','FAILED_RETRYABLE')");
    tenantId = UUID.randomUUID(); userId = UUID.randomUUID(); merchantId = UUID.randomUUID();
    accountId = UUID.randomUUID(); jobId = UUID.randomUUID();
    String now = "now()";
    db.update("insert into tenants(id,name,created_at,updated_at,version) values(?,?,now(),now(),0)", tenantId, "B2A test");
    db.update("insert into users(id,tenant_id,email,display_name,password_hash,role,created_at,updated_at,version) values(?,?,?,?,?,?,now(),now(),0)",
        userId, tenantId, "b2a-" + userId + "@test", "B2A", "x", "ADMIN");
    db.update("insert into merchants(id,tenant_id,name,industry,city,district,status,deleted,created_at,updated_at,created_by,version) values(?,?,?,?,?,?,?,false,now(),now(),?,0)",
        merchantId, tenantId, "B2A merchant", "TEST", "佛山", "南海", "ACTIVE", userId);
    UUID runId = UUID.randomUUID(), snapshotId = UUID.randomUUID(), planId = UUID.randomUUID(), workId = UUID.randomUUID(), briefId = UUID.randomUUID(), draftId = UUID.randomUUID();
    db.update("insert into diagnostic_runs(id,tenant_id,merchant_id,status,provider,sample_note,created_at,updated_at,created_by,version) values(?,?,?,?,?,?,now(),now(),?,0)",
        runId, tenantId, merchantId, "COMPLETED", "STUB", "B2A", userId);
    db.update("insert into geo_diagnosis_snapshots(id,tenant_id,merchant_id,task_diagnostic_run_id,rule_version,observation_count,config_snapshot,metric_snapshot,platform_snapshot,question_snapshot,competitor_snapshot,source_snapshot,gap_snapshot,strategy_snapshot,causality_notice,created_at,created_by) values(?,?,?,?,?,0,'{}','{}','{}','{}','{}','{}','{}','{}',?,now(),?)",
        snapshotId, tenantId, merchantId, runId, "B2A", "关联仅用于测试", userId);
    db.update("insert into geo_execution_plans(id,tenant_id,merchant_id,diagnosis_snapshot_id,idempotency_key,name,period_start,period_end,created_at,updated_at,created_by,version) values(?,?,?,?,?,?,current_date,current_date+30,now(),now(),?,0)",
        planId, tenantId, merchantId, snapshotId, "b2a-plan-" + planId, "B2A", userId);
    db.update("insert into geo_execution_work_items(id,tenant_id,merchant_id,execution_plan_id,task_type,title,created_at,updated_at,created_by,version) values(?,?,?,?,?,?,now(),now(),?,0)",
        workId, tenantId, merchantId, planId, "FAQ_CONTENT", "B2A", userId);
    db.update("insert into geo_content_briefs(id,tenant_id,merchant_id,work_item_id,content_goal,target_platform,content_type,created_at,updated_at,created_by) values(?,?,?,?,?,?,?,now(),now(),?)",
        briefId, tenantId, merchantId, workId, "B2A", "XIAOHONGSHU", "IMAGE_NOTE", userId);
    db.update("insert into m6_content_draft_versions(id,tenant_id,merchant_id,work_item_id,brief_id,draft_version,status,idempotency_key,prompt_version,title,body,created_at,updated_at,created_by) values(?,?,?,?,?,?,?, ?,?,?,?,now(),now(),?)",
        draftId, tenantId, merchantId, workId, briefId, 1, "APPROVED", "b2a-draft-" + draftId, "test", "标题", "正文", userId);
    db.update("insert into publisher_accounts(id,tenant_id,merchant_id,external_account_id,profile_ref,platform,display_name,default_publish_mode,status,created_at,updated_at,created_by,version_lock) values(?,?,?,?,?,'XIAOHONGSHU',?,'MANUAL_CONFIRM','ACTIVE',now(),now(),?,0)",
        accountId, tenantId, merchantId, "ext-" + accountId, "profile-" + accountId, "账号", userId);
    db.update("insert into publisher_jobs(id,tenant_id,merchant_id,account_id,source_type,source_draft_id,source_draft_version,idempotency_key,platform,content_type,publish_mode,status,title_snapshot,body_snapshot,structured_content_snapshot,topics_snapshot,content_hash,evidence_pack_hash,approved_by,approved_at,created_at,updated_at,created_by,version_lock) values(?,?,?,?, 'M6_DRAFT',?,?,?,'XIAOHONGSHU','IMAGE_NOTE','DRAFT','QUEUED',?,?, '{}'::jsonb,'[]'::jsonb,?, ?,?,now(),now(),now(),?,0)",
        jobId, tenantId, merchantId, accountId, draftId, 1, "b2a-job-" + jobId, "标题", "正文", "a".repeat(64), "b".repeat(64), userId, userId);
    db.update("insert into publisher_outbox(id,tenant_id,merchant_id,job_id,event_type,payload,deduplication_key,status,attempt_count,next_attempt_at,created_at,updated_at) values(?,?,?,?,?,'{}'::jsonb,?,'PENDING',0,now(),now(),now())",
        UUID.randomUUID(), tenantId, merchantId, jobId, "PUBLISH_JOB_QUEUED", "b2a-outbox-" + jobId);
  }

  @AfterEach
  void clean() {
    // The container is disposable; publisher_jobs are intentionally append-only and cannot be deleted.
  }

  @Test
  void concurrentClaimOnlyOneWorkerWinsAndLeavesProcessing() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Integer> a = pool.submit(() -> claimAfter(start, WORKER_A));
    Future<Integer> b = pool.submit(() -> claimAfter(start, WORKER_B));
    start.countDown();
    assertThat(a.get()).isEqualTo(200); assertThat(b.get()).isEqualTo(200);
    assertThat(db.queryForObject("select count(*) from publisher_jobs where id=? and claimed_by is not null", Integer.class, jobId)).isEqualTo(1);
    assertThat(db.queryForObject("select attempt_count from publisher_jobs where id=?", Integer.class, jobId)).isEqualTo(1);
    assertThat(db.queryForObject("select status from publisher_outbox where job_id=?", String.class, jobId)).isEqualTo("PROCESSING");
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("CLAIMED");
    pool.shutdownNow();
  }

  @Test
  void claimDoesNotMarkOutboxSent() throws Exception {
    assertThat(claim(WORKER_A).getResponse().getStatus()).isEqualTo(200);
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("CLAIMED");
    assertThat(db.queryForObject("select status from publisher_outbox where job_id=?", String.class, jobId)).isEqualTo("PROCESSING");
    assertThat(db.queryForObject("select count(*) from publisher_callback_events where job_id=?", Integer.class, jobId)).isZero();
    assertThat(db.queryForObject("select count(*) from publisher_job_events where job_id=? and event_type='WORKER_STATUS'", Integer.class, jobId)).isZero();
  }

  @Test
  void sameAccountCannotHoldTwoActiveLeases() throws Exception {
    UUID secondJob = UUID.randomUUID();
    UUID sourceDraft = db.queryForObject("select source_draft_id from publisher_jobs where id=?", UUID.class, jobId);
    db.update("insert into publisher_jobs(id,tenant_id,merchant_id,account_id,source_type,source_draft_id,source_draft_version,idempotency_key,platform,content_type,publish_mode,status,title_snapshot,body_snapshot,structured_content_snapshot,topics_snapshot,content_hash,evidence_pack_hash,approved_by,approved_at,created_at,updated_at,created_by,version_lock) values(?,?,?,?, 'M6_DRAFT',?,?,?,'XIAOHONGSHU','IMAGE_NOTE','DRAFT','QUEUED',?,?, '{}'::jsonb,'[]'::jsonb,?, ?,?,now(),now(),now(),?,0)",
        secondJob, tenantId, merchantId, accountId, sourceDraft, 1, "b2a-second-" + secondJob, "标题2", "正文2", "c".repeat(64), "d".repeat(64), userId, userId);
    db.update("insert into publisher_outbox(id,tenant_id,merchant_id,job_id,event_type,payload,deduplication_key,status,attempt_count,next_attempt_at,created_at,updated_at) values(?,?,?,?,?,'{}'::jsonb,?,'PENDING',0,now(),now(),now())",
        UUID.randomUUID(), tenantId, merchantId, secondJob, "PUBLISH_JOB_QUEUED", "b2a-second-outbox-" + secondJob);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<String> first = pool.submit(() -> { start.await(); return claim(WORKER_A).getResponse().getContentAsString(); });
    Future<String> second = pool.submit(() -> { start.await(); return claim(WORKER_B).getResponse().getContentAsString(); });
    start.countDown();
    String firstBody = first.get();
    String secondBody = second.get();
    assertThat((firstBody.contains("\"claimed\":true") ? 1 : 0)
        + (secondBody.contains("\"claimed\":true") ? 1 : 0)).isEqualTo(1);
    assertThat((firstBody.contains("\"claimed\":false") ? 1 : 0)
        + (secondBody.contains("\"claimed\":false") ? 1 : 0)).isEqualTo(1);
    pool.shutdownNow();
    assertThat(db.queryForObject("select count(*) from publisher_jobs where account_id=? and lease_until>now() and claimed_by is not null", Integer.class, accountId)).isEqualTo(1);
  }

  @Test
  void expiredLeaseCanBeReclaimedAndOldHeartbeatIsRejected() throws Exception {
    claim(WORKER_A);
    db.update("update publisher_jobs set lease_until=now()-interval '1 second' where id=?", jobId);
    db.update("update publisher_outbox set lease_until=now()-interval '1 second' where job_id=?", jobId);
    claim(WORKER_B);
    assertThat(db.queryForObject("select claimed_by from publisher_jobs where id=?", String.class, jobId)).isEqualTo(WORKER_B);
    assertThat(db.queryForObject("select count(*) from publisher_job_events where job_id=? and event_type='WORKER_REQUEUED'", Integer.class, jobId)).isEqualTo(1);
    assertThat(event(WORKER_A, 1, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(400);
    assertThat(db.queryForObject("select last_callback_sequence from publisher_jobs where id=?", Long.class, jobId)).isEqualTo(0L);
  }

  @Test
  void oldWorkerEventRejectedAfterLeaseReclaim() throws Exception {
    claim(WORKER_A);
    db.update("update publisher_jobs set lease_until=now()-interval '1 second' where id=?", jobId);
    db.update("update publisher_outbox set lease_until=now()-interval '1 second' where job_id=?", jobId);
    claim(WORKER_B);
    int callbacks = db.queryForObject("select count(*) from publisher_callback_events where job_id=?", Integer.class, jobId);
    int events = db.queryForObject("select count(*) from publisher_job_events where job_id=?", Integer.class, jobId);
    assertThat(event(WORKER_A, 1, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(400);
    assertThat(db.queryForObject("select count(*) from publisher_callback_events where job_id=?", Integer.class, jobId)).isEqualTo(callbacks);
    assertThat(db.queryForObject("select count(*) from publisher_job_events where job_id=?", Integer.class, jobId)).isEqualTo(events);
    assertThat(db.queryForObject("select last_callback_sequence from publisher_jobs where id=?", Long.class, jobId)).isZero();
  }

  @Test
  void runningConfirmsOutboxAndRepeatedRunningIsAllowed() throws Exception {
    claim(WORKER_A);
    long before = db.queryForObject("select extract(epoch from lease_until) from publisher_jobs where id=?", Number.class, jobId).longValue();
    assertThat(heartbeat(WORKER_A).getResponse().getStatus()).isEqualTo(200);
    long after = db.queryForObject("select extract(epoch from lease_until) from publisher_jobs where id=?", Number.class, jobId).longValue();
    assertThat(after).isGreaterThanOrEqualTo(before);
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("CLAIMED");
    assertThat(event(WORKER_A, 1, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(db.queryForObject("select status from publisher_outbox where job_id=?", String.class, jobId)).isEqualTo("SENT");
    assertThat(event(WORKER_A, 2, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(db.queryForObject("select count(*) from publisher_callback_events where job_id=?", Integer.class, jobId)).isEqualTo(2);
  }

  @Test
  void heartbeatRenewsCurrentLease() throws Exception {
    claim(WORKER_A);
    Number before = db.queryForObject("select extract(epoch from lease_until) from publisher_jobs where id=?", Number.class, jobId);
    assertThat(heartbeat(WORKER_A).getResponse().getStatus()).isEqualTo(200);
    Number after = db.queryForObject("select extract(epoch from lease_until) from publisher_jobs where id=?", Number.class, jobId);
    assertThat(after.doubleValue()).isGreaterThanOrEqualTo(before.doubleValue());
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("CLAIMED");
    assertThat(db.queryForObject("select status from publisher_outbox where job_id=?", String.class, jobId)).isEqualTo("PROCESSING");
    assertThat(db.queryForObject("select count(*) from publisher_job_events where job_id=? and event_type='WORKER_STATUS'", Integer.class, jobId)).isZero();
  }

  @Test
  void heartbeatRejectsInvalidOwnershipAndTerminalState() throws Exception {
    claim(WORKER_A);
    assertThat(heartbeat(WORKER_B).getResponse().getStatus()).isEqualTo(400);
    db.update("update publisher_jobs set lease_until=now()-interval '1 second' where id=?", jobId);
    assertThat(heartbeat(WORKER_A).getResponse().getStatus()).isEqualTo(400);

    setUp();
    claim(WORKER_A);
    assertThat(event(WORKER_A, 1, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(event(WORKER_A, 2, "DRAFT_SAVED", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(heartbeat(WORKER_A).getResponse().getStatus()).isEqualTo(400);

    setUp();
    claim(WORKER_A);
    assertThat(event(WORKER_A, 1, "FAILED_FINAL", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(heartbeat(WORKER_A).getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void sequenceReplayAndForbiddenStatusesHaveNoSideEffects() throws Exception {
    claim(WORKER_A);
    assertThat(event(WORKER_A, 1, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    int callbacks = db.queryForObject("select count(*) from publisher_callback_events where job_id=?", Integer.class, jobId);
    assertThat(event(WORKER_A, 1, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(400);
    assertThat(event(WORKER_A, 0, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(400);
    assertThat(event(WORKER_A, 2, "PUBLISHED", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(400);
    assertThat(event(WORKER_A, 2, "NOT_A_STATUS", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(400);
    assertThat(db.queryForObject("select count(*) from publisher_callback_events where job_id=?", Integer.class, jobId)).isEqualTo(callbacks);
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("RUNNING");
  }

  @Test
  void invalidTransitionRollsBackCallbackAndJobChanges() throws Exception {
    claim(WORKER_A);
    assertThat(event(WORKER_A, 1, "WAITING_HUMAN", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(400);
    assertThat(db.queryForObject("select count(*) from publisher_callback_events where job_id=?", Integer.class, jobId)).isZero();
    assertThat(db.queryForObject("select count(*) from publisher_job_events where job_id=? and event_type='WORKER_STATUS'", Integer.class, jobId)).isZero();
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("CLAIMED");
    assertThat(db.queryForObject("select status from publisher_outbox where job_id=?", String.class, jobId)).isEqualTo("PROCESSING");
  }

  @Test
  void requestAndNonceReplayAreRejected() throws Exception {
    claim(WORKER_A, "same-request", "nonce-1");
    assertThat(claim(WORKER_A, "same-request", "nonce-2").getResponse().getStatus()).isEqualTo(401);
    assertThat(claim(WORKER_A, "different-request", "nonce-1").getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void invalidSignatureDoesNotChangeDatabase() throws Exception {
    int events = db.queryForObject("select count(*) from publisher_job_events", Integer.class);
    MvcResult r = mvc.perform(post(claimPath()).contentType(MediaType.APPLICATION_JSON).content("{}").header("X-Worker-Id", WORKER_A)
        .header("X-Request-Id", UUID.randomUUID().toString()).header("X-Timestamp", Long.toString(System.currentTimeMillis() / 1000))
        .header("X-Nonce", UUID.randomUUID().toString()).header("X-Body-Sha256", "0".repeat(64)).header("X-Signature", "bad")).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(401);
    assertThat(db.queryForObject("select count(*) from publisher_job_events", Integer.class)).isEqualTo(events);
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("QUEUED");
  }

  @Test
  void authenticationFailuresHaveNoDatabaseSideEffects() throws Exception {
    int callbacks = db.queryForObject("select count(*) from publisher_callback_events", Integer.class);
    int events = db.queryForObject("select count(*) from publisher_job_events", Integer.class);
    int attempts = db.queryForObject("select attempt_count from publisher_jobs where id=?", Integer.class, jobId);

    String body = "{}";
    var wrongBody = headers(claimPath(), body, WORKER_A, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    wrongBody.set("X-Body-Sha256", "0".repeat(64));
    assertThat(mvc.perform(post(claimPath()).contentType(MediaType.APPLICATION_JSON).content(body).headers(wrongBody)).andReturn().getResponse().getStatus()).isEqualTo(401);

    var expired = headers(claimPath(), body, WORKER_A, UUID.randomUUID().toString(), UUID.randomUUID().toString(), System.currentTimeMillis() / 1000 - 10_000);
    assertThat(mvc.perform(post(claimPath()).contentType(MediaType.APPLICATION_JSON).content(body).headers(expired)).andReturn().getResponse().getStatus()).isEqualTo(401);

    var notAllowed = headers(claimPath(), body, "not-allowlisted", UUID.randomUUID().toString(), UUID.randomUUID().toString());
    assertThat(mvc.perform(post(claimPath()).contentType(MediaType.APPLICATION_JSON).content(body).headers(notAllowed)).andReturn().getResponse().getStatus()).isEqualTo(401);

    var wrongHmac = headers(claimPath(), body, WORKER_A, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    wrongHmac.set("X-Signature", "bad");
    assertThat(mvc.perform(post(claimPath()).contentType(MediaType.APPLICATION_JSON).content(body).headers(wrongHmac)).andReturn().getResponse().getStatus()).isEqualTo(401);

    assertThat(db.queryForObject("select count(*) from publisher_callback_events", Integer.class)).isEqualTo(callbacks);
    assertThat(db.queryForObject("select count(*) from publisher_job_events", Integer.class)).isEqualTo(events);
    assertThat(db.queryForObject("select attempt_count from publisher_jobs where id=?", Integer.class, jobId)).isEqualTo(attempts);
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("QUEUED");
  }

  @Test
  void workerIdTamperingInvalidatesSignatureWithoutSideEffects() throws Exception {
    int callbacks = db.queryForObject("select count(*) from publisher_callback_events", Integer.class);
    int events = db.queryForObject("select count(*) from publisher_job_events", Integer.class);
    var signedForA = headers(claimPath(), "{}", WORKER_A, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    signedForA.set("X-Worker-Id", WORKER_B);
    assertThat(mvc.perform(post(claimPath()).contentType(MediaType.APPLICATION_JSON).content("{}").headers(signedForA))
        .andReturn().getResponse().getStatus()).isEqualTo(401);
    assertThat(db.queryForObject("select count(*) from publisher_callback_events", Integer.class)).isEqualTo(callbacks);
    assertThat(db.queryForObject("select count(*) from publisher_job_events", Integer.class)).isEqualTo(events);
    assertThat(db.queryForObject("select attempt_count from publisher_jobs where id=?", Integer.class, jobId)).isZero();
  }

  @Test
  void queryParametersAreRejectedBeforeWorkerBusinessLogic() throws Exception {
    int events = db.queryForObject("select count(*) from publisher_job_events", Integer.class);
    var signed = headers(claimPath(), "{}", WORKER_A, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    assertThat(mvc.perform(post(claimPath() + "?unexpected=1").contentType(MediaType.APPLICATION_JSON).content("{}").headers(signed))
        .andReturn().getResponse().getStatus()).isEqualTo(400);
    assertThat(db.queryForObject("select count(*) from publisher_job_events", Integer.class)).isEqualTo(events);
    assertThat(db.queryForObject("select attempt_count from publisher_jobs where id=?", Integer.class, jobId)).isZero();
  }

  @Test
  void transactionRollsBackWhenDatabaseFailsAfterPartialWrites() throws Exception {
    claim(WORKER_A);
    int callbacks = db.queryForObject("select count(*) from publisher_callback_events where job_id=?", Integer.class, jobId);
    int events = db.queryForObject("select count(*) from publisher_job_events where job_id=?", Integer.class, jobId);
    db.execute("create or replace function b2a_test_fail_event() returns trigger language plpgsql as $$ begin raise exception 'B2A_TEST_EVENT_FAILURE'; end; $$");
    db.execute("create trigger b2a_test_fail_event_trigger before insert on publisher_job_events for each row execute function b2a_test_fail_event()");
    try {
      assertThat(event(WORKER_A, 1, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    } finally {
      db.execute("drop trigger if exists b2a_test_fail_event_trigger on publisher_job_events");
      db.execute("drop function if exists b2a_test_fail_event()");
    }
    assertThat(db.queryForObject("select count(*) from publisher_callback_events where job_id=?", Integer.class, jobId)).isEqualTo(callbacks);
    assertThat(db.queryForObject("select count(*) from publisher_job_events where job_id=?", Integer.class, jobId)).isEqualTo(events);
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("CLAIMED");
    assertThat(db.queryForObject("select last_callback_sequence from publisher_jobs where id=?", Long.class, jobId)).isZero();
    assertThat(db.queryForObject("select status from publisher_outbox where job_id=?", String.class, jobId)).isEqualTo("PROCESSING");
    assertThat(db.queryForObject("select claimed_by from publisher_jobs where id=?", String.class, jobId)).isEqualTo(WORKER_A);
  }

  @Test
  void draftSavedIsTerminalAndFailedRetryableCanBeRequeued() throws Exception {
    claim(WORKER_A);
    assertThat(event(WORKER_A, 1, "RUNNING", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(event(WORKER_A, 2, "DRAFT_SAVED", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("DRAFT_SAVED");
    assertThat(db.queryForObject("select lease_until from publisher_jobs where id=?", java.sql.Timestamp.class, jobId)).isNull();
    assertThat(heartbeat(WORKER_A).getResponse().getStatus()).isEqualTo(400);

    setUp();
    claim(WORKER_A);
    assertThat(event(WORKER_A, 1, "FAILED_RETRYABLE", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("FAILED_RETRYABLE");
    assertThat(db.queryForObject("select status from publisher_outbox where job_id=?", String.class, jobId)).isEqualTo("PENDING");
    assertThat(db.queryForObject("select lease_until from publisher_jobs where id=?", java.sql.Timestamp.class, jobId)).isNull();
  }

  @Test
  void failedFinalIsTerminal() throws Exception {
    claim(WORKER_A);
    assertThat(event(WORKER_A, 1, "FAILED_FINAL", UUID.randomUUID()).getResponse().getStatus()).isEqualTo(200);
    assertThat(db.queryForObject("select status from publisher_jobs where id=?", String.class, jobId)).isEqualTo("FAILED_FINAL");
    assertThat(db.queryForObject("select status from publisher_outbox where job_id=?", String.class, jobId)).isEqualTo("FAILED");
    assertThat(db.queryForObject("select lease_until from publisher_jobs where id=?", java.sql.Timestamp.class, jobId)).isNull();
    assertThat(claim(WORKER_B).getResponse().getContentAsString()).contains("\"claimed\":false");
    assertThat(heartbeat(WORKER_A).getResponse().getStatus()).isEqualTo(400);
  }

  private int claimAfter(CountDownLatch start, String worker) throws Exception { start.await(); return claim(worker).getResponse().getStatus(); }
  private MvcResult claim(String worker) throws Exception { return claim(worker, UUID.randomUUID().toString(), UUID.randomUUID().toString()); }
  private MvcResult claim(String worker, String request, String nonce) throws Exception {
    return mvc.perform(post(claimPath()).contentType(MediaType.APPLICATION_JSON).content("{}").headers(headers(claimPath(), "{}", worker, request, nonce))).andReturn();
  }
  private MvcResult event(String worker, long sequence, String status, UUID eventId) throws Exception {
    String body = "{\"eventId\":\"" + eventId + "\",\"sequenceNo\":" + sequence + ",\"status\":\"" + status + "\",\"payload\":{}}";
    return mvc.perform(post(jobPath("/events")).contentType(MediaType.APPLICATION_JSON).content(body).headers(headers(jobPath("/events"), body, worker,
        UUID.randomUUID().toString(), UUID.randomUUID().toString()))).andReturn();
  }
  private MvcResult heartbeat(String worker) throws Exception {
    String body = "{}";
    return mvc.perform(post(jobPath("/heartbeat")).contentType(MediaType.APPLICATION_JSON).content(body)
        .headers(headers(jobPath("/heartbeat"), body, worker, UUID.randomUUID().toString(), UUID.randomUUID().toString()))).andReturn();
  }
  private org.springframework.http.HttpHeaders headers(String path, String body, String worker, String request, String nonce) throws Exception {
    return headers(path, body, worker, request, nonce, System.currentTimeMillis() / 1000);
  }
  private org.springframework.http.HttpHeaders headers(String path, String body, String worker, String request, String nonce, long timestamp) throws Exception {
    String hash = sha(body);
    String canonical = String.join("\n", "POST", path, worker, request, Long.toString(timestamp), nonce, hash);
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
    h.add("X-Worker-Id", worker); h.add("X-Request-Id", request); h.add("X-Timestamp", Long.toString(timestamp));
    h.add("X-Nonce", nonce); h.add("X-Body-Sha256", hash); h.add("X-Signature", Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
    return h;
  }
  private String sha(String value) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
}
