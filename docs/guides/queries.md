# READ Queries & Projections

Reads are served exclusively by `http-read` (`READ_URL/api/v3`) from the materialized `pocoma_read` database. All read routes return `Cache-Control: no-store`.

## 1. Required Headers

- `Authorization: Bearer <access_token>` on all `/api/v3` routes.
- `X-Pocoma-Binding-Token: <bindingToken>` on business routes (pots, balances, expenses, `/me/pots` list, invitation lookup).

## 2. Read Current Pot State (`minVersion`)

```bash
curl -sS "$READ_URL/api/v3/pots/$POT_ID?minVersion=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

- `minVersion=V` guarantees that the returned version `W` satisfies `W >= V`.
- If a concurrent write advanced the Pot to `W > V`, use that returned `W` for subsequent related queries (history or balances) to keep a consistent view.
- Until the projection reaches `minVersion=V`, or if the user is not authorized, the API returns `404 Not Found`.

## 3. Query Exact History & Balances

### Exact historical version

```bash
curl -sS "$READ_URL/api/v3/pots/$POT_ID/versions/$VERSION" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

Returns the exact state at `VERSION` (never substituting a newer version).

### Exact balances for a version

```bash
curl -sS "$READ_URL/api/v3/pots/$POT_ID/versions/$VERSION/balance" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

Balances are returned as rationals `{"n":"5","d":"3"}`. A positive balance indicates a creditor; the exact sum of balances in a Pot is always zero.

### Expense details via opaque references

The references returned inside a Pot view are opaque objects to be echoed back verbatim (batch of `1` to `50` references):

```bash
curl -sS -X POST "$READ_URL/api/v3/pots/$POT_ID/versions/$VERSION/expenses/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"references":[<opaque reference read from Pot@VERSION>]}'
```

## 4. Paginate Your Pots (`/me/pots`)

```bash
curl -sS "$READ_URL/api/v3/me/pots?limit=20" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Pocoma-Binding-Token: $BINDING_TOKEN"
```

- `limit`: default `20`, maximum `100`.
- `cursor`: opaque value returned in `nextCursor` (never modify or reconstruct it).

## 5. Opaque Privacy (`404`) & Load Protection (`503`)

- **Opaque `404` privacy**: a nonexistent resource, an unmaterialized projection, and a resource owned by another user all return `404 Not Found` with a non-discriminating `{code, message, traceId}` body.
- **Deletion (`DeletePot`)**: after a `DeletePot` completes (`COMPLETED`) at version `D`, both the current state and version `D` return `404`, while earlier versions `V < D` remain queryable by historical authorized members.
- **Load protection (`503`)**: `http-read` validates the HTTP protocol and token **before** acquiring one of its `8` concurrency permits (`Q=8`); under saturation or READ maintenance, the API responds `503 Service Unavailable`.
