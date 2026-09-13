# Lot 7.9.1 — Contrats de query versionnée

> **Dossier de conception, non implémenté.** Ce document décrit uniquement les contrats
> framework-free à introduire pour préparer le Query Kernel versionné. Il ne livre ni résolution
> `CURRENT`/`EXACT`, ni autorisation, ni adapter, ni migration des GET existants.

## 1. Contexte et sources

Le Lot 7.9 introduit la frontière de query versionnée commune aux futures lectures read-side. Son
premier sous-lot, 7.9.1, doit fixer les types et ports minimaux sur lesquels pourront ensuite
s'appuyer :

- 7.9.2 pour résoudre une `businessVersion` servable en `CURRENT` ou `EXACT(V)` ;
- 7.10 pour produire `AUTH(V)` et décider l'autorisation métier à la version servie ;
- 7.14.1 pour fournir les `pipelineVersion` sélectionnées comme `serving` ;
- 7.9.3, 7.11 et 7.13 pour composer puis migrer progressivement les GET.

Sources canoniques relues :

- `docs/architecture/read-side-target.md` ;
- `docs/architecture/read-side-current-state.md` ;
- `docs/plans/lot-7-read-side-implementation-plan.md` ;
- `docs/architecture/type-ownership.md` ;
- `docs/architecture/module-dependency-matrix.md` ;
- `docs/README.md` et `README.md` pour la hiérarchie documentaire.

Le code inspecté couvre les domaines `domain-projection`, `domain-pipeline` et `domain-pot`, les
ports de `engine-read-projection`, les contrats et tests actuels de `engine-query`, son POM Maven et
`HexagonalArchitectureTest`.

## 2. État actuel vérifié dans le code

**CONFIRMED BY CODE INSPECTION —** `engine-query` expose aujourd'hui des intentions spécifiques
(`GetPotQuery`, `GetExpenseQuery`, `GetPotBalancesQuery`, listes associées), dont certaines utilisent
un `OptionalLong version`. Il ne possède aucun contrat générique exprimant `CURRENT`, `EXACT(V)`,
une vue composée, une sélection de génération ou une réponse versionnée. Ces `OptionalLong`
existants ne sont pas adaptés dans ce lot.

**CONFIRMED BY CODE INSPECTION —** les types génériques suivants existent dans
`domain-projection` et doivent être réutilisés :

- `ProjectionType` : identité logique d'un artifact versionné ;
- `ProjectionGenerationIdentity` : `ProjectionType + PipelineDefinition + PotId` ;
- `ProjectionIdentity` : génération + `potVersion` strictement positive ;
- `ProjectionStatus` : `NOT_READY`, `READY` ou `FAILED` ;
- `ProjectionHead` : plus grande version projetée observée pour une génération ;
- `LatestKnownVersion` : plus grande business version effectivement matérialisée par son consumer.

**CONFIRMED BY CODE INSPECTION —** `PipelineDefinition`, `PipelineVersionDefinition` et
`VersionApplicability` appartiennent à `domain-pipeline`. `PipelineDefinition` identifie un processus
par `PipelineId + pipelineVersion`; `PipelineVersionDefinition` lui ajoute sa plage d'applicabilité.
`ProjectionType` et `PipelineId` ne portent donc pas la même sémantique.

**CONFIRMED BY CODE INSPECTION —** `LatestKnownVersionPersistencePort` est un port mutable limité à
`advanceToAtLeast`. `ProjectionMetadataPort` mélange locks, lectures, écritures, head et violations.
Aucun des deux n'est une frontière read-only appropriée pour le Query Kernel.

**CONFIRMED BY CODE INSPECTION —** le POM de `engine-query` dépend actuellement de
`domain-authorization`, `engine-core`, `domain-pot`, `domain-pot-policy` et
`domain-projection-balance`, mais pas directement de `domain-projection` ou `domain-pipeline`.

