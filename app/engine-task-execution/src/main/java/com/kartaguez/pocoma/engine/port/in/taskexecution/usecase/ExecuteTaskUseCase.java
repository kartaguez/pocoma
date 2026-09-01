package com.kartaguez.pocoma.engine.port.in.taskexecution.usecase;

import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.taskexecution.model.TaskExecutionReport;

public interface ExecuteTaskUseCase {

	TaskExecutionReport executeTask(ExecuteTaskInput<? extends TaskPayload> input);
}
