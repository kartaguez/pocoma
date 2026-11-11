-- CURRENT READ extension. No secret decryption or WRITE connection is needed by materializers.
do $$ begin
 if current_database()<>'${expectedDatabase}' or current_schema()<>'${expectedSchema}' or current_user<>'${expectedAdmin}' or current_schema()='public' then
  raise exception 'Wrong READ Invitation migration target' using errcode='23514';
 end if;
end $$;
create table read_current_invitation (
 invitation_id uuid primary key, version bigint not null check(version>0),
 creation_request_id uuid not null unique,
 creator_issuer text collate "C" not null, creator_subject text collate "C" not null,
 pot_id uuid not null, expires_at timestamptz not null,
 token_hash text not null unique check(token_hash ~ '^[0-9a-f]{64}$'), protected_link text not null check(protected_link like 'v1.%')
);
create function read_invitation_monotone() returns trigger language plpgsql as $$
begin
 if tg_op<>'UPDATE' or new.version<=old.version
    or (to_jsonb(new)-array['version','expires_at','protected_link']) is distinct from (to_jsonb(old)-array['version','expires_at','protected_link']) then
  raise exception 'Invalid Invitation projection change' using errcode='23514';
 end if;
 return new;
end $$;
create trigger read_invitation_monotone before update or delete on read_current_invitation for each row execute function read_invitation_monotone();
create trigger read_invitation_no_truncate before truncate on read_current_invitation for each statement execute function read_subobject_immutable();
alter table read_command_result drop constraint read_command_result_write_outcome_check;
alter table read_command_result drop constraint read_command_result_check;
alter table read_command_result add constraint read_command_result_write_outcome_check check(write_outcome in ('CHANGED','UNCHANGED','REJECTED','INVITATION_CREATED'));
alter table read_command_result add constraint read_command_result_shape check(
 (operation='CreateInvitation' and write_outcome='INVITATION_CREATED' and resulting_pot_version is null and rejection is null)
 or (operation<>'CreateInvitation' and write_outcome in ('CHANGED','UNCHANGED') and resulting_pot_version>0 and resulting_pot_version is not null and rejection is null)
 or (write_outcome='REJECTED' and resulting_pot_version is null and rejection is not null and length(rejection)>0));
revoke all on read_current_invitation from public,"${expectedRuntime}";
grant select,insert on read_current_invitation to "${expectedRuntime}";
grant update(version,expires_at,protected_link) on read_current_invitation to "${expectedRuntime}";
