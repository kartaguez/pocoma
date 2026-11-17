package com.kartaguez.pocoma.supra.web.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.CommandAdmissionInput;
import com.kartaguez.pocoma.contract.writeadmission.IdentityAdmissionInput;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.infra.postgres.write.HmacAdmissionProtection;

/** Finite D4-A only: no admission service, JDBC, PostgreSQL, Spring or HTTP server. */
class D4AdmissionCalibrationTest {
    static final String POT = "00000000-0000-0000-0000-000000000001",
            SID = "00000000-0000-0000-0000-000000000002",
            EID = "00000000-0000-0000-0000-000000000003";
    static final PotCommand.Eui EUI = new PotCommand.Eui("issuer", "subject");

    static Map<String, Object> fraction(String a, String b) {
        return Map.of("numerator", a, "denominator", b);
    }

    static Map<String, Object> input(String op, Object payload) {
        return new LinkedHashMap<>(
                Map.of(
                        "operation",
                        op,
                        "potId",
                        POT,
                        "observedVersion",
                        op.equals("CreatePot") ? 0 : 1,
                        "bindingToken",
                        "binding-token",
                        "payload",
                        payload));
    }

    private static final Map<String, Map<String, Object>> OPERATION_PAYLOADS =
            Map.ofEntries(
                    Map.entry("CreatePot", Map.of("label", "Café")),
                    Map.entry("UpdatePotDetails", Map.of("label", "Café")),
                    Map.entry("DeletePot", Map.of()),
                    Map.entry(
                            "AddShareholders",
                            Map.of(
                                    "targets",
                                    List.of(
                                            Map.of(
                                                    "id", SID,
                                                    "name", "A",
                                                    "attachment", "SELF",
                                                    "weight", fraction("2", "4"))))),
                    Map.entry(
                            "UpdateShareholderDetails",
                            Map.of(
                                    "targets",
                                    List.of(
                                            Map.of("id", SID, "name", "A", "attachment", "KEEP")))),
                    Map.entry(
                            "UpdateShareholderWeights",
                            Map.of(
                                    "targets",
                                    List.of(Map.of("id", SID, "weight", fraction("1", "2"))))),
                    Map.entry("DeleteShareholder", Map.of("id", SID)),
                    Map.entry(
                            "CreateExpense",
                            Map.of(
                                    "id", EID,
                                    "label", "E",
                                    "date", "2026-11-04",
                                    "amount", fraction("10", "3"),
                                    "payerId", SID,
                                    "participants", List.of(participant(SID)))),
                    Map.entry(
                            "UpdateExpenseDetails",
                            Map.of("id", EID, "label", "E", "date", "2026-11-04")),
                    Map.entry(
                            "UpdateExpenseFinancials",
                            Map.of(
                                    "id", EID,
                                    "amount", fraction("10", "3"),
                                    "payerId", SID,
                                    "participants", List.of(participant(SID)))),
                    Map.entry("DeleteExpense", Map.of("id", EID)));

    static Map<String, Object> payload(String op) {
        Map<String, Object> result = OPERATION_PAYLOADS.get(op);
        if (result == null) {
            throw new AssertionError();
        }
        return result;
    }

    static Map<String, Object> participant(String id) {
        var p = new LinkedHashMap<String, Object>();
        p.put("id", id);
        p.put("weight", null);
        return p;
    }

    static CommandAdmissionInput decode(Object input) {
        return CommandAdmissionInput.decode(input, EUI, 1024);
    }

    private record CalibrationHarness(
            AdmissionTransport transport,
            ObjectMapper json,
            HmacAdmissionProtection protection,
            com.sun.management.ThreadMXBean mx) {}

    private static List<Object> buildShareholderTargets(
            int count, int idOffset, String prefix, Map<String, Object> weight) {
        var targets = new ArrayList<Object>(count);
        for (int n = 0; n < count; n++) {
            targets.add(
                    Map.of(
                            "id", new UUID(0, idOffset + n).toString(),
                            "name", prefix + n,
                            "attachment", "SELF",
                            "weight", weight));
        }
        return targets;
    }

    private static Map<String, Object> buildCalibrationCorpus() {
        var corpus = new LinkedHashMap<String, Object>();
        for (var op : OPERATION_PAYLOADS.keySet()) {
            corpus.put(op, input(op, payload(op)));
        }
        corpus.put("Registration", Map.of("operation", "Registration"));
        corpus.put(
                "Unlink",
                Map.of(
                        "operation", "Unlink",
                        "bindingToken", "token",
                        "target", Map.of("issuer", "issuer", "subject", "subject")));
        for (int count : List.of(1, 100, 101, 1000)) {
            corpus.put(
                    "targets-" + count,
                    input(
                            "AddShareholders",
                            Map.of(
                                    "targets",
                                    buildShareholderTargets(
                                            count, 100, "Équipe ", fraction("2", "3")))));
        }
        corpus.put("text-4096-utf8", input("CreatePot", Map.of("label", "é".repeat(2048))));
        corpus.put("text-65536-utf8", input("CreatePot", Map.of("label", "é".repeat(32768))));
        var a = java.math.BigInteger.ONE;
        var b = a;
        while (b.toString().length() < 1024) {
            var next = a.add(b);
            a = b;
            b = next;
        }
        corpus.put(
                "rational-1024-euclid",
                input(
                        "UpdateShareholderWeights",
                        Map.of(
                                "targets",
                                List.of(
                                        Map.of(
                                                "id", SID,
                                                "weight", fraction(a.toString(), b.toString()))))));
        corpus.put(
                "targets-101-rational-1024",
                input(
                        "AddShareholders",
                        Map.of(
                                "targets",
                                buildShareholderTargets(
                                        101,
                                        2000,
                                        "Exact ",
                                        fraction(a.toString(), b.toString())))));
        corpus.put(
                "rational-4096-refused",
                input(
                        "UpdateShareholderWeights",
                        Map.of(
                                "targets",
                                List.of(
                                        Map.of(
                                                "id", SID,
                                                "weight", fraction("9".repeat(4096), "1"))))));
        return corpus;
    }

