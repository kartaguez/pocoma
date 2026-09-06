package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DistributedComposeConfigurationTest {
	private static final String REQUIRED_VERSION =
			"${POCOMA_BALANCE_PIPELINE_VERSION:?POCOMA_BALANCE_PIPELINE_VERSION is required}";

	@Test
	void allDistributedBalanceConsumersUseTheSameRequiredPipelineVersion() throws IOException {
		String compose = Files.readString(findRepositoryFile("docker-compose.distributed.yml"));

		assertEquals(5, occurrences(compose, REQUIRED_VERSION));
		assertEquals(1, occurrences(compose, "POCOMA_QUERY_BALANCE_PIPELINE_VERSION: " + REQUIRED_VERSION));
		assertEquals(2, occurrences(compose, "POCOMA_EVENT_CONSUMPTION_PIPELINE_VERSION: " + REQUIRED_VERSION));
		assertEquals(2, occurrences(compose, "POCOMA_TASK_CONSUMPTION_PIPELINE_VERSION: " + REQUIRED_VERSION));
		assertFalse(compose.contains("POCOMA_BALANCE_PIPELINE_VERSION:-1"));
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

	private static String section(String value, String start, String end) {
		int startIndex = value.indexOf(start);
		if (startIndex < 0) throw new IllegalArgumentException("Missing section " + start);
		int endIndex = value.indexOf(end, startIndex + start.length());
		if (endIndex < 0) throw new IllegalArgumentException("Missing section terminator " + end);
		return value.substring(startIndex, endIndex);
	}
}
