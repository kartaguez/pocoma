-- Flyway executes this migration atomically. Exclude old writers during deployment.
lock table recorded_commands in share row exclusive mode;

do $$
begin
    if exists (select 1 from recorded_commands where envelope_version is distinct from 2) then
        raise exception 'WA.7: non-target recorded Command remains';
    end if;
    if exists (
        select 1 from recorded_commands
        where auth_issuer is null or btrim(auth_issuer) = ''
           or auth_subject is null or btrim(auth_subject) = ''
           or binding_id is null or auth_valid_until is null
           or auth_external_authorities_json is null
           or jsonb_typeof(auth_external_authorities_json) <> 'array'
           or jsonb_path_exists(auth_external_authorities_json, '$[*] ? (@.type() != "string")')
           or jsonb_path_exists(auth_external_authorities_json, '$[*] ? (@ == "")')
           or auth_user_id is not null or auth_authenticated_at is not null
           or auth_issued_at is not null or auth_permissions_json is not null
    ) then
        raise exception 'WA.7: invalid target recorded Command';
    end if;
    if exists (
        select 1 from consumption_slots s
        where s.consumable_type = 'COMMAND'
          and not case
              when jsonb_typeof(s.consumable_components) = 'array'
               and jsonb_array_length(s.consumable_components) = 1
              then (s.consumable_components->>0) ~
                  '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
              else false
          end
    ) then
        raise exception 'WA.7: malformed Command consumption slot';
    end if;
    if exists (
        select 1 from consumption_slots s
        where s.consumable_type = 'COMMAND'
          and not exists (
              select 1 from recorded_commands c
              where c.command_id = (s.consumable_components->>0)::uuid
          )
    ) then
        raise exception 'WA.7: orphan Command consumption slot';
    end if;
end $$;

alter table recorded_commands
    drop constraint ck_recorded_commands_auth_permissions,
    drop constraint ck_recorded_commands_envelope_version,
    drop constraint ck_recorded_commands_envelope_shape,
    drop column auth_user_id,
    drop column auth_authenticated_at,
    drop column auth_issued_at,
    drop column auth_permissions_json,
    drop column envelope_version,
    alter column auth_subject set not null,
    alter column binding_id set not null,
    alter column auth_external_authorities_json set not null,
    add constraint ck_recorded_commands_auth_subject check (btrim(auth_subject) <> ''),
    add constraint ck_recorded_commands_external_authorities check (
        jsonb_typeof(auth_external_authorities_json) = 'array'
        and not jsonb_path_exists(auth_external_authorities_json, '$[*] ? (@.type() != "string")')
        and not jsonb_path_exists(auth_external_authorities_json, '$[*] ? (@ == "")')
    );

do $$
begin
    if exists (select 1 from information_schema.columns
               where table_schema = current_schema()
                 and table_name = 'recorded_commands'
                 and column_name in ('envelope_version', 'auth_user_id',
                                     'auth_authenticated_at', 'auth_issued_at', 'auth_permissions_json')) then
        raise exception 'WA.7: recorded Command contraction verification failed';
    end if;
end $$;
