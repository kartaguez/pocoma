package com.kartaguez.pocoma.compose.polling.materialize;
import com.kartaguez.pocoma.infra.postgres.source.identity.*;
import com.kartaguez.pocoma.infra.postgres.source.*;

import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.engine.materialize.pot.*;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.engine.materialize.results.MaterializeCommandResult;
import com.kartaguez.pocoma.engine.materialize.identity.MaterializeCurrentBinding;
import com.kartaguez.pocoma.engine.materialize.results.MaterializeIdentityRequestResult;
import com.kartaguez.pocoma.infra.postgres.read.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;

import java.util.*;

import javax.sql.DataSource;

/** Explicit inert factory only: no Bean, scheduler, worker setting or productive activation. */
public final class ReadParticipants {
    public enum Name {
        POT_VERSION("MaterializePotVersion"),
        POT_BALANCE("MaterializePotBalance"),
        POT_VISIBILITY("MaterializePotVisibility"),
        CURRENT_BINDING("MaterializeCurrentBinding"),
        COMMAND_RESULT("MaterializeCommandResult"),
        IDENTITY_REQUEST_RESULT("MaterializeIdentityRequestResult"),
        INVITATION("MaterializeInvitation");

        private final String capability;

        Name(String capability) {
            this.capability = capability;
        }

        public static Name ofCapability(String capability) {
            return Arrays.stream(values())
                    .filter(name -> name.capability.equals(capability))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown materialization capability: " + capability));
        }
    }

    public record Inert(ConsumptionDefinition definition, ConsumptionParticipant participant) {}

    private ReadParticipants() {}

    public static Map<Name, Inert> create(
            DataSource write,
            SpringAttemptTransactions source,
            DataSource read,
            SpringAttemptTransactions effects) {
        return create(
                write,
                source,
                read,
                effects,
                MaterializePotVersion.DEFAULT_MAX_DELTA_DISTANCE,
                PotVersionObservation.NOOP);
    }

    public static Map<Name, Inert> create(
            DataSource write,
            SpringAttemptTransactions source,
            DataSource read,
            SpringAttemptTransactions effects,
            long potVersionMaxDeltaDistance,
            PotVersionObservation potVersionObservation) {
        return create(
                write,
                source,
                read,
                effects,
                potVersionMaxDeltaDistance,
                potVersionObservation,
                MaterializePotBalance.Settings.DEFAULT,
                PotBalanceObservation.NOOP);
    }

    public static Map<Name, Inert> create(
            DataSource write,
            SpringAttemptTransactions source,
            DataSource read,
            SpringAttemptTransactions effects,
            long potVersionMaxDeltaDistance,
            PotVersionObservation potVersionObservation,
            MaterializePotBalance.Settings potBalanceSettings,
            PotBalanceObservation potBalanceObservation) {
        requireSeparateOwners(write, source, read, effects);
        var participants = new EnumMap<Name, Inert>(Name.class);
        for (var name : Name.values()) {
            participants.put(name, participant(name, write, source, read, effects,
                    potVersionMaxDeltaDistance, potVersionObservation, potBalanceSettings, potBalanceObservation, false));
        }
        return Collections.unmodifiableMap(participants);
    }

