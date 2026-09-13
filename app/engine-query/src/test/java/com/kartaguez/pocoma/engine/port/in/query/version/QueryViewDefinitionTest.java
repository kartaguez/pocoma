package com.kartaguez.pocoma.engine.port.in.query.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.ProjectionType;

class QueryViewDefinitionTest {

	private static final ProjectionType AUTH = new ProjectionType("AUTH");
	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");
	private static final ProjectionType BALANCE = new ProjectionType("BALANCE");

	@Test
	void protectedViewRequiresAuthorizationAndBusinessComponents() {
		QueryViewDefinition definition = QueryViewDefinition.protectedView(AUTH, Set.of(READ_POT));

		assertEquals(Optional.of(AUTH), definition.authorizationComponent());
		assertEquals(Set.of(READ_POT), definition.businessComponents());
		assertEquals(Set.of(AUTH, READ_POT), definition.requiredComponents());
	}

	@Test
	void protectedViewMayRequireAuthorizationOnly() {
		QueryViewDefinition definition = QueryViewDefinition.protectedView(AUTH, Set.of());

		assertEquals(Set.of(AUTH), definition.requiredComponents());
	}

	@Test
	void unprotectedViewRequiresOnlyItsBusinessComponents() {
		QueryViewDefinition definition = QueryViewDefinition.unprotectedView(Set.of(READ_POT));

		assertEquals(Optional.empty(), definition.authorizationComponent());
		assertEquals(Set.of(READ_POT), definition.requiredComponents());
	}

	@Test
	void unprotectedViewMayRequireNoProjectedComponent() {
		QueryViewDefinition definition = QueryViewDefinition.unprotectedView(Set.of());

		assertEquals(Set.of(), definition.requiredComponents());
	}

	@Test
	void rejectsAuthorizationDuplicatedAsBusinessComponent() {
		assertThrows(IllegalArgumentException.class,
				() -> QueryViewDefinition.protectedView(AUTH, Set.of(AUTH, READ_POT)));
	}

	@Test
	void rejectsNullContainersAndComponents() {
		assertThrows(NullPointerException.class, () -> new QueryViewDefinition(null, Set.of()));
		assertThrows(NullPointerException.class, () -> QueryViewDefinition.unprotectedView(null));
		assertThrows(NullPointerException.class, () -> QueryViewDefinition.protectedView(null, Set.of()));

		Set<ProjectionType> withNull = new HashSet<>();
		withNull.add(null);
		assertThrows(NullPointerException.class, () -> QueryViewDefinition.unprotectedView(withNull));
	}

	@Test
	void defensivelyCopiesAndExposesImmutableComponents() {
		Set<ProjectionType> source = new HashSet<>();
		source.add(READ_POT);
		QueryViewDefinition definition = QueryViewDefinition.protectedView(AUTH, source);

		source.add(BALANCE);

		assertEquals(Set.of(READ_POT), definition.businessComponents());
		assertThrows(UnsupportedOperationException.class, () -> definition.businessComponents().add(BALANCE));
		assertThrows(UnsupportedOperationException.class, () -> definition.requiredComponents().add(BALANCE));
	}

	@Test
	void componentOrderHasNoFunctionalMeaning() {
		QueryViewDefinition first = QueryViewDefinition.unprotectedView(
				new LinkedHashSet<>(java.util.List.of(READ_POT, BALANCE)));
		QueryViewDefinition second = QueryViewDefinition.unprotectedView(
				new LinkedHashSet<>(java.util.List.of(BALANCE, READ_POT)));

		assertEquals(first, second);
		assertEquals(first.requiredComponents(), second.requiredComponents());
	}
}
