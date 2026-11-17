package com.kartaguez.pocoma.infra.postgres.write;
import javax.sql.DataSource;

import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotHistoryPort;
import com.kartaguez.pocoma.infra.postgres.core.PostgresPotHistoryReader;
/** WRITE port adapter over the neutral, caller-owned snapshot reader. */
public final class JdbcPotHistoryAdapter extends PostgresPotHistoryReader implements PotHistoryPort {
    public JdbcPotHistoryAdapter(DataSource source) { super(source); }
}
