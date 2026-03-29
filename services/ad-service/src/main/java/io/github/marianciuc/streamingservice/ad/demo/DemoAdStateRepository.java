package io.github.marianciuc.streamingservice.ad.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
class DemoAdStateRepository {

    private final JdbcTemplate jdbcTemplate;

    DemoAdStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    DemoAdController.AdIssueStateRecord loadIssueState() {
        return jdbcTemplate.queryForObject(
                """
                SELECT enabled,
                       preset,
                       response_delay_ms,
                       ad_load_failure_enabled,
                       updated_at
                FROM demo_ad_issue_state
                WHERE state_key = 1
                """,
                (resultSet, rowNumber) -> new DemoAdController.AdIssueStateRecord(
                        resultSet.getBoolean("enabled"),
                        resultSet.getString("preset"),
                        resultSet.getInt("response_delay_ms"),
                        resultSet.getBoolean("ad_load_failure_enabled"),
                        resultSet.getTimestamp("updated_at").toInstant()
                )
        );
    }

    void saveIssueState(DemoAdController.AdIssueStateRecord state) {
        jdbcTemplate.update(
                """
                UPDATE demo_ad_issue_state
                SET enabled = ?,
                    preset = ?,
                    response_delay_ms = ?,
                    ad_load_failure_enabled = ?,
                    updated_at = ?
                WHERE state_key = 1
                """,
                state.enabled(),
                state.preset(),
                state.responseDelayMs(),
                state.adLoadFailureEnabled(),
                Timestamp.from(state.updatedAt())
        );
    }

    DemoAdController.ProgramTimelineStateRecord loadTimelineState() {
        return jdbcTemplate.queryForObject(
                """
                SELECT cycle_origin_at,
                       updated_at
                FROM demo_ad_timeline_state
                WHERE state_key = 1
                """,
                (resultSet, rowNumber) -> new DemoAdController.ProgramTimelineStateRecord(
                        resultSet.getTimestamp("cycle_origin_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()
                )
        );
    }

    void saveTimelineState(DemoAdController.ProgramTimelineStateRecord state) {
        jdbcTemplate.update(
                """
                UPDATE demo_ad_timeline_state
                SET cycle_origin_at = ?,
                    updated_at = ?
                WHERE state_key = 1
                """,
                Timestamp.from(state.cycleOriginAt()),
                Timestamp.from(state.updatedAt())
        );
    }
}
