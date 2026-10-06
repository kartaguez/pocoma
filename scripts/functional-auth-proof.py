#!/usr/bin/env python3
"""Prove the local Keycloak browser flow and Pocoma resource-server boundary."""

import argparse
import base64
import hashlib
from html.parser import HTMLParser
import http.cookiejar
import json
import secrets
import urllib.error
import urllib.parse
import urllib.request

ISSUER = "http://localhost:8081/realms/pocoma"
REDIRECT = "http://127.0.0.1:8765/callback"


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
        if newurl.startswith(REDIRECT + "?"):
            raise CallbackReached(newurl)
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def response_code(url, token=None):
    headers = {"Authorization": "Bearer " + token} if token else {}
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=15) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--print-token", action="store_true", help="print the short-lived access token for curl")
    args = parser.parse_args()

    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(20)
    query = urllib.parse.urlencode({
        "client_id": "pocoma-local", "redirect_uri": REDIRECT, "response_type": "code",
        "scope": "openid", "state": state, "code_challenge": challenge,
        "code_challenge_method": "S256",
    })
    cookie_jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar), CaptureCallback())
    with opener.open(ISSUER + "/protocol/openid-connect/auth?" + query, timeout=15) as response:
        page = response.read().decode()
        login_url = response.url
    form = LoginForm()
    form.feed(page)
    if not form.action:
        raise RuntimeError("Keycloak login form was not found")
    action = urllib.parse.urljoin(login_url, form.action)
    if urllib.parse.urlparse(action).netloc != "localhost:8081":
        raise RuntimeError("Unexpected Keycloak login origin")
    credentials = urllib.parse.urlencode({"username": "bruno", "password": "bruno-local-only"}).encode()
    try:
        cookie_header = "; ".join(f"{cookie.name}={cookie.value}" for cookie in cookie_jar)
        opener.open(urllib.request.Request(action, data=credentials, headers={"Cookie": cookie_header}), timeout=15)
        raise RuntimeError("Keycloak did not redirect after Bruno login")
    except CallbackReached as callback:
        values = urllib.parse.parse_qs(urllib.parse.urlparse(callback.location).query)
    except urllib.error.HTTPError as error:
        body = error.read().decode()
        import re
        plain = re.sub(r"\s+", " ", re.sub('<[^>]+>', ' ', body))
        raise RuntimeError(f"Keycloak login failed with HTTP {error.code}: {plain[-350:]}") from error
    if values.get("state") != [state] or "code" not in values:
        raise RuntimeError("Keycloak authorization response was invalid: " + repr(values))

    token_data = urllib.parse.urlencode({
        "grant_type": "authorization_code", "client_id": "pocoma-local",
        "redirect_uri": REDIRECT, "code": values["code"][0], "code_verifier": verifier,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(ISSUER + "/protocol/openid-connect/token", data=token_data), timeout=15) as response:
        token = json.load(response)["access_token"]
    claims = json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "==="))
    observed = {key: claims.get(key) for key in ("iss", "aud", "sub", "iat", "exp", "auth_time", "scope", "scp")}
    print(json.dumps(observed, indent=2))
    scopes = set((claims.get("scope") or "").split()) | set(claims.get("scp") or [])
    assert claims["iss"] == ISSUER
    assert "pocoma-api" in ([claims["aud"]] if isinstance(claims["aud"], str) else claims["aud"])
    assert claims.get("sub") and claims.get("iat") and claims.get("exp")
    assert claims.get("auth_time") and claims["auth_time"] <= claims["iat"]
    assert {"pocoma:pot:create", "pocoma:pot:view"} <= scopes

    url = "http://localhost:8080/api/v1/me/binding"
    anonymous = response_code(url)
    authenticated = response_code(url, token)
    print("anonymous binding:", anonymous[0], anonymous[1])
    print("Bruno binding:", authenticated[0], authenticated[1])
    assert anonymous[0] == 401
    assert authenticated[0] == 404
    if args.print_token:
        print("access_token=" + token)


if __name__ == "__main__":
    main()
