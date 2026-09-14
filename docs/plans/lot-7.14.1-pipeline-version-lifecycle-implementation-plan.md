# Lot 7.14.1 — Plan d'implémentation du lifecycle minimal des pipelineVersions

Statut : **READY FOR IMPLEMENTATION**.

Ce document est le plan d'implémentation courant du Lot 7.14.1. Aucun autre plan d'implémentation
7.14.1 n'était versionné dans `docs/plans` lors de sa création. Il applique le design canonique
`lot-7.14.1-pipeline-version-lifecycle-design.md`, notamment la règle : `active` gouverne uniquement
l'acquisition de nouvelles consommations ; un Claim acquis peut atteindre son issue normale.

## 1. Repository findings

### 1.1 Acquisition existante

Le CAS autoritatif d'une consommation existe déjà :

```text
SequentialConsumptionOrchestrator
-> AcquireConsumptionUseCase
-> TransactionalAcquireConsumptionUseCase
-> AcquireConsumptionService
-> ConsumptionLifecyclePersistencePort.acquire
-> JpaConsumptionLifecycleAdapter.acquire
```

`TransactionalAcquireConsumptionUseCase` ouvre la transaction. `JpaConsumptionLifecycleAdapter`
crée éventuellement le Slot, le verrouille via `findByKeyForUpdate`, vérifie son éligibilité, insère
le Claim et installe son identité sur le Slot. Le contrôle lifecycle autoritatif doit rejoindre ce
chemin avant toute mutation du Slot et dans la même transaction.

`LocatedConsumption` porte aujourd'hui la clé, l'exécution et le classificateur d'échec. Il ne porte
pas de précondition d'acquisition. `AcquireConsumptionInput` ne porte que la clé, le worker et le
lease. `AcquireResult` ne représente pas une inéligibilité externe au Slot.

### 1.2 Event et Task

`EventConsumptionLocator` fournit toutes les définitions déclarées à la discovery. Sa
`ConsumptionKey` encode déjà le `PipelineId` et la `pipelineVersion` du trigger. Après acquisition,
`ScheduleProjectionTasksForEventService` parcourt le catalogue applicable et crée les Tasks dans son
flux normal.

`TaskConsumptionLocator` est construit pour une `PipelineDefinition` exacte et découvre ses Tasks.
Sa clé `TASK_EXECUTOR` n'encode pas la pipeline, mais le locator connaît la définition avant
l'acquisition. Modifier la clé casserait l'identité des Slots existants ; le plan l'interdit.

### 1.3 Catalogues et serving

`PipelineDefinitionRegistry` est l'autorité des définitions declared. Les bindings `pipeline-pot` et
`pipeline-balance` connaissent la relation producteur/projection, mais aucune abstraction commune ne
l'agrège. `ProjectionProducerCatalog` reste donc nécessaire et sera construit exclusivement depuis
leurs déclarations framework-free.

`QueryVersionResolver` consomme déjà `QueryProjectionSelection`. Il ne doit pas recevoir de logique
lifecycle. Une couche amont résout la `ServingSelection`, exige sa définition dans le catalogue puis
construit la sélection attendue.

### 1.4 Persistance

`consumption_slots` et `consumption_claims` sont possédés par `infra-persistence-jpa`. Le futur control
store lifecycle doit partager leur `DataSource` et leur `PlatformTransactionManager` afin que le
verrou d'activation et le CAS du Claim appartiennent à une transaction PostgreSQL locale. Il n'a pas
besoin de partager la transaction complète des handlers ou du read store.

## 2. Écarts entre le code actuel et la cible

- aucun module engine ne porte les modèles, ports et use cases lifecycle ;
- aucune table ne persiste activation ou serving ;
- Event/Task discovery ne filtre pas les générations inactives ;
- l'acquisition ne sait pas évaluer une précondition dans sa transaction ;
- aucun verrou d'activation ne sérialise Claim et `deactivate` ;
- aucun catalogue producteur commun ni fournisseur serving n'existe ;
- le bootstrap ne valide ni n'initialise le control state.

Les services de scheduling, création de Task, exécution de Task, provenance, fencing et
terminalisation n'ont pas à être modifiés pour la sémantique active/inactive.

