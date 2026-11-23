#!/usr/bin/env python3
"""Lightweight C4 diagnostic collectors (cgroup CPU/RAM, Prometheus deltas, PostgreSQL stats)."""

import json
import re
import subprocess
import time
import urllib.request


CONSUMPTIONS = (
    "Command",
    "IdentityRequest",
    "PotVersion",
    "PotBalance",
    "PotVisibility",
    "Invitation",
    "CurrentBinding",
    "CommandResult",
    "IdentityRequestResult",
)

RUNTIMES = (
    "http-write",
    "http-read",
    "command-worker",
    "results-worker",
    "business-read-worker",
)


def read_cgroup_stats(container):
    """Read cumulative CPU usage/throttling, current and peak memory from container cgroup v2."""
    try:
        out = subprocess.check_output(
            [
                "docker",
                "exec",
                container,
                "sh",
                "-c",
                "cat /sys/fs/cgroup/cpu.stat && echo '---MEM---' && cat /sys/fs/cgroup/memory.current && echo '---PEAK---' && (cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0)",
            ],
            text=True,
            timeout=5,
        )
    except Exception:
        return None
    cpu_part, _, rest = out.partition("---MEM---")
    mem_part, _, peak_part = rest.partition("---PEAK---")
    stats = {}
    for line in cpu_part.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] in ("usage_usec", "user_usec", "system_usec", "nr_periods", "nr_throttled", "throttled_usec"):
            stats[parts[0]] = int(parts[1])
    try:
        stats["memory_bytes"] = int(mem_part.strip())
    except ValueError:
        pass
    try:
        peak_val = int(peak_part.strip())
        if peak_val > 0:
            stats["memory_peak_bytes"] = peak_val
    except ValueError:
        pass
    return stats


def capture_cluster_cgroup(project, runtimes=None):
    rt_list = tuple(runtimes) if runtimes is not None else RUNTIMES
    res = {"ts_ns": time.monotonic_ns(), "_runtimes": list(rt_list)}
    for name in ("postgres", *rt_list):
        res[name] = read_cgroup_stats(f"{project}-{name}-1")
    return res


def diff_cluster_cgroup(before, after, runtimes=None):
    wall_us = max(1.0, (after["ts_ns"] - before["ts_ns"]) / 1000.0)
    rt_list = tuple(runtimes) if runtimes is not None else tuple(after.get("_runtimes", RUNTIMES))
    out = {"wall_seconds": round(wall_us / 1e6, 3), "containers": {}}
    for name in ("postgres", *rt_list):
        b = before.get(name)
        a = after.get(name)
        if not b or not a:
            continue
        du = a.get("usage_usec", 0) - b.get("usage_usec", 0)
        dt = a.get("throttled_usec", 0) - b.get("throttled_usec", 0)
        d_nr = a.get("nr_throttled", 0) - b.get("nr_throttled", 0)
        entry = {
            "cpu_seconds": round(du / 1e6, 4),
            "avg_cpus": round(du / wall_us, 4),
            "throttled_seconds": round(dt / 1e6, 4),
            "nr_throttled": d_nr,
            "memory_mib": round(a.get("memory_bytes", 0) / (1024 * 1024), 2),
        }
        if "memory_peak_bytes" in a:
            entry["memory_peak_mib"] = round(a["memory_peak_bytes"] / (1024 * 1024), 2)
        out["containers"][name] = entry
    return out


def scrape_prom(url):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url + "/actuator/prometheus", timeout=5) as resp:
        return resp.read().decode("utf-8")


def parse_prom(text):
    rows = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        m = re.match(r'^([a-zA-Z0-9_:]+)(?:\{([^}]*)\})?\s+([0-9eE.+\-NaInf]+)$', line)
        if not m:
            continue
        name, raw_labels, val_s = m.groups()
        labels = {}
        if raw_labels:
            for k, v in re.findall(r'([a-zA-Z0-9_]+)="([^"]*)"', raw_labels):
                labels[k] = v
        try:
            val = float(val_s)
        except ValueError:
            continue
        rows.append((name, labels, val))
    return rows


def prom_sum(rows, metric_name, label_filter=None):
    total = 0.0
    for name, labels, val in rows:
        if name != metric_name:
            continue
        if label_filter and not all(labels.get(k) == v for k, v in label_filter.items()):
            continue
        total += val
    return total


