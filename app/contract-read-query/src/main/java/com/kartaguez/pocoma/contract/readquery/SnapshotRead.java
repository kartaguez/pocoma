package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.pot.v3.Invitation;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import static com.kartaguez.pocoma.contract.readquery.ReadPotV3Result.*;
import java.util.*;
import java.util.function.Function;
/** Internal query capabilities. The execution policy alone owns the snapshot callback. */
public interface SnapshotRead {
    ReadPotV3Result<Pot> current(ExternalIdentity eui,String token,UUID id,long minimum,RecoveryReadContext context);
    ReadPotV3Result<Pot> at(ExternalIdentity eui,String token,UUID id,long version,RecoveryReadContext context);
    ReadPotV3Result<PotBalance> balance(ExternalIdentity eui,String token,UUID id,long version,RecoveryReadContext context);
    ReadPotV3Result<List<Expense>> expenses(ExternalIdentity eui,String token,UUID id,long version,List<ExpenseRef> refs,RecoveryReadContext context);
    ReadPotV3Result<List<UUID>> visible(ExternalIdentity eui,String token,UUID after,int limit,RecoveryReadContext context);
    BindingRead.Answer binding(ExternalIdentity eui,long minimum,RecoveryReadContext context);
    ReadPotV3Result<InvitationRead.Link> invitationLink(ExternalIdentity eui,UUID request,Function<Invitation,String> decrypt,RecoveryReadContext context);
    ReadPotV3Result<List<InvitationRead.Place>> invitationPlaces(ExternalIdentity eui,String binding,String token,RecoveryReadContext context);
    Optional<CommandTerminalResult> commandResult(UUID request,ExternalIdentity eui,RecoveryReadContext context);
    Optional<IdentityTerminalResult> identityResult(UUID request,ExternalIdentity eui,RecoveryReadContext context);
}
