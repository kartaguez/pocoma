#!/usr/bin/env python3
"""Pocoma V3 — OIDC Token Manager with Proactive Renewal (scripts/performance/v3_token.py).

Provides:
1. Thread-safe acquisition and proactive renewal of Keycloak JWT tokens.
2. Compliance with existing test user flows (PKCE S256 + OAuth2 refresh_token grant).
3. Zero modification to Pocoma JWT validation or server token lifespans.
4. Embedded lightweight HTTP server (default port 18083) so external processes (like k6)
   can fetch the currently active access token instantly without executing an OIDC flow.
5. Accurate telemetry: renewals count, failed renewals, authentication durations.
"""

import http.server
import json
import os
from pathlib import Path
import socketserver
import subprocess
import sys
import threading
import time
from typing import Dict, Optional, Tuple
import urllib.error
import urllib.parse
import urllib.request

REPO_ROOT = Path(__file__).resolve().parents[2]
KEYCLOAK_ISSUER = os.environ.get("KEYCLOAK_ISSUER", "http://localhost:8081/realms/pocoma")
CLIENT_ID = os.environ.get("CLIENT_ID", "pocoma-functional-tests")
REDIRECT_URI = os.environ.get("REDIRECT_URI", "http://127.0.0.1:8765/callback")
PERMISSIONS = ["openid", "pocoma:pot:create", "pocoma:pot:view", "pocoma:pot:update", "pocoma:pot:delete"]


class OidcTokenManager:
    def __init__(self, username: str = "bruno", password: str = "bruno-local-only",
                 renewal_margin_sec: float = 60.0):
        self.username = username
        self.password = password
        self.renewal_margin_sec = renewal_margin_sec

        self._lock = threading.Lock()
        self._access_token: Optional[str] = None
        self._refresh_token: Optional[str] = None
        self._expires_at: float = 0.0
        self._refresh_expires_at: float = 0.0

        # Telemetry
        self.renewal_count = 0
        self.renewal_failures = 0
        self.total_auth_duration_ms = 0.0

    def _initial_login(self) -> Dict:
        """Executes full PKCE Authorization Code flow via run-demo-journey helper."""
        import base64
        import hashlib
        from html.parser import HTMLParser
        import http.cookiejar
        import secrets

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
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                if newurl.startswith(REDIRECT_URI + "?"):
                    raise CallbackReached(newurl)
                return super().redirect_request(req, fp, code, msg, headers, newurl)

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
            action = urllib.parse.urljoin(login_url, form.action)
            creds = urllib.parse.urlencode({"username": self.username, "password": self.password}).encode()
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
            return json.load(resp)

    def _refresh_login(self) -> Dict:
        """Executes lightweight refresh_token grant."""
        if not self._refresh_token:
            raise ValueError("No refresh token available")
        token_data = urllib.parse.urlencode({
            "grant_type": "refresh_token",
            "client_id": CLIENT_ID,
            "refresh_token": self._refresh_token,
        }).encode()
        req = urllib.request.Request(f"{KEYCLOAK_ISSUER}/protocol/openid-connect/token", data=token_data)
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.load(resp)

    def get_token(self, force_renew: bool = False) -> str:
        """Returns a guaranteed valid access token, proactively refreshing if nearing expiry."""
        with self._lock:
            now = time.time()
            needs_refresh = (
                self._access_token is None
                or force_renew
                or (now + self.renewal_margin_sec >= self._expires_at)
            )

            if not needs_refresh:
                return self._access_token

            start_t = time.time()
            data = None

            # Attempt refresh if valid refresh_token exists
            if self._refresh_token and (now < self._refresh_expires_at or force_renew):
                try:
                    data = self._refresh_login()
                    self.renewal_count += 1
                except Exception as e:
                    self.renewal_failures += 1
                    data = None

            # Fall back to initial login flow if refresh failed or not available
            if data is None:
                try:
                    data = self._initial_login()
                    if self._access_token is not None:
                        self.renewal_count += 1
                except Exception as e:
                    self.renewal_failures += 1
                    raise RuntimeError(f"Failed to obtain OIDC token from Keycloak: {e}") from e

            dur_ms = (time.time() - start_t) * 1000.0
            self.total_auth_duration_ms += dur_ms

            self._access_token = data["access_token"]
            self._refresh_token = data.get("refresh_token", self._refresh_token)
            expires_in = float(data.get("expires_in", 300))
            refresh_expires_in = float(data.get("refresh_expires_in", 1800))

            self._expires_at = time.time() + expires_in
            self._refresh_expires_at = time.time() + refresh_expires_in
            return self._access_token

    def get_telemetry(self) -> Dict:
        with self._lock:
            return {
                "renewalCount": self.renewal_count,
                "renewalFailures": self.renewal_failures,
                "totalAuthDurationMs": round(self.total_auth_duration_ms, 2),
                "tokenRemainingSeconds": max(0.0, round(self._expires_at - time.time(), 1)),
            }


class TokenHttpServer:
    """Local ephemeral HTTP server serving active tokens to k6 VUs or local scripts."""

    def __init__(self, token_manager: OidcTokenManager, host: str = "127.0.0.1", port: int = 18083):
        self.token_manager = token_manager
        self.host = host
        self.port = port
        self.server: Optional[socketserver.TCPServer] = None
        self.thread: Optional[threading.Thread] = None

    def start(self):
        token_mgr = self.token_manager

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass  # Quiet logging

            def do_GET(self):
                if self.path == "/token":
                    try:
                        token = token_mgr.get_token()
                        body = json.dumps({"token": token, "expiresIn": 300}).encode("utf-8")
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                    except Exception as e:
                        err = json.dumps({"error": str(e)}).encode("utf-8")
                        self.send_response(500)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(err)))
                        self.end_headers()
                        self.wfile.write(err)
                elif self.path == "/telemetry":
                    tele = json.dumps(token_mgr.get_telemetry()).encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(tele)))
                    self.end_headers()
                    self.wfile.write(tele)
                else:
                    self.send_response(404)
                    self.end_headers()

        # Allow port reuse
        socketserver.TCPServer.allow_reuse_address = True
        self.server = socketserver.TCPServer((self.host, self.port), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()
            self.server = None
        if self.thread:
            self.thread.join(timeout=2.0)
            self.thread = None

    @property
    def url(self) -> str:
        return f"http://{self.host}:{self.port}/token"
