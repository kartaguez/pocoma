do $$ begin
  if current_database()<>'${expectedDatabase}' or current_schema()<>'${expectedSchema}'
    or current_user<>'${expectedAdmin}' or current_schema()='public' then
    raise exception 'wrong READ Results migration target' using errcode='23514';
  end if;
end $$;
create table read_command_result(
 request_id uuid primary key,operation text not null check(length(operation)>0),
 requester_issuer text collate "C" not null check(length(requester_issuer)>0),requester_subject text collate "C" not null check(length(requester_subject)>0),
 pot_id uuid not null,write_outcome text not null check(write_outcome in ('CHANGED','UNCHANGED','REJECTED')),
 resulting_pot_version bigint,rejection text,
 check((write_outcome in ('CHANGED','UNCHANGED') and resulting_pot_version is not null and resulting_pot_version>0 and rejection is null)
    or (write_outcome='REJECTED' and resulting_pot_version is null and rejection is not null and length(rejection)>0))
);
create table read_identity_request_result(
 request_id uuid primary key,operation text not null check(operation in ('REGISTRATION','LINK','UNLINK')),
 requester_issuer text collate "C" not null check(length(requester_issuer)>0),requester_subject text collate "C" not null check(length(requester_subject)>0),
 write_outcome text not null check(write_outcome in ('CHANGED','REJECTED')),
 target_issuer text collate "C",target_subject text collate "C",target_version bigint,rejection text,
 check((write_outcome='CHANGED' and target_issuer is not null and length(target_issuer)>0 and target_subject is not null and length(target_subject)>0 and target_version is not null and target_version>0 and rejection is null)
    or (write_outcome='REJECTED' and target_issuer is null and target_subject is null and target_version is null and rejection is not null and length(rejection)>0)),
 check(operation<>'REGISTRATION' or write_outcome<>'CHANGED' or (target_issuer=requester_issuer and target_subject=requester_subject))
);
create trigger read_command_result_immutable before update or delete on read_command_result for each row execute function read_subobject_immutable();
create trigger read_command_result_no_truncate before truncate on read_command_result for each statement execute function read_subobject_immutable();
create trigger read_identity_result_immutable before update or delete on read_identity_request_result for each row execute function read_subobject_immutable();
create trigger read_identity_result_no_truncate before truncate on read_identity_request_result for each statement execute function read_subobject_immutable();
alter table read_command_result enable always trigger read_command_result_immutable;
alter table read_command_result enable always trigger read_command_result_no_truncate;
alter table read_identity_request_result enable always trigger read_identity_result_immutable;
alter table read_identity_request_result enable always trigger read_identity_result_no_truncate;
revoke all on read_command_result,read_identity_request_result from public,"${expectedRuntime}";
grant select,insert on read_command_result,read_identity_request_result to "${expectedRuntime}";
