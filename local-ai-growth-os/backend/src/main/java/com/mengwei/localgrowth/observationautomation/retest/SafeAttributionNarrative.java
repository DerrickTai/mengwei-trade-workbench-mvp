package com.mengwei.localgrowth.observationautomation.retest;

import java.text.NumberFormat;
import java.util.Locale;

public class SafeAttributionNarrative {
  public String directCitation(RetestComparison comparison) {
    return "已观察到并核验作品级直接引用。该证据证明作品曾被引用，"
        + "但不能单独证明全部指标变化均由该作品造成。";
  }

  public String strongAssociation(RetestComparison comparison) {
    return "目标问题组相对基线改善，且对照组变化较小；校正后变化为"
        + percent(comparison.adjustedDelta())
        + "。多个周期结果一致，现有证据支持较强关联，但不构成确定因果证明。";
  }

  public String temporalAssociation(RetestComparison comparison) {
    return "优化后观察到目标指标变化，校正后变化为"
        + percent(comparison.adjustedDelta())
        + "。由于样本、对照、持续性或其他干预因素仍有限，目前仅能判断时间上的关联。";
  }

  public String insufficient(RetestComparison comparison) {
    return switch (comparison.status()) {
      case INSUFFICIENT_SAMPLE -> "当前有效样本不足，暂不判断优化效果。";
      case HIGH_VOLATILITY -> "当前结果波动较高，变化仍可能来自模型采样波动，建议继续复测。";
      default -> "现有证据不足以支持明确归因，建议补充样本、对照组和连续周期观察。";
    };
  }

  private String percent(double value) {
    NumberFormat format = NumberFormat.getPercentInstance(Locale.CHINA);
    format.setMaximumFractionDigits(1);
    return format.format(value);
  }
}
