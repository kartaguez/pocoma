# Persistance des Commands enregistrées

## Donnée durable immutable

`recorded_commands` conserve la demande telle qu'elle a été soumise : son `commandId`, son
`commandType`, son payload opaque, sa date de soumission, l'`ExternalIdentity` attestée, le
`BindingId` présenté et l'évidence d'authentification nécessaire. Le payload
est stocké en `text` et n'est jamais parsé par la persistence. Une chaîne vide ou non JSON est donc
conservée exactement ; sa validité relève du `CommandDecoder` lors de l'exécution.

Le port expose uniquement `insert` et `findById`. Un identifiant déjà présent est une erreur
technique et aucune opération d'update ou de delete n'existe. La table ne contient aucun statut,
claim, retry, résultat ou erreur de traitement.

## Identité et évidence durable

```text
RecordedCommand
  commandId
  commandType
  payload
  submittedAt
  ExternalIdentity(issuer, subject)
  BindingId
  authenticationEvidence
```

`PocomaUserId` ne fait pas partie de l'identité résolue à l'admission. La persistence ne
réinterprète ni l'identité, ni le binding, ni l'évidence. `validUntil`, lorsqu'il appartient à
l'évidence retenue, est vérifié au traitement avant le dispatch.

L'évidence peut conserver les capabilities externes attestées nécessaires au worker, mais elle
n'est jamais une décision d'AuthZ prise à l'admission et ne contient jamais le JWT brut. Le schéma
SQL exact et la migration des anciennes rows appartiennent au futur plan.

## Discovery best effort

La discovery parcourt `recorded_commands` dans l'ordre PostgreSQL
`(submitted_at, command_id)`. Son cursor keyset est exclusif et ne définit aucun ordre Java. La
query écarte les slots `DONE`, les retries futurs et les claims dont le lease est encore actif.
Elle ne verrouille et ne réserve rien : deux workers peuvent découvrir la même Command.

L'identité de consommation, propriété de `locator-consumption-command`, est :

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

## Intégration Consumption et runtime

La discovery ne transporte que `commandId` et `submittedAt`. Après acquisition, la recette recharge
la Command autoritativement, puis appelle `ExecuteRecordedCommandUseCase` dans la transaction
gagnante. Le décodage, le dispatch, les mutations Pot, l'append des Events, la provenance, le
fencing final et la terminalisation participent ainsi au même commit PostgreSQL.

```text
RecordedCommand
  -> discovery best effort
  -> acquire COMMAND / commandId, COMMAND_PROCESSOR
  -> reload autoritatif
  -> résolution primaire autoritative (ExternalIdentity, BindingId) -> PocomaUserId
  -> évaluation capabilities/AuthZ et invariants courants
  -> decode / dispatch / exécution
  -> SUCCESS ou REJECTED, ou classification technique
  -> fencing et terminalisation génériques
```

Une autorisation expirée n'est pas filtrée par la discovery : elle devient
`DONE/REJECTED/AUTHORIZATION_EXPIRED` pendant l'exécution. Seules les erreurs SQL transitoires
explicitement reconnues sont retentées. Toute erreur technique inconnue est terminale par défaut.

Le runtime `runtime-command-consumption-worker` compose désormais ce chemin avec le worker de
polling générique. Il est séparé du runtime HTTP et ne connaît ni payload Command, ni autorisation,
ni use case Pot. Un worker exécute au plus une consommation active ; le parallélisme est obtenu en
lançant plusieurs instances, arbitrées par les claims et le fencing génériques.

```text
HTTP
  -> RecordedCommand
  -> runtime Command : poll / locator / discovery
  -> acquire / ConsumptionSlot / Claim
  -> ExecuteRecordedCommandUseCase
  -> terminalisation ou retry
```

Le Lot 6.6 implémente désormais la première flèche avec `POST /api/v1/commands` : authentification
Resource Server dans le supra Spring, capture de l'identité, du BindingId et de l'évidence, puis insert dans
`recorded_commands`. Cette admission ne déclenche toujours ni discovery, ni slot, ni exécution.
Voir [recorded-command-intake.md](recorded-command-intake.md).

La configuration, le shutdown, le scaling et l'observabilité de la boucle sont détaillés dans
[command-consumption-runtime.md](command-consumption-runtime.md).
