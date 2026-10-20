#!/usr/bin/env bash
# Repeat NEW -> drain/stop -> OLD twice on one disposable PREPARED PostgreSQL.
# Sources are copied/exported without changing any existing checkout. No activation/backfill.
set -euo pipefail
repository=$(git rev-parse --show-toplevel)
previous_ref=${1:-4defd5a63af267656f2feee92aed8daafef2175f}
proof_dir=$(mktemp -d /private/tmp/pocoma-4d-runtime-return.XXXXXX)
mkdir -p "$proof_dir/current" "$proof_dir/previous"
python3 - "$repository" "$proof_dir/current" <<'PY'
from pathlib import Path
import shutil,sys
shutil.copytree(Path(sys.argv[1])/'app',Path(sys.argv[2])/'app',ignore=shutil.ignore_patterns('target'))
PY
git -C "$repository" archive "$previous_ref" app | tar -x -C "$proof_dir/previous"
container_id=$(docker run --rm -d -e POSTGRES_USER=pocoma4d -e POSTGRES_PASSWORD=pocoma4d -e POSTGRES_DB=pocoma4d -p 127.0.0.1::5432 postgres:17-alpine)
trap 'docker rm -f "$container_id" >/dev/null' EXIT
port=$(docker port "$container_id" 5432/tcp | awk -F: '{print $NF}')
dburl="jdbc:postgresql://127.0.0.1:$port/pocoma4d"
for attempt in $(seq 1 60); do
    if docker exec "$container_id" pg_isready -U pocoma4d -d pocoma4d >/dev/null 2>&1; then break; fi
    sleep 1
done
cat > "$proof_dir/RuntimeReturnPostgresTest.java" <<'JAVA'
package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
class RuntimeReturnPostgresTest {
 @Test void startPublishStopDrainAndReadPreviousState() throws Exception {
  String url=System.getProperty("step4d.return.url");
  int expected=Integer.parseInt(System.getProperty("step4d.expected"));
  try(var context=new SpringApplicationBuilder(PocomaCommandConsumptionWorkerApplication.class).run(
      "--server.port=0", "--pocoma.command-consumption.enabled=false", "--spring.datasource.url="+url,
      "--spring.datasource.username=pocoma4d", "--spring.datasource.password=pocoma4d")) {
   JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);
   assertEquals("PREPARED",jdbc.queryForObject("select mode from event_sequencing_protocol",String.class));
   assertEquals(1,jdbc.queryForObject("select protocol_version from event_sequencing_protocol",Integer.class));
   assertEquals(expected-1,jdbc.queryForObject("select count(*) from business_event_outbox",Integer.class));
   WRITE_EVENT
   assertEquals(expected,jdbc.queryForObject("select count(*) from business_event_outbox",Integer.class));
   assertEquals(0,jdbc.queryForObject("select count(*) from event_position",Integer.class));
   assertEquals(0L,jdbc.queryForObject("select sum(last_position) from event_sequence_authorities",Long.class));
  }
  // The complete runtime context/pool is closed before the next binary starts.
  try(var connection=DriverManager.getConnection(url,"pocoma4d","pocoma4d");var statement=connection.createStatement()) {
   try(var rows=statement.executeQuery("select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and backend_type='client backend' and xact_start is not null")) {
    assertTrue(rows.next());assertEquals(0,rows.getInt(1),"no producer transaction remains in flight");
   }
   try(var rows=statement.executeQuery("select count(*) from business_event_outbox")) {assertTrue(rows.next());assertEquals(expected,rows.getInt(1));}
  }
  System.out.println("STEP4D RETURN committed="+expected+" PREPARED drained; binary="+System.getProperty("step4d.binary"));
 }
}
JAVA
python3 - "$proof_dir" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1]);source=(root/'RuntimeReturnPostgresTest.java').read_text()
for version,write in [('current','context.getBean(com.kartaguez.pocoma.engine.consume.command.pot.publication.AppendBusinessEventService.class).append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));'),('previous','context.getBean(com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter.class).append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));')]:
    target=root/version/'app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/RuntimeReturnPostgresTest.java'
    target.write_text(source.replace('WRITE_EVENT',write))
PY
printf 'STEP4D runtime return source %s; exports %s\n' "$(git -C "$repository" rev-parse "$previous_ref")" "$proof_dir"
expected=0
for cycle in 1 2; do
    for binary in current previous; do
        expected=$((expected+1))
        (cd "$proof_dir/$binary/app" && ./mvnw -pl runtime-command-consumption-worker -am test \
            -Dtest=RuntimeReturnPostgresTest -Dsurefire.failIfNoSpecifiedTests=false \
            -Dstep4d.return.url="$dburl" -Dstep4d.expected="$expected" -Dstep4d.binary="$binary")
    done
done
