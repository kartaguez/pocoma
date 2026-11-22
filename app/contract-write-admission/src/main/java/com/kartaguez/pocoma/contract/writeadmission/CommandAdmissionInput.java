package com.kartaguez.pocoma.contract.writeadmission;

import static com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure.require;
import static com.kartaguez.pocoma.contract.writeadmission.AdmissionFields.list;
import static com.kartaguez.pocoma.contract.writeadmission.AdmissionFields.nonblank;
import static com.kartaguez.pocoma.contract.writeadmission.AdmissionFields.object;
import static com.kartaguez.pocoma.contract.writeadmission.AdmissionFields.text;
import static com.kartaguez.pocoma.contract.writeadmission.AdmissionFields.uuid;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.contract.writeadmission.PotCommand.AddShareholders;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.Attachment;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.CreateExpense;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.CreateInvitation;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.CreatePot;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.DeleteExpense;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.DeletePot;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.DeleteShareholder;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.Intent;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.UpdateExpenseDetails;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.UpdateExpenseFinancials;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.UpdatePotDetails;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.UpdateShareholderDetails;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.UpdateShareholderWeights;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.invitation.InvitationTokenHash;
import com.kartaguez.pocoma.domain.pot.ShareholderTransitions;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

/** Public V1 decoder. Parser safety limits remain applicable to historical comparisons. */
public final class CommandAdmissionInput {
    private final PotCommand command;
    private final String operation;
    private final byte[] canonical;
    private final int longestText;

    private CommandAdmissionInput(
            PotCommand command, String operation, Object normalized, int longestText) {
        this.command = command;
        this.operation = operation;
        this.canonical = AdmissionFields.canonical(normalized);
        this.longestText = longestText;
    }

    public String operation() {
        return operation;
    }

    public byte[] canonical(int version) {
        if (version != 1) throw new AdmissionFailure(503);
        return canonical.clone();
    }

    public void validateCurrent(int textBytes) {
        require(longestText <= textBytes);
    }

    public PotCommand command(UUID id) {
        return new PotCommand(
                id,
                command.requester(),
                command.bindingToken(),
                command.potId(),
                command.observedVersion(),
                command.intent(),
                command.attachments());
    }

    private record DecodedPayload(Map<String, Object> payload, Intent intent) {}

    public static CommandAdmissionInput decode(
            Object raw, PotCommand.Eui requester, int rationalDigits) {
        try {
            require(raw instanceof Map<?, ?> rawMap && rawMap.get("operation") instanceof String);
            String rawOp = (String) ((Map<?, ?>) raw).get("operation");
            var n =
                    "CreatePot".equals(rawOp)
                            ? object(raw, "operation", "observedVersion", "bindingToken", "payload")
                            : object(
                                    raw,
                                    "operation",
                                    "potId",
                                    "observedVersion",
                                    "bindingToken",
                                    "payload");
            String op = text(n, "operation");
            PotId pot = "CreatePot".equals(op) ? null : new PotId(uuid(n, "potId"));
            String token = nonblank(n, "bindingToken");
            Object observation = n.get("observedVersion");
            require(observation instanceof Integer || observation instanceof Long);
            long observed = ((Number) observation).longValue();
            n.put("observedVersion", observed);
            var modes = new HashMap<ShareholderId, Attachment>();
            var decoded = decodeOperation(op, n.get("payload"), pot, rationalDigits, modes);
            var p = decoded.payload();
            if (p.containsKey("date")) p.put("date", LocalDate.parse(text(p, "date")).toString());
            n.put("payload", p);
            n.put(
                    "requester",
                    Map.of("issuer", requester.issuer(), "subject", requester.subject()));
            var c = new PotCommand(new UUID(0, 0), requester, token, pot, observed, decoded.intent(), modes);
            return new CommandAdmissionInput(c, op, n, maxText(n));
        } catch (AdmissionFailure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw new AdmissionFailure(400);
        }
    }

