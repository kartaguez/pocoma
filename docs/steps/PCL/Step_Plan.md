# PCL — Projection Chain Legacy Cleanup — implementation tracker

```text
Step: PCL — Projection Chain Legacy Cleanup
Current lot: PCL.7
Overall status: IN_PROGRESS
```

La source architecturale normative de ce tracker est [`Step_Canon.md`](Step_Canon.md). EPT est la
baseline clôturée ; son Canon et ses preuves doivent être relus avant toute modification de la
chaîne Event → Projection.

| Lot | Sujet | Statut |
|-----|-------|--------|
| PCL.1 | Dead Event-side legacy | DONE |
| PCL.2 | Canonical exact READ_POT | DONE |
| PCL.3 | Legacy Task runtime demolition | DONE |
| PCL.4 | Legacy Query/read demolition | DONE |
| PCL.5 | LKV isolation + pipeline/lifecycle demolition | DONE |
| PCL.6 | Monolith demolition + migration ownership | DONE |
| PCL.7 | Module/dependency collapse | REVIEW |
| PCL.8 | Database demolition | TODO |

## Dependency graph

```text
PCL.1 Dead Event-side legacy ───────────────┐
        │                                    │
        └── stops tasks_4_pipeline writer    │
                                             ├──→ PCL.5 pipeline/lifecycle demolition
PCL.3 Legacy Task runtime ──────────────────┤
        │                                    │
        └── stops tasks_4_pipeline readers   │
                                             │
PCL.2 Canonical exact READ_POT ──→ PCL.4 Legacy Query/read demolition
                                      │
                                      └───────┘

PCL.6 Monolith demolition + migration ownership
        └── largely independent; migration ownership gates physical module deletion

PCL.1 ─┐
PCL.2 ─┤
PCL.3 ─┤
PCL.4 ─┼──→ PCL.7 Module/dependency collapse ──→ PCL.8 Database demolition
PCL.5 ─┤
PCL.6 ─┘
```

Règles de séquencement :

- PCL.2 est indépendant de PCL.1 et PCL.3.
- PCL.3 ne dépend pas fonctionnellement de PCL.2.
- La disparition complète des deux côtés de `tasks_4_pipeline` exige PCL.1 puis PCL.3.
- L'ancienne Query Pot ne disparaît qu'après PCL.2.
- Le code des stores encore écrits par le vieux Task runtime ne disparaît qu'après PCL.3.
- PCL.5 attend PCL.1, PCL.3 et PCL.4 afin de supprimer les derniers consumers
  pipeline/lifecycle/serving ; l'extraction LKV précède la suppression de ses hôtes legacy.
- PCL.6 peut avancer indépendamment, mais le module `runtime-monolith` ne peut être physiquement
  supprimé qu'après déplacement de la propriété des migrations V1–V3.
- PCL.7 est le sweep structurel après PCL.1–PCL.6.
- PCL.8 est le dernier lot et ne droppe que des structures sans reader, writer, mapping ou runtime.

## PCL.1 — Dead Event-side legacy

### Status

`DONE`

### Objective

Supprimer le scheduling Event pipeline/generation abandonné et laisser exactement un chemin de
production supporté d'un Event durable vers `projection_tasks` : le chemin EPT metadata-only.

PCL.1 arrête définitivement le writer legacy de `tasks_4_pipeline`. Il ne modifie ni le producer
des Events, ni LKV, ni le ProjectionTask executor canonical.

### DELETE

- `EventConsumptionLocator` legacy et ses failure policies/classifiers exclusivement associés ;
- `ScheduleProjectionTasksForEventUseCase` et la branche `engine-task-creation` ;
- `EventPipelineRelevanceRegistry`, `TaskCreationStrategyRegistry` et anciens registries de
  pipeline/generation ;
- relevances et stratégies Event des modules `pipeline-pot` et `pipeline-balance` ;
- `JpaEventConsumptionDiscoveryAdapter` et les méthodes/repositories SQL exclusivement dédiés à
  l'ancienne discovery pipeline ;
- `JpaTaskCreationAdapter`, ses modèles et bindings vers `tasks_4_pipeline` ;
- `CanonicalProjectionTaskScheduler` et `MeteredProjectionTaskScheduler`, qui représentent
  l'ancien dual-write ;
- `engine-task-materialization` si un scan de reachability confirme qu'il ne conserve aucune
  responsabilité protégée ;
- tests, fixtures, properties, métriques, runbooks et dépendances exclusivement associés à ce
  scheduling ;
- tout bean ou auto-configuration capable de réactiver le vieux chemin Event.

`event_4_pipeline_materialization_status` n'est pas une table à dropper dans ce lot : V10 la
supprime déjà. PCL.1 retire seulement ses références historiques actives éventuelles et en vérifie
l'absence sur un schéma migré.

### KEEP

- `business_event_outbox` et les adapters d'append/read nécessaires à Command, EPT et LKV ;
- `ProjectionMaterializationPolicy` et `PocomaProjectionMaterializationPolicy` ;
- `ProjectionMaterializationCandidate`, ordering key et port de discovery canonical ;
- `JdbcProjectionMaterializationDiscoveryAdapter` ;
- `ProjectionMaterializationConsumptionKeys`, `Source` et `Service` ;
- generic Consumption, acquire/finalize, fencing et polling ;
- l'identité `EVENT/[eventId] × PROJECTION_TASK_MATERIALIZER/[projectionType]` ;
- `JdbcProjectionTaskStoreAdapter` et `projection_tasks` ;
- la discovery LKV et le consumer `SOURCE_VERSION_WATERMARK`.

### Prerequisites

- EPT est `DONE` et ses preuves PostgreSQL/E2E sont vertes au HEAD du lot.
- Un scan frais confirme les callers du scheduling legacy et les contenus mixtes de
  `engine-processing-event`, `locator-consumption-event` et `infra-persistence-jpa`.
- Aucun traitement de données SQL n'est tenté : les tables restent jusqu'à PCL.8.

### Exit criteria

- Un seul composition root Event supporté crée des lignes dans `projection_tasks`.
- Aucun code de production ne crée de ligne dans `tasks_4_pipeline`.
- Le runtime Event actif n'importe ni pipeline definition, ni relevance registry, ni task creation
  strategy.
- Aucun scheduler dual-write legacy n'est instanciable depuis un runtime supporté.
- EPT metadata discovery reste strictement metadata-only.
- LKV continue à découvrir les Events depuis `business_event_outbox`.

### Tests / proofs

- tests permanents EPT policy, metadata-only discovery et backfill ;
- preuve Spring de reachability : worker Event canonical présent, locator/scheduler legacy absents ;
- preuve PostgreSQL qu'un Event produit seulement les Tasks canoniques attendues et aucun write
  `tasks_4_pipeline` ;
- replay, rollback, takeover et stale-Claim proofs inchangés ;
- tests LKV discovery toujours verts ;
- scan source/JDBC confirmant zéro writer restant de `tasks_4_pipeline` côté Event.

### What becomes removable next

- après PCL.3, `tasks_4_pipeline` et ses indexes deviennent candidats au drop PCL.8 ;
- les readers/executors Task legacy de `tasks_4_pipeline` relèvent de PCL.3 ;
- les modules pipeline/lifecycle encore requis par ces runtimes relèvent de PCL.5.

### Completion evidence

