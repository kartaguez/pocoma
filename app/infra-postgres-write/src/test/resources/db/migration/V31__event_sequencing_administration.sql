-- Administrative capabilities only. No activation, data backfill, runtime grants or startup hook.
-- SECURITY INVOKER: the maintenance operator must own the objects and hold pg_read_all_stats.
-- One transaction; table locks provide exclusion even if an uncooperative writer returns.
create table event_sequencing_cutovers (
    operation_id uuid primary key,
    activated_at timestamptz not null,
    operator_name name not null,
    runtime_role name not null,
    boundaries jsonb not null
);
revoke all on event_sequencing_cutovers from public, pocoma_event_runtime;

-- Expensive exhaustive check: run on demand/offline, never on the transaction hot path.
create function inspect_event_sequencing()
returns table(family text, source_count bigint, position_count bigint, authority bigint,
              missing bigint, orphan bigint, minimum bigint, maximum bigint, valid boolean)
language plpgsql set search_path = pg_catalog as $$
begin
    return query
    select 'BUSINESS_EVENT'::text, s.n, p.n, e.last_position, m.n, o.n, p.lo, p.hi,
           s.n=p.n and p.n=e.last_position and m.n=0 and o.n=0
           and ((p.n=0 and e.last_position=0) or (p.lo=1 and p.hi=p.n))
    from (select count(*) n from business_event_outbox) s
    cross join (select count(*) n, min(position) lo, max(position) hi from business_event_positions) p
    cross join (select count(*) n from business_event_outbox s where not exists
                (select 1 from business_event_positions p where p.event_id=s.id)) m
    cross join (select count(*) n from business_event_positions p where not exists
                (select 1 from business_event_outbox s where s.id=p.event_id)) o
    join event_sequence_authorities e on e.family='BUSINESS_EVENT';
    return query
    select 'COMMAND_TERMINAL_EVENT'::text, s.n, p.n, e.last_position, m.n, o.n, p.lo, p.hi,
           s.n=p.n and p.n=e.last_position and m.n=0 and o.n=0
           and ((p.n=0 and e.last_position=0) or (p.lo=1 and p.hi=p.n))
    from (select count(*) n from command_terminal_events) s
    cross join (select count(*) n, min(position) lo, max(position) hi from command_terminal_event_positions) p
    cross join (select count(*) n from command_terminal_events s where not exists
                (select 1 from command_terminal_event_positions p where p.event_id=s.event_id)) m
    cross join (select count(*) n from command_terminal_event_positions p where not exists
                (select 1 from command_terminal_events s where s.event_id=p.event_id)) o
    join event_sequence_authorities e on e.family='COMMAND_TERMINAL_EVENT';
    return query
    select 'BINDING_FACT'::text, s.n, p.n, e.last_position, m.n, o.n, p.lo, p.hi,
           s.n=p.n and p.n=e.last_position and m.n=0 and o.n=0
           and ((p.n=0 and e.last_position=0) or (p.lo=1 and p.hi=p.n))
    from (select count(*) n from external_identity_binding_facts) s
    cross join (select count(*) n, min(position) lo, max(position) hi from binding_fact_positions) p
    cross join (select count(*) n from external_identity_binding_facts s where not exists
                (select 1 from binding_fact_positions p where p.event_id=s.event_id)) m
    cross join (select count(*) n from binding_fact_positions p where not exists
                (select 1 from external_identity_binding_facts s where s.event_id=p.event_id)) o
    join event_sequence_authorities e on e.family='BINDING_FACT';
    return query
    select 'USER_CREATED_FACT'::text, s.n, p.n, e.last_position, m.n, o.n, p.lo, p.hi,
           s.n=p.n and p.n=e.last_position and m.n=0 and o.n=0
           and ((p.n=0 and e.last_position=0) or (p.lo=1 and p.hi=p.n))
    from (select count(*) n from user_created_facts) s
    cross join (select count(*) n, min(position) lo, max(position) hi from user_created_fact_positions) p
    cross join (select count(*) n from user_created_facts s where not exists
                (select 1 from user_created_fact_positions p where p.event_id=s.event_id)) m
    cross join (select count(*) n from user_created_fact_positions p where not exists
                (select 1 from user_created_facts s where s.event_id=p.event_id)) o
    join event_sequence_authorities e on e.family='USER_CREATED_FACT';
