\set ON_ERROR_STOP on
-- Run with the administrator of an explicitly provisioned WRITE database.
-- psql variables: write_database, write_schema, source_role (an existing dedicated LOGIN).
-- The role must have no CREATE/TEMP, mutation, ownership or privileged role membership.
BEGIN;
SELECT 1 / CASE WHEN current_database() = :'write_database'
    AND EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = :'write_schema')
    AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'source_role' AND rolcanlogin
        AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    AND NOT has_database_privilege(:'source_role', current_database(), 'CREATE,TEMP')
    THEN 1 ELSE 0 END;
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'write_database', :'source_role') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', :'write_schema', :'source_role') \gexec
SELECT format('GRANT SELECT (%s) ON TABLE %I.%I TO %I', cols, :'write_schema', rel, :'source_role')
FROM (VALUES
    ('v3_command','request_id,operation,requester_issuer,requester_subject,pot_id,status'),
    ('v3_identity_request','request_id,operation,requester_issuer,requester_subject,target_issuer,target_subject,status'),
    ('v3_eui_authority','eui_id,issuer,subject'),
    ('v3_eui_version','eui_id,version,user_id,binding_token')
) source(rel,cols) \gexec
SELECT format('GRANT SELECT ON TABLE %I.%I TO %I', :'write_schema', rel, :'source_role')
FROM unnest(ARRAY['v3_command_result','v3_command_completion_event','v3_identity_request_result',
    'v3_identity_completion_event','v3_identity_event','v3_pot_identity','v3_pot_version',
    'v3_pot_header','v3_pot_details','v3_pot_event','v3_shareholder_identity','v3_shareholder_header',
    'v3_shareholder_details','v3_shareholder_weight','v3_expense_identity','v3_expense_header',
    'v3_expense_details','v3_expense_financials','v3_expense_participant','v3_event_counter','v3_event_position']) AS source(rel) \gexec
-- Invitation is an independently selectable participant; do not bootstrap missing migrations here.
SELECT format('GRANT SELECT ON TABLE %I.%I TO %I', :'write_schema', rel, :'source_role')
FROM unnest(ARRAY['v3_invitation','v3_invitation_event']) AS source(rel)
WHERE to_regclass(format('%I.%I', :'write_schema', rel)) IS NOT NULL \gexec
COMMIT;
