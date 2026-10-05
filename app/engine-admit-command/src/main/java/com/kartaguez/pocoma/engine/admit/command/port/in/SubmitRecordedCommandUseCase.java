package com.kartaguez.pocoma.engine.admit.command.port.in;

import com.kartaguez.pocoma.engine.admit.command.model.SubmitRecordedCommandInput;
import com.kartaguez.pocoma.engine.admit.command.model.SubmittedCommand;

public interface SubmitRecordedCommandUseCase {

	SubmittedCommand submit(SubmitRecordedCommandInput input);
}
