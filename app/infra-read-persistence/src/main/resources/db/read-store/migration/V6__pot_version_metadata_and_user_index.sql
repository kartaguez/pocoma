create table pocoma_read.pot_version_metadata (
    pot_id uuid not null,
    pot_version bigint not null check (pot_version >= 1),
    created_at timestamp with time zone not null,
    primary key (pot_id, pot_version)
);

create function pocoma_read.reject_pot_version_metadata_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'POT_VERSION_METADATA_IMMUTABLE';
end
$$;

create trigger pot_version_metadata_immutable
before update or delete on pocoma_read.pot_version_metadata
for each row execute function pocoma_read.reject_pot_version_metadata_mutation();

create table pocoma_read.pot_projection_user_index (
    artifact_id uuid not null references pocoma_read.pot_projection_snapshots(artifact_id) on delete restrict,
    pipeline_id varchar(128) not null,
    pipeline_version integer not null check (pipeline_version >= 1),
    pot_id uuid not null,
    pot_version bigint not null check (pot_version >= 1),
    user_id uuid not null,
    updated_at timestamp with time zone not null,
    pot_status varchar(16) not null check (pot_status in ('ACTIVE','DELETED')),
    primary key (pipeline_id, pipeline_version, pot_id, pot_version, user_id),
    unique (artifact_id, user_id)
);

create index pot_projection_user_index_listing
    on pocoma_read.pot_projection_user_index (user_id, pipeline_id, updated_at desc, pot_id asc)
    include (pipeline_version, pot_version, pot_status, artifact_id);

create index pot_projection_user_index_exact
    on pocoma_read.pot_projection_user_index (pipeline_id, pipeline_version, pot_id, pot_version);
