# Commands & Asynchronous Results

Every business mutation in Pocoma goes through HTTP admission on `http-write` (`WRITE_URL/api/v3/commands`), followed by asynchronous execution by `command-worker` and result materialization by `results-worker`.

## 1. Admission & Idempotency Rules

Every `POST /api/v3/commands` request must include:
- `Authorization: Bearer <access_token>`
- `Content-Type: application/json`
- `Idempotency-Key: <unique key per intent>`

### `Idempotency-Key` Behavior

- Reusing **the same key and exact same body** lets you safely retry an admission whose network response was lost, returning the same `requestId`.
- Reusing **the same key with a different body** returns **`409 Conflict`**.
- Idempotency keys are isolated per caller identity.

### Meaning of `202 Accepted`

The `202 Accepted` response contains only `{"requestId":"<UUID>"}` (plus the `Location` header). It guarantees durable persistence of the request in `pocoma_write`, **never** business acceptance or immediate READ projection availability.

## 2. Polling the Terminal Result

Query the READ runtime (`READ_URL`) with the returned `requestId`:

```bash
curl -sS "$READ_URL/api/v3/commands/$REQUEST_ID/result" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

- While unmaterialized (or if the ID is unknown / belongs to another user), the endpoint returns **`404 Not Found`**.
- Once materialized (`200 OK`), `outcome` is either:
  - **`COMPLETED`**: command applied, with `resultingPotVersion` (positive integer).
  - **`REJECTED`**: explicit business rejection (`rejection`), with no transport error.

> [!TIP]
> After a `COMPLETED` outcome returning `resultingPotVersion = V`, pass `?minVersion=V` when reading the Pot to ensure the returned projection includes your mutation.

## 3. Common Envelope & Version Control (`observedVersion`)

All commands share the same JSON envelope:

```json
{
  "operation": "CreatePot",
  "potId": "<client-generated UUID>",
  "observedVersion": 0,
  "bindingToken": "<BINDING_TOKEN>",
  "payload": {
    "label": "Trip"
  }
}
```

- For `CreatePot`, `CreateInvitation`, and `AttachShareholderViaInvitation`, `observedVersion` is `0`.
- For mutations on an existing Pot, `observedVersion` must match the current observed version; on concurrent conflict, the command is `REJECTED` and the client must re-read the Pot before submitting a new intent.

## 4. Business Command Examples

### Add a shareholder (`AddShareholders`)

```json
{
  "operation": "AddShareholders",
  "potId": "<POT_ID>",
  "observedVersion": 1,
  "bindingToken": "<BINDING_TOKEN>",
  "payload": {
    "targets": [
      {
        "id": "<SHAREHOLDER_UUID>",
        "name": "Alice",
        "attachment": "FREE",
        "weight": {"numerator": "1", "denominator": "1"}
      }
    ]
  }
}
```

`attachment` values on creation: `FREE` or `SELF`. For `UpdateShareholderDetails`: `KEEP`, `SELF`, `DETACH`.

### Create an expense with rational amounts (`CreateExpense`)

Pocoma uses **no floating-point numbers** for amounts: all amounts and weights use exact rationals `{"numerator":"...","denominator":"..."}` on input (and `{"n":"...","d":"..."}` on READ output). A `weight` of `null` on a participant uses their default shareholder weight.

```json
{
  "operation": "CreateExpense",
  "potId": "<POT_ID>",
  "observedVersion": 2,
  "bindingToken": "<BINDING_TOKEN>",
  "payload": {
    "id": "<EXPENSE_UUID>",
    "label": "Groceries",
    "date": "2026-11-15",
    "amount": {"numerator": "10", "denominator": "3"},
    "payerId": "<PAYER_SHAREHOLDER_UUID>",
    "participants": [
      {"id": "<SHAREHOLDER_1_UUID>", "weight": null},
      {"id": "<SHAREHOLDER_2_UUID>", "weight": null}
    ]
  }
}
```

### Invite a participant (`CreateInvitation` → `AttachShareholderViaInvitation`)

1. The owner submits `CreateInvitation` (`observedVersion: 0`, `payload: {"expiresAt": "2026-11-20T12:00:00Z"}` — UTC timestamp with `Z` suffix, ≤ microsecond precision, strictly in the future at execution time).
2. Once the result is `COMPLETED`, the creator retrieves the `pocoma://invitation/<secret>` link via `GET /api/v3/invitations/by-creation-request/{requestId}`.
3. The invitee lists eligible slots via `POST /api/v3/invitations/available-shareholders/query` and submits `AttachShareholderViaInvitation` (`observedVersion: 0`, `payload: {"invitationToken": "<secret>", "shareholderId": "<UUID>"}`).

For the complete list of all 13 operations and payloads: [Public API `/api/v3` Reference](../reference/api.md).
