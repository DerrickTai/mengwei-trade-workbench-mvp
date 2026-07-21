package com.mengwei.localgrowth.observationautomation.retest;

public record RetestComparison(
    RetestMetricName primaryMetric,
    RetestResultStatus status,
    double targetBefore,
    double targetAfter,
    Double controlBefore,
    Double controlAfter,
    double targetDelta,
    Double controlDelta,
    double adjustedDelta,
    int totalSamples,
    double maxVolatility,
    boolean contextDrift,
    boolean hasControl) {}