def prom_timer_delta(pre, post, base, flt=None):
    dc = prom_sum(post, f"{base}_count", flt) - prom_sum(pre, f"{base}_count", flt)
    ds = prom_sum(post, f"{base}_sum", flt) - prom_sum(pre, f"{base}_sum", flt)
    return {
        "count": int(round(dc)),
        "totalMs": round(ds * 1000.0, 3),
        "avgMs": round((ds / dc) * 1000.0, 3) if dc > 0 else 0.0,
    }


def extract_runtime_deltas(pre_proms, post_proms, runtimes=None):
    """Extract per-runtime and per-consumption Micrometer deltas."""
    summary = {}
    rt_list = tuple(runtimes) if runtimes is not None else tuple(post_proms.keys())
    for runtime in rt_list:
        if runtime not in pre_proms or runtime not in post_proms:
            continue
        pre = parse_prom(pre_proms[runtime])
        post = parse_prom(post_proms[runtime])
        rt_data = {
            "gc_pauses_count": int(round(prom_sum(post, "jvm_gc_pause_seconds_count") - prom_sum(pre, "jvm_gc_pause_seconds_count"))),
            "gc_pauses_ms": round((prom_sum(post, "jvm_gc_pause_seconds_sum") - prom_sum(pre, "jvm_gc_pause_seconds_sum")) * 1000.0, 3),
            "heap_used_mib": round(prom_sum(post, "jvm_memory_used_bytes", {"area": "heap"}) / (1024 * 1024), 2),
            "nonheap_used_mib": round(prom_sum(post, "jvm_memory_used_bytes", {"area": "nonheap"}) / (1024 * 1024), 2),
            "hikari": {},
        }
        pools = sorted({lbl.get("pool") for n, lbl, _ in post if n.startswith("hikaricp_") and lbl.get("pool")})
        for pool in pools:
            rt_data["hikari"][pool] = {
                "acquires": int(round(prom_sum(post, "hikaricp_connections_acquire_seconds_count", {"pool": pool}) - prom_sum(pre, "hikaricp_connections_acquire_seconds_count", {"pool": pool}))),
                "acquire_ms": round((prom_sum(post, "hikaricp_connections_acquire_seconds_sum", {"pool": pool}) - prom_sum(pre, "hikaricp_connections_acquire_seconds_sum", {"pool": pool})) * 1000.0, 3),
                "usage_ms": round((prom_sum(post, "hikaricp_connections_usage_seconds_sum", {"pool": pool}) - prom_sum(pre, "hikaricp_connections_usage_seconds_sum", {"pool": pool})) * 1000.0, 3),
                "timeouts": int(round(prom_sum(post, "hikaricp_connections_timeout_total", {"pool": pool}) - prom_sum(pre, "hikaricp_connections_timeout_total", {"pool": pool}))),
                "pending_last": int(round(prom_sum(post, "hikaricp_connections_pending", {"pool": pool}))),
            }
        has_cons = any(n.startswith("pocoma_v3_rotation_opportunities_total") for n, _, _ in post)
        if has_cons:
            rt_data["consumptions"] = {}
            for cons in CONSUMPTIONS:
                rot_disc = int(round(prom_sum(post, "pocoma_v3_rotation_opportunities_total", {"consumption": cons, "loop": "discovery"}) - prom_sum(pre, "pocoma_v3_rotation_opportunities_total", {"consumption": cons, "loop": "discovery"})))
                rot_cons = int(round(prom_sum(post, "pocoma_v3_rotation_opportunities_total", {"consumption": cons, "loop": "consumption"}) - prom_sum(pre, "pocoma_v3_rotation_opportunities_total", {"consumption": cons, "loop": "consumption"})))
                att_started = int(round(prom_sum(post, "pocoma_v3_attempt_started_total", {"consumption": cons}) - prom_sum(pre, "pocoma_v3_attempt_started_total", {"consumption": cons})))
                if rot_disc == 0 and rot_cons == 0 and att_started == 0:
                    continue
                rt_data["consumptions"][cons] = {
                    "rotations_discovery": rot_disc,
                    "rotations_consumption": rot_cons,
                    "discovery_admitted": int(round(prom_sum(post, "pocoma_v3_discovery_references_total", {"consumption": cons, "kind": "admitted"}) - prom_sum(pre, "pocoma_v3_discovery_references_total", {"consumption": cons, "kind": "admitted"}))),
                    "discovery_duration": prom_timer_delta(pre, post, "pocoma_v3_discovery_duration_seconds", {"consumption": cons}),
                    "opportunity_wait": prom_timer_delta(pre, post, "pocoma_v3_consumption_opportunity_wait_seconds", {"consumption": cons}),
                    "attempt_started": att_started,
                    "attempt_duration": prom_timer_delta(pre, post, "pocoma_v3_attempt_duration_seconds", {"consumption": cons}),
                    "prepare_duration": prom_timer_delta(pre, post, "pocoma_v3_attempt_phase_duration_seconds", {"consumption": cons, "phase": "prepare"}),
                    "finalize_duration": prom_timer_delta(pre, post, "pocoma_v3_attempt_phase_duration_seconds", {"consumption": cons, "phase": "finalize"}),
                    "sat_worker": int(round(prom_sum(post, "pocoma_v3_saturation_total", {"consumption": cons, "kind": "worker"}) - prom_sum(pre, "pocoma_v3_saturation_total", {"consumption": cons, "kind": "worker"}))),
                    "sat_consumption": int(round(prom_sum(post, "pocoma_v3_saturation_total", {"consumption": cons, "kind": "consumption"}) - prom_sum(pre, "pocoma_v3_saturation_total", {"consumption": cons, "kind": "consumption"}))),
                    "sat_executor": int(round(prom_sum(post, "pocoma_v3_saturation_total", {"consumption": cons, "kind": "executor"}) - prom_sum(pre, "pocoma_v3_saturation_total", {"consumption": cons, "kind": "executor"}))),
                    "sat_softcap": int(round(prom_sum(post, "pocoma_v3_saturation_total", {"consumption": cons, "kind": "softcap"}) - prom_sum(pre, "pocoma_v3_saturation_total", {"consumption": cons, "kind": "softcap"}))),
                }
        summary[runtime] = rt_data
    return summary


