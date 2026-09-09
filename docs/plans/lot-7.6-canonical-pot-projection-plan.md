# Lot 7.6 — PotProjection canonique en shadow mode

## 1. Objectif et définition du done

**[CANONICAL INVARIANT]** Pour toute Task portant le payload d'exécution commun :

```text
ProjectionExecutionPayload(
    pipelineId,
    pipelineVersion,
    potId,
    potVersion
)
```

le Lot 7.6 reconstruit le Pot depuis l'historique primaire exact à `potVersion`, construit un
`PotProjection` logique complet, autonome, déterministe et immuable, puis le matérialise dans le read
store sous une `ProjectionIdentity` exacte.

**[CANONICAL INVARIANT]** Le lot reste en shadow mode : aucun GET actif, contrat HTTP, Query Kernel,
`PipelineSelectionStrategy` ou règle d'autorisation HTTP ne bascule sur cette projection.

Le lot est prêt à être validé lorsque :

- le pipeline Pot exact est dans le catalogue canonique et dispose des bindings Event et Task ;
- toutes les versions applicables produisent une Task puis un snapshot exact, sans dépendre d'une
  projection antérieure ni du watermark ;
- le contenu logique défini en section 6 est reconstruit depuis une seule vue cohérente du primaire ;
- header, fragments, descriptor générique et head sont atomiques dans le read store ;
- retry, concurrence, idempotence, divergence et failures respectent la fondation 7.3 ;
- le résultat shadow est comparé aux lectures primaires existantes sur les scénarios historiques ;
- aucun reader actif n'est modifié.

Deux réserves ne doivent pas être maquillées en hypothèses :

- **[BLOCKER DE CLÔTURE À RÉSOUDRE DANS 7.6]** le write side observé avant implémentation ne prouvait
  pas encore que delete Pot était terminal. Ce
  point ne bloque pas l'implémentation ni la validation shadow du projector : le Lot 7.6 reconstruit
  d'abord exactement la version `DELETED`, puis porte un micro-correctif write-side strictement ciblé
  et ses tests. Il bloque seulement la clôture canonique du lot tant que le correctif n'est pas livré ;
- **[OPEN / BLOCKER]** aucune source fonctionnelle univoque de `updatedAt` n'existe aujourd'hui. Cette
  valeur est exclue du snapshot 7.6. Elle ne bloque ni son implémentation ni sa clôture en shadow mode,
  mais devient un gate d'entrée du Lot 7.7 pour toute partie dépendant du tri current, des indexes
  current ou de la pagination canonique `updatedAt DESC, potId`.

## 2. Sources documentaires consultées

**[CONFIRMED BY DOCUMENTATION]** Sources canoniques lues avant toute inspection de code :

- `docs/README.md` ;
- `docs/architecture/read-side-target.md` ;
- `docs/architecture/read-side-current-state.md` ;
- `docs/architecture/write-side-closure.md` ;
- `docs/architecture/module-dependency-matrix.md` ;
- `docs/plans/lot-7-read-side-implementation-plan.md` ;
- `docs/plans/lot-7.3-generic-projection-foundation-plan.md` ;
- `docs/plans/lot-7.3.1-pipeline-applicability-alignment-plan.md` ;
- `docs/plans/lot-7.4-source-version-watermark-plan.md` ;
- `docs/plans/lot-7.5-applicable-projection-task-scheduling-plan.md` ;
- `docs/architecture/consumption-task-balance-runtime.md` ;
- `docs/architecture/consumption-event-pull-runtime.md`.

Le plan directeur est subordonné à `read-side-target.md`. Aucun conflit entre ces documents ne
requiert de modifier la cible. Les deux lacunes déjà annoncées par le plan directeur — delete terminal
et source de `updatedAt` — sont confirmées, pas résolues silencieusement ici.

## 3. Documentation complémentaire créée

**[CONFIRMED BY TARGETED CODE INSPECTION]** La temporalité primaire complète n'était pas centralisée.
Le présent travail ajoute :

- `docs/architecture/pot-historical-reconstruction.md` : règle temporelle, fragments disponibles,
  reconstruction exacte, contexte d'autorisation, delete et analyse temporelle ;
- une entrée correspondante dans `docs/README.md`.

Le plan référence ce document au lieu de recopier tous les détails JPA. Pendant l'exécution, toute
divergence découverte entre ce contrat et le code doit d'abord corriger cette documentation, puis le
plan si elle change une décision.

## 4. État actuel vérifié

### 4.1 Historique primaire

**[CONFIRMED BY TARGETED CODE INSPECTION]** `pot_headers`, `shareholders`, `expense_headers` et
`expense_shares` sont historisés par intervalles semi-ouverts
`started_at_version <= V < ended_at_version`, borne haute ouverte si `NULL`.

**[CONFIRMED BY TARGETED CODE INSPECTION]** Les repositories exacts déjà utilisables sont :

- `JpaPotHeaderRepository.findActiveAtVersion` ;
- `JpaShareholderRepository.findActiveAtVersion` ;
- `JpaExpenseShareRepository.findActiveAtVersion`.

Ils ne filtrent pas les marqueurs de suppression. En revanche,
`JpaExpenseHeaderRepository.findByPotActiveNotDeletedAtVersion` filtre `deleted=false`.
`JpaHistoricalPotBalanceSourceAdapter` et `JpaProjectedExpenseAdapter` sont donc insuffisants pour
le snapshot Pot complet, même s'ils restent corrects pour leur usage Balance.

### 4.2 Modèle métier disponible

**[CONFIRMED BY TARGETED CODE INSPECTION]** Le primaire permet de reconstruire :

- `PotHeader` : `potId`, label, `creatorId`, `deleted` ;
- `Shareholder` : `shareholderId`, `potId`, name, weight fractionnel, `userId` nullable, `deleted` ;
- `ExpenseHeader` : `expenseId`, `potId`, `payerId`, amount fractionnel, label, `deleted` ;
- `ExpenseShare` : `expenseId`, `shareholderId`, weight fractionnel.

Il n'existe aucun rôle Shareholder distinct. Le contexte utilisateur est le lien `userId` historisé
sur les Shareholders, complété par le creator du Pot.

