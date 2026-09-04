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
| `domain-consumption` | `ConsumptionKey`, `ConsumptionSlot`, `ClaimId`, lease, failure | JDK | objets consommés, engines, workers, persistence | target |

## Engines

| Module | Responsabilité et principaux types | Dépendances autorisées | Interdites | État |
|---|---|---|---|---|
| `engine-core` | contrats partagés : snapshots, `RecordedEvent`, trace, transaction, segmentation, `TaskDescriptor` | domaines nécessaires | infra, supra, runtime | shared |
| `engine-execution-guard` | ancien guard d'exécution technique par journal | engine-core, JDK | consumption, objets consommés, workers, frameworks | legacy — conservé uniquement pour Command |
| `engine-pot-command` | commandes métier typées, routeur et ports d'écriture Pot/événement | Pot, policies, core | consumption, processing, tasks, workers | target |
| `engine-query` | six lectures Pot/balances et ports query | Pot, policies, balance, core | command processing, consumption, workers | target |
| `engine-projection` | calcul applicatif de projection Balance et ports dédiés | Pot, balance, core | workers, nouveaux processing engines | target + legacy isolé |
| `engine-task-creation` | Event typé + pipeline vers zéro à N tâches | Pot events, pipeline, core | consumption, workers, materialization legacy | target |
| `engine-task-execution` | mapping durable, routage d'un `TaskPayload` typé et rapport fonctionnel d'exécution | pipeline, task | consumption, claims, workers, persistence | target |
| `engine-consumption` | slots/claims, acquisition/failure et exécution générique atomique protégée par `currentClaimId` | consumption, transaction core | Command, Event, Task, Pot, Pipeline, execution guard | target |
| `engine-processing-command` | sélection, ordre, transitions et réconciliation du statut Command depuis le slot autoritatif | command, consumption, core | Event/Task processing, frameworks, execution guard | target |
| `engine-processing-event` | consommation indépendante d'un événement par pipeline/version ; ignore les slots terminaux sans mutation source | consumption, pipeline, Pot event, core | Command/Task processing, task creation | target |
| `engine-processing-task` | contrat de recherche courte et de relecture autoritative des Tasks durables | pipeline, Pot id, core | consumption, Event/Command processing, task execution, execution guard | target |
| `engine-task-materialization` | ancien flux Event envelope vers tâches sérialisées | core et pipeline | nouveaux packages fonctionnels | legacy — retrait avec EventWorker |

## Adaptateurs, orchestration et composition

| Module(s) | Responsabilité | Peut dépendre de | État / retrait |
|---|---|---|---|
| `orchestrator-claimable-work-dispatcher` | polling, capacité, pools segmentés et cycle d'un travail claimable | contrats de travail injectés | target, réutilisé par les futurs workers |
| `supra-worker-command` | boucle pull Command séquentielle, guard puis lifecycle | ports entrants Command/processing, execution guard, orchestrateur | legacy côté guard ; migration vers l'exécution atomique au Lot 4 |
| `supra-worker-event` | boucle pull Event séquentielle par pipeline/version et segment, task creation idempotente puis lifecycle | ports entrants Event processing/task creation, orchestrateur | target, wiring PostgreSQL/Spring en étapes 4–5 |
| `supra-consumption-worker` | boucle de polling générique, budgets, cadence et arrêt gracieux | orchestrateur consumption | target, ignorant des familles métier |
| `locator-consumption-task` | localisation Task, relecture autoritative, traduction rapport/provenance et classification technique | processing/execution Task, consumption générique | target |
| `pipeline-balance` | binding Task Balance, calcul historique exact et contrat de projection immuable | domaines et engines fonctionnels | target, framework-free et indépendant de consumption |
| `supra-http-rest-spring` | entrée HTTP réactive | ports entrants Command/Query | target |
| `supra-dispatcher-business-events-outbox-nats` | ancien worker/outbox Event | projection legacy, orchestrateur | legacy, remplacé par EventWorker |
| `supra-dispatcher-task-materialization-nats` | ancien déclenchement de matérialisation | task materialization legacy | legacy, remplacé par EventWorker |
| `supra-dispatcher-balance-calculation-tasks-outbox-nats` | ancien traitement des tâches de projection | projection legacy | legacy, remplacé par TaskWorker |
| `supra-worker-balance-calculation-events-spring` | ancien worker événementiel Balance | projection legacy | legacy, remplacé par EventWorker/TaskWorker |
| `shared-supra-dispatcher-projection` | contrats partagés des workers de projection actuels | engine projection/core | legacy avec les workers actuels |
| `infra-tx-spring` | implémentation Spring de `TransactionRunner` | engine-core | target |
| `infra-event-publisher-spring` | outbox typée puis publication après commit | engine-pot-command/core | target |
| `infra-persistence-jpa` | implémentations JPA des ports et stockage legacy | engines propriétaires, domaines | target + adapters legacy à migrer |
| `observability` | décorateurs de métriques et trace | contrats observés | infrastructure transversale |
| `shared-runtime-spring-config` | assemblage Spring partagé | domaines, engines, infra | composition |
| `runtime-web-api` | composition de l'API HTTP | shared config, supra HTTP | composition |
| `runtime-business-events-outbox-dispatcher` | ancien dispatcher outbox | supra legacy, shared config | OLD RUNTIME ONLY — retrait Lot 5.5 |
| `runtime-task-materialization-dispatcher` | ancien matérialiseur | supra legacy, shared config | OLD RUNTIME ONLY — retrait Lot 5.5 |
| `runtime-task-consumption-worker` | composition du locator Task, moteur transactionnel, polling et binding Balance | locator/orchestrateur/supra/infra/pipeline Balance | composition target |
| `runtime-balance-calculation-tasks-dispatcher` | ancien runtime des tâches Balance | supra legacy, shared config | OLD RUNTIME ONLY — retrait Lot 5.5 |
| `runtime-monolith` | API/Query et composition transitionnelle | couches précédentes | conservé ; profil worker legacy déprécié jusqu'au Lot 6.5 |
| `architecture-tests` | vérification des frontières de packages | tous les modules inspectés | validation |

## Exceptions transitoires contrôlées

- Les processing engines composent les use cases génériques d'`engine-consumption`.
- Le chemin cible protège le commit avec une transaction métier unique terminée par le CAS
  `status=PENDING AND current_claim_id=:claimId`. `engine-execution-guard` ne participe jamais à ce
  chemin. Il reste temporairement utilisé par Command uniquement.
- `engine-processing-command` connaît les intentions d'`engine-pot-command` pour reconstruire l'appel métier.
- L'Event processing utilise `RecordedEvent` et `PipelineDefinition`, sans appeler task creation.
- Task processing connaît uniquement les données structurelles de la Task et ne consulte ni slot,
  ni claim, ni statut ou lease legacy.
- L'infrastructure dépend des ports sortants qu'elle implémente.
- La persistence Task n'a aucune dépendance vers un supra ni vers le lifecycle Consumption pour
  sélectionner ses candidats.
