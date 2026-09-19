# Architecture canonique du Read side et des projections

## 1. Statut, autorité et portée

Ce document est la référence normative pour la refonte du Read side à partir de la branche
`v2-make-it-pull`. Il remplace les modèles centrés sur les générations de pipelines,
`LatestKnownVersion`, les heads et la sélection d'une pipeline serving. Les documents de livraison
antérieurs restent utiles comme historique, mais ne prévalent pas sur cette cible.

Le premier lot stabilise le noyau de domaine générique. Le deuxième lot ajoute les ports
universels et leur stockage relationnel canonique. Le troisième lot ajoute la lecture applicative
exacte et la revalidation des projections stockées. Le quatrième lot définit la lecture métier
versionnée d'un Pot à partir des projections `AUTH` et `READ_POT`, toujours sans projector, worker,
registre runtime de définitions ni wiring.

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
`artifactType + artifactKey`. `Projection` conserve une `List` immuable, mais sa position n'a
aucune sémantique métier. L'ordre fourni à la publication n'est pas persisté, et l'ordre physique
de PostgreSQL n'a aucune signification. L'adapter restitue un ordre technique déterministe par
`artifactType`, puis `artifactKey`. Une logique métier ne doit jamais dépendre de cet ordre ; si un
ordre est métier, il est encodé explicitement dans le payload ou dans la clé.

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

`JsonNumber` conserve pour ce lot la sémantique native de `BigDecimal.equals`, sensible à la
scale. Le round-trip du store n'est donc pas garanti de préserver cette égalité représentationnelle.
Les tests PostgreSQL 17 montrent notamment que JSONB restitue `1E+2` sous la forme `100` ; le
décodage Jackson actuel réduit également certaines échelles, par exemple `100.0` vers `1E+2` et
`0.00000100` vers `0.000001`. Ces valeurs restent numériquement équivalentes, mais les
`JsonNumber` correspondants ne sont pas nécessairement égaux au sens Java. Un lot ultérieur devra
choisir explicitement une représentation canonique à la construction — probablement via une
normalisation cohérente de `BigDecimal` — ou une égalité numérique dédiée avant que l'égalité exacte
des payloads ne devienne un contrat fonctionnel.

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

`ProjectionWritePort.publish` accepte une `ValidatedProjection`, jamais une `Projection` brute.
Dans le lot 2, `ProjectionReadPort.findProjection` recharge uniquement une `Projection` brute et
complète. La revalidation avant exposition métier appartient à un lot ultérieur.

## 6. Lecture applicative exacte

`ExactProjectionReadUseCase` reçoit explicitement une `ProjectionKey` et une
`ProjectionDefinition`. Avant tout accès au store, les types de projection et d'objet cible de la
clé doivent correspondre à ceux de la définition ; une incohérence est une erreur d'appel et non un
état de lecture.

Lorsqu'une projection est trouvée, sa clé doit être exactement celle demandée, y compris
`targetObjectId` et `targetVersion`. Cette vérification précède la revalidation, car la définition
ne porte pas ces deux composants. Une autre clé retournée ou une projection incompatible avec sa
définition provoque une `StoredProjectionInvariantViolationException`. Dans les deux cas, les
failures historiques ne sont pas consultées et aucune écriture n'est effectuée.

Une projection de la bonne clé et validée produit `ProjectionReadResult.Ready`, qui transporte la
`ValidatedProjection` et en dérive sa clé. Si aucune projection n'existe, la présence d'au moins une
failure produit `Failed` ; son absence produit `NotReady`. `Failed` et `NotReady` transportent la
clé demandée. Ces trois résultats sont les seuls états normaux du use case.

Le caller fournit directement la définition. Aucun registre, resolver ou catalogue de définitions
n'est introduit. Le service reçoit un `ProjectionValidator` déjà assemblé et ne connaît ni
`JsonSchemaValidator`, ni persistence, ni framework.

## 7. Lecture métier versionnée d'un Pot

Le module Java pur `engine-pot-read` porte le use case :

```java
GetPotAtVersionResult get(
    UserId userId,
    Set<Permission> permissions,
    PotId potId,
    long version
)
```

Ses états normaux sont exclusivement `Ready`, `Forbidden`, `AuthFailed`, `AuthNotReady`,
`ReadPotFailed` et `ReadPotNotReady`. Une violation du contrat du store ou d'un invariant transverse
AUTH/READ_POT reste une exception interne et n'est jamais transformée en état normal.

L'autorisation combine obligatoirement deux barrières distinctes :

```text
PocomaPermissions.POT_VIEW
    AND
(créateur OR shareholder lié dans AUTH à la version demandée)
```

`POT_VIEW` autorise globalement l'exercice de l'action de lecture d'un pot ; elle ne donne pas accès
à tous les pots. La projection `AUTH(potId, version)` porte l'autorisation métier de lire ce pot à
cette version. Le contrôle suit toujours cet ordre :

```text
POT_VIEW
    -> AUTH(potId, version)
    -> créateur OR shareholder lié
    -> READ_POT(potId, version)
```

Sans `POT_VIEW`, aucune projection n'est lue. Lorsque AUTH est absente ou en échec, READ_POT n'est
pas lue. Lorsque l'utilisateur n'est ni créateur ni shareholder à la version demandée, le résultat
est `Forbidden` et READ_POT n'est pas lue.

AUTH déclare un artifact `CREATOR` obligatoire et des artifacts `SHAREHOLDER_USER` optionnels. Leur
clé est le `UserId`. Le payload doit répéter ce même identifiant. Deux utilisateurs différents ne
peuvent pas désigner le même `ShareholderId` dans une projection AUTH.

