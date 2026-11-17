-- This migration is intentionally fail closed. If V20's initial occurrence has
-- already been detached, its E/U/B must first be loaded from a verified cutover
-- snapshot into external_identity_binding_baseline_evidence (Flyway target V21).
do $$
declare
    missing_count bigint;
    missing_keys text;
begin
    select count(*), string_agg(issuer || '/' || subject, ', ' order by issuer, subject)
      into missing_count, missing_keys
      from external_identity_binding_streams s
     where not exists (select 1 from external_identity_binding_facts f
                       where f.issuer = s.issuer and f.subject = s.subject
                         and f.binding_revision = 0)
       and exists (select 1 from external_identity_binding_facts f
                   where f.issuer = s.issuer and f.subject = s.subject
                     and f.binding_revision = 1 and f.fact_type = 'DETACHED')
       and not exists (select 1 from external_identity_binding_baseline_evidence e
                       where e.issuer = s.issuer and e.subject = s.subject);
    if missing_count > 0 then
        raise exception 'MIGRATION BLOCKED: % detached R0 occurrences lack verified E/U/B evidence: %',
            missing_count, missing_keys;
    end if;
end $$;

-- R0 is proven directly by an unchanged active row or by external evidence.
insert into external_identity_binding_facts
    (event_id, issuer, subject, binding_revision, fact_type, user_id, binding_id,
     recorded_at, partition_hash, record_origin)
select md5('pocoma-binding-baseline:' || a.issuer || ':' || a.subject)::uuid,
       a.issuer, a.subject, 0, 'ATTACHED', a.user_id, a.binding_id,
       now(), hashtext(jsonb_build_array(a.issuer, a.subject)::text), 'MIGRATION_BASELINE'
  from external_identities a
  join external_identity_binding_streams s using (issuer, subject)
 where s.current_revision = 0
   and not exists (select 1 from external_identity_binding_facts f
                   where f.issuer = a.issuer and f.subject = a.subject);

insert into external_identity_binding_facts
    (event_id, issuer, subject, binding_revision, fact_type, user_id, binding_id,
     recorded_at, partition_hash, record_origin)
select md5('pocoma-binding-baseline:' || e.issuer || ':' || e.subject)::uuid,
       e.issuer, e.subject, 0, 'ATTACHED', e.user_id, e.binding_id,
       now(), hashtext(jsonb_build_array(e.issuer, e.subject)::text), 'MIGRATION_BASELINE'
  from external_identity_binding_baseline_evidence e
  join external_identity_binding_streams s using (issuer, subject)
 where exists (select 1 from external_identity_binding_facts f
               where f.issuer = e.issuer and f.subject = e.subject
                 and f.binding_revision = 1 and f.fact_type = 'DETACHED')
   and not exists (select 1 from external_identity_binding_facts f
                   where f.issuer = e.issuer and f.subject = e.subject
                     and f.binding_revision = 0);

do $$
declare
    bad_count bigint;
begin
    select count(*) into bad_count
      from external_identity_binding_facts d
      left join external_identity_binding_facts a
        on a.issuer = d.issuer and a.subject = d.subject
       and a.binding_id = d.binding_id and a.fact_type = 'ATTACHED'
       and a.binding_revision < d.binding_revision
     where d.fact_type = 'DETACHED'
       and (a.event_id is null or (d.user_id is not null and d.user_id <> a.user_id));
    if bad_count > 0 then
        raise exception 'MIGRATION BLOCKED: % DETACHED facts have no exact or consistent prior ATTACHED(E,U,B)', bad_count;
    end if;

    select count(*) into bad_count
      from (select binding_id from external_identity_binding_facts
             where fact_type = 'ATTACHED' group by binding_id having count(*) <> 1) duplicates;
    if bad_count > 0 then
        raise exception 'MIGRATION BLOCKED: % BindingIds were attached more than once', bad_count;
    end if;

    with ordered as (
        select issuer, subject, binding_revision, fact_type, user_id, binding_id,
               lag(fact_type) over w as previous_type,
               lag(user_id) over w as previous_user,
               lag(binding_id) over w as previous_binding
          from external_identity_binding_facts
        window w as (partition by issuer, subject order by binding_revision)
    )
    select count(*) into bad_count from ordered
     where (binding_revision = 0 and fact_type <> 'ATTACHED')
        or (binding_revision = 1 and previous_type is null and fact_type <> 'ATTACHED')
        or (previous_type = 'ATTACHED' and
            (fact_type <> 'DETACHED' or binding_id <> previous_binding
             or (user_id is not null and user_id <> previous_user)))
        or (previous_type = 'DETACHED' and fact_type <> 'ATTACHED');
    if bad_count > 0 then
        raise exception 'MIGRATION BLOCKED: % Binding facts violate lifecycle order or exact occurrence', bad_count;
    end if;
