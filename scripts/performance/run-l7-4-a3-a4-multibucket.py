#!/usr/bin/env python3
"""Pocoma V3 — L7.4-A3+A4 Multi-Bucket & D5 Boundary Performance Qualification.

Runs authentic 5-runtime Docker scenarios on a large pot (100 shareholders, ~2,000 expenses)
with genuinely distinct beneficiary buckets (default, specific, and mixed weights), weight
updates, expense creations, financial updates, and logical deletions, verifying exact
rational equivalence against an independent full-recomputation Python Fraction oracle.

Scenarios:
  1. multibucket-realistic:
     - 100 shareholders (varied initial weights), 2,000 expenses across 60 distinct buckets
       (45 pure-default subsets + 15 mixed-weight buckets) plus ~200 pure-specific expenses.
     - 60 mixed measured commands (CreateExpense, UpdateExpenseFinancials,
       UpdateShareholderWeights, DeleteExpense) verifying exact oracle equivalence and
       measuring full Micrometer breakdown, JSONB size, PotBalance/PotVersion p50/p95/p99,
       and E2E READ C-03 latency.
  2. multibucket-d5-boundary:
     - 100 shareholders, 2,000 expenses across 240 distinct buckets (200 pure-default +
       40 mixed-weight, ~6,500–8,000 entries — right near D5 caps 256 / 10,000).
     - Phase A (near-d5): 30 mixed commands staying at G <= 256.
     - Phase B (exceed-d5): 25 commands crossing G > 256 (new unique buckets + weight updates
       + expense updates + deletes), verifying clean BUCKET_CAP fallback, 0 aggregate rows
       written while G > 256, independent A3 PotVersion staying 100% incremental, and exact
       rational oracle equivalence on every version.
"""

import argparse
from fractions import Fraction
import importlib.util
import json
import os
from pathlib import Path
import random
import re
import shutil
import subprocess
import sys
import time
import uuid

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

_spec = importlib.util.spec_from_file_location("run_l7_4a_baseline", SCRIPT_DIR / "run-l7-4a-baseline.py")
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)
import smoke_test_5_runtimes as oracle  # noqa: E402
import spec09_bench as bench  # noqa: E402


# ---------------------------------------------------------------------------
# Prometheus parsing helpers
# ---------------------------------------------------------------------------

def parse_prom_all(prom_text):
    """Return list of (name, labels_dict, value) from Prometheus text exposition."""
    rows = []
    for line in prom_text.splitlines():
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


def prom_val(rows, metric_name, label_filter=None):
    total = 0.0
    for name, labels, val in rows:
        if name != metric_name:
            continue
        if label_filter and not all(labels.get(k) == v for k, v in label_filter.items()):
            continue
        total += val
    return total


def prom_timer_delta(pre_rows, post_rows, metric_base, label_filter=None):
    d_count = prom_val(post_rows, f"{metric_base}_count", label_filter) - prom_val(pre_rows, f"{metric_base}_count", label_filter)
    d_sum = prom_val(post_rows, f"{metric_base}_sum", label_filter) - prom_val(pre_rows, f"{metric_base}_sum", label_filter)
    avg_ms = round((d_sum / d_count) * 1000.0, 3) if d_count > 0 else 0.0
    return {"count": int(round(d_count)), "totalMs": round(d_sum * 1000.0, 3), "avgMs": avg_ms}


def prom_summary_delta(pre_rows, post_rows, metric_base, label_filter=None):
    d_count = prom_val(post_rows, f"{metric_base}_count", label_filter) - prom_val(pre_rows, f"{metric_base}_count", label_filter)
    d_sum = prom_val(post_rows, f"{metric_base}_sum", label_filter) - prom_val(pre_rows, f"{metric_base}_sum", label_filter)
    avg = round(d_sum / d_count, 3) if d_count > 0 else 0.0
    return {"count": int(round(d_count)), "sum": round(d_sum, 3), "avg": avg}


def prom_paths_delta(pre_rows, post_rows, metric_name):
    pre_map = {}
    post_map = {}
    for name, labels, val in pre_rows:
        if name == metric_name:
            pre_map[(labels.get("path", ""), labels.get("reason", ""))] = val
    for name, labels, val in post_rows:
        if name == metric_name:
            post_map[(labels.get("path", ""), labels.get("reason", ""))] = val
    out = {}
    for key in sorted(set(pre_map) | set(post_map)):
        delta = int(round(post_map.get(key, 0.0) - pre_map.get(key, 0.0)))
        if delta > 0:
            out[f"{key[0]}:{key[1]}"] = delta
    return out


