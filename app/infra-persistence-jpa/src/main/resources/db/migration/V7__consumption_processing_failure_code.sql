alter table consumption_claims
    add column failure_code text;

update consumption_claims
set failure_code = failure_category
where failure_category is not null;

alter table consumption_claims
    drop constraint ck_consumption_claims_failure_complete,
    add constraint ck_consumption_claims_failure_complete check (
        (failure_code is null and failure_category is null and failure_message is null
            and failure_occurred_at is null)
        or
        (failure_code is not null and btrim(failure_code) <> ''
            and failure_category is not null and btrim(failure_category) <> ''
            and failure_message is not null and failure_occurred_at is not null)
    );
