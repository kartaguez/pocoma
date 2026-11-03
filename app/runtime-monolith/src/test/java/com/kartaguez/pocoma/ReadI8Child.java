package com.kartaguez.pocoma;

import static com.kartaguez.pocoma.ReadMonolithI8Fixture.*;
import static com.kartaguez.pocoma.ReadWorkerResourcesPostgresTest.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Test-only child JVM seam. Starts the actual root; the parent alone owns process termination. */
public final class ReadI8Child {
    public static void main(String[] args) throws Exception {
        var config = new Properties();
        try (var input = Files.newInputStream(Path.of(args[1]))) {
            config.load(input);
        }
        var values = new LinkedHashMap<String, Object>();
        config.forEach((k, v) -> values.put(k.toString(), v));
        var app = new SpringApplication(Class.forName(args[0]));
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(values);
        var context = app.run("--spring.profiles.active=v3-read-worker");
        var worker = context.getBean(V3ConsumptionWorker.class);
        var settings = context.getBean(V3ConsumptionWorkerSettings.class);
        long candidates = settings.partitions().values().stream().mapToInt(List::size).sum();
        await(
                () ->
                        worker.localLeases().values().stream().mapToLong(Long::longValue).sum()
                                == candidates);
        if (args[2].equals("renew-unknown")) {
            var fault = new RetainingSource(context.getBean("readControl", HikariDataSource.class));
            fault.selected = thread -> thread.startsWith("v3-renew-READ-2");
            var owner = new SpringAttemptTransactions(fault, manager(fault), 3);
            for (var binding : bindings(worker))
                if (binding.definition().authority() == ConsumptionDefinition.Authority.READ) {
                    replace(binding.engine(), "controlTransactions", owner);
                    replace(binding.engine(), "controlSlots", new JdbcConsumptionSlotStore(fault));
                    replace(binding.engine(), "ownership", new JdbcPartitionOwnershipStore(fault));
                    replace(
                            binding.engine(),
                            "navigation",
                            new JdbcSlotKeyNavigation(fault, settings.lifecycle()));
                }
            fault.armed.set(true);
            await(() -> worker.snapshot().unknownRenewals() > 0, Duration.ofSeconds(15));
            worker.requestDrain();
            assertEquals(V3ConsumptionWorker.State.DRAINING, worker.snapshot().state());
            assertFalse(context.getBean("readControl", HikariDataSource.class).isClosed());
            System.out.println(
                    "I8_RENEW_UNKNOWN pid="
                            + fault.pid
                            + " xid="
                            + fault.xid
                            + " snapshot="
                            + worker.snapshot()
                            + " poolClosed=false");
        } else
            System.out.println(
                    "I8_CHILD_OWNER_READY root=" + args[0] + " candidates=" + candidates);
        System.out.flush();
        while (true) Thread.sleep(1000); // Parent terminates this live process as CRASH.
    }

    static final class Child implements AutoCloseable {
        final Path directory, config, log;
        final Process process;
        final String root, mode;

        Child(ReadMonolithI8Fixture fixture, String mode) throws Exception {
            this.root = fixture.root.getSimpleName();
            this.mode = mode;
            directory = Files.createTempDirectory("pocoma-i8-child-");
            config = directory.resolve("private.properties");
            log = directory.resolve("child.log");
            var properties = new Properties();
            fixture.properties.forEach((k, v) -> properties.setProperty(k, v.toString()));
            try (var out = Files.newOutputStream(config)) {
                properties.store(out, "private disposable fixture configuration");
            }
            Files.setPosixFilePermissions(
                    config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            String classpath =
                    System.getProperty(
                            "surefire.test.class.path", System.getProperty("java.class.path"));
            process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                    "-cp",
                                    classpath,
                                    ReadI8Child.class.getName(),
                                    fixture.root.getName(),
                                    config.toString(),
                                    mode)
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
        }

        String output() {
            try {
                return Files.exists(log) ? Files.readString(log) : "";
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void stage(String marker) throws Exception {
            await(() -> output().contains(marker) || !process.isAlive(), Duration.ofSeconds(30));
            assertTrue(
                    output().contains(marker),
                    "child " + root + "/" + mode + " did not reach " + marker + "; log=" + log);
        }

        void crash() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "physical child process terminated");
        }

        public void close() throws Exception {
            if (process.isAlive()) crash();
            Path target =
                    Path.of(
                            "target/l4-i8-evidence/child-"
                                    + root
                                    + "-"
                                    + mode
                                    + "-"
                                    + directory.getFileName()
                                    + ".log");
            Files.createDirectories(target.getParent());
            if (Files.exists(log)) Files.copy(log, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(config);
            Files.deleteIfExists(log);
            Files.deleteIfExists(directory);
        }
    }
}
