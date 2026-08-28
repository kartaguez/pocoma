# Propriété des types

## Types cibles

| Concept | Type représentatif | Propriétaire |
|---|---|---|
| Pot et valeurs métier | `PotHeader`, `PotId`, `UserId` | `domain-pot` |
| Faits métier Pot | `BusinessEvent`, `PotCreatedEvent`, `ExpenseCreatedEvent` | `domain-pot.event` |
| Autorisation Pot | `ReadPotAuthorizationPolicy`, `Scope` | `domain-pot-policy` |
| Calcul Balance | `PotBalances`, `PotBalancesCalculator` | `domain-projection-balance` |
| Pipeline versionné | `PipelineId`, `PipelineDefinition` | `domain-pipeline` |
| Payload fonctionnel de tâche | `TaskPayload` | `domain-task` |
| Consommation durable générique | `ConsumptionKey`, `ConsumptionSlot`, `Claim`, `ClaimToken` | `domain-consumption` |
| Événement enregistré | `RecordedEvent`, `EventTraceMetadata` | `engine-core` |
| Commande durable rejouable | `RecordedCommand` | `engine-processing-command` |
| Tâche durable enregistrée | `RecordedTask` | `engine-processing-task` |
| Description d'une tâche à créer | `TaskDescriptor` | `engine-core`, transitoire |
| Entrées et résultats de use case | `*Input`, `*Result` | engine qui expose le use case |
| État de projection Balance | `PotBalanceProjectionState` | `engine-projection` |
| État et entités persistés | `Jpa*Entity`, `Jpa*Status` | `infra-persistence-jpa` |
| Polling et capacité | `ClaimableWorkDispatcher`, `SegmentedWorkerPool` | orchestrateur/worker |

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

LegacyPipelineTask
  représentation sérialisée et claimée de l'ancien worker
```

### Commandes et consommation

```text
CommandIntent != RecordedCommand
ConsumptionKey != objet consommé
Claim != statut durable de la Command ou de la Task
```

Une intention exprime l'opération métier. Son enregistrement ajoute l'identité et l'ordre durable.
Une clé de consommation identifie une réservation logique. Le claim exprime uniquement la
propriété temporaire, protégée par son token.

## Legacy restant

| Élément | Utilisateurs actuels | Remplacement cible | Condition de suppression | Étape future |
|---|---|---|---|---|
| `engine-task-materialization` | worker et adapters de matérialisation | EventWorker + task creation | consommations Event persistées par pipeline | Workers/infra Event |
| `engine.taskexecution.*` historique | Task worker et pont JSON | TaskWorker + `ExecuteTaskUseCase` typé | worker branché sur processing Task | Workers Task |
| `LegacyPipelineTask` | registre/stratégies et worker actuels | `RecordedTask` puis `TaskPayload` | mapper durable placé dans l'adaptateur entrant | Workers Task |
| `ConfiguredTaskExecutionBinding` | sélection du worker actuel | configuration du TaskWorker | nouvelle sélection pull opérationnelle | Workers Task |
| `BusinessEventEnvelope` | outbox et projection legacy | `RecordedEvent<BusinessEvent>` | EventPort et mapper durable opérationnels | Infra Event |
| `PotPartitioner` | workers de projection | `PartitionHash` | anciens workers retirés | Workers |
| `ProjectionPartition` | ports/workers de projection | `WorkerSegment` | anciens ports retirés | Workers |
| `BuildProjectionTasksUseCase` | projection legacy | task creation typée | EventWorker actif | Workers Event |
| `ExecuteProjectionTasksUseCase` | projection legacy | task execution typée | TaskWorker actif | Workers Task |
| `engine.model.*` de projection | adapters JPA et workers legacy | modèles processing/infra propriétaires | anciens ports outbox retirés | Workers/infra |
| statuts/claims de l'ancien outbox | repositories et dispatchers actuels | slots/claims génériques | adapter PostgreSQL `ClaimPort` actif | Infrastructure |
| trois dépendances JPA → supra allow-listées | sources de travail legacy | ports sortants des processing engines | nouveaux adapters disponibles | Infrastructure |

Le legacy reste compilable, mais les packages fonctionnels et les nouveaux processing engines ne
peuvent pas en dépendre.