**CONFIRMED BY CODE INSPECTION —** les packages actuels séparent les intents
`engine.port.in.query.intent`, les résultats `engine.port.in.query.result`, les use cases et les ports
sortants `engine.port.out.query`. `HexagonalArchitectureTest` protège déjà ces packages contre les
dépendances vers processing, consumption, workers, Spring, JPA, Jackson et NATS.

**DOCUMENTED CURRENT STATE —** le Query Kernel, `AUTH(V)`, la résolution versionnée et la sélection
serving n'existent pas encore. 7.9.1 doit donc rester entièrement additif et ne doit pas présenter
ses contrats comme déjà actifs.

## 3. Objectif

Créer dans `engine-query` les contrats Java minimaux et framework-free qui permettent ultérieurement
de :

1. conserver l'intention `CURRENT` ou `EXACT(V)` d'une query ;
2. déclarer les composants logiques requis par une vue, avec ou sans composant d'autorisation ;
3. recevoir explicitement la `PipelineVersionDefinition` serving du producteur de chaque composant ;
4. adresser une génération puis un artifact exact avec les identités existantes ;
5. rechercher une version `READY` réelle sous une borne sans charger tout l'historique ;
6. lire la borne d'exposition `latestKnownVersion` ;
7. retourner une enveloppe réussie portant les versions demandée, servie et connue.

Le lot est terminé lorsque ces contrats, leurs invariants locaux, leurs tests unitaires et leurs
frontières architecturales sont définis, sans algorithme de résolution ni adapter.

## 4. Non-objectifs

7.9.1 n'implémente et ne planifie pas en détail :

- les algorithmes `CURRENT` et `EXACT` de 7.9.2 ;
- `AUTH(V)`, son artifact, son pipeline ou l'Authorization Kernel ;
- `TokenCapabilities` et les policies d'autorisation ;
- les résultats applicatifs `NOT_READY`/`PROJECTION_FAILED` et leur mapping HTTP ;
- controllers, adapters de persistence ou configuration Spring ;
- lifecycle `declared`/`active`/`serving`, sélection ou persistance du serving ;
- pagination `/pots`, limite de scan ou contrat list-specific ;
- généralisation de Balance, observabilité ou extinction du legacy ;
- migration des GET ou adaptation des `OptionalLong` actuels ;
- wrapper `BusinessVersion`, registry de composants ou DSL de vues.

## 5. Invariants structurants

- Events et Tasks peuvent être traités dans n'importe quel ordre ; les contrats n'imposent ni N-1,
  ni continuité, ni cursor fonctionnel.
- `ProjectionHead` décrit un maximum observé ; il ne prouve ni continuité ni readiness d'un artifact
  exact et ne participe pas à la résolution.
- `pipelineVersion` versionne un processus producteur ; `businessVersion` versionne l'état métier
  d'un Pot. Elles ne sont jamais interchangeables.
- `latestKnownVersion` ne bloque jamais scheduling, Task, projection ou matérialisation. Elle borne
  uniquement l'exposition réussie.
- `CURRENT` sert une seule `businessVersion` commune aux composants requis par la vue.
- `EXACT(V)` exige exactement V et ne fallback jamais.
- `AUTH(V)` est un artifact exact, immutable et autonome à la `businessVersion` V.
- Pour une vue protégée, la readiness d'AUTH participe à la sélection de `servedVersion`, mais le
  contenu des droits n'y participe jamais. Une fois la version choisie, `AUTH(servedVersion)`
  autorise ou refuse ; un refus est terminal et ne déclenche aucune recherche d'une version plus
  ancienne.
- Pour une vue non protégée, aucun composant ni contrôle métier AUTH n'est requis.
- Un pipeline est un processus générique et peut ne produire aucune projection ou produire plusieurs
  effets. Toutefois, une génération donnée d'un `ProjectionType` possède exactement un pipeline
  producteur.
- Les contrats restent sans Spring, JPA, HTTP, Jackson, persistence concrète ou processing engine.

## 6. Architecture proposée

Le Query Kernel reçoit quatre données structurelles indépendantes :

```text
QueryVersionIntent
+ QueryViewDefinition
+ QueryPipelineSelection fournie par le serving provider
+ PotId
```

