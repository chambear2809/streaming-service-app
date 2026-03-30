package io.github.marianciuc.streamingservice.content.controller;

import io.github.marianciuc.streamingservice.content.dto.ContentDto;
import io.github.marianciuc.streamingservice.content.dto.PaginationResponse;
import io.github.marianciuc.streamingservice.content.enums.ContentType;
import io.github.marianciuc.streamingservice.content.enums.RecordStatus;
import io.github.marianciuc.streamingservice.content.service.ContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContentControllerTest {

    private final ContentService contentService = mock(ContentService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ContentController(contentService)).build();
    }

    @Test
    void getContentByIdReturns200() throws Exception {
        UUID contentId = UUID.randomUUID();
        ContentDto dto = sampleContent(contentId, "Breaking News Live");
        when(contentService.getContent(contentId)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/content/{id}", contentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Breaking News Live")))
                .andExpect(jsonPath("$.type", is("MOVIE")));

        verify(contentService).getContent(contentId);
    }

    @Test
    void findAllContentsReturnsPagedResults() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        PaginationResponse<List<ContentDto>> page = new PaginationResponse<>(
                1, 0, 10,
                List.of(sampleContent(id1, "Show A"), sampleContent(id2, "Show B"))
        );
        when(contentService.getAllContentByFilters(
                eq(""), isNull(), isNull(), eq(0), eq(10),
                isNull(), eq(""), eq(""), isNull(),
                eq(RecordStatus.ACTIVE), isNull()
        )).thenReturn(page);

        mockMvc.perform(get("/api/v1/content")
                        .param("page", "0")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].title", is("Show A")))
                .andExpect(jsonPath("$.currentPage", is(0)))
                .andExpect(jsonPath("$.pageSize", is(10)));
    }

    @Test
    void findAllContentsWithTitleFilter() throws Exception {
        UUID id = UUID.randomUUID();
        PaginationResponse<List<ContentDto>> page = new PaginationResponse<>(
                1, 0, 1,
                List.of(sampleContent(id, "News"))
        );
        when(contentService.getAllContentByFilters(
                eq("News"), isNull(), isNull(), eq(0), eq(1),
                isNull(), eq(""), eq(""), isNull(),
                eq(RecordStatus.ACTIVE), isNull()
        )).thenReturn(page);

        mockMvc.perform(get("/api/v1/content")
                        .param("title", "News"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title", is("News")));
    }

    @Test
    void getContentByInvalidUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/content/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    private ContentDto sampleContent(UUID id, String title) {
        return new ContentDto(
                id, title, "A test content item",
                ContentType.MOVIE,
                LocalDate.of(2024, 6, 1),
                "PG-13", null, null, false,
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}
