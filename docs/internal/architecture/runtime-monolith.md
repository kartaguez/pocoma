# Functional-test runtime monolith

`app/runtime-monolith` is a Spring Boot composition root for the functional-test environment. It
runs the existing HTTP supras and asynchronous workers in one JVM; it does not introduce a new
engine, route, persistence model or business path.

The root imports the reusable `composition-runtime-spring` module and composes, exactly once:

- HTTP write/read supras for Registration, Registration Result, Current Binding, Command, Command
  Result and Read Pot;
- Registration, Registration Result, Binding, Command, Command Result, Event and Projection Task
  workers.

Every functional worker uses `segmentIndex=0` and `segmentCount=1`. Worker IDs are explicit and
distinct (`monolith-registration-worker`, `monolith-registration-result-worker`,
`monolith-binding-worker`, `monolith-command-worker`, `monolith-command-result-worker`,
`monolith-event-worker` and `monolith-projection-task-worker`). The monolith keeps PRIMARY and READ
responsibilities separate while allowing both stores to use the same PostgreSQL server. Existing
primary migrations and read-store migrations are reused unchanged.

The distributed runtimes remain independent and unchanged in topology. The repository rule is
semantic: every `runtime-*` module may depend on permitted domain, engine, supra, infrastructure or
composition modules, but no `runtime-*` module may depend on another `runtime-*` module. The word
`monolith` is not an exception to that rule; it is only the name of this deployment shape.
