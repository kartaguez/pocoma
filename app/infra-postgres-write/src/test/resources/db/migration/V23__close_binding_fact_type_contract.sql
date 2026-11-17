alter table external_identity_binding_facts
    drop constraint ck_external_identity_binding_facts_origin,
    drop constraint ck_external_identity_binding_facts_type,
    add constraint ck_external_identity_binding_facts_origin
        check (
            (binding_revision = 0
                and fact_type = 'ATTACHED'
                and record_origin = 'MIGRATION_BASELINE')
            or
            (binding_revision > 0
                and fact_type in ('ATTACHED', 'DETACHED')
                and record_origin = 'LIFECYCLE')
        );
