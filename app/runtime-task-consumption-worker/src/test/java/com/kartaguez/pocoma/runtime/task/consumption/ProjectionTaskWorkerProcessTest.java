package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.PocomaTaskConsumptionWorkerApplication;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;

@Testcontainers
class ProjectionTaskWorkerProcessTest {
	private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration LIVENESS_WINDOW = Duration.ofSeconds(2);
	private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@Test
	void enabledNonWebRuntimeKeepsTheForkedJvmAliveAfterStartup() throws Exception {
		Path output = Files.createTempFile("pocoma-projection-task-process-", ".log");
		Process process = startRuntime(output);
		try {
			String startedOutput = awaitOutput(process, output,
					"Started PocomaTaskConsumptionWorkerApplication", STARTUP_TIMEOUT);
			assertFalse(process.waitFor(LIVENESS_WINDOW.toMillis(), TimeUnit.MILLISECONDS),
					() -> "runtime exited after reaching Started:\n" + readOutput(output));
			assertTrue(process.isAlive(), () -> "runtime is not alive:\n" + startedOutput);
		}
		finally {
			stop(process);
			Files.deleteIfExists(output);
		}
	}

	private static Process startRuntime(Path output) throws IOException {
		String classpath = reactorClasspath();
		return new ProcessBuilder(
				javaExecutable(),
				"-cp", classpath,
				"com.kartaguez.pocoma.PocomaTaskConsumptionWorkerApplication",
				"--spring.main.banner-mode=off",
				"--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
				"--spring.datasource.username=" + POSTGRES.getUsername(),
				"--spring.datasource.password=" + POSTGRES.getPassword(),
				"--spring.datasource.driver-class-name=org.postgresql.Driver",
				"--pocoma.projection-task-consumption.enabled=true",
				"--pocoma.projection-task-consumption.catalog-projection-types=AUTH,READ_POT,POT_BALANCES",
				"--pocoma.projection-task-consumption.locator-projection-types=AUTH,READ_POT,POT_BALANCES")
				.redirectErrorStream(true)
				.redirectOutput(output.toFile())
				.start();
	}

	private static String reactorClasspath() {
		String classpath = Objects.requireNonNull(System.getProperty("surefire.test.class.path"),
				"Surefire must provide the tested reactor classpath");
		try {
			Path applicationClasses = Path.of(PocomaTaskConsumptionWorkerApplication.class
					.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
			Path reactorRoot = applicationClasses.getParent().getParent().getParent();
			Path workerClasses = Path.of(ConsumptionPollingWorker.class.getProtectionDomain()
					.getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
			Path expectedWorkerTarget = reactorRoot.resolve("orchestrator-poll-consumption/target");
			assertTrue(workerClasses.startsWith(expectedWorkerTarget), () ->
					"ConsumptionPollingWorker must come from the current reactor, not an installed SNAPSHOT: "
							+ workerClasses);
			return classpath;
		}
		catch (URISyntaxException exception) {
			throw new IllegalStateException("Could not resolve the reactor classpath", exception);
		}
	}

	private static String awaitOutput(Process process, Path output, String expected, Duration timeout)
			throws Exception {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			String current = readOutput(output);
			if (current.contains(expected)) return current;
			if (!process.isAlive()) {
				fail("runtime exited before reaching Started (exit " + process.exitValue() + "):\n" + current);
			}
			Thread.sleep(50);
		}
		fail("runtime did not reach Started within " + timeout + ":\n" + readOutput(output));
		return "";
	}

	private static void stop(Process process) throws InterruptedException {
		if (!process.isAlive()) return;
		process.destroy();
		if (process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) return;
		process.destroyForcibly();
		assertTrue(process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
				"runtime process could not be destroyed");
	}

	private static String readOutput(Path output) {
		try {
			return Files.readString(output);
		}
		catch (IOException exception) {
			return "<could not read process output: " + exception.getMessage() + ">";
		}
	}

	private static String javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}
}
