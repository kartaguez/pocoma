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
| `domain-authorization` | Capacité provider-neutral `Permission(objectType, action)` | JDK | Pot, engines, frameworks | target |
| `domain-event` | Marqueur générique `BusinessEvent` | JDK | domaines fonctionnels, engines, frameworks | target |
| `domain-pot` | Modèle Pot, valeurs, agrégats et `BusinessEvent` typés | JDK | autres domaines, engines, frameworks | target |
| `domain-pot-policy` | Policies Pot utilisant directement `Permission` | autorisation, Pot, JDK | engines, infra, runtime | target |
| `domain-projection-balance` | `PotBalances`, `Balance`, calcul incrémental | `domain-pot`, JDK | engines, persistence, workers | target |
| `domain-projection` | identité générique, statut dérivé, artifact/failure/head, watermark et modèle canonique `PotProjection` | pipeline, Pot, JDK | engines, persistence, frameworks | target Lot 7.6 |
| `domain-pipeline` | identité, applicabilité, catalogue/registry des définitions et stratégie statique de sélection reader par pipeline | JDK | tout module applicatif | target Lots 7.3.1/7.9 |
| `domain-task` | marqueur fonctionnel `TaskPayload` | JDK | pipeline, Pot, engines, persistence | target |
| `domain-consumption` | `ConsumptionKey`, `ConsumptionSlot`, `ClaimId`, lease, failure | JDK | objets consommés, engines, workers, persistence | target |

## Engines

| Module | Responsabilité et principaux types | Dépendances autorisées | Interdites | État |
|---|---|---|---|---|
| `engine-core` | contrats partagés : snapshots, `RecordedEvent`, trace, transaction, segmentation, `TaskDescriptor` | domaines nécessaires | infra, supra, runtime | shared |
| `engine-pot-command` | Commands métier typées, inbound ports d'écriture Pot, services et adapters du moteur Command | Pot, policies, core, engine-command | consumption, processing, tasks, workers | target |
| `engine-query` | six lectures Pot/balances et ports query | Pot, policies, balance, core | command processing, consumption, workers | target |
| `engine-projection` | calcul applicatif de projection Balance et ports dédiés | Pot, balance, core | workers, nouveaux processing engines | target + legacy isolé |
| `engine-read-projection` | watermark, matérialisation générique et reconstruction exacte de `PotProjection` via port primaire intentionnel | projection, pipeline, Pot | Task lifecycle, infra, frameworks | target Lot 7.6 |
| `engine-task-creation` | pertinence Event→pipelineId, applicabilité canonique par génération et assurance atomique de toutes les Tasks Event-derived | Pot events, pipeline, core | consumption, workers, read store, sélection reader, materialization legacy | target Lot 7.5 |
| `engine-task-execution` | mapping durable, routage d'un `TaskPayload` typé et rapport fonctionnel d'exécution | pipeline, task | consumption, claims, workers, persistence | target |
| `engine-consumption` | slots/claims, acquisition/failure et exécution générique atomique protégée par `currentClaimId` | consumption, transaction core | Command, Event, Task, Pot, Pipeline, execution guard | target |
| `engine-command` | envelope durable générique, décodage, dispatch, exécution et ports de persistence/discovery | authorization, event, consumption terminal, JDK | Pot, processing, infra, frameworks | target |
| `engine-processing-event` | discovery catalog-driven des couples Event/génération manquants et pagination stable | consumption, pipeline, Pot event, core | Command/Task processing, task creation, read store | target Lot 7.5 |
| `engine-processing-task` | contrat de recherche courte et de relecture autoritative des Tasks durables | pipeline, Pot id, core | consumption, Event/Command processing, task execution, execution guard | target |
| `engine-task-materialization` | ancien flux Event envelope vers tâches sérialisées | core et pipeline | nouveaux packages fonctionnels | legacy — retrait avec EventWorker |

## Adaptateurs, orchestration et composition

