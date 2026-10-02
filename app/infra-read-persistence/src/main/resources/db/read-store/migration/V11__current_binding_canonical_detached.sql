-- MIGRATE and VERIFY while binding consumption workers are stopped for cutover.
update pocoma_read.current_external_identity_binding
set user_id = null, binding_id = null
where binding_status = 'DETACHED';

do $$ begin
    if exists (select 1 from pocoma_read.current_external_identity_binding
               where (binding_status = 'ATTACHED' and (user_id is null or binding_id is null))
                  or (binding_status = 'DETACHED' and (user_id is not null or binding_id is not null))) then
        raise exception 'CURRENT_BINDING canonical shape verification failed';
    end if;
end $$;

-- CONTRACT: the new writer projects DETACHED with neither U nor B.
alter table pocoma_read.current_external_identity_binding drop constraint ck_current_binding_shape;
alter table pocoma_read.current_external_identity_binding add constraint ck_current_binding_shape
    check ((binding_status = 'ATTACHED' and user_id is not null and binding_id is not null)
        or (binding_status = 'DETACHED' and user_id is null and binding_id is null));
