package io.github.marianciuc.streamingservice.content.kafka;

import io.github.marianciuc.streamingservice.content.exceptions.NotFoundException;
import io.github.marianciuc.streamingservice.content.kafka.messages.CreateMasterPlayListMessage;
import io.github.marianciuc.streamingservice.content.service.EpisodeService;
import io.github.marianciuc.streamingservice.content.service.MovieService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlaylistUpdateConsumer {

    private final MovieService movieService;
    private final EpisodeService episodeService;

    @KafkaListener(topics = "master-playlist-update-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumePlaylistUpdate(CreateMasterPlayListMessage message) {
        try {
            movieService.updateMovieMasterPlaylist(message);
            return;
        } catch (NotFoundException ignored) {
            log.debug("Movie for content {} not found, trying episode update", message.contentId());
        }

        episodeService.updateEpisodeMasterPlaylist(message);
    }
}
