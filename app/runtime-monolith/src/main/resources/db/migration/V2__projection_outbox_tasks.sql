create table business_event_outbox (
    id uuid not null,
    event_type varchar(255) not null,
    pot_id uuid not null,
    pot_partition_hash integer not null,
    aggregate_id uuid not null,
    version bigint not null,
    payload_json text not null,
    trace_id varchar(255),
    command_committed_at_nanos bigint,
    status varchar(32) not null,
    claim_token uuid,
    claimed_by varchar(255),
    lease_until timestamp with time zone,
    attempt_count integer not null,
    created_at timestamp with time zone not null,
    claimed_at timestamp with time zone,
    accepted_at timestamp with time zone,
    started_at timestamp with time zone,
    processed_at timestamp with time zone,
    failed_at timestamp with time zone,
    last_error text,
    primary key (id)
);

create table projection_tasks (
    id uuid not null,
    task_type varchar(64) not null,
    pot_id uuid not null,
    pot_partition_hash integer not null,
    target_version bigint not null,
    source_event_type varchar(255),
    source_event_min_id uuid not null,
    source_event_max_id uuid not null,
    trace_id varchar(255),
    command_committed_at_nanos bigint,
    status varchar(32) not null,
    claim_token uuid,
    claimed_by varchar(255),
    lease_until timestamp with time zone,
    attempt_count integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    claimed_at timestamp with time zone,
    accepted_at timestamp with time zone,
    started_at timestamp with time zone,
    done_at timestamp with time zone,
    failed_at timestamp with time zone,
    last_error text,
    primary key (id)
);

create unique index uk_projection_tasks_active_pot_type
    on projection_tasks (pot_id, task_type)
    where status in ('PENDING', 'CLAIMED', 'ACCEPTED', 'RUNNING');

create index idx_business_event_outbox_claimable
    on business_event_outbox (status, lease_until, pot_partition_hash, created_at);

create index idx_business_event_outbox_pot_version
    on business_event_outbox (pot_id, version);

create index idx_projection_tasks_claimable
    on projection_tasks (status, lease_until, pot_partition_hash, updated_at, created_at);

create index idx_projection_tasks_pot_version
    on projection_tasks (pot_id, target_version);
