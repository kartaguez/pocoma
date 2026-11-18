package com.kartaguez.pocoma.runtime.task;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.compose.polling.materialize.ConsumptionDiscoveryComposition;
import com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants.Name;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.domain.consumption.RoutingV1;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.infra.postgres.source.JdbcEventDiscoveryReferences;
import com.kartaguez.pocoma.orchestrator.consumption.DiscoveryEngine;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Testcontainers
class L73TerminationReadPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse(
                                    "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                            .asCompatibleSubstituteFor("postgres"));

    @org.junit.jupiter.api.BeforeAll
    static void initPgcrypto() {
        var admin =
                new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        admin.execute("create extension if not exists pgcrypto with schema public");
    }

    /**
     * L2M-C01-A-REPORT: multi-partition interleaved Identity events -> exact slots and K per
     * partition on real READ DiscoveryEngine.
     */
    @Test
    void l2m_c01_a_report_multipartitionIdentityDiscoveryExactSlotsAndK() throws Exception {
        try (var fixture = new ReadParticipantsFixture(POSTGRES, "c01a")) {
            // Publish multiple distinct registrations to populate all 4 partitions with interleaved
            // POCOMA_USER (non-relevant) and EXTERNAL_USER_IDENTITY (relevant) events.
            for (int i = 0; i < 16; i++) {
                var ext = new ExternalIdentity("c01a", "user-" + i);
                publishRegistration(fixture, ext);
            }

            var d = fixture.participants.get(Name.CURRENT_BINDING).definition();
            var engine = fixture.engines.get(Name.CURRENT_BINDING);
            var discovery =
                    new DiscoveryEngine(
                            d,
                            fixture.f.read.tx,
                            fixture.f.read.ownership,
                            fixture.f.admissions,
                            SubobjectPostgresFixture.discoverySettings(),
                            fixture.f.sourceOwner,
                            new JdbcEventDiscoveryReferences(
                                    fixture.f.primary,
                                    d.family(),
                                    SubobjectPostgresFixture.discoverySettings()),
                            r -> ConsumptionDiscoveryComposition.relevant(d, r));

            for (int p = 0; p < 4; p++) {
                discovery.discoverEvents(fixture.lease(Name.CURRENT_BINDING, engine, p));
            }

            long expectedK =
                    fixture.f.oracle.queryForObject(
                            "select position from v3_event_counter where family='IDENTITY'",
                            Long.class);
            var relevantIds =
                    fixture.f.oracle.queryForList(
                            "select event_id from v3_identity_event where"
                                    + " subject_type='EXTERNAL_USER_IDENTITY'",
                            UUID.class);
            assertEquals(17, relevantIds.size()); // 1 initial + 16 published

            Map<Integer, Set<UUID>> expectedByPartition = new HashMap<>();
            for (int p = 0; p < 4; p++) expectedByPartition.put(p, new HashSet<>());
            for (UUID eventId : relevantIds) {
                int part =
                        RoutingV1.partition(
                                RoutingV1.hash(ConsumableFamily.IDENTITY_EVENT, eventId), 4);
                expectedByPartition.get(part).add(eventId);
            }

            for (int p = 0; p < 4; p++) {
                long checkpoint =
                        fixture.f.read.oracle.queryForObject(
                                "select position from discovery_checkpoint where"
                                        + " consumption_id=? and partition_id=?",
                                Long.class,
                                d.id().value(),
                                p);
                assertEquals(expectedK, checkpoint, "checkpoint K for partition " + p);
                var actualSlots =
                        new HashSet<>(
                                fixture.f.read.oracle.queryForList(
                                        "select consumable_id from consumption_slot where"
                                                + " consumption_id=? and partition_id=?",
                                        UUID.class,
                                        d.id().value(),
                                        p));
                assertEquals(
                        expectedByPartition.get(p),
                        actualSlots,
                        "exact slot set for partition " + p);
            }
        }
    }

    /**
     * L2M-C01-D-REPORT: concurrent retained publication on IDENTITY coupled with real Discovery
     * READ before and after commit and rollback.
     */
    @Test
    void l2m_c01_d_report_concurrentRetainedPublicationCoupledWithReadDiscovery() throws Exception {
        try (var fixture = new ReadParticipantsFixture(POSTGRES, "c01d");
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var d = fixture.participants.get(Name.CURRENT_BINDING).definition();
            var engine = fixture.engines.get(Name.CURRENT_BINDING);
            var discovery =
                    new DiscoveryEngine(
                            d,
                            fixture.f.read.tx,
                            fixture.f.read.ownership,
                            fixture.f.admissions,
                            SubobjectPostgresFixture.discoverySettings(),
                            fixture.f.sourceOwner,
                            new JdbcEventDiscoveryReferences(
                                    fixture.f.primary,
                                    d.family(),
                                    SubobjectPostgresFixture.discoverySettings()),
                            r -> ConsumptionDiscoveryComposition.relevant(d, r));

            // Baseline discovery before barrier
            for (int p = 0; p < 4; p++) {
                discovery.discoverEvents(fixture.lease(Name.CURRENT_BINDING, engine, p));
            }
            long kBefore =
                    fixture.f.oracle.queryForObject(
                            "select position from v3_event_counter where family='IDENTITY'",
                            Long.class);
            int slotsBefore =
                    fixture.f.read.oracle.queryForObject(
                            "select count(*) from consumption_slot where consumption_id=?",
                            Integer.class,
                            d.id().value());

            // 1. Concurrent retained publication that COMMITS
            runRetainedPublicationCase(fixture, discovery, engine, pool, kBefore, slotsBefore, true);

            long kAfterCommit =
                    fixture.f.oracle.queryForObject(
                            "select position from v3_event_counter where family='IDENTITY'",
                            Long.class);
            int slotsAfterCommit =
                    fixture.f.read.oracle.queryForObject(
                            "select count(*) from consumption_slot where consumption_id=?",
                            Integer.class,
                            d.id().value());
            assertTrue(kAfterCommit > kBefore);
            assertEquals(slotsBefore + 1, slotsAfterCommit);

            // 2. Concurrent retained publication that ROLLS BACK
            runRetainedPublicationCase(
                    fixture, discovery, engine, pool, kAfterCommit, slotsAfterCommit, false);
            assertEquals(
                    kAfterCommit,
                    fixture.f.oracle.queryForObject(
                            "select position from v3_event_counter where family='IDENTITY'",
                            Long.class));
            assertEquals(
                    slotsAfterCommit,
                    fixture.f.read.oracle.queryForObject(
                            "select count(*) from consumption_slot where consumption_id=?",
                            Integer.class,
                            d.id().value()));
        }
    }

    private void runRetainedPublicationCase(
            ReadParticipantsFixture fixture,
            DiscoveryEngine discovery,
            com.kartaguez.pocoma.orchestrator.consumption.ConsumptionEngine engine,
            java.util.concurrent.ExecutorService pool,
            long kBefore,
            int slotsBefore,
            boolean commit)
            throws Exception {
        var d = fixture.participants.get(Name.CURRENT_BINDING).definition();
        var barrierEntered = new CountDownLatch(1);
        var releaseBarrier = new CountDownLatch(1);
        var ext = new ExternalIdentity("c01d", commit ? "committed-user" : "rolledback-user");
        var id = UUID.randomUUID();
        var request =
                new IdentityRequest(
                        id,
                        ext,
                        new IdentityRequest.Registration(),
                        null,
                        java.time.Instant.EPOCH,
                        new IdentityRequest.AdmissionContext(
                                "L4 internal fixture",
                                "1",
                                ext,
                                ext,
                                id.toString(),
                                id.toString(),
                                java.time.Instant.EPOCH,
                                Map.of(),
                                IdentityRequest.Origin.CONTROLLED_INTERNAL));
        fixture.f.oracle.update(
                "with req as (insert into"
                        + " v3_identity_request(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at)"
                        + " values (?,?,?,?,?,?,?,?::jsonb,?) returning request_id), pos as (update"
                        + " v3_request_counter set position=position+1 where"
                        + " family='IDENTITY_REQUEST' returning position) insert into"
                        + " v3_request_position(family,position,request_id) select"
                        + " 'IDENTITY_REQUEST',pos.position,req.request_id from req,pos",
                id,
                ext.issuer(),
                ext.subject(),
                com.kartaguez.pocoma.infra.postgres.write.identity.IdentityRequestJson.operation(
                        request),
                null,
                ext.issuer(),
                ext.subject(),
                com.kartaguez.pocoma.infra.postgres.write.identity.IdentityRequestJson.encode(
                        request.admission()),
                java.sql.Timestamp.from(java.time.Instant.EPOCH));
        var writeDef =
                new com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition(
                        new com.kartaguez.pocoma.domain.consumption.ConsumptionId(
                                UUID.fromString("2999e75c-272d-5afe-a966-c9364ef021fe")),
                        ConsumableFamily.IDENTITY_REQUEST,
                        com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition.Authority
                                .WRITE);
        fixture.seedWrite(writeDef, id);

        var armed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var retainingTx =
                new com.kartaguez.pocoma.port.transaction.AttemptTransactions() {
                    @Override
                    public <T> T read(
                            java.util.function.Function<
                                            com.kartaguez.pocoma.port.transaction
                                                    .RecoveryReadContext,
                                            T>
                                    action,
                            Guard guard) {
                        return fixture.f.sourceOwner.read(action, guard);
                    }

                    @Override
                    public void poison(Throwable cause) {
                        fixture.f.sourceOwner.poison(cause);
                    }

                    @Override
                    public void requirePublicationClosed() {
                        fixture.f.sourceOwner.requirePublicationClosed();
                    }

                    @Override
                    public <T> T write(java.util.function.Supplier<T> action, Guard guard) {
                        if (!armed.get()) {
                            return fixture.f.sourceOwner.write(action, guard);
                        }
                        return fixture.f.sourceOwner.write(
                                () -> {
                                    T res = action.get();
                                    barrierEntered.countDown();
                                    try {
                                        assertTrue(releaseBarrier.await(10, TimeUnit.SECONDS));
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                    if (!commit) {
                                        throw new IllegalStateException("forced rollback");
                                    }
                                    return res;
                                },
                                guard);
                    }
                };

        Future<?> pubFuture =
                pool.submit(
                        () -> {
                            var p =
                                    new com.kartaguez.pocoma.engine.write.identity
                                            .ExecuteIdentityRequest(
                                            new com.kartaguez.pocoma.infra.postgres.write.identity
                                                    .JdbcIdentityRequestStore(
                                                    fixture.f.primary, fixture.f.sourceOwner),
                                            new com.kartaguez.pocoma.infra.postgres.write.identity
                                                    .JdbcIdentityRequestObservation(
                                                    fixture.f.primary),
                                            new com.kartaguez.pocoma.infra.postgres.write.identity
                                                    .JdbcIdentityAuthority(
                                                    fixture.f.primary, fixture.f.sourceOwner),
                                            () ->
                                                    new com.kartaguez.pocoma.domain.identity
                                                            .BindingToken(
                                                            "fixture-" + UUID.randomUUID()),
                                            UUID::randomUUID);
                            var writeEngine =
                                    new com.kartaguez.pocoma.orchestrator.consumption
                                            .ConsumptionEngine(
                                            writeDef,
                                            retainingTx,
                                            new com.kartaguez.pocoma.infra.postgres.core.consumption
                                                    .JdbcConsumptionSlotStore(fixture.f.primary),
                                            p,
                                            new com.kartaguez.pocoma.infra.postgres.core.consumption
                                                    .JdbcPartitionOwnershipStore(fixture.f.primary),
                                            fixture.f.read.settings,
                                            new com.kartaguez.pocoma.orchestrator.consumption
                                                    .WorkerCapacity(1, Map.of(writeDef.id(), 1)),
                                            fault ->
                                                    com.kartaguez.pocoma.contract.consumption.spi
                                                            .AttemptFailureClassifier.FailureKind
                                                            .NON_COUNTABLE);
                            int part =
                                    RoutingV1.partition(
                                            RoutingV1.hash(writeDef.family(), id), 4);
                            var lease = fixture.f.primaryLease(writeEngine, writeDef, part);
                            var claim = writeEngine.acquire(lease, List.of(id)).claims().getFirst();
                            armed.set(true);
                            var res = writeEngine.execute(claim);
                            if (commit) {
                                assertEquals(
                                        com.kartaguez.pocoma.contract.consumption.invocation
                                                .ConsumptionProtocol.Status.COMMITTED,
                                        res.status());
                            } else {
                                assertEquals(
                                        com.kartaguez.pocoma.contract.consumption.invocation
                                                .ConsumptionProtocol.Status.ROLLED_BACK,
                                        res.status());
                            }
                        });

        if (!barrierEntered.await(10, TimeUnit.SECONDS)) {
            if (pubFuture.isDone()) pubFuture.get();
            fail("pubFuture did not reach barrier");
        }

        // While retained, Discovery READ must observe only committed prefix kBefore and slotsBefore
        for (int p = 0; p < 4; p++) {
            discovery.discoverEvents(fixture.lease(Name.CURRENT_BINDING, engine, p));
            long cp =
                    fixture.f.read.oracle.queryForObject(
                            "select position from discovery_checkpoint where consumption_id=? and"
                                    + " partition_id=?",
                            Long.class,
                            d.id().value(),
                            p);
            assertEquals(kBefore, cp);
        }
        assertEquals(
                slotsBefore,
                fixture.f.read.oracle.queryForObject(
                        "select count(*) from consumption_slot where consumption_id=?",
                        Integer.class,
                        d.id().value()));

        releaseBarrier.countDown();
        pubFuture.get(10, TimeUnit.SECONDS);

        // After commit/rollback, Discovery READ advances cleanly (or stays at kBefore on rollback)
        for (int p = 0; p < 4; p++) {
            discovery.discoverEvents(fixture.lease(Name.CURRENT_BINDING, engine, p));
        }
    }

    private void publishRegistration(ReadParticipantsFixture fixture, ExternalIdentity ext) {
        var id = UUID.randomUUID();
        var request =
                new IdentityRequest(
                        id,
                        ext,
                        new IdentityRequest.Registration(),
                        null,
                        java.time.Instant.EPOCH,
                        new IdentityRequest.AdmissionContext(
                                "L4 internal fixture",
                                "1",
                                ext,
                                ext,
                                id.toString(),
                                id.toString(),
                                java.time.Instant.EPOCH,
                                Map.of(),
                                IdentityRequest.Origin.CONTROLLED_INTERNAL));
        fixture.f.oracle.update(
                "with req as (insert into"
                        + " v3_identity_request(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at)"
                        + " values (?,?,?,?,?,?,?,?::jsonb,?) returning request_id), pos as (update"
                        + " v3_request_counter set position=position+1 where"
                        + " family='IDENTITY_REQUEST' returning position) insert into"
                        + " v3_request_position(family,position,request_id) select"
                        + " 'IDENTITY_REQUEST',pos.position,req.request_id from req,pos",
                id,
                ext.issuer(),
                ext.subject(),
                com.kartaguez.pocoma.infra.postgres.write.identity.IdentityRequestJson.operation(
                        request),
                null,
                ext.issuer(),
                ext.subject(),
                com.kartaguez.pocoma.infra.postgres.write.identity.IdentityRequestJson.encode(
                        request.admission()),
                java.sql.Timestamp.from(java.time.Instant.EPOCH));
        var d =
                new com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition(
                        new com.kartaguez.pocoma.domain.consumption.ConsumptionId(
                                UUID.fromString("2999e75c-272d-5afe-a966-c9364ef021fe")),
                        ConsumableFamily.IDENTITY_REQUEST,
                        com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition.Authority
                                .WRITE);
        fixture.seedWrite(d, id);
        var p =
                new com.kartaguez.pocoma.engine.write.identity.ExecuteIdentityRequest(
                        new com.kartaguez.pocoma.infra.postgres.write.identity
                                .JdbcIdentityRequestStore(fixture.f.primary, fixture.f.sourceOwner),
                        new com.kartaguez.pocoma.infra.postgres.write.identity
                                .JdbcIdentityRequestObservation(fixture.f.primary),
                        new com.kartaguez.pocoma.infra.postgres.write.identity
                                .JdbcIdentityAuthority(fixture.f.primary, fixture.f.sourceOwner),
                        () ->
                                new com.kartaguez.pocoma.domain.identity.BindingToken(
                                        "fixture-" + UUID.randomUUID()),
                        UUID::randomUUID);
        fixture.executeWrite(d, id, p);
    }
}
