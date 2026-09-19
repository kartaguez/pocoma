create table projection_root (
    id bigint generated always as identity primary key,
    projection_type text not null check (btrim(projection_type) <> ''),
    target_object_type text not null check (btrim(target_object_type) <> ''),
    target_object_id text not null check (btrim(target_object_id) <> ''),
    target_version bigint not null check (target_version >= 1),
    unique (projection_type, target_object_type, target_object_id, target_version)
);

create table projection_artifact (
    id bigint generated always as identity primary key,
    projection_root_id bigint not null references projection_root(id) on delete restrict,
    artifact_type text not null check (btrim(artifact_type) <> ''),
    artifact_key text not null check (btrim(artifact_key) <> ''),
    payload jsonb not null,
    unique (projection_root_id, artifact_type, artifact_key)
);

create table projection_failure (
    failure_id uuid primary key,
    projection_type text not null check (btrim(projection_type) <> ''),
    target_object_type text not null check (btrim(target_object_type) <> ''),
    target_object_id text not null check (btrim(target_object_id) <> ''),
    target_version bigint not null check (target_version >= 1),
    failed_at timestamp(6) with time zone not null
);

create index projection_failure_key_idx on projection_failure (
    projection_type,
    target_object_type,
    target_object_id,
    target_version
);