- `engine-task-creation` et `engine-task-materialization` retirés du reactor ;
- locator, discovery, scheduler dual-write et adapter writer Event legacy supprimés ;
- scan production : aucun writer restant vers `tasks_4_pipeline` ;
- identité LKV `EVENT/[eventId] × SOURCE_VERSION_WATERMARK/[]` préservée et testée ;
- preuves ciblées EPT/LKV/PostgreSQL vertes ;
- `./mvnw test` vert sur les 58 modules après `./mvnw clean` le 2026-09-27 ;
- aucune migration historique modifiée et aucun drop de schéma ajouté.

## PCL.2 — Canonical exact READ_POT

### Status

`DONE`

### Objective

Figer la plus petite capacité interne capable de lire, valider et interpréter exactement :

```text
ProjectionKey(READ_POT, POT, potId, explicitVersion)
    → ProjectionReadPort
    → JdbcProjectionStoreAdapter
    → projection_root / projection_artifact / projection_failure
    → ReadPotProjectionDefinition
    → ReadPotInterpreter
    → Pot view/model
```

Le lot ne construit aucune API Query générale.

### DELETE

- de la frontière retenue, toute dépendance à `AUTH` ;
- toute version implicite, `current`, `latest`, listing ou pagination ;
- toute dépendance à Balance Query ;
- tout pipeline id, generation id, lifecycle ou serving selection ;
- toute orchestration générique qui ne sert pas l'exact read minimal ;
- le module `engine-projection-read` uniquement si le réexamen local prouve qu'une frontière
  survivante plus petite le rend entièrement redondant.

### KEEP

- `ProjectionKey`, `Projection`, `ProjectionArtifact` et les value objects canoniques ;
- `ProjectionReadPort` ;
- `JdbcProjectionStoreAdapter` ;
- `ReadPotProjectionDefinition` et les JSON Schemas READ_POT ;
- la validation de cardinalité, type, clé et payload ;
- l'interprétation READ_POT et ses invariants relationnels ;
- une représentation Pot interne suffisante pour le résultat ;
- `projection_root`, `projection_artifact` et `projection_failure`.

Au début du lot, choisir explicitement entre :

1. conserver et réduire `engine-projection-read` ;
2. déplacer uniquement ses primitives/services nécessaires dans une frontière plus petite ;
3. supprimer le module s'il devient sans contenu propre.

Le choix doit minimiser la surface survivante, pas préserver un nom de module.

### Prerequisites

- Aucun prérequis PCL.1 ou PCL.3.
- Le producer canonical READ_POT et le store V7 sont présents et prouvés.
- Un scan frais vérifie `ExactProjectionReadService`, `ProjectionReadPort`,
  `JdbcProjectionStoreAdapter`, `ReadPotProjectionDefinition` et `ReadPotInterpreter`.

### Exit criteria

- Une API interne prend obligatoirement `potId` et une version explicite.
- Elle construit exactement `READ_POT / POT / potId / version`.
- Elle lit uniquement le store canonical singulier.
- Une projection présente est revalidée et interprétée en modèle Pot.
- Not-ready et failure sont représentés sans dépendance au vieux read store.
- Aucun HTTP controller, `AUTH`, current/list semantic, Balance Query ou concept pipeline n'est
  introduit.
- Le choix physique concernant `engine-projection-read` est documenté dans les findings du lot.

### Tests / proofs

- exact key lookup sur les quatre composants de `ProjectionKey` ;
- `Ready`, `Failed` et `NotReady` selon root/failure/absence ;
- rejet d'une projection dont key, artifact types, cardinalités ou payloads sont invalides ;
- interprétation d'un READ_POT non trivial avec Pot, shareholders, expenses et shares ;
- invariants d'identité et références payer/shareholder ;
- test PostgreSQL lisant une projection réellement publiée par le producer canonical ;
- règle d'architecture interdisant pipeline/lifecycle/serving dans la frontière exacte.

### What becomes removable next

- l'ancienne Query Pot et ses controllers/use cases/adapters dans PCL.4 ;
- les anciens stores Pot pipeline/generation, qui ne justifient plus une capacité read supportée.

### Completion evidence

- `engine-projection-read` conservé et réduit à la responsabilité générique d'exact read ;
- API interne `READ_POT` exigeant `PotId` et `targetVersion`, sans résolution implicite de version ;
- store canonique singulier isolé dans `infra-projection-persistence`, avec lookup strict sur les
  quatre composants de `ProjectionKey` ;
- sémantiques `Ready`, `Failed` et `NotReady` prouvées avec revalidation de toute projection
  présente avant interprétation ;
- interprétation non triviale en `PotView`, incluant shareholders, expenses, shares et intégrité
  des références payer/shareholder ;
- preuve PostgreSQL du producer canonical jusqu'à l'exact read, sans utilisation de
  `pot_projection_snapshots`, et preuve `Failed` via la persistance canonique ;
- guards d'architecture interdisant `AUTH`, Query, pipeline/lifecycle/serving, LKV et ancien read
  store dans la frontière survivante ;
- audit PCL.2 accepté et `./mvnw test` vert sur les 59 modules le 2026-09-27.

## PCL.3 — Legacy Task runtime demolition

### Status

`DONE`

### Objective

Supprimer l'exécution de `tasks_4_pipeline` et les pipelines Task historiques sans toucher à la
branche `projection_tasks → ProjectionEngineService` canonique.

### DELETE

- `TaskConsumptionRuntimeConfiguration` et `TaskConsumptionProperties` ;
- l'ancien locator, discovery, acquisition/exécution et failure handling liés à
  `tasks_4_pipeline` ;
- `engine-processing-task` ;
- `engine-task-execution` ;
- `locator-consumption-task` ;
- `JpaTaskConsumptionDiscoveryAdapter`, `JpaTaskPort`, repositories et entités exclusifs ;
- legacy `READ_POT` Task DTO, mapper, handler et pipeline ;
- legacy Balance Task DTO, mapper, handler et pipeline ;
- anciens Task execution handler/mapper registries ;
- `JpaImmutableBalanceProjectionAdapter` comme writer du vieux Task runtime, sous réserve du
  retrait coordonné de son code exclusivement writer ;
- `JdbcProjectionMetadataAdapter`, `ProjectionMaterializationService`,
  `JdbcPotProjectionArtifactWriter` et autres writers old READ_POT lorsqu'ils n'ont plus aucun
  caller protégé ;
- properties `pocoma.task-consumption.*`, tests de takeover/binding pipeline, runbooks et métriques
  exclusivement legacy.

### KEEP

- le module/runtime qui héberge la composition Task canonique tant qu'il reste son owner ;
- `CanonicalProjectionTaskRuntimeConfiguration` et ses properties
  `pocoma.projection-task-consumption.*` ;
- `ProjectionTaskConsumptionOrchestrator` ;
- `ProjectionTaskConsumptionService` ;
- `ProjectionEngineService` et son catalogue de producers canoniques ;
- `JdbcProjectionTaskStoreAdapter` et `projection_tasks` ;
- generic Consumption et ses transactions/fencing ;
- `READ_POT` et `POT_BALANCES` producers ;
- `ProjectionWritePort` et le store canonical ;
- les historical source adapters requis par les producers.

### Prerequisites

- PCL.1 est `DONE`, afin que plus aucun Event ne produise de nouvelle ligne
  `tasks_4_pipeline`.
