package com.mengwei.localgrowth.observationautomation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ObservationHeuristics {
  private static final List<String> RECOMMENDATION_WORDS = List.of(
      "推荐", "首选", "值得去", "可以选择", "建议选择", "优先考虑", "值得尝试");
  private static final Pattern NUMBERED_BRAND =
      Pattern.compile("(?m)^\\s*(\\d{1,2})[.、)]\\s*([^\\n]{1,100})");

  public Extraction extract(
      String rawAnswer,
      List<String> brandTerms,
      List<String> ambiguousTerms) {
    String answer = rawAnswer == null ? "" : rawAnswer;
    String lower = answer.toLowerCase(Locale.ROOT);
    boolean mentioned = clean(brandTerms).stream()
        .anyMatch(term -> lower.contains(term.toLowerCase(Locale.ROOT)));
    boolean ambiguousOnly = !mentioned && clean(ambiguousTerms).stream()
        .anyMatch(term -> lower.contains(term.toLowerCase(Locale.ROOT)));
    boolean recommended = mentioned && RECOMMENDATION_WORDS.stream()
        .anyMatch(answer::contains);
    Integer rank = mentioned ? detectRank(answer, brandTerms) : null;
    Map<String, Object> details = Map.of(
        "ruleVersion", "OBSERVATION_HEURISTICS_V1",
        "ambiguousOnly", ambiguousOnly,
        "recommendationWords", RECOMMENDATION_WORDS);
    return new Extraction(mentioned, recommended, rank, details);
  }

  private Integer detectRank(String answer, List<String> brandTerms) {
    Matcher matcher = NUMBERED_BRAND.matcher(answer);
    while (matcher.find()) {
      String line = matcher.group(2).toLowerCase(Locale.ROOT);
      boolean matched = clean(brandTerms).stream()
          .map(term -> term.toLowerCase(Locale.ROOT))
          .anyMatch(line::contains);
      if (matched) return Integer.parseInt(matcher.group(1));
    }
    return null;
  }

  private List<String> clean(List<String> values) {
    if (values == null) return List.of();
    return values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }

  public record Extraction(
      boolean merchantMentioned,
      boolean merchantRecommended,
      Integer recommendationRank,
      Map<String, Object> details) {
  }
}
