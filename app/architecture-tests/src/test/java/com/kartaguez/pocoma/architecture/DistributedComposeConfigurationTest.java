package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DistributedComposeConfigurationTest {
	@Test
	void distributedWorkersDeclareTheirCanonicalProjectionTypesAndTaskSegments() throws IOException {
		String compose = Files.readString(findRepositoryFile("docker-compose.distributed.yml"));
		String taskWorker0 = section(compose, "  pocoma-task-consumption-worker-0:\n",
				"\n  pocoma-task-consumption-worker-1:\n");
		String taskWorker1 = section(compose, "  pocoma-task-consumption-worker-1:\n",
				"\n  pocoma-command-consumption-worker:\n");

		assertEquals(2, occurrences(compose,
				"POCOMA_EVENT_CONSUMPTION_PROJECTION_TYPES: READ_POT,POT_BALANCES"));
		assertCanonicalTaskWorker(taskWorker0, 0);
		assertCanonicalTaskWorker(taskWorker1, 1);
		assertFalse(compose.contains("POCOMA_TASK_CONSUMPTION_"));
		assertFalse(compose.contains("POCOMA_EVENT_CONSUMPTION_PIPELINE_ID"));
		assertFalse(compose.contains("POCOMA_EVENT_CONSUMPTION_PIPELINE_VERSION"));
		assertTrue(compose.contains("POCOMA_EVENT_CONSUMPTION_WORKER_ID: event-materializer-0"));
		assertTrue(compose.contains("POCOMA_EVENT_CONSUMPTION_WORKER_ID: event-materializer-1"));
		assertFalse(compose.contains("POCOMA_QUERY_BALANCE_PIPELINE"));
		assertFalse(compose.contains("POCOMA_BALANCE_PIPELINE_VERSION"));
	}

	@Test
	void taskRuntimePackagesTheStandardPostgresProfile() throws IOException {
		String taskProfile = Files.readString(findRepositoryFile(
				"app/runtime-task-consumption-worker/src/main/resources/application-postgres.properties"));
		String commandProfile = Files.readString(findRepositoryFile(
				"app/runtime-command-consumption-worker/src/main/resources/application-postgres.properties"));

		assertEquals(commandProfile, taskProfile);
	}

	@Test
	void distributedCompositionHasNoResidualNatsDependency() throws IOException {
		String compose = Files.readString(findRepositoryFile("docker-compose.distributed.yml"));

		assertFalse(compose.contains("POCOMA_NATS_SERVERS"));
		assertFalse(compose.contains("  nats:"));
		assertFalse(compose.contains("condition: service_started\n      nats:"));
	}

	@Test
	void distributedCompositionRunsAndScrapesTheCommandConsumptionWorker() throws IOException {
		String compose = Files.readString(findRepositoryFile("docker-compose.distributed.yml"));
		String prometheus = Files.readString(findRepositoryFile("docker/prometheus/prometheus.distributed.yml"));
		String service = section(compose, "  pocoma-command-consumption-worker:\n", "\n  prometheus:\n");

		assertTrue(service.contains("RUNTIME_MODULE: runtime-command-consumption-worker"));
		assertTrue(service.contains("RUNTIME_ARTIFACT: pocoma-runtime-command-consumption-worker"));
		assertTrue(service.contains("POCOMA_COMMAND_CONSUMPTION_ENABLED: \"true\""));
		assertTrue(service.contains("<<: *pocoma-java-environment"));
		assertTrue(service.contains("- \"8080\""));
		assertTrue(service.contains("- pocoma-distributed"));
		assertFalse(service.contains("POCOMA_COMMAND_CONSUMPTION_WORKER_ID"));
		assertTrue(compose.contains("pocoma-command-consumption-worker:\n        condition: service_started"));
		assertTrue(prometheus.contains("job_name: pocoma-command-consumption-worker"));
		assertTrue(prometheus.contains("pocoma-command-consumption-worker:8080"));
	}

	private static Path findRepositoryFile(String name) {
		Path directory = Path.of("").toAbsolutePath();
		while (directory != null) {
			Path candidate = directory.resolve(name);
			if (Files.isRegularFile(candidate)) return candidate;
			directory = directory.getParent();
		}
		throw new IllegalStateException("Could not locate " + name);
	}

	private static int occurrences(String value, String needle) {
		int count = 0;
		int offset = 0;
		while ((offset = value.indexOf(needle, offset)) >= 0) {
			count++;
			offset += needle.length();
		}
		return count;
	}

	private static void assertCanonicalTaskWorker(String service, int segmentIndex) {
		assertTrue(service.contains("RUNTIME_MODULE: runtime-task-consumption-worker"));
		assertTrue(service.contains("RUNTIME_ARTIFACT: pocoma-runtime-task-consumption-worker"));
		assertTrue(service.contains("<<: *pocoma-java-environment"));
		assertTrue(service.contains("POCOMA_PROJECTION_TASK_CONSUMPTION_ENABLED: \"true\""));
		assertTrue(service.contains(
				"POCOMA_PROJECTION_TASK_CONSUMPTION_CATALOG_PROJECTION_TYPES: READ_POT,POT_BALANCES"));
		assertTrue(service.contains(
				"POCOMA_PROJECTION_TASK_CONSUMPTION_LOCATOR_PROJECTION_TYPES: READ_POT,POT_BALANCES"));
		assertTrue(service.contains("POCOMA_PROJECTION_TASK_CONSUMPTION_WORKER_ID: "
				+ "canonical-projection-task-worker-" + segmentIndex));
		assertTrue(service.contains("POCOMA_PROJECTION_TASK_CONSUMPTION_SEGMENT_INDEX: " + segmentIndex));
		assertTrue(service.contains(
				"POCOMA_PROJECTION_TASK_CONSUMPTION_SEGMENT_COUNT: ${POCOMA_SEGMENT_COUNT:-2}"));
	}

	private static String section(String value, String start, String end) {
		int startIndex = value.indexOf(start);
		if (startIndex < 0) throw new IllegalArgumentException("Missing section " + start);
		int endIndex = value.indexOf(end, startIndex + start.length());
		if (endIndex < 0) throw new IllegalArgumentException("Missing section terminator " + end);
		return value.substring(startIndex, endIndex);
	}
}
