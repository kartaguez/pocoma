package com.kartaguez.pocoma.compose.polling.process;

import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport.DevTarget;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport.PoolSpec;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;

/** Technical PostgreSQL pool, endpoint, and physical dev-target configuration for Process. */
public record ProcessPoolSettings(
        PoolSpec writeBusiness,
        PoolSpec writeControl,
        List<DevTarget> allowedTargets) {

    public ProcessPoolSettings {
        allowedTargets = List.copyOf(allowedTargets);
    }

    static ProcessPoolSettings from(Environment env, int workerLimit) {
        var allowed = WorkerPostgresSupport.parseDevTargets(env);
        var wb = WorkerPostgresSupport.parsePool(env, "write-business", true, workerLimit);
        var wc = WorkerPostgresSupport.parsePool(env, "write-control", false, workerLimit);
        if (!WorkerPostgresSupport.sameAuthority(wb, wc)) {
            throw new IllegalArgumentException("WRITE Business/Control targets must match");
        }
        return new ProcessPoolSettings(wb, wc, allowed);
    }

    public void validateTarget(PoolSpec pool, DataSource source) {
        WorkerPostgresSupport.validateTarget(allowedTargets, pool, source);
    }
}
