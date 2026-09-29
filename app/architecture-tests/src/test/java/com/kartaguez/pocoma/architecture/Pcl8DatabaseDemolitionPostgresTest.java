package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Pcl8DatabaseDemolitionPostgresTest {

	private static final String UPGRADE_DATABASE = "pocoma";
	private static final String BOOTSTRAP_DATABASE = "pocoma_bootstrap";
	private static final Set<String> PRIMARY_DROP_TABLES = Set.of(
			"tasks_4_pipeline", "projection_tasks_legacy", "balance_projection_entries",
			"balance_projection_artifacts", "pot_balances", "pot_balance_versions",
			"pot_balance_projection_states", "event_4_pipeline_materialization_status");
	private static final Set<String> READ_DROP_TABLES = Set.of(
			"projection_artifacts", "projection_failures", "projection_heads",
			"projection_invariant_violations", "pot_projection_user_index",
			"pot_projection_expense_shares", "pot_projection_expenses",
			"pot_projection_shareholders", "pot_projection_snapshots", "pot_version_metadata",
			"projection_coverages");
	private static final Set<String> PRIMARY_KEEP_TABLES = Set.of(
			"business_event_outbox", "projection_tasks", "consumption_slots", "consumption_claims",
			"consumption_inputs", "consumption_results", "pot_global_versions", "pot_headers",
			"shareholders", "expense_headers", "expense_shares", "pot_version_metadata",
			"recorded_commands", "external_identities", "flyway_schema_history");
	private static final Set<String> READ_KEEP_TABLES = Set.of(
			"projection_root", "projection_artifact", "projection_failure",
			"source_version_watermarks", "flyway_schema_history");

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName(UPGRADE_DATABASE)
			.withUsername("pocoma")
			.withPassword("pocoma");

	@Test
	void prePclUpgradeAndCleanBootstrapProduceTheSameCanonicalSchema() throws Exception {
		createDatabase(BOOTSTRAP_DATABASE);

		String upgradeUrl = databaseUrl(UPGRADE_DATABASE);
		migratePrimary(upgradeUrl, "15");
		migrateRead(upgradeUrl, "7");
		seedPrePclDatabase(upgradeUrl);

		Map<String, MigrationIdentity> primaryHistory = migrationHistory(upgradeUrl, "public", 15);
		Map<String, MigrationIdentity> readHistory = migrationHistory(upgradeUrl, "pocoma_read", 7);

		MigrateResult primaryUpgrade = migratePrimary(upgradeUrl, null);
		MigrateResult readUpgrade = migrateRead(upgradeUrl, null);
		assertEquals(1, primaryUpgrade.migrationsExecuted);
		assertEquals(1, readUpgrade.migrationsExecuted);
		assertHistoricalHistoryUnchanged(primaryHistory, migrationHistory(upgradeUrl, "public", 15));
		assertHistoricalHistoryUnchanged(readHistory, migrationHistory(upgradeUrl, "pocoma_read", 7));
		assertEquals(16, successfulMigrationCount(upgradeUrl, "public"));
		assertEquals(8, successfulMigrationCount(upgradeUrl, "pocoma_read"));
		assertFinalSchema(upgradeUrl, true);

		String bootstrapUrl = databaseUrl(BOOTSTRAP_DATABASE);
		assertEquals(16, migratePrimary(bootstrapUrl, null).migrationsExecuted);
		assertEquals(8, migrateRead(bootstrapUrl, null).migrationsExecuted);
		assertFinalSchema(bootstrapUrl, false);

		assertEquals(structuralFingerprint(upgradeUrl), structuralFingerprint(bootstrapUrl));
	}

	private static void createDatabase(String database) throws SQLException {
		try (Connection connection = connection(databaseUrl(UPGRADE_DATABASE));
				Statement statement = connection.createStatement()) {
			statement.execute("create database " + database);
		}
	}

	private static MigrateResult migratePrimary(String url, String target) {
		var configuration = Flyway.configure()
				.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration");
		if (target != null) configuration.target(target);
		Flyway flyway = configuration.load();
		MigrateResult result = flyway.migrate();
		assertTrue(flyway.validateWithResult().validationSuccessful);
		return result;
	}

	private static MigrateResult migrateRead(String url, String target) {
		var configuration = Flyway.configure()
				.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
				.schemas("pocoma_read")
				.defaultSchema("pocoma_read")
				.createSchemas(true)
				.table("flyway_schema_history")
				.locations("classpath:db/read-store/migration");
		if (target != null) configuration.target(target);
		Flyway flyway = configuration.load();
		MigrateResult result = flyway.migrate();
		assertTrue(flyway.validateWithResult().validationSuccessful);
		return result;
	}

	private static void seedPrePclDatabase(String url) throws SQLException {
		try (Connection connection = connection(url); Statement statement = connection.createStatement()) {
			statement.executeUpdate("insert into pot_global_versions values "
					+ "('10000000-0000-0000-0000-000000000001',2)");
			statement.executeUpdate("insert into pot_headers values "
					+ "('11000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001',"
					+ "1,null,'PCL8','20000000-0000-0000-0000-000000000001',false)");
			statement.executeUpdate("insert into shareholders values "
					+ "('12000000-0000-0000-0000-000000000001','21000000-0000-0000-0000-000000000001',"
					+ "'10000000-0000-0000-0000-000000000001',1,null,'Alice',1,1,"
					+ "'20000000-0000-0000-0000-000000000001',false)");
			statement.executeUpdate("insert into expense_headers "
					+ "(id,expense_id,pot_id,started_at_version,ended_at_version,payer_id,amount_numerator,"
					+ "amount_denominator,label,deleted,expense_date) values "
					+ "('13000000-0000-0000-0000-000000000001','22000000-0000-0000-0000-000000000001',"
					+ "'10000000-0000-0000-0000-000000000001',2,null,"
					+ "'21000000-0000-0000-0000-000000000001',10,1,'Lunch',false,'2026-09-29')");
			statement.executeUpdate("insert into expense_shares values "
					+ "('14000000-0000-0000-0000-000000000001','22000000-0000-0000-0000-000000000001',"
					+ "'21000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001',"
					+ "2,null,1,1)");
			statement.executeUpdate("insert into pot_version_metadata values "
					+ "('10000000-0000-0000-0000-000000000001',2,now())");

			statement.executeUpdate("insert into business_event_outbox "
					+ "(id,event_type,pot_id,pot_partition_hash,aggregate_id,version,payload_json,status,"
					+ "attempt_count,created_at) values "
					+ "('30000000-0000-0000-0000-000000000001','EXPENSE_CREATED',"
					+ "'10000000-0000-0000-0000-000000000001',1,"
					+ "'22000000-0000-0000-0000-000000000001',2,'{}','PENDING',0,now())");
			statement.executeUpdate("insert into projection_tasks values "
					+ "('31000000-0000-0000-0000-000000000001','READ_POT','POT',"
					+ "'10000000-0000-0000-0000-000000000001',2,1,now())");

			statement.executeUpdate("insert into consumption_slots "
					+ "(slot_id,consumable_type,consumable_components,consumer_type,consumer_components,status,"
					+ "next_claim_at,created_at) values "
					+ "('32000000-0000-0000-0000-000000000001','TEST','[\"pcl8\"]','TEST','[]',"
					+ "'PENDING',now(),now())");
			statement.executeUpdate("insert into consumption_claims "
					+ "(claim_id,slot_id,attempt_number,claimed_by,claimed_at,lease_until) values "
					+ "('33000000-0000-0000-0000-000000000001','32000000-0000-0000-0000-000000000001',"
					+ "1,'pcl8',now(),now()+interval '1 hour')");
			statement.executeUpdate("insert into consumption_inputs values "
					+ "('34000000-0000-0000-0000-000000000001','32000000-0000-0000-0000-000000000001',"
					+ "'TEST','pcl8',1)");
			statement.executeUpdate("insert into consumption_results "
					+ "(result_id,slot_id,space,object_type,object_id,created_at) values "
					+ "('35000000-0000-0000-0000-000000000001','32000000-0000-0000-0000-000000000001',"
					+ "'TEST','TEST','pcl8',now())");
			statement.executeUpdate("insert into recorded_commands values "
					+ "('36000000-0000-0000-0000-000000000001','TEST','{}',now(),"
					+ "'20000000-0000-0000-0000-000000000001','issuer',now(),now(),now()+interval '1 hour','[]')");
			statement.executeUpdate("insert into external_identities values "
					+ "('issuer','subject','20000000-0000-0000-0000-000000000001')");

			statement.executeUpdate("insert into pot_balance_projection_states values "
					+ "('10000000-0000-0000-0000-000000000001',2)");
			statement.executeUpdate("insert into pot_balance_versions values "
					+ "('40000000-0000-0000-0000-000000000001',"
					+ "'10000000-0000-0000-0000-000000000001',2)");
			statement.executeUpdate("insert into pot_balances values "
					+ "('41000000-0000-0000-0000-000000000001',"
					+ "'10000000-0000-0000-0000-000000000001',2,"
					+ "'21000000-0000-0000-0000-000000000001',10,1)");
			statement.executeUpdate("insert into balance_projection_artifacts values "
					+ "('42000000-0000-0000-0000-000000000001','POT_BALANCES','balance',1,"
					+ "'10000000-0000-0000-0000-000000000001',2,now())");
			statement.executeUpdate("insert into balance_projection_entries values "
					+ "('42000000-0000-0000-0000-000000000001',"
					+ "'21000000-0000-0000-0000-000000000001',10,1)");

			statement.executeUpdate("insert into projection_tasks_legacy "
					+ "(id,task_type,pot_id,pot_partition_hash,target_version,source_event_min_id,"
					+ "source_event_max_id,status,attempt_count,created_at,updated_at) values "
					+ "('43000000-0000-0000-0000-000000000001','READ_POT',"
					+ "'10000000-0000-0000-0000-000000000001',1,2,"
					+ "'30000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',"
					+ "'PENDING',0,now(),now())");
			statement.executeUpdate("insert into tasks_4_pipeline "
					+ "(id,event_id,pipeline_id,pipeline_version,task_type,task_key,task_payload,partition_key,"
					+ "partition_hash,status,attempt_count,created_at,updated_at,target_version,pot_id) values "
					+ "('44000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',"
					+ "'read-pot',1,'READ_POT','pcl8','{}','10000000-0000-0000-0000-000000000001',1,"
					+ "'PENDING',0,now(),now(),2,'10000000-0000-0000-0000-000000000001')");

			statement.executeUpdate("insert into pocoma_read.projection_artifacts values "
					+ "('50000000-0000-0000-0000-000000000001','READ_POT','read-pot',1,"
					+ "'10000000-0000-0000-0000-000000000001',2,'" + "a".repeat(64) + "',now())");
			statement.executeUpdate("insert into pocoma_read.projection_failures values "
					+ "('READ_POT','read-pot',1,'10000000-0000-0000-0000-000000000001',3,now(),'TEST')");
			statement.executeUpdate("insert into pocoma_read.projection_heads values "
					+ "('READ_POT','read-pot',1,'10000000-0000-0000-0000-000000000001',2,now())");
			statement.executeUpdate("insert into pocoma_read.projection_invariant_violations values "
					+ "('51000000-0000-0000-0000-000000000001','READ_POT','read-pot',1,"
					+ "'10000000-0000-0000-0000-000000000001',2,'DIVERGENT_DUPLICATE',"
					+ "'50000000-0000-0000-0000-000000000001','" + "a".repeat(64) + "','"
					+ "b".repeat(64) + "',now())");
			statement.executeUpdate("insert into pocoma_read.pot_projection_snapshots values "
					+ "('50000000-0000-0000-0000-000000000001','READ_POT','read-pot',1,"
					+ "'10000000-0000-0000-0000-000000000001',2,'ACTIVE','PCL8',"
					+ "'20000000-0000-0000-0000-000000000001')");
			statement.executeUpdate("insert into pocoma_read.pot_projection_shareholders values "
					+ "('50000000-0000-0000-0000-000000000001',"
					+ "'21000000-0000-0000-0000-000000000001',0,'Alice',1,1,"
					+ "'20000000-0000-0000-0000-000000000001',false)");
			statement.executeUpdate("insert into pocoma_read.pot_projection_expenses values "
					+ "('50000000-0000-0000-0000-000000000001',"
					+ "'22000000-0000-0000-0000-000000000001',0,"
					+ "'21000000-0000-0000-0000-000000000001',10,1,'Lunch',false)");
			statement.executeUpdate("insert into pocoma_read.pot_projection_expense_shares values "
					+ "('50000000-0000-0000-0000-000000000001',"
					+ "'22000000-0000-0000-0000-000000000001',"
					+ "'21000000-0000-0000-0000-000000000001',0,1,1)");
			statement.executeUpdate("insert into pocoma_read.pot_projection_user_index values "
					+ "('50000000-0000-0000-0000-000000000001','read-pot',1,"
					+ "'10000000-0000-0000-0000-000000000001',2,"
					+ "'20000000-0000-0000-0000-000000000001',now(),'ACTIVE')");
			statement.executeUpdate("insert into pocoma_read.pot_version_metadata values "
					+ "('10000000-0000-0000-0000-000000000001',2,now())");

			statement.executeUpdate("insert into pocoma_read.projection_root "
					+ "(projection_type,target_object_type,target_object_id,target_version) values "
					+ "('READ_POT','POT','10000000-0000-0000-0000-000000000001',2)");
			statement.executeUpdate("insert into pocoma_read.projection_artifact "
					+ "(projection_root_id,artifact_type,artifact_key,payload) values "
					+ "(1,'POT','ROOT','{}')");
			statement.executeUpdate("insert into pocoma_read.projection_failure values "
					+ "('52000000-0000-0000-0000-000000000001','READ_POT','POT',"
					+ "'10000000-0000-0000-0000-000000000001',3,now())");
			statement.executeUpdate("insert into pocoma_read.source_version_watermarks values "
					+ "('10000000-0000-0000-0000-000000000001',2,now())");
		}
	}

	private static void assertFinalSchema(String url, boolean expectSeedData) throws SQLException {
		try (Connection connection = connection(url); Statement statement = connection.createStatement()) {
			for (String table : PRIMARY_DROP_TABLES) assertFalse(tableExists(statement, "public", table), table);
			for (String table : READ_DROP_TABLES) assertFalse(tableExists(statement, "pocoma_read", table), table);
			for (String table : PRIMARY_KEEP_TABLES) assertTrue(tableExists(statement, "public", table), table);
			for (String table : READ_KEEP_TABLES) assertTrue(tableExists(statement, "pocoma_read", table), table);
			assertFalse(schemaExists(statement, "pocoma_control"));
			assertTrue(functionExists(statement, "public", "reject_pot_version_metadata_mutation"));
			assertFalse(functionExists(statement, "pocoma_read", "reject_pot_version_metadata_mutation"));
			if (expectSeedData) {
				assertEquals(1, scalar(statement, "select count(*) from public.pot_global_versions"));
				assertEquals(1, scalar(statement, "select count(*) from public.projection_tasks"));
				assertEquals(1, scalar(statement, "select count(*) from pocoma_read.projection_root"));
				assertEquals(1, scalar(statement, "select count(*) from pocoma_read.projection_artifact"));
				assertEquals(1, scalar(statement, "select count(*) from pocoma_read.source_version_watermarks"));
				SQLException exception = assertThrows(SQLException.class,
						() -> statement.executeUpdate("delete from public.pot_version_metadata"));
				assertTrue(exception.getMessage().contains("POT_VERSION_METADATA_IMMUTABLE"));
			}
		}
	}

	private static boolean tableExists(Statement statement, String schema, String table) throws SQLException {
		return scalar(statement, "select count(*) from information_schema.tables where table_schema='"
				+ schema + "' and table_name='" + table + "'") == 1;
	}

	private static boolean schemaExists(Statement statement, String schema) throws SQLException {
		return scalar(statement, "select count(*) from information_schema.schemata where schema_name='"
				+ schema + "'") == 1;
	}

	private static boolean functionExists(Statement statement, String schema, String function) throws SQLException {
		return scalar(statement, "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace "
				+ "where n.nspname='" + schema + "' and p.proname='" + function + "'") == 1;
	}

	private static int scalar(Statement statement, String sql) throws SQLException {
		try (ResultSet resultSet = statement.executeQuery(sql)) {
			resultSet.next();
			return resultSet.getInt(1);
		}
	}

	private static Map<String, MigrationIdentity> migrationHistory(String url, String schema, int throughVersion)
			throws SQLException {
		Map<String, MigrationIdentity> history = new LinkedHashMap<>();
		try (Connection connection = connection(url); Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("select installed_rank,version,checksum from " + schema
						+ ".flyway_schema_history where success and version::integer <= " + throughVersion
						+ " order by installed_rank")) {
			while (resultSet.next()) {
				history.put(resultSet.getString("version"), new MigrationIdentity(
						resultSet.getInt("installed_rank"), resultSet.getInt("checksum")));
			}
		}
		assertEquals(throughVersion, history.size());
		return history;
	}

	private static int successfulMigrationCount(String url, String schema) throws SQLException {
		try (Connection connection = connection(url); Statement statement = connection.createStatement()) {
			return scalar(statement, "select count(*) from " + schema
					+ ".flyway_schema_history where success and version is not null");
		}
	}

	private static void assertHistoricalHistoryUnchanged(Map<String, MigrationIdentity> expected,
			Map<String, MigrationIdentity> actual) {
		assertEquals(expected, actual);
	}

	private static Set<String> structuralFingerprint(String url) throws SQLException {
		Set<String> fingerprint = new TreeSet<>();
		try (Connection connection = connection(url); Statement statement = connection.createStatement()) {
			append(fingerprint, statement, "schema", "select nspname from pg_namespace "
					+ "where nspname in ('public','pocoma_read') order by nspname");
			append(fingerprint, statement, "relation", "select n.nspname,c.relname,c.relkind "
					+ "from pg_class c join pg_namespace n on n.oid=c.relnamespace "
					+ "where n.nspname in ('public','pocoma_read') and c.relkind in ('r','p','S') "
					+ "order by 1,2,3");
			append(fingerprint, statement, "column", "select n.nspname,c.relname,a.attname,"
					+ "pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,"
					+ "coalesce(pg_get_expr(d.adbin,d.adrelid),'') "
					+ "from pg_attribute a join pg_class c on c.oid=a.attrelid "
					+ "join pg_namespace n on n.oid=c.relnamespace "
					+ "left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum "
					+ "where n.nspname in ('public','pocoma_read') and c.relkind in ('r','p') "
					+ "and a.attnum>0 and not a.attisdropped order by 1,2,a.attnum");
			append(fingerprint, statement, "constraint", "select n.nspname,c.relname,con.conname,"
					+ "con.contype,pg_get_constraintdef(con.oid,true) from pg_constraint con "
					+ "join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace "
					+ "where n.nspname in ('public','pocoma_read') order by 1,2,3");
			append(fingerprint, statement, "index", "select schemaname,tablename,indexname,indexdef "
					+ "from pg_indexes where schemaname in ('public','pocoma_read') order by 1,2,3");
			append(fingerprint, statement, "trigger", "select n.nspname,c.relname,t.tgname,"
					+ "pg_get_triggerdef(t.oid,true) from pg_trigger t join pg_class c on c.oid=t.tgrelid "
					+ "join pg_namespace n on n.oid=c.relnamespace where not t.tgisinternal "
					+ "and n.nspname in ('public','pocoma_read') order by 1,2,3");
			append(fingerprint, statement, "function", "select n.nspname,p.proname,"
					+ "pg_get_function_identity_arguments(p.oid),pg_get_functiondef(p.oid) "
					+ "from pg_proc p join pg_namespace n on n.oid=p.pronamespace "
					+ "where n.nspname in ('public','pocoma_read') order by 1,2,3");
			append(fingerprint, statement, "sequence", "select n.nspname,c.relname,s.seqtypid::regtype::text,"
					+ "s.seqstart,s.seqincrement,s.seqmin,s.seqmax,s.seqcache,s.seqcycle "
					+ "from pg_sequence s join pg_class c on c.oid=s.seqrelid "
					+ "join pg_namespace n on n.oid=c.relnamespace "
					+ "where n.nspname in ('public','pocoma_read') order by 1,2");
		}
		return fingerprint;
	}

	private static void append(Set<String> target, Statement statement, String category, String sql)
			throws SQLException {
		try (ResultSet resultSet = statement.executeQuery(sql)) {
			int columns = resultSet.getMetaData().getColumnCount();
			while (resultSet.next()) {
				StringBuilder row = new StringBuilder(category);
				for (int column = 1; column <= columns; column++) {
					String value = resultSet.getString(column);
					row.append('|').append(value == null ? "<null>" : value.replaceAll("\\s+", " ").trim());
				}
				target.add(row.toString());
			}
		}
	}

	private static Connection connection(String url) throws SQLException {
		return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static String databaseUrl(String database) {
		String url = POSTGRES.getJdbcUrl();
		return url.substring(0, url.lastIndexOf('/') + 1) + database;
	}

	private record MigrationIdentity(int installedRank, int checksum) {
	}
}