Pour chaque composant requis, le futur 7.9.2 pourra construire :

```text
ProjectionType requis
+ PipelineDefinition du producteur sélectionné
+ PotId
→ ProjectionGenerationIdentity

+ businessVersion candidate
→ ProjectionIdentity
```

Il interrogera ensuite les ports read-only de readiness et de latest-known. 7.9.1 expose uniquement
ces données et opérations ; il ne compose pas encore la recherche multi-composants.

### Vues protégées et non protégées

`QueryViewDefinition` représente deux catégories :

```text
protected view   = authorizationComponent + businessComponents
unprotected view = businessComponents seulement
```

Pour 7.9.2, une vue protégée fera participer AUTH et tous les composants métier à la recherche
`CURRENT` ou à la vérification `EXACT(V)`. Une vue non protégée n'y fera participer que ses composants
métier. `latestKnownVersion` reste la borne d'exposition dans les deux cas.

Le contrat accepte des composants métier vides : vue protégée AUTH-only et vue non protégée sans
composant métier. La sémantique précise d'une résolution sans composant READY appartient à 7.9.2 ;
7.9.1 n'ajoute aucune règle de sélection implicite.

### Sélection du pipeline producteur

`QueryPipelineSelection` porte :

```text
ProjectionType
→ PipelineVersionDefinition du pipeline producteur sélectionné pour serving
```

Ce n'est pas une association libre entre deux identités. Le fournisseur serving de 7.14.1 garantit
que chaque entrée désigne le producteur cohérent de la génération demandée. Le Query Kernel ne
redécouvre pas cette relation, ne la vérifie pas contre un registry et ne sélectionne jamais la plus
grande `pipelineVersion`.

## 7. Types et interfaces exacts

### 7.1 `QueryVersionIntent`

Fichier futur :
`app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryVersionIntent.java`

```java
public sealed interface QueryVersionIntent {

    record Current() implements QueryVersionIntent {}

    record Exact(long businessVersion) implements QueryVersionIntent {}

    static Current current();

    static Exact exact(long businessVersion);
}
```

Le constructeur compact de `Exact` rejette `businessVersion <= 0` par
`IllegalArgumentException`. Les factories construisent directement les variantes et n'ajoutent aucun
singleton mutable, parsing HTTP ou mapping JSON. Les records fournissent égalité, hash code et
immutabilité par valeur. Aucun wrapper `BusinessVersion` n'est créé.

### 7.2 `VersionedQueryResponse<T>`

Fichier futur :
`app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/result/VersionedQueryResponse.java`

```java
public record VersionedQueryResponse<T>(
        QueryVersionIntent requestedVersion,
        long servedVersion,
        long latestKnownVersion,
        Instant generatedAt,
        T data) {
}
```

Le constructeur compact valide :

- `requestedVersion`, `generatedAt` et `data` non nuls ;
- `servedVersion > 0` et `latestKnownVersion > 0` ;
- `servedVersion <= latestKnownVersion` ;
- pour `Exact(V)`, `servedVersion == V`.

La relation entre les versions est un invariant de toute réponse réussie et appartient donc au type.
La présence de latest-known est également garantie par les champs primitifs et leur validation. Le
futur resolver reste responsable de ne construire la réponse qu'après résolution réussie.

`generatedAt` est l'instant de génération de l'enveloppe fourni par l'appelant ; le record ne lit pas
l'horloge. Ce n'est ni le timestamp de l'Event, ni celui de l'artifact ou du head. Aucun champ `stale`,
statut HTTP, résultat d'autorisation ou timestamp de projection n'est ajouté.

### 7.3 `QueryViewDefinition`

Fichier futur :
`app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryViewDefinition.java`

```java
public record QueryViewDefinition(
        Optional<ProjectionType> authorizationComponent,
        Set<ProjectionType> businessComponents) {

    public static QueryViewDefinition protectedView(
            ProjectionType authorizationComponent,
            Set<ProjectionType> businessComponents);

    public static QueryViewDefinition unprotectedView(
            Set<ProjectionType> businessComponents);

    public Set<ProjectionType> requiredComponents();
}
```