## 3. Stratégie générale d'implémentation

1. Créer les contrats lifecycle framework-free et le catalogue producteur.
2. Créer le control store et ses mutations atomiques.
3. Étendre le mécanisme générique d'acquisition avec une précondition exécutée dans sa transaction.
4. Attacher aux candidats Event/Task une précondition qui verrouille leur activation ; conserver un
   filtre discovery best effort.
5. Ne plus consulter le lifecycle après un Claim réussi.
6. Ajouter serving, bootstrap et intégrité sans modifier `QueryVersionResolver`.
7. Initialiser explicitement les générations actuellement en production ; ne déduire aucune valeur
   par ordre ou `MAX`.

La séquence canonique devient :

```text
DISCOVERY  -> filtre active best effort
CLAIM      -> lock/check active + CAS Slot/Claim dans une transaction locale
EXECUTION  -> flux actuel, sans dépendance lifecycle
```

## 4. Plan détaillé par étapes

### 4.1 Créer `engine-pipeline-lifecycle`

Objectif : introduire la frontière framework-free commune à production et query.

Types et packages :

| Type | Package | Responsabilité |
|---|---|---|
| `PipelineVersionActivation` | `...engine.pipeline.lifecycle.model` | Identité d'une activation |
| `ServingSelection` | `...engine.pipeline.lifecycle.model` | Décision serving par `ProjectionType` |
| `ProjectionProducerBinding` | `...engine.pipeline.lifecycle.model` | Déclaration statique producteur/projection |
| `ProjectionProducerCatalog` | `...engine.pipeline.lifecycle.catalog` | Autorité agrégée de la relation producteur |
| `PipelineActivationQuery` | `...engine.port.in.pipeline.lifecycle` | Lecture best effort de l'activation |
| `ServingSelectionQuery` | `...engine.port.in.pipeline.lifecycle` | Lecture autoritative serving |
| `PipelineVersionLifecycleUseCase` | `...engine.port.in.pipeline.lifecycle` | `activate`, `deactivate`, select/clear serving |
| `PipelineClaimActivationGate` | `...engine.port.in.pipeline.lifecycle` | Lock/check active pour un Claim |
| mutation port | `...engine.port.out.pipeline.lifecycle` | Mutations persistantes atomiques |

Le module dépend de `domain-pipeline`, `domain-projection` et du contrat transactionnel minimal. Il
ne dépend ni de Spring/JPA/JDBC, ni des locators, runtimes, processing engines ou pipelines métier.

`PipelineClaimActivationGate.lockIfActive(PipelineDefinition)` exprime uniquement la précondition
transactionnelle du Claim. Il ne sera jamais appelé après acquisition.

Tests : validations null, value semantics, catalogue producteur unique, doublons incohérents refusés,
mutations idempotentes et préconditions declared/active/producer avec ports fake.

Critère de fin : module compilable, hexagonal, sans framework et sans logique d'éligibilité/cutover.

### 4.2 Faire converger les déclarations producteur

Objectif : fournir une seule autorité `PipelineDefinition -> ProjectionType`.

- ajouter aux bindings `pipeline-pot` et `pipeline-balance` une déclaration
  `ProjectionProducerBinding` typée ;
- remplacer la constante texte de Balance par le `ProjectionType` canonique là où nécessaire ;
- agréger ces déclarations une seule fois dans la composition ;
- ne rien ajouter à `PipelineDefinitionRegistry`, au control store ou à une configuration parallèle.

Tests : déclarations exactes READ_POT/read-pot et POT_BALANCES/balance-projection, rejet des bindings
dupliqués contradictoires, un pipeline sans projection reste autorisé.

Critère de fin : `selectServing` peut prouver producteur/projection depuis l'unique catalogue agrégé.

### 4.3 Créer le control store lifecycle

Objectif : persister ensemble active et serving hors du read store dérivé.

Créer `infra-pipeline-lifecycle-persistence`, câblé sur la ressource transactionnelle de
`infra-persistence-jpa`. Le schéma logique est :

```sql
pipeline_version_activations(
  pipeline_id,
  pipeline_version,
  activated_at,
  primary key (pipeline_id, pipeline_version)
)

projection_serving_selections(
  projection_type primary key,
  pipeline_id,
  pipeline_version,
  selected_at,
  foreign key (pipeline_id, pipeline_version)
    references pipeline_version_activations on delete restrict
)
```

