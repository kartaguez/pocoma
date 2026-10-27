package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.ClaimedSlot;
import com.kartaguez.pocoma.engine.consumption.v3.LifecycleSettings.RetryPolicy;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import javax.sql.DataSource;

/** Canonical slot SQL. No physical transaction ownership or purge entry point. */
public final class JdbcConsumptionSlotStore implements ConsumptionSlotStore {
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcConsumptionSlotStore(DataSource source) {
        this.source = Objects.requireNonNull(source);
        jdbc = new JdbcTemplate(source);
    }

    private Instant now() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    private List<Map<String, Object>> lockKeys(ConsumptionId id, List<UUID> keys) {
        return lockKeys(id, keys, null);
    }

    private List<Map<String, Object>> lockKeys(
            ConsumptionId id, List<UUID> keys, Integer partition) {
        if (keys.isEmpty()) return List.of();
        if (new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("duplicate keys");
        var args = new ArrayList<Object>();
        args.add(id.value());
        args.addAll(keys);
        if (partition != null) args.add(partition);
        return jdbc.queryForList(
                "select consumable_id,claim_token,claimed_until,state,partition_id from"
                        + " consumption_slot where consumption_id=? and consumable_id in ("
                        + String.join(",", Collections.nCopies(keys.size(), "?"))
                        + ")"
                        + (partition == null ? "" : " and partition_id=?")
                        + " order by consumable_id for update skip locked",
                args.toArray());
    }

    private GrantedClaim grant(ConsumptionId id, Map<String, Object> row) {
        return new GrantedClaim(
                new ClaimedSlot(id, (UUID) row.get("consumable_id"), (UUID) row.get("claim_token")),
                ((Timestamp) row.get("claimed_until")).toInstant());
    }

    @Override
    public List<GrantedClaim> acquire(
            PartitionLease lease, List<ClaimCandidate> candidates, Duration duration) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    long us = LifecycleSettings.micros(duration, false);
                    var rows =
                            lockKeys(
                                    lease.consumptionId(),
                                    candidates.stream().map(ClaimCandidate::consumableId).toList(),
                                    lease.partitionId());
                    var result = new ArrayList<GrantedClaim>();
                    for (var row : rows) {
                        var time = now(); // Includes any relation-lock wait in lockKeys.
                        if (!lease.ownedUntil().isAfter(time)) break;
                        var candidate =
                                candidates.stream()
                                        .filter(
                                                c ->
                                                        c.consumableId()
                                                                .equals(row.get("consumable_id")))
                                        .findFirst()
                                        .orElseThrow();
                        var updated =
                                jdbc.queryForList(
                                        "update consumption_slot set"
                                            + " claim_token=?,claimed_until=?::timestamptz + ? *"
                                            + " interval '1 microsecond' where consumption_id=? and"
                                            + " consumable_id=? and partition_id=? and"
                                            + " state='AVAILABLE' and (retry_after is null or"
                                            + " retry_after<=?) and (claim_token is null or"
                                            + " claimed_until<=?) returning"
                                            + " consumable_id,claim_token,claimed_until",
                                        candidate.freshToken(),
                                        Timestamp.from(time),
                                        us,
                                        lease.consumptionId().value(),
                                        candidate.consumableId(),
                                        lease.partitionId(),
                                        Timestamp.from(time),
                                        Timestamp.from(time));
                        updated.forEach(r -> result.add(grant(lease.consumptionId(), r)));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public List<GrantedClaim> renew(List<ClaimedSlot> claims, Duration duration) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    long us = LifecycleSettings.micros(duration, false);
                    var result = new ArrayList<GrantedClaim>();
                    // SQL UUID ordering, including across C; Java UUID.compareTo is signed.
                    var groups = new TreeMap<String, List<ClaimedSlot>>();
                    claims.forEach(
                            c ->
                                    groups.computeIfAbsent(
                                                    c.consumptionId().value().toString(),
                                                    k -> new ArrayList<>())
                                            .add(c));
                    for (var group : groups.values()) {
                        var id = group.getFirst().consumptionId();
                        var args = new ArrayList<Object>();
                        for (var claim : group) {
                            args.add(claim.consumableId());
                            args.add(claim.claimToken());
                        }
                        args.add(id.value());
                        var rows =
                                jdbc.queryForList(
                                        "select s.consumable_id from consumption_slot s join"
                                                + " (values "
                                                + String.join(
                                                        ",",
                                                        Collections.nCopies(
                                                                group.size(), "(?::uuid,?::uuid)"))
                                                + ") r(id,token) on s.consumable_id=r.id and"
                                                + " s.claim_token=r.token where s.consumption_id=?"
                                                + " and s.state='AVAILABLE' order by"
                                                + " s.consumable_id for update of s skip locked",
                                        args.toArray());
                        for (var row : rows) {
                            var claim =
                                    group.stream()
                                            .filter(
                                                    c ->
                                                            c.consumableId()
                                                                    .equals(
                                                                            row.get(
                                                                                    "consumable_id")))
                                            .findFirst()
                                            .orElseThrow();
                            var time = now();
                            var updated =
                                    jdbc.queryForList(
                                            "update consumption_slot set"
                                                + " claimed_until=greatest(claimed_until,?::timestamptz"
                                                + " + ? * interval '1 microsecond') where"
                                                + " consumption_id=? and consumable_id=? and"
                                                + " claim_token=? and state='AVAILABLE' and"
                                                + " claimed_until>? returning"
                                                + " consumable_id,claim_token,claimed_until",
                                            Timestamp.from(time),
                                            us,
                                            id.value(),
                                            claim.consumableId(),
                                            claim.claimToken(),
                                            Timestamp.from(time));
                            updated.forEach(r -> result.add(grant(id, r)));
                        }
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public Optional<Cession> cede(ClaimedSlot claim, RetryPolicy policy) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    jdbc.queryForList(
                            "select consumable_id from consumption_slot where consumption_id=? and"
                                    + " consumable_id=? for update",
                            claim.consumptionId().value(),
                            claim.consumableId());
                    var time = now();
                    var rows =
                            jdbc.queryForList(
                                    "update consumption_slot set"
                                        + " attempt_count=attempt_count+1,state=case when"
                                        + " attempt_count::bigint+1>=? then 'QUARANTINED' else"
                                        + " 'AVAILABLE' end,retry_after=case when"
                                        + " attempt_count::bigint+1>=? then null else"
                                        + " ?::timestamptz + ? * interval '1 microsecond'"
                                        + " end,claim_token=null,claimed_until=null where"
                                        + " consumption_id=? and consumable_id=? and"
                                        + " state='AVAILABLE' and claim_token=? and claimed_until>?"
                                        + " returning attempt_count,state,retry_after",
                                    policy.maxFailures(),
                                    policy.maxFailures(),
                                    Timestamp.from(time),
                                    LifecycleSettings.micros(policy.delay(), true),
                                    claim.consumptionId().value(),
                                    claim.consumableId(),
                                    claim.claimToken(),
                                    Timestamp.from(time));
                    return rows.stream()
                            .findFirst()
                            .map(
                                    r ->
                                            new Cession(
                                                    (Integer) r.get("attempt_count"),
                                                    (String) r.get("state"),
                                                    r.get("retry_after") instanceof Timestamp t
                                                            ? t.toInstant()
                                                            : null));
                });
    }

