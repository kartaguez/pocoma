package com.kartaguez.pocoma.engine.read.execution;
import com.kartaguez.pocoma.contract.readquery.*;
import static com.kartaguez.pocoma.contract.readquery.ReadPotResult.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.port.transaction.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.*;
/** Shared READ admission, input capture, deadline and single-snapshot execution policy. */
public class ReadExecution implements PotRead.Queries {
    public static final int SQL_PERMITS=2;
    private final Semaphore quota;
    private final ReadTransactions owner;
    private final SnapshotRead pot;
    private final ReadLimits limits;
    private final boolean httpBounds;
    private final ReadObservation observation;
    public ReadExecution(ReadTransactions owner,SnapshotRead queries,ReadLimits limits,boolean httpBounds,ReadObservation observation) {
        this(owner, queries, limits, httpBounds, observation, SQL_PERMITS);
    }
    public ReadExecution(ReadTransactions owner,SnapshotRead queries,ReadLimits limits,boolean httpBounds,ReadObservation observation,int sqlPermits) {
        if (sqlPermits < 1) throw new IllegalArgumentException("sqlPermits must be >= 1");
        this.quota=new Semaphore(sqlPermits);
        this.owner=Objects.requireNonNull(owner); this.pot=Objects.requireNonNull(queries);
        this.limits=Objects.requireNonNull(limits);this.httpBounds=httpBounds;this.observation=Objects.requireNonNull(observation);
        observation.quota(quota::availablePermits);
    }
    private void record(String operation,String outcome,long elapsedNanos) { observation.record(operation,outcome,elapsedNanos); }
        private static <T> String classifyResult(ReadPotResult<T> result) {
            if (result instanceof Available) return "available";
            if (result instanceof Unavailable) return "unavailable";
            if (result instanceof InvalidInput) return "invalid_input";
            return "other";
        }

        private <T> ReadPotResult<T> query(
                String operation,
                Function<RecoveryReadContext, ReadPotResult<T>> call) {
            long startNanos = System.nanoTime();
            if (!quota.tryAcquire()) {
                observation.rejected();
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
                var result = owner.read(call);
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

        private <T> ReadPotResult<T> invalidInput(String operation) {
            record(operation, "invalid_input", 0L);
            return new InvalidInput<>();
        }

        private static boolean isNonBlank(String value) {
            return value != null && !value.isBlank();
        }

        private static boolean hasValidPotRequest(
                ExternalIdentity eui, String token, UUID id) {
            return eui != null && isNonBlank(token) && id != null;
        }

        public ReadPotResult<Pot> current(
                ExternalIdentity eui, String token, UUID id, long minimum) {
            if (!hasValidPotRequest(eui, token, id) || minimum < 0) {
                return invalidInput("current");
            }
            return query("current", c -> pot.current(eui, token, id, minimum, c));
        }

        public ReadPotResult<Pot> at(ExternalIdentity eui, String token, UUID id, long version) {
            if (!hasValidPotRequest(eui, token, id) || version < 1) {
                return invalidInput("at");
            }
            return query("at", c -> pot.at(eui, token, id, version, c));
        }

        public ReadPotResult<PotBalance> balance(
                ExternalIdentity eui, String token, UUID id, long version) {
            if (!hasValidPotRequest(eui, token, id) || version < 1) {
                return invalidInput("balance");
            }
            return query("balance", c -> pot.balance(eui, token, id, version, c));
        }

        public ReadPotResult<List<Expense>> expenses(
                ExternalIdentity eui, String token, UUID id, long version, List<ExpenseRef> refs) {
            if (!hasValidPotRequest(eui, token, id) || version < 1) {
                return invalidInput("expenses");
            }
            if (refs == null || refs.isEmpty()) {
                return invalidInput("expenses");
            }
            if (refs.size() > limits.expenseBatch()) {
                return invalidInput("expenses");
            }
            final List<ExpenseRef> captured;
            try {
                captured = List.copyOf(refs);
            } catch (NullPointerException | ConcurrentModificationException malformedCapture) {
                return invalidInput("expenses");
            }
            if (!limits.validBatch(captured)) {
                return invalidInput("expenses");
            }
            return query("expenses", c -> pot.expenses(eui, token, id, version, captured, c));
        }

        public ReadPotResult<List<UUID>> visible(
                ExternalIdentity eui, String token, UUID after, int limit) {
            if (eui == null || !isNonBlank(token)) {
                return invalidInput("visible");
            }
            if (!limits.validPage(limit)) {
                return invalidInput("visible");
            }
            return query("visible", c -> pot.visible(eui, token, after, limit, c));
        }

        public ReadPotResult<BindingRead.Answer> binding(
                ExternalIdentity eui, long minimum) {
            if (eui == null || minimum < 0) {
                return invalidInput("binding");
            }
            return query(
                    "binding",
                    c -> {
                        var answer = pot.binding(eui, minimum, c);
                        return new Available<>(
                                answer, answer.value() == null ? 0 : answer.value().euiVersion());
                    });
        }

        public ReadPotResult<InvitationRead.Link> invitationLink(
                ExternalIdentity eui,
                UUID request,
                Function<com.kartaguez.pocoma.domain.pot.Invitation, String> decrypt) {
            if (eui == null || request == null) {
                return invalidInput("invitation_link");
            }
            return query("invitation_link", c -> pot.invitationLink(eui, request, decrypt, c));
        }

        public ReadPotResult<List<InvitationRead.Place>> invitationPlaces(
                ExternalIdentity eui, String binding, String token) {
            if (eui == null) {
                return invalidInput("invitation_places");
            }
            if (!isNonBlank(binding) || !isNonBlank(token)) {
                return invalidInput("invitation_places");
            }
            return query(
                    "invitation_places",
                    c -> pot.invitationPlaces(eui, binding, token, c));
        }

        public ReadPotResult<Optional<CommandTerminalResult>> commandResult(
                UUID request, ExternalIdentity eui) {
            if (request == null || eui == null) {
                record("command_result", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("command_result", c -> new Available<>(pot.commandResult(request, eui, c), 0));
        }

        public ReadPotResult<Optional<IdentityTerminalResult>> identityResult(
                UUID request, ExternalIdentity eui) {
            if (request == null || eui == null) {
                record("identity_result", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("identity_result", c -> new Available<>(pot.identityResult(request, eui, c), 0));
        }
}
