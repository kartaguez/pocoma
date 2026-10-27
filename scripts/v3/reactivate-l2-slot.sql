-- Explicit administrative CAS only. psql target/user and C/id are mandatory.
\set ON_ERROR_STOP on
begin;
set local lock_timeout='500ms';
set local statement_timeout='2s';
select set_config('pocoma.expected_database', :'expected_database', true);
select set_config('pocoma.expected_schema', :'expected_schema', true);
select set_config('pocoma.expected_admin', :'expected_admin', true);
do $$
begin
 if current_database()<>current_setting('pocoma.expected_database')
    or current_schema()<>current_setting('pocoma.expected_schema') or current_schema()='public'
    or current_user<>current_setting('pocoma.expected_admin') then
   raise exception 'wrong reactivation target/role' using errcode='23514';
 end if;
end $$;
update consumption_slot set state='AVAILABLE',attempt_count=0,claim_token=null,claimed_until=null,retry_after=null
where consumption_id=:'consumption_id'::uuid and consumable_id=:'consumable_id'::uuid and state='QUARANTINED'
returning consumption_id,consumable_id,state,attempt_count;
-- UPDATE 1 = transition; UPDATE 0 = absent/non-Q/already reactivated, never a fabricated success.
commit;