### 4.3 Scheduling 7.5

**[CONFIRMED BY TARGETED CODE INSPECTION]** `ScheduleProjectionTasksForEventService` sépare déjà :

```text
EventPipelineRelevance(Event, pipelineId)
PipelineVersionDefinition.appliesTo(potVersion)
TaskCreationStrategy(PipelineDefinition exacte)
```

Le catalogue courant ne contient que `balance-projection/v2`. Ajouter une définition Pot exige, dans
le même changement cohérent, sa relevance familiale et sa stratégie exacte ; sinon le wiring du
runtime Event échoue volontairement.

### 4.4 Exécution Task

**[CONFIRMED BY TARGETED CODE INSPECTION]** Le chemin générique existe :

```text
TaskConsumptionLocator
→ RecordedTaskExecutionMapperRegistry
→ ExecuteTaskService
→ TaskExecutionHandler exact
→ TaskExecutionReport
```

`runtime-task-consumption-worker` est encore composé uniquement pour Balance et refuse une autre
pipeline lorsqu'il est activé. Son moteur, son locator, ses slots, son fencing et ses transactions
sont génériques ; seule la composition doit être généralisée.

### 4.5 Fondation de matérialisation

**[CONFIRMED BY TARGETED CODE INSPECTION]** `ProjectionMaterializationService<A>` et
`ProjectionFailureService` :

- prennent un advisory lock PostgreSQL sur l'identité complète ;
- rechargent la définition exacte dans `PipelineDefinitionRegistry` ;
- appliquent `appliesTo` ;
- imposent artifact XOR failure ;
- adoptent l'identique et enregistrent une violation sur divergence ;
- avancent `ProjectionHead` par maximum.

Le contrat concret est `ProjectionArtifactWriter<A>`. Il écrit d'abord l'artifact concret, puis le
service insère le descriptor et avance le head dans un `ReadStoreTransactionRunner`.

### 4.6 Transaction réelle observée

**[CONFIRMED BY TARGETED CODE INSPECTION]** Les compositions actuelles du Task runtime et du read
store construisent leurs `TransactionTemplate` depuis le même `PlatformTransactionManager`, et les
adapters JPA/JDBC utilisent le même `DataSource` applicatif. La propagation par défaut est `REQUIRED`.

**[IMPLEMENTATION CHOICE]** Le Lot 7.6 doit conserver cette transaction locale commune, sans la
transformer en invariant abstrait de déploiement. Le nouveau wiring doit être prouvé par un test de
rollback englobant primaire read, fragments read-store, descriptor/head, provenance et CAS terminal.
Si cet enlistment n'est pas obtenu, l'implémentation s'arrête avant validation et le plan est amendé
pour définir le protocole nécessaire ; aucune atomicité inexistante ne sera revendiquée.

### 4.7 Migrations disponibles

**[CONFIRMED BY TARGETED CODE INSPECTION]** Le read store possède V1 à V4 ; la prochaine migration
libre est donc `V5`. Le schéma primaire est à V10. Le Lot 7.6 n'exige aucune modification du schéma
primaire.

## 5. Invariants verrouillés

Les points suivants sont tous **[CANONICAL INVARIANT]** :

- reconstruction depuis l'historique primaire exact, jamais depuis `PotProjection@(V-1)` ;
- snapshot autonome : aucun GET futur ne relit primaire, Event, Task ou projection précédente ;
- identité `projectionType + pipelineId + pipelineVersion + potId + potVersion` ;
- `PipelineVersionDefinition.appliesTo` est l'unique règle d'applicabilité par génération ;
- `PipelineSelectionStrategy` est absente du scheduling et du projector ;
- le watermark ne gate ni acquisition ni exécution ni matérialisation ;
- artifact/failure/absence dérivent `READY`/`FAILED`/`NOT_READY` ;
- artifact et failure sont mutuellement exclusifs ;
- même identité/même contenu est idempotent ; même identité/contenu divergent n'écrase jamais ;
- une divergence après READY n'est pas convertie en FAILED ;
- le head avance par maximum sans exigence de contiguïté ;
- les Tasks Event et les futures Tasks administratives utilisent le même payload d'exécution ;
- l'executor ne dépend pas de la provenance Event ;
- le primaire est autorisé pour le projector, interdit pour les futurs GET ;
- aucune table `ProjectionCoverage`, expectation ou state persistant par version n'est réintroduite.

## 6. Contenu logique exact de PotProjection

### 6.1 Modèle fonctionnel

**[IMPLEMENTATION CHOICE]** Ajouter au module framework-free `domain-projection`, qui dépend déjà de
`domain-pot`, un modèle conceptuellement équivalent à :

```text
PotProjection
  identity: ProjectionIdentity
  status: ACTIVE | DELETED
  label
  creatorId
  shareholders: ordered list<PotProjectionShareholder>
  expenses: ordered list<PotProjectionExpense>

PotProjectionShareholder
  shareholderId
  name
  weight: numerator + denominator
  userId: optional
  deleted

PotProjectionExpense
  expenseId
  payerId
  amount: numerator + denominator
  label
  deleted
  shares: ordered list<PotProjectionExpenseShare>

PotProjectionExpenseShare
  shareholderId
  weight: numerator + denominator
```

`potId` et `potVersion` sont accessibles par `identity`; ils ne doivent pas pouvoir diverger via des
champs dupliqués. Les constructeurs valident valeurs positives, identifiants non nuls, absence de
doublons et cohérence interne.

**[IMPLEMENTATION CHOICE]** Les fractions utilisent leurs valeurs canoniques existantes ou leurs
numérateur/dénominateur normalisés ; aucune conversion flottante n'entre dans le contenu ou le digest.

### 6.2 Présence et suppression des enfants

**[CONFIRMED BY DOCUMENTATION]** Tous les headers temporellement applicables sont présents, y compris
les Shareholders et Expenses marqués `deleted=true`. Leur absence signifie qu'ils n'existaient pas
encore à `V`, pas qu'une query GET les a filtrés.

