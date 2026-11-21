# L7-C3 consolidation evidence

The [consolidation report](../../../../v3/L7_C3/consolidation.md) describes the tested scope and the outstanding gates. Verify this directory with `shasum -a 256 -c manifest.sha256` from this directory.

- `targeted-maven.tar.gz` contains five commands, exits, Maven logs and separate Surefire XML directories. `qualification.json` records the source commit, the three tested overrides and XML checksums.
- `historical-maven.json` attributes the historical suite XML to the original or resumed runs. `audit-maven.py` reads the historical archive and takes a directory containing the six original command and log sets. The preserved excerpts and original archive limit what can be independently reproduced without the original journals.
- `verify-install.py` exercises the setup functions against a caller-provisioned disposable PostgreSQL 17 container. The `fresh` scenario deliberately truncates ownership after testing replay. The V4 scenarios use the setup script from C2. From an isolated copy of the repository, restore that fixture before invoking the proof.

  ```sh
  git show d7b4b6d73963cab7efeb658ef67478d52de03c32:scripts/operations/setup-v3-database.py \
    > scripts/operations/setup-v4-audit.py
  python3 -B docs/testing/evidence/L7-C3/consolidation/verify-install.py \
    <disposable-container> <fresh-or-empty-v4-or-populated-v4>
  ```

- `local-bootstrap.txt` comes from the replay of `local-bootstrap-proof.py` in [the previous archive](../closure/local-verification.tar.gz). It includes the two PostgreSQL plans.
- `setup-interrupted-before.txt` shows the original false success. The other setup transcripts and exit files cover the corrected state.
