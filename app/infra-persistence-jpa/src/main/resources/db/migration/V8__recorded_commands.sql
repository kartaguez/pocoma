create table recorded_commands (
    command_id uuid not null,
    command_type text not null,
    payload_json text not null,
    submitted_at timestamp with time zone not null,
    auth_user_id uuid not null,
    auth_issuer text not null,
    auth_authenticated_at timestamp with time zone not null,
    auth_issued_at timestamp with time zone not null,
    auth_valid_until timestamp with time zone not null,
    auth_permissions_json jsonb not null,
    primary key (command_id),
    constraint ck_recorded_commands_command_type check (btrim(command_type) <> ''),
    constraint ck_recorded_commands_auth_issuer check (btrim(auth_issuer) <> ''),
    constraint ck_recorded_commands_auth_permissions
        check (jsonb_typeof(auth_permissions_json) = 'array')
);

create index idx_recorded_commands_discovery
    on recorded_commands (submitted_at, command_id);
