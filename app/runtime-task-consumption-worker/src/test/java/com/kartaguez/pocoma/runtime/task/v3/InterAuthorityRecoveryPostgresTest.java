package com.kartaguez.pocoma.runtime.task.v3;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.ClaimedSlot;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoveryAdmissionStore;
import com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;

import static com.kartaguez.pocoma.runtime.task.v3.SubobjectPostgresFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.JdbcSubobjectVersionStore;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
class InterAuthorityRecoveryPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(IMAGE).withDatabaseName("i2_read");

    @BeforeAll
    static void init() {
        initialize(PG);
    }

    @Test
    void r01AndR03PresentPreexistingAndAbsentExactOrDivergentEvidence() throws Exception {
        for (boolean higher : List.of(false, true))
            for (boolean present : List.of(false, true))
                for (int evidence = 0; evidence < 4; evidence++)
                    try (var f =
                            new InterAuthorityPostgresFixture(
                                    PG, "R01-R03-" + higher + "-" + present + "-" + evidence)) {
                        var p = f.publish();
                        if (higher) {
                            var later = f.publish();
                            f.read.oracle.update(
                                    "update discovery_checkpoint set position=? where"
                                        + " consumption_id=? and partition_id=?",
                                    later.position(),
                                    f.read.definition.id().value(),
                                    RoutingV1.partition(
                                            RoutingV1.hash(ConsumableFamily.POT_EVENT, p.event()),
                                            4));
                        }
                        long checkpoint =
                                f.read.oracle.queryForObject(
                                        "select position from discovery_checkpoint where"
                                            + " consumption_id=? and partition_id=?",
                                        Long.class,
                                        f.read.definition.id().value(),
                                        RoutingV1.partition(
                                                RoutingV1.hash(
                                                        ConsumableFamily.POT_EVENT, p.event()),
                                                4));
                        assertEquals(higher ? p.position() + 1 : p.position(), checkpoint);
                        var sourceDetails =
                                f.oracle.queryForMap(
                                        "select label,creator_id from v3_pot_details where"
                                            + " pot_id=?",
                                        p.pot());
                        assertEquals("I2 source", sourceDetails.get("label"));
                        assertEquals(new UUID(0, 71), sourceDetails.get("creator_id"));
                        var second =
                                new SubobjectVersion(
                                        new SubobjectKey(
                                                p.pot(), SubobjectKind.POT_DETAILS, p.pot(), 1),
                                        new com.kartaguez.pocoma.domain.projection.v3
                                                .SubobjectContent.PotDetailsContent(
                                                new com.kartaguez.pocoma.domain.pot.v3.PotDetails(
                                                        new com.kartaguez.pocoma.domain.pot.value.id
                                                                .PotId(p.pot()),
                                                        new com.kartaguez.pocoma.domain.pot.value
                                                                .Label("I2 source"),
                                                        new com.kartaguez.pocoma.domain.pot.value
                                                                .UserId(new UUID(0, 71)))));
                        f.read.expected.put(p.event(), List.of(p.values().getFirst(), second));
                        if (evidence == 1) f.read.insert(p.values()); // conforming partial set
                        if (evidence == 2)
                            f.read.insert(
                                    f.read.expected.get(
                                            p.event())); // independent, before any attempt
                        if (evidence == 3) f.read.insert(List.of(header(p.pot(), 1, true)));
                        if (!present)
                            f.read.oracle.update(
                                    "delete from consumption_slot where consumable_id=?",
                                    p.event());
                        var e = f.engine();
                        var result = e.observe(f.claim(p));
                        var expected =
                                evidence == 3
                                        ? RecoveryObservation.INCONSISTENT
                                        : present
                                                ? RecoveryObservation.NOT_COMPLETED
                                                : evidence == 2
                                                        ? RecoveryObservation.COMPLETED
                                                        : RecoveryObservation.INCONSISTENT;
                        assertEquals(expected, result);
                        assertEquals(evidence == 2 ? 2 : evidence == 0 ? 0 : 1, f.read.count());
                        assertEquals(present ? 1 : 0, f.read.slotCount(p.event()));
                        assertFalse(e.hasUnresolvedRecovery());
                        f.read.trace.add(
                                "R01/R03 higher="
                                        + higher
                                        + " checkpoint="
                                        + checkpoint
                                        + " position="
                                        + p.position()
                                        + " preexisting="
                                        + evidence
                                        + " slotPresent="
                                        + present
                                        + " result="
                                        + result);
                    }
    }

    @Test
    void r02CoverageIsExactConsumptionPartitionAndSourcePosition() throws Exception {
        for (boolean present : List.of(false, true))
            for (int wrong = 0; wrong < 5; wrong++)
                try (var f =
                        new InterAuthorityPostgresFixture(PG, "R02-" + present + "-" + wrong)) {
                    f.publish();
                    var p = f.publish();
                    f.read.insert(p.values());
                    int partition =
                            RoutingV1.partition(
                                    RoutingV1.hash(ConsumableFamily.POT_EVENT, p.event()), 4);
                    f.read.oracle.update(
                            "delete from discovery_checkpoint where consumption_id=? and"
                                    + " partition_id=?",
                            f.read.definition.id().value(),
                            partition);
                    if (wrong == 1)
                        f.read.oracle.update(
                                "insert into discovery_checkpoint values (?,?,0)",
                                f.read.definition.id().value(),
                                partition);
                    if (wrong == 2)
                        f.read.oracle.update(
                                "insert into discovery_checkpoint values (?,?,?)",
                                f.read.definition.id().value(),
                                partition,
                                p.position() - 1);
                    if (wrong == 3)
                        f.read.oracle.update(
                                "insert into discovery_checkpoint values (?,?,999) on conflict do"
                                        + " nothing",
                                f.read.definition.id().value(),
                                (partition + 1) % 4);
                    if (wrong == 4) {
                        UUID other =
                                f.read.oracle.queryForObject(
                                        "select consumption_id from consumption_routing_contract"
                                                + " where family='POT_EVENT' and consumption_id<>?"
                                                + " limit 1",
                                        UUID.class,
                                        f.read.definition.id().value());
                        f.read.oracle.update(
                                "insert into discovery_checkpoint values (?,?,999)",
                                other,
                                partition);
                    }
                    if (!present)
                        f.read.oracle.update(
                                "delete from consumption_slot where consumable_id=?", p.event());
                    assertEquals(RecoveryObservation.INCONSISTENT, f.engine().observe(f.claim(p)));
                }
    }

    @Test
    void r04ExactSqlLookupDistinguishesAbsenceCorruptionAndTechnicalFailure() throws Exception {
        for (String bad :
                List.of(
                        "absent",
                        "wrong-family",
                        "position-only",
                        "envelope-only",
                        "counter-behind",
                        "counter-missing",
                        "subject",
                        "permission"))
            try (var f = new InterAuthorityPostgresFixture(PG, "R04-" + bad)) {
                var p = f.publish();
                UUID lookup = p.event();
                switch (bad) {
                    case "absent" -> lookup = UUID.randomUUID();
                    case "wrong-family" -> lookup = p.completion();
                    case "position-only" ->
                            f.oracle.execute(
                                    "alter table v3_pot_event disable trigger all;delete from"
                                            + " v3_pot_event where event_id='"
                                            + p.event()
                                            + "';alter table v3_pot_event enable trigger all");
                    case "envelope-only" ->
                            f.oracle.execute(
                                    "alter table v3_event_position disable trigger all;delete from"
                                            + " v3_event_position where event_id='"
                                            + p.event()
                                            + "';alter table v3_event_position enable trigger all");
                    case "counter-behind" ->
                            f.oracle.update(
                                    "update v3_event_counter set position=0 where family='POT'");
                    case "counter-missing" ->
                            f.oracle.execute(
                                    "alter table v3_event_counter disable trigger all;delete from"
                                            + " v3_event_counter where family='POT';alter table"
                                            + " v3_event_counter enable trigger all");
                    case "subject" ->
                            f.oracle.execute(
                                    "alter table v3_pot_event disable trigger all;alter table"
                                        + " v3_pot_event drop constraint"
                                        + " v3_pot_event_subject_type_check;update v3_pot_event set"
                                        + " subject_type='WRONG';alter table v3_pot_event enable"
                                        + " trigger all");
                    case "permission" ->
                            f.oracle.execute("revoke select on v3_event_position from " + f.role);
                }
                UUID id = lookup;
                if (bad.equals("absent"))
                    assertTrue(f.sourceOwner.read(c -> f.references.find(id, c)).isEmpty());
                else {
                    var failure =
                            assertThrows(
                                    AttemptTransactions.Failure.class,
                                    () -> f.sourceOwner.read(c -> f.references.find(id, c)));
                    assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, failure.resolution());
                    if (bad.equals("permission"))
                        assertFalse(
                                failure.getCause()
                                        instanceof NonBurnableReferences.InvalidReference);
                    else
                        assertInstanceOf(
                                NonBurnableReferences.InvalidReference.class, failure.getCause());
                    f.read.trace.add(
                            "R04 lookup="
                                    + bad
                                    + " original="
                                    + failure.getCause()
                                    + " resolution="
                                    + failure.resolution());
                }
                if (!bad.equals("absent") && !bad.equals("wrong-family"))
                    assertEquals(
                            bad.equals("permission")
                                    ? RecoveryObservation.UNAVAILABLE
                                    : RecoveryObservation.INCONSISTENT,
                            f.engine().observe(f.claim(p)));
                if (bad.equals("wrong-family")) {
                    // Counter-lookup filtering family silently loses a positive contradiction.
                    assertTrue(
                            f.sql.queryForList(
                                            "select event_id from v3_event_position where"
                                                    + " event_id=? and family='POT'",
                                            UUID.class,
                                            id)
                                    .isEmpty());
                }
                if (bad.equals("position-only"))
                    assertTrue(
                            f.sql.queryForList(
                                            "select p.event_id from v3_event_position p join"
                                                    + " v3_pot_event e using(event_id) where"
                                                    + " p.event_id=?",
                                            UUID.class,
                                            id)
                                    .isEmpty());
            }
    }

    @Test
    void r04AllFourDeliveredFamiliesUseTheirRealPublicationMapping() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "R04-families")) {
            var p = f.publish();
            var key =
                    new com.kartaguez.pocoma.domain.identity.ExternalIdentity(
                            "i2", "registration");
            var id = UUID.randomUUID();
            var request =
                    new com.kartaguez.pocoma.contract.writeadmission.IdentityRequest(
                            id,
                            key,
                            new com.kartaguez.pocoma.contract.writeadmission.IdentityRequest
                                    .Registration(),
                            null,
                            java.time.Instant.EPOCH,
                            new com.kartaguez.pocoma.contract.writeadmission.IdentityRequest
                                    .AdmissionContext(
                                    "I2 fixture",
                                    "1",
                                    key,
                                    key,
                                    id.toString(),
                                    id.toString(),
                                    java.time.Instant.EPOCH,
                                    Map.of("scope", "internal"),
                                    com.kartaguez.pocoma.contract.writeadmission.IdentityRequest
                                            .Origin.CONTROLLED_INTERNAL));
            var store =
                    new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity
                            .JdbcIdentityRequestStore(f.primary, f.sourceOwner);
            f.oracle.update(
                    "insert into"
                        + " v3_identity_request(request_id,requester_issuer,requester_subject,operation,target_issuer,target_subject,admission_context,created_at)"
                        + " values"
                        + " (?,'i2','registration','REGISTRATION','i2','registration',?::jsonb,?)",
                    id,
                    com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity
                            .IdentityRequestJson.encode(request.admission()),
                    java.sql.Timestamp.from(request.createdAt()));
            var definition =
                    new ConsumptionDefinition(
                            new ConsumptionId(
                                    UUID.fromString("2999e75c-272d-5afe-a966-c9364ef021fe")),
                            ConsumableFamily.IDENTITY_REQUEST,
                            ConsumptionDefinition.Authority.WRITE);
            f.oracle.update(
                    "insert into"
                        + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                        + " select ?,?,h,((h::bigint%4+4)%4)::int from(select"
                        + " consumption_hash_v1('IDENTITY_REQUEST',?) h)x",
                    definition.id().value(), id, id);
            var participant =
                    new com.kartaguez.pocoma.engine.write.identity.v3.ExecuteIdentityRequest(
                            store,
                            new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity
                                    .JdbcIdentityRequestObservation(f.primary),
                            new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity
                                    .JdbcIdentityAuthority(f.primary, f.sourceOwner),
                            () ->
                                    new com.kartaguez.pocoma.domain.identity.v3.BindingToken(
                                            "I2-generated-fixture-token"),
                            UUID::randomUUID);
            var e =
                    new ConsumptionEngine(
                            definition,
                            f.sourceOwner,
                            new JdbcConsumptionSlotStore(f.primary),
                            participant,
                            new JdbcPartitionOwnershipStore(f.primary),
                            f.read.settings,
                            new WorkerCapacity(1, Map.of(definition.id(), 1)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            int partition = RoutingV1.partition(RoutingV1.hash(definition.family(), id), 4);
            var lease = f.primaryLease(e, definition, partition);
            var identityAttempt = e.execute(e.acquire(lease, List.of(id)).claims().getFirst());
            f.read.trace.add(
                    "Identity publication attempt="
                            + identityAttempt
                            + " cause="
                            + identityAttempt.cause());
            assertEquals(
                    ConsumptionProtocol.Status.COMMITTED,
                    identityAttempt.status(),
                    () -> String.valueOf(identityAttempt.cause()));
            var mappings =
                    Map.of(
                            ConsumableFamily.POT_EVENT,
                            p.event(),
                            ConsumableFamily.COMMAND_COMPLETION_EVENT,
                            p.completion(),
                            ConsumableFamily.IDENTITY_EVENT,
                            f.oracle.queryForObject(
                                    "select event_id from v3_identity_event where"
                                            + " subject_type='EXTERNAL_USER_IDENTITY'",
                                    UUID.class),
                            ConsumableFamily.IDENTITY_COMPLETION_EVENT,
                            f.oracle.queryForObject(
                                    "select event_id from v3_identity_completion_event where"
                                            + " subject_id=?",
                                    UUID.class,
                                    id));
            for (var entry : mappings.entrySet()) {
                var references =
                        new JdbcEventDiscoveryReferences(
                                f.primary, entry.getKey(), discoverySettings());
                var found =
                        f.sourceOwner.read(c -> references.find(entry.getValue(), c)).orElseThrow();
                assertEquals(entry.getValue(), found.eventId());
                assertEquals(
                        f.oracle.queryForObject(
                                "select position from v3_event_position where event_id=?",
                                Long.class,
                                entry.getValue()),
                        found.position());
                assertTrue(
                        f.sourceOwner.read(c -> references.find(UUID.randomUUID(), c)).isEmpty());
                f.read.trace.add("R04 real family=" + entry.getKey() + " reference=" + found);
            }
            var identityDefinition =
                    new ConsumptionDefinition(
                            new ConsumptionId(
                                    f.read.oracle.queryForObject(
                                            "select consumption_id from"
                                                    + " consumption_routing_contract where"
                                                    + " family='IDENTITY_EVENT' limit 1",
                                            UUID.class)),
                            ConsumableFamily.IDENTITY_EVENT,
                            ConsumptionDefinition.Authority.READ);
            var identityReferences =
                    new JdbcEventDiscoveryReferences(
                            f.primary, identityDefinition.family(), discoverySettings());
            var userEvent =
                    f.oracle.queryForObject(
                            "select event_id from v3_identity_event where"
                                    + " subject_type='POCOMA_USER'",
                            UUID.class);
            var ineligible =
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
                            throw new AssertionError("not activated");
                        }

                        public Recovery prepareRecovery(UUID event, PreparationReads reads) {
                            throw new AssertionError("ineligible source must precede participant");
                        }
                    };
            var identityEngine =
                    new ConsumptionEngine(
                            identityDefinition,
                            f.read.tx,
                            f.read.slots,
                            ineligible,
                            f.sourceOwner,
                            Optional.of(identityReferences),
                            ref ->
                                    com.kartaguez.pocoma.runtime.consumption.v3
                                            .V3ConsumptionDiscoveryComposition.relevant(
                                            identityDefinition, ref),
                            f.admissions,
                            f.read.tx,
                            f.read.slots,
                            f.read.ownership,
                            f.read.settings,
                            new WorkerCapacity(1, Map.of(identityDefinition.id(), 1)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                            null,
                            ConsumptionObservation.noop());
            assertEquals(
                    RecoveryObservation.INCONSISTENT,
                    identityEngine.observe(
                            new ClaimedSlot(
                                    identityDefinition.id(), userEvent, UUID.randomUUID())));
        }
    }

    @Test
    void r05OneSnapshotRejectsTheTwoSnapshotFalseCompletedCounterexample() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "R05")) {
            var p = f.publish();
            f.read.oracle.update("delete from consumption_slot where consumable_id=?", p.event());
            f.read.oracle.update(
                    "delete from discovery_checkpoint where consumption_id=?",
                    f.read.definition.id().value());
            f.read.script(
                    "grant-l2-discovery.sql",
                    Map.of(
                            "runtime_role",
                            f.read.runtimeRole,
                            "reactivation_role",
                            f.read.reactivationRole));
            var slotRead = new CountDownLatch(1);
            var published = new CountDownLatch(1);
            var snapshots = new CopyOnWriteArrayList<String>();
            var slots =
                    (ConsumptionSlotStore)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class[] {ConsumptionSlotStore.class},
                                    (proxy, method, args) -> {
                                        Object value;
                                        try {
                                            value = method.invoke(f.read.slots, args);
                                        } catch (
                                                java.lang.reflect.InvocationTargetException error) {
                                            throw error.getCause();
                                        }
                                        if (method.getName().equals("observe")) {
                                            assertEquals(
                                                    ConsumptionSlotStore.SlotObservation.ABSENT,
                                                    value);
                                            snapshots.add(
                                                    f.read.sql.queryForObject(
                                                            "select pg_current_snapshot()::text",
                                                            String.class));
                                            slotRead.countDown();
                                            await(published);
                                        }
                                        return value;
                                    });
            f.effectObservation =
                    c ->
                            snapshots.add(
                                    f.read.sql.queryForObject(
                                            "select pg_current_snapshot()::text", String.class));
            var e =
                    new ConsumptionEngine(
                            f.read.definition,
                            f.read.tx,
                            slots,
                            f.participant(),
                            f.sourceOwner,
                            Optional.of(f.references),
                            r -> true,
                            f.admissions,
                            f.read.tx,
                            f.read.slots,
                            f.read.ownership,
                            f.read.settings,
                            f.read.capacity,
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                            null,
                            ConsumptionObservation.noop());
            var observing = f.read.threads.submit(() -> e.observe(f.claim(p)));
            await(slotRead);
            try {
                var writer = f.engine();
                int partition =
                        RoutingV1.partition(
                                RoutingV1.hash(ConsumableFamily.POT_EVENT, p.event()), 4);
                var lease = writer.acquirePartition(partition).orElseThrow();
                var discovery =
                        new DiscoveryEngine(
                                f.read.definition,
                                f.read.tx,
                                f.read.ownership,
                                f.admissions,
                                discoverySettings(),
                                f.sourceOwner,
                                f.references,
                                r -> true);
                assertEquals(1, discovery.discoverEvents(lease).inserted());
                assertEquals(
                        ConsumptionProtocol.Status.COMMITTED,
                        writer.execute(
                                        writer.acquire(lease, List.of(p.event()))
                                                .claims()
                                                .getFirst())
                                .status());
            } finally {
                published.countDown();
            }
            assertEquals(RecoveryObservation.INCONSISTENT, get(observing));
            assertEquals(2, snapshots.size());
            assertEquals(snapshots.getFirst(), snapshots.getLast());
            var now =
                    f.read.tx.read(
                            c -> {
                                assertTrue(
                                        f.admissions.checkpoint(
                                                        f.read.definition.id(),
                                                        RoutingV1.partition(
                                                                RoutingV1.hash(
                                                                        ConsumableFamily.POT_EVENT,
                                                                        p.event()),
                                                                4),
                                                        c)
                                                >= p.position());
                                assertEquals(
                                        1,
                                        f.read.store
                                                .readAll(
                                                        p.values().stream()
                                                                .map(v -> v.key())
                                                                .toList(),
                                                        c)
                                                .size());
                                return RecoveryObservation.COMPLETED;
                            });
            // Deliberately split counter-reader: old ABSENT + fresh coverage/effects would lie.
            assertNotEquals(RecoveryObservation.INCONSISTENT, now);
            f.read.trace.add("R05 stable snapshots=" + snapshots + " unsafe mixed verdict=" + now);
        }
    }

    @Test
    void r06FinalLostAckHasIndependentBusinessProofAndSeparateCapacityResolution()
            throws Exception {
        for (var fault :
                List.of(
                        CommitAcknowledgementProxy.Fault.BEFORE_COMMIT,
                        CommitAcknowledgementProxy.Fault.AFTER_COMMIT))
            try (var f = new InterAuthorityPostgresFixture(PG, "R06-" + fault);
                    var proxy =
                            new CommitAcknowledgementProxy(PG.getHost(), PG.getMappedPort(5432))) {
                var p = f.publish();
                var ds =
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:"
                                        + proxy.localPort()
                                        + "/"
                                        + PG.getDatabaseName()
                                        + "?sslmode=disable&gssEncMode=disable&preferQueryMode=simple&socketTimeout=5&currentSchema="
                                        + f.read.schema,
                                f.read.runtimeRole,
                                "i1-fixture");
                var tx = owner(ds);
                var storage = new JdbcSubobjectVersionStore(ds, tx);
                var participant =
                        f.read.participant(
                                ds,
                                storage,
                                (point, id, c) -> {
                                    if (point == Point.AFTER_CLOSE) proxy.arm(fault);
                                },
                                false);
                var e = effectsEngine(f, ds, tx, participant);
                var h = f.read.acquire(e, p.event());
                var result = e.execute(h);
                boolean committed = fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT;
                assertEquals(AttemptTransactions.Resolution.UNKNOWN, result.resolution());
                assertEquals(
                        committed
                                ? RecoveryObservation.COMPLETED
                                : RecoveryObservation.NOT_COMPLETED,
                        result.observation());
                f.assertPhysical(p, committed);
                assertTrue(e.retainsCapacity(h));
                assertEquals(1, e.settlePending());
                assertFalse(e.retainsCapacity(h));
                assertEquals(AttemptTransactions.Resolution.UNKNOWN, result.resolution());
                assertEquals(1, proxy.interceptedCount());
                assertEquals(committed ? 1 : 0, proxy.committedReplyCount());
                f.read.trace.add("R06 " + fault + " " + result);
            }
    }

    @Test
    void r07UnknownRecoveryDiscardsCalculatedVerdictAndPermanentlyExcludesEngine()
            throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "R07");
                var proxy = new CommitAcknowledgementProxy(PG.getHost(), PG.getMappedPort(5432))) {
            var p = f.publish();
            f.read.insert(p.values());
            f.read.oracle.update("delete from consumption_slot where consumable_id=?", p.event());
            var ds =
                    new DriverManagerDataSource(
                            "jdbc:postgresql://127.0.0.1:"
                                    + proxy.localPort()
                                    + "/"
                                    + PG.getDatabaseName()
                                    + "?sslmode=disable&gssEncMode=disable&preferQueryMode=simple&socketTimeout=5&currentSchema="
                                    + f.read.schema,
                            f.read.runtimeRole,
                            "i1-fixture");
            var tx = owner(ds);
            var storage = new JdbcSubobjectVersionStore(ds, tx);
            var calls = new AtomicInteger();
            var participant =
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                            throw new AssertionError("observation only");
                        }

                        public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                            return c -> {
                                calls.incrementAndGet();
                                assertEquals(
                                        1,
                                        storage.readAll(
                                                        p.values().stream()
                                                                .map(v -> v.key())
                                                                .toList(),
                                                        c)
                                                .size());
                                proxy.arm(CommitAcknowledgementProxy.Fault.AFTER_COMMIT);
                                return EffectEvidence.SATISFIED;
                            };
                        }
                    };
            var e = effectsEngine(f, ds, tx, participant);
            assertEquals(RecoveryObservation.UNAVAILABLE, e.observe(f.claim(p)));
            assertTrue(e.hasUnresolvedRecovery());
            assertEquals(RecoveryObservation.UNAVAILABLE, e.observe(f.claim(p)));
            assertEquals(1, calls.get());
            assertEquals(0, e.settlePending());
            assertEquals(1, proxy.committedReplyCount());
            f.assertPhysical(p, true);
        }
    }

    @Test
    void c03AndC04RenewalsContinueAcrossLongScopesAndDrainWaitsForPhysicalExit() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "C03-C04")) {
            var p = f.publish();
            f.read.oracle.update(
                    "delete from discovery_checkpoint where consumption_id=?",
                    f.read.definition.id().value());
            f.read.script(
                    "grant-l2-discovery.sql",
                    Map.of(
                            "runtime_role",
                            f.read.runtimeRole,
                            "reactivation_role",
                            f.read.reactivationRole));
            var sourceEntered = new CountDownLatch(1);
            var sourceRelease = new CountDownLatch(1);
            var effectsEntered = new CountDownLatch(1);
            var effectsRelease = new CountDownLatch(1);
            var renewedSource = new CountDownLatch(1);
            var renewedEffects = new CountDownLatch(1);
            var deadline = new CountDownLatch(1);
            var stopped = new CountDownLatch(1);
            var stage = new AtomicInteger();
            var closed = new AtomicBoolean();
            var cap = new WorkerCapacity(1, Map.of(f.read.definition.id(), 1));
            var control = f.read.datasource(f.read.runtimeRole);
            var technical = owner(control);
            var participant =
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                            reads.source(
                                    c -> {
                                        assertTrue(f.references.find(id, c).isPresent());
                                        stage.set(1);
                                        sourceEntered.countDown();
                                        await(sourceRelease);
                                        return null;
                                    });
                            reads.effects(
                                    c -> {
                                        f.read.store.readAll(
                                                p.values().stream().map(v -> v.key()).toList(), c);
                                        stage.set(2);
                                        effectsEntered.countDown();
                                        await(effectsRelease);
                                        return null;
                                    });
                            return f.read.participant().prepare(id, reads);
                        }

                        public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                            return f.participant().prepareRecovery(id, reads);
                        }
                    };
            ConsumptionObservation observation =
                    (id, phase, outcome, amount, elapsed) -> {
                        if (phase == ConsumptionObservation.Phase.CLAIM_RENEW
                                && outcome == ConsumptionObservation.Outcome.CONFIRMED
                                && amount > 0) {
                            if (stage.get() == 1) renewedSource.countDown();
                            if (stage.get() == 2) renewedEffects.countDown();
                        }
                        if (phase == ConsumptionObservation.Phase.DRAIN
                                && outcome == ConsumptionObservation.Outcome.UNKNOWN)
                            deadline.countDown();
                    };
            var e =
                    new ConsumptionEngine(
                            f.read.definition,
                            f.read.tx,
                            f.read.slots,
                            participant,
                            f.sourceOwner,
                            Optional.of(f.references),
                            r -> true,
                            f.admissions,
                            technical,
                            new JdbcConsumptionSlotStore(control),
                            new JdbcPartitionOwnershipStore(control),
                            f.read.settings,
                            cap,
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                            new JdbcSlotKeyNavigation(control, f.read.settings),
                            observation);
            var discovery =
                    new DiscoveryEngine(
                            f.read.definition,
                            technical,
                            new JdbcPartitionOwnershipStore(control),
                            new JdbcDiscoveryAdmissionStore(control, discoverySettings()),
                            discoverySettings(),
                            f.sourceOwner,
                            f.references,
                            r -> true);
            var settings =
                    new com.kartaguez.pocoma.supra.polling.consumption.v3.V3ConsumptionWorker
                            .Settings(
                            32,
                            1,
                            java.time.Duration.ofMillis(10),
                            java.time.Duration.ofMillis(20),
                            java.time.Duration.ofMillis(20),
                            java.time.Duration.ofMillis(10),
                            java.time.Duration.ofMillis(50),
                            java.time.Duration.ofSeconds(2),
                            java.time.Duration.ofMillis(150));
            int partition =
                    RoutingV1.partition(RoutingV1.hash(ConsumableFamily.POT_EVENT, p.event()), 4);
            var worker =
                    new com.kartaguez.pocoma.supra.polling.consumption.v3.V3ConsumptionWorker(
                            List.of(
                                    new com.kartaguez.pocoma.supra.polling.consumption.v3
                                            .V3ConsumptionWorker.Binding(
                                            f.read.definition, com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionProtocols.forDefinition(f.read.definition, e, discovery), List.of(partition))),
                            cap,
                            Map.of(f.read.definition.id(), 1),
                            settings,
                            observation,
                            () -> {
                                closed.set(true);
                                stopped.countDown();
                            }, com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionSafety::new);
            worker.start();
            try {
                await(sourceEntered);
                assertEquals(1, cap.snapshot().worker());
                assertEquals(0, e.settlePending());
                await(renewedSource);
                var first =
                        f.read.oracle.queryForMap(
                                "select claim_token,claimed_until from consumption_slot where"
                                        + " consumable_id=?",
                                p.event());
                sourceRelease.countDown();
                await(effectsEntered);
                await(renewedEffects);
                var second =
                        f.read.oracle.queryForMap(
                                "select claim_token,claimed_until from consumption_slot where"
                                        + " consumable_id=?",
                                p.event());
                assertEquals(first.get("claim_token"), second.get("claim_token"));
                assertTrue(
                        ((java.sql.Timestamp) second.get("claimed_until"))
                                .after((java.sql.Timestamp) first.get("claimed_until")));
                assertEquals(1, cap.snapshot().worker());
                assertFalse(closed.get());
                worker.requestDrain();
                await(deadline);
                assertEquals(
                        com.kartaguez.pocoma.contract.consumption.invocation.WorkerView.State.DRAINING,
                        worker.snapshot().state());
                assertTrue(worker.snapshot().deadlineExceeded());
                assertFalse(closed.get());
                assertEquals(0, e.settlePending());
                f.read.trace.add(
                        "C03/C04 held source/effects, real claim renewal="
                                + first
                                + " -> "
                                + second
                                + " drain="
                                + worker.snapshot());
            } finally {
                sourceRelease.countDown();
                effectsRelease.countDown();
                worker.requestDrain();
            }
            await(stopped);
            assertTrue(closed.get());
            assertEquals(0, cap.snapshot().worker());
            f.assertPhysical(p, true);
        }
    }

    @Test
    void r08TechnicalModelsRequireCompleteContentAndRespectConformingAbsence() throws Exception {
        // Test representations of future contracts, never production I3-I7 participants/tables.
        record Model(
                String kind,
                Long version,
                String content,
                ConsumptionParticipant.EffectEvidence evidence) {}
        var models =
                List.of(
                        new Model(
                                "deleted-balance",
                                null,
                                null,
                                ConsumptionParticipant.EffectEvidence.SATISFIED),
                        new Model(
                                "deleted-balance",
                                1L,
                                "forbidden",
                                ConsumptionParticipant.EffectEvidence.DIVERGENT),
                        new Model(
                                "exact-version",
                                null,
                                null,
                                ConsumptionParticipant.EffectEvidence.MISSING),
                        new Model(
                                "exact-version",
                                2L,
                                "exact",
                                ConsumptionParticipant.EffectEvidence.MISSING),
                        new Model(
                                "exact-version",
                                1L,
                                "changed-field",
                                ConsumptionParticipant.EffectEvidence.DIVERGENT),
                        new Model(
                                "exact-version",
                                1L,
                                "exact",
                                ConsumptionParticipant.EffectEvidence.SATISFIED),
                        new Model(
                                "monotone-current",
                                2L,
                                "coherent",
                                ConsumptionParticipant.EffectEvidence.SATISFIED),
                        new Model(
                                "monotone-current",
                                2L,
                                "corrupt",
                                ConsumptionParticipant.EffectEvidence.DIVERGENT),
                        new Model(
                                "immutable-result",
                                1L,
                                "partial-equality",
                                ConsumptionParticipant.EffectEvidence.DIVERGENT));
        for (boolean present : List.of(false, true))
            for (var model : models)
                try (var f =
                        new InterAuthorityPostgresFixture(
                                PG,
                                "R08-"
                                        + present
                                        + "-"
                                        + model.kind()
                                        + "-"
                                        + model.version()
                                        + "-"
                                        + model.content())) {
                    var p = f.publish();
                    f.read.oracle.execute(
                            "create table fixture_model(version bigint,content text);grant select"
                                    + " on fixture_model to "
                                    + f.read.runtimeRole);
                    if (model.version() != null)
                        f.read.oracle.update(
                                "insert into fixture_model values (?,?)",
                                model.version(),
                                model.content());
                    if (!present)
                        f.read.oracle.update(
                                "delete from consumption_slot where consumable_id=?", p.event());
                    var participant =
                            new ConsumptionParticipant() {
                                public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                                    throw new AssertionError("recovery model only");
                                }

                                public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                                    return c ->
                                            com.kartaguez.pocoma.infra.tx.spring.v3
                                                    .SpringAttemptTransactions.observed(
                                                    f.read.source,
                                                    c,
                                                    () -> {
                                                        var rows =
                                                                f.read.sql.queryForList(
                                                                        "select version,content"
                                                                                + " from"
                                                                                + " fixture_model");
                                                        if (model.kind().equals("deleted-balance"))
                                                            return rows.isEmpty()
                                                                    ? EffectEvidence.SATISFIED
                                                                    : EffectEvidence.DIVERGENT;
                                                        if (rows.isEmpty())
                                                            return EffectEvidence.MISSING;
                                                        long version =
                                                                ((Number)
                                                                                rows.getFirst()
                                                                                        .get(
                                                                                                "version"))
                                                                        .longValue();
                                                        String content =
                                                                (String)
                                                                        rows.getFirst()
                                                                                .get("content");
                                                        if (model.kind().equals("monotone-current"))
                                                            return version >= 1
                                                                            && content.equals(
                                                                                    "coherent")
                                                                    ? EffectEvidence.SATISFIED
                                                                    : EffectEvidence.DIVERGENT;
                                                        if (version != 1)
                                                            return EffectEvidence.MISSING;
                                                        return content.equals("exact")
                                                                ? EffectEvidence.SATISFIED
                                                                : EffectEvidence.DIVERGENT;
                                                    });
                                }
                            };
                    var expected =
                            model.evidence() == ConsumptionParticipant.EffectEvidence.DIVERGENT
                                    ? RecoveryObservation.INCONSISTENT
                                    : present
                                            ? RecoveryObservation.NOT_COMPLETED
                                            : model.evidence()
                                                            == ConsumptionParticipant.EffectEvidence
                                                                    .SATISFIED
                                                    ? RecoveryObservation.COMPLETED
                                                    : RecoveryObservation.INCONSISTENT;
                    assertEquals(
                            expected,
                            f.engine(participant, f.sourceOwner, f.read.tx).observe(f.claim(p)));
                    f.read.trace.add(
                            "R08 independent case="
                                    + model
                                    + " present="
                                    + present
                                    + " expected="
                                    + expected);
                }
    }

    @Test
    void r09WriteBurnableMatrixNeverConsultsCheckpoint() throws Exception {
        for (boolean terminal : List.of(false, true))
            for (boolean present : List.of(false, true))
                for (boolean divergent : List.of(false, true))
                    try (var f =
                            new InterAuthorityPostgresFixture(
                                    PG, "R09-" + terminal + "-" + present + "-" + divergent)) {
                        var published = f.publish();
                        UUID request = terminal ? published.request() : UUID.randomUUID();
                        if (!terminal)
                            f.oracle.update(
                                    "insert into"
                                        + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                        + " values"
                                        + " (?,'i2','pending','fixture',?,0,'CreatePot','{\"label\":\"pending\"}'::jsonb)",
                                    request,
                                    UUID.randomUUID());
                        var definition =
                                new ConsumptionDefinition(
                                        new ConsumptionId(
                                                UUID.fromString(
                                                        "98f7268d-d340-581c-b83d-010926223e14")),
                                        ConsumableFamily.COMMAND,
                                        ConsumptionDefinition.Authority.WRITE);
                        if (present)
                            f.oracle.update(
                                    "insert into"
                                        + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                                        + " select ?,?,h,((h::bigint%4+4)%4)::int from(select"
                                        + " consumption_hash_v1('COMMAND',?) h)x",
                                    definition.id().value(), request, request);
                        if (divergent) {
                            if (terminal)
                                f.oracle.execute(
                                        "alter table v3_command_completion_event disable trigger"
                                            + " all;delete from v3_command_completion_event where"
                                            + " subject_id='"
                                                + request
                                                + "';alter table v3_command_completion_event enable"
                                                + " trigger all");
                            else
                                f.oracle.execute(
                                        "alter table v3_command_result disable trigger all;insert"
                                            + " into"
                                            + " v3_command_result(request_id,outcome,resulting_pot_version,rejection)"
                                            + " values ('"
                                                + request
                                                + "','REJECTED',null,'TEST');alter table"
                                                + " v3_command_result enable trigger all");
                        }
                        var checks = new AtomicInteger();
                        var admissions =
                                (DiscoveryAdmissionStore)
                                        java.lang.reflect.Proxy.newProxyInstance(
                                                getClass().getClassLoader(),
                                                new Class[] {DiscoveryAdmissionStore.class},
                                                (proxy, m, args) -> {
                                                    checks.incrementAndGet();
                                                    throw new AssertionError("burnable checkpoint");
                                                });
                        var participant =
                                new ConsumptionParticipant() {
                                    public Optional<Prepared> prepare(
                                            UUID id, PreparationReads reads) {
                                        throw new AssertionError("recovery only");
                                    }

                                    public Recovery prepareRecovery(
                                            UUID id, PreparationReads reads) {
                                        return c ->
                                                switch (new com.kartaguez.pocoma.infra.persistence
                                                                .primary.jpa.v3
                                                                .JdbcPotCommandObservation(
                                                                f.primary)
                                                        .observe(id, c)) {
                                                    case COMPLETED -> EffectEvidence.SATISFIED;
                                                    case NOT_COMPLETED -> EffectEvidence.MISSING;
                                                    case INCONSISTENT -> EffectEvidence.DIVERGENT;
                                                    case UNAVAILABLE -> EffectEvidence.UNAVAILABLE;
                                                };
                                    }
                                };
                        var slots = new JdbcConsumptionSlotStore(f.primary);
                        var e =
                                new ConsumptionEngine(
                                        definition,
                                        f.sourceOwner,
                                        slots,
                                        participant,
                                        f.sourceOwner,
                                        Optional.empty(),
                                        r -> true,
                                        admissions,
                                        f.sourceOwner,
                                        slots,
                                        new JdbcPartitionOwnershipStore(f.primary),
                                        f.read.settings,
                                        new WorkerCapacity(1, Map.of(definition.id(), 1)),
                                        fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                                        null,
                                        ConsumptionObservation.noop());
                        var expected =
                                divergent
                                        ? RecoveryObservation.INCONSISTENT
                                        : terminal
                                                ? present
                                                        ? RecoveryObservation.INCONSISTENT
                                                        : RecoveryObservation.COMPLETED
                                                : present
                                                        ? RecoveryObservation.NOT_COMPLETED
                                                        : RecoveryObservation.INCONSISTENT;
                        assertEquals(
                                expected,
                                e.observe(
                                        new ClaimedSlot(
                                                definition.id(), request, UUID.randomUUID())));
                        assertEquals(0, checks.get());
                    }
    }

    ConsumptionEngine effectsEngine(
            InterAuthorityPostgresFixture f,
            javax.sql.DataSource ds,
            AttemptTransactions tx,
            ConsumptionParticipant participant) {
        return new ConsumptionEngine(
                f.read.definition,
                tx,
                new JdbcConsumptionSlotStore(ds),
                participant,
                f.sourceOwner,
                Optional.of(f.references),
                r -> true,
                new JdbcDiscoveryAdmissionStore(ds, discoverySettings()),
                f.read.tx,
                f.read.slots,
                f.read.ownership,
                f.read.settings,
                f.read.capacity,
                fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                null,
                ConsumptionObservation.noop());
    }
}
