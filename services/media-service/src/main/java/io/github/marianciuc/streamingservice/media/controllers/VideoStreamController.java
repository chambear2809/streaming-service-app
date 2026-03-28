/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: VideoStreamController.java
 *
 */

package io.github.marianciuc.streamingservice.media.controllers;


import io.github.marianciuc.streamingservice.media.services.PlaylistService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
public class VideoStreamController {

    private final PlaylistService playlistService;

    @GetMapping({"/playlist/{videoId}", "/playlist/{videoId}.m3u8"})
    public ResponseEntity<Resource> getMasterPlaylist(@PathVariable UUID videoId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"playlist.m3u8\"")
                .body(playlistService.getMasterPlaylistResource(videoId));
    }

    @GetMapping({"/playlist/{videoId}/{resolution}", "/playlist/{videoId}/{resolution}.m3u8"})
    public ResponseEntity<Resource> getResolutionPlaylist(@PathVariable UUID videoId, @PathVariable int resolution) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"playlist-" + resolution + ".m3u8\"")
                .body(playlistService.getResolutionPlaylistResource(videoId, resolution));
    }

    @GetMapping("/segment/{resolution}/{videoId}/segment{chunkIndex}.ts")
    public ResponseEntity<Resource> getVideoSegment(
            @PathVariable int resolution,
            @PathVariable UUID videoId,
            @PathVariable int chunkIndex
    ) {
        Resource resource = playlistService.getVideoSegmentResource(videoId, resolution, chunkIndex);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp2t"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"segment" + chunkIndex + ".ts\"")
                .body(resource);
    }
}
