package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.domain.pot.v3.Amount;
import com.kartaguez.pocoma.domain.pot.v3.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.v3.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.v3.Fraction;
import com.kartaguez.pocoma.domain.pot.v3.InconsistentPotFactsException;
import com.kartaguez.pocoma.domain.pot.v3.PotBalanceCalculator;
import com.kartaguez.pocoma.domain.pot.v3.ShareholderTransitions;
import com.kartaguez.pocoma.domain.pot.v3.Weight;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.CalculatePotBalances;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.JdbcPotHistoryAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotCommandJson;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.runtime.consumption.v3.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/**
 * The actual composition factory, not a duplicated wiring fixture. No Discovery or Identity
 * implementation.
 */
@Testcontainers
class V3PotCommandCompositionPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = ConsumptionBootstrapPostgresTest.PG;

    @BeforeAll
    static void initialize() throws Exception {
        ConsumptionBootstrapPostgresTest.databases();
    }

    static void assertInjectedCompositeClassifier(ConsumptionEngine execution) {
        try {
            var field = ConsumptionEngine.class.getDeclaredField("classifier");
            field.setAccessible(true);
            var classifier = (AttemptFailureClassifier) field.get(execution);
            var trace = new ArrayList<String>();
            for (boolean collision : List.of(true, false)) {
                for (String excluded : List.of("none", "08006", "40003", "configuration")) {
                    RuntimeException business =
                            collision
                                    ? new ExecutePotCommand.Collision()
                                    : new InconsistentPotFactsException("invalid facts");
                    var expected =
                            collision ? FailureKind.COLLISION : FailureKind.INCONSISTENT_FACTS;
                    if (!excluded.equals("none")) {
                        business.addSuppressed(
                                excluded.equals("configuration")
                                        ? new org.springframework.transaction
                                                .IllegalTransactionStateException(
                                                "invalid composition")
                                        : new SQLException("uncertain", excluded));
                        expected = FailureKind.NON_COUNTABLE;
                    }
                    // The real Command mapping must not override the common exclusion decision.
                    assertEquals(expected, classifier.classify(business));
                    assertEquals(
                            expected,
                            classifier.classify(
                                    new org.springframework.transaction.UnexpectedRollbackException(
                                            "wrapper", business)));
                    trace.add(
                            "PASS command collision="
                                    + collision
                                    + " excluded="
                                    + excluded
                                    + " direct and wrapped="
                                    + expected);
                }
            }
            var path = Path.of("target/v3-i3-evidence/command-composite-classifier.txt");
            Files.createDirectories(path.getParent());
            Files.write(path, trace);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void compositionUsesOneWriteManagerAndExplicitAuthority() {
        var fixture = new ConsumptionBootstrapPostgresTest();
        try {
            fixture.freshAuthorities();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        var schema = fixture.schema;
        var source = fixture.w.source();
        var sql = fixture.write;
        String reactivationRole = "react_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("create role " + reactivationRole + " login password 'fixture-runtime'");
        try {
            POSTGRES.copyFileToContainer(
                    org.testcontainers.utility.MountableFile.forHostPath(
                            java.nio.file.Path.of("../../scripts/v3/grant-l2-lifecycle.sql")
                                    .toAbsolutePath()),
                    "/tmp/grants.sql");
            for (var target : List.of(fixture.w, fixture.r)) {
                var result =
                        POSTGRES.execInContainer(
                                "env",
                                "PGOPTIONS=-c search_path=" + schema,
                                "psql",
                                "-X",
                                "-U",
                                POSTGRES.getUsername(),
                                "-d",
                                target.database(),
                                "-v",
                                "ON_ERROR_STOP=1",
                                "-v",
                                "expected_database=" + target.database(),
                                "-v",
                                "expected_schema=" + schema,
                                "-v",
                                "expected_admin=" + POSTGRES.getUsername(),
                                "-v",
                                "runtime_role=" + target.runtimeRole(),
                                "-v",
                                "reactivation_role=" + reactivationRole,
                                "-f",
                                "/tmp/grants.sql");
                assertEquals(0, result.getExitCode(), result.getStderr());
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        var capacity =
                new WorkerCapacity(
                        4,
                        Map.of(
                                new ConsumptionId(
                                        UUID.fromString("98f7268d-d340-581c-b83d-010926223e14")),
                                2));
        sql.execute(
                "create table fixture_identity(issuer text,subject text,user_id uuid,token"
                        + " text,primary key(issuer,subject))");
        sql.execute("grant select,update(token) on fixture_identity to pilot_w");
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
                            Clock.systemUTC(),
                            fixture.guard(),
                            LifecycleSettings.pilot(),
                            capacity,
                            8);
            assertInjectedCompositeClassifier(execution);
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
                    "insert into"
                        + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,claim_token,claimed_until)"
                        + " select"
                        + " '98f7268d-d340-581c-b83d-010926223e14',id,h,((h::bigint%4+4)%4)::int,token,clock_timestamp()+interval"
                        + " '1 minute' from (select id,token,consumption_hash_v1('COMMAND',id) h"
                        + " from (select ?::uuid id,?::uuid token) x) y",
                    request, claim);
            var result = CommandReservedClaimFixture.execute(execution, claim(request, claim));
            assertEquals(ConsumptionEngine.Status.COMMITTED, result.status());
            assertEquals(
                    1L,
                    sql.queryForObject(
                            "select resulting_pot_version from v3_command_result where"
                                    + " request_id=?",
                            Long.class,
                            request));
            assertEquals(
                    "TERMINAL",
                    sql.queryForObject(
                            "select status from v3_command where request_id=?",
                            String.class,
                            request));
            assertEquals(
                    0, sql.queryForObject("select count(*) from consumption_slot", Integer.class));
            assertEquals(1, sql.queryForObject("select count(*) from v3_pot_event", Integer.class));
            assertEquals(
                    1,
                    sql.queryForObject(
                            "select count(*) from v3_command_completion_event", Integer.class));
            assertEquals(
                    ConsumptionEngine.Status.COMMITTED,
                    CommandReservedClaimFixture.execute(execution, claim(request, claim)).status());
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
                                "insert into"
                                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,claim_token,claimed_until)"
                                    + " select"
                                    + " '98f7268d-d340-581c-b83d-010926223e14',id,h,((h::bigint%4+4)%4)::int,token,clock_timestamp()+interval"
                                    + " '1 minute' from (select"
                                    + " id,token,consumption_hash_v1('COMMAND',id) h from (select"
                                    + " ?::uuid id,?::uuid token) x) y",
                                id, token);
                        var completed =
                                CommandReservedClaimFixture.execute(execution, claim(id, token));
                        assertEquals(ConsumptionEngine.Status.COMMITTED, completed.status());
                        assertEquals(
                                observed + 1,
                                sql.queryForObject(
                                        "select resulting_pot_version from v3_command_result where"
                                                + " request_id=?",
                                        Long.class,
                                        id));
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
            var owner = new SpringAttemptTransactions(source, manager);
            var balances =
                    new CalculatePotBalances(
                            new JdbcPotHistoryAdapter(source), new PotBalanceCalculator());
            var old = owner.read(context -> balances.atVersion(pot, 3).orElseThrow());
            assertEquals(Map.of(a, new Fraction(75, 1), b, new Fraction(-75, 1)), old.values());
            submit.accept(
                    new PotCommand.UpdateShareholderWeights(
                            List.of(
                                    new ShareholderTransitions.WeightIntent(
                                            a, new Weight(new Fraction(3, 1))))));
            assertEquals(
                    Map.of(a, new Fraction(50, 1), b, new Fraction(-50, 1)),
                    owner.read(context -> balances.atVersion(pot, 4).orElseThrow()).values());
            assertEquals(old, owner.read(context -> balances.atVersion(pot, 3).orElseThrow()));
            try {
                var output =
                        java.nio.file.Path.of("target/v3-pot-evidence/E-spring-composition.txt");
                java.nio.file.Files.createDirectories(output.getParent());
                java.nio.file.Files.writeString(
                        output,
                        "Actual composition factory + JpaTransactionManager\n"
                                + old
                                + "\n"
                                + owner.read(context -> balances.atVersion(pot, 4).orElseThrow()));
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
                                    Clock.systemUTC(),
                                    fixture.guard(),
                                    LifecycleSettings.pilot(),
                                    capacity,
                                    8));
        } finally {
            factory.destroy();
        }
    }

    static ClaimedSlot claim(UUID request, UUID token) {
        return new ClaimedSlot(
                new ConsumptionId(UUID.fromString("98f7268d-d340-581c-b83d-010926223e14")),
                request,
                token);
    }
}
