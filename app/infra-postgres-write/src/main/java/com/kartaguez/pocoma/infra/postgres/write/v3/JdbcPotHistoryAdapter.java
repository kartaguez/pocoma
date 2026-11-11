package com.kartaguez.pocoma.infra.postgres.write.v3;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.infra.postgres.core.v3.PostgresPotHistoryReader;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotHistoryPort;
import javax.sql.DataSource;
/** WRITE port adapter over the neutral, caller-owned snapshot reader. */
public final class JdbcPotHistoryAdapter extends PostgresPotHistoryReader implements PotHistoryPort {
    public JdbcPotHistoryAdapter(DataSource source) { super(source); }
}