Le constructeur compact :

- rejette un `Optional` ou un ensemble nul ;
- rejette tout élément nul ;
- copie `businessComponents` avec une collection non ordonnée et immuable ;
- rejette une vue protégée dont le composant d'autorisation apparaît aussi dans
  `businessComponents` ;
- autorise un ensemble métier vide.

`protectedView` exige un composant d'autorisation non nul et l'enveloppe dans `Optional.of`.
`unprotectedView` utilise `Optional.empty`. Les factories constituent l'API d'appel lisible ; le
constructeur canonique conserve les mêmes validations si le record est construit directement.

`requiredComponents()` retourne un nouvel ensemble immuable égal à l'union sans doublon du composant
d'autorisation éventuel et des composants métier. Il ne garantit aucun ordre d'itération et ne met
pas en cache d'état mutable.

Le type ne sait pas qu'une valeur textuelle particulière de `ProjectionType` signifie AUTH : il
exprime un rôle structurel. Il n'introduit ni policy, ni registry, ni preuve d'autorisation.

### 7.4 `QueryPipelineSelection`

Fichier futur :
`app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryPipelineSelection.java`

```java
public record QueryPipelineSelection(
        Map<ProjectionType, PipelineVersionDefinition> pipelinesByComponent) {

    public PipelineVersionDefinition requireFor(ProjectionType component);
}
```

Le constructeur compact exige une map non nulle, sans clé ou valeur nulle, puis en prend une copie
immuable. Une map vide est valide : elle signifie uniquement qu'aucun composant projeté n'est requis
par cette query. Elle n'introduit aucun cas spécial de serving ou de résolution. `requireFor` rejette
un composant nul et lève explicitement une `IllegalArgumentException` si la sélection ne le contient
pas.

La map conserve exactement la `PipelineVersionDefinition` fournie. Elle ne calcule aucun maximum,
n'inspecte pas l'applicabilité, ne choisit pas serving et ne prouve pas que le pipeline produit le
composant. Cette cohérence est une précondition garantie par le fournisseur serving de 7.14.1.

`PipelineVersionDefinition.identity()` fournit le `PipelineDefinition` requis pour construire une
`ProjectionGenerationIdentity`. Aucun alias de `ProjectionType`, wrapper de pipeline,
`PipelineFamily` ou registry producteur n'est ajouté.

### 7.5 Identités existantes

Aucun nouveau type d'identité n'est créé. Le futur resolver utilise les types existants ainsi :

```text
ProjectionGenerationIdentity
  projectionType = composant logique demandé
  pipeline       = selection.requireFor(component).identity()
  potId          = Pot concerné

ProjectionIdentity
  generation = identité ci-dessus
  potVersion = businessVersion candidate exacte
```

`ProjectionGenerationIdentity` est naturel pour rechercher une readiness dans une génération.
`ProjectionIdentity` est naturel pour lire le statut d'un artifact exact. Ils ne remontent pas dans
`QueryVersionIntent`, `QueryViewDefinition`, `QueryPipelineSelection` ou
`VersionedQueryResponse`, qui n'ont pas besoin de tous leurs axes.

### 7.6 `ProjectionReadinessQueryPort`

Fichier futur :
`app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/out/query/ProjectionReadinessQueryPort.java`

```java
public interface ProjectionReadinessQueryPort {

    OptionalLong findHighestReadyBusinessVersionAtOrBelow(
            ProjectionGenerationIdentity generation,
            long upperBoundInclusive);

    ProjectionStatus statusAt(ProjectionIdentity identity);
}
```

Contrat de `findHighestReadyBusinessVersionAtOrBelow` :

- `generation` non nulle et `upperBoundInclusive > 0` ;
- renvoyer la plus grande `businessVersion` réellement `READY` dans cette génération et inférieure
  ou égale à la borne ;
- renvoyer vide si elle n'existe pas ;
- ne synthétiser aucune version et ne supposer ni continuité ni N-1 ;
- ne pas déduire la réponse de `ProjectionHead` ;
- ne pas charger ni exposer l'ensemble complet des versions READY.

