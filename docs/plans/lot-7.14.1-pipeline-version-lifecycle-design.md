# Lot 7.14.1 — Conception du lifecycle minimal des pipelineVersions

Statut : **DESIGN READY**.

Ce document est la référence de conception du modèle minimal `declared / active / serving`. Il doit
permettre une passe ultérieure de planification puis d'implémentation sans rouvrir les décisions
fonctionnelles. Il ne livre ni code, ni test, ni migration.

## 1. Statut et objectif

Le Lot 7.14.1 doit fournir le contrôle minimal nécessaire pour :

- distinguer une définition connue d'une génération autorisée à produire ;
- autoriser plusieurs pipelineVersions actives à converger en parallèle ;
- conserver au plus une pipelineVersion serving par `ProjectionType` ;
- fournir au Query Kernel la décision serving autoritative attendue par `QueryProjectionSelection` ;
- empêcher une pipelineVersion inactive d'acquérir de nouvelles consommations Event ou Task ;
- préserver les Tasks, Slots, Claims, artifacts et historiques déjà produits.

Il ne décide ni si une génération est suffisamment convergée pour servir, ni quand effectuer un
cutover. Ces responsabilités appartiennent aux Lots 7.14.2 et 7.14.3.

Sources vérifiées :

- `docs/architecture/read-side-target.md` ;
- `docs/architecture/read-side-current-state.md` ;
- `docs/plans/lot-7-read-side-implementation-plan.md` ;
- `docs/plans/lot-7.9.1-monoprojection-contract-revision-plan.md` ;
- `docs/plans/lot-7.9.2-query-version-resolution-design.md` ;
- `docs/architecture/type-ownership.md` ;
- `docs/architecture/module-dependency-matrix.md` ;
- les modules `domain-pipeline`, `domain-projection`, `engine-task-creation`,
  `engine-processing-event`, `engine-processing-task`, `locator-consumption-event`,
  `locator-consumption-task`, `pipeline-pot`, `pipeline-balance`, `infra-persistence-jpa` et
  `infra-read-persistence`.

## 2. Contexte vérifié dans le repository

Le code livre actuellement :

- `PipelineDefinition = PipelineId + pipelineVersion` ;
- `PipelineVersionDefinition = PipelineDefinition + VersionApplicability` ;
- `PipelineDefinitionRegistry`, catalogue immutable indexé par identité exacte ;
- `PocomaPipelineDefinitions`, qui déclare actuellement `balance-projection/v2` et `read-pot/v1` ;
- `ProjectionGenerationIdentity = ProjectionType + PipelineDefinition + PotId` ;
- `QueryProjectionSelection = ProjectionType + PipelineVersionDefinition serving` ;
- `QueryVersionResolver`, qui utilise exclusivement cette génération serving pour `CURRENT` et
  `EXACT`, y compris pour les lectures historiques.

Le flux Event actuel transmet `PipelineDefinitionRegistry.all()` à
`EventConsumptionDiscoveryPort`, puis `ScheduleProjectionTasksForEventService` parcourt de nouveau
toutes les définitions applicables. Il n'existe aucun filtre active.

Le flux Task actuel construit un `TaskConsumptionLocator` pour une `PipelineDefinition` configurée.
`TaskConsumptionDiscoveryPort` recherche les Tasks de cette génération et le locator les exécute
sans état d'activation persistant.

Les bindings `pipeline-pot` et `pipeline-balance` connaissent chacun leur `ProjectionType`, leur
`PipelineId`, leurs Task types et leurs handlers. En revanche, `PipelineDefinitionRegistry` ne porte
pas aujourd'hui la relation canonique « cette pipelineVersion produit ce ProjectionType ».

Aucun type, port, service, table ou migration ne représente encore l'activation ou la sélection
serving. Ce manque est attendu : le Lot 7.14.1 est `NOT_STARTED` dans l'état courant.

## 3. Vocabulaire

Les trois notions ne constituent pas un enum ni une progression exclusive.

```text
declared
= une PipelineVersionDefinition existe dans le catalogue canonique

active
= l'identité exacte de pipelineVersion possède une activation runtime persistée

serving
= une sélection persistée par ProjectionType pointe vers cette pipelineVersion
```

Le lien logique est :

```text
serving => active => declared
```

Les réciproques sont fausses. Plusieurs versions d'une même famille peuvent être actives ; une seule
peut être serving pour un `ProjectionType` donné.

`pipelineVersion` identifie une version du processus producteur. `businessVersion` identifie une
version métier d'un Pot. Le lifecycle 7.14.1 ne crée aucun lien d'ordre ou de continuité entre ces
deux axes.

## 4. Invariants globaux

1. `declared`, `active` et `serving` ne sont pas les valeurs d'un enum exclusif.
2. Une définition présente est declared ; aucun booléen declared n'est persisté.
3. L'activation est persistée par `PipelineDefinition` exacte.
4. Plusieurs pipelineVersions d'un même `PipelineId` peuvent être actives simultanément.
5. Active autorise l'acquisition de nouvelles consommations Event ou Task sur la plage
   d'applicabilité ; il ne prouve aucune convergence.
6. Inactive interdit toute nouvelle acquisition Event ou Task de cette génération.
7. Un Claim acquis pendant que la génération était active autorise la consommation à atteindre son
   issue normale, même si la génération devient ensuite inactive.
8. Une désactivation ne supprime ni ne terminalise aucune Task, Slot ou Claim.
9. Serving est une sélection séparée par `ProjectionType`, jamais un booléen par pipelineVersion.
10. Pour un `ProjectionType`, la cardinalité serving est `0..1`.
11. Une sélection serving ne peut cibler qu'une pipelineVersion declared, active et productrice du
    `ProjectionType`.
12. Une pipelineVersion serving ne peut pas être désactivée.
13. Le serving est autoritatif pour les lectures présentes et historiques.
14. Aucune sélection par `MAX(pipelineVersion)`, dernière active, première declared ou fallback
    d'ancienne génération n'est permise.
