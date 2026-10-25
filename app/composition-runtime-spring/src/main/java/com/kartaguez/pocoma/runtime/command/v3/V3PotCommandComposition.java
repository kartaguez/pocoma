package com.kartaguez.pocoma.runtime.command.v3;

import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotActorAuthority;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;

import org.springframework.orm.jpa.JpaTransactionManager;

import java.time.Clock;

import javax.sql.DataSource;

/** Explicit internal handoff for an already acquired V3 Command claim. No runtime activation. */
public final class V3PotCommandComposition {
    private V3PotCommandComposition() {}

    public static ExecutePotCommand assemble(
            DataSource writeSource,
            JpaTransactionManager manager,
            PotActorAuthority identityAuthority,
            ExecutePotCommand.RetryPolicy retryPolicy,
            Clock clock) {
        var transactions = new PotCommandJdbcTransactions(writeSource, manager);
        return new ExecutePotCommand(
                transactions,
                new JdbcPotCommandStore(writeSource, transactions),
                identityAuthority,
                new JdbcPotHistoryAdapter(writeSource),
                new JdbcPotRevisionAdapter(writeSource),
                retryPolicy,
                clock);
    }
}
