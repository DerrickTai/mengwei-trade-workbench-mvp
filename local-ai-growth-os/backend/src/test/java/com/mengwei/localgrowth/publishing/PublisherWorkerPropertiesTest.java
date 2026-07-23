package com.mengwei.localgrowth.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PublisherWorkerPropertiesTest {
  @Test
  void defaultPropertiesAreClosed() {
    PublisherWorkerProperties properties = new PublisherWorkerProperties(false, 300, 5);
    assertThat(properties.enabled()).isFalse();
  }
}