def extract_micrometer_window(pre_text, post_text):
    pre = parse_prom_all(pre_text)
    post = parse_prom_all(post_text)
    bal_paths = prom_paths_delta(pre, post, "pocoma_v3_potbalance_materialize_path_total")
    ver_paths = prom_paths_delta(pre, post, "pocoma_v3_potversion_materialize_path_total")
    bal_total = sum(bal_paths.values())
    bal_inc = sum(v for k, v in bal_paths.items() if k.startswith("incremental:"))
    ver_total = sum(ver_paths.values())
    ver_inc = sum(v for k, v in ver_paths.items() if k.startswith("incremental:"))
    return {
        "potBalance": {
            "paths": bal_paths,
            "total": bal_total,
            "incrementalCount": bal_inc,
            "incrementalRate": round(bal_inc / bal_total, 4) if bal_total else 0.0,
            "lookupAndDeserialize": prom_timer_delta(pre, post, "pocoma_v3_potbalance_aggregate_lookup_duration_seconds"),
            "deltaResolution": prom_timer_delta(pre, post, "pocoma_v3_potbalance_delta_duration_seconds"),
            "deltaExpenses": prom_summary_delta(pre, post, "pocoma_v3_potbalance_delta_expenses"),
            "compose": prom_timer_delta(pre, post, "pocoma_v3_potbalance_compose_duration_seconds"),
            "evaluateIncremental": prom_timer_delta(pre, post, "pocoma_v3_potbalance_evaluate_duration_seconds", {"path": "incremental"}),
            "evaluateFull": prom_timer_delta(pre, post, "pocoma_v3_potbalance_evaluate_duration_seconds", {"path": "full"}),
            "persistBalance": prom_timer_delta(pre, post, "pocoma_v3_potbalance_persist_duration_seconds", {"target": "balance"}),
            "persistAggregate": prom_timer_delta(pre, post, "pocoma_v3_potbalance_persist_duration_seconds", {"target": "aggregate"}),
            "aggregateBuckets": prom_summary_delta(pre, post, "pocoma_v3_potbalance_aggregate_buckets"),
            "aggregateEntries": prom_summary_delta(pre, post, "pocoma_v3_potbalance_aggregate_entries"),
            "referenceDistance": prom_summary_delta(pre, post, "pocoma_v3_potbalance_reference_distance"),
        },
        "potVersion": {
            "paths": ver_paths,
            "total": ver_total,
            "incrementalCount": ver_inc,
            "incrementalRate": round(ver_inc / ver_total, 4) if ver_total else 0.0,
        },
    }


# ---------------------------------------------------------------------------
# Independent exact Python Fraction full-recomputation oracle
# ---------------------------------------------------------------------------

class ExactPotOracle:
    """Full-recomputation exact rational oracle matching PotBalanceCalculator.java."""

    def __init__(self, shareholders, default_weights):
        self.shareholders = list(shareholders)
        self.default_weights = dict(default_weights)  # sid -> Fraction
        self.expenses = {}  # eid -> {"amount": Fraction, "payer_id": sid, "participants": [(sid, Optional[Fraction])]}

    def add_or_update_expense(self, eid, amount, payer_id, participants):
        self.expenses[eid] = {
            "amount": amount,
            "payer_id": payer_id,
            "participants": list(participants),
        }

    def delete_expense(self, eid):
        self.expenses.pop(eid, None)

    def update_weights(self, updates):
        for sid, weight in updates.items():
            self.default_weights[sid] = weight

    def compute_exact_balances(self):
        bal = {sid: Fraction(0, 1) for sid in self.shareholders}
        for exp in self.expenses.values():
            amt = exp["amount"]
            if amt == 0:
                continue
            bal[exp["payer_id"]] += amt
            eff = []
            w_total = Fraction(0, 1)
            for sid, spec_w in exp["participants"]:
                w = spec_w if spec_w is not None else self.default_weights[sid]
                eff.append((sid, w))
                w_total += w
            for sid, w in eff:
                bal[sid] -= amt * w / w_total
        assert sum(bal.values(), Fraction(0, 1)) == 0, "Oracle invariant violated: non-zero sum"
        return bal

    def compute_bucket_cardinality(self):
        """Return (bucket_count, total_bucket_entries) matching PotBalanceAggregate."""
        buckets = {}  # (tuple(sorted_def_ids), w_spec) -> (total_amt, set(spec_ids))
        for exp in self.expenses.values():
            amt = exp["amount"]
            if amt == 0:
                continue
            defs = sorted(sid for sid, sw in exp["participants"] if sw is None)
            specs = [(sid, sw) for sid, sw in exp["participants"] if sw is not None]
            if not defs:
                continue  # pure specific -> fixedNet
            w_spec = sum((sw for _, sw in specs), Fraction(0, 1))
            key = (tuple(defs), w_spec)
            cur_amt, cur_spec_ids = buckets.get(key, (Fraction(0, 1), set()))
            for sid, _ in specs:
                cur_spec_ids.add(sid)
            buckets[key] = (cur_amt + amt, cur_spec_ids)
        entries = sum(len(k[0]) + len(v[1]) for k, v in buckets.items())
        return len(buckets), entries


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def frac_json(f):
    return {"numerator": str(f.numerator), "denominator": str(f.denominator)}


