package com.kartaguez.pocoma.architecture.fixture.badread;

import com.kartaguez.pocoma.infra.postgres.write.pot.JdbcPotCommandStore;

public final class BadReadToWriteFixture {
    public Class<?> forbidden() {
        return JdbcPotCommandStore.class;
    }
}
