# Clôture du write side Command

## Architecture canonique

Depuis le Lot 6.8, le runtime cible possède une seule voie de mutation du modèle primaire :

```text
HTTP POST /api/v1/commands
  -> authentification et admission
  -> RecordedCommand immutable dans PostgreSQL
  -> polling Command
  -> acquire générique et reload autoritatif
  -> adapter Command
  -> inbound port métier Pot
  -> service métier Pot
  -> mutation primaire + BusinessEvent + fencing + terminalisation
```

`202 Accepted` signifie uniquement que la Command est durable. Le succès, le rejet, le retry ou
l'échec terminal sont produits ultérieurement par le lifecycle générique de consommation.

### Delete Pot terminal

Depuis le micro-correctif du Lot 7.6, `Pot.deleted=true` est terminal pour les mutations métier du Pot
et de ses enfants. `CreateExpenseContext` portait déjà ce guard. Les contextes `DeleteExpenseContext`,
`UpdateExpenseDetailsContext` et `UpdateExpenseSharesContext` portent désormais aussi l'état deleted
du Pot chargé à la version courante et rejettent avec `POT_ALREADY_DELETED` avant toute allocation de
version, écriture primaire ou émission de `BusinessEvent`. Le correctif ne modifie ni le chemin Command,
ni le lifecycle, ni l'architecture du Lot 6.

Les controllers HTTP ne peuvent pas dépendre des ports ou services de mutation Pot. Cette règle
protège la frontière du write model sans interdire de futurs endpoints non-GET qui ne muteraient pas
le modèle primaire (recherche, administration ou authentification, par exemple). Les anciennes
routes synchrones de mutation sont en plus vérifiées explicitement comme absentes de l'OpenAPI.

### Timestamp fonctionnel des versions Pot

Depuis le Lot 7.7, toute création ou avance de `PotGlobalVersion` insère également
`PotVersionMetadata(potId, version, createdAt)` dans la même transaction primaire. PostgreSQL fixe
`createdAt` avec son timestamp transactionnel ; les retries et les BusinessEvents ne peuvent donc ni
le recalculer ni le remplacer. La table est protégée contre UPDATE et DELETE. Une allocation sans
metadata fait échouer et rollbacker la mutation entière.

La migration refuse explicitement une base possédant des compteurs Pot legacy sans metadata exacte :
ces données de développement doivent être reset, jamais complétées depuis un timestamp Event estimé.

## Frontières métier conservées

Les dix interfaces spécialisées de `engine.port.in.command.usecase` expriment des capacités métier
indépendantes du transport et de l'ancien dispatch. Elles sont conservées :

- `CreatePotUseCase`, `DeletePotUseCase`, `UpdatePotDetailsUseCase` ;
- `AddPotShareholdersUseCase`, `UpdatePotShareholdersDetailsUseCase`,
  `UpdatePotShareholdersWeightsUseCase` ;
- `CreateExpenseUseCase`, `DeleteExpenseUseCase`, `UpdateExpenseDetailsUseCase`,
  `UpdateExpenseSharesUseCase`.

Les adapters du moteur Command dépendent de ces ports, pas des classes concrètes. Les services métier
et l'ordre de leurs accès ne sont pas modifiés. En revanche, le marker `CommandIntent`, le routeur
générique `ExecuteCommandUseCase`/`ExecuteCommandService` et les wrappers transactionnels propres au
chemin HTTP synchrone ont disparu.

## Legacy supprimé et données conservées

Les modules `engine-processing-command`, `engine-execution-guard`, `supra-worker-command` et
`orchestrator-claimable-work-dispatcher` n'avaient plus de consommateur hors de l'ancien worker
Command. Ils sont retirés du reactor avec leurs configurations et tests. Le lifecycle officiel reste
exclusivement dans `ConsumptionSlot` et `Claim`.

Aucune migration destructive n'accompagne ce nettoyage. Les anciennes colonnes ou données
historiques restent physiquement disponibles tant qu'un futur chantier de données n'a pas défini
leur politique de rétention. `recorded_commands` conserve son contrat insert-only et ne reçoit
aucune donnée de lifecycle.

## Périmètre des preuves

- Les tests du Lot 6.6 prouvent l'admission durable seule.
- Les tests du Lot 6.7 prouvent le runtime de consommation seul.
- `WriteSideClosurePostgresTest` prouve le contrat fonctionnel cross-runtime HTTP → PostgreSQL →
  worker → état + Event, sans invocation directe entre admission et worker.
- `DistributedComposeConfigurationTest` et `docker compose ... config` prouvent la topologie et la
  configuration multi-process.
- Les tests d'architecture interdisent HTTP → mutation Pot directe et vérifient la disparition des
  abstractions de dispatch legacy.

Ce test cross-runtime ne constitue pas un smoke test Docker : il ne prouve ni deux JVM distinctes,
ni DNS/réseau entre containers, ni lifecycle de containers, ni scrape Prometheus.

## Limite du chantier

Les GET, Query, Event, Task, Balance et projections continuent d'utiliser les composants actuels.
Leur redesign appartient au chantier 7.x et n'est ni anticipé ni contraint ici. Le monolithe est
conservé pour ces usages, sans réactivation du write path synchrone.
