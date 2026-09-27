# PCL — Projection Chain Legacy Cleanup

Ce document est la référence architecturale normative du step PCL. Il fixe ce qui doit disparaître,
ce qui doit survivre et les preuves requises. L'avancement est suivi dans
[`Step_Plan.md`](Step_Plan.md).

PCL prend comme baseline le step EPT clôturé :

- [`../EPT/Step_Canon.md`](../EPT/Step_Canon.md) reste l'autorité sur les invariants
  `Event → ProjectionTask` ;
- [`../EPT/Step_Plan.md`](../EPT/Step_Plan.md) en porte les preuves livrées ;
- [`../EPT/Legacy_Cleanup_Inventory.md`](../EPT/Legacy_Cleanup_Inventory.md) est un inventaire
  factuel d'entrée, pas une autorité supérieure au présent Canon.

## 1. Purpose

PCL est le step destructif post-EPT. Il retire les architectures de projection et de Query
abandonnées maintenant que la chaîne canonique Event → Projection est autoritaire et prouvée.

PCL n'est ni une migration de compatibilité du Query historique, ni un redesign de Query, ni une
occasion d'introduire une nouvelle architecture. Sa responsabilité est de :

- préserver la chaîne canonique et les fonctionnalités explicitement protégées ;
- rendre réelle l'autorité unique d'EPT en supprimant les chemins concurrents ou morts ;
- supprimer le code, les runtimes, les dépendances, la configuration, l'exploitation et les
  structures SQL qui ne justifient plus de responsabilité supportée ;
- conserver une capacité interne minimale de lecture exacte de `READ_POT` depuis le store
  canonique ;
- laisser le write model historique et Command hors de la destruction.

PCL ne préserve pas la compatibilité des anciennes APIs Query. Les futures APIs seront reconstruites
ultérieurement sur l'architecture canonique.

## 2. Canonical baseline

La chaîne autoritaire, intangible pendant PCL, est :

```text
business_event_outbox
    ↓
JdbcProjectionMaterializationDiscoveryAdapter
metadata-only event discovery
    ↓
ProjectionMaterializationPolicy
    ↓
Consumption
EVENT/[eventId]
×
PROJECTION_TASK_MATERIALIZER/[projectionType]
    ↓
ProjectionTaskStore.ensure(ProjectionKey)
    ↓
projection_tasks
    ↓
Consumption
PROJECTION_TASK/[ProjectionKey]
×
PROJECTION_EXECUTOR/[projectionType]
    ↓
ProjectionTaskConsumptionOrchestrator
    ↓
ProjectionEngineService
    ↓
JdbcProjectionStoreAdapter
    ↓
pocoma_read.projection_root
pocoma_read.projection_artifact
pocoma_read.projection_failure
```

PCL doit conserver les invariants EPT déjà établis : policy exhaustive, discovery metadata-only,
identités exactes de Consumption, idempotence de `ProjectionTaskStore.ensure`, transaction courte,
fencing du Claim courant, replay, takeover et production canonique de `READ_POT` et
`POT_BALANCES`.

Il ne doit exister à la fin de PCL qu'un seul chemin de production supporté d'un Event durable vers
une Projection durable dans ce périmètre.

## 3. Protected responsibilities

Les responsabilités suivantes sont protégées et ne peuvent être supprimées, contournées ou
réimplémentées par un chemin legacy :

- `business_event_outbox` comme source durable des Events métier ;
- generic Consumption, notamment `consumption_slots`, `consumption_claims`,
  `consumption_inputs` et `consumption_results` tant que les consumers supportés les utilisent ;
- `projection_tasks` comme queue canonique identifiée par `ProjectionKey` ;
- le runtime canonique `ProjectionTask`, dont `CanonicalProjectionTaskRuntimeConfiguration` ;
- `ProjectionTaskConsumptionOrchestrator` et `ProjectionEngineService` ;
- `ProjectionWritePort` / `JdbcProjectionStoreAdapter` ;
- `pocoma_read.projection_root`, `projection_artifact` et `projection_failure` ;
- le producer canonical `READ_POT` ;
- le producer canonical `POT_BALANCES` ;
- Latest Known Version ;
- le write model historique et Command.

