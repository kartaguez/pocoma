package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Supplemental EXPLAIN of the exact new close predicate; never commits the diagnostic close. */
class ClosePlans {
    public static void main(String[] args) throws Exception {
        var pg = PotHistoryPostgresTest.POSTGRES;
        pg.start();
        try {
            var results = new ArrayList<Object>();
            for (int depth : new int[] {100, 1000, 10000}) {
                var f = new PotHistoryPostgresTest();
                f.freshSchema(); f.pot();
                f.write(() -> {
                    f.jdbc.update("update v3_pot_details set active_to=2 where active_to is null");
                    f.jdbc.update("insert into v3_pot_version(pot_id,version,recorded_at) select ?,v,clock_timestamp() from generate_series(2,?) v", f.p.value(), depth);
                    f.jdbc.update("insert into v3_pot_details(pot_id,object_id,local_version,active_from,active_to,label,creator_id) select ?,?,v,v,case when v=? then null else v+1 end,'L'||v,? from generate_series(2,?) v", f.p.value(), f.p.value(), depth, f.creator.value(), depth);
                });
                f.jdbc.execute("analyze v3_pot_details");
                String[] plan = new String[1];
                long id = f.jdbc.queryForObject("select revision_id from v3_pot_details where active_to is null", Long.class);
                String close = "update v3_pot_details set active_to=? where pot_id=? and object_id=? and revision_id=? and local_version=? and active_from=? and active_to is null and active_from<?";
                try { f.write(() -> {
                    f.writer.recordVersion(f.p, depth, depth + 1L, PotHistoryPostgresTest.TIME);
                    plan[0] = f.jdbc.queryForObject("explain (analyze,buffers,format json) " + close, String.class,
                            depth + 1L, f.p.value(), f.p.value(), id, (long) depth, (long) depth, depth + 1L);
                    throw new PotHistoryPostgresTest.ForcedRollback();
                }); } catch (PotHistoryPostgresTest.ForcedRollback expected) { }
                if (plan[0] == null) throw new AssertionError("Missing plan");
                var tree = new ObjectMapper().readTree(plan[0]);
                var scan = tree.get(0).get("Plan").get("Plans").get(0);
                if (scan.get("Actual Rows").asInt() != 1 || (depth >= 1000 && !scan.has("Index Name"))) throw new AssertionError(plan[0]);
                results.add(Map.of("depth", depth, "sql", close, "plan", tree));
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), results);
        } finally { pg.stop(); }
    }
}
