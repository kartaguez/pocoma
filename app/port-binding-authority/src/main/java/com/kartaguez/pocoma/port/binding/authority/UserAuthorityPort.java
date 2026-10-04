package com.kartaguez.pocoma.port.binding.authority;

import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.User;

import java.util.Optional;

/**
 * Persistence authority for the minimal autonomous Pocoma User.
 * Every operation participates in and requires the caller's existing transaction.
 */
public interface UserAuthorityPort {

	void create(User user);

	Optional<User> findById(PocomaUserId userId);
}