    private static Inert participant(
            Name name, DataSource write, SpringAttemptTransactions source,
            DataSource read, SpringAttemptTransactions effects,
            long potVersionMaxDeltaDistance, PotVersionObservation potVersionObservation,
            MaterializePotBalance.Settings potBalanceSettings, PotBalanceObservation potBalanceObservation,
            boolean publishAvailability) {
        return switch (name) {
            case POT_VERSION -> inert(
                    "3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a",
                    ConsumableFamily.POT_EVENT,
                    new MaterializePotVersion(
                            new JdbcPotVersionSource(write),
                            new JdbcSubobjectVersionStore(read, effects),
                            new JdbcPotVersionStore(read, effects, publishAvailability),
                            potVersionMaxDeltaDistance,
                            potVersionObservation));
            case POT_BALANCE -> inert(
                    "190a93c6-b8af-5ac7-8001-72e2ec53c2df",
                    ConsumableFamily.POT_EVENT,
                    new MaterializePotBalance(
                            new JdbcPotBalanceSource(write),
                            new JdbcPotBalanceStore(read, effects, publishAvailability),
                            potBalanceSettings,
                            potBalanceObservation));
            case POT_VISIBILITY -> inert(
                    "b4d04cce-b5da-5d1b-9698-5ff9af9a46fa",
                    ConsumableFamily.POT_EVENT,
                    new MaterializePotVisibility(
                            new JdbcPotVisibilitySource(write),
                            new JdbcPotVisibilityStore(read, effects, publishAvailability)));
            case CURRENT_BINDING -> inert(
                    "7b8d2838-bf7a-5117-868a-e5c14b4c0221",
                    ConsumableFamily.IDENTITY_EVENT,
                    new MaterializeCurrentBinding(
                            new JdbcCurrentBindingSource(write, source),
                            new JdbcCurrentBindingStore(read, effects, publishAvailability)));
            case COMMAND_RESULT -> inert(
                    "63664303-d33e-5d42-8cce-c4dae8023311",
                    ConsumableFamily.COMMAND_COMPLETION_EVENT,
                    new MaterializeCommandResult(
                            new JdbcCommandTerminalSource(write, source),
                            new JdbcCommandTerminalResultStore(read, effects, publishAvailability)));
            case IDENTITY_REQUEST_RESULT -> inert(
                    "1d8ff246-358f-5ddd-a7f5-416efcfd7445",
                    ConsumableFamily.IDENTITY_COMPLETION_EVENT,
                    new MaterializeIdentityRequestResult(
                            new JdbcIdentityTerminalSource(write, source),
                            new JdbcIdentityTerminalResultStore(read, effects, publishAvailability)));
            case INVITATION -> inert(
                    "924cd9e3-435d-5804-9d1e-c6aecadb9ab4",
                    ConsumableFamily.INVITATION_EVENT,
                    new MaterializeInvitation(new JdbcInvitationSource(write),
                            new JdbcInvitationStore(read, effects, publishAvailability)));
        };
    }

    public static Inert select(
            String name,
            DataSource write,
            SpringAttemptTransactions source,
            DataSource read,
            SpringAttemptTransactions effects) {
        return select(
                name,
                write,
                source,
                read,
                effects,
                MaterializePotVersion.DEFAULT_MAX_DELTA_DISTANCE,
                PotVersionObservation.NOOP);
    }

    public static Inert select(
            String name,
            DataSource write,
            SpringAttemptTransactions source,
            DataSource read,
            SpringAttemptTransactions effects,
            long potVersionMaxDeltaDistance,
            PotVersionObservation potVersionObservation) {
        return select(
                name,
                write,
                source,
                read,
                effects,
                potVersionMaxDeltaDistance,
                potVersionObservation,
                MaterializePotBalance.Settings.DEFAULT,
                PotBalanceObservation.NOOP);
    }

    public static Inert select(
            String name,
            DataSource write,
            SpringAttemptTransactions source,
            DataSource read,
            SpringAttemptTransactions effects,
            long potVersionMaxDeltaDistance,
            PotVersionObservation potVersionObservation,
            MaterializePotBalance.Settings potBalanceSettings,
            PotBalanceObservation potBalanceObservation) {
        var capability = Name.ofCapability(name);
        requireSeparateOwners(write, source, read, effects);
        return participant(capability, write, source, read, effects, potVersionMaxDeltaDistance,
                potVersionObservation, potBalanceSettings, potBalanceObservation, true);
    }

    private static void requireSeparateOwners(
            DataSource write, SpringAttemptTransactions source,
            DataSource read, SpringAttemptTransactions effects) {
        source.requireSource(write);
        effects.requireSource(read);
        if (write == read || source == effects) {
            throw new IllegalArgumentException("Separate WRITE/READ owners required");
        }
    }

    private static Inert inert(
            String id, ConsumableFamily family, ConsumptionParticipant participant) {
        var definition =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString(id)),
                        family,
                        ConsumptionDefinition.Authority.READ);
        if (!com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions().contains(definition))
            throw new IllegalStateException("READ definition absent from canonical catalogue");
        return new Inert(definition, participant);
    }
}
