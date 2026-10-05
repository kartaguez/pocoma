# WP1 — rapport de travail (baseline `9d71a2d9`)

## Inventaire pré-implémentation au HEAD initial

- Git : local et `origin/v2-make-it-pull` = `9d71a2d98d49ccd5f698284e0f4e4be5562b09ae`, divergence `0/0`, arbre propre, aucun commit depuis `9d71a2d9`.
- `authentication-contracts` : seul type `AuthenticatedExternalPrincipal` (`com.kartaguez.pocoma.authentication`), dépend de `domain-user-identity` pour `ExternalIdentity`. Aucun Spring Security/JWT.
- `observability` : `TraceContext`, `TraceContextHolder` (`com.kartaguez.pocoma.observability.trace`), aucun import engine.
- `engine-registration` : `RegistrationRequest` (record), `RegistrationOutcome` (sealed interface avec `Registered` et `Rejected`) sont les deux types Request/Outcome à extraire ; restent aussi `AdmitRegistrationService`, `ExecuteRegistrationService`, `GetRegistrationResultService`, `MaterializeRegistrationResultService`, `ImmutableRegistrationResult`, `RegistrationRequestStore`, `RegistrationOutcomeStore`, `RegistrationResultStore`, `UserCreatedFactPort`.
- `TransactionRunner` : interface dans `engine-core`, package `engine.port.out.transaction`, utilisée par `engine-consumption` (quatre wrappers transactionnels), `engine-registration` admission, infra Spring, HTTP read, locators, configurations des workers et tests. Son implémentation `SpringTransactionRunner` et sa configuration restent dans `infra-tx-spring`.
- Binding authority : `ExternalIdentityBindingPort`, `ExternalIdentityBindingStreamPort`, `ExternalIdentityBindingFactPort`, `UserAuthorityPort` dans `domain-user-identity`. `ExternalIdentityResolverPort` est le port E→U historique distinct, non décidé dans WP1 ; ne pas le déplacer par accident.
- Projection : `ProjectionReadPort`, `ProjectionWritePort`, `ProjectionPublicationResult` dans `engine-projection-contracts`, dépendance `domain-projection` seulement.
- `WorkerSegment` et `PartitionHash` : records dans `engine-core`, package `engine.processing.segmentation` ; utilisés par workers, locators Event/LKV, discovery JPA et tests. `PartitionHash` calcule uniquement une partition à partir des identifiants Pot/pipeline ; `WorkerSegment` valide index/count et attribue une partition.
- Autres valeurs `engine-core` : `RecordedEvent`, `EventTraceMetadata`, `BusinessEventEnvelope`, `PotGlobalVersion`, `UserContext`, quatre snapshots Pot/Expense, deux exceptions métier, `PotPartitioner` legacy. Leur rôle doit être examiné, mais aucune n'est requise pour éliminer l'import `engine-core` du Consumption générique ; elles restent en place dans WP1.
- `domain-consumption` : key (`ConsumableIdentity`, `ConsumerIdentity`, `ConsumptionKey`), claim/slot/lease (`Claim`, `ClaimId`, `ClaimToken`, `ClaimLease`, `ClaimEndReason`, `ConsumptionSlot`, `WorkerId`), lifecycle (`ConsumptionOutcome`, `ConsumptionStatus`, `ProcessingFailure`, `ProcessingFailureCode`, `TerminalOutcome`, `TerminalReason`), provenance (`ConsumptionInput`, `ConsumptionResult`). Aucun import engine.
- `engine-consumption` : ports/use cases Acquire, Execute, Finalize, HandleFailure ; services correspondants, politique d'échec et résultats génériques, exceptions `LostClaimException` et `MissingTerminalConsumptionFailureException` ; quatre wrappers transactionnels importent `TransactionRunner` d'`engine-core`. Aucun type capability-specific constaté dans ses sources.
- `orchestrator-consumption` : `SequentialConsumptionOrchestrator`, `AcquireThenFinalizeConsumptionOrchestrator`, modèles budget/résultat, interfaces locator/fenced génériques ; `ProjectionTaskConsumptionOrchestrator` est une spécialisation existante qui importe `engine-projection-task` et devra sortir avec WP2/D.08. Aucun import direct de `engine-core`.
- `supra-consumption-worker` : `ConsumptionPollingWorker`, `ConsumptionPollingWorkerObservation`, `ConsumptionWorkerSettings`, `NoopConsumptionPollingWorkerObservation`, `ConsumptionWaiter`, `ConditionConsumptionWaiter`. Dépend seulement de `orchestrator-consumption` ; aucun import engine-core ou capacité métier.

## Vérification déclarée avant Java/POM

