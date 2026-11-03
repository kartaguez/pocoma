package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.read.commandresult.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcCommandTerminalResultStore implements CommandTerminalResultStore {
    private final ReadJdbc db;
    public JdbcCommandTerminalResultStore(DataSource source,SpringAttemptTransactions owner) { db=new ReadJdbc(source,owner); }
    private Optional<CommandTerminalResult> load(UUID id) { return db.sql.query("select operation,requester_issuer,requester_subject,pot_id,write_outcome,resulting_pot_version,rejection from read_command_result where request_id=?",(r,n) -> new CommandTerminalResult(id,r.getString(1),new ExternalIdentity(r.getString(2),r.getString(3)),r.getObject(4,UUID.class),r.getString(5),r.getObject(6,Long.class),r.getString(7)),id).stream().findFirst(); }
    public Optional<CommandTerminalResult> find(UUID id) { return db.readOwned(() -> load(id)); }
    public void insertOrVerify(CommandTerminalResult v) { db.write(() -> {
        db.sql.update("insert into read_command_result(request_id,operation,requester_issuer,requester_subject,pot_id,write_outcome,resulting_pot_version,rejection) values (?,?,?,?,?,?,?,?) on conflict do nothing",v.requestId(),v.operation(),v.requesterEui().issuer(),v.requesterEui().subject(),v.potId(),v.writeOutcome(),v.resultingPotVersion(),v.rejection());
        ReadJdbc.same(v,load(v.requestId()).orElse(null)); return null;
    }); }
    public void requireStored(CommandTerminalResult v) { db.write(() -> { ReadJdbc.same(v,load(v.requestId()).orElse(null)); return null; }); }
    public void closePublication() { SpringAttemptTransactions.closePublication(db.source); }
}
