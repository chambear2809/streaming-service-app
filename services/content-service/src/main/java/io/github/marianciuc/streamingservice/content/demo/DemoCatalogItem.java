package io.github.marianciuc.streamingservice.content.demo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

record DemoCatalogItem(
        UUID id,
        String title,
        String description,
        String type,
        String ageRating,
        LocalDate releaseDate,
        String runtimeLabel,
        String headline,
        String featureLine,
        String channelLabel,
        String programmingTrack,
        String lifecycleState,
        String readinessLabel,
        String signalProfile,
        List<String> genreList,
        String watchUrl,
        Instant scheduledStartAt,
        Instant publishedAt,
        Instant onAirAt,
        Instant archivedAt,
        Instant updatedAt
) {
}
