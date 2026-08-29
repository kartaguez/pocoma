# Matrice des dépendances des modules

## Direction générale

```text
domain
   ↑
engine fonctionnel
   ↑
engine processing
   ↑
worker / supra
   ↑
runtime
```

Les flèches représentent la direction dans laquelle une couche extérieure peut utiliser une
couche intérieure. L'infrastructure implémente les ports sortants définis par les engines. Aucun
domaine ou engine ne dépend d'un runtime, d'un supra ou d'un adapter d'infrastructure.

## Modules de domaine

| Module | Responsabilité et principaux types | Dépendances autorisées | Interdites | État |
|---|---|---|---|---|
| `domain-pot` | Modèle Pot, valeurs, agrégats et `BusinessEvent` typés | JDK | autres domaines, engines, frameworks | target |
| `domain-pot-policy` | Policies d'autorisation Pot et `Scope` | `domain-pot`, JDK | engines, infra, runtime | target |
| `domain-projection-balance` | `PotBalances`, `Balance`, calcul incrémental | `domain-pot`, JDK | engines, persistence, workers | target |
| `domain-pipeline` | `PipelineId`, `PipelineDefinition` | JDK | tout module applicatif | target |
| `domain-task` | marqueur fonctionnel `TaskPayload` | JDK | pipeline, Pot, engines, persistence | target |
| `domain-consumption` | `ConsumptionKey`, `ConsumptionSlot`, `Claim`, token, lease, failure | JDK | objets consommés, engines, workers, persistence | target |

## Engines

| Module | Responsabilité et principaux types | Dépendances autorisées | Interdites | État |
|---|---|---|---|---|
| `engine-core` | contrats partagés : snapshots, `RecordedEvent`, trace, transaction, segmentation, `TaskDescriptor` | domaines nécessaires | infra, supra, runtime | shared |
| `engine-execution-guard` | exécution technique idempotente d'une clé et journal abstrait | engine-core, JDK | consumption, objets consommés, workers, frameworks | target |
| `engine-command` | commandes métier typées, routeur et ports d'écriture Pot/événement | Pot, policies, core | consumption, processing, tasks, workers | target |
| `engine-query` | six lectures Pot/balances et ports query | Pot, policies, balance, core | command processing, consumption, workers | target |
| `engine-projection` | calcul applicatif de projection Balance et ports dédiés | Pot, balance, core | workers, nouveaux processing engines | target + legacy isolé |
| `engine-task-creation` | Event typé + pipeline vers zéro à N tâches | Pot events, pipeline, core | consumption, workers, materialization legacy | target |
| `engine-task-execution` | routage d'un `TaskPayload` typé vers son handler | pipeline, task | claims, workers, JSON dans le périmètre typé | target + pont legacy |
| `engine-consumption` | acquire/complete/fail/release d'une clé opaque | consumption, transaction core | Command, Event, Task, Pot, Pipeline | target |
| `engine-processing-command` | sélection, ordre, transitions et réconciliation du statut Command depuis le slot autoritatif | command, consumption, core | Event/Task processing, frameworks, execution guard | target |
| `engine-processing-event` | consommation indépendante d'un événement par pipeline/version ; ignore les slots terminaux sans mutation source | consumption, pipeline, Pot event, core | Command/Task processing, task creation | target |
| `engine-processing-task` | sélection, ordre, transitions et réconciliation du statut Task depuis le slot autoritatif | consumption, pipeline, Pot id, core | Event/Command processing, task execution, execution guard | target |
| `engine-task-materialization` | ancien flux Event envelope vers tâches sérialisées | core et pipeline | nouveaux packages fonctionnels | legacy — retrait avec EventWorker |

## Adaptateurs, orchestration et composition

| Module(s) | Responsabilité | Peut dépendre de | État / retrait |
|---|---|---|---|
| `orchestrator-claimable-work-dispatcher` | polling, capacité, pools segmentés et cycle d'un travail claimable | contrats de travail injectés | target, réutilisé par les futurs workers |
| `supra-worker-command` | boucle pull Command séquentielle, guard puis lifecycle | ports entrants Command/processing, execution guard, orchestrateur | target, wiring PostgreSQL/Spring en étapes 4–5 |
| `supra-worker-event` | boucle pull Event séquentielle par pipeline/version et segment, task creation idempotente puis lifecycle | ports entrants Event processing/task creation, orchestrateur | target, wiring PostgreSQL/Spring en étapes 4–5 |
| `supra-http-rest-spring` | entrée HTTP réactive | ports entrants Command/Query | target |
| `supra-dispatcher-business-events-outbox-nats` | ancien worker/outbox Event | projection legacy, orchestrateur | legacy, remplacé par EventWorker |
| `supra-dispatcher-task-materialization-nats` | ancien déclenchement de matérialisation | task materialization legacy | legacy, remplacé par EventWorker |
| `supra-dispatcher-pipeline-tasks` | ancien worker de tâches durables | task execution legacy, orchestrateur | legacy, remplacé par TaskWorker |
| `supra-dispatcher-balance-calculation-tasks-outbox-nats` | ancien traitement des tâches de projection | projection legacy | legacy, remplacé par TaskWorker |
| `supra-worker-balance-calculation-events-spring` | ancien worker événementiel Balance | projection legacy | legacy, remplacé par EventWorker/TaskWorker |
| `shared-supra-dispatcher-projection` | contrats partagés des workers de projection actuels | engine projection/core | legacy avec les workers actuels |
| `infra-tx-spring` | implémentation Spring de `TransactionRunner` | engine-core | target |
| `infra-event-publisher-spring` | outbox typée puis publication après commit | engine-command/core | target |
| `infra-persistence-jpa` | implémentations JPA des ports et stockage legacy | engines propriétaires, domaines | target + adapters legacy à migrer |
| `observability` | décorateurs de métriques et trace | contrats observés | infrastructure transversale |
| `shared-runtime-spring-config` | assemblage Spring partagé | domaines, engines, infra | composition |
| `runtime-web-api` | composition de l'API HTTP | shared config, supra HTTP | composition |
| `runtime-business-events-outbox-dispatcher` | lancement de l'ancien dispatcher outbox | supra legacy, shared config | composition legacy |
| `runtime-task-materialization-dispatcher` | lancement de l'ancien matérialiseur | supra legacy, shared config | composition legacy |
| `runtime-pipeline-task-executor` | handlers typés et pont worker de tâches | task execution, projection, supra legacy | composition transitoire |
| `runtime-balance-calculation-tasks-dispatcher` | ancien runtime des tâches Balance | supra legacy, shared config | composition legacy |
| `runtime-monolith` | composition de l'ensemble des adapters actuels | couches précédentes | composition |
| `architecture-tests` | vérification des frontières de packages | tous les modules inspectés | validation |

## Exceptions transitoires contrôlées

- Les processing engines composent les use cases génériques d'`engine-consumption`.
- `engine-execution-guard` est un moteur technique orthogonal : il protège l'effet commité et ne
  connaît ni claim ni type de travail. CommandWorker et le futur TaskWorker l'instancient avec des
  journaux distincts.
- `engine-processing-command` connaît les intentions d'`engine-command` pour reconstruire l'appel métier.
- L'Event processing utilise `RecordedEvent` et `PipelineDefinition`, sans appeler task creation.
- Task processing connaît le pipeline durable, jamais les handlers d'exécution.
- L'infrastructure dépend des ports sortants qu'elle implémente.
- Trois dépendances JPA vers des contrats de supra legacy restent dans l'allow-list ArchUnit ; aucune
  nouvelle dépendance de ce sens n'est autorisée.
