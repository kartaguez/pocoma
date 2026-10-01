package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.User;
import com.kartaguez.pocoma.domain.useridentity.UserAuthorityPort;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.UserJdbcRepository;

@Component
public class JpaUserAuthorityAdapter implements UserAuthorityPort {
	private final UserJdbcRepository repository;

	public JpaUserAuthorityAdapter(UserJdbcRepository repository) {
		this.repository = requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void create(User user) {
		requireNonNull(user, "user must not be null");
		repository.insert(user.id().value());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<User> findById(PocomaUserId userId) {
		requireNonNull(userId, "userId must not be null");
		return repository.findById(userId.value()).map(id -> new User(new PocomaUserId(id)));
	}
}
