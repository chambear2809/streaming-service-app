package io.github.marianciuc.streamingservice.media.services.impl;

import io.github.marianciuc.streamingservice.media.dto.ResolutionDto;
import io.github.marianciuc.streamingservice.media.exceptions.CompressingException;
import io.github.marianciuc.streamingservice.media.services.PlaylistService;
import io.github.marianciuc.streamingservice.media.services.VideoStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FFmpegJavaCVServiceTest {

    @Mock
    private VideoStorageService videoStorageService;

    @Mock
    private PlaylistService playlistService;

    @InjectMocks
    private FFmpegJavaCVService ffmpegJavaCVService;

    @Test
    void wrapsStorageIoErrorsInCompressingException() {
        ResolutionDto resolutionDto = new ResolutionDto(UUID.randomUUID(), "1920", "1080", 720, 1280, 3000);
        UUID videoId = UUID.randomUUID();

        when(videoStorageService.assembleVideo(videoId)).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk unavailable");
            }
        });

        assertThrows(CompressingException.class,
                () -> ffmpegJavaCVService.compressVideoAndUploadToStorage(resolutionDto, videoId));

        verify(videoStorageService).assembleVideo(videoId);
        verifyNoInteractions(playlistService);
    }
}
