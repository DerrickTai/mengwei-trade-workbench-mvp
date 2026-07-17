package com.mengwei.localgrowth.observationautomation;

public interface OfficialAiCollector {
  String providerCode();
  CollectorResponse collect(CollectorRequest request);
}
