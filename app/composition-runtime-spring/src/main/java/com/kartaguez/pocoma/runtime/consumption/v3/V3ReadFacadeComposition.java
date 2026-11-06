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
            Supplier<Optional<V3ConsumptionWorker.Snapshot>> worker) {
        return create(read, owner, limits, active, worker, false, null);
    }

    /** HTTP bounds are opt-in: inherited non-HTTP consultations retain their owner contract. */
    public static Services create(
            DataSource read,
            SpringAttemptTransactions owner,
            ReadLimitsV3 limits,
            Set<String> active,
            Supplier<Optional<V3ConsumptionWorker.Snapshot>> worker,
            boolean httpBounds) {
        return create(read, owner, limits, active, worker, httpBounds, null);
    }

    public static Services create(
            DataSource read,
            SpringAttemptTransactions owner,
            ReadLimitsV3 limits,
            Set<String> active,
            Supplier<Optional<V3ConsumptionWorker.Snapshot>> worker,
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

    public static final class Services {
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
        private final boolean httpBounds;
        private final org.springframework.jdbc.core.JdbcTemplate sql;
        private final MeterRegistry registry;
        private final Counter rejectionsCounter;

        private static final Duration[] BUCKETS = {
            Duration.ofMillis(1),
            Duration.ofMillis(5),
            Duration.ofMillis(10),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofSeconds(120)
        };

        private Services(
                SpringAttemptTransactions owner,
                ReadPotV3 pot,
                ReadNavigationV3 navigation,
                JdbcCurrentBindingStore binding,
                GetCommandTerminalResult command,
                GetIdentityTerminalResult identity,
                Set<String> active,
                Supplier<Optional<V3ConsumptionWorker.Snapshot>> worker,
                ReadLimitsV3 limits,
                DataSource source,
                boolean httpBounds,
                MeterRegistry registry) {
            this.httpBounds = httpBounds;
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
            this.registry = registry;
            if (registry != null) {
                Gauge.builder("pocoma.v3.read.quota.permits", quota, Semaphore::availablePermits)
                        .tag("scope", "sql")
                        .register(registry);
                this.rejectionsCounter = registry.counter("pocoma.v3.read.quota.rejections", "scope", "sql");
            } else {
                this.rejectionsCounter = null;
            }
        }

        public Set<String> activeParticipants() {
            return active;
        }

        public Optional<V3ConsumptionWorker.Snapshot> workerState() {
            return worker.get();
        }

        private void record(String operation, String outcome, long durationNanos) {
            if (registry != null) {
                Timer.builder("pocoma.v3.read.query.duration")
                        .tags("operation", operation, "outcome", outcome)
                        .publishPercentileHistogram()
                        .serviceLevelObjectives(BUCKETS)
                        .register(registry)
                        .record(Duration.ofNanos(durationNanos));
            }
        }

        private static <T> String classifyResult(ReadPotV3Result<T> result) {
            if (result instanceof Available) return "available";
            if (result instanceof Unavailable) return "unavailable";
            if (result instanceof InvalidInput) return "invalid_input";
            return "other";
        }

        private <T> ReadPotV3Result<T> query(
                String operation,
                Function<RecoveryReadContext, ReadPotV3Result<T>> call) {
            long startNanos = System.nanoTime();
            if (!quota.tryAcquire()) {
                if (rejectionsCounter != null) rejectionsCounter.increment();
                record(operation, "rejected", System.nanoTime() - startNanos);
                return new Unavailable<>();
            }
            String outcome = "unavailable";
            try {
                if (!httpBounds) {
                    var res = owner.read(call);
                    outcome = classifyResult(res);
                    return res;
                }
                var result =
                        owner.read(
                                c -> {
                                    // Spring applies its JDBC holder deadline to each statement.
                                    // Cap that
                                    // deadline as well: otherwise it would overwrite PostgreSQL's
                                    // 2s setting.
                                    var resource =
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager.getResource(
                                                    sql.getDataSource());
                                    if (resource
                                            instanceof
                                            org.springframework.jdbc.datasource.ConnectionHolder
                                                            holder)
                                        holder.setTimeoutInMillis(
                                                Math.min(
                                                        2000,
                                                        holder.hasTimeout()
                                                                ? holder.getTimeToLiveInMillis()
                                                                : 2000));
                                    sql.execute("set local statement_timeout = '2s'");
                                    sql.execute("set local transaction_timeout = '3s'");
                                    return call.apply(c);
                                });
                if (System.nanoTime() - startNanos > java.time.Duration.ofSeconds(5).toNanos()) {
                    outcome = "unavailable";
                    return new Unavailable<>();
                }
                outcome = classifyResult(result);
                return result;
            } catch (RuntimeException technicalFailure) {
                outcome = "technical_failure";
                return new Unavailable<>();
            } finally {
                quota.release();
                record(operation, outcome, System.nanoTime() - startNanos);
            }
        }

        public ReadPotV3Result<Pot> current(
                ExternalIdentity eui, String token, UUID id, long minimum) {
            if (eui == null || token == null || token.isBlank() || id == null || minimum < 0) {
                record("current", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("current", c -> pot.current(eui, token, id, minimum, c));
        }

        public ReadPotV3Result<Pot> at(ExternalIdentity eui, String token, UUID id, long version) {
            if (eui == null || token == null || token.isBlank() || id == null || version < 1) {
                record("at", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("at", c -> pot.at(eui, token, id, version, c));
        }

        public ReadPotV3Result<PotBalance> balance(
                ExternalIdentity eui, String token, UUID id, long version) {
            if (eui == null || token == null || token.isBlank() || id == null || version < 1) {
                record("balance", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("balance", c -> pot.balance(eui, token, id, version, c));
        }

        public ReadPotV3Result<List<Expense>> expenses(
                ExternalIdentity eui, String token, UUID id, long version, List<ExpenseRef> refs) {
            if (eui == null
                    || token == null
                    || token.isBlank()
                    || id == null
                    || version < 1
                    || refs == null
                    || refs.isEmpty()
                    || refs.size() > limits.expenseBatch()) {
                record("expenses", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            final List<ExpenseRef> captured;
            try {
                captured = List.copyOf(refs);
            } catch (NullPointerException | ConcurrentModificationException malformedCapture) {
                record("expenses", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            if (!limits.validBatch(captured)) {
                record("expenses", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("expenses", c -> pot.expenses(eui, token, id, version, captured, c));
        }

        public ReadPotV3Result<List<UUID>> visible(
                ExternalIdentity eui, String token, UUID after, int limit) {
            if (eui == null || token == null || token.isBlank() || !limits.validPage(limit)) {
                record("visible", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("visible", c -> navigation.visible(eui, token, after, limit, c));
        }

        public ReadPotV3Result<GetCurrentBindingV3.Answer> binding(
                ExternalIdentity eui, long minimum) {
            if (eui == null || minimum < 0) {
                record("binding", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query(
                    "binding",
                    c -> {
                        var answer =
                                new GetCurrentBindingV3(key -> bindingStore.read(key, c))
                                        .get(eui, minimum);
                        return new Available<>(
                                answer, answer.value() == null ? 0 : answer.value().euiVersion());
                    });
        }

        private ReadInvitation invitations(
                Function<com.kartaguez.pocoma.domain.pot.v3.Invitation, String> decrypt) {
            var read = sql.getDataSource();
            return new ReadInvitation(
                    new JdbcInvitationStore(read),
                    new JdbcPotVersionStore(read, owner),
                    new JdbcSubobjectVersionStore(read, owner),
                    bindingStore::read,
                    decrypt,
                    () ->
                            sql.queryForObject("select clock_timestamp()", java.sql.Timestamp.class)
                                    .toInstant());
        }

        public ReadPotV3Result<ReadInvitation.Link> invitationLink(
                ExternalIdentity eui,
                UUID request,
                Function<com.kartaguez.pocoma.domain.pot.v3.Invitation, String> decrypt) {
            if (eui == null || request == null) {
                record("invitation_link", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("invitation_link", c -> invitations(decrypt).link(eui, request, c));
        }

        public ReadPotV3Result<List<ReadInvitation.Place>> invitationPlaces(
                ExternalIdentity eui, String binding, String token) {
            if (eui == null
                    || binding == null
                    || binding.isBlank()
                    || token == null
                    || token.isBlank()) {
                record("invitation_places", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query(
                    "invitation_places",
                    c ->
                            invitations(
                                            i -> {
                                                throw new IllegalStateException(
                                                        "Decryption forbidden");
                                            })
                                    .places(eui, binding, token, c));
        }

        public ReadPotV3Result<Optional<CommandTerminalResult>> commandResult(
                UUID request, ExternalIdentity eui) {
            if (request == null || eui == null) {
                record("command_result", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("command_result", c -> new Available<>(commandReader.get(request, eui), 0));
        }

        public ReadPotV3Result<Optional<IdentityTerminalResult>> identityResult(
                UUID request, ExternalIdentity eui) {
            if (request == null || eui == null) {
                record("identity_result", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("identity_result", c -> new Available<>(identityReader.get(request, eui), 0));
        }
    }
}
