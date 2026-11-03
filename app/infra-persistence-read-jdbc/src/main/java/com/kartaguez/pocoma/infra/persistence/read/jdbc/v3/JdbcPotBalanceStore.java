package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.kartaguez.pocoma.domain.pot.v3.Fraction;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.port.projection.v3.PotBalanceStore;
import com.kartaguez.pocoma.port.projection.v3.ReadEvidence;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import javax.sql.DataSource;
import java.util.*;
import java.math.BigInteger;
public final class JdbcPotBalanceStore implements PotBalanceStore {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ReadJdbc db;
    public JdbcPotBalanceStore(DataSource source,SpringAttemptTransactions owner) { db=new ReadJdbc(source,owner); }
    private String encode(PotBalance b) {
        var a=JSON.createArrayNode(); b.values().forEach((id,f) -> a.addObject().put("id",id.toString()).put("n",f.numerator().toString()).put("d",f.denominator().toString())); return a.toString();
    }
    private PotBalance decode(UUID p,long v,String payload) {
        try {
            var array=JSON.readTree(payload); if(!array.isArray()) throw new IllegalArgumentException();
            var values=new LinkedHashMap<UUID,Fraction>();
            for(var n:array) {
                if(!n.isObject() || n.size()!=3 || !n.path("id").isTextual() || !n.path("n").isTextual() || !n.path("d").isTextual()) throw new IllegalArgumentException();
                if(values.put(UUID.fromString(n.get("id").textValue()),new Fraction(new BigInteger(n.get("n").textValue()),new BigInteger(n.get("d").textValue())))!=null) throw new IllegalArgumentException();
            }
            var b=new PotBalance(p,v,values); if(!array.equals(JSON.readTree(encode(b)))) throw new IllegalArgumentException(); return b;
        } catch(java.io.IOException | RuntimeException invalid) { throw new IllegalStateException("Corrupt PotBalance"); }
    }
    private Optional<PotBalance> load(UUID p,long v) { return db.sql.query("select payload::text from read_pot_balance where pot_id=? and pot_version=?",(r,n) -> decode(p,v,r.getString(1)),p,v).stream().findFirst(); }
    public Optional<PotBalance> read(UUID p,long v,RecoveryReadContext c) { return db.read(c,() -> load(p,v)); }
    public ReadEvidence evidence(UUID p,long v,Optional<PotBalance> expected,RecoveryReadContext c) { return db.read(c,() -> {
        try {
            var actual=load(p,v);
            if(expected.equals(actual)) return ReadEvidence.SATISFIED;
            return actual.isEmpty()?ReadEvidence.MISSING:ReadEvidence.DIVERGENT;
        } catch(IllegalStateException positiveCorruption) { return ReadEvidence.DIVERGENT; }
    }); }
    public void insertOrVerify(PotBalance b) { db.write(() -> {
        db.sql.update("insert into read_pot_balance(pot_id,pot_version,schema_version,payload) values (?,?,1,?::jsonb) on conflict do nothing",b.potId(),b.version(),encode(b)); ReadJdbc.same(b,load(b.potId(),b.version()).orElse(null)); return null;
    }); }
    public void requireStored(UUID p,long v,Optional<PotBalance> expected) { db.write(() -> { ReadJdbc.same(expected,load(p,v)); return null; }); }
    public void closePublication() { SpringAttemptTransactions.closePublication(db.source); }
}
