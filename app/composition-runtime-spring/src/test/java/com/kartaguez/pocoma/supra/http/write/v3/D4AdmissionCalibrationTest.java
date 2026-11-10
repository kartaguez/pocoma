package com.kartaguez.pocoma.supra.http.write.v3;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.HmacAdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionProtection;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.contract.writeadmission.CommandAdmissionInput;
import com.kartaguez.pocoma.contract.writeadmission.IdentityAdmissionInput;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;

import org.junit.jupiter.api.Test;

import java.lang.management.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

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

    static Map<String, Object> payload(String op) {
        return switch (op) {
            case "CreatePot", "UpdatePotDetails" -> Map.of("label", "Café");
            case "DeletePot" -> Map.of();
            case "AddShareholders" ->
                    Map.of(
                            "targets",
                            List.of(
                                    Map.of(
                                            "id",
                                            SID,
                                            "name",
                                            "A",
                                            "attachment",
                                            "SELF",
                                            "weight",
                                            fraction("2", "4"))));
            case "UpdateShareholderDetails" ->
                    Map.of(
                            "targets",
                            List.of(Map.of("id", SID, "name", "A", "attachment", "KEEP")));
            case "UpdateShareholderWeights" ->
                    Map.of("targets", List.of(Map.of("id", SID, "weight", fraction("1", "2"))));
            case "DeleteShareholder" -> Map.of("id", SID);
            case "CreateExpense" ->
                    Map.of(
                            "id",
                            EID,
                            "label",
                            "E",
                            "date",
                            "2026-11-04",
                            "amount",
                            fraction("10", "3"),
                            "payerId",
                            SID,
                            "participants",
                            List.of(participant(SID)));
            case "UpdateExpenseDetails" -> Map.of("id", EID, "label", "E", "date", "2026-11-04");
            case "UpdateExpenseFinancials" ->
                    Map.of(
                            "id",
                            EID,
                            "amount",
                            fraction("10", "3"),
                            "payerId",
                            SID,
                            "participants",
                            List.of(participant(SID)));
            case "DeleteExpense" -> Map.of("id", EID);
            default -> throw new AssertionError();
        };
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

    @Test
    void finiteInputCorpusCpuAllocationAndHeap() throws Exception {
        var transport = new V3AdmissionTransport(V3AdmissionTransport.Limits.initial());
        var json = new ObjectMapper();
        var corpus = new LinkedHashMap<String, Object>();
        for (var op :
                List.of(
                        "CreatePot",
                        "UpdatePotDetails",
                        "DeletePot",
                        "AddShareholders",
                        "UpdateShareholderDetails",
                        "UpdateShareholderWeights",
                        "DeleteShareholder",
                        "CreateExpense",
                        "UpdateExpenseDetails",
                        "UpdateExpenseFinancials",
                        "DeleteExpense")) corpus.put(op, input(op, payload(op)));
        corpus.put("Registration", Map.of("operation", "Registration"));
        corpus.put(
                "Unlink",
                Map.of(
                        "operation",
                        "Unlink",
                        "bindingToken",
                        "token",
                        "target",
                        Map.of("issuer", "issuer", "subject", "subject")));
        for (int count : List.of(1, 100, 101, 1000)) {
            var targets = new ArrayList<Object>();
            for (int n = 0; n < count; n++)
                targets.add(
                        Map.of(
                                "id",
                                new UUID(0, 100 + n).toString(),
                                "name",
                                "Équipe " + n,
                                "attachment",
                                "SELF",
                                "weight",
                                fraction("2", "3")));
            corpus.put("targets-" + count, input("AddShareholders", Map.of("targets", targets)));
        }
        corpus.put("text-4096-utf8", input("CreatePot", Map.of("label", "é".repeat(2048))));
        corpus.put("text-65536-utf8", input("CreatePot", Map.of("label", "é".repeat(32768))));
        // Consecutive Fibonacci numbers exercise Euclid rather than only powers of ten.
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
                                                "id",
                                                SID,
                                                "weight",
                                                fraction(a.toString(), b.toString()))))));
        var expensiveTargets = new ArrayList<Object>();
        for (int n = 0; n < 101; n++)
            expensiveTargets.add(
                    Map.of(
                            "id",
                            new UUID(0, 2000 + n).toString(),
                            "name",
                            "Exact " + n,
                            "attachment",
                            "SELF",
                            "weight",
                            fraction(a.toString(), b.toString())));
        corpus.put(
                "targets-101-rational-1024",
                input("AddShareholders", Map.of("targets", expensiveTargets)));
        corpus.put(
                "rational-4096-refused",
                input(
                        "UpdateShareholderWeights",
                        Map.of(
                                "targets",
                                List.of(
                                        Map.of(
                                                "id",
                                                SID,
                                                "weight",
                                                fraction("9".repeat(4096), "1"))))));
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        mx.setThreadAllocatedMemoryEnabled(true);
        var observations = new ArrayList<Map<String, Object>>();
        long start = System.nanoTime();
        Instant wall = Instant.now();
        var protection =
                new HmacAdmissionProtection("stable", new byte[32], "k", Map.of("k", new byte[32]));
        for (var e : corpus.entrySet()) {
            byte[] bytes = json.writeValueAsBytes(e.getValue());
            boolean refused = e.getKey().contains("refused") || e.getKey().startsWith("text-65536");
            int bound =
                    e.getKey().equals("Registration") || e.getKey().equals("Unlink")
                            ? 16384
                            : 262144;
            assertTrue(bytes.length <= bound, e.getKey());
            Runnable sample =
                    () -> {
                        Object raw = transport.parse(bytes);
                        byte[] canonical;
                        if (e.getKey().equals("Registration") || e.getKey().equals("Unlink")) {
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
                        assertEquals(32, protection.intent("k", 1, canonical).length);
                    };
            for (int n = 0; n < 5; n++) {
                if (refused) assertThrows(AdmissionFailure.class, sample::run);
                else sample.run();
            }
            long allocation = mx.getThreadAllocatedBytes(Thread.currentThread().threadId()),
                    cpu = mx.getCurrentThreadCpuTime(),
                    clock = System.nanoTime(),
                    maxHeap = 0;
            for (var pool : ManagementFactory.getMemoryPoolMXBeans())
                if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
            for (int n = 0; n < 30; n++) {
                if (refused) assertThrows(AdmissionFailure.class, sample::run);
                else sample.run();
                maxHeap =
                        Math.max(
                                maxHeap,
                                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            }
            long cpuNs = mx.getCurrentThreadCpuTime() - cpu,
                    elapsed = System.nanoTime() - clock,
                    allocated =
                            mx.getThreadAllocatedBytes(Thread.currentThread().threadId())
                                    - allocation;
            var r = new LinkedHashMap<String, Object>();
            r.put("profile", e.getKey());
            r.put("utf8Bytes", bytes.length);
            r.put("accepted", !refused);
            r.put("warmups", 5);
            r.put("samples", 30);
            r.put("cpuNs", cpuNs);
            r.put("elapsedNs", elapsed);
            r.put("allocatedBytesCumulative", allocated);
            r.put("heapUsedSampledMax", maxHeap);
            r.put("heapMax", Runtime.getRuntime().maxMemory());
            r.put("jsonDepth", depth(json.readTree(bytes)));
            observations.add(r);
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
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
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