**[IMPLEMENTATION CHOICE]** Les Expense shares applicables sont conservées dans l'Expense, même si
son header est supprimé. Cette représentation préserve l'état historique complet ; les futurs readers
décideront ensuite si une ressource supprimée est exposable.

**[CANONICAL INVARIANT]** Présence d'un sous-objet dans `PotProjection` ne signifie pas exposition par
défaut dans un futur GET. Le snapshot conserve l'état historique complet ; les futurs readers décident
séparément si le sous-objet est actif, archivé, masqué ou visible seulement avec `VIEW_ARCHIVE`.

### 6.3 Contexte d'autorisation

**[CONFIRMED BY DOCUMENTATION]** Le contexte minimal est déjà contenu dans les données ci-dessus :
`creatorId`, et pour chaque Shareholder `userId + deleted`. L'ensemble des membres à `V` est dérivable
par `userId != null && !deleted`.

**[IMPLEMENTATION CHOICE]** Ne pas dupliquer dans le contenu fonctionnel une liste d'ACL susceptible
de diverger. Si un fragment d'accès dérivé est ultérieurement nécessaire pour un index, il appartiendra
au Lot 7.7 et devra être atomiquement recalculé depuis ce snapshot. Permissions et scopes du principal
ne sont jamais persistés dans PotProjection.

### 6.4 Données explicitement exclues

**[CANONICAL INVARIANT]** Sont exclus du contenu fonctionnel :

- ids techniques et bornes des lignes temporelles primaires ;
- `eventId`, Task id, campaign id ou autre provenance ;
- timestamps d'exécution/matérialisation ;
- `SourceVersionWatermark` ;
- `PipelineSelectionStrategy` ;
- `updatedAt` tant que sa source n'est pas fermée ;
- DTO et codes HTTP.

## 7. Représentation physique proposée

**[IMPLEMENTATION CHOICE]** La projection reste logiquement unique mais utilise quatre tables dans
`pocoma_read` :

1. `pot_projection_snapshots`
   - `artifact_id uuid` PK ;
   - identité complète répétée pour lookup exact et contrainte unique ;
   - `status` avec check `ACTIVE|DELETED` ;
   - `label`, `creator_id` ;
2. `pot_projection_shareholders`
   - PK `(artifact_id, shareholder_id)` ;
   - name, poids fractionnel, `user_id` nullable, `deleted` ;
3. `pot_projection_expenses`
   - PK `(artifact_id, expense_id)` ;
   - payer, montant fractionnel, label, `deleted` ;
4. `pot_projection_expense_shares`
   - PK `(artifact_id, expense_id, shareholder_id)` ;
   - poids fractionnel.

Contraintes minimales : versions et dénominateurs positifs, textes non vides conformément aux value
objects, FK fragments vers leur snapshot, FK shares vers l'Expense du même artifact. Une FK vers le
Shareholder du même artifact est ajoutée si les fixtures historiques confirment sans ambiguïté cet
invariant ; sinon l'incohérence est détectée par le reconstructeur et documentée avant de choisir.

**[IMPLEMENTATION CHOICE]** Le snapshot référence `projection_artifacts(artifact_id)` par une FK
`DEFERRABLE INITIALLY DEFERRED`, car le writer concret précède actuellement l'insertion du descriptor
dans la transaction générique. Aucun `ON DELETE CASCADE` n'est nécessaire : les artifacts publiés ne
sont pas supprimés dans ce lot.

Indexes strictement 7.6 :

- unique identité complète sur `pot_projection_snapshots` ;
- les PK de fragments, suffisantes pour charger un artifact exact ;
- éventuellement `(artifact_id, expense_id)` déjà couvert par la PK de shares.

**[CANONICAL INVARIANT]** Les indexes `user→Pot` et la pagination `updatedAt` appartiennent au
Lot 7.7 et ne sont pas introduits ici. Les sous-ressources Expense et Shareholder sont toujours
adressées sous leur Pot parent ; aucun routage transversal enfant vers Pot n'est requis.

## 8. Reconstruction historique primaire

### 8.1 Port et use case

**[IMPLEMENTATION CHOICE]** Ajouter dans `engine-read-projection` un port intentionnel, par exemple
`HistoricalPotProjectionSourcePort.loadExact(potId, potVersion)`, et un service de reconstruction qui
retourne le modèle fonctionnel sans connaître JPA/JDBC.

Le nom exact reste ajustable aux conventions locales, mais le contrat n'est pas un CRUD générique et
ne retourne pas les entities temporelles. Il expose une vue historique complète, suppression comprise.

**[IMPLEMENTATION CHOICE]** Implémenter ce port dans `infra-persistence-jpa` avec une méthode
`@Transactional(propagation = MANDATORY, readOnly = true)` afin de réutiliser la transaction Task
ouverte. Réutiliser les trois repositories exacts existants et ajouter seulement la query Pot-wide
Expense manquante, sans filtre `deleted`.

### 8.2 Algorithme exact

**[CONFIRMED BY DOCUMENTATION]** Dans la même vue primaire :

1. charger le Pot header à V ;
2. charger tous les Shareholders du Pot applicables à V ;
3. charger tous les Expense headers du Pot applicables à V ;
4. charger les shares applicables à V pour chacune de ces Expenses ;
5. valider que chaque fragment appartient au Pot demandé et qu'il n'existe aucun doublon fonctionnel ;
6. construire les fragments fonctionnels ;
7. ordonner et geler toutes les collections.

**[IMPLEMENTATION CHOICE]** Une query groupée des shares Pot-wide peut remplacer les lectures par
Expense si elle réduit le N+1 sans modifier la sémantique. Cette optimisation reste locale à l'adapter ;
le port et les tests ne dépendent pas de sa forme.

### 8.3 Erreurs de reconstruction

**[CANONICAL INVARIANT]** Aucun fallback vers current, V-1 ou un Event n'est permis.

**[IMPLEMENTATION CHOICE]** Header absent, doublon actif, rattachement incohérent ou référence de share
impossible produisent une erreur de reconstruction typée et terminale pour cette identité. Les erreurs
SQL, timeout et indisponibilités restent techniques/retryables.

## 9. Pipeline et intégration Task

