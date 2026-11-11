package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real V6 history and canonical protections, with the delivered JPA physical owner. */
@Testcontainers
class IdentityPotAuthorityPostgresTest extends IdentityPostgresFixture {
    LocalContainerEntityManagerFactoryBean factory;
    JdbcIdentityPotActorAuthority pot;

    @BeforeEach
    void jpaOwner() {
        var delegate = source;
        source =
                new AbstractDataSource() {
                    public Connection getConnection() throws SQLException {
                        var connection = delegate.getConnection();
                        return (Connection)
                                Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {Connection.class},
                                        (p, m, args) -> {
                                            var result = invoke(connection, m, args);
                                            if (m.getName().equals("prepareStatement")
                                                    && args[0] instanceof String sql)
                                                evidence.add(
                                                        "SQL prepared "
                                                                + sql.replaceAll("\\s+", " ")
                                                                        .trim());
                                            if (m.getName().equals("createStatement")) {
                                                var statement = (Statement) result;
                                                return Proxy.newProxyInstance(
                                                        getClass().getClassLoader(),
                                                        new Class[] {Statement.class},
                                                        (q, method, values) -> {
                                                            if (method.getName()
                                                                            .startsWith("execute")
                                                                    && values != null
                                                                    && values[0]
                                                                            instanceof String sql)
                                                                evidence.add(
                                                                        "SQL statement "
                                                                                + sql.replaceAll(
                                                                                                "\\s+",
                                                                                                " ")
                                                                                        .trim());
                                                            return invoke(
                                                                    statement, method, values);
                                                        });
                                            }
                                            return result;
                                        });
                    }

                    public Connection getConnection(String user, String password)
                            throws SQLException {
                        return getConnection();
                    }
                };
        jdbc = new JdbcTemplate(source);
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        var manager = new JpaTransactionManager(factory.getObject());
        manager.setDataSource(source);
        manager.setNestedTransactionAllowed(false);
        tx = new SpringAttemptTransactions(source, manager, 25);
        authority = new JdbcIdentityAuthority(source, tx);
        pot = new JdbcIdentityPotActorAuthority(source, tx);
    }

    @AfterEach
    void closeJpa() {
        factory.destroy();
    }

    PotCommand command(ExternalIdentity key, String token) {
        return new PotCommand(
                UUID.randomUUID(),
                new PotCommand.Eui(key.issuer(), key.subject()),
                token,
                new PotId(UUID.randomUUID()),
                0,
                new PotCommand.CreatePot(new Label("I4")));
    }

    PotActorAuthority.Witness observe(PotCommand command) {
        return tx.read(c -> pot.observe(command));
    }

    void stable(PotCommand command, long version, Optional<UserId> actor) {
        var expected = new PotActorAuthority.Witness(version, actor);
        var observed = observe(command);
        assertEquals(expected, observed);
        assertTrue(tx.<Boolean>write(() -> pot.protect(command, observed)));
        evidence.add("stable request=" + command.requestId() + " witness=" + observed);
    }

    @Test
    void l3_absent_stable() {
        var key = key("absent");
        var command = command(key, "invented");
        stable(command, 0, Optional.empty());
        stable(command, 0, Optional.empty());
        assertEquals(1, count("v3_eui_authority"));
        assertCounts(0, 0, 0, 0, 0);
        assertEquals(
                0,
                admin.queryForObject("select current_version from v3_eui_authority", Long.class));
    }

    @Test
    void l3_free_stable() {
        var key = key("free");
        var state = changed(run(admit(registration(key)))).identity();
        run(admit(unlink(key, state.bindingToken(), key)));
        stable(command(key, state.bindingToken().value()), 2, Optional.empty());
    }

    @Test
    void l3_invalid_token_same_version() {
        var key = key("bound");
        run(admit(registration(key)));
        stable(command(key, "invented"), 1, Optional.empty());
        stable(command(key, "invented"), 1, Optional.empty());
    }

    @Test
    void l3_valid_binding_and_foreign_token() {
        var first = key("same-subject");
        var a = changed(run(admit(registration(first)))).identity();
        var keys =
                List.of(
                        new ExternalIdentity("another-issuer", first.subject()),
                        key("Same-subject"),
                        key("é"),
                        key("e\u0301"));
        for (var key : keys) {
            var b = changed(run(admit(link(first, a.bindingToken(), key)))).identity();
            assertEquals(a.userId(), b.userId());
            stable(
                    command(key, b.bindingToken().value()),
                    1,
                    Optional.of(new UserId(a.userId().value())));
            stable(command(key, a.bindingToken().value()), 1, Optional.empty());
            stable(command(first, b.bindingToken().value()), 1, Optional.empty());
        }
        stable(
                command(first, a.bindingToken().value()),
                1,
                Optional.of(new UserId(a.userId().value())));
        stable(command(first, a.bindingToken().value() + " "), 1, Optional.empty());
    }

    @Test
    void l3_observation_change() {
        var key = key("changes");
        var negative = command(key, "wrong");
        var absent = observe(negative);
        var first = changed(run(admit(registration(key)))).identity();
        assertFalse(tx.<Boolean>write(() -> pot.protect(negative, absent)));
        var invalid = observe(negative);
        var positive = command(key, first.bindingToken().value());
        var bound = observe(positive);
        run(admit(unlink(key, first.bindingToken(), key)));
        assertFalse(tx.<Boolean>write(() -> pot.protect(negative, invalid)));
        assertFalse(tx.<Boolean>write(() -> pot.protect(positive, bound)));
        var other = key("initiator");
        var a = changed(run(admit(registration(other)))).identity();
        var linked = changed(run(admit(link(other, a.bindingToken(), key)))).identity();
        var old = command(key, linked.bindingToken().value());
        var witness = observe(old);
        run(admit(unlink(other, a.bindingToken(), key)));
        var relink = changed(run(admit(link(other, a.bindingToken(), key)))).identity();
        assertEquals(linked.userId(), relink.userId());
        assertTrue(!linked.bindingToken().equals(relink.bindingToken()));
        assertFalse(tx.<Boolean>write(() -> pot.protect(old, witness)));
        stable(old, 5, Optional.empty());
        stable(
                command(key, relink.bindingToken().value()),
                5,
                Optional.of(new UserId(relink.userId().value())));
    }

    @Test
    void l3_registration_vs_command() throws Exception {
        for (String initial : List.of("absent", "zero", "free")) {
            for (boolean rollback : List.of(false, true)) {
                var key = key(initial + rollback);
                if (initial.equals("zero")) stable(command(key, "wrong"), 0, Optional.empty());
                if (initial.equals("free")) {
                    var s = changed(run(admit(registration(key)))).identity();
                    run(admit(unlink(key, s.bindingToken(), key)));
                }
                var command = command(key, "wrong");
                var witness = observe(command);
                var held = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var holderPid = new AtomicInteger();
                var registration = admit(registration(key));
                var holder =
                        threads.submit(
                                () -> {
                                    try {
                                        tx.write(
                                                () -> {
                                                    holderPid.set(
                                                            jdbc.queryForObject(
                                                                    "select pg_backend_pid()",
                                                                    Integer.class));
                                                    assertTrue(pot.protect(command, witness));
                                                    held.countDown();
                                                    await(release);
                                                    if (rollback)
                                                        throw new IllegalStateException(
                                                                "intentional rollback");
                                                    return null;
                                                });
                                        assertFalse(rollback);
                                    } catch (AttemptTransactions.Failure e) {
                                        assertTrue(rollback);
                                        assertEquals(
                                                AttemptTransactions.Resolution.ROLLED_BACK,
                                                e.resolution());
                                    }
                                });
                await(held);
                int mark = lockCalls.size();
                var waiter = threads.submit(() -> run(registration));
                try {
                    blocked(waiterPid(key, holderPid.get(), mark), holderPid.get());
                } finally {
                    release.countDown();
                }
                holder.get(20, TimeUnit.SECONDS);
                changed(waiter.get(20, TimeUnit.SECONDS));
                assertFalse(tx.<Boolean>write(() -> pot.protect(command, witness)));
                assertEquals(initial.equals("free") ? 3 : 1, current(key).version());
                evidence.add("variant registration " + initial + " holderRollback=" + rollback);
            }
        }
    }

    @Test
    void l3_command_vs_unlink_and_link() throws Exception {
        for (boolean rollback : List.of(false, true)) {
            for (String operation : List.of("UNLINK", "LINK")) {
                var key = key("é-" + operation + rollback);
                var state = changed(run(admit(registration(key)))).identity();
                var command = command(key, state.bindingToken().value());
                var witness = observe(command);
                var work =
                        admit(
                                operation.equals("UNLINK")
                                        ? unlink(key, state.bindingToken(), key)
                                        : link(
                                                key,
                                                state.bindingToken(),
                                                key("a-target-" + rollback)));
                var held = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var pid = new AtomicInteger();
                var first =
                        threads.submit(
                                () -> {
                                    try {
                                        tx.write(
                                                () -> {
                                                    pid.set(
                                                            jdbc.queryForObject(
                                                                    "select pg_backend_pid()",
                                                                    Integer.class));
                                                    assertTrue(pot.protect(command, witness));
                                                    held.countDown();
                                                    await(release);
                                                    if (rollback)
                                                        throw new IllegalStateException("rollback");
                                                    return null;
                                                });
                                    } catch (AttemptTransactions.Failure e) {
                                        assertTrue(rollback);
                                        assertEquals(
                                                AttemptTransactions.Resolution.ROLLED_BACK,
                                                e.resolution());
                                    }
                                });
                await(held);
                int mark = lockCalls.size();
                var second = threads.submit(() -> run(work));
                try {
                    blocked(waiterPid(key, pid.get(), mark), pid.get());
                } finally {
                    release.countDown();
                }
                first.get(20, TimeUnit.SECONDS);
                changed(second.get(20, TimeUnit.SECONDS));
                assertEquals(operation.equals("UNLINK") ? 2 : 1, current(key).version());
                evidence.add("variant command vs " + operation + " rollback=" + rollback);
            }
        }
    }

    @Test
    void l3_identity_first_commit_and_rollback() throws Exception {
        for (String transition :
                List.of(
                        "REGISTRATION_ABSENT",
                        "REGISTRATION_ZERO",
                        "REGISTRATION_FREE",
                        "LINK",
                        "UNLINK")) {
            for (boolean rollback : List.of(false, true)) {
                var key = key(transition + rollback);
                String token = "invalid";
                Work work;
                if (transition.equals("REGISTRATION_ZERO"))
                    stable(command(key, token), 0, Optional.empty());
                if (transition.equals("REGISTRATION_FREE") || transition.equals("UNLINK")) {
                    var first = changed(run(admit(registration(key)))).identity();
                    token = first.bindingToken().value();
                    if (transition.equals("REGISTRATION_FREE"))
                        run(admit(unlink(key, first.bindingToken(), key)));
                }
                if (transition.equals("LINK")) {
                    var initiator = key("a-initiator-" + rollback);
                    var first = changed(run(admit(registration(initiator)))).identity();
                    work = admit(link(initiator, first.bindingToken(), key));
                } else if (transition.equals("UNLINK")) {
                    work =
                            admit(
                                    unlink(
                                            key,
                                            new com.kartaguez.pocoma.domain.identity.v3
                                                    .BindingToken(token),
                                            key));
                } else work = admit(registration(key));
                var command = command(key, token);
                var held = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var holderPid = new AtomicInteger();
                var holder =
                        threads.submit(
                                () -> {
                                    try {
                                        run(
                                                work,
                                                phase -> {
                                                    if (phase.equals("after_apply")) {
                                                        holderPid.set(
                                                                jdbc.queryForObject(
                                                                        "select pg_backend_pid()",
                                                                        Integer.class));
                                                        held.countDown();
                                                        await(release);
                                                        if (rollback)
                                                            throw new IllegalStateException(
                                                                    "intentional Identity"
                                                                            + " rollback");
                                                    }
                                                });
                                        assertFalse(rollback);
                                    } catch (AttemptTransactions.Failure e) {
                                        assertTrue(rollback);
                                        assertEquals(
                                                AttemptTransactions.Resolution.ROLLED_BACK,
                                                e.resolution());
                                    }
                                });
                await(held);
                var witness =
                        observe(command); // RR sees only the state committed before the holder.
                int mark = lockCalls.size();
                var waiter = threads.submit(() -> tx.write(() -> pot.protect(command, witness)));
                try {
                    blocked(waiterPid(key, holderPid.get(), mark), holderPid.get());
                } finally {
                    release.countDown();
                }
                holder.get(20, TimeUnit.SECONDS);
                assertEquals(rollback, waiter.get(20, TimeUnit.SECONDS));
                var finalWitness = observe(command);
                assertEquals(rollback, finalWitness.equals(witness));
                evidence.add(
                        "Identity first variant="
                                + transition
                                + " rollback="
                                + rollback
                                + " observed="
                                + witness
                                + " afterResolution="
                                + finalWitness);
            }
        }
    }

    int waiterPid(ExternalIdentity key, int holder, int mark) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var snapshot = List.copyOf(lockCalls);
            var call =
                    snapshot.subList(mark, snapshot.size()).stream()
                            .filter(c -> c.pid() != holder && c.key().equals(key))
                            .reduce((a, b) -> b);
            if (call.isPresent()) return call.get().pid();
            Thread.yield();
        }
        throw new AssertionError("waiter reached canonical EUI SQL");
    }

    @Test
    void l3_canonical_pot_path() {
        var first = new ExternalIdentity("é", "\uE000");
        var second = new ExternalIdentity("é", "\uD800\uDC00");
        var a = changed(run(admit(registration(first)))).identity();
        var b = changed(run(admit(link(first, a.bindingToken(), second)))).identity();
        stable(
                command(second, b.bindingToken().value()),
                1,
                Optional.of(new UserId(a.userId().value())));
        run(admit(unlink(second, b.bindingToken(), first)));
        var grouped = new LinkedHashMap<Integer, List<LockCall>>();
        for (var call : lockCalls)
            grouped.computeIfAbsent(call.pid(), ignored -> new ArrayList<>()).add(call);
        for (var calls : grouped.values()) {
            var keys =
                    calls.stream()
                            .filter(c -> c.operation().equals("INSERT"))
                            .map(LockCall::key)
                            .toList();
            var sorted =
                    new TreeSet<ExternalIdentity>(
                            com.kartaguez.pocoma.domain.identity.v3.ExternalUserIdentity
                                    ::compareKeys);
            sorted.addAll(keys);
            assertEquals(new ArrayList<>(sorted), keys);
            assertEquals(keys.size() * 2, calls.size());
            for (int n = 0; n < keys.size(); n++) {
                assertEquals("INSERT", calls.get(n * 2).operation());
                assertEquals("FOR UPDATE", calls.get(n * 2 + 1).operation());
                assertEquals(keys.get(n), calls.get(n * 2 + 1).key());
            }
        }
        evidence.add(
                "Pot singleton and reversed two-EUI request use only canonical paired INSERT/FOR"
                        + " UPDATE, UTF-8 vectors");
    }

    @Test
    void l3_anchor_constraints_before_suffix() {
        var command = command(key("zero"), "invalid");
        var witness = observe(command);
        tx.write(
                () -> {
                    assertTrue(pot.protect(command, witness));
                    assertTrue(evidence.stream().anyMatch(s -> s.contains(STRUCTURAL)));
                    SpringAttemptTransactions.closePublication(source);
                    return null;
                });
        assertCounts(0, 0, 0, 0, 0);
        assertEquals(1, count("v3_eui_authority"));
        var poisoned = command(key("rollback-zero"), "invalid");
        var before = count("v3_eui_authority");
        assertThrows(
                AttemptTransactions.Failure.class,
                () ->
                        tx.write(
                                () -> {
                                    assertTrue(
                                            pot.protect(
                                                    poisoned,
                                                    new PotActorAuthority.Witness(
                                                            0, Optional.empty())));
                                    SpringAttemptTransactions.closePublication(source);
                                    try {
                                        pot.protect(poisoned, witness);
                                    } catch (RuntimeException ignored) {
                                    }
                                    return null;
                                }));
        assertEquals(before, count("v3_eui_authority"));
    }

    @Test
    void l3_no_hidden_transaction_and_redaction() {
        var command = command(key("guards"), "private-token-sentinel");
        assertThrows(IllegalStateException.class, () -> pot.observe(command));
        assertThrows(
                RuntimeException.class,
                () -> pot.protect(command, new PotActorAuthority.Witness(0, Optional.empty())));
        assertThrows(AttemptTransactions.Failure.class, () -> tx.write(() -> pot.observe(command)));
        assertThrows(
                AttemptTransactions.Failure.class,
                () ->
                        tx.read(
                                c ->
                                        pot.protect(
                                                command,
                                                new PotActorAuthority.Witness(
                                                        0, Optional.empty()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JdbcIdentityPotActorAuthority(adminSource, tx));
        admin.execute("alter table v3_eui_version rename to hidden_state");
        try {
            var failure = assertThrows(AttemptTransactions.Failure.class, () -> observe(command));
            assertTrue(allMessages(failure).contains("42P01"));
            assertFalse(allMessages(failure).contains(command.bindingToken()));
        } finally {
            admin.execute("alter table hidden_state rename to v3_eui_version");
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
