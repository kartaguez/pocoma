# Pocoma

Pocoma is an application for managing shared pots: a user creates a pot, adds participants, records expenses, and the application computes balances between participants. The project is also an architecture playground for validating a clean separation between domain, application engine, persistence, HTTP API, projection worker, and observability.

## How It Works

The Spring Boot HTTP API admits mutations asynchronously through `POST /api/v1/commands`. It stores an immutable `RecordedCommand` and returns `202 Accepted`; a separate Command consumption runtime later mutates the versioned Pot state and appends a business Event atomically. The former synchronous Pot, Expense and Balance GET endpoints were retired by PCL.4.

Each pot has a global version. Writes require an `expectedVersion`, which protects commands against concurrent updates. Canonical projection reads address an exact projection identity and version; they do not fall back to the write model.

READ_POT and POT_BALANCES are canonical projections. A Command worker persists business state and a
business Event atomically in `business_event_outbox`. Event consumption materializes exact,
versioned work in `projection_tasks`; the ProjectionTask runtime then executes the matching producer
through `ProjectionEngineService` and publishes only to the canonical `pocoma_read.projection_root`,
`projection_artifact` and `projection_failure` store.

## Architecture

The Maven application lives in `app/`. The rest of the repository contains test scripts and local observability assets.

```text
app/
  domain/                         Pure business model
  domain-policy/                  Business authorization rules
  domain-projection/              Pure balance computation
  engine/                         Use cases, ports, events, logical transactions
  infra-persistence-jpa/          JPA adapters for H2/PostgreSQL
  infra-tx-spring/                Spring transaction adapter
  locator-consumption-command/   Command specialization of generic consumption
  observability/                  Trace and measurement abstractions
  supra-http-rest-spring/         Asynchronous Command admission HTTP adapter
  runtime-command-consumption-worker/
                                  Durable Command processing runtime
  runtime-event-consumption-worker/
                                  Durable Event-to-Task consumption runtime
  runtime-latest-known-version-consumption-worker/
                                  Direct transactional Event-to-latest-known runtime
  runtime-task-consumption-worker/
                                  Canonical ProjectionTask execution runtime
  runtime-web-api/                API-only Spring Boot runtime

docker/                           Prometheus and Grafana
scripts/bruno/                    Bruno HTTP collection
scripts/k6/                       k6 load tests
```

The core design choice is hexagonal architecture: `domain` depends on nothing, `engine` depends on ports, and `infra-*` / `supra-*` modules plug in technologies. `runtime-web-api` admits durable Commands; `runtime-command-consumption-worker` is the sole runtime executor of primary mutations. Dedicated Event and ProjectionTask runtimes implement the canonical projection chain.

This separation addresses several technical challenges:

- Keep business rules testable without Spring, JPA, or HTTP.
- Make optimistic concurrency explicit through pot versions.
- Support versioned reads without mixing the write model and projections.
- Store projection work durably so workers can absorb bursts with back pressure.
- Observe projection lag instead of hiding it.

## Local Run

Start PostgreSQL:

```bash
cd app
docker compose -f docker-compose.postgres.yml up -d
```

Start the dedicated API and worker runtimes:

```bash
cd app
./mvnw -pl runtime-web-api spring-boot:run \
  -Dspring-boot.run.profiles=postgres

./mvnw -pl runtime-command-consumption-worker spring-boot:run \
  -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.arguments="--pocoma.command-consumption.enabled=true"

./mvnw -pl runtime-event-consumption-worker spring-boot:run \
  -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.arguments="--pocoma.event-consumption.enabled=true --pocoma.event-consumption.projection-types=READ_POT,POT_BALANCES"

./mvnw -pl runtime-latest-known-version-consumption-worker spring-boot:run \
  -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.arguments="--pocoma.latest-known-version-consumption.enabled=true --pocoma.latest-known-version-consumption.poll-interval=250ms"

./mvnw -pl runtime-task-consumption-worker spring-boot:run \
  -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.arguments="--pocoma.projection-task-consumption.enabled=true --pocoma.projection-task-consumption.catalog-projection-types=READ_POT,POT_BALANCES --pocoma.projection-task-consumption.locator-projection-types=READ_POT,POT_BALANCES"
```

