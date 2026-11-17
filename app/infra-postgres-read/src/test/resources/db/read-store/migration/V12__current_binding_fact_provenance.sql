-- MIGRATE: all projected rows must correspond exactly to an immutable binding fact.
-- This migration reads the fact journal only; it never reads the active WRITE authority.
-- The V9 check disallows provenance on R0; drop it inside this transactional migration
-- before filling source_event_id, then contract to NOT NULL after verification.
alter table pocoma_read.current_external_identity_binding drop constraint ck_current_binding_source;
do $$ begin
    if to_regclass('public.external_identity_binding_facts') is null then
        if exists (select 1 from pocoma_read.current_external_identity_binding) then
            raise exception 'CURRENT_BINDING fact journal unavailable for nonempty projection';
        end if;
    else
    if exists (
        select 1 from pocoma_read.current_external_identity_binding p
        left join external_identity_binding_facts f
          on f.issuer = p.issuer and f.subject = p.subject
         and f.binding_revision = p.binding_revision
        where f.event_id is null
           or (p.binding_status = 'ATTACHED' and
               (f.fact_type <> 'ATTACHED' or f.user_id is distinct from p.user_id
                or f.binding_id is distinct from p.binding_id))
           or (p.binding_status = 'DETACHED' and f.fact_type <> 'DETACHED')
           or (p.source_event_id is not null and p.source_event_id <> f.event_id)
    ) then
        raise exception 'CURRENT_BINDING differs from binding fact journal';
    end if;
    update pocoma_read.current_external_identity_binding p
    set source_event_id = f.event_id
    from external_identity_binding_facts f
    where p.issuer = f.issuer and p.subject = f.subject
      and p.binding_revision = f.binding_revision and p.source_event_id is null;
    end if;
end $$;

-- CONTRACT: every row, including a migrated R0 baseline, has real fact provenance.
alter table pocoma_read.current_external_identity_binding
    alter column source_event_id set not null;
