#!/usr/bin/env python3
"""Plan or measure the authorized F01-E Registration → /me/pots observation."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

RATE = 1
WARMUP = 180
SAMPLE = 1_200
P95_BUDGET_MS = 500
POLL_SECONDS = .025
POLL_DEADLINE_SECONDS = 8
MAX_IN_FLIGHT = 12
MAX_OFFER_LAG_MS = 50


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def reserve(path):
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600),
                     "w", encoding="utf-8")


def dump(stream, data):
    json.dump(data, stream, indent=2, ensure_ascii=False, allow_nan=False)
    stream.write("\n")


def origin(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username or parsed.password or parsed.path not in ("", "/")
            or parsed.query or parsed.fragment):
        raise ValueError("provide a dedicated HTTP origin without credentials, path or query")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_call(base, token, path, *, binding=None, body=None, key=None):
    headers = {"Authorization": f"Bearer {token}"}
    if binding is not None:
        headers["X-Pocoma-Binding-Token"] = binding
    if body is not None:
        headers["Content-Type"] = "application/json"
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(
        base + "/api/v3" + path,
        data=None if body is None else json.dumps(body).encode(),
        headers=headers,
    )
    try:
        client = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
        with client.open(request, timeout=2) as response:
            payload = json.loads(response.read(65_536))
            return response.status, payload, False, time.monotonic_ns()
    except urllib.error.HTTPError as failure:
        status, maintenance = failure.code, bool(failure.headers.get("Retry-After"))
        failure.close()
        return status, None, maintenance, time.monotonic_ns()
    except (OSError, http.client.HTTPException, ValueError):
        return None, None, False, time.monotonic_ns()


def observe_one(write, read, token, key, request=http_call, clock=time.monotonic_ns,
                wait=time.sleep):
    sent = clock()
    status, payload, _, ack = request(write, token, "/identity/requests",
                                       body={"operation": "Registration"}, key=key)
    obs = {"admissionStatus": status, "admissionResponseMs": (ack - sent) / 1e6}
    if status != 202:
        return {**obs, "outcome": "ADMISSION_UNKNOWN" if status is None or status == 503 else "ADMISSION_REFUSED"}
    try:
        request_id = str(uuid.UUID(payload["requestId"]))
    except (TypeError, ValueError, KeyError, AttributeError):
        return {**obs, "outcome": "ADMISSION_UNKNOWN"}
    obs["requestId"] = request_id
    limit = ack + int(POLL_DEADLINE_SECONDS * 1e9)
    binding = None
    polls = Counter()
    while clock() < limit:
        path = "/me/binding" if binding is None else "/me/pots?limit=1"
        status, payload, maintenance, received = request(read, token, path, binding=binding)
        polls[f"{'binding' if binding is None else 'pots'}:{status}"] += 1
        obs["probes"] = dict(polls)
        if received > limit:
            return {**obs, "outcome": "CENSORED", "lowerMs": (limit - ack) / 1e6, "upperMs": None}
        if status == 200:
            if binding is None:
                if (not isinstance(payload, dict) or payload.get("active") is not True
                        or type(payload.get("euiVersion")) is not int or payload["euiVersion"] != 1
                        or not isinstance(payload.get("bindingToken"), str) or not payload["bindingToken"]):
                    return {**obs, "outcome": "UNEXPECTED_BINDING"}
                binding = payload["bindingToken"]
                obs["bindingSeenAfterAckMs"] = (received - ack) / 1e6
                continue
            if not isinstance(payload, dict) or payload.get("potIds") != [] or payload.get("nextCursor") is not None:
                return {**obs, "outcome": "UNEXPECTED_VISIBILITY"}
            return {**obs, "outcome": "AUTHORIZED",
                    "lowerMs": (received - ack) / 1e6, "upperMs": (received - sent) / 1e6}
        if status not in (404, 503) or maintenance:
            return {**obs, "outcome": "PROBE_ERROR"}
        remaining = limit - clock()
        if remaining > 0:
            wait(min(POLL_SECONDS, remaining / 1e9))
    return {**obs, "outcome": "CENSORED", "lowerMs": (limit - ack) / 1e6, "upperMs": None}


def nearest_rank(values, quantile):
    value = sorted(values)[math.ceil(quantile * len(values)) - 1]
    return None if math.isinf(value) else value


def summarize(rows):
    counts = Counter(row["outcome"] for row in rows)
    admitted = [row["requestId"] for row in rows if "requestId" in row]
    valid = (bool(rows) and len(admitted) == len(set(admitted)) == len(rows)
             and all(row["outcome"] in ("AUTHORIZED", "CENSORED")
                     and row["offerLagMs"] <= MAX_OFFER_LAG_MS for row in rows))
    if not valid:
        return {"status": "INCONCLUSIVE", "outcomes": dict(counts), "admitted": len(admitted)}
    bands = {}
    for q in (50, 95, 99):
        bands[f"p{q}"] = {
            "lowerMs": nearest_rank([row["lowerMs"] for row in rows], q / 100),
            "upperMs": nearest_rank([row["upperMs"] if row["upperMs"] is not None else math.inf
                                     for row in rows], q / 100),
        }
    p95 = bands["p95"]
    status = ("PASS" if p95["upperMs"] is not None and p95["upperMs"] <= P95_BUDGET_MS
              else "FAIL" if p95["lowerMs"] > P95_BUDGET_MS else "INCONCLUSIVE")
    return {"status": status, "outcomes": dict(counts), "admitted": len(admitted), "latencyBounds": bands}


def observe_sql(service, schema, statement):
    args = ["psql", "-X", "-q", "-t", "-A", "-w", "-v", "ON_ERROR_STOP=1"]
    sql = ("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
           f"SET LOCAL search_path = {schema}, public;\n"
           "SET LOCAL statement_timeout = '5s';\n" + statement + "\nCOMMIT;\n")
    response = subprocess.run(args, env={**os.environ, "PGSERVICE": service, "PGCONNECT_TIMEOUT": "5"},
                              input=sql, capture_output=True, text=True, timeout=12)
    if response.returncode:
        raise ValueError(f"{schema} read-only reconciliation failed ({response.returncode})")
    return json.loads(response.stdout)


def reconcile(write_service, read_service, requests):
    # UUIDs are canonicalized by the client before entering either read-only query.
    population = ",".join(f"'{request}'::uuid" for request in requests)
    target = f"WITH ids(request_id) AS (SELECT unnest(ARRAY[{population}])) "
    write = observe_sql(write_service, "v3_write", target + """
        SELECT json_build_object('complete',count(*),'distinctUsers',count(distinct v.user_id),
            'userDigest',encode(sha256(convert_to(string_agg(q.request_id::text||':'||v.user_id::text,
                ',' ORDER BY q.request_id),'UTF8')),'hex'))
        FROM ids JOIN v3_identity_request q USING (request_id)
        JOIN v3_identity_request_result r USING (request_id)
        JOIN v3_eui_version v ON v.eui_id=r.target_eui_id AND v.version=r.target_version
        JOIN v3_identity_completion_event e ON e.subject_id=q.request_id
        JOIN v3_event_position p ON p.event_id=e.event_id AND p.family='IDENTITY_COMPLETION'
        WHERE q.status='TERMINAL' AND q.operation='REGISTRATION'
          AND r.outcome='CHANGED' AND r.target_version=1 AND v.user_id IS NOT NULL
          AND NOT EXISTS(SELECT 1 FROM consumption_slot s WHERE s.consumable_id=q.request_id);
        """)
    read = observe_sql(read_service, "v3_read", target + """
        SELECT json_build_object('complete',count(*),'distinctUsers',count(distinct b.user_id),
            'userDigest',encode(sha256(convert_to(string_agg(r.request_id::text||':'||b.user_id::text,
                ',' ORDER BY r.request_id),'UTF8')),'hex'))
        FROM ids JOIN read_identity_request_result r USING(request_id)
        JOIN read_current_binding b ON b.issuer=r.requester_issuer AND b.subject=r.requester_subject
        WHERE r.operation='REGISTRATION' AND r.write_outcome='CHANGED'
          AND r.target_version=1 AND b.eui_version=1 AND b.user_id IS NOT NULL AND b.binding_token IS NOT NULL;
        """)
    complete = (write == read and write["complete"] == len(requests)
                and write["distinctUsers"] == len(requests) and write["userDigest"] is not None)
    return {"write": write, "read": read, "complete": complete}


def schedule(write, read, tokens, run_id, observations):
    base = time.monotonic()
    pending = []

    def attempt(token, key, deadline):
        lag = (time.monotonic() - deadline) * 1000
        if lag > MAX_OFFER_LAG_MS:
            return {"outcome": "DROPPED_OFFER", "offerLagMs": lag}
        return {**observe_one(write, read, token, key), "offerLagMs": lag}

    with ThreadPoolExecutor(max_workers=MAX_IN_FLIGHT) as pool:
        for i, (subject, token) in enumerate(tokens.items()):
            deadline = base + i / RATE
            time.sleep(max(0, deadline - time.monotonic()))
            lag = (time.monotonic() - deadline) * 1000
            inflight = sum(not future.done() for _, future in pending)
            if inflight >= MAX_IN_FLIGHT or lag > MAX_OFFER_LAG_MS:
                observations[i] = {"outcome": "DROPPED_OFFER", "offerLagMs": lag}
                continue
            key = str(uuid.uuid5(run_id, subject))
            pending.append((i, pool.submit(attempt, token, key, deadline)))
        for i, future in pending:
            observations[i] = future.result()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--subjects", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--run-id", required=True, type=uuid.UUID)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--isolated-target", action="store_true")
    parser.add_argument("--tokens", type=Path)
    parser.add_argument("--write-url")
    parser.add_argument("--read-url")
    parser.add_argument("--write-service")
    parser.add_argument("--read-service")
    args = parser.parse_args()
    subjects = [str(uuid.uuid5(args.run_id, f"F01-E:{i}")) for i in range(WARMUP + SAMPLE)]
    receipt = {"status": "STATIC_PLAN_ONLY", "startedAt": utc_now(), "runId": str(args.run_id),
               "rate": RATE, "warmupSeconds": WARMUP, "sampleSeconds": SAMPLE,
               "subjects": len(subjects), "p95BudgetMs": P95_BUDGET_MS, "pollIntervalMs": POLL_SECONDS*1000,
               "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "observer": "POST Registration / CurrentBinding / GET /me/pots?limit=1 with new binding"}
    if args.subjects.resolve() == args.report.resolve():
        raise ValueError("subjects and report must use different paths")
    if not args.execute:
        if args.report.exists() or not args.report.parent.is_dir():
            raise ValueError("choose a new report path in an existing directory")
        with reserve(args.subjects) as output:
            dump(output, subjects)
        with reserve(args.report) as output:
            dump(output, receipt)
        return 0
    if not all((args.isolated_target, args.tokens, args.write_url, args.read_url,
                args.write_service, args.read_service)):
        raise ValueError("execution requires a disposable target, isolated URLs, two SQL services and issuer token file")
    if json.loads(args.subjects.read_text()) != subjects:
        raise ValueError("wrong or reused subject file; plan from a new run ID")
    if stat.S_IMODE(args.tokens.stat().st_mode) & 0o077:
        raise ValueError("issuer token file must be private to its owner")
    tokens = json.loads(args.tokens.read_text())
    if (not isinstance(tokens, dict) or tokens.keys() != set(subjects)
            or any(not isinstance(token, str) or not token for token in tokens.values())
            or len(set(tokens.values())) != len(subjects)):
        raise ValueError("token population must exactly match unique planned subjects")
    write, read = origin(args.write_url), origin(args.read_url)
    if write == read or args.write_service == args.read_service:
        raise ValueError("require separate WRITE/READ endpoints and SQL services")
    with reserve(args.report) as output:
        try:
            receipt["status"] = "INCONCLUSIVE"
            fresh = Counter(http_call(read, token, "/me/binding")[0] for token in tokens.values())
            receipt["preflightStatuses"] = {str(status): count for status, count in fresh.items()}
            if fresh != {404: len(subjects)}:
                raise ValueError("Fresh, authenticated EUI population not established")
            rows = [{"outcome": "NOT_ATTEMPTED"} for _ in subjects]
            schedule(write, read, {subject: tokens[subject] for subject in subjects}, args.run_id, rows)
            receipt["observations"] = rows
            warmup = summarize(rows[:WARMUP])
            sample = summarize(rows[WARMUP:])
            receipt.update(warmup=warmup, sample=sample)
            requests = [row["requestId"] for row in rows if "requestId" in row]
            if len(set(requests)) == len(subjects):
                deadline = time.monotonic() + 30
                while True:
                    reconciled = reconcile(args.write_service, args.read_service, requests)
                    receipt["sql"] = reconciled
                    if reconciled["complete"] or time.monotonic() >= deadline:
                        break
                    time.sleep(.25)
                if reconciled["complete"] and warmup["status"] != "INCONCLUSIVE":
                    receipt["status"] = sample["status"]
        finally:
            receipt["finishedAt"] = utc_now()
            dump(output, receipt)
    return {"PASS": 0, "FAIL": 1, "INCONCLUSIVE": 2}[receipt["status"]]


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError, AttributeError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        sys.exit(2)
