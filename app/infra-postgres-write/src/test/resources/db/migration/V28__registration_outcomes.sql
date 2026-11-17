create table registration_outcomes (
    request_id uuid primary key references registration_requests (request_id) on delete restrict,
    outcome_type text not null,
    user_id uuid references users (user_id) on delete restrict,
    binding_id uuid references external_identity_binding_occurrences (binding_id) on delete restrict,
    rejection_code text,
    decided_at timestamptz not null,
    constraint ck_registration_outcomes_shape check (
        (outcome_type = 'REGISTERED' and user_id is not null and binding_id is not null and rejection_code is null)
        or (outcome_type = 'REJECTED' and user_id is null and binding_id is null
            and rejection_code = 'EXTERNAL_IDENTITY_ALREADY_USED')
    )
);
create trigger registration_outcome_immutable before update or delete on registration_outcomes
    for each row execute function reject_registration_request_mutation();

create table user_created_facts (
    event_id uuid primary key,
    request_id uuid not null unique references registration_requests (request_id) on delete restrict,
    user_id uuid not null unique references users (user_id) on delete restrict,
    recorded_at timestamptz not null
);
create trigger user_created_fact_immutable before update or delete on user_created_facts
    for each row execute function reject_registration_request_mutation();
