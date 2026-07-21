package com.mengwei.localgrowth.contentexecution;

import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.text.Normalizer;import java.util.*;

/** Deterministic pre-review scan; it does not replace legal or human review. */
public final class ContentRiskScanner {
  public static final String ALGORITHM_VERSION="M6_RISK_V1";
  private static final List<String> FIELDS=List.of("title","excerpt","body","keywords","meta_description");
  public ContentRiskScanResult scan(Map<String,String> input,List<ContentRiskRule> rules){
    Map<String,String> content=new LinkedHashMap<>();for(String field:FIELDS)content.put(field,normalize(input==null?"":input.get(field)));
    List<ContentRiskFinding> hits=new ArrayList<>();for(ContentRiskRule rule:rules==null?List.<ContentRiskRule>of():rules){String needle=compact(fold(rule.expression()));for(String field:rule.fields().isEmpty()?FIELDS:rule.fields()){String value=content.getOrDefault(field,"");String target=rule.compactMatch()?compact(fold(value)):fold(value);String expected=rule.compactMatch()?needle:fold(rule.expression());int count=count(target,expected);if(count>0)hits.add(new ContentRiskFinding(rule.code(),field,count,rule.severity(),rule.category(),rule.suggestion(),value.length()>160?value.substring(0,160):value));}}
    ContentRiskScanResult.Status status=hits.stream().anyMatch(x->x.severity()==ContentRiskRule.Severity.BLOCKED)?ContentRiskScanResult.Status.BLOCKED:hits.isEmpty()?ContentRiskScanResult.Status.CLEAN:ContentRiskScanResult.Status.WARNING;
    return new ContentRiskScanResult(ALGORITHM_VERSION,sha(content.toString()),sha(String.valueOf(rules)),status,List.copyOf(hits));
  }
  private static int count(String v,String n){if(n==null||n.isBlank())return 0;int c=0,p=0;while((p=v.indexOf(n,p))>=0){c++;p+=n.length();}return c;}
  private static String normalize(String s){return Normalizer.normalize(s==null?"":s,Normalizer.Form.NFKC).replaceAll("[\\p{Cc}\\p{Cf}]","").replaceAll("\\s+"," ").trim();}
  private static String fold(String s){return normalize(s).toLowerCase(Locale.ROOT);}
  private static String compact(String s){return s.replaceAll("[\\p{Z}\\p{P}]","");}
  private static String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
