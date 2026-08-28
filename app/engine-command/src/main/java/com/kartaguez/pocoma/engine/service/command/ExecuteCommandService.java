package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.exception.UnsupportedCommandIntentException;
import com.kartaguez.pocoma.engine.port.in.command.input.ExecuteCommandInput;
import com.kartaguez.pocoma.engine.port.in.command.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.port.in.command.usecase.AddPotShareholdersUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreateExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeleteExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeletePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.ExecuteCommandUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseSharesUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersWeightsUseCase;

final class ExecuteCommandService implements ExecuteCommandUseCase {

	private final CreatePotUseCase createPotUseCase;
	private final CreateExpenseUseCase createExpenseUseCase;
	private final AddPotShareholdersUseCase addPotShareholdersUseCase;
	private final DeletePotUseCase deletePotUseCase;
	private final DeleteExpenseUseCase deleteExpenseUseCase;
	private final UpdatePotDetailsUseCase updatePotDetailsUseCase;
	private final UpdateExpenseDetailsUseCase updateExpenseDetailsUseCase;
	private final UpdateExpenseSharesUseCase updateExpenseSharesUseCase;
	private final UpdatePotShareholdersDetailsUseCase updatePotShareholdersDetailsUseCase;
	private final UpdatePotShareholdersWeightsUseCase updatePotShareholdersWeightsUseCase;

	ExecuteCommandService(
			CreatePotUseCase createPotUseCase,
			CreateExpenseUseCase createExpenseUseCase,
			AddPotShareholdersUseCase addPotShareholdersUseCase,
			DeletePotUseCase deletePotUseCase,
			DeleteExpenseUseCase deleteExpenseUseCase,
			UpdatePotDetailsUseCase updatePotDetailsUseCase,
			UpdateExpenseDetailsUseCase updateExpenseDetailsUseCase,
			UpdateExpenseSharesUseCase updateExpenseSharesUseCase,
			UpdatePotShareholdersDetailsUseCase updatePotShareholdersDetailsUseCase,
			UpdatePotShareholdersWeightsUseCase updatePotShareholdersWeightsUseCase) {
		this.createPotUseCase = requireNonNull(createPotUseCase, "createPotUseCase must not be null");
		this.createExpenseUseCase = requireNonNull(createExpenseUseCase, "createExpenseUseCase must not be null");
		this.addPotShareholdersUseCase = requireNonNull(addPotShareholdersUseCase, "addPotShareholdersUseCase must not be null");
		this.deletePotUseCase = requireNonNull(deletePotUseCase, "deletePotUseCase must not be null");
		this.deleteExpenseUseCase = requireNonNull(deleteExpenseUseCase, "deleteExpenseUseCase must not be null");
		this.updatePotDetailsUseCase = requireNonNull(updatePotDetailsUseCase, "updatePotDetailsUseCase must not be null");
		this.updateExpenseDetailsUseCase = requireNonNull(updateExpenseDetailsUseCase, "updateExpenseDetailsUseCase must not be null");
		this.updateExpenseSharesUseCase = requireNonNull(updateExpenseSharesUseCase, "updateExpenseSharesUseCase must not be null");
		this.updatePotShareholdersDetailsUseCase = requireNonNull(updatePotShareholdersDetailsUseCase,
				"updatePotShareholdersDetailsUseCase must not be null");
		this.updatePotShareholdersWeightsUseCase = requireNonNull(updatePotShareholdersWeightsUseCase,
				"updatePotShareholdersWeightsUseCase must not be null");
	}

	@Override
	public void execute(ExecuteCommandInput input) {
		requireNonNull(input, "input must not be null");
		var userContext = input.userContext();
		var intent = input.commandIntent();

		switch (intent) {
			case CreatePotCommand command -> createPotUseCase.createPot(userContext, command);
			case CreateExpenseCommand command -> createExpenseUseCase.createExpense(userContext, command);
			case AddPotShareholdersCommand command -> addPotShareholdersUseCase.addPotShareholders(userContext, command);
			case DeletePotCommand command -> deletePotUseCase.deletePot(userContext, command);
			case DeleteExpenseCommand command -> deleteExpenseUseCase.deleteExpense(userContext, command);
			case UpdatePotDetailsCommand command -> updatePotDetailsUseCase.updatePotDetails(userContext, command);
			case UpdateExpenseDetailsCommand command -> updateExpenseDetailsUseCase.updateExpenseDetails(userContext, command);
			case UpdateExpenseSharesCommand command -> updateExpenseSharesUseCase.updateExpenseShares(userContext, command);
			case UpdatePotShareholdersDetailsCommand command ->
					updatePotShareholdersDetailsUseCase.updatePotShareholdersDetails(userContext, command);
			case UpdatePotShareholdersWeightsCommand command ->
					updatePotShareholdersWeightsUseCase.updatePotShareholdersWeights(userContext, command);
			default -> throw new UnsupportedCommandIntentException(intent);
		}
	}
}
