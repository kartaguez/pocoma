-- L0 proof fixtures only. No Pot, Identity, Consumption or production schema contract.
create table foundation_effect (
    id uuid primary key,
    payload text not null
);

create table foundation_commit_guard (
    id uuid primary key,
    effect_id uuid not null references foundation_effect(id) deferrable initially deferred
);

create table component_revision (
    component_id uuid not null,
    local_version bigint not null check (local_version > 0),
    active_from bigint not null check (active_from > 0),
    active_to bigint,
    payload text not null,
    primary key (component_id, local_version),
    unique (component_id, active_from),
    check (active_to is null or active_to > active_from)
);

create unique index component_revision_one_open
    on component_revision(component_id) where active_to is null;
-- This index is not a proof against closed-interval overlap or gaps.
