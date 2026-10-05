# WP2.1 — Ownership / Namespace / Current Binding Port Cleanup

## Baseline et périmètre

- Branche : `v2-make-it-pull`.
- HEAD initial local/distant après `git fetch origin` : `cfdaad6624c7934f12e47f371f71f49a4c36d244` ; divergence `0/0`, tree propre, aucun commit depuis WP2.
- Slice primaire déclaré avant le code : PROJECTION (`runtime-task-consumption-worker`). Secondaires : BINDING, WEB, EVENT, LKV. Gate architecture et réacteur complet requis par la restructuration Maven transversale et la demande explicite. DB : tests Postgres des slices et gate ; aucun SQL, schéma ni migration modifié.
- WP3 non commencé. `TBD-E2U`, `TBD-LKV`, `TBD-COMMAND-CONTRACT` restent ouverts ; resolver E→U PRIMARY et hébergement LKV provisoire conservés.

## Correction des deux noms techniques

| Ancien POM | Nouveau POM | Preuve d’implémentation |
| --- | --- | --- |
| `infra-persistence-projection-jpa` | `infra-persistence-projection-jdbc` | `spring-boot-starter-jdbc`, `JdbcProjectionStoreAdapter` |
| `infra-persistence-read-jpa` | `infra-persistence-read-jdbc` | `spring-boot-starter-jdbc`, `JdbcCurrentBindingAdapter` |

Convention : `infra-<responsibility>-<technology>`. Le POM `infra-persistence-primary-jpa` n’est pas renommé.

## Current Binding

`CurrentBindingProjectionPort` regroupait `apply` et `find` dans un contrat hébergé par le domaine, alors que les deux opérations servent deux engines distincts. Le mot Projection suggérait aussi la famille de projection historique exacte `@V`, bien que CURRENT_BINDING soit une vue courante mutable issue directement des Binding facts. Les quatre types de modèle/invariant (`CurrentBinding`, `CurrentBindingStatus`, `CurrentBindingApplyResult`, `CurrentBindingInvariantException`) appartiennent désormais à `domain-user-identity` sous `currentbinding`.

- `engine-materialize-current-binding` possède `CurrentBindingWritePort.apply`.
- `engine-read-current-binding` possède `CurrentBindingReadPort.find`.
- `infra-persistence-read-jdbc` fournit un seul `JdbcCurrentBindingAdapter` aux deux ports. Sa lecture interne pour duplicate/divergence demeure dans l’adapter.
- `port-projection` reste le port de la projection historique exacte `@V` et du travail de matérialisation associé. Les moteurs Current Binding ne l’importent pas.
- Invariants inchangés : facts append-only, ordre de révision selon garanties existantes, même révision/même payload = duplicate, même révision/payload différent = violation, révision antérieure = stale, tombstone DETACHED, fact Binding directement vers CURRENT_BINDING, sans ProjectionTask.

## Inventaire et déplacements Java

Avant modification, les 21 POM structurants demandés ont été scannés par package de chaque source Java. Les classes déjà cohérentes étaient dans `orchestrator-poll-consumption`, `contracts-registration`, `port-binding-authority`, `port-transaction` et `supra-consume-projection-task`. Le scan WP1 complémentaire a confirmé `domain-consumption`, `engine-consumption`, `orchestrator-consumption` et `infra-tx-spring` cohérents avec leur responsabilité. Les 74 déplacements suivants corrigent les autres packages contradictoires ; les quatre types modèle Current Binding sont inclus et l’ancien port est retiré.