def participants_json(participants):
    return [
        {"id": sid, "weight": frac_json(sw) if sw is not None else None}
        for sid, sw in participants
    ]


def submit_command(payload, token, binding, timeout_s=30.0, poll_s=0.01):
    idem = str(uuid.uuid4())
    body = {**payload, "bindingToken": binding}
    t0 = time.monotonic_ns()
    code, resp = oracle.request("POST", "http://127.0.0.1:28000/api/v3/commands", token, body, key=idem)
    if code != 202:
        raise RuntimeError(f"Command admission failed HTTP {code}: {resp}")
    req_id = resp["requestId"]
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        rcode, rbody = oracle.request("GET", f"http://127.0.0.1:28002/api/v3/commands/{req_id}/result", token)
        if rcode == 200:
            if rbody.get("outcome") != "COMPLETED":
                raise RuntimeError(f"Command {req_id} not COMPLETED: {rbody}")
            return req_id, rbody["resultingPotVersion"], t0
        time.sleep(poll_s)
    raise TimeoutError(f"Command {req_id} result timeout")


def wait_e2e_c03_and_verify_oracle(pot_id, version, expected_balances, shareholders, token, binding, t0_ns, timeout_s=45.0):
    deadline = time.monotonic() + timeout_s
    got_pot = False
    got_bal = None
    while time.monotonic() < deadline:
        if not got_pot:
            pcode, _ = oracle.request(
                "GET", f"http://127.0.0.1:28002/api/v3/pots/{pot_id}?minVersion={version}", token, binding=binding
            )
            if pcode == 200:
                got_pot = True
        if got_bal is None:
            bcode, bbody = oracle.request(
                "GET", f"http://127.0.0.1:28002/api/v3/pots/{pot_id}/versions/{version}/balance", token, binding=binding
            )
            if bcode == 200:
                got_bal = bbody
        if got_pot and got_bal is not None:
            e2e_ms = round((time.monotonic_ns() - t0_ns) / 1e6, 3)
            actual = oracle.balance_values(got_bal, shareholders)
            for sid in shareholders:
                if actual[sid] != expected_balances[sid]:
                    raise AssertionError(
                        f"EXACT RATIONAL DIVERGENCE at v{version} shareholder={sid}: "
                        f"expected={expected_balances[sid]} actual={actual[sid]}"
                    )
            return e2e_ms
        time.sleep(0.015)
    raise TimeoutError(f"E2E C-03 timeout for {pot_id} v{version} (got_pot={got_pot}, got_bal={got_bal is not None})")


def query_aggregate_window_stats(pg, pot_id, min_version, max_version):
    has_v4 = bench.sql(pg, "pocoma_read", "SELECT to_jsonb(to_regclass('v3_read.read_pot_balance_aggregate_content') IS NOT NULL)")
    if has_v4:
        return bench.sql(
            pg,
            "pocoma_read",
            f"SELECT jsonb_build_object("
            f"'rowCount', count(*),"
            f"'distinctContents', count(DISTINCT pba.content_hash),"
            f"'distinctShareholderSets', count(DISTINCT bac.shareholder_set_hash),"
            f"'minBucketCount', coalesce(min(bac.bucket_count), 0),"
            f"'avgBucketCount', coalesce(round(avg(bac.bucket_count), 2), 0),"
            f"'maxBucketCount', coalesce(max(bac.bucket_count), 0),"
            f"'avgPayloadBytes', coalesce(round(avg(octet_length(bac.payload))), 0),"
            f"'maxPayloadBytes', coalesce(max(octet_length(bac.payload)), 0),"
            f"'avgColumnBytes', coalesce(round(avg(pg_column_size(bac.payload))), 0),"
            f"'maxColumnBytes', coalesce(max(pg_column_size(bac.payload)), 0)"
            f") FROM v3_read.read_pot_balance_aggregate pba "
            f"JOIN v3_read.read_pot_balance_aggregate_content bac USING (content_hash) "
            f"WHERE pba.pot_id = '{pot_id}'::uuid AND pba.pot_version >= {min_version} AND pba.pot_version <= {max_version}",
        )
    return bench.sql(
        pg,
        "pocoma_read",
        f"SELECT jsonb_build_object("
        f"'rowCount', count(*),"
        f"'minBucketCount', coalesce(min(bucket_count), 0),"
        f"'avgBucketCount', coalesce(round(avg(bucket_count), 2), 0),"
        f"'maxBucketCount', coalesce(max(bucket_count), 0),"
        f"'avgEntries', coalesce(round(avg((SELECT coalesce(sum(jsonb_array_length(b->'p') + jsonb_array_length(b->'sq')), 0) FROM jsonb_array_elements(payload->'b') b)), 1), 0),"
        f"'maxEntries', coalesce(max((SELECT coalesce(sum(jsonb_array_length(b->'p') + jsonb_array_length(b->'sq')), 0) FROM jsonb_array_elements(payload->'b') b)), 0),"
        f"'avgTextBytes', coalesce(round(avg(octet_length(payload::text))), 0),"
        f"'maxTextBytes', coalesce(max(octet_length(payload::text)), 0),"
        f"'avgColumnBytes', coalesce(round(avg(pg_column_size(payload))), 0),"
        f"'maxColumnBytes', coalesce(max(pg_column_size(payload)), 0)"
        f") FROM v3_read.read_pot_balance_aggregate "
        f"WHERE pot_id = '{pot_id}'::uuid AND pot_version >= {min_version} AND pot_version <= {max_version}",
    )


