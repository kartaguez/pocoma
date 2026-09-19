# Architecture canonique du Read side et des projections

## 1. Statut, autorité et portée

Ce document est la référence normative pour la refonte du Read side à partir de la branche
`v2-make-it-pull`. Il remplace les modèles centrés sur les générations de pipelines,
`LatestKnownVersion`, les heads et la sélection d'une pipeline serving. Les documents de livraison
antérieurs restent utiles comme historique, mais ne prévalent pas sur cette cible.

Le premier lot stabilise uniquement le noyau de domaine générique. Il n'ajoute ni port, ni stockage,
ni parser ou serializer JSON, ni adaptateur Jackson, ni moteur JSON Schema, ni projection métier
concrète.

## 2. Modèle canonique

### 2.1 Identité exacte

Une projection exacte est identifiée par :

```text
ProjectionKey = (
  projectionType,
  targetObjectType,
  targetObjectId,
  targetVersion
)
```

`targetVersion` est une version canonique du Write side et vaut au minimum 1. Une identité de
pipeline, une version de pipeline, un Event, une Task, un worker, un claim ou une tentative ne fait
jamais partie de cette clé.

`ProjectionType`, `TargetObjectType`, `TargetObjectId`, `ArtifactType` et `ArtifactKey` sont des
valeurs textuelles non nulles et non blanches. Ces identités génériques évitent tout couplage du
noyau à `domain-pot` ou à un autre domaine métier.

### 2.2 Projection

`Projection` est une valeur candidate complète, structurellement saine et profondément immuable.
Elle porte exactement une `ProjectionKey` et une liste immuable de `ProjectionArtifact`, mais peut
encore ne pas satisfaire sa `ProjectionDefinition`. `ValidatedProjection` est la preuve typée que
cette valeur candidate satisfait le contrat déclaré.

Il n'existe ni `ProjectionRoot` métier, ni `ProjectionResult`. Dans le futur Read store, l'existence
d'une projection validée et publiée sera le marqueur fonctionnel de complétude. Un futur stockage
relationnel pourra employer une table nommée `projection_root` pour normaliser la clé et rattacher
les artifacts, mais ce détail physique n'introduira ni type métier `ProjectionRoot`, ni statut
propre.

### 2.3 Artifacts

Chaque `ProjectionArtifact` porte :

```text
artifactType
artifactKey
payload: JsonValue
```

L'identité logique d'un artifact dans une projection est le couple
`artifactType + artifactKey`. L'ordre physique de la liste n'a aucune sémantique. Si un ordre est
métier, il est encodé explicitement dans le payload ou dans la clé.

Le constructeur garantit seulement la santé structurelle de l'artifact : aucun champ Java null.
Les types autorisés, doublons, cardinalités et schémas relèvent exclusivement de la validation de la
projection complète.

### 2.4 Échec terminal

`ProjectionFailure` porte uniquement :

```text
ProjectionFailureId(UUID)
ProjectionKey
failedAt
```

Deux échecs portant la même clé et le même timestamp restent distincts grâce à leur UUID. Aucun code,
raison, message, stack trace ou metadata opérationnelle n'appartient à cet objet métier. Le
diagnostic et la politique de retry restent dans les mécanismes opérationnels de Task et de
consommation.

L'état d'une clé sera dérivé à l'avenir avec cette priorité permanente :

1. une projection validée et publiée est présente : `READY` ;
2. sinon, au moins une `ProjectionFailure` est présente : `FAILED` ;
3. sinon : `NOT_READY`.

La présence d'une projection validée et publiée prévaut donc toujours sur un échec antérieur. Aucun
statut n'est stocké dans `Projection` ou `ProjectionFailure`.

## 3. JSON immuable

Le noyau représente le JSON par le sealed type `JsonValue` et six variantes :

- `JsonObject` ;
- `JsonArray` ;
- `JsonString` ;
- `JsonNumber` ;
- `JsonBoolean` ;
- le singleton `JsonNull.INSTANCE`.

`JsonObject` et `JsonArray` copient respectivement leurs `Map` et `List`. Toutes les feuilles sont
immuables ; le graphe complet l'est donc profondément. Une référence Java null est interdite à tous
les niveaux. Une valeur JSON `null` s'exprime uniquement par `JsonNull.INSTANCE`.

Le noyau ne choisit aucun format texte, mapper ou vocabulaire de schéma. La frontière technique
unique est :

```java
boolean JsonSchemaValidator.isValid(JsonValue schema, JsonValue payload)
```

L'implémentation concrète et la version de JSON Schema seront choisies dans un adapter ultérieur.
Cet adapter devra distinguer un schéma canonique invalide d'un payload non conforme à un schéma
valide, sans modifier l'API du lot 1.

## 4. Définition déclarative

Une `ArtifactDefinition` associe un `ArtifactType`, une `Cardinality` et un schéma `JsonValue`.
`Cardinality(min, max)` accepte un maximum nullable pour représenter une borne supérieure illimitée.

