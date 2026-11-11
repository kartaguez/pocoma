-- EXPAND: the old writer may still persist a detached tombstone with B.
alter table pocoma_read.current_external_identity_binding
    alter column binding_id drop not null;
