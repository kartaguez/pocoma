#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
output=${1:?usage: bash run.sh OUTPUT_DIRECTORY order|cost|wal [SAMPLES]}
campaign=${2:?specify order, cost or wal}
samples=${3:-100}
case "$campaign" in order|cost|wal) ;; *) exit 2 ;; esac
mkdir -p "$output"
output=$(cd "$output" && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
tested_root=${POCOMA_C3_TESTED_ROOT:-"$root"}
module="$tested_root/app/runtime-worker-materialize"
# Use the effective test classpath recorded by the current composed Maven run.
receipt="$module/target/surefire-reports/TEST-com.kartaguez.pocoma.runtime.task.CommandResultAvailabilityPostgresTest.xml"
classpath=$(python3 -B - "$receipt" <<'PY'
import sys
from xml.etree import ElementTree as ET

report = ET.parse(sys.argv[1])
value = report.find("./properties/property[@name='java.class.path']")
if value is None:
    raise SystemExit("Missing Maven test classpath")
print(value.attrib["value"])
PY
)
javac -cp "$classpath" -d "$scratch" "$root"/experiments/l7-c3-gate/*.java
# The existing fixtures resolve scripts/v3 relative to the Maven module.
cd "$module"
java -Dpocoma.verification.id="c3-complement-$campaign" -cp "$scratch:$classpath" \
    com.kartaguez.pocoma.runtime.task.C3Complement "$output" "$root/experiments/l7-c3-gate" "$campaign" "$samples" \
    > "$output/$campaign.log" 2>&1
