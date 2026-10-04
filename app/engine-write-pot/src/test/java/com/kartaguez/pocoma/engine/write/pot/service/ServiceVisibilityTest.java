package com.kartaguez.pocoma.engine.write.pot.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

class ServiceVisibilityTest {

	@Test
	void rawUseCaseServicesAreNotPublic() throws ClassNotFoundException {
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.AddPotShareholdersService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.CreateExpenseService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.CreatePotService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.DeleteExpenseService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.DeletePotService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.UpdateExpenseDetailsService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.UpdateExpenseSharesService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.UpdatePotDetailsService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.UpdatePotShareholdersDetailsService");
		assertPackagePrivate("com.kartaguez.pocoma.engine.write.pot.service.UpdatePotShareholdersWeightsService");
	}

	private static void assertPackagePrivate(String className) throws ClassNotFoundException {
		Class<?> type = Class.forName(className);

		assertFalse(Modifier.isPublic(type.getModifiers()), className + " must not be public");
	}
}
