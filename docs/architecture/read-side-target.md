# Architecture canonique du Read side et des projections

## 1. Statut, autorité et portée

Ce document est la référence normative pour la refonte du Read side à partir de la branche
`v2-make-it-pull`. Il remplace la cible précédente centrée sur les générations de pipelines,
`LatestKnownVersion`, les heads et la sélection d'une pipeline serving. Les documents de plans Lot 7
et les runbooks existants restent utiles comme historique de livraison, mais ne prévalent pas sur ce
document lorsqu'ils conservent cet ancien modèle.

La cible décrite ici couvre :

- le domaine générique d'une projection ;
- son contrat canonique et sa validation ;
- les ports universels de lecture et d'écriture ;
- la production d'une projection à partir de l'état canonique versionné du Write side ;
- la lecture et la revalidation avant exposition ;
- la transformation ultérieure d'un Event acquis en tâches de projection ;
- le raccordement aux mécanismes génériques de consommation déjà livrés.

Le document décrit une cible et un plan à challenger. Il n'autorise aucune implémentation avant la
validation de ce plan.

## 2. Audit de `v2-make-it-pull`

### 2.1 Flux effectivement présents

Deux chemins de production coexistent actuellement.

Le chemin `READ_POT` est :

```text
RecordedEvent
  -> EventConsumptionLocator
  -> ScheduleProjectionTasksForEventService
  -> PotEventPipelineRelevance + PotTaskCreationStrategy
  -> RecordedTask(READ_POT)
  -> TaskConsumptionLocator
  -> ProjectPotRecordedTaskMapper
  -> ExecutePotProjectionTaskHandler
  -> ReconstructPotProjectionService
  -> ProjectionMaterializationService
  -> JdbcProjectionMetadataAdapter + JdbcPotProjectionArtifactWriter
```

Le chemin `POT_BALANCES` est :

```text
RecordedEvent
  -> EventConsumptionLocator
  -> ScheduleProjectionTasksForEventService
  -> BalanceEventPipelineRelevance + BalanceTaskCreationStrategy
  -> RecordedTask(COMPUTE_BALANCES_FOR_VERSION)
  -> TaskConsumptionLocator
  -> ComputeBalancesRecordedTaskMapper
  -> ExecuteBalanceProjectionTaskHandler
  -> CalculatePotBalancesAtVersionService
  -> JpaImmutableBalanceProjectionAdapter
```

Les deux flux réutilisent déjà la bonne infrastructure opérationnelle : `domain-consumption`,
`engine-consumption`, `orchestrator-consumption`, `supra-consumption-worker`, les locators Event/Task,
les slots, claims, leases, retry/failure policies, le polling et les runtimes Event/Task. Cette
mécanique doit rester extérieure au domaine projection.

### 2.2 Inventaire et rattachement à la cible

| Zone actuelle | Éléments réels | Constat pour la cible |
|---|---|---|
| Domaine projection | `domain-projection`: `ProjectionIdentity`, `ProjectionGenerationIdentity`, `ProjectionArtifactDescriptor`, `ProjectionFailure`, `ProjectionHead`, `ProjectionStatus`, `PotProjection` | Bonne localisation de module, mais identité couplée à `PipelineDefinition`, artifact typé Pot, statut/head/failure selon l'ancien modèle |
| Contrat de projection | Constructeurs de `PotProjection*`, contraintes SQL V5, invariants de `BalanceProjectionArtifact` | Validation dispersée ; aucune `ProjectionDefinition` canonique, aucun catalogue d'`ArtifactType`, aucune validation JSON commune écriture/lecture |
| Application de production | `engine-read-projection`: `ReconstructPotProjectionService`, `ProjectionMaterializationService`, `ProjectionFailureService` ; `engine-projection-balance`: `CalculatePotBalancesAtVersionService` | Briques réutilisables, mais pas de use case universel `ProjectionTask -> Projection`, pas de registre simple par `ProjectionType` |
| Sources Write versionnées | `HistoricalPotSnapshotSource` / `JpaHistoricalPotSnapshotSourceAdapter`, `HistoricalPotBalanceSourcePort` / `JpaHistoricalPotBalanceSourceAdapter`, et ports granulaires `PotHeaderPort`, `PotShareholdersPort`, `ExpenseHeaderPort`, `ExpenseSharesPort` | La lecture exacte existe déjà. La cible doit composer ces ports ; elle ne justifie pas un nouveau `GoldenSourceLoader` |
| Persistence READ_POT | `infra-read-persistence`, migrations V2–V6, `JdbcProjectionMetadataAdapter`, `JdbcPotProjectionArtifactWriter` | Transaction read-store déjà disponible, mais metadata `artifact/failure/head` et tables physiques Pot spécifiques |
| Persistence Balance | `balance_projection_artifacts`, `balance_projection_entries`, `JpaImmutableBalanceProjectionAdapter`, `JpaImmutablePotBalancesQueryAdapter` dans `infra-persistence-jpa` | Second stockage spécifique, dans le primaire, sans root/result universels ni validation de lecture |
| Création de tâches | `engine-task-creation`: `EventPipelineRelevanceRegistry`, `TaskCreationStrategyRegistry`, `ScheduleProjectionTasksForEventService` | Fonctionne, mais multiplie relevance + strategy + `PipelineVersionDefinition` pour produire une tâche minimale |
| Exécution de tâches | `engine-task-execution`: mapper registry, handler registry, `ExecuteTaskService` | Routage générique réutilisable pendant la migration ; la spécialisation par pipeline doit céder la place au use case de matérialisation par `ProjectionType` |
| Lifecycle pipeline | `domain-pipeline`, `engine-pipeline-lifecycle`, `infra-pipeline-lifecycle-persistence`, `ProjectionProducerCatalog` | `declared/active/serving` et applicabilité sont au centre de l'ancien chemin. Ils ne font pas partie de l'identité ni du domaine cible |
| Lecture versionnée | `engine-query`: `QueryVersionResolver`, `ProjectionReadinessQueryPort`, `ServingQueryProjectionSelectionProvider`; readers métier primaires | Resolver encore non branché aux GET, fondé sur génération serving, latest-known et état terminal ; pas de `ProjectionReadPort` universel revalidant le contenu |
| Latest-known | `LatestKnownVersion`, `AdvanceLatestKnownVersionService`, `JdbcLatestKnownVersionAdapter`, locator/runtime dédié, table `source_version_watermarks` | Mécanisme livré mais absent du nouveau modèle cœur. Il ne doit pas devenir une précondition de production ou de servabilité |
| Legacy Balance mutable | `engine-projection`, `PotBalanceProjectionState`, `JpaPotBalancesAdapter`, tables `pot_balance_*`, runtime monolith | Dépend de l'état projeté précédent ; incompatible avec le recalcul autonome `P(X,V)` |