Une classe, un module ou un adapter mixte doit être nettoyé chirurgicalement ou scindé si sa
suppression en bloc détruirait une de ces responsabilités. Le nom legacy d'un module ne suffit pas
à autoriser sa suppression ; inversement, la présence d'une responsabilité protégée dans un module
ne protège pas ses autres contenus.

## 4. Minimal supported read capability

PCL protège une seule capacité read-side : lire une projection `READ_POT` canonique pour un `potId`
et une version explicite, la valider et l'interpréter en modèle Pot.

La frontière fonctionnelle est exactement :

```text
ProjectionKey(
    projectionType   = READ_POT,
    targetObjectType = POT,
    targetObjectId   = potId,
    targetVersion    = explicitVersion
)
    ↓
ProjectionReadPort
    ↓
JdbcProjectionStoreAdapter
    ↓
pocoma_read.projection_root / projection_artifact / projection_failure
    ↓
ReadPotProjectionDefinition validation
    ↓
READ_POT interpretation
    ↓
Pot view/model
```

Le store canonique identifie cette lecture sans ambiguïté par la clé unique :

```text
projection_type
target_object_type
target_object_id
target_version
```

Cette capacité est interne. PCL ne garantit pas et ne doit pas introduire :

- une API HTTP Query ;
- la compatibilité de `GET /api/pots/{potId}?version=...` ;
- une projection `AUTH` ;
- un modèle d'autorisation Query futur ;
- la résolution `current`, `latest` ou une version implicite ;
- les listes de Pots, Expenses ou Balances ;
- Balance Query ou `ListUserPotBalances` ;
- des indexes Query spécialisés ;
- pipeline, generation, lifecycle ou serving selection.

Le chemin historique `PotsQueryController → GetPotUseCase → GetPotService → JpaPotQueryAdapter`
n'est pas protégé.

`ExactProjectionReadService`, `ProjectionReadPort`, `JdbcProjectionStoreAdapter` et
`ReadPotInterpreter` prouvent qu'une frontière canonique existe déjà partiellement. PCL doit
conserver la plus petite responsabilité propre qui satisfait le flux ci-dessus. Il ne doit pas
présumer que le module physique `engine-projection-read` doit survivre, ni décider sa suppression
avant le réexamen local du début de PCL.2.

## 5. Latest Known Version

Latest Known Version reste une fonctionnalité supportée, indépendante de l'ancien modèle pipeline :

```text
business_event_outbox
    ↓
LatestKnownVersion event discovery
    ↓
Consumption
EVENT/[eventId]
×
SOURCE_VERSION_WATERMARK/[]
    ↓
AdvanceLatestKnownVersionService
    ↓
LatestKnownVersionPersistencePort
    ↓
pocoma_read.source_version_watermarks
```

LKV ne dépend fonctionnellement ni de `domain-pipeline`, ni du lifecycle, ni du serving, ni du vieux
Task runtime, ni du vieux scheduling Event, ni des anciens stores de projection.

Les primitives neutres actuellement placées dans des modules legacy, notamment
`LatestKnownVersion`, doivent être déplacées ou extraites avant suppression de leurs modules hôtes.
Un couplage Maven dû au mélange de classes legacy et neutres ne justifie pas de conserver le module
legacy entier.

## 6. Historical write model

Le write model historique et le comportement Command sont hors destruction PCL. Sont notamment
protégés :

- `pot_global_versions` ;
- `pot_headers` ;
- `shareholders` ;
- `expense_headers` ;
- `expense_shares` ;
- les tables de version, commande, identité et outbox encore requises par le write path ;
- les adapters historiques utilisés comme inputs des producers canoniques.

`READ_POT` et `POT_BALANCES` chargent aujourd'hui leurs inputs historiques depuis ce write model.
La suppression d'une ancienne Query adapter n'autorise jamais la suppression de sa table si cette
table appartient aussi au write model ou alimente un producer canonical.

