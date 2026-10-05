package com.kartaguez.pocoma.engine.read.pot;
import java.util.Objects;
import java.util.Set;
import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.read.currentbinding.GetCurrentBindingUseCase;
/** Resolves E against convergent CURRENT_BINDING before AUTH@V and READ_POT@V. */
public final class ReadPotForExternalIdentityService implements ReadPotForExternalIdentityUseCase {
 private final ReadPotUseCase pots;
 private final GetCurrentBindingUseCase bindings;
 private final ExternalAuthorityPermissionTranslator permissions;
 public ReadPotForExternalIdentityService(ReadPotUseCase pots, GetCurrentBindingUseCase bindings, ExternalAuthorityPermissionTranslator permissions) {
  this.pots=Objects.requireNonNull(pots); this.bindings=Objects.requireNonNull(bindings); this.permissions=Objects.requireNonNull(permissions);
 }
 @Override public ReadPotResult read(ExternalIdentity identity, Set<String> externalAuthorities, PotId potId, long version) {
  Objects.requireNonNull(identity); Objects.requireNonNull(externalAuthorities);
  var attached=bindings.getAttached(identity);
  if (attached.isEmpty()) return new ReadPotResult.Forbidden();
  var capabilities=new TokenCapabilities(permissions.translate(externalAuthorities));
  return pots.read(new UserId(attached.orElseThrow().userId().value()), capabilities, potId, version);
 }
}
