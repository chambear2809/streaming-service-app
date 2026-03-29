package io.github.marianciuc.streamingservice.media.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class DemoMediaStateRepository {

    private final JdbcTemplate jdbcTemplate;

    DemoMediaStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<PersistedRtspJob> findAllJobs() {
        return jdbcTemplate.query(
                """
                SELECT job_id,
                       job_number,
                       content_id,
                       media_type,
                       target_title,
                       ingest_source_url,
                       source_url,
                       operator_email,
                       capture_duration_seconds,
                       status,
                       created_at,
                       updated_at,
                       playback_url,
                       playback_format,
                       error_message,
                       capture_path
                FROM demo_rtsp_jobs
                ORDER BY job_number DESC
                """,
                this::mapJob
        );
    }

    void saveJob(PersistedRtspJob job) {
        int updated = jdbcTemplate.update(
                """
                UPDATE demo_rtsp_jobs
                SET job_number = ?,
                    content_id = ?,
                    media_type = ?,
                    target_title = ?,
                    ingest_source_url = ?,
                    source_url = ?,
                    operator_email = ?,
                    capture_duration_seconds = ?,
                    status = ?,
                    created_at = ?,
                    updated_at = ?,
                    playback_url = ?,
                    playback_format = ?,
                    error_message = ?,
                    capture_path = ?
                WHERE job_id = ?
                """,
                job.jobNumber(),
                job.contentId(),
                job.mediaType(),
                job.targetTitle(),
                job.ingestSourceUrl(),
                job.sourceUrl(),
                job.operatorEmail(),
                job.captureDurationSeconds(),
                job.status(),
                Timestamp.from(job.createdAt()),
                Timestamp.from(job.updatedAt()),
                job.playbackUrl(),
                job.playbackFormat(),
                job.errorMessage(),
                job.capturePath(),
                job.jobId()
        );

        if (updated > 0) {
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO demo_rtsp_jobs (
                    job_id,
                    job_number,
                    content_id,
                    media_type,
                    target_title,
                    ingest_source_url,
                    source_url,
                    operator_email,
                    capture_duration_seconds,
                    status,
                    created_at,
                    updated_at,
                    playback_url,
                    playback_format,
                    error_message,
                    capture_path
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                job.jobId(),
                job.jobNumber(),
                job.contentId(),
                job.mediaType(),
                job.targetTitle(),
                job.ingestSourceUrl(),
                job.sourceUrl(),
                job.operatorEmail(),
                job.captureDurationSeconds(),
                job.status(),
                Timestamp.from(job.createdAt()),
                Timestamp.from(job.updatedAt()),
                job.playbackUrl(),
                job.playbackFormat(),
                job.errorMessage(),
                job.capturePath()
        );
    }

    Optional<String> loadStateJson(String key) {
        List<String> results = jdbcTemplate.query(
                "SELECT state_json FROM demo_media_state WHERE state_key = ?",
                (resultSet, rowNumber) -> resultSet.getString("state_json"),
                key
        );
        return results.stream().findFirst();
    }

    void saveStateJson(String key, String json) {
        int updated = jdbcTemplate.update(
                """
                UPDATE demo_media_state
                SET state_json = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE state_key = ?
                """,
                json,
                key
        );

        if (updated > 0) {
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO demo_media_state (state_key, state_json, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                """,
                key,
                json
        );
    }

    private PersistedRtspJob mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PersistedRtspJob(
                resultSet.getObject("job_id", UUID.class),
                resultSet.getLong("job_number"),
                resultSet.getObject("content_id", UUID.class),
                resultSet.getString("media_type"),
                resultSet.getString("target_title"),
                resultSet.getString("ingest_source_url"),
                resultSet.getString("source_url"),
                resultSet.getString("operator_email"),
                resultSet.getInt("capture_duration_seconds"),
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                resultSet.getString("playback_url"),
                resultSet.getString("playback_format"),
                resultSet.getString("error_message"),
                resultSet.getString("capture_path")
        );
    }
}