La table primaire `pot_version_metadata` doit être distinguée de
`pocoma_read.pot_version_metadata`, qui appartient à l'ancien read store.

## 7. Officially abandoned architectures

Les familles suivantes sont officiellement abandonnées. Elles doivent être supprimées lorsque les
prérequis de leur lot sont satisfaits ; elles ne doivent pas être migrées pour préserver leur ancien
contrat.

### 7.1 Legacy Event scheduling/materialization

- `EventConsumptionLocator` legacy ;
- `ScheduleProjectionTasksForEventUseCase` et `engine-task-creation` ;
- relevance/strategy registries pipeline ;
- `JpaEventConsumptionDiscoveryAdapter` legacy ;
- `JpaTaskCreationAdapter` ;
- `CanonicalProjectionTaskScheduler` et `MeteredProjectionTaskScheduler` ;
- l'ancien dual-write vers les Tasks pipeline/canoniques ;
- `engine-task-materialization` s'il ne conserve aucune responsabilité après le retrait de ses
  consumers.

`event_4_pipeline_materialization_status` est une structure historique déjà supprimée par la
migration V10. PCL doit supprimer ses références résiduelles éventuelles et vérifier son absence ;
il ne doit pas créer une seconde migration destinée à dropper une table déjà absente.

### 7.2 Legacy projection task branches

- `projection_tasks_legacy` ;
- `BuildProjectionTasksUseCase` / `ExecuteProjectionTasksUseCase` ;
- les adapters, repositories, métriques et workers associés.

Cette famille est distincte de `projection_tasks`, qui est protégée.

### 7.3 Legacy Task runtime

- `TaskConsumptionRuntimeConfiguration` et ses anciennes properties ;
- `tasks_4_pipeline` ;
- `engine-processing-task` ;
- `engine-task-execution` ;
- `locator-consumption-task` ;
- anciens Task DTOs, mappers, handlers et registries ;
- anciens pipelines Task `READ_POT` et Balance.

Le module `runtime-task-consumption-worker` contient aussi la composition canonique et ne doit pas
être supprimé en bloc.

### 7.4 Pipeline lifecycle, generation and serving

- `domain-pipeline` ;
- `engine-pipeline-lifecycle` ;
- `infra-pipeline-lifecycle-persistence` ;
- activation gates et generation registries ;
- `pipeline_version_activations` ;
- `projection_serving_selections` ;
- sélection Query serving-aware et mutations lifecycle.

### 7.5 Historical Query/read architecture

- anciennes Query Pot, Expense et Balance ;
- `GetPotBalancesUseCase`, `ListUserPotBalancesUseCase` et leurs ports ;
- anciens controllers/endpoints et DTOs exclusivement Query ;
- current-version semantics et listings ;
- anciens indexes Query ;
- Query pipeline/generation/serving-aware.

### 7.6 Immutable Balance store

- `balance_projection_artifacts` ;
- `balance_projection_entries` ;
- `JpaImmutableBalanceProjectionAdapter` ;
- `JpaImmutablePotBalancesQueryAdapter`.

Le producer canonical `POT_BALANCES` ne dépend pas de cette famille et doit survivre.

### 7.7 Old READ_POT stores

- `pocoma_read.projection_artifacts`, `projection_failures`, `projection_heads` et
  `projection_invariant_violations` ;
- `pot_projection_snapshots`, `pot_projection_shareholders`, `pot_projection_expenses`,
  `pot_projection_expense_shares` et `pot_projection_user_index` ;
- `pocoma_read.pot_version_metadata` ;
- metadata, fragment and index adapters liés à ces tables.

Les noms pluriels legacy doivent rester distingués des tables canoniques singulières
`projection_artifact` et `projection_failure`.

### 7.8 Monolith and mutable Balance store

