#!/usr/bin/env bash
set -eu
source_root=$1
bench_root=$2
pg_image=postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7
java_image=eclipse-temurin@sha256:66bb900643426ad01996d25bada7d56751913f9cec3b827fcb715d2ec9a0fbfc

run() {
  local label=$1 repository=$2 result
  shift 2
  printf '%s %s\n' "$(date -u +%FT%TZ)" "$label" >> "$bench_root/comparison-status.tsv"
  printf '%q ' "$@" >> "$bench_root/$label.command"
  printf '\n' >> "$bench_root/$label.command"
  if (cd "$repository" && "$@") > "$bench_root/$label.log" 2>&1; then
    result=0
  else
    result=$?
  fi
  printf '%s %s %s\n' "$(date -u +%FT%TZ)" "$label" "$result" >> "$bench_root/comparison-status.tsv"
}

compare() {
  local label=$1 repository=$2 campaign=$3
  shift 3
  run "$label" "$repository" python3 -Bu scripts/performance/run-l7-4a-baseline.py \
    --campaign "$campaign" --output "$bench_root/$label" --java-image "$java_image" --postgres-image "$pg_image" "$@"
}

compare c2-med-5 "$bench_root/c2" med-5 --mode full
compare c3-med-5 "$source_root" med-5 --mode full
compare c3-large-1 "$source_root" large-1 --mode restore-run --snapshot-dir /private/tmp/pocoma-l74a-snapshots/large
compare c2-large-1 "$bench_root/c2" large-1 --mode restore-run --snapshot-dir /private/tmp/pocoma-l74a-snapshots/large
run xlarge "$source_root" python3 -Bu scripts/performance/run-l7-c2-b2-xlarge-ab.py --compare-c3 \
  --baseline-app-dir "$bench_root/c2/app" --candidate-app-dir "$source_root/app" --out "$bench_root/xlarge" --postgres-image "$pg_image"
for side in c3 c2; do
  runner=$source_root
  if [[ "$side" = c2 ]]; then runner=$bench_root/c2; fi
  run "$side-d5" "$runner" python3 -Bu scripts/performance/run-l7-4-a3-a4-multibucket.py \
    --scenario multibucket-d5-boundary --output "$bench_root/$side-d5" --java-image "$java_image" --postgres-image "$pg_image"
done
