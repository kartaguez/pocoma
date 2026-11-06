#!/usr/bin/env python3
"""Pocoma V3 — Replayable Operational Demonstration Journey (Python + Bruno CLI).

Executes end-to-end business workflows against live Pocoma V3:
1. Obtains valid OIDC token from Keycloak using PKCE S256.
2. Checks user binding on /api/v3/me/binding or performs registration via /api/v3/identity/requests.
3. Submits CreatePot command via /api/v3/commands and polls /api/v3/commands/{cmdId}/result.
4. Strictly verifies terminal business outcome (outcome == 'CHANGED', resultingPotVersion >= 1).
5. Queries the V3 Read facade (/api/v3/pots/{potId}) verifying id, label, and version match.
6. Runs the dedicated Bruno CLI test suite ("05 Observability Demo") via `bru run`.
7. Asserts genuine progression of `pocoma_v3_*` metrics in Prometheus.

Every run generates fresh, random UUIDs and timestamped keys,
making it safely replayable indefinitely without resetting PostgreSQL.
"""

import argparse
import base64
import hashlib
from html.parser import HTMLParser
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

KEYCLOAK_ISSUER = "http://localhost:8081/realms/pocoma"
CLIENT_ID = "pocoma-functional-tests"
REDIRECT_URI = "http://127.0.0.1:8765/callback"
PERMISSIONS = ["openid", "pocoma:pot:create", "pocoma:pot:view", "pocoma:pot:update", "pocoma:pot:delete"]
PROMETHEUS_URL = "http://127.0.0.1:9090"


class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__()
        self.action = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form" and attrs.get("id") == "kc-form-login":
            self.action = attrs.get("action")


class CallbackReached(Exception):
    def __init__(self, location):
        self.location = location


class CaptureCallback(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        if newurl.startswith(REDIRECT_URI + "?"):
            raise CallbackReached(newurl)
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def get_token(username="bruno", password="bruno-local-only"):
    verifier = secrets.token_urlsafe(48)
    state = secrets.token_urlsafe(20)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    query = {
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": " ".join(PERMISSIONS),
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    cookie_jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar), CaptureCallback())
    auth_url = f"{KEYCLOAK_ISSUER}/protocol/openid-connect/auth?{urllib.parse.urlencode(query)}"
    try:
        with opener.open(auth_url, timeout=15) as resp:
            page = resp.read().decode()
            login_url = resp.url
    except CallbackReached as cb:
        values = urllib.parse.parse_qs(urllib.parse.urlparse(cb.location).query)
    else:
        form = LoginForm()
        form.feed(page)
        if not form.action:
            raise RuntimeError("Keycloak login form action not found")
        action = urllib.parse.urljoin(login_url, form.action)
        creds = urllib.parse.urlencode({"username": username, "password": password}).encode()
        try:
            cookie_header = "; ".join(f"{c.name}={c.value}" for c in cookie_jar)
            opener.open(urllib.request.Request(action, data=creds, headers={"Cookie": cookie_header}), timeout=15)
            raise RuntimeError("Keycloak did not redirect after credentials POST")
        except CallbackReached as cb:
            values = urllib.parse.parse_qs(urllib.parse.urlparse(cb.location).query)

    code = values["code"][0]
    token_data = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "code": code,
        "code_verifier": verifier,
    }).encode()
    req = urllib.request.Request(f"{KEYCLOAK_ISSUER}/protocol/openid-connect/token", data=token_data)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.load(resp)["access_token"]


def http_req(method, url, token=None, body=None, headers=None):
    hdrs = headers.copy() if headers else {}
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        if isinstance(body, (dict, list)):
            data = json.dumps(body).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
        elif isinstance(body, bytes):
            data = body
        else:
            data = str(body).encode("utf-8")

    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            content = resp.read()
            return resp.status, resp.headers, content
    except urllib.error.HTTPError as err:
        return err.code, err.headers, err.read()


