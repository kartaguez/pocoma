# EPT — legacy cleanup inventory

Ce document est le handoff factuel entre la clôture d'EPT et un futur step de nettoyage. Il ne
modifie pas le Canon EPT et n'autorise aucune suppression non vérifiée. Les migrations historiques
restent immuables ; toute suppression physique de table doit être portée par une nouvelle migration.

## Frontière canonique à préserver

```text
BusinessEvent
  → business_event_outbox
  → JdbcProjectionMaterializationDiscoveryAdapter (metadata-only)
  → ProjectionMaterializationPolicy
  → Consumption EVENT/[eventId]
       × PROJECTION_TASK_MATERIALIZER/[projectionType]
  → ProjectionTaskStore.ensure(ProjectionKey)
  → projection_tasks
  → Consumption PROJECTION_TASK/[projectionType,targetObjectType,targetObjectId,targetVersion]
       × PROJECTION_EXECUTOR/[projectionType]
  → ProjectionTaskConsumptionOrchestrator
  → ProjectionEngineService
  → pocoma_read.projection_root / projection_artifact
```

Les autorités transactionnelles à conserver sont `TransactionalAcquireConsumptionUseCase` et
`TransactionalFinalizeConsumptionUseCase`. La finalisation verrouille et fence le Claim courant
avant l'effet durable, puis committe cet effet avec Claim `SUCCESS` et Slot `DONE/SUCCESS`.

## DELETE

Ces groupes n'ont plus d'utilisateur autoritaire dans la chaîne canonique. Leur suppression devra
inclure code, tests exclusivement associés, dépendances Maven, propriétés et documentation.

### Scheduling Event pipeline/generation

- Le module `engine-task-creation` complet : plan/create/schedule, registries de relevance et de
  stratégies, contrats et transaction wrapper.
- Dans `locator-consumption-event`, `EventConsumptionLocator` et ses policies/classifiers legacy ;
  conserver le sous-package canonique `materialization`.
- `JpaEventConsumptionDiscoveryAdapter` et son repository, `JpaTaskCreationAdapter`, ainsi que les
  modèles/repositories exclusivement liés à `tasks_4_pipeline`.
- Les relevances et stratégies Event de `pipeline-balance` et `pipeline-pot`.
- `CanonicalProjectionTaskScheduler`, `MeteredProjectionTaskScheduler` et leurs tests : le premier
  est un ancien dual-write, mais aucune configuration Spring ne l'instancie encore.
- Les dépendances pipeline, lifecycle et task-creation du runtime Event qui ne subsisteront plus
  après extraction de ces classes. Le runtime Event canonique ne reçoit que `projection-types`.

### Exécution Task pipeline/generation

- `engine-processing-task`, `engine-task-execution` et `locator-consumption-task`, dédiés à la
  discovery/exécution de `tasks_4_pipeline`.
- `TaskConsumptionRuntimeConfiguration`, `TaskConsumptionProperties` et les anciens tests de
  binding/takeover pipeline. Le runtime canonique à conserver est
  `CanonicalProjectionTaskRuntimeConfiguration`.
- Les mappers, handlers, Task DTOs et failure policies de `pipeline-balance` / `pipeline-pot`.
- `JpaTaskConsumptionDiscoveryAdapter`, `JpaTaskPort`, les repositories et l'entité JPA de
  `tasks_4_pipeline`.
- `TransactionalExecuteConsumptionUseCase` et la provenance uniquement depuis ce chemin Task ; le
  moteur générique et la provenance eux-mêmes restent nécessaires ailleurs et ne doivent pas être
  supprimés globalement.

### Branches abandonnées ou sans runtime

- Le module `engine-task-materialization`, qui n'a aucun utilisateur de production en dehors de
  dépendances de build résiduelles.
- La branche `BuildProjectionTasksUseCase` / `ExecuteProjectionTasksUseCase`, ses événements Spring
  et les beans correspondants lorsqu'ils ne servent que `projection_tasks_legacy`. L'adapter,
  l'entité et le repository `JpaProjectionTask*` deviennent alors supprimables.
- Les tests, fixtures, runbooks et métriques qui n'observent que `tasks_4_pipeline` ou
  `projection_tasks_legacy`. Les preuves EPT doivent être conservées comme filet de sécurité.

## KEEP

