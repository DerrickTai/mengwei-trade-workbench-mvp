package com.mengwei.localgrowth.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.audit.AuditService;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublisherService {
  private static final Set<String> SAFE_CONFIG_KEYS = Set.of("locale", "timezone", "contentCategory");
  private static final Set<String> SENSITIVE_TERMS = Set.of("cookie", "password", "token", "localstorage", "authorization", "session");

  private final NamedParameterJdbcTemplate db;
  private final ObjectMapper json;
  private final AuditService audit;

  public PublisherService(NamedParameterJdbcTemplate db, ObjectMapper json, AuditService audit) {
    this.db = db;
    this.json = json;
    this.audit = audit;
  }

  @Transactional
  public Map<String, Object> createAccount(Identity identity, UUID merchantId, AccountInput input) {
    merchant(identity, merchantId);
    requireText(input.displayName(), "displayName");
    requireText(input.profileRef(), "profileRef");
    PublishMode mode = parseMode(input.defaultPublishMode());
    Map<String, Object> config = safeConfig(input.nonSensitiveConfig());
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    db.update("""
        insert into publisher_accounts(
          id,tenant_id,merchant_id,external_account_id,profile_ref,credential_ref,platform,
          display_name,default_publish_mode,status,non_sensitive_config,deleted,created_at,updated_at,
          created_by,updated_by,version_lock)
        values(:id,:tenantId,:merchantId,:externalAccountId,:profileRef,:credentialRef,'XIAOHONGSHU',
          :displayName,:publishMode,'ACTIVE',cast(:config as jsonb),false,:now,:now,:user,:user,0)
        """, params(identity, merchantId)
        .addValue("id", id).addValue("externalAccountId", input.externalAccountId())
        .addValue("profileRef", input.profileRef()).addValue("credentialRef", input.credentialRef())
        .addValue("displayName", input.displayName()).addValue("publishMode", mode.name())
        .addValue("config", writeJson(config)).addValue("now", now).addValue("user", identity.userId()));
    audit.log(identity.tenantId(), identity.userId(), "PUBLISHER_ACCOUNT_CREATED", "PublisherAccount", id,
        "创建小红书发布账号元数据");
    return account(identity, merchantId, id);
  }

  public List<Map<String, Object>> accounts(Identity identity, UUID merchantId) {
    merchant(identity, merchantId);
    return db.queryForList("""
        select id,platform,display_name as display_name,external_account_id,profile_ref,
          credential_ref,default_publish_mode,status,non_sensitive_config,last_login_checked_at,
          last_login_error,created_at,updated_at
        from publisher_accounts
        where tenant_id=:tenantId and merchant_id=:merchantId and deleted=false
        order by created_at desc
        """, params(identity, merchantId));
  }

  @Transactional
  public Map<String, Object> deactivateAccount(Identity identity, UUID merchantId, UUID accountId) {
    Map<String, Object> current = account(identity, merchantId, accountId);
    db.update("""
        update publisher_accounts set status='DISABLED',deleted=true,updated_at=:now,updated_by=:user,
          version_lock=version_lock+1
        where id=:accountId and tenant_id=:tenantId and merchant_id=:merchantId and deleted=false
        """, params(identity, merchantId).addValue("accountId", accountId)
        .addValue("now", OffsetDateTime.now()).addValue("user", identity.userId()));
    audit.log(identity.tenantId(), identity.userId(), "PUBLISHER_ACCOUNT_DEACTIVATED", "PublisherAccount", accountId,
        "停用发布账号：" + current.get("display_name"));
    return account(identity, merchantId, accountId, true);
  }

  @Transactional
  public Map<String, Object> createJob(Identity identity, UUID merchantId, JobInput input) {
    merchant(identity, merchantId);
    requireText(input.idempotencyKey(), "idempotencyKey");
    if (input.idempotencyKey().trim().length() < 16 || input.idempotencyKey().trim().length() > 128) {
      throw bad("INVALID_IDEMPOTENCY_KEY", "idempotencyKey 长度必须为16至128个字符");
    }
    if (input.assetIds() != null && !input.assetIds().isEmpty()) {
      throw bad("PUBLISH_ASSET_AUTHORIZATION_UNAVAILABLE", "当前素材缺少可靠的商家授权关系，请先创建纯文本发布任务");
    }
    PublishMode mode = parseMode(input.publishMode());
    Map<String, Object> account = account(identity, merchantId, input.accountId());
    if (!"ACTIVE".equals(String.valueOf(account.get("status")))) {
      throw bad("PUBLISHER_ACCOUNT_NOT_ACTIVE", "只有 ACTIVE 发布账号可以创建任务");
    }
    Map<String, Object> draft = approvedDraft(identity, merchantId, input.sourceDraftId());
    Map<String, Object> approval = approval(identity, merchantId, input.sourceDraftId());
    String title = text(draft.get("title"));
    String body = text(draft.get("body"));
    if (title.isBlank() || body.isBlank()) throw bad("PUBLISH_DRAFT_CONTENT_MISSING", "已审核草稿缺少标题或正文");
    String structured = jsonText(draft.get("structured_content"), "{}");
    String topics = "[]";
    String contentHash = hashJson(Map.of("title", title, "body", body, "structuredContent", parseJson(structured), "topics", parseJson(topics)));
    String evidencePack = jsonText(draft.get("evidence_pack"), "[]");
    String evidenceHash = hashJson(parseJson(evidencePack));
    UUID jobId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    MapSqlParameterSource p = params(identity, merchantId).addValue("jobId", jobId)
        .addValue("accountId", input.accountId()).addValue("sourceDraftId", input.sourceDraftId())
        .addValue("sourceDraftVersion", draft.get("draft_version")).addValue("idempotencyKey", input.idempotencyKey().trim())
        .addValue("mode", mode.name()).addValue("title", title).addValue("body", body)
        .addValue("structured", structured).addValue("topics", topics).addValue("contentHash", contentHash)
        .addValue("evidenceHash", evidenceHash).addValue("approvedBy", approval.get("approved_by"))
        .addValue("approvedAt", approval.get("approved_at")).addValue("now", now).addValue("user", identity.userId());
    int inserted = db.update("""
        insert into publisher_jobs(
          id,tenant_id,merchant_id,account_id,source_type,source_draft_id,source_draft_version,
          idempotency_key,platform,content_type,publish_mode,status,title_snapshot,body_snapshot,
          structured_content_snapshot,topics_snapshot,content_hash,evidence_pack_hash,approved_by,approved_at,
          created_at,updated_at,created_by,updated_by,version_lock)
        values(:jobId,:tenantId,:merchantId,:accountId,'M6_DRAFT',:sourceDraftId,:sourceDraftVersion,
          :idempotencyKey,'XIAOHONGSHU','IMAGE_NOTE',:mode,'PENDING',:title,:body,
          cast(:structured as jsonb),cast(:topics as jsonb),:contentHash,:evidenceHash,:approvedBy,:approvedAt,
          :now,:now,:user,:user,0)
        on conflict (tenant_id,merchant_id,idempotency_key) do nothing
        """, p);
    if (inserted == 0) return job(identity, merchantId, input.idempotencyKey(), true);
    appendEvent(identity, merchantId, jobId, 1, "JOB_CREATED", "PENDING", "发布任务已创建");
    db.update("""
        insert into publisher_outbox(id,tenant_id,merchant_id,job_id,event_type,payload,deduplication_key,
          status,attempt_count,next_attempt_at,created_at,updated_at)
        values(:outboxId,:tenantId,:merchantId,:jobId,'PUBLISH_JOB_QUEUED',cast(:payload as jsonb),:dedup,
          'PENDING',0,:now,:now,:now)
        on conflict (tenant_id,deduplication_key) do nothing
        """, params(identity, merchantId).addValue("outboxId", UUID.randomUUID()).addValue("jobId", jobId)
        .addValue("payload", writeJson(Map.of("jobId", jobId, "publishMode", mode.name())))
        .addValue("dedup", "publisher-job:" + jobId + ":QUEUED").addValue("now", now));
    db.update("""
        update publisher_jobs set status='QUEUED',updated_at=:now,updated_by=:user,version_lock=version_lock+1
        where id=:jobId and tenant_id=:tenantId and merchant_id=:merchantId and status='PENDING'
        """, params(identity, merchantId).addValue("jobId", jobId).addValue("now", now).addValue("user", identity.userId()));
    appendEvent(identity, merchantId, jobId, 2, "JOB_QUEUED", "QUEUED", "任务已排队，尚未连接发布 Worker");
    audit.log(identity.tenantId(), identity.userId(), "PUBLISHER_JOB_CREATED", "PublisherJob", jobId,
        "创建安全发布任务，状态 QUEUED");
    return job(identity, merchantId, jobId, false);
  }

  public List<Map<String, Object>> jobs(Identity identity, UUID merchantId) {
    merchant(identity, merchantId);
    return db.queryForList("""
        select j.id,j.platform,j.publish_mode,j.status,j.title_snapshot,j.content_hash,j.evidence_pack_hash,
          j.approved_by,j.approved_at,j.created_at,j.updated_at,a.display_name as account_name
        from publisher_jobs j join publisher_accounts a on a.id=j.account_id
        where j.tenant_id=:tenantId and j.merchant_id=:merchantId order by j.created_at desc
        """, params(identity, merchantId));
  }

  public Map<String, Object> job(Identity identity, UUID merchantId, UUID jobId) {
    Map<String, Object> result = job(identity, merchantId, jobId, false);
    return result;
  }

  private Map<String, Object> job(Identity identity, UUID merchantId, String key, boolean replay) {
    List<Map<String, Object>> rows = db.queryForList("""
        select id from publisher_jobs where tenant_id=:tenantId and merchant_id=:merchantId and idempotency_key=:key
        """, params(identity, merchantId).addValue("key", key));
    if (rows.isEmpty()) throw bad("PUBLISH_JOB_NOT_FOUND", "发布任务不存在");
    return job(identity, merchantId, (UUID) rows.getFirst().get("id"), replay);
  }

  private Map<String, Object> job(Identity identity, UUID merchantId, UUID jobId, boolean replay) {
    List<Map<String, Object>> rows = db.queryForList("""
        select j.*,a.display_name as account_name from publisher_jobs j
        join publisher_accounts a on a.id=j.account_id
        where j.id=:jobId and j.tenant_id=:tenantId and j.merchant_id=:merchantId
        """, params(identity, merchantId).addValue("jobId", jobId));
    if (rows.isEmpty()) throw bad("PUBLISH_JOB_NOT_FOUND", "发布任务不存在或无权访问");
    Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
    result.put("events", db.queryForList("""
        select id,sequence_no,event_type,status,message,payload,occurred_at,created_at,created_by
        from publisher_job_events where tenant_id=:tenantId and merchant_id=:merchantId and job_id=:jobId
        order by sequence_no
        """, params(identity, merchantId).addValue("jobId", jobId)));
    result.put("idempotentReplay", replay);
    return result;
  }

  private void appendEvent(Identity identity, UUID merchantId, UUID jobId, long sequence, String type,
      String status, String message) {
    db.update("""
        insert into publisher_job_events(id,tenant_id,merchant_id,job_id,sequence_no,event_type,status,message,
          payload,occurred_at,created_at,created_by)
        values(:id,:tenantId,:merchantId,:jobId,:sequence,:type,:status,:message,cast(:payload as jsonb),:now,:now,:user)
        """, params(identity, merchantId).addValue("id", UUID.randomUUID()).addValue("jobId", jobId)
        .addValue("sequence", sequence).addValue("type", type).addValue("status", status)
        .addValue("message", message).addValue("payload", "{}").addValue("now", OffsetDateTime.now())
        .addValue("user", identity.userId()));
  }

  private Map<String, Object> approvedDraft(Identity identity, UUID merchantId, UUID draftId) {
    List<Map<String, Object>> rows = db.queryForList("""
        select d.*,b.target_platform,b.content_type as brief_content_type
        from m6_content_draft_versions d join geo_content_briefs b on b.id=d.brief_id
        where d.id=:draftId and d.tenant_id=:tenantId and d.merchant_id=:merchantId
        """, params(identity, merchantId).addValue("draftId", draftId));
    if (rows.isEmpty()) throw bad("M6_DRAFT_NOT_FOUND", "草稿不存在或无权访问");
    Map<String, Object> draft = rows.getFirst();
    if (!"APPROVED".equals(String.valueOf(draft.get("status")))) throw bad("M6_DRAFT_NOT_APPROVED", "只有 APPROVED 草稿可以创建发布任务");
    if (!"XIAOHONGSHU".equalsIgnoreCase(String.valueOf(draft.get("target_platform")))) {
      throw bad("PUBLISH_PLATFORM_UNSUPPORTED", "当前只支持 XIAOHONGSHU 草稿");
    }
    return draft;
  }

  private Map<String, Object> approval(Identity identity, UUID merchantId, UUID draftId) {
    List<Map<String, Object>> rows = db.queryForList("""
        select a.actor_id as approved_by,a.created_at as approved_at
        from audit_logs a join m6_content_draft_versions d on d.id=a.entity_id
        where a.tenant_id=:tenantId and d.tenant_id=:tenantId and d.merchant_id=:merchantId
          and a.entity_type='M6ContentDraft' and a.action='M6_DRAFT_APPROVE' and a.entity_id=:draftId
          and a.actor_id is not null order by a.created_at desc limit 1
        """, params(identity, merchantId).addValue("draftId", draftId));
    if (rows.isEmpty()) throw bad("M6_DRAFT_APPROVAL_EVIDENCE_MISSING", "缺少 M6_DRAFT_APPROVE 审批记录");
    return rows.getFirst();
  }

  private Map<String, Object> account(Identity identity, UUID merchantId, UUID accountId) {
    return account(identity, merchantId, accountId, false);
  }

  private Map<String, Object> account(Identity identity, UUID merchantId, UUID accountId, boolean includeDeleted) {
    String deleted = includeDeleted ? "" : " and deleted=false";
    List<Map<String, Object>> rows = db.queryForList("select * from publisher_accounts where id=:accountId and tenant_id=:tenantId and merchant_id=:merchantId" + deleted,
        params(identity, merchantId).addValue("accountId", accountId));
    if (rows.isEmpty()) throw bad("PUBLISHER_ACCOUNT_NOT_FOUND", "发布账号不存在或无权访问");
    return rows.getFirst();
  }

  private void merchant(Identity identity, UUID merchantId) {
    Integer count = db.queryForObject("select count(*) from merchants where id=:merchantId and tenant_id=:tenantId and deleted=false",
        params(identity, merchantId), Integer.class);
    if (count == null || count == 0) throw bad("NOT_FOUND", "数据不存在或无权访问");
  }

  private PublishMode parseMode(String value) {
    try { return PublishMode.valueOf(value == null ? "" : value); }
    catch (Exception e) { throw bad("INVALID_PUBLISH_MODE", "publishMode 只支持 DRAFT 或 MANUAL_CONFIRM"); }
  }

  private Map<String, Object> safeConfig(Map<String, Object> input) {
    Map<String, Object> config = new LinkedHashMap<>();
    if (input == null) return config;
    for (Map.Entry<String, Object> entry : input.entrySet()) {
      if (!SAFE_CONFIG_KEYS.contains(entry.getKey()) || entry.getValue() == null || !(entry.getValue() instanceof String)) {
        throw bad("NON_SENSITIVE_CONFIG_INVALID", "nonSensitiveConfig 含有不允许的字段");
      }
      String key = entry.getKey().toLowerCase();
      String value = String.valueOf(entry.getValue()).toLowerCase();
      if (SENSITIVE_TERMS.stream().anyMatch(term -> key.contains(term) || value.contains(term))) {
        throw bad("NON_SENSITIVE_CONFIG_INVALID", "nonSensitiveConfig 不得包含凭证内容");
      }
      config.put(entry.getKey(), entry.getValue());
    }
    return config;
  }

  private void requireText(String value, String field) { if (value == null || value.isBlank()) throw bad("VALIDATION_ERROR", field + " 不能为空"); }
  private String text(Object value) { return value == null ? "" : String.valueOf(value); }
  private String jsonText(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
  private JsonNode parseJson(String value) { try { return json.readTree(value == null ? "null" : value); } catch (Exception e) { return json.getNodeFactory().textNode(value == null ? "" : value); } }
  private String hashJson(Object value) { try { return sha(json.writeValueAsBytes(value)); } catch (Exception e) { throw new IllegalStateException(e); } }
  private String sha(byte[] value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception e) { throw new IllegalStateException(e); } }
  private String writeJson(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
  private MapSqlParameterSource params(Identity identity, UUID merchantId) { return new MapSqlParameterSource().addValue("tenantId", identity.tenantId()).addValue("merchantId", merchantId); }
  private ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }

  public record AccountInput(String displayName, String externalAccountId, String profileRef,
      String credentialRef, String defaultPublishMode, Map<String, Object> nonSensitiveConfig) {}
  public record JobInput(UUID sourceDraftId, UUID accountId, String publishMode, List<UUID> assetIds,
      String idempotencyKey) {}
}
