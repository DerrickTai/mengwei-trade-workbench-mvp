package com.mengwei.localgrowth.observationautomation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlCanonicalizerTest {
  private final UrlCanonicalizer canonicalizer = new UrlCanonicalizer();

  @Test
  void removesTrackingAndFragment() {
    var result = canonicalizer.normalize(
        "https://www.Example.com/a/?utm_source=x&b=2&a=1#section");
    assertThat(result.url()).isEqualTo("https://www.example.com/a?a=1&b=2");
    assertThat(result.domain()).isEqualTo("www.example.com");
  }
}
