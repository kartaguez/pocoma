"""Private Authorization Code + PKCE S256 sessions; never password grant."""
import base64
import hashlib
import importlib.util
import json
import secrets
import time
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REDIRECT = 'http://127.0.0.1:8765/callback'


def provider(issuer):
    spec = importlib.util.spec_from_file_location('local_oidc', ROOT / 'scripts/functional-auth-proof.py')
    auth = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(auth)
    auth.ISSUER = issuer
    return auth


def login(issuer, username, password):
    auth = provider(issuer)
    values, verifier = auth.authorize(['openid'], username=username, password=password)
    token, claims = auth.exchange(values, verifier)
    auth.check_token(claims, set())
    return token


def prove_pkce(issuer, username, password):
    auth = provider(issuer)
    for options in ({'use_pkce': False}, {'method': 'plain'}):
        values, _ = auth.authorize(['openid'], username=username, password=password, **options)
        if values.get('error') != ['invalid_request']:
            raise RuntimeError('IDP-PKCE-REQUIRED')
    return login(issuer, username, password)


def interactive(issuer):
    auth = provider(issuer)
    state, verifier = secrets.token_urlsafe(32), secrets.token_urlsafe(48)
    query = urllib.parse.urlencode(dict(client_id=auth.CLIENT, redirect_uri=REDIRECT,
        response_type='code', scope='openid', state=state, code_challenge_method='S256',
        code_challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()))
    callback = {}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Callback contains an authorization code.
        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            values = urllib.parse.parse_qs(parsed.query)
            if parsed.path != '/callback' or values.get('state') != [state] or 'code' not in values:
                self.send_error(400, 'Invalid OIDC callback')
                return
            callback.update(values)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'Pocoma authentication complete. You can close this tab.')
    with HTTPServer(('127.0.0.1', 8765), Handler) as server:
        server.timeout = 1
        webbrowser.open(issuer + '/protocol/openid-connect/auth?' + query)
        deadline = time.monotonic() + 180
        while not callback and time.monotonic() < deadline:
            server.handle_request()
    if not callback:
        raise RuntimeError('IDP-INTERACTIVE-TIMEOUT')
    token, claims = auth.exchange(callback, verifier)
    auth.check_token(claims, set())
    return token
