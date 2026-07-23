package com.mengwei.localgrowth.publishing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.publisher-worker")
public record PublisherWorkerProperties(boolean enabled, long leaseSeconds, int maxAttempts) {
  public PublisherWorkerProperties {
    if (leaseSeconds <= 0) leaseSeconds = 300;
    if (maxAttempts <= 0) maxAttempts = 5;
  }
}
