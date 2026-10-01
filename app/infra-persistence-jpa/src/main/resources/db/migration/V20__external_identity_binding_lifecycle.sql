create table external_identity_binding_streams (
    issuer text not null,
    subject text not null,
    current_revision bigint not null,
    primary key (issuer, subject),
    constraint ck_external_identity_binding_streams_issuer check (btrim(issuer) <> ''),
    constraint ck_external_identity_binding_streams_subject check (btrim(subject) <> ''),
    constraint ck_external_identity_binding_streams_revision check (current_revision >= 0)
);

insert into external_identity_binding_streams (issuer, subject, current_revision)
select issuer, subject, 0
from external_identities
on conflict (issuer, subject) do nothing;

create table external_identity_binding_facts (
    event_id uuid primary key,
    issuer text not null,
    subject text not null,
    binding_revision bigint not null,
    fact_type varchar(64) not null,
    user_id uuid,
    binding_id uuid not null,
    recorded_at timestamp with time zone not null,
    partition_hash integer not null,
    constraint uk_external_identity_binding_facts_revision
        unique (issuer, subject, binding_revision),
    constraint fk_external_identity_binding_facts_stream
        foreign key (issuer, subject)
        references external_identity_binding_streams (issuer, subject)
        on delete restrict,
    constraint fk_external_identity_binding_facts_user
        foreign key (user_id) references users (user_id) on delete restrict,
    constraint ck_external_identity_binding_facts_issuer check (btrim(issuer) <> ''),
    constraint ck_external_identity_binding_facts_subject check (btrim(subject) <> ''),
    constraint ck_external_identity_binding_facts_revision check (binding_revision >= 1),
    constraint ck_external_identity_binding_facts_type
        check (fact_type in ('ATTACHED', 'DETACHED')),
    constraint ck_external_identity_binding_facts_shape
        check (
            (fact_type = 'ATTACHED' and user_id is not null)
            or
            (fact_type = 'DETACHED' and user_id is null)
        )
);

create index idx_external_identity_binding_facts_discovery
    on external_identity_binding_facts (partition_hash, issuer, subject, binding_revision);
