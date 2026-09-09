package com.kartaguez.pocoma.engine.read.projection;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.pipeline.*;
import com.kartaguez.pocoma.domain.pot.aggregate.*;
import com.kartaguez.pocoma.domain.pot.association.ExpenseShare;
import com.kartaguez.pocoma.domain.pot.entity.Shareholder;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.*;

class ReconstructPotProjectionServiceTest {
	@Test void reconstructsDeletedSnapshotWithCanonicalExactContent(){
		var pot=PotId.of(uuid(1));var creator=UserId.of(uuid(2));var s1=ShareholderId.of(uuid(3));var s2=ShareholderId.of(uuid(4));var expense=ExpenseId.of(uuid(5));
		var source=new HistoricalPotSnapshotSource.HistoricalPotSnapshot(
				PotHeader.reconstitute(pot,Label.of("Trip"),creator,true),
				List.of(Shareholder.reconstitute(s2,pot,Name.of("B"),Weight.of(Fraction.of(2,4)),null,true),Shareholder.reconstitute(s1,pot,Name.of("A"),Weight.of(Fraction.of(1,2)),creator,false)),
				List.of(new HistoricalPotSnapshotSource.HistoricalExpense(ExpenseHeader.reconstitute(expense,pot,s1,Amount.of(Fraction.of(10,2)),Label.of("Train"),true),List.of(new ExpenseShare(expense,s2,Weight.of(Fraction.of(1,2))),new ExpenseShare(expense,s1,Weight.of(Fraction.of(1,2)))))));
		var identity=new ProjectionIdentity(new ProjectionGenerationIdentity(new ProjectionType("READ_POT"),new PipelineDefinition(PipelineId.of("read-pot"),1),pot),5);
		var projection=new ReconstructPotProjectionService((id,v)->source).reconstruct(identity);
		assertEquals(PotProjectionStatus.DELETED,projection.status());assertEquals(List.of(s1,s2),projection.shareholders().stream().map(PotProjectionShareholder::shareholderId).toList());
		assertEquals(Fraction.of(1,2),projection.shareholders().get(1).weight());assertTrue(projection.shareholders().get(1).deleted());
		assertEquals(List.of(s1,s2),projection.expenses().getFirst().shares().stream().map(PotProjectionExpenseShare::shareholderId).toList());
		assertThrows(UnsupportedOperationException.class,()->projection.shareholders().add(projection.shareholders().getFirst()));
	}
	@Test void rejectsImpossibleReferences(){
		var pot=PotId.of(uuid(1));var unknown=ShareholderId.of(uuid(9));var expense=ExpenseId.of(uuid(5));
		var source=new HistoricalPotSnapshotSource.HistoricalPotSnapshot(PotHeader.reconstitute(pot,Label.of("Trip"),UserId.of(uuid(2)),false),List.of(),List.of(new HistoricalPotSnapshotSource.HistoricalExpense(ExpenseHeader.reconstitute(expense,pot,unknown,Amount.ZERO,Label.of("X"),false),List.of())));
		var identity=new ProjectionIdentity(new ProjectionGenerationIdentity(new ProjectionType("READ_POT"),new PipelineDefinition(PipelineId.of("read-pot"),1),pot),1);
		assertEquals("INCOHERENT_POT_HISTORY",assertThrows(HistoricalPotReconstructionException.class,()->new ReconstructPotProjectionService((id,v)->source).reconstruct(identity)).failureCode());
	}
	@Test void reconstructsEveryVersionIndependentlyAcrossTheRepresentativeTimeline(){
		var pot=PotId.of(uuid(20));var creator=UserId.of(uuid(21));var user=UserId.of(uuid(22));
		var shareholder=ShareholderId.of(uuid(23));var expense=ExpenseId.of(uuid(24));
		var header=PotHeader.reconstitute(pot,Label.of("Trip"),creator,false);
		var active=Shareholder.reconstitute(shareholder,pot,Name.of("Alice"),Weight.of(Fraction.ONE),user,false);
		var removed=Shareholder.reconstitute(shareholder,pot,Name.of("Alice"),Weight.of(Fraction.ONE),null,true);
		var expenseV3=new HistoricalPotSnapshotSource.HistoricalExpense(
				ExpenseHeader.reconstitute(expense,pot,shareholder,Amount.of(Fraction.of(10,1)),Label.of("Train"),false),
				List.of(new ExpenseShare(expense,shareholder,Weight.of(Fraction.ONE))));
		var expenseV4=new HistoricalPotSnapshotSource.HistoricalExpense(
				ExpenseHeader.reconstitute(expense,pot,shareholder,Amount.of(Fraction.of(25,2)),Label.of("Train changed"),false),
				List.of(new ExpenseShare(expense,shareholder,Weight.of(Fraction.of(2,2)))));
		var history=Map.of(
				1L,new HistoricalPotSnapshotSource.HistoricalPotSnapshot(header,List.of(),List.of()),
				2L,new HistoricalPotSnapshotSource.HistoricalPotSnapshot(header,List.of(active),List.of()),
				3L,new HistoricalPotSnapshotSource.HistoricalPotSnapshot(header,List.of(active),List.of(expenseV3)),
				4L,new HistoricalPotSnapshotSource.HistoricalPotSnapshot(header,List.of(active),List.of(expenseV4)),
				5L,new HistoricalPotSnapshotSource.HistoricalPotSnapshot(header,List.of(removed),List.of(expenseV4)));
		var generation=new ProjectionGenerationIdentity(new ProjectionType("READ_POT"),
				new PipelineDefinition(PipelineId.of("read-pot"),1),pot);
		var service=new ReconstructPotProjectionService((id,version)->history.get(version));

		var v5=service.reconstruct(new ProjectionIdentity(generation,5));
		var v3=service.reconstruct(new ProjectionIdentity(generation,3));
		var v1=service.reconstruct(new ProjectionIdentity(generation,1));
		var v4=service.reconstruct(new ProjectionIdentity(generation,4));
		var v2=service.reconstruct(new ProjectionIdentity(generation,2));

		assertTrue(v1.shareholders().isEmpty());
		assertEquals(Optional.of(user),v2.shareholders().getFirst().userId());
		assertEquals(Fraction.of(10,1),v3.expenses().getFirst().amount());
		assertEquals(Fraction.of(25,2),v4.expenses().getFirst().amount());
		assertTrue(v5.shareholders().getFirst().deleted());
		assertEquals(Optional.empty(),v5.shareholders().getFirst().userId());
		assertNotEquals(v2.shareholders(),v5.shareholders());
	}
	private static UUID uuid(int n){return UUID.fromString("00000000-0000-0000-0000-"+String.format("%012d",n));}
}
