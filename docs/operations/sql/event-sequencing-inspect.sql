\set ON_ERROR_STOP on
begin isolation level repeatable read read only;
set local statement_timeout = '60s';
select current_database(), session_user, current_user, version();
select * from event_sequencing_protocol;
select * from event_sequence_authorities order by family;
select * from event_sequencing_cutovers order by activated_at;
select * from inspect_event_sequencing();
select * from audit_event_sequencing_role(:'runtime_role'::name);
with recursive reachable(roleid, path) as (
 select oid,array[oid] from pg_roles where rolname=:'runtime_role'
 union all select m.roleid,path||m.roleid from pg_auth_members m join reachable r on m.member=r.roleid
 where not m.roleid=any(path)
) select p.rolname,p.rolsuper,p.rolcreaterole,p.rolcreatedb,p.rolreplication,p.rolbypassrls,r.path
 from reachable r join pg_roles p on p.oid=r.roleid;
select n.nspname,c.relname,pg_get_userbyid(c.relowner) as owner,c.relacl
 from pg_class c join pg_namespace n on n.oid=c.relnamespace
 where c.relname in ('event_sequencing_protocol','event_sequence_authorities','event_sequencing_cutovers',
 'business_event_positions','command_terminal_event_positions','binding_fact_positions','user_created_fact_positions',
 'business_event_outbox','command_terminal_events','external_identity_binding_facts','user_created_facts');
select n.nspname,p.proname,pg_get_userbyid(p.proowner) as owner,p.prosecdef,p.proconfig,p.proacl
 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where p.proname in ('reserve_event_positions','activate_event_sequencing','inspect_event_sequencing',
 'audit_event_sequencing_role') or p.proname like 'check_%_positions_complete';
select pid,usename,application_name,state,xact_start,wait_event_type,wait_event,pg_blocking_pids(pid) blockers,query
 from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and xact_start is not null;
select datname,xact_commit,xact_rollback,deadlocks,temp_bytes,blk_read_time,blk_write_time from pg_stat_database where datname=current_database();
select * from pg_stat_wal;
commit;
