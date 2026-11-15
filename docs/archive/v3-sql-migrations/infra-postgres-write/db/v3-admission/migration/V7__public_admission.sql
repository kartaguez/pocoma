-- CURRENT additive I2. Apply after WRITE V6, never in READ. No Link grant objects.
create table v3_admission_association (
    request_id uuid primary key,
    category text not null check(category in ('COMMAND','IDENTITY')),
    operation text not null,
    canonical_version integer not null check(canonical_version>0),
    lookup_key_id text not null check(length(lookup_key_id)>0),
    comparison_key_id text not null check(length(comparison_key_id)>0),
    scope_key_fingerprint bytea not null unique check(octet_length(scope_key_fingerprint)=32),
    intent_fingerprint bytea not null check(octet_length(intent_fingerprint)=32),
    created_at timestamptz not null,
    command_id uuid unique references v3_command(request_id) deferrable initially deferred,
    identity_request_id uuid unique references v3_identity_request(request_id) deferrable initially deferred,
    check ((category='COMMAND' and command_id is not null and command_id=request_id and identity_request_id is null)
        or (category='IDENTITY' and identity_request_id is not null and identity_request_id=request_id and command_id is null))
);
-- Immutability is also enforced for trusted administrative mistakes; no reverse integrity trigger.
create trigger v3_admission_immutable before update or delete on v3_admission_association
    for each row execute function v3_immutable_execution_row();
alter table v3_identity_request drop constraint v3_identity_admission_shape;
alter table v3_identity_request add constraint v3_identity_admission_shape check((jsonb_typeof(admission_context)='object'
        and admission_context ?& array['validator','contractVersion','initiator','target',
            'intentionFingerprint','grantOrCorrelationReference','validatedAt','attestations','origin']
        and jsonb_typeof(admission_context->'attestations')='object'
        and admission_context->>'origin' in ('CONTROLLED_INTERNAL','EXTERNAL_AUTHORIZATION_SERVER','AUTHENTICATED_HTTP')
        and jsonb_typeof(admission_context->'validator')='string' and length(admission_context->>'validator')>0
        and jsonb_typeof(admission_context->'contractVersion')='string' and length(admission_context->>'contractVersion')>0
        and jsonb_typeof(admission_context->'intentionFingerprint')='string' and length(admission_context->>'intentionFingerprint')>0
        and jsonb_typeof(admission_context->'grantOrCorrelationReference')='string' and length(admission_context->>'grantOrCorrelationReference')>0
        and jsonb_typeof(admission_context->'validatedAt')='string' and length(admission_context->>'validatedAt')>0
        and admission_context->'initiator'=jsonb_build_object('issuer',requester_issuer,'subject',requester_subject)
        and admission_context->'target'=jsonb_build_object('issuer',target_issuer,'subject',target_subject)) is true);
