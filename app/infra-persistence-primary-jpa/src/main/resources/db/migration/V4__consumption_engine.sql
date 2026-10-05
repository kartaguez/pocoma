create table consumption_slots (
    slot_id uuid not null,
    consumable_type varchar(255) not null,
    consumable_components jsonb not null,
    consumer_type varchar(255) not null,
    consumer_components jsonb not null,
    revision bigint not null default 0,
    last_attempt_number integer not null default 0,
    status varchar(16) not null,
    terminal_outcome varchar(16),
    current_claim_id uuid,
    next_claim_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    done_at timestamp with time zone,
    primary key (slot_id),
    constraint uk_consumption_slots_key unique (
        consumable_type, consumable_components, consumer_type, consumer_components
    ),
    constraint ck_consumption_slots_consumable_components
        check (jsonb_typeof(consumable_components) = 'array' and jsonb_array_length(consumable_components) > 0),
    constraint ck_consumption_slots_consumer_components
        check (jsonb_typeof(consumer_components) = 'array'),
    constraint ck_consumption_slots_revision check (revision >= 0),
    constraint ck_consumption_slots_attempt_number check (last_attempt_number >= 0),
    constraint ck_consumption_slots_next_claim_at check (next_claim_at >= created_at),
    constraint ck_consumption_slots_status check (status in ('PENDING', 'DONE')),
    constraint ck_consumption_slots_terminal_outcome
        check (terminal_outcome is null or terminal_outcome in ('SUCCESS', 'REJECTED', 'FAILED', 'ABANDONED')),
    constraint ck_consumption_slots_lifecycle check (
        (status = 'PENDING' and terminal_outcome is null and done_at is null)
        or
        (status = 'DONE' and terminal_outcome is not null and done_at is not null and current_claim_id is null)
    ),
    constraint ck_consumption_slots_done_at check (done_at is null or done_at >= created_at)
);

create index idx_consumption_slots_status_next_claim_at
    on consumption_slots (status, next_claim_at);

create table consumption_claims (
    claim_id uuid not null,
    slot_id uuid not null,
    attempt_number integer not null,
    claimed_by varchar(255) not null,
    claimed_at timestamp with time zone not null,
    lease_until timestamp with time zone not null,
    ended_at timestamp with time zone,
    invalidated_at timestamp with time zone,
    failure_category varchar(255),
    failure_message text,
    failure_occurred_at timestamp with time zone,
    end_reason varchar(32),
    primary key (claim_id),
    constraint fk_consumption_claims_slot foreign key (slot_id) references consumption_slots (slot_id),
    constraint uk_consumption_claims_slot_attempt unique (slot_id, attempt_number),
    constraint uk_consumption_claims_slot_claim unique (slot_id, claim_id),
    constraint ck_consumption_claims_attempt check (attempt_number >= 1),
    constraint ck_consumption_claims_lease check (lease_until > claimed_at),
    constraint ck_consumption_claims_ended_at check (ended_at is null or ended_at >= claimed_at),
    constraint ck_consumption_claims_invalidated_at
        check (invalidated_at is null or invalidated_at >= claimed_at),
    constraint ck_consumption_claims_end_reason check (
        end_reason is null or end_reason in (
            'SUCCESS', 'REJECTED', 'PROCESSING_FAILURE', 'RELEASED', 'TAKEN_OVER', 'ABANDONED'
        )
    ),
    constraint ck_consumption_claims_closure check (
        (end_reason is null and ended_at is null and invalidated_at is null)
        or
        (end_reason in ('TAKEN_OVER', 'ABANDONED') and invalidated_at is not null and ended_at is null)
        or
        (end_reason in ('SUCCESS', 'REJECTED', 'PROCESSING_FAILURE', 'RELEASED')
            and ended_at is not null and invalidated_at is null)
    ),
    constraint ck_consumption_claims_failure_complete check (
        (failure_category is null and failure_message is null and failure_occurred_at is null)
        or
        (failure_category is not null and failure_message is not null and failure_occurred_at is not null)
    ),
    constraint ck_consumption_claims_failure_reason check (
        (end_reason = 'PROCESSING_FAILURE') = (failure_category is not null)
    )
);

alter table consumption_slots
    add constraint fk_consumption_slots_current_claim
    foreign key (slot_id, current_claim_id)
    references consumption_claims (slot_id, claim_id);

create table consumption_inputs (
    input_id uuid not null,
    slot_id uuid not null,
    subject_type varchar(255) not null,
    subject_id varchar(255) not null,
    subject_version bigint not null,
    primary key (input_id),
    constraint fk_consumption_inputs_slot foreign key (slot_id) references consumption_slots (slot_id),
    constraint uk_consumption_inputs_identity unique (slot_id, subject_type, subject_id, subject_version),
    constraint ck_consumption_inputs_version check (subject_version >= 1)
);

create table consumption_results (
    result_id uuid not null,
    slot_id uuid not null,
    space varchar(255) not null,
    object_type varchar(255) not null,
    object_id varchar(255) not null,
    object_version bigint,
    subject_type varchar(255),
    subject_id varchar(255),
    subject_version bigint,
    created_at timestamp with time zone not null,
    primary key (result_id),
    constraint fk_consumption_results_slot foreign key (slot_id) references consumption_slots (slot_id),
    constraint ck_consumption_results_object_version check (object_version is null or object_version >= 1),
    constraint ck_consumption_results_subject check (
        (subject_type is null and subject_id is null and subject_version is null)
        or
        (subject_type is not null and subject_id is not null and subject_version is not null and subject_version >= 1)
    )
);

create index idx_consumption_results_slot_created_at
    on consumption_results (slot_id, created_at, result_id);
