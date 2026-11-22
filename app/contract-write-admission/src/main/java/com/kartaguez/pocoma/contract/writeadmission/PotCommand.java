package com.kartaguez.pocoma.contract.writeadmission;

import com.kartaguez.pocoma.domain.pot.*;
import com.kartaguez.pocoma.domain.pot.expense.*;
import com.kartaguez.pocoma.domain.pot.invitation.*;
import com.kartaguez.pocoma.domain.pot.revision.*;
import com.kartaguez.pocoma.domain.pot.shareholder.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Immutable admitted intention. The actor is resolved by WRITE, never supplied here. */
public record PotCommand(
        UUID requestId,
        Eui requester,
        String bindingToken,
        PotId potId,
        long observedVersion,
        Intent intent,
        Map<ShareholderId, Attachment> attachments) {
    public enum Attachment {
        FREE,
        SELF,
        KEEP,
        DETACH
    }

    /** Historical internal callers keep their concrete attachment intentions. */
    public PotCommand(
            UUID requestId,
            Eui requester,
            String bindingToken,
            PotId potId,
            long observedVersion,
            Intent intent) {
        this(requestId, requester, bindingToken, potId, observedVersion, intent, Map.of());
    }

    public PotCommand {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(requester);
        Objects.requireNonNull(intent);
        if (potId == null && !(intent instanceof CreatePot)) {
            throw new NullPointerException("PotId required");
        }
        attachments = Map.copyOf(attachments);
        if (!attachments.isEmpty()) {
            Set<ShareholderId> ids;
            if (intent instanceof AddShareholders additions) {
                if (additions.serverAssigned()) {
                    throw new IllegalArgumentException(
                            "Server-assigned AddShareholders carries attachments per target");
                }
                ids = new HashSet<>();
                for (var t : additions.creations()) {
                    ids.add(t.legacyId());
                    if (t.legacyUserId().isPresent()
                            || !Set.of(Attachment.FREE, Attachment.SELF)
                                    .contains(attachments.get(t.legacyId())))
                        throw new IllegalArgumentException("Invalid creation attachment");
                }
            } else if (intent instanceof UpdateShareholderDetails details) {
                ids = new HashSet<>();
                for (var t : details.targets()) {
                    ids.add(t.id());
                    if (t.userId().isPresent()
                            || !Set.of(Attachment.KEEP, Attachment.SELF, Attachment.DETACH)
                                    .contains(attachments.get(t.id())))
                        throw new IllegalArgumentException("Invalid details attachment");
                }
            } else throw new IllegalArgumentException("Attachments outside shareholder intention");
            if (!ids.equals(attachments.keySet()))
                throw new IllegalArgumentException("Attachment targets differ");
        }
        if (bindingToken == null || bindingToken.isBlank())
            throw new IllegalArgumentException("Binding token required");
        if (observedVersion < 0
                || (intent instanceof CreatePot
                                || intent instanceof CreateInvitation
                                || intent instanceof AttachShareholderViaInvitation
                        ? observedVersion != 0
                        : observedVersion == 0))
            throw new IllegalArgumentException("Invalid observation");
    }

    @Override
    public String toString() {
        return "PotCommand[requestId=" + requestId + ", potId=" + potId + "]";
    }

    public record Eui(String issuer, String subject) {
        public Eui {
            Objects.requireNonNull(issuer);
            Objects.requireNonNull(subject);
        }
    }

    public sealed interface Intent
            permits CreateInvitation,
                    AttachShareholderViaInvitation,
                    CreatePot,
                    UpdatePotDetails,
                    DeletePot,
                    AddShareholders,
                    UpdateShareholderDetails,
                    UpdateShareholderWeights,
                    DeleteShareholder,
                    CreateExpense,
                    UpdateExpenseDetails,
                    UpdateExpenseFinancials,
                    DeleteExpense {}

    public record CreateInvitation(Instant expiresAt) implements Intent {
        public CreateInvitation {
            Objects.requireNonNull(expiresAt);
            // Finite PostgreSQL timestamps, exact microseconds. Storage bounds, not a business TTL.
            // PostgreSQL 18 src/include/datatype/timestamp.h MIN_TIMESTAMP/END_TIMESTAMP, epoch
            // 2000.
            var minimum = Instant.ofEpochSecond(946684800L - 211813488000L);
            var maximum = Instant.ofEpochSecond(946684800L + 9223371331200L);
            if (expiresAt.getNano() % 1000 != 0
                    || expiresAt.isBefore(minimum)
                    || !expiresAt.isBefore(maximum))
                throw new IllegalArgumentException("Unrepresentable Invitation expiration");
        }
    }

    public record AttachShareholderViaInvitation(String tokenHash, ShareholderId shareholderId)
            implements Intent {
        public AttachShareholderViaInvitation {
            Objects.requireNonNull(tokenHash);
            Objects.requireNonNull(shareholderId);
            if (!tokenHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invitation token required");
        }

        @Override
        public String toString() {
            return "AttachShareholderViaInvitation[redacted]";
        }
    }

    public record CreatePot(Label label) implements Intent {
        public CreatePot {
            Objects.requireNonNull(label);
        }
    }

    public record UpdatePotDetails(Label label) implements Intent {
        public UpdatePotDetails {
            Objects.requireNonNull(label);
        }
    }

    public record DeletePot() implements Intent {}

    /**
     * Ordered Shareholder creation target. New admissions omit {@code legacyId} and carry an
     * explicit {@code attachment} ({@link Attachment#FREE} or {@link Attachment#SELF}); historical
     * durable commands may still carry {@code legacyId} and optional {@code legacyUserId}.
     */
    public record ShareholderCreation(
            ShareholderId legacyId,
            Name name,
            Optional<UserId> legacyUserId,
            Attachment attachment,
            Weight defaultWeight) {
        public ShareholderCreation {
            Objects.requireNonNull(name);
            Objects.requireNonNull(legacyUserId);
            Objects.requireNonNull(defaultWeight);
            if (legacyId == null) {
                if (legacyUserId.isPresent()
                        || (attachment != Attachment.FREE && attachment != Attachment.SELF)) {
                    throw new IllegalArgumentException("Invalid server-assigned creation target");
                }
            } else if (attachment != null
                    && attachment != Attachment.FREE
                    && attachment != Attachment.SELF) {
                throw new IllegalArgumentException("Invalid creation attachment");
            }
        }

        public static ShareholderCreation of(
                Name name, Attachment attachment, Weight defaultWeight) {
            return new ShareholderCreation(
                    null, name, Optional.empty(), Objects.requireNonNull(attachment), defaultWeight);
        }

        public static ShareholderCreation legacy(ShareholderTransitions.Creation target) {
            if (target == null) throw new IllegalArgumentException("Null target");
            return new ShareholderCreation(
                    target.id(), target.name(), target.userId(), null, target.defaultWeight());
        }

        public boolean serverAssigned() {
            return legacyId == null;
        }
    }

    public record AddShareholders(List<ShareholderCreation> creations) implements Intent {
        public AddShareholders {
            if (creations == null || creations.isEmpty())
                throw new IllegalArgumentException("Targets required");
            boolean serverAssigned = false;
            boolean first = true;
            var seen = new HashSet<ShareholderId>();
            for (var target : creations) {
                if (target == null) throw new IllegalArgumentException("Null target");
                if (first) {
                    serverAssigned = target.serverAssigned();
                    first = false;
                } else if (target.serverAssigned() != serverAssigned) {
                    throw new IllegalArgumentException("Mixed server-assigned and legacy targets");
                }
                if (!serverAssigned && !seen.add(target.legacyId())) {
                    throw new IllegalArgumentException("Duplicate target");
                }
            }
            creations = List.copyOf(creations);
        }

        public static AddShareholders of(List<ShareholderCreation> creations) {
            return new AddShareholders(creations);
        }

        public static AddShareholders legacy(List<ShareholderTransitions.Creation> targets) {
            if (targets == null || targets.isEmpty())
                throw new IllegalArgumentException("Targets required");
            return new AddShareholders(
                    targets.stream().map(ShareholderCreation::legacy).toList());
        }

        public boolean serverAssigned() {
            return creations.getFirst().serverAssigned();
        }

        /** Legacy view for historical commands with pre-assigned ShareholderIds. */
        public List<ShareholderTransitions.Creation> targets() {
            if (serverAssigned()) {
                throw new IllegalStateException("Server-assigned AddShareholders has no input IDs");
            }
            return creations.stream()
                    .map(
                            c ->
                                    new ShareholderTransitions.Creation(
                                            c.legacyId(),
                                            c.name(),
                                            c.legacyUserId(),
                                            c.defaultWeight()))
                    .toList();
        }
    }

    public record UpdateShareholderDetails(List<ShareholderTransitions.DetailsIntent> targets)
            implements Intent {
        public UpdateShareholderDetails {
            targets = PotCommand.targets(targets, ShareholderTransitions.DetailsIntent::id);
        }
    }

    public record UpdateShareholderWeights(List<ShareholderTransitions.WeightIntent> targets)
            implements Intent {
        public UpdateShareholderWeights {
            targets = PotCommand.targets(targets, ShareholderTransitions.WeightIntent::id);
        }
    }

    private static <T> List<T> targets(
            List<T> targets, java.util.function.Function<T, ShareholderId> id) {
        if (targets == null || targets.isEmpty())
            throw new IllegalArgumentException("Targets required");
        var seen = new HashSet<ShareholderId>();
        for (var target : targets) {
            if (target == null) throw new IllegalArgumentException("Null target");
            if (!seen.add(id.apply(target))) throw new IllegalArgumentException("Duplicate target");
        }
        return List.copyOf(targets);
    }

    public record DeleteShareholder(ShareholderId id) implements Intent {
        public DeleteShareholder {
            Objects.requireNonNull(id);
        }
    }

    public record CreateExpense(
            PotId legacyPotId,
            ExpenseId legacyId,
            Label label,
            LocalDate date,
            Amount amount,
            ShareholderId payerId,
            Map<ShareholderId, Optional<Weight>> participants)
            implements Intent {
        public CreateExpense {
            if ((legacyPotId == null) != (legacyId == null)) {
                throw new IllegalArgumentException(
                        "Legacy CreateExpense requires both legacyPotId and legacyId");
            }
            Objects.requireNonNull(label);
            Objects.requireNonNull(date);
            Objects.requireNonNull(amount);
            Objects.requireNonNull(payerId);
            if (participants == null || participants.isEmpty())
                throw new IllegalArgumentException("Participants required");
            var copy = new LinkedHashMap<ShareholderId, Optional<Weight>>();
            for (var entry : participants.entrySet()) {
                Objects.requireNonNull(entry.getKey());
                Objects.requireNonNull(entry.getValue());
                copy.put(entry.getKey(), entry.getValue());
            }
            participants = Collections.unmodifiableMap(copy);
        }

        /** Server-assigned Expense creation (no client-supplied ExpenseId). */
        public CreateExpense(
                Label label,
                LocalDate date,
                Amount amount,
                ShareholderId payerId,
                List<ExpenseFinancials.Participation> participations) {
            this(null, null, label, date, amount, payerId, toParticipantMap(participations));
        }

        /** Historical Expense creation retaining a pre-assigned ExpenseId. */
        public CreateExpense(ExpenseDetails details, ExpenseFinancials financials) {
            this(
                    Objects.requireNonNull(details).potId(),
                    details.expenseId(),
                    details.label(),
                    details.date(),
                    Objects.requireNonNull(financials).amount(),
                    financials.payerId(),
                    financials.participants());
            if (!details.potId().equals(financials.potId())
                    || !details.expenseId().equals(financials.expenseId())) {
                throw new IllegalArgumentException("Expense details/financials mismatch");
            }
        }

        private static Map<ShareholderId, Optional<Weight>> toParticipantMap(
                List<ExpenseFinancials.Participation> participations) {
            if (participations == null || participations.isEmpty())
                throw new IllegalArgumentException("Participants required");
            var map = new LinkedHashMap<ShareholderId, Optional<Weight>>();
            for (var p : participations) {
                Objects.requireNonNull(p);
                if (map.put(p.shareholderId(), p.specificWeight()) != null) {
                    throw new IllegalArgumentException("Duplicate participant");
                }
            }
            return map;
        }

        public boolean serverAssigned() {
            return legacyId == null;
        }

        public ExpenseDetails details(PotId potId, ExpenseId expenseId) {
            return new ExpenseDetails(potId, expenseId, label, date);
        }

        public ExpenseFinancials financials(PotId potId, ExpenseId expenseId) {
            var entries =
                    participants.entrySet().stream()
                            .map(e -> new ExpenseFinancials.Participation(e.getKey(), e.getValue()))
                            .toList();
            return ExpenseFinancials.of(potId, expenseId, amount, payerId, entries);
        }
    }

    public record UpdateExpenseDetails(ExpenseId id, Label label, LocalDate date)
            implements Intent {
        public UpdateExpenseDetails {
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(date);
        }
    }

    public record UpdateExpenseFinancials(ExpenseFinancials financials) implements Intent {
        public UpdateExpenseFinancials {
            Objects.requireNonNull(financials);
        }
    }

    public record DeleteExpense(ExpenseId id) implements Intent {
        public DeleteExpense {
            Objects.requireNonNull(id);
        }
    }
}