| Class/type | Ancien package | Nouveau package | POM owner |
| --- | --- | --- | --- |
| `CurrentBinding` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.domain.useridentity.currentbinding` | `domain-user-identity` |
| `CurrentBindingApplyResult` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.domain.useridentity.currentbinding` | `domain-user-identity` |
| `CurrentBindingInvariantException` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.domain.useridentity.currentbinding` | `domain-user-identity` |
| `CurrentBindingStatus` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.domain.useridentity.currentbinding` | `domain-user-identity` |
| `AuthProjectionDefinition` | `com.kartaguez.pocoma.domain.pot.projection.definition` | `com.kartaguez.pocoma.domain.projection.pot.definition` | `domain-projection` |
| `ReadPotProjectionDefinition` | `com.kartaguez.pocoma.domain.pot.projection.definition` | `com.kartaguez.pocoma.domain.projection.pot.definition` | `domain-projection` |
| `PotBalancesProjectionDefinition` | `com.kartaguez.pocoma.domain.pot.projection.definition` | `com.kartaguez.pocoma.domain.projection.pot.definition` | `domain-projection` |
| `PotProjectionSchemas` | `com.kartaguez.pocoma.domain.pot.projection.definition` | `com.kartaguez.pocoma.domain.projection.pot.definition` | `domain-projection` |
| `ProjectionProjector` | `com.kartaguez.pocoma.engine.projection.task.engine` | `com.kartaguez.pocoma.domain.projection.projector` | `domain-projection` |
| `PotBalancesProjector` | `com.kartaguez.pocoma.engine.projection.balance` | `com.kartaguez.pocoma.projector.pot.balance` | `projector-pot` |
| `AuthProjector` | `com.kartaguez.pocoma.engine.projection.pot` | `com.kartaguez.pocoma.projector.pot` | `projector-pot` |
| `ReadPotProjector` | `com.kartaguez.pocoma.engine.projection.pot` | `com.kartaguez.pocoma.projector.pot` | `projector-pot` |
| `ReadPotProjectionInput` | `com.kartaguez.pocoma.engine.projection.pot` | `com.kartaguez.pocoma.projector.pot` | `projector-pot` |
| `AuthProjectionInput` | `com.kartaguez.pocoma.engine.projection.pot` | `com.kartaguez.pocoma.projector.pot` | `projector-pot` |
| `ProjectorDeterminismTest` | `com.kartaguez.pocoma.engine.projection.pot` | `com.kartaguez.pocoma.projector.pot` | `projector-pot` |
| `ProjectionMaterializationDiscoveryPort` | `com.kartaguez.pocoma.engine.port.out.processing.event` | `com.kartaguez.pocoma.engine.produce.projectiontask.port` | `engine-produce-projection-task` |
| `ProjectionMaterializationCandidate` | `com.kartaguez.pocoma.engine.port.out.processing.event` | `com.kartaguez.pocoma.engine.produce.projectiontask.port` | `engine-produce-projection-task` |
| `ProjectionMaterializationPolicy` | `com.kartaguez.pocoma.engine.processing.event.materialization` | `com.kartaguez.pocoma.engine.produce.projectiontask.materialization` | `engine-produce-projection-task` |
| `ProduceProjectionTaskService` | `com.kartaguez.pocoma.engine.processing.event.materialization` | `com.kartaguez.pocoma.engine.produce.projectiontask.materialization` | `engine-produce-projection-task` |
| `PocomaProjectionMaterializationPolicy` | `com.kartaguez.pocoma.engine.processing.event.materialization` | `com.kartaguez.pocoma.engine.produce.projectiontask.materialization` | `engine-produce-projection-task` |
| `ProjectionMaterializationOrderingKey` | `com.kartaguez.pocoma.engine.processing.event.ordering` | `com.kartaguez.pocoma.engine.produce.projectiontask.ordering` | `engine-produce-projection-task` |
| `ProjectionTaskConsumptionService` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.engine.consume.projectiontask` | `engine-consume-projection-task` |
| `ProjectionTaskKeys` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.engine.consume.projectiontask` | `engine-consume-projection-task` |
| `TerminalProjectionPreparationException` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.engine.consume.projectiontask` | `engine-consume-projection-task` |
| `ProjectionPreparationInvariantViolationException` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.engine.consume.projectiontask` | `engine-consume-projection-task` |
| `ProjectionTaskRetryPolicy` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.engine.consume.projectiontask` | `engine-consume-projection-task` |
| `TemporaryProjectionPreparationException` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.engine.consume.projectiontask` | `engine-consume-projection-task` |
| `ProjectionPreparationOutcome` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.engine.consume.projectiontask` | `engine-consume-projection-task` |
| `ProjectionTaskExecutionResult` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.engine.consume.projectiontask` | `engine-consume-projection-task` |
| `ProjectionTaskConsumptionOrchestrator` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.engine.consume.projectiontask` | `engine-consume-projection-task` |
| `ProjectionProducerCatalog` | `com.kartaguez.pocoma.engine.projection.task.engine` | `com.kartaguez.pocoma.engine.consume.projectiontask.engine` | `engine-consume-projection-task` |
| `ProjectionProducerDeclaration` | `com.kartaguez.pocoma.engine.projection.task.engine` | `com.kartaguez.pocoma.engine.consume.projectiontask.engine` | `engine-consume-projection-task` |
| `ProjectionEngineService` | `com.kartaguez.pocoma.engine.projection.task.engine` | `com.kartaguez.pocoma.engine.consume.projectiontask.engine` | `engine-consume-projection-task` |
| `ExecuteProjectionTaskUseCase` | `com.kartaguez.pocoma.engine.projection.task.engine` | `com.kartaguez.pocoma.engine.consume.projectiontask.engine` | `engine-consume-projection-task` |
| `ProjectionInputLoader` | `com.kartaguez.pocoma.engine.projection.task.engine` | `com.kartaguez.pocoma.engine.consume.projectiontask.engine` | `engine-consume-projection-task` |
| `HistoricalPotReconstructionException` | `com.kartaguez.pocoma.engine.read.projection` | `com.kartaguez.pocoma.engine.consume.projectiontask.historical` | `engine-consume-projection-task` |
| `HistoricalPotSnapshotSource` | `com.kartaguez.pocoma.engine.read.projection` | `com.kartaguez.pocoma.engine.consume.projectiontask.historical` | `engine-consume-projection-task` |
| `MaterializeCurrentBindingService` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.engine.materialize.currentbinding` | `engine-materialize-current-binding` |
| `BindingFactCursor` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.engine.materialize.currentbinding` | `engine-materialize-current-binding` |
| `BindingFactCandidate` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.engine.materialize.currentbinding` | `engine-materialize-current-binding` |
| `BindingFactReadPort` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.engine.materialize.currentbinding` | `engine-materialize-current-binding` |
| `BindingFactDiscoveryPort` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.engine.materialize.currentbinding` | `engine-materialize-current-binding` |
| `GetCurrentBindingUseCase` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.engine.read.currentbinding` | `engine-read-current-binding` |
| `GetCurrentBindingService` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.engine.read.currentbinding` | `engine-read-current-binding` |
| `GetCurrentBindingServiceTest` | `com.kartaguez.pocoma.engine.read.binding` | `com.kartaguez.pocoma.engine.read.currentbinding` | `engine-read-current-binding` |
| `StoredProjectionInvariantViolationException` | `com.kartaguez.pocoma.engine.exception.projection.read` | `com.kartaguez.pocoma.engine.read.projection.exception` | `engine-read-projection` |
| `ExactProjectionReadUseCase` | `com.kartaguez.pocoma.engine.port.in.projection.read` | `com.kartaguez.pocoma.engine.read.projection.port` | `engine-read-projection` |
| `ProjectionReadResult` | `com.kartaguez.pocoma.engine.port.in.projection.read` | `com.kartaguez.pocoma.engine.read.projection.port` | `engine-read-projection` |
| `ExactProjectionReadService` | `com.kartaguez.pocoma.engine.service.projection.read` | `com.kartaguez.pocoma.engine.read.projection.service` | `engine-read-projection` |
| `ExactProjectionReads` | `com.kartaguez.pocoma.engine.service.projection.read` | `com.kartaguez.pocoma.engine.read.projection.service` | `engine-read-projection` |
| `ExactProjectionReadServiceTest` | `com.kartaguez.pocoma.engine.service.projection.read` | `com.kartaguez.pocoma.engine.read.projection.service` | `engine-read-projection` |
| `ProjectionMaterializationConsumptionKeys` | `com.kartaguez.pocoma.locator.consumption.event.materialization` | `com.kartaguez.pocoma.supra.consume.event.materialization` | `supra-consume-event` |
| `ProjectionMaterializationConsumptionSource` | `com.kartaguez.pocoma.locator.consumption.event.materialization` | `com.kartaguez.pocoma.supra.consume.event.materialization` | `supra-consume-event` |
| `ProjectionMaterializationConsumptionService` | `com.kartaguez.pocoma.locator.consumption.event.materialization` | `com.kartaguez.pocoma.supra.consume.event.materialization` | `supra-consume-event` |
| `BindingFactConsumptionLocator` | `com.kartaguez.pocoma.locator.consumption.binding` | `com.kartaguez.pocoma.supra.consume.binding` | `supra-consume-binding` |
| `BindingFactNotFoundException` | `com.kartaguez.pocoma.locator.consumption.binding` | `com.kartaguez.pocoma.supra.consume.binding` | `supra-consume-binding` |
| `BindingFactFailurePolicy` | `com.kartaguez.pocoma.locator.consumption.binding` | `com.kartaguez.pocoma.supra.consume.binding` | `supra-consume-binding` |
| `BindingFactFailureClassifier` | `com.kartaguez.pocoma.locator.consumption.binding` | `com.kartaguez.pocoma.supra.consume.binding` | `supra-consume-binding` |
| `ProjectionFailureIdConflictException` | `com.kartaguez.pocoma.infra.projection.persistence` | `com.kartaguez.pocoma.infra.persistence.projection.jdbc` | `infra-persistence-projection-jdbc` |
| `ProjectionStoreProperties` | `com.kartaguez.pocoma.infra.projection.persistence` | `com.kartaguez.pocoma.infra.persistence.projection.jdbc` | `infra-persistence-projection-jdbc` |
| `JsonValueCodec` | `com.kartaguez.pocoma.infra.projection.persistence` | `com.kartaguez.pocoma.infra.persistence.projection.jdbc` | `infra-persistence-projection-jdbc` |
| `JdbcProjectionStoreAdapter` | `com.kartaguez.pocoma.infra.projection.persistence` | `com.kartaguez.pocoma.infra.persistence.projection.jdbc` | `infra-persistence-projection-jdbc` |
| `ProjectionStoreAutoConfiguration` | `com.kartaguez.pocoma.infra.projection.persistence` | `com.kartaguez.pocoma.infra.persistence.projection.jdbc` | `infra-persistence-projection-jdbc` |
| `JsonValueCodecTest` | `com.kartaguez.pocoma.infra.projection.persistence` | `com.kartaguez.pocoma.infra.persistence.projection.jdbc` | `infra-persistence-projection-jdbc` |
| `JdbcCurrentBindingAdapter` | `com.kartaguez.pocoma.infra.read.persistence` | `com.kartaguez.pocoma.infra.persistence.read.jdbc` | `infra-persistence-read-jdbc` |
| `NetworkntJsonSchemaValidator` | `com.kartaguez.pocoma.infra.projection.jsonschema` | `com.kartaguez.pocoma.infra.projection.validation.networknt` | `infra-projection-validation-networknt` |
| `NetworkntJsonSchemaValidatorTest` | `com.kartaguez.pocoma.infra.projection.jsonschema` | `com.kartaguez.pocoma.infra.projection.validation.networknt` | `infra-projection-validation-networknt` |
| `AuthenticatedExternalPrincipal` | `com.kartaguez.pocoma.authentication` | `com.kartaguez.pocoma.contracts.authentication` | `contracts-authentication` |
| `TraceContextHolder` | `com.kartaguez.pocoma.observability.trace` | `com.kartaguez.pocoma.contracts.observability.trace` | `contracts-observability` |
| `TraceContext` | `com.kartaguez.pocoma.observability.trace` | `com.kartaguez.pocoma.contracts.observability.trace` | `contracts-observability` |
| `TraceContextHolderTest` | `com.kartaguez.pocoma.observability.trace` | `com.kartaguez.pocoma.contracts.observability.trace` | `contracts-observability` |
| `ProjectionTaskStorePort` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.port.projection.task` | `port-projection` |
| `ProjectionTask` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.port.projection.task` | `port-projection` |
| `ProjectionTaskCandidate` | `com.kartaguez.pocoma.engine.projection.task` | `com.kartaguez.pocoma.port.projection.task` | `port-projection` |

