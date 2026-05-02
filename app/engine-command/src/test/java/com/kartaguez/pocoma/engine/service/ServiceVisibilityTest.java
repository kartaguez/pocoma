package com.kartaguez.pocoma.engine.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

class ServiceVisibilityTest {

	@Test
	void rawUseCaseServicesAreNotPublic() throws ClassNotFoundException {
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.AddPotShareholdersService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.CreateExpenseService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.CreatePotService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.DeleteExpenseService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.DeletePotService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.UpdateExpenseDetailsService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.UpdateExpenseSharesService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.UpdatePotDetailsService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.UpdatePotShareholdersDetailsService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.service.command.UpdatePotShareholdersWeightsService");
	}

	private static void assertPackagePrivate(String className) throws ClassNotFoundException {
		Class<?> type = Class.forName(className);

		assertFalse(Modifier.isPublic(type.getModifiers()), className + " must not be public");
	}
}
