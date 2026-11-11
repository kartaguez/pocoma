create table pot_version_metadata (
    pot_id uuid not null references pot_global_versions(pot_id) on delete restrict,
    version bigint not null check (version >= 1),
    created_at timestamp with time zone not null,
    primary key (pot_id, version)
);

do $$
begin
    if exists (
        select 1
        from pot_global_versions
    ) then
        raise exception using
            message = 'POT_VERSION_METADATA_LEGACY_RESET_REQUIRED',
            detail = 'Existing Pot versions have no exact createdAt source; reset legacy development data before applying V11.';
    end if;
end
$$;

create function reject_pot_version_metadata_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'POT_VERSION_METADATA_IMMUTABLE';
end
$$;

create trigger pot_version_metadata_immutable
before update or delete on pot_version_metadata
for each row execute function reject_pot_version_metadata_mutation();
