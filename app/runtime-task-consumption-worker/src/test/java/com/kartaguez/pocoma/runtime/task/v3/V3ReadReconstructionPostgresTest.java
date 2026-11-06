package com.kartaguez.pocoma.runtime.task.v3;

import static com.kartaguez.pocoma.runtime.task.v3.ReadRuntimeI8Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.useridentity.v3.BindingToken;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionWorkerSettings;
import com.kartaguez.pocoma.runtime.consumption.v3.maintenance.JdbcReadMaintenanceAuthority;
import com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceFilter;
import com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceState;
import com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadinessOracle;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;

@Testcontainers
class V3ReadReconstructionPostgresTest {

    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withCommand("postgres", "-c", "log_line_prefix=%m [%p] xid=%x state=%e ");

    @BeforeAll
    static void initializeServer() throws Exception {
        initialize(PG);
    }

    private void ensureMaintenanceTable(JdbcTemplate readAdmin, String readRole) {
        String sql = String.format("""
            CREATE TABLE IF NOT EXISTS public.read_maintenance_state (
                id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                state VARCHAR(32) NOT NULL CHECK (state IN ('READY', 'MAINTENANCE', 'REBUILDING', 'FAILED')),
                phase VARCHAR(32) NOT NULL CHECK (phase IN (
                    'IDLE', 'DRAINING', 'EXCLUDED', 'SCHEMA_DROPPED',
                    'SCHEMA_CREATED', 'MIGRATED', 'ROUTED', 'REPLAYING', 'CONVERGED'
                )),
                target_prefix JSONB NULL,
                operator_id VARCHAR(64) NULL,
                started_at TIMESTAMPTZ NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                error_detail TEXT NULL
            );
            ALTER TABLE public.read_maintenance_state OWNER TO %s;
            REVOKE ALL ON public.read_maintenance_state FROM PUBLIC;
            REVOKE ALL ON public.read_maintenance_state FROM %s;
            GRANT SELECT ON public.read_maintenance_state TO %s;
            INSERT INTO public.read_maintenance_state (id, state, phase, updated_at)
            VALUES (1, 'READY', 'IDLE', clock_timestamp())
            ON CONFLICT (id) DO UPDATE SET
                state = 'READY', phase = 'IDLE', target_prefix = NULL, operator_id = NULL,
                started_at = NULL, error_detail = NULL, updated_at = clock_timestamp();
            """, PG.getUsername(), readRole, readRole);
        readAdmin.execute(sql);
    }

    private void performRebuildDdl(ReadRuntimeI8Fixture f, JdbcTemplate readAdmin) throws Exception {
        // Drop and recreate schema
        readAdmin.execute("DROP SCHEMA IF EXISTS " + f.schema + " CASCADE");
        readAdmin.execute("CREATE SCHEMA " + f.schema + " AUTHORIZATION " + PG.getUsername());
        readAdmin.execute("GRANT USAGE ON SCHEMA " + f.schema + " TO " + f.readRole);

        // Reapply Flyway migrations
        Flyway.configure()
                .dataSource(f.readUrl, PG.getUsername(), PG.getPassword())
                .schemas(f.schema)
                .defaultSchema(f.schema)
                .locations(
                        "classpath:db/v3-consumption-read/migration",
                        "classpath:db/v3-read-business/migration",
                        "classpath:db/v3-read-invitation/migration")
                .placeholders(Map.of(
                        "expectedDatabase", "i5_read",
                        "expectedSchema", f.schema,
                        "expectedAdmin", PG.getUsername(),
                        "expectedRuntime", f.readRole))
                .load()
                .migrate();

        // Reapply routing & grants
        f.script("bootstrap-l2-routing.sql", false);
        f.script("grant-l2-lifecycle.sql", false);
        f.script("grant-l2-discovery.sql", false);
    }