### 2.3 Écarts structurants

L'existant viole ou ne garantit pas encore les propriétés cibles suivantes :

- l'identité canonique inclut actuellement `pipelineId + pipelineVersion`, alors que la cible est
  identifiée par le type de projection, l'objet cible, son identifiant et sa version ;
- la présence d'un descriptor ou d'une ligne Balance vaut implicitement readiness ; il n'existe pas
  de `ProjectionRoot` jouant explicitement le rôle de marqueur de complétude ;
- le résultat de production est représenté par `ProjectionFailure` ou par le lifecycle de Task, pas
  par un `projection_result` indépendant de la servabilité ;
- `READ_POT` et `POT_BALANCES` n'utilisent ni la même persistence ni les mêmes ports ;
- les artifacts sont des modèles/tables spécialisés, et non des tuples génériques
  `artifactType + logicalKey + JSON payload` ;
- la validation n'est ni décrite par une `ProjectionDefinition`, ni rejouée à la lecture ;
- la corruption d'un artifact derrière un descriptor/root n'est pas un résultat explicite distinct
  de `NOT_READY` ;
- la création Event vers Task dépend d'un catalogue de pipelines et de stratégies par génération,
  alors que le besoin cible est un mapping simple `EventType -> ProjectionType(s)` ;
- l'ancien Query Kernel associe `CURRENT` aux notions de latest-known, terminal state et serving
  pipeline, qui ne font pas partie du modèle présenté ici.

## 3. Langage canonique et modèle de domaine

### 3.1 Identité d'une projection

Une projection exacte est identifiée au minimum par :

```text
ProjectionKey = (
  projectionType,
  targetObjectType,
  targetObjectId,
  targetVersion
)
```

`targetObjectType` désigne le type d'objet canonique projeté, par exemple `POT`.
`targetObjectId` est son identifiant stable. `targetVersion` est une version canonique du Write side,
pas une version de pipeline, un offset d'Event ou un numéro de tentative.

`pipelineId`, `pipelineVersion`, worker, claim, Event déclencheur et Task durable ne font pas partie de
`ProjectionKey`.

### 3.2 ProjectionRoot

`ProjectionRoot` porte `ProjectionKey` et aucune propriété de statut `READY` ou `FAILED`. Sa présence
est le marqueur de commit fonctionnel suivant :

> La projection exacte est complètement produite, validée, persistée atomiquement et peut être
> considérée comme servable après une revalidation de lecture réussie.

L'absence du root ne dit rien, à elle seule, de la raison pour laquelle la projection n'est pas
servable.

### 3.3 ProjectionArtifact

Une projection contient zéro, un ou plusieurs `ProjectionArtifact`. Chaque artifact porte :

```text
artifactType
logicalKey
payload JSON
```

- `artifactType` a une sémantique locale à la `ProjectionDefinition` ;
- `logicalKey` identifie logiquement un artifact au sein d'une projection exacte ;
- `payload` est le contenu canonique validé par le schéma de cet `artifactType`.

L'identité persistée d'un artifact est au minimum
`ProjectionKey + artifactType + logicalKey`. Un identifiant technique peut exister pour les clés
étrangères ou la traçabilité, sans devenir un identifiant métier.

L'ordre physique des artifacts n'a aucune sémantique. Si l'ordre fait partie d'un résultat métier,
il doit être encodé et validé dans le payload ou par une clé logique explicitement ordonnée.

### 3.4 ProjectionResult

`ProjectionResult` décrit l'issue d'une production pour un `ProjectionKey` :

```text
SUCCESS
FAILED
```

Un résultat `FAILED` peut porter au minimum un code d'échec stable et les métadonnées techniques
utiles au diagnostic, sans exposer d'exception ou de stack trace comme donnée métier.

`ProjectionResult` ne dit pas si une projection est servable. Cette propriété se déduit comme suit :

| Root | Résultat FAILED | État dérivé |
|---|---:|---|
| présent | interdit | `READY` |
| absent | oui | `FAILED` |
| absent | non | `NOT_READY` |

Un root présent implique un résultat `SUCCESS`. `root + FAILED`, `root sans SUCCESS` et `SUCCESS sans
root` sont des incohérences internes de stockage, jamais des états métier normaux.

### 3.5 ProjectionDefinition

Chaque `ProjectionType` possède exactement une `ProjectionDefinition` canonique, accessible dans un
registre simple indexé par `ProjectionType`. Elle décrit :

- le `targetObjectType` accepté ;
- les `ArtifactType` autorisés ;
- pour chaque type, sa cardinalité minimale et maximale ;
- le schéma JSON du payload ;
- les règles éventuelles d'unicité de `logicalKey`, globales ou par `ArtifactType` ;
- les validations transverses indispensables entre artifacts, uniquement lorsqu'elles ne peuvent
  pas être exprimées par cardinalité, schéma et unicité.