## Architecture, vérification et contrôles

- Nouveaux gardes : POM ↔ racine de namespace sur les 21 frontières structurantes ; `projector-pot` sans Spring/JPA/SQL/engine/infra/runtime ; `engine-read-current-binding` sans `port-binding-authority` ni `port-projection` ; `engine-materialize-current-binding` sans `port-projection`. Les anciennes règles ArchUnit ont été recalées sur les packages réellement déplacés.
- Anciennes classes en namespaces legacy hors scope : `infra-read-persistence` conserve migrations et LKV provisoire sous `infra.read.persistence` ; les tests des POM facades `locator-consumption-event` gardent leur ancien package de test ; `engine-processing-event` conserve le LKV provisoire. Aucun de ces éléments n’est un package d’un POM ferme WP1/WP2 corrigé.
- Contrôles statiques dans `app/` hors `target` : anciens artifactIds `infra-persistence-projection-jpa` = 0, `infra-persistence-read-jpa` = 0 ; `CurrentBindingProjectionPort` = 0 ; packages `domain-*` sous `engine.*`, `supra-*` sous `locator.*` et `projector-*` sous `engine.*` dans le périmètre WP1/WP2 = 0 ; artifactIds Maven dupliqués = 0 ; cycles Maven = 0 ; nouveau bridge TARGET → legacy = 0. La TARGET conserve 58 responsabilités logiques, 54 POM fermes, 1 `PACKAGE_ONLY`, 3 `TBD_PHYSICAL`.
- Aucune modification de schéma, sémantique SQL ou fichier de migration. Les fixtures Postgres existantes de Projection, Binding et des autres slices ont été exécutées via Testcontainers ; aucune preuve de migration historique supplémentaire propre à WP2.1 n’était requise. La politique indique qu’aucune baseline DB n’est encore `TRUSTED`.