READ_POT déclare un artifact `POT` obligatoire ainsi que zéro ou plusieurs artifacts `SHAREHOLDER`
et `EXPENSE`. Pour l'artifact POT, l'égalité suivante est volontaire :

```text
ArtifactKey == payload.potId == ProjectionKey.targetObjectId
```

Dans un shareholder, le champ `userId` est toujours présent : une chaîne UUID représente un
rattachement et JSON `null` son absence. Une omission n'est pas une seconde représentation valide.
Les montants et parts utilisent des fractions entières exactes dont le numérateur est positif ou
nul et le dénominateur strictement positif. Un tableau `shares` vide est structurellement valide,
mais viole l'invariant métier de `ReadPotInterpreter`.

`ProjectionDefinition` et `ProjectionValidator` garantissent la structure locale. Les interpreters
ne répètent pas ces contrôles : ils vérifient les égalités entre clés et payloads ainsi que les
références entre artifacts. Les schemas restent exprimés en `JsonValue`; l'adapter réel de
`JsonSchemaValidator` et le wiring du chemin Read appartiennent à un lot ultérieur.

## 8. Invariants de production et de lecture futurs

Une projection `P(X,V)` doit pouvoir être recalculée uniquement à partir des données exactes
chargées pour cette clé, de la définition de `P` et du projector associé. Ces données peuvent être
issues de l'état primaire versionné, d'autres projections chargées à leur clé exacte, ou des deux.
Elle ne dépend pas :

- de l'Event déclencheur ;
- de `P(X,V-1)` ;
- d'une projection choisie implicitement comme `CURRENT` ou substituée à la clé exacte demandée ;
- de l'ordre de traitement ;
- d'une génération ou sélection de pipeline ;
- d'un watermark, claim, slot, worker, retry ou statut de Task.

Une publication réussie persiste atomiquement la clé et tous ses artifacts, y compris une liste
vide valide. L'implémentation est atomique seule et n'emploie pas de transaction autonome de type
`REQUIRES_NEW` : elle peut rejoindre une transaction englobante compatible utilisant le même
transaction manager et la même ressource. Cette capacité ne présuppose aucune architecture future
de Consumption.

Deux traitements concurrents d'une même clé ne peuvent publier deux vérités différentes. Une
contrainte unique SQL sur `ProjectionKey` est l'autorité concurrente. La première publication gagne.
Toute republication retourne `ALREADY_EXISTS`, sans digest, comparaison structurelle ni détection de
divergence.

Les failures forment un historique append-only et n'ont aucune clé étrangère vers une projection.
`recordFailure` est idempotent sur `ProjectionFailureId` pour un contenu persisté identique. À la
frontière de persistence, `failedAt` est normalisé à la microseconde, précision explicite de la
colonne PostgreSQL `timestamp(6) with time zone`. Deux `Instant` différents uniquement sous cette
précision représentent donc le même contenu observable. Réutiliser le même UUID avec une autre clé
ou un timestamp différent après normalisation constitue une violation d'invariant interne.

`ProjectionReadPort.findProjection` et `hasFailure` exposent des faits bruts par deux appels
indépendants, sans promesse de snapshot commun. L'interprétation future reste : projection présente
donc `READY`, sinon failure présente donc `FAILED`, sinon `NOT_READY`.

## 9. Frontières d'architecture

Le package cœur `com.kartaguez.pocoma.domain.projection` dépend uniquement du JDK et de lui-même. Il
ne dépend notamment ni de `domain-pot`, ni de `domain-pipeline`, ni de Jackson, SQL/JPA, Spring,
SLF4J, Micrometer ou OpenTelemetry.

Les ports universels `ProjectionReadPort` et `ProjectionWritePort`, ainsi que
`ProjectionPublicationResult`, vivent dans `engine-projection-contracts`. Ce module représente une
frontière applicative Java pure : il dépend seulement du JDK et de `domain-projection`, et ne porte
aucune implémentation de stockage, Spring, JDBC/JPA, Jackson, PostgreSQL ou runtime.

Le use case générique de lecture exacte, ses trois résultats et son exception d'invariant vivent
dans `engine-projection-read`. Ce module dépend uniquement du JDK, de `domain-projection` et
d'`engine-projection-contracts`. Son service reste package-private tant qu'aucune composition
externe n'est introduite ; cette visibilité pourra être revue au moment d'un futur wiring.

Le use case métier versionné, ses six résultats, ses vues, ses définitions et ses interpreters
vivent dans `engine-pot-read`. Ce module dépend uniquement du JDK, de `domain-authorization`,
`domain-pot`, `domain-projection` et `engine-projection-read`. Il ne contient aucun framework,
adapter JSON Schema, accès au store, runtime ou dépendance legacy.

Le codec `JsonValue`/JSONB et l'adapter JDBC qui implémente les deux ports vivent dans
`infra-read-persistence`. Les clés primaires `BIGINT IDENTITY` de `projection_root` et
`projection_artifact` sont exclusivement relationnelles : elles ne quittent jamais
l'infrastructure et aucun type de domaine ou d'engine ne les représente.

Les mécanismes d'observation seront ajoutés extérieurement par décoration ou composition. Les
objets métier ne portent aucune metadata destinée aux logs, métriques ou traces.

## 10. Isolation transitoire du legacy

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

## 10. Hors scope des lots 2 et 3

Restent explicitement hors scope :

- moteur JSON Schema concret ;
- projectors `READ_POT`, `POT_BALANCES` ou autres projections concrètes ;
- registre runtime de définitions ;
- mapping Event vers tâches de projection ;
- politique de résolution `CURRENT` ;
- migration ou suppression des données et APIs legacy.
