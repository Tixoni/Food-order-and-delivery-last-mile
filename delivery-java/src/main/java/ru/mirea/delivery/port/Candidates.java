package ru.mirea.delivery.port;

import ru.mirea.delivery.domain.GeoPoint;

import java.util.List;

/** Точка выдачи и ближайшие к ней свободные курьеры. */
public record Candidates(GeoPoint pickup, List<CandidateInfo> candidates) {
}
