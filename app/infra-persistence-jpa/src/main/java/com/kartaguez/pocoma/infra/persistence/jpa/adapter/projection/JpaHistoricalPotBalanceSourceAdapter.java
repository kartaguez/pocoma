package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.persistence.HistoricalPotBalanceSourcePort;
import com.kartaguez.pocoma.engine.port.out.persistence.model.HistoricalPotBalanceSource;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaPotHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaPotShareholdersAdapter;

@Component
public class JpaHistoricalPotBalanceSourceAdapter implements HistoricalPotBalanceSourcePort {
	private final JpaPotHeaderAdapter headers;
	private final JpaPotShareholdersAdapter shareholders;
	private final JpaProjectedExpenseAdapter expenses;

	public JpaHistoricalPotBalanceSourceAdapter(JpaPotHeaderAdapter headers,
			JpaPotShareholdersAdapter shareholders, JpaProjectedExpenseAdapter expenses) {
		this.headers = headers;
		this.shareholders = shareholders;
		this.expenses = expenses;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public HistoricalPotBalanceSource loadAtVersion(PotId potId, long version) {
		return new HistoricalPotBalanceSource(headers.loadActiveAtVersion(potId, version),
				shareholders.loadActiveAtVersion(potId, version), expenses.loadActiveAtVersion(potId, version), version);
	}
}
