# L3/I3 — Internal Identity DEV runtime

This bench is controlled internal admission. `CONTROLLED_INTERNAL` is neither external
authentication nor Link consent. No HTTP admission, WRITE client read, READ projection,
real Pot authority, I4 or I5 activation is delivered here.

Use a new disposable PostgreSQL server/database pair, exact authorized TCP targets,
non-public schemas, and four distinct non-admin roles. Admin prepares once; runtime
never migrates or resets. Never reuse an existing V2 target. Preserve the prepared
configuration for restart. Credentials belong in environment variables or a private
external file (mode 0600), never argv or versioned files.

The existing DEV profile requires an external configuration. In addition to the existing
four pool credentials/URLs, pilot identities and seven-field `dev-targets` tuples, set:

```properties
pocoma.v3.worker.enabled=true
pocoma.v3.worker.environment=DEV
pocoma.v3.worker.identity-authority=real
pocoma.v3.worker.identity-request=true
pocoma.v3.worker.fixture-identity=false
pocoma.v3.worker.command-enabled=false
pocoma.v3.worker.technical-probes=false
pocoma.v3.worker.worker-name=l3-identity
pocoma.v3.worker.worker-limit=4
pocoma.v3.worker.consumption-limits=IdentityRequest=4
pocoma.v3.worker.partitions=IdentityRequest=0|1|2|3
```

`dev-targets`: `host,clientPort,database,schema,role,serverAddress,serverPort` for
WRITE and READ, separated by `;`. WRITE Business/Control must use exactly the same
physical role/target/credentials, likewise READ; WRITE and READ databases differ.
The existing pool sizing and renewal margins remain mandatory. An invalid configuration
is refused; this lot does not qualify different sizing or temporal budgets.

Administrative environment uses the existing `POCOMA_I5_ADMIN_URL`,
`POCOMA_I5_ADMIN_USER`, `POCOMA_I5_ADMIN_PASSWORD`, `POCOMA_I5_REACTIVATION_ROLE`,
`POCOMA_I5_REACTIVATION_PASSWORD`, `POCOMA_I5_INJECTOR_ROLE`,
`POCOMA_I5_INJECTOR_PASSWORD`. Supply them without shell tracing. For container psql,
`POCOMA_I5_PSQL_CONTAINER` must identify the same disposable TCP server. Preparation
refuses existing roles/databases before its first DDL. It installs WRITE V1–V6,
READ technical V1, canonical routing and explicit real grants, without a fixture.

Build from `app/`. The normal packaging command is:

```sh
./mvnw -pl runtime-command-consumption-worker -am package
```

A failed historical transitive gate is a failed gate; do not retry it unchanged to
obtain a different result. A filtered packaging contribution must be identified as
such in its receipt and never represented as canonical PASS.

From the repository root, with your private external configuration:

```sh
python3 -B scripts/v3/worker-dev.py prepare --config /private/tmp/l3-dev.properties
java -jar app/runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar --spring.profiles.active=v3-consumption-worker-dev --spring.config.additional-location=file:/private/tmp/l3-dev.properties
```

While the worker is running, in another terminal:

```sh
python3 -B scripts/v3/worker-dev.py demo-identity --config /private/tmp/l3-dev.properties
```

The demo uses private temporary intention/token files and distinct EUI keys per run.
It verifies two Registrations, Link, other-Unlink, re-Link with a new token,
self-Unlink, last-Unlink preserving User, then a terminal rejection. Tokens never
appear in result output. `identity-token` is a confidential DEV operator helper
requiring admin credentials; its input identifies one EUI and its output is a new
0600 file. It is not a client endpoint or a substitute for L4/L5.

For a single request, `inject-identity --intention-file <private-file>` accepts:

```properties
request-id=<UUID>
created-at=<ISO instant, at most six fractional digits>
operation=REGISTRATION
requester.issuer=<exact issuer>
requester.subject=<exact subject>
target.issuer=<same issuer for Registration>
target.subject=<same subject for Registration>
```

Link/Unlink use their exact target and a `binding-token` in that private file.
The request and captured provenance are immutable. Same UUID plus the same decoded
intention/provenance (including its original timestamp), independently of file layout, observes the original request/result; changed intention under that
UUID is refused. Insertion creates PENDING only, never a slot. `verify-identity
--request-id <UUID>` observes terminal evidence without exposing a token/UserId.

Stop using the existing `worker-dev.py stop --pid <PID> --log-file <private-log>
--config <private-config>` command. Start the same jar/config again without prepare.
Previously terminal requests and their result/events/positions must remain identical;
a PENDING admitted while stopped is discovered after restart. Shutdown evidence must
show physical drain; an inconclusive shutdown is not a clean-stop proof.

The monolith accepts the same DEV configuration/profile through its existing root.
Historical Pot fixture benches explicitly select `identity-authority=fixture` and
`command-enabled=true`; the real mode refuses them. The pilot profile remains inert.
Historical `i5_r05`/`i5_r12` reservations remain open.
