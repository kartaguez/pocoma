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
The wrapper passes `identity_read_role` to the real-mode grant scripts. Direct
administrative psql use must supply that exact runtime READ role too. PUBLIC
rights on Identity tables/columns, or Identity WRITE rights held by the READ
principal, are refused rather than normalized.

Build from `app/`. The normal packaging command is:

```sh
./mvnw -pl runtime-command-consumption-worker -am package
```

A failed historical transitive gate is a failed gate; do not retry it unchanged to
obtain a different result. A filtered packaging contribution must be identified as
such in its receipt and never represented as canonical PASS.

For a reproducible internal DEV build, this bounded packaging command runs the
I3 participant, PostgreSQL, composition and tool tests (Docker required):

```sh
./mvnw -pl runtime-command-consumption-worker -am -Dtest=ExecuteIdentityRequestTest,IdentityRequestExecutionPostgresTest,IdentityPublicationPostgresTest,IdentityDiscoveryPostgresTest,IdentityPrivilegesPostgresTest,V3IdentityRequestCompositionPostgresTest,V3WorkerDevelopmentToolsTest -Dsurefire.failIfNoSpecifiedTests=false package
```

Record it as filtered I3 feedback/packaging. Keep the unfiltered COMMAND,
monolith and Architecture gate verdict alongside it; this command does not
satisfy that gate or lift i5_r05/i5_r12. The execution report records the packaging
commands actually used for the delivered jar.

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
These inputs follow Java Properties syntax, including Unicode escapes for keys
that cannot be represented directly in its ISO-8859-1 input encoding.
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

## I3 counter-audit corrections: trust and diagnostics

The [counter-audit](L3_I3_Contradictory_Audit_Report.md) remains FAIL historically;
[the resolution report](L3_I3_Findings_Resolution_Report.md) records the separate
before/after evidence. This does not approve I4 or close i5_r05/i5_r12.

Real Identity DEV uses a dedicated closed WRITE database. Lifecycle/Discovery
provisioning refuses every user view/materialized view readable by the named READ
principal on WRITE, across all schemas (including PUBLIC and column grants),
and every user SECURITY DEFINER routine outside system schemas. Even an invoker
view is refused: it is not part of the delivered profile. No view dependency graph
or SQL-function body inspection is claimed to establish confidentiality. Shipped
V6 invoker functions remain usable by their legitimate writers; READ does not
gain their underlying table privileges. READ must have an explicit nonprivileged
role with no memberships in the WRITE catalog too; absent or divergent role
catalogs are not silently accepted as safe.

Run lifecycle before Discovery when first provisioning; revalidate the combined
profile with `grant-l2-discovery.sql` and `grant-l2-worker-dev.sql`. These two
administrative stages certify the explicitly named reactivation/injector roles.
The runtime knows WRITE and READ, not auxiliary credentials; its startup check
does not replace the administrative certification of those auxiliary roles.
The scripts refuse excess effective DELETE/TRUNCATE/TRIGGER on all seven Identity
tables; the injector also has an exhaustive SELECT/INSERT column allowlist.
They roll back on refusal and never silently normalize grants. An operator must
explicitly REVOKE an accidental grant (including its PUBLIC/membership source),
remove an unauthorized intermediary, rerun those stages, and restart the pools.
TRUNCATE is not protected by the V6 UPDATE/DELETE immutability triggers.

The schema/object owner, SQL provisioner, server administrator and installed
extensions are trusted. A PostgreSQL superuser can bypass or change these controls.
No continuous surveillance or protection against such an administrator is claimed.
The injector can read admitted tokens/provenance by design; its credentials and
files remain confidential, with no per-request isolation implied.

Start the disposable PostgreSQL server with an error prefix carrying SQLSTATE,
PID and XID, for example append these server arguments to its usual launch:

```sh
-c 'log_line_prefix=%m [%p] xid=%x state=%e '
```

For real mode, `worker-dev.py prepare` now invokes the explicit administrative
`configure-l3-identity-logging.sql` stage on the new WRITE database before grants.
It installs database defaults for new sessions: `log_error_verbosity=terse`,
`log_min_error_statement=panic`, no statement/duration/sampled-transaction logs,
and zero logged bind-parameter lengths. A manual administrative preparation must
run this same stage with `expected_database`, `expected_schema`, `expected_admin`
and the exact non-public search_path. It refuses an insufficient server prefix.
Existing sessions retain their old settings: reconnect every pool and operator
connection. Startup checks effective settings on both actual WRITE pools and
refuses an unsafe role/session override. Preparation never changes ALTER SYSTEM
or the logging settings of another database.

The UNIQUE constraint on `v3_eui_version.binding_token` is unchanged. PostgreSQL
normally writes the conflicting value in its UNIQUE DETAIL; CHECK DETAIL can
include the entire failing request row. Parameter-length limits and the Java
exception sanitizer alone do not suppress these server-generated details.
The tested policy retains ERROR, SQLSTATE, PID/XID and safe constraint names,
while removing DETAIL/CONTEXT and raw statement traces for these failures.
Use application request UUIDs and these technical identifiers to correlate errors;
never temporarily enable raw SQL/bind or row details on this sensitive database.

New tests exercise PostgreSQL stderr through the Docker log collector and Java
exception graphs. A different collector, audit extension, tracing agent, manually
logged literal, client debug logger or production logging pipeline needs its own
qualification. Do not infer zero secret exposure in an untested pipeline. Never
export raw server logs to establish absence: inspect markers in memory and export
only booleans, SQLSTATE/correlation, and explicitly redacted diagnostics.

The delivered WRITE Business owner is JPA in both roots. A JDBC ConnectionHolder
prebound outside a transaction is refused before the participant callback; tests
protect this composition after commit/rollback and confirm physical locks with
NOWAIT. A manually assembled DataSourceTransactionManager owner retaining that
holder across attempts remains outside the delivered runtime composition and is
not certified here. Any future change of that manager/composition must first
address the conditional CT-001 reproduction. The common L2 owner and transaction
semantics are unchanged by these corrections.
