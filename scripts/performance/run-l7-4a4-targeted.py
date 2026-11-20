#!/usr/bin/env python3
"""Pocoma V3 — L7.4-A4.3 Targeted Qualification Campaigns for PotBalance N-m.

Runs two focused scenarios on the exact L7.4 Large Pot snapshot (100 shareholders,
2,000 expenses, 2 pots) under the same 5-runtime Docker topology:
  1. weight-update-large: 30 UpdateShareholderWeights commands proving 0 expense re-reads
     and low latency on 100/2000 pots.
  2. out-of-order-nm-large: burst of 20 mixed commands queued while business-read-worker
     is paused, then drained concurrently across 4 partitions to exercise out-of-order
     N-K > 1 materialization.
"""

import argparse
from fractions import Fraction
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid

import importlib.util

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

_spec = importlib.util.spec_from_file_location("run_l7_4a_baseline", SCRIPT_DIR / "run-l7-4a-baseline.py")
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)
import smoke_test_5_runtimes as oracle  # noqa: E402
import spec09_bench as bench  # noqa: E402


def parse_prom_value(prom_text, metric_name, labels=None):
    for line in prom_text.splitlines():
        if not line or line.startswith("#"):
            continue
        if labels is None:
            if line.startswith(metric_name + " "):
                return float(line.split()[-1])
        else:
            if line.startswith(metric_name + "{"):
                if all(f'{k}="{v}"' in line for k, v in labels.items()):
                    return float(line.split()[-1])
    return 0.0


def parse_distance_histogram(prom_text):
    buckets = {}
    for line in prom_text.splitlines():
        m = re.match(r'^pocoma_v3_potbalance_reference_distance_bucket\{le="([^"]+)"\}\s+([0-9.]+)$', line)
        if m:
            buckets[m.group(1)] = int(float(m.group(2)))
    return buckets


def query_aggregate_stats(pg):
    has_v4 = bench.sql(pg, "pocoma_read", "SELECT to_jsonb(to_regclass('v3_read.read_pot_balance_aggregate_content') IS NOT NULL)")
    if has_v4:
        return bench.sql(
            pg,
            "pocoma_read",
            "SELECT jsonb_build_object("
            "'rowCount', count(*),"
            "'distinctContents', count(DISTINCT pba.content_hash),"
            "'avgPayloadBytes', round(avg(octet_length(bac.payload))),"
            "'maxPayloadBytes', max(octet_length(bac.payload)),"
            "'avgColumnBytes', round(avg(pg_column_size(bac.payload))),"
            "'maxColumnBytes', max(pg_column_size(bac.payload)),"
            "'avgBuckets', round(avg(bac.bucket_count), 2),"
            "'maxBuckets', max(bac.bucket_count)"
            ") FROM v3_read.read_pot_balance_aggregate pba "
            "JOIN v3_read.read_pot_balance_aggregate_content bac USING (content_hash)",
        )
    return bench.sql(
        pg,
        "pocoma_read",
        "SELECT jsonb_build_object("
        "'rowCount', count(*),"
        "'avgTextBytes', round(avg(octet_length(payload::text))), "
        "'maxTextBytes', max(octet_length(payload::text)),"
        "'avgColumnBytes', round(avg(pg_column_size(payload))),"
        "'maxColumnBytes', max(pg_column_size(payload)),"
        "'avgBuckets', round(avg(bucket_count), 2),"
        "'maxBuckets', max(bucket_count)"
        ") FROM v3_read.read_pot_balance_aggregate",
    )


def submit_and_wait_result(payload, token, binding, timeout_s=20.0):
    idem = str(uuid.uuid4())
    body = {**payload, "bindingToken": binding}
    code, resp = oracle.request(
        "POST", "http://127.0.0.1:28000/api/v3/commands", token, body, key=idem
    )
    if code != 202:
        raise RuntimeError(f"Command admission failed HTTP {code}: {resp}")
    req_id = resp["requestId"]
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        rcode, rbody = oracle.request(
            "GET", f"http://127.0.0.1:28002/api/v3/commands/{req_id}/result", token
        )
        if rcode == 200:
            if rbody.get("outcome") != "COMPLETED":
                raise RuntimeError(f"Command {req_id} not COMPLETED: {rbody}")
            return req_id, rbody["resultingPotVersion"]
        time.sleep(0.025)
    raise TimeoutError(f"Command {req_id} result timeout")


