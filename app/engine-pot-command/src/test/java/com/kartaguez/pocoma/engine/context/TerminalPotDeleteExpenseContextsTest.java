package com.kartaguez.pocoma.engine.context;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.pot.version.PotGlobalVersion;
class TerminalPotDeleteExpenseContextsTest {
	private final PotId pot=PotId.of(UUID.randomUUID());private final UserId creator=UserId.of(UUID.randomUUID());private final ShareholderId shareholder=ShareholderId.of(UUID.randomUUID());
	@Test void allExpenseMutationsRejectWhenPotDeleted(){
		assertPotDeleted(()->new DeleteExpenseContext(new PotGlobalVersion(pot,7),false,true,creator).assertDeletePreconditions(7));
		assertPotDeleted(()->new UpdateExpenseDetailsContext(new PotGlobalVersion(pot,7),false,true,creator,Set.of(shareholder)).assertUpdatePreconditions(7,shareholder));
		assertPotDeleted(()->new UpdateExpenseSharesContext(new PotGlobalVersion(pot,7),false,true,creator,Set.of(shareholder)).assertUpdatePreconditions(7,Set.of(shareholder)));
	}
	private static void assertPotDeleted(Runnable action){assertEquals("POT_ALREADY_DELETED",assertThrows(BusinessRuleViolationException.class,action::run).ruleCode());}
}
