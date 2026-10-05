create table registration_results (
    request_id uuid primary key references registration_requests (request_id) on delete restrict,
    owner_issuer text not null,
    owner_subject text not null,
    schema_version integer not null default 1 check (schema_version = 1),
    outcome_type text not null,
    user_id uuid,
    binding_id uuid,
    rejection_code text,
    constraint ck_registration_results_owner check (btrim(owner_issuer) <> '' and btrim(owner_subject) <> ''),
    constraint ck_registration_results_shape check (
        (outcome_type = 'REGISTERED' and user_id is not null and binding_id is not null and rejection_code is null)
        or (outcome_type = 'REJECTED' and user_id is null and binding_id is null
            and rejection_code = 'EXTERNAL_IDENTITY_ALREADY_USED')
    )
);
create trigger registration_result_immutable before update or delete on registration_results
    for each row execute function reject_registration_request_mutation();
