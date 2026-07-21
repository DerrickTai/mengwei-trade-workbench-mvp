package com.mengwei.localgrowth.contentexecution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.audit.AuditService;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.model.ModelProviderPort;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class M61DraftService {
  private final NamedParameterJdbcTemplate db;
  private final ObjectMapper json;
  private final ModelProviderPort ai;
  private final AuditService audit;

  public M61DraftService(
      NamedParameterJdbcTemplate db,
      ObjectMapper json,
      @Qualifier("openAiCompatibleProvider") ModelProviderPort ai,
      AuditService audit) {
    this.db = db;
    this.json = json;
    this.ai = ai;
    this.audit = audit;
  }

  @Transactional
  public Map<String, Object> generate(Identity identity, UUID merchantId, UUID briefId, String key) {
    Map<String, Object> brief = brief(identity, merchantId, briefId);
    var existing = db.queryForList(
        "select * from m6_content_draft_versions where tenant_id=:t and merchant_id=:m and idempotency_key=:k",
        parameters(identity, merchantId).addValue("k", key));
    if (!existing.isEmpty()) return existing.getFirst();

    List<Map<String, Object>> evidencePack = evidence(identity, merchantId);
    String evidenceHash = stableHash(json, evidencePack);
    UUID id = UUID.randomUUID();
    int draftVersion = db.queryForObject(
        "select coalesce(max(draft_version),0)+1 from m6_content_draft_versions where brief_id=:b",
        new MapSqlParameterSource("b", briefId), Integer.class);

    Map<String, Object> claims = Map.of();
    Map<String, Object> risk = Map.of();
    String status = "FAILED";
    String title = null;
    String body = null;
    String structured = "{}";
    String model = null;
    String error = null;
    try {
      if (evidencePack.isEmpty()) {
        throw bad("EVIDENCE_PACK_EMPTY", "当前没有足够的已核验证据，事实性内容将被阻断");
      }
      String prompt = "仅返回JSON {title,summary,sections,faqItems,callToAction}。商家官方FAQ，不得编造。"
          + "每个事实性claim必须在evidenceIds中引用如下证据ID：" + json.writeValueAsString(evidencePack)
          + "。Brief：" + brief.get("content_goal");
      var output = ai.invoke(prompt, "", "", "");
      model = output.model();
      Map<String, Object> parsed = parse(output.text());
      title = String.valueOf(parsed.getOrDefault("title", ""));
      body = json.writeValueAsString(parsed);
      structured = body;
      claims = claimCheck(parsed, evidencePack);
      if (Boolean.TRUE.equals(claims.get("blocked"))) {
        status = "EVIDENCE_BLOCKED";
      } else {
        var scan = new ContentRiskScanner().scan(
            Map.of("title", title, "body", body), riskRules(brief));
        risk = Map.of(
            "status", scan.status().name(),
            "findings", scan.findings(),
            "contentHash", scan.contentHash(),
            "ruleSetHash", scan.ruleSetHash());
        status = scan.status() == ContentRiskScanResult.Status.BLOCKED ? "RISK_BLOCKED" : "DRAFT";
      }
    } catch (ApiException exception) {
      error = exception.code();
    } catch (Exception exception) {
      error = "MODEL_RESPONSE_INVALID";
    }

    OffsetDateTime now = OffsetDateTime.now();
    db.update("""
        insert into m6_content_draft_versions(
          id,tenant_id,merchant_id,work_item_id,brief_id,draft_version,status,idempotency_key,
          provider_code,model_name,prompt_version,title,body,structured_content,evidence_pack,
          evidence_pack_hash,claim_evidence_result,risk_scan_result,generation_metadata,
          created_at,updated_at,created_by,updated_by)
        values(
          :id,:t,:m,:w,:b,:v,:s,:k,:provider,:model,'m6-faq-v1',:title,:body,
          cast(:structured as jsonb),cast(:pack as jsonb),:hash,cast(:claims as jsonb),
          cast(:risk as jsonb),cast(:meta as jsonb),:now,:now,:user,:user)
        """, parameters(identity, merchantId)
        .addValue("id", id).addValue("w", brief.get("work_item_id")).addValue("b", briefId)
        .addValue("v", draftVersion).addValue("s", status).addValue("k", key)
        .addValue("provider", ai.name()).addValue("model", model).addValue("title", title)
        .addValue("body", body).addValue("structured", structured)
        .addValue("pack", jsonString(evidencePack)).addValue("hash", evidenceHash)
        .addValue("claims", jsonString(claims)).addValue("risk", jsonString(risk))
        .addValue("meta", jsonString(Map.of("error", error == null ? "" : error)))
        .addValue("now", now).addValue("user", identity.userId()));
    audit.log(identity.tenantId(), identity.userId(), "M6_DRAFT_GENERATED", "M6ContentDraft", id,
        "status=" + status);
    return draft(identity, merchantId, id);
  }

  public List<Map<String, Object>> drafts(Identity identity, UUID merchantId, UUID briefId) {
    brief(identity, merchantId, briefId);
    return db.queryForList("""
        select * from m6_content_draft_versions
        where tenant_id=:t and merchant_id=:m and brief_id=:b order by draft_version desc
        """, parameters(identity, merchantId).addValue("b", briefId));
  }

  public Map<String, Object> draft(Identity identity, UUID merchantId, UUID id) {
    return one("select * from m6_content_draft_versions where id=:id and tenant_id=:t and merchant_id=:m",
        parameters(identity, merchantId).addValue("id", id));
  }

  @Transactional
  public Map<String, Object> review(
      Identity identity, UUID merchantId, UUID id, String action, String comment) {
    Map<String, Object> current = draft(identity, merchantId, id);
    String next = reviewTransition(String.valueOf(current.get("status")), action);
    db.update("""
        update m6_content_draft_versions
        set status=:status,review_comment=:comment,updated_at=:now,updated_by=:user,
            version_lock=version_lock+1
        where id=:id and tenant_id=:t and merchant_id=:m
        """, parameters(identity, merchantId).addValue("status", next).addValue("comment", comment)
        .addValue("now", OffsetDateTime.now()).addValue("user", identity.userId()).addValue("id", id));
    audit.log(identity.tenantId(), identity.userId(), "M6_DRAFT_" + action,
        "M6ContentDraft", id, "status=" + next);
    return draft(identity, merchantId, id);
  }

  static String reviewTransition(String current, String action) {
    if ("SUBMIT".equals(action) && "DRAFT".equals(current)) return "REVIEW_PENDING";
    if ("APPROVE".equals(action) && "REVIEW_PENDING".equals(current)) return "APPROVED";
    if ("REJECT".equals(action) && "REVIEW_PENDING".equals(current)) return "REJECTED";
    if (("EVIDENCE_BLOCKED".equals(current) || "RISK_BLOCKED".equals(current))
        && "APPROVE".equals(action)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "DRAFT_APPROVAL_BLOCKED", "事实或风险门禁阻止通过");
    }
    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DRAFT_STATUS_TRANSITION", "草稿状态不允许执行该审核动作");
  }

  private List<Map<String, Object>> evidence(Identity identity, UUID merchantId) {
    List<Map<String, Object>> rows = db.queryForList("""
        select f.id fact_id,v.id version_id,f.fact_type,v.normalized_text,v.effective_to
        from merchant_facts f join merchant_fact_versions v on v.id=f.current_version_id
        where f.tenant_id=:t and f.merchant_id=:m and not f.deleted and v.status='VERIFIED'
          and (v.effective_to is null or v.effective_to>=now())
        """, parameters(identity, merchantId));
    return rows.stream().map(row -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("evidenceId", row.get("version_id"));
      item.put("type", "VERIFIED_FACT");
      item.put("text", row.get("normalized_text"));
      item.put("factType", row.get("fact_type"));
      return item;
    }).toList();
  }

  Map<String, Object> claimCheck(Map<String, Object> generated, List<Map<String, Object>> pack) {
    Set<String> ids = new HashSet<>();
    pack.forEach(item -> ids.add(String.valueOf(item.get("evidenceId"))));
    List<String> unsupported = new ArrayList<>();
    walkClaims(generated, ids, unsupported);
    return Map.of("blocked", !unsupported.isEmpty(), "unsupportedClaims", unsupported);
  }

  private void walkClaims(Object value, Set<String> ids, List<String> unsupported) {
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey("text")) {
        Object evidenceIds = map.get("evidenceIds");
        if (!(evidenceIds instanceof Collection<?> collection)
            || collection.isEmpty()
            || collection.stream().anyMatch(candidate -> !ids.contains(String.valueOf(candidate)))) {
          unsupported.add(String.valueOf(map.get("text")));
        }
      }
      map.values().forEach(child -> walkClaims(child, ids, unsupported));
    } else if (value instanceof Collection<?> collection) {
      collection.forEach(child -> walkClaims(child, ids, unsupported));
    }
  }

  List<ContentRiskRule> riskRules(Map<String, Object> brief) {
    List<ContentRiskRule> rules = new ArrayList<>();
    for (String expression : List.of("第一", "唯一", "保证", "百分百", "百分之百", "治疗", "治愈")) {
      rules.add(new ContentRiskRule("M6_" + expression, expression, ContentRiskRule.Severity.BLOCKED,
          "claim", "删除或改为可验证的保守措辞", List.of(), true));
    }
    rules.add(new ContentRiskRule("M6_INDUSTRY_LEADING", "行业领先", ContentRiskRule.Severity.WARNING,
        "claim", "建议补充可验证依据或改为保守措辞", List.of(), true));
    for (String prohibited : stringList(brief.get("prohibited_claims"))) {
      if (!prohibited.isBlank()) {
        rules.add(new ContentRiskRule("M6_PROHIBITED_" + stableHash(json, prohibited).substring(0, 12),
            prohibited, ContentRiskRule.Severity.BLOCKED, "prohibitedClaim",
            "删除与商家禁止宣传事项冲突的内容", List.of(), true));
      }
    }
    return rules;
  }

  private List<String> stringList(Object value) {
    if (value == null) return List.of();
    try {
      JsonNode node = json.readTree(String.valueOf(value));
      if (!node.isArray()) return List.of();
      return json.convertValue(node, new TypeReference<List<String>>() {});
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private Map<String, Object> brief(Identity identity, UUID merchantId, UUID id) {
    return one("select * from geo_content_briefs where id=:id and tenant_id=:t and merchant_id=:m",
        parameters(identity, merchantId).addValue("id", id));
  }

  private MapSqlParameterSource parameters(Identity identity, UUID merchantId) {
    return new MapSqlParameterSource().addValue("t", identity.tenantId()).addValue("m", merchantId);
  }

  private Map<String, Object> one(String query, MapSqlParameterSource parameters) {
    var rows = db.queryForList(query, parameters);
    if (rows.isEmpty()) throw bad("NOT_FOUND", "数据不存在或无权访问");
    return rows.getFirst();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(String value) {
    try {
      return json.readValue(value, Map.class);
    } catch (Exception exception) {
      throw bad("MODEL_RESPONSE_INVALID", "模型返回非法JSON");
    }
  }

  private String jsonString(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  static String stableHash(ObjectMapper mapper, Object value) {
    try {
      byte[] canonical = mapper.writeValueAsBytes(value);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private ApiException bad(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }
}
