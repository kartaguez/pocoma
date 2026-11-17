-- Explicit administrative provisioning of a NEW dedicated Identity DEV database.
-- New sessions only; reconnect pools after changing these database defaults.
\set ON_ERROR_STOP on
begin;
select set_config('pocoma.expected_database', :'expected_database', true);
select set_config('pocoma.expected_schema', :'expected_schema', true);
select set_config('pocoma.expected_admin', :'expected_admin', true);
do $$
begin
 if current_database()<>current_setting('pocoma.expected_database')
    or current_schema()<>current_setting('pocoma.expected_schema') or current_schema()='public'
    or current_user<>current_setting('pocoma.expected_admin')
    or position('%e' in current_setting('log_line_prefix'))=0
    or position('%p' in current_setting('log_line_prefix'))=0
    or position('%x' in current_setting('log_line_prefix'))=0
 then raise exception 'Identity logging requires exact administrative target and SQLSTATE/PID/XID server prefix';end if;
end $$;
-- Terse removes UNIQUE/CHECK DETAIL (parameter limits alone do not).
-- Keep ERROR messages; put SQLSTATE/PID/XID in the server prefix, not SQL values.
alter database :"expected_database" set log_error_verbosity='terse';
alter database :"expected_database" set log_min_messages='warning';
alter database :"expected_database" set log_min_error_statement='panic';
alter database :"expected_database" set log_statement='none';
alter database :"expected_database" set log_duration='off';
alter database :"expected_database" set log_min_duration_statement='-1';
alter database :"expected_database" set log_min_duration_sample='-1';
alter database :"expected_database" set log_transaction_sample_rate='0';
alter database :"expected_database" set log_parameter_max_length='0';
alter database :"expected_database" set log_parameter_max_length_on_error='0';
commit;