def pg_sql(container, database, query):
    out = subprocess.check_output(
        ["docker", "exec", "-i", container, "psql", "-X", "-qAt", "-U", "pocoma", "-d", database, "-v", "ON_ERROR_STOP=1"],
        input="BEGIN READ ONLY; SET LOCAL statement_timeout='5s'; " + query + "; COMMIT;",
        text=True,
        timeout=15,
    ).strip()
    return json.loads(out) if out else {}


def pg_exec(container, database, query):
    subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-X", "-qAt", "-U", "pocoma", "-d", database, "-v", "ON_ERROR_STOP=1", "-c", query],
        check=True,
        capture_output=True,
        text=True,
        timeout=15,
    )


def pg_capture_window(container):
    data = {}
    for db in ("pocoma_write", "pocoma_read"):
        data[db] = pg_sql(
            container,
            db,
            """
            SELECT jsonb_build_object(
              'wal', (SELECT to_jsonb(w) FROM pg_stat_wal w),
              'lsn', pg_current_wal_insert_lsn()::text,
              'connections', (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()),
              'db', (SELECT to_jsonb(d) - 'datid' FROM pg_stat_database d WHERE datname = current_database()),
              'tables', (SELECT coalesce(jsonb_agg(jsonb_build_object(
                  'relname', relname,
                  'seq_scan', seq_scan,
                  'idx_scan', idx_scan,
                  'n_tup_ins', n_tup_ins,
                  'n_tup_upd', n_tup_upd,
                  'n_tup_del', n_tup_del,
                  'n_tup_hot_upd', n_tup_hot_upd,
                  'n_live_tup', n_live_tup,
                  'n_dead_tup', n_dead_tup,
                  'autovacuum_count', autovacuum_count,
                  'total_bytes', pg_total_relation_size(relid)
                )), '[]'::jsonb) FROM pg_stat_user_tables)
            )
            """,
        )
    return data


def pg_top_statements(container, database="pocoma_read", limit=20):
    try:
        return pg_sql(
            container,
            "pocoma",
            f"""
            SELECT coalesce(jsonb_agg(x), '[]'::jsonb) FROM (
              SELECT r.rolname AS role, s.calls, round(s.total_exec_time::numeric, 3) AS total_ms,
                     round(s.mean_exec_time::numeric, 3) AS mean_ms, s.rows,
                     s.wal_bytes, s.wal_records, s.wal_fpi,
                     left(regexp_replace(s.query, '\\s+', ' ', 'g'), 180) AS query
              FROM pg_stat_statements s
              JOIN pg_roles r ON r.oid = s.userid
              JOIN pg_database d ON d.oid = s.dbid
              WHERE d.datname = '{database}' AND r.rolname LIKE 'pocoma%'
              ORDER BY s.total_exec_time DESC
              LIMIT {limit}
            ) x
            """,
        )
    except Exception:
        return []
