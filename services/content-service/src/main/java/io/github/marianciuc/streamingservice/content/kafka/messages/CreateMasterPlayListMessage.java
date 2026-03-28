/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: CreateMasterPlayListMessage.java
 *
 */

package io.github.marianciuc.streamingservice.content.kafka.messages;

import java.util.UUID;

public record CreateMasterPlayListMessage (
        UUID contentId,
        String mediaType,
        UUID masterPlaylistId,
        String masterPlaylistUrl
) {
    public String url() {
        return masterPlaylistUrl;
    }
}