15. Production et serving restent indépendants : une version active non-serving produit normalement.
16. Serving, active et declared ne dépendent ni de latest-known, ni d'un `ProjectionHead`, ni de
    l'ordre des Tasks ou des Events.

Exemple valide :

```text
READ_POT/v2 : declared, active, serving
READ_POT/v3 : declared, active, non-serving
READ_POT/v4 : declared, inactive, non-serving
```

## 5. `declared`

Une pipelineVersion est declared si et seulement si sa `PipelineVersionDefinition` est retournée par
le catalogue canonique.

```text
PipelineDefinitionRegistry.require(identity) réussit
-> declared

require(identity) produit UnknownPipelineDefinitionException
-> non declared
```

La déclaration n'est pas une ligne mutable de lifecycle et n'est jamais stockée sous la forme
`declared = true|false`. `VersionApplicability` reste exclusivement portée par la définition du
catalogue.

Les primitives `activate` et `selectServing` doivent appeler le catalogue avec l'identité exacte.
Une identité absente est une erreur de configuration/commande et ne crée aucun état persistant.

Le catalogue actuel est suffisant pour prouver l'existence de la définition, mais pas sa relation à
un `ProjectionType`. Cette seconde précondition nécessite la vue producteur minimale décrite en
section 9.3.

## 6. `active`

Active est un état runtime persistant par identité exacte :

```text
PipelineDefinition(pipelineId, pipelineVersion)
```

Le modèle recommandé représente l'activation par la présence d'une ligne plutôt que par un booléen :

```java
record PipelineVersionActivation(PipelineDefinition pipeline) {}
```

Cette forme rend l'état binaire sans conserver de lignes inactives :

```text
ligne présente -> active
ligne absente  -> inactive
```

Une pipeline active est autorisée à :

- exposer de nouveaux candidats à la discovery Event et Task ;
- acquérir de nouvelles consommations Event et Task ;
- redécouvrir l'historique durable applicable ;
- converger indépendamment des autres générations.

La décision autoritative est prise au Claim. La discovery peut consulter `active` pour éviter de
remonter du travail inutile, mais son résultat est seulement une optimisation best effort.

Active ne signifie jamais :

- catch-up historique terminé ;
- absence de trou ;
- absence de `ProjectionFailure` ;
- backlog nul ;
- `eligibleForServing` ;
- serving.

## 7. `inactive`

Une pipelineVersion inactive n'autorise aucune nouvelle acquisition :

```text
inactive
-> aucune nouvelle consommation Event acquise
-> aucune nouvelle consommation Task acquise
```

La frontière autoritative est le Claim. Dès qu'un Claim a été acquis pendant que la génération était
active, la consommation est in flight et conserve le droit de s'achever normalement. Une
désactivation postérieure ne provoque ni interruption, ni rollback, ni release, ni retry, ni
déférencement spécifique au lifecycle.

Une consommation Event déjà acquise peut donc créer ses Tasks applicables après la désactivation.
Ces Tasks sont persistées normalement ; si elles n'ont pas encore de Claim, elles restent non
acquérables jusqu'à la réactivation. De même, une Task déjà claimée exécute son handler, persiste son
effet et atteint son issue normale après la désactivation.

La désactivation est un gate d'acquisition réversible. Elle ne produit aucun événement fonctionnel et
ne modifie pas l'histoire passée :

- les Tasks restent persistées ;
- les Tasks non terminales restent non terminales ;
- les Slots terminaux ne sont pas rouverts ;
- les Claims ne sont ni reset ni réécrits ;
- les artifacts et failures déjà matérialisés restent intacts ;
- aucune Task déjà existante n'est recréée.

Après réactivation, les Events et Tasks durables non terminalisés redeviennent acquérables selon les
règles normales d'applicabilité, de discovery, de claim, de retry et de fencing, sans reset ni rebuild
spécifique.

Une pipelineVersion serving ne peut jamais devenir inactive. Pour la retirer :

```text
1. selectServing vers une autre génération ou clearServing
2. deactivate l'ancienne pipelineVersion
```

## 8. `serving`

Serving est une décision de lecture persistée et autoritative par `ProjectionType`. Ce n'est pas un
attribut booléen de chaque pipelineVersion.

Le modèle interdit :

```text
pipeline v2 serving=true
pipeline v3 serving=false
```

Il retient :

```text
ProjectionType READ_POT -> PipelineDefinition read-pot/v2
```

L'absence de sélection est valide avant le premier cutover, pendant un bootstrap ou lorsqu'aucune
génération n'est choisie pour les reads. Le Query Kernel ne fabrique aucun défaut.

Une sélection serving ne modifie pas l'activation : remplacer v2 par v3 laisse v2 active tant qu'une
commande distincte ne la désactive pas. Cette séparation permet observation et rollback futurs sans
les implémenter dans 7.14.1.

## 9. `ServingSelection` et catalogue producteur

### 9.1 Type canonique

Le type proposé est :

```java
record ServingSelection(
    ProjectionType projectionType,
    PipelineDefinition servingPipeline) {}
```

Invariants locaux :

- `projectionType` non null ;
- `servingPipeline` non null ;
- value semantics immuables.

La sélection stocke l'identité exacte, pas la `PipelineVersionDefinition` complète. L'applicabilité
n'est donc pas dupliquée dans l'état lifecycle.

### 9.2 Lecture de la définition canonique

À partir de `ServingSelection.servingPipeline()`, le consommateur obtient la définition complète par :

```java
PipelineVersionDefinition definition = pipelineDefinitionRegistry.require(
    servingSelection.servingPipeline());
```

Une sélection persistée orpheline par rapport au catalogue constitue une erreur d'intégrité de
configuration détectée au bootstrap et à la lecture. Elle ne déclenche jamais un fallback.

### 9.3 Relation producteur/projection