# ---------------------------------------------------------------------------
# Bucket template generators
# ---------------------------------------------------------------------------

def build_realistic_templates(shareholders, rng):
    """Build 45 pure-default + 15 mixed-weight bucket templates (G = 60 distinct buckets)."""
    n = len(shareholders)
    pure_default = []
    seen_defs = set()
    # 1 full group of 100
    full_t = tuple(sorted(shareholders))
    pure_default.append([(sid, None) for sid in full_t])
    seen_defs.add(full_t)
    # 44 varied subsets (sizes 2..60)
    sizes = [2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60]
    while len(pure_default) < 45:
        sz = sizes[len(pure_default) % len(sizes)]
        sub = tuple(sorted(rng.sample(shareholders, sz)))
        if sub not in seen_defs:
            seen_defs.add(sub)
            pure_default.append([(sid, None) for sid in sub])

    mixed = []
    spec_weights = [Fraction(2, 1), Fraction(5, 2), Fraction(7, 3), Fraction(3, 1), Fraction(4, 3)]
    seen_mixed = set()
    while len(mixed) < 15:
        def_sz = rng.choice([4, 6, 10, 15, 20])
        spec_sz = rng.choice([1, 2, 3])
        picked = rng.sample(shareholders, def_sz + spec_sz)
        defs = tuple(sorted(picked[:def_sz]))
        specs = picked[def_sz:]
        sw_list = [(sid, rng.choice(spec_weights)) for sid in specs]
        w_spec = sum((w for _, w in sw_list), Fraction(0, 1))
        key = (defs, w_spec)
        if key not in seen_mixed:
            seen_mixed.add(key)
            mixed.append([(sid, None) for sid in defs] + sw_list)

    return pure_default, mixed


def build_near_d5_templates(shareholders, rng):
    """Build 200 pure-default + 40 mixed-weight bucket templates (G = 240, ~6,600 entries)."""
    pure_default = []
    seen_defs = set()
    while len(pure_default) < 200:
        sz = rng.randint(22, 32)
        sub = tuple(sorted(rng.sample(shareholders, sz)))
        if sub not in seen_defs:
            seen_defs.add(sub)
            pure_default.append([(sid, None) for sid in sub])

    mixed = []
    spec_weights = [Fraction(2, 1), Fraction(5, 2), Fraction(7, 3), Fraction(3, 1)]
    seen_mixed = set()
    while len(mixed) < 40:
        def_sz = rng.randint(18, 28)
        spec_sz = rng.choice([2, 3])
        picked = rng.sample(shareholders, def_sz + spec_sz)
        defs = tuple(sorted(picked[:def_sz]))
        specs = picked[def_sz:]
        sw_list = [(sid, rng.choice(spec_weights)) for sid in specs]
        w_spec = sum((w for _, w in sw_list), Fraction(0, 1))
        key = (defs, w_spec)
        if key not in seen_mixed:
            seen_mixed.add(key)
            mixed.append([(sid, None) for sid in defs] + sw_list)

    return pure_default, mixed


def make_unique_bucket_participants(shareholders, existing_keys, rng, size=25):
    for _ in range(10000):
        sub = tuple(sorted(rng.sample(shareholders, size)))
        if (sub, Fraction(0, 1)) not in existing_keys:
            existing_keys.add((sub, Fraction(0, 1)))
            return [(sid, None) for sid in sub]
    raise RuntimeError("Could not sample unique bucket")


def make_pure_specific_participants(shareholders, rng):
    picked = rng.sample(shareholders, rng.choice([2, 3, 4, 5]))
    return [(sid, Fraction(rng.randint(1, 5), rng.choice([1, 2, 3]))) for sid in picked]


