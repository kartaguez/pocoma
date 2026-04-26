package com.kartaguez.pocoma.engine.port.out.persistence;

import java.util.Collection;

import com.kartaguez.pocoma.domain.projection.ProjectedExpense;
import com.kartaguez.pocoma.domain.value.id.PotId;

public interface ProjectedExpensePort {

	default Collection<ProjectedExpense> loadActiveAtSourceOnly(
			PotId potId,
			long sourceVersion,
			long comparedVersion) {
		throw new UnsupportedOperationException("Projected expense loading is not implemented");
	}
}
