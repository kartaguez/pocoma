## Summary

<!-- Describe what this PR changes and why. -->

## Verification Scope

- **Primary slice**: `WEB` / `COMMAND` / `PROJECTION` / `FOUNDATION` / `DOCS` / `LOCAL-CLI`
- **Secondary slices**: `NONE` (or list)
- **Global architecture gate (`architecture-tests`)**: `NOT REQUIRED` / `RUN`
- **Full reactor (`./mvnw test`)**: `NOT REQUIRED` / `RUN`

### Commands Executed

```bash
# Paste the exact commands run and their outcomes (PASS/FAIL/counts)
```

## Checklist

- [ ] Changes stay within the declared module/layer boundaries ([`docs/architecture/modules.md`](../docs/architecture/modules.md))
- [ ] Added or updated tests for changed behavior and failure cases
- [ ] `git diff --check` passes cleanly from the repository root
- [ ] Canonical documentation updated (if an invariant, API, or limitation changed)