Une `ProjectionDefinition` associe :

- un `ProjectionType` ;
- le `TargetObjectType` attendu ;
- une liste immuable d'`ArtifactDefinition`.

Deux définitions portant le même `ArtifactType` sont interdites. La définition est une valeur
déclarative : elle n'est ni une DSL, ni un resolver, ni un registre dynamique, ni un emplacement
pour des règles transverses arbitraires.

## 5. Validation et preuve typée

`ProjectionValidator` applique exclusivement les six contrôles suivants :

1. le `ProjectionType` correspond à la définition ;
2. le `TargetObjectType` correspond à la définition ;
3. chaque `ArtifactType` est déclaré ;
4. la cardinalité de chaque type déclaré est respectée ;
5. aucun couple `ArtifactType + ArtifactKey` n'est dupliqué ;
6. chaque payload est accepté par le `JsonSchemaValidator` avec le schéma du type correspondant.

Tout échec lève une `ProjectionValidationException`. Aucun objet validé n'est alors produit.

Le succès retourne un `ValidatedProjection`. Cette classe est publique et immuable, mais son
constructeur est package-private : le code extérieur au package
`com.kartaguez.pocoma.domain.projection` ne peut pas construire directement cette preuve via l'API
publique normale. Par convention d'architecture, `ProjectionValidator` ne la produit qu'après le
passage complet des six contrôles.

Le futur `ProjectionWritePort.publish` acceptera une `ValidatedProjection`, jamais une `Projection`
brute. La lecture rechargera une `Projection` complète puis appliquera la même définition et le même
validateur avant toute exposition métier.

## 6. Invariants de production et de lecture futurs

Une projection `P(X,V)` doit pouvoir être recalculée uniquement à partir de l'état canonique de
`X@V`, de la définition de `P` et du projector associé. Elle ne dépend pas :

- de l'Event déclencheur ;
- de `P(X,V-1)` ;
- d'une autre projection ;
- de l'ordre de traitement ;
- d'une génération ou sélection de pipeline ;
- d'un watermark, claim, slot, worker, retry ou statut de Task.

Une publication réussie persistera atomiquement la clé et tous ses artifacts, y compris une liste
vide valide. Un reader ne doit jamais exposer un graphe incomplet ou invalide. Une corruption lue
est une incohérence interne, pas un retard `NOT_READY`.

Deux traitements concurrents d'une même clé ne peuvent publier deux vérités différentes. Une
republication strictement équivalente pourra être adoptée comme succès idempotent ; un contenu
divergent devra être rejeté et observé par la couche opérationnelle.

## 7. Frontières d'architecture

Le package cœur `com.kartaguez.pocoma.domain.projection` dépend uniquement du JDK et de lui-même. Il
ne dépend notamment ni de `domain-pot`, ni de `domain-pipeline`, ni de Jackson, SQL/JPA, Spring,
SLF4J, Micrometer ou OpenTelemetry.

Les mécanismes d'observation seront ajoutés extérieurement par décoration ou composition. Les
objets métier ne portent aucune metadata destinée aux logs, métriques ou traces.

## 8. Isolation transitoire du legacy

Le module `pocoma-domain-projection-legacy` contient, sous
`com.kartaguez.pocoma.domain.projection.legacy`, les anciens types nécessaires aux flux actifs :

- `LatestKnownVersion` ;
- les projections Pot spécialisées ;
- les identités de génération et de pipeline ;
- descriptor, identifiant technique, digest, head et statut ;
- l'ancienne failure avec code ;
- l'invariant violation.

Ce module dépend volontairement de `domain-projection`, `domain-pot` et `domain-pipeline`. Son
changement de package et de module ne modifie pas le comportement des flux existants. Les modules ne
consommant que `ProjectionType`, notamment le lifecycle pipeline et `pipeline-balance`, restent
directement dépendants du nouveau noyau.

Cette dette pourra être supprimée uniquement après le cutover de tous les readers et writers
`READ_POT` historiques, le retrait des résolutions fondées sur latest-known/head/serving pipeline,
l'absence de référence compilée ou runtime aux types legacy, et la migration ou rematérialisation
des données nécessaires. Les migrations Flyway historiques ne sont jamais réécrites ; toute
suppression physique passera par de nouvelles migrations.

## 9. Hors scope du lot 1

Restent explicitement hors scope :

- `ProjectionWritePort` et `ProjectionReadPort` ;
- parser, serializer et format JSON canonique ;
- adapter Jackson et moteur JSON Schema ;
- tables universelles et transactions de publication ;
- projectors `READ_POT`, `POT_BALANCES` ou autres projections concrètes ;
- registre runtime de définitions ;
- mapping Event vers tâches de projection ;
- politique de résolution `CURRENT` ;
- migration ou suppression des données et APIs legacy.
