package io.github.marianciuc.streamingservice.media.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
public class DemoMediaController {

    @Value("${demo.media.file:/opt/demo/demo.mp4}")
    private String demoMediaFile;

    @GetMapping("/api/v1/demo/media/movie.mp4")
    public ResponseEntity<Resource> getDemoMovie() {
        Resource resource = new FileSystemResource(demoMediaFile);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"demo.mp4\"")
                .body(resource);
    }
}
