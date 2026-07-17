package com.mengwei.localgrowth.execution;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProhibitedClaimGuardTest {
  private final ProhibitedClaimGuard guard=new ProhibitedClaimGuard(new ObjectMapper());
  private Map<String,Object> claim(String fact,String version,String value){return Map.of("fact_id",fact,"version_id",version,"value_json",value,"normalized_text",value);}
  @Test void highMatchBlocks(){var r=guard.check("介绍","本服务可以根治脱发。",List.of(claim("f1","v1","{\"claimText\":\"不得宣称治疗脱发\",\"matchTerms\":[\"治疗脱发\",\"根治脱发\",\"治好脱发\"],\"severity\":\"HIGH\"}")),List.of());assertTrue(r.hit());assertTrue(r.riskFlags().contains("PROHIBITED_CLAIM_HIT"));assertTrue(r.riskFlags().contains("PROHIBITED_CLAIM_REVIEW_BLOCKED"));assertEquals("HIGH",r.highestSeverity());assertTrue(r.reviewSubmissionBlocked());assertEquals("f1",r.matchedClaims().getFirst().get("factId"));assertEquals("v1",r.matchedClaims().getFirst().get("factVersionId"));assertEquals("根治脱发",r.matchedClaims().getFirst().get("matchedText"));}
  @Test void lowMatchWarnsOnly(){var r=guard.check("","我们提供行业 第一的服务体验",List.of(claim("f2","v2","{\"claimText\":\"避免使用夸张宣传\",\"matchTerms\":[\"行业第一\"],\"severity\":\"LOW\"}")),List.of());assertTrue(r.hit());assertTrue(r.riskFlags().contains("PROHIBITED_CLAIM_HIT"));assertFalse(r.riskFlags().contains("PROHIBITED_CLAIM_REVIEW_BLOCKED"));assertEquals("LOW",r.highestSeverity());assertFalse(r.reviewSubmissionBlocked());}
  @Test void noMatchAndLegacyDefaults(){var none=guard.check("标题","普通门店介绍",List.of(claim("f3","v3","旧纯文本")),List.of());assertFalse(none.hit());assertTrue(none.matchedClaims().isEmpty());var legacy=guard.check("","旧纯文本",List.of(claim("f3","v3","旧纯文本")),List.of());assertTrue(legacy.hit());assertEquals("HIGH",legacy.highestSeverity());assertTrue(legacy.reviewSubmissionBlocked());}
  @Test void nullAndPunctuationAreSafe(){var r=guard.check(null,"  治疗脱发！ ",List.of(Map.of("fact_id","f4","version_id","v4","value_json","{\"claimText\":\"不得宣称治疗脱发\"}","normalized_text","x")),List.of("EXISTING"));assertTrue(r.hit());assertTrue(r.riskFlags().contains("EXISTING"));}
}