Commandes Maven exécutées depuis `app/` :

1. `./mvnw -pl runtime-task-consumption-worker,runtime-binding-consumption-worker,runtime-web-api,runtime-event-consumption-worker,runtime-latest-known-version-consumption-worker -am test -DskipTests` : compilation verte pendant le rehome.
2. `./mvnw -pl architecture-tests -am test -Dtest=HexagonalArchitectureTest,Wp2BoundaryTest -Dsurefire.failIfNoSpecifiedTests=false` : vert après mise à jour des règles.
3. `./mvnw -pl runtime-task-consumption-worker,runtime-binding-consumption-worker,runtime-web-api,runtime-event-consumption-worker,runtime-latest-known-version-consumption-worker -am test` : vert avec Docker.
4. `./mvnw -pl runtime-command-consumption-worker -am test` : vert avec Docker.
5. `./mvnw -pl architecture-tests -am test` : vert avec Docker.
6. `./mvnw test` : vert avec Docker, réacteur complet demandé pour la restructuration Maven transversale.
7. `./mvnw -pl architecture-tests -am test -DskipTests` : compilation verte après harmonisation finale des blancs d’import.

Le premier essai du gate architecture en sandbox échouait faute d’accès Docker. Son premier essai avec Docker a relevé neuf règles dépendant des anciens noms de packages ; elles ont été corrigées avant le gate vert. Le franchissement secondaire COMMAND n’était pas dans la déclaration initiale ; il a été ajouté après l’inventaire des imports Authentication et vérifié par sa slice canonique. Aucun autre franchissement de production n’a été découvert.

## Livraison

Le commit de ce checkpoint est livré sur `v2-make-it-pull`. Le hash, le push, la divergence finale et l’état du working tree sont consignés dans le handoff final.
