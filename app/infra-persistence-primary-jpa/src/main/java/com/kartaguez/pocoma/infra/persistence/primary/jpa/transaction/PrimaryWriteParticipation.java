package com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction;

import java.sql.SQLException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local participation check for CURRENT durable JDBC writers, including direct/new calls.
 * No transaction is opened here. Owner/phase certification will be added in 4D.
 * A Spring transaction for a different datasource is insufficient.
 */
public final class PrimaryWriteParticipation {
    private PrimaryWriteParticipation() {}

    public static void require(JdbcTemplate jdbc) {
        var dataSource = jdbc.getDataSource();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || dataSource == null
                || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder)) {
            throw new IllegalTransactionStateException("Durable PRIMARY write requires its current writable transaction");
        }
        try {
            if (holder.getConnection().getAutoCommit()) {
                throw new IllegalTransactionStateException("Durable PRIMARY write cannot autocommit");
            }
        } catch (SQLException exception) {
            throw new IllegalTransactionStateException("Cannot verify PRIMARY transaction participation", exception);
        }
    }
}
