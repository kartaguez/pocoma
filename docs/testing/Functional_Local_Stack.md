# Local functional infrastructure

This stack is for local functional testing only. It contains PostgreSQL 16.6,
Keycloak 26.7.0 and the existing `runtime-monolith`. All passwords in the realm,
Compose file and proof script are disposable local test credentials. Never reuse
them outside this stack.

From the repository root:

```sh
docker compose -f docker-compose.functional.yml up -d --build --wait
python3 scripts/functional-auth-proof.py
```

The script signs Bruno in twice through Authorization Code with required PKCE
S256. The first access token requests only `openid` and has no Pocoma business
permission; the second requests the four Pot permissions. It checks their
audience, issuer, subject, client, time claims and scopes, and proves that a
request without PKCE, a request using plain PKCE, and a request for an unassigned
Pocoma permission cannot obtain the corresponding access. It prints no tokens.
The protected Current Binding endpoint returns 401 without a token and 404 with
the onboarding token; the 404 is expected before Registration creates a binding.

The realm import file is `docker/keycloak/pocoma-realm.json`. It defines the
public `pocoma-functional-tests` client with only
`http://127.0.0.1:8765/callback` as redirect URI. The default
`pocoma-api-access` client scope adds `pocoma-api` to the access-token
audience. `basic` supplies `sub` and `auth_time`. The four optional scopes
are `pocoma:pot:create`, `pocoma:pot:view`, `pocoma:pot:update` and
`pocoma:pot:delete`; they appear in the access-token `scope` claim only
when requested. Bruno has no business roles or permissions. No Pocoma
Registration or Binding is seeded.

Keycloak advertises the single issuer
`http://localhost:8081/realms/pocoma`. The host can reach this address. The
monolith checks that exact issuer and fetches signing keys through Docker's
internal address `http://keycloak:8080/realms/pocoma/protocol/openid-connect/certs`.
The issuer check and audience check remain enabled. Keycloak's direct password
grant is disabled because its tokens do not carry the `auth_time` required by
Pocoma; the proof uses a browser authorization code flow.

PostgreSQL's `public` schema holds PRIMARY and Keycloak uses `keycloak`. The
READ migrations run separately in `pocoma_read` on the same database. The
PostgreSQL named volume preserves all three schemas after ordinary `down/up`:

```sh
docker compose -f docker-compose.functional.yml down
docker compose -f docker-compose.functional.yml up -d --wait
python3 scripts/functional-auth-proof.py
```

To prove full reconstruction, remove this stack's volume and start again:

```sh
docker compose -f docker-compose.functional.yml down -v
docker compose -f docker-compose.functional.yml up -d --build --wait
python3 scripts/functional-auth-proof.py
```

Keycloak imports the versioned realm only when the realm does not already
exist. Changes to the realm JSON therefore require `down -v` in this local
environment. All three services have healthchecks; the monolith starts
only after PostgreSQL and Keycloak are healthy.
