package com.kartaguez.pocoma.compose.web.read;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.contract.readquery.BindingRead;
import com.kartaguez.pocoma.contract.readquery.InvitationRead;
import com.kartaguez.pocoma.contract.readquery.ReadLimits;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult.Expense;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult.ExpenseRef;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult.Pot;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.domain.projection.IdentityTerminalResult;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.engine.read.identity.GetCurrentBinding;
import com.kartaguez.pocoma.engine.read.pot.ReadInvitation;
import com.kartaguez.pocoma.engine.read.pot.ReadNavigation;
import com.kartaguez.pocoma.engine.read.pot.ReadPot;
import com.kartaguez.pocoma.engine.read.results.GetCommandTerminalResult;
import com.kartaguez.pocoma.engine.read.results.GetIdentityTerminalResult;
import com.kartaguez.pocoma.infra.postgres.read.JdbcCommandTerminalResultStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcCurrentBindingStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcIdentityTerminalResultStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcInvitationStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotBalanceStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotVersionStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotVisibilityStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcSubobjectVersionStore;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import io.micrometer.core.instrument.MeterRegistry;

/** One READ owner, no WRITE source. All contexts remain inside the callback that owns them. */
public final class ReadFacadeComposition {
    private ReadFacadeComposition() {}

    public static Services create(
            DataSource read,
            SpringAttemptTransactions owner,
            ReadLimits limits,
            Set<String> active,
            Supplier<Optional<WorkerView.Snapshot>> worker) {
        return create(read, owner, limits, active, worker, false, null);
    }

    /** HTTP bounds are opt-in: inherited non-HTTP consultations retain their owner contract. */
    public static Services create(
            DataSource read,
            SpringAttemptTransactions owner,
            ReadLimits limits,
            Set<String> active,
            Supplier<Optional<WorkerView.Snapshot>> worker,
            boolean httpBounds) {
        return create(read, owner, limits, active, worker, httpBounds, null);
    }

    public static Services create(
            DataSource read,
            SpringAttemptTransactions owner,
            ReadLimits limits,
            Set<String> active,
            Supplier<Optional<WorkerView.Snapshot>> worker,
            boolean httpBounds,
            MeterRegistry registry) {
        return create(read, owner, limits, active, worker, httpBounds, registry, Services.SQL_PERMITS);
    }

    public static Services create(
            DataSource read,
            SpringAttemptTransactions owner,
            ReadLimits limits,
            Set<String> active,
            Supplier<Optional<WorkerView.Snapshot>> worker,
            boolean httpBounds,
            MeterRegistry registry,
            int sqlPermits) {
        owner.requireSource(read);
        var binding = new JdbcCurrentBindingStore(read, owner);
        var potVersions = new JdbcPotVersionStore(read, owner);
        var subobjects = new JdbcSubobjectVersionStore(read, owner);
        var invitations = new JdbcInvitationStore(read);
        var pot =
                new ReadPot(
                        potVersions,
                        subobjects,
                        new JdbcPotBalanceStore(read, owner),
                        binding::read,
                        limits);
        return new Services(
                pot,
                new ReadNavigation(pot, new JdbcPotVisibilityStore(read, owner), limits),
                binding,
                potVersions,
                subobjects,
                invitations,
                new GetCommandTerminalResult(new JdbcCommandTerminalResultStore(read, owner)),
                new GetIdentityTerminalResult(new JdbcIdentityTerminalResultStore(read, owner)),
                Set.copyOf(active),
                worker,
                limits,
                read,
                owner,
                httpBounds,
                registry,
                sqlPermits);
    }

