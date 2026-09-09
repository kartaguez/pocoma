# Continuous integration

## Permanent validation contract

The repository workflow is `.github/workflows/ci.yml`.

- Workflow name: `Pocoma CI`.
- Job and status-check context: `build-and-test`, displayed under the `Pocoma CI` workflow.
- Triggers: every `push`, every `pull_request`, and optional manual `workflow_dispatch`.
- Runner: `ubuntu-24.04` with Temurin Java 21, matching `app/pom.xml`.
- Build root: `app`.
- Build command: `./mvnw --batch-mode --no-transfer-progress test`.
- Repository validation: `git diff --check HEAD^ HEAD` from the repository root.

The complete Maven reactor includes `architecture-tests` and the PostgreSQL integration tests backed
by Testcontainers. Those tests are not disabled in CI. GitHub-hosted Linux runners provide the Docker
runtime used by Testcontainers.

The Maven Wrapper is the only Maven entry point used by CI. The workflow first verifies that `app/mvnw`
is executable and that `.mvn/wrapper/maven-wrapper.properties` is present.

Every commit intended to become the head of a Pocoma working branch must be validated by this automatic
GitHub CI. Changing or removing the workflow is an explicit infrastructure change; it must not be done
as incidental cleanup.

## Required branch check

The workflow creates the stable `build-and-test` check, displayed as `Pocoma CI / build-and-test` in
the Actions UI. To make it mandatory on a protected branch in GitHub:

1. open **Settings → Branches → Branch protection rules**;
2. create or edit the rule covering the target branch, for example `v2-make-it-pull`;
3. enable **Require status checks to pass before merging**;
4. select the `build-and-test` check produced by `Pocoma CI`;
5. optionally enable **Require branches to be up to date before merging**;
6. save the rule.

The check becomes selectable after the workflow has run at least once with this stable name.
