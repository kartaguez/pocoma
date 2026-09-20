package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;

final class ReadPotInterpreter {
	private static final Comparator<ShareholderView> SHAREHOLDER_ORDER = Comparator
			.comparing(view -> view.shareholderId().value());
	private static final Comparator<ExpenseView> EXPENSE_ORDER = Comparator
			.comparing(view -> view.expenseId().value());
	private static final Comparator<ExpenseShareView> SHARE_ORDER = Comparator
			.comparing(view -> view.shareholderId().value());

	PotView interpret(ValidatedProjection validatedProjection) {
		requireNonNull(validatedProjection, "validatedProjection must not be null");
		ProjectionKey key = validatedProjection.projection().projectionKey();
		try {
			ProjectionArtifact potArtifact = validatedProjection.projection().artifacts().stream()
					.filter(artifact -> artifact.artifactType().equals(ReadPotProjectionDefinition.POT))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException("missing POT artifact"));
			var potPayload = JsonValueReader.object(potArtifact.payload());
			PotId potId = new PotId(JsonValueReader.uuid(potPayload, "potId"));
			assertPotIdentity(potArtifact, potId, key);

			var shareholdersById = new HashMap<ShareholderId, ShareholderView>();
			for (ProjectionArtifact artifact : validatedProjection.projection().artifacts()) {
				if (artifact.artifactType().equals(ReadPotProjectionDefinition.SHAREHOLDER)) {
					ShareholderView shareholder = shareholder(artifact, key);
					shareholdersById.put(shareholder.shareholderId(), shareholder);
				}
			}

			var expenses = new ArrayList<ExpenseView>();
			for (ProjectionArtifact artifact : validatedProjection.projection().artifacts()) {
				if (artifact.artifactType().equals(ReadPotProjectionDefinition.EXPENSE)) {
					expenses.add(expense(artifact, shareholdersById, key));
				}
			}

			List<ShareholderView> shareholders = shareholdersById.values().stream()
					.sorted(SHAREHOLDER_ORDER)
					.toList();
			expenses.sort(EXPENSE_ORDER);
			return new PotView(potId, key.targetVersion(), new Label(JsonValueReader.string(potPayload, "name")),
					shareholders, expenses);
		}
		catch (ReadPotInvariantViolationException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new ReadPotInvariantViolationException(key,
					"READ_POT projection cannot be interpreted", exception);
		}
	}

	private static ShareholderView shareholder(ProjectionArtifact artifact, ProjectionKey key) {
		Map<String, JsonValue> payload = JsonValueReader.object(artifact.payload());
		ShareholderId shareholderId = new ShareholderId(JsonValueReader.uuid(payload, "shareholderId"));
		assertArtifactKey(artifact, shareholderId.value(), key);
		JsonValue userIdValue = JsonValueReader.required(payload, "userId");
		Optional<UserId> userId;
		if (userIdValue == JsonNull.INSTANCE) {
			userId = Optional.empty();
		}
		else if (userIdValue instanceof JsonString string) {
			userId = Optional.of(new UserId(UUID.fromString(string.value())));
		}
		else {
			throw new IllegalArgumentException("expected UUID string or JSON null for userId");
		}
		return new ShareholderView(shareholderId, new Name(JsonValueReader.string(payload, "name")),
				userId, new Weight(fraction(JsonValueReader.required(payload, "part"))));
	}

	private static ExpenseView expense(ProjectionArtifact artifact,
			Map<ShareholderId, ShareholderView> shareholdersById, ProjectionKey key) {
		Map<String, JsonValue> payload = JsonValueReader.object(artifact.payload());
		ExpenseId expenseId = new ExpenseId(JsonValueReader.uuid(payload, "expenseId"));
		assertArtifactKey(artifact, expenseId.value(), key);
		ShareholderId payerId = new ShareholderId(JsonValueReader.uuid(payload, "payerShareholderId"));
		if (!shareholdersById.containsKey(payerId)) {
			throw violation(key, "expense payer does not reference a projection shareholder: " + payerId);
		}

		var shares = new ArrayList<ExpenseShareView>();
		for (JsonValue shareValue : JsonValueReader.array(JsonValueReader.required(payload, "shares"))) {
			Map<String, JsonValue> share = JsonValueReader.object(shareValue);
			ShareholderId shareholderId = new ShareholderId(JsonValueReader.uuid(share, "shareholderId"));
			if (!shareholdersById.containsKey(shareholderId)) {
				throw violation(key, "expense share does not reference a projection shareholder: " + shareholderId);
			}
			shares.add(new ExpenseShareView(shareholderId,
					new Weight(fraction(JsonValueReader.required(share, "part")))));
		}
		if (shares.isEmpty()) {
			throw violation(key, "expense must contain at least one share");
		}
		shares.sort(SHARE_ORDER);
		return new ExpenseView(
				expenseId,
				new Label(JsonValueReader.string(payload, "name")),
				new Amount(fraction(JsonValueReader.required(payload, "amount"))),
				LocalDate.parse(JsonValueReader.string(payload, "date")),
				payerId,
				shares);
	}

	private static Fraction fraction(JsonValue value) {
		Map<String, JsonValue> fraction = JsonValueReader.object(value);
		long numerator = JsonValueReader.integer(fraction, "numerator");
		long denominator = JsonValueReader.integer(fraction, "denominator");
		if (numerator < 0 || denominator <= 0) {
			throw new IllegalArgumentException("fraction must have a non-negative numerator and positive denominator");
		}
		return new Fraction(numerator, denominator);
	}

	private static void assertPotIdentity(ProjectionArtifact artifact, PotId potId, ProjectionKey key) {
		String canonicalPotId = potId.value().toString();
		if (!artifact.artifactKey().value().equals(canonicalPotId)
				|| !key.targetObjectId().value().equals(canonicalPotId)) {
			throw violation(key, "POT artifact key, payload potId and projection targetObjectId must match");
		}
	}

	private static void assertArtifactKey(ProjectionArtifact artifact, UUID identifier, ProjectionKey key) {
		if (!artifact.artifactKey().value().equals(identifier.toString())) {
			throw violation(key, artifact.artifactType().value() + " artifact key does not match payload identity");
		}
	}

	private static ReadPotInvariantViolationException violation(ProjectionKey key, String message) {
		return new ReadPotInvariantViolationException(key, message);
	}
}
