create table registration_requests (
    request_id uuid primary key,
    issuer text not null,
    subject text not null,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null,
    constraint ck_registration_requests_identity check (length(issuer) > 0 and length(subject) > 0),
    constraint ck_registration_requests_payload check (payload = '{}'::jsonb)
);

create index idx_registration_requests_discovery on registration_requests (created_at, request_id);

create function reject_registration_request_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'RegistrationRequest is immutable';
end $$;
create trigger registration_request_immutable before update or delete on registration_requests
    for each row execute function reject_registration_request_mutation();
