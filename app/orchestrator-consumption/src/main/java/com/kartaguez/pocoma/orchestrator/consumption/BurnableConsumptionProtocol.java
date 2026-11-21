package com.kartaguez.pocoma.orchestrator.consumption;
import com.kartaguez.pocoma.contract.consumption.invocation.*;
import com.kartaguez.pocoma.domain.consumption.*;

public final class BurnableConsumptionProtocol extends AbstractConsumptionProtocol {
    public BurnableConsumptionProtocol(ConsumptionDefinition definition, ConsumptionEngine engine, DiscoveryEngine discovery) {
        super(definition, engine, discovery);
        if (definition.family() != ConsumableFamily.COMMAND && definition.family() != ConsumableFamily.IDENTITY_REQUEST)
            throw new IllegalArgumentException("Burnable definition required");
    }
    @Override
    public DiscoveryReports.Progress discover(Partition partition) {
        return discover(partition, true);
    }

    @Override
    public DiscoveryReports.Progress discover(Partition partition, boolean verifyOwnershipOnEmpty) {
        var progress = discovery.discoverRequests(requirePartition(partition), verifyOwnershipOnEmpty);
        return new DiscoveryReports.Progress(progress.status(), progress.checkpoint(), progress.examined(),
                progress.advanced(), progress.committedAdmissions());
    }
}