    private static DecodedPayload decodeOperation(
            String op,
            Object rawPayload,
            PotId pot,
            int rationalDigits,
            Map<ShareholderId, Attachment> modes) {
        return switch (op) {
            case "CreateInvitation" -> decodeCreateInvitation(rawPayload);
            case "AttachShareholderViaInvitation" -> {
                var p = object(rawPayload, "invitationToken", "shareholderId");
                yield new DecodedPayload(
                        p,
                        new PotCommand.AttachShareholderViaInvitation(
                                InvitationTokenHash.of(nonblank(p, "invitationToken")),
                                new ShareholderId(uuid(p, "shareholderId"))));
            }
            case "CreatePot", "UpdatePotDetails" -> {
                var p = object(rawPayload, "label");
                var label = new Label(text(p, "label"));
                yield new DecodedPayload(
                        p,
                        op.equals("CreatePot") ? new CreatePot(label) : new UpdatePotDetails(label));
            }
            case "DeletePot" -> new DecodedPayload(object(rawPayload), new DeletePot());
            case "AddShareholders" -> decodeAddShareholders(rawPayload, rationalDigits, modes);
            case "UpdateShareholderDetails" -> decodeUpdateShareholderDetails(rawPayload, modes);
            case "UpdateShareholderWeights" ->
                    decodeUpdateShareholderWeights(rawPayload, rationalDigits);
            case "DeleteShareholder" -> {
                var p = object(rawPayload, "id");
                yield new DecodedPayload(p, new DeleteShareholder(new ShareholderId(uuid(p, "id"))));
            }
            case "CreateExpense" -> decodeCreateExpense(rawPayload, pot, rationalDigits);
            case "UpdateExpenseDetails" -> {
                var p = object(rawPayload, "id", "label", "date");
                yield new DecodedPayload(
                        p,
                        new UpdateExpenseDetails(
                                new ExpenseId(uuid(p, "id")),
                                new Label(text(p, "label")),
                                LocalDate.parse(text(p, "date"))));
            }
            case "UpdateExpenseFinancials" -> {
                var p = object(rawPayload, "id", "amount", "payerId", "participants");
                var id = new ExpenseId(uuid(p, "id"));
                yield new DecodedPayload(
                        p, new UpdateExpenseFinancials(financials(p, pot, id, rationalDigits)));
            }
            case "DeleteExpense" -> {
                var p = object(rawPayload, "id");
                yield new DecodedPayload(p, new DeleteExpense(new ExpenseId(uuid(p, "id"))));
            }
            default -> throw new AdmissionFailure(400);
        };
    }

    private static DecodedPayload decodeCreateInvitation(Object rawPayload) {
        var p = object(rawPayload, "expiresAt");
        var expiresAt = java.time.Instant.parse(text(p, "expiresAt"));
        require(text(p, "expiresAt").endsWith("Z"));
        require(expiresAt.getNano() % 1000 == 0);
        // Preserve the former Timestamp round-trip range without an SQL type in the contract.
        require(
                expiresAt.getEpochSecond() >= Long.MIN_VALUE / 1000L
                        && expiresAt.getEpochSecond() <= Long.MAX_VALUE / 1000L);
        p.put("expiresAt", expiresAt.toString());
        return new DecodedPayload(p, new CreateInvitation(expiresAt));
    }

    private static DecodedPayload decodeAddShareholders(
            Object rawPayload, int rationalDigits, Map<ShareholderId, Attachment> modes) {
        var p = object(rawPayload, "targets");
        var values = new ArrayList<PotCommand.ShareholderCreation>();
        var normalized = new ArrayList<Object>();
        for (var rawTarget : list(p, "targets")) {
            var t = object(rawTarget, "name", "attachment", "weight");
            var mode = Attachment.valueOf(text(t, "attachment"));
            require(mode == Attachment.FREE || mode == Attachment.SELF);
            values.add(
                    PotCommand.ShareholderCreation.of(
                            new Name(text(t, "name")), mode, weight(t, "weight", rationalDigits)));
            normalized.add(t);
        }
        p.put("targets", normalized);
        return new DecodedPayload(p, new AddShareholders(values));
    }

    private static DecodedPayload decodeUpdateShareholderDetails(
            Object rawPayload, Map<ShareholderId, Attachment> modes) {
        var p = object(rawPayload, "targets");
        var values = new ArrayList<ShareholderTransitions.DetailsIntent>();
        var normalized = new ArrayList<Object>();
        for (var rawTarget : list(p, "targets")) {
            var t = object(rawTarget, "id", "name", "attachment");
            var id = new ShareholderId(uuid(t, "id"));
            var mode = Attachment.valueOf(text(t, "attachment"));
            require(
                    mode == Attachment.KEEP
                            || mode == Attachment.SELF
                            || mode == Attachment.DETACH);
            modes.put(id, mode);
            values.add(
                    new ShareholderTransitions.DetailsIntent(
                            id, new Name(text(t, "name")), Optional.empty()));
            normalized.add(t);
        }
        p.put("targets", normalized);
        return new DecodedPayload(p, new UpdateShareholderDetails(values));
    }