    @Override
    public List<ClaimInspection> inspectResolved(List<ClaimedSlot> claims) {
        return SpringAttemptTransactions.guarded(
                source,
                () -> {
                    var result = new ArrayList<ClaimInspection>();
                    // One lock scan in PostgreSQL order for each Consumption; no business
                    // observation here.
                    var groups = new TreeMap<String, List<ClaimedSlot>>();
                    claims.forEach(
                            c ->
                                    groups.computeIfAbsent(
                                                    c.consumptionId().value().toString(),
                                                    k -> new ArrayList<>())
                                            .add(c));
                    for (var group : groups.values()) {
                        var id = group.getFirst().consumptionId();
                        var rows =
                                lockKeys(
                                        id, group.stream().map(ClaimedSlot::consumableId).toList());
                        now();
                        for (var claim : group) {
                            boolean obtained =
                                    rows.stream()
                                            .anyMatch(
                                                    r ->
                                                            claim.consumableId()
                                                                    .equals(
                                                                            r.get(
                                                                                    "consumable_id")));
                            boolean exists =
                                    obtained
                                            || Boolean.TRUE.equals(
                                                    jdbc.queryForObject(
                                                            "select exists(select 1 from"
                                                                    + " consumption_slot where"
                                                                    + " consumption_id=? and"
                                                                    + " consumable_id=?)",
                                                            Boolean.class,
                                                            id.value(),
                                                            claim.consumableId()));
                            result.add(
                                    new ClaimInspection(
                                            claim,
                                            obtained
                                                    ? Inspection.PRESENT_UNLOCKED
                                                    : exists
                                                            ? Inspection.LOCKED
                                                            : Inspection.ABSENT));
                        }
                    }
                    return List.copyOf(result);
                });
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
