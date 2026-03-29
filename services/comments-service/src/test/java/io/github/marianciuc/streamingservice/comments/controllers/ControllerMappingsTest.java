package io.github.marianciuc.streamingservice.comments.controllers;

import io.github.marianciuc.streamingservice.comments.dto.response.CommentsResponse;
import io.github.marianciuc.streamingservice.comments.services.CommentsRateService;
import io.github.marianciuc.streamingservice.comments.services.CommentsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControllerMappingsTest {

    private final CommentsService commentsService = mock(CommentsService.class);
    private final CommentsRateService commentsRateService = mock(CommentsRateService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CommentsController(commentsService),
                new RateController(commentsRateService)
        ).build();
    }

    @Test
    void exposesCommentRoutesUnderExpectedBasePaths() throws Exception {
        when(commentsService.add(any())).thenReturn(new CommentsResponse(
                UUID.randomUUID(),
                "hello",
                List.of(),
                UUID.randomUUID(),
                "alec",
                null
        ));

        mockMvc.perform(post("/api/v1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "hello",
                                  "contentId": "11111111-1111-1111-1111-111111111111"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/comments/rates/{commentId}/rate", UUID.randomUUID())
                        .param("rate", "LIKE"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/"))
                .andExpect(status().isNotFound());
    }
}
