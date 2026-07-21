package com.mengwei.localgrowth.contentexecution;

import java.util.List;
public record ContentRiskScanResult(String algorithmVersion, String contentHash, String ruleSetHash, Status status,
                                    List<ContentRiskFinding> findings) {
  public enum Status { CLEAN, WARNING, BLOCKED }
  public int blockedCount(){return (int)findings.stream().filter(x->x.severity()==ContentRiskRule.Severity.BLOCKED).count();}
  public int warningCount(){return findings.size()-blockedCount();}
}