### Docker Compose Modes

The supported Compose mode runs one API runtime, one Command consumption worker, one dedicated latest-known
consumer, two Event consumption workers, two canonical ProjectionTask workers, PostgreSQL,
Prometheus, and Grafana:

```bash
docker compose -f docker-compose.distributed.yml up --build
```

Stop the stack with:

```bash
docker compose -f docker-compose.distributed.yml down
```

The distributed mode uses `jdbc:postgresql://postgres:5432/pocoma`, and every Java runtime uses
the `postgres` Spring profile. Event and Task
consumption workers are split by their respective segment properties, sourced
from `POCOMA_SEGMENT_COUNT`, so the services ending in `-0` and `-1` own
segments `0/2` and `1/2` with the default count.

Optional environment overrides:

```bash
POSTGRES_DB=pocoma
POSTGRES_USER=pocoma
POSTGRES_PASSWORD=pocoma
API_PORT=8080
POCOMA_SEGMENT_COUNT=2
```

The canonical workers derive projection work from Event and ProjectionTask consumption; the
distributed composition has no pipeline id or pipeline-version setting.

Useful endpoints:

- API: `http://localhost:8080`
- Actuator Prometheus: `http://localhost:8080/actuator/prometheus`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin` / `admin`)

## Validating The Choices

Validation is intentionally layered.

Maven tests cover business rules, use cases, JPA adapters, HTTP admission, and the supported
Command, Event, ProjectionTask and LatestKnownVersion runtimes:

```bash
cd app
./mvnw test
```

The former query and mutation requests in the Bruno collection and the existing k6 scenarios are
historical suites for removed synchronous HTTP paths. Their READMEs mark that limitation
explicitly. They are not a supported alternative to `POST /api/v1/commands`.

The historical k6 suite can still be inspected with:

```bash
cd app
k6 run ../scripts/k6/smoke.js
k6 run ../scripts/k6/stress.js
k6 run ../scripts/k6/projection_backpressure.js
```

Those scripts document the former valid-command, concurrent-conflict, inconsistent-request and
projection-backpressure workloads. They require migration to asynchronous admission before they can
serve as executable validation of the current runtime.

## Observability

Each HTTP request receives a `traceId`, propagated through logs and projection tasks. Logs can therefore reconstruct the full chain initiated by a user: HTTP request, command admission, commit, event publication, worker execution, and projection persistence.

Prometheus metrics track, among other things:

- command persistence latency;
- projection outbox and task backlog;
- delay between command commit and worker processing start;
- projection processing duration;
- end-to-end latency from persisted command to persisted projection;
- distribution of the signed distance between known and projected versions, without interpreting it as continuity or readiness;
- retries and failures observed by the worker or load tests.

These metrics address the main risk of the asynchronous projection architecture: a projection can temporarily lag behind. Rather than assuming this lag is negligible, the application measures it.

## Design Notes

- Start architecture work from `docs/README.md`, the index of current canonical documents.
- Command admission and execution are separate transactions. The winning execution transaction writes Pot state, business Events, provenance, fencing and terminal state atomically.
- The write-side closure is documented in `docs/architecture/write-side-closure.md`.
- The current Event/Task Balance pipeline is documented in
  `docs/architecture/consumption-event-pull-runtime.md` and
  `docs/architecture/consumption-task-balance-runtime.md`; `docs/projection-workers.md` is historical.
- Event and Task Balance workers are partitioned by stable `potId` hash through
  `pocoma.projection.worker.segment-index` and `segment-count`.
- Canonical exact projection reads remain isolated from the retired legacy HTTP Query stack.
- PostgreSQL is enabled with the Spring `postgres` profile; H2 remains the default local mode.
- Flyway is the source of truth for the PostgreSQL schema, while Hibernate validates the schema in PostgreSQL mode.
- Command admission uses OAuth2 Resource Server identity; legacy caller headers are no longer exposed.