La définition n'est ni une DSL, ni un resolver, ni un framework de projection. C'est une valeur
déclarative et un validateur associé. Une `Map<ProjectionType, ProjectionDefinition>` immuable est la
forme de registre attendue tant qu'un besoin plus riche n'est pas démontré.

La même définition et le même moteur de validation sont employés :

1. après exécution du projector, avant tout appel au port d'écriture ;
2. après chargement par le port de lecture, avant toute exposition à un use case métier.

## 4. Invariants normatifs

### 4.1 Invariant principal

> Aucune projection invalide n'entre dans le stockage canonique et aucune projection invalide n'est
> servie.

Une validation échouée avant persistence produit une issue `FAILED` selon la politique d'échec
retenue ; elle ne publie ni root ni artifact canonique. Une validation échouée après lecture signale
une incohérence interne et interdit l'exposition.

### 4.2 Atomicité et visibilité

Pour une production réussie, les éléments suivants sont publiés dans une même transaction :

```text
ProjectionRoot
+ tous les ProjectionArtifact, y compris zéro artifact
+ ProjectionResult(SUCCESS)
```

Aucun reader ne doit observer un root sans l'ensemble d'artifacts validé ni un `SUCCESS` sans root.
La transaction, les contraintes relationnelles et le niveau d'isolation de l'adapter garantissent
cette propriété ; le domaine n'implémente pas de protocole de commit distribué.

Pour un échec, `ProjectionResult(FAILED)` est persisté sans root et sans artifacts canoniques.

### 4.3 Déterminisme et autonomie

Une projection `P(X,V)` doit pouvoir être recalculée uniquement à partir :

- de l'état canonique de l'objet `X` à la version exacte `V` ;
- de la `ProjectionDefinition` de `P` ;
- du code du `Projector` associé à `P`.

Un projector ne dépend jamais :

- de l'Event ayant déclenché la tâche ;
- de `P(X,V-1)` ;
- d'une autre projection ;
- de l'ordre de traitement ;
- d'un watermark, d'un segment de continuité ou d'un état de bootstrap ;
- d'un claim, slot, worker, retry ou statut de Task.

La découverte et le traitement peuvent être ordonnés pour la pagination, l'équité ou la performance.
Cet ordre n'est jamais une condition de correction.

### 4.4 Lecture et corruption

Le port universel de lecture charge une projection exacte complète. L'application la revalide avec
la `ProjectionDefinition` correspondant à son `ProjectionType`.

- root absent et aucun résultat `FAILED` : `NOT_READY` ;
- root absent et résultat `FAILED` : `FAILED` ;
- root présent, graphe complet et valide : `READY` et exposable ;
- root présent mais résultat, cardinalité, clé, type ou payload incohérent : corruption interne.

La dernière branche ne doit jamais être rabattue sur `NOT_READY`, car cela transformerait une perte
d'intégrité en retard normal de projection.

### 4.5 Idempotence sous la même identité

Deux traitements concurrents d'un même `ProjectionKey` ne peuvent publier deux vérités différentes.
Une republication strictement équivalente peut être adoptée comme succès idempotent. Un contenu
divergent sous la même identité est une violation de déterminisme et doit être rejeté et observable,
jamais écrasé silencieusement.

### 4.6 Indépendance vis-à-vis de l'observabilité

Les composants fonctionnels de traitement introduits par cette architecture restent indépendants des
mécanismes d'observabilité. Cette règle s'applique notamment à la création
`Event -> ProjectionTask`, à la matérialisation `ProjectionTask -> Projection`, aux `Projector`, à la
validation de projection et aux use cases de lecture.

Ces composants ne dépendent directement ni de SLF4J, ni de Micrometer, ni d'OpenTelemetry, ni d'un
tracer, ni d'un `MeterRegistry`, ni d'une API d'observation injectée uniquement pour produire des
logs, métriques ou traces. Un composant fonctionnel doit pouvoir être exécuté et testé intégralement
sans infrastructure d'observabilité.

L'observation est ajoutée extérieurement, par décoration ou composition à une frontière appropriée :

```text
functional component
        ^
observed decorator
```

et non par une dépendance sortante du traitement fonctionnel :

```text
functional component -> Observation API
```

Une panne ou une absence de l'observabilité ne modifie jamais le résultat fonctionnel, la
transaction, la classification d'échec ou la décision de retry du traitement observé.

## 5. Frontières d'architecture

| Couche | Responsabilités | Ne connaît pas |
|---|---|---|
| Domaine projection | `ProjectionType`, `ProjectionKey`, `ProjectionRoot`, `ProjectionArtifact`, `ArtifactType`, `ProjectionResult`, `ProjectionDefinition`, règles de validation | SQL/JPA/Spring, Event, Task, pipeline, worker, claim, retry, HTTP |
| Application projection | `ProjectionTask`, `Projector`, registre de projectors, use case de matérialisation, ports universels read/write, traduction des erreurs applicatives | Tables, polling, leases, détails du transport Event/Task |
| Persistence | Mapping relationnel/JSON, transactions, contraintes d'unicité et d'intégrité, lecture atomique du graphe, migrations | Règles de scheduling, mapping Event, calcul métier du projector |
| Orchestration | Acquisition, claim, slots, retry, polling, workers, relecture autoritative des Event/Task durables, transaction englobante | Structure interne des artifacts, validation métier des projections |
| Exposition Read | Use cases métier, autorisation, mapping de réponse, traduction contrôlée de `NOT_READY`/`FAILED`/corruption | Tables, Task, Event, pipeline machinery |

La direction de dépendance cible est :

```text
domain-projection
       ^
application projection/read (ports)
       ^                         ^
persistence adapters       orchestration adapters
       ^                         ^
                 runtimes/supra
```

## 6. Ports et use cases cibles