end $$;

-- Conservative closure includes SET ROLE paths even for NOINHERIT memberships.
-- Empty findings means the sequencing privilege profile passes, not that R1 disappears.
create function audit_event_sequencing_role(runtime_name name)
returns table(finding text) language plpgsql set search_path = pg_catalog as $$
declare r record; obj text; privilege text; role_ids oid[]; schema_name text := (current_schemas(false))[2];
begin
    if not exists (select 1 from pg_roles where rolname=runtime_name) then
        return query select 'runtime role missing'::text; return;
    end if;
    with recursive runtime_members(id) as (
        select oid from pg_roles where rolname=runtime_name
        union select m.member from pg_auth_members m join runtime_members c on m.roleid=c.id
    ), reachable(id) as (
        select id from runtime_members
        union select m.roleid from pg_auth_members m join reachable c on m.member=c.id
    ) select array_agg(id) into role_ids from reachable;
    for r in select * from pg_roles where oid=any(role_ids) loop
        if r.rolsuper or r.rolcreaterole or r.rolcreatedb or r.rolreplication or r.rolbypassrls then
            return query select 'administrative membership: '||r.rolname;
        end if;
        if has_database_privilege(r.oid,current_database(),'CREATE') then
            return query select 'database CREATE via '||r.rolname;
        end if;
        if has_schema_privilege(r.oid,schema_name,'CREATE') then
            return query select 'trusted schema CREATE via '||r.rolname;
        end if;
        if has_parameter_privilege(r.oid,'session_replication_role','SET') then
            return query select 'replication trigger bypass via '||r.rolname;
        end if;
        if r.rolname in ('pg_execute_server_program','pg_write_server_files') then
            return query select 'server execution membership: '||r.rolname;
        end if;
        foreach obj in array array['business_event_outbox','command_terminal_events',
                                  'external_identity_binding_facts','user_created_facts'] loop
            foreach privilege in array array['TRUNCATE','TRIGGER','REFERENCES','MAINTAIN'] loop
                if has_table_privilege(r.oid,obj,privilege) then
                    return query select 'forbidden source '||privilege||' on '||obj||' via '||r.rolname;
                end if;
            end loop;
        end loop;
        for obj in select unnest(array['event_sequencing_protocol','event_sequence_authorities',
            'business_event_positions','command_terminal_event_positions','binding_fact_positions',
            'user_created_fact_positions','event_sequencing_cutovers']) loop
            foreach privilege in array array['UPDATE','DELETE','TRUNCATE','TRIGGER','REFERENCES','MAINTAIN'] loop
                if has_table_privilege(r.oid,obj,privilege) then
                    return query select 'forbidden '||privilege||' on '||obj||' via '||r.rolname;
                end if;
            end loop;
            if obj in ('event_sequence_authorities','event_sequencing_protocol','event_sequencing_cutovers')
               and has_table_privilege(r.oid,obj,'INSERT') then
                return query select 'forbidden INSERT on '||obj||' via '||r.rolname;
            end if;
        end loop;
        for obj in select c.oid::regclass::text||'.'||a.attname from pg_class c
                   join pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
                   where c.relnamespace=schema_name::regnamespace and c.relname in
                   ('event_sequencing_protocol','event_sequence_authorities','event_sequencing_cutovers',
                    'business_event_positions','command_terminal_event_positions','binding_fact_positions','user_created_fact_positions')
                   and has_column_privilege(r.oid,c.oid,a.attnum,'UPDATE') loop
            return query select 'forbidden column UPDATE: '||obj||' via '||r.rolname;
        end loop;
        if has_function_privilege(r.oid,'activate_event_sequencing(uuid,name)','EXECUTE') then
            return query select 'maintenance EXECUTE via '||r.rolname;
        end if;
        for obj in select oid::regprocedure::text from pg_proc where pronamespace=schema_name::regnamespace
                   and proname like 'check_%_positions_complete' loop
            if has_function_privilege(r.oid,obj,'EXECUTE') then
                return query select 'trigger function EXECUTE via '||r.rolname;
            end if;
        end loop;
    end loop;
    -- Ownership bypasses grants; include sources, positions, functions, schema and DB.
    for obj in select c.oid::regclass::text from pg_class c where c.relnamespace=schema_name::regnamespace
               and c.relowner=any(role_ids)
               union all select p.oid::regprocedure::text from pg_proc p
               where p.pronamespace=schema_name::regnamespace and p.proowner=any(role_ids)
               union all select nspname from pg_namespace where nspname=schema_name and nspowner=any(role_ids)
               union all select datname from pg_database where datname=current_database() and datdba=any(role_ids) loop
        return query select 'runtime ownership: '||obj;
    end loop;
    foreach obj in array array['event_sequencing_protocol','event_position','business_event_positions',
          'command_terminal_event_positions','binding_fact_positions','user_created_fact_positions'] loop
        if not has_table_privilege(runtime_name,obj,'SELECT') then
            return query select 'missing SELECT on '||obj;
        end if;
    end loop;
    foreach obj in array array['business_event_positions','command_terminal_event_positions',
                              'binding_fact_positions','user_created_fact_positions'] loop
        if not has_table_privilege(runtime_name,obj,'INSERT') then
            return query select 'missing INSERT on '||obj;
        end if;
    end loop;
    if not has_function_privilege(runtime_name,'reserve_event_positions(text,bigint)','EXECUTE') then
        return query select 'missing reservation EXECUTE'::text;
    end if;
    if not has_schema_privilege(runtime_name,schema_name,'USAGE') then
        return query select 'missing trusted schema USAGE'::text;
    end if;
