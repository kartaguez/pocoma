package com.kartaguez.pocoma.infra.observability.micrometer.v3;
import com.kartaguez.pocoma.contract.consumption.observation.AttemptObservation;
import com.kartaguez.pocoma.contract.consumption.observation.DiscoveryListener;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe, bounded in-memory metrology store and Micrometer reporter for asynchronous stage handoffs.
 * Measures intervals A1 (T0a - T0), A2 (T0b - T0a), A3 (T0c - T0b), A4 (T1 - T0c),
 * A (T1 - T0), B (T2 - T1), C (T3 - T2), D (T4 - T3), and aggregates C-03 readiness.
 */
public final class StageHandoffTracker
        implements AttemptObservation, DiscoveryListener {

    private static final int MAX_TRACKED_COMMANDS = 10_000;

    private static final Duration[] HISTOGRAM_BUCKETS = {
        Duration.ofMillis(1),
        Duration.ofMillis(2),
        Duration.ofMillis(5),
        Duration.ofMillis(10),
        Duration.ofMillis(20),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(200),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofSeconds(60)
    };


    public static final class ProjectionRecord {
        public final String name;
        public volatile long t3Nanos;
        public volatile long t3EpochMs;
        public volatile long t4Nanos;
        public volatile long t4EpochMs;
        public volatile Double readWaitMs;
        public volatile Double readProcessingMs;

        public ProjectionRecord(String name) {
            this.name = name;
        }

        public Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("t3EpochMs", t3EpochMs > 0 ? t3EpochMs : null);
            m.put("t4EpochMs", t4EpochMs > 0 ? t4EpochMs : null);
            m.put("readWaitMs", readWaitMs);
            m.put("readProcessingMs", readProcessingMs);
            m.put("t3ToT4Ms", readProcessingMs);
            return m;
        }
    }

    public static final class CommandRecord {
        public final UUID commandId;
        public volatile UUID potId;
        public volatile Long resultingVersion;
        public volatile UUID potEventId;
        public volatile UUID completionEventId;

        public volatile Integer partition;
        public volatile int attempts = 0;
        public volatile ConsumptionProtocol.Status terminalStatus;

        public volatile long t0Nanos;
        public volatile long t0EpochMs;

        public volatile long t0aNanos;
        public volatile long t0aEpochMs;

        public volatile long t0bNanos;
        public volatile long t0bEpochMs;

        public volatile long t0cNanos;
        public volatile long t0cEpochMs;

        public volatile long t1Nanos;
        public volatile long t1EpochMs;

        public volatile long t2Nanos;
        public volatile long t2EpochMs;

        public volatile Double commandWaitMs;
        public volatile Double waitBeforeDiscoveryMs;
        public volatile Double discoveryToSlotMs;
        public volatile Double slotAcquisitionWaitMs;
        public volatile Double acquisitionToAttemptMs;
        public volatile Double t0aToT1Ms;
        public volatile Double commandProcessingMs;

        public volatile Long c03ReadyEpochMs;
        public volatile Double c03ReadWaitMs;

        public final ConcurrentHashMap<String, ProjectionRecord> projections =
                new ConcurrentHashMap<>();

        public CommandRecord(UUID commandId) {
            this.commandId = commandId;
        }

        public String missingDataReason() {
            if (t0Nanos == 0) return "MISSING_T0";
            if (t0aNanos == 0) return "MISSING_T0A";
            if (t0bNanos == 0) return "MISSING_T0B";
            if (t0cNanos == 0) return "MISSING_T0C";
            if (t1Nanos == 0) return "MISSING_T1";
            if (t2Nanos == 0) return "MISSING_T2";
            return null;
        }

        public Map<String, Object> toMap() {
            return toMap(true);
        }

        public Map<String, Object> toMap(boolean includeBusinessIds) {
            var m = new LinkedHashMap<String, Object>();
            if (includeBusinessIds) {
                m.put("commandId", commandId.toString());
                m.put("potId", potId != null ? potId.toString() : null);
                m.put("resultingVersion", resultingVersion);
                m.put("potEventId", potEventId != null ? potEventId.toString() : null);
                m.put("completionEventId", completionEventId != null ? completionEventId.toString() : null);
            }
            m.put("partition", partition);
            m.put("attempts", attempts);
            m.put("status", terminalStatus != null ? terminalStatus.name() : (t1Nanos > 0 ? "EXECUTING" : "PENDING"));
            m.put("missingDataReason", missingDataReason());
            m.put("t0EpochMs", t0EpochMs > 0 ? t0EpochMs : null);
            m.put("t0aEpochMs", t0aEpochMs > 0 ? t0aEpochMs : null);
            m.put("t0bEpochMs", t0bEpochMs > 0 ? t0bEpochMs : null);
            m.put("t0cEpochMs", t0cEpochMs > 0 ? t0cEpochMs : null);
            m.put("t1EpochMs", t1EpochMs > 0 ? t1EpochMs : null);
            m.put("t2EpochMs", t2EpochMs > 0 ? t2EpochMs : null);
            m.put("commandWaitMs", commandWaitMs);
            m.put("waitBeforeDiscoveryMs", waitBeforeDiscoveryMs);
            m.put("discoveryToSlotMs", discoveryToSlotMs);
            m.put("slotAcquisitionWaitMs", slotAcquisitionWaitMs);
            m.put("acquisitionToAttemptMs", acquisitionToAttemptMs);
            m.put("t0aToT1Ms", t0aToT1Ms);
            m.put("commandProcessingMs", commandProcessingMs);
            m.put("t1ToT2Ms", commandProcessingMs);

            var projs = new LinkedHashMap<String, Object>();
            for (var entry : projections.entrySet()) {
                projs.put(entry.getKey(), entry.getValue().toMap());
            }
            m.put("projections", projs);

            // Compute C-03 readiness: requires PotBalance and PotVersion
            var pv = projections.get("PotVersion");
            var pb = projections.get("PotBalance");
            if (pv != null && pb != null && pv.t4EpochMs > 0 && pb.t4EpochMs > 0) {
                long c03Epoch = Math.max(pv.t4EpochMs, pb.t4EpochMs);
                m.put("c03ReadyEpochMs", c03Epoch);
                if (t2EpochMs > 0) {
                    m.put("c03ReadWaitMs", Math.max(0.0, (c03Epoch - t2EpochMs)));
                } else {
                    m.put("c03ReadWaitMs", null);
                }
            } else {
                m.put("c03ReadyEpochMs", null);
                m.put("c03ReadWaitMs", null);
            }
            return m;
        }
    }

    private final Map<ConsumptionId, String> consumptionNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CommandRecord> commands = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> eventToCommand = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentHashMap<String, ProjectionRecord>> eventProjections =
            Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<UUID, ConcurrentHashMap<String, ProjectionRecord>> eldest) {
                    return size() > MAX_TRACKED_COMMANDS;
                }
            });
    private final ConcurrentLinkedDeque<UUID> ring = new ConcurrentLinkedDeque<>();
    private static final java.nio.file.Path HANDOFF_LOG_PATH =
            java.nio.file.Path.of(
                    System.getProperty(
                            "pocoma.v3.handoff.log", "/tmp/pocoma-stage-handoffs.jsonl"));

    private volatile MeterRegistry registry;

    public StageHandoffTracker() {
        initCatalog();
    }

    private void appendLogLine(String jsonLine) {
        try {
            java.nio.file.Files.writeString(
                    HANDOFF_LOG_PATH,
                    jsonLine + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private void initCatalog() {
        String[] canonical = {
            "Command",
            "IdentityRequest",
            "PotVersion",
            "PotBalance",
            "PotVisibility",
            "Invitation",
            "CurrentBinding",
            "CommandResult",
            "IdentityRequestResult"
        };
        var defs = new ArrayList<>(com.kartaguez.pocoma.domain.consumption.v3.ConsumptionCatalog.definitions());
        defs.addAll(com.kartaguez.pocoma.domain.consumption.v3.ConsumptionCatalog.probeDefinitions());
        for (int i = 0; i < defs.size(); i++) {
            var d = defs.get(i);
            consumptionNames.put(
                    d.id(),
                    i < 9 ? canonical[i] : i == 9 ? "I5ReadPotProbe" : "I5ReadCompletionProbe");
        }
    }

    public synchronized void bindRegistry(MeterRegistry registry) {
        if (registry == null) return;
        this.registry = registry;
    }

    private void recordTimer(String name, Duration elapsed, Tags tags) {
        var reg = this.registry;
        if (reg != null && elapsed != null && !elapsed.isNegative()) {
            Timer.builder("pocoma.v3." + name)
                    .tags(tags)
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(HISTOGRAM_BUCKETS)
                    .register(reg)
                    .record(elapsed);
        }
    }

    private boolean isCommand(ConsumptionDefinition definition) {
        return definition != null && (
                definition.family() == com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily.COMMAND
                || "Command".equals(consumptionNames.get(definition.id())));
    }

    // T0a: Discovery discovers candidate command
    @Override
    public void onCandidateDiscovered(ConsumptionDefinition definition, int partition, UUID id, long nanos) {
        if (id == null || !isCommand(definition)) return;
        long epoch = System.currentTimeMillis();
        var rec = commands.computeIfAbsent(id, k -> {
            evictIfNeeded();
            ring.add(k);
            return new CommandRecord(k);
        });
        if (rec.t0aNanos == 0) {
            rec.t0aNanos = nanos;
            rec.t0aEpochMs = epoch;
            rec.partition = partition;
            appendLogLine(
                    String.format(
                            Locale.ROOT,
                            "{\"stage\":\"T0a\",\"commandId\":\"%s\",\"partition\":%d,\"t0aEpochMs\":%d}",
                            id,
                            partition,
                            epoch));
        }
    }

    // T0b: Slot durably admitted / persisted in consumption_slot
    @Override
    public void onSlotAdmitted(ConsumptionDefinition definition, int partition, UUID id, long nanos) {
        if (id == null || !isCommand(definition)) return;
        long epoch = System.currentTimeMillis();
        var rec = commands.computeIfAbsent(id, k -> {
            evictIfNeeded();
            ring.add(k);
            return new CommandRecord(k);
        });
        if (rec.t0bNanos == 0) {
            rec.t0bNanos = nanos;
            rec.t0bEpochMs = epoch;
            if (rec.partition == null) rec.partition = partition;
            if (rec.t0aNanos > 0 && nanos >= rec.t0aNanos) {
                rec.discoveryToSlotMs =
                        Math.round(((nanos - rec.t0aNanos) / 1_000_000.0) * 100.0) / 100.0;
            }
            appendLogLine(
                    String.format(
                            Locale.ROOT,
                            "{\"stage\":\"T0b\",\"commandId\":\"%s\",\"partition\":%d,\"t0bEpochMs\":%d,\"discoveryToSlotMs\":%s}",
                            id,
                            partition,
                            epoch,
                            rec.discoveryToSlotMs != null ? rec.discoveryToSlotMs : "null"));
        }
    }

    // T0c: Slot acquired by worker
    @Override
    public void onSlotAcquired(ConsumptionId id, UUID consumableId, int partition, long nanos) {
        if (consumableId == null) return;
        String name = consumptionNames.getOrDefault(id, "unknown");
        if ("Command".equals(name)) {
            long epoch = System.currentTimeMillis();
            var rec = commands.computeIfAbsent(consumableId, k -> {
                evictIfNeeded();
                ring.add(k);
                return new CommandRecord(k);
            });
            if (rec.t0cNanos == 0) {
                rec.t0cNanos = nanos;
                rec.t0cEpochMs = epoch;
                if (rec.partition == null) rec.partition = partition;
                if (rec.t0bNanos > 0 && nanos >= rec.t0bNanos) {
                    rec.slotAcquisitionWaitMs =
                            Math.round(((nanos - rec.t0bNanos) / 1_000_000.0) * 100.0) / 100.0;
                }
                appendLogLine(
                        String.format(
                                Locale.ROOT,
                                "{\"stage\":\"T0c\",\"commandId\":\"%s\",\"partition\":%d,\"t0cEpochMs\":%d,\"slotAcquisitionWaitMs\":%s}",
                                consumableId,
                                partition,
                                epoch,
                                rec.slotAcquisitionWaitMs != null ? rec.slotAcquisitionWaitMs : "null"));
            }
        }
    }

    @FunctionalInterface
    public interface WakeSignal {
        void signal();
    }

    private volatile WakeSignal wakeSignal = null;

    public void registerWakeSignal(WakeSignal signal) {
        this.wakeSignal = signal;
    }

    // T0: Admission confirmed
    public void onCommandAdmitted(UUID commandId) {
        if (commandId == null) return;
        long nanos = System.nanoTime();
        long epoch = System.currentTimeMillis();
        var rec = commands.computeIfAbsent(commandId, k -> {
            evictIfNeeded();
            ring.add(k);
            return new CommandRecord(k);
        });
        rec.t0Nanos = nanos;
        rec.t0EpochMs = epoch;
        appendLogLine(
                String.format(
                        Locale.ROOT,
                        "{\"stage\":\"T0\",\"commandId\":\"%s\",\"t0EpochMs\":%d}",
                        commandId,
                        epoch));

        WakeSignal signal = this.wakeSignal;
        if (signal != null) {
            try {
                signal.signal();
            } catch (Throwable ignored) {
            }
        }
    }

    // Linkage from JdbcPotCommandStore.publish(...)
    public void linkCommandEvents(
            UUID commandId,
            UUID potId,
            Long resultingVersion,
            UUID potEventId,
            UUID completionEventId) {
        if (commandId == null) return;
        var rec = commands.computeIfAbsent(commandId, k -> {
            evictIfNeeded();
            ring.add(k);
            return new CommandRecord(k);
        });
        rec.potId = potId;
        rec.resultingVersion = resultingVersion;
        rec.potEventId = potEventId;
        rec.completionEventId = completionEventId;

        if (potEventId != null) {
            eventToCommand.put(potEventId, commandId);
        }
        if (completionEventId != null) {
            eventToCommand.put(completionEventId, commandId);
        }
        appendLogLine(
                String.format(
                        Locale.ROOT,
                        "{\"stage\":\"LINK\",\"commandId\":\"%s\",\"potId\":%s,\"resultingVersion\":%s,\"potEventId\":%s,\"completionEventId\":%s}",
                        commandId,
                        potId != null ? "\"" + potId + "\"" : "null",
                        resultingVersion != null ? resultingVersion : "null",
                        potEventId != null ? "\"" + potEventId + "\"" : "null",
                        completionEventId != null ? "\"" + completionEventId + "\"" : "null"));
    }

    // T1 / T3: Worker starts attempt
    @Override
    public void onAttemptStarting(ConsumptionId id, UUID consumableId, long startNanos) {
        if (id == null || consumableId == null) return;
        String name = consumptionNames.getOrDefault(id, "unknown");
        long epoch = System.currentTimeMillis();

        if ("Command".equals(name)) {
            // T1: Command worker starts attempt
            var rec = commands.computeIfAbsent(consumableId, k -> {
                evictIfNeeded();
                ring.add(k);
                return new CommandRecord(k);
            });
            rec.attempts++;
            if (rec.t1Nanos == 0) {
                rec.t1Nanos = startNanos;
                rec.t1EpochMs = epoch;
                if (rec.t0aNanos > 0 && startNanos >= rec.t0aNanos) {
                    rec.t0aToT1Ms =
                            Math.round(((startNanos - rec.t0aNanos) / 1_000_000.0) * 100.0) / 100.0;
                }
                if (rec.t0bNanos >= rec.t0aNanos && rec.t0aNanos > 0) {
                    rec.discoveryToSlotMs =
                            Math.round(((rec.t0bNanos - rec.t0aNanos) / 1_000_000.0) * 100.0) / 100.0;
                }
                if (rec.t0cNanos >= rec.t0bNanos && rec.t0bNanos > 0) {
                    rec.slotAcquisitionWaitMs =
                            Math.round(((rec.t0cNanos - rec.t0bNanos) / 1_000_000.0) * 100.0) / 100.0;
                }
                if (startNanos >= rec.t0cNanos && rec.t0cNanos > 0) {
                    rec.acquisitionToAttemptMs =
                            Math.round(((startNanos - rec.t0cNanos) / 1_000_000.0) * 100.0) / 100.0;
                }
                if (rec.t0Nanos > 0 && startNanos >= rec.t0Nanos) {
                    long waitNanos = startNanos - rec.t0Nanos;
                    rec.commandWaitMs = Math.round((waitNanos / 1_000_000.0) * 100.0) / 100.0;
                    recordTimer(
                            "handoff.wait",
                            Duration.ofNanos(waitNanos),
                            Tags.of("phase", "command", "consumption", "Command"));

                    // Sub-interval A1 (same JVM only)
                    if (rec.t0aNanos >= rec.t0Nanos) {
                        rec.waitBeforeDiscoveryMs =
                                Math.round(((rec.t0aNanos - rec.t0Nanos) / 1_000_000.0) * 100.0) / 100.0;
                    }
                }
                appendLogLine(
                        String.format(
                                Locale.ROOT,
                                "{\"stage\":\"T1\",\"commandId\":\"%s\",\"partition\":%s,\"t1EpochMs\":%d,\"t0aToT1Ms\":%s,\"discoveryToSlotMs\":%s,\"slotAcquisitionWaitMs\":%s,\"acquisitionToAttemptMs\":%s}",
                                consumableId,
                                rec.partition != null ? rec.partition : "null",
                                epoch,
                                rec.t0aToT1Ms != null ? rec.t0aToT1Ms : "null",
                                rec.discoveryToSlotMs != null ? rec.discoveryToSlotMs : "null",
                                rec.slotAcquisitionWaitMs != null ? rec.slotAcquisitionWaitMs : "null",
                                rec.acquisitionToAttemptMs != null ? rec.acquisitionToAttemptMs : "null"));
            }
        } else {
            // T3: Read projection starts attempt
            var eventProjMap =
                    eventProjections.computeIfAbsent(consumableId, k -> new ConcurrentHashMap<>());
            var eventProj = eventProjMap.computeIfAbsent(name, ProjectionRecord::new);
            if (eventProj.t3Nanos == 0) {
                eventProj.t3Nanos = startNanos;
                eventProj.t3EpochMs = epoch;
            }
            UUID commandId = eventToCommand.get(consumableId);
            if (commandId != null) {
                var rec = commands.get(commandId);
                if (rec != null) {
                    var proj = rec.projections.computeIfAbsent(name, ProjectionRecord::new);
                    proj.t3Nanos = startNanos;
                    proj.t3EpochMs = epoch;
                    if (rec.t2Nanos > 0 && startNanos >= rec.t2Nanos) {
                        long waitNanos = startNanos - rec.t2Nanos;
                        proj.readWaitMs = Math.round((waitNanos / 1_000_000.0) * 100.0) / 100.0;
                        eventProj.readWaitMs = proj.readWaitMs;
                        recordTimer(
                                "handoff.wait",
                                Duration.ofNanos(waitNanos),
                                Tags.of("phase", "read", "consumption", name));
                    }
                }
            }
            appendLogLine(
                    String.format(
                            Locale.ROOT,
                            "{\"stage\":\"T3\",\"eventId\":\"%s\",\"consumption\":\"%s\",\"t3EpochMs\":%d}",
                            consumableId,
                            name,
                            epoch));
        }
    }

    // T2 / T4: Worker finishes attempt
    @Override
    public void onAttemptFinished(
            ConsumptionId id,
            UUID consumableId,
            ConsumptionProtocol.Status status,
            long finishNanos) {
        if (id == null || consumableId == null) return;
        String name = consumptionNames.getOrDefault(id, "unknown");
        long epoch = System.currentTimeMillis();

        if ("Command".equals(name)) {
            // T2: Command write transaction commit confirmed
            var rec = commands.get(consumableId);
            if (rec != null) {
                rec.terminalStatus = status;
                if (status == ConsumptionProtocol.Status.COMMITTED && rec.t2Nanos == 0) {
                    rec.t2Nanos = finishNanos;
                    rec.t2EpochMs = epoch;
                    if (rec.t1Nanos > 0 && finishNanos >= rec.t1Nanos) {
                        long procNanos = finishNanos - rec.t1Nanos;
                        rec.commandProcessingMs = Math.round((procNanos / 1_000_000.0) * 100.0) / 100.0;
                        recordTimer(
                                "handoff.processing",
                                Duration.ofNanos(procNanos),
                                Tags.of("phase", "command", "consumption", "Command"));
                    }
                    appendLogLine(
                            String.format(
                                    Locale.ROOT,
                                    "{\"stage\":\"T2\",\"commandId\":\"%s\",\"t2EpochMs\":%d,\"t1ToT2Ms\":%s,\"potId\":%s,\"resultingVersion\":%s,\"potEventId\":%s,\"completionEventId\":%s}",
                                    consumableId,
                                    epoch,
                                    rec.commandProcessingMs != null ? rec.commandProcessingMs : "null",
                                    rec.potId != null ? "\"" + rec.potId + "\"" : "null",
                                    rec.resultingVersion != null ? rec.resultingVersion : "null",
                                    rec.potEventId != null ? "\"" + rec.potEventId + "\"" : "null",
                                    rec.completionEventId != null ? "\"" + rec.completionEventId + "\"" : "null"));
                }
            }
        } else {
            // T4: Read projection write transaction commit confirmed
            if (status != ConsumptionProtocol.Status.COMMITTED) return;
            Double procMs = null;
            var eventProjMap =
                    eventProjections.computeIfAbsent(consumableId, k -> new ConcurrentHashMap<>());
            var eventProj = eventProjMap.computeIfAbsent(name, ProjectionRecord::new);
            eventProj.t4Nanos = finishNanos;
            eventProj.t4EpochMs = epoch;
            if (eventProj.t3Nanos > 0 && finishNanos >= eventProj.t3Nanos) {
                long procNanos = finishNanos - eventProj.t3Nanos;
                procMs = Math.round((procNanos / 1_000_000.0) * 100.0) / 100.0;
                eventProj.readProcessingMs = procMs;
                recordTimer(
                        "handoff.processing",
                        Duration.ofNanos(procNanos),
                        Tags.of("phase", "read", "consumption", name));
            }
            UUID commandId = eventToCommand.get(consumableId);
            if (commandId != null) {
                var rec = commands.get(commandId);
                if (rec != null) {
                    var proj = rec.projections.computeIfAbsent(name, ProjectionRecord::new);
                    proj.t4Nanos = finishNanos;
                    proj.t4EpochMs = epoch;
                    if (proj.t3Nanos > 0 && finishNanos >= proj.t3Nanos) {
                        long procNanos = finishNanos - proj.t3Nanos;
                        proj.readProcessingMs = Math.round((procNanos / 1_000_000.0) * 100.0) / 100.0;
                    }
                    checkC03Ready(rec);
                }
            }
            appendLogLine(
                    String.format(
                            Locale.ROOT,
                            "{\"stage\":\"T4\",\"eventId\":\"%s\",\"consumption\":\"%s\",\"t4EpochMs\":%d,\"t3ToT4Ms\":%s}",
                            consumableId,
                            name,
                            epoch,
                            procMs != null ? procMs : "null"));
        }
    }

    private void checkC03Ready(CommandRecord rec) {
        var pv = rec.projections.get("PotVersion");
        if (pv == null) pv = rec.projections.get("MaterializePotVersion");
        var pb = rec.projections.get("PotBalance");
        if (pb == null) pb = rec.projections.get("MaterializePotBalance");
        if (pv != null && pv.t4EpochMs > 0 && pb != null && pb.t4EpochMs > 0) {
            long maxT4 = Math.max(pv.t4EpochMs, pb.t4EpochMs);
            rec.c03ReadyEpochMs = maxT4;
            if (rec.t2EpochMs > 0 && maxT4 >= rec.t2EpochMs) {
                rec.c03ReadWaitMs = (double) (maxT4 - rec.t2EpochMs);
            }
        }
    }

    private void evictIfNeeded() {
        while (ring.size() > MAX_TRACKED_COMMANDS) {
            UUID evicted = ring.pollFirst();
            if (evicted != null) {
                var rec = commands.remove(evicted);
                if (rec != null) {
                    if (rec.potEventId != null) {
                        eventToCommand.remove(rec.potEventId);
                        eventProjections.remove(rec.potEventId);
                    }
                    if (rec.completionEventId != null) {
                        eventToCommand.remove(rec.completionEventId);
                        eventProjections.remove(rec.completionEventId);
                    }
                }
            }
        }
    }

    public void clear() {
        ring.clear();
        commands.clear();
        eventToCommand.clear();
        eventProjections.clear();
        try {
            java.nio.file.Files.deleteIfExists(HANDOFF_LOG_PATH);
        } catch (Throwable ignored) {
        }
    }

    public Map<String, Object> snapshot(int limit, Long sinceEpochMs) {
        return snapshot(limit, sinceEpochMs, true);
    }

    public Map<String, Object> snapshot(int limit, Long sinceEpochMs, boolean includeBusinessIds) {
        var result = new LinkedHashMap<String, Object>();
        var list = new ArrayList<Map<String, Object>>();
        int count = 0;
        int max = limit > 0 ? limit : 2000;

        var partitionStats = new LinkedHashMap<String, Map<String, Object>>();
        for (int p = 0; p < 4; p++) {
            var pMap = new LinkedHashMap<String, Object>();
            pMap.put("partition", p);
            pMap.put("totalTracked", 0);
            pMap.put("discovered", 0);
            pMap.put("slotPersisted", 0);
            pMap.put("slotAcquired", 0);
            pMap.put("attemptStarted", 0);
            pMap.put("attemptFinished", 0);
            partitionStats.put("partition_" + p, pMap);
        }

        for (UUID cid : ring) {
            var rec = commands.get(cid);
            if (rec != null) {
                if (sinceEpochMs != null && sinceEpochMs > 0 && rec.t0EpochMs > 0 && rec.t0EpochMs < sinceEpochMs) {
                    continue;
                }
                if (rec.partition != null && rec.partition >= 0 && rec.partition < 4) {
                    var pMap = partitionStats.get("partition_" + rec.partition);
                    if (pMap != null) {
                        pMap.put("totalTracked", (int) pMap.get("totalTracked") + 1);
                        if (rec.t0aNanos > 0) pMap.put("discovered", (int) pMap.get("discovered") + 1);
                        if (rec.t0bNanos > 0) pMap.put("slotPersisted", (int) pMap.get("slotPersisted") + 1);
                        if (rec.t0cNanos > 0) pMap.put("slotAcquired", (int) pMap.get("slotAcquired") + 1);
                        if (rec.t1Nanos > 0) pMap.put("attemptStarted", (int) pMap.get("attemptStarted") + 1);
                        if (rec.t2Nanos > 0) pMap.put("attemptFinished", (int) pMap.get("attemptFinished") + 1);
                    }
                }
                list.add(rec.toMap(includeBusinessIds));
                count++;
                if (count >= max) break;
            }
        }

        var exportedEventProjections = new LinkedHashMap<String, Map<String, Object>>();
        if (includeBusinessIds) {
            synchronized (eventProjections) {
                for (var entry : eventProjections.entrySet()) {
                    var projMap = new LinkedHashMap<String, Object>();
                    for (var projection : entry.getValue().entrySet()) {
                        projMap.put(projection.getKey(), projection.getValue().toMap());
                    }
                    exportedEventProjections.put(entry.getKey().toString(), projMap);
                    if (exportedEventProjections.size() >= max) break;
                }
            }
        }

        result.put("totalTracked", ring.size());
        result.put("returned", list.size());
        result.put("partitionStats", partitionStats);
        result.put("records", list);
        result.put("eventProjections", exportedEventProjections);
        return result;
    }
}
