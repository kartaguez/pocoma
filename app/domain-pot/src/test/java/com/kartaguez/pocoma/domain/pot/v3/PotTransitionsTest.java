package com.kartaguez.pocoma.domain.pot.v3;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.domain.pot.v3.TransitionResult.Rejection.*;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;

class PotTransitionsTest {
    final PotId p = new PotId(new UUID(0,1));
    final PotId other = new PotId(new UUID(0,2));
    final ShareholderId a = new ShareholderId(new UUID(0,3)), b = new ShareholderId(new UUID(0,4)), c = new ShareholderId(new UUID(0,5));
    final ExpenseId e = new ExpenseId(new UUID(0,6));
    final UserId creator = new UserId(new UUID(0,7)), actor = new UserId(new UUID(0,8)), third = new UserId(new UUID(0,9));
    final Label label = new Label("A");
    final LocalDate date = LocalDate.of(2026,10,25);
    final Weight one = new Weight(Fraction.ONE), two = new Weight(new Fraction(2,1));
    PotFacts pot(long v) { return pot(v,creator,false,false); }
    PotFacts pot(long v,UserId user,boolean attached,boolean deleted) {
        return new PotFacts(p,v,new Versioned<>(1,1,new PotHeader(p,deleted)),new AuthorizationFacts(p,v,creator,user,attached));
    }
    ShareholderReference ref(ShareholderId id,long v,long created,boolean deleted) {
        return new ShareholderReference(p,v,id,created,Optional.of(new Versioned<>(deleted?2:1,deleted?v:created,new ShareholderHeader(p,id,deleted))));
    }
    ShareholderReference absent(ShareholderId id,long v) { return new ShareholderReference(p,v,id,0,Optional.empty()); }
    Map<ShareholderId,ShareholderReference> refs(long v) { return Map.of(a,ref(a,v,2,false),b,ref(b,v,4,false),c,ref(c,v,8,false)); }
    ExpenseFinancials financials(long amount) { return new ExpenseFinancials(p,e,new Amount(new Fraction(amount,1)),a,Map.of(b,Optional.empty(),c,Optional.of(two))); }
    Versioned<ExpenseHeader> eh() { return new Versioned<>(1,10,new ExpenseHeader(p,e,false)); }
    Versioned<ExpenseDetails> ed(long from) { return new Versioned<>(2,from,new ExpenseDetails(p,e,label,date)); }
    Versioned<ExpenseFinancials> ef(long from) { return new Versioned<>(3,from,financials(100)); }
    <T> TransitionResult.Changed<T> changed(TransitionResult<T> result,long expected) {
        var change=assertInstanceOf(TransitionResult.Changed.class,result);
        assertEquals(expected,change.expectedPotVersion()); assertEquals(expected+1,change.candidatePotVersion());
        @SuppressWarnings("unchecked") var typed=(TransitionResult.Changed<T>)change; return typed;
    }
    void rejected(TransitionResult<?> result,TransitionResult.Rejection reason) { assertEquals(reason,assertInstanceOf(TransitionResult.Rejected.class,result).reason()); }

