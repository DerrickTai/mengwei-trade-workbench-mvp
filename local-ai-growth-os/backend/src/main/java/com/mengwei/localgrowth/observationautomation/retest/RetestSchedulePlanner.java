package com.mengwei.localgrowth.observationautomation.retest;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class RetestSchedulePlanner {
  public List<PlannedPoint> planRetests(
      OffsetDateTime publicationTime,
      ZoneId zone,
      LocalTime localTime,
      List<Integer> dayOffsets) {
    if (publicationTime == null) throw new IllegalArgumentException("publicationTime is required");
    if (zone == null) throw new IllegalArgumentException("zone is required");
    LocalTime runTime = localTime == null ? LocalTime.of(9, 0) : localTime;
    TreeSet<Integer> offsets = new TreeSet<>();
    for (Integer offset : dayOffsets == null ? List.<Integer>of() : dayOffsets) {
      if (offset == null || offset < 0 || offset > 3650) {
        throw new IllegalArgumentException("day offsets must be between 0 and 3650");
      }
      offsets.add(offset);
    }
    ZonedDateTime publishedLocal = publicationTime.atZoneSameInstant(zone);
    List<PlannedPoint> points = new ArrayList<>();
    int sequence = 1;
    for (Integer offset : offsets) {
      ZonedDateTime due = publishedLocal.toLocalDate().plusDays(offset)
          .atTime(runTime).atZone(zone);
      if (offset == 0 && due.isBefore(publishedLocal)) due = publishedLocal;
      points.add(new PlannedPoint(sequence++, offset, due.toOffsetDateTime()));
    }
    return List.copyOf(points);
  }

  public record PlannedPoint(int sequenceNo, int dayOffset, OffsetDateTime dueAt) {}
}