# ---------------------------------------------------------------------------
# Main runner
# ---------------------------------------------------------------------------

def register_and_create_pot(token, rng, shareholder_count=100):
    # Register user & wait for active binding using canonical L7 registration flow
    registration = base.load_module("l74a_reg", "run-l7-spec09-registration.py")
    identity = registration.observe_one("http://127.0.0.1:28000", "http://127.0.0.1:28002", token, str(uuid.uuid4()))
    if identity.get("outcome") != "AUTHORIZED":
        raise RuntimeError(f"Registration failed: {identity}")
    code, binding_resp = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)
    if code != 200 or not binding_resp.get("bindingToken"):
        raise RuntimeError(f"Binding unavailable: HTTP {code} {binding_resp}")
    binding = binding_resp["bindingToken"]

    pot_id = str(uuid.uuid4())
    _, v, _ = submit_command(
        {"operation": "CreatePot", "potId": pot_id, "observedVersion": 0, "payload": {"label": "Multi-Bucket Large Pot"}},
        token,
        binding,
    )
    assert v == 1

    init_weights = [Fraction(1, 1), Fraction(2, 1), Fraction(3, 2), Fraction(5, 3)]
    shareholders = [str(uuid.uuid4()) for _ in range(shareholder_count)]
    default_weights = {}
    targets = []
    for idx, sid in enumerate(shareholders):
        w = init_weights[idx % len(init_weights)]
        default_weights[sid] = w
        targets.append({
            "id": sid,
            "name": f"SH-{idx:03d}",
            "attachment": "SELF" if idx == 0 else "FREE",
            "weight": frac_json(w),
        })
    _, v, _ = submit_command(
        {"operation": "AddShareholders", "potId": pot_id, "observedVersion": v, "payload": {"targets": targets}},
        token,
        binding,
    )
    assert v == 2
    return binding, pot_id, v, shareholders, default_weights


def seed_expenses(pot_id, start_version, shareholders, default_weights, pure_default, mixed, expense_count, include_pure_specific, token, binding, rng):
    pot_oracle = ExactPotOracle(shareholders, default_weights)
    version = start_version
    expense_ids = []
    t_start = time.monotonic()
    for i in range(expense_count):
        eid = str(uuid.uuid4())
        payer = shareholders[i % len(shareholders)]
        amt = Fraction(rng.randint(10, 500), rng.choice([1, 2, 3, 5]))
        # Guarantee every template gets at least several expenses first
        if i < len(pure_default):
            parts = pure_default[i]
        elif i < len(pure_default) + len(mixed):
            parts = mixed[i - len(pure_default)]
        else:
            r = i % 10
            if include_pure_specific and r == 9:
                parts = make_pure_specific_participants(shareholders, rng)
            elif r in (7, 8):
                parts = mixed[i % len(mixed)]
            else:
                parts = pure_default[i % len(pure_default)]

        _, version, _ = submit_command(
            {
                "operation": "CreateExpense",
                "potId": pot_id,
                "observedVersion": version,
                "payload": {
                    "id": eid,
                    "label": f"seed-exp-{i:04d}",
                    "date": "2026-11-19",
                    "amount": frac_json(amt),
                    "payerId": payer,
                    "participants": participants_json(parts),
                },
            },
            token,
            binding,
        )
        pot_oracle.add_or_update_expense(eid, amt, payer, parts)
        expense_ids.append(eid)
        if (i + 1) % 400 == 0:
            elapsed = time.monotonic() - t_start
            print(f"  seeded {i + 1}/{expense_count} expenses in {elapsed:.1f}s (v={version})", flush=True)

    return version, pot_oracle, expense_ids, round(time.monotonic() - t_start, 2)


