package com.kartaguez.pocoma.supra.http.rest.spring.controller;

import static com.kartaguez.pocoma.supra.http.rest.spring.controller.RequestBodyValidator.requireBody;
import static com.kartaguez.pocoma.supra.http.rest.spring.controller.RequestBodyValidator.requireList;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kartaguez.pocoma.engine.port.in.command.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.port.in.command.usecase.AddPotShareholdersUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreateExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeletePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersWeightsUseCase;
import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.supra.http.rest.spring.config.OpenApiConfiguration;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.AddPotShareholdersRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.CreateExpenseRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.CreatePotRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.ExpectedVersionRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.ExpenseShareRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.UpdatePotDetailsRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.UpdatePotShareholdersDetailsRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.UpdatePotShareholdersWeightsRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ExpenseSharesResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.PotResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.PotShareholdersResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.mapper.RestMapper;
import com.kartaguez.pocoma.supra.http.rest.spring.security.UserContextFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/pots")
@Tag(name = "Pot commands")
@SecurityRequirement(name = OpenApiConfiguration.USER_ID_HEADER)
public class PotsCommandController {

	private final CreatePotUseCase createPotUseCase;
	private final UpdatePotDetailsUseCase updatePotDetailsUseCase;
	private final DeletePotUseCase deletePotUseCase;
	private final AddPotShareholdersUseCase addPotShareholdersUseCase;
	private final UpdatePotShareholdersDetailsUseCase updatePotShareholdersDetailsUseCase;
	private final UpdatePotShareholdersWeightsUseCase updatePotShareholdersWeightsUseCase;
	private final CreateExpenseUseCase createExpenseUseCase;

	public PotsCommandController(
			CreatePotUseCase createPotUseCase,
			UpdatePotDetailsUseCase updatePotDetailsUseCase,
			DeletePotUseCase deletePotUseCase,
			AddPotShareholdersUseCase addPotShareholdersUseCase,
			UpdatePotShareholdersDetailsUseCase updatePotShareholdersDetailsUseCase,
			UpdatePotShareholdersWeightsUseCase updatePotShareholdersWeightsUseCase,
			CreateExpenseUseCase createExpenseUseCase) {
		this.createPotUseCase = Objects.requireNonNull(createPotUseCase, "createPotUseCase must not be null");
		this.updatePotDetailsUseCase = Objects.requireNonNull(
				updatePotDetailsUseCase,
				"updatePotDetailsUseCase must not be null");
		this.deletePotUseCase = Objects.requireNonNull(deletePotUseCase, "deletePotUseCase must not be null");
		this.addPotShareholdersUseCase = Objects.requireNonNull(
				addPotShareholdersUseCase,
				"addPotShareholdersUseCase must not be null");
		this.updatePotShareholdersDetailsUseCase = Objects.requireNonNull(
				updatePotShareholdersDetailsUseCase,
				"updatePotShareholdersDetailsUseCase must not be null");
		this.updatePotShareholdersWeightsUseCase = Objects.requireNonNull(
				updatePotShareholdersWeightsUseCase,
				"updatePotShareholdersWeightsUseCase must not be null");
		this.createExpenseUseCase = Objects.requireNonNull(createExpenseUseCase, "createExpenseUseCase must not be null");
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a pot")
	public PotResponse createPot(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@RequestBody CreatePotRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		CreatePotCommand command = new CreatePotCommand(
				requireBody(request).label(),
				UserContextFactory.userId(userId));

		return RestMapper.toResponse(createPotUseCase.createPot(userContext, command));
	}

	@PatchMapping("/{potId}/details")
	@Operation(summary = "Update pot details")
	public PotResponse updatePotDetails(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID potId,
			@RequestBody UpdatePotDetailsRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		UpdatePotDetailsRequest body = requireBody(request);

		return RestMapper.toResponse(updatePotDetailsUseCase.updatePotDetails(
				userContext,
				new UpdatePotDetailsCommand(potId, body.label(), body.expectedVersion())));
	}

	@DeleteMapping("/{potId}")
	@Operation(summary = "Delete a pot")
	public PotResponse deletePot(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID potId,
			@RequestBody ExpectedVersionRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);

		return RestMapper.toResponse(deletePotUseCase.deletePot(
				userContext,
				new DeletePotCommand(potId, requireBody(request).expectedVersion())));
	}

