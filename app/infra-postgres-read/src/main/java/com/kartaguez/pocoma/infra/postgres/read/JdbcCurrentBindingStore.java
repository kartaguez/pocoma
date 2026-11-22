package com.kartaguez.pocoma.infra.postgres.read;
import com.kartaguez.pocoma.domain.identity.*;
import com.kartaguez.pocoma.port.projection.CurrentBindingStore;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcCurrentBindingStore implements CurrentBindingStore {
    private static final ReadAvailability.Family FAMILY = ReadAvailability.Family.CURRENT_BINDING_CHANGED;

    private final ReadJdbc db;
    private final ReadAvailability availability;

    public JdbcCurrentBindingStore(DataSource source, SpringAttemptTransactions owner) {
        this(source, owner, false);
    }

    public JdbcCurrentBindingStore(DataSource source, SpringAttemptTransactions owner, boolean publishAvailability) {
        db = new ReadJdbc(source, owner);
        availability = new ReadAvailability(db, publishAvailability);
    }
    private Optional<ExternalUserIdentity> load(ExternalIdentity key) {
        return db.sql.query("select eui_id,eui_version,user_id,binding_token from read_current_binding where issuer=? and subject=?",(r,n) -> new ExternalUserIdentity(new ExternalUserIdentityId(r.getObject(1,UUID.class)),key,r.getLong(2),r.getObject(3)==null?null:new PocomaUserId(r.getObject(3,UUID.class)),r.getString(4)==null?null:new BindingToken(r.getString(4))),key.issuer(),key.subject()).stream().findFirst();
    }
    public Optional<ExternalUserIdentity> read(ExternalIdentity key,RecoveryReadContext context) { return db.read(context,() -> load(key)); }
    public boolean apply(ExternalUserIdentity v) { return db.write(() -> {
        // Both natural-key and eui_id unique indexes can arbitrate the initial race.
        // Resolve either conflict first, then lock/read the committed row in a new RC statement.
        boolean inserted = db.sql.update("insert into read_current_binding(issuer,subject,eui_id,eui_version,user_id,binding_token) values (?,?,?,?,?,?) on conflict do nothing",v.identity().issuer(),v.identity().subject(),v.id().value(),v.version(),v.userId()==null?null:v.userId().value(),v.bindingToken()==null?null:v.bindingToken().value()) == 1;
        var ids=db.sql.query("select eui_id from read_current_binding where issuer=? and subject=? for update",(r,n) -> r.getObject(1,UUID.class),v.identity().issuer(),v.identity().subject());
        if(ids.size()!=1 || !ids.getFirst().equals(v.id().value())) throw new IllegalStateException("Conflicting EUI identity");
        boolean advanced = db.sql.update("update read_current_binding set eui_version=?,user_id=?,binding_token=? where issuer=? and subject=? and eui_version<?",v.version(),v.userId()==null?null:v.userId().value(),v.bindingToken()==null?null:v.bindingToken().value(),v.identity().issuer(),v.identity().subject(),v.version()) == 1;
        check(v);
        return inserted || advanced;
    }); }
    private ExternalUserIdentity check(ExternalUserIdentity expected) {
        var actual=load(expected.identity()).orElseThrow();
        if(!actual.id().equals(expected.id()) || actual.version()<expected.version()) throw new IllegalStateException("Incomplete CurrentBinding");
        if(actual.version()==expected.version()) ReadJdbc.same(expected,actual);
        return actual;
    }

    public void requireStored(ExternalUserIdentity value) {
        requireCompletionReady(value, false);
    }

    public void requireCompletionReady(ExternalUserIdentity value, boolean advanced) {
        db.write(() -> {
            var actual = check(value);
            availability.requireCurrent(FAMILY, actual.id().value(), actual.version(), advanced);
            return null;
        });
    }

    public void closePublication(ExternalUserIdentity value, boolean advanced) {
        availability.closeAndPublish(ReadAvailability.Publication.current(FAMILY, value.id().value(), value.version(), advanced));
    }

    @Override
    public ReadEvidence evidence(ExternalUserIdentity incoming, ExternalUserIdentity expected, RecoveryReadContext context) {
        return db.read(context, () -> availability.currentEvidence(
                FAMILY, incoming.id().value(), incoming.version(), expected, load(incoming.identity()), ExternalUserIdentity::version));
    }
}
