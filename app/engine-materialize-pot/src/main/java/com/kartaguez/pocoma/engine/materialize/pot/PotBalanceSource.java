package com.kartaguez.pocoma.engine.materialize.pot;
import com.kartaguez.pocoma.domain.pot.PotFinancialState;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.UUID;
public interface PotBalanceSource { PotFinancialState load(UUID eventId,RecoveryReadContext context); }