- PCL.2 n'est pas un prérequis.
- Le runtime distribué active et prouve la configuration canonique.
- Un scan frais sépare les classes `pipeline-pot` / `pipeline-balance` legacy des engines
  producers canoniques de noms voisins.

### Exit criteria

- Aucun runtime supporté ne lit, claim, execute ou écrit `tasks_4_pipeline`.
- `TaskConsumptionRuntimeConfiguration` et sa branche conditionnelle n'existent plus.
- Le runtime Task canonical démarre sans les modules processing/execution/locator legacy.
- READ_POT et POT_BALANCES sont préparés par `ProjectionEngineService` et publiés uniquement dans
  le store canonical.
- Aucun dual-write old READ_POT ou Balance n'est possible.
- Generic Consumption reste fonctionnel pour Event, ProjectionTask, Command et LKV.

### Tests / proofs

- Spring context du runtime Task prouvant uniquement la composition canonique ;
- PostgreSQL READ_POT et POT_BALANCES à travers `projection_tasks` ;
- replay, idempotence, retry, takeover et fencing du ProjectionTask executor ;
- absence de beans legacy Task ;
- scan production prouvant zéro reader et zéro writer `tasks_4_pipeline` ;
- absence de write dans `balance_projection_*` et les old READ_POT stores ;
- preuves EPT distribuées inchangées.

### What becomes removable next

- `tasks_4_pipeline` au lot DB final ;
- immutable Balance store après retrait de ses readers PCL.4 ;
- old READ_POT metadata/fragments après retrait du code Query/read PCL.4 ;
- derniers consumers Task de lifecycle, generation et `domain-pipeline` pour PCL.5 ;
- modules vidés lors de PCL.7.

### Notes / findings

- Le baseline d'implémentation était toujours exactement
  `71332204834c035e44e667da2eb89ba159a7cf1c`, aligné avec
  `origin/v2-make-it-pull`, sans divergence ni modification locale.
- `domain-task`, `engine-processing-task`, `engine-task-execution`,
  `locator-consumption-task`, `pipeline-pot` et `pipeline-balance` n'avaient aucun caller
  canonique restant et ont été retirés atomiquement du reactor.
- `ProjectionMetadataPort` conserve trois opérations de lecture encore appelées par l'ancienne
  Query (`findArtifact`, `findFailure`, `findHead`) ; son adapter a été réduit à ce contrat
  read-only. Leur suppression reste donc PCL.4.
- Le reader de fragments `PotProjectionArtifactReader` et le service de reconstruction old
  READ_POT avaient déjà zéro caller de production ; ils ont été supprimés avec le writer, sans
  attendre PCL.4.
- La migration V7 du store canonique singulier est encore co-localisée dans
  `infra-read-persistence`. Le runtime ProjectionTask garde une dépendance directe explicite sur ce
  module pour exécuter cette migration ; sa séparation physique relève de PCL.4/PCL.7 et aucun
  writer old store n'est réintroduit.
- Le reader `JpaImmutablePotBalancesQueryAdapter` reste le seul accès de production aux tables
  immutable Balance ; le writer `JpaImmutableBalanceProjectionAdapter` a disparu.
- Les seules références de production restantes à `tasks_4_pipeline` sont dans les migrations
  historiques V3, V5 et V10. Aucun source Java, mapping, repository, SQL actif ou configuration
  runtime ne référence cette table.

### Completion evidence

- audit indépendant PCL.3 accepté sans correction bloquante ;
- composition root, properties, locator, modèle d'exécution, pipelines Pot/Balance et six modules
  Task legacy supprimés ; aucun fallback conditionnel ne subsiste ;
- persistence JPA `tasks_4_pipeline` et writer immutable Balance supprimés ;
- writers old READ_POT, services de materialization/failure et transactions associées supprimés ;
- preuve PostgreSQL conjointe READ_POT + POT_BALANCES : deux racines canoniques publiées et snapshot
  avant/après strictement inchangé pour `tasks_4_pipeline`, old READ_POT et immutable Balance ;
- guards permanents : modules/edges legacy absents, zéro référence active à
  `tasks_4_pipeline`, types Spring/exécution/writers legacy absents ;
- `./mvnw -pl runtime-task-consumption-worker -am test` vert, 10 preuves runtime PostgreSQL ;
- `./mvnw -pl architecture-tests test` vert, 60 preuves dont les EPT distribuées ;
- `./mvnw clean verify` vert sur les 53 modules le 2026-09-28 ;
- aucune migration historique modifiée, aucune migration de drop ajoutée et aucune table supprimée.

## PCL.4 — Legacy Query/read demolition

### Status

`DONE`

### Objective

Supprimer radicalement les anciennes Query Pot, Expense, Balance et listing ainsi que le code des
anciens read stores. La seule capacité read protégée est l'exact READ_POT interne livré par PCL.2.

PCL.4 n'essaie pas de migrer l'ancienne Query Balance vers `POT_BALANCES`.

### DELETE

- `GetPotUseCase`, `GetPotService` et transaction wrapper historiques ;
- `ListUserPotsUseCase`, anciennes Query/list Pot et current-version semantics ;
- `GetExpenseUseCase`, `ListPotExpensesUseCase` et anciennes Query Expense ;
- `GetPotBalancesUseCase` ;
- `ListUserPotBalancesUseCase` ;
- `PotBalancesQueryPort` ;
- `JpaPotQueryAdapter`, `JpaExpenseQueryAdapter` en tant qu'adapters Query ;
- `JpaImmutablePotBalancesQueryAdapter` et `ImmutableBalanceQueryConfiguration` ;
- Query controllers/endpoints, DTOs, mappers, policies et OpenAPI exclusivement liés aux contrats
  abandonnés ;
- `QueryVersionResolver`, `ServingQueryProjectionSelectionProvider` et Query selection contracts ;
- `JdbcPotUserIndexReader`, listing/index contracts et anciens indexes côté code ;
- `ProjectionMetadataPort`, status resolution et artifact readers old store lorsqu'ils ne
  conservent aucun caller après PCL.3 ;
- mappings/adapters/JDBC references pour :
  - `pocoma_read.projection_artifacts` plural ;
  - `pocoma_read.projection_failures` plural ;
  - `pocoma_read.projection_heads` ;
  - `pocoma_read.projection_invariant_violations` ;
  - `pot_projection_snapshots` ;
  - `pot_projection_shareholders` ;
  - `pot_projection_expenses` ;
  - `pot_projection_expense_shares` ;
  - `pot_projection_user_index` ;
  - `pocoma_read.pot_version_metadata` ;
  - `balance_projection_artifacts` ;
  - `balance_projection_entries` ;
- tests, fixtures, properties, docs et monitoring exclusivement associés à ces Query/read paths.

Les tables physiques restent jusqu'à PCL.8.

### KEEP

- la capacité interne exacte de PCL.2 ;
- `ProjectionReadPort`, le store canonical et l'interprétation READ_POT retenus par PCL.2 ;
- le producer canonical READ_POT ;
- le producer canonical POT_BALANCES ;
- `pocoma_read.projection_root`, `projection_artifact`, `projection_failure` ;
- `pocoma_read.source_version_watermarks` et LKV ;
- les entités/repositories/adapters du write model requis par Command et les producers ;
- `pot_global_versions`, `pot_headers`, `shareholders`, `expense_headers`, `expense_shares` et la
  table primaire `pot_version_metadata` ;
- les controllers Command et contrats HTTP non Query encore supportés.