Le repository ne possède pas encore d'abstraction commune reliant `ProjectionType` et producteur.
L'information existe de façon dispersée dans les bindings `pipeline-pot` et `pipeline-balance` :
`PotProjectionPipeline` expose déjà un `ProjectionType` typé, tandis que `BalancePipeline` conserve
encore son type de projection sous forme de constante texte, à côté de l'identité du pipeline.
`PipelineDefinitionRegistry` ne porte que l'identité et l'applicabilité des définitions ; lui ajouter
la relation producteur/projection mélangerait le catalogue générique des pipelines avec une propriété
qui n'existe pas pour tous les pipelines.

La précondition de `selectServing` impose donc une vue minimale :

```java
record ProjectionProducerBinding(
    ProjectionType projectionType,
    PipelineDefinition producer) {}

interface ProjectionProducerCatalog {
    boolean produces(PipelineDefinition pipeline, ProjectionType projectionType);
}
```

`ProjectionProducerCatalog` devient l'unique autorité agrégée pour cette relation. Ses entrées sont
les déclarations framework-free fournies par les bindings de pipelines ; elles ne sont jamais
recopiées dans une configuration, une table lifecycle ou un second mapping maintenu séparément. La
composition construit une seule instance à partir de ces déclarations et les consumers consultent
cette instance.

Ce catalogue n'est ni un registry dynamique de projections, ni un choix de serving. Il répond
uniquement à la question « cette pipelineVersion déclarée produit-elle ce ProjectionType ? ». Un
pipeline peut produire plusieurs effets ou ne produire aucune projection ; `PipelineId` et
`ProjectionType` restent distincts. Le futur plan d'implémentation devra faire évoluer les bindings
vers cette déclaration commune, puis interdire toute autre source de la relation.

## 10. Cardinalité et unicité

Pour chaque `ProjectionType` :

```text
0 serving -> valide
1 serving -> valide
2 serving -> impossible
```

L'unicité est garantie par la clé primaire persistante sur `projection_type`, pas seulement par un
check applicatif. Le remplacement d'une sélection existante est un upsert atomique sur cette clé.

Une `ServingSelection` doit référencer une activation existante. Une contrainte référentielle
composite garantit structurellement :

```text
serving => active
```

Le lien `active => declared` est vérifié contre le catalogue code/configuration, qui n'est pas une
table du lifecycle.

## 11. Modèle persistant minimal et frontière d'autorité

`active` et `serving` sont un control state autoritatif. Ils ne sont ni des projections dérivées, ni
des artifacts métier, et ils ne sont pas reconstructibles automatiquement depuis les Business
Events, Tasks, `ProjectionHead`, artifacts ou latest-known. Leur perte ferait perdre les décisions
opérationnelles « quelles générations produisent » et « quelle génération sert ».

La frontière canonique est donc :

```text
read store
  état métier dérivé et reconstructible

pipeline lifecycle control store
  état active/serving autoritatif et non dérivable
```

Le lifecycle control store porte ensemble activations et sélections serving. Le séparer en deux
stores empêcherait de garantir localement la FK `serving -> active` et les transitions atomiques.

Le schéma SQL suivant est conceptuel. `pocoma_control` illustre une frontière logique dédiée ; son
nom physique n'est pas un invariant fonctionnel et sera arrêté dans le plan d'implémentation selon
les conventions de déploiement :

```sql
create table pocoma_control.pipeline_version_activations (
    pipeline_id       varchar(...) not null,
    pipeline_version  integer      not null,
    activated_at      timestamptz  not null,
    primary key (pipeline_id, pipeline_version),
    check (pipeline_version >= 1)
);

create table pocoma_control.projection_serving_selections (
    projection_type   varchar(...) not null primary key,
    pipeline_id       varchar(...) not null,
    pipeline_version  integer      not null,
    selected_at       timestamptz  not null,
    foreign key (pipeline_id, pipeline_version)
        references pocoma_control.pipeline_version_activations
            (pipeline_id, pipeline_version)
        on delete restrict
);
```

Les timestamps servent l'audit opérationnel minimal ; ils ne participent pas aux décisions et ne
portent aucun ordre fonctionnel.

Propriétés :

- identité exacte de pipelineVersion ;
- absence de ligne d'activation = inactive ;
- une ligne maximum de serving par `ProjectionType` ;
- zéro ligne autorisée ;
- FK serving→activation ;
- aucune copie de `VersionApplicability` ;
- aucune colonne `declared`, `eligible`, `head`, `latest_known` ou `serving` sur l'activation.

La persistance lifecycle doit vivre dans une frontière transactionnelle compatible avec le store qui
porte l'acquisition des Slots et Claims. Le contrôle d'activation et le CAS d'acquisition doivent
pouvoir être sérialisés dans une transaction locale courte, sans transaction distribuée. Après le
commit du Claim, le lifecycle ne participe plus à la transaction d'exécution : création de Task,
effet métier et read store suivent leurs frontières existantes.

Le fait que le déploiement actuel puisse partager un PostgreSQL entre plusieurs schémas est une
facilité d'implémentation présente, pas la justification ni l'autorité du modèle. Le mécanisme SQL de
verrouillage reste un détail d'adapter ; l'invariant fonctionnel porte sur l'ordre des commits du
Claim et de `deactivate`.

En conséquence, une future séparation physique du read store dérivé ne déplace pas le lifecycle avec
lui. Le control store doit rester colocalisé transactionnellement avec le store de
consumption qui porte Slots et Claims, ou conserver une frontière offrant les mêmes garanties
locales d'acquisition. Il n'a pas besoin d'être colocalisé avec les effets complets Event/Task ou le
read store. Le Query Kernel peut lire `ServingSelection` dans ce control store : cette lecture d'une
décision runtime sur le chemin d'un GET ne transforme pas le lifecycle en read model métier.

## 12. Primitives de lifecycle

### 12.1 Forme applicative

Les mutations sont des use cases applicatifs du moteur lifecycle, pas des méthodes de domaine sur
`PipelineVersionDefinition` et pas des controllers :

```java
interface PipelineVersionLifecycleUseCase {
    void activate(PipelineDefinition pipeline);
    void deactivate(PipelineDefinition pipeline);
    void selectServing(ProjectionType projectionType, PipelineDefinition pipeline);
    void clearServing(ProjectionType projectionType);
}
```

