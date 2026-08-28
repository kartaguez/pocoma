package test.java.com.kartaguez.pocoma.domain.pot.policy.scope;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.policy.AddPotShareholdersAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope.Action;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope.Resource;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope.SubResource;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class ScopeTest {

	@Test
	void createValidScope() {
		Resource resource = Scope.Resource.POT;
        SubResource subResource = Scope.SubResource.DETAILS;
        Action action = Scope.Action.UPDATE;
        assertDoesNotThrow(() -> new Scope(resource, subResource, action));
        Scope scope = new Scope(resource, subResource, action);
        assertEquals("pot.details:update", scope.toString());
	}

	@Test
	void createScopeWithEmptySubResource() {
		Resource resource = Scope.Resource.POT;
        SubResource subResource = null;
        Action action = Scope.Action.CREATE;
        assertDoesNotThrow(() -> new Scope(resource, subResource, action));
        Scope scope = new Scope(resource, subResource, action);
        assertEquals("pot:create", scope.toString());
    }

	@Test
	void createEmptyScope() {
		Resource resource = null;
        SubResource subResource = null;
        Action action = null;
        assertDoesNotThrow(() -> new Scope(resource, subResource, action));
        Scope scope = new Scope(resource, subResource, action);
        assertEquals(":", scope.toString());
    }

}
