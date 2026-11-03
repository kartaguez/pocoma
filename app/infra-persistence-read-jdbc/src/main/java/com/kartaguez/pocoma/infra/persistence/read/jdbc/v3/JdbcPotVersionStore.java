package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;

import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.port.projection.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import javax.sql.DataSource;
import java.util.*;

public final class JdbcPotVersionStore implements PotVersionStore {
    private final ReadJdbc db;
    public JdbcPotVersionStore(DataSource source,SpringAttemptTransactions owner) { db=new ReadJdbc(source,owner); }
    private Optional<PotVersion> load(UUID pot,long version) {
        return db.sql.query("select schema_version,payload::text from read_pot_version where pot_id=? and pot_version=?",
            (r,n) -> { var v=PotVersionJson.decode(r.getInt(1),r.getString(2)); if(!pot.equals(v.potId()) || version!=v.version()) throw new IllegalStateException("Mixed PotVersion key"); return v; },pot,version).stream().findFirst();
    }
    public Optional<PotVersion> read(UUID pot,long version,RecoveryReadContext context) { return db.read(context,() -> load(pot,version)); }
    public ReadEvidence evidence(PotVersion expected,RecoveryReadContext context) { return db.read(context,() -> {
        try {
            var actual=load(expected.potId(),expected.version());
            return actual.isEmpty()?ReadEvidence.MISSING:actual.get().equals(expected)?ReadEvidence.SATISFIED:ReadEvidence.DIVERGENT;
        } catch(IllegalStateException positiveCorruption) { return ReadEvidence.DIVERGENT; }
    }); }
    public void insertAndAdvance(PotVersion value) { db.write(() -> {
        db.sql.update("insert into read_pot_version(pot_id,pot_version,schema_version,payload) values (?,?,1,?::jsonb) on conflict do nothing",value.potId(),value.version(),PotVersionJson.encode(value));
        ReadJdbc.same(value,load(value.potId(),value.version()).orElse(null));
        db.sql.update("insert into read_current_pot(pot_id,pot_version) values (?,?) on conflict(pot_id) do update set pot_version=excluded.pot_version where read_current_pot.pot_version<excluded.pot_version",value.potId(),value.version());
        return null;
    }); }
    public void requireStored(PotVersion value) { db.write(() -> {
        ReadJdbc.same(value,load(value.potId(),value.version()).orElse(null));
        var current=db.sql.queryForObject("select pot_version from read_current_pot where pot_id=?",Long.class,value.potId());
        if(current==null || current<value.version() || load(value.potId(),current).isEmpty()) throw new IllegalStateException("Incomplete CurrentPot");
        return null;
    }); }
    public void closePublication() { SpringAttemptTransactions.closePublication(db.source); }
}
