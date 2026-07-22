package com.mengwei.localgrowth.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ProhibitedClaimGuard {
  private final ObjectMapper mapper;
  public ProhibitedClaimGuard(ObjectMapper mapper){this.mapper=mapper;}
  public Result check(String title,String content,List<Map<String,Object>> claims,List<String> existing){
    String text=normalize(String.valueOf(title)+" "+String.valueOf(content));
    List<String> flags=new ArrayList<>(existing==null?List.of():existing);
    List<Map<String,Object>> matches=new ArrayList<>();
    String highest=null;
    for (Map<String,Object> c : claims == null ? List.<Map<String,Object>>of() : claims) {
      Map<String,Object> value=parse(c.get("value_json"));
      String claim=String.valueOf(value.getOrDefault("claimText", c.getOrDefault("normalized_text", "")));
      String severity=String.valueOf(value.getOrDefault("severity", "HIGH")).toUpperCase(Locale.ROOT);
      List<String> terms=new ArrayList<>();
      Object raw=value.get("matchTerms");
      if (raw instanceof Collection<?> collection) {
        collection.forEach(item -> terms.add(String.valueOf(item)));
      }

      String matched=null;
      String type=null;
      String normalizedClaim=normalize(claim);
      if (isMeaningful(normalizedClaim) && text.contains(normalizedClaim)) {
        matched=claim;
        type="CLAIM_TEXT";
      } else if (terms.isEmpty()) {
        String core=stripCompliancePrefix(normalizedClaim);
        if (isMeaningful(core) && text.contains(core)) {
          matched=core;
          type="CLAIM_CORE";
        }
      } else {
        for (String term : terms) {
          String normalizedTerm=normalize(term);
          if (isMeaningful(normalizedTerm) && text.contains(normalizedTerm)) {
            matched=term;
            type="MATCH_TERM";
            break;
          }
        }
      }
      if (matched != null) {
        Map<String,Object> match=new LinkedHashMap<>();
        match.put("factId",c.get("fact_id"));
        match.put("factVersionId",c.get("version_id"));
        match.put("claimText",claim);
        match.put("matchedText",matched);
        match.put("matchType",type);
        match.put("severity",severity);
        matches.add(match);
        if (highest==null || rank(severity)>rank(highest)) highest=severity;
      }
    }
    boolean hit=!matches.isEmpty();
    boolean blocked=highest!=null&&(highest.equals("HIGH")||highest.equals("CRITICAL"));
    if(hit&&!flags.contains("PROHIBITED_CLAIM_HIT")) flags.add("PROHIBITED_CLAIM_HIT");
    if(blocked&&!flags.contains("PROHIBITED_CLAIM_REVIEW_BLOCKED")) flags.add("PROHIBITED_CLAIM_REVIEW_BLOCKED");
    return new Result(hit,flags,matches,highest,blocked);
  }
  private int rank(String s){return switch(s){case "CRITICAL"->4;case "HIGH"->3;case "MEDIUM"->2;default->1;};}
  private String normalize(String s){return s.toLowerCase(Locale.ROOT).replace('，',',').replace('。','.').replace('：',':').replaceAll("\\s+","").trim();}
  private String stripCompliancePrefix(String normalizedClaim){
    for(String prefix : List.of("不得宣称","禁止宣称","不得声称","禁止声称")){
      if(normalizedClaim.startsWith(prefix)) return normalizedClaim.substring(prefix.length());
    }
    return normalizedClaim;
  }
  private boolean isMeaningful(String value){
    return value != null && value.length() >= 2 && value.codePoints().anyMatch(Character::isLetterOrDigit);
  }
  @SuppressWarnings("unchecked") private Map<String,Object> parse(Object o){if(!(o instanceof String s))return o instanceof Map<?,?>m?(Map<String,Object>)m:Map.of();try{Map<String,Object> x=mapper.readValue(s,Map.class);return x;}catch(Exception e){return Map.of("claimText",s);}}
  public record Result(boolean hit,List<String> riskFlags,List<Map<String,Object>> matchedClaims,String highestSeverity,boolean reviewSubmissionBlocked){}
}
