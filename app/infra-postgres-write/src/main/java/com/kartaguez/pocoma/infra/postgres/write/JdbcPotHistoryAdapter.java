package com.kartaguez.pocoma.infra.postgres.write;
import com.kartaguez.pocoma.infra.postgres.core.*;
import com.kartaguez.pocoma.infra.postgres.core.PostgresPotHistoryReader;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotHistoryPort;
import javax.sql.DataSource;
/** WRITE port adapter over the neutral, caller-owned snapshot reader. */
public final class JdbcPotHistoryAdapter extends PostgresPotHistoryReader implements PotHistoryPort {
    public JdbcPotHistoryAdapter(DataSource source) { super(source); }
}