    public static final class Services extends com.kartaguez.pocoma.engine.read.execution.ReadExecution {
        private final Set<String> active;
        private final Supplier<Optional<WorkerView.Snapshot>> worker;
        private Services(
                ReadPot pot,
                ReadNavigation navigation,
                JdbcCurrentBindingStore binding,
                JdbcPotVersionStore potVersions,
                JdbcSubobjectVersionStore subobjects,
                JdbcInvitationStore invitations,
                GetCommandTerminalResult command,
                GetIdentityTerminalResult identity,
                Set<String> active,
                Supplier<Optional<WorkerView.Snapshot>> worker,
                ReadLimits limits,
                DataSource source,
                SpringAttemptTransactions owner,
                boolean httpBounds,
                MeterRegistry registry,
                int sqlPermits) {
            super(
                    new com.kartaguez.pocoma.infra.tx.spring.SpringReadTransactions(source, owner, httpBounds),
                    new SnapshotReader(
                            pot,
                            navigation,
                            binding,
                            potVersions,
                            subobjects,
                            invitations,
                            command,
                            identity,
                            new org.springframework.jdbc.core.JdbcTemplate(source)),
                    limits,
                    httpBounds,
                    new com.kartaguez.pocoma.infra.observability.micrometer.MicrometerReadObservation(registry),
                    sqlPermits);
            this.active = Set.copyOf(active);
            this.worker = worker;
        }
        public Set<String> activeParticipants() {return active;}
        public Optional<WorkerView.Snapshot> workerState() {return worker.get();}
    }

    private static final class SnapshotReader implements com.kartaguez.pocoma.contract.readquery.SnapshotRead {
        private final ReadPot pot;
        private final ReadNavigation navigation;
        private final JdbcCurrentBindingStore binding;
        private final JdbcPotVersionStore potVersions;
        private final JdbcSubobjectVersionStore subobjects;
        private final JdbcInvitationStore invitations;
        private final GetCommandTerminalResult command;
        private final GetIdentityTerminalResult identity;
        private final Supplier<java.time.Instant> dbClock;
        private final ReadInvitation placesReader;

        SnapshotReader(
                ReadPot pot,
                ReadNavigation navigation,
                JdbcCurrentBindingStore binding,
                JdbcPotVersionStore potVersions,
                JdbcSubobjectVersionStore subobjects,
                JdbcInvitationStore invitations,
                GetCommandTerminalResult command,
                GetIdentityTerminalResult identity,
                org.springframework.jdbc.core.JdbcTemplate sql) {
            this.pot = pot;
            this.navigation = navigation;
            this.binding = binding;
            this.potVersions = potVersions;
            this.subobjects = subobjects;
            this.invitations = invitations;
            this.command = command;
            this.identity = identity;
            this.dbClock = () -> sql.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
            this.placesReader = new ReadInvitation(
                    invitations,
                    potVersions,
                    subobjects,
                    binding::read,
                    i -> { throw new IllegalStateException("Decryption forbidden"); },
                    dbClock);
        }

        public ReadPotResult<Pot> current(ExternalIdentity e, String t, UUID i, long v, RecoveryReadContext c) {
            return pot.current(e, t, i, v, c);
        }

        public ReadPotResult<Pot> at(ExternalIdentity e, String t, UUID i, long v, RecoveryReadContext c) {
            return pot.at(e, t, i, v, c);
        }

        public ReadPotResult<PotBalance> balance(ExternalIdentity e, String t, UUID i, long v, RecoveryReadContext c) {
            return pot.balance(e, t, i, v, c);
        }

        public ReadPotResult<List<Expense>> expenses(ExternalIdentity e, String t, UUID i, long v, List<ExpenseRef> refs, RecoveryReadContext c) {
            return pot.expenses(e, t, i, v, refs, c);
        }

        public ReadPotResult<List<UUID>> visible(ExternalIdentity e, String t, UUID i, int n, RecoveryReadContext c) {
            return navigation.visible(e, t, i, n, c);
        }

        public BindingRead.Answer binding(ExternalIdentity e, long v, RecoveryReadContext c) {
            return new GetCurrentBinding(key -> binding.read(key, c)).get(e, v);
        }

        public ReadPotResult<InvitationRead.Link> invitationLink(
                ExternalIdentity e,
                UUID i,
                Function<com.kartaguez.pocoma.domain.pot.invitation.Invitation, String> decrypt,
                RecoveryReadContext c) {
            return new ReadInvitation(invitations, potVersions, subobjects, binding::read, decrypt, dbClock)
                    .link(e, i, c);
        }

        public ReadPotResult<List<InvitationRead.Place>> invitationPlaces(ExternalIdentity e, String b, String t, RecoveryReadContext c) {
            return placesReader.places(e, b, t, c);
        }

        public Optional<CommandTerminalResult> commandResult(UUID i, ExternalIdentity e, RecoveryReadContext c) {
            return command.get(i, e);
        }

        public Optional<IdentityTerminalResult> identityResult(UUID i, ExternalIdentity e, RecoveryReadContext c) {
            return identity.get(i, e);
        }
    }
}
