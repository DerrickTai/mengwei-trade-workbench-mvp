package com.mengwei.localgrowth.observationautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationHeuristicsTest {
  private final ObservationHeuristics heuristics = new ObservationHeuristics();

  @Test
  void findsMentionRecommendationAndRank() {
    var result = heuristics.extract(
        "1. 其他品牌\n2. 示例品牌：值得去，推荐提前预约。",
        List.of("示例品牌"),
        List.of());
    assertThat(result.merchantMentioned()).isTrue();
    assertThat(result.merchantRecommended()).isTrue();
    assertThat(result.recommendationRank()).isEqualTo(2);
  }
}
