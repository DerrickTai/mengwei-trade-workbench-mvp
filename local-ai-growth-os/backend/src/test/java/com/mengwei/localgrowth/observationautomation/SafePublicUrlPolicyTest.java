package com.mengwei.localgrowth.observationautomation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SafePublicUrlPolicyTest {
  private final SafePublicUrlPolicy policy = new SafePublicUrlPolicy();

  @Test
  void blocksLoopback() {
    assertThatThrownBy(() -> policy.requirePublicHttpUrl("http://127.0.0.1/admin"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blocksPrivateNetwork() {
    assertThatThrownBy(() -> policy.requirePublicHttpUrl("http://10.0.0.1/"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blocksNonHttpProtocol() {
    assertThatThrownBy(() -> policy.requirePublicHttpUrl("file:///etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
