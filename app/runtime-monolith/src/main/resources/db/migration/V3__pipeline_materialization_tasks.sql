create table event_4_pipeline_materialization_status (
    id uuid not null,
    event_id uuid not null,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null,
    status varchar(32) not null,
    attempt_count integer not null,
    failure_kind varchar(64),
    last_error text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    materialized_at timestamp with time zone,
    skipped_at timestamp with time zone,
    failed_at timestamp with time zone,
    primary key (id)
);

create table tasks_4_pipeline (
    id uuid not null,
    materialization_id uuid not null,
    event_id uuid not null,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null,
    task_type varchar(128) not null,
    task_key varchar(255) not null,
    task_payload text not null,
    partition_key varchar(255),
    partition_hash integer not null,
    status varchar(32) not null,
    claim_token uuid,
    claimed_by varchar(255),
    lease_until timestamp with time zone,
    attempt_count integer not null,
    failure_kind varchar(64),
    last_error text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    claimed_at timestamp with time zone,
    accepted_at timestamp with time zone,
    started_at timestamp with time zone,
    done_at timestamp with time zone,
    failed_at timestamp with time zone,
    primary key (id),
    constraint fk_tasks_4_pipeline_materialization
        foreign key (materialization_id)
        references event_4_pipeline_materialization_status (id)
);

create unique index uk_pipeline_materialization_event_pipeline
    on event_4_pipeline_materialization_status (event_id, pipeline_id, pipeline_version);

create index idx_pipeline_materialization_status
    on event_4_pipeline_materialization_status (pipeline_id, pipeline_version, status);

create index idx_pipeline_materialization_event
    on event_4_pipeline_materialization_status (event_id);

create unique index uk_tasks_4_pipeline_materialization_key
    on tasks_4_pipeline (materialization_id, task_key);

create unique index uk_tasks_4_pipeline_event_key
    on tasks_4_pipeline (pipeline_id, pipeline_version, event_id, task_key);

create index idx_tasks_4_pipeline_claimable
    on tasks_4_pipeline (status, lease_until, partition_hash, updated_at, created_at);

create index idx_tasks_4_pipeline_status
    on tasks_4_pipeline (pipeline_id, pipeline_version, status);

create index idx_tasks_4_pipeline_event
    on tasks_4_pipeline (event_id);
