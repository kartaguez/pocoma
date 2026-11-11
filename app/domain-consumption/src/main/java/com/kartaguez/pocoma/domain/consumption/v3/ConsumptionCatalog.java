package com.kartaguez.pocoma.domain.consumption.v3;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition.Authority;
import java.util.*;
/** Stable routing identities; reading the catalogue activates no participant. */
public final class ConsumptionCatalog {
private ConsumptionCatalog() {}
    public static List<ConsumptionDefinition> definitions() {
        return List.of(
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("98f7268d-d340-581c-b83d-010926223e14")),
                        ConsumableFamily.COMMAND,
                        Authority.WRITE),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("2999e75c-272d-5afe-a966-c9364ef021fe")),
                        ConsumableFamily.IDENTITY_REQUEST,
                        Authority.WRITE),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a")),
                        ConsumableFamily.POT_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("190a93c6-b8af-5ac7-8001-72e2ec53c2df")),
                        ConsumableFamily.POT_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("b4d04cce-b5da-5d1b-9698-5ff9af9a46fa")),
                        ConsumableFamily.POT_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("924cd9e3-435d-5804-9d1e-c6aecadb9ab4")),
                        ConsumableFamily.INVITATION_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("7b8d2838-bf7a-5117-868a-e5c14b4c0221")),
                        ConsumableFamily.IDENTITY_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("63664303-d33e-5d42-8cce-c4dae8023311")),
                        ConsumableFamily.COMMAND_COMPLETION_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("1d8ff246-358f-5ddd-a7f5-416efcfd7445")),
                        ConsumableFamily.IDENTITY_COMPLETION_EVENT,
                        Authority.READ));
    }

    public static List<ConsumptionDefinition> probeDefinitions() {
        return List.of(
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("a5000000-0000-4000-8000-000000000001")),
                        ConsumableFamily.POT_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("a5000000-0000-4000-8000-000000000002")),
                        ConsumableFamily.COMMAND_COMPLETION_EVENT,
                        Authority.READ));
    }

 public static Map<ConsumptionId,String> names() {
    var names=List.of("Command","IdentityRequest","PotVersion","PotBalance","PotVisibility","Invitation","CurrentBinding","CommandResult","IdentityRequestResult","I5ReadPotProbe","I5ReadCompletionProbe");
    var all=new ArrayList<>(definitions());all.addAll(probeDefinitions());var result=new LinkedHashMap<ConsumptionId,String>();
    for(int i=0;i<all.size();i++)result.put(all.get(i).id(),names.get(i));return Map.copyOf(result);
 }
}
