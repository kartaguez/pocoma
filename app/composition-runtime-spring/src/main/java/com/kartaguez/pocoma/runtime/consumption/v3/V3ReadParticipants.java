package com.kartaguez.pocoma.runtime.consumption.v3;
import com.kartaguez.pocoma.infra.postgres.source.v3.identity.*;
import com.kartaguez.pocoma.infra.postgres.source.v3.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.materialize.pot.v3.*;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.engine.materialize.results.v3.MaterializeCommandResult;
import com.kartaguez.pocoma.engine.materialize.identity.v3.MaterializeCurrentBinding;
import com.kartaguez.pocoma.engine.materialize.results.v3.MaterializeIdentityRequestResult;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.*;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import java.util.*;

import javax.sql.DataSource;

/** Explicit inert factory only: no Bean, scheduler, worker setting or productive activation. */
public final class V3ReadParticipants {
    public enum Name {
        POT_VERSION,
        POT_BALANCE,
        POT_VISIBILITY,
        CURRENT_BINDING,
        COMMAND_RESULT,
        IDENTITY_REQUEST_RESULT
    }

    public record Inert(ConsumptionDefinition definition, ConsumptionParticipant participant) {}

    private V3ReadParticipants() {}

    public static Map<Name, Inert> create(
            DataSource write,
            SpringAttemptTransactions source,
            DataSource read,
            SpringAttemptTransactions effects) {
        source.requireSource(write);
        effects.requireSource(read);
        if (write == read || source == effects)
            throw new IllegalArgumentException("Separate WRITE/READ owners required");
        var result = new EnumMap<Name, Inert>(Name.class);
        result.put(
                Name.POT_VERSION,
                inert(
                        "3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a",
                        ConsumableFamily.POT_EVENT,
                        new MaterializePotVersion(
                                new JdbcPotVersionSource(write),
                                new JdbcSubobjectVersionStore(read, effects),
                                new JdbcPotVersionStore(read, effects))));
        result.put(
                Name.POT_BALANCE,
                inert(
                        "190a93c6-b8af-5ac7-8001-72e2ec53c2df",
                        ConsumableFamily.POT_EVENT,
                        new MaterializePotBalance(
                                new JdbcPotBalanceSource(write),
                                new JdbcPotBalanceStore(read, effects))));
        result.put(
                Name.POT_VISIBILITY,
                inert(
                        "b4d04cce-b5da-5d1b-9698-5ff9af9a46fa",
                        ConsumableFamily.POT_EVENT,
                        new MaterializePotVisibility(
                                new JdbcPotVisibilitySource(write),
                                new JdbcPotVisibilityStore(read, effects))));
        result.put(
                Name.CURRENT_BINDING,
                inert(
                        "7b8d2838-bf7a-5117-868a-e5c14b4c0221",
                        ConsumableFamily.IDENTITY_EVENT,
                        new MaterializeCurrentBinding(
                                new JdbcCurrentBindingSource(write, source),
                                new JdbcCurrentBindingStore(read, effects))));
        result.put(
                Name.COMMAND_RESULT,
                inert(
                        "63664303-d33e-5d42-8cce-c4dae8023311",
                        ConsumableFamily.COMMAND_COMPLETION_EVENT,
                        new MaterializeCommandResult(
                                new JdbcCommandTerminalSource(write, source),
                                new JdbcCommandTerminalResultStore(read, effects))));
        result.put(
                Name.IDENTITY_REQUEST_RESULT,
                inert(
                        "1d8ff246-358f-5ddd-a7f5-416efcfd7445",
                        ConsumableFamily.IDENTITY_COMPLETION_EVENT,
                        new MaterializeIdentityRequestResult(
                                new JdbcIdentityTerminalSource(write, source),
                                new JdbcIdentityTerminalResultStore(read, effects))));
        return Collections.unmodifiableMap(result);
    }

    public static Inert invitation(DataSource write, DataSource read) {
        if (write == read)
            throw new IllegalArgumentException("Separate WRITE/READ sources required");
        return inert(
                "924cd9e3-435d-5804-9d1e-c6aecadb9ab4",
                ConsumableFamily.INVITATION_EVENT,
                new MaterializeInvitation(
                        new JdbcInvitationSource(write),
                        new com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.JdbcInvitationStore(
                                read)));
    }

    private static Inert inert(
            String id, ConsumableFamily family, ConsumptionParticipant participant) {
        var definition =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString(id)),
                        family,
                        ConsumptionDefinition.Authority.READ);
        if (!V3ConsumptionCatalogConfiguration.definitions().contains(definition))
            throw new IllegalStateException("READ definition absent from canonical catalogue");
        return new Inert(definition, participant);
    }
}