end $$;

create function activate_event_sequencing(requested_operation uuid, runtime_name name)
returns jsonb language plpgsql set search_path = pg_catalog as $$
declare state text; result jsonb; issue text; f record; n bigint; existing bigint; mismatch bigint;
        schema_name text := (current_schemas(false))[2];
begin
    if requested_operation is null or runtime_name is null then
        raise exception 'operation UUID and runtime role required' using errcode='22023';
    end if;
    if current_setting('transaction_isolation') <> 'read committed' then
        raise exception 'maintenance requires READ COMMITTED' using errcode='25001';
    end if;
    -- No default timeouts: caller must establish a bounded maintenance window.
    if current_setting('lock_timeout')='0' or current_setting('statement_timeout')='0' then
        raise exception 'bounded lock_timeout and statement_timeout required' using errcode='55000';
    end if;
    if not pg_has_role(current_user,'pg_read_all_stats','USAGE') then
        raise exception 'maintenance needs pg_read_all_stats' using errcode='42501';
    end if;
    -- Fixed order, all four sources and all associations, including empty histories.
    lock table event_sequencing_protocol, event_sequence_authorities,
        business_event_outbox, command_terminal_events, external_identity_binding_facts, user_created_facts,
        business_event_positions, command_terminal_event_positions, binding_fact_positions,
        user_created_fact_positions, event_sequencing_cutovers in share row exclusive mode;
    select mode into strict state from event_sequencing_protocol where singleton and protocol_version=1;
    select string_agg(finding,'; ') into issue from audit_event_sequencing_role(runtime_name);
    if issue is not null then raise exception 'unsafe runtime profile: %',issue using errcode='42501'; end if;
    -- Even a producer that has not touched the source yet must have drained.
    if exists (with recursive roles(id) as (
            select oid from pg_roles where rolname=runtime_name
            union select m.member from pg_auth_members m join roles c on m.roleid=c.id)
        select 1 from pg_stat_activity a where a.datname=current_database() and a.pid<>pg_backend_pid()
            and a.usesysid in (select id from roles) and a.xact_start is not null) then
        raise exception 'runtime transactions still active; keep producers stopped' using errcode='55000';
    end if;
    -- Refuse disabled/missing deferrable integrity or disabled immutability/FK triggers.
    if (select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid
         where c.relnamespace=schema_name::regnamespace and t.tgname in
         ('business_event_positions_complete','command_terminal_event_positions_complete',
          'binding_fact_positions_complete','user_created_fact_positions_complete')
         and t.tgenabled in ('O','A') and t.tgdeferrable and t.tginitdeferred)<>4
       or exists (select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid
           where c.relnamespace=schema_name::regnamespace and c.relname in
           ('business_event_positions','command_terminal_event_positions','binding_fact_positions',
            'user_created_fact_positions') and t.tgenabled not in ('O','A')) then
        raise exception 'integrity triggers missing or disabled' using errcode='55000';
    end if;
    if (select count(*) from pg_proc p where p.pronamespace=schema_name::regnamespace
         and p.proname in ('reserve_event_positions','check_business_event_positions_complete',
         'check_command_terminal_event_positions_complete','check_binding_fact_positions_complete',
         'check_user_created_fact_positions_complete') and p.prosecdef
         and p.proconfig=array['search_path=pg_catalog, '||quote_ident(schema_name)||', pg_temp'])<>5 then
        raise exception 'untrusted SECURITY DEFINER configuration' using errcode='55000';
    end if;
    if state='ACTIVE' then
        select boundaries into result from event_sequencing_cutovers
            where operation_id=requested_operation and runtime_role=runtime_name;
        if result is null then raise exception 'ACTIVE belongs to another operation; no backfill allowed' using errcode='55000'; end if;
        if (select count(*)<>4 or not bool_and(valid) from inspect_event_sequencing()) then
            raise exception 'ACTIVE integrity failure' using errcode='23514';
        end if;
        return result; -- UNKNOWN acknowledgement reconciliation, never renumber ACTIVE.
    end if;
    if exists(select 1 from event_sequencing_cutovers) then
        raise exception 'cutover journal exists in PREPARED' using errcode='23514';
    end if;
    result := '{}'::jsonb;
    for f in select * from (values
        ('BUSINESS_EVENT','business_event_outbox','id','business_event_positions'),
        ('COMMAND_TERMINAL_EVENT','command_terminal_events','event_id','command_terminal_event_positions'),
        ('BINDING_FACT','external_identity_binding_facts','event_id','binding_fact_positions'),
        ('USER_CREATED_FACT','user_created_facts','event_id','user_created_fact_positions')
        ) v(family,source,key,positions) loop
        execute format('select count(*) from %I',f.source) into n;
        execute format('select count(*) from %I',f.positions) into existing;
        -- Existing associations are accepted only if each exact canonical pair matches,
        -- they form a dense prefix, and E agrees. No conflict-ignore or rewrite.
        execute format('select count(*) from %I p left join
            (select %I event_id,row_number() over(order by %I::text collate "C") position from %I) s
            using(event_id) where s.event_id is null or p.position<>s.position',
            f.positions,f.key,f.key,f.source) into mismatch;
        if mismatch<>0 or not exists(select 1 from event_sequence_authorities e
                where e.family=f.family and e.last_position=existing) then
            raise exception 'historical pairs/authority diverge: %',f.family using errcode='23514';
        end if;
        execute format('select count(*) from %I where position> $1',f.positions) into mismatch using existing;
        if mismatch<>0 then raise exception 'historical prefix not dense: %',f.family using errcode='23514'; end if;
        execute format('insert into %I(event_id,position)
            select event_id,position from
            (select %I event_id,row_number() over(order by %I::text collate "C") position from %I) s
            where not exists(select 1 from %I p where p.event_id=s.event_id) order by position',
            f.positions,f.key,f.key,f.source,f.positions);
        update event_sequence_authorities set last_position=n where family=f.family;
        result := result || jsonb_build_object(f.family,n);
    end loop;
    if (select count(*)<>4 or not bool_and(valid) from inspect_event_sequencing()) then
        raise exception 'incomplete historical inventory' using errcode='23514';
    end if;
    update event_sequencing_protocol set mode='ACTIVE' where singleton and mode='PREPARED' and protocol_version=1;
    if not found then raise exception 'protocol changed' using errcode='55000'; end if;
    insert into event_sequencing_cutovers values(requested_operation,clock_timestamp(),session_user,runtime_name,result);
    return result;
end $$;

-- Pin every invoker routine to the installation schema as well. No caller search_path.
do $$ declare routine text; installed text:=current_schema(); begin
    foreach routine in array array['inspect_event_sequencing()', 'audit_event_sequencing_role(name)',
                                    'activate_event_sequencing(uuid,name)'] loop
        execute format('alter function %s set search_path=pg_catalog,%I,pg_temp',routine,installed);
        execute format('revoke all on function %s from public,pocoma_event_runtime',routine);
    end loop;
end $$;
