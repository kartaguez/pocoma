alter table recorded_commands
    add column envelope_version smallint not null default 1,
    add column auth_subject text,
    add column binding_id uuid,
    add column auth_external_authorities_json jsonb;

alter table recorded_commands
    alter column auth_user_id drop not null,
    alter column auth_authenticated_at drop not null,
    alter column auth_issued_at drop not null,
    alter column auth_permissions_json drop not null;

alter table recorded_commands
    add constraint ck_recorded_commands_envelope_version
        check (envelope_version in (1, 2)),
    add constraint ck_recorded_commands_envelope_shape check (
        (
            envelope_version = 1
            and auth_user_id is not null
            and auth_authenticated_at is not null
            and auth_issued_at is not null
            and auth_permissions_json is not null
            and jsonb_typeof(auth_permissions_json) = 'array'
            and auth_subject is null
            and binding_id is null
            and auth_external_authorities_json is null
        )
        or
        (
            envelope_version = 2
            and auth_user_id is null
            and auth_authenticated_at is null
            and auth_issued_at is null
            and auth_permissions_json is null
            and auth_subject is not null
            and btrim(auth_subject) <> ''
            and binding_id is not null
            and auth_external_authorities_json is not null
            and jsonb_typeof(auth_external_authorities_json) = 'array'
        )
    );
