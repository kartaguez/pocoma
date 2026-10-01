# Pocoma Bruno collection

`00 Pot E2E` is the canonical reference flow for the asynchronous API:

```text
CreatePot -> COMMAND_RESULT -> Pot@V1
UpdatePotDetails -> COMMAND_RESULT -> Pot@V2 -> Pot@V1 unchanged
```

Select the `Local` environment, set the secret variable `accessToken` to a real bearer token and set
`pocomaUserId` to the internal Pocoma user mapped from that token's external identity. Bruno stores
the secret outside the checked-in environment file; no token is versioned. The token must provide
the authorities translated to `POT_CREATE`, `POT_UPDATE` and `POT_VIEW` (with the current translator:
`pocoma:pot:create`, `pocoma:pot:update`, `pocoma:pot:view`).

Run requests in sequence. Both Command Result requests deliberately accept `404` while the pipeline
is converging. Re-run the same request until it returns `200 APPLIED`; its post-response script then
captures `potId` and `v1` or `v2` for the next steps. This explicit polling keeps every asynchronous
boundary visible for diagnosis.

The remaining folders describe legacy synchronous routes and are retained only as historical assets;
they are not an alternative write path.

With Bruno CLI installed, open `scripts/bruno` or run:

```bash
bru run "00 Pot E2E" --env Local
```

A one-shot CLI run assumes workers converge before the dependent reads. For a real asynchronous
environment, the recommended diagnostic workflow is to execute the requests individually and re-run
steps 02 and 05 as described above.