| Module(s) | Responsabilité | Peut dépendre de | État / retrait |
|---|---|---|---|
| `orchestrator-command-admission` | principal authentifié provider-neutral, traduction des autorités, résolution d'identité, snapshot et insert transactionnel | engine-command, transaction core | target, sans Spring/JWT/Keycloak |
| `supra-worker-event` | boucle pull Event séquentielle par pipeline/version et segment, task creation idempotente puis lifecycle | ports entrants Event processing/task creation, orchestrateur | target, wiring PostgreSQL/Spring en étapes 4–5 |
| `supra-consumption-worker` | boucle de polling générique, budgets, cadence, arrêt coopératif et observation runtime minimale | orchestrateur consumption | target, ignorant des familles métier |
| `locator-consumption-task` | localisation Task, relecture autoritative, traduction rapport/provenance et classification technique | processing/execution Task, consumption générique | target |
| `locator-consumption-command` | convention `ConsumptionKey` Command, discovery, relecture/exécution autoritative, adaptation de provenance et classification technique conservative | engine-command, domain/engine consumption, orchestrator-consumption | target, sans runtime |
| `locator-consumption-source-version-watermark` | localisation Event dédiée, observation monotone du watermark, provenance d'entrée et classification technique | processing Event, read projection, consumption générique | target Lot 7.4, sans Task ni projection métier |
| `binding-pot-command-spring` | assemblage des decoders et adapters Pot derrière les contrats génériques Command | engine-command, engine-pot-command, Spring composition | target, sans polling ni transaction locale |
| `pipeline-balance` | binding Task Balance, calcul historique exact et contrat de projection immuable | domaines et engines fonctionnels | target, framework-free et indépendant de consumption |
| `pipeline-pot` | relevance Event, création/mapping Task et handler `read-pot/v1`, sans watermark ni sélection reader | domaines et engines fonctionnels | target Lot 7.6, framework-free |
| `supra-authentication-spring-security` | Resource Server OAuth2 standard et adaptation du principal Spring vers `AuthenticatedExternalPrincipal` | Spring Security, orchestrator-command-admission | target, implémentation de frontière remplaçable |
| `supra-http-rest-spring` | queries HTTP existantes et admission Command asynchrone ; aucune mutation Pot directe | Query/admission | target |
| `supra-dispatcher-business-events-outbox-nats` | ancien worker/outbox Event | projection legacy, orchestrateur | legacy, remplacé par EventWorker |
| `supra-dispatcher-task-materialization-nats` | ancien déclenchement de matérialisation | task materialization legacy | legacy, remplacé par EventWorker |
| `supra-dispatcher-balance-calculation-tasks-outbox-nats` | ancien traitement des tâches de projection | projection legacy | legacy, remplacé par TaskWorker |
| `supra-worker-balance-calculation-events-spring` | ancien worker événementiel Balance | projection legacy | legacy, remplacé par EventWorker/TaskWorker |
| `shared-supra-dispatcher-projection` | contrats partagés des workers de projection actuels | engine projection/core | legacy avec les workers actuels |
| `infra-tx-spring` | implémentation Spring de `TransactionRunner` | engine-core | target |
| `infra-event-publisher-spring` | publication Spring utilisée par les projections/read flows conservés | core et Spring | transition read-side |
| `infra-persistence-jpa` | implémentations JPA/JDBC des ports, dont Recorded Commands immutables et discovery best effort | engines propriétaires, domaines | target + adapters legacy à migrer |
| `infra-read-persistence` | frontière logique du read store : métadonnées génériques, watermark et quatre tables/writer Pot V5 | ports `engine-read-projection`, Spring JDBC/transactions et Flyway | target Lot 7.6, sans dépendance vers la persistence primaire |
| `observability` | décorateurs de métriques et trace | contrats observés | infrastructure transversale |
| `shared-runtime-spring-config` | assemblage Spring partagé | domaines, engines, infra | composition |
| `runtime-web-api` | composition de l'API HTTP | shared config, supra HTTP | composition |
| `runtime-event-consumption-worker` | composition du locator Event→Task, moteur transactionnel et polling générique | locator/orchestrateur/supra/infra | composition target |
| `runtime-source-version-watermark-consumption-worker` | consumer Event express indépendant : reload autoritatif, max-upsert watermark, lifecycle générique | locator watermark/orchestrateur/supra/infra primaire et read store | composition target Lot 7.4, sans Task ni projector |
| `runtime-business-events-outbox-dispatcher` | ancien dispatcher outbox | supra legacy, shared config | OLD RUNTIME ONLY — retrait Lot 5.5 |
| `runtime-task-materialization-dispatcher` | ancien matérialiseur | supra legacy, shared config | OLD RUNTIME ONLY — retrait Lot 5.5 |
| `runtime-task-consumption-worker` | composition générique du locator Task et binding exact Balance ou READ_POT selon la génération configurée | locator/orchestrateur/supra/infra/pipelines | composition target Lot 7.6 |
| `runtime-command-consumption-worker` | composition du locator Command, moteur transactionnel, polling générique et binding Pot | locator/orchestrateur/supra/infra/binding Pot Command | composition target, processus distinct de l'API HTTP |
| `runtime-balance-calculation-tasks-dispatcher` | ancien runtime des tâches Balance | supra legacy, shared config | OLD RUNTIME ONLY — retrait Lot 5.5 |
| `runtime-monolith` | composition transitionnelle Query/projection sans write path Command synchrone | couches read/projection conservées | conservé jusqu'au redesign read-side |
| `architecture-tests` | vérification des frontières de packages | tous les modules inspectés | validation |

## Exceptions transitoires contrôlées

- Les processing engines composent les use cases génériques d'`engine-consumption`.
- Le chemin Command protège le commit avec une transaction métier unique terminée par le CAS
  `status=PENDING AND current_claim_id=:claimId`. L'ancien execution guard et l'ancien processing
  Command ont été supprimés.
- La persistence Command cible implémente uniquement les ports d'`engine-command` et ne crée aucun slot.
- `engine-command` ne connaît pas `ConsumptionKey`; la convention `COMMAND / COMMAND_PROCESSOR`
  appartient exclusivement à `locator-consumption-command`.
- Seules les erreurs Command explicitement reconnues comme transitoires sont retentées ; une
  exception runtime inconnue est terminale.
- L'observation du polling reste limitée aux cycles, budgets, délais et compteurs déjà exposés par
  l'orchestrateur. Elle n'ajoute aucun callback métier à `engine-consumption` ou à
  `orchestrator-consumption`.
- L'Event processing utilise `RecordedEvent` et `PipelineDefinition`, sans appeler task creation.
- Task processing connaît uniquement les données structurelles de la Task et ne consulte ni slot,
  ni claim, ni statut ou lease legacy.
- L'infrastructure dépend des ports sortants qu'elle implémente.
- Les interfaces spécialisées `*UseCase` de `engine-pot-command` restent les inbound ports métier ;
  les adapters Command durables ne dépendent pas des services concrets.
- Le supra HTTP ne dépend ni des ports ni des services de mutation Pot. Les verbes HTTP ne sont pas
  interdits globalement : seule la mutation directe du write model primaire l'est.
- La persistence Task n'a aucune dépendance vers un supra ni vers le lifecycle Consumption pour
  sélectionner ses candidats.
