package com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kartaguez.pocoma.port.transaction.EventFamily;

/** Dormant terminal suffix. Explicit composition only, no component/configuration.
 * Uses the JDBC holder exposed by the same PRIMARY JPA manager, never opens a
 * connection/transaction for sequencing. Mode is durable and read without a lock;
 * changing it requires the offline maintenance protocol, never a runtime switch.
 */
public final class JdbcEventTransactionSequencer implements PrimaryEventTransactionSuffix {
    private record Family(EventFamily family, String positions) {}
    private static final List<Family> ORDER = List.of(
            new Family(EventFamily.BUSINESS_EVENT, "business_event_positions"),
            new Family(EventFamily.COMMAND_TERMINAL_EVENT, "command_terminal_event_positions"),
            new Family(EventFamily.BINDING_FACT, "binding_fact_positions"),
            new Family(EventFamily.USER_CREATED_FACT, "user_created_fact_positions"));
    private final JpaTransactionManager manager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public JdbcEventTransactionSequencer(JpaTransactionManager manager) {
        this.manager = Objects.requireNonNull(manager);
        this.dataSource = Objects.requireNonNull(manager.getDataSource());
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Future startup composition must explicitly call this; never changes mode. */
    public String verifyCompatibleMode() {
        var rows = jdbc.queryForList("select protocol_version,mode from event_sequencing_protocol where singleton");
        if (rows.size() != 1 || !Integer.valueOf(1).equals(rows.getFirst().get("protocol_version")))
            throw new IllegalStateException("Missing/incompatible event sequencing protocol");
        String mode = (String) rows.getFirst().get("mode");
        if (!"PREPARED".equals(mode) && !"ACTIVE".equals(mode))
            throw new IllegalStateException("Unknown event sequencing mode");
        return mode;
    }

    @Override public void execute(ClosedPublishedEvents batch) {
        Objects.requireNonNull(batch);
        if (batch.isEmpty()) return; // No mode query, authority read/lock or association DML.
        Object em = TransactionSynchronizationManager.getResource(manager.getEntityManagerFactory());
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder)
                || !(em instanceof EntityManagerHolder holder)
                || !holder.getEntityManager().getTransaction().isActive())
            throw new IllegalTransactionStateException("Sequencing requires the current PRIMARY JPA transaction");
        if ("PREPARED".equals(verifyCompatibleMode())) return;
        for (Family family : ORDER) {
            var events = batch.inFamily(family.family());
            if (events.isEmpty()) continue;
            // SQL function is the privilege boundary, not a transaction boundary.
            Long end = jdbc.queryForObject("select reserve_event_positions(?,?)", Long.class,
                    family.family().name(), (long) events.size());
            if (end == null || end < events.size()) throw new IllegalStateException("Invalid event block");
            long position = end - events.size();
            for (var event : events) {
                if (jdbc.update("insert into " + family.positions() + "(event_id,position) values (?,?)",
                        event.eventId(), ++position) != 1)
                    throw new IllegalStateException("Association must insert exactly one row");
            }
        }
    }
}
