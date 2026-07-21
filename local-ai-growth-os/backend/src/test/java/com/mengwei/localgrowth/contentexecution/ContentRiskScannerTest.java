package com.mengwei.localgrowth.contentexecution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContentRiskScannerTest {
  private final ContentRiskScanner scanner = new ContentRiskScanner();

  @Test
  void ordinaryContentIsClean() {
    var result = scanner.scan(Map.of("title", "门店服务介绍", "body", "我们提供已核验的到店服务。"), rules());
    assertThat(result.status()).isEqualTo(ContentRiskScanResult.Status.CLEAN);
  }

  @Test
  void generalRiskIsWarning() {
    var result = scanner.scan(Map.of("body", "我们希望提供行业领先的服务体验。"), rules());
    assertThat(result.status()).isEqualTo(ContentRiskScanResult.Status.WARNING);
    assertThat(result.warningCount()).isEqualTo(1);
  }

  @Test
  void absoluteClaimsAreBlocked() {
    for (String expression : List.of("第一", "唯一", "保证", "百分百")) {
      var result = scanner.scan(Map.of("body", "宣传内容：" + expression), rules());
      assertThat(result.status()).as(expression).isEqualTo(ContentRiskScanResult.Status.BLOCKED);
    }
  }

  @Test
  void prohibitedClaimIsBlocked() {
    var prohibited = new ContentRiskRule("PROHIBITED", "不得宣称治疗脱发",
        ContentRiskRule.Severity.BLOCKED, "prohibitedClaim", "删除该主张", List.of(), true);
    var result = scanner.scan(Map.of("body", "不得宣称治疗脱发"), List.of(prohibited));
    assertThat(result.status()).isEqualTo(ContentRiskScanResult.Status.BLOCKED);
  }

  private List<ContentRiskRule> rules() {
    return List.of(
        new ContentRiskRule("FIRST", "第一", ContentRiskRule.Severity.BLOCKED, "claim", "修改", List.of(), true),
        new ContentRiskRule("ONLY", "唯一", ContentRiskRule.Severity.BLOCKED, "claim", "修改", List.of(), true),
        new ContentRiskRule("GUARANTEE", "保证", ContentRiskRule.Severity.BLOCKED, "claim", "修改", List.of(), true),
        new ContentRiskRule("PERCENT", "百分百", ContentRiskRule.Severity.BLOCKED, "claim", "修改", List.of(), true),
        new ContentRiskRule("LEADING", "行业领先", ContentRiskRule.Severity.WARNING, "claim", "补充依据", List.of(), true));
  }
}
