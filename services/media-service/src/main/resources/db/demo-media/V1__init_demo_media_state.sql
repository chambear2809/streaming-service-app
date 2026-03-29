CREATE TABLE IF NOT EXISTS demo_rtsp_jobs (
    job_id UUID PRIMARY KEY,
    job_number BIGINT NOT NULL,
    content_id UUID NOT NULL,
    media_type VARCHAR(32) NOT NULL,
    target_title VARCHAR(255) NOT NULL,
    ingest_source_url VARCHAR(1024) NOT NULL,
    source_url VARCHAR(1024) NOT NULL,
    operator_email VARCHAR(255) NOT NULL,
    capture_duration_seconds INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    playback_url VARCHAR(1024),
    playback_format VARCHAR(16),
    error_message VARCHAR(2048),
    capture_path VARCHAR(2048)
);

CREATE INDEX IF NOT EXISTS idx_demo_rtsp_jobs_content_id
    ON demo_rtsp_jobs(content_id);

CREATE INDEX IF NOT EXISTS idx_demo_rtsp_jobs_status
    ON demo_rtsp_jobs(status);

CREATE TABLE IF NOT EXISTS demo_media_state (
    state_key VARCHAR(64) PRIMARY KEY,
    state_json TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