Exemple normatif :

```text
READY = {15, 13, 8}

bound 15 -> 15
bound 14 -> 13
bound 12 -> 8
bound 7  -> empty
```

`statusAt` exige une `ProjectionIdentity` non nulle et retourne le statut dérivé exact de cet
artifact. `V14 = NOT_READY` reste normal entre `V15 = READY` et `V13 = READY`.

Le futur 7.9.2 pourra demander une première candidate à une génération, vérifier les autres
composants exactement avec `statusAt`, puis rechercher une candidate strictement sous la précédente
si nécessaire. La manière de calculer cette nouvelle borne et d'orchestrer les composants appartient
à 7.9.2 ; le port ne prescrit ni `V-1` comme existence, ni intersection en mémoire.

### 7.7 `LatestKnownVersionQueryPort`

Fichier futur :
`app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/out/query/LatestKnownVersionQueryPort.java`

```java
public interface LatestKnownVersionQueryPort {

    Optional<LatestKnownVersion> findByPotId(PotId potId);
}
```

Le port exige un `PotId` non nul. Une absence signifie qu'aucune borne d'exposition n'est connue ; le
futur resolver produira alors `NOT_READY`. Le port ne modifie aucune valeur et ne doit ni étendre ni
réutiliser `LatestKnownVersionPersistencePort`, qui appartient à l'avance monotone du consumer.

## 8. Placement modules/packages

| Type | Module | Package | Responsabilité | Dépendances autorisées | Futurs consommateurs |
|---|---|---|---|---|---|
| `QueryVersionIntent` | `engine-query` | `com.kartaguez.pocoma.engine.port.in.query.version` | Intention CURRENT ou EXACT | JDK | 7.9.2, futurs GET |
| `QueryViewDefinition` | `engine-query` | `com.kartaguez.pocoma.engine.port.in.query.version` | Composants logiques d'une vue protégée ou non | JDK, `domain-projection` | 7.9.2, 7.9.3 |
| `QueryPipelineSelection` | `engine-query` | `com.kartaguez.pocoma.engine.port.in.query.version` | Producteur serving fourni pour chaque composant | JDK, `domain-projection`, `domain-pipeline` | 7.14.1, 7.9.2 |
| `VersionedQueryResponse<T>` | `engine-query` | `com.kartaguez.pocoma.engine.port.in.query.result` | Enveloppe d'une query versionnée réussie | JDK, `QueryVersionIntent` | 7.9.3, futurs GET |
| `ProjectionReadinessQueryPort` | `engine-query` | `com.kartaguez.pocoma.engine.port.out.query` | Readiness descendante et statut exact | JDK, `domain-projection` | 7.9.2, futur adapter read-store |
| `LatestKnownVersionQueryPort` | `engine-query` | `com.kartaguez.pocoma.engine.port.out.query` | Lecture de la borne d'exposition d'un Pot | JDK, `domain-pot`, `domain-projection` | 7.9.2, futur adapter read-store |

Ajouter ultérieurement au POM de `engine-query` des dépendances directes vers
`pocoma-domain-projection` et `pocoma-domain-pipeline`. `domain-pot` est déjà direct. Même si
`domain-projection` dépend lui-même de pipeline, l'import explicite de `PipelineVersionDefinition`
justifie une dépendance Maven directe et lisible.

Ces dépendances respectent la matrice : un engine fonctionnel peut dépendre des domaines nécessaires.
Le lot n'ajoute aucune dépendance de `engine-query` vers `engine-read-projection`, infrastructure,
runtime, supra ou processing.

## 9. Séquence d'implémentation

### A. Dépendances de compilation

- Modifier uniquement le POM `engine-query` pour déclarer `domain-projection` et `domain-pipeline`.
- Vérifier l'absence de cycle Maven et la compilation isolée du module.
- Critère de fin : les domaines sont directement disponibles sans dépendance extérieure ajoutée.

### B. Intention versionnée

