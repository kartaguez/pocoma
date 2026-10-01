package com.kartaguez.pocoma.supra.http.read.query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.engine.pot.read.ExpenseShareView;
import com.kartaguez.pocoma.engine.pot.read.ExpenseView;
import com.kartaguez.pocoma.engine.pot.read.PotView;
import com.kartaguez.pocoma.engine.pot.read.ShareholderView;

public record PotResponse(UUID potId, long version, String label,
		List<ShareholderResponse> shareholders, List<ExpenseResponse> expenses) {

	public static PotResponse from(PotView view) {
		return new PotResponse(view.potId().value(), view.version(), view.name().value(),
				view.shareholders().stream().map(ShareholderResponse::from).toList(),
				view.expenses().stream().map(ExpenseResponse::from).toList());
	}

	public record FractionResponse(long numerator, long denominator) {
		static FractionResponse from(Fraction value) {
			return new FractionResponse(value.numerator(), value.denominator());
		}
	}

	public record ShareholderResponse(UUID shareholderId, String name, UUID userId, FractionResponse part) {
		static ShareholderResponse from(ShareholderView view) {
			return new ShareholderResponse(view.shareholderId().value(), view.name().value(),
					view.userId().map(userId -> userId.value()).orElse(null),
					FractionResponse.from(view.part().value()));
		}
	}

	public record ExpenseShareResponse(UUID shareholderId, FractionResponse part) {
		static ExpenseShareResponse from(ExpenseShareView view) {
			return new ExpenseShareResponse(view.shareholderId().value(), FractionResponse.from(view.part().value()));
		}
	}

	public record ExpenseResponse(UUID expenseId, String label, FractionResponse amount, LocalDate date,
			UUID payerShareholderId, List<ExpenseShareResponse> shares) {
		static ExpenseResponse from(ExpenseView view) {
			return new ExpenseResponse(view.expenseId().value(), view.name().value(),
					FractionResponse.from(view.amount().value()), view.date(), view.payerShareholderId().value(),
					view.shares().stream().map(ExpenseShareResponse::from).toList());
		}
	}
}
