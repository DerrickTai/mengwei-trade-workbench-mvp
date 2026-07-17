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
    List<String> flags=new ArrayList<>(existing==null?List.of():existing); List<Map<String,Object>> matches=new ArrayList<>(); String highest=null;
    for(Map<String,Object> c:claims==null?List.<Map<String,Object>>of():claims){Map<String,Object> v=parse(c.get("value_json"));String claim=String.valueOf(v.getOrDefault("claimText",c.getOrDefault("normalized_text","")));String severity=String.valueOf(v.getOrDefault("severity","HIGH")).toUpperCase(Locale.ROOT);List<String> terms=new ArrayList<>();Object raw=v.get("matchTerms");if(raw instanceof Collection<?> col)col.forEach(x->terms.add(String.valueOf(x)));if(terms.isEmpty()&&!claim.isBlank())terms.add(claim);String matched=null, type=null;if(!claim.isBlank()&&text.contains(normalize(claim))){matched=claim;type="CLAIM_TEXT";}else for(String term:terms)if(!term.isBlank()&&text.contains(normalize(term))){matched=term;type="MATCH_TERM";break;}if(matched!=null){Map<String,Object> m=new LinkedHashMap<>();m.put("factId",c.get("fact_id"));m.put("factVersionId",c.get("version_id"));m.put("claimText",claim);m.put("matchedText",matched);m.put("matchType",type);m.put("severity",severity);matches.add(m);if(highest==null||rank(severity)>rank(highest))highest=severity;}}
    boolean hit=!matches.isEmpty(), blocked=highest!=null&&(highest.equals("HIGH")||highest.equals("CRITICAL"));if(hit&&!flags.contains("PROHIBITED_CLAIM_HIT"))flags.add("PROHIBITED_CLAIM_HIT");if(blocked&&!flags.contains("PROHIBITED_CLAIM_REVIEW_BLOCKED"))flags.add("PROHIBITED_CLAIM_REVIEW_BLOCKED");return new Result(hit,flags,matches,highest,blocked);
  }
  private int rank(String s){return switch(s){case "CRITICAL"->4;case "HIGH"->3;case "MEDIUM"->2;default->1;};}
  private String normalize(String s){return s.toLowerCase(Locale.ROOT).replace('，',',').replace('。','.').replace('：',':').replaceAll("\\s+","").trim();}
  @SuppressWarnings("unchecked") private Map<String,Object> parse(Object o){if(!(o instanceof String s))return o instanceof Map<?,?>m?(Map<String,Object>)m:Map.of();try{Map<String,Object> x=mapper.readValue(s,Map.class);return x;}catch(Exception e){return Map.of("claimText",s);}}
  public record Result(boolean hit,List<String> riskFlags,List<Map<String,Object>> matchedClaims,String highestSeverity,boolean reviewSubmissionBlocked){}
}
