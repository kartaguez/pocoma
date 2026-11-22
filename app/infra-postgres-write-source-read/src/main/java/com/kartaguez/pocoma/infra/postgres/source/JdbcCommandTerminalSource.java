package com.kartaguez.pocoma.infra.postgres.source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.engine.materialize.results.CommandTerminalSource;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
/** Exact terminal projection. No executable command or mutating store is constructed. */
public final class JdbcCommandTerminalSource implements CommandTerminalSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> OPERATIONS = Set.of("CreatePot", "UpdatePotDetails", "DeletePot",
            "AddShareholders", "UpdateShareholderDetails", "UpdateShareholderWeights", "DeleteShareholder",
            "CreateExpense", "UpdateExpenseDetails", "UpdateExpenseFinancials", "DeleteExpense",
            "CreateInvitation", "AttachShareholderViaInvitation");
    private final DataSource source;
    private final JdbcTemplate sql;
    public JdbcCommandTerminalSource(DataSource source) { this.source=Objects.requireNonNull(source); sql=new JdbcTemplate(source); }
    public JdbcCommandTerminalSource(DataSource source, SpringAttemptTransactions owner) { this(source); Objects.requireNonNull(owner); }
    public CommandTerminalResult load(UUID event, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(source, context, () -> {
            var rows=sql.queryForList("""
                select c.request_id,c.operation,c.requester_issuer,c.requester_subject,
                       coalesce(r.pot_id,c.pot_id) as effective_pot_id,c.status,
                       r.outcome,r.resulting_pot_version,r.rejection,
                       r.created_resources::text as created_resources
                from v3_command_completion_event e
                join v3_event_position p on p.event_id=e.event_id and p.family='COMMAND_COMPLETION'
                join v3_event_counter k on k.family=p.family and k.position>=p.position
                join v3_command c on c.request_id=e.subject_id
                join v3_command_result r on r.request_id=c.request_id
                where e.event_id=? and e.subject_type='COMMAND'
                """, event);
            if(rows.size()!=1) throw new IllegalStateException("Missing exact Command completion");
            var row=rows.getFirst();
            var operation=(String)row.get("operation");
            if(!"TERMINAL".equals(row.get("status")) || !OPERATIONS.contains(operation))
                throw new IllegalStateException("Invalid durable Command terminal");
            var version=(Number)row.get("resulting_pot_version");
            return new CommandTerminalResult((UUID)row.get("request_id"),operation,
                    new ExternalIdentity((String)row.get("requester_issuer"),(String)row.get("requester_subject")),
                    (UUID)row.get("effective_pot_id"),(String)row.get("outcome"),
                    version==null?null:version.longValue(),(String)row.get("rejection"),
                    decodeCreatedResources((String)row.get("created_resources")));
        });
    }

    private static CommandTerminalResult.CreatedResources decodeCreatedResources(String raw) {
        if (raw == null) return null;
        try {
            JsonNode n = JSON.readTree(raw);
            if (!n.isObject() || n.size() != 1) throw new IllegalArgumentException();
            if (n.has("potId")) {
                return CommandTerminalResult.CreatedResources.pot(UUID.fromString(n.required("potId").textValue()));
            }
            if (n.has("expenseId")) {
                return CommandTerminalResult.CreatedResources.expense(UUID.fromString(n.required("expenseId").textValue()));
            }
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
            throw new IllegalStateException("Invalid durable Command createdResources");
        }
    }
}
