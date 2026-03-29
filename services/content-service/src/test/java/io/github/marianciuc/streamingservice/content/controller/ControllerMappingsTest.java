package io.github.marianciuc.streamingservice.content.controller;

import io.github.marianciuc.streamingservice.content.dto.EpisodeDto;
import io.github.marianciuc.streamingservice.content.dto.PersonDto;
import io.github.marianciuc.streamingservice.content.dto.SeasonDto;
import io.github.marianciuc.streamingservice.content.dto.TagDto;
import io.github.marianciuc.streamingservice.content.enums.RecordStatus;
import io.github.marianciuc.streamingservice.content.service.ActorService;
import io.github.marianciuc.streamingservice.content.service.DirectorService;
import io.github.marianciuc.streamingservice.content.service.EpisodeService;
import io.github.marianciuc.streamingservice.content.service.SeasonService;
import io.github.marianciuc.streamingservice.content.service.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControllerMappingsTest {

    private final EpisodeService episodeService = mock(EpisodeService.class);
    private final SeasonService seasonService = mock(SeasonService.class);
    private final ActorService actorService = mock(ActorService.class);
    private final DirectorService directorService = mock(DirectorService.class);
    private final TagService tagService = mock(TagService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new EpisodeController(episodeService),
                new SeasonController(seasonService),
                new ActorController(actorService),
                new DirectorsController(directorService),
                new TagsController(tagService),
                new RateController()
        ).build();
    }

    @Test
    void exposesControllersUnderExpectedBasePaths() throws Exception {
        UUID id = UUID.randomUUID();
        when(episodeService.getEpisode(id)).thenReturn(sampleEpisode(id));
        when(seasonService.findSeason(id)).thenReturn(sampleSeason(id));
        when(actorService.findActorById(id)).thenReturn(samplePerson(id));
        when(directorService.findDirectorById(id)).thenReturn(samplePerson(id));
        when(tagService.findTagByNamePrefix("ac")).thenReturn(List.of(sampleTag(id)));

        mockMvc.perform(get("/api/v1/episodes/{id}", id))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/episodes/{id}", id))
                .andExpect(status().isOk());

        verify(episodeService).deleteEpisode(id);

        mockMvc.perform(get("/api/v1/seasons/{id}", id))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/actors/{id}", id))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/directors/{id}", id))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/content/tags").param("query", "ac"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/rates/rate/{contentId}", id).param("rate", "5"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/"))
                .andExpect(status().isNotFound());
    }

    private EpisodeDto sampleEpisode(UUID id) {
        return new EpisodeDto(
                id,
                "Episode 1",
                "Description",
                UUID.randomUUID(),
                1,
                42,
                null,
                null,
                LocalDate.of(2024, 1, 1)
        );
    }

    private SeasonDto sampleSeason(UUID id) {
        return new SeasonDto(
                id,
                "Season 1",
                "Season description",
                List.of(),
                UUID.randomUUID(),
                1
        );
    }

    private PersonDto samplePerson(UUID id) {
        return new PersonDto(
                id,
                "Alec",
                "Chamberlain",
                LocalDate.of(1990, 1, 1),
                "New York",
                "Bio",
                null,
                RecordStatus.ACTIVE
        );
    }

    private TagDto sampleTag(UUID id) {
        return new TagDto(
                id,
                "acme",
                LocalDateTime.now(),
                LocalDateTime.now(),
                RecordStatus.ACTIVE
        );
    }
}
