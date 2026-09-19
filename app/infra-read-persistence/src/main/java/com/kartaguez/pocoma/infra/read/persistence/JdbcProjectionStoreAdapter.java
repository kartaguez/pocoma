package com.kartaguez.pocoma.infra.read.persistence;

import static java.util.Objects.requireNonNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.ArtifactType;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionFailure;
import com.kartaguez.pocoma.domain.projection.ProjectionFailureId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionPublicationResult;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionReadPort;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionWritePort;

public final class JdbcProjectionStoreAdapter implements ProjectionReadPort, ProjectionWritePort {
	private static final Comparator<ProjectionArtifact> ARTIFACT_ORDER = Comparator
			.comparing((ProjectionArtifact artifact) -> artifact.artifactType().value())
			.thenComparing(artifact -> artifact.artifactKey().value());

	private final JdbcOperations jdbc;
	private final TransactionOperations transactions;
	private final JsonValueCodec jsonCodec;
	private final String schema;

	JdbcProjectionStoreAdapter(JdbcOperations jdbc, TransactionOperations transactions,
			JsonValueCodec jsonCodec, String schema) {
		this.jdbc = requireNonNull(jdbc, "jdbc must not be null");
		this.transactions = requireNonNull(transactions, "transactions must not be null");
		this.jsonCodec = requireNonNull(jsonCodec, "jsonCodec must not be null");
		if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("schema must be a simple SQL identifier");
		}
		this.schema = schema;
	}

	@Override
	public ProjectionPublicationResult publish(ValidatedProjection validatedProjection) {
		requireNonNull(validatedProjection, "projection must not be null");
		return transactions.execute(status -> publishInTransaction(validatedProjection.projection()));
	}

	private ProjectionPublicationResult publishInTransaction(Projection projection) {
		ProjectionKey key = projection.projectionKey();
		List<Long> insertedIds = jdbc.query("""
				insert into %s
				    (projection_type, target_object_type, target_object_id, target_version)
				values (?, ?, ?, ?)
				on conflict (projection_type, target_object_type, target_object_id, target_version)
				do nothing
				returning id
				""".formatted(table("projection_root")),
				(rs, rowNum) -> rs.getLong(1), keyArguments(key));
		if (insertedIds.isEmpty()) {
			return ProjectionPublicationResult.ALREADY_EXISTS;
		}

		long rootId = insertedIds.getFirst();
		for (ProjectionArtifact artifact : projection.artifacts()) {
			jdbc.update("""
					insert into %s
					    (projection_root_id, artifact_type, artifact_key, payload)
					values (?, ?, ?, cast(? as jsonb))
					""".formatted(table("projection_artifact")), rootId,
					artifact.artifactType().value(), artifact.artifactKey().value(),
					jsonCodec.encode(artifact.payload()));
		}
		return ProjectionPublicationResult.PUBLISHED;
	}

	@Override
	public void recordFailure(ProjectionFailure failure) {
		requireNonNull(failure, "failure must not be null");
		transactions.executeWithoutResult(status -> recordFailureInTransaction(failure));
	}

	private void recordFailureInTransaction(ProjectionFailure failure) {
		Instant normalizedFailedAt = normalize(failure.failedAt());
		ProjectionKey key = failure.projectionKey();
		int inserted = jdbc.update("""
				insert into %s
				    (failure_id, projection_type, target_object_type, target_object_id, target_version, failed_at)
				values (?, ?, ?, ?, ?, ?)
				on conflict (failure_id) do nothing
				""".formatted(table("projection_failure")), failure.id().value(),
				key.projectionType().value(), key.targetObjectType().value(), key.targetObjectId().value(),
				key.targetVersion(), Timestamp.from(normalizedFailedAt));
		if (inserted == 1) {
			return;
		}

		StoredFailure stored = jdbc.query("""
				select projection_type, target_object_type, target_object_id, target_version, failed_at
				from %s
				where failure_id = ?
				""".formatted(table("projection_failure")),
				(rs, rowNum) -> new StoredFailure(
						new ProjectionKey(new ProjectionType(rs.getString(1)),
								new TargetObjectType(rs.getString(2)), new TargetObjectId(rs.getString(3)),
								rs.getLong(4)), rs.getTimestamp(5).toInstant()),
				failure.id().value()).stream().findFirst().orElseThrow(() -> new IllegalStateException(
						"Conflicting ProjectionFailureId is not visible after insert conflict: " + failure.id().value()));
		if (!stored.projectionKey().equals(key) || !stored.failedAt().equals(normalizedFailedAt)) {
			throw new ProjectionFailureIdConflictException(
					"ProjectionFailureId was reused with different persisted content: " + failure.id().value());
		}
	}

	@Override
	public Optional<Projection> findProjection(ProjectionKey key) {
		requireNonNull(key, "key must not be null");
		Optional<Long> rootId = jdbc.query("""
				select id from %s
				where projection_type = ? and target_object_type = ? and target_object_id = ? and target_version = ?
				""".formatted(table("projection_root")), (rs, rowNum) -> rs.getLong(1), keyArguments(key))
				.stream().findFirst();
		if (rootId.isEmpty()) {
			return Optional.empty();
		}

		List<ProjectionArtifact> artifacts = jdbc.query("""
				select artifact_type, artifact_key, payload::text
				from %s
				where projection_root_id = ?
				""".formatted(table("projection_artifact")),
				(rs, rowNum) -> new ProjectionArtifact(new ArtifactType(rs.getString(1)),
						new ArtifactKey(rs.getString(2)), jsonCodec.decode(rs.getString(3))), rootId.get())
				.stream().sorted(ARTIFACT_ORDER).toList();
		return Optional.of(new Projection(key, artifacts));
	}

	@Override
	public boolean hasFailure(ProjectionKey key) {
		requireNonNull(key, "key must not be null");
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				select exists (
				    select 1 from %s
				    where projection_type = ? and target_object_type = ?
				      and target_object_id = ? and target_version = ?
				)
				""".formatted(table("projection_failure")), Boolean.class, keyArguments(key)));
	}

	private String table(String name) {
		return schema + "." + name;
	}

	private static Object[] keyArguments(ProjectionKey key) {
		return new Object[] { key.projectionType().value(), key.targetObjectType().value(),
				key.targetObjectId().value(), key.targetVersion() };
	}

	private static Instant normalize(Instant failedAt) {
		return failedAt.truncatedTo(ChronoUnit.MICROS);
	}

	private record StoredFailure(ProjectionKey projectionKey, Instant failedAt) {
	}
}