### Prerequisites

- PCL.2 est `DONE` avant suppression de l'ancienne Query Pot.
- PCL.3 est `DONE` avant suppression du code des stores encore écrits par le vieux Task runtime.
- Un scan de reachability distingue chaque adapter Query d'un adapter write/source producer.
- Aucun drop SQL n'est effectué dans ce lot.

### Exit criteria

- Aucune ancienne API Query Pot, Expense, Balance ou list n'est un contrat de production.
- Aucun bean de production n'implémente `PotBalancesQueryPort`.
- Aucun code de production ne lit ou écrit les stores plural/fragments/index/immutable Balance.
- La capacité exacte PCL.2 lit exclusivement les tables canoniques singulières.
- Les producers READ_POT et POT_BALANCES continuent à lire le write model historique et à écrire le
  store canonical.
- Aucune table du write model n'est retirée ou rendue inaccessible à Command.

### Tests / proofs

- tests exact READ_POT PCL.2 ;
- tests des deux producers canoniques ;
- tests Command/write model ciblés ;
- preuve Spring qu'aucun ancien Query controller/use case/adapter n'est composé ;
- scan de code et SQL : zéro reference active aux old read stores ;
- test d'architecture distinguant explicitement schéma principal et `pocoma_read`, singulier et
  pluriel ;
- preuves EPT et LKV inchangées.

### Notes / findings

- `JpaPotBalancesAdapter` était mixte : son implémentation de `PotBalancesQueryPort` a été retirée,
  mais son accès `loadAtVersion` au write model reste requis par `PotBalanceProjectionPort` et le
  producer canonical POT_BALANCES.
- `infra-read-persistence` reste un module utile : après retrait des readers metadata/index legacy,
  il conserve la migration du store canonical singulier et l'adapter LKV.
- `domain-projection-legacy` et `engine-read-projection` ont été prunés jusqu'à leurs seules
  responsabilités LKV encore protégées ; leur relocalisation ou suppression physique relève de
  PCL.5/PCL.7.
- La disparition de `ImmutableBalanceQueryConfiguration` a révélé que ce composant fournissait aussi
  l'`ObjectMapper` du runtime Web ; ce bean transverse est désormais composé explicitement par le
  runtime, sans recréer de façade Query.
- Les tables et migrations historiques ont été conservées sans modification, conformément à la
  frontière PCL.4/PCL.8.

### Completion evidence

- six endpoints Query HTTP, leurs DTOs/mappers/policies, leur wiring Spring et les propriétés
  associées supprimés ; les contrats HTTP Command restants sont inchangés ;
- module `engine-query`, anciens ports/use cases/services et adapters Query JPA/JDBC supprimés du
  reactor ; aucun alias, shim ou fallback ne les remplace ;
- anciens readers metadata, status et user-index retirés, tandis que
  `ExactProjectionReadService` → `ProjectionReadPort` → `JdbcProjectionStoreAdapter` et
  `ReadPotService` → `ReadPotInterpreter` restent composables ;
- guard permanent `Pcl4LegacyQueryReadAbsenceTest` prouvant l'absence des artefacts, références,
  wiring et accès runtime legacy, ainsi que la conservation des responsabilités mixtes/canoniques ;
- preuve OpenAPI renforcée contre le retour des GET Query et anciens headers de sécurité ;
- scans production : zéro référence aux types legacy et zéro accès actif aux old read stores hors
  migrations historiques ; aucune migration SQL modifiée ;
- `./mvnw clean verify` vert sur les 52 modules le 2026-09-28, dont 61 preuves dans
  `architecture-tests` ;
- audit indépendant accepté ; le fix de preuve PCL4-AUD-01 étend le guard aux tables
  `balance_projection_artifacts`, `balance_projection_entries` et
  `pocoma_read.pot_version_metadata` sans interdire la table primaire `pot_version_metadata` ;
- check GitHub `build-and-test` du fix `59888c8562df818cbb1ba1466e5d45197487e5e9` terminé avec
  succès ; PCL.4 est clôturé.

### What becomes removable next

- derniers consumers Query de serving/lifecycle et `domain-pipeline`, permettant PCL.5 ;
- old read-store tables et immutable Balance tables au PCL.8 ;
- parties legacy de `engine-query`, `engine-read-projection`, `domain-projection-legacy` et
  `infra-read-persistence` au sweep PCL.7.

## PCL.5 — LKV isolation + pipeline/lifecycle demolition

### Status

`DONE`

### Objective

Isoler d'abord Latest Known Version dans une frontière neutre, puis supprimer l'ensemble du modèle
pipeline/generation/lifecycle/serving devenu sans consumer supporté.

### DELETE

- dépendance LKV vers `domain-projection-legacy` ;
- mélange dans `engine-read-projection` entre LKV et anciens services de materialization/read ;
- méthodes legacy pipeline du repository de discovery Event lorsque seule la discovery LKV reste ;
- `PipelineId` ;
- `PipelineDefinition` ;
- `PipelineVersionDefinition` ;
- `VersionApplicability` ;
- `PipelineDefinitionRegistry` ;
- `PocomaPipelineDefinitions` ;
- exceptions et catalogues exclusivement associés ;
- `engine-pipeline-lifecycle` ;
- `infra-pipeline-lifecycle-persistence` ;
- activation gates, lifecycle mutation services, integrity services et generation registries ;
- serving selection et contrats Query associés ;
- auto-configurations, properties, startup validation, POM edges, tests et docs legacy ;
- mappings/JDBC references à `pipeline_version_activations` et
  `projection_serving_selections`.

Les deux tables restent physiquement présentes jusqu'à PCL.8.

### KEEP

- `LatestKnownVersion` comme primitive neutre, éventuellement relocalisée ;
- `AdvanceLatestKnownVersionInput`, `LatestKnownVersionUpdate`, use case et service ;
- `LatestKnownVersionPersistencePort` et `JdbcLatestKnownVersionAdapter` ;
- `LatestKnownVersionConsumptionLocator`, policies de retry/failure réellement utilisées et
  métriques supportées ;
- `LatestKnownVersionEventDiscoveryPort` et son adapter/repository minimal ;
- `EventPort` et le mapping Event durable nécessaires à LKV ;
- `EVENT/[eventId] × SOURCE_VERSION_WATERMARK/[]` ;
- `pocoma_read.source_version_watermarks` ;
- contrats/candidates/ordering metadata-only nécessaires à EPT ;
- generic Consumption.

### Prerequisites

- PCL.1 est `DONE` : plus de consumer Event pipeline.
- PCL.3 est `DONE` : plus de consumer Task pipeline/lifecycle.
- PCL.4 est `DONE` : plus de Query serving-aware.
- Avant toute suppression d'un module hôte, les types LKV neutres sont relocalisés et leurs imports
  sont migrés.
- Un scan frais confirme qu'aucune nouvelle responsabilité protégée n'utilise `domain-pipeline`.

### Exit criteria

- La chaîne LKV complète est supportée et testée sans pipeline/lifecycle/serving.
- `supported imports from domain-pipeline = 0`.
- Aucun bean supporté n'implémente activation gate ou serving selection.
- Aucun runtime supporté ne dépend de `infra-pipeline-lifecycle-persistence`.
- Les modules et arêtes Maven pipeline/lifecycle abandonnés sont absents du reactor supporté.
- EPT et les producers canoniques restent indépendants du modèle supprimé.

