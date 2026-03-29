CREATE TABLE IF NOT EXISTS demo_ad_issue_state (
    state_key INTEGER PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    preset VARCHAR(64) NOT NULL,
    response_delay_ms INTEGER NOT NULL,
    ad_load_failure_enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS demo_ad_timeline_state (
    state_key INTEGER PRIMARY KEY,
    cycle_origin_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO demo_ad_issue_state (
    state_key,
    enabled,
    preset,
    response_delay_ms,
    ad_load_failure_enabled,
    updated_at
)
SELECT 1, FALSE, 'clear', 0, FALSE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM demo_ad_issue_state WHERE state_key = 1
);

INSERT INTO demo_ad_timeline_state (
    state_key,
    cycle_origin_at,
    updated_at
)
SELECT 1, TIMESTAMP '2026-01-01 00:00:00', CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM demo_ad_timeline_state WHERE state_key = 1
);
