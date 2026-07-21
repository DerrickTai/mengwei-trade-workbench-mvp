package com.mengwei.localgrowth.contentexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.audit.AuditService;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.model.ModelProviderPort;
import com.mengwei.localgrowth.model.ModelProviderPort.ModelAnswer;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class M61DraftServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void evidencePackHashIsStable() {
    var pack = List.of(Map.of("evidenceId", "a", "text", "已核验事实"));
    assertThat(M61DraftService.stableHash(mapper, pack))
        .isEqualTo(M61DraftService.stableHash(mapper, pack));
  }

  @Test
  void existingEvidenceIdPassesGate() {
    var service = service(mock(NamedParameterJdbcTemplate.class), mock(ModelProviderPort.class));
    var generated = Map.<String, Object>of("claims", List.of(Map.of("text", "事实", "evidenceIds", List.of("e1"))));
    var result = service.claimCheck(generated, List.of(Map.of("evidenceId", "e1")));
    assertThat(result.get("blocked")).isEqualTo(false);
  }

  @Test
  void missingEvidenceIdBlocksClaim() {
    var service = service(mock(NamedParameterJdbcTemplate.class), mock(ModelProviderPort.class));
    var generated = Map.<String, Object>of("claims", List.of(Map.of("text", "事实", "evidenceIds", List.of("missing"))));
    var result = service.claimCheck(generated, List.of(Map.of("evidenceId", "e1")));
    assertThat(result.get("blocked")).isEqualTo(true);
  }

  @Test
  void factualClaimWithoutEvidenceIdsIsBlocked() {
    var service = service(mock(NamedParameterJdbcTemplate.class), mock(ModelProviderPort.class));
    var generated = Map.<String, Object>of("claims", List.of(Map.of("text", "无证据事实")));
    var result = service.claimCheck(generated, List.of(Map.of("evidenceId", "e1")));
    assertThat(result.get("blocked")).isEqualTo(true);
  }

  @Test
  void blockedDraftCannotBeApproved() {
    assertThatThrownBy(() -> M61DraftService.reviewTransition("RISK_BLOCKED", "APPROVE"))
        .isInstanceOf(ApiException.class).hasMessageContaining("事实或风险门禁阻止通过");
    assertThatThrownBy(() -> M61DraftService.reviewTransition("EVIDENCE_BLOCKED", "APPROVE"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void validReviewTransitionsAreEnforced() {
    assertThat(M61DraftService.reviewTransition("DRAFT", "SUBMIT")).isEqualTo("REVIEW_PENDING");
    assertThat(M61DraftService.reviewTransition("REVIEW_PENDING", "APPROVE")).isEqualTo("APPROVED");
    assertThat(M61DraftService.reviewTransition("REVIEW_PENDING", "REJECT")).isEqualTo("REJECTED");
    assertThatThrownBy(() -> M61DraftService.reviewTransition("DRAFT", "APPROVE"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void idempotencyReturnsExistingDraftWithoutCallingProvider() {
    NamedParameterJdbcTemplate db = mock(NamedParameterJdbcTemplate.class);
    ModelProviderPort provider = mock(ModelProviderPort.class);
    UUID tenant = UUID.randomUUID();
    UUID merchant = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    UUID brief = UUID.randomUUID();
    Map<String, Object> existing = Map.of("id", UUID.randomUUID(), "status", "DRAFT", "draft_version", 1);
    when(db.queryForList(anyString(), any(SqlParameterSource.class))).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("geo_content_briefs")) return List.of(Map.of("id", brief, "work_item_id", UUID.randomUUID()));
      if (sql.contains("idempotency_key")) return List.of(existing);
      return List.of();
    });
    var result = service(db, provider).generate(new Identity(tenant, user), merchant, brief, "same-key");
    assertThat(result).isEqualTo(existing);
    verify(provider, never()).invoke(anyString(), anyString(), anyString(), anyString());
    verify(db, never()).update(anyString(), any(SqlParameterSource.class));
  }

  @Test
  void invalidJsonIsPersistedAsFailed() {
    var fixture = generationFixture(new ModelAnswer("stub-model", "not-json", 1, true, 200, 1L, "summary"), null);
    fixture.service.generate(fixture.identity, fixture.merchant, fixture.brief, "invalid-json");
    assertPersistedStatus(fixture.db, "FAILED");
  }

  @Test
  void providerExceptionIsPersistedAsFailedAndDoesNotUpdateOldDraft() {
    var fixture = generationFixture(null, new IllegalStateException("provider unavailable"));
    fixture.service.generate(fixture.identity, fixture.merchant, fixture.brief, "provider-failure");
    assertPersistedStatus(fixture.db, "FAILED");
    verify(fixture.db, never()).update(org.mockito.ArgumentMatchers.startsWith("update m6_content_draft_versions"),
        any(SqlParameterSource.class));
  }

  @Test
  void aNewGenerationUsesTheNextVersionWithoutOverwritingOlderDrafts() {
    var answer = new ModelAnswer("stub-model", """
        {"title":"FAQ","sections":[{"text":"测试品牌","evidenceIds":["__EVIDENCE__"]}]}
        """, 1, true, 200, 1L, "summary");
    var fixture = generationFixture(answer, null);
    when(fixture.db.queryForObject(anyString(), any(SqlParameterSource.class),
        org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(2);
    fixture.service.generate(fixture.identity, fixture.merchant, fixture.brief, "new-version");
    ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(fixture.db).update(org.mockito.ArgumentMatchers.startsWith("insert into m6_content_draft_versions"),
        parameters.capture());
    assertThat(parameters.getValue().getValue("v")).isEqualTo(2);
    assertThat(parameters.getValue().getValue("s")).isEqualTo("DRAFT");
    verify(fixture.db, never()).update(org.mockito.ArgumentMatchers.startsWith("update m6_content_draft_versions"),
        any(SqlParameterSource.class));
  }

  private Fixture generationFixture(ModelAnswer answer, RuntimeException failure) {
    NamedParameterJdbcTemplate db = mock(NamedParameterJdbcTemplate.class);
    ModelProviderPort provider = mock(ModelProviderPort.class);
    AuditService audit = mock(AuditService.class);
    UUID tenant = UUID.randomUUID();
    UUID merchant = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    UUID brief = UUID.randomUUID();
    UUID work = UUID.randomUUID();
    UUID evidence = UUID.randomUUID();
    when(provider.name()).thenReturn("STUB");
    if (failure == null) {
      ModelAnswer resolved = answer;
      if (answer != null && answer.text().contains("__EVIDENCE__")) {
        resolved = new ModelAnswer(answer.model(), answer.text().replace("__EVIDENCE__", evidence.toString()),
            answer.durationMs(), answer.simulated(), answer.httpStatus(), answer.tokenUsage(), answer.rawResponse());
      }
      when(provider.invoke(anyString(), anyString(), anyString(), anyString())).thenReturn(resolved);
    }
    else when(provider.invoke(anyString(), anyString(), anyString(), anyString())).thenThrow(failure);
    when(db.queryForObject(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.eq(Integer.class)))
        .thenReturn(1);
    when(db.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    when(db.queryForList(anyString(), any(SqlParameterSource.class))).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("geo_content_briefs")) return List.of(Map.of(
          "id", brief, "work_item_id", work, "content_goal", "FAQ", "prohibited_claims", "[]"));
      if (sql.contains("idempotency_key")) return List.of();
      if (sql.contains("merchant_facts")) return List.of(Map.of(
          "version_id", evidence, "fact_type", "BRAND_NAME", "normalized_text", "测试品牌"));
      if (sql.contains("where id=:id")) return List.of(Map.of("id", UUID.randomUUID(), "status", "FAILED"));
      return List.of();
    });
    return new Fixture(db, new M61DraftService(db, mapper, provider, audit),
        new Identity(tenant, user), merchant, brief);
  }

  private void assertPersistedStatus(NamedParameterJdbcTemplate db, String expected) {
    ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
    verify(db).update(org.mockito.ArgumentMatchers.startsWith("insert into m6_content_draft_versions"), parameters.capture());
    assertThat(parameters.getValue().getValue("s")).isEqualTo(expected);
  }

  private M61DraftService service(NamedParameterJdbcTemplate db, ModelProviderPort provider) {
    return new M61DraftService(db, mapper, provider, mock(AuditService.class));
  }

  private record Fixture(NamedParameterJdbcTemplate db, M61DraftService service, Identity identity,
                         UUID merchant, UUID brief) {}
}
