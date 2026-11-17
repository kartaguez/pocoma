-- V12 is an applied migration. This forward-only gate completes its cutover proof.
-- No business state is repaired here: every known E must already equal its terminal fact.
do $$
declare
    terminal_count bigint;
    projection_count bigint;
begin
    if to_regclass('public.external_identity_binding_facts') is null then
        if exists (select 1 from pocoma_read.current_external_identity_binding) then
            raise exception 'CURRENT_BINDING differs from terminal binding fact journal';
        end if;
        return;
    end if;

    with terminal as (
        select distinct on (issuer, subject)
               issuer, subject, binding_revision, fact_type, user_id, binding_id, event_id
        from public.external_identity_binding_facts
        order by issuer, subject, binding_revision desc
    )
    select (select count(*) from terminal),
           (select count(*) from pocoma_read.current_external_identity_binding)
    into terminal_count, projection_count;

    if terminal_count <> projection_count then
        raise exception 'CURRENT_BINDING differs from terminal binding fact journal: cardinality';
    end if;

    if exists (
        with terminal as (
            select distinct on (issuer, subject)
                   issuer, subject, binding_revision, fact_type, user_id, binding_id, event_id
            from public.external_identity_binding_facts
            order by issuer, subject, binding_revision desc
        )
        select 1
        from terminal f
        full join pocoma_read.current_external_identity_binding p
          on p.issuer = f.issuer and p.subject = f.subject
        where f.event_id is null or p.issuer is null
           or p.binding_revision is distinct from f.binding_revision
           or p.binding_status is distinct from f.fact_type
           or (f.fact_type = 'ATTACHED' and
               (p.user_id is distinct from f.user_id or p.binding_id is distinct from f.binding_id))
           or (f.fact_type = 'DETACHED' and (p.user_id is not null or p.binding_id is not null))
           or p.source_event_id is distinct from f.event_id
    ) then
        raise exception 'CURRENT_BINDING differs from terminal binding fact journal';
    end if;
end $$;
