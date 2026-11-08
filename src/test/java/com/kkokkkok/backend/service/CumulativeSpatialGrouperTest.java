package com.kkokkkok.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CumulativeSpatialGrouperTest {
    private static final double METERS_PER_DEGREE_AT_EQUATOR = 111_320.0;

    @Test
    void groupsAroundMedoidAndDoesNotJoinAnEntireDistanceChain() {
        UUID a = id(1), b = id(2), c = id(3), d = id(4);
        List<CumulativeSpatialGrouper.Point> points = List.of(
                point(a, 0), point(b, 80), point(c, 160), point(d, 240));

        List<CumulativeSpatialGrouper.Group> groups = CumulativeSpatialGrouper.group(points);

        assertEquals(2, groups.size());
        assertEquals(b, groups.get(0).centerId());
        assertEquals(List.of(a, b, c), groups.get(0).memberIds());
        assertEquals(List.of(d), groups.get(groups.size() - 1).memberIds());
        for (CumulativeSpatialGrouper.Group group : groups) {
            var center = points.stream().filter(point -> point.id().equals(group.centerId())).findFirst().orElseThrow();
            for (UUID memberId : group.memberIds()) {
                var member = points.stream().filter(point -> point.id().equals(memberId)).findFirst().orElseThrow();
                assertTrue(CumulativeSpatialGrouper.distance(center.latitude(), center.longitude(),
                        member.latitude(), member.longitude()) <= CumulativeSpatialGrouper.RADIUS_METERS);
            }
        }
    }

    @Test
    void separatesPointsOutsideHundredMeterRadius() {
        var groups = CumulativeSpatialGrouper.group(List.of(point(id(1), 0), point(id(2), 100.2)));
        assertEquals(2, groups.size());
    }

    private CumulativeSpatialGrouper.Point point(UUID id, double metersEast) {
        return new CumulativeSpatialGrouper.Point(id, 0, metersEast / METERS_PER_DEGREE_AT_EQUATOR);
    }

    private UUID id(int value) { return new UUID(0, value); }
}