    private static void executeSample(
            String profile, byte[] bytes, CalibrationHarness harness) {
        Object raw = harness.transport().parse(bytes);
        byte[] canonical;
        if (profile.equals("Registration") || profile.equals("Unlink")) {
            var i =
                    IdentityAdmissionInput.decode(
                            raw, new ExternalIdentity("issuer", "subject"));
            i.validateCurrent(4096);
            canonical = i.canonical(1);
        } else {
            var i = CommandAdmissionInput.decode(raw, EUI, 1024);
            i.validateCurrent(4096);
            canonical = i.canonical(1);
        }
        assertEquals(32, harness.protection().intent("k", 1, canonical).length);
    }

    private static void runIterations(
            Runnable sample, boolean refused, int iterations, boolean trackPeakHeap) {
        for (int n = 0; n < iterations; n++) {
            if (refused) {
                assertThrows(AdmissionFailure.class, sample::run);
            } else {
                sample.run();
            }
            if (trackPeakHeap) {
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            }
        }
    }

    private static Map<String, Object> measureProfile(
            String profile, Object value, CalibrationHarness harness) throws Exception {
        byte[] bytes = harness.json().writeValueAsBytes(value);
        boolean refused = profile.contains("refused") || profile.startsWith("text-65536");
        int bound = (profile.equals("Registration") || profile.equals("Unlink")) ? 16384 : 262144;
        assertTrue(bytes.length <= bound, profile);
        Runnable sample = () -> executeSample(profile, bytes, harness);
        runIterations(sample, refused, 5, false);
        long allocation = harness.mx().getThreadAllocatedBytes(Thread.currentThread().threadId());
        long cpu = harness.mx().getCurrentThreadCpuTime();
        long clock = System.nanoTime();
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
        long maxHeap = 0;
        for (int n = 0; n < 30; n++) {
            runIterations(sample, refused, 1, false);
            maxHeap =
                    Math.max(
                            maxHeap,
                            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        }
        var r = new LinkedHashMap<String, Object>();
        r.put("profile", profile);
        r.put("utf8Bytes", bytes.length);
        r.put("accepted", !refused);
        r.put("warmups", 5);
        r.put("samples", 30);
        r.put("cpuNs", harness.mx().getCurrentThreadCpuTime() - cpu);
        r.put("elapsedNs", System.nanoTime() - clock);
        r.put(
                "allocatedBytesCumulative",
                harness.mx().getThreadAllocatedBytes(Thread.currentThread().threadId())
                        - allocation);
        r.put("heapUsedSampledMax", maxHeap);
        r.put("heapMax", Runtime.getRuntime().maxMemory());
        r.put("jsonDepth", depth(harness.json().readTree(bytes)));
        return r;
    }

    @Test
    void finiteInputCorpusCpuAllocationAndHeap() throws Exception {
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        mx.setThreadAllocatedMemoryEnabled(true);
        var harness =
                new CalibrationHarness(
                        new AdmissionTransport(AdmissionTransport.Limits.initial()),
                        new ObjectMapper(),
                        new HmacAdmissionProtection("stable", new byte[32], "k", Map.of("k", new byte[32])),
                        mx);
        long start = System.nanoTime();
        Instant wall = Instant.now();
        var observations = new ArrayList<Map<String, Object>>();
        for (var e : buildCalibrationCorpus().entrySet()) {
            observations.add(measureProfile(e.getKey(), e.getValue(), harness));
        }
        var result =
                Map.of(
                        "protocol",
                        "D4-A input-only; actual heap cap recorded per sample; 5 warmups/30"
                            + " samples/profile; no SLO or percentile claim",
                        "startedAt",
                        wall.toString(),
                        "finishedAt",
                        Instant.now().toString(),
                        "monotonicNs",
                        System.nanoTime() - start,
                        "heapSampling",
                        "after each sample; misses transient peaks; cumulative allocation is not"
                                + " peak",
                        "profiles",
                        observations);
        var output =
                Path.of(System.getProperty("pocoma.verification.reports", "target/reports"))
                        .getParent()
                        .resolve("d4-input-measures.json");
        Files.createDirectories(output.getParent());
        Files.writeString(
                output, harness.json().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        assertTrue(
                observations.stream()
                        .anyMatch(
                                r ->
                                        r.get("profile").equals("targets-1000")
                                                && Boolean.TRUE.equals(r.get("accepted"))));
    }

    static int depth(com.fasterxml.jackson.databind.JsonNode n) {
        if (!n.isContainerNode()) return 0;
        int depth = 0;
        for (var child : n) depth = Math.max(depth, depth(child));
        return depth + 1;
    }
}
