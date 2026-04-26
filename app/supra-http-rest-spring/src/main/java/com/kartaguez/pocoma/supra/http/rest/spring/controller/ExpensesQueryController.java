package com.kartaguez.pocoma.supra.http.rest.spring.controller;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kartaguez.pocoma.engine.port.in.query.intent.GetExpenseQuery;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetExpenseUseCase;
import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.supra.http.rest.spring.config.OpenApiConfiguration;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ExpenseViewResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.mapper.RestMapper;
import com.kartaguez.pocoma.supra.http.rest.spring.security.UserContextFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/expenses")
@Tag(name = "Expense queries")
@SecurityRequirement(name = OpenApiConfiguration.USER_ID_HEADER)
public class ExpensesQueryController {

	private final GetExpenseUseCase getExpenseUseCase;

	public ExpensesQueryController(GetExpenseUseCase getExpenseUseCase) {
		this.getExpenseUseCase = Objects.requireNonNull(getExpenseUseCase, "getExpenseUseCase must not be null");
	}

	@GetMapping("/{expenseId}")
	@Operation(summary = "Get an expense")
	public ExpenseViewResponse getExpense(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID expenseId,
			@RequestParam(required = false) Long version) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		return RestMapper.toResponse(getExpenseUseCase.getExpense(
				userContext,
				new GetExpenseQuery(expenseId, optionalVersion(version))));
	}

	private static OptionalLong optionalVersion(Long version) {
		return version == null ? OptionalLong.empty() : OptionalLong.of(version);
	}
}
