package com.mengwei.localgrowth.contentexecution;

public record ContentRiskFinding(String code, String field, int count, ContentRiskRule.Severity severity,
                                 String category, String suggestion, String snippet) {}
