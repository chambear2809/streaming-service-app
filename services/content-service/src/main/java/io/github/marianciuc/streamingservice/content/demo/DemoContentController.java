package io.github.marianciuc.streamingservice.content.demo;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*")
@Profile("broadcast-demo")
public class DemoContentController {

    @GetMapping("/api/v1/demo/content/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/v1/demo/content")
    public List<Map<String, Object>> getDemoContent() {
        return List.of(
                catalogItem(
                        "11111111-1111-1111-1111-111111111111",
                        "Big Buck Bunny",
                        "A classic Blender open movie featuring a rabbit and three very unlucky woodland bullies.",
                        "MOVIE",
                        "G",
                        "2008-05-30",
                        "9m 57s",
                        "Blender classic",
                        "Forest slapstick with open-movie pedigree",
                        "Prime East",
                        "Family Matinee",
                        "QC cleared",
                        "1080p mezzanine",
                        List.of("Animation", "Comedy", "Open Movie"),
                        "/api/v1/demo/media/library/big-buck-bunny.mp4"
                ),
                catalogItem(
                        "22222222-2222-2222-2222-222222222222",
                        "Elephants Dream",
                        "A surreal tour through a machine world where two travelers collide over what the place is meant to become.",
                        "MOVIE",
                        "PG",
                        "2006-03-24",
                        "10m 54s",
                        "Experimental sci-fi",
                        "Foundational Blender feature with a dream-logic visual world",
                        "Events",
                        "Innovation Desk",
                        "Standards review",
                        "Primary contribution feed",
                        List.of("Animation", "Science Fiction", "Open Movie"),
                        "/api/v1/demo/media/library/elephants-dream.mp4"
                ),
                catalogItem(
                        "33333333-3333-3333-3333-333333333333",
                        "Sintel",
                        "A lone traveler searches for a lost dragon companion across frozen valleys and ruined cities.",
                        "MOVIE",
                        "PG-13",
                        "2010-09-30",
                        "14m 48s",
                        "Epic fantasy",
                        "Adventure feature with a stronger dramatic arc for review screenings",
                        "Review Desk",
                        "Premium Window",
                        "Executive notes pending",
                        "HDR mezzanine",
                        List.of("Animation", "Fantasy", "Adventure"),
                        "/api/v1/demo/media/library/sintel.mp4"
                ),
                catalogItem(
                        "44444444-4444-4444-4444-444444444444",
                        "Tears of Steel",
                        "A near-future team attempts to repair a broken relationship before a robotic threat overruns Amsterdam.",
                        "MOVIE",
                        "PG",
                        "2012-09-26",
                        "12m 14s",
                        "Live-action hybrid",
                        "Sci-fi short with VFX-heavy sequences and live-action review value",
                        "Prime West",
                        "VFX Showcase",
                        "Ready for playout",
                        "1080p clean feed",
                        List.of("Science Fiction", "Drama", "Open Movie"),
                        "/api/v1/demo/media/library/tears-of-steel.mp4"
                )
        );
    }

    private Map<String, Object> catalogItem(
            String id,
            String title,
            String description,
            String type,
            String ageRating,
            String releaseDate,
            String runtimeLabel,
            String headline,
            String featureLine,
            String channelLabel,
            String programmingTrack,
            String readinessLabel,
            String signalProfile,
            List<String> genres,
            String watchUrl
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", UUID.fromString(id));
        item.put("title", title);
        item.put("description", description);
        item.put("type", type);
        item.put("ageRating", ageRating);
        item.put("releaseDate", releaseDate);
        item.put("runtimeLabel", runtimeLabel);
        item.put("headline", headline);
        item.put("featureLine", featureLine);
        item.put("channelLabel", channelLabel);
        item.put("programmingTrack", programmingTrack);
        item.put("readinessLabel", readinessLabel);
        item.put("signalProfile", signalProfile);
        item.put("genreList", genres);
        item.put("watchUrl", watchUrl);
        return item;
    }
}