Les violations de précondition sont des erreurs explicites du use case ; elles ne deviennent ni
`NOT_READY`, ni une failure de projection, ni un état de Task.

### 12.2 `activate`

Préconditions :

- identité non nulle ;
- définition declared dans `PipelineDefinitionRegistry`.

Effet : insertion de l'activation. Si elle existe déjà, l'opération réussit sans changement :
`activate` est idempotent.

### 12.3 `deactivate`

Préconditions :

- identité non nulle ;
- définition declared ;
- pipelineVersion non serving pour tout `ProjectionType`.

Effet : suppression de l'activation. Une identité déjà inactive est un succès sans changement si
elle reste non-serving. La désactivation d'une serving échoue.

### 12.4 `selectServing`

Préconditions :

- `ProjectionType` et identité non nulles ;
- définition declared ;
- activation présente ;
- `ProjectionProducerCatalog.produces(pipeline, projectionType)` vrai.

Effet : insertion ou remplacement atomique de la sélection pour ce `ProjectionType`. Sélectionner la
même identité est idempotent. Aucune éligibilité n'est calculée dans 7.14.1.

### 12.5 `clearServing`

Précondition : `ProjectionType` non null.

Effet : suppression de la sélection. L'absence préalable est un succès idempotent. Aucune activation
n'est modifiée.

### 12.6 Ports persistants et lectures orthogonales

Le port de mutation persistant peut regrouper les primitives qui doivent partager les garanties
transactionnelles :

```java
interface PipelineLifecycleStateMutationPort {
    void activate(PipelineDefinition pipeline);
    void deactivateIfNotServing(PipelineDefinition pipeline);
    void selectServingIfActive(ServingSelection selection);
    void clearServing(ProjectionType projectionType);
}
```

Les méthodes `deactivateIfNotServing` et `selectServingIfActive` expriment des garanties atomiques du
port, pas des séquences read-then-write race-prone. L'adapter du control store mappe les violations
de FK ou de précondition concurrente vers les erreurs applicatives prévues.

Les lectures consommées par production et query restent deux contrats orthogonaux :

```java
interface PipelineActivationQuery {
    boolean isActive(PipelineDefinition pipeline);
}

interface ServingSelectionQuery {
    Optional<ServingSelection> findServing(ProjectionType projectionType);
}
```

`PipelineActivationQuery` alimente le filtre best effort de discovery ; le verrou autoritatif est
pris dans la transaction d'acquisition. `ServingSelectionQuery` restitue la décision autoritative de
lecture sans choisir, classer ou fallback. Aucun port de lecture agrégé ne
regroupe artificiellement ces deux usages et aucune seconde abstraction ne définit `findServing`.

## 13. Atomicité et concurrence

Toutes les mutations d'un état lifecycle sont des transactions locales du control store. La
technologie physique peut être PostgreSQL, comme aujourd'hui, mais la garantie requise est la
compatibilité transactionnelle locale avec l'acquisition des Slots/Claims, non l'appartenance au
read store.

### 13.1 Deux `selectServing` concurrents

L'upsert atomique sur la PK `projection_type` sérialise les deux écritures. Après commit, une seule
sélection existe. Le gagnant observable est celui dont la transaction commit en dernier ; aucun ordre
métier supplémentaire n'est inféré.

### 13.2 `selectServing(v3)` contre `deactivate(v3)`

Les deux opérations verrouillent d'abord la ligne d'activation cible, puis la ligne serving du
`ProjectionType` si nécessaire :

- si `selectServing` gagne, la FK est créée et la désactivation suivante échoue car v3 sert ;
- si `deactivate` gagne, l'activation disparaît et la sélection suivante échoue car v3 est inactive.

La FK fournit un dernier verrou structurel : aucun commit ne peut laisser une sélection vers une
activation absente.

### 13.3 Changement puis désactivation de l'ancienne génération

`selectServing(type, v3)` remplace atomiquement v2 par v3. Une transaction ultérieure peut alors
désactiver v2. L'opération de sélection ne désactive jamais automatiquement l'ancienne génération.

### 13.4 Acquisition contre désactivation

La transaction autoritative d'acquisition vérifie et verrouille la ligne d'activation exacte avant le
CAS du Slot et l'insertion du Claim. Sur PostgreSQL, la stratégie proposée est un
`SELECT ... FOR KEY SHARE` sur `pipeline_version_activations`, tenu uniquement jusqu'au commit de la
transaction d'acquisition. `deactivate` supprime la ligne sous un verrou incompatible :

- si le Claim commit avant `deactivate`, la consommation est acquise et peut atteindre son issue
  normale sans autre contrôle lifecycle ;
- si `deactivate` commit avant la tentative de Claim, la ligne est absente et aucun Claim n'est
  acquis.

L'absence de ligne ne fournit pas de gap lock. Une activation concurrente postérieure à une tentative
refusée ne rend donc pas cette tentative rétroactivement valide : le travail redevient candidat au
cycle suivant. Le plan d'implémentation devra confirmer par un test PostgreSQL réel que la matrice de
conflit retenue sérialise bien `FOR KEY SHARE` et la suppression comme attendu.

La sérialisation ne couvre jamais l'exécution complète. Aucun verrou lifecycle n'est maintenu pendant
le scheduling d'un Event, le handler d'une Task, l'effet métier ou la terminalisation.

## 14. Event→Task discovery et création

### 14.1 Discovery best effort

`EventConsumptionLocator` transmet aujourd'hui toutes les définitions à
`EventConsumptionDiscoveryPort`. La cible transmet uniquement les définitions actuellement actives.

```text
declared + inactive -> exclue des candidats
declared + active + applicable -> candidate normale
```

Ce filtre évite de créer des Slots/Claims inutiles. Il reste best effort comme la discovery actuelle
et ne constitue pas l'autorité finale.

### 14.2 Gate autoritatif au Claim Event

