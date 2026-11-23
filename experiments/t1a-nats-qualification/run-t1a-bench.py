#!/usr/bin/env python3
"""Pocoma V3 — Experimental Event-Driven Wakeup Benchmark & Failure Harness (Phases 4 & 5).

Supports A (polling 20ms), B (slow polling), C (Valkey Pub/Sub), D (Core NATS),
on topologies T0 (reference), T3 (unified READ), and T4 (sharded 2x READ),
with full broker cgroup accounting, JVM heap/non-heap breakdown, wakeup metrics,
and real Docker failure scenarios.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
PERF_DIR = ROOT / "scripts/performance"
sys.path.insert(0, str(PERF_DIR))

import c4_metrics as c4m
import smoke_test_5_runtimes as oracle
import spec09_bench as bench

_diag_spec = importlib.util.spec_from_file_location("run_l7_c4_diagnostic", PERF_DIR / "run-l7-c4-diagnostic.py")
diag = importlib.util.module_from_spec(_diag_spec)
_diag_spec.loader.exec_module(diag)
base = diag.base

NATS_IMAGE = "nats:2.15.3-alpine"
SNAP_ROOT = Path("/tmp/pocoma-l7c4-snapshots")

T0_TOPOLOGY = {
    "name": "T0",
    "postgresCpus": 2.0,
    "runtimes": {
        "http-write": {"module": "runtime-web-write", "profile": "v3-http-admission", "baseProperties": "http-write", "httpPort": 8080, "managementPort": 18080, "cpus": 0.5, "memLimit": "512m", "extraProps": ""},
        "http-read": {"module": "runtime-web-read", "profile": "v3-http-read", "baseProperties": "http-read", "httpPort": 8082, "managementPort": 18082, "cpus": 0.75, "memLimit": "768m", "extraProps": ""},
        "command-worker": {"module": "runtime-worker-process", "profile": "v3-consumption-worker-dev", "baseProperties": "command-worker", "managementPort": 18083, "cpus": 0.75, "memLimit": "512m", "extraProps": "pocoma.v3.worker.worker-limit=4\npocoma.v3.worker.consumption-limits=Command=3,IdentityRequest=2\npocoma.v3.worker.write-business.minimum-idle=6\npocoma.v3.worker.write-business.maximum-pool-size=6\npocoma.v3.worker.write-control.minimum-idle=10\npocoma.v3.worker.write-control.maximum-pool-size=10"},
        "results-worker": {"module": "runtime-worker-materialize", "profile": "v3-read-worker", "baseProperties": "results-worker", "managementPort": 18084, "cpus": 0.75, "memLimit": "512m", "extraProps": "pocoma.v3.worker.worker-limit=4\npocoma.v3.worker.consumption-limits=MaterializeCommandResult=3,MaterializeIdentityRequestResult=2\npocoma.v3.worker.write-source-read.minimum-idle=6\npocoma.v3.worker.write-source-read.maximum-pool-size=6\npocoma.v3.worker.read-business.minimum-idle=6\npocoma.v3.worker.read-business.maximum-pool-size=6\npocoma.v3.worker.read-control.minimum-idle=10\npocoma.v3.worker.read-control.maximum-pool-size=10"},
        "business-read-worker": {"module": "runtime-worker-materialize", "profile": "v3-read-worker", "baseProperties": "business-read-worker", "managementPort": 18085, "cpus": 1.25, "memLimit": "1024m", "extraProps": "pocoma.v3.worker.worker-limit=4\npocoma.v3.worker.write-source-read.minimum-idle=6\npocoma.v3.worker.write-source-read.maximum-pool-size=6\npocoma.v3.worker.read-business.minimum-idle=6\npocoma.v3.worker.read-business.maximum-pool-size=6\npocoma.v3.worker.read-control.minimum-idle=10\npocoma.v3.worker.read-control.maximum-pool-size=10"},
    },
}

T3_TOPOLOGY = {
    "name": "T3",
    "postgresCpus": 2.0,
    "runtimes": {
        "http-write": T0_TOPOLOGY["runtimes"]["http-write"],
        "http-read": T0_TOPOLOGY["runtimes"]["http-read"],
        "command-worker": {**T0_TOPOLOGY["runtimes"]["command-worker"], "cpus": 0.85, "memLimit": "640m"},
        "unified-read-worker": {
            "module": "runtime-worker-materialize",
            "profile": "v3-read-worker",
            "baseProperties": "business-read-worker",
            "managementPort": 18085,
            "cpus": 1.90,
            "memLimit": "1408m",
            "extraProps": (
                "pocoma.v3.worker.worker-name=unified-read-worker\n"
                "pocoma.v3.worker.worker-limit=6\n"
                "pocoma.v3.worker.read-participants=MaterializeCommandResult,MaterializeIdentityRequestResult,MaterializeCurrentBinding,MaterializePotVersion,MaterializePotBalance,MaterializePotVisibility,MaterializeInvitation\n"
                "pocoma.v3.worker.consumption-limits=MaterializeCommandResult=3,MaterializeIdentityRequestResult=2,MaterializeCurrentBinding=2,MaterializePotVersion=2,MaterializePotBalance=2,MaterializePotVisibility=2,MaterializeInvitation=2\n"
                "pocoma.v3.worker.partitions=MaterializeCommandResult=0|1|2|3;MaterializeIdentityRequestResult=0|1|2|3;MaterializeCurrentBinding=0|1|2|3;MaterializePotVersion=0|1|2|3;MaterializePotBalance=0|1|2|3;MaterializePotVisibility=0|1|2|3;MaterializeInvitation=0|1|2|3\n"
                "pocoma.v3.worker.write-source-read.minimum-idle=8\n"
                "pocoma.v3.worker.write-source-read.maximum-pool-size=8\n"
                "pocoma.v3.worker.read-business.minimum-idle=8\n"
                "pocoma.v3.worker.read-business.maximum-pool-size=8\n"
                "pocoma.v3.worker.read-control.minimum-idle=12\n"
                "pocoma.v3.worker.read-control.maximum-pool-size=12"
            ),
        },
    },
}

T1A_TOPOLOGY = {
    "name": "T1A",
    "postgresCpus": 2.0,
    "runtimes": {
        "http-write": T0_TOPOLOGY["runtimes"]["http-write"],
        "http-read": T0_TOPOLOGY["runtimes"]["http-read"],
        "command-worker": T0_TOPOLOGY["runtimes"]["command-worker"],
        "critical-read-worker": {
            "module": "runtime-worker-materialize",
            "profile": "v3-read-worker",
            "baseProperties": "critical-read-worker",
            "managementPort": 18084,
            "cpus": 0.75,
            "memLimit": "512m",
            "extraProps": "",
        },
        "pot-read-worker": {
            "module": "runtime-worker-materialize",
            "profile": "v3-read-worker",
            "baseProperties": "pot-read-worker",
            "managementPort": 18085,
            "cpus": 1.25,
            "memLimit": "1024m",
            "extraProps": "",
        },
    },
}

TOPOLOGIES = {"T0": T0_TOPOLOGY, "T1A": T1A_TOPOLOGY, "T3": T3_TOPOLOGY}


def read_cgroup_full(container):
    try:
        out = subprocess.check_output(
            [
                "docker", "exec", container, "sh", "-c",
                "cat /sys/fs/cgroup/cpu.stat && echo '---MEM---' && cat /sys/fs/cgroup/memory.current && echo '---PEAK---' && (cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0)",
            ],
            text=True, timeout=5,
        )
    except Exception:
        return None
    cpu_part, _, rest = out.partition("---MEM---")
    mem_part, _, peak_part = rest.partition("---PEAK---")
    stats = {}
    for line in cpu_part.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            stats[parts[0]] = int(parts[1])
    try:
        stats["memory_bytes"] = int(mem_part.strip())
    except ValueError:
        pass
    try:
        p = int(peak_part.strip())
        if p > 0:
            stats["memory_peak_bytes"] = p
    except ValueError:
        pass
    return stats


def capture_cgroups(project, names):
    res = {"ts_ns": time.monotonic_ns()}
    for name in names:
        res[name] = read_cgroup_full(f"{project}-{name}-1")
    return res


def diff_cgroups(before, after, names):
    wall_us = max(1.0, (after["ts_ns"] - before["ts_ns"]) / 1000.0)
    out = {"wall_seconds": round(wall_us / 1e6, 3), "containers": {}}
    for name in names:
        b = before.get(name)
        a = after.get(name)
        if not b or not a:
            continue
        du = a.get("usage_usec", 0) - b.get("usage_usec", 0)
        dt = a.get("throttled_usec", 0) - b.get("throttled_usec", 0)
        entry = {
            "cpu_seconds": round(du / 1e6, 4),
            "avg_cpus": round(du / wall_us, 4),
            "throttled_seconds": round(dt / 1e6, 4),
            "memory_mib": round(a.get("memory_bytes", 0) / (1024 * 1024), 2),
        }
        if "memory_peak_bytes" in a:
            entry["memory_peak_mib"] = round(a["memory_peak_bytes"] / (1024 * 1024), 2)
        out["containers"][name] = entry
    return out


def extract_wakeup_and_jvm(pre_proms, post_proms):
    out = {}
    for name, post_txt in post_proms.items():
        if name not in pre_proms:
            continue
        pre = c4m.parse_prom(pre_proms[name])
        post = c4m.parse_prom(post_proms[name])
        def delta(metric, labels=None):
            return round(c4m.prom_sum(post, metric, labels) - c4m.prom_sum(pre, metric, labels), 4)
        def avg_ms(base_metric, labels=None):
            d_cnt = c4m.prom_sum(post, base_metric + "_count", labels) - c4m.prom_sum(pre, base_metric + "_count", labels)
            d_sum = c4m.prom_sum(post, base_metric + "_sum", labels) - c4m.prom_sum(pre, base_metric + "_sum", labels)
            return round((d_sum / d_cnt) * 1000.0, 2) if d_cnt > 0 else None
        out[name] = {
            "jvm_heap_mib": round(c4m.prom_sum(post, "jvm_memory_used_bytes", {"area": "heap"}) / (1024 * 1024), 2),
            "jvm_nonheap_mib": round(c4m.prom_sum(post, "jvm_memory_used_bytes", {"area": "nonheap"}) / (1024 * 1024), 2),
            "jvm_threads_live": int(round(c4m.prom_sum(post, "jvm_threads_live_threads"))),
            "discovery_passes": int(round(delta("pocoma_v3_consumption_phase_seconds_count", {"phase": "DISCOVERY"}))),
            "wakeup_published": delta("pocoma_v3_wakeup_published_total"),
            "wakeup_coalesced": delta("pocoma_v3_wakeup_coalesced_total"),
            "wakeup_publish_errors": delta("pocoma_v3_wakeup_publish_errors_total"),
            "wakeup_received": delta("pocoma_v3_wakeup_received_total"),
            "wakeup_useful": delta("pocoma_v3_wakeup_useful_total"),
            "wakeup_spurious": delta("pocoma_v3_wakeup_spurious_total"),
            "wakeup_ignored_no_lease": delta("pocoma_v3_wakeup_ignored_no_lease_total"),
            "wakeup_fallback_transitions": delta("pocoma_v3_wakeup_fallback_transitions_total"),
            "wakeup_reconnects": delta("pocoma_v3_wakeup_reconnects_total"),
            "wakeup_state": int(round(c4m.prom_sum(post, "pocoma_v3_wakeup_state"))),
            "wakeup_discovery_delay_avg_ms": avg_ms("pocoma_v3_wakeup_discovery_delay_seconds"),
            "admission_http_avg_ms": avg_ms("pocoma_v3_admission_http_duration_seconds"),
            "hikari_pending": int(round(c4m.prom_sum(post, "hikaricp_connections_pending"))),
            "hikari_timeouts": delta("hikaricp_connections_timeout_total"),
            "hikari_acquire_avg_ms": avg_ms("hikaricp_connections_acquire_seconds"),
            "hikari_acquire_max_ms": round(c4m.prom_sum(post, "hikaricp_connections_acquire_seconds_max") * 1000.0, 2),
            "handoff_admit_to_write_avg_ms": avg_ms("pocoma_v3_handoff_duration_seconds", {"stage": "ADMIT_TO_WRITE"}),
            "handoff_write_exec_avg_ms": avg_ms("pocoma_v3_handoff_duration_seconds", {"stage": "WRITE_EXEC"}),
            "handoff_write_to_read_avg_ms": avg_ms("pocoma_v3_handoff_duration_seconds", {"stage": "WRITE_TO_READ"}),
            "handoff_read_exec_avg_ms": avg_ms("pocoma_v3_handoff_duration_seconds", {"stage": "READ_EXEC"}),
        }
    return out


def build_compose(cfg, topo, work, pg_ip, variant, max_poll_ms, reconcile_ms, fallback_ms, strategy, window_ms, start_broker=True, silent_loss=False):
    definition = {
        "services": {
            "postgres": {
                "image": diag.POSTGRES_IMAGE,
                "labels": {bench.LABEL: "true"},
                "command": [
                    "postgres",
                    "-c", "shared_preload_libraries=pg_stat_statements",
                    "-c", "pg_stat_statements.track=top",
                    "-c", "log_lock_waits=on",
                    "-c", "deadlock_timeout=5ms",
                ],
                "environment": {
                    "POSTGRES_USER": "pocoma",
                    "POSTGRES_PASSWORD": "pocoma-local-only",
                    "POSTGRES_DB": "pocoma",
                },
                "tmpfs": [f"/var/lib/postgresql/data:size={cfg['topology']['postgres']['tmpfsSize']}"],
                "mem_limit": cfg["topology"]["postgres"]["memLimit"],
                "cpus": topo["postgresCpus"],
            }
        }
    }
    broker_name = None
    wakeup_mode = "noop"
    wakeup_url = ""
    if variant == "D-nats":
        broker_name = "nats"
        wakeup_mode = "nats"
        wakeup_url = "nats://nats:4222"
        if start_broker:
            nats_conf = ROOT / "docker/v3-five-runtimes/nats.conf"
            definition["services"]["nats"] = {
                "image": NATS_IMAGE,
                "labels": {bench.LABEL: "true"},
                "command": ["-c", "/etc/nats/nats.conf"],
                "volumes": [f"{nats_conf}:/etc/nats/nats.conf:ro"],
                "mem_limit": "128m",
                "cpus": 0.25,
            }

    for name, rcfg in topo["runtimes"].items():
        mod = rcfg["module"]
        jar = ROOT / "app" / mod / "target" / f"pocoma-{mod}-0.0.1-SNAPSHOT-exec.jar"
        if not jar.is_file():
            raise ValueError(f"Package runtime first: {mod} ({jar} missing)")
        content = (base.PROPERTIES_DIR / f"{rcfg['baseProperties']}.properties").read_text()
        content = content.replace("172.28.0.2/32", pg_ip + "/32")
        content += "\npocoma.v3.http.issuer=http://pocoma-l74a.invalid\n"
        content += "pocoma.v3.http.jwk-set-uri=http://host.docker.internal:28090/jwks.json\n"
        if name in ("http-read", "command-worker"):
            content += (
                "\npocoma.v3.invitation.current-key=local\n"
                "pocoma.v3.invitation.key-ids=local\n"
                f"pocoma.v3.invitation.keys.local={diag.INVITATION_KEY}\n"
            )
        if rcfg.get("extraProps"):
            content += "\n" + rcfg["extraProps"] + "\n"
        if mod in ("runtime-worker-process", "runtime-worker-materialize"):
            content += f"\npocoma.v3.worker.max-poll={max_poll_ms}\n"
        effective_mode = "noop" if (silent_loss and name == "http-write") else wakeup_mode
        if mod in ("runtime-web-write", "runtime-worker-process", "runtime-worker-materialize"):
            content += (
                f"\npocoma.v3.wakeup.mode={effective_mode}\n"
                f"pocoma.v3.wakeup.url={wakeup_url}\n"
                "pocoma.v3.wakeup.auth-token=pocoma-nats-local-only\n"
                f"pocoma.v3.wakeup.reconcile-poll-ms={reconcile_ms}\n"
                f"pocoma.v3.wakeup.fallback-max-poll-ms={fallback_ms}\n"
                f"pocoma.v3.wakeup.consumption-max-poll-ms=500\n"
                f"pocoma.v3.wakeup.coalesce-strategy={strategy}\n"
                f"pocoma.v3.wakeup.coalesce-window-ms={window_ms}\n"
            )
        prop_file = work / f"{name}.properties"
        prop_file.write_text(content)
        mgmt = rcfg["managementPort"]
        ports = [f"127.0.0.1:{mgmt + 10000}:{mgmt}"]
        if "httpPort" in rcfg:
            hp = rcfg["httpPort"]
            ports.append(f"127.0.0.1:{hp + 19920}:{hp}")
        definition["services"][name] = {
            "image": diag.JAVA_IMAGE,
            "labels": {bench.LABEL: "true"},
            "entrypoint": ["java", "-jar", "/app/runtime.jar"],
            "environment": {
                "SPRING_PROFILES_ACTIVE": rcfg["profile"],
                "SPRING_CONFIG_ADDITIONAL_LOCATION": "file:/app/runtime.properties",
                "JAVA_TOOL_OPTIONS": cfg["topology"]["javaOptions"],
            },
            "volumes": [f"{jar}:/app/runtime.jar:ro", f"{prop_file}:/app/runtime.properties:ro"],
            "ports": ports,
            "mem_limit": rcfg["memLimit"],
            "cpus": rcfg["cpus"],
        }
    return definition, broker_name if start_broker else None


def run_single(args):
    cfg = diag.load_cfg()
    topo = TOPOLOGIES[args.topology]
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=f"pocoma-wk-{args.scenario}-"))
    project = f"pocoma-wk-{uuid.uuid4().hex[:8]}"
    compose_path = work / "compose.json"
    compose_args = ["docker", "compose", "-p", project, "-f", str(compose_path)]
    pg = f"{project}-postgres-1"
    issuer = None

    def dc(*argv, timeout=180):
        with (out / "lifecycle.log").open("a") as log:
            subprocess.run(compose_args + list(argv), check=True, stdout=log, stderr=log, timeout=timeout)

    start_broker = args.scenario != "fail-bus-absent"
    silent_loss = args.scenario == "fail-silent-loss"
    receipt = {
        "variant": args.variant,
        "topology": args.topology,
        "scenario": args.scenario,
        "params": {
            "maxPollMs": args.max_poll_ms,
            "reconcilePollMs": args.reconcile_ms,
            "fallbackMaxPollMs": args.fallback_ms,
            "strategy": args.strategy,
            "windowMs": args.window_ms,
        },
        "startedAt": bench.utc(),
        "verdict": "INCONCLUSIVE",
    }

    try:
        # 1. Start postgres
        pg_only = {"services": {"postgres": build_compose(cfg, topo, work, "127.0.0.1", args.variant, args.max_poll_ms, args.reconcile_ms, args.fallback_ms, args.strategy, args.window_ms, False)[0]["services"]["postgres"]}}
        compose_path.write_text(json.dumps(pg_only))
        dc("up", "-d", "postgres")
        base.wait_for(lambda: bench.command(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1", "-U", "pocoma"]) != "")
        pg_ip = next(iter(bench.owned(pg)["NetworkSettings"]["Networks"].values()))["IPAddress"]

        snap_dir = SNAP_ROOT / ("medium50-v5" if args.scenario in ("c2-med-10", "c2-ramp", "c3-int-pot-vs-id", "c3-int-pot-vs-id-deep") else "medium10-v5")
        snap_meta, dump_path = base.verify_snapshot_metadata(snap_dir, "medium", 1)
        base.restore_snapshot_into_pg(pg, dump_path, out)
        # Upgrade V5 snapshot to feat/v3-identity-governance schema (server-assigned identities)
        for db, schema, sql_path in (
            ("pocoma_write", "v3_write", ROOT / "app/infra-postgres-write/src/main/resources/db/write/migration/V2__server_assigned_pot_identities.sql"),
            ("pocoma_read", "v3_read", ROOT / "app/infra-postgres-read/src/main/resources/db/read/migration/V4.1__server_assigned_pot_identities.sql"),
        ):
            sql_text = f"SET search_path TO {schema}, public;\n" + sql_path.read_text()
            subprocess.run(
                ["docker", "exec", "-i", pg, "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", "pocoma", "-d", db],
                input=sql_text, text=True, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
        diag.init_pg_stat_statements(pg)
        main_subject = snap_meta["tokenSubject"]

        fresh_subjects = [f"wk-fresh-{uuid.uuid4().hex[:6]}-{i}" for i in range(100)]
        (work / "subjects.json").write_text(json.dumps([main_subject, *fresh_subjects]))
        issuer_log = (out / "issuer.log").open("w")
        issuer = subprocess.Popen(
            [
                "node", str(PERF_DIR / "spec09-test-issuer.mjs"),
                "--host", "0.0.0.0", "--port", "28090",
                "--issuer", "http://pocoma-l74a.invalid", "--audience", "pocoma-api",
                "--subjects", str(work / "subjects.json"), "--token-file", str(work / "tokens.json"),
                "--ttl-seconds", "3600",
            ],
            stdout=issuer_log, stderr=issuer_log,
        )
        base.wait_for(lambda: (work / "tokens.json").is_file(), 10)
        tokens_map = json.loads((work / "tokens.json").read_text())
        token = tokens_map[main_subject]
        fresh_tokens = [tokens_map[s] for s in fresh_subjects]

        definition, broker_name = build_compose(
            cfg, topo, work, pg_ip, args.variant,
            args.max_poll_ms, args.reconcile_ms, args.fallback_ms,
            args.strategy, args.window_ms, start_broker=start_broker, silent_loss=silent_loss,
        )
        compose_path.write_text(json.dumps(definition))
        services_to_start = ([broker_name] if broker_name else []) + list(topo["runtimes"].keys())
        dc("up", "-d", "--no-recreate", *services_to_start)

        metrics_urls = {name: f"http://127.0.0.1:{rcfg['managementPort'] + 10000}" for name, rcfg in topo["runtimes"].items()}
        for name, url in metrics_urls.items():
            base.wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 150)

        _, b_resp = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)
        binding = b_resp["bindingToken"]
        os.environ.update(POCOMA_TOKEN=token, POCOMA_BINDING_TOKEN=binding)
        states = diag.load_states_from_meta(snap_meta, len(snap_meta["pots"]), token, binding)

        # Wait briefly for wakeup subscriber initial connection if broker started
        if broker_name:
            time.sleep(1.5)

        c4m.pg_exec(pg, "pocoma", "SELECT pg_stat_statements_reset();")
        cgroup_names = ["postgres", *topo["runtimes"].keys()] + ([broker_name] if broker_name else [])
        pre_proms = {r: c4m.scrape_prom(u) for r, u in metrics_urls.items()}
        pre_cg = capture_cgroups(project, cgroup_names)
        pre_pg = c4m.pg_capture_window(pg)

        sc = args.scenario
        if sc == "c1-idle":
            time.sleep(60.0)
            rows, b_ns, e_ns = diag.run_open_rate(states, 0.33, 10, 0, 2, 20.0, 0.025, 100.0, token, binding)
            receipt["results"] = diag.summarize_rows(rows, b_ns, e_ns)
        elif sc == "c1-med-1":
            rows, b_ns, e_ns = diag.run_open_rate(states, 1.0, 60, 10, 4, 15.0, 0.025, 100.0, token, binding)
            receipt["results"] = diag.summarize_rows(rows, b_ns, e_ns)
        elif sc == "c2-med-10":
            rows, b_ns, e_ns = diag.run_open_rate(states, 10.0, 200, 30, 12, 20.0, 0.025, 100.0, token, binding)
            receipt["results"] = diag.summarize_rows(rows, b_ns, e_ns)
        elif sc == "c2-ramp":
            ramp_res = []
            for rate in (1.0, 5.0, 10.0, 20.0, 25.0, 35.0, 50.0):
                cnt = int(rate * 15)
                rows, b_ns, e_ns = diag.run_open_rate(states, rate, cnt, 0, min(32, max(4, int(rate))), 30.0, 0.025, 150.0, token, binding)
                base.wait_for_drainage(pg, timeout_s=60)
                ramp_res.append({"targetRate": rate, **diag.summarize_rows(rows, b_ns, e_ns)})
            receipt["results"] = {"steps": ramp_res}
        elif sc == "c3-int-pot-vs-id":
            stop_bg = threading.Event()
            with ThreadPoolExecutor(max_workers=2) as pool:
                f_bg = pool.submit(diag.run_open_rate, states, 20.0, 400, 20, 20, 30.0, 0.025, 150.0, token, binding)
                time.sleep(2.0)
                f_id = pool.submit(diag.run_registration_stream, 20, 1.0, fresh_tokens[:20], stop_bg)
                id_rows = f_id.result()
                bg_rows, b_ns, e_ns = f_bg.result()
            obs = [r for r in id_rows if r.get("status") == "OBSERVED"]
            receipt["results"] = {
                "potBackground": diag.summarize_rows(bg_rows, b_ns, e_ns),
                "identityProbe": {
                    "planned": 20, "observed": len(obs),
                    "resultE2eMs": base.stats_summary([r["resultE2eMs"] for r in obs]),
                    "usableBindingE2eMs": base.stats_summary([r["usableBindingE2eMs"] for r in obs]),
                },
            }
        elif sc == "c3-int-pot-vs-id-deep":
            stop_bg = threading.Event()
            with ThreadPoolExecutor(max_workers=2) as pool:
                f_bg = pool.submit(diag.run_open_rate, states, 20.0, 800, 20, 20, 30.0, 0.025, 150.0, token, binding)
                time.sleep(2.0)
                f_id = pool.submit(diag.run_registration_stream, 80, 2.0, fresh_tokens[:80], stop_bg)
                id_rows = f_id.result()
                bg_rows, b_ns, e_ns = f_bg.result()
            obs = [r for r in id_rows if r.get("status") == "OBSERVED"]
            outliers = [
                {
                    "index": idx,
                    "admitMs": round(r.get("admitMs", 0), 1),
                    "resultE2eMs": round(r["resultE2eMs"], 1),
                    "usableBindingE2eMs": round(r["usableBindingE2eMs"], 1),
                }
                for idx, r in enumerate(obs)
                if r["usableBindingE2eMs"] >= 300.0
            ]
            receipt["results"] = {
                "potBackground": diag.summarize_rows(bg_rows, b_ns, e_ns),
                "identityProbe": {
                    "planned": 80, "observed": len(obs),
                    "admitMs": base.stats_summary([r["admitMs"] for r in obs if "admitMs" in r]),
                    "resultE2eMs": base.stats_summary([r["resultE2eMs"] for r in obs]),
                    "usableBindingE2eMs": base.stats_summary([r["usableBindingE2eMs"] for r in obs]),
                    "outliersGe300MsCount": len(outliers),
                    "outliersGe300Ms": outliers,
                    "rawUsableBindingMs": [round(r["usableBindingE2eMs"], 1) for r in obs],
                    "rawResultMs": [round(r["resultE2eMs"], 1) for r in obs],
                },
            }
        elif sc == "c4-backlog-10k":
            worker_rts = [n for n, rc in topo["runtimes"].items() if rc["module"].startswith("runtime-worker-")]
            receipt["results"] = diag.run_backlog_10k(
                pg, dc, states, token, binding, fresh_tokens, out,
                worker_names=worker_rts,
                worker_urls=[metrics_urls[w] for w in worker_rts],
            )
        elif sc == "burst-same-pot":
            # Rapid burst of 40 commands on 2 pots (high coalescing stress)
            rows, b_ns, e_ns = diag.run_open_rate(states[:2], 20.0, 40, 0, 8, 30.0, 0.025, 200.0, token, binding)
            receipt["results"] = diag.summarize_rows(rows, b_ns, e_ns)
        elif sc == "fail-bus-absent":
            rows, b_ns, e_ns = diag.run_open_rate(states, 2.0, 20, 0, 4, 20.0, 0.025, 100.0, token, binding)
            receipt["results"] = diag.summarize_rows(rows, b_ns, e_ns)
        elif sc == "fail-silent-loss":
            rows, b_ns, e_ns = diag.run_open_rate(states, 1.0, 12, 0, 2, 20.0, 0.025, 100.0, token, binding)
            receipt["results"] = diag.summarize_rows(rows, b_ns, e_ns)
        elif sc == "fail-bus-kill-restart":
            def kill_and_restart():
                time.sleep(5.0)
                subprocess.run(["docker", "kill", f"{project}-{broker_name}-1"], check=False)
                time.sleep(8.0)
                dc("start", broker_name)
            t = threading.Thread(target=kill_and_restart, daemon=True)
            t.start()
            rows, b_ns, e_ns = diag.run_open_rate(states, 5.0, 100, 0, 8, 25.0, 0.025, 150.0, token, binding)
            t.join(timeout=30)
            receipt["results"] = diag.summarize_rows(rows, b_ns, e_ns)

        base.wait_for_drainage(pg, timeout_s=60)
        post_pg = c4m.pg_capture_window(pg)
        post_cg = capture_cgroups(project, cgroup_names)
        post_proms = {r: c4m.scrape_prom(u) for r, u in metrics_urls.items()}

        receipt["cgroupDelta"] = diff_cgroups(pre_cg, post_cg, cgroup_names)
        receipt["wakeupAndJvm"] = extract_wakeup_and_jvm(pre_proms, post_proms)
        receipt["pgWalBytes"] = {
            db: int(c4m.pg_sql(pg, db, f"SELECT pg_wal_lsn_diff('{post_pg[db]['lsn']}'::pg_lsn, '{pre_pg[db]['lsn']}'::pg_lsn)::bigint"))
            for db in ("pocoma_write", "pocoma_read")
        }
        receipt["pgTxDelta"] = {
            db: {
                "xact_commit": post_pg[db]["db"]["xact_commit"] - pre_pg[db]["db"]["xact_commit"],
                "xact_rollback": post_pg[db]["db"]["xact_rollback"] - pre_pg[db]["db"]["xact_rollback"],
                "deadlocks": post_pg[db]["db"]["deadlocks"] - pre_pg[db]["db"]["deadlocks"],
            }
            for db in ("pocoma_write", "pocoma_read")
        }
        receipt["availabilityValid"] = base.read_availability_snapshot(pg)["valid"]
        receipt["verdict"] = "PASS" if receipt["availabilityValid"] else "FAIL"
    except Exception as exc:
        receipt["verdict"] = "FAIL"
        receipt["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        if issuer:
            issuer.terminate()
            try:
                issuer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                issuer.kill()
        try:
            dc("down", "--volumes", "--remove-orphans")
            receipt["cleanup"] = "REMOVED_OWN_PROJECT"
        except Exception as err:
            receipt["cleanup"] = f"CLEANUP_ERROR: {err}"
        shutil.rmtree(work, ignore_errors=True)
        receipt["finishedAt"] = bench.utc()
        (out / "receipt.json").write_text(json.dumps(receipt, indent=2, allow_nan=False) + "\n")

    print(json.dumps({"variant": args.variant, "topology": args.topology, "scenario": args.scenario, "verdict": receipt["verdict"]}))
    return 0 if receipt["verdict"] == "PASS" else 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", required=True, choices=["A-20", "B-slow", "D-nats"])
    ap.add_argument("--topology", default="T1A", choices=list(TOPOLOGIES.keys()))
    ap.add_argument("--scenario", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--max-poll-ms", type=int, default=20)
    ap.add_argument("--reconcile-ms", type=int, default=2000)
    ap.add_argument("--fallback-ms", type=int, default=20)
    ap.add_argument("--strategy", default="IN_FLIGHT_DEDUP")
    ap.add_argument("--window-ms", type=int, default=1)
    args = ap.parse_args()
    return run_single(args)


if __name__ == "__main__":
    sys.exit(main())
