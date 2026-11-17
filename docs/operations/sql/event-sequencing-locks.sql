-- Cheap live diagnostic: no historical scans. Requires monitoring privileges.
select * from event_sequencing_protocol;
select * from event_sequence_authorities order by family;
select pid,usename,application_name,state,clock_timestamp()-xact_start as transaction_age,
       wait_event_type,wait_event,pg_blocking_pids(pid) as blockers,query
from pg_stat_activity where datname=current_database() and xact_start is not null
order by xact_start;
