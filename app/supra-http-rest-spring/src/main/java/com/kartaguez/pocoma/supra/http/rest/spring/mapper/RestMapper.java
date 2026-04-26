package com.kartaguez.pocoma.supra.http.rest.spring.mapper;

import java.util.Comparator;

import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.result.BalanceSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.result.ExpenseViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.result.PotBalancesSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.result.PotViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.result.UserPotBalanceSnapshot;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.BalanceResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ExpenseHeaderResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ExpenseShareResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ExpenseSharesResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ExpenseViewResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.FractionResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.PotBalancesResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.PotResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.PotShareholdersResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.PotViewResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ShareholderResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.UserPotBalanceResponse;

public final class RestMapper {

	private RestMapper() {
	}

	public static PotResponse toResponse(PotHeaderSnapshot snapshot) {
		return new PotResponse(
				snapshot.id().value().toString(),
				snapshot.label().value(),
				snapshot.creatorId().value().toString(),
				snapshot.deleted(),
				snapshot.version());
	}

	public static PotShareholdersResponse toResponse(PotShareholdersSnapshot snapshot) {
		return new PotShareholdersResponse(
				snapshot.potId().value().toString(),
				snapshot.shareholders().stream()
						.sorted(Comparator.comparing(shareholder -> shareholder.id().value().toString()))
						.map(RestMapper::toResponse)
						.toList(),
				snapshot.version());
	}

	public static ExpenseHeaderResponse toResponse(ExpenseHeaderSnapshot snapshot) {
		return new ExpenseHeaderResponse(
				snapshot.id().value().toString(),
				snapshot.potId().value().toString(),
				snapshot.payerId().value().toString(),
				toResponse(snapshot.amount().value()),
				snapshot.label().value(),
				snapshot.deleted(),
				snapshot.version());
	}

	public static ExpenseSharesResponse toResponse(ExpenseSharesSnapshot snapshot) {
		return new ExpenseSharesResponse(
				snapshot.expenseId().value().toString(),
				snapshot.potId().value().toString(),
				snapshot.shares().values().stream()
						.sorted(Comparator.comparing(share -> share.shareholderId().value().toString()))
						.map(RestMapper::toResponse)
						.toList(),
				snapshot.version());
	}

	public static PotViewResponse toResponse(PotViewSnapshot snapshot) {
		return new PotViewResponse(toResponse(snapshot.header()), toResponse(snapshot.shareholders()));
	}

	public static ExpenseViewResponse toResponse(ExpenseViewSnapshot snapshot) {
		return new ExpenseViewResponse(toResponse(snapshot.header()), toResponse(snapshot.shares()));
	}

	public static PotBalancesResponse toResponse(PotBalancesSnapshot snapshot) {
		return new PotBalancesResponse(
				snapshot.potId().value().toString(),
				snapshot.version(),
				snapshot.balances().stream()
						.sorted(Comparator.comparing(balance -> balance.shareholderId().value().toString()))
						.map(RestMapper::toResponse)
						.toList());
	}

	public static UserPotBalanceResponse toResponse(UserPotBalanceSnapshot snapshot) {
		return new UserPotBalanceResponse(
				toResponse(snapshot.pot()),
				snapshot.shareholderId().value().toString(),
				toResponse(snapshot.balance()),
				snapshot.version());
	}

	private static ShareholderResponse toResponse(Shareholder shareholder) {
		return new ShareholderResponse(
				shareholder.id().value().toString(),
				shareholder.potId().value().toString(),
				shareholder.name().value(),
				toResponse(shareholder.weight().value()),
				shareholder.userId() == null ? null : shareholder.userId().value().toString(),
				shareholder.deleted());
	}

	private static ExpenseShareResponse toResponse(ExpenseShare share) {
		return new ExpenseShareResponse(
				share.shareholderId().value().toString(),
				toResponse(share.weight().value()));
	}

	private static BalanceResponse toResponse(BalanceSnapshot balance) {
		return new BalanceResponse(
				balance.shareholderId().value().toString(),
				toResponse(balance.value()));
	}

	private static FractionResponse toResponse(Fraction fraction) {
		return new FractionResponse(fraction.numerator(), fraction.denominator());
	}
}