### 6.1 ProjectionTask

La tâche applicative minimale porte :

```text
projectionType
targetObjectType
targetObjectId
targetVersion
```

Elle ne porte ni Event, ni payload métier à rejouer, ni projection antérieure, ni pipeline. Le format
durable d'une `RecordedTask` peut ajouter ses identifiants techniques, mais le mapper doit aboutir à
ce contrat minimal.

### 6.2 Projector

Un `Projector` est sélectionné par `ProjectionType` dans une map immuable. Il reçoit l'identité cible
et l'état canonique exact nécessaire, puis retourne une collection d'artifacts candidats. Il ne
persiste rien et ne connaît ni l'orchestration ni l'observabilité. Son instrumentation ne peut pas
l'obliger à recevoir `sourceEventType`, un contexte Event ou toute autre donnée absente de son besoin
fonctionnel.

Les sources versionnées existantes sont réutilisées derrière leurs ports :

- `HistoricalPotSnapshotSource` / `JpaHistoricalPotSnapshotSourceAdapter` couvrent déjà le snapshot
  Pot exact, y compris la metadata de version ;
- `HistoricalPotBalanceSourcePort` / `JpaHistoricalPotBalanceSourceAdapter` couvrent déjà les
  entrées Balance exactes ;
- les ports Write granulaires (`PotHeaderPort`, `PotShareholdersPort`, `ExpenseHeaderPort`,
  `ExpenseSharesPort`) restent préférables lorsqu'ils exposent la sémantique exacte nécessaire.

Un adapter de composition spécifique à un projector reste acceptable. Une abstraction générique
`GoldenSourceLoader`, un resolver de sources ou une DSL de chargement ne l'est pas sans second besoin
concret non couvert par ces ports.

### 6.3 Use case de matérialisation

Le use case `ProjectionTask -> Projection` exécute dans cet ordre :

1. valider la forme de la tâche ;
2. charger la `ProjectionDefinition` par `ProjectionType` ;
3. vérifier la compatibilité du `targetObjectType` ;
4. trouver le `Projector` dans le registre simple ;
5. recharger l'état canonique `X@V` depuis les ports Write versionnés ;
6. exécuter le projector ;
7. construire la projection candidate ;
8. la valider contre la même `ProjectionDefinition` ;
9. appeler le `ProjectionWritePort` universel pour publier atomiquement root, artifacts et `SUCCESS` ;
10. en cas d'échec terminal de production/validation, enregistrer `FAILED` sans root ni artifacts.

L'idempotence concurrente et la détection d'un contenu divergent sont garanties au niveau du port et
de l'adapter, sous verrou logique ou contrainte équivalente.

### 6.4 Ports universels

Le contrat d'écriture est unique pour tous les `ProjectionType`. Il expose conceptuellement :

```text
publishSuccess(validatedProjection)
publishFailure(projectionKey, failure)
```

Le contrat de lecture est lui aussi unique :

```text
load(projectionKey) -> PRESENT(raw projection) | FAILED(result) | NOT_READY
```

Le port retourne des données du modèle générique et ne valide pas à la place de l'application. Le
service de lecture revalide le cas `PRESENT` avant de le qualifier `READY` et de rendre la projection
exposable. Une incohérence
physique détectée par l'adapter est remontée comme corruption interne.

Des readers d'index spécialisés pourront exister pour trouver des `ProjectionKey` candidats, mais
ils ne remplacent jamais le `ProjectionReadPort` pour charger et valider la projection canonique.

## 7. Modèle logique de persistence

Le stockage canonique contient au minimum les relations logiques suivantes.

### `projection_root`

Clé unique :

```text
(projection_type, target_object_type, target_object_id, target_version)
```

La table peut porter un identifiant technique et des timestamps. Elle ne porte pas de statut
`READY/FAILED` et ne porte pas de pipeline.

### `projection_artifact`

Chaque ligne référence une identité de projection et contient :

```text
artifact_type
logical_key
payload json/jsonb
```

Une contrainte unique protège au minimum
`ProjectionKey + artifact_type + logical_key`. Les contraintes SQL protègent la forme structurelle ;
la `ProjectionDefinition` reste l'autorité pour les schémas et invariants métier.

### `projection_result`

Chaque résultat référence `ProjectionKey` et contient `SUCCESS` ou `FAILED`, avec les métadonnées
d'échec nécessaires. Les contraintes doivent rendre impossible un root avec résultat `FAILED` et
permettre de détecter tout `SUCCESS` sans root.

La publication réussie écrit les trois ensembles dans une transaction du read store. Le schéma peut
utiliser des contraintes différées ou un ordre d'insert adapté ; ce détail ne fuite pas dans le
domaine.

Les tables spécialisées (`pot_projection_*`, `balance_projection_*`) peuvent coexister pendant la
migration, mais ne sont pas le stockage canonique cible.

## 8. Event vers ProjectionTask

Cette étape vient après la matérialisation directe et la lecture générique.

Un composant applicatif transforme un Event acquis en zéro, une ou plusieurs `ProjectionTask` à
partir d'un catalogue simple :

```text
EventType -> Set<ProjectionType>
```

Pour chaque type mappé, l'identité cible est dérivée des métadonnées stables de l'Event : objet,
identifiant et version. Le contenu métier de l'Event ne devient pas une entrée du projector.

La map est explicite et exhaustive. Une fonction spécifique n'est ajoutée que si un Event ne permet
pas une dérivation directe de la cible. Les couples actuels
`EventPipelineRelevance + TaskCreationStrategy`, l'applicabilité par génération et le
`ProjectionProducerCatalog` ne sont pas conservés par inertie.

La création durable reste idempotente. La clé d'idempotence doit représenter la tâche fonctionnelle
minimale, indépendamment du worker qui la créera ou l'exécutera.

## 9. Orchestration et transactions

