package com.mengwei.localgrowth.observationautomation.retest;

public record RetestSampleSignal(
    boolean valid,
    boolean merchantMentioned,
    boolean merchantRecommended,
    Integer recommendationRank,
    boolean anyCitation,
    boolean directPublicationCitation,
    String contextSha256) {

  public double reciprocalRank() {
    return recommendationRank != null && recommendationRank > 0
        ? 1d / recommendationRank
        : 0d;
  }
}
