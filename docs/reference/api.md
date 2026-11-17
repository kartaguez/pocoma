# Public API Reference (`/api/v3`)

Pocoma's public API is exposed under `/api/v3` by two separate web runtimes:
- **`WRITE_URL`** (`http-write`, internal port `8080`): command and identity request admission (`POST`).
- **`READ_URL`** (`http-read`, internal port `8082`): querying bindings, results, pots, and invitations (`GET` / `POST`).

Exact URLs for a local instance are printed by `./pocoma-local start` and `./pocoma-local status`.

## 1. Headers & Authentication

| Header | Scope | Role |
| :--- | :--- | :--- |
| `Authorization: Bearer <token>` | All `/api/v3` routes | Keycloak OIDC JWT (`audience=pocoma-api`, `scope=openid`) |
| `Content-Type: application/json` | Requests with body (`POST`) | Strict JSON (unknown fields rejected with `400`) |
| `Idempotency-Key: <key>` | All WRITE admissions | Unique key per intent; same key + same body → same `requestId`; different body → `409` |
| `X-Pocoma-Binding-Token: <token>` | Business READ routes (pots, balances, expenses, `/me/pots`, invitations) | Active binding token obtained via `GET /api/v3/me/binding` |

All API responses include `Cache-Control: no-store`.

## 2. Endpoint Catalog

### Write (`WRITE_URL/api/v3`)

| Method & Route | Input | Nominal Response |
| :--- | :--- | :--- |
| `POST /identity/requests` | `{"operation":"Registration"}` or `{"operation":"Unlink","bindingToken":"...","target":{"issuer":"...","subject":"..."}}` | `202 Accepted`: `{"requestId":"<UUID>"}` + `Location` |
| `POST /commands` | Envelope `{"operation","potId","observedVersion","bindingToken","payload"}` | `202 Accepted`: `{"requestId":"<UUID>"}` + `Location` |

> [!NOTE]
> A `202 Accepted` response confirms only durable admission persistence in `pocoma_write`, never business acceptance or immediate READ projection availability.

### Read (`READ_URL/api/v3`)

| Method & Route | Role | Nominal Response |
| :--- | :--- | :--- |
| `GET /me/binding` | Query active binding for the authenticated identity | `200` `{active, euiVersion, bindingToken}` or `404` if unknown |
| `GET /identity/requests/{id}/result` | Terminal result of an identity request | `200` `{outcome, operation, targetVersion, rejection}` or `404` |
| `GET /commands/{id}/result` | Terminal result of a command | `200` `{outcome, resultingPotVersion, rejection}` or `404` |
| `GET /me/pots?limit=N&cursor=OPAQUE` | Paginated list of accessible pots (`limit` default `20`, max `100`) | `200` `{potIds, nextCursor}` |
| `GET /pots/{id}?minVersion=V` | Current full state of a Pot at version `W >= V` | `200` `{potVersion, label, shareholders, expenses, balances}` or `404` |
| `GET /pots/{id}/versions/{V}` | Exact historical state at version `V` (no substitution) | `200` `{potVersion, label, shareholders, expenses, balances}` or `404` |
| `GET /pots/{id}/versions/{V}/balance` | Exact balances of a Pot at version `V` | `200` `{potId, potVersion, balances}` or `404` |
| `POST /pots/{id}/versions/{V}/expenses/query` | Details for `1` to `50` expenses via `{"references":[...]}` (opaque objects read from the Pot) | `200` `{expenses:[...]}` or `404` |
| `GET /invitations/by-creation-request/{id}` | Invitation link (accessible only to its creator) | `200` `{link}` (`pocoma://invitation/<secret>`) or `404` |
| `POST /invitations/available-shareholders/query` | Eligible slots for `{"token":"<secret>"}` (+ binding header) | `200` `{shareholders:[{shareholderId, name}]}` or `404` |

## 3. Business Operations (`POST /api/v3/commands`)

Common envelope:

```json
{
  "operation": "CreatePot",
  "potId": "<UUID>",
  "observedVersion": 0,
  "bindingToken": "<BINDING_TOKEN>",
  "payload": {}
}
```

| Operation | `observedVersion` | Exact Payload |
| :--- | :--- | :--- |
| `CreatePot` | `0` (new Pot UUID) | `{"label": "..."}` |
| `UpdatePotDetails` | Current version | `{"label": "..."}` |
| `DeletePot` | Current version | `{}` |
| `AddShareholders` | Current version | `{"targets": [{"id": "<UUID>", "name": "...", "attachment": "FREE\|SELF", "weight": {"numerator":"1","denominator":"1"}}]}` |
| `UpdateShareholderDetails` | Current version | `{"targets": [{"id": "<UUID>", "name": "...", "attachment": "KEEP\|SELF\|DETACH"}]}` |
| `UpdateShareholderWeights` | Current version | `{"targets": [{"id": "<UUID>", "weight": {"numerator":"...","denominator":"..."}}]}` |
| `DeleteShareholder` | Current version | `{"id": "<UUID>"}` |
| `CreateExpense` | Current version | `{"id": "<UUID>", "label": "...", "date": "YYYY-MM-DD", "amount": {"numerator":"...","denominator":"..."}, "payerId": "<UUID>", "participants": [{"id": "<UUID>", "weight": null\|{...}}]}` |
| `UpdateExpenseDetails` | Current version | `{"id": "<UUID>", "label": "...", "date": "YYYY-MM-DD"}` |
| `UpdateExpenseFinancials` | Current version | `{"id": "<UUID>", "amount": {...}, "payerId": "<UUID>", "participants": [{"id": "<UUID>", "weight": null\|{...}}]}` |
| `DeleteExpense` | Current version | `{"id": "<UUID>"}` |
| `CreateInvitation` | `0` | `{"expiresAt": "YYYY-MM-DDTHH:MM:SSZ"}` (UTC with `Z` suffix, ≤ microsecond precision, strictly in the future) |
| `AttachShareholderViaInvitation` | `0` | `{"invitationToken": "<secret>", "shareholderId": "<UUID>"}` |

### Rationals & Constraints

- **No floating-point numbers**: amounts and weights use `{"numerator":"10","denominator":"3"}` on input and `{"n":"10","d":"3"}` on READ output. Positive balances are creditors, and their sum is always zero.
- **Request bounds**: max admission body `262,144` bytes; expense query batch `1`–`50`; `/me/pots` pagination default `20`, max `100`.

## 4. HTTP Status Codes & Error Format

Errors follow the format `{"code": "...", "message": "...", "traceId": "..."}`.

| Status | Meaning |
| :---: | :--- |
| `400` | Invalid JSON, unknown fields, or invalid values/cursors/references |
| `401` | Missing, invalid, or expired JWT, or incorrect issuer/audience |
| `404` | Missing resource, unmaterialized result, or unauthorized access (indistinguishable for privacy) |
| `409` | `Idempotency-Key` reused with a different request body |
| `413` / `415` | Payload too large or unsupported `Content-Type` |
| `503` | HTTP/SQL capacity saturated or READ maintenance active; retain `requestId` if admission was already sent |
