#!/usr/bin/env bash
# Paired pure-calculator measurement against the immutable L1 baseline; no Maven test gate.
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo"
baseline=2fb9a536f3894e797923c0b49aeba1a9b5adfda2
profile_dir=app/domain-pot/target/l1f-profile
mkdir -p "$profile_dir/baseline-src" "$profile_dir/baseline-classes"
git show "$baseline:app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotBalanceCalculator.java" > "$profile_dir/baseline-src/PotBalanceCalculator.java"
javac --release 21 -cp app/domain-pot/target/classes -d "$profile_dir/baseline-classes" "$profile_dir/baseline-src/PotBalanceCalculator.java"
for variant in before after; do
    profile_cp=app/domain-pot/target/test-classes:app/domain-pot/target/classes
    if [ "$variant" = before ]; then profile_cp="$profile_dir/baseline-classes:$profile_cp"; fi
    for fork in 1 2 3; do
        java -Xms256m -Xmx1g -XX:+UseSerialGC -cp "$profile_cp" com.kartaguez.pocoma.domain.pot.v3.PotBalanceProfile > "$profile_dir/$variant-$fork.txt"
    done
done
python3 -B - <<'PY'
import hashlib,json,subprocess
from pathlib import Path
root=Path('app/domain-pot')
files=[*root.glob('target/l1f-profile/baseline-src/*'),*root.glob('target/l1f-profile/baseline-classes/**/*.class'),*root.glob('target/classes/**/*.class'),*root.glob('target/test-classes/**/PotBalanceProfile.class'),root/'src/test/java/com/kartaguez/pocoma/domain/pot/v3/PotBalanceProfile.java']
metadata={'tested_sha':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'baseline':'2fb9a536f3894e797923c0b49aeba1a9b5adfda2','sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
(root/'target/l1f-profile/provenance.json').write_text(json.dumps(metadata,indent=2)+'\n')
PY
