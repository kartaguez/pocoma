#!/usr/bin/env bash
# Reproducible isolated prior-bytecode compatibility proof. Does not check out,
# reset, clean or modify an existing worktree. Requires Java 21 and Docker.
set -euo pipefail
repository=$(git rev-parse --show-toplevel)
previous_ref=${1:-1771514a51c74452e019a2a259b40d0303cf2cde}
proof_dir=$(mktemp -d /private/tmp/pocoma-4c-previous-runtime.XXXXXX)
git -C "$repository" archive "$previous_ref" | tar -x -C "$proof_dir"
mkdir -p "$proof_dir/compat-ddl"
cp "$repository/app/infra-persistence-primary-jpa/src/main/resources/db/migration/V30__prepared_event_transactional_sequencing.sql" "$proof_dir/compat-ddl/"
cat > "$proof_dir/app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/PreparedPreviousRuntimePostgresTest.java" <<'JAVA'
package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcUserCreatedFactAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner;

@SpringBootTest(classes=PocomaCommandConsumptionWorkerApplication.class,
    properties={"pocoma.command-consumption.enabled=false","spring.jpa.hibernate.ddl-auto=validate"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PreparedPreviousRuntimePostgresTest {
    static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:17-alpine");
    static {
        PG.start();
        Flyway.configure().dataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())
            .locations("classpath:db/migration","filesystem:"+System.getProperty("step4c.ddl")).load().migrate();
    }
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);
        r.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Test void firstPreviousRuntimeStartupAndCurrentWriter() { verify(); }
    @Test void secondPreviousRuntimeStartupAndCurrentWriter() { verify(); }
    void verify() {
        assertThrows(ClassNotFoundException.class,()->Class.forName(
            "com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.JdbcEventTransactionSequencer"));
        assertTrue(context.getBeansOfType(PrimaryEventTransactionRunner.class).isEmpty());
        UUID id=UUID.randomUUID();
        new TransactionTemplate(manager).execute(status->{
            jdbc.update("insert into users values (?)",id);
            jdbc.update("insert into registration_requests(request_id,issuer,subject,created_at) values (?,'previous-runtime',?,now())",id,id.toString());
            new JdbcUserCreatedFactAdapter(jdbc).append(id,new PocomaUserId(id));return null;
        });
        assertEquals(1,jdbc.queryForObject("select count(*) from user_created_facts where request_id=?",Integer.class,id));
        assertEquals("PREPARED",jdbc.queryForObject("select mode from event_sequencing_protocol",String.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from event_position",Integer.class));
        assertEquals(0,jdbc.queryForObject("select sum(last_position) from event_sequence_authorities",Long.class));
        System.out.println("STEP4C PREVIOUS_RUNTIME startup + unchanged writer committed request="+id+" PREPARED E=0 positions=0");
    }
}
JAVA
printf 'STEP4C previous source %s exported to %s\n' "$(git -C "$repository" rev-parse "$previous_ref")" "$proof_dir"
cd "$proof_dir/app"
./mvnw -pl runtime-command-consumption-worker -am test \
    -Dtest=PreparedPreviousRuntimePostgresTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dstep4c.ddl="$proof_dir/compat-ddl"