Le contrôle autoritatif intervient pendant l'acquisition du Claim Event, avant le CAS du Slot. Il
ferme la course entre discovery et acquisition sans modifier `ScheduleProjectionTasksForEventService`
ni `TaskCreationPort`.

Si la génération devient inactive avant le commit du Claim, aucun Claim n'est acquis et le traitement
Event ne commence pas. Si le Claim commit avant la désactivation, `ScheduleProjectionTasksForEventService`
parcourt normalement les définitions applicables et peut créer les Tasks après la désactivation. Le
Claim et le Slot suivent ensuite leur issue normale ; aucune release ou failure lifecycle n'est
produite.

L'applicabilité continue d'être évaluée par `PipelineVersionDefinition.appliesTo(businessVersion)` et
reste indépendante d'active/serving.

La discovery et la création ne consultent jamais serving, latest-known, `ProjectionHead` ou
`eligibleForServing`. Une génération active non-serving produit normalement.

## 15. Task discovery et exécution

### 15.1 Discovery

`TaskConsumptionLocator` et `TaskConsumptionDiscoveryPort` sont actuellement liés à une
`PipelineDefinition` exacte. La requête de discovery doit joindre/consulter l'activation et ne pas
retourner de Task lorsque cette identité est inactive.

Ainsi un worker n'acquiert normalement aucun nouveau Claim pour une génération désactivée.

### 15.2 Gate autoritatif au Claim Task

La discovery restant best effort, la transaction d'acquisition vérifie l'activation exacte avant le
CAS du Slot et l'insertion du Claim. Si la désactivation gagne, aucun Claim n'est acquis et le handler
n'est jamais invoqué.

Après un Claim réussi, l'exécution ne consulte plus le lifecycle. Une désactivation postérieure
n'empêche ni le mapper/handler, ni l'artifact, ni la provenance, ni le fencing, ni la terminalisation
normale. Elle ne déclenche aucun release, retry, rollback ou failure spécifique.

Une Task persistée mais non claimée lors de la désactivation reste durable et non terminale. Elle
redevient naturellement acquérable au cycle suivant après réactivation.

Serving ne participe jamais à cette décision. v2 active+serving et v3 active+non-serving sont toutes
deux exécutables.

## 16. Reconstruction

Rebuild, replay et backfill utilisent le flux normal :

```text
nouvelle PipelineVersionDefinition applicable à l'historique
-> declared par présence au catalogue
-> activate
-> discovery Event normale
-> création et exécution de Tasks normales
-> convergence indépendante
```

Exemple :

```text
READ_POT/v2 active + serving
READ_POT/v3 declared
-> activate(v3)
-> v2 et v3 produisent en parallèle
-> le Query Kernel continue d'utiliser v2
```

Ni les Tasks ni les Slots de v2 ne sont réutilisés par v3. Le futur calcul
`eligibleForServing(v3)` et le cutover ne font pas partie de 7.14.1.

## 17. Bootstrap

Le bootstrap conserve trois sources distinctes :

1. le catalogue code/configuration fournit les `PipelineVersionDefinition` declared ;
2. les lignes `pipeline_version_activations` fournissent active ;
3. les lignes `projection_serving_selections` fournissent serving.

Le système peut démarrer avec zéro sélection serving. Aucun ordre du catalogue, aucune plus grande
pipelineVersion et aucune activation ne crée implicitement une sélection. Le bootstrap lit le
control store autoritatif ; il ne tente jamais de reconstruire active ou serving depuis les artifacts
du read store, les Events ou les Tasks.

Une migration de déploiement ou une configuration explicite peut initialiser idempotemment les
activations des générations connues pour préserver la production existante. Une sélection serving
initiale, si désirée, doit être explicitement nommée par `ProjectionType` et identité exacte.

Au démarrage, un contrôle d'intégrité vérifie :

- toute activation persistée correspond à une définition encore declared ;
- toute sélection serving correspond à une définition declared ;
- la FK garantit son activation ;
- le catalogue producteur confirme la relation projection/producteur.

Une incohérence fait échouer le bootstrap de la composition concernée ; elle ne déclenche jamais un
fallback ou une réparation automatique.

## 18. Intégration avec le Query Kernel

Le Query Version Resolver 7.9.2 reste inchangé. Une couche fournisseur située avant lui effectue :

```text
ProjectionType demandé
-> ServingSelectionQuery.findServing(projectionType)
-> Optional.empty : absence explicite, aucun choix par défaut
-> ServingSelection présente
-> PipelineDefinitionRegistry.require(servingPipeline)
-> PipelineVersionDefinition canonique
-> new QueryProjectionSelection(projectionType, definition)
-> QueryVersionResolver.resolve(...)
```

Forme conceptuelle du fournisseur :

```java
interface QueryProjectionSelectionProvider {
    Optional<QueryProjectionSelection> findServingSelection(ProjectionType projectionType);
}
```

Son implémentation dépend de `ServingSelectionQuery` et du catalogue déclaré. Elle ne calcule ni
éligibilité, ni MAX, ni fallback. L'absence de serving reste explicite pour l'orchestration ; 7.14.1
ne décide pas encore son mapping HTTP.

Pour une sélection présente, la définition complète fournie à `QueryProjectionSelection` est
exactement celle dont l'identité est persistée. Le resolver consulte ensuite cette génération pour
les lectures récentes et historiques.

`ServingSelectionQuery` lit ici une décision du lifecycle control store. Cette dépendance dans le
chemin d'un GET est normale : elle choisit l'autorité de lecture, mais ne lit aucun artifact métier et
ne fait pas du control state une projection reconstructible.

## 19. Ownership et modules

Le lifecycle gouverne production et query ; il ne doit donc vivre ni dans `engine-query`, ni dans un
pipeline métier, ni dans un runtime.

### 19.1 Nouveau module recommandé

Créer ultérieurement un module framework-free `engine-pipeline-lifecycle` :

