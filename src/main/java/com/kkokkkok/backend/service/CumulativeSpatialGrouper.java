package com.kkokkkok.backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Partitions points so every member is within the configured radius of a real-photo medoid. */
public final class CumulativeSpatialGrouper {
    private CumulativeSpatialGrouper() {}
    public static final double RADIUS_METERS = 100.0;

    public static List<Group> group(List<Point> points) {
        List<Point> remaining = new ArrayList<>(points);
        remaining.sort(Comparator.comparing(point -> point.id().toString()));
        List<Group> groups = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Point center = remaining.stream().max(Comparator
                    .comparingInt((Point candidate) -> (int) remaining.stream()
                            .filter(other -> distance(candidate.latitude(), candidate.longitude(),
                                    other.latitude(), other.longitude()) <= RADIUS_METERS).count())
                    .thenComparing(candidate -> candidate.id().toString(), Comparator.reverseOrder())).orElseThrow();
            List<Point> members = remaining.stream()
                    .filter(point -> distance(center.latitude(), center.longitude(), point.latitude(), point.longitude()) <= RADIUS_METERS)
                    .sorted(Comparator.comparing(point -> point.id().toString())).toList();
            remaining.removeAll(members);
            groups.add(new Group(center.id(), members.stream().map(Point::id).toList()));
        }
        return List.copyOf(groups);
    }

    public static double distance(double latitude1, double longitude1, double latitude2, double longitude2) {
        double earth = 6_371_000.0;
        double dLat = Math.toRadians(latitude2 - latitude1);
        double dLon = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public record Point(UUID id, double latitude, double longitude) {}
    public record Group(UUID centerId, List<UUID> memberIds) {}
}
