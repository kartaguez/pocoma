package com.kartaguez.pocoma.runtime.task.v3;

import static com.kartaguez.pocoma.runtime.task.v3.ReadRuntimeI8Fixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.PocomaTaskConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.WorkerCapacity;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.context.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
class ReadRuntimeAssemblyPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withCommand("postgres", "-c", "log_line_prefix=%m [%p] xid=%x state=%e ");

    @BeforeAll
    static void initializeServer() throws Exception {
        initialize(PG);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class Unexpected {
        @Bean
        Object extraBorrower() {
            throw new AssertionError("preflight must reject before construction");
        }
    }

    static void refused(ReadRuntimeI8Fixture f, Map<String, Object> changed, Class<?>... extra) {
        String before = f.coordination();
        var properties = new LinkedHashMap<>(f.properties);
        properties.putAll(changed);
        var app = new SpringApplication(PocomaTaskConsumptionWorkerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(properties);
        app.addPrimarySources(List.of(extra));
        assertThrows(
                RuntimeException.class,
                () -> {
                    try (var context = app.run("--spring.profiles.active=v3-read-worker")) {
                        fail("invalid root started");
                    }
                },
                "preflight " + changed);
        assertEquals(before, f.coordination(), "refusal before coordination mutations: " + changed);
    }

    @Test
    void rootsAndPrivilegesAreClosed() throws Exception {
        try (var f = new ReadRuntimeI8Fixture(PG)) {
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
            refused(f, Map.of(), Unexpected.class);
            refused(
                    f,
                    Map.of(),
                    com.kartaguez.pocoma.runtime.task.consumption
                            .CanonicalProjectionTaskRuntimeConfiguration.class);
            refused(f, Map.of("pocoma.v3.worker.read-business.maximum-pool-size", "5"));
            f.read.execute("revoke insert on read_subobject_version from " + f.readRole);
            refused(f, Map.of());
            f.read.execute("grant insert on read_subobject_version to " + f.readRole);
            f.read.execute("create table i8_unallocated(value text)");
            f.read.execute("grant select(value) on i8_unallocated to " + f.readRole);
            refused(f, Map.of());
            f.read.execute("revoke select(value) on i8_unallocated from " + f.readRole);
            refused(f, Map.of("pocoma.v3.worker.command-enabled", "true"));
            refused(f, Map.of("pocoma.v3.worker.technical-probes", "true"));
            refused(f, Map.of("pocoma.v3.pilot.read.database", "wrong_db"));
            f.read.execute("grant update on read_pot_version to " + f.readRole);
            refused(f, Map.of());
            f.read.execute("revoke update on read_pot_version from " + f.readRole);
            f.read.execute("grant select on read_current_binding to public");
            refused(f, Map.of());
            f.read.execute("revoke select on read_current_binding from public");
            var ctx = f.start();
            assertEquals(4, ctx.getBeansOfType(HikariDataSource.class).size(), "four pools");
            assertEquals(1, ctx.getBeansOfType(WorkerCapacity.class).size(), "one capacity");
            assertEquals(1, ctx.getBeansOfType(V3ConsumptionWorker.class).size(), "one Worker");
            assertEquals(
                    new HashSet<>(V3ConsumptionWorkerSettings.READ_NAMES),
                    f.services(ctx).activeParticipants());
            for (var name : ctx.getBeanDefinitionNames()) {
                var type = ctx.getType(name, false);
                if (type == null) continue;
                assertFalse(
                        type.getName().contains("$Legacy")
                                || type.getName()
                                        .endsWith("CanonicalProjectionTaskRuntimeConfiguration")
                                || type.getName().contains("V3TechnicalReadParticipant"),
                        "allocated type=" + type.getName());
            }
            f.physical("T02-active");
            f.drain(ctx);
            String before = f.coordination();
            f.select(List.of(), false);
            var idle = f.start();
            assertTrue(
                    idle.getBeansOfType(V3ConsumptionWorker.class).isEmpty(),
                    "empty selection has no Worker");
            assertTrue(
                    idle.getBeansOfType(WorkerCapacity.class).isEmpty(),
                    "empty selection has no capacity");
            assertTrue(
                    f.services(idle).workerState().isEmpty(), "activation separate from readiness");
            assertEquals(before, f.coordination(), "disable preserves durable state");
            idle.close();
            f.contexts.remove(idle);
            f.evidence("T02");
        }
    }

    static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void replace(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<V3ConsumptionWorker.Binding> bindings(V3ConsumptionWorker worker) {
        return (List<V3ConsumptionWorker.Binding>) field(worker, "bindings");
    }

    static final class Barrier {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean once = new AtomicBoolean();
        volatile UUID event;

        void hold(UUID id) {
            if (!once.compareAndSet(false, true)) return;
            event = id;
            entered.countDown();
            try {
                assertTrue(
                        release.await(20, TimeUnit.SECONDS),
                        "finalization barrier release for " + id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    static void intercept(V3ConsumptionWorker.Binding binding, AtomicReference<Barrier> barrier) {
        var original = (ConsumptionParticipant) field(binding.engine(), "participant");
        replace(
                binding.engine(),
                "participant",
                new ConsumptionParticipant() {
                    public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                        return original.prepare(id, reads)
                                .map(
                                        prepared ->
                                                new Prepared() {
                                                    public void finalizeIn(
                                                            SlotCompletion completion) {
                                                        var gate = barrier.get();
                                                        if (gate != null) gate.hold(id);
                                                        prepared.finalizeIn(completion);
                                                    }

                                                    public void requireCompletionReady() {
                                                        prepared.requireCompletionReady();
                                                    }
                                                });
                    }

                    public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                        return original.prepareRecovery(id, reads);
                    }
                });
    }

    static boolean projected(
            ReadRuntimeI8Fixture f,
            String name,
            UUID pot,
            long version,
            UUID command,
            UUID identity,
            String subject) {
        return switch (name) {
            case "MaterializePotVersion" ->
                    f.read.queryForObject(
                                    "select count(*) from read_pot_version where pot_id=? and"
                                        + " pot_version=?",
                                    Integer.class,
                                    pot,
                                    version)
                            == 1;
            case "MaterializePotBalance" ->
                    f.read.queryForObject(
                                    "select count(*) from read_pot_balance where pot_id=? and"
                                        + " pot_version=?",
                                    Integer.class,
                                    pot,
                                    version)
                            == 1;
            case "MaterializePotVisibility" ->
                    f.read.queryForObject(
                                    "select count(*) from read_pot_visibility_state where pot_id=?"
                                        + " and materialized_version=?",
                                    Integer.class,
                                    pot,
                                    version)
                            == 1;
            case "MaterializeCurrentBinding" ->
                    f.read.queryForObject(
                                    "select count(*) from read_current_binding where subject=?",
                                    Integer.class,
                                    subject)
                            == 1;
            case "MaterializeCommandResult" ->
                    f.read.queryForObject(
                                    "select count(*) from read_command_result where request_id=?",
                                    Integer.class,
                                    command)
                            == 1;
            case "MaterializeIdentityRequestResult" ->
                    f.read.queryForObject(
                                    "select count(*) from read_identity_request_result where"
                                        + " request_id=?",
                                    Integer.class,
                                    identity)
                            == 1;
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    void sixParticipantsProgressThroughWorkers() throws Exception {
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
                            new PotCommand.CreatePot(new Label("V1")));
            f.produce(create);
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
            var ctx = f.start();
            for (String name : V3ConsumptionWorkerSettings.READ_NAMES)
                await(
                        () ->
                                projected(
                                        f,
                                        name,
                                        pot.value(),
                                        1,
                                        create.requestId(),
                                        registration.requestId(),
                                        "owner"));
            f.witnesses();
            var gates = new HashMap<String, AtomicReference<Barrier>>();
            for (var binding : bindings(f.worker(ctx))) {
                String name =
                        V3ConsumptionWorkerSettings.READ_NAMES.stream()
                                .filter(
                                        n ->
                                                V3ConsumptionWorkerSettings.definition(n)
                                                        .equals(binding.definition()))
                                .findFirst()
                                .orElseThrow();
                var gate = new AtomicReference<Barrier>();
                gates.put(name, gate);
                intercept(binding, gate);
            }
            long version = 1;
            for (String held : V3ConsumptionWorkerSettings.READ_NAMES) {
                var gate = new Barrier();
                gates.get(held).set(gate);
                version++;
                try {
                    var command =
                            f.admitCommand(
                                    "owner",
                                    f.ownToken("owner").value(),
                                    pot,
                                    version - 1,
                                    new PotCommand.UpdatePotDetails(new Label("V" + version)));
                    f.produce(command);
                    String subject = "independent-" + version;
                    var identity = f.admit("REGISTRATION", subject, subject, null);
                    f.produce(identity);
                    assertTrue(
                            gate.entered.await(15, TimeUnit.SECONDS),
                            held + " finalization entered");
                    final long expectedVersion = version;
                    for (String progressing : V3ConsumptionWorkerSettings.READ_NAMES)
                        if (!progressing.equals(held)) {
                            await(
                                    () ->
                                            projected(
                                                    f,
                                                    progressing,
                                                    pot.value(),
                                                    expectedVersion,
                                                    command.requestId(),
                                                    identity.requestId(),
                                                    subject));
                            f.trace.add(
                                    "T03 held="
                                            + held
                                            + " progressed="
                                            + progressing
                                            + " V="
                                            + version);
                        }
                    assertFalse(
                            projected(
                                    f,
                                    held,
                                    pot.value(),
                                    version,
                                    command.requestId(),
                                    identity.requestId(),
                                    subject),
                            held + " effect absent before commit");
                    UUID consumption = V3ConsumptionWorkerSettings.definition(held).id().value();
                    assertEquals(
                            1,
                            f.read.queryForObject(
                                    "select count(*) from consumption_slot where consumption_id=?"
                                        + " and consumable_id=?",
                                    Integer.class,
                                    consumption,
                                    gate.event),
                            held + " slot visible externally before finalization");
                    gate.release.countDown();
                    await(
                            () ->
                                    projected(
                                            f,
                                            held,
                                            pot.value(),
                                            expectedVersion,
                                            command.requestId(),
                                            identity.requestId(),
                                            subject));
                    await(
                            () ->
                                    f.read.queryForObject(
                                                    "select count(*) from consumption_slot where"
                                                        + " consumption_id=? and consumable_id=?",
                                                    Integer.class,
                                                    consumption,
                                                    gate.event)
                                            == 0);
                    var witness =
                            f.read.queryForMap(
                                    "select pid,xid from i8_witness where t='consumption_slot' and"
                                        + " consumption_id=? and consumable_id=?",
                                    consumption,
                                    gate.event);
                    String table =
                            switch (held) {
                                case "MaterializePotVersion" -> "read_pot_version";
                                case "MaterializePotBalance" -> "read_pot_balance";
                                case "MaterializePotVisibility" -> "read_pot_visibility_state";
                                case "MaterializeCurrentBinding" -> "read_current_binding";
                                case "MaterializeCommandResult" -> "read_command_result";
                                default -> "read_identity_request_result";
                            };
                    assertTrue(
                            f.read.queryForObject(
                                            "select count(*) from i8_witness where t=? and pid=?"
                                                + " and xid=?",
                                            Integer.class,
                                            table,
                                            witness.get("pid"),
                                            witness.get("xid"))
                                    > 0,
                            held + " effects/slot same PID/XID");
                    if (held.equals("MaterializePotVersion"))
                        for (String atomic : List.of("read_subobject_version", "read_current_pot"))
                            assertTrue(
                                    f.read.queryForObject(
                                                    "select count(*) from i8_witness where t=? and"
                                                        + " pid=? and xid=?",
                                                    Integer.class,
                                                    atomic,
                                                    witness.get("pid"),
                                                    witness.get("xid"))
                                            > 0,
                                    "atomic " + atomic);
                    f.trace.add("T03 held=" + held + " final=" + witness);
                } finally {
                    gate.release.countDown();
                    gates.get(held).set(null);
                }
            }
            var deletion =
                    f.admitCommand(
                            "owner",
                            f.ownToken("owner").value(),
                            pot,
                            version,
                            new PotCommand.DeletePot());
            f.produce(deletion);
            long terminal = version + 1;
            await(
                    () ->
                            f.read.queryForObject(
                                            "select count(*) from read_pot_version where pot_id=?"
                                                + " and pot_version=? and"
                                                + " (payload->>'deleted')::boolean",
                                            Integer.class,
                                            pot.value(),
                                            terminal)
                                    == 1);
            await(
                    () ->
                            f.read.queryForObject(
                                            "select count(*) from read_user_pot where pot_id=? and"
                                                + " visible",
                                            Integer.class,
                                            pot.value())
                                    == 0);
            await(
                    () ->
                            f.read.queryForObject(
                                            "select count(*) from consumption_slot", Integer.class)
                                    == 0);
            assertEquals(
                    0,
                    f.read.queryForObject(
                            "select count(*) from read_pot_balance where pot_id=? and"
                                + " pot_version=?",
                            Integer.class,
                            pot.value(),
                            terminal),
                    "D completes without Balance(D)");
            assertEquals(
                    version,
                    f.read.queryForObject(
                            "select count(*) from read_pot_balance where pot_id=?",
                            Long.class,
                            pot.value()),
                    "all active historical balances retained");
            var userEvents =
                    f.write.queryForList(
                            "select event_id from v3_identity_event where"
                                + " subject_type='POCOMA_USER'",
                            UUID.class);
            assertFalse(userEvents.isEmpty(), "interleaved real User events");
            for (UUID event : userEvents)
                assertEquals(
                        0,
                        f.read.queryForObject(
                                "select count(*) from i8_witness where consumable_id=?",
                                Integer.class,
                                event),
                        "User has no enrolled/completed READ slot: " + event);
            assertEquals(
                    3,
                    f.read.queryForObject(
                            "select count(distinct consumption_id) from discovery_checkpoint where"
                                + " consumption_id in"
                                + " ('3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a','190a93c6-b8af-5ac7-8001-72e2ec53c2df','b4d04cce-b5da-5d1b-9698-5ff9af9a46fa')",
                            Integer.class),
                    "three independent Pot checkpoints");
            f.physical("T03-six-branches");
            f.trace.add(
                    "T03 witnesses=" + f.read.queryForList("select * from i8_witness order by at"));
            f.drain(ctx);
            f.evidence("T03");
        }
    }
}
