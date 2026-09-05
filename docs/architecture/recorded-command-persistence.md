# Persistance des Commands enregistrées

## Donnée durable immutable

`recorded_commands` conserve la demande telle qu'elle a été soumise : son `commandId`, son
`commandType`, son payload opaque, sa date de soumission et le snapshot d'autorisation. Le payload
est stocké en `text` et n'est jamais parsé par la persistence. Une chaîne vide ou non JSON est donc
conservée exactement ; sa validité relève du `CommandDecoder` lors de l'exécution.

Le port expose uniquement `insert` et `findById`. Un identifiant déjà présent est une erreur
technique et aucune opération d'update ou de delete n'existe. La table ne contient aucun statut,
claim, retry, résultat ou erreur de traitement.

## Snapshot d'autorisation

Les permissions provider-neutral `Permission(objectType, action)` sont capturées à la soumission
avec l'utilisateur, l'issuer et les dates `authenticatedAt`, `issuedAt` et `validUntil`. Elles sont
stockées dans un tableau JSONB. L'ordre du tableau n'est pas fonctionnel et les permissions futures
restent lisibles sans liste fermée côté persistence.

Le snapshot permet de rejouer exactement la décision soumise. `validUntil` est vérifié par
`ExecuteRecordedCommandService` avant le décodage ; la persistence ne réinterprète aucune donnée
d'autorisation.

## Discovery best effort

La discovery parcourt `recorded_commands` dans l'ordre PostgreSQL
`(submitted_at, command_id)`. Son cursor keyset est exclusif et ne définit aucun ordre Java. La
query écarte les slots `DONE`, les retries futurs et les claims dont le lease est encore actif.
Elle ne verrouille et ne réserve rien : deux workers peuvent découvrir la même Command.

L'identité future de consommation est :

```text
ConsumableIdentity = COMMAND / [commandId]
ConsumerIdentity   = COMMAND_PROCESSOR / []
```

`engine-consumption.acquire()` reste la seule autorité. Il crée paresseusement le slot absent,
verrouille le lifecycle, arbitre les takeovers et installe le claim. L'insertion d'une Command ne
crée jamais de `ConsumptionSlot` ou de `Claim`.

```text
recorded_commands       = données durables de la demande
consumption_slots       = lifecycle autoritatif
consumption_claims      = propriété temporaire et fencing
```

## Flux cible futur

Le Lot 6.4 fournit seulement la persistence et la discovery. Le flux suivant documente la cible,
pas un runtime déjà actif :

```text
HTTP (Lot 6.6)
  -> RecordedCommand
  -> discovery
  -> ConsumptionSlot / Claim et locator (Lot 6.5)
  -> ExecuteRecordedCommandUseCase
  -> worker Command (Lot 6.7)
```
