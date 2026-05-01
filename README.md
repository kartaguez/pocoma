# Pocoma

Pocoma is an application for managing shared pots: a user creates a pot, adds participants, records expenses, and the application computes balances between participants. The project is also an architecture playground for validating a clean separation between domain, application engine, persistence, HTTP API, projection worker, and observability.

## How It Works

The application exposes a Spring Boot HTTP API. Commands mutate the versioned state of a pot: creating a pot, updating details, adding or linking participants, creating or updating expenses, and deleting pots or expenses. Queries read views of that state: accessible pots, pot details, expenses, balances, and balances for the calling user.

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
  infra-event-publisher-spring/   Legacy Spring event publishing adapter
  observability/                  Trace and measurement abstractions
  supra-http-rest-spring/         REST controllers and DTOs
  supra-worker-projection-*/      Projection worker
  runtime-web-api/                API-only Spring Boot runtime
  runtime-worker/                 Projection-worker Spring Boot runtime
  runtime-monolith/               Spring Boot monolith composition

docker/                           Prometheus and Grafana
scripts/bruno/                    Bruno HTTP collection
scripts/k6/                       k6 load tests
```

The core design choice is hexagonal architecture: `domain` depends on nothing, `engine` depends on ports, and `infra-*` / `supra-*` modules plug in technologies. `runtime-web-api` wires the HTTP/API role, `runtime-worker` wires the projection role, and `runtime-monolith` remains the local all-in-one composition root.

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

./mvnw -pl runtime-worker spring-boot:run \
  -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.arguments="--pocoma.projection.worker.segment-index=0 --pocoma.projection.worker.segment-count=2"

./mvnw -pl runtime-worker spring-boot:run \
  -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.arguments="--pocoma.projection.worker.segment-index=1 --pocoma.projection.worker.segment-count=2"
```

`runtime-monolith` still supports the `api` and `worker` profiles for local experiments, but the dedicated runtimes match the target deployment shape.

Full stack with PostgreSQL, application, Prometheus, and Grafana:

```bash
cd app
docker compose up -d
```

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

The Bruno collection in `scripts/bruno` lets you manually run the full flow: create a pot, add participants, create expenses, run queries, inspect balances, then clean up. It stores ids and versions so requests can be chained without copying values by hand.

The k6 tests in `scripts/k6` exercise concurrency and projection behavior:

```bash
cd app
k6 run ../scripts/k6/smoke.js
k6 run ../scripts/k6/stress.js
k6 run ../scripts/k6/projection_backpressure.js
```

They cover valid commands, concurrent conflicts, inconsistent requests, queries under load, and projection back pressure. The backpressure scenario can be run while multiple `runtime-worker` processes own different `segment-index` values; it scrapes `/actuator/prometheus` on the API runtime to track backlog and latency.

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

- Commands are transactional and write business events to `business_event_outbox`.
- Projection workers are documented in `docs/projection-workers.md`.
- Dedicated workers are partitioned by stable `potId` hash through `pocoma.projection.worker.segment-index` and `segment-count`.
- Queries are read-only and apply the same read policies as direct views.
- PostgreSQL is enabled with the Spring `postgres` profile; H2 remains the default local mode.
- Flyway is the source of truth for the PostgreSQL schema, while Hibernate validates the schema in PostgreSQL mode.
- Calling users are identified through the `X-User-Id` header.