Le nom physique du schéma reste une décision de déploiement ; la migration choisira la convention
control existante ou `pocoma_control` sans déplacer cet état dans `infra-read-persistence`.

Implémenter :

- présence de ligne = active ; absence = inactive ;
- insert idempotent pour `activate` ;
- suppression de l'activation uniquement si aucune serving ne la référence ;
- upsert serving atomique sous PK `projection_type` ;
- clear idempotent ;
- queries séparées activation/serving ;
- lecture/verrouillage d'activation pour Claim.

Critère de fin : contraintes PK/FK et transactions rendent impossible serving+inactive et deux
servings du même type.

### 4.4 Implémenter les use cases lifecycle

Objectif : appliquer catalogue et invariants avant les mutations persistantes.

- `activate` exige une définition declared puis insère idempotemment ;
- `deactivate` exige declared et délègue à une suppression atomique non-serving ;
- `selectServing` exige declared, active et producteur compatible, puis remplace atomiquement ;
- `clearServing` supprime idempotemment sans toucher aux activations.

Les courses restent résolues par l'adapter persistant, jamais par une séquence read-then-write du
service. Mapper les violations structurelles vers des erreurs applicatives explicites.

Tests : deux versions actives, sélection inactive/undeclared/non-producer refusée, désactivation
serving refusée, changement v2→v3 puis désactivation v2 autorisée.

### 4.5 Étendre l'acquisition générique

Objectif : permettre un contrôle autoritatif dans la transaction existante sans coupler
`engine-consumption` à `domain-pipeline`.

Ajouter un contrat framework-free générique, par exemple :

```java
@FunctionalInterface
interface ConsumptionAcquisitionPrecondition {
    boolean lockAndCheck();

    static ConsumptionAcquisitionPrecondition alwaysSatisfied();
}
```

Évolutions :

- `LocatedConsumption` porte cette précondition ;
- `AcquireConsumptionInput` la transmet ;
- `AcquireConsumptionService` l'évalue avant `ConsumptionLifecyclePersistencePort.acquire` ;
- comme le service est enveloppé par `TransactionalAcquireConsumptionUseCase`, vérification et CAS
  partagent la transaction ;
- `AcquireResult.NotEligible` représente une précondition refusée sans Claim ni mutation du Slot ;
- `SequentialConsumptionOrchestrator` traite ce résultat comme un candidat non acquis et poursuit la
  recherche, sans failure ni compteur d'exécution.

Les locators sans lifecycle pipeline, notamment Command et latest-known, utilisent
`alwaysSatisfied()`. Ne pas modifier les `ConsumptionKey` existantes.

La précondition ne doit pas être conservée ni réévaluée après le retour `Acquired`.

Critère de fin : une précondition refusée ne crée aucun Slot/Claim et une précondition validée utilise
le CAS actuel sans altérer son fencing.

### 4.6 Sérialiser Claim et désactivation

Objectif : rendre l'ordre des commits autoritatif.

L'implémentation PostgreSQL de `PipelineClaimActivationGate` exécute dans la transaction d'acquisition :

```sql
select 1
from pipeline_version_activations
where pipeline_id = ? and pipeline_version = ?
for key share
```

- ligne trouvée : poursuivre immédiatement le CAS du Slot/Claim ;
- ligne absente : retourner faux avant toute mutation consumption ;
- `deactivate` supprime la ligne et attend le verrou incompatible ;
- le verrou est libéré au commit de l'acquisition, avant toute exécution métier.

Un test PostgreSQL doit confirmer la matrice réelle de conflit `FOR KEY SHARE`/`DELETE`. Si le driver
ou la requête générée ne tient pas ce verrou jusqu'au commit, corriger l'adapter SQL ; ne pas déplacer
le contrôle vers l'exécution.

Critère de fin : Claim commit avant deactivate implique traitement autorisé ; deactivate commit avant
Claim implique aucune acquisition.

### 4.7 Adapter Event discovery et acquisition

Objectif : filtrer tôt tout en conservant le Claim comme autorité.

