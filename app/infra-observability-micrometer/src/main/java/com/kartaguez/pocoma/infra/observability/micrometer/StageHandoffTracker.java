package com.kartaguez.pocoma.infra.observability.micrometer;
import com.kartaguez.pocoma.contract.consumption.observation.AttemptObservation;
import com.kartaguez.pocoma.contract.consumption.observation.DiscoveryListener;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;

import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;

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
                putBusinessIds(m);
            }
            putTimestamps(m);
            putDurations(m);
            putProjections(m);
            putC03Readiness(m);
            return m;
        }

        private void putBusinessIds(Map<String, Object> m) {
            m.put("commandId", commandId.toString());
            m.put("potId", uuidStringOrNull(potId));
            m.put("resultingVersion", resultingVersion);
            m.put("potEventId", uuidStringOrNull(potEventId));
            m.put("completionEventId", uuidStringOrNull(completionEventId));
        }

        private void putTimestamps(Map<String, Object> m) {
            m.put("partition", partition);
            m.put("attempts", attempts);
            m.put("status", resolveStatus());
            m.put("missingDataReason", missingDataReason());
            m.put("t0EpochMs", positiveOrNull(t0EpochMs));
            m.put("t0aEpochMs", positiveOrNull(t0aEpochMs));
            m.put("t0bEpochMs", positiveOrNull(t0bEpochMs));
            m.put("t0cEpochMs", positiveOrNull(t0cEpochMs));
            m.put("t1EpochMs", positiveOrNull(t1EpochMs));
            m.put("t2EpochMs", positiveOrNull(t2EpochMs));
        }

        private void putDurations(Map<String, Object> m) {
            m.put("commandWaitMs", commandWaitMs);
            m.put("waitBeforeDiscoveryMs", waitBeforeDiscoveryMs);
            m.put("discoveryToSlotMs", discoveryToSlotMs);
            m.put("slotAcquisitionWaitMs", slotAcquisitionWaitMs);
            m.put("acquisitionToAttemptMs", acquisitionToAttemptMs);
            m.put("t0aToT1Ms", t0aToT1Ms);
            m.put("commandProcessingMs", commandProcessingMs);
            m.put("t1ToT2Ms", commandProcessingMs);
        }

        private void putProjections(Map<String, Object> m) {
            var projs = new LinkedHashMap<String, Object>();
            for (var entry : projections.entrySet()) {
                projs.put(entry.getKey(), entry.getValue().toMap());
            }
            m.put("projections", projs);
        }

        private void putC03Readiness(Map<String, Object> m) {
            var pv = projections.get("PotVersion");
            var pb = projections.get("PotBalance");
            if (!hasPositiveT4(pv) || !hasPositiveT4(pb)) {
                m.put("c03ReadyEpochMs", null);
                m.put("c03ReadWaitMs", null);
                return;
            }
            long c03Epoch = Math.max(pv.t4EpochMs, pb.t4EpochMs);
            m.put("c03ReadyEpochMs", c03Epoch);
            m.put("c03ReadWaitMs", t2EpochMs > 0 ? Math.max(0.0, (c03Epoch - t2EpochMs)) : null);
        }

        private String resolveStatus() {
            if (terminalStatus != null) return terminalStatus.name();
            return t1Nanos > 0 ? "EXECUTING" : "PENDING";
        }
    }

    private static boolean hasPositiveT4(ProjectionRecord record) {
        return record != null && record.t4EpochMs > 0;
    }

    private static Long positiveOrNull(long epochMs) {
        return epochMs > 0 ? epochMs : null;
    }

    private static String uuidStringOrNull(UUID id) {
        return id != null ? id.toString() : null;
    }

    private static Double elapsedMillisIfValid(long startNanos, long endNanos) {
        if (startNanos <= 0 || endNanos < startNanos) return null;
        return roundMillis(endNanos - startNanos);
    }

    private static double roundMillis(long nanos) {
        return Math.round((nanos / 1_000_000.0) * 100.0) / 100.0;
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
        var defs = new ArrayList<>(com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions());
        defs.addAll(com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.probeDefinitions());
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
        if (reg == null || elapsed == null) return;
        if (elapsed.isNegative()) return;
        Timer.builder("pocoma.v3." + name)
                .tags(tags)
                .publishPercentileHistogram()
                .serviceLevelObjectives(HISTOGRAM_BUCKETS)
                .register(reg)
                .record(elapsed);
    }

    private CommandRecord getOrCreateCommand(UUID commandId) {
        return commands.computeIfAbsent(
                commandId,
                k -> {
                    evictIfNeeded();
                    ring.add(k);
                    return new CommandRecord(k);
                });
    }

    private boolean isCommand(ConsumptionDefinition definition) {
        return definition != null && (
                definition.family() == com.kartaguez.pocoma.domain.consumption.ConsumableFamily.COMMAND
                || "Command".equals(consumptionNames.get(definition.id())));
    }

    // T0a: Discovery discovers candidate command
    @Override
    public void onCandidateDiscovered(ConsumptionDefinition definition, int partition, UUID id, long nanos) {
        if (id == null || !isCommand(definition)) return;
        long epoch = System.currentTimeMillis();
        var rec = getOrCreateCommand(id);
        if (rec.t0aNanos != 0) return;
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

    // T0b: Slot durably admitted / persisted in consumption_slot
    @Override
    public void onSlotAdmitted(ConsumptionDefinition definition, int partition, UUID id, long nanos) {
        if (id == null || !isCommand(definition)) return;
        long epoch = System.currentTimeMillis();
        var rec = getOrCreateCommand(id);
        if (rec.t0bNanos != 0) return;
        rec.t0bNanos = nanos;
        rec.t0bEpochMs = epoch;
        if (rec.partition == null) rec.partition = partition;
        rec.discoveryToSlotMs = elapsedMillisIfValid(rec.t0aNanos, nanos);
        appendLogLine(
                String.format(
                        Locale.ROOT,
                        "{\"stage\":\"T0b\",\"commandId\":\"%s\",\"partition\":%d,\"t0bEpochMs\":%d,\"discoveryToSlotMs\":%s}",
                        id,
                        partition,
                        epoch,
                        rec.discoveryToSlotMs != null ? rec.discoveryToSlotMs : "null"));
    }

    // T0c: Slot acquired by worker
    @Override
    public void onSlotAcquired(ConsumptionId id, UUID consumableId, int partition, long nanos) {
        if (consumableId == null) return;
        String name = consumptionNames.getOrDefault(id, "unknown");
        if (!"Command".equals(name)) return;
        long epoch = System.currentTimeMillis();
        var rec = getOrCreateCommand(consumableId);
        if (rec.t0cNanos != 0) return;
        rec.t0cNanos = nanos;
        rec.t0cEpochMs = epoch;
        if (rec.partition == null) rec.partition = partition;
        rec.slotAcquisitionWaitMs = elapsedMillisIfValid(rec.t0bNanos, nanos);
        appendLogLine(
                String.format(
                        Locale.ROOT,
                        "{\"stage\":\"T0c\",\"commandId\":\"%s\",\"partition\":%d,\"t0cEpochMs\":%d,\"slotAcquisitionWaitMs\":%s}",
                        consumableId,
                        partition,
                        epoch,
                        rec.slotAcquisitionWaitMs != null ? rec.slotAcquisitionWaitMs : "null"));
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
            recordCommandAttemptStart(consumableId, startNanos, epoch);
            return;
        }
        recordProjectionAttemptStart(name, consumableId, startNanos, epoch);
    }

    private void recordCommandAttemptStart(UUID commandId, long startNanos, long epoch) {
        var rec = getOrCreateCommand(commandId);
        rec.attempts++;
        if (rec.t1Nanos != 0) return;
        rec.t1Nanos = startNanos;
        rec.t1EpochMs = epoch;
        populateCommandStartDurations(rec, startNanos);
        logT1(commandId, rec, epoch);
    }

    private void populateCommandStartDurations(CommandRecord rec, long startNanos) {
        rec.t0aToT1Ms = elapsedMillisIfValid(rec.t0aNanos, startNanos);
        if (rec.t0bNanos >= rec.t0aNanos && rec.t0aNanos > 0) {
            rec.discoveryToSlotMs = roundMillis(rec.t0bNanos - rec.t0aNanos);
        }
        if (rec.t0cNanos >= rec.t0bNanos && rec.t0bNanos > 0) {
            rec.slotAcquisitionWaitMs = roundMillis(rec.t0cNanos - rec.t0bNanos);
        }
        rec.acquisitionToAttemptMs = elapsedMillisIfValid(rec.t0cNanos, startNanos);
        if (rec.t0Nanos > 0 && startNanos >= rec.t0Nanos) {
            long waitNanos = startNanos - rec.t0Nanos;
            rec.commandWaitMs = roundMillis(waitNanos);
            recordTimer(
                    "handoff.wait",
                    Duration.ofNanos(waitNanos),
                    Tags.of("phase", "command", "consumption", "Command"));
            rec.waitBeforeDiscoveryMs = elapsedMillisIfValid(rec.t0Nanos, rec.t0aNanos);
        }
    }

    private void logT1(UUID commandId, CommandRecord rec, long epoch) {
        appendLogLine(
                String.format(
                        Locale.ROOT,
                        "{\"stage\":\"T1\",\"commandId\":\"%s\",\"partition\":%s,\"t1EpochMs\":%d,\"t0aToT1Ms\":%s,\"discoveryToSlotMs\":%s,\"slotAcquisitionWaitMs\":%s,\"acquisitionToAttemptMs\":%s}",
                        commandId,
                        rec.partition != null ? rec.partition : "null",
                        epoch,
                        rec.t0aToT1Ms != null ? rec.t0aToT1Ms : "null",
                        rec.discoveryToSlotMs != null ? rec.discoveryToSlotMs : "null",
                        rec.slotAcquisitionWaitMs != null ? rec.slotAcquisitionWaitMs : "null",
                        rec.acquisitionToAttemptMs != null ? rec.acquisitionToAttemptMs : "null"));
    }

    private void recordProjectionAttemptStart(
            String name, UUID eventId, long startNanos, long epoch) {
        var eventProjMap = eventProjections.computeIfAbsent(eventId, k -> new ConcurrentHashMap<>());
        var eventProj = eventProjMap.computeIfAbsent(name, ProjectionRecord::new);
        if (eventProj.t3Nanos == 0) {
            eventProj.t3Nanos = startNanos;
            eventProj.t3EpochMs = epoch;
        }
        linkProjectionStartToCommand(name, eventId, startNanos, eventProj);
        appendLogLine(
                String.format(
                        Locale.ROOT,
                        "{\"stage\":\"T3\",\"eventId\":\"%s\",\"consumption\":\"%s\",\"t3EpochMs\":%d}",
                        eventId,
                        name,
                        epoch));
    }

    private void linkProjectionStartToCommand(
            String name, UUID eventId, long startNanos, ProjectionRecord eventProj) {
        UUID commandId = eventToCommand.get(eventId);
        if (commandId == null) return;
        var rec = commands.get(commandId);
        if (rec == null) return;
        var proj = rec.projections.computeIfAbsent(name, ProjectionRecord::new);
        proj.t3Nanos = startNanos;
        proj.t3EpochMs = eventProj.t3EpochMs;
        if (rec.t2Nanos > 0 && startNanos >= rec.t2Nanos) {
            long waitNanos = startNanos - rec.t2Nanos;
            proj.readWaitMs = roundMillis(waitNanos);
            eventProj.readWaitMs = proj.readWaitMs;
            recordTimer(
                    "handoff.wait",
                    Duration.ofNanos(waitNanos),
                    Tags.of("phase", "read", "consumption", name));
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
            recordCommandAttemptFinish(consumableId, status, finishNanos, epoch);
            return;
        }
        if (status != ConsumptionProtocol.Status.COMMITTED) return;
        recordProjectionAttemptFinish(name, consumableId, finishNanos, epoch);
    }

    private void recordCommandAttemptFinish(
            UUID commandId, ConsumptionProtocol.Status status, long finishNanos, long epoch) {
        var rec = commands.get(commandId);
        if (rec == null) return;
        rec.terminalStatus = status;
        if (status != ConsumptionProtocol.Status.COMMITTED || rec.t2Nanos != 0) return;
        rec.t2Nanos = finishNanos;
        rec.t2EpochMs = epoch;
        if (rec.t1Nanos > 0 && finishNanos >= rec.t1Nanos) {
            long procNanos = finishNanos - rec.t1Nanos;
            rec.commandProcessingMs = roundMillis(procNanos);
            recordTimer(
                    "handoff.processing",
                    Duration.ofNanos(procNanos),
                    Tags.of("phase", "command", "consumption", "Command"));
        }
        logT2(commandId, rec, epoch);
    }

    private void logT2(UUID commandId, CommandRecord rec, long epoch) {
        appendLogLine(
                String.format(
                        Locale.ROOT,
                        "{\"stage\":\"T2\",\"commandId\":\"%s\",\"t2EpochMs\":%d,\"t1ToT2Ms\":%s,\"potId\":%s,\"resultingVersion\":%s,\"potEventId\":%s,\"completionEventId\":%s}",
                        commandId,
                        epoch,
                        valueOrNullLiteral(rec.commandProcessingMs),
                        quotedUuidOrNullLiteral(rec.potId),
                        valueOrNullLiteral(rec.resultingVersion),
                        quotedUuidOrNullLiteral(rec.potEventId),
                        quotedUuidOrNullLiteral(rec.completionEventId)));
    }

    private static Object valueOrNullLiteral(Object value) {
        return value != null ? value : "null";
    }

    private static String quotedUuidOrNullLiteral(UUID id) {
        return id != null ? "\"" + id + "\"" : "null";
    }

    private void recordProjectionAttemptFinish(
            String name, UUID eventId, long finishNanos, long epoch) {
        var eventProjMap = eventProjections.computeIfAbsent(eventId, k -> new ConcurrentHashMap<>());
        var eventProj = eventProjMap.computeIfAbsent(name, ProjectionRecord::new);
        eventProj.t4Nanos = finishNanos;
        eventProj.t4EpochMs = epoch;
        Double procMs = elapsedMillisIfValid(eventProj.t3Nanos, finishNanos);
        if (procMs != null) {
            eventProj.readProcessingMs = procMs;
            recordTimer(
                    "handoff.processing",
                    Duration.ofNanos(finishNanos - eventProj.t3Nanos),
                    Tags.of("phase", "read", "consumption", name));
        }
        linkProjectionFinishToCommand(name, eventId, finishNanos, epoch);
        appendLogLine(
                String.format(
                        Locale.ROOT,
                        "{\"stage\":\"T4\",\"eventId\":\"%s\",\"consumption\":\"%s\",\"t4EpochMs\":%d,\"t3ToT4Ms\":%s}",
                        eventId,
                        name,
                        epoch,
                        procMs != null ? procMs : "null"));
    }

    private void linkProjectionFinishToCommand(
            String name, UUID eventId, long finishNanos, long epoch) {
        UUID commandId = eventToCommand.get(eventId);
        if (commandId == null) return;
        var rec = commands.get(commandId);
        if (rec == null) return;
        var proj = rec.projections.computeIfAbsent(name, ProjectionRecord::new);
        proj.t4Nanos = finishNanos;
        proj.t4EpochMs = epoch;
        proj.readProcessingMs = elapsedMillisIfValid(proj.t3Nanos, finishNanos);
        checkC03Ready(rec);
    }

    private void checkC03Ready(CommandRecord rec) {
        var pv = findProjection(rec, "PotVersion", "MaterializePotVersion");
        var pb = findProjection(rec, "PotBalance", "MaterializePotBalance");
        if (!hasPositiveT4(pv) || !hasPositiveT4(pb)) return;
        long maxT4 = Math.max(pv.t4EpochMs, pb.t4EpochMs);
        rec.c03ReadyEpochMs = maxT4;
        if (rec.t2EpochMs > 0 && maxT4 >= rec.t2EpochMs) {
            rec.c03ReadWaitMs = (double) (maxT4 - rec.t2EpochMs);
        }
    }

    private static ProjectionRecord findProjection(
            CommandRecord rec, String primary, String fallback) {
        var found = rec.projections.get(primary);
        return found != null ? found : rec.projections.get(fallback);
    }

    private void evictIfNeeded() {
        while (ring.size() > MAX_TRACKED_COMMANDS) {
            UUID evicted = ring.pollFirst();
            if (evicted == null) continue;
            var rec = commands.remove(evicted);
            if (rec != null) {
                removeEventMapping(rec.potEventId);
                removeEventMapping(rec.completionEventId);
            }
        }
    }

    private void removeEventMapping(UUID eventId) {
        if (eventId == null) return;
        eventToCommand.remove(eventId);
        eventProjections.remove(eventId);
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
        int max = limit > 0 ? limit : 2000;
        var partitionStats = initPartitionStats();
        var list = collectSnapshotRecords(max, sinceEpochMs, includeBusinessIds, partitionStats);
        var exportedEventProjections =
                includeBusinessIds ? exportEventProjections(max) : new LinkedHashMap<String, Map<String, Object>>();

        var result = new LinkedHashMap<String, Object>();
        result.put("totalTracked", ring.size());
        result.put("returned", list.size());
        result.put("partitionStats", partitionStats);
        result.put("records", list);
        result.put("eventProjections", exportedEventProjections);
        return result;
    }

    private static Map<String, Map<String, Object>> initPartitionStats() {
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
        return partitionStats;
    }

    private List<Map<String, Object>> collectSnapshotRecords(
            int max,
            Long sinceEpochMs,
            boolean includeBusinessIds,
            Map<String, Map<String, Object>> partitionStats) {
        var list = new ArrayList<Map<String, Object>>();
        for (UUID cid : ring) {
            var rec = commands.get(cid);
            if (rec == null || isBeforeSince(rec, sinceEpochMs)) continue;
            updatePartitionStats(partitionStats, rec);
            list.add(rec.toMap(includeBusinessIds));
            if (list.size() >= max) break;
        }
        return list;
    }

    private static boolean isBeforeSince(CommandRecord rec, Long sinceEpochMs) {
        if (sinceEpochMs == null || sinceEpochMs <= 0) return false;
        return rec.t0EpochMs > 0 && rec.t0EpochMs < sinceEpochMs;
    }

    private static void updatePartitionStats(
            Map<String, Map<String, Object>> partitionStats, CommandRecord rec) {
        if (rec.partition == null) return;
        if (rec.partition < 0 || rec.partition >= 4) return;
        var pMap = partitionStats.get("partition_" + rec.partition);
        if (pMap == null) return;
        incrementStat(pMap, "totalTracked");
        incrementIfPositive(pMap, "discovered", rec.t0aNanos);
        incrementIfPositive(pMap, "slotPersisted", rec.t0bNanos);
        incrementIfPositive(pMap, "slotAcquired", rec.t0cNanos);
        incrementIfPositive(pMap, "attemptStarted", rec.t1Nanos);
        incrementIfPositive(pMap, "attemptFinished", rec.t2Nanos);
    }

    private static void incrementIfPositive(Map<String, Object> pMap, String key, long nanos) {
        if (nanos > 0) incrementStat(pMap, key);
    }

    private static void incrementStat(Map<String, Object> pMap, String key) {
        pMap.put(key, (int) pMap.get(key) + 1);
    }

    private Map<String, Map<String, Object>> exportEventProjections(int max) {
        var exported = new LinkedHashMap<String, Map<String, Object>>();
        synchronized (eventProjections) {
            for (var entry : eventProjections.entrySet()) {
                var projMap = new LinkedHashMap<String, Object>();
                for (var projection : entry.getValue().entrySet()) {
                    projMap.put(projection.getKey(), projection.getValue().toMap());
                }
                exported.put(entry.getKey().toString(), projMap);
                if (exported.size() >= max) break;
            }
        }
        return exported;
    }
}
