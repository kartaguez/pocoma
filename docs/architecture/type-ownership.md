# Propriété des types

## Types cibles

| Concept | Type représentatif | Propriétaire |
|---|---|---|
| Pot et valeurs métier | `PotHeader`, `PotId`, `UserId` | `domain-pot` |
| Faits métier Pot | `BusinessEvent`, `PotCreatedEvent`, `ExpenseCreatedEvent` | `domain-pot.event` |
| Autorisation générique | `Permission` | `domain-authorization` |
| Policies d'autorisation Pot | `UpdatePotDetailsAuthorizationPolicy` et autres policies Command | `domain-pot-policy` |
| Calcul Balance | `PotBalances`, `PotBalancesCalculator` | `domain-projection-balance` |
| Projection générique | `ProjectionIdentity`, `ProjectionArtifactDescriptor`, `ProjectionFailure`, `ProjectionHead`, `ProjectionStatus` | `domain-projection` |
| Connaissance de version | `LatestKnownVersion`, avance monotone et port de persistance | `engine-read-projection` |
| Projection Pot canonique | `PotProjection` et ses composants logiques | `domain-projection` |
| Lecture exacte de projection | `ExactProjectionReadUseCase`, `ExactProjectionReadService`, `ProjectionReadPort` | `engine-projection-read` |
| Projection AUTH read-side | `AuthProjectionDefinition`, `AuthProjectionInput`, loader exact et `AuthProjector` | `domain-pot-projection`, `engine-projection-pot`, adapter JPA en infrastructure |
| Consommation durable générique | `ConsumptionKey`, `ConsumptionSlot`, `Claim`, `ClaimId` | `domain-consumption` |
| Événement enregistré | `RecordedEvent`, `EventTraceMetadata` | `engine-core` |
| Commande durable rejouable cible | `engine.command.model.RecordedCommand` | `engine-command` |
| Entrées et résultats de use case | `*Input`, `*Result` | engine qui expose le use case |
| État et entités persistés | `Jpa*Entity`, `Jpa*Status` | `infra-persistence-jpa` |
| Polling et capacité | `ConsumptionPollingWorker`, budgets de cycle | `supra-consumption-worker` |
| Exécution atomique et fencing | `TransactionalExecuteConsumptionUseCase`, `currentClaimId` | `engine-consumption` / infra transactionnelle |
| Précondition générique d'acquisition | `ConsumptionAcquisitionPrecondition`, évaluée avant toute mutation Slot/Claim | `engine-consumption` |
| Orchestration pull Command | `CommandConsumptionLocator`, `SequentialConsumptionOrchestrator`, `ConsumptionPollingWorker` | locator/orchestrateur/supra génériques |
| Orchestration pull Event | `EventWorker`, `EventWorkerIteration` | `supra-worker-event` |
| Orchestration ProjectionTask | `ProjectionTaskConsumptionOrchestrator`, `ConsumptionPollingWorker` | orchestrator/supra génériques |
| Spécialisation de consommation Command | `CommandConsumptionKeys`, `CommandConsumptionLocator`, `CommandConsumptionExecution` | `locator-consumption-command` |
| Identité externe déjà authentifiée | `AuthenticatedExternalPrincipal`, `ExternalIdentity` | `orchestrator-command-admission` |
| Adaptation du principal Spring | `SpringSecurityExternalPrincipalAdapter` | `supra-authentication-spring-security` |

## Distinctions obligatoires

### Événements

```text
BusinessEvent
  fait métier typé, sans identité d'outbox ni consommation

RecordedEvent
  événement enrichi de eventId, recordedAt et trace optionnelle

BusinessEventEnvelope
  représentation durable sérialisée de l'Event enregistré dans business_event_outbox
```

### ProjectionTasks

```text
ProjectionKey
  identité exacte projectionType/targetObjectType/targetObjectId/targetVersion

ProjectionTask
  travail durable canonique relu depuis projection_tasks
```

### Commandes et consommation

```text
Command != RecordedCommand
ConsumptionKey != objet consommé
Claim != statut durable de la Command ou de la ProjectionTask
ClaimId != lease
Claim = tentative durable d'acquisition, pas provenance ni rapport métier générique
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

ProjectionTask Consumption
  PROJECTION_TASK/[ProjectionKey] × PROJECTION_EXECUTOR/[projectionType]
```

Les effets métier, la provenance et le CAS terminal gagnant appartiennent à la même transaction
d'exécution. Ils deviennent visibles atomiquement au commit ; aucun ordre de visibilité intermédiaire
n'est un contrat fonctionnel.

## Legacy SQL clôturé

| Élément | Utilisateurs actuels | Remplacement cible | État final | Lot |
|---|---|---|---|---|
| table et colonnes `tasks_4_pipeline` | migrations historiques uniquement | aucune capacité runtime | absentes du schéma final depuis V16 | PCL.8 `DONE` |
| colonnes lifecycle de `business_event_outbox` | append durable initialise encore `status`/`attempt_count` ; aucun lifecycle runtime | Consumption générique | conservées dans la table KEEP ; aucune responsabilité legacy active | PCL clôturé |
| tables historiques `pot_balance_*` | migrations historiques uniquement | store canonique `POT_BALANCES` | absentes du schéma final depuis V16 | PCL.8 `DONE` |

Le legacy Query/read exécutable a été retiré par PCL.4. Le legacy applicatif des anciens flux de
projection a été retiré par PCL.5 à PCL.7, puis les structures SQL legacy restantes par V16/V8 en
PCL.8.

Le modèle pipeline/generation/lifecycle/serving et ses modules ont été retirés par PCL.5. La
migration historique V12 reste append-only sous la propriété de `infra-persistence-jpa`; ses tables
lifecycle sont absentes du schéma final depuis V16.
