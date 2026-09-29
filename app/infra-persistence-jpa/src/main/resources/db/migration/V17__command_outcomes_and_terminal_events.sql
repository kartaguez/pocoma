create table command_outcomes (
    command_id uuid not null,
    outcome_type varchar(16) not null,
    pot_id uuid,
    resulting_version bigint,
    public_code varchar(255),
    resolved_at timestamp with time zone not null,
    primary key (command_id),
    constraint fk_command_outcomes_recorded_command
        foreign key (command_id) references recorded_commands(command_id),
    constraint ck_command_outcomes_type
        check (outcome_type in ('APPLIED', 'REJECTED', 'FAILED')),
    constraint ck_command_outcomes_shape check (
        (outcome_type = 'APPLIED' and pot_id is not null and resulting_version >= 1 and public_code is null)
        or
        (outcome_type in ('REJECTED', 'FAILED') and pot_id is null and resulting_version is null
            and public_code is not null and length(trim(public_code)) > 0)
    ),
    constraint ck_command_outcomes_failed_public_code check (
        outcome_type <> 'FAILED' or public_code = 'COMMAND_PROCESSING_FAILED'
    )
);

create table command_terminal_events (
    event_id uuid not null,
    event_type varchar(32) not null,
    command_id uuid not null,
    command_partition_hash integer not null,
    trace_id varchar(255),
    recorded_at timestamp with time zone not null,
    primary key (event_id),
    constraint uk_command_terminal_events_command unique (command_id),
    constraint fk_command_terminal_events_outcome
        foreign key (command_id) references command_outcomes(command_id),
    constraint ck_command_terminal_events_type
        check (event_type in ('COMMAND_APPLIED', 'COMMAND_REJECTED', 'COMMAND_FAILED'))
);

create index idx_command_terminal_events_discovery
    on command_terminal_events (command_partition_hash, recorded_at, event_id);