Les composants suivants sont conservés et réutilisés :

- `domain-consumption` pour slots, claims et leases ;
- `engine-consumption` pour acquire, exécution transactionnelle, fencing et failure handling ;
- `orchestrator-consumption.SequentialConsumptionOrchestrator` ;
- `supra-consumption-worker.ConsumptionPollingWorker` ;
- les locators Event et Task, adaptés au nouveau mapper/use case ;
- les adapters JPA de discovery, relecture de `RecordedEvent`/`RecordedTask`, lifecycle et provenance ;
- `runtime-event-consumption-worker` et `runtime-task-consumption-worker` comme points de composition.

La transaction gagnante du Task worker doit englober la publication via `ProjectionWritePort`, la
provenance de consommation et la terminalisation fencée du slot. Une perte de claim fait rollback de
l'ensemble.

Le domaine projection ne connaît aucune de ces classes. Inversement, l'orchestrateur traite le
succès ou l'échec du use case sans interpréter root, artifacts, cardinalités ou schémas JSON.

L'observabilité générique existante autour de la mécanique de consommation est réutilisée lorsqu'elle
reste pertinente. Une observation spécifique indispensable au test, à la migration ou à
l'exploitation du nouveau chemin est ajoutée dans les supra/runtimes par un décorateur ou un
composant d'observation simple. Elle ne justifie ni une dépendance depuis le domaine ou les use cases,
ni un framework générique d'observabilité. Les métadonnées techniques de corrélation peuvent rester
dans les enveloppes et frontières d'orchestration ; elles ne deviennent pas des champs fonctionnels
de `ProjectionTask` et ne sont pas transmises au `Projector` sans besoin métier.

## 10. Lecture métier et exposition

Le chemin cible d'une lecture est :

```text
requête métier
  -> résolution explicite du ProjectionKey requis
  -> ProjectionReadPort.load(key)
  -> revalidation par ProjectionDefinition
  -> mapping vers le résultat métier
  -> autorisation et exposition supra/HTTP
```

La résolution de la clé exacte et les politiques `CURRENT` éventuelles appartiennent au use case
Read, pas au domaine projection. Elles ne doivent pas réintroduire pipeline serving, watermark,
continuity segment ou bootstrap readiness dans le modèle cœur.

Les anciens readers primaires de `engine-query` restent en place jusqu'à ce qu'un use case Read
complet ait atteint la parité fonctionnelle. Le cutover HTTP est un lot ultérieur et réversible.

## 11. Ce qui est explicitement hors scope à ce stade

- un framework générique de projection, une DSL de définition ou un resolver dynamique ;
- un `GoldenSourceLoader` générique ;
- les watermarks, continuity segments, coverage, bootstrap readiness et `ProjectionHead` comme
  concepts cœur ;
- le choix final de la sémantique `CURRENT`, la pagination et les indexes de listing ;
- la refonte de l'autorisation Read ;
- le format HTTP final et la traduction précise des erreurs en statuts HTTP ;
- une API administrative de replay/backfill/rebuild ;
- un protocole de coordination entre projections ;
- des dépendances de production entre `READ_POT`, `POT_BALANCES` ou toute future projection ;
- le déploiement/cutover et la suppression physique immédiate des tables legacy ;
- une stratégie générale de versionnement de schémas au-delà des besoins des premiers
  `ProjectionType` ;
- une architecture générale d'observabilité commune au Write, au Read, à la consommation et aux
  runtimes.

La reconstructibilité depuis l'état canonique exact `X@V` est l'invariant nécessaire de cette
refonte. Aucun moteur opérationnel général de refill, replay ou rebuild n'est ajouté aux lots.

Une revue puis, si nécessaire, une refonte transversale de l'observabilité seront réalisées après la
stabilisation de la nouvelle architecture Read et la suppression de son legacy. Cette revue future
auditera les frontières alors réellement restantes du Write, du Read, de la consommation et des
runtimes avant de décider quelles abstractions sont effectivement communes. Elle n'appartient pas aux
lots fonctionnels ci-dessous. D'ici là, seules les observations extérieures strictement nécessaires
au test, à la migration, à l'exploitation et au rollback du nouveau chemin sont ajoutées.

## 12. Legacy et candidats à suppression

### 12.1 À réutiliser ou refondre

| Élément actuel | Cible |
|---|---|
| Module `domain-projection` | Propriétaire du nouveau modèle générique et des définitions, sans dépendance à `domain-pipeline` ni à `domain-pot` pour les primitives génériques |
| Module `engine-read-projection` | Application projection : tâches, projectors, validation, ports universels et services de matérialisation/lecture |
| `ReconstructPotProjectionService` | Devient ou alimente un `Projector` `READ_POT`, sans persistence ni pipeline |
| `CalculatePotBalancesAtVersionService` et `PotBalancesCalculator` | Calcul métier réutilisé derrière un `Projector` `POT_BALANCES` |
| `HistoricalPotSnapshotSource`, `HistoricalPotBalanceSourcePort` et leurs adapters JPA | Ports/adapters de lecture exacte du Write side à conserver ou rapprocher des ports granulaires existants |
| `infra-read-persistence` et `ReadStoreTransactionRunner` | Accueillent les tables et adapters read/write universels |
| `engine-task-execution` | Peut rester comme enveloppe de routage pendant le raccordement, puis être simplifié si un seul handler universel suffit |
| Stack consumption Event/Task | Conservée comme orchestration générique extérieure au domaine |
| Use cases et snapshots de `engine-query` | Conservés pour construire les lectures métier après livraison du reader générique |

### 12.2 Obsolètes dans le chemin cible ou supprimables après validation