- Impact attendu : POM et imports des contrats, ports, Consumption générique, polling et clients directs ; aucune logique métier ni migration SQL.
- Slice primaire : COMMAND (`./mvnw -pl runtime-command-consumption-worker -am test`).
- Slices secondaires : EVENT, PROJECTION, BINDING, REGISTRATION, COMMAND_RESULT, REGISTRATION_RESULT, WEB et LKV en raison des ports et du polling partagés. Commande composée canonique : `./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker,runtime-binding-consumption-worker,runtime-registration-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker,runtime-web-api,runtime-latest-known-version-consumption-worker -am test`.
- Impact interdit : changements de sémantique Command/Registration/Projection/Binding/Result, nouveaux modules WP2–WP6, schéma/données/SQL, décision LKV/E→U/Command contract.
- Gates : `./mvnw -pl architecture-tests -am test` requis pour le graphe ; `./mvnw test` requis à la clôture de WP1 si le delta traverse effectivement la majorité des slices (prévision : oui). Pas de preuve DB historique ; tests DB actuels des slices uniquement.
- Escalade/arrêt : dépendance métier imprévue, cycle, dérive transactionnelle, besoin d'implémenter WP2, changement de comportement ou élargissement majeur : arrêt au dernier checkpoint vert, rapport du blocage et du delta du plan.

## Résultat de WP1 (gates globaux verts)

Le regroupement en six WP est inscrit au plan ; D.01–D.27 y restent des checkpoints internes. B10 est introduit à D.08 et retiré à D.19. `infra-persistence-read-jpa` n'absorbera que le READ ferme, dont CURRENT_BINDING ; code et storage LKV resteront CURRENT/provisoires jusqu'à D.TBD-LKV. WP2–WP6 n'ont pas commencé.

| Frontière WP1 | Source CURRENT et dépendances avant | Changement local et dépendances après | Bridge |
| --- | --- | --- | --- |
| `contracts-authentication` | `authentication-contracts` → `domain-user-identity` | POM renommé, principal inchangé → `domain-user-identity` | Aucun ; clients basculés |
| `contracts-observability` | `observability` → aucune dépendance interne | POM renommé, `TraceContext`/`TraceContextHolder` inchangés → aucune | Aucun |
| `contracts-registration` | `RegistrationRequest`/`RegistrationOutcome` dans `engine-registration` → `domain-user-identity`, `engine-core` (module source) | Deux types extraits en contrat → `domain-user-identity` seul | Aucun ; engine et clients basculés |
| `port-transaction` | `TransactionRunner` dans `engine-core` → `domain-pot`, `domain-authorization` (module source) | Interface extraite → aucune dépendance interne ; provider Spring inchangé dans `infra-tx-spring` | Aucun |
| `port-binding-authority` | Quatre interfaces dans `domain-user-identity` → aucune dépendance interne (module source) | Interfaces extraites → `domain-user-identity` pour les types purs | Aucun |
| `port-projection` | `engine-projection-contracts` → `domain-projection` | POM et packages rehome, trois types → `domain-projection` | Aucun |
| `domain-consumption` | key/slot/claim/lease/provenance → aucune | Ajout `WorkerSegment`/`PartitionHash` et tests → aucune | Aucun |
| `engine-consumption` | `engine-core`, `domain-consumption` | Wrappers transactionnels → `port-transaction` ; dépendances : `port-transaction`, `domain-consumption` | Aucun |
| `orchestrator-consumption` | `engine-consumption`, `engine-projection-task` | Deux orchestrateurs génériques conservés ; spécialisation Task sortie ; dépendance : `engine-consumption` | B6 temporaire côté runtime Task |
| `orchestrator-poll-consumption` | `supra-consumption-worker` → `orchestrator-consumption` | POM et packages renommés/rehome ; neuf runtimes CURRENT basculés → `orchestrator-consumption` | Aucun |

`engine-core` existe encore. Il a perdu exactement `TransactionRunner`, `WorkerSegment` et `PartitionHash`. Il conserve `RecordedEvent`, `EventTraceMetadata`, `BusinessEventEnvelope`, `PotGlobalVersion`, `UserContext`, `PotHeaderSnapshot`, `PotShareholdersSnapshot`, `ExpenseHeaderSnapshot`, `ExpenseSharesSnapshot`, `BusinessEntityNotFoundException`, `VersionConflictException` et `PotPartitioner` legacy. Aucune autre extraction Pot/Event n'était nécessaire pour casser les dépendances des fondations.

B6 est l'unique compatibilité temporaire WP1 : la classe `ProjectionTaskConsumptionOrchestrator` est hébergée dans le runtime Task CURRENT, dépend du nouvel orchestrateur générique et sera retirée/rehome en WP2/D.08 avec le split Task. Dépendants : configuration et tests du runtime Task. Les B1–B5 et B7 prévus initialement n'ont pas été instanciés grâce à la bascule directe de tous leurs clients. Aucun bridge TARGET → legacy.

### Preuves exécutées

Toutes les commandes Maven ci-dessous ont été lancées depuis `app/` ; la première tentative depuis la racine a échoué car `./mvnw` s'y trouve absent.

