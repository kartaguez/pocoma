package com.kartaguez.pocoma.runtime.command.v3;

import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.orchestrator.consumption.v3.*;

/** Privileged fixture inspection only. No test accessor is shipped in a production artifact. */
public final class L2TestAccess {
    private L2TestAccess() {}
    private static Object member(Object target, Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    public static ConsumptionEngine engine(Object value) {
        if (value instanceof ConsumptionEngine engine) return engine;
        return (ConsumptionEngine) member(value, AbstractConsumptionProtocol.class, "engine");
    }
    public static DiscoveryEngine discovery(Object value) {
        if (value instanceof DiscoveryEngine discovery) return discovery;
        return (DiscoveryEngine) member(value, AbstractConsumptionProtocol.class, "discovery");
    }
    public static Object unwrap(Object value) {
        if (value instanceof ConsumptionProtocol) return engine(value);
        if (value instanceof ConsumptionProtocol.Handle)
            return member(value, value.getClass(), "claim");
        if (value instanceof ConsumptionProtocol.Partition)
            return member(value, value.getClass(), "lease");
        return value;
    }
}
