alter table video
    add column if not exists source_type varchar(32) default 'UPLOAD';

alter table video
    add column if not exists source_url varchar(2048);

alter table video
    add column if not exists is_live_stream boolean default false;