### 9.1 Identité figée pour le lot

La documentation antérieure utilisait `READ_POT` comme nom conceptuel sans identité technique figée.
Le test catalog-driven du Lot 7.5 utilise déjà la convention `read-pot`/`READ_POT`.

**[IMPLEMENTATION CHOICE]** Figer en 7.6 :

```text
pipelineId       = read-pot
pipelineVersion  = 1
projectionType   = POT
taskType          = READ_POT
applicability     = [1..∞]
```

Cette identité respecte les conventions lower-kebab-case des pipeline ids et upper-snake-case des
task/projection types. La version 1 est la première définition publiée et devient immuable. Toute
modification future du contenu ou de l'applicabilité exige une nouvelle `pipelineVersion`.

### 9.2 Catalogue et scheduling

**[IMPLEMENTATION CHOICE]** Ajouter `read-pot/v1` à `PocomaPipelineDefinitions.all()` dans le même
commit que les bindings suivants :

- `PotProjectionEventPipelineRelevance` : tous les `BusinessEvent` Pot sont pertinents au pipeline
  `read-pot` ;
- stratégie exacte v1 : une Task `READ_POT` avec potId/version dans les champs structurels et le JSON ;
- garde de cohérence du payload, identique au pattern `ComputeBalancesRecordedTaskMapper`.

**[CANONICAL INVARIANT]** La relevance est au niveau `pipelineId`; `appliesTo` reste exclusivement dans
la définition versionnée. La stratégie exacte ne possède aucune policy de production additionnelle.

Le runtime Event 7.5 continue d'énumérer le catalogue courant. Aucun changement de discovery, slot,
identité `(eventId,pipelineId,pipelineVersion)` ou transaction de scheduling n'est requis.

### 9.3 Payload sans provenance

**[CANONICAL INVARIANT]** Le mapper produit un payload typé contenant uniquement l'identité
d'exécution Pot/génération/version. Le handler ne lit pas `eventId`. Une future Task administrative du
Lot 7.8 pourra donc appeler exactement le même mapper/handler à partir de ses champs structurels.

## 10. Executor

### 10.1 Chaîne

**[IMPLEMENTATION CHOICE]** Ajouter un module framework-free `pipeline-pot` analogue à
`pipeline-balance`, contenant constantes, relevance, stratégie de Task, payload typé, mapper et handler.
Le modèle reste dans `domain-projection`; reconstruction et fondation restent dans
`engine-read-projection`.

Le chemin devient :

```text
Task READ_POT
→ TaskConsumptionLocator (pipeline read-pot/v1)
→ mapper exact et validation structure/JSON
→ ExecuteTaskService
→ PotProjection Task handler
→ require PipelineVersionDefinition exacte
→ appliesTo(potVersion)
→ reconstructeur primaire exact
→ ProjectionMaterializationService<PotProjection>
→ TaskExecutionReport
```

### 10.2 Composition runtime

**[IMPLEMENTATION CHOICE]** Généraliser `runtime-task-consumption-worker` juste assez pour connaître
les bindings Balance et Pot, puis sélectionner l'ensemble exact mapper/handler à partir des propriétés
`pipeline-id`, `pipeline-version` et `task-types`. Une instance reste affectée à une génération ; un
déploiement Pot shadow distinct exécute `read-pot/v1` et le déploiement Balance continue inchangé.

Cela conserve les pools séparés, le locator générique et le scaling horizontal. Ne pas créer un moteur
Pot ni une nouvelle convention de `ConsumptionKey`.

Le runtime doit échouer au démarrage si : définition absente du catalogue, mapper/handler exact absent,
task type incohérent ou doublon de binding.

### 10.3 Résultats Task

**[IMPLEMENTATION CHOICE]** Traduction attendue :

- `Created` ou `AlreadySatisfied` → `TaskExecutionReport.Succeeded`, input Pot exact et référence à
  l'artifact créé/adopté ;
- `DivergentDuplicate` → rapport terminal `Rejected(PROJECTION_DIVERGENT_DUPLICATE)`, aucun overwrite,
  violation conservée ;
- `AlreadyFailed` → rapport terminal rejected avec code stable, sans nouvel artifact ;
- reconstruction terminalement impossible → `ProjectionFailureService`, puis rapport rejected si la
  failure est enregistrée/adoptée ;
- panne technique retryable → exception, rollback et retry via le lifecycle Task.

La représentation exacte d'une référence de failure dans la provenance reste absente :
`TaskExecutionReport.artifacts` ne doit pas mentir en présentant une failure comme artifact.

## 11. Applicability

**[CANONICAL INVARIANT]** Le handler recharge `PipelineVersionDefinition(read-pot,v1)` depuis un
`PipelineDefinitionRegistry` local construit par `PocomaPipelineDefinitions.all()` et vérifie
`appliesTo(potVersion)` avant toute lecture primaire.

Une Task non applicable ou une définition exacte absente est une erreur technique/configuration
non retryable : aucun fragment, descriptor, failure fonctionnelle ni head n'est écrit. Le cas
`ProjectionMaterializationResult.NotApplicable`, s'il survient malgré la prévalidation, est traité
comme violation de protocole et jamais comme un statut reader.

**[CANONICAL INVARIANT]** `PipelineSelectionStrategy` et `SourceVersionWatermark` ne sont injectés dans
aucun mapper, handler, reconstructeur ou writer Pot.

## 12. Matérialisation générique

**[IMPLEMENTATION CHOICE]** Implémenter dans `infra-read-persistence` un
`ProjectionArtifactWriter<PotProjection>` :

- `digest` sérialise uniquement le contenu fonctionnel canonique ;
- `write` insère snapshot et fragments avec le même `artifact_id` ;
- `hasSameContent` compare le digest canonique proposé au descriptor existant et, dans les tests
  d'intégrité, vérifie que les fragments relus correspondent au digest stocké.

Le format de digest doit être explicite et versionné dans le code, par exemple un flux binaire/JSON
canonique à champs ordonnés et UTF-8, puis SHA-256. Il ne dépend ni de l'ordre JPA, ni d'un
`ObjectMapper` configuré par runtime.

