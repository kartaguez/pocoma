#!/usr/bin/env python3
"""Prove the disposable Keycloak OIDC fixture and Pocoma authentication boundary."""

import base64
import hashlib
from html.parser import HTMLParser
import http.cookiejar
import json
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

ISSUER = "http://localhost:8081/realms/pocoma"
CLIENT = "pocoma-functional-tests"
REDIRECT = "http://127.0.0.1:8765/callback"
PERMISSIONS = {"pocoma:pot:create", "pocoma:pot:view", "pocoma:pot:update", "pocoma:pot:delete"}
FORBIDDEN = "pocoma:expense:view"  # A Pocoma permission outside this client's four optional scopes.


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


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def authorize(requested_scopes, use_pkce=True, method="S256"):
    """Return callback parameters, or a Keycloak HTTP 400 without exposing secrets."""
    verifier = secrets.token_urlsafe(48)
    state = secrets.token_urlsafe(20)
    query = {
        "client_id": CLIENT, "redirect_uri": REDIRECT, "response_type": "code",
        "scope": " ".join(requested_scopes), "state": state,
    }
    if use_pkce:
        query["code_challenge"] = (base64.urlsafe_b64encode(
            hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
            if method == "S256" else verifier)
        query["code_challenge_method"] = method
    cookie_jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar), CaptureCallback())
    try:
        with opener.open(ISSUER + "/protocol/openid-connect/auth?" + urllib.parse.urlencode(query), timeout=15) as response:
            page = response.read().decode()
            login_url = response.url
    except CallbackReached as callback:
        values = urllib.parse.parse_qs(urllib.parse.urlparse(callback.location).query)
    except urllib.error.HTTPError as error:
        return {"http_error": error.code}, verifier
    else:
        form = LoginForm()
        form.feed(page)
        require(form.action, "Keycloak login form was not found")
        action = urllib.parse.urljoin(login_url, form.action)
        require(urllib.parse.urlparse(action).netloc == "localhost:8081", "Unexpected Keycloak login origin")
        credentials = urllib.parse.urlencode({"username": "bruno", "password": "bruno-local-only"}).encode()
        try:
            cookie_header = "; ".join(f"{cookie.name}={cookie.value}" for cookie in cookie_jar)
            opener.open(urllib.request.Request(action, data=credentials,
                                               headers={"Cookie": cookie_header}), timeout=15)
            raise RuntimeError("Keycloak did not redirect after Bruno login")
        except CallbackReached as callback:
            values = urllib.parse.parse_qs(urllib.parse.urlparse(callback.location).query)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Keycloak login failed with HTTP {error.code}") from None
    require(values.get("state") == [state], "Keycloak authorization state mismatch")
    return values, verifier


def exchange(values, verifier):
    require("code" in values and "error" not in values, "Authorization Code was not issued")
    data = urllib.parse.urlencode({
        "grant_type": "authorization_code", "client_id": CLIENT,
        "redirect_uri": REDIRECT, "code": values["code"][0], "code_verifier": verifier,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(
            ISSUER + "/protocol/openid-connect/token", data=data), timeout=15) as response:
        token = json.load(response)["access_token"]
    claims = json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "==="))
    return token, claims


def check_token(claims, expected_permissions):
    now = int(time.time())
    require(claims.get("iss") == ISSUER, "Unexpected issuer")
    require(isinstance(claims.get("sub"), str) and claims["sub"], "Missing subject")
    require(claims.get("azp") == CLIENT, "Unexpected authorized client")
    audience = claims.get("aud", [])
    require("pocoma-api" in ([audience] if isinstance(audience, str) else audience), "Missing Pocoma audience")
    require(all(isinstance(claims.get(key), int) for key in ("iat", "exp", "auth_time")), "Missing token time claim")
    require(claims["auth_time"] <= claims["iat"] <= now + 60, "Invalid issue or authentication time")
    require(now < claims["exp"] and claims["iat"] < claims["exp"], "Access token is expired")
    scopes = set((claims.get("scope") or "").split())
    require("openid" in scopes, "Missing OIDC scope")
    require({scope for scope in scopes if scope.startswith("pocoma:")} == expected_permissions,
            "Unexpected Pocoma permissions")


def response_code(url, token=None):
    headers = {"Authorization": "Bearer " + token} if token else {}
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def main():
    no_pkce, _ = authorize(["openid"], use_pkce=False)
    require(no_pkce.get("error") == ["invalid_request"],
            "Client accepted an authorization request without PKCE")
    plain_pkce, _ = authorize(["openid"], method="plain")
    require(plain_pkce.get("error") == ["invalid_request"],
            "Client accepted a non-S256 PKCE challenge")
    print("PKCE S256 required: missing and plain challenges rejected")

    onboarding_values, onboarding_verifier = authorize(["openid"])
    onboarding_token, onboarding = exchange(onboarding_values, onboarding_verifier)
    check_token(onboarding, set())
    print("Onboarding: client, audience, issuer, subject, OIDC scope and time claims valid; no Pocoma permission")

    business_values, business_verifier = authorize(["openid", *sorted(PERMISSIONS)])
    _, business = exchange(business_values, business_verifier)
    check_token(business, PERMISSIONS)
    require(onboarding["iss"] == business["iss"] and onboarding["sub"] == business["sub"],
            "The two flows did not authenticate the same external identity")
    print("Business: exactly four requested Pocoma permissions; issuer and subject match onboarding")

    forbidden_values, forbidden_verifier = authorize(["openid", FORBIDDEN])
    if "code" in forbidden_values:
        _, forbidden = exchange(forbidden_values, forbidden_verifier)
        check_token(forbidden, set())
        print("Unassigned Pocoma scope: omitted from issued access token")
    else:
        require(forbidden_values.get("error") == ["invalid_scope"],
                "Unexpected response to unassigned Pocoma scope")
        print("Unassigned Pocoma scope: authorization rejected; no access token issued")

    url = "http://localhost:8080/api/v1/me/binding"
    anonymous = response_code(url)
    authenticated = response_code(url, onboarding_token)
    require(anonymous == 401, f"Anonymous Current Binding returned {anonymous}, expected 401")
    require(authenticated == 404, f"Onboarding Current Binding returned {authenticated}, expected 404")
    print("Current Binding: anonymous 401; onboarding token accepted, no binding 404")


if __name__ == "__main__":
    main()