- Après A, `./mvnw -pl runtime-web-api,runtime-registration-consumption-worker,runtime-registration-result-consumption-worker -am test` : compilation réparée, puis arrêt dans `infra-persistence-jpa` sur Docker absent. `./mvnw -pl runtime-web-api,runtime-registration-consumption-worker,runtime-registration-result-consumption-worker -am -DskipTests test` : succès. `./mvnw -pl contracts-authentication,contracts-observability,contracts-registration,engine-registration -am test` : succès.
- Après B, C, D et E, commande composée des neuf ancres déclarées avec `-am -DskipTests test` : succès final (compilation du code et des tests sur les neuf slices). Les premières itérations ont révélé des imports de tests et une spécialisation Task ; corrigés avant le succès final.
- `./mvnw -pl port-transaction,port-binding-authority,port-projection,domain-consumption,engine-consumption,orchestrator-consumption,engine-projection-task,orchestrator-poll-consumption -am test` : succès, notamment les tests de segmentation, transaction, orchestration générique, Task legacy et polling.
- `./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker -am -Dtest=CommandConsumptionRuntimeContextTest,CommandConsumptionWorkerLifecycleTest,EventConsumptionRuntimeSpringBindingTest,EventConsumptionWorkerLifecycleTest,ProjectionTaskWorkerLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test` : succès ; contextes/bindings/lifecycle ciblés verts.
- `./mvnw -pl architecture-tests -am -Dtest=HexagonalArchitectureTest,Pcl6MonolithAbsenceTest -Dsurefire.failIfNoSpecifiedTests=false test` : succès ; les règles de pureté contrats/ports et d'indépendance Consumption sont activées.
- `./mvnw -pl engine-consumption,orchestrator-consumption,orchestrator-poll-consumption,contracts-registration,port-binding-authority,port-projection,port-transaction -am dependency:tree -Dincludes=com.kartaguez.pocoma` : succès. Contrôle XML/DFS du reactor CURRENT local : 54 POM enfants (dont legacy et LKV CURRENT), 215 arcs de production, aucun cycle. Ces chiffres CURRENT ne sont pas les 54 POM/147 arcs TARGET.
- `./mvnw -pl architecture-tests -am test` et `./mvnw test` : premières tentatives bloquées par Docker absent ; relancés avec Docker disponible après correction d’une attente de chemin dans `ProjectionTaskWorkerProcessTest`, puis **BUILD SUCCESS** tous deux. Aucun changement SQL, schéma ou données ; aucune preuve historique de migration demandée.
- `git diff --check` : succès. Recherche des anciens artefacts Maven et imports Java déplacés : aucune occurrence de production ; recherche B10→D.23 : aucune.

**Unexpected slice crossing :** aucun ; les neuf slices et le déplacement provisoire de la spécialisation Task avaient été déclarés après l'audit. **Global architecture gate :** requis et vert. **Full reactor :** requis à la clôture de cette restructuration large et vert. **Temporary Maven cycles: 0. TARGET → legacy bridges: 0. WP2 started: NO.**

WP1 est DONE après réussite du gate architecture et du full reactor. Le commit et le push consignent cette clôture.

Complément : les dix classes de tests d'architecture sans PostgreSQL (`DistributedComposeConfigurationTest`, `HexagonalArchitectureTest`, `Pcl3LegacyAbsenceTest`, `Pcl4LegacyQueryReadAbsenceTest`, `Pcl5PipelineLifecycleAbsenceTest`, `Pcl6MonolithAbsenceTest`, `Pcl7ModuleDependencyCollapseTest`, `Pcl8DatabaseDemolitionTest`, `RegistrationModuleBoundaryTest`, `Wa67BindingArchitectureTest`) ont ensuite été exécutées via `./mvnw -pl architecture-tests -am -Dtest=DistributedComposeConfigurationTest,HexagonalArchitectureTest,Pcl3LegacyAbsenceTest,Pcl4LegacyQueryReadAbsenceTest,Pcl5PipelineLifecycleAbsenceTest,Pcl6MonolithAbsenceTest,Pcl7ModuleDependencyCollapseTest,Pcl8DatabaseDemolitionTest,RegistrationModuleBoundaryTest,Wa67BindingArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test` : 88 tests verts. Cela ne remplace pas le gate canonique complet.

La spécialisation temporaire Task a été testée à son nouvel emplacement via `./mvnw -pl runtime-task-consumption-worker -am -Dtest=ProjectionTaskConsumptionOrchestratorTest -Dsurefire.failIfNoSpecifiedTests=false test` : succès.

### Gate final avec Docker disponible

Le daemon Docker de la session `julien.guezennec` (29.4.0) a été démarré. Le premier gate complet a révélé une seule attente de test obsolète : `ProjectionTaskWorkerProcessTest` vérifiait encore `supra-consumption-worker/target`. Elle a été mise à jour vers `orchestrator-poll-consumption/target`, puis son test ciblé a réussi. Les relances canoniques depuis `app/` ont donné :

- `./mvnw -pl architecture-tests -am test` : **BUILD SUCCESS**, 99 tests du module architecture, tous les modules de sa closure verts.
- `./mvnw test` : **BUILD SUCCESS**, reactor entier vert, y compris les neuf runtimes CURRENT et `architecture-tests` ; durée 3 min 07 s.

Les deux commandes ont été exécutées avec accès au socket Docker pour Testcontainers. Aucune migration SQL n’a été modifiée ou déplacée.
