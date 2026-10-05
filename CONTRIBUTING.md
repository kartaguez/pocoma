# Contributing to Pocoma

Pocoma is primarily maintained by one person, so the most useful contributions are focused, well-explained, and easy to verify.

## Development setup

You need Java 21 and Docker. Use the Maven Wrapper from the `app` directory; a separately installed Maven version is not the supported entry point.

```bash
cd app
./mvnw --batch-mode --no-transfer-progress test
```

This is the full CI suite and includes Testcontainers-based PostgreSQL tests. For implementation feedback, first read the normative [Reactor Verification Policy](docs/testing/Reactor_Verification_Policy.md), declare the affected architectural slice, and run its canonical targeted command. Escalate to the full reactor only at the gates defined by that policy.

## Making a change

- Work on a short-lived branch dedicated to one concern.
- Keep pull requests small and targeted when practical.
- Add or update tests for changed behavior and failure cases.
- Explain the verification slice and list the commands actually run.
- Update the canonical documentation when an architectural invariant, guarantee, or limitation changes.
- Preserve the existing dependency direction. Do not introduce cross-layer or runtime-to-runtime dependencies that contradict the documented architecture.
- Do not mix opportunistic dependency, Maven model, workflow, or architecture changes into an unrelated contribution.

Before opening a pull request, run the relevant tests, check `git diff --check`, and complete the pull request template. The CI contract is documented in [docs/development/ci.md](docs/development/ci.md).

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Contributions are submitted under the repository's [Apache License 2.0](LICENSE).