def run_mixed_window(
    label,
    pot_id,
    version,
    pot_oracle,
    shareholders,
    expense_ids,
    pure_default,
    mixed,
    operations_plan,
    token,
    binding,
    rng,
    pg,
    project,
    out,
    metrics_url,
    existing_bucket_keys,
):
    base.wait_for_drainage(pg, timeout_s=60)
    pre_prom = bench.fetch_metrics(metrics_url)
    (out / f"metrics-pre-{label}.prom").write_text(pre_prom)
    min_ver = version + 1

    req_ids = []
    e2e_c03_ms = []
    op_counts = {}
    weight_update_req_ids = []

    for idx, op in enumerate(operations_plan):
        op_counts[op] = op_counts.get(op, 0) + 1
        if op == "CreateExpense_existing":
            eid = str(uuid.uuid4())
            payer = shareholders[(idx * 7) % len(shareholders)]
            amt = Fraction(rng.randint(15, 300), rng.choice([1, 2, 3]))
            parts = mixed[idx % len(mixed)] if idx % 3 == 0 else pure_default[idx % len(pure_default)]
            payload = {
                "operation": "CreateExpense",
                "potId": pot_id,
                "observedVersion": version,
                "payload": {
                    "id": eid,
                    "label": f"{label}-create-{idx}",
                    "date": "2026-11-19",
                    "amount": frac_json(amt),
                    "payerId": payer,
                    "participants": participants_json(parts),
                },
            }
            pot_oracle.add_or_update_expense(eid, amt, payer, parts)
            expense_ids.append(eid)
        elif op == "CreateExpense_specific":
            eid = str(uuid.uuid4())
            payer = shareholders[(idx * 11) % len(shareholders)]
            amt = Fraction(rng.randint(20, 400), rng.choice([1, 2]))
            parts = make_pure_specific_participants(shareholders, rng)
            payload = {
                "operation": "CreateExpense",
                "potId": pot_id,
                "observedVersion": version,
                "payload": {
                    "id": eid,
                    "label": f"{label}-spec-{idx}",
                    "date": "2026-11-19",
                    "amount": frac_json(amt),
                    "payerId": payer,
                    "participants": participants_json(parts),
                },
            }
            pot_oracle.add_or_update_expense(eid, amt, payer, parts)
            expense_ids.append(eid)
        elif op == "CreateExpense_new_bucket":
            eid = str(uuid.uuid4())
            payer = shareholders[(idx * 13) % len(shareholders)]
            amt = Fraction(rng.randint(25, 250), rng.choice([1, 3]))
            parts = make_unique_bucket_participants(shareholders, existing_bucket_keys, rng, size=26)
            payload = {
                "operation": "CreateExpense",
                "potId": pot_id,
                "observedVersion": version,
                "payload": {
                    "id": eid,
                    "label": f"{label}-newb-{idx}",
                    "date": "2026-11-19",
                    "amount": frac_json(amt),
                    "payerId": payer,
                    "participants": participants_json(parts),
                },
            }
            pot_oracle.add_or_update_expense(eid, amt, payer, parts)
            expense_ids.append(eid)
        elif op == "UpdateExpenseFinancials":
            # Pick a recent active expense so we don't empty an initial bucket unexpectedly
            eid = expense_ids[-(idx % 50 + 1)]
            while eid not in pot_oracle.expenses:
                eid = rng.choice(list(pot_oracle.expenses.keys()))
            payer = shareholders[(idx * 5 + 3) % len(shareholders)]
            amt = Fraction(rng.randint(30, 600), rng.choice([1, 2, 5]))
            parts = mixed[(idx + 2) % len(mixed)] if idx % 2 == 0 else pure_default[(idx + 3) % len(pure_default)]
            payload = {
                "operation": "UpdateExpenseFinancials",
                "potId": pot_id,
                "observedVersion": version,
                "payload": {
                    "id": eid,
                    "amount": frac_json(amt),
                    "payerId": payer,
                    "participants": participants_json(parts),
                },
            }
            pot_oracle.add_or_update_expense(eid, amt, payer, parts)
        elif op == "UpdateShareholderWeights":
            targets = []
            updates = {}
            for j in range(4):
                sid = shareholders[(idx * 4 + j) % len(shareholders)]
                w = Fraction(((idx + j) % 7) + 2, rng.choice([1, 2, 3]))
                updates[sid] = w
                targets.append({"id": sid, "weight": frac_json(w)})
            payload = {
                "operation": "UpdateShareholderWeights",
                "potId": pot_id,
                "observedVersion": version,
                "payload": {"targets": targets},
            }
            pot_oracle.update_weights(updates)
        elif op == "DeleteExpense":
            # Delete a later expense (keeping initial bucket population intact)
            eid = None
            for cand in reversed(expense_ids):
                if cand in pot_oracle.expenses:
                    eid = cand
                    break
            assert eid is not None
            payload = {
                "operation": "DeleteExpense",
                "potId": pot_id,
                "observedVersion": version,
                "payload": {"id": eid},
            }
            pot_oracle.delete_expense(eid)
        else:
            raise ValueError(f"Unknown op {op}")

        req_id, version, t0_ns = submit_command(payload, token, binding)
        expected = pot_oracle.compute_exact_balances()
        e2e_ms = wait_e2e_c03_and_verify_oracle(pot_id, version, expected, shareholders, token, binding, t0_ns)
        req_ids.append(req_id)
        e2e_c03_ms.append(e2e_ms)
        if op == "UpdateShareholderWeights":
            weight_update_req_ids.append(req_id)

    base.wait_for_drainage(pg, timeout_s=60)
    post_prom = bench.fetch_metrics(metrics_url)
    (out / f"metrics-post-{label}.prom").write_text(post_prom)
    max_ver = version

    micro = extract_micrometer_window(pre_prom, post_prom)
    write_timings = base.query_write_stage_timings(pg, [{"requestId": r} for r in req_ids])
    handoffs = base.parse_handoff_logs(project, out, req_ids, write_timings)
    weight_timings = {r: write_timings[r] for r in weight_update_req_ids if r in write_timings}
    weight_handoffs = base.parse_handoff_logs(project, out, weight_update_req_ids, weight_timings) if weight_update_req_ids else {}
    agg_stats = query_aggregate_window_stats(pg, pot_id, min_ver, max_ver)
    oracle_buckets, oracle_entries = pot_oracle.compute_bucket_cardinality()

    return version, {
        "phase": label,
        "versionRange": [min_ver, max_ver],
        "commandCount": len(operations_plan),
        "operationCounts": op_counts,
        "exactOracleVerified": True,
        "exactOracleDivergences": 0,
        "finalOracleBucketCount": oracle_buckets,
        "finalOracleBucketEntries": oracle_entries,
        "e2eReadC03Ms": base.stats_summary(e2e_c03_ms),
        "projections": handoffs["projections"],
        "weightUpdateProjections": weight_handoffs.get("projections", {}),
        "aggregateStorage": agg_stats,
        "micrometer": micro,
    }


