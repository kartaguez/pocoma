package com.kartaguez.pocoma.engine.admit.command.v3;

import static com.kartaguez.pocoma.contracts.command.v3.AdmissionFailure.require;
import static com.kartaguez.pocoma.contracts.command.v3.AdmissionFields.*;

import com.kartaguez.pocoma.contracts.command.v3.*;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand.*;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;

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

    public static CommandAdmissionInput decode(
            Object raw, PotCommand.Eui requester, int rationalDigits) {
        try {
            var n = object(raw, "operation", "potId", "observedVersion", "bindingToken", "payload");
            String op = text(n, "operation");
            var pot = new PotId(uuid(n, "potId"));
            String token = nonblank(n, "bindingToken");
            Object observation = n.get("observedVersion");
            require(observation instanceof Integer || observation instanceof Long);
            long observed = ((Number) observation).longValue();
            n.put("observedVersion", observed);
            var modes = new HashMap<ShareholderId, Attachment>();
            Map<String, Object> p;
            Intent intent;
            switch (op) {
                case "CreateInvitation" -> {
                    p = object(n.get("payload"), "expiresAt");
                    var expiresAt = java.time.Instant.parse(text(p, "expiresAt"));
                    require(text(p, "expiresAt").endsWith("Z"));
                    require(expiresAt.getNano() % 1000 == 0);
                    require(java.sql.Timestamp.from(expiresAt).toInstant().equals(expiresAt));
                    p.put("expiresAt", expiresAt.toString());
                    intent = new CreateInvitation(expiresAt);
                }
                case "AttachShareholderViaInvitation" -> {
                    p = object(n.get("payload"), "invitationToken", "shareholderId");
                    intent =
                            new PotCommand.AttachShareholderViaInvitation(
                                    InvitationTokenHash.of(nonblank(p, "invitationToken")),
                                    new ShareholderId(uuid(p, "shareholderId")));
                }
                case "CreatePot", "UpdatePotDetails" -> {
                    p = object(n.get("payload"), "label");
                    var label = new Label(text(p, "label"));
                    intent =
                            op.equals("CreatePot")
                                    ? new CreatePot(label)
                                    : new UpdatePotDetails(label);
                }
                case "DeletePot" -> {
                    p = object(n.get("payload"));
                    intent = new DeletePot();
                }
                case "AddShareholders" -> {
                    p = object(n.get("payload"), "targets");
                    var values = new ArrayList<ShareholderTransitions.Creation>();
                    var normalized = new ArrayList<Object>();
                    for (var rawTarget : list(p, "targets")) {
                        var t = object(rawTarget, "id", "name", "attachment", "weight");
                        var id = new ShareholderId(uuid(t, "id"));
                        var mode = Attachment.valueOf(text(t, "attachment"));
                        require(mode == Attachment.FREE || mode == Attachment.SELF);
                        modes.put(id, mode);
                        values.add(
                                new ShareholderTransitions.Creation(
                                        id,
                                        new Name(text(t, "name")),
                                        Optional.empty(),
                                        weight(t, "weight", rationalDigits)));
                        normalized.add(t);
                    }
                    p.put("targets", normalized);
                    intent = new AddShareholders(values);
                }
                case "UpdateShareholderDetails" -> {
                    p = object(n.get("payload"), "targets");
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
                    intent = new UpdateShareholderDetails(values);
                }
                case "UpdateShareholderWeights" -> {
                    p = object(n.get("payload"), "targets");
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
                    intent = new UpdateShareholderWeights(values);
                }
                case "DeleteShareholder" -> {
                    p = object(n.get("payload"), "id");
                    intent = new DeleteShareholder(new ShareholderId(uuid(p, "id")));
                }
                case "CreateExpense" -> {
                    p =
                            object(
                                    n.get("payload"),
                                    "id",
                                    "label",
                                    "date",
                                    "amount",
                                    "payerId",
                                    "participants");
                    var id = new ExpenseId(uuid(p, "id"));
                    intent =
                            new CreateExpense(
                                    new ExpenseDetails(
                                            pot,
                                            id,
                                            new Label(text(p, "label")),
                                            LocalDate.parse(text(p, "date"))),
                                    financials(p, pot, id, rationalDigits));
                }
                case "UpdateExpenseDetails" -> {
                    p = object(n.get("payload"), "id", "label", "date");
                    intent =
                            new UpdateExpenseDetails(
                                    new ExpenseId(uuid(p, "id")),
                                    new Label(text(p, "label")),
                                    LocalDate.parse(text(p, "date")));
                }
                case "UpdateExpenseFinancials" -> {
                    p = object(n.get("payload"), "id", "amount", "payerId", "participants");
                    var id = new ExpenseId(uuid(p, "id"));
                    intent = new UpdateExpenseFinancials(financials(p, pot, id, rationalDigits));
                }
                case "DeleteExpense" -> {
                    p = object(n.get("payload"), "id");
                    intent = new DeleteExpense(new ExpenseId(uuid(p, "id")));
                }
                default -> throw new AdmissionFailure(400);
            }
            if (p.containsKey("date")) p.put("date", LocalDate.parse(text(p, "date")).toString());
            n.put("payload", p);
            n.put(
                    "requester",
                    Map.of("issuer", requester.issuer(), "subject", requester.subject()));
            var c = new PotCommand(new UUID(0, 0), requester, token, pot, observed, intent, modes);
            return new CommandAdmissionInput(c, op, n, maxText(n));
        } catch (AdmissionFailure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw new AdmissionFailure(400);
        }
    }

    private static ExpenseFinancials financials(
            Map<String, Object> p, PotId pot, ExpenseId id, int digits) {
        var amount = new com.kartaguez.pocoma.domain.pot.v3.Amount(fraction(p, "amount", digits));
        var payer = new ShareholderId(uuid(p, "payerId"));
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
        return ExpenseFinancials.of(pot, id, amount, payer, entries);
    }

    private static com.kartaguez.pocoma.domain.pot.v3.Weight weight(
            Map<String, Object> m, String key, int digits) {
        return new com.kartaguez.pocoma.domain.pot.v3.Weight(fraction(m, key, digits));
    }

    private static com.kartaguez.pocoma.domain.pot.v3.Fraction fraction(
            Map<String, Object> m, String key, int digits) {
        var f = object(m.get(key), "numerator", "denominator");
        String a = text(f, "numerator"), b = text(f, "denominator");
        require(
                a.length() <= digits + 1
                        && b.length() <= digits + 1
                        && a.matches("-?[0-9]{1," + digits + "}")
                        && b.matches("-?[0-9]{1," + digits + "}"));
        var value =
                new com.kartaguez.pocoma.domain.pot.v3.Fraction(
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
