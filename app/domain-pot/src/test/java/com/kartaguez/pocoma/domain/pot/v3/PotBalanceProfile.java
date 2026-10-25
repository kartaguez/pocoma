package com.kartaguez.pocoma.domain.pot.v3;

import com.kartaguez.pocoma.domain.pot.value.id.*;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Standalone reproducible local profile, deliberately outside correctness tests and SLOs. */
public final class PotBalanceProfile {
    static volatile Object retainedState, retainedResult;
    static final PotBalanceCalculator CALCULATOR = new PotBalanceCalculator();
    static final int WARMUP = 8, SAMPLES = 10;

    public static void main(String[] args) throws Exception {
        System.out.println(
                "jvm="
                        + System.getProperty("java.vm.name")
                        + " "
                        + System.getProperty("java.runtime.version"));
        System.out.println("options=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println(
                "warmup="
                        + WARMUP
                        + " samples="
                        + SAMPLES
                        + " seed=731; heap=MemoryMXBean after explicit full GC; bytes=thread"
                        + " cumulative allocations");
        for (int[] profile :
                List.of(new int[] {5, 20}, new int[] {20, 200}, new int[] {100, 2000})) {
            retainedState = null;
            retainedResult = null;
            long emptyHeap = liveHeap();
            var state = state(profile[0], profile[1]);
            retainedState = state;
            for (int i = 0; i < WARMUP; i++)
                retainedResult = CALCULATOR.calculate(state).orElseThrow();
            retainedResult = null;
            long inputHeap = liveHeap();
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            bean.setThreadAllocatedMemoryEnabled(true);
            long tid = Thread.currentThread().threadId();
            long[] nanos = new long[SAMPLES], bytes = new long[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                long allocation = bean.getThreadAllocatedBytes(tid), start = System.nanoTime();
                retainedResult = CALCULATOR.calculate(state).orElseThrow();
                nanos[i] = System.nanoTime() - start;
                bytes[i] = bean.getThreadAllocatedBytes(tid) - allocation;
            }
            var result = (PotBalanceCalculator.Balances) retainedResult;
            if (!Fraction.ZERO.equals(
                    result.values().values().stream().reduce(Fraction.ZERO, Fraction::add)))
                throw new AssertionError("Nonzero sum");
            long resultHeap = liveHeap();
            System.out.println(
                    "shareholders="
                            + profile[0]
                            + " expenses="
                            + profile[1]
                            + " participations="
                            + (profile[0] * profile[1])
                            + " nanos="
                            + Arrays.toString(nanos)
                            + " allocatedBytes="
                            + Arrays.toString(bytes)
                            + " emptyHeap="
                            + emptyHeap
                            + " inputHeap="
                            + inputHeap
                            + " retainedResultHeap="
                            + resultHeap);
        }
    }

    static long liveHeap() throws Exception {
        long before =
                ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .mapToLong(b -> Math.max(0, b.getCollectionCount()))
                        .sum();
        System.gc();
        for (int i = 0; i < 40; i++) {
            Thread.sleep(25);
            long after =
                    ManagementFactory.getGarbageCollectorMXBeans().stream()
                            .mapToLong(b -> Math.max(0, b.getCollectionCount()))
                            .sum();
            if (after > before)
                return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        }
        throw new IllegalStateException("Explicit GC not observed");
    }

    static PotFinancialState state(int size, int count) {
        var p = new PotId(new UUID(1, 1));
        var random = new Random(731);
        var ss = new LinkedHashMap<ShareholderId, PotFinancialState.Shareholder>();
        for (int i = 1; i <= size; i++) {
            var id = new ShareholderId(new UUID(2, i));
            ss.put(
                    id,
                    new PotFinancialState.Shareholder(
                            new Versioned<>(1, 1, new ShareholderHeader(p, id, false)),
                            new Versioned<>(
                                    1,
                                    1,
                                    new ShareholderWeight(
                                            p,
                                            id,
                                            new Weight(
                                                    new Fraction(
                                                            1 + random.nextInt(9),
                                                            1 + random.nextInt(7)))))));
        }
        var ids = new ArrayList<>(ss.keySet());
        var es = new LinkedHashMap<ExpenseId, PotFinancialState.Expense>();
        for (int i = 1; i <= count; i++) {
            var id = new ExpenseId(new UUID(3, i));
            var participants = new LinkedHashMap<ShareholderId, Optional<Weight>>();
            for (int j = 0; j < ids.size(); j++)
                participants.put(
                        ids.get(j),
                        (j + 1) % 3 == 0
                                ? Optional.of(new Weight(new Fraction(j + 2, 7)))
                                : Optional.empty());
            var financials =
                    new ExpenseFinancials(
                            p, id, new Amount(new Fraction(i, 7)), ids.get(i % size), participants);
            es.put(
                    id,
                    new PotFinancialState.Expense(
                            new Versioned<>(1, 1, new ExpenseHeader(p, id, false)),
                            new Versioned<>(1, 1, financials)));
        }
        return new PotFinancialState(p, 1, new Versioned<>(1, 1, new PotHeader(p, false)), ss, es);
    }
}
