-- CURRENT V3 additive. Technical proposals only; no business state/slot/event before consent.
-- UUID FKs avoid indexing the raw occurrence token or long provider identities.
-- The reverse deferred check compares the exact intention without a second proposal-side trigger.
alter table v3_identity_request add column link_proposal_id uuid;
alter table v3_identity_request add constraint v3_link_request_proposal unique(request_id,link_proposal_id);
alter table v3_identity_request add constraint v3_http_link_proposal_shape check (
    case when operation='LINK' and admission_context->>'origin'='AUTHENTICATED_HTTP'
         then link_proposal_id is not null and link_proposal_id=request_id
         else link_proposal_id is null end);
alter table v3_admission_association add constraint v3_link_association_intention unique
    (request_id,category,operation,intent_fingerprint);
create table v3_link_proposal (
    link_request_id uuid primary key,
    initiator_issuer text not null check(length(initiator_issuer)>0),
    initiator_subject text not null check(length(initiator_subject)>0),
    target_issuer text not null check(length(target_issuer)>0),
    target_subject text not null check(length(target_subject)>0),
    binding_token text not null check(length(binding_token)>0),
    authenticated_at timestamptz not null,
    issued_at timestamptz not null,
    principal_expires_at timestamptz not null,
    created_at timestamptz not null,
    expires_at timestamptz not null check(expires_at>created_at),
    canonical_version integer not null check(canonical_version=1),
    lookup_key_id text not null check(length(lookup_key_id)>0),
    comparison_key_id text not null check(length(comparison_key_id)>0),
    scope_key_fingerprint bytea not null unique check(octet_length(scope_key_fingerprint)=32),
    intent_fingerprint bytea not null check(octet_length(intent_fingerprint)=32),
    accepted_at timestamptz,
    identity_request_id uuid unique,
    admission_category text generated always as ('IDENTITY'::text) stored,
    admission_operation text generated always as ('Link'::text) stored,
    check((initiator_issuer,initiator_subject)<>(target_issuer,target_subject)),
    check((accepted_at is null and identity_request_id is null) or
          (accepted_at is not null and identity_request_id is not null and identity_request_id=link_request_id
           and accepted_at>=created_at and accepted_at<expires_at)),
    foreign key(identity_request_id,link_request_id)
        references v3_identity_request(request_id,link_proposal_id)
        deferrable initially deferred,
    foreign key(identity_request_id,admission_category,admission_operation,intent_fingerprint)
        references v3_admission_association(request_id,category,operation,intent_fingerprint)
        deferrable initially deferred
);
alter table v3_identity_request add constraint v3_link_proposal_reference foreign key(link_proposal_id)
    references v3_link_proposal(link_request_id) deferrable initially deferred;
revoke all on v3_link_proposal from public;

-- A CHECK cannot compare OLD/NEW. No deletion, immutable intention, single acceptance.
create function v3_link_proposal_guard() returns trigger language plpgsql as $$
begin
    if tg_op='INSERT' then
        if new.accepted_at is not null or new.identity_request_id is not null then
            raise exception 'Proposal must start unaccepted' using errcode='23514';
        end if;
    elsif tg_op='DELETE' then
        raise exception 'Proposal deletion forbidden' using errcode='23514';
    elsif (to_jsonb(new)-array['accepted_at','identity_request_id','admission_category','admission_operation']) is distinct from
          (to_jsonb(old)-array['accepted_at','identity_request_id','admission_category','admission_operation'])
          or old.accepted_at is not null or new.accepted_at is null or new.identity_request_id is null
          or clock_timestamp()>=new.expires_at then
        raise exception 'Immutable or expired proposal' using errcode='23514';
    end if;
    return new;
end $$;
create trigger v3_link_proposal_immutable before insert or update or delete on v3_link_proposal
    for each row execute function v3_link_proposal_guard();

-- Reverse integrity cannot be expressed by the nullable pre-admission FKs alone.
-- INSERT only: L3's status update does not read technical proposals or change its transitions.
create function v3_http_link_requires_proposal() returns trigger language plpgsql as $$
declare p v3_link_proposal%rowtype;
begin
    if new.operation<>'LINK' or new.admission_context->>'origin'<>'AUTHENTICATED_HTTP' then return new; end if;
    select * into p from v3_link_proposal where identity_request_id=new.request_id;
    if p.link_request_id is null
       or new.requester_issuer is distinct from p.initiator_issuer
       or new.requester_subject is distinct from p.initiator_subject
       or new.binding_token is distinct from p.binding_token
       or new.target_issuer is distinct from p.target_issuer
       or new.target_subject is distinct from p.target_subject
       or new.admission_context->>'validator'<>'pocoma-v3-link-post'
       or new.admission_context->>'contractVersion'<>'1'
       or new.admission_context->>'grantOrCorrelationReference'<>p.link_request_id::text
       or new.admission_context->>'intentionFingerprint'<>encode(p.intent_fingerprint,'hex')
       or (new.admission_context->>'validatedAt')::timestamptz is distinct from p.accepted_at
       or new.created_at is distinct from p.accepted_at
       or new.admission_context->'attestations'->>'acceptedIssuer' is distinct from p.target_issuer
       or new.admission_context->'attestations'->>'acceptedSubject' is distinct from p.target_subject
       or (new.admission_context->'attestations'->>'expiresAt')::timestamptz is null
       or (new.admission_context->'attestations'->>'expiresAt')::timestamptz<=p.accepted_at
       or (new.admission_context->'attestations'->>'authenticatedAt')::timestamptz is null
       or (new.admission_context->'attestations'->>'issuedAt')::timestamptz is null then
        raise exception 'HTTP Link requires exact accepted proposal' using errcode='23514';
    end if;
    return new;
end $$;
create constraint trigger v3_http_link_proposal after insert on v3_identity_request
    deferrable initially deferred for each row execute function v3_http_link_requires_proposal();
