package com.kartaguez.pocoma.runtime.command.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily;
import com.kartaguez.pocoma.engine.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotActorAuthority;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionCatalogConfiguration;

import org.springframework.orm.jpa.JpaTransactionManager;

import java.time.Clock;

import javax.sql.DataSource;

/** Explicit factory only. I1 remains inert; no Engine bean, worker or polling is installed. */
public final class V3PotCommandComposition {
    private V3PotCommandComposition() {}

    public static ConsumptionEngine assemble(
            DataSource writeSource,
            JpaTransactionManager manager,
            PotActorAuthority identityAuthority,
            Clock clock) {
        var transactions = new SpringAttemptTransactions(writeSource, manager, 60);
        var participant =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(writeSource, transactions),
                        new JdbcPotCommandObservation(writeSource),
                        identityAuthority,
                        new JdbcPotHistoryAdapter(writeSource),
                        new JdbcPotRevisionAdapter(writeSource),
                        clock);
        var definition =
                V3ConsumptionCatalogConfiguration.definitions().stream()
                        .filter(d -> d.family() == ConsumableFamily.COMMAND)
                        .findFirst()
                        .orElseThrow();
        return new ConsumptionEngine(
                definition, transactions, new JdbcConsumptionSlotStore(writeSource), participant);
    }
}
