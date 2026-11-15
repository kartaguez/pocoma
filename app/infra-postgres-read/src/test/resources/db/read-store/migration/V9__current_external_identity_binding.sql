create table pocoma_read.current_external_identity_binding (
    issuer varchar(512) not null,
    subject varchar(512) not null,
    binding_revision bigint not null,
    binding_status varchar(16) not null,
    user_id uuid,
    binding_id uuid not null,
    source_event_id uuid,
    projected_at timestamp with time zone not null,
    primary key (issuer, subject),
    constraint ck_current_binding_revision check (binding_revision >= 0),
    constraint ck_current_binding_status check (binding_status in ('ATTACHED', 'DETACHED')),
    constraint ck_current_binding_shape check (
        (binding_status = 'ATTACHED' and user_id is not null)
        or (binding_status = 'DETACHED' and user_id is null)
    ),
    constraint ck_current_binding_source check (
        (binding_revision = 0 and source_event_id is null)
        or (binding_revision > 0 and source_event_id is not null)
    )
);

create unique index uk_current_binding_source_event
    on pocoma_read.current_external_identity_binding (source_event_id)
    where source_event_id is not null;
