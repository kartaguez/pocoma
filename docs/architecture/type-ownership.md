# Propriété des types

## Types cibles

| Concept | Type représentatif | Propriétaire |
|---|---|---|
| Pot et valeurs métier | `PotHeader`, `PotId`, `UserId` | `domain-pot` |
| Faits métier Pot | `BusinessEvent`, `PotCreatedEvent`, `ExpenseCreatedEvent` | `domain-pot.event` |
| Autorisation générique | `Permission` | `domain-authorization` |
| Policies d'autorisation Pot | `ReadPotAuthorizationPolicy`, `UpdatePotDetailsAuthorizationPolicy` | `domain-pot-policy` |
| Calcul Balance | `PotBalances`, `PotBalancesCalculator` | `domain-projection-balance` |
| Pipeline versionné | `PipelineId`, `PipelineDefinition` | `domain-pipeline` |
| Payload fonctionnel de tâche | `TaskPayload` | `domain-task` |
| Consommation durable générique | `ConsumptionKey`, `ConsumptionSlot`, `Claim`, `ClaimId` | `domain-consumption` |
| Événement enregistré | `RecordedEvent`, `EventTraceMetadata` | `engine-core` |
| Commande durable rejouable cible | `engine.command.model.RecordedCommand` | `engine-command` |
| Tâche durable enregistrée | `RecordedTask` | `engine-processing-task` |
| Description d'une tâche à créer | `TaskDescriptor` | `engine-core`, transitoire |
| Entrées et résultats de use case | `*Input`, `*Result` | engine qui expose le use case |
| État de projection Balance | `PotBalanceProjectionState` | `engine-projection` |
| État et entités persistés | `Jpa*Entity`, `Jpa*Status` | `infra-persistence-jpa` |
| Polling et capacité | `ConsumptionPollingWorker`, budgets de cycle | `supra-consumption-worker` |
| Exécution atomique et fencing | `TransactionalExecuteConsumptionUseCase`, `currentClaimId` | `engine-consumption` / infra transactionnelle |
| Orchestration pull Command | `CommandConsumptionLocator`, `SequentialConsumptionOrchestrator`, `ConsumptionPollingWorker` | locator/orchestrateur/supra génériques |
| Orchestration pull Event | `EventWorker`, `EventWorkerIteration` | `supra-worker-event` |
| Orchestration pull Task | `TaskConsumptionLocator`, `ConsumptionPollingWorker` | locator Task et supra générique |
| Spécialisation de consommation Command | `CommandConsumptionKeys`, `CommandConsumptionLocator`, `CommandConsumptionExecution` | `locator-consumption-command` |
| Identité externe déjà authentifiée | `AuthenticatedExternalPrincipal`, `ExternalIdentity` | `orchestrator-command-admission` |
| Adaptation du principal Spring | `SpringSecurityExternalPrincipalAdapter` | `supra-authentication-spring-security` |
| Exécution Task fonctionnelle | `TaskExecutionReport`, `BusinessObjectVersion`, `ProducedArtifactReference` | `engine-task-execution` |
| Projection Balance immuable | `BalanceProjectionIdentity`, `BalanceProjectionArtifact` | `pipeline-balance` |

## Distinctions obligatoires

### Événements

```text
BusinessEvent
  fait métier typé, sans identité d'outbox ni consommation

RecordedEvent
  événement enrichi de eventId, recordedAt et trace optionnelle

BusinessEventEnvelope (legacy)
  représentation sérialisée de l'ancien flux outbox
```

### Tâches

```text
TaskPayload
  travail fonctionnel typé reçu par un handler

TaskDescriptor
  instruction applicative de création d'une tâche durable

RecordedTask
  tâche durable relue par Task processing, sans claim

TaskExecutionReport
  rapport fonctionnel neutre, traduit en provenance par le locator
```

### Commandes et consommation

```text
Command != RecordedCommand
ConsumptionKey != objet consommé
Claim != statut durable de la Command ou de la Task
ClaimId != lease et Claim != journal d'exécution
```

Une intention exprime l'opération métier. Son enregistrement ajoute l'identité et l'ordre durable.
Une clé de consommation identifie une réservation logique. Le claim exprime uniquement la
propriété temporaire, protégée par son token.

Pour Command, `engine-command` reste propriétaire du décodage, du dispatch et de l'exécution
spécialisée. `locator-consumption-command` est seul propriétaire de la traduction en
`ConsumptionKey` (`COMMAND / [commandId]`, `COMMAND_PROCESSOR / []`) et en résultat de consommation.

Le `currentClaimId` du slot décide quelle transaction peut committer. Le CAS final est exécuté dans
la même transaction que les effets et la provenance. L'ancien `ExecutionGuard` Command a été retiré.

```text
Claim
  possession temporaire

ConsumptionSlot
  lifecycle autoritatif du processing

Colonnes lifecycle Task legacy
  audit temporaire, non consulté par le nouveau provider
```

La transition terminale du slot précède toujours la matérialisation. L'état temporaire
`slot COMPLETED/FAILED + durable READY/PENDING` est réparé par le processing engine propriétaire.

## Legacy restant

| Élément | Utilisateurs actuels | Remplacement cible | Condition de suppression | Étape future |
|---|---|---|---|---|
| `engine-task-materialization` | worker et adapters de matérialisation | EventWorker + task creation | consommations Event persistées par pipeline | Workers/infra Event |
| `BusinessEventEnvelope` | outbox et projection legacy | `RecordedEvent<BusinessEvent>` | EventPort et mapper durable opérationnels | Infra Event |
| `PotPartitioner` | workers de projection | `PartitionHash` | anciens workers retirés | Workers |
| `ProjectionPartition` | ports/workers de projection | `WorkerSegment` | anciens ports retirés | Workers |
| `BuildProjectionTasksUseCase` | projection legacy | task creation typée | EventWorker actif | Workers Event |
| `ExecuteProjectionTasksUseCase` | projection legacy | task execution typée | TaskWorker actif | Workers Task |
| `engine.model.*` de projection | adapters JPA et workers legacy | modèles processing/infra propriétaires | anciens ports outbox retirés | Workers/infra |
| statuts/claims de l'ancien outbox | repositories et dispatchers actuels | slots/claims génériques | adapter PostgreSQL `ClaimPort` actif | Infrastructure |
| colonnes lifecycle de `tasks_4_pipeline` | audit après cutover | `ConsumptionSlot`/`Claim` | lot ultérieur de nettoyage physique | Infrastructure |

Le legacy restant appartient aux flux Event, Task, projection et read. Le lifecycle et le worker
Command historiques ne sont plus compilés.
