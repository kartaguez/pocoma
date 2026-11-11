package com.kartaguez.pocoma.orchestrator.consumption.v3;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;

/** Explicit definition selection by a Compose, before the generic polling channel is constructed. */
public final class ConsumptionProtocols {
    private ConsumptionProtocols() {}
    public static ConsumptionProtocol forDefinition(ConsumptionDefinition definition,
            ConsumptionProtocol existing, DiscoveryEngine discovery) {
        if (!(existing instanceof AbstractConsumptionProtocol p)) throw new IllegalArgumentException("foreign provider");
        return forDefinition(definition, p.engine, discovery);
    }
    public static ConsumptionProtocol forDefinition(ConsumptionDefinition definition,
            ConsumptionEngine engine, DiscoveryEngine discovery) {
        return switch (definition.family()) {
            case COMMAND, IDENTITY_REQUEST -> new BurnableConsumptionProtocol(definition, engine, discovery);
            default -> new NonBurnableConsumptionProtocol(definition, engine, discovery);
        };
    }
}
