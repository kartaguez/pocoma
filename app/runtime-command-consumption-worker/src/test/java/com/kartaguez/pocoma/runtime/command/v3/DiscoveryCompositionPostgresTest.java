package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.*;
import java.time.*;
import java.util.*;

@Testcontainers
class DiscoveryCompositionPostgresTest {
    @Container static final PostgreSQLContainer PG = ConsumptionBootstrapPostgresTest.PG;

    @BeforeAll
    static void init() throws Exception {
        ConsumptionBootstrapPostgresTest.databases();
    }

    ConsumptionBootstrapPostgresTest f;
    String react;
    final List<String> trace = new ArrayList<>();
    static final V3BootstrapGuard.Privileges COMBINED =
            V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY;
    static final DiscoveryEngine.Settings SETTINGS =
            new DiscoveryEngine.Settings(
                    128, 32, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));

    @BeforeEach
    void setup() throws Exception {
        f = new ConsumptionBootstrapPostgresTest();
        f.freshAuthorities();
        react = "react_" + UUID.randomUUID().toString().replace("-", "");
        f.write.execute("create role " + react + " login password 'fixture-runtime'");
        for (var target : List.of(f.w, f.r)) script("grant-l2-lifecycle.sql", target);
        f.guard().validateLifecyclePrivileges();
        assertThrows(IllegalStateException.class, () -> f.guard().validate());
        for (var target : List.of(f.w, f.r)) script("grant-l2-discovery.sql", target);
    }

    void script(String name, V3BootstrapGuard.Target target) throws Exception {
        PG.copyFileToContainer(
                MountableFile.forHostPath(Path.of("../../scripts/v3/" + name).toAbsolutePath()),
                "/tmp/" + name);
        var r =
                PG.execInContainer(
                        "env",
                        "PGOPTIONS=-c search_path=" + f.schema,
                        "psql",
                        "-X",
                        "-U",
                        PG.getUsername(),
                        "-d",
                        target.database(),
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-v",
                        "expected_database=" + target.database(),
                        "-v",
                        "expected_schema=" + f.schema,
                        "-v",
                        "expected_admin=" + PG.getUsername(),
                        "-v",
                        "runtime_role=" + target.runtimeRole(),
                        "-v",
                        "reactivation_role=" + react,
                        "-f",
                        "/tmp/" + name);
        trace.add(
                name
                        + " target="
                        + target.database()
                        + " exit="
                        + r.getExitCode()
                        + " "
                        + r.getStdout()
                        + r.getStderr());
        assertEquals(0, r.getExitCode(), r.getStderr());
    }

    @AfterEach
    void evidence(TestInfo info) throws Exception {
        trace.add("WRITE ACL " + acl(f.write));
        trace.add("READ ACL " + acl(f.read));
        var path =
                Path.of(
                        "target/v3-i4-evidence/"
                                + info.getTestMethod().orElseThrow().getName()
                                + ".txt");
        Files.createDirectories(path.getParent());
        Files.write(path, trace);
    }

    List<Map<String, Object>> acl(JdbcTemplate sql) {
        return sql.queryForList(
                "select table_name,column_name,grantee,privilege_type from"
                    + " information_schema.column_privileges where table_schema=current_schema()"
                    + " order by 1,2,3,4");
    }

    ConsumptionDefinition definition(ConsumableFamily family) {
        return V3ConsumptionCatalogConfiguration.definitions().stream()
                .filter(d -> d.family() == family)
                .findFirst()
                .orElseThrow();
    }

    SpringAttemptTransactions jdbcOwner(javax.sql.DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return new SpringAttemptTransactions(source, manager);
    }

    Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    @Test
    void sharedPhysicalOwnerAndAuthorities() throws Exception {
        var emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(f.w.source());
        emf.setPackagesToScan("com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        emf.afterPropertiesSet();
        try {
            var manager = new JpaTransactionManager(emf.getObject());
            manager.setDataSource(f.w.source());
            manager.setNestedTransactionAllowed(false);
            var write = new SpringAttemptTransactions(f.w.source(), manager);
            var read = jdbcOwner(f.r.source());
            f.write.execute(
                    "create table fixture_identity(id int primary key,marker text);insert into"
                        + " fixture_identity values(1,'unchanged');grant select,update(marker) on"
                        + " fixture_identity to "
                            + f.w.runtimeRole());
            var runtime = new JdbcTemplate(f.w.source());
            var authority =
                    new PotActorAuthority() {
                        public Witness observe(PotCommand c) {
                            return new Witness(1, Optional.of(new UserId(new UUID(0, 1))));
                        }

                        public boolean protect(PotCommand c, Witness observed) {
                            runtime.queryForList(
                                    "select marker from fixture_identity where id=1 for update");
                            return observed.equals(observe(c));
                        }
                    };
            var command = definition(ConsumableFamily.COMMAND);
            var capacity =
                    new WorkerCapacity(
                            2,
                            Map.of(
                                    command.id(),
                                    2,
                                    definition(ConsumableFamily.POT_EVENT).id(),
                                    2));
            var lifecycle =
                    V3PotCommandComposition.assemble(
                            f.w.source(),
                            write,
                            authority,
                            Clock.systemUTC(),
                            f.guard(),
                            LifecycleSettings.pilot(),
                            capacity,
                            6,
                            COMBINED);
            var discovery =
                    V3ConsumptionDiscoveryComposition.requests(
                            f.guard(), command, f.w.source(), write, SETTINGS, COMBINED);
            assertSame(write, field(lifecycle, "transactions"));
            assertSame(write, field(discovery, "effects"));
            UUID id = new UUID(0, 3);
            f.write.update(
                    "insert into"
                        + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                        + " values(?,'fixture','actor','fixture',?,0,'CreatePot',?::jsonb)",
                    id,
                    UUID.randomUUID(),
                    PotCommandJson.encode(
                            new PotCommand.CreatePot(new Label("Discovery composition"))));
            var lease = lifecycle.acquirePartition(0).orElseThrow();
            assertEquals(
                    1,
                    discovery
                            .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                            .committedAdmissions());
            var claim = lifecycle.acquire(lease, List.of(id)).claims().getFirst();
            assertEquals(ConsumptionEngine.Status.COMMITTED, lifecycle.execute(claim).status());
            assertEquals(
                    0,
                    discovery
                            .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh())
                            .committedAdmissions());
            var event = definition(ConsumableFamily.POT_EVENT);
            var readDiscovery =
                    V3ConsumptionDiscoveryComposition.events(
                            f.guard(),
                            event,
                            f.r.source(),
                            read,
                            f.w.source(),
                            write,
                            SETTINGS,
                            COMBINED);
            assertSame(read, field(readDiscovery, "effects"));
            assertSame(write, field(readDiscovery, "sourceReads"));
            for (int p = 0; p < 4; p++) {
                final int partition = p;
                var ownership = new JdbcPartitionOwnershipStore(f.r.source());
                var l =
                        read.write(
                                () ->
                                        ownership
                                                .acquire(
                                                        event,
                                                        partition,
                                                        UUID.randomUUID(),
                                                        Duration.ofSeconds(30))
                                                .orElseThrow());
                assertEquals(1, readDiscovery.discoverEvents(l).checkpoint().orElseThrow());
            }
            assertEquals(
                    1,
                    f.read.queryForObject("select count(*) from consumption_slot", Integer.class));
            assertEquals(
                    4,
                    f.read.queryForObject(
                            "select count(*) from discovery_checkpoint where position=1",
                            Integer.class));
            assertEquals(
                    "TERMINAL",
                    f.write.queryForObject("select status from v3_command", String.class));
            f.read.execute(
                    "create table fixture_read_effect(id uuid primary key,value text);grant"
                        + " select,insert on fixture_read_effect to "
                            + f.r.runtimeRole());
            var readSql = new JdbcTemplate(f.r.source());
            var readParticipant =
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID eventId) {
                            return Optional.of(
                                    new Prepared() {
                                        public void finalizeIn(SlotCompletion completion) {
                                            SpringAttemptTransactions.guarded(
                                                    f.r.source(),
                                                    () -> {
                                                        readSql.update(
                                                                "insert into fixture_read_effect"
                                                                    + " values(?,'expected')",
                                                                eventId);
                                                        return null;
                                                    });
                                            completion.complete();
                                            SpringAttemptTransactions.closePublication(
                                                    f.r.source());
                                        }

                                        public void requireCompletionReady() {
                                            assertEquals(
                                                    1,
                                                    readSql.queryForObject(
                                                            "select count(*) from"
                                                                + " fixture_read_effect",
                                                            Integer.class));
                                        }
                                    });
                        }

                        public RecoveryObservation observe(UUID eventId, RecoveryReadContext c) {
                            SpringAttemptTransactions.requireRead(f.r.source(), c);
                            return readSql.queryForObject(
                                            "select exists(select 1 from fixture_read_effect where"
                                                + " id=?)",
                                            Boolean.class,
                                            eventId)
                                    ? RecoveryObservation.COMPLETED
                                    : RecoveryObservation.NOT_COMPLETED;
                        }
                    };
            var readLifecycle =
                    V3ConsumptionLifecycleComposition.assemble(
                            f.guard(),
                            event,
                            f.r.source(),
                            read,
                            readParticipant,
                            LifecycleSettings.pilot(),
                            capacity,
                            6,
                            c ->
                                    com.kartaguez.pocoma.engine.consumption.v3.port
                                            .AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                            COMBINED);
            assertSame(read, field(readLifecycle, "transactions"));
            assertSame(capacity, field(readLifecycle, "capacity"));
            var slot =
                    f.read.queryForMap("select consumable_id,partition_id from consumption_slot");
            int partition = (Integer) slot.get("partition_id");
            var row =
                    f.read.queryForMap(
                            "select owner_token,owned_until from partition_ownership where"
                                + " consumption_id=? and partition_id=?",
                            event.id().value(),
                            partition);
            var readLease =
                    new PartitionLease(
                            event.id(),
                            partition,
                            (UUID) row.get("owner_token"),
                            ((java.sql.Timestamp) row.get("owned_until")).toInstant());
            var readClaim =
                    readLifecycle
                            .acquire(readLease, List.of((UUID) slot.get("consumable_id")))
                            .claims()
                            .getFirst();
            assertEquals(
                    ConsumptionEngine.Status.COMMITTED, readLifecycle.execute(readClaim).status());
            assertEquals(
                    1,
                    f.read.queryForObject(
                            "select count(*) from fixture_read_effect", Integer.class));
            assertEquals(
                    0,
                    f.read.queryForObject("select count(*) from consumption_slot", Integer.class));

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            V3ConsumptionDiscoveryComposition.requests(
                                    f.guard(), command, f.w.source(), read, SETTINGS, COMBINED));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            V3ConsumptionDiscoveryComposition.events(
                                    f.guard(),
                                    event,
                                    f.r.source(),
                                    read,
                                    f.r.source(),
                                    write,
                                    SETTINGS,
                                    COMBINED));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            V3ConsumptionDiscoveryComposition.requests(
                                    f.guard(),
                                    definition(ConsumableFamily.IDENTITY_EVENT),
                                    f.w.source(),
                                    write,
                                    SETTINGS,
                                    COMBINED));
            trace.add(
                    "shared owner identity WRITE/READ asserted; actual Command D->I3 acquire->I2"
                            + " terminal, actual POT publication->READ K/slots");
        } finally {
            emf.destroy();
        }
    }

    @Test
    void explicitPrivilegesDoNotActivatePilot() throws Exception {
        var write = jdbcOwner(f.w.source());
        var read = jdbcOwner(f.r.source());
        var beforeW = acl(f.write);
        var beforeR = acl(f.read);
        var d = definition(ConsumableFamily.COMMAND);
        assertThrows(IllegalStateException.class, () -> f.guard().validate());
        assertThrows(IllegalStateException.class, () -> f.guard().validateLifecyclePrivileges());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        V3ConsumptionDiscoveryComposition.requests(
                                f.guard(),
                                d,
                                f.w.source(),
                                write,
                                SETTINGS,
                                V3BootstrapGuard.Privileges.LIFECYCLE_ONLY));
        V3ConsumptionDiscoveryComposition.requests(
                f.guard(), d, f.w.source(), write, SETTINGS, COMBINED);
        assertEquals(beforeW, acl(f.write));
        assertEquals(beforeR, acl(f.read));
        for (var target : List.of(f.w, f.r)) {
            var admin = target == f.w ? f.write : f.read;
            var runtime = new JdbcTemplate(target.source());
            assertEquals(
                    target.runtimeRole(),
                    runtime.queryForObject("select session_user", String.class));
            assertEquals(
                    target.runtimeRole(),
                    runtime.queryForObject("select current_user", String.class));
            for (String grant :
                    List.of(
                            "grant insert(state) on consumption_slot to " + target.runtimeRole(),
                            "grant update(partition_id) on consumption_slot to public")) {
                admin.execute(grant);
                assertThrows(
                        IllegalStateException.class,
                        () -> f.guard().validateLifecyclePrivileges(COMBINED));
                admin.execute(
                        "revoke insert(state) on consumption_slot from "
                                + target.runtimeRole()
                                + ";revoke update(partition_id) on consumption_slot from public");
                f.guard().validateLifecyclePrivileges(COMBINED);
            }
            if (target == f.w)
                for (String grant :
                        List.of(
                                "grant insert on v3_command to " + target.runtimeRole(),
                                "grant update(payload) on v3_command to " + target.runtimeRole(),
                                "grant delete on v3_event_position to " + target.runtimeRole(),
                                "grant update(status) on v3_command to public")) {
                    admin.execute(grant);
                    assertThrows(
                            IllegalStateException.class,
                            () -> f.guard().validateLifecyclePrivileges(COMBINED));
                    admin.execute(grant.replace("grant ", "revoke ").replace(" to ", " from "));
                    f.guard().validateLifecyclePrivileges(COMBINED);
                }
            admin.execute(
                    "revoke insert(partition_hash) on consumption_slot from "
                            + target.runtimeRole());
            assertThrows(
                    IllegalStateException.class,
                    () -> f.guard().validateLifecyclePrivileges(COMBINED));
            admin.execute(
                    "grant insert(partition_hash) on consumption_slot to " + target.runtimeRole());
            String member = "member_" + UUID.randomUUID().toString().replace("-", "");
            admin.execute("create role " + member);
            for (boolean inherit : List.of(true, false)) {
                admin.execute(
                        "alter role "
                                + target.runtimeRole()
                                + (inherit ? " inherit" : " noinherit")
                                + ";grant "
                                + member
                                + " to "
                                + target.runtimeRole());
                assertThrows(
                        IllegalStateException.class,
                        () -> f.guard().validateLifecyclePrivileges(COMBINED));
                admin.execute("revoke " + member + " from " + target.runtimeRole());
            }
        }
        f.guard().validateLifecyclePrivileges(COMBINED);
        assertEquals(beforeW, acl(f.write));
        assertEquals(beforeR, acl(f.read));
        for (var type :
                List.of(
                        V3ConsumptionDiscoveryComposition.class,
                        V3ConsumptionLifecycleComposition.class))
            assertEquals(0, type.getAnnotations().length);
        assertEquals(
                0, f.write.queryForObject("select count(*) from consumption_slot", Integer.class));
        assertEquals(
                0,
                f.read.queryForObject("select count(*) from discovery_checkpoint", Integer.class));
        trace.add(
                "I1/I3 strict refusal, I4 explicit exact union only; unchanged ACL and runtime"
                        + " identities, no SQL mutation by construction");
    }
}
