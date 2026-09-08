package com.kartaguez.pocoma.infra.read.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcOperations;

import com.kartaguez.pocoma.domain.pipeline.*;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.engine.read.projection.ProjectionMetadataPort;

public final class JdbcProjectionMetadataAdapter implements ProjectionMetadataPort {
	private final JdbcOperations jdbc;
	private final String schema;

	public JdbcProjectionMetadataAdapter(JdbcOperations jdbc, String schema) {
		this.jdbc = jdbc;
		if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("invalid schema");
		this.schema = schema;
	}

	@Override public Optional<ProjectionCoverage> findCoverage(ProjectionGenerationIdentity generation) {
		return one(jdbc.query("select from_version, through_version from " + table("projection_coverages") + " where " + generationWhere(),
				(rs, n) -> new ProjectionCoverage(generation, rs.getLong(1), rs.getLong(2)), generationArgs(generation)));
	}

	@Override public ProjectionCoverage createCoverage(ProjectionCoverage coverage) {
		var g = coverage.generation();
		jdbc.update("insert into " + table("projection_coverages") + " (projection_type,pipeline_id,pipeline_version,pot_id,from_version,through_version) values (?,?,?,?,?,?) on conflict do nothing",
				g.projectionType().value(), g.pipeline().pipelineId().value(), g.pipeline().pipelineVersion(), g.potId().value(), coverage.fromVersion(), coverage.throughVersion());
		var stored = findCoverage(g).orElseThrow();
		if (!stored.equals(coverage)) throw new IllegalStateException("Projection coverage already exists with different bounds");
		return stored;
	}

	@Override public ProjectionCoverage extendCoverageThrough(ProjectionGenerationIdentity generation, long version) {
		if (version < 1) throw new IllegalArgumentException("version must be positive");
		jdbc.update("update " + table("projection_coverages") + " set through_version=greatest(through_version, ?) where " + generationWhere(), prepend(version, generationArgs(generation)));
		return findCoverage(generation).orElseThrow(() -> new IllegalStateException("Projection coverage does not exist"));
	}

	@Override public ProjectionCoverage extendCoverageFrom(ProjectionGenerationIdentity generation, long version) {
		if (version < 1) throw new IllegalArgumentException("version must be positive");
		jdbc.update("update " + table("projection_coverages") + " set from_version=least(from_version, ?) where " + generationWhere(), prepend(version, generationArgs(generation)));
		return findCoverage(generation).orElseThrow(() -> new IllegalStateException("Projection coverage does not exist"));
	}

