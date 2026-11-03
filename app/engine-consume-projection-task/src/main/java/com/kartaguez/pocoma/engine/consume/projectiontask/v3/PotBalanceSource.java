package com.kartaguez.pocoma.engine.consume.projectiontask.v3;
import com.kartaguez.pocoma.domain.pot.v3.PotFinancialState;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.UUID;
public interface PotBalanceSource { PotFinancialState load(UUID eventId,RecoveryReadContext context); }
