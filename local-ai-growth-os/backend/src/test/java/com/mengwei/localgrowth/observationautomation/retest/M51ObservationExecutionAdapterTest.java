package com.mengwei.localgrowth.observationautomation.retest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.observationautomation.ObservationCollectionController.RunInput;
import com.mengwei.localgrowth.observationautomation.ObservationCollectionService;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class M51ObservationExecutionAdapterTest {
  @Test
  void promotesStubRetestResultAsDraftObservation() {
    ObservationCollectionService collections = mock(ObservationCollectionService.class);
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    UUID tenant = UUID.randomUUID(), merchant = UUID.randomUUID(), actor = UUID.randomUUID();
    UUID question = UUID.randomUUID(), collector = UUID.randomUUID(), run = UUID.randomUUID(), result = UUID.randomUUID(), observation = UUID.randomUUID();
    when(collections.run(any(Identity.class), eq(merchant), any(RunInput.class), eq("RETEST")))
        .thenReturn(Map.of("id", run));
    Map<String,Object> row=new LinkedHashMap<>();
    row.put("id",result);row.put("status","SUCCESS");row.put("promoted_observation_id",observation);
    row.put("verification_status","DRAFT");row.put("ai_platform","DOUBAO");row.put("provider_code","STUB");
    row.put("provider_model","stub-local");row.put("collection_channel","OFFICIAL_API");
    row.put("merchant_mentioned",true);row.put("merchant_recommended",true);row.put("cited_sources","[]");row.put("observed_at",OffsetDateTime.now());
    when(collections.results(any(Identity.class), eq(merchant), eq(run))).thenReturn(List.of(row));

    M51ObservationExecutionAdapter adapter = new M51ObservationExecutionAdapter(collections, jdbc, new ObjectMapper());
    RetestObservationExecutionResult actual = adapter.execute(new RetestObservationExecutionRequest(
        tenant, merchant, actor, UUID.randomUUID(), UUID.randomUUID(), question,
        RetestCohort.TARGET, collector, 1));

    assertTrue(actual.success());
    assertEquals(run, actual.collectionRunId());
    assertEquals(result, actual.collectionResultId());
    assertEquals(observation, actual.observationId());
    assertEquals("DOUBAO", actual.aiPlatform());
    assertEquals("OFFICIAL_API", actual.collectionChannel());
    assertEquals("DRAFT", actual.verificationStatus());
    ArgumentCaptor<String> trigger = ArgumentCaptor.forClass(String.class);
    verify(collections).run(any(Identity.class), eq(merchant), any(RunInput.class), trigger.capture());
    assertEquals("RETEST", trigger.getValue());
  }
}