	@Override public void lock(ProjectionIdentity identity) {
		jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, lockKey(identity));
	}

	@Override public Optional<ProjectionArtifactDescriptor> findArtifact(ProjectionIdentity identity) {
		return one(jdbc.query("select artifact_id,content_digest,created_at from " + table("projection_artifacts") + " where " + identityWhere(),
				(rs, n) -> new ProjectionArtifactDescriptor(new ProjectionArtifactId(rs.getObject(1, UUID.class)), identity,
						new ProjectionContentDigest(rs.getString(2).trim()), rs.getTimestamp(3).toInstant()), identityArgs(identity)));
	}

	@Override public Optional<ProjectionFailure> findFailure(ProjectionIdentity identity) {
		return one(jdbc.query("select failed_at,terminal_failure_code from " + table("projection_failures") + " where " + identityWhere(),
				(rs, n) -> new ProjectionFailure(identity, rs.getTimestamp(1).toInstant(), rs.getString(2)), identityArgs(identity)));
	}

	@Override public void insertArtifact(ProjectionArtifactDescriptor d) {
		var i = d.identity(); var g = i.generation();
		jdbc.update("insert into " + table("projection_artifacts") + " (artifact_id,projection_type,pipeline_id,pipeline_version,pot_id,pot_version,content_digest,created_at) values (?,?,?,?,?,?,?,?)",
				d.artifactId().value(), g.projectionType().value(), g.pipeline().pipelineId().value(), g.pipeline().pipelineVersion(), g.potId().value(), i.potVersion(), d.digest().value(), Timestamp.from(d.createdAt()));
	}

	@Override public void insertFailure(ProjectionFailure f) {
		var i=f.identity(); var g=i.generation();
		jdbc.update("insert into " + table("projection_failures") + " (projection_type,pipeline_id,pipeline_version,pot_id,pot_version,failed_at,terminal_failure_code) values (?,?,?,?,?,?,?)",
				g.projectionType().value(),g.pipeline().pipelineId().value(),g.pipeline().pipelineVersion(),g.potId().value(),i.potVersion(),Timestamp.from(f.failedAt()),f.terminalFailureCode());
	}

	@Override public ProjectionHead advanceHead(ProjectionGenerationIdentity g, long version, Instant at) {
		jdbc.update("insert into " + table("projection_heads") + " as current_head (projection_type,pipeline_id,pipeline_version,pot_id,latest_projected_version,advanced_at) values (?,?,?,?,?,?) on conflict (projection_type,pipeline_id,pipeline_version,pot_id) do update set latest_projected_version=excluded.latest_projected_version,advanced_at=excluded.advanced_at where excluded.latest_projected_version > current_head.latest_projected_version",
				g.projectionType().value(),g.pipeline().pipelineId().value(),g.pipeline().pipelineVersion(),g.potId().value(),version,Timestamp.from(at));
		return findHead(g).orElseThrow();
	}

	@Override public Optional<ProjectionHead> findHead(ProjectionGenerationIdentity g) {
		return one(jdbc.query("select latest_projected_version,advanced_at from " + table("projection_heads") + " where " + generationWhere(),
				(rs,n)->new ProjectionHead(g,rs.getLong(1),rs.getTimestamp(2).toInstant()),generationArgs(g)));
	}

	@Override public void recordViolation(ProjectionInvariantViolation v) {
		var i=v.identity(); var g=i.generation();
		jdbc.update("insert into " + table("projection_invariant_violations") + " (violation_id,projection_type,pipeline_id,pipeline_version,pot_id,pot_version,violation_type,existing_artifact_id,existing_digest,proposed_digest,detected_at) values (?,?,?,?,?,?,'DIVERGENT_DUPLICATE',?,?,?,?)",
				v.violationId(),g.projectionType().value(),g.pipeline().pipelineId().value(),g.pipeline().pipelineVersion(),g.potId().value(),i.potVersion(),v.existingArtifactId().value(),v.existingDigest().value(),v.proposedDigest().value(),Timestamp.from(v.detectedAt()));
	}

	private String table(String name){return schema+"."+name;}
	private static String generationWhere(){return "projection_type=? and pipeline_id=? and pipeline_version=? and pot_id=?";}
	private static String identityWhere(){return generationWhere()+" and pot_version=?";}
	private static Object[] generationArgs(ProjectionGenerationIdentity g){return new Object[]{g.projectionType().value(),g.pipeline().pipelineId().value(),g.pipeline().pipelineVersion(),g.potId().value()};}
	private static Object[] identityArgs(ProjectionIdentity i){return append(generationArgs(i.generation()),i.potVersion());}
	private static Object[] prepend(Object value,Object[] values){var out=new Object[values.length+1];out[0]=value;System.arraycopy(values,0,out,1,values.length);return out;}
	private static Object[] append(Object[] values,Object value){var out=new Object[values.length+1];System.arraycopy(values,0,out,0,values.length);out[values.length]=value;return out;}
	private static <T> Optional<T> one(java.util.List<T> values){if(values.size()>1)throw new IllegalStateException("Expected at most one row");return values.stream().findFirst();}
	private static String lockKey(ProjectionIdentity i){var g=i.generation();return g.projectionType().value()+"\u001f"+g.pipeline().pipelineId().value()+"\u001f"+g.pipeline().pipelineVersion()+"\u001f"+g.potId().value()+"\u001f"+i.potVersion();}
}