- filtrer `PipelineDefinitionRegistry.all()` via `PipelineActivationQuery` avant
  `EventConsumptionDiscoveryPort` ;
- attacher au `LocatedConsumption` Event une précondition fermée sur `event.trigger()` et
  `PipelineClaimActivationGate` ;
- ne modifier ni `ScheduleProjectionTasksForEventService`, ni `TaskCreationPort` ;
- après Claim, laisser l'Event créer toutes les Tasks applicables prévues par le scheduling actuel,
  même si une désactivation commit entre-temps.

Une Task ainsi créée pour une génération inactive reste persistée mais non claimable. Après
réactivation, elle est acquise directement ; si aucune Task n'existe, la redécouverte historique
Event par identité pipeline/version reste possible.

Critère de fin : aucune acquisition Event inactive, mais aucune interruption d'un Event déjà claimé.

### 4.8 Adapter Task discovery et acquisition

Objectif : appliquer la même règle aux Tasks.

- si la `PipelineDefinition` du locator est inactive, sa discovery retourne immédiatement vide ;
- chaque Task localisée porte la précondition fermée sur cette définition ;
- après `AcquireResult.Acquired`, aucun composant d'exécution ne consulte le lifecycle ;
- conserver sans changement mapper, handler, artifact, provenance, lease/fencing, retry technique et
  terminalisation.

Ne créer ni `ConsumptionDeferredException`, ni release/retry/failure lifecycle. Une Task non claimée
reste durable et redevient acquérable après activation.

Critère de fin : deactivate avant Claim empêche le handler ; deactivate après Claim ne change aucun
résultat d'exécution.

### 4.9 Serving et Query Kernel

Objectif : fournir la génération serving explicite attendue par 7.9.2.

Créer un `QueryProjectionSelectionProvider` qui :

```text
ProjectionType
-> ServingSelectionQuery.findServing
-> PipelineDefinitionRegistry.require
-> QueryProjectionSelection
```

Absence de serving = absence explicite. Sélection orpheline = erreur d'intégrité. Aucun `MAX`, aucune
dernière active, aucun fallback historique. `QueryVersionResolver` reste inchangé.

Tests : même génération serving pour CURRENT et EXACT, active non-serving ignorée, absence sans
fallback, producteur exact conservé.

### 4.10 Bootstrap et migration compatible

Objectif : introduire le lifecycle sans arrêter implicitement les flux actuels.

- créer les tables et contraintes ;
- initialiser idempotemment les activations exactes actuellement déclarées : `read-pot/v1` et
  `balance-projection/v2` ;
- initialiser explicitement les servings READ_POT→read-pot/v1 et
  POT_BALANCES→balance-projection/v2 ;
- ne jamais utiliser ordre, `MAX`, latest-known, head, Tasks ou artifacts pour initialiser ;
- au bootstrap, vérifier activations declared, servings declared+active et producteur compatible ;
- faire échouer la composition concernée sur toute activation ou serving orpheline.

Déployer la migration et le câblage de façon atomique avec l'activation du gate : le code ne doit pas
commencer à exiger les lignes avant leur seed explicite.

Critère de fin : comportement existant préservé avec un control state explicite et auditable.

### 4.11 Architecture et documentation finale

- ajouter les modules au parent Maven et aux tests d'architecture ;
- mettre à jour `type-ownership.md`, `module-dependency-matrix.md`, `read-side-current-state.md`, le
  plan directeur Lot 7 et l'index documentaire ;
- marquer 7.14.1 DONE uniquement après validation complète ;
- confirmer que 7.14.2/7.14.3 restent NOT_STARTED et hors du code livré.

## 5. Stratégie de tests

### Unitaires et use cases

- modèles/value semantics et validation ;
- catalogue producteur unique ;
- quatre mutations, idempotence et erreurs ;
- précondition always/active/inactive ;
- `AcquireResult.NotEligible` sans exécution ;
- discovery active best effort ;
- fournisseur serving sans fallback.

### Adapter PostgreSQL

- absence de ligne = inactive ; activation concurrente ultérieure sans effet rétroactif ;
- PK activation et serving, FK serving→active ;
- double `selectServing` ; `selectServing(v3)` contre `deactivate(v3)` ;
- `FOR KEY SHARE` tenu jusqu'au commit du Claim et incompatible avec `DELETE` ;
- aucune mutation Slot/Claim lorsque l'activation est absente.

