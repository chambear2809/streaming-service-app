alter table if exists orders
    add column if not exists subscription_activated_at timestamp;

create index if not exists idx_orders_pending_activation
    on orders (order_status, subscription_activated_at);
