#!/bin/bash
set -eu
export JAVA_HOME=/Users/julien.guezennec/.sdkman/candidates/java/current
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1
cd /Users/julien.guezennec/Dev/projects/pocoma
id="$1"; anchor="$2"; selection="$3"
work="$PWD/certification-raw/l7-i3-final-$id"
mkdir "$work"
git rev-parse HEAD > "$work/sha"
git status --porcelain=v1 > "$work/worktree"
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$work/started-at"
java -version > "$work/jdk" 2>&1
printf '%s\n' "$anchor" > "$work/anchor"
printf '%s\n' "$selection" > "$work/selection"
rc=0
(cd app; ./mvnw --batch-mode --no-transfer-progress -pl "$anchor" -am "-Dtest=$selection" -Dsurefire.failIfNoSpecifiedTests=false "-Dpocoma.verification.id=l7-i3-final-$id" "-Dpocoma.verification.reports=$work/reports" test) > "$work/maven.log" 2>&1 || rc=$?
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$work/finished-at"
printf '%s\n' "$rc" > "$work/exit-code"
grep -E '^\[(INFO|ERROR)\] (Tests run:|BUILD|.*(invitationPublication|l73_concurrent))' "$work/maven.log" | tail -10
exit "$rc"
