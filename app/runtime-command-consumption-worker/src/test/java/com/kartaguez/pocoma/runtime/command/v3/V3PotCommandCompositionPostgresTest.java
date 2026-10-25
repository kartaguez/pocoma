package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotCommandJson;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;

/**
 * The actual composition factory, not a duplicated wiring fixture. No Discovery or Identity
 * implementation.
 */
@Testcontainers
class V3PotCommandCompositionPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void compositionUsesOneWriteManagerAndExplicitAuthority() {
        var schema = "v3_composition_" + UUID.randomUUID().toString().replace("-", "");
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        var sql = new JdbcTemplate(source);
        Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations(
                        "classpath:db/v3-pot/migration", "classpath:db/v3-pot-execution/migration")
                .load()
                .migrate();
        sql.execute(
                "create table fixture_identity(issuer text,subject text,user_id uuid,token"
                    + " text,primary key(issuer,subject))");
        var user = new UserId(UUID.randomUUID());
        var pot = new PotId(UUID.randomUUID());
        sql.update(
                "insert into fixture_identity values ('issuer','subject',?,'secret')",
                user.value());
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        try {
            var manager = new JpaTransactionManager(factory.getObject());
            manager.setDataSource(source);
            manager.setNestedTransactionAllowed(false);
            var authority =
                    new PotActorAuthority() {
                        public Witness observe(PotCommand c) {
                            var rows =
                                    sql.queryForList(
                                            "select user_id from fixture_identity where issuer=?"
                                                + " and subject=? and token=?",
                                            c.requester().issuer(),
                                            c.requester().subject(),
                                            c.bindingToken());
                            return new Witness(
                                    1,
                                    rows.isEmpty()
                                            ? Optional.empty()
                                            : Optional.of(
                                                    new UserId(
                                                            (UUID)
                                                                    rows.getFirst()
                                                                            .get("user_id"))));
                        }

                        public boolean protect(PotCommand c, Witness witness) {
                            sql.queryForList(
                                    "select user_id from fixture_identity where issuer=? and"
                                        + " subject=? for update",
                                    c.requester().issuer(),
                                    c.requester().subject());
                            return observe(c).equals(witness);
                        }
                    };
            var execution =
                    V3PotCommandComposition.assemble(
                            source,
                            manager,
                            authority,
                            new ExecutePotCommand.RetryPolicy(3, Duration.ZERO),
                            Clock.systemUTC());
            var request = UUID.randomUUID();
            var claim = UUID.randomUUID();
            var intent = new PotCommand.CreatePot(new Label("composed"));
            sql.update(
                    "insert into"
                        + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                        + " values (?,'issuer','subject','secret',?,0,'CreatePot',?::jsonb)",
                    request,
                    pot.value(),
                    PotCommandJson.encode(intent));
            sql.update(
                    "insert into v3_command_slot(request_id,claim_token,claimed_until) values"
                        + " (?,?,clock_timestamp()+interval '1 minute')",
                    request,
                    claim);
            var result = execution.execute(request, claim);
            assertEquals(ExecutePotCommand.Status.COMPLETED, result.status());
            assertEquals(1L, result.result().orElseThrow().resultingPotVersion());
            assertEquals(
                    "TERMINAL",
                    sql.queryForObject(
                            "select status from v3_command where request_id=?",
                            String.class,
                            request));
            assertEquals(
                    0, sql.queryForObject("select count(*) from v3_command_slot", Integer.class));
            assertEquals(1, sql.queryForObject("select count(*) from v3_pot_event", Integer.class));
            assertEquals(
                    1,
                    sql.queryForObject(
                            "select count(*) from v3_command_completion_event", Integer.class));
            assertEquals(result, execution.execute(request, claim));
            var different =
                    new DriverManagerDataSource(
                            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            V3PotCommandComposition.assemble(
                                    different,
                                    manager,
                                    authority,
                                    new ExecutePotCommand.RetryPolicy(3, Duration.ZERO),
                                    Clock.systemUTC()));
        } finally {
            factory.destroy();
        }
    }
}