end $$;

-- The sole permitted repair of pre-V22 facts.
update external_identity_binding_facts d
   set user_id = a.user_id
  from external_identity_binding_facts a
 where d.fact_type = 'DETACHED' and d.user_id is null
   and a.fact_type = 'ATTACHED'
   and a.issuer = d.issuer and a.subject = d.subject
   and a.binding_id = d.binding_id and a.binding_revision < d.binding_revision;

insert into external_identity_binding_occurrences
    (binding_id, issuer, subject, user_id, attached_revision, created_at)
select binding_id, issuer, subject, user_id, binding_revision, recorded_at
  from external_identity_binding_facts where fact_type = 'ATTACHED';

do $$
declare
    bad_count bigint;
begin
    -- An empty R0 stream might be an old stale-detach artifact, but could also
    -- hide a mutation made by an older writer. Do not erase it by inference.
    select count(*) into bad_count from external_identity_binding_streams s
     where s.current_revision = 0
       and not exists (select 1 from external_identities a
                       where a.issuer = s.issuer and a.subject = s.subject)
       and not exists (select 1 from external_identity_binding_facts f
                       where f.issuer = s.issuer and f.subject = s.subject);
    if bad_count > 0 then
        raise exception 'MIGRATION BLOCKED: % empty R0 streams require provenance review', bad_count;
    end if;

    select count(*) into bad_count from external_identity_binding_streams s
     where s.current_revision is distinct from (select max(f.binding_revision)
                                   from external_identity_binding_facts f
                                   where f.issuer = s.issuer and f.subject = s.subject)
        or (select min(f.binding_revision) from external_identity_binding_facts f
             where f.issuer = s.issuer and f.subject = s.subject) not in (0, 1)
        or exists (
            select 1 from (
                select f.binding_revision,
                       lag(f.binding_revision) over (order by f.binding_revision) as prior_revision
                  from external_identity_binding_facts f
                 where f.issuer = s.issuer and f.subject = s.subject
            ) ordered
            where ordered.prior_revision is not null
              and ordered.binding_revision <> ordered.prior_revision + 1
        );
    if bad_count > 0 then
        raise exception 'MIGRATION BLOCKED: % Binding streams have missing facts or revision mismatch', bad_count;
    end if;

    select count(*) into bad_count from external_identity_binding_streams s
      left join external_identity_binding_facts last_fact
        on last_fact.issuer = s.issuer and last_fact.subject = s.subject
       and last_fact.binding_revision = s.current_revision
      left join external_identities a on a.issuer = s.issuer and a.subject = s.subject
     where (last_fact.fact_type = 'ATTACHED'
            and (a.binding_id is distinct from last_fact.binding_id
              or a.user_id is distinct from last_fact.user_id))
        or (last_fact.fact_type = 'DETACHED' and a.binding_id is not null);
    if bad_count > 0 then
        raise exception 'MIGRATION BLOCKED: % replayed Binding states disagree with authority', bad_count;
    end if;
end $$;

alter table external_identity_binding_facts alter column user_id set not null;
alter table external_identity_binding_facts
    add constraint fk_external_identity_binding_facts_occurrence
    foreign key (binding_id, issuer, subject, user_id)
    references external_identity_binding_occurrences(binding_id, issuer, subject, user_id)
    on delete restrict;
alter table external_identities
    add constraint fk_external_identities_binding_occurrence
    foreign key (binding_id, issuer, subject, user_id)
    references external_identity_binding_occurrences(binding_id, issuer, subject, user_id)
    on delete restrict;
