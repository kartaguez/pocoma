alter table projection_tasks rename to projection_tasks_legacy;
alter index uk_projection_tasks_pending_pot_type rename to uk_projection_tasks_legacy_pending_pot_type;
alter index idx_projection_tasks_claimable rename to idx_projection_tasks_legacy_claimable;
alter index idx_projection_tasks_pot_version rename to idx_projection_tasks_legacy_pot_version;

create table projection_tasks (
    id uuid primary key,
    projection_type varchar(255) not null,
    target_object_type varchar(255) not null,
    target_object_id varchar(255) not null,
    target_version bigint not null,
    partition_hash integer not null,
    created_at timestamp(6) with time zone not null,
    constraint ck_projection_tasks_projection_type check (length(trim(projection_type)) > 0),
    constraint ck_projection_tasks_target_object_type check (length(trim(target_object_type)) > 0),
    constraint ck_projection_tasks_target_object_id check (length(trim(target_object_id)) > 0),
    constraint ck_projection_tasks_target_version check (target_version >= 1),
    constraint uk_projection_tasks_key unique (
        projection_type, target_object_type, target_object_id, target_version
    )
);

create index idx_projection_tasks_discovery
    on projection_tasks (projection_type, partition_hash, created_at, id);