    private static DecodedPayload decodeUpdateShareholderWeights(
            Object rawPayload, int rationalDigits) {
        var p = object(rawPayload, "targets");
        var values = new ArrayList<ShareholderTransitions.WeightIntent>();
        var normalized = new ArrayList<Object>();
        for (var rawTarget : list(p, "targets")) {
            var t = object(rawTarget, "id", "weight");
            var id = new ShareholderId(uuid(t, "id"));
            values.add(
                    new ShareholderTransitions.WeightIntent(
                            id, weight(t, "weight", rationalDigits)));
            normalized.add(t);
        }
        p.put("targets", normalized);
        return new DecodedPayload(p, new UpdateShareholderWeights(values));
    }

    private static DecodedPayload decodeCreateExpense(
            Object rawPayload, PotId pot, int rationalDigits) {
        var p = object(rawPayload, "label", "date", "amount", "payerId", "participants");
        var amount = new com.kartaguez.pocoma.domain.pot.value.Amount(fraction(p, "amount", rationalDigits));
        var payer = new ShareholderId(uuid(p, "payerId"));
        var entries = participations(p, rationalDigits);
        return new DecodedPayload(
                p,
                new CreateExpense(
                        new Label(text(p, "label")),
                        LocalDate.parse(text(p, "date")),
                        amount,
                        payer,
                        entries));
    }

    private static ExpenseFinancials financials(
            Map<String, Object> p, PotId pot, ExpenseId id, int digits) {
        var amount = new com.kartaguez.pocoma.domain.pot.value.Amount(fraction(p, "amount", digits));
        var payer = new ShareholderId(uuid(p, "payerId"));
        return ExpenseFinancials.of(pot, id, amount, payer, participations(p, digits));
    }

    private static List<ExpenseFinancials.Participation> participations(
            Map<String, Object> p, int digits) {
        var entries = new ArrayList<ExpenseFinancials.Participation>();
        var normalized = new ArrayList<Map<String, Object>>();
        for (var raw : list(p, "participants")) {
            var t = object(raw, "id", "weight");
            var sid = new ShareholderId(uuid(t, "id"));
            entries.add(
                    new ExpenseFinancials.Participation(
                            sid,
                            t.get("weight") == null
                                    ? Optional.empty()
                                    : Optional.of(weight(t, "weight", digits))));
            normalized.add(t);
        }
        normalized.sort(Comparator.comparing(t -> (String) t.get("id")));
        p.put("participants", normalized);
        return entries;
    }

    private static com.kartaguez.pocoma.domain.pot.value.Weight weight(
            Map<String, Object> m, String key, int digits) {
        return new com.kartaguez.pocoma.domain.pot.value.Weight(fraction(m, key, digits));
    }

    private static com.kartaguez.pocoma.domain.pot.value.Fraction fraction(
            Map<String, Object> m, String key, int digits) {
        var f = object(m.get(key), "numerator", "denominator");
        String a = text(f, "numerator"), b = text(f, "denominator");
        require(
                a.length() <= digits + 1
                        && b.length() <= digits + 1
                        && a.matches("-?[0-9]{1," + digits + "}")
                        && b.matches("-?[0-9]{1," + digits + "}"));
        var value =
                new com.kartaguez.pocoma.domain.pot.value.Fraction(
                        new BigInteger(a), new BigInteger(b));
        m.put(
                key,
                Map.of(
                        "numerator",
                        value.numerator().toString(),
                        "denominator",
                        value.denominator().toString()));
        return value;
    }

    private static int maxText(Object value) {
        if (value instanceof String s)
            return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (value instanceof Map<?, ?> m)
            return m.values().stream().mapToInt(CommandAdmissionInput::maxText).max().orElse(0);
        if (value instanceof List<?> l)
            return l.stream().mapToInt(CommandAdmissionInput::maxText).max().orElse(0);
        return 0;
    }

    @Override
    public String toString() {
        return "CommandAdmissionInput[REDACTED]";
    }
}