| Élément actuel | Motif | Condition de suppression |
|---|---|---|
| `ProjectionGenerationIdentity` et dépendance de `ProjectionIdentity` à `PipelineDefinition` | La génération de pipeline ne fait plus partie de l'identité canonique | Nouveau modèle et migration des producteurs/readers |
| `ProjectionArtifactDescriptor`, `ProjectionFailure`, `ProjectionHead`, `ProjectionStatusResolver`, `ProjectionMaterializationService`, `ProjectionFailureService` dans leur forme actuelle | Remplacés par root/artifacts/result, validation canonique et ports universels | Parité des tests de matérialisation et lecture |
| `ProjectionInvariantViolation` et digest writer spécifique | Mécanisme à remplacer par le conflit générique sous `ProjectionKey`; le digest peut rester un détail d'adapter | Idempotence/divergence couverte par l'adapter universel |
| `PotProjection` et tables `pot_projection_*` comme artifact spécial | Remplacés par artifacts JSON décrits par la définition `READ_POT` | Migration ou acceptation de non-reprise des données, puis cutover reader |
| `BalanceProjectionIdentity`, `BalanceProjectionArtifact`, `BalanceProjectionPort`, `JpaImmutableBalanceProjectionAdapter`, `JpaImmutablePotBalancesQueryAdapter`, tables `balance_projection_*` | Persistence spécifique concurrente du stockage universel | `POT_BALANCES` produit et lu via les ports universels |
| `PotBalanceProjectionState`, `ComputePotBalancesService`, `JpaPotBalancesAdapter`, tables `pot_balance_*` | Calcul incrémental dépendant de N-1 | Cutover complet vers le projector exact autonome |
| `PotProjectionPipeline`, `BalancePipeline` comme définitions métier | Confondent type de projection et pipeline de déploiement | Registres `ProjectionDefinition`/`Projector` opérationnels |
| `PotEventPipelineRelevance`, `BalanceEventPipelineRelevance`, `PotTaskCreationStrategy`, `BalanceTaskCreationStrategy` | Remplacés par le mapping simple EventType -> ProjectionType(s) | Lot Event -> ProjectionTask validé |
| `ProjectPotTask`, `ComputeBalancesTask`, leurs mappers et handlers spécifiques | Remplacés par `ProjectionTask` et un handler de matérialisation universel | Task worker branché sur le nouveau use case |
| `PipelineDefinitionRegistry`, `PipelineVersionDefinition`, `VersionApplicability`, `ProjectionProducerCatalog` sur le chemin projection | N'apportent plus de différence fonctionnelle au calcul d'une projection | Vérification qu'aucun besoin opérationnel validé ne requiert plusieurs producteurs concurrents |
| `engine-pipeline-lifecycle` et `infra-pipeline-lifecycle-persistence` pour active/serving | Ne doivent pas gouverner l'identité, la production ou la lecture cible | Décision de rollout prise et anciens workers arrêtés |
| `ServingQueryProjectionSelectionProvider`, génération serving de `QueryVersionResolver`, `ProjectionReadinessQueryPort` dans sa forme actuelle | Lecture couplée à pipeline/latest-known/terminal state | Nouveau reader et use case métier complet |
| `LatestKnownVersion`, `source_version_watermarks`, locator/runtime latest-known | Hors du nouveau modèle cœur ; utilité à réévaluer pour une politique Read concrète | Politique `CURRENT` décidée et absence d'autre consommateur confirmée |
| `engine-task-materialization` et anciennes abstractions de dispatch projection encore compilées | Antérieures au pull générique et sans rôle cible | Absence de runtime/adapter utilisateur confirmée |

Les migrations Flyway historiques ne sont jamais réécrites. La suppression physique passe par de
nouvelles migrations après cutover et validation de l'absence de reader/writer actif.

## 13. Plan d'implémentation par petits lots réversibles

### Lot 1 — Domaine projection et contrats

**Objectif.** Introduire le nouveau modèle générique, deux premières `ProjectionDefinition`
(`READ_POT`, `POT_BALANCES`) et le validateur commun, sans brancher de runtime.

**Fichiers/modules probablement concernés.** `domain-projection`, ses tests, éventuellement les
POMs pour retirer progressivement `domain-pipeline`/`domain-pot` des primitives génériques.

**Invariants établis.** Identité sans pipeline ; cardinalités/types/clés/payloads vérifiables ; root
sans statut ; résultat distinct ; même validation appelable en écriture et lecture ; domaine et
validateur sans dépendance d'observabilité.

**Tests à ajouter.** Construction des valeurs ; registry duplicate/missing ; artifact interdit ;
cardinalités min/max ; clé dupliquée ; JSON invalide ; validation transverse ; projection à zéro
artifact valide lorsque la définition l'autorise.

**Critères de fin.** API domaine framework-free stabilisée, tests unitaires verts sans infrastructure
d'observabilité, aucune composition runtime modifiée, définitions revues avec des exemples JSON réels.

**Dépendances avec les lots suivants.** Bloque tous les autres lots ; n'en dépend pas.

**Legacy supprimable après validation.** Aucun : coexistence volontaire avec l'ancien modèle.

### Lot 2 — Ports universels et persistence canonique

**Objectif.** Ajouter `ProjectionWritePort`/`ProjectionReadPort`, tables root/artifact/result et
adapters transactionnels, sans migrer les producteurs.

**Fichiers/modules probablement concernés.** `engine-read-projection`, `infra-read-persistence`,
nouvelles migrations read-store, auto-configuration et tests PostgreSQL.

**Invariants établis.** Publication atomique ; états dérivés exacts ; contraintes d'identité ; succès
idempotent ; divergent rejeté ; root corrompu distingué de `NOT_READY`.

**Tests à ajouter.** Zéro/un/plusieurs artifacts ; rollback à chaque point d'écriture ; concurrence
same/same et same/different ; `FAILED` sans root ; incohérences root/result ; payload JSON intact ;
lecture exacte multi-type.

