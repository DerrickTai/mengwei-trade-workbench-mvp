package com.mengwei.localgrowth.model;
public interface ModelProviderPort { String name(); ModelAnswer invoke(String prompt, String brand, String services, String city); record ModelAnswer(String model, String text, long durationMs, boolean simulated, int httpStatus, Long tokenUsage, String rawResponse){} }