### Concurrence Event

- discovery active, deactivate commit, tentative de Claim : aucun Claim ;
- Claim commit, deactivate, traitement : succès normal ;
- Event claimé avant deactivate crée ses Tasks après deactivate ;
- ces Tasks restent non claimables tant que leur génération est inactive.

### Concurrence Task

- discovery active, deactivate commit, tentative de Claim : aucun Claim/handler ;
- Claim commit, deactivate, handler : artifact, provenance et terminalisation normaux ;
- Task créée mais non claimée avant deactivate : persistée, non exécutée.

### Réactivation et reconstruction

- travail durable présent pendant inactive : aucune nouvelle acquisition ;
- activate puis cycle suivant : acquisition possible sans reset ;
- Event historique redécouvert pour une nouvelle génération active ;
- une Task déjà créée pendant l'inactivité évite une recréation et devient simplement claimable ;
- versions actives multiples produisent indépendamment, serving inchangée.

### Architecture et non-régression

- aucune dépendance framework dans le moteur lifecycle ;
- aucune dépendance lifecycle de `engine-task-creation` ou de l'exécution après Claim ;
- Command/latest-known continuent avec précondition neutre ;
- leases, retries, fencing et terminalisation existants inchangés ;
- aucune dépendance à latest-known, `ProjectionHead`, ordre ou continuité.

## 6. Risques techniques / points à vérifier pendant l'exécution

1. Vérifier par test réel, pas seulement documentation PostgreSQL, le conflit `FOR KEY SHARE`/DELETE.
2. Garantir que la précondition est invoquée sous la transaction externe, avant toute création de
   Slot ; aucun appel direct non transactionnel ne doit contourner ce chemin.
3. Ne pas encoder la pipeline dans la clé Task existante : cela changerait l'identité des Slots et
   pourrait rejouer des Tasks terminales.
4. Éviter une dépendance `engine-consumption -> domain-pipeline` ; la précondition générique ferme sur
   le gate lifecycle dans les locators.
5. Accepter qu'un Event déjà claimé crée des Tasks après désactivation : c'est désormais requis, pas
   une race à corriger.
6. Ne pas conserver un verrou lifecycle pendant les handlers ou effets métier.
7. Coordonner seed et activation du gate afin que l'absence initiale de ligne n'arrête pas les flux
   existants lors du déploiement.

## 7. Definition of Done globale

- declared, active et serving restent trois concepts distincts ;
- `serving => active => declared` est garanti ;
- active gouverne uniquement les nouvelles acquisitions Event/Task ;
- Claim avant deactivate termine normalement ; deactivate avant Claim empêche l'acquisition ;
- discovery est best effort, Claim est autoritatif, execution est lifecycle-free ;
- aucune Task, Slot, Claim, artifact ou failure n'est supprimé/réinitialisé par deactivate ;
- réactivation reprend le travail durable au cycle normal suivant ;
- serving est explicite, unique par `ProjectionType`, sans défaut ni fallback ;
- le control store est distinct du read store et transactionnellement compatible avec Slots/Claims ;
- `ProjectionProducerCatalog` est l'unique autorité producteur/projection ;
- tests PostgreSQL prouvent les deux ordres Claim/deactivate ;
- aucun mécanisme de release/defer/retry n'est ajouté pour l'inactivité ;
- 7.14.2 et 7.14.3 restent hors périmètre.

## Simplifications par rapport au plan antérieur

- suppression du gate autour de `TaskCreationPort` : un Event claimé conserve son droit de finir ;
- suppression du gate juste avant le handler Task : le Claim est la frontière autoritative ;
- suppression de `ConsumptionDeferredException` et du release fenced lifecycle : inactive n'est pas
  une failure ni un retry ;
- suppression des verrous longs : la sérialisation s'arrête au commit du Claim ;
- suppression du couplage lifecycle/read effect : seule l'acquisition partage la transaction ;
- suppression des tests « deactivate avant effet bloque le handler » lorsqu'un Claim existe déjà.

## BLOCKERS / DESIGN CONTRADICTIONS

```text
NONE
```