**Critères de fin.** Contract tests exécutés contre l'adapter PostgreSQL, aucun writer existant
redirigé, migrations uniquement additives.

**Dépendances avec les lots suivants.** Dépend du lot 1 ; débloque matérialisation et lecture.

**Legacy supprimable après validation.** Aucun ; les deux stockages fonctionnent en parallèle.

### Lot 3 — Use case `ProjectionTask -> Projection`

**Objectif.** Introduire la tâche minimale, le registre simple de projectors et le use case complet,
puis adapter `READ_POT` et `POT_BALANCES` en appels directs de test ou shadow.

**Fichiers/modules probablement concernés.** `engine-read-projection`,
`engine-projection-balance`, `pipeline-pot`, `pipeline-balance`, adapters historiques de
`infra-persistence-jpa`.

**Invariants établis.** `P(X,V)` ne dépend que de `X@V` et du contrat ; aucune dépendance Event/N-1/
autre projection/ordre ; validation obligatoire avant write ; échec sans artifact canonique ; tâche,
projectors et use case sans dépendance d'observabilité.

**Tests à ajouter.** Projector manquant ; mauvais target type ; source exacte absente/incohérente ;
production déterministe hors ordre ; validation rejetée ; propagation `FAILED` ; retry identique ;
test de non-interaction avec Event et projection précédente.

**Critères de fin.** Les deux types sont matérialisables directement via le même use case et le même
port ; aucun nouveau loader générique ; tests d'intégration shadow verts sans logger, tracer,
`MeterRegistry` ou API d'observation requis par le traitement fonctionnel.

**Dépendances avec les lots suivants.** Dépend des lots 1–2 ; débloque Event/Task et parité données.

**Legacy supprimable après validation.** Services de matérialisation/failure actuels dans leur forme
pipeline-centric ; handlers de calcul uniquement si aucun runtime ne les utilise encore.

### Lot 4 — Lecture générique et revalidation

**Objectif.** Ajouter le service applicatif de lecture qui charge via le port universel, revalide et
retourne `READY`, `FAILED`, `NOT_READY` ou corruption interne.

**Fichiers/modules probablement concernés.** `engine-read-projection`, `infra-read-persistence`,
tests de contrat ; aucun endpoint actif.

**Invariants établis.** Aucune projection invalide servie ; root corrompu jamais assimilé à
`NOT_READY` ; définition identique à celle de la production ; service de lecture indépendant de
l'observabilité.

**Tests à ajouter.** Mutation SQL volontaire de chaque composant ; mauvais schéma/cardinalité/clé ;
root sans result ; success sans root ; failure ; absence totale ; lecture valide pour les deux types.

**Critères de fin.** Un unique service charge et valide les deux projections ; classification des
erreurs stable ; aucun GET migré.

**Dépendances avec les lots suivants.** Dépend des lots 1–2 ; peut avancer en parallèle de la fin du
lot 3 ; bloque le use case Read métier.

**Legacy supprimable après validation.** Aucun avant cutover métier.

### Lot 5 — Event vers ProjectionTask

**Objectif.** Remplacer la planification par pipeline par un catalogue explicite
`EventType -> ProjectionType(s)` et persister les tâches minimales.

**Fichiers/modules probablement concernés.** `engine-task-creation`, `engine-processing-event`,
`pipeline-pot`, `pipeline-balance`, `infra-persistence-jpa` pour l'idempotence des Tasks.

**Invariants établis.** Event utilisé uniquement pour cibler la tâche ; un même Event peut produire
plusieurs types ; aucun payload Event requis à l'exécution ; mapping exhaustif et déterministe ;
création fonctionnelle des tâches indépendante de l'observabilité.

**Tests à ajouter.** Chaque EventType connu ; zéro/un/plusieurs types ; duplicate Event ; Tasks déjà
présentes ; mapping absent ; identité objet/version correcte ; aucune consultation du read store.

**Critères de fin.** Planification testée sans `PipelineDefinitionRegistry`, relevance ou strategy ;
ancien chemin encore désactivable pour rollback.

**Dépendances avec les lots suivants.** Dépend des lots 1 et 3 ; débloque le branchement runtime.

**Legacy supprimable après validation.** `*EventPipelineRelevance`, `*TaskCreationStrategy`,
`ProjectionProducerCatalog` sur le chemin projection.

### Lot 6 — Branchement sur la consumption existante

**Objectif.** Raccorder le nouveau scheduler et le handler universel aux runtimes Event/Task sans
modifier les moteurs de claim/retry/polling.

**Fichiers/modules probablement concernés.** `locator-consumption-event`,
`locator-consumption-task`, `runtime-event-consumption-worker`,
`runtime-task-consumption-worker`, configuration Spring et tests PostgreSQL des runtimes.

**Invariants établis.** Publication, provenance et terminalisation fencée dans la même transaction ;
lost claim rollback ; retry idempotent ; domaine projection indépendant de consumption.

**Tests à ajouter.** Chaîne Event->Task->root/artifacts/result ; takeover ; rollback après publication ;
retry ; duplicate ; exécution hors ordre ; deux ProjectionType ; failure terminale.

**Critères de fin.** Tests runtime existants et nouveaux verts ; feature flag/configuration de retour
à l'ancien handler pendant observation ; observation générique de consumption réutilisée et toute
observation spécifique ajoutée par décoration dans les supra/runtimes ; aucune modification
sémantique du moteur générique ni du résultat fonctionnel en cas d'échec d'observation.

**Dépendances avec les lots suivants.** Dépend des lots 3 et 5 ; fournit les données pour le Read
métier et le cutover.

**Legacy supprimable après validation.** Tasks, mappers et handlers spécifiques ; bindings pipeline
des runtimes ; éventuellement lifecycle active/serving après décision de rollout.

### Lot 7 — Use case Read métier complet