- Créer `QueryVersionIntent` et son test unitaire.
- Implémenter factories, validation de `Exact` et value semantics naturelles.
- Critère de fin : CURRENT et toute version exacte positive sont représentables, les autres refusées.

### C. Définition de vue

- Créer `QueryViewDefinition` avec factories protégée/non protégée.
- Implémenter validations, copies immuables et union `requiredComponents()`.
- Critère de fin : les quatre formes protected/unprotected, avec ou sans composant métier, sont
  représentables sans registry ni ordre fonctionnel.

### D. Sélection explicite des producteurs

- Créer `QueryPipelineSelection` avec copie immuable et `requireFor`.
- Ne consulter aucun catalogue et ne coder aucun choix serving.
- Critère de fin : une sélection fournie, y compris vide, est conservée exactement et une entrée
  absente demandée par `requireFor` échoue explicitement.

### E. Ports read-only

- Créer `ProjectionReadinessQueryPort` puis `LatestKnownVersionQueryPort`.
- Documenter leurs préconditions et leur sémantique d'absence.
- Critère de fin : 7.9.2 peut être codé ultérieurement sans dépendre d'un head, de tout l'historique
  READY, d'un port mutable ou d'un adapter concret.

### F. Enveloppe de réponse

- Créer `VersionedQueryResponse<T>` et ses validations locales.
- Ne lire aucune horloge dans le type.
- Critère de fin : toute instance valide décrit une réponse réussie bornée par latest-known.

### G. Tests de contrats et architecture

- Compléter les tests unitaires de chaque type et les doubles contractuels des ports.
- Renforcer `HexagonalArchitectureTest` seulement si les règles existantes ne couvrent pas les
  nouveaux packages ou les nouvelles dépendances interdites.
- Lancer les tests ciblés puis la suite Maven proportionnée aux changements.
- Critère de fin : tous les scénarios de la section 10 et les frontières de la section 11 passent.

### H. Documentation après implémentation

- Mettre à jour les documents énumérés en section 12 et le statut du plan directeur uniquement une
  fois le code livré.
- Critère de fin : documentation cible, état courant et statut 7.9.1 décrivent le code réellement
  présent.

## 10. Tests

### `QueryVersionIntentTest`

- `current()` produit une variante `Current` valide ;
- `exact(1)` et une valeur positive arbitraire conservent exactement la business version ;
- zéro et une valeur négative lèvent `IllegalArgumentException` ;
- deux `Current` et deux `Exact(V)` identiques ont les mêmes value semantics ;
- `Exact(V1)` diffère de `Exact(V2)`.

### `VersionedQueryResponseTest`

- chaque référence obligatoire n'accepte pas `null` ;
- versions servie et latest-known doivent être strictement positives ;
- `servedVersion > latestKnownVersion` est refusé ;
- `Exact(V)` exige `servedVersion == V` ;
- `Current` peut servir une version inférieure à latest-known ;
- l'intention demandée, `generatedAt` et les données génériques sont conservés exactement ;
- une réflexion ciblée sur les record components confirme l'absence de champ `stale` si ce contrôle
  est jugé utile pour verrouiller le contrat public.

### `QueryViewDefinitionTest`

```text
protected AUTH + READ_POT -> required = {AUTH, READ_POT}
protected AUTH-only       -> required = {AUTH}
unprotected READ_POT      -> required = {READ_POT}
unprotected empty         -> required = {}
```

Ajouter les cas suivants :

- `Optional`, ensemble, composant d'autorisation et éléments nuls refusés selon leur factory ;
- AUTH dupliqué comme composant métier refusé pour une vue protégée ;
- ordre d'insertion différent donnant la même définition fonctionnelle ;
- mutation de la collection source sans effet après construction ;
- ensembles exposés non modifiables ;
- aucun registry ou DSL nécessaire à la construction.

### `QueryPipelineSelectionTest`

- association explicite retrouvée par `ProjectionType` ;
- `PipelineVersionDefinition` exacte conservée, y compris son applicabilité ;
- composant absent détecté explicitement ;
- map, clé ou valeur nulle refusée ;
- map vide acceptée ;
- mutation de la map source sans effet et map exposée immuable ;
- aucun comportement de `MAX(pipelineVersion)` ou de sélection serving.