- `domain-event`, les dix `PocomaEventTypes` et `business_event_outbox` : source durable EPT.
- `domain-projection`, `domain-pot-projection`, `engine-projection-task`,
  `engine-projection-balance`, `engine-projection-pot`, `engine-projection-read` et
  `engine-projection-contracts` : clés, définitions et producteurs canoniques.
- Dans `engine-processing-event`, la policy et les contrats/candidats/ordering keys
  `ProjectionMaterialization*`. Les anciens contrats Event de scheduling peuvent être retirés
  séparément.
- Dans `locator-consumption-event`, `ProjectionMaterializationConsumptionKeys`, `Source` et
  `Service`.
- `domain-consumption`, `engine-consumption`, `AcquireThenFinalizeConsumptionOrchestrator`,
  `ConsumptionPollingWorker` et les adapters de lifecycle : socle partagé des Consumptions Event,
  ProjectionTask, Command et Latest Known Version.
- `JdbcProjectionMaterializationDiscoveryAdapter`, `JdbcProjectionTaskStoreAdapter`, les loaders
  historiques nécessaires aux deux producteurs, et `JdbcProjectionStoreAdapter`.
- Les composition roots Event et ProjectionTask canoniques, leurs propriétés
  `pocoma.event-consumption.*` / `pocoma.projection-task-consumption.*`, et les services du
  `docker-compose.distributed.yml`.
- `consumption_inputs`, `consumption_results`, `JpaConsumptionProvenanceAdapter` et
  `TransactionalExecuteConsumptionUseCase` tant que Command et Latest Known Version les utilisent.
- Le runtime Latest Known Version et `EventPort` : fonctionnalité distincte, hors frontière EPT.

## VERIFY / MIGRATE FIRST

### Pipeline et serving hors chemin EPT

`domain-pipeline`, `engine-pipeline-lifecycle`, `infra-pipeline-lifecycle-persistence` et
`pocoma_control.*` ne sont pas requis par les composition roots EPT, mais restent référencés par
les sélections de serving, le code Query/read historique, des auto-configurations et les runtimes
transitionnels. Il faut d'abord décider la nouvelle sélection de projection servie et migrer les
consommateurs avant de supprimer ces concepts.

### Read/query historique

- Le Web API lit encore `balance_projection_artifacts` / `balance_projection_entries` via
  `JpaImmutablePotBalancesQueryAdapter`; le writer correspondant appartient à l'ancien runtime
  Task. La Query doit lire le store canonique avant suppression de ces tables.
- `domain-projection-legacy`, `engine-read-projection` et les anciennes tables du schéma
  `pocoma_read` restent utilisés par Latest Known Version, le listing/index Pot et les chemins
  Query historiques. Séparer les types encore légitimes des matérialisations pipeline avant de
  supprimer le module ou le schéma associé.
- `ProjectionUseCaseConfiguration` du shared runtime crée encore des beans de l'ancien moteur de
  projection. Ils n'ont pas d'orchestrateur Event/Task dans la topologie distribuée, mais doivent
  être retirés avec leurs dépendances et leurs métriques pour éviter de conserver un graphe mort.

### Monolithe transitionnel

`runtime-monolith` et `supra-worker-balance-calculation-events-spring` restent déployables pour les
expériences locales décrites par le README. Ils projettent depuis des événements Spring en mémoire,
pas depuis `business_event_outbox`, et ne constituent donc pas une seconde autorité durable EPT.
Ils bloquent néanmoins la suppression globale de l'ancien moteur Balance. Décider explicitement de
les retirer ou de les migrer vers les runtimes canoniques avant de supprimer leurs ports, tables et
documentation.

### Configuration et documentation

- Les commandes README du worker Task utilisent encore les propriétés
  `pocoma.task-consumption.*`; les remplacer par `pocoma.projection-task-consumption.*` lors du
  nettoyage.
- Réauditer les runbooks `event-consumption-*` / `task-consumption-*`, les dashboards et les
  métriques pipeline avant leur suppression : certains noms legacy peuvent encore alimenter des
  procédures opérationnelles même sans autorité runtime.

## Tables et migrations

