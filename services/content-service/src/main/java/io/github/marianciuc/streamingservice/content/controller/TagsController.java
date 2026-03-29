/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: TagsController.java
 *
 */

package io.github.marianciuc.streamingservice.content.controller;

import io.github.marianciuc.streamingservice.content.dto.TagDto;
import io.github.marianciuc.streamingservice.content.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/content/tags")
@RequiredArgsConstructor
public class TagsController {

    private final TagService tagService;

    @PostMapping
    public ResponseEntity<UUID> createTag(@RequestParam("tag") String tagName) {
        return ResponseEntity.ok(tagService.createTag(tagName));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> updateTag(@PathVariable("id") UUID id, @RequestParam("tag") String tagName) {
        tagService.updateTag(id, tagName);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTag(@PathVariable("id") UUID id) {
        tagService.deleteTag(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<TagDto>> getAllTags(
            @RequestParam(value = "query", required = false, defaultValue = "") String query
    ) {
        return ResponseEntity.ok(tagService.findTagByNamePrefix(query));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TagDto> getTagById(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(TagDto.toTagDto(tagService.findTagById(id)));
    }
}