**[CANONICAL INVARIANT]** Le writer concret ne crée pas son propre lifecycle. Il est exclusivement
appelé par `ProjectionMaterializationService`, qui conserve advisory lock, first-terminal-wins,
descriptor, violation et head.

## 13. Atomicité et transaction

### 13.1 Unité attendue

**[IMPLEMENTATION CHOICE]** Sous le wiring PostgreSQL réel :

```text
reload Task autoritatif
→ validation mapper/applicability
→ reconstruction primaire read-only
→ lock exact
→ snapshot + fragments
→ projection_artifacts
→ projection_heads
→ provenance Task
→ CAS terminal du slot
→ commit unique
```

Toutes les écritures read-store sont atomiques même si le handler est ultérieurement appelé depuis
une Task administrative. L'atomicité étendue au lifecycle Task est une propriété locale du runtime
actuel à prouver, pas une dépendance fonctionnelle du read store envers les Tasks.

### 13.2 Tests de preuve

**[CANONICAL INVARIANT]** Injecter au minimum :

- échec après le premier fragment, avant descriptor/head ;
- échec après descriptor, avant head ;
- échec après matérialisation complète, avant provenance/CAS terminal ;
- perte du claim au CAS terminal.

Dans chaque cas, aucun fragment partiel, artifact ou head ne reste visible ; pour les deux derniers,
provenance et terminalisation sont également rollbackées. Un retry reconstruit et converge.

Si un test montre que les transactions JPA primaire, JDBC read store et lifecycle ne partagent pas le
même manager/DataSource effectif, arrêter la validation et amender le plan avant de concevoir un autre
protocole.

## 14. Immutabilité, idempotence et violation

**[CANONICAL INVARIANT]** La clé de concurrence est l'identité complète. Les tables concrètes sont
insert-only dans ce lot ; aucun update du contenu ni delete applicatif n'est exposé.

Cas à garantir :

| Situation | Résultat |
|---|---|
| aucun terminal | fragments + artifact créés, head max |
| artifact identique | adoption/`AlreadySatisfied`, aucune réécriture |
| artifact divergent | artifact inchangé, violation ajoutée, READY conservé |
| failure existante | aucune matérialisation, FAILED conservé |
| success/failure concurrents | first terminal commit wins |

**[IMPLEMENTATION CHOICE]** La violation contient les digests existant/proposé déjà prévus par 7.3.
Les UUID et timestamps de violation sont techniques et exclus du contenu Pot.

## 15. ProjectionHead

**[CANONICAL INVARIANT]** Après un `Created` uniquement, le service générique applique :

```text
latestProjectedVersion = max(existing, potVersion)
```

`V5 → V3 → V7` produit un head à 7. Une adoption identique ou une divergence ne régresse pas le head.
Aucun trou n'est synthétisé ni considéré comme erreur.

Le head peut dépasser `SourceVersionWatermark`; le runtime Pot ne lit pas ce watermark.

## 16. ACTIVE / DELETED

**[CANONICAL INVARIANT]** `PotProjection.status` dérive uniquement de `PotHeader.deleted` à la version
cible. La version du delete produit `DELETED` avec header et fragments autonomes, sans effacer les
snapshots précédents.

**[CONFIRMED BY TARGETED CODE INSPECTION]** Les fragments nécessaires restent historisés à la version
de delete. Une Task visant une version primaire inexistante ne peut pas être matérialisée silencieusement
et produit une failure terminale de reconstruction.

**[BLOCKER IDENTIFIÉ AVANT IMPLÉMENTATION, RÉSOLU EN 7.6]** Le write side permettait encore certaines
mutations Expense après delete Pot. Le projector ne filtre pas les éventuelles lignes legacy pour
fabriquer artificiellement une terminalité.
Le séquencement obligatoire du Lot 7.6 est :

1. implémenter le projector Pot en shadow mode ;
2. prouver la reconstruction exacte de la version `DELETED`, historique complet inclus ;
3. relire `docs/architecture/write-side-closure.md`, puis inspecter uniquement les guards/contextes
   métier des mutations post-delete vérifiées ;
4. livrer un micro-correctif write-side strictement ciblé rendant delete Pot réellement terminal,
   sans rouvrir l'architecture du Lot 6 ;
5. prouver qu'une commande métier ultérieure visant le Pot ou ses enfants est rejetée de manière
   cohérente, sans nouvelle `potVersion` ni `BusinessEvent` de mutation ;
6. seulement alors déclarer le Lot 7.6 clos.

Les éventuelles versions post-delete de fixtures legacy restent visibles et documentées comme
divergence historique ; le projector ne les normalise ni ne les masque silencieusement.

## 17. Contexte d'autorisation historique

**[CANONICAL INVARIANT]** À V, les futurs readers doivent pouvoir déterminer sans primaire :

- si l'utilisateur est creator ;
- s'il est lié à un Shareholder actif/non supprimé ;
- l'état supprimé du Pot et du sous-objet demandé.

**[CONFIRMED BY DOCUMENTATION]** `creatorId`, `Shareholder.userId` nullable et
`Shareholder.deleted` suffisent au contexte actuellement défini. Il n'existe pas de rôle additionnel à
inventer. Les permissions `VIEW`/`VIEW_ARCHIVE` sont évaluées plus tard depuis le principal et le head.

Tests : membre ajouté puis retiré, utilisateur jamais membre, creator, lien utilisateur nullable,
snapshot Pot/Expense/Shareholder supprimé. Le Lot 7.6 vérifie les données, pas les réponses 404/409/503.

## 18. Déterminisme

**[CANONICAL INVARIANT]** Deux reconstructions de la même identité depuis le même primaire produisent
le même contenu et le même digest.

**[IMPLEMENTATION CHOICE]** Canonisation :

- Shareholders triés par UUID `shareholderId` ;
- Expenses triées par UUID `expenseId` ;
- shares triées par UUID `shareholderId` ;
- records/listes immuables ;
- fractions exactes normalisées ;
- aucune valeur `now()`, UUID aléatoire ou metadata Task dans le contenu ;
- enum et champs sérialisés dans un ordre explicite ;
- `null` et absence représentés de manière unique.