### Tests / proofs

- PostgreSQL LKV : discovery, acquire/execute, advance monotone et idempotence ;
- reprise, retry et provenance Consumption LKV ;
- contexte Spring LKV sans lifecycle persistence ;
- contexte Spring Event et ProjectionTask sans lifecycle beans ;
- scan production et test d'architecture : zéro import `domain-pipeline` depuis le graphe supporté ;
- scan JDBC/JPA : seule la migration historique V12 et le futur drop PCL.8 référencent encore les
  tables lifecycle ;
- preuves EPT/exact READ_POT/POT_BALANCES inchangées.

### Notes / findings

- Le scan d'ouverture au commit `c35fe20e6dd93130254567d2a46afd73c97a702c` confirme que
  `LatestKnownVersion` est le dernier type de `domain-projection-legacy`; sa frontière survivante
  minimale est `engine-read-projection`, qui porte déjà le use case et le port de persistance LKV.
- `HistoricalPotSnapshotSource` et `HistoricalPotReconstructionException` restent dans
  `engine-read-projection` : ils sont utilisés par le loader canonical READ_POT et ne relèvent pas
  du modèle pipeline/lifecycle supprimé.
- Aucun caller de production protégé n'importe `domain-pipeline`; ses callers restants sont
  confinés à `engine-pipeline-lifecycle` et `infra-pipeline-lifecycle-persistence`.
- La migration historique `V12__pipeline_version_lifecycle.sql` a été relocalisée sans modification
  dans `infra-persistence-jpa` avant suppression de son module hôte. Les tables lifecycle restent
  présentes jusqu'à PCL.8.

### Completion evidence

- `LatestKnownVersion` relocalisé dans la frontière survivante `engine-read-projection`; le module
  `domain-projection-legacy` et son arête reactor ont été retirés ;
- `domain-pipeline`, `engine-pipeline-lifecycle` et
  `infra-pipeline-lifecycle-persistence` supprimés avec leurs contrats, services, adapters,
  auto-configurations, properties, startup validation, tests et arêtes Maven ;
- migration historique `V12__pipeline_version_lifecycle.sql` relocalisée byte-for-byte dans
  `infra-persistence-jpa`, SHA-256
  `b83cef2c00da4fde15e6a2c587ff7487ac05d924f12ddfb3135f52444bca6399`; aucune table n'est
  droppée ;
- preuve PostgreSQL LKV couvrant discovery metadata-only, acquire/execute, avance monotone,
  idempotence, retry après échec technique et provenance Consumption ; preuve des métriques
  advanced/unchanged/error ;
- preuve Spring que les beans LKV supportés sont présents sans bean lifecycle/serving, et garde
  permanent `Pcl5PipelineLifecycleAbsenceTest` sur les sources, POM, accès aux tables et migration ;
- correction d'audit : le guard impose désormais exactement une occurrence repository-wide de
  `V12__pipeline_version_lifecycle.sql`, conserve son checksum et inspecte toutes les ressources SQL
  de production contre un `DROP TABLE` prématuré des deux tables lifecycle, avec casse, espacement,
  qualification de schéma et `IF EXISTS` tolérés ;
- correction d'audit PostgreSQL : un Event supprimé après discovery mais avant reload produit
  `RECORDED_EVENT_NOT_FOUND` / `SOURCE_VERSION_WATERMARK_INPUT_NOT_FOUND`, terminalise la
  Consumption en `FAILED`, ne décale pas `next_claim_at`, ne crée ni watermark ni provenance, et
  reste non réacquérable lors d'une nouvelle passe, y compris avec un candidat stale ;
- scans finaux : zéro import production `domain-pipeline`, zéro arête POM vers les quatre modules
  retirés et zéro accès actif aux deux tables lifecycle hors V12 ;
- `./mvnw -pl runtime-latest-known-version-consumption-worker,architecture-tests -am test` vert
  sur 45 modules le 2026-09-28 : 9 tests runtime LKV, 1 test locator LKV et 64 tests
  `architecture-tests`, dont 5 dans le guard PCL.5 ;
- `./mvnw clean verify` vert sur les 48 modules survivants le 2026-09-28, dont 9 tests runtime LKV
  et 64 preuves dans `architecture-tests`.
- ré-audit accepté au HEAD `36ac4843ea9d39491c3169f0ca628e2aa60676c3` avec le verdict
  `PCL.5 CLOSURE READY`; aucun finding ne reste ouvert et PCL.5 est clôturé.

### What becomes removable next

- `pipeline_version_activations` et `projection_serving_selections` au PCL.8 ;
- les éventuelles coquilles Maven sans responsabilité fonctionnelle, indépendantes du modèle déjà
  détruit, restent du ressort du sweep PCL.7.

## PCL.6 — Monolith demolition + migration ownership

### Status

`DONE`

### Objective

Supprimer le runtime monolith et l'ancien worker Balance Spring events, puis déplacer la propriété
physique des migrations V1–V3 sans modifier leur historique afin de permettre la suppression du
module `runtime-monolith`.

### DELETE

- composition fonctionnelle `runtime-monolith` ;
- profils API/worker monolith ;
- `supra-worker-balance-calculation-events-spring` ;
- `SegmentedBalanceCalculationWorker` et son listener Spring Event ;
- ancien `ComputePotBalancesUseCase`, service, transaction wrapper et configuration lorsqu'ils ne
  conservent aucun caller protégé ;
- `JpaPotBalancesAdapter` et repositories/entities du mutable Balance store, dont le scan de
  reachability confirme qu'ils n'ont aucun caller protégé ;
- branche worker `projection_tasks_legacy` et `JpaProjectionTask*` ;
- métriques de backlog/projection legacy du monolith ;
- Compose monolith, scripts, monitoring, runbooks et documentation exclusivement associés ;
- le module `runtime-monolith` après relocation prouvée des migrations V1–V3 ;
- resource imports Maven qui pointent vers le module supprimé.

Les tables mutable Balance et `projection_tasks_legacy` restent jusqu'à PCL.8.

Les adapters historiques mixtes restent prunés au minimum protégé :

- `JpaHistoricalPotBalanceSourceAdapter` reste la source du producer canonical `POT_BALANCES` ;
- `JpaProjectedExpenseAdapter` conserve la lecture historique exacte du write model mais perd ses
  contrats et opérations exclusivement legacy ;
- `JpaPotShareholdersAdapter` conserve Command et la lecture historique exacte mais perd son
  contrat projection legacy.

### KEEP

- contenu historique exact et identité Flyway de V1–V3 ;
- une propriété physique survivante et explicite pour ces migrations ;
- tous les runtimes supportés qui assemblent le schéma primaire ;
- write/Command model et ses tables ;
- `business_event_outbox` ;
- EPT Event et ProjectionTask runtimes ;
- producers READ_POT et POT_BALANCES ;
- historical source adapters requis par ces producers ;
- canonical store et LKV.

### Prerequisites

- Le retrait du runtime fonctionnel est largement indépendant de PCL.1–PCL.5.
- Avant suppression physique du module, inventorier tous les `<resources>` et test resources qui
  importent `runtime-monolith/src/main/resources/db/migration`.
- `infra-persistence-jpa` est l'owner physique survivant unique des migrations primaires V1–V3.
- Conserver strictement les fichiers V1–V3 inchangés pendant le déplacement.

### Exit criteria