**Objectif.** Migrer un use case métier vertical, recommandé `GetPot`, vers une clé de projection
explicite, le reader générique, la revalidation, le mapping de réponse et l'autorisation existante.

**Fichiers/modules probablement concernés.** `engine-query`, `engine-read-projection`, modèle de
snapshots et tests de policy ; reader primaire conservé pour comparaison/rollback.

**Invariants établis.** Le résultat métier ne lit pas le primaire ; version exposée explicite ;
corruption non masquée ; autorisation évaluée sur les données/version servies ; use case Read
exécutable et testable sans infrastructure d'observabilité.

**Tests à ajouter.** Parité legacy/cible ; exact ready/failed/not-ready/corrupt ; deleted ; accès
autorisé/refusé ; absence de lecture primaire ; version retournée.

**Critères de fin.** Use case complet appelable en shadow et comparé au legacy sur un corpus ; décision
explicite sur la sélection `CURRENT` si ce endpoint l'exige.

**Dépendances avec les lots suivants.** Dépend des lots 4 et 6 ; débloque l'exposition.

**Legacy supprimable après validation.** Ports/readers primaires propres au use case migré ; parties
correspondantes de `QueryVersionResolver` si devenues sans utilisateur.

### Lot 8 — Exposition/supra Read et extension verticale

**Objectif.** Basculer l'endpoint pilote, puis répéter verticalement pour les autres GET et Balance.

**Fichiers/modules probablement concernés.** `supra-http-rest-spring`, `runtime-web-api`,
`runtime-monolith`, configuration, tests HTTP/Bruno/k6 et observabilité.

**Invariants établis.** Aucun endpoint migré ne contourne le reader/revalidateur ; traduction
publique cohérente des états ; rollback de configuration possible.

**Tests à ajouter.** Contrats HTTP, auth, exact/current décidé, concurrence pendant production,
projection corrompue, compatibilité des réponses, smoke et charge ciblée.

**Critères de fin.** Endpoint pilote activé puis observé par des composants extérieurs au use case ;
métriques nécessaires à l'exploitation et critères de rollback documentés ; chaque endpoint suivant
migre dans un sous-lot indépendant ; aucune instrumentation ne réintroduit de contexte Event dans la
matérialisation fonctionnelle.

**Dépendances avec les lots suivants.** Dépend du lot 7 ; conditionne les suppressions physiques.

**Legacy supprimable après validation.** Readers primaires et adapters spécifiques sans utilisateur,
runtime monolith de projection, anciens stockages après période d'observation.

### Lot 9 — Nettoyage contrôlé

**Objectif.** Retirer seulement les abstractions et stockages dont l'absence d'utilisateur est
prouvée après cutover.

**Fichiers/modules probablement concernés.** POM parent, `domain-pipeline`,
`engine-pipeline-lifecycle`, `infra-pipeline-lifecycle-persistence`, anciens modules pipeline,
Flyway par migrations additives de drop, documentation et architecture tests.

**Invariants établis.** Aucun concept pipeline/watermark/head/bootstrap dans le chemin projection ;
graphe de modules simplifié ; aucune suppression de migration historique.

**Tests à ajouter.** Architecture tests de dépendances ; démarrage de chaque runtime ; recherche
statique des anciens types ; tests de migration depuis une base pré-cutover ; non-régression complète.

**Critères de fin.** Zéro référence compilée/runtime/SQL active, sauvegarde et procédure de rollback
validées avant drop, documentation d'état courant mise à jour.

**Dépendances avec les lots suivants.** Dépend des cutovers des lots 6–8 ; aucun lot fonctionnel ne
doit dépendre de ce nettoyage.

**Legacy supprimable après validation.** Tous les candidats de la section 12 dont les conditions sont
satisfaites, par sous-lots séparés et réversibles avant le drop physique final.

La stabilisation et le nettoyage de ce lot rendent possible la revue transversale ultérieure de
l'observabilité. Cette revue constitue un chantier distinct et ne prolonge pas le séquencement
fonctionnel de la refonte Read.

## 14. Open implementation decisions

Les choix suivants restent réellement ouverts et doivent être challengés avant de coder :

1. **Représentation JSON et validation.** Choisir le type Java canonique du payload, la bibliothèque
   et la version de JSON Schema. Ce choix doit préserver un `domain-projection` sans dépendance à
   Spring/JPA et garantir la même sémantique en production et en lecture.
2. **Types d'identité cible.** Décider si `targetObjectType` et `targetObjectId` sont des valeurs
   génériques textuelles dans le noyau, ou des sealed types extensibles. Le choix ne doit pas
   recoupler le noyau générique à `domain-pot`.
3. **Historique de `projection_result`.** Décider si la table conserve seulement l'issue canonique
   courante par `ProjectionKey` ou un journal de tentatives plus une issue canonique. Dans les deux
   cas, un `FAILED` ne peut coexister comme issue canonique avec un root servable.
4. **Adoption idempotente.** Fixer la représentation canonique utilisée pour comparer deux
   productions équivalentes (JSON normalisé, digest, ou comparaison structurelle) et le diagnostic
   d'un résultat divergent.
5. **Migration des données existantes.** Choisir, environnement par environnement, entre backfill des
   tables universelles et rematérialisation depuis le Write side. Les tables historiques ne doivent
   pas être promues comme troisième format canonique.
6. **Rollout de producteurs.** Déterminer si un besoin opérationnel concret impose de conserver une
   notion de version/activation de producteur hors du domaine projection. À défaut d'une différence
   observable nécessaire, `engine-pipeline-lifecycle` sort du chemin et devient supprimable.
7. **Politique `CURRENT`.** Définir au Lot 7 la règle métier de résolution d'une version courante. Elle
   peut nécessiter un index ou une borne d'exposition, mais ne doit pas modifier `ProjectionKey`, la
   servabilité d'un root exact ni l'autonomie des projectors.
