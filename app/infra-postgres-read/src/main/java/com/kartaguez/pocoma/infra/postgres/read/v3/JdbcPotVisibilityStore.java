package com.kartaguez.pocoma.infra.postgres.read.v3;
import com.kartaguez.pocoma.domain.projection.v3.PotVisibility;
import com.kartaguez.pocoma.port.projection.v3.PotVisibilityStore;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcPotVisibilityStore implements PotVisibilityStore {
    private final ReadJdbc db;
    public JdbcPotVisibilityStore(DataSource source,SpringAttemptTransactions owner) { db=new ReadJdbc(source,owner); }
    private Set<UUID> visible(UUID p,long g) {
        var users=new HashSet<UUID>();
        db.sql.query("select user_id,pot_version from read_user_pot where pot_id=? and visible=true",r -> {
            long version=r.getLong(2); if(version<1 || version>g) throw new IllegalStateException("Corrupt visibility entry"); users.add(r.getObject(1,UUID.class));
        },p); return Set.copyOf(users);
    }
    private Optional<PotVisibility> load(UUID p) {
        var g=db.sql.query("select materialized_version from read_pot_visibility_state where pot_id=?",(r,n) -> r.getLong(1),p);
        if(g.isEmpty()) return Optional.empty(); if(g.getFirst()<1) throw new IllegalStateException("Unfinished visibility guard");
        return Optional.of(new PotVisibility(p,g.getFirst(),visible(p,g.getFirst())));
    }
    public Optional<PotVisibility> read(UUID p,RecoveryReadContext c) { return db.read(c,() -> load(p)); }
    public Receipt apply(PotVisibility value) { return db.write(() -> {
        var p=value.potId();
        db.sql.update("insert into read_pot_visibility_state(pot_id,materialized_version) values (?,0) on conflict do nothing",p);
        long g=db.sql.queryForObject("select materialized_version from read_pot_visibility_state where pot_id=? for update",Long.class,p);
        var before=visible(p,g);
        if(value.version()<g) return new Receipt(new PotVisibility(p,g,before),Set.of());
        if(value.version()==g) { ReadJdbc.same(value.users(),before); return new Receipt(value,Set.of()); }
        var disabled=new HashSet<>(before); disabled.removeAll(value.users());
        var ordered=new TreeSet<UUID>(com.kartaguez.pocoma.domain.projection.v3.SubobjectKey::compareUuid); ordered.addAll(value.users()); ordered.addAll(disabled);
        for(var user:ordered) if(!before.contains(user) || disabled.contains(user))
            db.sql.update("insert into read_user_pot(user_id,pot_id,pot_version,visible) values (?,?,?,?) on conflict(user_id,pot_id) do update set pot_version=excluded.pot_version,visible=excluded.visible",user,p,value.version(),value.users().contains(user));
        db.sql.update("update read_pot_visibility_state set materialized_version=? where pot_id=?",value.version(),p);
        return new Receipt(value,disabled);
    }); }
    public void requireStored(Receipt receipt) { db.write(() -> {
        ReadJdbc.same(receipt.state(),load(receipt.state().potId()).orElse(null));
        for(var user:receipt.disabled()) {
            var rows=db.sql.queryForList("select pot_version,visible from read_user_pot where user_id=? and pot_id=?",user,receipt.state().potId());
            if(rows.size()!=1 || !Objects.equals(rows.getFirst().get("pot_version"),receipt.state().version()) || !Boolean.FALSE.equals(rows.getFirst().get("visible"))) throw new IllegalStateException("Missing tombstone");
        }
        return null;
    }); }
    public List<UUID> visiblePots(UUID user,UUID after,int limit,RecoveryReadContext c) {
        return db.read(c,() -> {
            if(limit<1 || limit>1000) throw new IllegalArgumentException("Bounded page required");
            return after==null ? db.sql.query("select pot_id from read_user_pot where user_id=? and visible=true order by pot_id limit ?",(r,n) -> r.getObject(1,UUID.class),user,limit)
                : db.sql.query("select pot_id from read_user_pot where user_id=? and visible=true and pot_id>? order by pot_id limit ?",(r,n) -> r.getObject(1,UUID.class),user,after,limit);
        });
    }
    public void closePublication() { SpringAttemptTransactions.closePublication(db.source); }
}