- Aucun service, profil ou composition supportée ne démarre le monolith ou le worker Balance Spring
  events.
- Aucun production caller n'utilise l'ancien `ComputePotBalancesUseCase`.
- Les producers canoniques ne dépendent d'aucun old Balance store.
- V1–V3 sont assemblées depuis un owner survivant par tous les runtimes concernés.
- Le module `runtime-monolith` est physiquement absent du reactor et du repository, sans perte de
  migration ni resource path cassé.
- `projection_tasks_legacy` et les tables mutable Balance ont zéro reader, writer et mapping
  runtime ; elles restent physiquement présentes jusqu'à PCL.8.
- Aucune migration historique n'est modifiée et aucun `DROP TABLE` de ces structures n'est ajouté.
- Aucun Compose/script/dashboard/runbook actif ne présente le monolith comme runtime supporté.

### Tests / proofs

- noms, contenu et SHA-256 de V1–V3 identiques avant/après relocation, avec une occurrence unique
  repository-wide ;
- upgrade PostgreSQL depuis une base possédant déjà V1–V15 : `validate` réussi, aucune réparation,
  aucun checksum mismatch, aucune version dupliquée et aucune réexécution ;
- clean bootstrap PostgreSQL V1–V15 depuis `infra-persistence-jpa` ;
- boot/smoke des runtimes Web/Command/Event/Task/LKV survivants qui consomment les migrations ;
- tests Command/write model ;
- preuves READ_POT et POT_BALANCES canoniques ;
- `Pcl6MonolithAbsenceTest` permanent : modules/workers legacy absents, zéro resource import vers
  `runtime-monolith`, V1–V3 uniques, zéro runtime sur `projection_tasks_legacy` et le mutable Balance
  store, aucun drop prématuré et responsabilités canoniques présentes.

### Notes / findings

- Le scan de reachability a confirmé qu'aucun caller protégé ne dépendait de
  `JpaPotBalancesAdapter`, `PotBalanceProjectionPort`, des repositories `pot_balance_*` ou de la
  branche JPA `projection_tasks_legacy`; ils ont donc été supprimés plutôt que prunés.
- `JpaHistoricalPotBalanceSourceAdapter`, `CalculatePotBalancesAtVersionService` et
  `PotBalancesProjectionInputLoader` forment la frontière minimale conservée pour le producer
  canonique `POT_BALANCES` et ne lisent aucun résultat mutable Balance.
- `JpaProjectedExpenseAdapter` et `JpaPotShareholdersAdapter` ont été prunés aux seules lectures
  historiques exactes et responsabilités Command encore protégées.
- Les tables historiques `projection_tasks_legacy`, `pot_balance_projection_states`,
  `pot_balance_versions` et `pot_balances` restent présentes uniquement par les migrations V1–V3 ;
  aucun mapping, reader ou writer de production ne les référence.

### Completion evidence

- V1–V3 relocalisées byte-for-byte dans `infra-persistence-jpa`, avec une occurrence unique et les
  SHA-256 conservés : `d35a440f7ac3d3722176844fc29c3ab9b515e864d77fb9788eb12639ad712ae4`,
  `998ac4c5c4a57eb4e074f8e905630395ec03a0b58f78c367c5c5fd45f5617150` et
  `974a89b5e2abedb3fd1c6e353c7c47081a67ebf480206c0ec1a10b3897c964d0` ; tous les imports Maven
  de resources vers `runtime-monolith` ont disparu.
- `PrimaryMigrationsPostgresTest` prouve le bootstrap propre V1–V15 et la validation d'une base
  existante V1–V15 sans repair, mismatch, doublon ni réexécution.
- `runtime-monolith`, `supra-worker-balance-calculation-events-spring`,
  `shared-supra-dispatcher-projection` et `infra-event-publisher-spring` sont physiquement absents
  du reactor et du repository ; leurs configurations, tests, métriques et surfaces Compose actives
  ont été retirés.
- `Pcl6MonolithAbsenceTest` verrouille en permanence l'absence des modules et types legacy,
  l'identité/unicité de V1–V3, le zéro accès runtime aux quatre tables conservées jusqu'à PCL.8,
  l'absence de `DROP TABLE` prématuré et la présence des responsabilités canoniques.
- `docker compose -f docker-compose.distributed.yml config --quiet` est valide avec la version de
  pipeline requise ; Prometheus et Grafana ciblent les runtimes supportés.
- `./mvnw clean test` vert sur les 44 modules survivants le 2026-09-28 : Web, Command, Event,
  ProjectionTask et LKV démarrent avec PostgreSQL ; Command/write model, EPT, READ_POT,
  POT_BALANCES, LKV et exact READ_POT restent couverts ; 70 preuves `architecture-tests` passent.
