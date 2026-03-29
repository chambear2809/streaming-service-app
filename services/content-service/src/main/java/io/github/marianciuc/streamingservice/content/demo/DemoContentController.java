package io.github.marianciuc.streamingservice.content.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*")
@Profile("broadcast-demo")
public class DemoContentController {

    private final DemoContentRepository repository;

    public DemoContentController(DemoContentRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/v1/demo/content/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/v1/demo/content")
    public List<Map<String, Object>> getDemoContent() {
        return repository.findAll().stream()
                .map(this::toPayload)
                .toList();
    }

    @GetMapping("/api/v1/demo/content/{id}")
    public Map<String, Object> getDemoContentById(@PathVariable UUID id) {
        return repository.findById(id)
                .map(this::toPayload)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo catalog item was not found."));
    }

    @PutMapping("/api/v1/demo/content/{id}/lifecycle")
    public Map<String, Object> updateLifecycle(@PathVariable UUID id, @RequestBody DemoContentLifecycleUpdate request) {
        if (request == null || request.lifecycleState() == null || request.lifecycleState().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lifecycleState is required.");
        }

        boolean updated = repository.updateLifecycle(id, request);
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo catalog item was not found.");
        }

        return repository.findById(id)
                .map(this::toPayload)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo catalog item was not found."));
    }

    private Map<String, Object> toPayload(DemoCatalogItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", item.id());
        payload.put("title", item.title());
        payload.put("description", item.description());
        payload.put("type", item.type());
        payload.put("ageRating", item.ageRating());
        payload.put("releaseDate", item.releaseDate().toString());
        payload.put("runtimeLabel", item.runtimeLabel());
        payload.put("headline", item.headline());
        payload.put("featureLine", item.featureLine());
        payload.put("channelLabel", item.channelLabel());
        payload.put("programmingTrack", item.programmingTrack());
        payload.put("lifecycleState", item.lifecycleState());
        payload.put("readinessLabel", item.readinessLabel());
        payload.put("signalProfile", item.signalProfile());
        payload.put("genreList", item.genreList());
        payload.put("watchUrl", item.watchUrl());
        payload.put("scheduledStartAt", item.scheduledStartAt() == null ? null : item.scheduledStartAt().toString());
        payload.put("publishedAt", item.publishedAt() == null ? null : item.publishedAt().toString());
        payload.put("onAirAt", item.onAirAt() == null ? null : item.onAirAt().toString());
        payload.put("archivedAt", item.archivedAt() == null ? null : item.archivedAt().toString());
        payload.put("updatedAt", item.updatedAt() == null ? null : item.updatedAt().toString());
        return payload;
    }
}