Artifact id, descriptor `createdAt`, head `advancedAt`, violation id/date et timestamps de consommation
sont des métadonnées techniques. Ils peuvent varier sans faire diverger le contenu fonctionnel.

## 19. Analyse `updatedAt`

**[CONFIRMED BY TARGETED CODE INSPECTION]** Classification : **B — source candidate nécessitant un
invariant supplémentaire**.

Le primaire historisé et `pot_global_versions` n'ont aucun timestamp par version. L'outbox possède un
`recordedAt`, mais plusieurs Events peuvent porter la même version et aucune contrainte n'établit
`potVersion → one durable timestamp`. `JpaPotCommandEventAppendAdapter` attribue un instant commun aux
Events d'un append, mais cet effet d'implémentation n'est pas une garantie durable universelle.

**[CANONICAL INVARIANT]** Ne choisir ni min, max, premier, dernier, temps de projection ou temps de
replay. Ne pas ajouter `updatedAt` au modèle 7.6.

**[OPEN / BLOCKER 7.7]** Avant de commencer ou valider la partie du Lot 7.7 portant l'ordre current,
les indexes current et la pagination, choisir et rendre durable une source fonctionnelle univoque,
par exemple une metadata de commit portée par la version Pot elle-même, puis définir sa reconstruction
administrative. Tant que ce gate reste ouvert : snapshot et comparaison shadow 7.6 possibles et
clôturables ; ordre canonique `updatedAt DESC, potId`, pagination current et rebuild des indexes
correspondants interdits. Les parties de 7.7 indépendantes pourront être planifiées séparément, sans
prétendre valider cet ordre.

## 20. Failure semantics

### 20.1 Panne technique retryable

**[CANONICAL INVARIANT]** DB indisponible, timeout, deadlock ou rollback inattendu : exception vers le
lifecycle Task, aucun `ProjectionFailure`, projection `NOT_READY`, retry normal.

### 20.2 Failure terminale de projection

**[CANONICAL INVARIANT]** Historique structurellement impossible à reconstruire pour l'identité exacte
après lecture réussie : `ProjectionFailure`, donc `FAILED`.

**[IMPLEMENTATION CHOICE]** Le handler appelle `ProjectionFailureService` puis retourne un report
`Rejected` afin que failure, input provenance et CAS terminal committent ensemble. Un throw après
l'insert ferait rollbacker la failure et est interdit. La classification des anomalies terminales doit
être une liste fermée et testée, pas « toute RuntimeException ».

### 20.3 Violation après READY

**[CANONICAL INVARIANT]** Recalcul divergent : artifact et READY restent inchangés, violation enregistrée,
Task terminée avec signal explicite. Ne jamais appeler `ProjectionFailureService` dans ce cas.

### 20.4 Erreur de protocole

**[CANONICAL INVARIANT]** Binding absent, payload incohérent, définition inconnue ou non applicable :
failure technique Task non retryable, aucune issue fonctionnelle de projection créée.

## 21. Migration nécessaire

**[IMPLEMENTATION CHOICE]** Créer pendant l'exécution :

```text
infra-read-persistence/src/main/resources/db/read-store/migration/
V5__canonical_pot_projection.sql
```

Elle crée uniquement les quatre tables de section 7, leurs checks, FK read-store et indexes locaux.
Elle ne modifie ni migration publiée V1–V4, ni schéma primaire, ni Tasks, ni watermark.

Préflights/compatibilité :

- installation fraîche V1→V5 ;
- upgrade V4→V5 sur read store contenant artifacts/failures d'autres types ;
- aucun backfill : shadow store démarre vide pour Pot ;
- application avec pipeline Pot désactivé peut démarrer avant activation du worker, mais le worker Pot
  ne doit pas être activé avant V5 ;
- rollback technique raisonnable avant trafic : désactiver worker puis supprimer V5 uniquement sur
  environnement non partagé ; après publication d'artifacts, rollback applicatif = désactivation du
  worker, sans supprimer les données.

**[CANONICAL INVARIANT]** Aucun overwrite/déduplication destructive n'est inclus.

## 22. Tests à implémenter

### 22.1 Domaine et reconstruction unitaires

**[CANONICAL INVARIANT]** Couvrir :

- validation identité, version positive, fractions, doublons et cohérence Pot ;
- collections immuables et ordre canonique ;
- statut ACTIVE/DELETED ;
- même source → même snapshot/digest ;
- timestamp/UUID technique absent du contenu.

### 22.2 Reconstruction temporelle PostgreSQL primaire

Créer un Pot : V1 create ; V2 add shareholder ; V3 create Expense ; V4 mutate Expense/shares ; V5
remove shareholder. Reconstruire séparément `@1..@5` et vérifier chaque champ, présence/absence,
deleted flags et shares.

Ajouter :

- Expense supprimée reste dans le snapshot avec `deleted=true` ;
- Shareholder supprimé reste présent avec son contexte historique ;
- utilisateur membre à V2 mais plus à V5 ;
- reconstruction V5 directe sans aucune projection V1–V4 ;
- header exact absent et incohérences de fragments donnent les failures typées prévues ;
- lecture effectuée sous transaction primaire cohérente.

### 22.3 Pipeline et applicability

- mapper refuse divergence entre structure Task et JSON ;
- `read-pot/v1` applicable produit/matérialise ;
- fixture non applicable : erreur protocole, aucun artifact/failure/head ;
- définition absente : erreur configuration ;
- deux versions applicables à V : deux identités/artifacts indépendants ;
- changement de `PipelineSelectionStrategy` : aucun effet ;
- watermark V-1 + Task V : succès sans lecture du watermark ;
- handler fonctionne sans `eventId`, preuve d'extensibilité administrative.

### 22.4 Persistence read-store

- V5 fraîche et upgrade V4→V5 ;
- contraintes/FK et absence d'indexes 7.7 ;
- insert complet et reload exact ;
- idempotence identique ;
- divergence : fragments/artifact inchangés, READY inchangé, violation créée ;
- success/success identique et divergent ;
- success/failure dans les deux ordres ;
- `V5→V3→V7` donne head 7 ;
- fragments orphelins/impossibles rejetés.

