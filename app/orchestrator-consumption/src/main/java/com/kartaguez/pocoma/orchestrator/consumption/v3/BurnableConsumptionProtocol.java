package com.kartaguez.pocoma.orchestrator.consumption.v3;
import com.kartaguez.pocoma.contract.consumption.invocation.*;
import com.kartaguez.pocoma.domain.consumption.v3.*;

public final class BurnableConsumptionProtocol extends AbstractConsumptionProtocol {
    public BurnableConsumptionProtocol(ConsumptionDefinition definition, ConsumptionEngine engine, DiscoveryEngine discovery) {
        super(definition, engine, discovery);
        if (definition.family() != ConsumableFamily.COMMAND && definition.family() != ConsumableFamily.IDENTITY_REQUEST)
            throw new IllegalArgumentException("Burnable definition required");
    }
    public DiscoveryReports.Progress discover(Partition partition) {
        var progress = discovery.discoverRequests(requirePartition(partition));
        return new DiscoveryReports.Progress(progress.status(), progress.checkpoint(), progress.examined(),
                progress.advanced(), progress.committedAdmissions());
    }
}
