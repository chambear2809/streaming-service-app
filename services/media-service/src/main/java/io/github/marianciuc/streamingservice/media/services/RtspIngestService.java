package io.github.marianciuc.streamingservice.media.services;

import io.github.marianciuc.streamingservice.media.dto.RtspIngestRequestDto;
import io.github.marianciuc.streamingservice.media.dto.RtspIngestStatusDto;

import java.util.UUID;

public interface RtspIngestService {
    RtspIngestStatusDto startIngest(RtspIngestRequestDto request);

    RtspIngestStatusDto getStatus(UUID videoId);
}
