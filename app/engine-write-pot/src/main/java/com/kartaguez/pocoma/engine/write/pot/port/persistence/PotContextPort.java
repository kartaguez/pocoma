package com.kartaguez.pocoma.engine.write.pot.port.persistence;

import java.util.Optional;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.context.AddPotShareholdersContext;
import com.kartaguez.pocoma.engine.write.pot.context.CreateExpenseContext;
import com.kartaguez.pocoma.engine.write.pot.context.DeletePotContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdatePotDetailsContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdatePotShareholdersDetailsContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdatePotShareholdersWeightsContext;

/** Empty means an unknown business reference at the initial lookup. Once ownership/version is
 * known, missing persisted facts are technical errors and must escape the Execute transaction. */
public interface PotContextPort {

	default Optional<AddPotShareholdersContext> loadAddPotShareholdersContext(PotId potId) {
		throw new UnsupportedOperationException("AddPotShareholdersContext loading is not implemented");
	}

	default Optional<CreateExpenseContext> loadCreateExpenseContext(PotId potId) {
		throw new UnsupportedOperationException("CreateExpenseContext loading is not implemented");
	}

	default Optional<DeletePotContext> loadDeletePotContext(PotId potId) {
		throw new UnsupportedOperationException("DeletePotContext loading is not implemented");
	}

	default Optional<UpdatePotDetailsContext> loadUpdatePotDetailsContext(PotId potId) {
		throw new UnsupportedOperationException("UpdatePotDetailsContext loading is not implemented");
	}

	default Optional<UpdatePotShareholdersDetailsContext> loadUpdatePotShareholdersDetailsContext(PotId potId) {
		throw new UnsupportedOperationException("UpdatePotShareholdersDetailsContext loading is not implemented");
	}

	default Optional<UpdatePotShareholdersWeightsContext> loadUpdatePotShareholdersWeightsContext(PotId potId) {
		throw new UnsupportedOperationException("UpdatePotShareholdersWeightsContext loading is not implemented");
	}
}