### 22.5 Atomicité runtime PostgreSQL

- échec après fragment avant descriptor/head : rollback intégral ;
- échec après descriptor avant head : rollback intégral ;
- échec avant CAS terminal : fragments, descriptor, head et provenance rollbackés ;
- retry/takeover : convergence vers un artifact ;
- deux workers même Task : un claim actif, un terminal ;
- deux Tasks/Event distinctes même identité : matérialisation finale idempotente.

### 22.6 Scénarios fonctionnels shadow

- Event Pot → scheduler 7.5 → Task `READ_POT` → Task worker → PotProjection READY ;
- GET actifs inchangés et lisant toujours leur source actuelle ;
- comparaison champ à champ aux `PotQueryPort`/`ExpenseQueryPort` historiques pour scénarios actifs et
  archives, sans réutiliser leurs filtres pour construire la projection ;
- delete Vn → snapshot `DELETED`, contenu autonome ;
- Task d'une version exacte inexistante → aucune matérialisation silencieuse ;
- preuve terminalité post-delete activée uniquement après résolution du blocker write-side.

### 22.7 Architecture

- `domain-projection`, `engine-read-projection` et `pipeline-pot` framework-free ;
- projector Pot peut dépendre des ports primaires mais pas de `engine-query` ni du runtime ;
- aucune dépendance vers watermark, selection reader ou Task provenance ;
- infra primaire implémente le port de reconstruction ; infra read implémente le writer ;
- Event scheduler ne dépend toujours d'aucune table `pocoma_read` ;
- aucun GET ne dépend des nouveaux adapters shadow.

## 23. Ordre précis d'implémentation

1. **Gate documentaire** — relire ce plan et la reconstruction historique ; acter `updatedAt` comme
   gate d'entrée du 7.7 et le delete terminal comme blocker de clôture, non d'implémentation shadow.
2. **Tests de caractérisation primaire** — figer les lectures temporelles, deleted children et contexte
   utilisateur avant d'ajouter le modèle.
3. **Modèle fonctionnel** — ajouter PotProjection/fragments/statut et tests de validation/déterminisme.
4. **Port de reconstruction** — ajouter le contrat exact et le service sans framework.
5. **Adapter primaire** — implémenter la query Pot-wide Expense non filtrée et l'adapter transactionnel ;
   valider les scénarios V1–V5.
6. **Identité pipeline** — ajouter constantes/bindings `pipeline-pot`, leurs tests, puis seulement la
   définition `read-pot/v1` au catalogue.
7. **Migration read-store V5** — créer tables/contraintes et tests fresh/upgrade.
8. **Writer concret** — digest canonique, write/reload/compare, tests idempotence/divergence.
9. **Handler exact** — prévalidation applicability, reconstruction, materialization/failure et reports.
10. **Runtime Event** — enregistrer relevance/strategy Pot sans changer discovery/slots 7.5.
11. **Runtime Task** — généraliser la composition, ajouter profil/déploiement shadow `read-pot/v1`.
12. **Atomicité** — exécuter les injections de failure et prouver l'enlistment réel.
13. **E2E shadow** — Event→Task→projection, version `DELETED` et comparaison au primaire ; GET inchangés.
14. **Micro-correctif terminal-delete** — après la preuve shadow, inspection ciblée des guards Expense,
    rejet métier post-delete et tests d'absence de nouvelle version/Event.
15. **Architecture/observabilité** — règles de dépendance et métriques bornées created/adopted/rejected/
    failed, sans tags potId/eventId.
16. **Documentation factuelle** — mettre à jour current state, runtime Task/Event, matrice, clôture
    write-side et le présent
    document seulement selon le code réellement livré.
17. **Validation complète** — commandes section 27 et `git diff --check`.

Chaque étape doit laisser les modules concernés compilables. Le catalogue ne doit jamais référencer
une génération dont les deux runtimes ne savent pas construire les bindings requis.

## 24. Modules et fichiers probablement touchés pendant l'exécution

### À ajouter

**[IMPLEMENTATION CHOICE]**

- `app/pipeline-pot/` et son enregistrement dans `app/pom.xml` ;
- modèle PotProjection dans `app/domain-projection/...` ;
- reconstructeur/port dans `app/engine-read-projection/...` ;
- adapter historique dans `app/infra-persistence-jpa/...` ;
- writer et migration V5 dans `app/infra-read-persistence/...`.

### À modifier

**[IMPLEMENTATION CHOICE]**

- `PocomaPipelineDefinitions` ;
- repository Expense pour la lecture Pot-wide sans filtre deleted ;
- POMs d'`infra-persistence-jpa`, des deux runtimes et tests d'architecture ;
- `EventConsumptionRuntimeConfiguration` pour relevance/strategy Pot ;
- `TaskConsumptionRuntimeConfiguration` pour bindings catalog-driven Balance/Pot ;
- propriétés/déploiement du Task worker pour une instance Pot shadow ;
- tests PostgreSQL des runtimes et du read store.

### Documentation factuelle après code

- `docs/architecture/read-side-current-state.md` ;
- `docs/architecture/consumption-event-pull-runtime.md` ;
- `docs/architecture/consumption-task-balance-runtime.md` ou une documentation Task runtime générique
  renommée/complétée ;
- `docs/architecture/module-dependency-matrix.md` ;
- `docs/architecture/pot-historical-reconstruction.md` si les détails confirmés évoluent.

### Explicitement non touchés fonctionnellement

**[CANONICAL INVARIANT]** GET/controllers, Query Kernel, `PipelineSelectionStrategy`, watermark runtime,
Balance calculation, Tasks administratives et backfill/rebuild/repair. Le seul changement write-side
autorisé est le micro-correctif terminal-delete ciblé décrit en section 16.

## 25. Risques et blockers

