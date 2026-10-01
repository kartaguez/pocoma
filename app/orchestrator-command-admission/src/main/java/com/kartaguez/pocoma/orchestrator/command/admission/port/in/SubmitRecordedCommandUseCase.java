package com.kartaguez.pocoma.orchestrator.command.admission.port.in;

import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmitRecordedCommandInput;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmittedCommand;

public interface SubmitRecordedCommandUseCase {

	SubmittedCommand submit(SubmitRecordedCommandInput input);
}
