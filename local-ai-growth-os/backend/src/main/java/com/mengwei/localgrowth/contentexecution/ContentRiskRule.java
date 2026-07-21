package com.mengwei.localgrowth.contentexecution;

import java.util.List;

public record ContentRiskRule(String code, String expression, Severity severity, String category,
                              String suggestion, List<String> fields, boolean compactMatch) {
  public enum Severity { WARNING, BLOCKED }
  public ContentRiskRule { fields = fields == null ? List.of() : List.copyOf(fields); }
}
