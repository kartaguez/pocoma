\set ON_ERROR_STOP on
-- Pocoma V3 — L7-C2-B1.4 (F4) — Read-Only Diagnostic of Unreachable Radix-16 Contents
--
-- Strictly read-only diagnostic (no GC, no DELETE, no UPDATE).
-- Identifies nodes in read_pot_version_node and sets in read_pot_user_set that are not
-- reachable from any published logical version in read_pot_version.
--
-- Usage:
--   psql -X -U pocoma -d pocoma_read -v read_schema=v3_read -f scripts/v3/diagnose-pot-version-unreachable.sql

BEGIN TRANSACTION READ ONLY;

WITH RECURSIVE reachable_nodes(node_hash) AS (
    SELECT shareholders_root
    FROM :read_schema.read_pot_version
    WHERE shareholders_root IS NOT NULL
    UNION
    SELECT expenses_root
    FROM :read_schema.read_pot_version
    WHERE expenses_root IS NOT NULL
    UNION
    SELECT ch.h
    FROM reachable_nodes rn
    JOIN :read_schema.read_pot_version_node n ON n.node_hash = rn.node_hash
    JOIN LATERAL unnest(n.child_hashes) AS ch(h) ON true
    WHERE n.node_kind = 'I'
),
node_stats AS (
    SELECT
        count(*) AS total_node_count,
        count(*) FILTER (WHERE r.node_hash IS NOT NULL) AS reachable_node_count,
        count(*) FILTER (WHERE r.node_hash IS NULL) AS unreachable_node_count,
        coalesce(sum(pg_column_size(n.*)) FILTER (WHERE r.node_hash IS NULL), 0) AS unreachable_node_bytes,
        coalesce(sum(pg_column_size(n.*)), 0) AS total_node_row_bytes,
        pg_relation_size(format('%I.read_pot_version_node', :'read_schema')::regclass) AS node_heap_bytes,
        pg_indexes_size(format('%I.read_pot_version_node', :'read_schema')::regclass) AS node_index_bytes,
        pg_total_relation_size(format('%I.read_pot_version_node', :'read_schema')::regclass)
            - pg_relation_size(format('%I.read_pot_version_node', :'read_schema')::regclass)
            - pg_indexes_size(format('%I.read_pot_version_node', :'read_schema')::regclass) AS node_toast_bytes,
        pg_total_relation_size(format('%I.read_pot_version_node', :'read_schema')::regclass) AS node_total_bytes
    FROM :read_schema.read_pot_version_node n
    LEFT JOIN reachable_nodes r ON r.node_hash = n.node_hash
),
reachable_user_sets(set_hash) AS (
    SELECT authorized_users_hash
    FROM :read_schema.read_pot_version
    WHERE authorized_users_hash IS NOT NULL
    UNION
    SELECT attached_active_users_hash
    FROM :read_schema.read_pot_version
    WHERE attached_active_users_hash IS NOT NULL
),
user_set_stats AS (
    SELECT
        count(*) AS total_user_set_count,
        count(*) FILTER (WHERE r.set_hash IS NOT NULL) AS reachable_user_set_count,
        count(*) FILTER (WHERE r.set_hash IS NULL) AS unreachable_user_set_count,
        coalesce(sum(pg_column_size(u.*)) FILTER (WHERE r.set_hash IS NULL), 0) AS unreachable_user_set_bytes,
        coalesce(sum(pg_column_size(u.*)), 0) AS total_user_set_row_bytes,
        pg_relation_size(format('%I.read_pot_user_set', :'read_schema')::regclass) AS user_set_heap_bytes,
        pg_indexes_size(format('%I.read_pot_user_set', :'read_schema')::regclass) AS user_set_index_bytes,
        pg_total_relation_size(format('%I.read_pot_user_set', :'read_schema')::regclass)
            - pg_relation_size(format('%I.read_pot_user_set', :'read_schema')::regclass)
            - pg_indexes_size(format('%I.read_pot_user_set', :'read_schema')::regclass) AS user_set_toast_bytes,
        pg_total_relation_size(format('%I.read_pot_user_set', :'read_schema')::regclass) AS user_set_total_bytes
    FROM :read_schema.read_pot_user_set u
    LEFT JOIN reachable_user_sets r ON r.set_hash = u.set_hash
)
SELECT jsonb_pretty(jsonb_build_object(
    'publishedVersions', (SELECT count(*) FROM :read_schema.read_pot_version),
    'nodes', to_jsonb(node_stats.*),
    'userSets', to_jsonb(user_set_stats.*)
)) AS f4_unreachable_diagnostic
FROM node_stats, user_set_stats;

COMMIT;
