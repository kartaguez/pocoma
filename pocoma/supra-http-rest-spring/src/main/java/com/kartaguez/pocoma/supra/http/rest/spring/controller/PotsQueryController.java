package com.kartaguez.pocoma.supra.http.rest.spring.controller;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotQuery;
import com.kartaguez.pocoma.engine.port.in.query.intent.ListPotExpensesQuery;
import com.kartaguez.pocoma.engine.port.in.query.intent.ListUserPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListPotExpensesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotsUseCase;
import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.supra.http.rest.spring.config.OpenApiConfiguration;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ExpenseHeaderResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.PotBalancesResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.PotResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.PotViewResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.UserPotBalanceResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.mapper.RestMapper;
import com.kartaguez.pocoma.supra.http.rest.spring.security.UserContextFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/pots")
@Tag(name = "Pot queries")
@SecurityRequirement(name = OpenApiConfiguration.USER_ID_HEADER)
public class PotsQueryController {

	private final ListUserPotsUseCase listUserPotsUseCase;
	private final GetPotUseCase getPotUseCase;
	private final ListPotExpensesUseCase listPotExpensesUseCase;
	private final GetPotBalancesUseCase getPotBalancesUseCase;
	private final ListUserPotBalancesUseCase listUserPotBalancesUseCase;

	public PotsQueryController(
			ListUserPotsUseCase listUserPotsUseCase,
			GetPotUseCase getPotUseCase,
			ListPotExpensesUseCase listPotExpensesUseCase,
			GetPotBalancesUseCase getPotBalancesUseCase,
			ListUserPotBalancesUseCase listUserPotBalancesUseCase) {
		this.listUserPotsUseCase = Objects.requireNonNull(listUserPotsUseCase, "listUserPotsUseCase must not be null");
		this.getPotUseCase = Objects.requireNonNull(getPotUseCase, "getPotUseCase must not be null");
		this.listPotExpensesUseCase = Objects.requireNonNull(
				listPotExpensesUseCase,
				"listPotExpensesUseCase must not be null");
		this.getPotBalancesUseCase = Objects.requireNonNull(
				getPotBalancesUseCase,
				"getPotBalancesUseCase must not be null");
		this.listUserPotBalancesUseCase = Objects.requireNonNull(
				listUserPotBalancesUseCase,
				"listUserPotBalancesUseCase must not be null");
	}

	@GetMapping
	@Operation(summary = "List accessible pots")
	public List<PotResponse> listUserPots(@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		return listUserPotsUseCase.listUserPots(userContext).stream()
				.map(RestMapper::toResponse)
				.toList();
	}

	@GetMapping("/balances/me")
	@Operation(summary = "List current user's balances by pot")
	public List<UserPotBalanceResponse> listUserPotBalances(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@RequestParam(required = false) Long version) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		return listUserPotBalancesUseCase
				.listUserPotBalances(userContext, new ListUserPotBalancesQuery(optionalVersion(version)))
				.stream()
				.map(RestMapper::toResponse)
				.toList();
	}

	@GetMapping("/{potId}")
	@Operation(summary = "Get a pot")
	public PotViewResponse getPot(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID potId,
			@RequestParam(required = false) Long version) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		return RestMapper.toResponse(getPotUseCase.getPot(
				userContext,
				new GetPotQuery(potId, optionalVersion(version))));
	}

	@GetMapping("/{potId}/expenses")
	@Operation(summary = "List pot expenses")
	public List<ExpenseHeaderResponse> listPotExpenses(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID potId,
			@RequestParam(required = false) Long version) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		return listPotExpensesUseCase
				.listPotExpenses(userContext, new ListPotExpensesQuery(potId, optionalVersion(version)))
				.stream()
				.map(RestMapper::toResponse)
				.toList();
	}

	@GetMapping("/{potId}/balances")
	@Operation(summary = "Get pot balances")
	public PotBalancesResponse getPotBalances(
			@RequestHeader(UserContextFactory.USER_ID_HEADER) String userId,
			@PathVariable UUID potId,
			@RequestParam(required = false) Long version) {
		UserContext userContext = UserContextFactory.fromHeader(userId);
		return RestMapper.toResponse(getPotBalancesUseCase.getPotBalances(
				userContext,
				new GetPotBalancesQuery(potId, optionalVersion(version))));
	}

	private static OptionalLong optionalVersion(Long version) {
		return version == null ? OptionalLong.empty() : OptionalLong.of(version);
	}
}
