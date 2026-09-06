# Pocoma

Pocoma is an application for managing shared pots: a user creates a pot, adds participants, records expenses, and the application computes balances between participants. The project is also an architecture playground for validating a clean separation between domain, application engine, persistence, HTTP API, projection worker, and observability.

## How It Works

The Spring Boot HTTP API admits mutations asynchronously through `POST /api/v1/commands`. It stores an immutable `RecordedCommand` and returns `202 Accepted`; a separate Command consumption runtime later mutates the versioned Pot state and appends a business Event atomically. Queries continue to read views of that state: accessible pots, pot details, expenses, balances, and balances for the calling user.

Each pot has a global version. Writes require an `expectedVersion`, which protects commands against concurrent updates. Reads can target a specific version or, by default, the current version. Lists hide deleted elements; direct views can return a deleted entity with its `deleted` flag.

Balances are projections. A command persists the business state and writes a business event into `business_event_outbox`. Task-builder workers coalesce those events into `projection_tasks`, and projection workers poll those tasks to compute balances. This keeps commands fast, makes back pressure explicit in the database, and makes projection lag measurable.

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
  infra-event-publisher-spring/   Spring event publishing for retained projection flows
  observability/                  Trace and measurement abstractions
  supra-http-rest-spring/         Query and asynchronous Command admission HTTP adapters
  runtime-command-consumption-worker/
                                  Durable Command processing runtime
  supra-worker-balance-calculation-events-spring/
                                  Spring event-driven balance calculation worker
  runtime-web-api/                API-only Spring Boot runtime
  runtime-business-events-outbox-dispatcher/
                                  Projection task builder runtime
  runtime-balance-calculation-tasks-dispatcher/
                                  Projection task executor runtime
  runtime-monolith/               Spring Boot monolith composition

docker/                           Prometheus and Grafana
scripts/bruno/                    Bruno HTTP collection
scripts/k6/                       k6 load tests
```

The core design choice is hexagonal architecture: `domain` depends on nothing, `engine` depends on ports, and `infra-*` / `supra-*` modules plug in technologies. `runtime-web-api` admits durable Commands and serves queries; `runtime-command-consumption-worker` is the sole runtime executor of primary mutations. The projection runtimes and `runtime-monolith` remain transitional read/projection compositions.

This separation addresses several technical challenges:

- Keep business rules testable without Spring, JPA, or HTTP.
- Make optimistic concurrency explicit through pot versions.
- Support versioned reads without mixing the write model and projections.
- Store projection work durably so workers can absorb bursts with back pressure.
- Observe projection lag instead of hiding it.

## Local Run

Default local mode with H2:

```bash
cd app
./mvnw -pl runtime-monolith -am install -DskipTests
./mvnw -pl runtime-monolith spring-boot:run
```

PostgreSQL mode:

```bash
cd app
docker compose -f docker-compose.postgres.yml up -d
./mvnw -pl runtime-monolith -am install -DskipTests
./mvnw -pl runtime-monolith spring-boot:run -Dspring-boot.run.profiles=postgres
```

Split API/worker mode:

```bash
cd app
./mvnw -pl runtime-web-api spring-boot:run -Dspring-boot.run.profiles=postgres

./mvnw -pl runtime-business-events-outbox-dispatcher spring-boot:run \
  -Dspring-boot.run.profiles=postgres,segment-0-of-2

./mvnw -pl runtime-balance-calculation-tasks-dispatcher spring-boot:run \
  -Dspring-boot.run.profiles=postgres,segment-1-of-2
```

`runtime-monolith` still supports the `api` and `worker` profiles for local experiments, but the dedicated runtimes match the target deployment shape.

### Docker Compose Modes

The repository provides three root-level Compose files for the main runtime
shapes. Run only one mode at a time because they all publish the same local
ports: API `8080`, Prometheus `9090`, and Grafana `3000`.

Monolith with in-memory H2, Spring events, in-process balance worker,
Prometheus, and Grafana:

```bash
docker compose -f docker-compose.monolith-h2.yml up --build
```

Monolith with PostgreSQL, Spring events, in-process balance worker, Prometheus,
and Grafana:

```bash
docker compose -f docker-compose.monolith-postgres.yml up --build
```

Distributed mode with one API runtime, two Event consumption workers, two Task
consumption workers, PostgreSQL, Prometheus, and Grafana. The Balance pipeline
version is part of the projection identity and must be supplied explicitly:

```bash
POCOMA_BALANCE_PIPELINE_VERSION=2 docker compose -f docker-compose.distributed.yml up --build
```

Before switching mode, stop the current stack:

```bash
docker compose -f <compose-file> down
```

The H2 monolith intentionally keeps data in memory. The PostgreSQL monolith and
distributed modes use `jdbc:postgresql://postgres:5432/pocoma`. In distributed
mode, every Java runtime uses the `postgres` Spring profile. Event and Task
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

`POCOMA_BALANCE_PIPELINE_VERSION` is required rather than optional for the
distributed mode and is propagated unchanged to Event, Task, and Query.

Useful endpoints:

- API: `http://localhost:8080`
- Actuator Prometheus: `http://localhost:8080/actuator/prometheus`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin` / `admin`)

## Validating The Choices

Validation is intentionally layered.

Maven tests cover business rules, use cases, JPA adapters, HTTP controllers, the worker, and monolith boot:

```bash
cd app
./mvnw test
```

The query requests in the Bruno collection remain useful for the current read API. Its mutation
requests and the existing k6 scenarios are historical suites for the removed synchronous write
path; their READMEs mark that limitation explicitly. They are not a supported alternative to
`POST /api/v1/commands`.

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

Each HTTP request receives a `traceId`, propagated through logs and projection tasks. Logs can therefore reconstruct the full chain initiated by a user: HTTP request, command or query, commit, event publication, worker execution, and projection persistence.

Prometheus metrics track, among other things:

- command persistence latency;
- projection outbox and task backlog;
- delay between command commit and worker processing start;
- projection processing duration;
- end-to-end latency from persisted command to persisted projection;
- distribution of gaps between current version and projected version;
- retries and failures observed by the worker or load tests.

These metrics address the main risk of the asynchronous projection architecture: a projection can temporarily lag behind. Rather than assuming this lag is negligible, the application measures it.

## Design Notes

- Command admission and execution are separate transactions. The winning execution transaction writes Pot state, business Events, provenance, fencing and terminal state atomically.
- The write-side closure is documented in `docs/architecture/write-side-closure.md`.
- Projection workers are documented in `docs/projection-workers.md`.
- Dedicated workers are partitioned by stable `potId` hash through `pocoma.projection.worker.segment-index` and `segment-count`.
- Queries are read-only and apply the same read policies as direct views.
- PostgreSQL is enabled with the Spring `postgres` profile; H2 remains the default local mode.
- Flyway is the source of truth for the PostgreSQL schema, while Hibernate validates the schema in PostgreSQL mode.
- Command admission uses OAuth2 Resource Server identity; retained read endpoints still use legacy caller headers temporarily.
