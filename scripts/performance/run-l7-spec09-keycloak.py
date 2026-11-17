#!/usr/bin/env python3
"""Prepare or observe the isolated Keycloak PKCE and Identity profile F01-F."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import secrets
import stat
import sys
import time
import urllib.parse
import uuid


ROOT = Path(__file__).resolve().parents[2]
WARMUP = 180
SAMPLE = 600
RATE = 1
OBSERVERS = 12
LAG_LIMIT_MS = 50


def load_script(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def private_file(path):
    if stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError("Keycloak credentials must be private to their owner")
    return json.loads(path.read_text())


def prepare(template, subjects, realm_name, password=secrets.token_urlsafe):
    if not re.fullmatch(r"pocoma-spec09-[a-f0-9-]{8,36}", realm_name):
        raise ValueError("require a fresh, dedicated pocoma-spec09 realm name")
    ids = [str(uuid.UUID(value)) for value in subjects]
    if len(ids) < WARMUP + SAMPLE or len(set(ids)) != len(ids):
        raise ValueError("Keycloak fixture needs at least 780 distinct EUI subjects")
    realm = json.loads(json.dumps(template))
    client, = realm["clients"]
    if (not client["standardFlowEnabled"] or client["directAccessGrantsEnabled"]
            or client["attributes"].get("pkce.code.challenge.method") != "S256"):
        raise ValueError("Keycloak fixture requires the existing authorization code and PKCE client")
    realm.update(realm=realm_name, users=[], accessTokenLifespan=7_200)
    accounts = []
    for i, subject in enumerate(ids):
        user = f"spec09-{i:04d}-{subject[:8]}"
        secret = password(28)
        accounts.append({"subject": subject, "username": user, "password": secret})
        realm["users"].append({
            "id": subject, "username": user, "firstName": "SPEC-09", "lastName": f"Fixture {i}",
            "email": f"{user}@pocoma.invalid", "emailVerified": True, "enabled": True,
            "requiredActions": [], "credentials": [{"type": "password", "value": secret, "temporary": False}],
        })
    return realm, accounts


def validate_issuer(value):
    issuer = urllib.parse.urlsplit(value)
    if (issuer.scheme not in ("http", "https") or not issuer.hostname or issuer.username or issuer.password
            or issuer.query or issuer.fragment
            or not re.fullmatch(r"/realms/pocoma-spec09-[a-f0-9-]{8,36}", issuer.path)):
        raise ValueError("require an isolated HTTP(S) Keycloak pocoma-spec09 realm issuer")
    return value


def login(auth, account, clock=time.monotonic_ns):
    begin = clock()
    callback, verifier = auth.authorize(["openid"], username=account["username"], password=account["password"])
    authorized = clock()
    token, claims = auth.exchange(callback, verifier)
    exchanged = clock()
    auth.check_token(claims, set())
    if claims["sub"] != account["subject"]:
        raise ValueError("Keycloak changed the preplanned test subject")
    return token, {"authorizeMs": (authorized - begin)/1e6, "exchangeMs": (exchanged - authorized)/1e6}


def require_result(identity, read, token, request, version):
    path = f"/identity/requests/{request}/result"
    deadline = time.monotonic()+identity.POLL_DEADLINE_SECONDS
    while time.monotonic() < deadline:
        status, body, maintenance, _ = identity.http_call(read, token, path)
        if status == 200:
            return isinstance(body, dict) and body.get("outcome") == "COMPLETED" and body.get("targetVersion") == version
        if maintenance or status not in (404, 503):
            return False
        time.sleep(identity.POLL_SECONDS)
    return False


def unlink(identity, write, read, token, binding, issuer, subject, key):
    sent = time.monotonic_ns()
    status, body, _, ack = identity.http_call(write, token, "/identity/requests", key=key, body={
        "operation": "Unlink", "bindingToken": binding, "target": {"issuer": issuer, "subject": subject},
    })
    if status != 202:
        return {"outcome": "UNLINK_ADMISSION_NOT_CONFIRMED", "admissionStatus": status}
    request = str(uuid.UUID(body["requestId"]))
    limit = ack + int(identity.POLL_DEADLINE_SECONDS * 1e9)
    while time.monotonic_ns() < limit:
        status, body, maintenance, observed = identity.http_call(read, token, "/me/binding")
        if status == 200 and isinstance(body, dict) and body.get("active") is False and body.get("euiVersion") == 2:
            after, _, _, denied = identity.http_call(read, token, "/me/pots?limit=1", binding=binding)
            if after == 404 and denied <= limit:
                return {"outcome": "UNLINK_OBSERVED", "requestId": request,
                        "bindingInactiveMs": (observed-ack)/1e6,
                        "lowerMs": (denied-ack)/1e6, "upperMs": (denied-sent)/1e6}
            return {"outcome": "OLD_BINDING_ACCEPTED" if after == 200 else "OLD_BINDING_NOT_PROVEN_DENIED",
                    "httpStatus": after, "requestId": request}
        if status not in (200, 404, 503) or maintenance:
            break
        time.sleep(identity.POLL_SECONDS)
    return {"outcome": "UNLINK_NOT_OBSERVED", "requestId": request}


def observe(auth, identity, account, write, read, run_id):
    facts = {}
    try:
        token, facts["oidc"] = login(auth, account)
        if identity.http_call(read, token, "/me/binding")[0] != 404:
            return {**facts, "outcome": "EUI_NOT_FRESH"}
        subject = account["subject"]
        facts["registration"] = identity.observe_one(write, read, token, str(uuid.uuid5(run_id, "register:"+subject)))
        if facts["registration"]["outcome"] != "AUTHORIZED":
            return {**facts, "outcome": "REGISTRATION_NOT_AUTHORIZED"}
        status, binding, _, _ = identity.http_call(read, token, "/me/binding")
        if status != 200 or not isinstance(binding, dict) or not binding.get("bindingToken"):
            return {**facts, "outcome": "BINDING_NOT_AVAILABLE_FOR_UNLINK"}
        wrong_status = identity.http_call(read, token, "/me/pots?limit=1", binding="invalid-test-binding")[0]
        if wrong_status != 404:
            return {**facts, "outcome": "WRONG_BINDING_ACCEPTED" if wrong_status == 200 else "WRONG_BINDING_NOT_PROVEN_DENIED",
                    "httpStatus": wrong_status}
        facts["unlink"] = unlink(identity, write, read, token, binding["bindingToken"],
                                 auth.ISSUER, subject, str(uuid.uuid5(run_id, "unlink:"+subject)))
        if facts["unlink"]["outcome"] != "UNLINK_OBSERVED":
            return {**facts, "outcome": facts["unlink"]["outcome"]}
        if not require_result(identity, read, token, facts["registration"]["requestId"], 1):
            return {**facts, "outcome": "REGISTRATION_RESULT_MISSING"}
        if not require_result(identity, read, token, facts["unlink"]["requestId"], 2):
            return {**facts, "outcome": "UNLINK_RESULT_MISSING"}
        return {**facts, "outcome": "OBSERVED"}
    except (RuntimeError, OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        return {**facts, "outcome": "INCONCLUSIVE", "errorKind": type(error).__name__}


def reconcile(identity, write_service, read_service, rows):
    registrations = [row["registration"]["requestId"] for row in rows]
    unlinks = [row["unlink"]["requestId"] for row in rows]
    request_ids = registrations + unlinks
    if len(set(request_ids)) != len(request_ids):
        return {"complete": False, "reason": "duplicate_request"}
    population = ",".join(f"('{register}'::uuid,'{unlink}'::uuid)"
                          for register,unlink in zip(registrations,unlinks))
    pairs = f"WITH pairs(reg,rev) AS (VALUES {population}) "
    write = identity.observe_sql(write_service, "v3_write", pairs + """
        SELECT json_build_object('pairs',count(*),'euis',count(distinct a.target_eui_id))
        FROM pairs
        JOIN v3_identity_request q1 ON q1.request_id=pairs.reg
        JOIN v3_identity_request q2 ON q2.request_id=pairs.rev
        JOIN v3_identity_request_result a ON a.request_id=q1.request_id
        JOIN v3_identity_request_result b ON b.request_id=q2.request_id
        JOIN v3_eui_version v1 ON v1.eui_id=a.target_eui_id AND v1.version=1
        JOIN v3_eui_version v2 ON v2.eui_id=b.target_eui_id AND v2.version=2
        WHERE q1.operation='REGISTRATION' AND q2.operation='UNLINK'
          AND q1.status='TERMINAL' AND q2.status='TERMINAL'
          AND a.outcome='CHANGED' AND b.outcome='CHANGED' AND a.target_eui_id=b.target_eui_id
          AND a.target_version=1 AND b.target_version=2 AND v1.user_id IS NOT NULL AND v2.user_id IS NULL
          AND NOT EXISTS(SELECT 1 FROM consumption_slot s WHERE s.consumable_id IN (pairs.reg,pairs.rev));
        """)
    read = identity.observe_sql(read_service, "v3_read", pairs + """
        SELECT json_build_object('pairs',count(*),'euis',count(distinct b.eui_id))
        FROM pairs
        JOIN read_identity_request_result r1 ON r1.request_id=pairs.reg
        JOIN read_identity_request_result r2 ON r2.request_id=pairs.rev
        JOIN read_current_binding b ON b.issuer=r1.requester_issuer AND b.subject=r1.requester_subject
        WHERE r1.operation='REGISTRATION' AND r2.operation='UNLINK'
          AND r1.write_outcome='CHANGED' AND r2.write_outcome='CHANGED'
          AND r1.requester_issuer=r2.requester_issuer AND r1.requester_subject=r2.requester_subject
          AND r1.target_version=1 AND r2.target_version=2 AND b.eui_version=2
          AND b.user_id IS NULL AND b.binding_token IS NULL;
        """)
    expected = {"pairs": len(rows), "euis": len(rows)}
    return {"write":write, "read":read, "complete":write==read==expected}

def sample(identity, auth, accounts, write, read, run_id, rows):
    begin = time.monotonic()
    pending = []

    def start(account, at):
        lag = (time.monotonic()-at)*1000
        if lag > LAG_LIMIT_MS:
            return {"outcome": "DROPPED_OFFER", "offerLagMs": lag}
        return {**observe(auth, identity, account, write, read, run_id), "offerLagMs": lag}

    with ThreadPoolExecutor(max_workers=OBSERVERS) as pool:
        for i, account in enumerate(accounts):
            at = begin+i/RATE
            time.sleep(max(0, at-time.monotonic()))
            if sum(not task.done() for _, task in pending) >= OBSERVERS:
                rows[i] = {"outcome": "DROPPED_OFFER"}
                continue
            pending.append((i,pool.submit(start, account, at)))
        for i, task in pending:
            rows[i] = task.result()


def summarize(identity, rows):
    counts = Counter(row["outcome"] for row in rows)
    observed = [row for row in rows if row["outcome"] == "OBSERVED"]
    names = [("oidc","authorizeMs"), ("oidc","exchangeMs"),
             ("registration","lowerMs"), ("registration","upperMs"),
             ("unlink","lowerMs"), ("unlink","upperMs")]
    quantiles = {f"{stage}.{metric}": {f"p{q}": identity.nearest_rank([row[stage][metric] for row in observed],q/100)
                                     for q in (50,95,99)}
                 for stage,metric in names} if observed else {}
    return {"outcomes": dict(counts), "observed": len(observed), "offered": len(rows), "ms": quantiles}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "execute", "issue-tokens"))
    parser.add_argument("--subjects", type=Path)
    parser.add_argument("--realm")
    parser.add_argument("--realm-file", type=Path)
    parser.add_argument("--accounts", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--tokens", type=Path)
    parser.add_argument("--issuer")
    parser.add_argument("--write-url")
    parser.add_argument("--read-url")
    parser.add_argument("--write-service")
    parser.add_argument("--read-service")
    parser.add_argument("--run-id", required=True, type=uuid.UUID)
    parser.add_argument("--isolated-target", action="store_true")
    args = parser.parse_args()
    identity = load_script(Path(__file__).with_name("run-l7-spec09-registration.py"), "identity")
    receipt = {"status": "INCONCLUSIVE", "mode": args.mode, "runId": str(args.run_id),
               "startedAt": identity.utc_now(), "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    if args.mode == "prepare":
        if not all((args.subjects, args.realm, args.realm_file)):
            raise ValueError("prepare requires subjects, a fresh realm name and realm output")
        destinations = (args.realm_file, args.accounts, args.report)
        if len({p.resolve() for p in destinations}) != 3 or any(p.exists() for p in destinations):
            raise ValueError("all output files must be new and distinct")
        template = json.loads((ROOT/"docker/keycloak/pocoma-realm.json").read_text())
        realm, accounts = prepare(template, json.loads(args.subjects.read_text()), args.realm)
        with identity.reserve(args.realm_file) as file:
            identity.dump(file, realm)
        with identity.reserve(args.accounts) as file:
            identity.dump(file, accounts)
        receipt.update(status="FIXTURE_PREPARED", accounts=len(accounts), pkce="S256", realm=args.realm)
        with identity.reserve(args.report) as file:
            identity.dump(file, receipt)
        return 0
    if not (args.isolated_target and args.issuer):
        raise ValueError("network execution requires an isolated test deployment and issuer")
    accounts = private_file(args.accounts)
    if len(accounts) < WARMUP+SAMPLE or len({a["subject"] for a in accounts}) != len(accounts):
        raise ValueError("incomplete or duplicate fixture accounts")
    auth = load_script(ROOT/"scripts/functional-auth-proof.py", "auth")
    auth.ISSUER = validate_issuer(args.issuer)
    with identity.reserve(args.report) as file:
        try:
            if args.mode == "issue-tokens":
                if not args.tokens or args.tokens.exists():
                    raise ValueError("issue-tokens requires a fresh destination")
                tokens = {}
                for account in accounts:
                    tokens[account["subject"]], _ = login(auth, account)
                with identity.reserve(args.tokens) as token_file:
                    identity.dump(token_file, tokens)
                receipt.update(status="TOKENS_ISSUED", subjects=len(tokens))
            else:
                if not all((args.write_url, args.read_url, args.write_service, args.read_service)):
                    raise ValueError("execute requires two HTTP origins and read-only SQL services")
                write, read = identity.origin(args.write_url), identity.origin(args.read_url)
                if write == read or args.write_service == args.read_service:
                    raise ValueError("WRITE and READ origins and services must be distinct")
                rows = [{"outcome":"NOT_ATTEMPTED"} for _ in range(WARMUP+SAMPLE)]
                sample(identity, auth, accounts[:len(rows)], write, read, args.run_id, rows)
                receipt.update(rows=rows, warmup=summarize(identity,rows[:WARMUP]),
                               sample=summarize(identity,rows[WARMUP:]))
                if any(row["outcome"] in {"OLD_BINDING_ACCEPTED", "WRONG_BINDING_ACCEPTED"} for row in rows):
                    receipt["status"] = "FAIL"
                elif all(row["outcome"]=="OBSERVED" for row in rows):
                    receipt["sql"] = reconcile(identity,args.write_service,args.read_service,rows)
                    if receipt["sql"]["complete"]:
                        receipt["status"] = "OBSERVED"
        finally:
            receipt["finishedAt"] = identity.utc_now()
            identity.dump(file, receipt)
    return 0 if receipt["status"] in ("OBSERVED","TOKENS_ISSUED") else 1 if receipt["status"]=="FAIL" else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, TypeError, RuntimeError) as error:
        print(type(error).__name__, "in F01-F; inspect the non-secret receipt", file=sys.stderr)
        sys.exit(2)
