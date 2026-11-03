do $$ begin
  if current_database()<>'${expectedDatabase}' or current_schema()<>'${expectedSchema}'
    or current_user<>'${expectedAdmin}' or current_schema()='public' then
    raise exception 'wrong READ Visibility migration target' using errcode='23514';
  end if;
end $$;
create table read_pot_visibility_state(pot_id uuid primary key,materialized_version bigint not null check(materialized_version>=0));
create table read_user_pot(user_id uuid not null,pot_id uuid not null,pot_version bigint not null check(pot_version>0),visible boolean not null,primary key(user_id,pot_id));
create index read_visible_by_pot on read_user_pot(pot_id,user_id) where visible=true;
create index read_visible_by_user on read_user_pot(user_id,pot_id) where visible=true;
revoke all on read_pot_visibility_state,read_user_pot from public,"${expectedRuntime}";
grant select,insert on read_pot_visibility_state,read_user_pot to "${expectedRuntime}";
grant update(materialized_version) on read_pot_visibility_state to "${expectedRuntime}";
grant update(pot_version,visible) on read_user_pot to "${expectedRuntime}";