- le runtime fonctionnel `runtime-monolith` ;
- `supra-worker-balance-calculation-events-spring` ;
- l'ancien `ComputePotBalancesUseCase` et son worker ;
- `pot_balance_projection_states`, `pot_balance_versions` et `pot_balances` ;
- métriques, Compose, scripts, monitoring et documentation exclusivement monolith/legacy.

PCL ne finance aucune migration de ce monolith vers EPT.

## 8. Monolith migration ownership constraint

La suppression du runtime monolith et la suppression physique du module `runtime-monolith` sont
deux opérations distinctes.

Le module possède encore physiquement les migrations historiques V1–V3, importées comme ressources
par plusieurs runtimes survivants et par l'infrastructure de tests. Avant de supprimer le module :

- déplacer la propriété physique de V1–V3 vers un emplacement survivant ;
- mettre à jour uniquement les resource edges qui les assemblent ;
- préserver leurs noms, ordre, checksums et contenu historique ;
- prouver l'upgrade d'une base existante ;
- prouver le bootstrap d'une base vide.

Les migrations historiques ne doivent jamais être réécrites, copiées avec des identités Flyway
concurrentes ou supprimées. Le déplacement est une opération de propriété de ressource, pas une
nouvelle définition du schéma historique.

## 9. `domain-pipeline`

Aucun type de `domain-pipeline` n'est requis par :

- EPT ;
- `ProjectionEngineService` ;
- le producer canonical `READ_POT` ;
- le producer canonical `POT_BALANCES` ;
- LKV ;
- l'exact read `READ_POT` minimal ;
- le write/Command model.

`PipelineId`, `PipelineDefinition`, `PipelineVersionDefinition`, `VersionApplicability`,
`PipelineDefinitionRegistry` et `PocomaPipelineDefinitions` appartiennent au modèle abandonné.
Les dépendances Maven ou imports résiduels doivent être supprimés ou les primitives neutres voisines
doivent être extraites. Ils ne constituent pas une responsabilité canonique protégée.

La cible de PCL.5 est zéro import supporté depuis `domain-pipeline`, sous réserve d'un scan frais au
début du lot confirmant que le repository n'a pas acquis une nouvelle responsabilité protégée.

## 10. `engine-projection-read`

PCL ne fixe pas le sort du module par son nom.

Au début de PCL.2, l'implémentation doit choisir, selon le code réel, entre :

- conserver et réduire `engine-projection-read` s'il forme la plus petite frontière exacte propre ;
- garder seulement les primitives/services canoniques nécessaires dans une autre frontière
  survivante ;
- supprimer le module s'il devient redondant après ce collapse.

La règle normative est :

> Preserve the smallest clean canonical exact-read responsibility.

Le résultat ne doit conserver ni `AUTH`, ni HTTP, ni current/list semantics, ni Balance Query, ni
pipeline/generation/serving.

## 11. Maven and module policy

La suppression fonctionnelle est incomplète tant que le graphe Maven ou le reactor conserve des
branches exclusivement legacy.

Chaque lot doit retirer, dans son périmètre :

- les sources et tests exclusivement legacy ;
- les dependency edges devenus inutiles ;
- les entrées de reactor des modules vides ;
- les entrées `dependencyManagement` sans consumer ;
- auto-configurations, properties et resources legacy ;
- fixtures et test dependencies qui maintiennent artificiellement un module ;
- imports de ressources depuis un module condamné.

Un module mixte doit être pruné avant d'envisager un split. Pour `infra-persistence-jpa`, la règle
est : **prune first; split only if concretely required**.

La présence d'un module dans des tests d'architecture ne le protège pas. Les guards doivent être
réécrits pour protéger les frontières survivantes, pas pour conserver la topologie historique.

## 12. Database policy

Les drops SQL constituent le dernier lot. Avant tout drop :

```text
production readers      = 0
production writers      = 0
JPA mappings            = 0
JDBC/native SQL refs    = 0
runtime/config deps     = 0
```

Le code, les runtimes, les bean graphs, les opérations et les dépendances doivent disparaître avant
la table correspondante.

