create table if not exists user_outbox_events
(
    id uuid primary key,
    aggregate_id uuid not null,
    event_type varchar(100) not null,
    payload text not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamp not null,
    published_at timestamp,
    last_error text,
    created_at timestamp,
    updated_at timestamp
);

create index if not exists idxUserOutboxPending on user_outbox_events (published_at, next_attempt_at);
create index if not exists idxUserOutboxAggregate on user_outbox_events (aggregate_id);
