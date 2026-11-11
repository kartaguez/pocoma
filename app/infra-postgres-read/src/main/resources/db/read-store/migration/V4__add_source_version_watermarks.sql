create table source_version_watermarks (
    pot_id uuid primary key,
    latest_version_seen bigint not null check (latest_version_seen >= 1),
    advanced_at timestamp with time zone not null
);