- Aucun fichier SQL n'a été modifié et aucune migration destructive n'a été ajoutée.
- audit fonctionnel final au HEAD `70bace98fc08759532c691f8c8e80ddc9411eee8` :
  `NO BLOCKING FINDING`; workflow GitHub Actions `Pocoma CI`, job `build-and-test`, terminé avec la
  conclusion `success` ([run 36449939333](https://github.com/kartaguez/pocoma/actions/runs/36449939333)).

### What becomes removable next

- `projection_tasks_legacy` et `pot_balance_*` au PCL.8 ;
- seuls les résidus structurels sans responsabilité fonctionnelle relèvent encore du sweep PCL.7.

## PCL.7 — Module/dependency collapse

### Status

`REVIEW`

### Objective

Effectuer le sweep structurel final après PCL.1–PCL.6 afin qu'aucun module, dependency edge,
auto-configuration, property, fixture ou resource ne conserve artificiellement le legacy supprimé.

### DELETE

- modules devenus vides ou exclusivement legacy ;
- entrées correspondantes du reactor racine ;
- dependencies directes/transitives inutiles et résidus `dependencyManagement` ;
- auto-configurations et `AutoConfiguration.imports` legacy ;
- properties et aliases de properties sans runtime ;
- fixtures, tests d'architecture et test dependencies qui ne couvrent qu'un chemin supprimé ;
- resource imports, profiles et packaging de runtimes supprimés ;
- documentation active, runbooks, Compose, scripts et monitoring résiduels ;
- package-level docs ou catalogs qui décrivent l'architecture abandonnée comme supportée.

Modules candidats à évaluer par reachability, sans suppression mécanique :

- `domain-pipeline` ;
- `engine-processing-task` ;
- `engine-task-execution` ;
- `locator-consumption-task` ;
- `engine-task-materialization` ;
- `engine-pipeline-lifecycle` ;
- `infra-pipeline-lifecycle-persistence` ;
- `domain-projection-legacy` ;
- remainder legacy d'`engine-read-projection` ;
- `engine-projection-read` s'il est redondant après PCL.2 ;
- `pipeline-pot` ;
- `pipeline-balance` ;

Pour `infra-persistence-jpa` : prune first; split only if concretely required.

### KEEP

- tous les modules portant une responsabilité protégée du Canon ;
- les portions canoniques des modules mixtes, éventuellement après prune/extraction ;
- EPT policy/discovery/materialization et ses guards ;
- generic Consumption ;
- canonical ProjectionTask engine/runtime ;
- READ_POT et POT_BALANCES producers ;
- frontière exacte PCL.2 ;
- LKV isolé ;
- Command/write model ;
- owner survivant des migrations ;
- tests permanents définis dans le Canon.

### Prerequisites

- PCL.1 à PCL.6 sont `DONE` ou leurs suppressions structurelles atomiques sont explicitement
  incluses et déjà prouvées.
- Le graphe de modules et les usages de classes sont rescannés au HEAD réel.
- Les tables ne sont pas encore droppées : PCL.8 reste séparé.

### Notes / findings

- L'audit repository-grounded du cadrage conclut `PCL.7 FRAMING CLOSURE READY` et l'audit
  transversal rétrospectif conclut `CANONICAL INFRASTRUCTURE PRESERVATION CONFIRMED`.
- Aucune capacité canonique n'a perdu son implémentation d'infrastructure concrète pendant
  PCL.1–PCL.6. Les décisions PCL.7 doivent appliquer la reachability depuis les capacités
  canoniques, et jamais la seule reachability des runtimes ou de l'injection Spring actuels.
- `engine-projection` et `shared-runtime-spring-config` sont les deux modules DELETE confirmés au
  HEAD du cadrage. La suppression d'`engine-projection` concerne exclusivement l'ancien lifecycle
  outbox porté par `BusinessEventOutboxPort`, `BusinessEventClaim`, `BusinessEventStatus` et leurs
  responsabilités associées.
- `JpaBusinessEventOutboxAdapter` est un adapter mixte à **PRUNE LEGACY HALF**. Conserver
  `BusinessEventAppendPort`, `append(BusinessEvent)`, le mapping et la sérialisation durables, le
  `repository.save`, les trace metadata, `JpaBusinessEventOutboxEntity` et l'accès d'append à
  `business_event_outbox`. Supprimer `BusinessEventOutboxPort`, `BusinessEventClaim`,
  `BusinessEventStatus`, `claimPending`, les transitions `markAccepted` / `markRunning` /
  `markDone` / `markFailed`, `heartbeat`, `release`, `countPendingOrClaimed`, leurs requêtes
  repository exclusives et leurs tests legacy-only. Ne pas splitter l'adapter sauf nécessité
  technique concrète découverte pendant l'implémentation.
- `infra-persistence-jpa` est **PRUNE**, jamais DELETE ni split par défaut. Il reste owner de
  l'infrastructure concrète de Command, du write model, des versions, identités, Events, EPT,
  generic Consumption, ProjectionTask, sources historiques READ_POT/POT_BALANCES et migrations
  primaires. En particulier, `JpaProjectedExpenseAdapter.loadActiveAtVersion` et
  `JpaPotShareholdersAdapter.loadActiveAtVersion` sont des lectures historiques canoniques, pas de
  la Query legacy.
- Dans `engine-consumption`, le vieux cluster `ClaimPort` et les anciens use cases/services
  `TryAcquire` / `Complete` / `Fail` / `Release` sont des résidus DELETE confirmés. La capacité
  standalone d'abandon reste un prune candidate à confirmer localement. Conserver impérativement
  `ConsumptionLifecyclePersistencePort`, `ConsumptionQueryPort`,
  `ConsumptionProvenancePersistencePort`, acquire, lease/takeover/fencing, `lockCurrentClaim`,
  `tryTerminalize`, `handleFailure`, retry scheduling, provenance, leurs wrappers transactionnels
  et leurs implémentations JPA/JDBC.
- `engine-projection-read`, `ProjectionReadPort`, `JdbcProjectionStoreAdapter`,
  `ExactProjectionReadService`, `ExactProjectionReads`, `ReadPotService` et `ReadPotInterpreter`
  restent protégés. L'exact read est volontairement recomposable et possède une implémentation
  PostgreSQL réelle malgré l'absence de Query runtime ou de composition HTTP.
- `engine-read-projection` reste protégé sans split, rename ou fusion cosmétique : il porte Latest
  Known Version et `HistoricalPotSnapshotSource`.
- Les sources historiques des producers restent protégées : `HistoricalPotSnapshotSource`,
  `JpaHistoricalPotSnapshotSourceAdapter`, `HistoricalPotBalanceSourcePort`,
  `JpaHistoricalPotBalanceSourceAdapter` et les repositories Pot/Expense/Shareholder nécessaires
  aux lectures exactes. Un nom `findActiveAtVersion` ou `loadActiveAtVersion` ne constitue pas une
  preuve de legacy.
- Les prunes locaux restants doivent conserver les définitions/schemas READ_POT et POT_BALANCES,
  les policies/guards Command, le coeur métier Pot et toute primitive encore atteinte depuis une
  capacité canonique. `AuthProjectionDefinition`, les anciennes capacités Query/read
  authorization sans consumer et les types réellement orphelins restent des candidats à confirmer
  par un scan frais.
- Le cluster `PocomaObservation` / `NoopPocomaObservation` / `ProjectionObservationContext` et ses
  metadata associées est un prune candidate sans caller ; `TraceContext`, `TraceContextHolder` et
  toute infrastructure appelée par les chemins canoniques doivent survivre.
- Les edges `engine-core → domain-pot-policy`,
  `infra-read-persistence → infra-projection-persistence` et
  `runtime-event-consumption-worker → engine-projection-task` sont candidats respectivement à
  suppression ou passage en test scope. Les deux derniers ne sont importés que par les tests au
  HEAD du cadrage. Toute dependency Spring, auto-configuration, migration ou resource doit être
  vérifiée séparément des alertes `dependency:analyze`.
- Les résidus de configuration confirmés incluent `pocoma.projection.worker.enabled=false`,
  `POCOMA_QUERY_BALANCE_PIPELINE_ID`, l'exigence `POCOMA_BALANCE_PIPELINE_VERSION`, les assertions
  Compose correspondantes et le commentaire PCL.4 obsolète du POM Task runtime.
- Les guards PCL.3–PCL.6 et les preuves EPT distribuées restent permanents. PCL.7 peut réduire le
  couplage topologique de `Pcl4LegacyQueryReadAbsenceTest`, `Pcl6MonolithAbsenceTest`,
  `HexagonalArchitectureTest` et `DistributedComposeConfigurationTest`, mais ne doit pas supprimer
  les invariants ou absences qu'ils protègent.
- La documentation active (`README.md`, état du read side, matrice de dépendances et ownership des
  types) doit cesser de présenter pipeline version, monolith, ancien Balance pipeline ou anciens
  owners comme supportés. Les documents explicitement historiques restent historiques.
- Les familles candidates PCL.8 ont les cinq zéros hors migrations et documentation historiques.
  PCL.7 doit rendre cette preuve reproductible sans aucun DROP. La table primaire canonique
  `pot_version_metadata` reste distincte de `pocoma_read.pot_version_metadata`, et le type de
  projection `POT_BALANCES` reste distinct de la table legacy `pot_balances`.
- Implémentation PCL.7 soumise à review : `engine-projection` et
  `shared-runtime-spring-config` ont été retirés du reactor ; l'append Event JPA, les sources
  historiques, la lecture exacte, LKV, le Projection Engine et les runtimes canoniques restent
  présents et compilés.
- Le scan local a confirmé que l'abandon standalone n'avait aucun consumer runtime : son use case,
  son wrapper transactionnel et ses mutations JPA exclusives ont été retirés. Les valeurs
  historiques `ABANDONED` restent lisibles dans le modèle persistant ; aucune migration ni table
  n'a été modifiée.
- L'edge `infra-read-persistence → infra-projection-persistence` est désormais test-only. En
  revanche, `runtime-event-consumption-worker → engine-projection-task` reste compile-scope : la
  signature concrète de l'adapter de matérialisation exposée par sa configuration Spring requiert
  `ProjectionTaskStorePort`. Le build a donc réfuté sa suppression au lieu de masquer la
  dépendance par transitivité.
- `Pcl7ModuleDependencyCollapseTest` pérennise l'absence des modules et contrats retirés, la
  présence d'implémentations concrètes protégées et le scan applicatif/configuration des familles
  de tables PCL.8. Les guards PCL.3 à PCL.6 restent inchangés et verts.
- Validation de soumission : reactor complet compilé/package (`-DskipTests`), tests unitaires
  ciblés Consumption/READ_POT/trace/policy Event verts, puis suite complète des 42 modules concernés
  verte avec PostgreSQL/Testcontainers, runtimes Command/Event/ProjectionTask/LKV et preuves EPT ;
  `architecture-tests` passe 74 tests sans échec. Le run complet du 2026-09-28 termine en
  `BUILD SUCCESS` en 1 min 57 s.

### Exit criteria

- Le reactor ne contient aucun module vide ou exclusivement legacy.
- Chaque dependency interne restante justifie un import/runtime/resource réel protégé.
- Aucun runtime supporté ne charge une auto-configuration legacy.
- Aucune property ne peut réactiver un chemin supprimé.
- Aucun test ne maintient uniquement la compilation d'une architecture abandonnée.
- Les opérations et documents actifs ne présentent que les runtimes supportés.
- Les scans de tables PCL.8 ne trouvent plus de références code/configuration.

### Tests / proofs

- reactor Maven complet ;
- tests d'architecture mis à jour pour les frontières survivantes ;
- boot/smoke de chaque runtime supporté ;
- dependency analysis et recherche des artifactIds candidats ;
- recherche des packages, properties, class names et resource paths legacy ;
- preuves permanentes EPT, exact READ_POT, producers, LKV et Command ;
- vérification que les seuls noms de tables DROP restants sont dans migrations historiques,
  documentation historique ou future migration de destruction.

### What becomes removable next

- toutes les tables et indexes dont readers, writers, mappings, native SQL refs et runtime deps
  sont désormais nuls ;
- PCL.8 peut écrire les nouvelles migrations de destruction sans couplage applicatif résiduel.

## PCL.8 — Database demolition

### Status

`TODO`

### Objective

Supprimer physiquement le schéma legacy après disparition effective de tous ses readers, writers,
mappings et runtimes, au moyen de nouvelles migrations uniquement.

### DELETE

Après preuve des cinq zéros, créer des migrations de destruction pour les structures encore
présentes parmi :

- `tasks_4_pipeline` et ses indexes/constraints ;
- `projection_tasks_legacy` et ses indexes/constraints ;
- `pocoma_control.projection_serving_selections` puis
  `pocoma_control.pipeline_version_activations` dans l'ordre des foreign keys ;
- `pocoma_read.projection_artifacts` plural ;
- `pocoma_read.projection_failures` plural ;
- `pocoma_read.projection_heads` ;
- `pocoma_read.projection_invariant_violations` ;
- `pocoma_read.pot_projection_user_index` ;
- `pocoma_read.pot_projection_expense_shares` ;
- `pocoma_read.pot_projection_expenses` ;
- `pocoma_read.pot_projection_shareholders` ;
- `pocoma_read.pot_projection_snapshots` ;
- `pocoma_read.pot_version_metadata` et ses trigger/function dédiés ;
- `balance_projection_entries` puis `balance_projection_artifacts` ;
- `pot_balances`, `pot_balance_versions`, `pot_balance_projection_states` ;
- indexes, constraints, functions et triggers exclusivement attachés à ces structures.

`event_4_pipeline_materialization_status` est déjà supprimée par V10. PCL.8 vérifie son absence et
ne crée pas de drop redondant.

### KEEP

- `business_event_outbox` ;
- `projection_tasks` ;
- `pocoma_read.projection_root` ;
- `pocoma_read.projection_artifact` ;
- `pocoma_read.projection_failure` ;
- `pocoma_read.source_version_watermarks` ;
- `consumption_slots`, `consumption_claims`, `consumption_inputs`, `consumption_results` ;
- `pot_global_versions`, `pot_headers`, `shareholders`, `expense_headers`, `expense_shares` ;
- la table primaire `pot_version_metadata` et les autres structures Command/write model ;
- tables de commands, identities et outbox supportées ;
- historiques Flyway primaire et read-store ;
- toutes les migrations historiques inchangées.

### Prerequisites

- PCL.1 à PCL.7 sont `DONE`.
- Pour chaque table candidate :

```text
production readers      = 0
production writers      = 0
JPA mappings            = 0
JDBC/native SQL refs    = 0
runtime/config deps     = 0
```

- La propriété des migrations V1–V3 a été relocalisée et prouvée.
- L'ordre de drop est établi depuis les foreign keys réelles du schéma migré.
- Toute exigence d'archivage opérationnel est explicitement décidée sans restaurer de contrat
  runtime legacy.

### Exit criteria

- Toutes les tables DROP encore présentes sont supprimées par de nouvelles migrations.
- Les tables KEEP et leurs contraintes sont intactes.
- Le schéma final issu d'un upgrade pré-PCL égale le schéma final d'un clean bootstrap.
- Aucun checksum de migration historique ne change.
- Aucun runtime supporté n'émet de requête vers une structure supprimée.
- EPT est la seule chaîne supportée durable Event → durable Projection.
- Le Global Definition of Done du Canon est satisfait.

### Tests / proofs

- test d'upgrade depuis un dump/base à la dernière version pré-PCL ;
- clean bootstrap primaire et `pocoma_read` ;
- comparaison structurelle des schémas finaux ;
- assertions de présence des tables KEEP ;
- assertions d'absence des tables DROP ;
- tests de migration Flyway et checksums historiques ;
- tests PostgreSQL Command/write model ;
- chaîne distribuée EPT complète READ_POT et POT_BALANCES ;
- exact READ_POT interne ;
- LKV jusqu'à `source_version_watermarks` ;
- scans finaux JPA/JDBC/native SQL et runtime configuration.

### What becomes removable next

- rien dans le périmètre PCL : ce lot clôt le step ;
- les futures Query peuvent être conçues séparément sur le store canonical sans contrainte de
  compatibilité historique.

## Maintenance rule

- Avant de commencer un lot, passer son statut de `TODO` à `IN_PROGRESS` et faire pointer
  `Current lot` vers lui.
- Lorsque son implémentation est terminée et soumise à audit, passer son statut à `REVIEW`.
- Après audit accepté et corrections, passer son statut à `DONE`.
- Ne commencer un lot dépendant que lorsque ses prérequis sont `DONE`.
- Inscrire les écarts repository-grounded dans `Notes / findings` du lot concerné.
- Si un écart modifie l'architecture cible, mettre aussi à jour `Step_Canon.md`.
- Le Canon ne porte pas l'avancement ; le Plan ne doit pas devenir une spécification divergente.
- Les suppressions SQL restent exclusivement dans PCL.8, sauf impossibilité technique démontrée et
  modification explicite du Canon.

Pour tout travail PCL, Codex commence par lire :

```text
docs/steps/PCL/Step_Canon.md
docs/steps/PCL/Step_Plan.md
```

puis les preuves EPT et le code strictement nécessaires au lot courant.
