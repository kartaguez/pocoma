#!/usr/bin/env python3
"""Summarize validated A/B windows; keep the full JDBC samples and SQL in TSV."""

import collections
import csv
from datetime import datetime
import json
import math
from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1])
FAMILIES = {"COMMAND_RESULT", "IDENTITY_REQUEST_RESULT", "POT_VERSION", "POT_BALANCE"}


def rows(filename):
    with (ROOT / filename).open(newline="") as file:
        yield from csv.DictReader(file, delimiter="\t")


def key(row):
    return row["profile"], int(row["workers"]), row["publish"] == "true"


def is_counter(query):
    query = query.lower()
    return "update read_availability_counter" in query or "update c3_bench_counter" in query


def quantiles(values):
    values = sorted(values)
    if not values:
        return None
    return {str(q): round(values[math.ceil(len(values) * q / 100) - 1], 4) for q in (50, 95, 99)}


windows = {key(row): row for row in rows("windows.tsv")}
expected = {(profile, worker, publish)
            for worker in (1, 4) for publish in (False, True)
            for profile in FAMILIES | ({"MIXED"} if worker == 4 else set())}
assert windows.keys() == expected, sorted(expected - windows.keys())

samples = collections.defaultdict(list)
for row in rows("samples.tsv"):
    samples[key(row)].append(row)
sql_rows = collections.defaultdict(list)
for row in rows("sql.tsv"):
    sql_rows[key(row)].append(row)
wait_samples = collections.defaultdict(collections.Counter)
for row in rows("waits.tsv"):
    query = row["query"]
    role = "COUNTER" if is_counter(query) else "OTHER_SQL"
    wait_samples[key(row)][f"{row['type']}:{row['event']}:{role}"] += 1

# Match each reported acquisition to the immediately following STATEMENT for that PID.
# PostgreSQL emits an acquisition duration only for waits past its 5 ms diagnostic threshold.
pending = {}
lock_file = ROOT / "postgres-locks.log"
if not lock_file.is_file():
    lock_file = ROOT / "postgres-locks.txt"
lock_waits = collections.defaultdict(list)
for line in lock_file.read_text().splitlines():
    log = re.match(r"^(\S+ \S+) UTC \[(\d+)\] (LOG|STATEMENT):\s+(.+)", line)
    if not log:
        continue
    timestamp, pid, level, message = log.groups()
    if level == "LOG":
        match = re.search(r"process \d+ acquired .+ after ([\d.]+) ms", message)
        if match:
            pending[pid] = (datetime.fromisoformat(timestamp + "+00:00"), float(match.group(1)))
        continue
    if pid not in pending:
        continue
    time, duration = pending.pop(pid)
    for variant, row in windows.items():
        if datetime.fromisoformat(row["utc_start"]) <= time <= datetime.fromisoformat(row["utc_end"]):
            lock_waits[variant].append({"pid": int(pid), "time": time.isoformat(), "ms": duration,
                                        "counter": is_counter(message), "query": message})
            break

output = []
deltas = []
for variant, row in windows.items():
    profile, workers, publish = variant
    n = int(row["n"])
    jobs = samples[variant]
    wall_seconds = int(row["wall_ns"]) / 1e9
    assert n == len(jobs), variant
    profiles = collections.defaultdict(list)
    for sample in jobs:
        profiles[sample["family"]].append(sample)
    if profile == "MIXED":
        assert set(profiles) == {"COMMAND_RESULT", "POT_VERSION", "POT_BALANCE"}
        assert all(len(items) * 3 == n for items in profiles.values())
    else:
        assert set(profiles) == {profile}
    publication_sql = [entry for entry in sql_rows[variant]
                       if "read_availability_" in entry["query"].lower()
                       or "c3_bench_" in entry["query"].lower()]
    counter = [entry for entry in publication_sql if is_counter(entry["query"])]
    counter_calls = sum(int(entry["calls"]) for entry in counter)
    counter_ms = sum(float(entry["exec_ms"]) for entry in counter)
    published = (n // 3 if profile == "MIXED" else n) if publish else 0
    assert counter_calls == published, (variant, counter_calls, published)

    per_family = {}
    for family, items in profiles.items():
        per_family[family] = {
            "n": len(items),
            "partitions": collections.Counter(item["partition"] for item in items if "partition" in item),
            "final_ms": quantiles([int(item["final_ns"]) / 1e6 for item in items]),
            "claim_and_final_ms": quantiles([int(item["claim_and_final_ns"]) / 1e6 for item in items]),
            "worker_cpu_ms_per_job": round(sum(int(item["worker_cpu_ns"]) for item in items) / 1e6 / len(items), 4),
        }
    observed_waits = lock_waits[variant]
    counter_waits = [entry["ms"] for entry in observed_waits if entry["counter"]]
    other_waits = [entry["ms"] for entry in observed_waits if not entry["counter"]]
    data = {
        **row, "n": n, "publish": publish, "workers": workers, "events": published,
        "jobs_per_second": round(n / wall_seconds, 4),
        "aggregates_per_family": per_family,
        "counter_sql_ms_per_update": round(counter_ms / counter_calls, 4) if counter_calls else None,
        "publication_sql_ms_per_event": round(sum(float(entry["exec_ms"]) for entry in publication_sql)
                                              / published, 4) if published else None,
        "publication_sql_wal_bytes_per_event": round(sum(int(entry["wal_bytes"]) for entry in publication_sql)
                                                     / published, 4) if published else None,
        "wait_samples": wait_samples[variant],
        "sql_wal_fpi": sum(int(entry["wal_fpi"]) for entry in sql_rows[variant])
        if all("wal_fpi" in entry for entry in sql_rows[variant]) else None,
        "sql_wal_records": sum(int(entry["wal_records"]) for entry in sql_rows[variant])
        if all("wal_records" in entry for entry in sql_rows[variant]) else None,
        "waits_ge_threshold": {
            "counter": {"count": len(counter_waits), "ms": quantiles(counter_waits),
                        "max_ms": max(counter_waits, default=None), "sum_ms": round(sum(counter_waits), 4)},
            "other": {"count": len(other_waits), "ms": quantiles(other_waits),
                      "max_ms": max(other_waits, default=None), "sum_ms": round(sum(other_waits), 4)},
        },
    }
    output.append(data)
    if publish:
        base = windows[profile, workers, False]
        assert int(base["n"]) == n
        added_jdbc = int(row["read_jdbc"]) - int(base["read_jdbc"])
        assert added_jdbc == 3 * published, (variant, added_jdbc)
        for column in ("write_jdbc", "read_tx_commit", "write_tx_commit", "read_tx_rollback", "write_tx_rollback"):
            assert row[column] == base[column], (variant, column)
        deltas.append({
            "profile": profile, "workers": workers, "events": published,
            "added_read_jdbc_per_event": added_jdbc / published,
            "wal_delta_bytes_per_event": (int(row["wal_bytes"]) - int(base["wal_bytes"])) / published,
            "added_transactions": 0,
        })

(ROOT / "cost-summary.json").write_text(json.dumps(
    {"windows": output, "paired_deltas": deltas}, ensure_ascii=False, indent=2) + "\n")
print(f"{len(output)} valid windows, {sum(row['n'] for row in output)} measured finalizations")
