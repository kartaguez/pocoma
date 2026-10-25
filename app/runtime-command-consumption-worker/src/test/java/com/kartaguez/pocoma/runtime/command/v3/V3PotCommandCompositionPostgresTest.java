package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.Amount;
import com.kartaguez.pocoma.domain.pot.v3.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.v3.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.v3.Fraction;
import com.kartaguez.pocoma.domain.pot.v3.PotBalanceCalculator;
import com.kartaguez.pocoma.domain.pot.v3.ShareholderTransitions;
import com.kartaguez.pocoma.domain.pot.v3.Weight;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.CalculatePotBalances;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.JdbcPotHistoryAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotCommandJdbcTransactions;
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
            // E: actual Spring/JPA composition also executes finance and historical calculation.
            var a = new ShareholderId(UUID.randomUUID());
            var b = new ShareholderId(UUID.randomUUID());
            var expense = new ExpenseId(UUID.randomUUID());
            var one = new Weight(Fraction.ONE);
            java.util.function.Consumer<PotCommand.Intent> submit =
                    operation -> {
                        var id = UUID.randomUUID();
                        var token = UUID.randomUUID();
                        long observed =
                                sql.queryForObject(
                                        "select current_version from v3_pot_identity where"
                                            + " pot_id=?",
                                        Long.class,
                                        pot.value());
                        sql.update(
                                "insert into"
                                    + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                    + " values (?,'issuer','subject','secret',?,?,?,?::jsonb)",
                                id,
                                pot.value(),
                                observed,
                                operation.getClass().getSimpleName(),
                                PotCommandJson.encode(operation));
                        sql.update(
                                "insert into v3_command_slot(request_id,claim_token,claimed_until)"
                                    + " values (?,?,clock_timestamp()+interval '1 minute')",
                                id,
                                token);
                        var completed = execution.execute(id, token);
                        assertEquals(ExecutePotCommand.Status.COMPLETED, completed.status());
                        assertEquals(
                                observed + 1,
                                completed.result().orElseThrow().resultingPotVersion());
                    };
            submit.accept(
                    new PotCommand.AddShareholders(
                            List.of(
                                    new ShareholderTransitions.Creation(
                                            a, new Name("A"), Optional.empty(), one),
                                    new ShareholderTransitions.Creation(
                                            b,
                                            new Name("B"),
                                            Optional.empty(),
                                            new Weight(new Fraction(3, 1))))));
            submit.accept(
                    new PotCommand.CreateExpense(
                            new ExpenseDetails(
                                    pot, expense, new Label("E"), LocalDate.of(2026, 10, 25)),
                            new ExpenseFinancials(
                                    pot,
                                    expense,
                                    new Amount(new Fraction(100, 1)),
                                    a,
                                    Map.of(a, Optional.empty(), b, Optional.empty()))));
            var owner = new PotCommandJdbcTransactions(source, manager);
            var balances =
                    new CalculatePotBalances(
                            new JdbcPotHistoryAdapter(source), new PotBalanceCalculator());
            var old = owner.snapshot(() -> balances.atVersion(pot, 3).orElseThrow());
            assertEquals(Map.of(a, new Fraction(75, 1), b, new Fraction(-75, 1)), old.values());
            submit.accept(
                    new PotCommand.UpdateShareholderWeights(
                            List.of(
                                    new ShareholderTransitions.WeightIntent(
                                            a, new Weight(new Fraction(3, 1))))));
            assertEquals(
                    Map.of(a, new Fraction(50, 1), b, new Fraction(-50, 1)),
                    owner.snapshot(() -> balances.atVersion(pot, 4).orElseThrow()).values());
            assertEquals(old, owner.snapshot(() -> balances.atVersion(pot, 3).orElseThrow()));
            try {
                var output =
                        java.nio.file.Path.of("target/v3-pot-evidence/E-spring-composition.txt");
                java.nio.file.Files.createDirectories(output.getParent());
                java.nio.file.Files.writeString(
                        output,
                        "Actual composition factory + JpaTransactionManager\n"
                                + old
                                + "\n"
                                + owner.snapshot(() -> balances.atVersion(pot, 4).orElseThrow()));
            } catch (java.io.IOException error) {
                throw new AssertionError(error);
            }
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
