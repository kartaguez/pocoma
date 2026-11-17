do $$ begin
  if current_database()<>'${expectedDatabase}' or current_schema()<>'${expectedSchema}'
    or current_user<>'${expectedAdmin}' or current_schema()='public' then
    raise exception 'wrong READ Balance migration target' using errcode='23514';
  end if;
end $$;
create table read_pot_balance (
  pot_id uuid not null, pot_version bigint not null check(pot_version>0),
  schema_version integer not null check(schema_version=1),
  payload jsonb not null check(jsonb_typeof(payload)='array'), primary key(pot_id,pot_version)
);
create trigger read_pot_balance_immutable before update or delete on read_pot_balance
  for each row execute function read_subobject_immutable();
create trigger read_pot_balance_no_truncate before truncate on read_pot_balance
  for each statement execute function read_subobject_immutable();
alter table read_pot_balance enable always trigger read_pot_balance_immutable;
alter table read_pot_balance enable always trigger read_pot_balance_no_truncate;
revoke all on read_pot_balance from public,"${expectedRuntime}";
grant select,insert on read_pot_balance to "${expectedRuntime}";
