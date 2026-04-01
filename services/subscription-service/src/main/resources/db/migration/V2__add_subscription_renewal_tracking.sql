alter table if exists user_subscriptions
    add column if not exists renewal_claimed_at timestamp;

alter table if exists user_subscriptions
    add column if not exists renewal_order_id uuid;

create index if not exists idx_user_subscriptions_due_renewal
    on user_subscriptions (status, end_date, renewal_claimed_at);

create index if not exists idx_user_subscriptions_pending_finalize
    on user_subscriptions (status, renewal_order_id, renewal_claimed_at);
