package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.domain.pot.Invitation;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import static com.kartaguez.pocoma.contract.readquery.ReadPotResult.*;
import java.util.*;
import java.util.function.Function;
/** Internal query capabilities. The execution policy alone owns the snapshot callback. */
public interface SnapshotRead {
    ReadPotResult<Pot> current(ExternalIdentity eui,String token,UUID id,long minimum,RecoveryReadContext context);
    ReadPotResult<Pot> at(ExternalIdentity eui,String token,UUID id,long version,RecoveryReadContext context);
    ReadPotResult<PotBalance> balance(ExternalIdentity eui,String token,UUID id,long version,RecoveryReadContext context);
    ReadPotResult<List<Expense>> expenses(ExternalIdentity eui,String token,UUID id,long version,List<ExpenseRef> refs,RecoveryReadContext context);
    ReadPotResult<List<UUID>> visible(ExternalIdentity eui,String token,UUID after,int limit,RecoveryReadContext context);
    BindingRead.Answer binding(ExternalIdentity eui,long minimum,RecoveryReadContext context);
    ReadPotResult<InvitationRead.Link> invitationLink(ExternalIdentity eui,UUID request,Function<Invitation,String> decrypt,RecoveryReadContext context);
    ReadPotResult<List<InvitationRead.Place>> invitationPlaces(ExternalIdentity eui,String binding,String token,RecoveryReadContext context);
    Optional<CommandTerminalResult> commandResult(UUID request,ExternalIdentity eui,RecoveryReadContext context);
    Optional<IdentityTerminalResult> identityResult(UUID request,ExternalIdentity eui,RecoveryReadContext context);
}
