package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.port.projection.v3.CurrentBindingStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcCurrentBindingStore implements CurrentBindingStore {
    private final ReadJdbc db;
    public JdbcCurrentBindingStore(DataSource source,SpringAttemptTransactions owner) { db=new ReadJdbc(source,owner); }
    private Optional<ExternalUserIdentity> load(ExternalIdentity key) {
        return db.sql.query("select eui_id,eui_version,user_id,binding_token from read_current_binding where issuer=? and subject=?",(r,n) -> new ExternalUserIdentity(new ExternalUserIdentityId(r.getObject(1,UUID.class)),key,r.getLong(2),r.getObject(3)==null?null:new PocomaUserId(r.getObject(3,UUID.class)),r.getString(4)==null?null:new BindingToken(r.getString(4))),key.issuer(),key.subject()).stream().findFirst();
    }
    public Optional<ExternalUserIdentity> read(ExternalIdentity key,RecoveryReadContext context) { return db.read(context,() -> load(key)); }
    public void apply(ExternalUserIdentity v) { db.write(() -> {
        // Both natural-key and eui_id unique indexes can arbitrate the initial race.
        // Resolve either conflict first, then lock/read the committed row in a new RC statement.
        db.sql.update("insert into read_current_binding(issuer,subject,eui_id,eui_version,user_id,binding_token) values (?,?,?,?,?,?) on conflict do nothing",v.identity().issuer(),v.identity().subject(),v.id().value(),v.version(),v.userId()==null?null:v.userId().value(),v.bindingToken()==null?null:v.bindingToken().value());
        var ids=db.sql.query("select eui_id from read_current_binding where issuer=? and subject=? for update",(r,n) -> r.getObject(1,UUID.class),v.identity().issuer(),v.identity().subject());
        if(ids.size()!=1 || !ids.getFirst().equals(v.id().value())) throw new IllegalStateException("Conflicting EUI identity");
        db.sql.update("update read_current_binding set eui_version=?,user_id=?,binding_token=? where issuer=? and subject=? and eui_version<?",v.version(),v.userId()==null?null:v.userId().value(),v.bindingToken()==null?null:v.bindingToken().value(),v.identity().issuer(),v.identity().subject(),v.version());
        check(v); return null;
    }); }
    private void check(ExternalUserIdentity expected) {
        var actual=load(expected.identity()).orElseThrow();
        if(!actual.id().equals(expected.id()) || actual.version()<expected.version()) throw new IllegalStateException("Incomplete CurrentBinding");
        if(actual.version()==expected.version()) ReadJdbc.same(expected,actual);
    }
    public void requireStored(ExternalUserIdentity v) { db.write(() -> { check(v); return null; }); }
    public void closePublication() { SpringAttemptTransactions.closePublication(db.source); }
}
