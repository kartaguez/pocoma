#!/usr/bin/env python3
"""Plan or measure the authorized F01-E Registration → /me/pots observation."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
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


def _has_valid_origin_parts(parsed):
    if parsed.scheme not in ("http", "https"):
        return False
    if not parsed.hostname:
        return False
    if parsed.username or parsed.password:
        return False
    if parsed.path not in ("", "/"):
        return False
    return not parsed.query and not parsed.fragment


def origin(value):
    parsed = urllib.parse.urlsplit(value)
    if not _has_valid_origin_parts(parsed):
        raise ValueError("provide a dedicated HTTP origin without credentials, path or query")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


@dataclass(frozen=True)
class TargetUrls:
    write: str
    read: str


@dataclass(frozen=True)
class HttpOptions:
    binding: str | None = None
    body: dict | None = None
    key: str | None = None


def http_call(base, token, path, options=None):
    opts = options or HttpOptions()
    headers = {"Authorization": f"Bearer {token}"}
    if opts.binding is not None:
        headers["X-Pocoma-Binding-Token"] = opts.binding
    if opts.body is not None:
        headers["Content-Type"] = "application/json"
        headers["Idempotency-Key"] = opts.key
    request = urllib.request.Request(
        base + "/api/v3" + path,
        data=None if opts.body is None else json.dumps(opts.body).encode(),
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


@dataclass(frozen=True)
class ProbeDriver:
    request: object = http_call
    clock: object = time.monotonic_ns
    wait: object = time.sleep


def _is_valid_binding_payload(payload):
    if not isinstance(payload, dict) or payload.get("active") is not True:
        return False
    if type(payload.get("euiVersion")) is not int or payload["euiVersion"] != 1:
        return False
    return isinstance(payload.get("bindingToken"), str) and bool(payload["bindingToken"])


def _is_empty_pots_payload(payload):
    return isinstance(payload, dict) and payload.get("potIds") == [] and payload.get("nextCursor") is None


@dataclass(frozen=True)
class ProbeContext:
    read: str
    token: str
    sent: int
    ack: int
    driver: ProbeDriver


def _admit_registration(write, token, key, driver):
    sent = driver.clock()
    if driver.request is http_call:
        status, payload, _, ack = driver.request(
            write, token, "/identity/requests", HttpOptions(body={"operation": "Registration"}, key=key)
        )
    else:
        status, payload, _, ack = driver.request(
            write, token, "/identity/requests", body={"operation": "Registration"}, key=key
        )
    obs = {"admissionStatus": status, "admissionResponseMs": (ack - sent) / 1e6}
    if status != 202:
        outcome = "ADMISSION_UNKNOWN" if status is None or status == 503 else "ADMISSION_REFUSED"
        return None, obs, {**obs, "outcome": outcome}
    try:
        request_id = str(uuid.UUID(payload["requestId"]))
    except (TypeError, ValueError, KeyError, AttributeError):
        return None, obs, {**obs, "outcome": "ADMISSION_UNKNOWN"}
    obs["requestId"] = request_id
    return ProbeContext("", token, sent, ack, driver), obs, None


def _handle_poll_200(binding, resp, obs, ctx):
    _, payload, _, received = resp
    if binding is None:
        if not _is_valid_binding_payload(payload):
            return None, {**obs, "outcome": "UNEXPECTED_BINDING"}
        obs["bindingSeenAfterAckMs"] = (received - ctx.ack) / 1e6
        return payload["bindingToken"], None
    if not _is_empty_pots_payload(payload):
        return binding, {**obs, "outcome": "UNEXPECTED_VISIBILITY"}
    return binding, {
        **obs,
        "outcome": "AUTHORIZED",
        "lowerMs": (received - ctx.ack) / 1e6,
        "upperMs": (received - ctx.sent) / 1e6,
    }


def _poll_step(ctx, binding, obs, polls, limit):
    path = "/me/binding" if binding is None else "/me/pots?limit=1"
    if ctx.driver.request is http_call:
        resp = ctx.driver.request(ctx.read, ctx.token, path, HttpOptions(binding=binding))
    else:
        resp = ctx.driver.request(ctx.read, ctx.token, path, binding=binding)
    status, _, maintenance, received = resp
    polls[f"{'binding' if binding is None else 'pots'}:{status}"] += 1
    obs["probes"] = dict(polls)
    if received > limit:
        return binding, {**obs, "outcome": "CENSORED", "lowerMs": (limit - ctx.ack) / 1e6, "upperMs": None}, False
    if status == 200:
        new_binding, done = _handle_poll_200(binding, resp, obs, ctx)
        return new_binding, done, done is None
    if status not in (404, 503) or maintenance:
        return binding, {**obs, "outcome": "PROBE_ERROR"}, False
    return binding, None, False


def _poll_visibility(ctx, obs):
    limit = ctx.ack + int(POLL_DEADLINE_SECONDS * 1e9)
    binding = None
    polls = Counter()
    while ctx.driver.clock() < limit:
        binding, done, immediate = _poll_step(ctx, binding, obs, polls, limit)
        if done is not None:
            return done
        if immediate:
            continue
        remaining = limit - ctx.driver.clock()
        if remaining > 0:
            ctx.driver.wait(min(POLL_SECONDS, remaining / 1e9))
    return {**obs, "outcome": "CENSORED", "lowerMs": (limit - ctx.ack) / 1e6, "upperMs": None}


def observe_one(*args, **kwargs):
    if isinstance(args[0], TargetUrls):
        targets, token, key = args[:3]
        active_driver = args[3] if len(args) > 3 else kwargs.get("driver") or ProbeDriver()
    else:
        write, read, token, key = args[:4]
        targets = TargetUrls(write, read)
        active_driver = ProbeDriver(
            request=args[4] if len(args) > 4 else kwargs.get("request", http_call),
            clock=args[5] if len(args) > 5 else kwargs.get("clock", time.monotonic_ns),
            wait=args[6] if len(args) > 6 else kwargs.get("wait", time.sleep),
        )
    ctx, obs, failure = _admit_registration(targets.write, token, key, active_driver)
    if failure is not None:
        return failure
    poll_ctx = ProbeContext(targets.read, token, ctx.sent, ctx.ack, active_driver)
    return _poll_visibility(poll_ctx, obs)


def nearest_rank(values, quantile):
    value = sorted(values)[math.ceil(quantile * len(values)) - 1]
    return None if math.isinf(value) else value


def _is_valid_population(rows, admitted):
    if not rows:
        return False
    if len(admitted) != len(set(admitted)):
        return False
    if len(admitted) != len(rows):
        return False
    for row in rows:
        if row["outcome"] not in ("AUTHORIZED", "CENSORED"):
            return False
        if row["offerLagMs"] > MAX_OFFER_LAG_MS:
            return False
    return True


def _latency_status(p95):
    if p95["upperMs"] is not None and p95["upperMs"] <= P95_BUDGET_MS:
        return "PASS"
    return "FAIL" if p95["lowerMs"] > P95_BUDGET_MS else "INCONCLUSIVE"


def summarize(rows):
    counts = Counter(row["outcome"] for row in rows)
    admitted = [row["requestId"] for row in rows if "requestId" in row]
    if not _is_valid_population(rows, admitted):
        return {"status": "INCONCLUSIVE", "outcomes": dict(counts), "admitted": len(admitted)}
    upper_values = [row["upperMs"] if row["upperMs"] is not None else math.inf for row in rows]
    lower_values = [row["lowerMs"] for row in rows]
    bands = {
        f"p{q}": {
            "lowerMs": nearest_rank(lower_values, q / 100),
            "upperMs": nearest_rank(upper_values, q / 100),
        }
        for q in (50, 95, 99)
    }
    return {
        "status": _latency_status(bands["p95"]),
        "outcomes": dict(counts),
        "admitted": len(admitted),
        "latencyBounds": bands,
    }


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


def schedule(targets, tokens, run_id, observations):
    base = time.monotonic()
    pending = []

    def attempt(token, key, deadline):
        lag = (time.monotonic() - deadline) * 1000
        if lag > MAX_OFFER_LAG_MS:
            return {"outcome": "DROPPED_OFFER", "offerLagMs": lag}
        return {**observe_one(targets, token, key), "offerLagMs": lag}

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


def _build_parser():
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
    return parser


def _write_static_plan(args, subjects, receipt):
    if args.report.exists() or not args.report.parent.is_dir():
        raise ValueError("choose a new report path in an existing directory")
    with reserve(args.subjects) as output:
        dump(output, subjects)
    with reserve(args.report) as output:
        dump(output, receipt)


def _validate_token_values(tokens, subjects):
    for token in tokens.values():
        if not isinstance(token, str) or not token:
            raise ValueError("token population must exactly match unique planned subjects")
    if len(set(tokens.values())) != len(subjects):
        raise ValueError("token population must exactly match unique planned subjects")


def _load_validated_tokens(tokens_path, subjects):
    if stat.S_IMODE(tokens_path.stat().st_mode) & 0o077:
        raise ValueError("issuer token file must be private to its owner")
    tokens = json.loads(tokens_path.read_text())
    if not isinstance(tokens, dict) or tokens.keys() != set(subjects):
        raise ValueError("token population must exactly match unique planned subjects")
    _validate_token_values(tokens, subjects)
    return tokens


def _validate_execution_inputs(args, subjects):
    if not all((args.isolated_target, args.tokens, args.write_url, args.read_url,
                args.write_service, args.read_service)):
        raise ValueError("execution requires a disposable target, isolated URLs, two SQL services and issuer token file")
    if json.loads(args.subjects.read_text()) != subjects:
        raise ValueError("wrong or reused subject file; plan from a new run ID")
    tokens = _load_validated_tokens(args.tokens, subjects)
    targets = TargetUrls(origin(args.write_url), origin(args.read_url))
    if targets.write == targets.read or args.write_service == args.read_service:
        raise ValueError("require separate WRITE/READ endpoints and SQL services")
    return tokens, targets


def _await_reconciliation(write_service, read_service, requests):
    deadline = time.monotonic() + 30
    while True:
        reconciled = reconcile(write_service, read_service, requests)
        if reconciled["complete"] or time.monotonic() >= deadline:
            return reconciled
        time.sleep(.25)


@dataclass(frozen=True)
class ExecutionContext:
    subjects: list
    tokens: dict
    targets: TargetUrls


def _verify_fresh_population(read_url, tokens, expected_count, receipt):
    fresh = Counter(http_call(read_url, token, "/me/binding")[0] for token in tokens.values())
    receipt["preflightStatuses"] = {str(status): count for status, count in fresh.items()}
    if fresh != {404: expected_count}:
        raise ValueError("Fresh, authenticated EUI population not established")


def _run_execution(args, exec_ctx, receipt):
    receipt["status"] = "INCONCLUSIVE"
    _verify_fresh_population(exec_ctx.targets.read, exec_ctx.tokens, len(exec_ctx.subjects), receipt)
    rows = [{"outcome": "NOT_ATTEMPTED"} for _ in exec_ctx.subjects]
    token_map = {subject: exec_ctx.tokens[subject] for subject in exec_ctx.subjects}
    schedule(exec_ctx.targets, token_map, args.run_id, rows)
    receipt["observations"] = rows
    warmup = summarize(rows[:WARMUP])
    sample = summarize(rows[WARMUP:])
    receipt.update(warmup=warmup, sample=sample)
    requests = [row["requestId"] for row in rows if "requestId" in row]
    if len(set(requests)) == len(exec_ctx.subjects):
        reconciled = _await_reconciliation(args.write_service, args.read_service, requests)
        receipt["sql"] = reconciled
        if reconciled["complete"] and warmup["status"] != "INCONCLUSIVE":
            receipt["status"] = sample["status"]


def main():
    args = _build_parser().parse_args()
    subjects = [str(uuid.uuid5(args.run_id, f"F01-E:{i}")) for i in range(WARMUP + SAMPLE)]
    receipt = {"status": "STATIC_PLAN_ONLY", "startedAt": utc_now(), "runId": str(args.run_id),
               "rate": RATE, "warmupSeconds": WARMUP, "sampleSeconds": SAMPLE,
               "subjects": len(subjects), "p95BudgetMs": P95_BUDGET_MS, "pollIntervalMs": POLL_SECONDS*1000,
               "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "observer": "POST Registration / CurrentBinding / GET /me/pots?limit=1 with new binding"}
    if args.subjects.resolve() == args.report.resolve():
        raise ValueError("subjects and report must use different paths")
    if not args.execute:
        _write_static_plan(args, subjects, receipt)
        return 0
    tokens, targets = _validate_execution_inputs(args, subjects)
    with reserve(args.report) as output:
        try:
            _run_execution(args, ExecutionContext(subjects, tokens, targets), receipt)
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
