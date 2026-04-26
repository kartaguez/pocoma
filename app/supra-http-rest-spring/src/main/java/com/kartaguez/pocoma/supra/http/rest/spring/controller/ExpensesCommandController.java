package com.kartaguez.pocoma.supra.http.rest.spring.controller;

import static com.kartaguez.pocoma.supra.http.rest.spring.controller.RequestBodyValidator.requireBody;
import static com.kartaguez.pocoma.supra.http.rest.spring.controller.RequestBodyValidator.requireList;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kartaguez.pocoma.engine.port.in.command.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeleteExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseSharesUseCase;
import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.supra.http.rest.spring.config.OpenApiConfiguration;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.ExpectedVersionRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.ExpenseShareRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.UpdateExpenseDetailsRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.UpdateExpenseSharesRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ExpenseHeaderResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ExpenseSharesResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.mapper.RestMapper;
import com.kartaguez.pocoma.supra.http.rest.spring.security.UserContextFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/expenses")
@Tag(name = "Expense commands")
@SecurityRequirement(name = OpenApiConfiguration.USER_ID_HEADER)
public class ExpensesCommandController {

	private final UpdateExpenseDetailsUseCase updateExpenseDetailsUseCase;
	private final UpdateExpenseSharesUseCase updateExpenseSharesUseCase;
	private final DeleteExpenseUseCase deleteExpenseUseCase;

	public ExpensesCommandController(
			UpdateExpenseDetailsUseCase updateExpenseDetailsUseCase,
			UpdateExpenseSharesUseCase updateExpenseSharesUseCase,
			DeleteExpenseUseCase deleteExpenseUseCase) {
		this.updateExpenseDetailsUseCase = Objects.requireNonNull(
				updateExpenseDetailsUseCase,
				"updateExpenseDetailsUseCase must not be null");
		this.updateExpenseSharesUseCase = Objects.requireNonNull(
				updateExpenseSharesUseCase,
				"updateExpenseSharesUseCase must not be null");
		this.deleteExpenseUseCase = Objects.requireNonNull(deleteExpenseUseCase, "deleteExpenseUseCase must not be null");
	}

	@PatchMapping("/{expenseId}/details")
	@Operation(summary = "Update expense details")
	public ExpenseHeaderResponse updateExpenseDetails(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID expenseId,
			@RequestBody UpdateExpenseDetailsRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		UpdateExpenseDetailsRequest body = requireBody(request);

		return RestMapper.toResponse(updateExpenseDetailsUseCase.updateExpenseDetails(
				userContext,
				new UpdateExpenseDetailsCommand(
						expenseId,
						body.payerId(),
						body.amountNumerator(),
						body.amountDenominator(),
						body.label(),
						body.expectedVersion())));
	}

	@PatchMapping("/{expenseId}/shares")
	@Operation(summary = "Update expense shares")
	public ExpenseSharesResponse updateExpenseShares(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID expenseId,
			@RequestBody UpdateExpenseSharesRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		UpdateExpenseSharesRequest body = requireBody(request);

		return RestMapper.toResponse(updateExpenseSharesUseCase.updateExpenseShares(
				userContext,
				new UpdateExpenseSharesCommand(
						expenseId,
						toExpenseShareInputs(body.shares()),
						body.expectedVersion())));
	}

	@DeleteMapping("/{expenseId}")
	@Operation(summary = "Delete an expense")
	public ExpenseHeaderResponse deleteExpense(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID expenseId,
			@RequestBody ExpectedVersionRequest request) {
		UserContext userContext = UserContextFactory.fromHeader(userId);

		return RestMapper.toResponse(deleteExpenseUseCase.deleteExpense(
				userContext,
				new DeleteExpenseCommand(expenseId, requireBody(request).expectedVersion())));
	}

	private static Set<UpdateExpenseSharesCommand.ExpenseShareInput> toExpenseShareInputs(
			java.util.List<ExpenseShareRequest> shares) {
		return requireList(shares, "shares").stream()
				.map(share -> new UpdateExpenseSharesCommand.ExpenseShareInput(
						share.shareholderId(),
						share.weightNumerator(),
						share.weightDenominator()))
				.collect(Collectors.toSet());
	}
}
