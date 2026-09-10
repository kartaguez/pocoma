package com.kartaguez.pocoma.infra.read.persistence;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcOperations;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.PotProjection;
import com.kartaguez.pocoma.domain.projection.PotProjectionExpense;
import com.kartaguez.pocoma.domain.projection.PotProjectionExpenseShare;
import com.kartaguez.pocoma.domain.projection.PotProjectionShareholder;
import com.kartaguez.pocoma.domain.projection.PotProjectionStatus;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactDescriptor;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactId;
import com.kartaguez.pocoma.domain.projection.ProjectionContentDigest;
import com.kartaguez.pocoma.domain.projection.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.read.projection.PotProjectionArtifactReader;
import com.kartaguez.pocoma.engine.read.projection.ProjectionArtifactWriter;
import com.kartaguez.pocoma.engine.read.projection.ReconstructedPotProjection;

public final class JdbcPotProjectionArtifactWriter
		implements ProjectionArtifactWriter<ReconstructedPotProjection>, PotProjectionArtifactReader {

	private static final int DIGEST_FORMAT_VERSION = 1;
	private static final String VALID_SCHEMA_NAME = "[A-Za-z_][A-Za-z0-9_]*";

	private final JdbcOperations jdbc;
	private final String schema;

	public JdbcPotProjectionArtifactWriter(JdbcOperations jdbc, String schema) {
		this.jdbc = Objects.requireNonNull(jdbc);
		if (schema == null || !schema.matches(VALID_SCHEMA_NAME)) {
			throw new IllegalArgumentException("invalid schema");
		}
		this.schema = schema;
	}

	@Override
	public ProjectionContentDigest digest(ReconstructedPotProjection reconstructed) {
		return digestProjection(reconstructed.projection());
	}

	public ProjectionContentDigest digestProjection(PotProjection projection) {
		try {
			var bytes = new ByteArrayOutputStream();
			var output = new DataOutputStream(bytes);
			output.writeInt(DIGEST_FORMAT_VERSION);
			writeFunctionalContent(output, projection);

			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
			return new ProjectionContentDigest(HexFormat.of().formatHex(digest));
		} catch (Exception exception) {
			throw new IllegalStateException("Cannot digest PotProjection", exception);
		}
	}

	private static void writeFunctionalContent(DataOutputStream output, PotProjection projection)
			throws IOException {
		writeString(output, projection.status().name());
		writeString(output, projection.label());
		writeString(output, projection.creatorId().value().toString());

		output.writeInt(projection.shareholders().size());
		for (var shareholder : projection.shareholders()) {
			writeString(output, shareholder.shareholderId().value().toString());
			writeString(output, shareholder.name());
			writeFraction(output, shareholder.weight());
			output.writeBoolean(shareholder.userId().isPresent());
			if (shareholder.userId().isPresent()) {
				writeString(output, shareholder.userId().get().value().toString());
			}
			output.writeBoolean(shareholder.deleted());
		}

		output.writeInt(projection.expenses().size());
		for (var expense : projection.expenses()) {
			writeString(output, expense.expenseId().value().toString());
			writeString(output, expense.payerId().value().toString());
			writeFraction(output, expense.amount());
			writeString(output, expense.label());
			output.writeBoolean(expense.deleted());
			output.writeInt(expense.shares().size());
			for (var share : expense.shares()) {
				writeString(output, share.shareholderId().value().toString());
				writeFraction(output, share.weight());
			}
		}
	}

	@Override
	public void write(
			ProjectionArtifactId artifactId,
			ProjectionIdentity identity,
			ReconstructedPotProjection reconstructed) {
		var projection = reconstructed.projection();
		if (!identity.equals(projection.identity())) {
			throw new IllegalArgumentException("artifact identity mismatch");
		}

		ensureVersionMetadata(reconstructed);
		insertSnapshot(artifactId, identity, projection);
		insertShareholders(artifactId, projection);
		insertExpenses(artifactId, projection);
		insertUserIndex(artifactId, identity, reconstructed);
	}

	private void ensureVersionMetadata(ReconstructedPotProjection reconstructed) {
		var metadata = reconstructed.versionMetadata();
		int inserted = jdbc.update(
				"insert into " + table("pot_version_metadata")
						+ " (pot_id,pot_version,created_at) values (?,?,?) on conflict do nothing",
				metadata.potId().value(),
				metadata.version(),
				java.sql.Timestamp.from(metadata.createdAt()));
		if (inserted == 1) {
			return;
		}
		var existing = jdbc.queryForObject(
				"select created_at from " + table("pot_version_metadata")
						+ " where pot_id=? and pot_version=?",
				(rs, row) -> rs.getTimestamp(1).toInstant(),
				metadata.potId().value(),
				metadata.version());
		if (!metadata.createdAt().equals(existing)) {
			throw new IllegalStateException("divergent Pot version metadata");
		}
	}

	private void insertUserIndex(
			ProjectionArtifactId artifactId,
			ProjectionIdentity identity,
			ReconstructedPotProjection reconstructed) {
		var projection = reconstructed.projection();
		var users = new LinkedHashSet<UUID>();
		users.add(projection.creatorId().value());
		projection.shareholders().stream()
				.filter(shareholder -> !shareholder.deleted())
				.flatMap(shareholder -> shareholder.userId().stream())
				.map(UserId::value)
				.sorted()
				.forEach(users::add);

		var generation = identity.generation();
		for (var userId : users) {
			ensureUserIndexEntry(
					artifactId,
					identity,
					userId,
					reconstructed.versionMetadata().createdAt(),
					projection.status());
		}
	}

	void ensureUserIndexEntry(
			ProjectionArtifactId artifactId,
			ProjectionIdentity identity,
			UUID userId,
			java.time.Instant updatedAt,
			PotProjectionStatus potStatus) {
		var generation = identity.generation();
		int inserted = jdbc.update(
				"insert into " + table("pot_projection_user_index")
						+ " (artifact_id,pipeline_id,pipeline_version,pot_id,pot_version,user_id,updated_at,pot_status)"
						+ " values (?,?,?,?,?,?,?,?) on conflict do nothing",
				artifactId.value(),
				generation.pipeline().pipelineId().value(),
				generation.pipeline().pipelineVersion(),
				generation.potId().value(),
				identity.potVersion(),
				userId,
				java.sql.Timestamp.from(updatedAt),
				potStatus.name());
		if (inserted == 1) {
			return;
		}

		var existing = jdbc.query(
				"select artifact_id,updated_at,pot_status from " + table("pot_projection_user_index")
						+ " where pipeline_id=? and pipeline_version=? and pot_id=? and pot_version=? and user_id=?",
				(rs, row) -> new UserIndexContent(
						rs.getObject(1, UUID.class),
						rs.getTimestamp(2).toInstant(),
						PotProjectionStatus.valueOf(rs.getString(3))),
				generation.pipeline().pipelineId().value(),
				generation.pipeline().pipelineVersion(),
				generation.potId().value(),
				identity.potVersion(),
				userId);
		var expected = new UserIndexContent(artifactId.value(), updatedAt, potStatus);
		if (existing.size() != 1 || !expected.equals(existing.getFirst())) {
			throw new IllegalStateException("divergent Pot user index entry");
		}
	}

	private record UserIndexContent(UUID artifactId, java.time.Instant updatedAt, PotProjectionStatus potStatus) {
	}

	private void insertSnapshot(
			ProjectionArtifactId artifactId,
			ProjectionIdentity identity,
			PotProjection projection) {
		var generation = identity.generation();
		jdbc.update(
				"insert into " + table("pot_projection_snapshots")
						+ " (artifact_id,projection_type,pipeline_id,pipeline_version,pot_id,pot_version,status,label,creator_id)"
						+ " values (?,?,?,?,?,?,?,?,?)",
				artifactId.value(),
				generation.projectionType().value(),
				generation.pipeline().pipelineId().value(),
				generation.pipeline().pipelineVersion(),
				generation.potId().value(),
				identity.potVersion(),
				projection.status().name(),
				projection.label(),
				projection.creatorId().value());
	}

	private void insertShareholders(ProjectionArtifactId artifactId, PotProjection projection) {
		int ordinal = 0;
		for (var shareholder : projection.shareholders()) {
			jdbc.update(
					"insert into " + table("pot_projection_shareholders")
							+ " (artifact_id,shareholder_id,ordinal,name,weight_numerator,weight_denominator,user_id,deleted)"
							+ " values (?,?,?,?,?,?,?,?)",
					artifactId.value(),
					shareholder.shareholderId().value(),
					ordinal++,
					shareholder.name(),
					shareholder.weight().numerator(),
					shareholder.weight().denominator(),
					shareholder.userId().map(userId -> userId.value()).orElse(null),
					shareholder.deleted());
		}
	}

	private void insertExpenses(ProjectionArtifactId artifactId, PotProjection projection) {
		int expenseOrdinal = 0;
		for (var expense : projection.expenses()) {
			jdbc.update(
					"insert into " + table("pot_projection_expenses")
							+ " (artifact_id,expense_id,ordinal,payer_id,amount_numerator,amount_denominator,label,deleted)"
							+ " values (?,?,?,?,?,?,?,?)",
					artifactId.value(),
					expense.expenseId().value(),
					expenseOrdinal++,
					expense.payerId().value(),
					expense.amount().numerator(),
					expense.amount().denominator(),
					expense.label(),
					expense.deleted());
			insertExpenseShares(artifactId, expense);
		}
	}

	private void insertExpenseShares(ProjectionArtifactId artifactId, PotProjectionExpense expense) {
		int shareOrdinal = 0;
		for (var share : expense.shares()) {
			jdbc.update(
					"insert into " + table("pot_projection_expense_shares")
							+ " (artifact_id,expense_id,shareholder_id,ordinal,weight_numerator,weight_denominator)"
							+ " values (?,?,?,?,?,?)",
					artifactId.value(),
					expense.expenseId().value(),
					share.shareholderId().value(),
					shareOrdinal++,
					share.weight().numerator(),
					share.weight().denominator());
		}
	}

	@Override
	public boolean hasSameContent(
			ProjectionArtifactDescriptor existing,
			ReconstructedPotProjection proposed) {
		if (!existing.digest().equals(digest(proposed))) {
			return false;
		}
		var metadata = proposed.versionMetadata();
		var stored = jdbc.query(
				"select created_at from " + table("pot_version_metadata")
						+ " where pot_id=? and pot_version=?",
				(rs, row) -> rs.getTimestamp(1).toInstant(),
				metadata.potId().value(),
				metadata.version());
		return stored.size() == 1 && stored.getFirst().equals(metadata.createdAt());
	}

	@Override
	public Optional<PotProjection> findByArtifactId(ProjectionArtifactId artifactId) {
		Objects.requireNonNull(artifactId);
		var snapshots = loadSnapshotRows(artifactId);
		if (snapshots.isEmpty()) {
			return Optional.empty();
		}
		if (snapshots.size() != 1) {
			throw new IllegalStateException("duplicate Pot snapshot artifact");
		}

		var shareholders = loadShareholders(artifactId);
		var expenses = loadExpenses(artifactId);
		var snapshot = snapshots.getFirst();
		return Optional.of(new PotProjection(
				snapshot.identity(),
				snapshot.status(),
				snapshot.label(),
				snapshot.creatorId(),
				shareholders,
				expenses));
	}

	private List<SnapshotRow> loadSnapshotRows(ProjectionArtifactId artifactId) {
		return jdbc.query(
				"select projection_type,pipeline_id,pipeline_version,pot_id,pot_version,status,label,creator_id"
						+ " from " + table("pot_projection_snapshots") + " where artifact_id=?",
				(rs, row) -> new SnapshotRow(
						new ProjectionIdentity(
								new ProjectionGenerationIdentity(
										new ProjectionType(rs.getString(1)),
										new PipelineDefinition(PipelineId.of(rs.getString(2)), rs.getInt(3)),
										PotId.of(rs.getObject(4, UUID.class))),
								rs.getLong(5)),
						PotProjectionStatus.valueOf(rs.getString(6)),
						rs.getString(7),
						UserId.of(rs.getObject(8, UUID.class))),
				artifactId.value());
	}

	private List<PotProjectionShareholder> loadShareholders(ProjectionArtifactId artifactId) {
		return jdbc.query(
				"select shareholder_id,name,weight_numerator,weight_denominator,user_id,deleted"
						+ " from " + table("pot_projection_shareholders")
						+ " where artifact_id=? order by ordinal",
				(rs, row) -> new PotProjectionShareholder(
						ShareholderId.of(rs.getObject(1, UUID.class)),
						rs.getString(2),
						Fraction.of(rs.getLong(3), rs.getLong(4)),
						Optional.ofNullable(rs.getObject(5, UUID.class)).map(UserId::of),
						rs.getBoolean(6)),
				artifactId.value());
	}

	private List<PotProjectionExpense> loadExpenses(ProjectionArtifactId artifactId) {
		var expenseRows = jdbc.query(
				"select expense_id,payer_id,amount_numerator,amount_denominator,label,deleted"
						+ " from " + table("pot_projection_expenses")
						+ " where artifact_id=? order by ordinal",
				(rs, row) -> new ExpenseRow(
						ExpenseId.of(rs.getObject(1, UUID.class)),
						ShareholderId.of(rs.getObject(2, UUID.class)),
						Fraction.of(rs.getLong(3), rs.getLong(4)),
						rs.getString(5),
						rs.getBoolean(6)),
				artifactId.value());

		return expenseRows.stream()
				.map(expense -> new PotProjectionExpense(
						expense.expenseId(),
						expense.payerId(),
						expense.amount(),
						expense.label(),
						expense.deleted(),
						loadExpenseShares(artifactId, expense.expenseId())))
				.toList();
	}

	private List<PotProjectionExpenseShare> loadExpenseShares(
			ProjectionArtifactId artifactId,
			ExpenseId expenseId) {
		return jdbc.query(
				"select shareholder_id,weight_numerator,weight_denominator"
						+ " from " + table("pot_projection_expense_shares")
						+ " where artifact_id=? and expense_id=? order by ordinal",
				(rs, row) -> new PotProjectionExpenseShare(
						ShareholderId.of(rs.getObject(1, UUID.class)),
						Fraction.of(rs.getLong(2), rs.getLong(3))),
				artifactId.value(),
				expenseId.value());
	}

	private String table(String name) {
		return schema + "." + name;
	}

	private static void writeString(DataOutputStream output, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		output.writeInt(bytes.length);
		output.write(bytes);
	}

	private static void writeFraction(DataOutputStream output, Fraction fraction) throws IOException {
		output.writeLong(fraction.numerator());
		output.writeLong(fraction.denominator());
	}

	private record SnapshotRow(
			ProjectionIdentity identity,
			PotProjectionStatus status,
			String label,
			UserId creatorId) {
	}

	private record ExpenseRow(
			ExpenseId expenseId,
			ShareholderId payerId,
			Fraction amount,
			String label,
			boolean deleted) {
	}
}
