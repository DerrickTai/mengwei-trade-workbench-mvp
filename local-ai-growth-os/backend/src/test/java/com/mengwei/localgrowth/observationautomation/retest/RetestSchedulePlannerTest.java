package com.mengwei.localgrowth.observationautomation.retest;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetestSchedulePlannerTest {
  @Test void plansSortedUniqueOffsetsInLocalTimezone() {
    OffsetDateTime published = OffsetDateTime.parse("2026-03-01T20:00:00+08:00");
    var points = new RetestSchedulePlanner().planRetests(published,
        ZoneId.of("Asia/Shanghai"), LocalTime.of(9,0), List.of(14,3,7,3,30));
    assertEquals(List.of(3,7,14,30), points.stream().map(RetestSchedulePlanner.PlannedPoint::dayOffset).toList());
    assertEquals(9, points.getFirst().dueAt().getHour());
  }
}
