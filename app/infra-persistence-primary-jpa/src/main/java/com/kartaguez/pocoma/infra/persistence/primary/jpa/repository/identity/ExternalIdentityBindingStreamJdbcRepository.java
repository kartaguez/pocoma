package com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExternalIdentityBindingStreamJdbcRepository {
	private static final String CREATE_IF_ABSENT = """
			insert into external_identity_binding_streams (issuer, subject, current_revision)
			values (?, ?, 0)
			on conflict (issuer, subject) do nothing
			""";
	private static final String FIND = """
			select issuer, subject, current_revision
			from external_identity_binding_streams
			where issuer = ? and subject = ?
			""";
	private static final String LOCK = FIND + " for update";
	private static final String ADVANCE = """
			update external_identity_binding_streams
			set current_revision = ?
			where issuer = ? and subject = ? and current_revision = ?
			""";
	private static final String OBSERVE_EXACT = """
			select a.user_id, s.current_revision
			from external_identity_binding_streams s
			join external_identities a on a.issuer = s.issuer and a.subject = s.subject
			where s.issuer = ? and s.subject = ? and a.binding_id = ?
			""";
	private static final String FENCE_EXACT = """
			update external_identity_binding_streams as s
			set current_revision = s.current_revision
			where s.issuer = ? and s.subject = ? and s.current_revision = ?
			  and exists (
			    select 1 from external_identities as a
			    where a.issuer = s.issuer and a.subject = s.subject
			      and a.user_id = ? and a.binding_id = ?
			  )
			""";

	private final JdbcTemplate jdbc;

	public ExternalIdentityBindingStreamJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void createIfAbsent(String issuer, String subject) {
		jdbc.update(CREATE_IF_ABSENT, issuer, subject);
	}

	public Optional<ExternalIdentityBindingStreamRow> find(String issuer, String subject) {
		return query(FIND, issuer, subject);
	}

	public ExternalIdentityBindingStreamRow lock(String issuer, String subject) {
		return query(LOCK, issuer, subject).orElseThrow(() ->
				new IllegalStateException("Binding stream disappeared for " + issuer + "/" + subject));
	}

	public void advance(String issuer, String subject, long expectedRevision, long nextRevision) {
		if (jdbc.update(ADVANCE, nextRevision, issuer, subject, expectedRevision) != 1) {
			throw new IllegalStateException("Binding stream revision changed while locked");
		}
	}

	public Optional<ObservedBindingRow> observeExact(String issuer, String subject, UUID bindingId) {
		return jdbc.query(OBSERVE_EXACT, (rs, row) -> new ObservedBindingRow(
				rs.getObject("user_id", UUID.class), rs.getLong("current_revision")),
				issuer, subject, bindingId).stream().findFirst();
	}

	public boolean fenceExact(String issuer, String subject, UUID userId, UUID bindingId, long revision) {
		return jdbc.update(FENCE_EXACT, issuer, subject, revision, userId, bindingId) == 1;
	}

	public record ObservedBindingRow(UUID userId, long revision) {}

	private Optional<ExternalIdentityBindingStreamRow> query(String sql, String issuer, String subject) {
		return jdbc.query(sql, (result, rowNumber) -> new ExternalIdentityBindingStreamRow(
				result.getString("issuer"), result.getString("subject"), result.getLong("current_revision")),
				issuer, subject).stream().findFirst();
	}
}
