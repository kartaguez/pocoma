package com.kartaguez.pocoma.infra.postgres.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class BatchLeaseRenewalPostgresTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("pocoma_read")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void renewBatchUpdatesTwentyLeasesAcrossFiveDefinitionsInSingleSqlQuery() {
        var raw =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(raw);
        jdbc.execute(
                """
                create table consumption_routing_contract (
                    consumption_id uuid primary key,
                    family text not null,
                    routing_version text not null,
                    partition_count integer not null
                );
                create table partition_ownership (
                    consumption_id uuid not null,
                    partition_id int not null,
                    owner_token uuid not null,
                    owned_until timestamptz not null,
                    primary key (consumption_id, partition_id)
                );
                """);

        var sqlStatements = new AtomicInteger();
        DataSource counting =
                new AbstractDataSource() {
                    @Override
                    public Connection getConnection() throws java.sql.SQLException {
                        Connection c = raw.getConnection();
                        return (Connection)
                                java.lang.reflect.Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (proxy, method, args) -> {
                                            if (method.getName().equals("prepareStatement")) {
                                                sqlStatements.incrementAndGet();
                                            }
                                            return method.invoke(c, args);
                                        });
                    }

                    @Override
                    public Connection getConnection(String username, String password)
                            throws java.sql.SQLException {
                        return getConnection();
                    }
                };

        var store = new JdbcPartitionOwnershipStore(counting);
        var manager = new DataSourceTransactionManager(counting);
        manager.setNestedTransactionAllowed(false);
        var tx = new SpringAttemptTransactions(counting, manager, 5);

        List<ConsumptionDefinition> definitions = new ArrayList<>();
        List<PartitionLease> leases = new ArrayList<>();
        for (int d = 0; d < 5; d++) {
            var def =
                    new ConsumptionDefinition(
                            new ConsumptionId(UUID.randomUUID()),
                            ConsumableFamily.POT_EVENT,
                            ConsumptionDefinition.Authority.READ);
            jdbc.update(
                    "insert into consumption_routing_contract values (?, ?, 'sha256-v1', ?)",
                    def.id().value(),
                    def.family().name(),
                    def.partitionCount());
            definitions.add(def);
            for (int p = 0; p < 4; p++) {
                int partition = p;
                jdbc.update(
                        "insert into partition_ownership values (?, ?, ?, now() - interval '1 minute')",
                        def.id().value(),
                        partition,
                        UUID.randomUUID());
                var acquired =
                        tx.write(
                                () ->
                                        store.acquire(
                                                def,
                                                partition,
                                                UUID.randomUUID(),
                                                Duration.ofSeconds(30)));
                assertTrue(acquired.isPresent());
                leases.add(acquired.get());
            }
        }
        assertEquals(20, leases.size());

        sqlStatements.set(0);
        var renewed = tx.write(() -> store.renewBatch(definitions, leases, Duration.ofSeconds(30)));
        assertEquals(20, renewed.size());
        // Single grouped SQL statement for all 20 leases across all 5 definitions!
        assertEquals(1, sqlStatements.get());

        // While the 30s lease is active, a competing worker cannot acquire any partition.
        assertTrue(
                tx.write(
                                () ->
                                        store.acquire(
                                                definitions.getFirst(),
                                                0,
                                                UUID.randomUUID(),
                                                Duration.ofSeconds(30)))
                        .isEmpty());

        // Simulate 30s lease expiration: Worker B takes over and fences out Worker A's renewBatch.
        jdbc.update("update partition_ownership set owned_until = now() - interval '1 millisecond'");
        var takeoverLeases = new ArrayList<PartitionLease>();
        for (var def : definitions) {
            for (int p = 0; p < 4; p++) {
                int partition = p;
                var taken =
                        tx.write(
                                () ->
                                        store.acquire(
                                                def,
                                                partition,
                                                UUID.randomUUID(),
                                                Duration.ofSeconds(30)));
                assertTrue(taken.isPresent());
                takeoverLeases.add(taken.get());
            }
        }
        assertEquals(20, takeoverLeases.size());
        assertEquals(0, tx.write(() -> store.renewBatch(definitions, renewed, Duration.ofSeconds(30))).size());
        assertEquals(20, tx.write(() -> store.renewBatch(definitions, takeoverLeases, Duration.ofSeconds(30))).size());
    }

    @Test
    void partialRenewalAndStrictFencingAndConcurrency() throws Exception {
        var raw =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(raw);
        jdbc.execute(
                """
                create table if not exists consumption_routing_contract (
                    consumption_id uuid primary key,
                    family text not null,
                    routing_version text not null,
                    partition_count integer not null
                );
                create table if not exists partition_ownership (
                    consumption_id uuid not null,
                    partition_id int not null,
                    owner_token uuid not null,
                    owned_until timestamptz not null,
                    primary key (consumption_id, partition_id)
                );
                """);

        var store = new JdbcPartitionOwnershipStore(raw);
        var manager = new DataSourceTransactionManager(raw);
        manager.setNestedTransactionAllowed(false);
        var tx = new SpringAttemptTransactions(raw, manager, 5);

        var def =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.POT_EVENT,
                        ConsumptionDefinition.Authority.READ);
        jdbc.update(
                "insert into consumption_routing_contract values (?, ?, 'sha256-v1', ?)",
                def.id().value(),
                def.family().name(),
                def.partitionCount());
        for (int p = 0; p < 4; p++) {
            jdbc.update(
                    "insert into partition_ownership values (?, ?, ?, now() - interval '1 minute')",
                    def.id().value(),
                    p,
                    UUID.randomUUID());
        }

        // Worker A acquires partitions 0, 1, 2, 3
        List<PartitionLease> workerALeases = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            int partition = p;
            workerALeases.add(
                    tx.write(() -> store.acquire(def, partition, UUID.randomUUID(), Duration.ofSeconds(30)))
                            .orElseThrow());
        }

        // Partition 1 expires (not yet taken over); Partition 2 expires and is taken over by Worker B
        jdbc.update(
                "update partition_ownership set owned_until = now() - interval '1 second' where consumption_id = ? and partition_id in (1, 2)",
                def.id().value());
        PartitionLease workerBLease =
                tx.write(() -> store.acquire(def, 2, UUID.randomUUID(), Duration.ofSeconds(30)))
                        .orElseThrow();
        var workerBOwnedUntilBefore =
                jdbc.queryForObject(
                        "select owned_until from partition_ownership where consumption_id = ? and partition_id = 2",
                        java.sql.Timestamp.class,
                        def.id().value());

        // Worker A calls renewBatch on all 4 partitions
        List<PartitionLease> renewed =
                tx.write(() -> store.renewBatch(List.of(def), workerALeases, Duration.ofSeconds(30)));

        // Only partitions 0 and 3 are renewed; 1 (expired) and 2 (fenced by Worker B) are excluded
        var renewedPartitions = renewed.stream().map(PartitionLease::partitionId).toList();
        assertEquals(List.of(0, 3), renewedPartitions);

        // Worker B's lease on partition 2 is untouched
        var row2 =
                jdbc.queryForMap(
                        "select owner_token, owned_until from partition_ownership where consumption_id = ? and partition_id = 2",
                        def.id().value());
        assertEquals(workerBLease.ownerToken(), row2.get("owner_token"));
        assertEquals(workerBOwnedUntilBefore, row2.get("owned_until"));

        // Partition 1 remains expired and is not extended
        Boolean p1Expired =
                jdbc.queryForObject(
                        "select owned_until < clock_timestamp() from partition_ownership where consumption_id = ? and partition_id = 1",
                        Boolean.class,
                        def.id().value());
        assertTrue(Boolean.TRUE.equals(p1Expired));

        // Concurrency: Worker B holds a row lock (FOR UPDATE) on partition 3 while Worker A renews [0, 3]
        try (var conn = raw.getConnection()) {
            conn.setAutoCommit(false);
            try (var ps =
                    conn.prepareStatement(
                            "select 1 from partition_ownership where consumption_id = ? and partition_id = 3 for update")) {
                ps.setObject(1, def.id().value());
                ps.executeQuery().next();
            }
            var concurrentRenewed =
                    tx.write(
                            () -> {
                                jdbc.execute("set local lock_timeout = '200ms'");
                                return store.renewBatch(
                                        List.of(def),
                                        List.of(renewed.get(0), renewed.get(1)),
                                        Duration.ofSeconds(30));
                            });
            // SKIP LOCKED omits locked partition 3 without blocking/timing out and renews partition 0
            assertEquals(List.of(0), concurrentRenewed.stream().map(PartitionLease::partitionId).toList());
            conn.rollback();
        }
    }
}
