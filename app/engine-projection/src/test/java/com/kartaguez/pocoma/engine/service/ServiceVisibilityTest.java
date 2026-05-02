package com.kartaguez.pocoma.engine.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

class ServiceVisibilityTest {

	@Test
	void rawUseCaseServicesAreNotPublic() throws ClassNotFoundException {
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.projection.ComputePotBalancesService");
	}

	private static void assertPackagePrivate(String className) throws ClassNotFoundException {
		Class<?> type = Class.forName(className);

		assertFalse(Modifier.isPublic(type.getModifiers()), className + " must not be public");
	}
}
