package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.engine.consumption.v3.ClaimedSlot;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.*;

import javax.sql.DataSource;

/** Canonical R1 slot adapter. No claim acquisition, renewal, cession or purge. */
public final class JdbcConsumptionSlotStore implements ConsumptionSlotStore {
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcConsumptionSlotStore(DataSource source) {
        this.source = Objects.requireNonNull(source);
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public Protection protectFinal(ClaimedSlot claim) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    var rows =
                            jdbc.queryForList(
                                    "select claim_token,claimed_until,state from consumption_slot"
                                        + " where consumption_id=? and consumable_id=? for update",
                                    claim.consumptionId().value(),
                                    claim.consumableId());
                    // A separate statement: PostgreSQL evaluates this only after the lock wait
                    // completed.
                    var now =
                            jdbc.queryForObject("select clock_timestamp()", Timestamp.class)
                                    .toInstant();
                    if (rows.isEmpty()) return Protection.ABSENT;
                    var row = rows.getFirst();
                    return claim.claimToken().equals(row.get("claim_token"))
                                    && "AVAILABLE".equals(row.get("state"))
                                    && row.get("claimed_until") instanceof Timestamp until
                                    && until.toInstant().isAfter(now)
                            ? Protection.VALID
                            : Protection.STALE_OR_EXPIRED;
                });
    }

    @Override
    public void deleteProtected(ClaimedSlot claim) {
        SpringAttemptTransactions.guarded(
                source,
                () -> {
                    // No deadline recheck: a claim valid at the final lock stays protected to
                    // resolution.
                    if (jdbc.update(
                                    "delete from consumption_slot where consumption_id=? and"
                                        + " consumable_id=? and claim_token=? and"
                                        + " state='AVAILABLE'",
                                    claim.consumptionId().value(),
                                    claim.consumableId(),
                                    claim.claimToken())
                            != 1)
                        throw new IllegalStateException(
                                "Protected canonical slot was not deleted exactly once");
                    return null;
                });
    }

    @Override
    public SlotObservation observe(
            ConsumptionId id, UUID consumableId, RecoveryReadContext context) {
        SpringAttemptTransactions.requireRead(source, context);
        return Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "select exists(select 1 from consumption_slot "
                                        + "where consumption_id=? and consumable_id=?)",
                                Boolean.class,
                                id.value(),
                                consumableId))
                ? SlotObservation.PRESENT
                : SlotObservation.ABSENT;
    }
}
