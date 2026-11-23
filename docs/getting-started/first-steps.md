# First Steps

This self-contained walkthrough takes you through your first complete cycle on Pocoma: **start the instance**, **authenticate**, **submit a business command**, **retrieve its result**, and **read the resulting projection**.

> [!TIP]
> To explore visually without writing requests by hand, you can also run `./pocoma-local gui --instance demo` (Bruno GUI preconfigured with OIDC PKCE) or `./pocoma-local bruno --instance demo` (automated collection). See [Local Development & Bruno](../guides/local-development.md).

## 1. Start Pocoma & Retrieve Local URLs

Start the `demo` instance and verify it is `READY`:

```bash
./pocoma-local start --instance demo
./pocoma-local status --instance demo
```

Note the three URLs from the output (host ports are allocated per instance):
- `WRITE_URL` (e.g., `http://127.0.0.1:<port_write>`)
- `READ_URL` (e.g., `http://127.0.0.1:<port_read>`)
- `ISSUER` (e.g., `http://localhost:<port_keycloak>/realms/pocoma`)

## 2. Authenticate & Obtain Your `bindingToken`

Pocoma uses **Keycloak** (OIDC Authorization Code + PKCE S256, public client `pocoma-functional-tests`, scope `openid`). Local test accounts are `third`, `guest`, and `bruno` (password `pocoma`).

### Option A — Via `./pocoma-local login` or Bruno GUI

```bash
./pocoma-local login --instance demo
```

Or open Bruno GUI (`./pocoma-local gui --instance demo`) and run `00 Login Owner` with `third` / `pocoma` (see [Authentication & Binding](../guides/authentication.md)).

### Option B — Application Binding (`Registration` → `bindingToken`)

Once you have an `ACCESS_TOKEN` from `ISSUER`, check whether your OIDC identity is already bound to a Pocoma user:

```bash
curl -sS -i "$READ_URL/api/v3/me/binding" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

- If the response is **`200 OK`**, extract `bindingToken` from the returned JSON.
- If the response is **`404 Not Found`**, register your identity on the WRITE side:

```bash
IDEMP_REG=$(python3 -c 'import uuid; print(uuid.uuid4())')
curl -sS -X POST "$WRITE_URL/api/v3/identity/requests" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMP_REG" \
  -d '{"operation":"Registration"}'
```

The **`202 Accepted`** response returns `{"requestId":"<REG_REQUEST_ID>"}`. Poll its asynchronous result until `COMPLETED`:

```bash
curl -sS "$READ_URL/api/v3/identity/requests/$REG_REQUEST_ID/result" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Then query `GET $READ_URL/api/v3/me/binding` again to retrieve your active `bindingToken`.

## 3. Submit a Business Command (`CreatePot`)

Generate a unique idempotency key (`IDEMP_CMD`) and submit `CreatePot` with `observedVersion: 0` and your `BINDING_TOKEN`. **Do not send a `potId` field**: WRITE assigns the `PotId` server-side and returns it in the terminal command result.

```bash
IDEMP_CMD=$(python3 -c 'import uuid; print(uuid.uuid4())')

curl -sS -i -X POST "$WRITE_URL/api/v3/commands" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMP_CMD" \
  -d "{
    \"operation\": \"CreatePot\",
    \"observedVersion\": 0,
    \"bindingToken\": \"$BINDING_TOKEN\",
    \"payload\": {\"label\": \"Trip\"}
  }"
```

The **`202 Accepted`** response contains `{"requestId":"<CMD_REQUEST_ID>"}` (along with the `Location` header). It confirms durable admission persistence in `pocoma_write`, not yet business execution.

## 4. Retrieve the Command Result & Assigned `PotId`

Poll the READ result endpoint with `CMD_REQUEST_ID` until `200 OK` (while unmaterialized, the API returns `404`):

```bash
curl -sS "$READ_URL/api/v3/commands/$CMD_REQUEST_ID/result" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

A successful execution returns:

```json
{
  "requestId": "...",
  "operation": "CreatePot",
  "potId": "<ASSIGNED_POT_ID>",
  "outcome": "COMPLETED",
  "resultingPotVersion": 1,
  "rejection": null,
  "createdResources": {
    "potId": "<ASSIGNED_POT_ID>",
    "expenseId": null,
    "shareholders": null
  }
}
```

Extract `POT_ID` from `createdResources.potId` (or `potId`) and note `resultingPotVersion` (`1` here).

## 5. Read the Resulting Projection

Query the current Pot projection requiring at least the produced version (`minVersion=1`) and passing your `bindingToken` in the `X-Pocoma-Binding-Token` header:

```bash
curl -sS "$READ_URL/api/v3/pots/$POT_ID?minVersion=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

The `200 OK` response returns the full materialized view from `pocoma_read` (`potVersion`, `label`, `shareholders`, `expenses`, `balances`).

## Next Steps

- [Authentication & Binding](../guides/authentication.md)
- [Commands & Asynchronous Results](../guides/commands.md)
- [READ Queries & Projections](../guides/queries.md)
- [Public API `/api/v3` Reference](../reference/api.md)