	@PostMapping("/{potId}/shareholders")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Add pot shareholders")
	public PotShareholdersResponse addPotShareholders(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID potId,
			@RequestBody AddPotShareholdersRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		AddPotShareholdersRequest body = requireBody(request);

		return RestMapper.toResponse(addPotShareholdersUseCase.addPotShareholders(
				userContext,
				new AddPotShareholdersCommand(
						potId,
						toNewShareholderInputs(body),
						body.expectedVersion())));
	}

	@PatchMapping("/{potId}/shareholders/details")
	@Operation(summary = "Update pot shareholders details")
	public PotShareholdersResponse updatePotShareholdersDetails(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID potId,
			@RequestBody UpdatePotShareholdersDetailsRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		UpdatePotShareholdersDetailsRequest body = requireBody(request);

		return RestMapper.toResponse(updatePotShareholdersDetailsUseCase.updatePotShareholdersDetails(
				userContext,
				new UpdatePotShareholdersDetailsCommand(
						potId,
						toShareholderDetailsInputs(body),
						body.expectedVersion())));
	}

	@PatchMapping("/{potId}/shareholders/weights")
	@Operation(summary = "Update pot shareholders weights")
	public PotShareholdersResponse updatePotShareholdersWeights(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID potId,
			@RequestBody UpdatePotShareholdersWeightsRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		UpdatePotShareholdersWeightsRequest body = requireBody(request);

		return RestMapper.toResponse(updatePotShareholdersWeightsUseCase.updatePotShareholdersWeights(
				userContext,
				new UpdatePotShareholdersWeightsCommand(
						potId,
						toShareholderWeightInputs(body),
						body.expectedVersion())));
	}

	@PostMapping("/{potId}/expenses")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an expense")
	public ExpenseSharesResponse createExpense(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID potId,
			@RequestBody CreateExpenseRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		CreateExpenseRequest body = requireBody(request);

		return RestMapper.toResponse(createExpenseUseCase.createExpense(
				userContext,
				new CreateExpenseCommand(
						potId,
						body.payerId(),
						body.amountNumerator(),
						body.amountDenominator(),
						body.label(),
						toExpenseShareInputs(body.shares()),
						body.expectedVersion())));
	}

	private static Set<AddPotShareholdersCommand.ShareholderInput> toNewShareholderInputs(
			AddPotShareholdersRequest request) {
		return requireList(request.shareholders(), "shareholders").stream()
				.map(shareholder -> new AddPotShareholdersCommand.ShareholderInput(
						shareholder.name(),
						shareholder.weightNumerator(),
						shareholder.weightDenominator()))
				.collect(Collectors.toSet());
	}

	private static Set<UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput> toShareholderDetailsInputs(
			UpdatePotShareholdersDetailsRequest request) {
		return requireList(request.shareholders(), "shareholders").stream()
				.map(shareholder -> new UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput(
						shareholder.shareholderId(),
						shareholder.name(),
						shareholder.userId()))
				.collect(Collectors.toSet());
	}

	private static Set<UpdatePotShareholdersWeightsCommand.ShareholderWeightInput> toShareholderWeightInputs(
			UpdatePotShareholdersWeightsRequest request) {
		return requireList(request.shareholders(), "shareholders").stream()
				.map(shareholder -> new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(
						shareholder.shareholderId(),
						shareholder.weightNumerator(),
						shareholder.weightDenominator()))
				.collect(Collectors.toSet());
	}

	private static Set<CreateExpenseCommand.ExpenseShareInput> toExpenseShareInputs(
			java.util.List<ExpenseShareRequest> shares) {
		return requireList(shares, "shares").stream()
				.map(share -> new CreateExpenseCommand.ExpenseShareInput(
						share.shareholderId(),
						share.weightNumerator(),
						share.weightDenominator()))
				.collect(Collectors.toSet());
	}
}
