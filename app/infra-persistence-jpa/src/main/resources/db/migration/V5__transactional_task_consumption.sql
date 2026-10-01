alter table tasks_4_pipeline
    add column target_version bigint;

update tasks_4_pipeline
set target_version = (task_payload::jsonb ->> 'targetVersion')::bigint;

do $$
begin
    if exists (select 1 from tasks_4_pipeline where target_version is null or target_version < 1) then
        raise exception 'Every durable Task must expose a positive targetVersion';
    end if;
end $$;

alter table tasks_4_pipeline
    alter column target_version set not null,
    alter column status set default 'PENDING',
    alter column attempt_count set default 0,
    add constraint ck_tasks_4_pipeline_target_version check (target_version >= 1);

create index idx_tasks_4_pipeline_discovery
    on tasks_4_pipeline (pipeline_id, pipeline_version, created_at, id);

create table balance_projection_artifacts (
    projection_id uuid primary key,
    projection_type varchar(64) not null,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null,
    pot_id uuid not null,
    pot_version bigint not null,
    created_at timestamp with time zone not null,
    constraint fk_balance_projection_artifact_pot
        foreign key (pot_id) references pot_global_versions (pot_id),
    constraint uk_balance_projection_identity
        unique (projection_type, pipeline_id, pipeline_version, pot_id, pot_version),
    constraint ck_balance_projection_type check (projection_type = 'POT_BALANCES'),
    constraint ck_balance_projection_pipeline_version check (pipeline_version >= 1),
    constraint ck_balance_projection_pot_version check (pot_version >= 1)
);

create table balance_projection_entries (
    projection_id uuid not null,
    shareholder_id uuid not null,
    value_numerator bigint not null,
    value_denominator bigint not null,
    primary key (projection_id, shareholder_id),
    constraint fk_balance_projection_entry_artifact
        foreign key (projection_id) references balance_projection_artifacts (projection_id) on delete cascade,
    constraint ck_balance_projection_entry_denominator check (value_denominator > 0)
);

create index idx_balance_projection_pot
    on balance_projection_artifacts (pot_id, pot_version, pipeline_id, pipeline_version);
