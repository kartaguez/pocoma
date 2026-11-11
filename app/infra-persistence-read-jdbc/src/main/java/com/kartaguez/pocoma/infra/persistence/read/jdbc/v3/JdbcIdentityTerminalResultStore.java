package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;
import com.kartaguez.pocoma.domain.projection.v3.IdentityTerminalResult;
import com.kartaguez.pocoma.port.projection.v3.IdentityTerminalResultStore;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.engine.read.results.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcIdentityTerminalResultStore implements IdentityTerminalResultStore {
    private final ReadJdbc db;
    public JdbcIdentityTerminalResultStore(DataSource source,SpringAttemptTransactions owner) { db=new ReadJdbc(source,owner); }
    private Optional<IdentityTerminalResult> load(UUID id) { return db.sql.query("select operation,requester_issuer,requester_subject,write_outcome,target_issuer,target_subject,target_version,rejection from read_identity_request_result where request_id=?",(r,n) -> new IdentityTerminalResult(id,r.getString(1),new ExternalIdentity(r.getString(2),r.getString(3)),r.getString(4),r.getString(5)==null?null:new ExternalIdentity(r.getString(5),r.getString(6)),r.getObject(7,Long.class),r.getString(8)),id).stream().findFirst(); }
    public Optional<IdentityTerminalResult> find(UUID id) { return db.readOwned(() -> load(id)); }
    public void insertOrVerify(IdentityTerminalResult v) { db.write(() -> {
        db.sql.update("insert into read_identity_request_result(request_id,operation,requester_issuer,requester_subject,write_outcome,target_issuer,target_subject,target_version,rejection) values (?,?,?,?,?,?,?,?,?) on conflict do nothing",v.requestId(),v.operation(),v.requesterEui().issuer(),v.requesterEui().subject(),v.writeOutcome(),v.targetEui()==null?null:v.targetEui().issuer(),v.targetEui()==null?null:v.targetEui().subject(),v.targetVersion(),v.rejection());
        ReadJdbc.same(v,load(v.requestId()).orElse(null)); return null;
    }); }
    public void requireStored(IdentityTerminalResult v) { db.write(() -> { ReadJdbc.same(v,load(v.requestId()).orElse(null)); return null; }); }
    public void closePublication() { SpringAttemptTransactions.closePublication(db.source); }
}
