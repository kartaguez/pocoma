#!/usr/bin/env python3
"""Plan or admit the fixed, independently verified 10,000-command F01-G stock."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

POPULATION = 10_000
REQUEST_TIMEOUT_SECONDS = 10
MAX_CONCURRENCY = 16


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def parse_fixtures(raw):
    data = json.loads(raw)
    if not isinstance(data, list) or len(data) != POPULATION:
        raise ValueError(f"expected exactly {POPULATION} verified Pot fixtures")
    fixtures = []
    seen = set()
    for entry in data:
        pot = str(uuid.UUID(entry["potId"]))
        version, expenses = entry["version"], entry["expenses"]
        members = tuple(str(uuid.UUID(value)) for value in entry["shareholders"])
        if (pot in seen or type(version) is not int or version < 1 or type(expenses) is not int or expenses != 200
                or len(members) != 20 or len(set(members)) != 20):
            raise ValueError(f"invalid or duplicate medium Pot fixture: {pot}")
        seen.add(pot)
        fixtures.append((pot, version, members))
    return fixtures


def command(run_id, fixture, binding):
    pot, version, members = fixture
    key = str(uuid.uuid5(run_id, f"admission:{pot}"))
    payload = {
        "id": str(uuid.uuid5(run_id, f"expense:{pot}")),
        "label": "SPEC-09 backlog",
        "date": "2026-11-06",
        "amount": {"numerator": "1", "denominator": "1"},
        "payerId": members[0],
        "participants": [{"id": member, "weight": None} for member in members],
    }
    return key, {
        "operation": "CreateExpense", "potId": pot, "observedVersion": version,
        "bindingToken": binding, "payload": payload,
    }


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def write_endpoint(value):
    uri = urllib.parse.urlsplit(value)
    if (uri.scheme not in ("http", "https") or not uri.hostname or uri.username or uri.password
            or uri.path not in ("", "/") or uri.query or uri.fragment):
        raise ValueError("provide a dedicated WRITE origin without credentials, path or query")
    return urllib.parse.urlunsplit((uri.scheme, uri.netloc, "/api/v3/commands", "", ""))


def submit_one(endpoint, run_id, fixture, token, binding):
    key, body = command(run_id, fixture, binding)
    req = urllib.request.Request(
        endpoint, data=json.dumps(body, separators=(",", ":")).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}",
                 "Idempotency-Key": key},
    )
    receipt = {"potId": fixture[0], "observedVersion": fixture[1], "idempotencyKey": key}
    try:
        # No redirects or proxies: the supplied test credentials go only to the designated origin.
        opener = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
        with opener.open(req, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            receipt["httpStatus"] = response.status
            if response.status == 202:
                payload = json.loads(response.read(65_536))
                receipt["requestId"] = str(uuid.UUID(payload["requestId"]))
    except urllib.error.HTTPError as failure:
        receipt["httpStatus"] = failure.code
        failure.close()
    except (OSError, http.client.HTTPException, ValueError, KeyError, TypeError, AttributeError):
        receipt["outcome"] = "UNKNOWN_ADMISSION"
    return receipt


def collect(fixtures, concurrency, submit, observed):
    seen = set()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        for offset in range(0, len(fixtures), concurrency):
            # Finish and retain every already issued request. Stop before issuing another batch
            # if this batch contained a refusal, an unknown admission, or a duplicate ID.
            batch = list(pool.map(submit, fixtures[offset:offset + concurrency]))
            observed.extend(batch)
            for entry in batch:
                request = entry.get("requestId")
                if entry.get("httpStatus") != 202 or not request or request in seen:
                    return None
                seen.add(request)
    return sorted(seen)


def reserve(path):
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600),
                     "w", encoding="utf-8")


def dump(stream, data):
    json.dump(data, stream, indent=2, ensure_ascii=False)
    stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--execute-stock", action="store_true")
    parser.add_argument("--isolated-and-workers-stopped", action="store_true")
    parser.add_argument("--write-url")
    parser.add_argument("--run-id", type=uuid.UUID)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--requests", type=Path)
    args = parser.parse_args()
    raw = args.fixtures.read_bytes()
    fixtures = parse_fixtures(raw)
    receipt = {
        "status": "STATIC_PLAN_ONLY", "fixturesSha256": hashlib.sha256(raw).hexdigest(),
        "population": POPULATION, "operation": "CreateExpense",
        "producerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "maxConcurrentAdmissions": args.concurrency, "startedAt": utc_now(),
    }
    if not 1 <= args.concurrency <= MAX_CONCURRENCY:
        raise ValueError(f"concurrency must be between 1 and {MAX_CONCURRENCY}")
    if not args.execute_stock:
        with reserve(args.report) as out:
            dump(out, receipt)
        return 0
    if not all((args.isolated_and_workers_stopped, args.write_url, args.run_id, args.requests)):
        raise ValueError("admission requires explicit isolated target, stopped Workers, WRITE origin, run ID and inventory path")
    if (args.report.resolve() == args.requests.resolve()
            or args.requests.exists() or not args.requests.parent.is_dir()):
        raise ValueError("use a separate, new inventory file in an existing evidence directory")
    endpoint = write_endpoint(args.write_url)
    token, binding = os.environ.get("POCOMA_TOKEN"), os.environ.get("POCOMA_BINDING_TOKEN")
    if not token or not binding:
        raise ValueError("set POCOMA_TOKEN and POCOMA_BINDING_TOKEN in the environment")
    observed = []
    started = time.monotonic()
    receipt.update(status="INCOMPLETE", writeEndpoint=endpoint, runId=str(args.run_id))
    with reserve(args.report) as out:
        try:
            identifiers = collect(
                fixtures, args.concurrency,
                lambda fixture: submit_one(endpoint, args.run_id, fixture, token, binding),
                observed,
            )
            if identifiers is not None and len(identifiers) == POPULATION:
                with reserve(args.requests) as inventory:
                    dump(inventory, identifiers)
                receipt.update(status="ADMITTED",
                               inventorySha256=hashlib.sha256(args.requests.read_bytes()).hexdigest())
        finally:
            receipt.update(finishedAt=utc_now(), seconds=time.monotonic()-started, admissions=observed)
            dump(out, receipt)
    return 0 if receipt["status"] == "ADMITTED" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        sys.exit(str(error))