| Élément | Package proposé | Responsabilité |
|---|---|---|
| `PipelineVersionActivation` | `com.kartaguez.pocoma.engine.pipeline.lifecycle.model` | État active par présence |
| `ServingSelection` | `com.kartaguez.pocoma.engine.pipeline.lifecycle.model` | Décision serving par ProjectionType |
| `ProjectionProducerBinding` | `com.kartaguez.pocoma.engine.pipeline.lifecycle.model` | Relation statique producteur/projection |
| `ProjectionProducerCatalog` | `com.kartaguez.pocoma.engine.pipeline.lifecycle.catalog` | Validation producteur/projection |
| `PipelineVersionLifecycleUseCase` | `com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle` | Quatre mutations minimales |
| `ServingSelectionQuery` | `com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle` | Lecture serving publique |
| `PipelineActivationQuery` | `com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle` | Gate active public pour production |
| state mutation port | `com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle` | Mutations lifecycle atomiques |
| services lifecycle | `com.kartaguez.pocoma.engine.service.pipeline.lifecycle` | Préconditions catalogue/producteur et transactions |

Le module dépend de `domain-pipeline`, `domain-projection` et du contrat transactionnel minimal
d'`engine-core`. Il ne dépend d'aucun framework, runtime, locator, moteur de query, moteur de
processing ou pipeline métier.

`domain-pipeline` reste JDK-only ; `ServingSelection`, qui dépend de `ProjectionType`, ne doit pas y
être introduit. `domain-projection` ne reçoit pas non plus un état opérationnel mutable.

### 19.2 Adaptation persistante

Le lifecycle ne doit pas être ajouté à `infra-read-persistence`, dont l'ownership reste le read store
dérivé et reconstructible. Le futur plan d'implémentation doit créer une frontière d'adaptation
explicite, par exemple `infra-pipeline-lifecycle-persistence`, propriétaire du control store et des
ports lifecycle.

Cette frontière logique est déployée sur la ressource transactionnelle qui porte l'acquisition des
Slots et Claims. Le nom de module clarifie l'ownership ; il n'impose ni une base physique séparée ni
le nom illustratif `pocoma_control`. Elle implémente les lectures activation et serving, le verrou
d'acquisition, les mutations atomiques, l'unicité et la FK décrites plus haut.

### 19.3 Consommateurs

- `locator-consumption-event` et `locator-consumption-task` consomment la vue active pour la discovery
  best effort et attachent le contrôle autoritatif à l'acquisition ;
- le moteur de consommation évalue cette précondition dans la transaction existante d'acquisition ;
- `engine-task-creation` et `engine-processing-task` ne dépendent pas du lifecycle après le Claim ;
- la composition du Query Kernel consomme `ServingSelectionQuery` depuis le control store et le
  catalogue déclaré ;
- les runtimes ne font que câbler ces contrats et adapters.

Cette orientation évite toute dépendance du moteur lifecycle vers ses consommateurs.

## 20. Matrice de tests de conception

### 20.1 Déclaration

| Cas | Résultat attendu |
|---|---|
| définition présente | declared |
| définition absente + activate | erreur, aucune activation |
| définition absente + selectServing | erreur, aucune sélection |
| état persisté absent du catalogue au bootstrap | échec d'intégrité explicite |

### 20.2 Activation

| Cas | Résultat attendu |
|---|---|
| activate declared inactive | ligne active créée |
| activate already active | succès idempotent, une ligne |
| deux activate concurrents | une ligne, aucun doublon |
| deactivate active non-serving | ligne supprimée |
| deactivate already inactive non-serving | succès idempotent |
| deactivate serving | refus, active et serving inchangés |

### 20.3 Production

| Cas | Résultat attendu |
|---|---|
| declared inactive | aucune nouvelle consommation Event ou Task acquise |
| active applicable | génération candidate et Claim normal |
| active non-serving | production normale |
| Task préexistante + inactive | non acquérable, persistée et non terminalisée |
| désactivation après discovery, avant Claim | aucun Claim, aucune exécution |
| désactivation après Claim Event | Event termine et peut créer ses Tasks |
| désactivation après Claim Task | handler, effets et terminalisation normaux |
| réactivation | discovery et acquisition redeviennent possibles sans reset |
| activation/désactivation | aucune mutation des Tasks/Slots/Claims/artifacts historiques |

### 20.4 Serving

| Cas | Résultat attendu |
|---|---|
| zéro serving | valide, `Optional.empty()` |
| select active producer | devient unique serving |
| select inactive | refus |
| select undeclared | refus |
| select non-producer | refus |
| select même génération | succès idempotent |
| v2 serving puis select v3 | v3 unique serving, v2 reste active |
| clearServing | zéro serving, activations inchangées |
| deactivate après clear/changement | autorisé |

### 20.5 Concurrence

| Cas | Invariant vérifié |
|---|---|
| deux selectServing concurrents | jamais deux lignes pour le ProjectionType |
| selectServing(v3) vs deactivate(v3) | jamais serving+inactive |
| Claim Event vs deactivate | Claim committé avant : Event termine ; deactivate avant : aucun Claim |
| Claim Task vs deactivate | Claim committé avant : Task termine ; deactivate avant : aucun Claim |
| absence de ligne puis activate | tentative courante refusée, cycle suivant acquérable |

### 20.6 Query Kernel

| Cas | Résultat attendu |
|---|---|
| findServing(READ_POT) | `ServingSelection` exacte |
| aucune serving | Optional vide, aucun MAX |
| sélection orpheline | erreur d'intégrité, aucun fallback |
| ServingSelection + catalogue | `QueryProjectionSelection` canonique |
| serving v2, active v3 non-serving | Query Kernel utilise v2 |
| historique demandé | même serving que CURRENT |

### 20.7 Reconstruction et architecture

- v2 active+serving et v3 active+non-serving produisent toutes deux ; les reads restent sur v2 ;
- une nouvelle version active redécouvre l'historique applicable sans mécanisme de rebuild séparé ;
- Event historique durable et v3 inactive : aucune consommation Event v3 n'est acquise ; après
  `activate(v3)`, l'Event peut être redécouvert et claimé. Si une Task v3 avait déjà été créée par un
  Event en vol, elle devient directement acquérable ; sinon le scheduling normal la crée ;