Les migrations historiques existantes sont immuables. Les suppressions doivent être portées par de
nouvelles migrations ordonnées selon les foreign keys et placées sous une propriété survivante.

PCL.8 doit prouver séparément :

- l'upgrade depuis une base pré-PCL portant l'historique complet ;
- le bootstrap d'une base vide en rejouant migrations historiques puis migrations de destruction ;
- la présence et le fonctionnement des tables KEEP ;
- l'absence des tables DROP.

Les données legacy n'ont pas de contrat fonctionnel de migration. Une obligation explicite
d'archivage ou d'exploitation découverte au début de PCL.8 doit être traitée comme une contrainte
opérationnelle, pas comme la restauration d'un chemin runtime abandonné.

## 13. Permanent tests and architectural guards

PCL doit conserver ou renforcer des preuves permanentes pour :

- l'exhaustivité de `ProjectionMaterializationPolicy` ;
- la discovery metadata-only, sans chargement de `payload_json` ;
- l'idempotence, le replay et le backfill par policy courante ;
- le fencing et le takeover des Claims ;
- l'unicité du chemin supporté Event → `projection_tasks` ;
- l'exécution canonique `projection_tasks` → Projection ;
- `ProjectionEngineService` comme moteur de préparation ;
- le producer canonical `READ_POT` ;
- le producer canonical `POT_BALANCES` ;
- l'exact read interne `READ_POT / POT / potId / explicitVersion` ;
- la validation des artifacts et leur interprétation en modèle Pot ;
- la chaîne LKV jusqu'à `source_version_watermarks` ;
- l'absence de dependency edges et bean roots legacy ;
- l'upgrade et le clean bootstrap du schéma final.

Les tests exclusivement associés à une architecture supprimée doivent disparaître. Ils ne sont pas
des preuves permanentes. Un test de démarrage d'un vieux runtime ne protège aucune fonctionnalité.

## 14. General deletion rule

Dans la zone durable Event → durable Projection, tout composant hors chaîne canonique doit justifier
une responsabilité fonctionnelle encore supportée.

Les affirmations suivantes ne justifient jamais la conservation d'un composant :

- il compile ;
- il a des tests ;
- Spring peut le démarrer ;
- une property peut l'activer ;
- Compose le référence ;
- un script, dashboard, runbook ou document le mentionne ;
- une table ou des données existent ;
- un POM dépend encore de son module ;
- il a appartenu à une architecture historiquement importante.

Un composant legacy est supprimé avec ses callers, callees exclusifs, tests exclusifs, bindings,
properties, resources, dépendances, opérations et tables lorsque les responsabilités protégées ne
l'utilisent plus.

## 15. Global Definition of Done

PCL est `DONE` lorsque :

- EPT est la seule architecture de production supportée de durable Event à durable Projection dans
  ce périmètre ;
- les deux producers canoniques `READ_POT` et `POT_BALANCES` restent opérationnels ;
- la capacité interne exacte `READ_POT / POT / potId / explicitVersion` lit, valide et interprète le
  store canonique ;
- LKV reste opérationnel sans dépendance pipeline/lifecycle/serving ;
- le write/Command model reste opérationnel ;
- les anciens Event scheduling, Task runtime, Query/read paths, lifecycle/serving, monolith et
  stores Balance ont disparu du code actif et des bean graphs ;
- les modules, dependencies, auto-configurations, properties, tests et resources exclusivement
  legacy ont disparu ;
- Compose, scripts, monitoring, runbooks et documentation active n'exposent plus les runtimes
  abandonnés ;
- la propriété des migrations historiques appartient à un module survivant ;
- les tables legacy sont physiquement absentes après de nouvelles migrations de destruction ;
- un upgrade pré-PCL et un clean bootstrap produisent le même schéma final ;
- les preuves EPT, canonical Task execution, exact READ_POT et LKV sont vertes.

Le résultat final ne doit pas seulement désactiver le legacy : il doit le rendre physiquement absent
du code actif, des runtimes, du graphe Maven, des opérations et du schéma.
