package com.kartaguez.pocoma.infra.postgres.read;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.port.projection.CommandTerminalResultStore;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcCommandTerminalResultStore implements CommandTerminalResultStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ReadJdbc db;
    public JdbcCommandTerminalResultStore(DataSource source,SpringAttemptTransactions owner) { db=new ReadJdbc(source,owner); }
    private Optional<CommandTerminalResult> load(UUID id) {
        return db.sql.query(
                "select operation,requester_issuer,requester_subject,pot_id,write_outcome,resulting_pot_version,rejection,created_resources::text from read_command_result where request_id=?",
                (r,n) -> new CommandTerminalResult(
                        id,
                        r.getString(1),
                        new ExternalIdentity(r.getString(2),r.getString(3)),
                        r.getObject(4,UUID.class),
                        r.getString(5),
                        r.getObject(6,Long.class),
                        r.getString(7),
                        decodeCreatedResources(r.getString(8))),
                id).stream().findFirst();
    }
    public Optional<CommandTerminalResult> find(UUID id) { return db.readOwned(() -> load(id)); }
    public void insertOrVerify(CommandTerminalResult v) { db.write(() -> {
        db.sql.update(
                "insert into read_command_result(request_id,operation,requester_issuer,requester_subject,pot_id,write_outcome,resulting_pot_version,rejection,created_resources) values (?,?,?,?,?,?,?,?,?::jsonb) on conflict do nothing",
                v.requestId(),v.operation(),v.requesterEui().issuer(),v.requesterEui().subject(),v.potId(),v.writeOutcome(),v.resultingPotVersion(),v.rejection(),encodeCreatedResources(v.createdResources()));
        ReadJdbc.same(v,load(v.requestId()).orElse(null)); return null;
    }); }
    public void requireStored(CommandTerminalResult v) { db.write(() -> { ReadJdbc.same(v,load(v.requestId()).orElse(null)); return null; }); }
    public void closePublication() { SpringAttemptTransactions.closePublication(db.source); }

    private static String encodeCreatedResources(CommandTerminalResult.CreatedResources created) {
        if (created == null) return null;
        var n = JSON.createObjectNode();
        if (created.potId() != null) {
            n.put("potId", created.potId().toString());
        } else if (created.expenseId() != null) {
            n.put("expenseId", created.expenseId().toString());
        } else if (created.shareholders() != null) {
            var arr = n.withArray("shareholders");
            for (var s : created.shareholders()) {
                arr.addObject().put("inputIndex", s.inputIndex()).put("shareholderId", s.shareholderId().toString());
            }
        }
        return n.toString();
    }

    private static CommandTerminalResult.CreatedResources decodeCreatedResources(String raw) {
        if (raw == null) return null;
        try {
            JsonNode n = JSON.readTree(raw);
            if (!n.isObject() || n.size() != 1) throw new IllegalArgumentException();
            if (n.has("potId")) return CommandTerminalResult.CreatedResources.pot(UUID.fromString(n.required("potId").textValue()));
            if (n.has("expenseId")) return CommandTerminalResult.CreatedResources.expense(UUID.fromString(n.required("expenseId").textValue()));
            if (n.has("shareholders") && n.required("shareholders").isArray()) {
                var items = new ArrayList<CommandTerminalResult.CreatedShareholder>();
                for (JsonNode s : n.required("shareholders")) {
                    if (!s.isObject() || s.size() != 2 || !s.required("inputIndex").isIntegralNumber())
                        throw new IllegalArgumentException();
                    items.add(new CommandTerminalResult.CreatedShareholder(
                            s.required("inputIndex").intValue(),
                            UUID.fromString(s.required("shareholderId").textValue())));
                }
                return CommandTerminalResult.CreatedResources.shareholders(items);
            }
            throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid READ Command createdResources");
        }
    }
}
