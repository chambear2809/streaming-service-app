package io.github.marianciuc.streamingservice.media.controllers;

import io.github.marianciuc.streamingservice.media.dto.RtspIngestRequestDto;
import io.github.marianciuc.streamingservice.media.dto.RtspIngestStatusDto;
import io.github.marianciuc.streamingservice.media.services.RtspIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/media/video/rtsp")
@RequiredArgsConstructor
public class RtspController {

    private final RtspIngestService rtspIngestService;

    @PostMapping("/ingest")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
    public ResponseEntity<RtspIngestStatusDto> startIngest(@RequestBody @Valid RtspIngestRequestDto request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(rtspIngestService.startIngest(request));
    }

    @GetMapping("/ingest/{videoId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN')")
    public ResponseEntity<RtspIngestStatusDto> getIngestStatus(@PathVariable UUID videoId) {
        return ResponseEntity.ok(rtspIngestService.getStatus(videoId));
    }
}
