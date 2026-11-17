-- EXPAND only. A V18/V20 cutover snapshot may be loaded into the evidence table
-- before V22; its source_ref must identify an independently verifiable backup/WAL.
create table external_identity_binding_baseline_evidence (
    issuer text not null,
    subject text not null,
    user_id uuid not null references users(user_id) on delete restrict,
    binding_id uuid not null,
    source_ref text not null check (btrim(source_ref) <> ''),
    primary key (issuer, subject),
    unique (binding_id)
);

create table external_identity_binding_occurrences (
    binding_id uuid primary key,
    issuer text not null,
    subject text not null,
    user_id uuid not null references users(user_id) on delete restrict,
    attached_revision bigint not null check (attached_revision >= 0),
    created_at timestamptz not null,
    unique (binding_id, issuer, subject, user_id),
    unique (issuer, subject, attached_revision),
    foreign key (issuer, subject)
        references external_identity_binding_streams(issuer, subject) on delete restrict
);

alter table external_identity_binding_facts
    add column record_origin varchar(32) not null default 'LIFECYCLE';

alter table external_identity_binding_facts
    drop constraint ck_external_identity_binding_facts_shape;

alter table external_identity_binding_facts
    drop constraint ck_external_identity_binding_facts_revision;

alter table external_identity_binding_facts
    add constraint ck_external_identity_binding_facts_origin
    check ((binding_revision = 0 and fact_type = 'ATTACHED' and record_origin = 'MIGRATION_BASELINE')
        or (binding_revision > 0 and record_origin = 'LIFECYCLE'));