- lifecycle indépendant d'AUTH, HTTP, controller, latest-known, `ProjectionHead`, Task ordering et
  continuité de businessVersion ;
- module engine sans Spring/JPA/JDBC ; adapter du control store seul propriétaire du SQL lifecycle ;
- read store dérivé et lifecycle control store ont des ownerships distincts ; perte du read store ne
  reconstruit ni ne réinitialise active/serving ;
- aucune dépendance d'`engine-pipeline-lifecycle` vers query, processing, locators, runtimes ou
  pipelines métier.

## 21. Risques et pièges

1. Modéliser `DECLARED/ACTIVE/SERVING` comme enum exclusif empêcherait plusieurs versions actives.
2. Stocker `serving=true` sur plusieurs lignes rendrait l'unicité fragile et race-prone.
3. Confondre active et `eligibleForServing` autoriserait un cutover sans preuve de convergence.
4. Empêcher une pipeline active non-serving de produire bloquerait le catch-up préalable.
5. Choisir automatiquement le plus grand pipelineVersion violerait la décision serving explicite.
6. Désactiver une serving avant clear/changement produirait un état incohérent.
7. Supprimer les Tasks lors d'une désactivation détruirait l'historique durable.
8. Terminaliser une Task parce que sa pipeline est inactive confondrait gate opérationnel et résultat
   fonctionnel.
9. Faire dépendre Event discovery de serving empêcherait les nouvelles générations de converger.
10. Utiliser latest-known ou `ProjectionHead` pour active/serving mélangerait exposition, production
    et lifecycle.
11. Dupliquer `VersionApplicability` dans les tables lifecycle créerait deux autorités concurrentes.
12. Introduire un rebuild séparé contournerait la redécouverte normale Event→Task.
13. Vérifier active seulement en discovery laisserait une race jusqu'au Claim.
14. Vérifier active puis acquérir sans transaction/verrou compatible laisserait la désactivation
    gagner entre les deux opérations.
15. Recontrôler active après le Claim ferait dépendre une consommation in flight d'un changement de
    lifecycle postérieur et introduirait rollback/release artificiels.
16. Traiter inactive comme failure/rejet terminal empêcherait la reprise après réactivation.
17. Placer le lifecycle dans `engine-query` ferait dépendre la production d'un moteur de lecture.
18. Persister active/serving dans le read store dérivé ferait croire à tort que ces décisions sont
    reconstructibles et couplerait leur autorité à son déploiement physique.
19. Maintenir la relation producteur/projection dans plusieurs mappings indépendants créerait des
    autorités concurrentes.

## 22. Hors périmètre

Le Lot 7.14.1 ne calcule ou n'implémente pas :

- `eligibleForServing` ;
- structural readiness ;
- catch-up historique complet ;
- détection de trous ;
- agrégation de failures ;
- backlog nul ;
- capacité observée des workers ;
- preflight de cutover ;
- cutover ou rollback gouverné ;
- période d'observation ;
- auto-switch ;
- stratégie de migration progressive ;
- endpoint admin final ;
- mapping HTTP de l'absence de serving.

Une future valeur `eligibleForServing=true` ne déclenchera jamais automatiquement une mutation
serving.

## 23. Relation avec 7.14.2 et 7.14.3

```text
7.14.1
  catalogue declared
  activation persistée
  serving selection persistée
  invariants minimaux et concurrence
  primitives de mutation
  gates d'acquisition
  port de lecture serving

7.14.2
  eligibleForServing
  structural readiness
  historical catch-up
  holes et failures
  diagnostic d'éligibilité

7.14.3
  preflight
  cutover gouverné
  observation
  rollback
  procédure opératoire
```

7.14.2 lit les états de 7.14.1 mais ne les mute pas automatiquement. 7.14.3 orchestre explicitement
les primitives 7.14.1 après preflight ; aucun callback d'éligibilité ne sélectionne serving.

## 24. Critères de design fermé

Le design est fermé lorsque les assertions suivantes sont normatives :

1. declared = présence dans `PipelineDefinitionRegistry` ;
2. aucune colonne declared n'existe ;
3. active = présence persistée par identité exacte ;
4. plusieurs versions peuvent être actives ;
5. inactive bloque toute nouvelle acquisition Event ou Task sans détruire ni terminaliser ;
6. serving est une sélection `ProjectionType -> PipelineDefinition` séparée ;
7. cardinalité serving `0..1` garantie par PK ;
8. activations et serving résident ensemble dans un control store autoritatif distinct du read store
   dérivé ;
9. FK et transactions garantissent `serving => active` ;
10. catalogue et bootstrap garantissent `active => declared` ;
11. `ProjectionProducerCatalog` est l'unique vue agrégée de déclarations issues des bindings, jamais
    une configuration parallèle ;
12. select exige declared+active+producer ;
13. deactivate serving échoue ;
14. activation, re-activation, sélection identique et clear sont idempotents ;
15. discovery filtre active pour l'efficacité ; le Claim revalide sous verrou pour la correction ;
16. Claim et désactivation sont sérialisés par leur ordre de commit ; un Claim déjà acquis autorise
    l'achèvement normal sans nouveau contrôle lifecycle ;
17. la frontière transactionnelle lifecycle est compatible avec l'acquisition des Slots/Claims sans
    dépendre d'une colocalisation avec les effets métier ou le read store ;
18. v2 serving et v3 active non-serving produisent en parallèle ;
19. une activation permet de redécouvrir les Events historiques déjà consommés pour cette nouvelle
    génération ;
20. le Query Kernel reçoit la définition serving canonique sans modifier `QueryVersionResolver` ;
21. aucune sélection implicite ou fallback n'existe ;
22. `VersionApplicability` reste dans le catalogue ;
23. le modèle n'utilise ni latest-known ni head ;
24. éligibilité, cutover et rollback restent hors 7.14.1.

