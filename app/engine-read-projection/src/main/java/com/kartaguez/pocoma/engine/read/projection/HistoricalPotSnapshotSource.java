package com.kartaguez.pocoma.engine.read.projection;

import java.util.List;
import com.kartaguez.pocoma.domain.pot.aggregate.*;
import com.kartaguez.pocoma.domain.pot.association.ExpenseShare;
import com.kartaguez.pocoma.domain.pot.entity.Shareholder;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public interface HistoricalPotSnapshotSource {
	HistoricalPotSnapshot load(PotId potId, long potVersion) throws HistoricalPotReconstructionException;
	record HistoricalPotSnapshot(PotHeader header, List<Shareholder> shareholders,
			List<HistoricalExpense> expenses) {}
	record HistoricalExpense(ExpenseHeader header, List<ExpenseShare> shares) {}
}
