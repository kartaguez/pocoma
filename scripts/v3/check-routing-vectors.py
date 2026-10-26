#!/usr/bin/env python3
"""Independent oracle: stdlib only; no production Java/SQL imports."""
import csv, hashlib, uuid
from pathlib import Path
path = Path(__file__).resolve().parents[2] / "docs/testing/evidence/L2-I1/routing-vectors.csv"
rows = list(csv.DictReader(path.open()))
assert len(rows) == 21
for row in rows:
    message = b"pocoma-consumable-v1\0" + row["family"].encode("ascii") + b"\0" + uuid.UUID(row["uuid"]).bytes
    digest = hashlib.sha256(message).digest()
    value = int.from_bytes(digest[:4], "big", signed=True)
    assert (digest.hex(), value, value % 4, value % 16) == (row["digest"], int(row["hash"]), int(row["p4"]), int(row["p16"]))
    print(message.hex(), digest.hex(), value, value % 4, value % 16)
print("PASS: 21 independent routing vectors")
