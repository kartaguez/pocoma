package com.kartaguez.pocoma.engine.read.execution.v3;
import com.kartaguez.pocoma.contract.readquery.*;
import static com.kartaguez.pocoma.contract.readquery.ReadPotV3Result.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.*;
/** Shared READ admission, input capture, deadline and single-snapshot execution policy. */
public class ReadExecution implements PotRead.Queries {
    public static final int SQL_PERMITS=2;
    private final Semaphore quota=new Semaphore(SQL_PERMITS);
    private final ReadTransactions owner;
    private final SnapshotRead pot;
    private final ReadLimitsV3 limits;
    private final boolean httpBounds;
    private final ReadObservation observation;
    public ReadExecution(ReadTransactions owner,SnapshotRead queries,ReadLimitsV3 limits,boolean httpBounds,ReadObservation observation) {
        this.owner=Objects.requireNonNull(owner); this.pot=Objects.requireNonNull(queries);
        this.limits=Objects.requireNonNull(limits);this.httpBounds=httpBounds;this.observation=Objects.requireNonNull(observation);
        observation.quota(quota::availablePermits);
    }
    private void record(String operation,String outcome,long elapsedNanos) { observation.record(operation,outcome,elapsedNanos); }
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
            return query("visible", c -> pot.visible(eui, token, after, limit, c));
        }

        public ReadPotV3Result<BindingRead.Answer> binding(
                ExternalIdentity eui, long minimum) {
            if (eui == null || minimum < 0) {
                record("binding", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query(
                    "binding",
                    c -> {
                        var answer = pot.binding(eui, minimum, c);
                        return new Available<>(
                                answer, answer.value() == null ? 0 : answer.value().euiVersion());
                    });
        }

        public ReadPotV3Result<InvitationRead.Link> invitationLink(
                ExternalIdentity eui,
                UUID request,
                Function<com.kartaguez.pocoma.domain.pot.v3.Invitation, String> decrypt) {
            if (eui == null || request == null) {
                record("invitation_link", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("invitation_link", c -> pot.invitationLink(eui, request, decrypt, c));
        }

        public ReadPotV3Result<List<InvitationRead.Place>> invitationPlaces(
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
                    c -> pot.invitationPlaces(eui, binding, token, c));
        }

        public ReadPotV3Result<Optional<CommandTerminalResult>> commandResult(
                UUID request, ExternalIdentity eui) {
            if (request == null || eui == null) {
                record("command_result", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("command_result", c -> new Available<>(pot.commandResult(request, eui, c), 0));
        }

        public ReadPotV3Result<Optional<IdentityTerminalResult>> identityResult(
                UUID request, ExternalIdentity eui) {
            if (request == null || eui == null) {
                record("identity_result", "invalid_input", 0L);
                return new InvalidInput<>();
            }
            return query("identity_result", c -> new Available<>(pot.identityResult(request, eui, c), 0));
        }
}