Vérifier aussi le scénario cohérent, sans ajouter de resolver :

```text
QueryViewDefinition.unprotectedView({}) -> requiredComponents = {}
QueryPipelineSelection({})              -> valide
```

Ne pas écrire de test affirmant que ce type prouve la relation producteur/projection : aucun catalogue
ne lui fournit cette information et la garantie appartient à 7.14.1.

### Contrat de readiness

Utiliser un fake test-only du port, isolé du code de production, avec une génération dont les READY
sont `{15, 13, 8}` :

```text
bound 15 -> 15
bound 14 -> 13
bound 12 -> 8
bound 7  -> empty
statusAt(V14) -> NOT_READY
```

Vérifier aussi `statusAt(V15) = READY`, `statusAt(V13) = READY`, les bornes invalides, les identités
nulles et l'isolation entre deux `ProjectionGenerationIdentity`. L'ordre d'insertion du fake ne doit
pas influencer le résultat.

Ce test documente le contrat du port ; il n'ajoute ni adapter, ni intersection multi-composants, ni
algorithme 7.9.2 au code de production.

### `LatestKnownVersionQueryPort`

Un stub suffit pour vérifier que le contrat distingue une valeur existante de l'absence. Ne pas
implémenter le futur comportement `NOT_READY`, qui appartient au resolver.

## 11. Règles d'architecture

Conserver et, si nécessaire, étendre les assertions ArchUnit pour garantir que les nouveaux packages
`engine.port.in.query.version`, `engine.port.in.query.result` et `engine.port.out.query` ne dépendent
pas de :

- `engine-read-projection`, processing, task execution/materialization ou consumption ;
- infrastructure, supra, runtime ou orchestrateurs ;
- Spring, Jakarta Persistence, Jackson, NATS ou classes HTTP.

Les seules nouvelles dépendances de domaine admissibles sont celles nécessaires vers
`domain-projection`, `domain-pipeline` et le `PotId` déjà fourni par `domain-pot`. Aucun type de
`engine-query` n'est déplacé dans un domaine : ces contrats appartiennent au use case de lecture.

Un test d'architecture ne doit pas figer les noms de valeurs `READ_POT`, `AUTH` ou `BALANCE`, ni
transformer `ProjectionType` en enum fermé. Il protège les directions de dépendances, pas un registry
de composants.

## 12. Documentation à mettre à jour après implémentation

Ces modifications sont hors de la présente création de plan et appartiennent à la livraison future
de 7.9.1 :

- `docs/architecture/read-side-current-state.md` : déclarer les contrats réellement présents, tout en
  maintenant resolver et adapters comme absents ;
- `docs/architecture/type-ownership.md` : attribuer les contrats du Query Kernel à `engine-query` ;
- `docs/architecture/module-dependency-matrix.md` : ajouter les dépendances directes et la nouvelle
  responsabilité contractuelle de `engine-query` ;
- `docs/plans/lot-7-read-side-implementation-plan.md` : marquer 7.9.1 `DONE` uniquement après tests ;
- `docs/README.md` : référencer ce dossier si la convention d'indexation des plans détaillés
  l'exige alors encore.

`read-side-target.md` ne doit changer que si l'implémentation révèle une divergence de cible. Les
contrats prévus ici appliquent déjà ses invariants.

## 13. Risques et pièges à éviter

- Ne pas confondre `ProjectionType`, identité de l'artifact, avec `PipelineId`, identité du processus.
- Ne pas accepter une association arbitraire producteur/projection : la sélection est une donnée
  serving cohérente fournie par 7.14.1, sans validation locale possible dans 7.9.1.
