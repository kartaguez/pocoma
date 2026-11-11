\set ON_ERROR_STOP on
-- Execute as the READ administrator after migrations, including after a READ rebuild.
-- Variables: read_database, read_schema, query_role, status_role.
BEGIN;
SELECT 1 / CASE WHEN current_database() = :'read_database'
    AND :'query_role' <> :'status_role'
    AND EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = :'read_schema')
    AND 2 = (SELECT count(*) FROM pg_roles
        WHERE rolname IN (:'query_role', :'status_role') AND rolcanlogin
          AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
          AND NOT has_database_privilege(rolname, current_database(), 'CREATE,TEMP')
          AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member = pg_roles.oid))
    THEN 1 ELSE 0 END;
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', :'read_database', :'query_role', :'status_role') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', :'read_schema', :'query_role') \gexec
SELECT format('GRANT SELECT ON TABLE %I.%I TO %I', :'read_schema', rel, :'query_role')
FROM unnest(ARRAY['read_current_binding','read_current_pot','read_pot_version',
    'read_subobject_version','read_pot_balance','read_user_pot',
    'read_command_result','read_identity_request_result','read_current_invitation']) AS tables(rel)
WHERE rel <> 'read_current_invitation' OR to_regclass(format('%I.%I', :'read_schema', rel)) IS NOT NULL \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'status_role') \gexec
SELECT format('GRANT SELECT ON TABLE public.read_maintenance_state TO %I', :'status_role') \gexec
COMMIT;
