/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: DirectorsController.java
 *
 */

package io.github.marianciuc.streamingservice.content.controller;


import io.github.marianciuc.streamingservice.content.dto.PersonDto;
import io.github.marianciuc.streamingservice.content.service.DirectorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/directors")
@RequiredArgsConstructor
public class DirectorsController {

    private final DirectorService directorService;

    @PostMapping
    public ResponseEntity<UUID> createDirector(@Valid @RequestBody PersonDto request) {
        return ResponseEntity.ok(directorService.createDirector(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PersonDto> getDirectorById(@PathVariable UUID id) {
        return ResponseEntity.ok(directorService.findDirectorById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> updateDirector(@PathVariable UUID id, @RequestBody PersonDto request) {
        directorService.updateDirector(id, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDirector(@PathVariable UUID id) {
        directorService.deleteDirector(id);
        return ResponseEntity.ok().build();
    }
}
