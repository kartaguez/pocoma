package com.kartaguez.pocoma.architecture.fixture.badwrite;

import com.kartaguez.pocoma.infra.postgres.read.JdbcPotBalanceStore;

public final class BadWriteToReadFixture {
    public Class<?> forbidden() {
        return JdbcPotBalanceStore.class;
    }
}
