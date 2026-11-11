package com.kartaguez.pocoma.infra.tx.spring.v3;
import com.kartaguez.pocoma.port.transaction.v3.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.Objects;
import java.util.function.Function;
/** Physical READ limits; quota and end-to-end deadline belong to the neutral execution policy. */
public final class SpringReadTransactions implements ReadTransactions {
    private final DataSource source;
    private final SpringAttemptTransactions owner;
    private final JdbcTemplate sql;
    private final boolean bounded;
    public SpringReadTransactions(DataSource source,SpringAttemptTransactions owner,boolean bounded) {
        this.source=Objects.requireNonNull(source); this.owner=Objects.requireNonNull(owner);
        owner.requireSource(source); this.sql=new JdbcTemplate(source); this.bounded=bounded;
    }
    public <T> T read(Function<RecoveryReadContext,T> call) {
        return owner.read(c -> {
            if(bounded) {
                var resource=TransactionSynchronizationManager.getResource(source);
                if(resource instanceof ConnectionHolder holder)
                    holder.setTimeoutInMillis(Math.min(2000,holder.hasTimeout()?holder.getTimeToLiveInMillis():2000));
                sql.execute("set local statement_timeout = '2s'");
                sql.execute("set local transaction_timeout = '3s'");
            }
            return call.apply(c);
        });
    }
}