- Ne pas introduire un registry global pour compenser l'absence actuelle du serving provider.
- Ne pas utiliser `ProjectionHead` comme preuve de readiness ou de continuité.
- Ne pas exposer `Set<Long>` de tout l'historique READY et ne pas coder d'intersection en mémoire.
- Ne pas supposer qu'après V15 la candidate précédente existe à V14 ; les trous sont normaux.
- Ne pas enrichir le port mutable latest-known avec les besoins de query.
- Ne pas rendre AUTH obligatoire pour toute query générique : il est obligatoire uniquement pour une
  vue protégée.
- Ne pas laisser le contenu des droits influencer une recherche de version ou provoquer un fallback.
- Ne pas injecter `PotId` ou les identités composites dans les contrats qui n'en ont pas besoin.
- Ne pas faire de `VersionedQueryResponse` un DTO HTTP ou une représentation de persistence.
- Ne pas forcer `/pots` dans `VersionedQueryResponse<List<T>>` : des Pots distincts peuvent avoir des
  `servedVersion` différentes. Le contrat des listes convergentes appartient à 7.11.
- Ne pas migrer les intents ou services legacy pendant ce lot additif.

## 14. Critères d'acceptation

1. `QueryVersionIntent` représente sans framework `CURRENT` et `EXACT(V > 0)`.
2. `VersionedQueryResponse<T>` garantit localement une réponse réussie avec
   `servedVersion <= latestKnownVersion`, et EXACT conserve exactement V.
3. `QueryViewDefinition` distingue vues protégées et non protégées ; AUTH n'est obligatoire que pour
   une vue protégée.
4. Une vue protégée AUTH-only, une vue non protégée sans composant métier et les vues avec plusieurs
   composants sont valides et immuables.
5. Pour une vue protégée, AUTH ne peut pas être dupliqué parmi les composants métier.
6. `ProjectionType` est réutilisé comme identité logique ; aucun alias, enum fermé, registry ou DSL
   parallèle n'est créé.
7. `ProjectionType` et `PipelineId` restent distincts, tandis qu'une génération de projection possède
   exactement un pipeline producteur.
8. `QueryPipelineSelection` reçoit explicitement les producteurs serving cohérents fournis par
   7.14.1, peut être vide lorsqu'aucun composant n'est requis par la vue et ne sélectionne, ne
   maximise ni ne redécouvre aucune pipelineVersion.
9. Les identités composites existantes sont utilisées seulement pour readiness et statut exact.
10. Le Query Kernel peut rechercher la plus haute version READY sous une borne sans demander tout
    l'historique et sans supposer de continuité.
11. `statusAt` permet d'observer `READY`, `NOT_READY` ou `FAILED` à une business version exacte,
    indépendamment des versions voisines.
12. Le port latest-known est read-only et distinct du port d'avance monotone.
13. Aucun algorithme `CURRENT`/`EXACT`, contrôle AUTH, adapter, controller ou lifecycle serving n'est
    implémenté.
14. Les nouveaux contrats ne dépendent que du JDK et des domaines autorisés.
15. Les tests unitaires et d'architecture ciblés passent ; les GET existants restent inchangés.

## 15. Fichiers attendus

### Créés pendant l'implémentation future

```text
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryVersionIntent.java
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryViewDefinition.java
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryPipelineSelection.java
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/result/VersionedQueryResponse.java
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/out/query/ProjectionReadinessQueryPort.java
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/out/query/LatestKnownVersionQueryPort.java
```

Tests unitaires correspondants sous :

```text
app/engine-query/src/test/java/com/kartaguez/pocoma/engine/port/in/query/version/
app/engine-query/src/test/java/com/kartaguez/pocoma/engine/port/in/query/result/
app/engine-query/src/test/java/com/kartaguez/pocoma/engine/port/out/query/
```

### Modifiés pendant l'implémentation future

```text
app/engine-query/pom.xml
app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/HexagonalArchitectureTest.java
docs/architecture/read-side-current-state.md
docs/architecture/type-ownership.md
docs/architecture/module-dependency-matrix.md
docs/plans/lot-7-read-side-implementation-plan.md
docs/README.md (seulement si l'indexation des plans détaillés le requiert)
```

La présente passe crée uniquement ce document de plan.

## 16. OPEN QUESTIONS / BLOCKERS

NONE
