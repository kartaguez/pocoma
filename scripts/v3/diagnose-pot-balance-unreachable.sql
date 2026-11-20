\set ON_ERROR_STOP on
-- Pocoma V3 — L7-C2-B2.4 (ARB-5 / F4) — Read-Only Diagnostic of Unreachable PotBalance Contents
--
-- Strictly read-only diagnostic (no GC, no DELETE, no UPDATE).
-- Identifies contents in read_pot_balance_content, read_pot_balance_aggregate_content, and
-- read_pot_shareholder_set that are not reachable from any published logical version in
-- read_pot_balance / read_pot_balance_aggregate.
--
-- Usage:
--   psql -X -U pocoma -d pocoma_read -v read_schema=v3_read -f scripts/v3/diagnose-pot-balance-unreachable.sql

BEGIN TRANSACTION READ ONLY;

WITH reachable_balances(content_hash) AS (
    SELECT DISTINCT content_hash
    FROM :read_schema.read_pot_balance
),
balance_stats AS (
    SELECT
        (SELECT count(*) FROM :read_schema.read_pot_balance) AS published_balance_versions,
        count(*) AS total_content_count,
        count(*) FILTER (WHERE r.content_hash IS NOT NULL) AS reachable_content_count,
        count(*) FILTER (WHERE r.content_hash IS NULL) AS unreachable_content_count,
        coalesce(sum(pg_column_size(c.*)) FILTER (WHERE r.content_hash IS NOT NULL), 0) AS reachable_content_row_bytes,
        coalesce(sum(pg_column_size(c.*)) FILTER (WHERE r.content_hash IS NULL), 0) AS unreachable_content_bytes,
        coalesce(sum(pg_column_size(c.*)), 0) AS total_content_row_bytes,
        pg_relation_size(format('%I.read_pot_balance_content', :'read_schema')::regclass) AS content_heap_bytes,
        pg_indexes_size(format('%I.read_pot_balance_content', :'read_schema')::regclass) AS content_index_bytes,
        pg_total_relation_size(format('%I.read_pot_balance_content', :'read_schema')::regclass)
            - pg_relation_size(format('%I.read_pot_balance_content', :'read_schema')::regclass)
            - pg_indexes_size(format('%I.read_pot_balance_content', :'read_schema')::regclass) AS content_toast_bytes,
        pg_total_relation_size(format('%I.read_pot_balance_content', :'read_schema')::regclass) AS content_total_bytes,
        pg_relation_size(format('%I.read_pot_balance', :'read_schema')::regclass) AS version_heap_bytes,
        pg_indexes_size(format('%I.read_pot_balance', :'read_schema')::regclass) AS version_index_bytes,
        pg_total_relation_size(format('%I.read_pot_balance', :'read_schema')::regclass) AS version_total_bytes
    FROM :read_schema.read_pot_balance_content c
    LEFT JOIN reachable_balances r ON r.content_hash = c.content_hash
),
reachable_aggregates(content_hash) AS (
    SELECT DISTINCT content_hash
    FROM :read_schema.read_pot_balance_aggregate
),
aggregate_stats AS (
    SELECT
        (SELECT count(*) FROM :read_schema.read_pot_balance_aggregate) AS published_aggregate_versions,
        count(*) AS total_content_count,
        count(*) FILTER (WHERE r.content_hash IS NOT NULL) AS reachable_content_count,
        count(*) FILTER (WHERE r.content_hash IS NULL) AS unreachable_content_count,
        coalesce(sum(pg_column_size(c.*)) FILTER (WHERE r.content_hash IS NOT NULL), 0) AS reachable_content_row_bytes,
        coalesce(sum(pg_column_size(c.*)) FILTER (WHERE r.content_hash IS NULL), 0) AS unreachable_content_bytes,
        coalesce(sum(pg_column_size(c.*)), 0) AS total_content_row_bytes,
        pg_relation_size(format('%I.read_pot_balance_aggregate_content', :'read_schema')::regclass) AS content_heap_bytes,
        pg_indexes_size(format('%I.read_pot_balance_aggregate_content', :'read_schema')::regclass) AS content_index_bytes,
        pg_total_relation_size(format('%I.read_pot_balance_aggregate_content', :'read_schema')::regclass)
            - pg_relation_size(format('%I.read_pot_balance_aggregate_content', :'read_schema')::regclass)
            - pg_indexes_size(format('%I.read_pot_balance_aggregate_content', :'read_schema')::regclass) AS content_toast_bytes,
        pg_total_relation_size(format('%I.read_pot_balance_aggregate_content', :'read_schema')::regclass) AS content_total_bytes,
        pg_relation_size(format('%I.read_pot_balance_aggregate', :'read_schema')::regclass) AS version_heap_bytes,
        pg_indexes_size(format('%I.read_pot_balance_aggregate', :'read_schema')::regclass) AS version_index_bytes,
        pg_total_relation_size(format('%I.read_pot_balance_aggregate', :'read_schema')::regclass) AS version_total_bytes
    FROM :read_schema.read_pot_balance_aggregate_content c
    LEFT JOIN reachable_aggregates r ON r.content_hash = c.content_hash
),
reachable_shareholder_sets(set_hash) AS (
    SELECT DISTINCT bc.shareholder_set_hash
    FROM reachable_balances rb
    JOIN :read_schema.read_pot_balance_content bc ON bc.content_hash = rb.content_hash
    UNION
    SELECT DISTINCT bac.shareholder_set_hash
    FROM reachable_aggregates ra
    JOIN :read_schema.read_pot_balance_aggregate_content bac ON bac.content_hash = ra.content_hash
),
shareholder_set_stats AS (
    SELECT
        count(*) AS total_set_count,
        count(*) FILTER (WHERE r.set_hash IS NOT NULL) AS reachable_set_count,
        count(*) FILTER (WHERE r.set_hash IS NULL) AS unreachable_set_count,
        coalesce(sum(pg_column_size(s.*)) FILTER (WHERE r.set_hash IS NOT NULL), 0) AS reachable_set_row_bytes,
        coalesce(sum(pg_column_size(s.*)) FILTER (WHERE r.set_hash IS NULL), 0) AS unreachable_set_bytes,
        coalesce(sum(pg_column_size(s.*)), 0) AS total_set_row_bytes,
        pg_relation_size(format('%I.read_pot_shareholder_set', :'read_schema')::regclass) AS set_heap_bytes,
        pg_indexes_size(format('%I.read_pot_shareholder_set', :'read_schema')::regclass) AS set_index_bytes,
        pg_total_relation_size(format('%I.read_pot_shareholder_set', :'read_schema')::regclass)
            - pg_relation_size(format('%I.read_pot_shareholder_set', :'read_schema')::regclass)
            - pg_indexes_size(format('%I.read_pot_shareholder_set', :'read_schema')::regclass) AS set_toast_bytes,
        pg_total_relation_size(format('%I.read_pot_shareholder_set', :'read_schema')::regclass) AS set_total_bytes
    FROM :read_schema.read_pot_shareholder_set s
    LEFT JOIN reachable_shareholder_sets r ON r.set_hash = s.set_hash
)
SELECT jsonb_pretty(jsonb_build_object(
    'publishedVersions', (SELECT count(*) FROM :read_schema.read_pot_version WHERE NOT deleted),
    'balanceContents', to_jsonb(balance_stats.*),
    'aggregateContents', to_jsonb(aggregate_stats.*),
    'shareholderSets', to_jsonb(shareholder_set_stats.*)
)) AS f4_pot_balance_unreachable_diagnostic
FROM balance_stats, aggregate_stats, shareholder_set_stats;

COMMIT;
