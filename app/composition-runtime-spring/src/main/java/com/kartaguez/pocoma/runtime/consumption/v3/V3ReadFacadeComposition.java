package com.kartaguez.pocoma.runtime.consumption.v3;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.contract.readquery.BindingRead;
import com.kartaguez.pocoma.contract.readquery.InvitationRead;
import com.kartaguez.pocoma.contract.readquery.ReadLimitsV3;
import com.kartaguez.pocoma.contract.readquery.ReadPotV3Result;
import com.kartaguez.pocoma.domain.projection.v3.CommandTerminalResult;
import com.kartaguez.pocoma.domain.projection.v3.IdentityTerminalResult;

import static com.kartaguez.pocoma.contract.readquery.ReadPotV3Result.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.PotBalance;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.engine.read.results.v3.*;
import com.kartaguez.pocoma.engine.read.identity.v3.GetCurrentBindingV3;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.kartaguez.pocoma.engine.read.results.v3.*;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.polling.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.*;

import javax.sql.DataSource;

/** One READ owner, no WRITE source. All contexts remain inside the callback that owns them. */
public final class V3ReadFacadeComposition {
    private V3ReadFacadeComposition() {}

    public static Services create(
            DataSource read,
            SpringAttemptTransactions owner,
            ReadLimitsV3 limits,
            Set<String> active,
            Supplier<Optional<WorkerView.Snapshot>> worker) {
        return create(read, owner, limits, active, worker, false, null);
    }

    /** HTTP bounds are opt-in: inherited non-HTTP consultations retain their owner contract. */
    public static Services create(
            DataSource read,
            SpringAttemptTransactions owner,
            ReadLimitsV3 limits,
            Set<String> active,
            Supplier<Optional<WorkerView.Snapshot>> worker,
            boolean httpBounds) {
        return create(read, owner, limits, active, worker, httpBounds, null);
    }

    public static Services create(
            DataSource read,
            SpringAttemptTransactions owner,
            ReadLimitsV3 limits,
            Set<String> active,
            Supplier<Optional<WorkerView.Snapshot>> worker,
            boolean httpBounds,
            MeterRegistry registry) {
        owner.requireSource(read);
        var binding = new JdbcCurrentBindingStore(read, owner);
        var pot =
                new ReadPotV3(
                        new JdbcPotVersionStore(read, owner),
                        new JdbcSubobjectVersionStore(read, owner),
                        new JdbcPotBalanceStore(read, owner),
                        binding::read,
                        limits);
        return new Services(
                owner,
                pot,
                new ReadNavigationV3(pot, new JdbcPotVisibilityStore(read, owner), limits),
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

    public static final class Services extends com.kartaguez.pocoma.engine.read.execution.v3.ReadExecution {
        private final Set<String> active;
        private final Supplier<Optional<WorkerView.Snapshot>> worker;
        private Services(SpringAttemptTransactions owner,ReadPotV3 pot,ReadNavigationV3 navigation,
                JdbcCurrentBindingStore binding,GetCommandTerminalResult command,GetIdentityTerminalResult identity,
                Set<String> active,Supplier<Optional<WorkerView.Snapshot>> worker,ReadLimitsV3 limits,
                DataSource source,boolean httpBounds,MeterRegistry registry) {
            super(new com.kartaguez.pocoma.infra.tx.spring.v3.SpringReadTransactions(source,owner,httpBounds),
                new com.kartaguez.pocoma.contract.readquery.SnapshotRead() {
                    private final org.springframework.jdbc.core.JdbcTemplate sql=new org.springframework.jdbc.core.JdbcTemplate(source);
                    public ReadPotV3Result<Pot> current(ExternalIdentity e,String t,UUID i,long v,RecoveryReadContext c) { return pot.current(e,t,i,v,c); }
                    public ReadPotV3Result<Pot> at(ExternalIdentity e,String t,UUID i,long v,RecoveryReadContext c) { return pot.at(e,t,i,v,c); }
                    public ReadPotV3Result<PotBalance> balance(ExternalIdentity e,String t,UUID i,long v,RecoveryReadContext c) { return pot.balance(e,t,i,v,c); }
                    public ReadPotV3Result<List<Expense>> expenses(ExternalIdentity e,String t,UUID i,long v,List<ExpenseRef> refs,RecoveryReadContext c) { return pot.expenses(e,t,i,v,refs,c); }
                    public ReadPotV3Result<List<UUID>> visible(ExternalIdentity e,String t,UUID i,int n,RecoveryReadContext c) { return navigation.visible(e,t,i,n,c); }
                    public BindingRead.Answer binding(ExternalIdentity e,long v,RecoveryReadContext c) { return new GetCurrentBindingV3(key -> binding.read(key,c)).get(e,v); }
                    private ReadInvitation invitations(Function<com.kartaguez.pocoma.domain.pot.v3.Invitation,String> decrypt) {
                        return new ReadInvitation(new JdbcInvitationStore(source),new JdbcPotVersionStore(source,owner),
                            new JdbcSubobjectVersionStore(source,owner),binding::read,decrypt,
                            () -> sql.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant());
                    }
                    public ReadPotV3Result<InvitationRead.Link> invitationLink(ExternalIdentity e,UUID i,Function<com.kartaguez.pocoma.domain.pot.v3.Invitation,String> d,RecoveryReadContext c) { return invitations(d).link(e,i,c); }
                    public ReadPotV3Result<List<InvitationRead.Place>> invitationPlaces(ExternalIdentity e,String b,String t,RecoveryReadContext c) { return invitations(i -> {throw new IllegalStateException("Decryption forbidden");}).places(e,b,t,c); }
                    public Optional<CommandTerminalResult> commandResult(UUID i,ExternalIdentity e,RecoveryReadContext c) { return command.get(i,e); }
                    public Optional<IdentityTerminalResult> identityResult(UUID i,ExternalIdentity e,RecoveryReadContext c) { return identity.get(i,e); }
                },limits,httpBounds,new com.kartaguez.pocoma.infra.observability.micrometer.v3.MicrometerReadObservation(registry));
            this.active=Set.copyOf(active); this.worker=worker;
        }
        public Set<String> activeParticipants() {return active;}
        public Optional<WorkerView.Snapshot> workerState() {return worker.get();}
    }
}