| Point | Classification | Traitement |
|---|---|---|
| Expense supprimée filtrée par le reconstructeur Balance | CONFIRMED BY TARGETED CODE INSPECTION | port Pot dédié + query sans filtre |
| delete Pot non terminal pour certaines commandes Expense | RÉSOLU EN 7.6 | projector shadow puis micro-correctif write-side ciblé et tests avant clôture |
| `updatedAt` non univoque | OPEN / BLOCKER 7.7 | exclure du contenu 7.6 ; gate avant ordering/indexes/pagination current 7.7 |
| ajout catalogue avant bindings | risque de configuration | changement atomique + tests de démarrage |
| mélange provenance/payload | risque architectural | handler uniquement sur payload structurel |
| divergence due à l'ordre JPA | risque déterministe | ordre canonique et digest versionné |
| snapshot partiel visible | risque transactionnel | writer sous service générique + tests rollback |
| failure enregistrée puis throw | risque de rollback involontaire | report Rejected après record terminal |
| FK descriptor avec ordre writer-first | contrainte technique | FK différée testée PostgreSQL |
| N+1 shares | risque performance borné | query Pot-wide optionnelle sans changer le contrat |
| duplication future d'ACL | risque de divergence | stocker les sources, index dérivé au 7.7 |

Les noms Java fins du port/adapters peuvent évoluer sans amender l'architecture. En revanche, changer
le contenu fonctionnel, l'identité publiée, l'applicabilité, le protocole transactionnel ou la
classification des blockers exige un amendement explicite du plan.

## 26. Critères de sortie

### Invariants canoniques satisfaits

- `PotProjection@V` est reconstruite indépendamment depuis le primaire exact ;
- snapshot complet avec Pot, Shareholders supprimés compris, Expenses supprimées comprises, shares et
  contexte d'autorisation ;
- identity exacte, immutable, deterministic, artifact XOR failure ;
- non-applicable = erreur protocole, aucun artifact ;
- watermark en retard n'empêche pas V ; selection reader jamais consultée ;
- head max et projections hors ordre ;
- transaction read-store tout-ou-rien ;
- handler sans provenance Event et compatible future Task admin ;
- GET actifs inchangés.

### Choix d'implémentation livrés et prouvés

- identité `read-pot/v1`, `POT`, `READ_POT`, `[1..∞]` publiée dans le catalogue ;
- `pipeline-pot`, reconstructeur exact et writer concret branchés sur les moteurs génériques ;
- migration read-store V5 fresh/upgrade ;
- Task runtime générique exécutable en pool Balance ou Pot ;
- test E2E shadow et comparaison primaire ;
- tests d'enlistment réel réussis.

### Blockers à lever ou accepter explicitement

- la clôture finale du Lot 7.6 exige la preuve write-side que delete Pot est terminal ;
- `updatedAt` reste absent du snapshot et explicitement ouvert ; il est un gate d'entrée du Lot 7.7
  avant toute réalisation ou validation de l'ordre, des indexes et de la pagination current.

## 27. Commandes de validation

Depuis `app` :

```bash
./mvnw -pl domain-projection,engine-read-projection,pipeline-pot -am test
./mvnw -pl infra-persistence-jpa -am test
./mvnw -pl infra-read-persistence -am test
./mvnw -pl runtime-event-consumption-worker -am test
./mvnw -pl runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
./mvnw test
git diff --check
```

Ajouter au run de validation PostgreSQL les profils/Testcontainers réellement utilisés pour les tests
fresh migration, concurrence et atomicité. Si Docker ou une dépendance externe empêche une commande,
consigner la commande exacte, sa sortie et les tests non prouvés ; ne pas déclarer le lot terminé.

## Annexe — classification finale

### CANONICAL INVARIANT

- reconstruction exacte et indépendante ; snapshot autonome ; identité complète ;
- applicability avant calcul ; aucune selection reader/watermark ;
- immutabilité, first terminal wins, head max et atomicité read-store ;
- contexte historique embarqué ; Task executor indépendant de la provenance.

### CONFIRMED BY DOCUMENTATION

- temporalité primaire semi-ouverte et deleted explicite ;
- contenu cible Pot/Shareholder/Expense/share ;
- contexte creator/membership et GET encore hors read store ;
- limites connues delete terminal et `updatedAt`.

### CONFIRMED BY TARGETED CODE INSPECTION

- repositories exacts et filtre Expense du reconstructeur Balance ;
- chaîne générique Task et composition Balance-only ;
- fondation materialization/failure/advisory lock/head ;
- mêmes ressources Spring transactionnelles actuellement inspectées ;
- catalogue Balance-only et prochaine migration read-store V5 ;
- absence de timestamp primaire univoque par `potVersion`.

### IMPLEMENTATION CHOICE

- modèle dans `domain-projection`, port/service dans `engine-read-projection`, binding `pipeline-pot` ;
- identité technique `read-pot/v1`, `POT`, `READ_POT`, `[1..∞]` ;
- quatre tables fragmentées et FK descriptor différée ;
- runtime Task commun configuré par génération ; digest canonique versionné.

### OPEN / BLOCKER

- source fonctionnelle de `updatedAt`, gate d'entrée du Lot 7.7 avant ordering/indexes/pagination
  current ; ce point n'empêche pas la clôture shadow du Lot 7.6.

## 28. État réel après implémentation

- Le micro-correctif write-side est livré : create, delete et updates Expense rejettent désormais un
  Pot supprimé avec `POT_ALREADY_DELETED` avant allocation de version, persistence ou Event.
- `read-pot/v1` et `READ_POT` sont publiés, produits par le runtime Event et exécutables par le moteur
  Task générique configuré pour cette génération exacte.
- La migration read-store V5 porte les quatre tables Pot et leurs FK internes ; le writer charge aussi
  exactement un snapshot autonome par `artifactId`.
- Le digest fonctionnel binaire versionné exclut identité d'artifact, instant technique et `updatedAt`.
- Le read store et le primaire partagent réellement le même `DataSource`, le même transaction manager
  et une propagation `REQUIRED`; la transaction Task englobe reconstruction, fragments, descriptor,
  head, provenance et CAS. Les tests de takeover prouvent le rollback lorsque le CAS terminal est perdu.
- Aucun GET actif n'est basculé. `updatedAt` reste OPEN/BLOCKER uniquement pour les parties 7.7
  dépendantes de l'ordre current.
