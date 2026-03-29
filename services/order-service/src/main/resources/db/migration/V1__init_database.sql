create table if not exists orders
(
    id                   uuid primary key not null,
    user_id              uuid             not null,
    amount               numeric(19, 4)   not null,
    payment_id           uuid,
    subscription_id      uuid             not null,
    order_create_date    timestamp        not null default now(),
    order_completed_date timestamp,
    order_status         varchar(50)      not null,
    scheduled_time       date
);
