do $$ begin
  if current_database()<>'${expectedDatabase}' or current_schema()<>'${expectedSchema}'
    or current_user<>'${expectedAdmin}' or current_schema()='public'
    or to_regclass('read_subobject_version') is null then
    raise exception 'wrong READ PotVersion migration target' using errcode='23514';
  end if;
end $$;
create table read_pot_version (
  pot_id uuid not null, pot_version bigint not null check(pot_version>0),
  schema_version integer not null check(schema_version=1),
  payload jsonb not null check(jsonb_typeof(payload)='object'),
  primary key(pot_id,pot_version)
);
create table read_current_pot (
  pot_id uuid primary key, pot_version bigint not null check(pot_version>0),
  foreign key(pot_id,pot_version) references read_pot_version(pot_id,pot_version)
);
create trigger read_pot_version_immutable before update or delete on read_pot_version
  for each row execute function read_subobject_immutable();
create trigger read_pot_version_no_truncate before truncate on read_pot_version
  for each statement execute function read_subobject_immutable();
alter table read_pot_version enable always trigger read_pot_version_immutable;
alter table read_pot_version enable always trigger read_pot_version_no_truncate;
revoke all on read_pot_version,read_current_pot from public,"${expectedRuntime}";
grant select,insert on read_pot_version,read_current_pot to "${expectedRuntime}";
grant update(pot_version) on read_current_pot to "${expectedRuntime}";
