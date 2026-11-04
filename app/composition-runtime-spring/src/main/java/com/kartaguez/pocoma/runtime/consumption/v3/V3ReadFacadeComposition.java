package com.kartaguez.pocoma.runtime.consumption.v3;

import static com.kartaguez.pocoma.engine.read.pot.v3.ReadPotV3Result.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.PotBalance;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.read.commandresult.v3.*;
import com.kartaguez.pocoma.engine.read.currentbinding.v3.GetCurrentBindingV3;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.kartaguez.pocoma.engine.read.registrationresult.v3.*;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

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
            Supplier<Optional<V3ConsumptionWorker.Snapshot>> worker) {
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
                worker, limits, read);
    }

    public static final class Services implements ReadPotV3.Queries {
        private final SpringAttemptTransactions owner;
        private final ReadPotV3 pot;
        private final ReadNavigationV3 navigation;
        private final JdbcCurrentBindingStore bindingStore;
        private final GetCommandTerminalResult commandReader;
        private final GetIdentityTerminalResult identityReader;
        private final Set<String> active;
        private final Supplier<Optional<V3ConsumptionWorker.Snapshot>> worker;
        // Q=2 is the existing Business consultation allocation, never Control.
        public static final int SQL_PERMITS = 2;
        private final Semaphore quota = new Semaphore(SQL_PERMITS);
        private final ReadLimitsV3 limits;
        private final org.springframework.jdbc.core.JdbcTemplate sql;

        private Services(
                SpringAttemptTransactions owner,
                ReadPotV3 pot,
                ReadNavigationV3 navigation,
                JdbcCurrentBindingStore binding,
                GetCommandTerminalResult command,
                GetIdentityTerminalResult identity,
                Set<String> active,
                Supplier<Optional<V3ConsumptionWorker.Snapshot>> worker, ReadLimitsV3 limits, DataSource source) {
            this.sql = new org.springframework.jdbc.core.JdbcTemplate(source);
            this.limits = Objects.requireNonNull(limits);
            this.owner = owner;
            this.pot = pot;
            this.navigation = navigation;
            this.bindingStore = binding;
            this.commandReader = command;
            this.identityReader = identity;
            this.active = Set.copyOf(active);
            this.worker = worker;
        }

        public Set<String> activeParticipants() {
            return active;
        }

        public Optional<V3ConsumptionWorker.Snapshot> workerState() {
            return worker.get();
        }

        private <T> ReadPotV3Result<T> query(
                Function<RecoveryReadContext, ReadPotV3Result<T>> call) {
            if (!quota.tryAcquire()) return new Unavailable<>();
            try {
                long started = System.nanoTime();
                var result = owner.read(c -> {
                    // Spring applies its JDBC holder deadline to each statement. Cap that
                    // deadline as well: otherwise it would overwrite PostgreSQL's 2s setting.
                    var resource = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(sql.getDataSource());
                    if (resource instanceof org.springframework.jdbc.datasource.ConnectionHolder holder)
                        holder.setTimeoutInMillis(Math.min(2000, holder.hasTimeout() ? holder.getTimeToLiveInMillis() : 2000));
                    sql.execute("set local statement_timeout = '2s'");
                    sql.execute("set local transaction_timeout = '3s'");
                    return call.apply(c);
                });
                return System.nanoTime() - started > java.time.Duration.ofSeconds(5).toNanos()
                        ? new Unavailable<>() : result;
            } catch (RuntimeException technicalFailure) {
                return new Unavailable<>();
            } finally {
                quota.release();
            }
        }

        public ReadPotV3Result<Pot> current(
                ExternalIdentity eui, String token, UUID id, long minimum) {
            if (eui == null || token == null || token.isBlank() || id == null || minimum < 0)
                return new InvalidInput<>();
            return query(c -> pot.current(eui, token, id, minimum, c));
        }

        public ReadPotV3Result<Pot> at(ExternalIdentity eui, String token, UUID id, long version) {
            if (eui == null || token == null || token.isBlank() || id == null || version < 1)
                return new InvalidInput<>();
            return query(c -> pot.at(eui, token, id, version, c));
        }

        public ReadPotV3Result<PotBalance> balance(
                ExternalIdentity eui, String token, UUID id, long version) {
            if (eui == null || token == null || token.isBlank() || id == null || version < 1)
                return new InvalidInput<>();
            return query(c -> pot.balance(eui, token, id, version, c));
        }

        public ReadPotV3Result<List<Expense>> expenses(
                ExternalIdentity eui, String token, UUID id, long version, List<ExpenseRef> refs) {
            if (eui == null
                    || token == null
                    || token.isBlank()
                    || id == null
                    || version < 1
                    || !limits.validBatch(refs)) return new InvalidInput<>();
            var captured = List.copyOf(refs);
            return query(c -> pot.expenses(eui, token, id, version, captured, c));
        }

        public ReadPotV3Result<List<UUID>> visible(
                ExternalIdentity eui, String token, UUID after, int limit) {
            if (eui == null || token == null || token.isBlank() || !limits.validPage(limit)) return new InvalidInput<>();
            return query(c -> navigation.visible(eui, token, after, limit, c));
        }

        public ReadPotV3Result<GetCurrentBindingV3.Answer> binding(
                ExternalIdentity eui, long minimum) {
            if (eui == null || minimum < 0) return new InvalidInput<>();
            return query(
                    c -> {
                        var answer =
                                new GetCurrentBindingV3(key -> bindingStore.read(key, c))
                                        .get(eui, minimum);
                        return new Available<>(
                                answer, answer.value() == null ? 0 : answer.value().euiVersion());
                    });
        }

        public ReadPotV3Result<Optional<CommandTerminalResult>> commandResult(
                UUID request, ExternalIdentity eui) {
            if (request == null || eui == null) return new InvalidInput<>();
            return query(c -> new Available<>(commandReader.get(request, eui), 0));
        }

        public ReadPotV3Result<Optional<IdentityTerminalResult>> identityResult(
                UUID request, ExternalIdentity eui) {
            if (request == null || eui == null) return new InvalidInput<>();
            return query(c -> new Available<>(identityReader.get(request, eui), 0));
        }
    }
}