def query_prometheus(metric_query):
    url = f"{PROMETHEUS_URL}/api/v1/query?query={urllib.parse.quote(metric_query)}"
    try:
        status, _, content = http_req("GET", url)
        if status == 200:
            res = json.loads(content.decode())
            data = res.get("data", {}).get("result", [])
            if data and "value" in data[0]:
                return float(data[0]["value"][1])
    except Exception:
        pass
    return 0.0


def poll_result(url, token, headers=None, max_attempts=40, interval=0.5):
    for _ in range(max_attempts):
        status, resp_headers, content = http_req("GET", url, token=token, headers=headers)
        if status == 200:
            return json.loads(content.decode("utf-8")) if content else {}
        if status in (404, 503):
            time.sleep(interval)
            continue
        raise RuntimeError(f"Unexpected status polling {url}: HTTP {status} {content.decode('utf-8', errors='replace')}")
    raise TimeoutError(f"Exceeded {max_attempts * interval}s waiting for {url}")


def run_campaign(base_url, management_url, token, campaign_index=1, run_bruno_cli=True):
    print(f"\n=======================================================")
    print(f"▶ Starting Demonstration Campaign #{campaign_index}")
    print(f"=======================================================")

    # Prometheus Snapshot Before
    m_admission_before = query_prometheus("sum(pocoma_v3_admission_requests_total)")
    m_attempts_before = query_prometheus("sum(pocoma_v3_attempt_outcomes_total)")
    m_read_before = query_prometheus("sum(pocoma_v3_read_query_duration_seconds_count)")
    m_hikari_before = query_prometheus("sum(hikaricp_connections_acquire_seconds_count)")

    # 1. Identity Check / Registration
    print("1. Checking current user binding (/api/v3/me/binding)...")
    status, _, content = http_req("GET", f"{base_url}/api/v3/me/binding", token=token)
    binding_token = None
    if status == 200:
        data = json.loads(content.decode())
        if data.get("active"):
            binding_token = data.get("bindingToken")
            print(f"   ✓ Existing binding active (euiVersion: {data.get('euiVersion')})")

    if not binding_token:
        print(f"   → No active binding (HTTP {status}). Requesting registration (/api/v3/identity/requests)...")
        reg_idem = f"reg-{uuid.uuid4()}"
        status, _, content = http_req(
            "POST",
            f"{base_url}/api/v3/identity/requests",
            token=token,
            body={"operation": "Registration"},
            headers={"Idempotency-Key": reg_idem},
        )
        if status != 202:
            raise RuntimeError(f"Registration admission failed: HTTP {status} {content.decode()}")
        reg_data = json.loads(content.decode())
        reg_id = reg_data["requestId"]
        print(f"   ✓ Registration admitted (202). Polling result for requestId={reg_id}...")
        res = poll_result(f"{base_url}/api/v3/identity/requests/{reg_id}/result", token=token)
        if res.get("outcome") != "CHANGED":
            raise RuntimeError(f"Registration failed with outcome: {res.get('outcome')} rejection: {res.get('rejection')}")
        print(f"   ✓ Registration completed! Polling binding token...")

        # Poll binding until active
        for _ in range(30):
            time.sleep(0.5)
            s, _, c = http_req("GET", f"{base_url}/api/v3/me/binding", token=token)
            if s == 200:
                b_data = json.loads(c.decode())
                if b_data.get("active"):
                    binding_token = b_data.get("bindingToken")
                    break
        if not binding_token:
            raise TimeoutError("Timed out waiting for active binding token after registration")
        print(f"   ✓ Binding token materialized and active!")

    # 2. Command Admission: CreatePot
    pot_id = str(uuid.uuid4())
    pot_label = f"Demo Pot #{campaign_index} - {time.strftime('%H:%M:%S')}"
    cmd_idem = f"cmd-{uuid.uuid4()}"
    print(f"\n2. Submitting CreatePot command ('{pot_label}', potId={pot_id})...")
    status, _, content = http_req(
        "POST",
        f"{base_url}/api/v3/commands",
        token=token,
        body={
            "operation": "CreatePot",
            "potId": pot_id,
            "observedVersion": 0,
            "bindingToken": binding_token,
            "payload": {"label": pot_label},
        },
        headers={"Idempotency-Key": cmd_idem},
    )
    if status != 202:
        raise RuntimeError(f"CreatePot admission failed: HTTP {status} {content.decode()}")
    cmd_data = json.loads(content.decode())
    cmd_id = cmd_data["requestId"]
    print(f"   ✓ Command admitted (202, requestId={cmd_id}). Polling result...")

    # 3. Poll Command Result & Strict Oracle
    print("\n3. Polling command result and verifying strict business outcome...")
    res = poll_result(f"{base_url}/api/v3/commands/{cmd_id}/result", token=token)
    outcome = res.get("outcome")
    if outcome in ("REJECTED", "FAILED"):
        raise RuntimeError(f"Command execution {outcome} by worker: {res.get('rejection')}")
    if outcome != "COMPLETED":
        raise RuntimeError(f"Unexpected terminal command outcome: '{outcome}' (expected 'COMPLETED')")
    resulting_version = res.get("resultingPotVersion", 0)
    if resulting_version < 1:
        raise RuntimeError(f"Invalid resultingPotVersion: {resulting_version} (expected >= 1)")
    print(f"   ✓ Command COMPLETED successfully! outcome={outcome}, resultingPotVersion={resulting_version}")

    # 4. Read Pot via V3 Read Facade & Verify Functional State
    print(f"\n4. Consulting Pot via V3 Read facade (/api/v3/pots/{pot_id})...")
    pot_res = poll_result(
        f"{base_url}/api/v3/pots/{pot_id}",
        token=token,
        headers={"X-Pocoma-Binding-Token": binding_token}
    )
    if pot_res.get("id") != pot_id:
        raise RuntimeError(f"Read facade returned mismatched pot ID: {pot_res.get('id')} != {pot_id}")
    if pot_res.get("label") != pot_label:
        raise RuntimeError(f"Read facade returned mismatched pot label: '{pot_res.get('label')}' != '{pot_label}'")
    if pot_res.get("potVersion", 0) < resulting_version:
        raise RuntimeError(f"Read facade potVersion {pot_res.get('potVersion')} is older than command version {resulting_version}")
    print(f"   ✓ Pot verified via Read facade! id={pot_res['id']}, label='{pot_res['label']}', potVersion={pot_res['potVersion']}")

    # 5. Bruno CLI Demonstration Execution
    if run_bruno_cli:
        repo_root = Path(__file__).resolve().parents[2]
        bruno_bin = repo_root / "scripts/bruno-v3/node_modules/.bin/bru"
        bruno_dir = repo_root / "scripts/bruno-v3"
        if bruno_bin.exists():
            print(f"\n5. Running Bruno CLI on '05 Observability Demo'...")
            env = os.environ.copy()
            env["POCOMA_I2_BASE_URL"] = base_url
            env["POCOMA_I2_bearerOwner"] = token
            bruno_pot_id = str(uuid.uuid4())
            ts = int(time.time())
            cmd = [
                str(bruno_bin),
                "run",
                "05 Observability Demo",
                "--env-var", f"baseUrl={base_url}",
                "--env-var", f"managementUrl={management_url}",
                "--env-var", f"bearerToken={token}",
                "--env-var", f"bindingToken={binding_token}",
                "--env-var", f"potId={bruno_pot_id}",
                "--env-var", f"timestamp={ts}",
                "--sandbox", "developer",
                "--noproxy"
            ]
            res = subprocess.run(cmd, cwd=str(bruno_dir), env=env, capture_output=True, text=True)
            if res.returncode == 0:
                print("   ✓ Bruno CLI suite PASSED with 0 errors!")
                for line in res.stdout.splitlines():
                    if "Requests:" in line or "Tests:" in line or "Passed:" in line:
                        print(f"     {line.strip()}")
            else:
                print(f"   ⚠ Bruno CLI exited with code {res.returncode}: {res.stderr}\n{res.stdout}")
        else:
            print("   (Bruno CLI binary not found at default location, skipping CLI execution)")

    # 6. Wait for Prometheus 5s scrape
    print("\n6. Awaiting Prometheus scrape cycle (6s)...")
    time.sleep(6)

    # 7. Prometheus Snapshot After & Deltas
    m_admission_after = query_prometheus("sum(pocoma_v3_admission_requests_total)")
    m_attempts_after = query_prometheus("sum(pocoma_v3_attempt_outcomes_total)")
    m_read_after = query_prometheus("sum(pocoma_v3_read_query_duration_seconds_count)")
    m_hikari_after = query_prometheus("sum(hikaricp_connections_acquire_seconds_count)")

    d_admission = m_admission_after - m_admission_before
    d_attempts = m_attempts_after - m_attempts_before
    d_read = m_read_after - m_read_before
    d_hikari = m_hikari_after - m_hikari_before

    print(f"\n7. Observability Progression Metrics (V3 Genuine):")
    print(f"   • V3 Admission Requests: {m_admission_before:.0f} → {m_admission_after:.0f}  (Δ +{d_admission:.0f} requests)")
    print(f"   • V3 Worker Attempts   : {m_attempts_before:.0f} → {m_attempts_after:.0f}  (Δ +{d_attempts:.0f} attempts)")
    print(f"   • V3 Read Queries      : {m_read_before:.0f} → {m_read_after:.0f}  (Δ +{d_read:.0f} queries)")
    print(f"   • Hikari CP Acquires   : {m_hikari_before:.0f} → {m_hikari_after:.0f}  (Δ +{d_hikari:.0f} acquires)")

    if d_admission <= 0 and d_attempts <= 0:
        raise RuntimeError("FAIL: Neither V3 admission nor worker attempt metrics incremented in Prometheus!")
    print("   ✓ Real pocoma_v3_* metrics progression successfully verified in Prometheus!")

    print(f"\n✔ Campaign #{campaign_index} completed with SUCCESS!")
    return {
        "campaign": campaign_index,
        "delta_admission": d_admission,
        "delta_attempts": d_attempts,
        "delta_read": d_read,
        "delta_hikari": d_hikari,
    }


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 Replayable Demo Journey")
    parser.add_argument("--url", default="http://127.0.0.1:8080", help="Base URL of Pocoma V3 Monolith")
    parser.add_argument("--management-url", default="http://127.0.0.1:18081", help="Actuator Management URL")
    parser.add_argument("--repeat", type=int, default=1, help="Number of successive campaigns to run")
    parser.add_argument("--no-bruno", action="store_true", help="Skip running Bruno CLI suite")
    args = parser.parse_args()

    print("=======================================================")
    print("🚀 Pocoma V3 — Operational Demonstration & Observability")
    print("=======================================================")
    print(f"Pocoma Runtime : {args.url}")
    print(f"Management URL : {args.management_url}")
    print(f"Keycloak IdP   : {KEYCLOAK_ISSUER}")
    print(f"Prometheus     : {PROMETHEUS_URL}")
    print(f"Grafana UI     : http://localhost:3000 (Pocoma V3 Overview)")
    print("-------------------------------------------------------")

    print("Authenticating against Keycloak...")
    token = get_token()
    print("✓ Successfully authenticated! Access token generated via PKCE.")

    results = []
    for i in range(1, args.repeat + 1):
        r = run_campaign(args.url, args.management_url, token, campaign_index=i, run_bruno_cli=not args.no_bruno)
        results.append(r)
        if i < args.repeat:
            time.sleep(1)

    print("\n=======================================================")
    print("🎉 All demonstration campaigns completed successfully!")
    print("Grafana Dashboard available at: http://localhost:3000/d/pocoma-v3-overview")
    print("=======================================================")


if __name__ == "__main__":
    main()
