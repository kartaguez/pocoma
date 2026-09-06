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

Les controllers HTTP ne peuvent pas dépendre des ports ou services de mutation Pot. Cette règle
protège la frontière du write model sans interdire de futurs endpoints non-GET qui ne muteraient pas
le modèle primaire (recherche, administration ou authentification, par exemple). Les anciennes
routes synchrones de mutation sont en plus vérifiées explicitement comme absentes de l'OpenAPI.

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