    @Test void l1a01ImmutableEnvelopesAndCollections() {
        assertThrows(IllegalArgumentException.class,()->new Versioned<>(0,1,label));
        assertThrows(IllegalArgumentException.class,()->new Versioned<>(1,0,label));
        assertThrows(NullPointerException.class,()->new Versioned<>(1,1,null));
        var input=new HashMap<ShareholderId,Optional<Weight>>(); input.put(b,Optional.empty());
        var f=new ExpenseFinancials(p,e,new Amount(Fraction.ZERO),a,input); input.clear();
        assertEquals(Set.of(b),f.participants().keySet());
        assertThrows(UnsupportedOperationException.class,()->f.participants().clear());
        assertTrue(Arrays.stream(TransitionResult.Changed.class.getConstructors()).noneMatch(x->java.lang.reflect.Modifier.isPublic(x.getModifiers())));
        assertTrue(Arrays.stream(Versioned.class.getMethods()).noneMatch(x->x.getName().equals("revise")));
    }
    @Test void l1a02CreationVersionsAndStableIdentities() {
        var ps=changed(PotTransitions.create(p,label,creator,false),0).changes();
        assertEquals(new Versioned<>(1,1,new PotHeader(p,false)),ps.header());
        assertEquals(new Versioned<>(1,1,new PotDetails(p,label,creator)),ps.details());
        rejected(PotTransitions.create(p,label,creator,true),ALREADY_EXISTS);
        var intentions=List.of(new ShareholderTransitions.Creation(a,new Name("A"),Optional.of(creator),one),new ShareholderTransitions.Creation(b,new Name("B"),Optional.empty(),two));
        var states=changed(ShareholderTransitions.create(pot(1),1,intentions,Map.of(a,absent(a,1),b,absent(b,1))),1).changes();
        assertEquals(2,states.size());
        for(var state:states) {
            assertEquals(1,state.header().localVersion()); assertEquals(2,state.header().activeFrom());
            assertEquals(1,state.details().localVersion()); assertEquals(2,state.details().activeFrom());
            assertEquals(1,state.weight().localVersion()); assertEquals(2,state.weight().activeFrom());
        }
        assertThrows(UnsupportedOperationException.class,()->states.clear());
        var expense=changed(ExpenseTransitions.create(pot(12),10,new ExpenseDetails(p,e,label,date),financials(100),false,refs(12)),12).changes();
        assertEquals(new Versioned<>(1,13,new ExpenseHeader(p,e,false)),expense.header());
        assertEquals(new Versioned<>(1,13,new ExpenseDetails(p,e,label,date)),expense.details());
        assertEquals(new Versioned<>(1,13,financials(100)),expense.financials());
    }
    @Test void l1a03ChangedContainsExactlyEffectiveRevisions() {
        var old=new Versioned<>(2,5,new PotDetails(p,label,creator));
        var proposal=changed(PotTransitions.updateDetails(pot(8),6,old,new Label("B")),8);
        assertEquals(new Versioned<>(3,9,new PotDetails(p,new Label("B"),creator)),proposal.changes());
        assertEquals(new Versioned<>(2,5,new PotDetails(p,label,creator)),old);
        var noop=assertInstanceOf(TransitionResult.Unchanged.class,PotTransitions.updateDetails(pot(8),6,old,label));
        assertEquals(8,noop.currentPotVersion());
    }
    @Test void l1a04ObsolescencePrecedesEqualityIncludingReturnToOldValue() {
        for(long from:new long[]{7,8}) {
            var old=new Versioned<>(3,from,new PotDetails(p,label,creator));
            rejected(PotTransitions.updateDetails(pot(8),6,old,label),OBSOLETE);
            assertEquals(3,old.localVersion()); assertEquals(label,old.value().label());
        }
    }
    @Test void l1a05AtomicWeightBatchesAndPermutations() {
        var wa=new Versioned<>(2,7,new ShareholderWeight(p,a,one));
        var wb=new Versioned<>(3,9,new ShareholderWeight(p,b,two));
        var requests=List.of(new ShareholderTransitions.WeightIntent(a,new Weight(new Fraction(3,1))),new ShareholderTransitions.WeightIntent(b,two));
        var expected=Map.of(a,new Versioned<>(3,13,new ShareholderWeight(p,a,new Weight(new Fraction(3,1)))));
        for(var order:List.of(requests,List.of(requests.get(1),requests.get(0)))) {
            var result=changed(ShareholderTransitions.updateWeights(pot(12),10,order,refs(12),Map.of(a,wa,b,wb)),12).changes();
            assertEquals(expected,result); assertSame(wb,Map.of(b,wb).get(b));
            assertThrows(UnsupportedOperationException.class,()->result.clear());
            rejected(ShareholderTransitions.updateWeights(pot(12),10,order,refs(12),Map.of(a,wa,b,new Versioned<>(3,11,wb.value()))),OBSOLETE);
            var missing=new HashMap<>(refs(12)); missing.put(b,absent(b,12));
            rejected(ShareholderTransitions.updateWeights(pot(12),10,order,missing,Map.of(a,wa)),NOT_FOUND);
            var deleted=new HashMap<>(refs(12)); deleted.put(b,ref(b,12,4,true));
            rejected(ShareholderTransitions.updateWeights(pot(12),10,order,deleted,Map.of(a,wa,b,wb)),DELETED);
            assertEquals(2,wa.localVersion()); assertEquals(one,wa.value().defaultWeight());
        }
        var both=changed(ShareholderTransitions.updateWeights(pot(12),10,List.of(requests.get(0),new ShareholderTransitions.WeightIntent(b,one)),refs(12),Map.of(a,wa,b,wb)),12).changes();
        assertEquals(Set.of(a,b),both.keySet()); assertEquals(4,both.get(b).localVersion());
        assertTrue(both.values().stream().allMatch(v->v.activeFrom()==13));
        assertInstanceOf(TransitionResult.Unchanged.class,ShareholderTransitions.updateWeights(pot(12),10,List.of(new ShareholderTransitions.WeightIntent(a,one),new ShareholderTransitions.WeightIntent(b,two)),refs(12),Map.of(a,wa,b,wb)));
        assertThrows(IllegalArgumentException.class,()->ShareholderTransitions.updateWeights(pot(12),10,List.of(requests.get(0),requests.get(0)),refs(12),Map.of(a,wa)));
    }
    @Test void l1a06PotDetailsRightsLifecycleAndObservation() {
        var old=new Versioned<>(2,5,new PotDetails(p,label,creator));
        rejected(PotTransitions.updateDetails(pot(8,actor,false,false),6,old,label),FORBIDDEN);
        rejected(PotTransitions.updateDetails(pot(8,creator,false,true),6,old,label),DELETED);
        rejected(PotTransitions.updateDetails(pot(8),9,old,label),OBSOLETE);
        rejected(PotTransitions.updateDetails(pot(8),0,old,label),OBSOLETE);
        assertEquals(creator,changed(PotTransitions.updateDetails(pot(8,actor,true,false),6,old,new Label("B")),8).changes().value().creatorId());
        assertThrows(InconsistentPotFactsException.class,()->PotTransitions.updateDetails(pot(8),6,new Versioned<>(2,5,new PotDetails(other,label,creator)),label));
        assertThrows(InconsistentPotFactsException.class,()->PotTransitions.updateDetails(pot(8),6,new Versioned<>(2,5,new PotDetails(p,label,actor)),label));
    }
    @Test void l1a07DetailsAndWeightsAreIndependent() {
        var d=new Versioned<>(4,11,new ShareholderDetails(p,a,new Name("A"),Optional.empty()));
        var w=new Versioned<>(2,7,new ShareholderWeight(p,a,one));
        var result=changed(ShareholderTransitions.updateWeights(pot(12),10,List.of(new ShareholderTransitions.WeightIntent(a,two)),refs(12),Map.of(a,w)),12).changes();
        assertEquals(Map.of(a,new Versioned<>(3,13,new ShareholderWeight(p,a,two))),result);
        assertEquals(11,d.activeFrom());
        var details=changed(ShareholderTransitions.updateDetails(pot(12),12,List.of(new ShareholderTransitions.DetailsIntent(a,new Name("B"),Optional.empty())),refs(12),Map.of(a,d)),12).changes();
        assertEquals(Map.of(a,new Versioned<>(5,13,new ShareholderDetails(p,a,new Name("B"),Optional.empty()))),details);
        assertEquals(2,w.localVersion());
        rejected(ShareholderTransitions.updateDetails(pot(12),10,List.of(new ShareholderTransitions.DetailsIntent(a,d.value().name(),Optional.empty())),refs(12),Map.of(a,d)),OBSOLETE);
    }
    @Test void l1a08FinancialsReferenceAndEqualityVariants() {
        var old=ef(14); var requested=financials(101);
        assertEquals(new Versioned<>(4,21,requested),changed(ExpenseTransitions.updateFinancials(pot(20),16,eh(),old,requested,refs(20)),20).changes());
        assertEquals(financials(100),old.value());
        assertInstanceOf(TransitionResult.Unchanged.class,ExpenseTransitions.updateFinancials(pot(20),16,eh(),old,financials(100),refs(20)));
        rejected(ExpenseTransitions.updateFinancials(pot(20),16,eh(),ef(18),financials(100),refs(20)),OBSOLETE);
        for(var bad:List.of(ref(c,20,17,false),ref(c,20,8,true),absent(c,20))) {
            var facts=new HashMap<>(refs(20)); facts.put(c,bad);
            assertInstanceOf(TransitionResult.Rejected.class,ExpenseTransitions.updateFinancials(pot(20),16,eh(),old,requested,facts));
        }
        rejected(ExpenseTransitions.updateFinancials(pot(20),16,new Versioned<>(2,19,new ExpenseHeader(p,e,true)),old,requested,refs(20)),DELETED);
        rejected(ExpenseTransitions.updateFinancials(pot(20,actor,false,false),16,eh(),old,requested,refs(20)),FORBIDDEN);
        assertThrows(InconsistentPotFactsException.class,()->ExpenseTransitions.updateFinancials(pot(20),16,eh(),old,requested,Map.of(a,ref(a,20,2,false))));
        var entries=List.of(new ExpenseFinancials.Participation(c,Optional.of(new Weight(new Fraction(4,2)))),new ExpenseFinancials.Participation(b,Optional.empty()));
        var reordered=ExpenseFinancials.of(p,e,new Amount(new Fraction(200,2)),a,entries);
        assertEquals(financials(100),reordered);
        assertInstanceOf(TransitionResult.Unchanged.class,ExpenseTransitions.updateFinancials(pot(20),16,eh(),old,reordered,refs(20)));
    }
    @Test void l1a09DeletionDependenciesAndHeaderOnlyChanges() {
        var state=new ExpenseState(eh(),ed(12),ef(14));
        var result=changed(ExpenseTransitions.delete(pot(20),16,state),20).changes();
        assertEquals(new Versioned<>(2,21,new ExpenseHeader(p,e,true)),result);
        assertEquals(2,state.details().localVersion()); assertEquals(3,state.financials().localVersion());
        rejected(ExpenseTransitions.delete(pot(20),11,state),OBSOLETE);
        rejected(ExpenseTransitions.delete(pot(20),13,state),OBSOLETE);
        var detailChange=changed(ExpenseTransitions.updateDetails(pot(20),16,eh(),ed(12),new Label("B"),date.plusDays(1)),20).changes();
        assertEquals(new Versioned<>(3,21,new ExpenseDetails(p,e,new Label("B"),date.plusDays(1))),detailChange);
        assertEquals(new Versioned<>(2,21,new PotHeader(p,true)),changed(PotTransitions.delete(pot(20),20),20).changes());
        rejected(PotTransitions.delete(pot(20),19),OBSOLETE);
        rejected(PotTransitions.delete(pot(20,actor,true,false),20),FORBIDDEN);
        rejected(PotTransitions.delete(pot(21,creator,false,true),21),DELETED);
    }
    @Test void l1a10ShareholderDeletionRequiresCreatorAndExhaustiveAbsence() {
        var header=ref(a,3,2,false);
        var usage=new ActiveShareholderUsage(p,3,a,false,true);
        var deleted=changed(ShareholderTransitions.delete(pot(3),3,header,usage),3).changes();
        assertEquals(new Versioned<>(2,4,new ShareholderHeader(p,a,true)),deleted);
        assertEquals(new Versioned<>(1,2,new ShareholderHeader(p,a,false)),header.header().orElseThrow());
        rejected(ShareholderTransitions.delete(pot(3,actor,true,false),3,header,usage),FORBIDDEN);
        rejected(ShareholderTransitions.delete(pot(3),3,header,new ActiveShareholderUsage(p,3,a,true,true)),REFERENCED);
        assertThrows(InconsistentPotFactsException.class,()->new ActiveShareholderUsage(p,3,a,false,false));
        var deletedRef=new ShareholderReference(p,4,a,2,Optional.of(deleted));
        rejected(ShareholderTransitions.updateWeights(pot(4),3,List.of(new ShareholderTransitions.WeightIntent(a,two)),Map.of(a,deletedRef),Map.of(a,new Versioned<>(2,3,new ShareholderWeight(p,a,one)))),DELETED);
        rejected(ShareholderTransitions.delete(pot(4),3,deletedRef,new ActiveShareholderUsage(p,4,a,false,true)),DELETED);
    }
    @Test void l1a11ExactRationalsAndIntrinsicFinancialInvariants() {
        assertEquals(new Fraction(-1,2),new Fraction(2,-4)); assertEquals(Fraction.ZERO,new Fraction(0,-9));
        assertEquals(BigInteger.ONE,new Fraction(0,99).denominator());
        assertEquals(new Fraction(1,8),Fraction.decimal("0.125")); assertEquals(new Fraction(1000,1),Fraction.decimal("1E+3"));
        assertEquals(new Fraction(5,6),new Fraction(1,2).add(new Fraction(1,3)));
        assertEquals(new Fraction(7,12),new Fraction(3,4).subtract(new Fraction(1,6)));
        assertEquals(new Fraction(3,5),new Fraction(2,3).multiply(new Fraction(9,10)));
        assertEquals(new Fraction(5,6),new Fraction(2,3).divide(new Fraction(4,5)));
        var huge=BigInteger.ONE.shiftLeft(150).add(BigInteger.ONE);
        var sum=new Fraction(huge,BigInteger.TWO).add(new Fraction(1,3));
        assertEquals(huge.multiply(BigInteger.valueOf(3)).add(BigInteger.TWO),sum.numerator());
        assertEquals(BigInteger.valueOf(6),sum.denominator());
        assertTrue(new Fraction(1,3).compareTo(new Fraction(1,2))<0);
        assertThrows(IllegalArgumentException.class,()->new Fraction(1,0));
        assertThrows(ArithmeticException.class,()->Fraction.ONE.divide(Fraction.ZERO));
        for(var f:List.of(Fraction.ZERO,new Fraction(-1,3))) assertThrows(IllegalArgumentException.class,()->new Weight(f));
        assertThrows(IllegalArgumentException.class,()->new Amount(new Fraction(-1,1)));
        assertEquals(Fraction.ZERO,new Amount(Fraction.ZERO).value());
        assertThrows(IllegalArgumentException.class,()->new ExpenseFinancials(p,e,new Amount(Fraction.ZERO),a,Map.of()));
        var participant=new ExpenseFinancials.Participation(b,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->ExpenseFinancials.of(p,e,new Amount(Fraction.ONE),a,List.of(participant,participant)));
        assertFalse(financials(0).participants().containsKey(a));
    }
    @Test void l1a12MixedFactsAreTechnicalAnomalies() {
        assertThrows(InconsistentPotFactsException.class,()->new PotFacts(p,5,new Versioned<>(1,6,new PotHeader(p,false)),new AuthorizationFacts(p,5,creator,creator,false)));
        assertThrows(InconsistentPotFactsException.class,()->new PotFacts(p,5,new Versioned<>(1,1,new PotHeader(p,false)),new AuthorizationFacts(p,4,creator,creator,false)));
        assertThrows(InconsistentPotFactsException.class,()->new ShareholderReference(p,5,a,2,Optional.of(new Versioned<>(1,1,new ShareholderHeader(p,a,false)))));
        assertThrows(InconsistentPotFactsException.class,()->ShareholderTransitions.updateWeights(pot(12),10,List.of(new ShareholderTransitions.WeightIntent(a,two)),refs(11),Map.of(a,new Versioned<>(1,2,new ShareholderWeight(p,a,one)))));
        assertThrows(InconsistentPotFactsException.class,()->ShareholderTransitions.updateWeights(pot(12),10,List.of(new ShareholderTransitions.WeightIntent(a,two)),refs(12),Map.of()));
        assertThrows(InconsistentPotFactsException.class,()->new ExpenseState(eh(),new Versioned<>(1,1,new ExpenseDetails(other,e,label,date)),ef(14)));
        assertThrows(InconsistentPotFactsException.class,()->ShareholderTransitions.delete(pot(12),10,ref(a,12,2,false),new ActiveShareholderUsage(other,12,a,false,true)));
    }
    @Test void l1a13DeterministicReevaluationPreservesInitialObservation() {
        var old=new Versioned<>(2,5,new PotDetails(p,label,creator));
        var first=changed(PotTransitions.updateDetails(pot(8),6,old,new Label("B")),8);
        var duplicate=changed(PotTransitions.updateDetails(pot(8),6,old,new Label("B")),8);
        assertEquals(first.changes(),duplicate.changes());
        var rebased=changed(PotTransitions.updateDetails(pot(9),6,old,new Label("B")),9);
        assertEquals(new Versioned<>(3,10,new PotDetails(p,new Label("B"),creator)),rebased.changes());
        rejected(PotTransitions.updateDetails(pot(9),6,new Versioned<>(3,9,old.value()),new Label("B")),OBSOLETE);
        assertEquals(2,old.localVersion());
        assertThrows(ArithmeticException.class,()->PotTransitions.updateDetails(pot(Long.MAX_VALUE),6,old,new Label("B")));
        assertThrows(ArithmeticException.class,()->PotTransitions.updateDetails(pot(8),6,new Versioned<>(Long.MAX_VALUE,5,old.value()),new Label("B")));
    }
    @Test void l1a14HistoricalCompositionsAndOrdinaryAttachment() {
        var before=new ShareholderState(new Versioned<>(1,2,new ShareholderHeader(p,a,false)),new Versioned<>(1,2,new ShareholderDetails(p,a,new Name("A"),Optional.empty())),new Versioned<>(1,2,new ShareholderWeight(p,a,one)));
        var after=new ShareholderState(before.header(),before.details(),new Versioned<>(2,6,new ShareholderWeight(p,a,two)));
        assertSame(before.details(),after.details()); assertEquals(one,before.weight().value().defaultWeight());
        var self=List.of(new ShareholderTransitions.DetailsIntent(a,new Name("A"),Optional.of(actor)));
        var attached=changed(ShareholderTransitions.updateDetails(pot(12,actor,true,false),10,self,refs(12),Map.of(a,before.details())),12).changes().get(a);
        assertEquals(Optional.of(actor),attached.value().userId());
        rejected(ShareholderTransitions.updateDetails(pot(12),10,self,refs(12),Map.of(a,before.details())),INVALID_ATTACHMENT);
        rejected(ShareholderTransitions.updateDetails(pot(13,third,true,false),13,List.of(new ShareholderTransitions.DetailsIntent(a,new Name("A"),Optional.empty())),refs(13),Map.of(a,attached)),INVALID_ATTACHMENT);
        for(var user:List.of(actor,creator)) assertEquals(Optional.empty(),changed(ShareholderTransitions.updateDetails(pot(13,user,true,false),13,List.of(new ShareholderTransitions.DetailsIntent(a,new Name("A"),Optional.empty())),refs(13),Map.of(a,attached)),13).changes().get(a).value().userId());
        rejected(ShareholderTransitions.updateDetails(pot(13),13,List.of(new ShareholderTransitions.DetailsIntent(a,new Name("A"),Optional.of(creator))),refs(13),Map.of(a,attached)),INVALID_ATTACHMENT);
    }
}
