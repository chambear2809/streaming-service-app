package io.github.marianciuc.streamingservice.media.controllers;

import io.github.marianciuc.streamingservice.media.exceptions.InvalidChunkUploadRequestException;
import io.github.marianciuc.streamingservice.media.handlers.ExceptionHandlerController;
import io.github.marianciuc.streamingservice.media.services.UploadVideoService;
import io.github.marianciuc.streamingservice.media.services.VideoUploadStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VideoControllerTest {

    private final UploadVideoService uploadVideoService = mock(UploadVideoService.class);
    private final VideoUploadStatusService videoUploadStatusService = mock(VideoUploadStatusService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new VideoController(uploadVideoService, videoUploadStatusService))
                .setControllerAdvice(new ExceptionHandlerController())
                .build();
    }

    @Test
    void returnsBadRequestForInvalidChunkMetadata() throws Exception {
        UUID fileId = UUID.randomUUID();
        doThrow(new InvalidChunkUploadRequestException("Chunk number must be greater than 0"))
                .when(uploadVideoService)
                .uploadVideo(any(), eq(0), eq(fileId), eq(5));

        mockMvc.perform(multipart("/api/v1/media/video/upload")
                        .file(videoFile())
                        .param("tempFileName", fileId.toString())
                        .param("chunkNumber", "0")
                        .param("totalChunks", "5"))
                .andExpect(status().isBadRequest());
    }

    private MockMultipartFile videoFile() throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/test_video.mp4"));
        return new MockMultipartFile("file", "test_video.mp4", "video/mp4", bytes);
    }
}
