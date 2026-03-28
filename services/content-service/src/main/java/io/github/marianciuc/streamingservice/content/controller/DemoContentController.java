package io.github.marianciuc.streamingservice.content.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*")
public class DemoContentController {

    private static final UUID DEMO_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @GetMapping("/api/v1/demo/content")
    public List<Map<String, Object>> getDemoContent() {
        return List.of(
                Map.of(
                        "id", DEMO_ID,
                        "title", "Big Buck Bunny",
                        "description", "Demo catalog entry served by content-service for frontend playback integration.",
                        "type", "MOVIE",
                        "ageRating", "G",
                        "releaseDate", "2008-01-01",
                        "posterUrl", "",
                        "genreList", List.of("Animation", "Comedy", "Open Movie")
                )
        );
    }
}
