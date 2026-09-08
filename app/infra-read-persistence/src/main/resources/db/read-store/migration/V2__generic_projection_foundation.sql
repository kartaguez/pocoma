create table projection_coverages (
    projection_type varchar(64) not null,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null check (pipeline_version >= 1),
    pot_id uuid not null,
    from_version bigint not null check (from_version >= 1),
    through_version bigint not null,
    primary key (projection_type, pipeline_id, pipeline_version, pot_id),
    check (projection_type <> '' and pipeline_id <> ''),
    check (through_version >= from_version)
);

create table projection_artifacts (
    artifact_id uuid primary key,
    projection_type varchar(64) not null,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null,
    pot_id uuid not null,
    pot_version bigint not null check (pot_version >= 1),
    content_digest char(64) not null check (content_digest ~ '^[0-9a-f]{64}$'),
    created_at timestamp with time zone not null,
    unique (projection_type, pipeline_id, pipeline_version, pot_id, pot_version),
    foreign key (projection_type, pipeline_id, pipeline_version, pot_id)
        references projection_coverages (projection_type, pipeline_id, pipeline_version, pot_id)
);

create table projection_failures (
    projection_type varchar(64) not null,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null,
    pot_id uuid not null,
    pot_version bigint not null check (pot_version >= 1),
    failed_at timestamp with time zone not null,
    terminal_failure_code varchar(128) not null check (terminal_failure_code <> ''),
    primary key (projection_type, pipeline_id, pipeline_version, pot_id, pot_version),
    foreign key (projection_type, pipeline_id, pipeline_version, pot_id)
        references projection_coverages (projection_type, pipeline_id, pipeline_version, pot_id)
);

create table projection_heads (
    projection_type varchar(64) not null,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null,
    pot_id uuid not null,
    latest_projected_version bigint not null check (latest_projected_version >= 1),
    advanced_at timestamp with time zone not null,
    primary key (projection_type, pipeline_id, pipeline_version, pot_id),
    foreign key (projection_type, pipeline_id, pipeline_version, pot_id)
        references projection_coverages (projection_type, pipeline_id, pipeline_version, pot_id)
);

create table projection_invariant_violations (
    violation_id uuid primary key,
    projection_type varchar(64) not null,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null,
    pot_id uuid not null,
    pot_version bigint not null,
    violation_type varchar(64) not null check (violation_type = 'DIVERGENT_DUPLICATE'),
    existing_artifact_id uuid not null,
    existing_digest char(64) not null,
    proposed_digest char(64) not null,
    detected_at timestamp with time zone not null
);

create index idx_projection_violations_identity
    on projection_invariant_violations
    (projection_type, pipeline_id, pipeline_version, pot_id, pot_version, detected_at);
