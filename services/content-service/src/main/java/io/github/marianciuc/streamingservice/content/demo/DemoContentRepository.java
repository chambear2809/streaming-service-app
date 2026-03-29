package io.github.marianciuc.streamingservice.content.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class DemoContentRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<DemoCatalogItem> rowMapper = this::mapItem;

    DemoContentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    List<DemoCatalogItem> findAll() {
        return jdbcTemplate.query(
                """
                SELECT id,
                       title,
                       description,
                       type,
                       age_rating,
                       release_date,
                       runtime_label,
                       headline,
                       feature_line,
                       channel_label,
                       programming_track,
                       lifecycle_state,
                       readiness_label,
                       signal_profile,
                       genre_list_json,
                       watch_url,
                       scheduled_start_at,
                       published_at,
                       on_air_at,
                       archived_at,
                       updated_at
                FROM demo_content_items
                ORDER BY
                    CASE lifecycle_state
                        WHEN 'ON_AIR' THEN 0
                        WHEN 'PUBLISHED' THEN 1
                        WHEN 'SCHEDULED' THEN 2
                        WHEN 'QC_REVIEW' THEN 3
                        WHEN 'INGESTING' THEN 4
                        WHEN 'INGEST_ERROR' THEN 5
                        WHEN 'ARCHIVED' THEN 6
                        ELSE 7
                    END,
                    title ASC
                """,
                rowMapper
        );
    }

    Optional<DemoCatalogItem> findById(UUID id) {
        List<DemoCatalogItem> results = jdbcTemplate.query(
                """
                SELECT id,
                       title,
                       description,
                       type,
                       age_rating,
                       release_date,
                       runtime_label,
                       headline,
                       feature_line,
                       channel_label,
                       programming_track,
                       lifecycle_state,
                       readiness_label,
                       signal_profile,
                       genre_list_json,
                       watch_url,
                       scheduled_start_at,
                       published_at,
                       on_air_at,
                       archived_at,
                       updated_at
                FROM demo_content_items
                WHERE id = ?
                """,
                rowMapper,
                id
        );
        return results.stream().findFirst();
    }

    boolean updateLifecycle(UUID id, DemoContentLifecycleUpdate update) {
        return jdbcTemplate.update(
                """
                UPDATE demo_content_items
                SET lifecycle_state = ?,
                    readiness_label = COALESCE(NULLIF(?, ''), readiness_label),
                    signal_profile = COALESCE(NULLIF(?, ''), signal_profile),
                    channel_label = COALESCE(NULLIF(?, ''), channel_label),
                    programming_track = COALESCE(NULLIF(?, ''), programming_track),
                    scheduled_start_at = CASE WHEN ? = 'SCHEDULED' AND scheduled_start_at IS NULL THEN CURRENT_TIMESTAMP ELSE scheduled_start_at END,
                    published_at = CASE WHEN ? = 'PUBLISHED' AND published_at IS NULL THEN CURRENT_TIMESTAMP ELSE published_at END,
                    on_air_at = CASE WHEN ? = 'ON_AIR' THEN CURRENT_TIMESTAMP ELSE on_air_at END,
                    archived_at = CASE WHEN ? = 'ARCHIVED' THEN CURRENT_TIMESTAMP ELSE archived_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                normalize(update.lifecycleState()),
                normalize(update.readinessLabel()),
                normalize(update.signalProfile()),
                normalize(update.channelLabel()),
                normalize(update.programmingTrack()),
                normalize(update.lifecycleState()),
                normalize(update.lifecycleState()),
                normalize(update.lifecycleState()),
                normalize(update.lifecycleState()),
                id
        ) > 0;
    }

    private DemoCatalogItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DemoCatalogItem(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("title"),
                resultSet.getString("description"),
                resultSet.getString("type"),
                resultSet.getString("age_rating"),
                resultSet.getDate("release_date").toLocalDate(),
                resultSet.getString("runtime_label"),
                resultSet.getString("headline"),
                resultSet.getString("feature_line"),
                resultSet.getString("channel_label"),
                resultSet.getString("programming_track"),
                resultSet.getString("lifecycle_state"),
                resultSet.getString("readiness_label"),
                resultSet.getString("signal_profile"),
                readGenres(resultSet.getString("genre_list_json")),
                resultSet.getString("watch_url"),
                toInstant(resultSet.getTimestamp("scheduled_start_at")),
                toInstant(resultSet.getTimestamp("published_at")),
                toInstant(resultSet.getTimestamp("on_air_at")),
                toInstant(resultSet.getTimestamp("archived_at")),
                toInstant(resultSet.getTimestamp("updated_at"))
        );
    }

    private List<String> readGenres(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read demo catalog genres.", exception);
        }
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }
}