    private void setupFixture(ReadRuntimeI8Fixture f) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        f.properties.put("pocoma.v3.invitation.current-key", "fixture");
        f.properties.put("pocoma.v3.invitation.key-ids", "fixture");
        f.properties.put("pocoma.v3.invitation.keys.fixture", Base64.getEncoder().encodeToString(key));
    }

    @Test
    void rcv01A_fullResetOnPopulatedRead_writeRemainsIntact() throws Exception {
        try (var f = new ReadRuntimeI8Fixture(PG)) {
            setupFixture(f);
            var readAdmin = new JdbcTemplate(new DriverManagerDataSource(f.readUrl, PG.getUsername(), PG.getPassword()));
            ensureMaintenanceTable(readAdmin, f.readRole);
            var authority = new JdbcReadMaintenanceAuthority(readAdmin);

            // 1. Populate WRITE and READ
            var userReq = f.admit("REGISTRATION", "creator", "creator", null);
            f.produce(userReq);
            var potId = new PotId(UUID.randomUUID());
            f.produce(f.admitCommand("creator", f.ownToken("creator").value(), potId, 0, new PotCommand.CreatePot(new Label("Pot-1"))));

            // Start worker to populate READ
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
            var ctx = f.start();
            await(() -> {
                Integer count = f.read.queryForObject("SELECT count(*) FROM read_current_pot", Integer.class);
                return count != null && count >= 1;
            }, Duration.ofSeconds(30));
            f.drain(ctx);

            // Ensure READ has projections and discovery checkpoints
            assertTrue(f.read.queryForObject("SELECT count(*) FROM read_current_pot", Integer.class) >= 1);
            assertTrue(f.read.queryForObject("SELECT count(*) FROM discovery_checkpoint", Integer.class) >= 1);

            // Snapshot WRITE before reset
            String writeSnapshotBefore = f.snapshot();

            // 2. Trigger Reset
            assertTrue(authority.tryAcquireMaintenance("test-operator"));
            authority.updatePhase(ReadMaintenanceState.Phase.SCHEMA_DROPPED);

            // Perform controlled DDL
            performRebuildDdl(f, readAdmin);

            // Verify: read_maintenance_state SURVIVES DROP SCHEMA CASCADE
            ReadMaintenanceState stateAfterDrop = authority.currentState();
            assertEquals(ReadMaintenanceState.State.MAINTENANCE, stateAfterDrop.state());
            assertEquals("test-operator", stateAfterDrop.operatorId());

            // Verify: READ projections, slots, checkpoints completely clean
            assertEquals(0, f.read.queryForObject("SELECT count(*) FROM read_current_pot", Integer.class));
            assertEquals(0, f.read.queryForObject("SELECT count(*) FROM read_pot_version", Integer.class));
            assertEquals(0, f.read.queryForObject("SELECT count(*) FROM read_pot_balance", Integer.class));
            assertEquals(0, f.read.queryForObject("SELECT count(*) FROM consumption_slot", Integer.class));
            assertEquals(0, f.read.queryForObject("SELECT count(*) FROM discovery_checkpoint", Integer.class));

            // Verify: WRITE snapshot is completely intact!
            String writeSnapshotAfter = f.snapshot();
            assertEquals(writeSnapshotBefore, writeSnapshotAfter, "WRITE database must remain completely unaltered by READ reset");
        }
    }

    @Test
    void rcv01B_lateWorkerExclusion_commitFailsAndDoesNotCorruptNewGeneration() throws Exception {
        try (var f = new ReadRuntimeI8Fixture(PG)) {
            var readAdmin = new JdbcTemplate(new DriverManagerDataSource(f.readUrl, PG.getUsername(), PG.getPassword()));
            ensureMaintenanceTable(readAdmin, f.readRole);

            // A worker session connects using readRole
            var workerSource = new DriverManagerDataSource(f.readUrl, f.readRole, "i5-fixture");
            var workerJdbc = new JdbcTemplate(workerSource);

            // Worker can query initially
            assertDoesNotThrow(() -> workerJdbc.queryForList("SELECT * FROM consumption_routing_contract"));

            // Exclusion step: Revoke USAGE on schema and drop schema
            readAdmin.execute("REVOKE USAGE ON SCHEMA " + f.schema + " FROM " + f.readRole);

            // Late worker attempt immediately suffers permission denied
            assertThrows(Exception.class, () -> workerJdbc.queryForList("SELECT * FROM consumption_routing_contract"));

            // Drop schema
            readAdmin.execute("DROP SCHEMA IF EXISTS " + f.schema + " CASCADE");

            // Even if late worker attempts an insert, it fails with relation does not exist / permission denied
            assertThrows(Exception.class, () -> workerJdbc.update(
                    "INSERT INTO " + f.schema + ".discovery_checkpoint (consumption_id, partition_id, position) VALUES (?, ?, ?)",
                    UUID.randomUUID(), 0, 10L
            ));
        }
    }

    @Test
    void rcv01C_explicitUnavailabilityAfterResetBeforeReplay() throws Exception {
        try (var f = new ReadRuntimeI8Fixture(PG)) {
            var readAdmin = new JdbcTemplate(new DriverManagerDataSource(f.readUrl, PG.getUsername(), PG.getPassword()));
            ensureMaintenanceTable(readAdmin, f.readRole);
            var authority = new JdbcReadMaintenanceAuthority(readAdmin);

            assertTrue(authority.tryAcquireMaintenance("op-1"));
            performRebuildDdl(f, readAdmin);
            authority.updatePhase(ReadMaintenanceState.Phase.ROUTED);

            // Verify HTTP barrier returns 503
            var filter = new ReadMaintenanceFilter(authority);
            var req = new MockHttpServletRequest("GET", "/api/v3/me/binding");
            var res = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilter(req, res, chain);

            assertEquals(503, res.getStatus());
            assertEquals("30", res.getHeader("Retry-After"));
            assertNull(chain.getRequest(), "No request served while in maintenance");
            assertFalse(authority.isReady());
        }
    }

    @Test
    void rcv01D_rebuildReplaysFromFiveWriteFamiliesToConvergence() throws Exception {
        try (var f = new ReadRuntimeI8Fixture(PG)) {
            setupFixture(f);
            var readAdmin = new JdbcTemplate(new DriverManagerDataSource(f.readUrl, PG.getUsername(), PG.getPassword()));
            ensureMaintenanceTable(readAdmin, f.readRole);
            var authority = new JdbcReadMaintenanceAuthority(readAdmin);

            // 1. Admit and produce WRITE events
            var userReq = f.admit("REGISTRATION", "creator", "creator", null);
            f.produce(userReq);
            var potId = new PotId(UUID.randomUUID());
            f.produce(f.admitCommand("creator", f.ownToken("creator").value(), potId, 0, new PotCommand.CreatePot(new Label("Pot-Alpha"))));

            // 2. Perform Reset
            assertTrue(authority.tryAcquireMaintenance("rebuild-operator"));
            performRebuildDdl(f, readAdmin);

            // Step 12: Initialize 28 checkpoints at position 0 if absent
            readAdmin.execute("INSERT INTO " + f.schema + ".discovery_checkpoint (consumption_id, partition_id, position) "
                    + "SELECT consumption_id, p, 0 "
                    + "FROM " + f.schema + ".consumption_routing_contract "
                    + "CROSS JOIN generate_series(0, 3) p "
                    + "ON CONFLICT (consumption_id, partition_id) DO NOTHING");

            // 3. Capture B from v3_event_counter in WRITE
            List<Map<String, Object>> counters = f.write.queryForList("SELECT family, position FROM v3_event_counter");
            Map<String, Long> targetB = new HashMap<>();
            for (var row : counters) {
                targetB.put((String) row.get("family"), ((Number) row.get("position")).longValue());
            }
            assertTrue(authority.startRebuilding(targetB));

            // 4. Run replay worker
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
            var ctx = f.start();
            await(() -> {
                Integer potCount = f.read.queryForObject("SELECT count(*) FROM read_current_pot", Integer.class);
                Integer slotCount = f.read.queryForObject("SELECT count(*) FROM consumption_slot", Integer.class);
                return potCount != null && potCount >= 1 && slotCount != null && slotCount == 0;
            }, Duration.ofSeconds(30));
            f.drain(ctx);

            // 5. Evaluate Readiness Oracle
            var oracle = new ReadinessOracle();
            var eval = oracle.evaluate(f.read, f.write, targetB);

            assertTrue(eval.checkpointsSatisfied(), eval.diagnostic());
            assertTrue(eval.slotsSatisfied(), eval.diagnostic());
            assertTrue(eval.integritySatisfied(), eval.diagnostic());
            assertTrue(eval.ready(), "Readiness Oracle must evaluate to TRUE upon convergence");

            // 6. Transition to READY
            assertTrue(authority.markConverged());
            assertTrue(authority.markReady());
            assertTrue(authority.isReady());

            // 7. Verify HTTP now responds normally
            var filter = new ReadMaintenanceFilter(authority);
            var req = new MockHttpServletRequest("GET", "/api/v3/me/binding");
            var res = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(req, res, chain);
            assertEquals(200, res.getStatus());
            assertNotNull(chain.getRequest());
        }
    }

    @Test
    void rcv01G_crashRecoveryAndResume() throws Exception {
        try (var f = new ReadRuntimeI8Fixture(PG)) {
            var readAdmin = new JdbcTemplate(new DriverManagerDataSource(f.readUrl, PG.getUsername(), PG.getPassword()));
            ensureMaintenanceTable(readAdmin, f.readRole);
            var authority = new JdbcReadMaintenanceAuthority(readAdmin);

            // Simulate crash after ROUTED with B captured
            Map<String, Long> targetB = Map.of("POT", 5L, "IDENTITY", 2L);
            assertTrue(authority.tryAcquireMaintenance("resume-operator"));
            authority.startRebuilding(targetB);

            ReadMaintenanceState crashedState = authority.currentState();
            assertEquals(ReadMaintenanceState.State.REBUILDING, crashedState.state());
            assertEquals(ReadMaintenanceState.Phase.REPLAYING, crashedState.phase());
            assertEquals(targetB, crashedState.targetPrefix());

            // Resume protocol:
            // 1. Inspect state
            assertEquals(ReadMaintenanceState.State.REBUILDING, crashedState.state());
            // 2. Schema check: schema exists and routing is valid
            assertTrue(f.read.queryForObject("SELECT count(*) FROM consumption_routing_contract", Integer.class) >= 7);
            // 3. Do NOT drop schema on REPLAYING resume! Replay picks up from current checkpoints.
            authority.updatePhase(ReadMaintenanceState.Phase.REPLAYING);

            assertEquals(ReadMaintenanceState.State.REBUILDING, authority.currentState().state());
        }
    }

    @Test
    void rcv01H_quarantineAndIntegrityViolations_blockReady() throws Exception {
        try (var f = new ReadRuntimeI8Fixture(PG)) {
            setupFixture(f);
            var readAdmin = new JdbcTemplate(new DriverManagerDataSource(f.readUrl, PG.getUsername(), PG.getPassword()));
            ensureMaintenanceTable(readAdmin, f.readRole);

            // Setup a Pot and populate READ
            var userReq = f.admit("REGISTRATION", "creator", "creator", null);
            f.produce(userReq);
            var potId = new PotId(UUID.randomUUID());
            f.produce(f.admitCommand("creator", f.ownToken("creator").value(), potId, 0, new PotCommand.CreatePot(new Label("Pot-Beta"))));

            f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
            var ctx = f.start();
            await(() -> {
                Integer count = f.read.queryForObject("SELECT count(*) FROM read_current_pot", Integer.class);
                Integer slotCount = f.read.queryForObject("SELECT count(*) FROM consumption_slot", Integer.class);
                return count != null && count >= 1 && slotCount != null && slotCount == 0;
            }, Duration.ofSeconds(30));
            f.drain(ctx);

            // Ensure all 28 checkpoints are initialized to at least 0
            f.read.execute("INSERT INTO discovery_checkpoint (consumption_id, partition_id, position) "
                    + "SELECT consumption_id, p, 0 "
                    + "FROM consumption_routing_contract "
                    + "CROSS JOIN generate_series(0, 3) p "
                    + "ON CONFLICT (consumption_id, partition_id) DO NOTHING");

            Map<String, Long> targetB = Map.of("POT", 0L, "IDENTITY", 0L, "COMMAND_COMPLETION", 0L, "IDENTITY_COMPLETION", 0L, "INVITATION", 0L);
            var oracle = new ReadinessOracle();

            // Nominal check passes
            var nominalEval = oracle.evaluate(f.read, f.write, targetB);
            assertTrue(nominalEval.ready(), nominalEval.diagnostic());

            // Inject integrity violation: insert dangling current pot entry without balance/visibility
            UUID fakePotId = UUID.randomUUID();
            f.read.update(
                    "INSERT INTO read_pot_version(pot_id, pot_version, schema_version, payload) VALUES (?, 1, 1, '{\"test\":1}'::jsonb)",
                    fakePotId);
            f.read.update(
                    "INSERT INTO read_current_pot(pot_id, pot_version) VALUES (?, 1)",
                    fakePotId);
            var brokenIntegrityEval = oracle.evaluate(f.read, f.write, targetB);
            assertFalse(brokenIntegrityEval.ready(), "Missing balance/visibility must block READY");
            assertFalse(brokenIntegrityEval.integritySatisfied());
            assertTrue(brokenIntegrityEval.diagnostic().contains("missing balance rows"));

            // Restore integrity by removing fake pot from current_pot
            f.read.update("DELETE FROM read_current_pot WHERE pot_id = ?", fakePotId);
            UUID potEventId = f.write.queryForObject("SELECT event_id FROM v3_pot_event WHERE pot_id = ? LIMIT 1", UUID.class, potId.value());
            UUID potConsumptionId = UUID.fromString("3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a");

            f.read.update(
                    "INSERT INTO consumption_slot(consumption_id, consumable_id, partition_hash, partition_id, state) "
                            + "SELECT ?, ?, h, ((h::bigint%4+4)%4)::int, 'QUARANTINED' "
                            + "FROM (SELECT consumption_hash_v1('POT_EVENT', ?) h) x",
                    potConsumptionId, potEventId, potEventId
            );

            // Target B set to 10 (so slot at position <= 10 is considered residual)
            Map<String, Long> targetBHigh = Map.of("POT", 100L);
            var quarantinedEval = oracle.evaluate(f.read, f.write, targetBHigh);
            assertFalse(quarantinedEval.ready(), "Quarantined slot <= B must block READY");
            assertFalse(quarantinedEval.slotsSatisfied());
            assertEquals(1, quarantinedEval.quarantinedSlotsCount());
        }
    }
}
