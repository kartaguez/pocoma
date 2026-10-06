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

The script signs Bruno in through Keycloak's authorization code flow with PKCE,
prints the relevant access-token claims, and asserts that the protected Current
Binding endpoint returns 401 without a token and 404 with Bruno's token. The
404 is expected before Registration creates a binding. To obtain the short-lived
token for a manual curl call, use `--print-token`; avoid saving it in the repo.

The realm import file is `docker/keycloak/pocoma-realm.json`. It defines the
public `pocoma-local` client, `pocoma-api` audience, `basic` client scope for
`sub` and `auth_time`, `pocoma:pot:create` and `pocoma:pot:view` client scopes,
and the Bruno user. The API reads these Pocoma permissions from the `scope`
claim. No Pocoma Registration or Binding is seeded.

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
