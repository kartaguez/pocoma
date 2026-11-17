# Authentication & Binding

Access to Pocoma APIs (`/api/v3`) combines **OIDC** authentication (Keycloak) with an explicit application binding (`Binding`) between your external identity and an internal Pocoma user.

## 1. OIDC Authentication (Keycloak)

The local infrastructure deploys **Keycloak 26.7.0** with the [`docker/keycloak/pocoma-realm.json`](../../docker/keycloak/pocoma-realm.json) realm:

- **Flow**: Authorization Code + PKCE (`S256`) with `state` verification.
- **OIDC Discovery**: `ISSUER/.well-known/openid-configuration` (`ISSUER` is printed by `./pocoma-local status`).
- **Public client**: `pocoma-functional-tests` (no `Client Secret`), callback `http://127.0.0.1:8765/callback`, scope `openid`.
- **Expected Bearer JWT audience**: `pocoma-api`.
- **Local test accounts**: `third`, `guest`, and `bruno` (password `pocoma`).

> [!WARNING]
> These accounts and this realm are reserved for the local playground. The API verifies the exact issuer, `pocoma-api` audience, signature, and token expiration; the `openid` scope carries no business roles (authorization depends on the bound identity and per-Pot rights).

### Via CLI or Bruno GUI

- `./pocoma-local login --instance demo` opens/renews the OIDC session from the command line.
- `./pocoma-local gui --instance demo` opens Bruno preconfigured with native PKCE OAuth (use `00 Login Owner` for `third` and `01 Login Guest` for `guest`). No manual JWT copying is required.

## 2. Check or Create Your Binding

A valid OIDC token alone is not enough to manipulate pots: your external identity must be bound to a Pocoma user via an active `bindingToken`.

### Check the current binding

```bash
curl -sS -i "$READ_URL/api/v3/me/binding" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

- **`200 OK`**: returns `active`, `euiVersion`, and `bindingToken`.
- **`404 Not Found`**: your identity is not registered yet.

### Register your identity (`Registration`)

Submit a registration request on the WRITE side (the client never picks its `userId` or `subject`; they are extracted from the JWT):

```bash
curl -sS -X POST "$WRITE_URL/api/v3/identity/requests" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
  -d '{"operation":"Registration"}'
```

The `202 Accepted` response returns `{"requestId":"..."}`. Poll the result on the READ side until `COMPLETED`:

```bash
curl -sS "$READ_URL/api/v3/identity/requests/<requestId>/result" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Then query `GET $READ_URL/api/v3/me/binding` again until you observe the active `bindingToken` matching `targetVersion`. Submitting another `Registration` for an already-bound identity is rejected with `outcome: "REJECTED"` (`TARGET_ALREADY_BOUND`).

## 3. Using `bindingToken` in API Calls

- **On WRITE (`POST /api/v3/commands` or `Unlink`)**: pass `"bindingToken": "..."` in the JSON body.
- **On READ (`GET`/`POST` on pots and invitations)**: pass the `X-Pocoma-Binding-Token: <bindingToken>` HTTP header.

> [!IMPORTANT]
> Keep the Bearer JWT, `bindingToken`, and invitation secrets strictly server-side / in protected session memory (or Bruno runtime memory); never place them in API URLs or logs.

## 4. Unlinking (`Unlink`)

To detach your current external identity:

```json
{
  "operation": "Unlink",
  "bindingToken": "<BINDING_TOKEN>",
  "target": {
    "issuer": "<ISSUER>",
    "subject": "<SUBJECT>"
  }
}
```

Submitted to `POST $WRITE_URL/api/v3/identity/requests` with an `Idempotency-Key`, `Unlink` revokes the current `bindingToken`. A revoked binding token remains invalid even with a fresh JWT; however, historical results of commands you submitted remain queryable by your historical identity.
