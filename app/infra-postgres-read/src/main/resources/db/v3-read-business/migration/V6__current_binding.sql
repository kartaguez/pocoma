do $$ begin
  if current_database()<>'${expectedDatabase}' or current_schema()<>'${expectedSchema}'
    or current_user<>'${expectedAdmin}' or current_schema()='public' then
    raise exception 'wrong READ Binding migration target' using errcode='23514';
  end if;
end $$;
create table read_current_binding(
 issuer text collate "C" not null check(length(issuer)>0),subject text collate "C" not null check(length(subject)>0),
 eui_id uuid not null unique,eui_version bigint not null check(eui_version>0),
 user_id uuid,binding_token text check(binding_token is null or length(btrim(binding_token))>0),
 primary key(issuer,subject),check((user_id is null)=(binding_token is null))
);
revoke all on read_current_binding from public,"${expectedRuntime}";
grant select,insert on read_current_binding to "${expectedRuntime}";
grant update(eui_version,user_id,binding_token) on read_current_binding to "${expectedRuntime}";
