package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.consumption.v3.ClaimedSlot;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.JdbcConsumptionSlotStore;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.*;

import javax.sql.DataSource;

/** Historical retry stimuli ONLY. This interface and its SQL never enter production. */
interface L1CommandStore extends PotCommandStore {
    enum FailureRecorded {
        RETRY_SCHEDULED,
        QUARANTINED,
        CLAIM_LOST
    }

    DataSource source();

    boolean protectClaim(UUID request, UUID token);

    FailureRecorded recordFailure(UUID request, UUID token, int maximum, Duration delay);

    final class Fixture implements L1CommandStore {
        private final DataSource source;
        private final PotCommandStore store;
        private final JdbcConsumptionSlotStore slots;
        private final JdbcTemplate jdbc;

        Fixture(DataSource source, PotCommandStore store) {
            this.source = source;
            this.store = store;
            slots = new JdbcConsumptionSlotStore(source);
            jdbc = new JdbcTemplate(source);
        }

        public DataSource source() {
            return source;
        }

        public Stored load(UUID id) {
            return store.load(id);
        }

        public long currentVersion(PotId id) {
            return store.currentVersion(id);
        }

        public boolean protectPot(PotId id, long version) {
            return store.protectPot(id, version);
        }

        public void finishTerminal(PotCommand command, Result result) {
            store.finishTerminal(command, result);
        }

        public void requireCompletionReady(PotCommand c, Result r, List<Component> changes) {
            store.requireCompletionReady(c, r, changes);
        }

        public void publish(PotCommand c, Result r, List<Component> changes) {
            store.publish(c, r, changes);
        }

        public boolean protectClaim(UUID request, UUID token) {
            return slots.protectFinal(claim(request, token))
                    == ConsumptionSlotStore.Protection.VALID;
        }

        public FailureRecorded recordFailure(
                UUID request, UUID token, int maximum, Duration delay) {
            // This fixture runs only AFTER a resolved failed attempt, in a separate test
            // transaction.
            var rows =
                    jdbc.queryForList(
                            "select * from consumption_slot where consumable_id=? for update",
                            request);
            if (rows.isEmpty()) return FailureRecorded.CLAIM_LOST;
            var row = rows.getFirst();
            var now =
                    jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class)
                            .toInstant();
            if (!token.equals(row.get("claim_token"))
                    || !"AVAILABLE".equals(row.get("state"))
                    || !((java.sql.Timestamp) row.get("claimed_until")).toInstant().isAfter(now))
                return FailureRecorded.CLAIM_LOST;
            var state =
                    jdbc.queryForObject(
                            "update consumption_slot set"
                                + " attempt_count=attempt_count+1,claim_token=null,claimed_until=null,state=case"
                                + " when attempt_count+1>=? then 'QUARANTINED' else 'AVAILABLE'"
                                + " end,retry_after=case when attempt_count+1>=? then null else"
                                + " clock_timestamp()+(? * interval '1 millisecond') end where"
                                + " consumable_id=? and claim_token=? returning state",
                            String.class,
                            maximum,
                            maximum,
                            delay.toMillis(),
                            request,
                            token);
            return "QUARANTINED".equals(state)
                    ? FailureRecorded.QUARANTINED
                    : FailureRecorded.RETRY_SCHEDULED;
        }
    }

    static ClaimedSlot claim(UUID request, UUID token) {
        return new ClaimedSlot(
                new ConsumptionId(UUID.fromString("98f7268d-d340-581c-b83d-010926223e14")),
                request,
                token);
    }
}