État de fermeture du design :

| Sujet | État |
|---|---|
| Declared semantics | `CLOSED` |
| Active semantics | `CLOSED` |
| Serving semantics | `CLOSED` |
| Serving uniqueness | `CLOSED` |
| Production gating | `CLOSED` |
| Task preservation | `CLOSED` |
| Query integration | `CLOSED` |
| Reconstruction | `CLOSED` |
| Concurrency | `CLOSED` |
| Query ports | `CLOSED` |
| Persistence boundary | `CLOSED` |

## 25. Questions ouvertes / blockers

L'inspection révèle des adaptations futures nécessaires, mais aucune contradiction bloquante :

- le catalogue déclaré existe déjà ;
- le catalogue producteur étroit est absent mais peut être assemblé comme unique vue des déclarations
  déjà possédées par les bindings, sans seconde configuration ;
- la frontière d'adaptation du control store reste à créer et doit partager une ressource
  transactionnelle compatible avec l'acquisition des Slots/Claims qu'elle gouverne ;
- le contrat d'acquisition devra transporter une précondition lifecycle sans coupler le moteur de
  consommation générique à `domain-pipeline`.

Ces éléments sont du travail d'implémentation cadré par le présent design, pas des arbitrages
fonctionnels.

```text
OPEN QUESTIONS / BLOCKERS
NONE
```

## DECISIONS CONFIRMED

- `declared / active / serving` ne forment pas un enum exclusif.
- Declared est l'existence d'une `PipelineVersionDefinition` dans le catalogue.
- Active est un état runtime persistant par pipelineVersion ; plusieurs versions peuvent être actives.
- Active autorise l'acquisition de nouvelles consommations mais ne prouve ni convergence, ni
  éligibilité, ni serving.
- Inactive bloque les nouveaux Claims Event/Task sans interrompre les Claims déjà acquis.
- Serving est une sélection séparée par `ProjectionType`, de cardinalité `0..1`.
- L'absence de serving est valide et aucun défaut n'est calculé.
- `serving => active => declared` ; select inactive et deactivate serving sont interdits.
- Serving est autoritatif pour les lectures présentes et historiques.
- Event discovery dépend d'active, jamais de serving.
- Une génération active non-serving produit normalement.
- Les lectures lifecycle sont séparées entre `PipelineActivationQuery` et `ServingSelectionQuery`.
- Active et serving résident ensemble dans un control store autoritatif distinct du read store dérivé.
- La relation producteur/projection possède une unique vue agrégée issue des bindings de pipelines.
- `eligibleForServing`, cutover, rollback et auto-switch sont hors périmètre.

## PROPOSED TYPES

```java
record PipelineVersionActivation(PipelineDefinition pipeline) {}

record ServingSelection(
    ProjectionType projectionType,
    PipelineDefinition servingPipeline) {}

record ProjectionProducerBinding(
    ProjectionType projectionType,
    PipelineDefinition producer) {}

interface ProjectionProducerCatalog {
    boolean produces(PipelineDefinition pipeline, ProjectionType projectionType);
}

interface PipelineActivationQuery {
    boolean isActive(PipelineDefinition pipeline);
}

interface ServingSelectionQuery {
    Optional<ServingSelection> findServing(ProjectionType projectionType);
}

interface PipelineVersionLifecycleUseCase {
    void activate(PipelineDefinition pipeline);
    void deactivate(PipelineDefinition pipeline);
    void selectServing(ProjectionType projectionType, PipelineDefinition pipeline);
    void clearServing(ProjectionType projectionType);
}

interface PipelineLifecycleStateMutationPort {
    void activate(PipelineDefinition pipeline);
    void deactivateIfNotServing(PipelineDefinition pipeline);
    void selectServingIfActive(ServingSelection selection);
    void clearServing(ProjectionType projectionType);
}
```

## LIFECYCLE INVARIANTS

```text
declared(identity) := pipeline catalogue contains identity
active(identity)   := activation row exists for identity
serving(type)      := optional selection row keyed by type

serving(type) = identity
=> active(identity)
=> declared(identity)
=> producer(identity, type)

active != eligibleForServing
active != serving
inactive => no new Event or Task Claim
Claim committed while active => execution may complete after deactivation
serving cardinality per ProjectionType = 0..1
active/serving authority = lifecycle control store, not derived read store
```

## TRANSITIONS

```text
activate(declared inactive)       -> active
activate(active)                  -> active, idempotent
deactivate(active non-serving)    -> inactive
deactivate(inactive non-serving)  -> inactive, idempotent
deactivate(serving)               -> refused

selectServing(declared active producer)
-> atomically replace the unique selection for ProjectionType

selectServing(inactive|undeclared|non-producer)
-> refused

clearServing(type)
-> no serving for type, idempotent
```

## TEST MATRIX

- Declared : définition présente/absente et intégrité de bootstrap.
- Active : activate/deactivate idempotents, plusieurs versions actives, serving non désactivable.
- Production : inactive exclue de discovery best effort et des nouveaux Claims ; un Claim acquis
  termine normalement ; réactivation reprend sans reset.
- Serving : zéro ou une sélection, active+declared+producer requis, remplacement atomique.
- Concurrence : double select et select/deactivate ne violent jamais les invariants.
- Query : `ServingSelection` + catalogue produit exactement `QueryProjectionSelection`, sans MAX ni
  fallback.
- Reconstruction : ancienne serving et nouvelle active produisent ensemble, reads sur serving seule.
- Reconstruction après activation : l'Event historique ou la Task durable redevient acquérable au
  cycle suivant, sans mécanisme parallèle.
- Persistence : active et serving partagent le control store ; l'activation partage uniquement la
  transaction locale d'acquisition des Claims. Le read store dérivé n'en est ni propriétaire ni
  source de reconstruction.
- Architecture : aucune dépendance à AUTH, HTTP, latest-known, head, ordre ou continuité.

## OPEN QUESTIONS / BLOCKERS

```text
NONE
```
