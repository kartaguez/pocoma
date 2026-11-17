# Contributing to Pocoma

Pocoma is primarily maintained by one person, so the most useful contributions are focused, well-explained, and easy to verify.

## Development setup

You need **Java 21** and **Docker** (Testcontainers runs PostgreSQL integration tests automatically). Use the Maven Wrapper from the `app/` directory; a separately installed Maven version is not the supported entry point.

Before editing code under `app/`, review:
- [Modular Architecture (42 Maven modules)](docs/architecture/modules.md) — understand layer boundaries and where to place new code.
- [Reactor Verification Policy](docs/testing/Reactor_Verification_Policy.md) — normative rules for scoped verification (see the **Developer TL;DR** at the top of that file).

### Targeted verification by slice (default workflow)

**Do not run `./mvnw test` across all 42 modules by default** — the full reactor takes ~50 minutes and includes quarantined resource tests. Instead, declare the affected architectural slice and run its canonical command from `app/`:

| Slice | Canonical command (from `app/`) | Scope |
| :--- | :--- | :--- |
| **WEB** | `./mvnw -pl runtime-web-write,runtime-web-read -am test` | HTTP admission, READ queries, security, transport |
| **COMMAND** | `./mvnw -pl runtime-worker-process -am test` | Command & identity execution, fencing, recovery |
| **PROJECTION** | `./mvnw -pl runtime-worker-materialize -am test` | Terminal result & business READ materialization |
| **FOUNDATION** | `./mvnw -pl infra-tx-spring -am test` | Physical transaction contexts & isolation |

For fast inner-loop feedback during development, you can also use the verification helper from the repository root:

```bash
python3 -B scripts/testing/verify.py FAST identity
```

Run the global architecture gate (`./mvnw -pl architecture-tests -am test`) or the full reactor (`./mvnw --batch-mode --no-transfer-progress test`) only when required by the verification policy (e.g., module dependency changes, cross-cutting infrastructure, or integration into `main`).

For local Python CLI tooling (`scripts/local/`), run:

```bash
PYTHONPATH=scripts/local python3 -B -m unittest discover -s scripts/local -p 'test_*.py'
```

## Making a change

- Work on a short-lived branch dedicated to one concern.
- Keep pull requests small and targeted when practical.
- Add or update tests for changed behavior and failure cases.
- Declare your verification slice and list the exact commands actually run in your PR description.
- Update the canonical documentation when an architectural invariant, guarantee, or limitation changes.
- Preserve the existing dependency direction ([docs/architecture/modules.md](docs/architecture/modules.md)). Do not introduce cross-layer or runtime-to-runtime dependencies that contradict the documented architecture.
- Do not mix opportunistic dependency, Maven model, workflow, or architecture changes into an unrelated contribution.

Before opening a pull request, run the relevant slice tests, check `git diff --check`, and complete the [pull request template](.github/pull_request_template.md). The CI contract is documented in [docs/development/ci.md](docs/development/ci.md).

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Contributions are submitted under the repository's [Apache License 2.0](LICENSE).
