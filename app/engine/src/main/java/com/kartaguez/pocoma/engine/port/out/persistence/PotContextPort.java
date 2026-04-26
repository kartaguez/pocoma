package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.context.AddPotShareholdersContext;
import com.kartaguez.pocoma.engine.context.CreateExpenseContext;
import com.kartaguez.pocoma.engine.context.DeletePotContext;
import com.kartaguez.pocoma.engine.context.UpdatePotDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersWeightsContext;

public interface PotContextPort {

	default AddPotShareholdersContext loadAddPotShareholdersContext(PotId potId) {
		throw new UnsupportedOperationException("AddPotShareholdersContext loading is not implemented");
	}

	default CreateExpenseContext loadCreateExpenseContext(PotId potId) {
		throw new UnsupportedOperationException("CreateExpenseContext loading is not implemented");
	}

	default DeletePotContext loadDeletePotContext(PotId potId) {
		throw new UnsupportedOperationException("DeletePotContext loading is not implemented");
	}

	default UpdatePotDetailsContext loadUpdatePotDetailsContext(PotId potId) {
		throw new UnsupportedOperationException("UpdatePotDetailsContext loading is not implemented");
	}

	default UpdatePotShareholdersDetailsContext loadUpdatePotShareholdersDetailsContext(PotId potId) {
		throw new UnsupportedOperationException("UpdatePotShareholdersDetailsContext loading is not implemented");
	}

	default UpdatePotShareholdersWeightsContext loadUpdatePotShareholdersWeightsContext(PotId potId) {
		throw new UnsupportedOperationException("UpdatePotShareholdersWeightsContext loading is not implemented");
	}
}