| Table | Migration d'origine | Writers / readers actuels | Présence canonique | Décision |
|---|---|---|---|---|
| `business_event_outbox` | monolith `V2`, canonisée par `V15` | write side et discovery EPT ; readers Latest Known Version | source EPT | KEEP |
| `consumption_slots`, `consumption_claims` | `V4`, enrichies par `V6`/`V7` | lifecycle partagé par tous les workers | autorité de fencing | KEEP |
| `consumption_inputs`, `consumption_results` | `V4` | Command, Latest Known Version et Task legacy | absentes du chemin EPT, mais actives ailleurs | KEEP |
| `projection_tasks` | `V13` | `JdbcProjectionTaskStoreAdapter` en lecture/écriture | queue canonique | KEEP |
| `pocoma_read.projection_root`, `projection_artifact`, `projection_failure` | read-store `V7` | `JdbcProjectionStoreAdapter` | store canonique | KEEP |
| `tasks_4_pipeline` | monolith `V3`, modifiée par `V5`/`V10` | writer `JpaTaskCreationAdapter`; readers discovery/Task legacy | aucune lecture/écriture canonique | DELETE dans un lot DB séparé |
| `projection_tasks_legacy` | monolith `V2`, renommée par `V13` | adapter/repository `JpaProjectionTask*`; aucun orchestrateur distribué actif | aucune | DELETE dans un lot DB séparé |
| `balance_projection_artifacts`, `balance_projection_entries` | `V5` | writer Task legacy, reader Web API actif ; FK entries → artifacts, artifacts → `pot_global_versions` | non | MIGRATE FIRST |
| `pocoma_control.pipeline_version_activations`, `projection_serving_selections` | `V12` | `JdbcPipelineLifecycleAdapter`; FK selection → activation | non | VERIFY / MIGRATE FIRST |
| `pocoma_read.projection_artifacts`, `projection_failures`, `projection_heads`, `projection_invariant_violations` | read-store `V2`/`V3` | metadata/materialization legacy | non | VERIFY / MIGRATE FIRST |
| `pocoma_read.source_version_watermarks` | read-store `V4` | Latest Known Version et listing Pot | hors EPT, actif | KEEP jusqu'à migration dédiée |
| `pocoma_read.pot_projection_snapshots` et tables de fragments/index | read-store `V5`/`V6` | writer/readers Pot et Query historiques ; plusieurs FK sur `artifact_id` | non | VERIFY / MIGRATE FIRST |

`event_4_pipeline_materialization_status` est uniquement historique : créée par monolith `V3`, elle
a déjà été absorbée puis supprimée par `V10`. Aucune migration existante ne doit être effacée.

## Dépendances croisées et risques

- Le module canonique `engine-processing-event` dépend encore de `domain-pipeline` parce qu'il
  contient aussi les anciens contrats Event ; séparer ces sources avant de retirer la dépendance.
- `locator-consumption-event` contient à la fois le locator legacy et le sous-package canonique :
  ne pas supprimer le module en bloc avant extraction ou nettoyage chirurgical.
- Les runtimes Event et Task déclarent encore des modules pipeline dans leurs POM, même si leurs
  composition roots actifs n'en dépendent pas. Retirer ces dépendances après le code legacy.
- Les adapters legacy annotés `@Component` peuvent rester instanciés sans caller. Supprimer d'abord
  leurs consommateurs/configurations, puis vérifier le bean graph avant de retirer les tables.
- La Query Balance ne lit pas encore `pocoma_read.projection_root/projection_artifact`; supprimer
  l'ancien store auparavant casserait une fonctionnalité hors EPT.
- Les anciennes lignes et Consumptions doivent être inventoriées avant drop. L'absence d'autorité
  runtime ne prouve pas qu'aucune donnée historique n'a besoin d'archivage ou de migration.

## Ordre de destruction recommandé

1. Ajouter des tests de non-régression du bean graph et figer les preuves EPT comme garde-fous.
2. Retirer le scheduling Event pipeline/generation et les dépendances Maven devenues orphelines.
3. Retirer l'exécution `tasks_4_pipeline`, son runtime conditionnel, ses handlers et adapters.
4. Retirer la branche `projection_tasks_legacy` et les anciens événements/use cases sans caller.
5. Migrer le Web API et les autres readers vers le store canonique ; décider du sort du monolithe.
6. Détacher lifecycle/serving, projection legacy, Latest Known Version et provenance uniquement
   lorsque leurs derniers usages légitimes ont été remplacés.
7. Supprimer les tests, propriétés, runbooks, dashboards et modules devenus exclusivement legacy.
8. Dans un lot DB séparé, mesurer les données, traiter compatibilité/archivage, puis ajouter de
   nouvelles migrations de drop dans l'ordre FK. Ne jamais supprimer les migrations historiques.
9. Après chaque lot, rejouer les preuves EPT PostgreSQL et le reactor complet.
