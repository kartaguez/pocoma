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
        owner.requireSource(read);
        var binding = new JdbcCurrentBindingStore(read, owner);
        var pot =
                new ReadPot(
                        new JdbcPotVersionStore(read, owner),
                        new JdbcSubobjectVersionStore(read, owner),
                        new JdbcPotBalanceStore(read, owner),
                        binding::read,
                        limits);
        return new Services(
                owner,
                pot,
                new ReadNavigation(pot, new JdbcPotVisibilityStore(read, owner), limits),
                binding,
                new GetCommandTerminalResult(new JdbcCommandTerminalResultStore(read, owner)),
                new GetIdentityTerminalResult(new JdbcIdentityTerminalResultStore(read, owner)),
                Set.copyOf(active),
                worker,
                limits,
                read,
                httpBounds,
                registry);
    }

    public static final class Services extends com.kartaguez.pocoma.engine.read.execution.ReadExecution {
        private final Set<String> active;
        private final Supplier<Optional<WorkerView.Snapshot>> worker;
        private Services(SpringAttemptTransactions owner,ReadPot pot,ReadNavigation navigation,
                JdbcCurrentBindingStore binding,GetCommandTerminalResult command,GetIdentityTerminalResult identity,
                Set<String> active,Supplier<Optional<WorkerView.Snapshot>> worker,ReadLimits limits,
                DataSource source,boolean httpBounds,MeterRegistry registry) {
            super(new com.kartaguez.pocoma.infra.tx.spring.SpringReadTransactions(source,owner,httpBounds),
                new com.kartaguez.pocoma.contract.readquery.SnapshotRead() {
                    private final org.springframework.jdbc.core.JdbcTemplate sql=new org.springframework.jdbc.core.JdbcTemplate(source);
                    public ReadPotResult<Pot> current(ExternalIdentity e,String t,UUID i,long v,RecoveryReadContext c) { return pot.current(e,t,i,v,c); }
                    public ReadPotResult<Pot> at(ExternalIdentity e,String t,UUID i,long v,RecoveryReadContext c) { return pot.at(e,t,i,v,c); }
                    public ReadPotResult<PotBalance> balance(ExternalIdentity e,String t,UUID i,long v,RecoveryReadContext c) { return pot.balance(e,t,i,v,c); }
                    public ReadPotResult<List<Expense>> expenses(ExternalIdentity e,String t,UUID i,long v,List<ExpenseRef> refs,RecoveryReadContext c) { return pot.expenses(e,t,i,v,refs,c); }
                    public ReadPotResult<List<UUID>> visible(ExternalIdentity e,String t,UUID i,int n,RecoveryReadContext c) { return navigation.visible(e,t,i,n,c); }
                    public BindingRead.Answer binding(ExternalIdentity e,long v,RecoveryReadContext c) { return new GetCurrentBinding(key -> binding.read(key,c)).get(e,v); }
                    private ReadInvitation invitations(Function<com.kartaguez.pocoma.domain.pot.Invitation,String> decrypt) {
                        return new ReadInvitation(new JdbcInvitationStore(source),new JdbcPotVersionStore(source,owner),
                            new JdbcSubobjectVersionStore(source,owner),binding::read,decrypt,
                            () -> sql.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant());
                    }
                    public ReadPotResult<InvitationRead.Link> invitationLink(ExternalIdentity e,UUID i,Function<com.kartaguez.pocoma.domain.pot.Invitation,String> d,RecoveryReadContext c) { return invitations(d).link(e,i,c); }
                    public ReadPotResult<List<InvitationRead.Place>> invitationPlaces(ExternalIdentity e,String b,String t,RecoveryReadContext c) { return invitations(i -> {throw new IllegalStateException("Decryption forbidden");}).places(e,b,t,c); }
                    public Optional<CommandTerminalResult> commandResult(UUID i,ExternalIdentity e,RecoveryReadContext c) { return command.get(i,e); }
                    public Optional<IdentityTerminalResult> identityResult(UUID i,ExternalIdentity e,RecoveryReadContext c) { return identity.get(i,e); }
                },limits,httpBounds,new com.kartaguez.pocoma.infra.observability.micrometer.MicrometerReadObservation(registry));
            this.active=Set.copyOf(active); this.worker=worker;
        }
        public Set<String> activeParticipants() {return active;}
        public Optional<WorkerView.Snapshot> workerState() {return worker.get();}
    }
}