def verify_balance_exact_zero_sum(pot_id, version, shareholders, token, binding, timeout_s=20.0):
    t0 = time.monotonic_ns()
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        code, body = oracle.request(
            "GET",
            f"http://127.0.0.1:28002/api/v3/pots/{pot_id}/versions/{version}/balance",
            token,
            binding=binding,
        )
        if code == 200:
            elapsed_ms = (time.monotonic_ns() - t0) / 1e6
            vals = oracle.balance_values(body, shareholders)
            total = sum(vals.values(), Fraction(0, 1))
            if total != 0:
                raise AssertionError(f"Non-zero balance sum {total} at {pot_id} v{version}")
            return vals, elapsed_ms
        time.sleep(0.025)
    raise TimeoutError(f"Balance timeout for {pot_id} v{version}")


def run_scenario(args):
    cfg = base.load_profiles_config()
    topo = cfg["topology"]
    guardrails = cfg["guardrails"]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)

    snap_meta, snap_dump = base.verify_snapshot_metadata(args.snapshot_dir, "large", 2)
    work = Path(f"/private/tmp/pocoma-l74a4-work-{uuid.uuid4().hex[:8]}")
    work.mkdir(mode=0o700, parents=True)
    project = f"pocoma-l74a-{uuid.uuid4().hex[:8]}"
    pg = f"{project}-postgres-1"
    compose_path = work / "compose.json"
    compose_args = ["docker", "compose", "-p", project, "-f", str(compose_path)]

    def dc(*argv, timeout=120):
        with (out / "lifecycle.log").open("a") as log:
            subprocess.run(compose_args + list(argv), check=True, stdout=log, stderr=log, timeout=timeout)

    issuer = None
    manifest = {
        "scenario": args.scenario,
        "snapshotDir": str(args.snapshot_dir.resolve()),
        "snapshotSha256": snap_meta["snapshotSha256"],
        "startedAt": bench.utc(),
        "verdict": "INCONCLUSIVE",
    }

    try:
        base.preflight_guardrails(work, out, guardrails)
        definition = {
            "services": {
                "postgres": {
                    "image": args.postgres_image,
                    "labels": {bench.LABEL: "true"},
                    "environment": {
                        "POSTGRES_USER": "pocoma",
                        "POSTGRES_PASSWORD": "pocoma-local-only",
                        "POSTGRES_DB": "pocoma",
                    },
                    "tmpfs": [f"/var/lib/postgresql/data:size={topo['postgres']['tmpfsSize']}"],
                    "mem_limit": topo["postgres"]["memLimit"],
                    "cpus": topo["postgres"]["cpus"],
                }
            }
        }
        compose_path.write_text(json.dumps(definition))
        dc("up", "-d", "postgres")
        base.wait_for(lambda: bench.command(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1", "-U", "pocoma"]) != "")
        ip = next(iter(bench.owned(pg)["NetworkSettings"]["Networks"].values()))["IPAddress"]

        print(f"[{args.scenario}] Restoring large snapshot + ensuring V2 migration...")
        restore_s = base.restore_snapshot_into_pg(pg, snap_dump, out)
        manifest["restoreElapsedSeconds"] = restore_s

        (work / "subjects.json").write_text(json.dumps([snap_meta["tokenSubject"]]))
        issuer_log = (out / "issuer.log").open("w")
        issuer = subprocess.Popen(
            [
                "node",
                str(base.ROOT / "scripts/performance/spec09-test-issuer.mjs"),
                "--host", "0.0.0.0", "--port", "28090",
                "--issuer", "http://pocoma-l74a.invalid",
                "--audience", "pocoma-api",
                "--subjects", str(work / "subjects.json"),
                "--token-file", str(work / "tokens.json"),
                "--ttl-seconds", "3600",
            ],
            stdout=issuer_log,
            stderr=issuer_log,
        )
        base.wait_for(lambda: (work / "tokens.json").is_file(), 10)

        base._configure_services(args, cfg, work, ip, definition)
        compose_path.write_text(json.dumps(definition))
        dc("up", "-d", "--no-recreate", *topo["runtimes"].keys())

        metrics_urls = {
            name: f"http://127.0.0.1:{rcfg['managementPort'] + 10000}"
            for name, rcfg in topo["runtimes"].items()
        }
        for url in metrics_urls.values():
            base.wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 90)

        token = next(iter(json.loads((work / "tokens.json").read_text()).values()))
        _, binding, states = base.verify_restored_state(pg, snap_meta, 2, token, out, metrics_urls)
        os.environ.update(POCOMA_TOKEN=token, POCOMA_BINDING_TOKEN=binding)

        # Warmup: 1 CreateExpense per pot to seed A(2003) on both restored pots
        print(f"[{args.scenario}] Warmup: establishing A(2003) on both large pots...")
        for idx, st in enumerate(states):
            payload = {
                "operation": "CreateExpense",
                "potId": st["potId"],
                "observedVersion": st["version"],
                "payload": {
                    "id": str(uuid.uuid4()),
                    "label": f"warmup-{idx}",
                    "date": "2026-11-19",
                    "amount": {"numerator": "10", "denominator": "1"},
                    "payerId": st["shareholders"][0],
                    "participants": [{"id": sid, "weight": None} for sid in st["shareholders"]],
                },
            }
            _, new_v = submit_and_wait_result(payload, token, binding)
            verify_balance_exact_zero_sum(st["potId"], new_v, st["shareholders"], token, binding)
            st["version"] = new_v
        base.wait_for_drainage(pg, timeout_s=30)

        pre_prom = bench.fetch_metrics(metrics_urls["business-read-worker"])
        (out / "metrics-pre-business-read-worker.prom").write_text(pre_prom)
        pre_delta_expenses_sum = parse_prom_value(pre_prom, "pocoma_v3_potbalance_delta_expenses_sum")
        pre_inc_hits = parse_prom_value(
            pre_prom, "pocoma_v3_potbalance_materialize_path_total", {"path": "incremental", "reason": "incremental_hit"}
        )

        sample_req_ids = []
        e2e_bal_ms = []

        if args.scenario == "weight-update-large":
            print(f"[{args.scenario}] Running 30 UpdateShareholderWeights commands @ 1.0 cmd/s...")
            for i in range(30):
                st = states[i % 2]
                targets = [
                    {
                        "id": st["shareholders"][(i * 3 + j) % len(st["shareholders"])],
                        "weight": {"numerator": str(((i + j) % 7) + 2), "denominator": "3"},
                    }
                    for j in range(3)
                ]
                payload = {
                    "operation": "UpdateShareholderWeights",
                    "potId": st["potId"],
                    "observedVersion": st["version"],
                    "payload": {"targets": targets},
                }
                t0 = time.monotonic_ns()
                req_id, new_v = submit_and_wait_result(payload, token, binding)
                bal_body, _ = verify_balance_exact_zero_sum(st["potId"], new_v, st["shareholders"], token, binding)
                e2e_bal_ms.append(round((time.monotonic_ns() - t0) / 1e6, 3))
                sample_req_ids.append(req_id)
                st["version"] = new_v
                time.sleep(0.2)

            base.wait_for_drainage(pg, timeout_s=30)
            final_prom = bench.fetch_metrics(metrics_urls["business-read-worker"])
            (out / "metrics-final-business-read-worker.prom").write_text(final_prom)
            post_delta_expenses_sum = parse_prom_value(final_prom, "pocoma_v3_potbalance_delta_expenses_sum")
            post_inc_hits = parse_prom_value(
                final_prom, "pocoma_v3_potbalance_materialize_path_total", {"path": "incremental", "reason": "incremental_hit"}
            )
            expenses_read_during_weights = post_delta_expenses_sum - pre_delta_expenses_sum
            inc_hits_during_weights = post_inc_hits - pre_inc_hits
            if expenses_read_during_weights != 0.0:
                raise AssertionError(
                    f"Expected 0 changed expenses read during weight updates, got {expenses_read_during_weights}"
                )
            if inc_hits_during_weights != 30.0:
                raise AssertionError(
                    f"Expected 30 incremental hits during weight updates, got {inc_hits_during_weights}"
                )

            handoffs = base.parse_handoff_logs(project, out, sample_req_ids, {})
            agg_stats = query_aggregate_stats(pg)
            manifest.update(
                {
                    "verdict": "PASS",
                    "safety": "PASS",
                    "weightUpdateCount": 30,
                    "incrementalHitsDuringWindow": int(inc_hits_during_weights),
                    "expensesReadDuringWeightUpdates": int(expenses_read_during_weights),
                    "e2eBalanceMs": base.stats_summary(e2e_bal_ms),
                    "projections": handoffs["projections"],
                    "aggregateStorage": agg_stats,
                }
            )

        elif args.scenario == "out-of-order-nm-large":
            print(f"[{args.scenario}] Pausing business-read-worker to queue 20 mixed events (10/pot)...")
            dc("stop", "business-read-worker")
            queued_versions = []
            for i in range(20):
                st = states[i % 2]
                if i % 3 == 0:
                    payload = {
                        "operation": "UpdateShareholderWeights",
                        "potId": st["potId"],
                        "observedVersion": st["version"],
                        "payload": {
                            "targets": [
                                {
                                    "id": st["shareholders"][i % len(st["shareholders"])],
                                    "weight": {"numerator": str((i % 5) + 2), "denominator": "2"},
                                }
                            ]
                        },
                    }
                else:
                    payload = {
                        "operation": "CreateExpense",
                        "potId": st["potId"],
                        "observedVersion": st["version"],
                        "payload": {
                            "id": str(uuid.uuid4()),
                            "label": f"ooo-{i}",
                            "date": "2026-11-19",
                            "amount": {"numerator": str(i + 5), "denominator": "3"},
                            "payerId": st["shareholders"][i % len(st["shareholders"])],
                            "participants": [{"id": sid, "weight": None} for sid in st["shareholders"]],
                        },
                    }
                req_id, new_v = submit_and_wait_result(payload, token, binding)
                sample_req_ids.append(req_id)
                queued_versions.append((st["potId"], new_v, st["shareholders"]))
                st["version"] = new_v

            print(f"[{args.scenario}] Restarting business-read-worker to drain 20 queued events out of order...")
            dc("start", "business-read-worker")
            base.wait_for(lambda: bool(bench.fetch_metrics(metrics_urls["business-read-worker"])), 90)

            for pid, v, shs in queued_versions:
                verify_balance_exact_zero_sum(pid, v, shs, token, binding, timeout_s=75.0)
            base.wait_for_drainage(pg, timeout_s=60)

            final_prom = bench.fetch_metrics(metrics_urls["business-read-worker"])
            (out / "metrics-final-business-read-worker.prom").write_text(final_prom)
            dist_buckets = parse_distance_histogram(final_prom)
            dist_max = parse_prom_value(final_prom, "pocoma_v3_potbalance_reference_distance_max")
            inc_hits = parse_prom_value(
                final_prom, "pocoma_v3_potbalance_materialize_path_total", {"path": "incremental", "reason": "incremental_hit"}
            )
            full_no_ref = parse_prom_value(
                final_prom, "pocoma_v3_potbalance_materialize_path_total", {"path": "full", "reason": "no_reference"}
            )
            handoffs = base.parse_handoff_logs(project, out, sample_req_ids, {})
            agg_stats = query_aggregate_stats(pg)
            manifest.update(
                {
                    "verdict": "PASS" if dist_max > 1.0 else "FAIL_NO_OOO",
                    "safety": "PASS",
                    "queuedEvents": len(queued_versions),
                    "incrementalHits": int(inc_hits),
                    "fullNoReference": int(full_no_ref),
                    "referenceDistanceMax": dist_max,
                    "referenceDistanceBuckets": dist_buckets,
                    "projections": handoffs["projections"],
                    "aggregateStorage": agg_stats,
                }
            )
    finally:
        if issuer:
            issuer.terminate()
            try:
                issuer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                issuer.kill()
        try:
            with (out / "runtime.log").open("w") as log:
                subprocess.run(compose_args + ["logs", "--no-color"], stdout=log, stderr=log, timeout=30)
            dc("down", "--volumes", "--remove-orphans")
            manifest["cleanup"] = "REMOVED_OWN_PROJECT"
        except Exception as err:
            manifest["cleanup"] = f"CLEANUP_ERROR: {err}"
        shutil.rmtree(work, ignore_errors=True)
        manifest["finishedAt"] = bench.utc()
        (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    print(json.dumps(manifest, indent=2))
    return 0 if manifest["verdict"] == "PASS" else 2


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--scenario", choices=("weight-update-large", "out-of-order-nm-large"), required=True)
    p.add_argument("--snapshot-dir", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--java-image", required=True)
    p.add_argument("--postgres-image", required=True)
    return run_scenario(p.parse_args())


if __name__ == "__main__":
    sys.exit(main())
