package com.kartaguez.pocoma.runtime.task.v3;

import static com.kartaguez.pocoma.domain.projection.v3.SubobjectContent.*;
import static com.kartaguez.pocoma.domain.projection.v3.SubobjectKind.*;
import static com.kartaguez.pocoma.engine.read.pot.v3.ReadPotV3Result.*;
import static com.kartaguez.pocoma.runtime.task.v3.ReadRuntimeI8Fixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.read.currentbinding.v3.GetCurrentBindingV3;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.*;

import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
class ReadFacadesPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withCommand("postgres", "-c", "log_line_prefix=%m [%p] xid=%x state=%e ");

    @BeforeAll
    static void initializeServer() throws Exception {
        initialize(PG);
    }

    static UUID id(long n) {
        return new UUID(0, n);
    }

    static final UUID OWNER = id(101), OTHER = id(102), S1 = id(201), S2 = id(202);
    static final ExternalIdentity EUI = new ExternalIdentity("issuer-a", "same-subject");

    /** Wrap the actual READ business pool; record SQL text only, never bound tokens. */
    static class Observed extends AbstractDataSource {
        final HikariDataSource delegate;
        final List<String> statements = new CopyOnWriteArrayList<>();
        volatile Runnable afterPointer = () -> {};

        Observed(HikariDataSource delegate) {
            this.delegate = delegate;
        }

        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        public Connection getConnection(String u, String p) throws SQLException {
            return wrap(delegate.getConnection(u, p));
        }

        Connection wrap(Connection c) {
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, m, args) -> {
                                try {
                                    Object value = m.invoke(c, args);
                                    if (m.getName().equals("prepareStatement")
                                            && args[0] instanceof String sql
                                            && value instanceof PreparedStatement st) {
                                        return Proxy.newProxyInstance(
                                                PreparedStatement.class.getClassLoader(),
                                                new Class<?>[] {PreparedStatement.class},
                                                (p, method, a) -> {
                                                    try {
                                                        if (method.getName().startsWith("execute"))
                                                            statements.add(sql);
                                                        Object result = method.invoke(st, a);
                                                        if (method.getName().equals("executeQuery")
                                                                && sql.startsWith(
                                                                        "select pot_version from"
                                                                            + " read_current_pot"))
                                                            afterPointer.run();
                                                        return result;
                                                    } catch (InvocationTargetException ex) {
                                                        throw ex.getCause();
                                                    }
                                                });
                                    }
                                    return value;
                                } catch (InvocationTargetException ex) {
                                    throw ex.getCause();
                                }
                            });
        }
    }

    static final class Queries implements AutoCloseable {
        final ReadRuntimeI8Fixture f;
        final ConfigurableApplicationContext ctx;
        final Observed read;
        final SpringAttemptTransactions owner;
        final AtomicLong writeAcquisitions = new AtomicLong();
        final JdbcPotVersionStore pots;
        final JdbcSubobjectVersionStore objects;
        final JdbcPotBalanceStore balances;
        final JdbcCurrentBindingStore bindings;
        final V3ReadFacadeComposition.Services services;

        Queries() throws Exception {
            f = new ReadRuntimeI8Fixture(PG);
            f.select(List.of(), false);
            ctx = f.start();
            try {
                writeAcquisitions.set(writeBorrowCount());
                read = new Observed(ctx.getBean("readBusiness", HikariDataSource.class));
                owner = new SpringAttemptTransactions(read, manager(read), 60);
                pots = new JdbcPotVersionStore(read, owner);
                objects = new JdbcSubobjectVersionStore(read, owner);
                balances = new JdbcPotBalanceStore(read, owner);
                bindings = new JdbcCurrentBindingStore(read, owner);
                services = withLimits(new ReadLimitsV3(50, 20, 16000000));
                binding(EUI, 1, OWNER, "token-a");
            } catch (RuntimeException | Error failure) {
                ctx.close();
                f.contexts.remove(ctx);
                throw failure;
            }
        }

        long writeBorrowCount() {
            var registry = ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            long count = 0;
            for (String name : List.of("writeBusiness", "writeControl")) {
                var pool = ctx.getBean(name, HikariDataSource.class);
                var timer =
                        registry.find("hikaricp.connections.acquire")
                                .tag("pool", pool.getPoolName())
                                .timer();
                assertNotNull(timer, "physical borrow instrumentation: " + name);
                count += timer.count();
            }
            return count;
        }

        V3ReadFacadeComposition.Services withLimits(ReadLimitsV3 limits) {
            var registry = ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            return V3ReadFacadeComposition.create(read, owner, limits, Set.of(), Optional::empty, false, registry);
        }

        void binding(ExternalIdentity eui, long version, UUID user, String token) {
            UUID euiId =
                    UUID.nameUUIDFromBytes(
                            (eui.issuer() + "/" + eui.subject())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            owner.write(
                    () -> {
                        bindings.apply(
                                new ExternalUserIdentity(
                                        new ExternalUserIdentityId(euiId),
                                        eui,
                                        version,
                                        user == null ? null : new PocomaUserId(user),
                                        token == null ? null : new BindingToken(token)));
                        return null;
                    });
        }

        PotVersion seed(
                UUID pot,
                long version,
                int expenses,
                Set<UUID> authorized,
                boolean deleted,
                boolean balance,
                boolean expenseDetails,
                String label) {
            return seed(
                    pot,
                    version,
                    expenses,
                    authorized,
                    deleted,
                    balance,
                    expenseDetails,
                    label,
                    Map.of());
        }

        PotVersion seed(
                UUID pot,
                long version,
                int expenses,
                Set<UUID> authorized,
                boolean deleted,
                boolean balance,
                boolean expenseDetails,
                String label,
                Map<UUID, UUID> attachments) {
            var shareholders =
                    List.of(
                            new PotVersion.Shareholder(
                                    S1,
                                    version,
                                    version,
                                    version,
                                    false,
                                    !attachments.containsKey(S1)),
                            new PotVersion.Shareholder(
                                    S2,
                                    version,
                                    version,
                                    version,
                                    false,
                                    !attachments.containsKey(S2)));
            var refs = new ArrayList<PotVersion.Expense>();
            var values = new ArrayList<SubobjectVersion>();
            add(
                    values,
                    pot,
                    POT_HEADER,
                    pot,
                    version,
                    new PotHeaderContent(new PotHeader(new PotId(pot), deleted)));
            add(
                    values,
                    pot,
                    POT_DETAILS,
                    pot,
                    version,
                    new PotDetailsContent(
                            new PotDetails(new PotId(pot), new Label(label), new UserId(OWNER))));
            for (UUID s : List.of(S1, S2)) {
                add(
                        values,
                        pot,
                        SHAREHOLDER_HEADER,
                        s,
                        version,
                        new ShareholderHeaderContent(
                                new ShareholderHeader(
                                        new PotId(pot), new ShareholderId(s), false)));
                add(
                        values,
                        pot,
                        SHAREHOLDER_DETAILS,
                        s,
                        version,
                        new ShareholderDetailsContent(
                                new ShareholderDetails(
                                        new PotId(pot),
                                        new ShareholderId(s),
                                        new Name("share-" + s),
                                        Optional.ofNullable(attachments.get(s)).map(UserId::new))));
                add(
                        values,
                        pot,
                        SHAREHOLDER_WEIGHT,
                        s,
                        version,
                        new ShareholderWeightContent(
                                new ShareholderWeight(
                                        new PotId(pot),
                                        new ShareholderId(s),
                                        new com.kartaguez.pocoma.domain.pot.v3.Weight(
                                                new com.kartaguez.pocoma.domain.pot.v3.Fraction(
                                                        2, 3)))));
            }
            for (int n = 0; n < expenses; n++) {
                UUID expense = id(1000 + n);
                refs.add(new PotVersion.Expense(expense, version, version, version, false));
                add(
                        values,
                        pot,
                        EXPENSE_HEADER,
                        expense,
                        version,
                        new ExpenseHeaderContent(
                                new ExpenseHeader(new PotId(pot), new ExpenseId(expense), false)));
                if (expenseDetails)
                    add(
                            values,
                            pot,
                            EXPENSE_DETAILS,
                            expense,
                            version,
                            new ExpenseDetailsContent(
                                    new ExpenseDetails(
                                            new PotId(pot),
                                            new ExpenseId(expense),
                                            new Label(label + "-expense-" + n),
                                            LocalDate.of(2026, 11, 3))));
                add(
                        values,
                        pot,
                        EXPENSE_FINANCIALS,
                        expense,
                        version,
                        new ExpenseFinancialsContent(
                                new ExpenseFinancials(
                                        new PotId(pot),
                                        new ExpenseId(expense),
                                        new com.kartaguez.pocoma.domain.pot.v3.Amount(
                                                new com.kartaguez.pocoma.domain.pot.v3.Fraction(
                                                        1, 3)),
                                        new ShareholderId(S1),
                                        Map.of(
                                                new ShareholderId(S1),
                                                Optional.empty(),
                                                new ShareholderId(S2),
                                                Optional.empty()))));
            }
            var p =
                    new PotVersion(
                            pot,
                            version,
                            version,
                            version,
                            deleted,
                            shareholders,
                            refs,
                            authorized,
                            new HashSet<>(attachments.values()));
            owner.write(
                    () -> {
                        objects.insertOrVerifyAll(values);
                        pots.insertAndAdvance(p);
                        if (balance && !deleted) balance(pot, version);
                        return null;
                    });
            return p;
        }

        void balance(UUID pot, long version) {
            balances.insertOrVerify(
                    new PotBalance(
                            pot,
                            version,
                            Map.of(
                                    S1,
                                    new com.kartaguez.pocoma.domain.pot.v3.Fraction(1, 3),
                                    S2,
                                    new com.kartaguez.pocoma.domain.pot.v3.Fraction(-1, 3))));
        }

        static void add(
                List<SubobjectVersion> values,
                UUID pot,
                SubobjectKind kind,
                UUID object,
                long version,
                SubobjectContent content) {
            values.add(new SubobjectVersion(new SubobjectKey(pot, kind, object, version), content));
        }

        void evidence(String name) throws Exception {
            assertEquals(
                    writeAcquisitions.get(),
                    writeBorrowCount(),
                    name + " zero WRITE acquisition/SQL during consultations");
            f.trace.add(
                    name
                            + " WRITE acquisition delta="
                            + (writeBorrowCount() - writeAcquisitions.get())
                            + " READ statements="
                            + read.statements.size());
            f.trace.addAll(
                    read.statements.stream()
                            .distinct()
                            .map(
                                    s ->
                                            "READ query "
                                                    + s.replaceAll(
                                                            "\\([^()]*\\?[^()]*\\)",
                                                            "(bound batch)"))
                            .toList());
            f.evidence(name);
        }

        public void close() throws Exception {
            ctx.close();
            f.contexts.remove(ctx);
            f.close();
        }
    }

    @SuppressWarnings("unchecked")
    static <T> Available<T> available(ReadPotV3Result<T> result, String scenario) {
        assertInstanceOf(Available.class, result, scenario);
        return (Available<T>) result;
    }

    static List<ExpenseRef> refs(PotVersion p) {
        return p.expenses().stream()
                .map(
                        e ->
                                new ExpenseRef(
                                        e.id(),
                                        e.headerVersion(),
                                        e.detailsVersion(),
                                        e.financialsVersion(),
                                        e.deleted()))
                .toList();
    }

    @Test
    void currentHistoryAndConcurrentAdvanceStayExact() throws Exception {
        try (var q = new Queries()) {
            for (int count : List.of(0, 200, 2000)) {
                UUID pot = UUID.randomUUID();
                q.seed(pot, 1, count, Set.of(OWNER), false, true, true, "original");
                var view =
                        available(q.services.current(EUI, "token-a", pot, 0), "manifest=" + count);
                assertEquals(1, view.servedVersion());
                assertEquals("original", view.value().label());
                assertEquals(count, view.value().expenses().size());
                assertEquals(
                        List.of(S1, S2),
                        view.value().shareholders().stream().map(Shareholder::id).toList());
                assertEquals(
                        new com.kartaguez.pocoma.domain.pot.v3.Fraction(1, 3),
                        view.value().balances().get(S1));
                assertFalse(
                        view.value().toString().contains(OWNER.toString()),
                        "no internal User in view");
                assertInstanceOf(
                        NotReady.class,
                        q.services.current(EUI, "token-a", pot, 2),
                        "minimum after authorization");
                assertInstanceOf(Available.class, q.services.current(EUI, "token-a", pot, 1));
            }
            var registry = q.ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            var permits = registry.find("pocoma.v3.read.quota.permits").tag("scope", "sql").gauge();
            assertNotNull(permits, "quota permits gauge must exist");
            assertEquals(2.0, permits.value());
            var queryTimer = registry.find("pocoma.v3.read.query.duration").tag("operation", "current").tag("outcome", "available").timer();
            assertNotNull(queryTimer, "read query duration timer must exist");
            assertTrue(queryTimer.count() >= 3);
            for (String name : List.of("writeBusiness", "writeControl", "readBusiness", "readControl")) {
                var pool = q.ctx.getBean(name, HikariDataSource.class);
                assertNotNull(registry.find("hikaricp.connections.active").tag("pool", pool.getPoolName()).gauge(), "active pool gauge: " + name);
                assertNotNull(registry.find("hikaricp.connections.pending").tag("pool", pool.getPoolName()).gauge(), "pending pool gauge: " + name);
            }
            UUID pot = UUID.randomUUID();
            q.seed(pot, 1, 2, Set.of(OWNER), false, true, true, "before");
            var captured = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            q.read.afterPointer =
                    () -> {
                        captured.countDown();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS), "capture release");
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                    };
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var reading = executor.submit(() -> q.services.current(EUI, "token-a", pot, 0));
                assertTrue(captured.await(10, TimeUnit.SECONDS), "CurrentPot captured V1");
                q.seed(pot, 2, 3, Set.of(OWNER), false, true, true, "after");
                release.countDown();
                var snapshot = available(reading.get(10, TimeUnit.SECONDS), "concurrent advance");
                assertEquals(1, snapshot.servedVersion());
                assertEquals("before", snapshot.value().label());
                assertEquals(2, snapshot.value().expenses().size());
            } finally {
                release.countDown();
                q.read.afterPointer = () -> {};
            }
            assertEquals(
                    2,
                    available(q.services.current(EUI, "token-a", pot, 1), "new snapshot")
                            .servedVersion());
            // Uncommitted publication and rollback are observed from another physical connection.
            try (var c = q.f.read.getDataSource().getConnection()) {
                c.setAutoCommit(false);
                try (var s =
                        c.prepareStatement(
                                "update read_current_pot set pot_version=1 where pot_id=?")) {
                    s.setObject(1, pot);
                    s.executeUpdate();
                }
                assertEquals(
                        2,
                        available(
                                        q.services.current(EUI, "token-a", pot, 0),
                                        "uncommitted pointer invisible")
                                .servedVersion());
                c.rollback();
            }
            q.seed(pot, 3, 3, Set.of(OWNER), true, false, true, "deleted");
            assertInstanceOf(NotFound.class, q.services.current(EUI, "token-a", pot, 999999));
            assertInstanceOf(NotFound.class, q.services.at(EUI, "token-a", pot, 3));
            assertEquals(
                    "before",
                    available(q.services.at(EUI, "token-a", pot, 1), "historical after deletion")
                            .value()
                            .label());
            q.evidence("T04");
        }
    }

    @Test
    void readinessDoesNotInventFreshness() throws Exception {
        try (var q = new Queries()) {
            UUID pot = UUID.randomUUID();
            assertTrue(q.services.activeParticipants().isEmpty());
            assertTrue(q.services.workerState().isEmpty());
            assertInstanceOf(
                    NotFound.class,
                    q.services.current(EUI, "token-a", pot, 99999),
                    "new READ has no existence oracle");
            q.seed(pot, 2, 0, Set.of(OWNER), false, false, true, "authorized");
            q.owner.write(
                    () -> {
                        q.balance(pot, 1);
                        q.balance(pot, 3);
                        return null;
                    });
            assertInstanceOf(
                    NotReady.class,
                    q.services.current(EUI, "token-a", pot, 2),
                    "neighbor balances cannot substitute V2");
            q.binding(new ExternalIdentity("issuer-b", "same-subject"), 1, OTHER, "other");
            assertInstanceOf(
                    NotFound.class,
                    q.services.current(
                            new ExternalIdentity("issuer-b", "same-subject"), "other", pot, 999999),
                    "authorization precedes minimum/dependencies");
            q.owner.write(
                    () -> {
                        q.balance(pot, 2);
                        return null;
                    });
            assertEquals(
                    2,
                    available(q.services.current(EUI, "token-a", pot, 2), "exact balance arrived")
                            .servedVersion());
            assertEquals(
                    0,
                    q.f.read.queryForObject(
                            "select count(*) from read_pot_visibility_state", Integer.class),
                    "no visibility prerequisite");
            // Administrative absence fault: remove a required immutable component, then restore its
            // exact
            // bytes.
            var key = new SubobjectKey(pot, POT_DETAILS, pot, 2);
            var missing = q.owner.read(c -> q.objects.readAll(List.of(key), c).get(key));
            q.f.read.execute("alter table read_subobject_version disable trigger user");
            q.f.read.update(
                    "delete from read_subobject_version where pot_id=? and kind='POT_DETAILS' and"
                            + " local_version=2",
                    pot);
            q.f.read.execute(
                    "alter table read_subobject_version enable always trigger"
                        + " read_subobject_immutable");
            q.f.read.execute(
                    "alter table read_subobject_version enable always trigger"
                        + " read_subobject_no_truncate");
            assertInstanceOf(
                    NotReady.class,
                    q.services.current(EUI, "token-a", pot, 0),
                    "missing exact subobject");
            q.owner.write(
                    () -> {
                        q.objects.insertOrVerifyAll(List.of(missing));
                        return null;
                    });
            assertInstanceOf(
                    Available.class,
                    q.services.current(EUI, "token-a", pot, 0),
                    "restored exact subobject");
            q.read.delegate.close();
            assertInstanceOf(
                    Unavailable.class,
                    q.services.current(EUI, "token-a", pot, 0),
                    "physical READ outage");
            q.evidence("T05");
        }
    }

    @Test
    void expenseBatchesRespectExactManifestAndBounds() throws Exception {
        try (var q = new Queries()) {
            UUID pot = UUID.randomUUID();
            var p = q.seed(pot, 1, 51, Set.of(OWNER), false, true, true, "batch");
            var all = refs(p);
            q.seed(pot, 2, 51, Set.of(OWNER), false, true, true, "new");
            for (int count : List.of(49, 50)) {
                var requested = new ArrayList<>(all.subList(0, count));
                Collections.reverse(requested);
                q.read.statements.clear();
                var result =
                        available(
                                q.services.expenses(EUI, "token-a", pot, 1, requested),
                                "count=" + count);
                assertEquals(
                        requested,
                        result.value().stream().map(Expense::reference).toList(),
                        "requested order at V1");
                assertEquals(
                        "batch-expense-" + (count - 1),
                        result.value().getFirst().label(),
                        "exact details V1");
                assertEquals(
                        1,
                        q.read.statements.stream()
                                .filter(
                                        s ->
                                                s.startsWith("select")
                                                        && s.contains(
                                                                "from read_subobject_version"))
                                .count(),
                        "grouped SQL, not N+1");
            }
            assertInstanceOf(
                    InvalidInput.class,
                    q.services.expenses(EUI, "token-a", pot, 1, all),
                    "limit+1");
            assertInstanceOf(
                    InvalidInput.class,
                    q.services.expenses(EUI, "token-a", pot, 1, List.of()),
                    "empty");
            assertInstanceOf(
                    InvalidInput.class,
                    q.services.expenses(
                            EUI, "token-a", pot, 1, List.of(all.getFirst(), all.getFirst())),
                    "duplicate");
            assertInstanceOf(
                    NotFound.class,
                    q.services.expenses(
                            EUI,
                            "token-a",
                            pot,
                            1,
                            List.of(new ExpenseRef(id(1000), 2, 2, 2, false))),
                    "foreign revision masked");
            assertInstanceOf(
                    NotFound.class,
                    q.services.expenses(
                            EUI,
                            "token-a",
                            pot,
                            1,
                            List.of(new ExpenseRef(UUID.randomUUID(), 1, 1, 1, false))),
                    "foreign Pot/reference masked");
            assertEquals(
                    51,
                    available(
                                    q.services.current(EUI, "token-a", pot, 0),
                                    "manifest independent of batch bound")
                            .value()
                            .expenses()
                            .size());
            UUID incomplete = UUID.randomUUID();
            var partial = q.seed(incomplete, 1, 1, Set.of(OWNER), false, true, false, "incomplete");
            assertInstanceOf(
                    NotReady.class,
                    q.services.expenses(EUI, "token-a", incomplete, 1, refs(partial)),
                    "missing exact detail is all-or-error");
            UUID huge = UUID.randomUUID();
            var large = q.seed(huge, 1, 1, Set.of(OWNER), false, true, true, "X".repeat(50000));
            var bounded = q.withLimits(new ReadLimitsV3(50, 20, 10000));
            assertInstanceOf(
                    Unavailable.class,
                    bounded.expenses(EUI, "token-a", huge, 1, refs(large)),
                    "response budget explicit error, no truncation");
            q.evidence("T06");
        }
    }

    @Test
    void bindingAndResultsKeepTheirOwnAuthorities() throws Exception {
        try (var q = new Queries()) {
            UUID pot = UUID.randomUUID();
            q.seed(pot, 1, 0, Set.of(OWNER), false, true, true, "private");
            var sameUser = new ExternalIdentity("issuer-a", "second");
            var otherIssuer = new ExternalIdentity("issuer-b", "same-subject");
            q.binding(sameUser, 1, OWNER, "second-token");
            q.binding(otherIssuer, 1, OTHER, "other-token");
            UUID attached = UUID.randomUUID();
            q.seed(
                    attached,
                    1,
                    0,
                    Set.of(OWNER, OTHER),
                    false,
                    true,
                    true,
                    "two attachments",
                    Map.of(S1, OTHER, S2, OTHER));
            assertInstanceOf(
                    Available.class,
                    q.services.at(otherIssuer, "other-token", attached, 1),
                    "two Shareholders for one User");
            q.seed(
                    attached,
                    2,
                    0,
                    Set.of(OWNER, OTHER),
                    false,
                    true,
                    true,
                    "one attachment",
                    Map.of(S2, OTHER));
            assertInstanceOf(
                    Available.class,
                    q.services.current(otherIssuer, "other-token", attached, 0),
                    "removing one Shareholder preserves the other right");
            q.seed(attached, 3, 0, Set.of(OWNER), false, true, true, "no attachment");
            assertInstanceOf(
                    NotFound.class,
                    q.services.current(otherIssuer, "other-token", attached, 0),
                    "removing both revokes current right");
            assertInstanceOf(
                    Available.class,
                    q.services.at(otherIssuer, "other-token", attached, 1),
                    "historical right is evaluated at V");
            assertInstanceOf(
                    Available.class,
                    q.services.current(sameUser, "second-token", pot, 0),
                    "same User own EUI token");
            assertInstanceOf(
                    NotFound.class,
                    q.services.current(otherIssuer, "other-token", pot, 9999),
                    "same subject other issuer");
            assertInstanceOf(
                    NotFound.class,
                    q.services.current(sameUser, "token-a", pot, 0),
                    "foreign token even same User");
            assertInstanceOf(
                    InvalidInput.class,
                    q.services.current(null, "token-a", pot, 0),
                    "EUI attestation mandatory");
            for (var method : ReadPotV3.class.getDeclaredMethods())
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    assertFalse(
                            Arrays.asList(method.getParameterTypes()).contains(PocomaUserId.class),
                            "no caller-supplied User authority: " + method.getName());
            var own = available(q.services.binding(EUI, 1), "own Binding without token").value();
            assertEquals("token-a", own.value().bindingToken());
            assertEquals(
                    GetCurrentBindingV3.Status.NOT_READY,
                    available(q.services.binding(sameUser, 2), "minVersion same EUI")
                            .value()
                            .status());
            assertEquals(
                    GetCurrentBindingV3.Status.ABSENT,
                    available(
                                    q.services.binding(new ExternalIdentity("unknown", "none"), 0),
                                    "normal absent Binding")
                            .value()
                            .status());
            // Results are READ-only and independent of Binding existence/changes.
            UUID command = UUID.randomUUID(), identity = UUID.randomUUID();
            q.f.read.update(
                    "insert into read_command_result values (?,'CreatePot',?,?,?,'CHANGED',1,null)",
                    command,
                    EUI.issuer(),
                    EUI.subject(),
                    pot);
            q.f.read.update(
                    "insert into read_identity_request_result values"
                        + " (?,'LINK',?,?,'CHANGED',?,?,9,null)",
                    identity,
                    EUI.issuer(),
                    EUI.subject(),
                    otherIssuer.issuer(),
                    otherIssuer.subject());
            assertTrue(
                    available(q.services.commandResult(command, EUI), "requester Command result")
                            .value()
                            .isPresent());
            assertTrue(
                    available(q.services.identityResult(identity, EUI), "initiator Link result")
                            .value()
                            .isPresent());
            assertEquals(
                    available(q.services.identityResult(UUID.randomUUID(), otherIssuer), "absent")
                            .value(),
                    available(
                                    q.services.identityResult(identity, otherIssuer),
                                    "target is not requester")
                            .value());
            assertTrue(
                    available(
                                    q.services.commandResult(command, sameUser),
                                    "same User not requester")
                            .value()
                            .isEmpty());
            q.binding(EUI, 2, null, null);
            assertInstanceOf(
                    NotFound.class,
                    q.services.current(EUI, "token-a", pot, 0),
                    "new consultation after propagated Unlink");
            assertInstanceOf(
                    Available.class,
                    q.services.current(sameUser, "second-token", pot, 0),
                    "other EUI unaffected");
            assertTrue(
                    available(q.services.commandResult(command, EUI), "result after Unlink")
                            .value()
                            .isPresent());
            q.binding(EUI, 3, OTHER, "new-token");
            assertInstanceOf(
                    NotFound.class,
                    q.services.current(EUI, "token-a", pot, 0),
                    "old token after re-Link");
            assertInstanceOf(
                    NotFound.class,
                    q.services.current(EUI, "new-token", pot, 0),
                    "new User not authorized");
            assertTrue(
                    available(q.services.identityResult(identity, EUI), "same result after re-Link")
                            .value()
                            .isPresent());
            q.evidence("T07");
        }
        realUnlinkPropagationWindow();
    }

    static void realUnlinkPropagationWindow() throws Exception {
        try (var f = new ReadRuntimeI8Fixture(PG)) {
            var registration = f.admit("REGISTRATION", "owner", "owner", null);
            f.produce(registration);
            var pot = new PotId(UUID.randomUUID());
            var create =
                    f.admitCommand(
                            "owner",
                            f.ownToken("owner").value(),
                            pot,
                            0,
                            new com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand
                                    .CreatePot(new Label("real window")));
            f.produce(create);
            String oldToken = f.ownToken("owner").value();
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
            var context = f.start();
            var services = f.services(context);
            await(
                    () ->
                            services.current(f.eui("owner"), oldToken, pot.value(), 0)
                                    instanceof Available<?>);
            f.read.execute(
                    "create function i8_hold_binding() returns trigger language plpgsql as $$ begin"
                        + " perform pg_advisory_xact_lock(1188007);return new;end $$");
            f.read.execute(
                    "create trigger i8_hold_binding before update on read_current_binding for each"
                        + " row execute function i8_hold_binding()");
            try (var gate = f.read.getDataSource().getConnection()) {
                gate.setAutoCommit(false);
                try (var statement = gate.createStatement()) {
                    statement.execute("select pg_advisory_xact_lock(1188007)");
                }
                var unlink = f.admit("UNLINK", "owner", "owner", new BindingToken(oldToken));
                f.produce(unlink);
                await(
                        () ->
                                f.read.queryForObject(
                                                "select count(*) from read_identity_request_result"
                                                    + " where request_id=?",
                                                Integer.class,
                                                unlink.requestId())
                                        == 1);
                await(
                        () ->
                                f.read.queryForObject(
                                                "select count(*) from pg_stat_activity where"
                                                    + " application_name='i5-test-read-business'"
                                                    + " and wait_event='advisory'",
                                                Integer.class)
                                        > 0);
                assertInstanceOf(
                        Available.class,
                        services.current(f.eui("owner"), oldToken, pot.value(), 0),
                        "Unlink WRITE committed; READ window accepted until actual propagation");
                assertTrue(
                        available(
                                        services.identityResult(unlink.requestId(), f.eui("owner")),
                                        "Unlink result before Binding")
                                .value()
                                .isPresent());
                gate.commit();
                await(
                        () -> {
                            var answer = services.binding(f.eui("owner"), 2);
                            return answer instanceof Available<?> value
                                    && value.value() instanceof GetCurrentBindingV3.Answer binding
                                    && binding.value() != null
                                    && !binding.value().active();
                        });
                assertInstanceOf(
                        NotFound.class,
                        services.current(f.eui("owner"), oldToken, pot.value(), 0),
                        "new consultation after propagated Unlink refuses old token");
                assertTrue(
                        available(
                                        services.identityResult(unlink.requestId(), f.eui("owner")),
                                        "result stays owned by requester after Unlink")
                                .value()
                                .isPresent());
                f.trace.add(
                        "T07 real WRITE Unlink -> result visible while Binding retained -> Binding"
                            + " V2 inactive -> new READ refused; no revocation SLO inferred");
            }
            f.physical("T07-real-window");
            f.drain(context);
            f.evidence("T07-real-window");
        }
    }

    @Test
    void navigationIsKeysetAndNeverAuthorizes() throws Exception {
        try (var q = new Queries()) {
            for (int n = 1; n <= 25; n++)
                q.f.read.update(
                        "insert into read_user_pot values (?,?,1,true)", OWNER, id(10000 + n));
            for (int limit : List.of(19, 20))
                assertEquals(
                        limit,
                        available(
                                        q.services.visible(EUI, "token-a", null, limit),
                                        "page limit=" + limit)
                                .value()
                                .size());
            assertInstanceOf(
                    InvalidInput.class,
                    q.services.visible(EUI, "token-a", null, 21),
                    "page limit+1");
            var first =
                    available(q.services.visible(EUI, "token-a", null, 20), "first page").value();
            UUID after = first.getLast();
            q.f.read.update("insert into read_user_pot values (?,?,1,true)", OWNER, id(9999));
            q.f.read.update("insert into read_user_pot values (?,?,1,true)", OWNER, id(10030));
            q.f.read.update("update read_user_pot set visible=false where pot_id=?", id(10022));
            var second =
                    available(q.services.visible(EUI, "token-a", after, 20), "changed page")
                            .value();
            assertEquals(
                    List.of(id(10021), id(10023), id(10024), id(10025), id(10030)),
                    second,
                    "keyset after insertion/removal");
            assertTrue(Collections.disjoint(first, second));
            UUID authorized = UUID.randomUUID(), fakeVisible = id(10001);
            q.seed(authorized, 1, 0, Set.of(OWNER), false, true, true, "direct");
            q.seed(fakeVisible, 1, 0, Set.of(OTHER), false, true, true, "foreign");
            assertInstanceOf(
                    Available.class,
                    q.services.current(EUI, "token-a", authorized, 0),
                    "direct access outside navigation");
            assertInstanceOf(
                    NotFound.class,
                    q.services.current(EUI, "token-a", fakeVisible, 999999),
                    "visible entry never authorizes");
            q.binding(EUI, 2, OTHER, "new-token");
            assertTrue(
                    available(
                                    q.services.visible(EUI, "new-token", null, 20),
                                    "Binding resolved each page")
                            .value()
                            .isEmpty());
            q.f.read.update("insert into read_user_pot values (?,?,1,true)", OTHER, id(10040));
            assertInstanceOf(
                    Unavailable.class,
                    q.withLimits(new ReadLimitsV3(50, 20, 39)).visible(EUI, "new-token", null, 1),
                    "response bound, never truncate");
            q.evidence("T08");
        }
    }
}