def run_scenario(args):
    cfg = base.load_profiles_config()
    topo = cfg["topology"]
    guardrails = cfg["guardrails"]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)

    work = Path(f"/private/tmp/pocoma-l74-mb-{uuid.uuid4().hex[:8]}")
    work.mkdir(mode=0o700, parents=True)
    project = f"pocoma-l74mb-{uuid.uuid4().hex[:8]}"
    pg = f"{project}-postgres-1"
    compose_path = work / "compose.json"
    compose_args = ["docker", "compose", "-p", project, "-f", str(compose_path)]

    def dc(*argv, timeout=120):
        with (out / "lifecycle.log").open("a") as log:
            subprocess.run(compose_args + list(argv), check=True, stdout=log, stderr=log, timeout=timeout)

    issuer = None
    manifest = {
        "scenario": args.scenario,
        "startedAt": bench.utc(),
        "javaImage": args.java_image,
        "postgresImage": args.postgres_image,
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

        print(f"[{args.scenario}] Bootstrapping V3 database (including V2__pot_balance_aggregate.sql)...", flush=True)
        with (out / "bootstrap.log").open("w") as log:
            subprocess.run(
                [sys.executable, "-B", str(base.ROOT / "scripts/operations/setup-v3-database.py"), "--postgres-container", pg],
                check=True,
                stdout=log,
                stderr=log,
                timeout=120,
            )

        subject = f"mb-{args.scenario}-user"
        (work / "subjects.json").write_text(json.dumps([subject]))
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
        rng = random.Random(20261119 if args.scenario == "multibucket-realistic" else 20261120)

        print(f"[{args.scenario}] Creating pot with 100 shareholders...", flush=True)
        binding, pot_id, version, shareholders, default_weights = register_and_create_pot(token, rng, 100)
        manifest["potId"] = pot_id
        manifest["shareholderCount"] = len(shareholders)

        if args.scenario == "multibucket-realistic":
            pure_default, mixed = build_realistic_templates(shareholders, rng)
            print(f"[{args.scenario}] Seeding 2,000 expenses across 60 distinct buckets (45 default + 15 mixed + pure-specific)...", flush=True)
            version, pot_oracle, expense_ids, seed_s = seed_expenses(
                pot_id, version, shareholders, default_weights, pure_default, mixed,
                2000, True, token, binding, rng
            )
            base.wait_for_drainage(pg, timeout_s=90)
            # Verify initial seeded state against exact oracle
            expected0 = pot_oracle.compute_exact_balances()
            wait_e2e_c03_and_verify_oracle(pot_id, version, expected0, shareholders, token, binding, time.monotonic_ns())
            seed_buckets, seed_entries = pot_oracle.compute_bucket_cardinality()
            manifest["seed"] = {
                "expenseCount": 2000,
                "elapsedSeconds": seed_s,
                "finalVersion": version,
                "bucketCount": seed_buckets,
                "bucketEntries": seed_entries,
            }
            print(f"[{args.scenario}] Seed verified! G={seed_buckets} buckets, entries={seed_entries}. Running 60 mixed commands...", flush=True)

            existing_keys = set()
            for parts in pure_default:
                existing_keys.add((tuple(sorted(sid for sid, _ in parts)), Fraction(0, 1)))
            plan = (
                ["CreateExpense_existing"] * 12
                + ["CreateExpense_specific"] * 4
                + ["CreateExpense_new_bucket"] * 4
                + ["UpdateExpenseFinancials"] * 15
                + ["UpdateShareholderWeights"] * 15
                + ["DeleteExpense"] * 10
            )
            rng.shuffle(plan)
            version, phase_res = run_mixed_window(
                "realistic-60",
                pot_id, version, pot_oracle, shareholders, expense_ids,
                pure_default, mixed, plan, token, binding, rng,
                pg, project, out, metrics_urls["business-read-worker"], existing_keys,
            )
            manifest["phases"] = [phase_res]
            manifest["verdict"] = "PASS" if phase_res["micrometer"]["potBalance"]["incrementalRate"] == 1.0 else "FAIL"

        elif args.scenario == "multibucket-d5-boundary":
            pure_default, mixed = build_near_d5_templates(shareholders, rng)
            print(f"[{args.scenario}] Seeding 2,000 expenses across 240 distinct buckets (near D5 caps 256 / 10,000)...", flush=True)
            version, pot_oracle, expense_ids, seed_s = seed_expenses(
                pot_id, version, shareholders, default_weights, pure_default, mixed,
                2000, False, token, binding, rng
            )
            base.wait_for_drainage(pg, timeout_s=120)
            expected0 = pot_oracle.compute_exact_balances()
            wait_e2e_c03_and_verify_oracle(pot_id, version, expected0, shareholders, token, binding, time.monotonic_ns())
            seed_buckets, seed_entries = pot_oracle.compute_bucket_cardinality()
            manifest["seed"] = {
                "expenseCount": 2000,
                "elapsedSeconds": seed_s,
                "finalVersion": version,
                "bucketCount": seed_buckets,
                "bucketEntries": seed_entries,
            }
            print(f"[{args.scenario}] Seed verified! G={seed_buckets}, entries={seed_entries}. Running Phase A (near-d5, 30 cmds)...", flush=True)

            existing_keys = set()
            for parts in pure_default:
                existing_keys.add((tuple(sorted(sid for sid, _ in parts)), Fraction(0, 1)))

            plan_near = (
                ["CreateExpense_existing"] * 10
                + ["UpdateExpenseFinancials"] * 8
                + ["UpdateShareholderWeights"] * 8
                + ["DeleteExpense"] * 4
            )
            rng.shuffle(plan_near)
            version, phase_near = run_mixed_window(
                "near-d5-30",
                pot_id, version, pot_oracle, shareholders, expense_ids,
                pure_default, mixed, plan_near, token, binding, rng,
                pg, project, out, metrics_urls["business-read-worker"], existing_keys,
            )

            print(f"[{args.scenario}] Phase A complete (G={phase_near['finalOracleBucketCount']}). Running Phase B (exceed-d5, crossing G > 256)...", flush=True)
            # First add enough new unique buckets to cross 256 (from ~240 -> 260), then mix updates/weights/deletes above cap
            need_to_cross = max(18, 260 - phase_near["finalOracleBucketCount"])
            plan_exceed = (
                ["CreateExpense_new_bucket"] * need_to_cross
                + ["UpdateShareholderWeights"] * 4
                + ["UpdateExpenseFinancials"] * 3
                + ["DeleteExpense"] * 2
            )
            version, phase_exceed = run_mixed_window(
                "exceed-d5",
                pot_id, version, pot_oracle, shareholders, expense_ids,
                pure_default, mixed, plan_exceed, token, binding, rng,
                pg, project, out, metrics_urls["business-read-worker"], existing_keys,
            )

            # Verify 0 aggregate rows persisted once G > 256 (tail of phase_exceed)
            tail_min_ver = phase_exceed["versionRange"][1] - 8
            tail_agg_rows = int(
                bench.sql(
                    pg,
                    "pocoma_read",
                    f"SELECT count(*) FROM v3_read.read_pot_balance_aggregate "
                    f"WHERE pot_id = '{pot_id}'::uuid AND pot_version >= {tail_min_ver}",
                )
            )
            phase_exceed["aggregateRowsPersistedWhileAboveCap"] = tail_agg_rows
            manifest["phases"] = [phase_near, phase_exceed]

            near_ok = phase_near["micrometer"]["potBalance"]["incrementalRate"] == 1.0
            exceed_has_cap = phase_exceed["micrometer"]["potBalance"]["paths"].get("full:bucket_cap", 0) >= 1
            ver_stayed_inc = phase_exceed["micrometer"]["potVersion"]["incrementalRate"] == 1.0
            no_tail_agg = tail_agg_rows == 0
            manifest["verdict"] = "PASS" if (near_ok and exceed_has_cap and ver_stayed_inc and no_tail_agg) else "FAIL"

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
    p.add_argument("--scenario", choices=("multibucket-realistic", "multibucket-d5-boundary"), required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--java-image", required=True)
    p.add_argument("--postgres-image", required=True)
    return run_scenario(p.parse_args())


if __name__ == "__main__":
    sys.exit(main())
