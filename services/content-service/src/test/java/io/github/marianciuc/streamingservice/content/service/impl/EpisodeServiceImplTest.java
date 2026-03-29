package io.github.marianciuc.streamingservice.content.service.impl;

import io.github.marianciuc.streamingservice.content.dto.EpisodeDto;
import io.github.marianciuc.streamingservice.content.entity.Episode;
import io.github.marianciuc.streamingservice.content.entity.Season;
import io.github.marianciuc.streamingservice.content.enums.RecordStatus;
import io.github.marianciuc.streamingservice.content.kafka.KafkaMediaProducer;
import io.github.marianciuc.streamingservice.content.repository.EpisodeRepository;
import io.github.marianciuc.streamingservice.content.service.SeasonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodeServiceImplTest {

    @Mock
    private EpisodeRepository repository;

    @Mock
    private SeasonService seasonService;

    @Mock
    private KafkaMediaProducer kafkaMediaProducer;

    private EpisodeServiceImpl episodeService;

    @BeforeEach
    void setUp() {
        episodeService = new EpisodeServiceImpl(repository, seasonService, kafkaMediaProducer);
    }

    @Test
    void updateEpisodeSavesRequestedFieldsAndReturnsId() {
        UUID episodeId = UUID.randomUUID();
        Episode episode = sampleEpisode(episodeId);
        when(repository.findById(episodeId)).thenReturn(Optional.of(episode));
        when(repository.save(any(Episode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UUID updatedId = episodeService.updateEpisode(episodeId, new EpisodeDto(
                null,
                "Updated title",
                "Updated description",
                null,
                2,
                55,
                null,
                null,
                LocalDate.of(2024, 2, 2)
        ));

        assertThat(updatedId).isEqualTo(episodeId);
        assertThat(episode.getTitle()).isEqualTo("Updated title");
        assertThat(episode.getDescription()).isEqualTo("Updated description");
        assertThat(episode.getNumber()).isEqualTo(2);
        assertThat(episode.getDuration()).isEqualTo(55);
        assertThat(episode.getReleaseDate()).isEqualTo(LocalDate.of(2024, 2, 2));
        verify(repository).save(episode);
    }

    @Test
    void updateEpisodeIgnoresNullAndBlankTextFields() {
        UUID episodeId = UUID.randomUUID();
        Episode episode = sampleEpisode(episodeId);
        when(repository.findById(episodeId)).thenReturn(Optional.of(episode));
        when(repository.save(any(Episode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        episodeService.updateEpisode(episodeId, new EpisodeDto(
                null,
                "   ",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertThat(episode.getTitle()).isEqualTo("Original title");
        assertThat(episode.getDescription()).isEqualTo("Original description");
        verify(repository).save(episode);
    }

    private Episode sampleEpisode(UUID episodeId) {
        return Episode.builder()
                .id(episodeId)
                .recordStatus(RecordStatus.ACTIVE)
                .season(Season.builder().id(UUID.randomUUID()).recordStatus(RecordStatus.ACTIVE).build())
                .number(1)
                .duration(45)
                .title("Original title")
                .releaseDate(LocalDate.of(2024, 1, 1))
                .description("Original description")
                .build();
    }
}
