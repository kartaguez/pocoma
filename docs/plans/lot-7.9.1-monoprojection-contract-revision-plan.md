# Lot 7.9.1 — Plan de révision des contrats monoprojection

Statut : **plan de révision à exécuter — aucune implémentation incluse dans ce document**.

Ce plan remplace le cadrage multi-projection des contrats préparatoires du Query Kernel. Il décrit la
révision du code, des tests et de la documentation nécessaire avant de reconcevoir le Lot 7.9.2. Il
ne modifie pas encore le plan 7.9.2 et n'introduit aucun resolver.

## 1. Motivation du changement

Le modèle livré par le Lot 7.9.1 permet à une query versionnée de décrire zéro, une ou plusieurs
projections et d'inclure AUTH dans une résolution de version commune. Le cadrage cible est désormais :

> Une query versionnée porte exactement une projection métier. Jamais zéro. Jamais plusieurs.

Une query possède :

```text
ProjectionType métier
+ PipelineVersionDefinition serving correspondante
+ QueryVersionIntent CURRENT | EXACT(V)
```

AUTH reste une projection versionnée, mais sort entièrement du Query Version Resolver :

```text
projection métier
→ résolution de servedVersion
→ si query protégée : AUTH demandé en EXACT(servedVersion)
→ autorisation à cette même businessVersion
→ aucun fallback
```

La révision doit rendre les cas abandonnés impossibles par construction plutôt que de maintenir une
généralité sans consommateur.

## 2. Ancien modèle vérifié dans le repository

Le code actuel contient :

```text
QueryViewDefinition
├── Optional<ProjectionType> authorizationComponent
└── Set<ProjectionType> businessComponents

QueryPipelineSelection
└── Map<ProjectionType, PipelineVersionDefinition>

ProjectionReadinessQueryPort
├── findHighestReadyBusinessVersionAtOrBelow(...)
└── statusAt(...)
```

Constats factuels :

- `QueryViewDefinition` permet zéro ou plusieurs projections et mélange AUTH avec les projections
  métier à résoudre ;
- `QueryPipelineSelection` permet une sélection vide ou multi-composants ;
- `findHighestReadyBusinessVersionAtOrBelow` ignore les versions `FAILED` ;
- `ProjectionStatus` possède exactement `NOT_READY`, `READY` et `FAILED` ;
- aucun resolver 7.9.2 n'existe ;
- aucun service ni adapter de production ne consomme `QueryViewDefinition`,
  `QueryPipelineSelection` ou `ProjectionReadinessQueryPort` ;
- leurs seuls call sites Java sont leurs tests unitaires/contractuels.

Une rupture propre est donc préférable à une compatibilité transitoire.

## 3. Nouveau modèle cible

### 3.1 Query monoprojection

Le contrat préparatoire devient :

```text
QueryVersionIntent
+ QueryProjectionSelection
+ PotId
+ LatestKnownVersionQueryPort
+ ProjectionReadinessQueryPort
```

avec :

```text
QueryProjectionSelection
├── ProjectionType métier
└── PipelineVersionDefinition serving de son producteur
```

Il n'existe plus de vue vide, de vue multi-composants, de vue protégée/non protégée dans le resolver,
d'intersection de readiness ni de composant d'ancrage.

### 3.2 Autorité de la génération serving

Pour une `ProjectionType` donnée, le serving provider sélectionne à un instant donné une unique
`PipelineVersionDefinition` faisant autorité pour les lectures de cette projection.

Cette sélection vaut pour toutes les lectures normales couvertes par cette génération, y compris les
lectures historiques. Elle ne désigne pas uniquement le producteur des nouvelles businessVersions.

```text
ProjectionType
+ PipelineDefinition serving
+ PotId
→ ProjectionGenerationIdentity serving
```

Le Query Kernel ne recherche que dans cette génération. Il ne consulte aucune ancienne génération
pour obtenir un artifact plus récent ou plus disponible.

> Il n'existe aucun fallback inter-pipelineVersion lors d'une query.

Une ancienne pipelineVersion peut conserver physiquement des artifacts historiques ; ils ne sont
plus candidats aux lectures normales lorsqu'une autre génération est serving.

### 3.3 CURRENT

CURRENT sélectionne :

> la plus haute businessVersion terminale inférieure ou égale à `latestKnownVersion` dans la
> génération serving.

```text
terminal = READY | FAILED
```

Le statut de cette version produit directement :

```text
READY  → RESOLVED(V)
FAILED → PROJECTION_FAILED(V)
```

Un `FAILED` récent n'est jamais masqué par un ancien `READY` :

```text
latestKnown = 15

V15 NOT_READY
V14 FAILED
V13 READY

CURRENT → PROJECTION_FAILED(14)
```

Si latest-known est absent ou si aucune version terminale n'existe sous la borne, le résultat futur
est `NOT_READY`.

### 3.4 EXACT(V)

La résolution future respecte :

```text
latestKnown absent             → NOT_READY
V > latestKnown                → NOT_READY
pipeline serving non applicable à V → NOT_APPLICABLE
status(V) = READY              → RESOLVED(V)
status(V) = FAILED             → PROJECTION_FAILED(V)
status(V) = NOT_READY          → NOT_READY
```

EXACT ne fallback jamais vers une autre businessVersion ou une autre pipelineVersion.

### 3.5 latestKnownVersion

`latestKnownVersion` reste uniquement une borne d'exposition :

- elle ne bloque ni scheduling ni production ;
- aucune version supérieure ne peut être servie ;
- son absence devient simplement `NOT_READY` ;
- elle ne prouve ni continuité ni matérialisation d'un artifact.

## 4. Exemple canonique de serving sans fallback

```text
ProjectionType = READ_POT
latestKnown = 100

serving :
pipeline P / v3

P/v3 :
V100 NOT_READY
V99  NOT_READY
V98  READY

ancienne génération P/v2 :
V100 READY
V99  READY
V98  READY
```

Résolution attendue :

```text
CURRENT(READ_POT)
→ génération P/v3
→ V98 READY
→ RESOLVED(98)
```

La résolution ne doit jamais produire `P/v2 / V100`. La génération P/v2 n'est pas interrogée.

Second cas :

```text
serving P/v3 : aucune version terminale <= latestKnown
ancienne P/v2 : plusieurs versions READY

CURRENT → NOT_READY
```

L'indisponibilité de la génération serving n'autorise aucun fallback vers P/v2.

## 5. Inventaire et décision par contrat

| Contrat | État actuel | Décision de révision |
| --- | --- | --- |
| `QueryVersionIntent` | `CURRENT` ou `EXACT(V > 0)` | Conserver sans modification |
| `VersionedQueryResponse<T>` | Enveloppe réussie bornée par latest-known | Conserver sans modification |
| `LatestKnownVersionQueryPort` | Lecture read-only par `PotId` | Conserver sans modification |
| `QueryViewDefinition` | AUTH optionnel et ensemble métier 0..N | Supprimer |
| `QueryPipelineSelection` | Map 0..N projection→pipeline | Supprimer et remplacer |
| `ProjectionReadinessQueryPort.statusAt` | Statut exact d'une identité | Conserver |
| `findHighestReadyBusinessVersionAtOrBelow` | Recherche uniquement READY | Remplacer par une recherche terminale |
| `ProjectionType` | Identité logique d'artifact | Conserver |
| `ProjectionGenerationIdentity` | Projection + producteur + Pot | Conserver |
| `ProjectionIdentity` | Génération + businessVersion | Conserver |
| `ProjectionStatus` | `NOT_READY`, `READY`, `FAILED` | Conserver |
| Types pipeline/applicabilité | Identité serving et plage | Conserver |

## 6. Contrats conservés

### 6.1 `QueryVersionIntent`

Aucune modification. Ses validations et value semantics ne dépendent pas de la cardinalité des
projections.

### 6.2 `VersionedQueryResponse<T>`

Aucune modification. Les invariants restent valides :

- `latestKnownVersion` est présent sur toute réponse réussie ;
- `servedVersion <= latestKnownVersion` ;
- EXACT impose `servedVersion == requestedVersion` ;
- aucun champ `stale`.

La simplification des résultats négatifs n'affecte pas cette enveloppe de succès.

### 6.3 `LatestKnownVersionQueryPort`

Aucune modification. `Optional.empty()` continue de représenter l'absence de borne ; le futur
resolver la traduira en `NOT_READY`.

### 6.4 Identités, pipeline et statuts

Conserver sans modification :

- `ProjectionGenerationIdentity` ;
- `ProjectionIdentity` ;
- `ProjectionStatus` ;
- `PipelineDefinition` ;
- `PipelineVersionDefinition` ;
- `VersionApplicability`.

`pipelineVersion` et `businessVersion` restent deux axes distincts.

### 6.5 `statusAt(ProjectionIdentity)`

Conserver dans `ProjectionReadinessQueryPort`. Cette lecture exacte est nécessaire au futur EXACT(V).

## 7. Contrats modifiés ou ajoutés

### 7.1 `QueryProjectionSelection`

Remplacer les deux contrats multi-composants par :

```java
public record QueryProjectionSelection(
    ProjectionType projectionType,
    PipelineVersionDefinition servingPipeline) {

    public QueryProjectionSelection {
        Objects.requireNonNull(projectionType);
        Objects.requireNonNull(servingPipeline);
    }
}
```

Placement :

```text
module  : engine-query
package : com.kartaguez.pocoma.engine.port.in.query.version
fichier : QueryProjectionSelection.java
```

Responsabilité exacte :

> Identifier l'unique projection métier demandée par la query et la génération actuellement serving
> qui fait autorité pour cette query, y compris lorsqu'une businessVersion historique est résolue.

Le type contient exactement une projection et exactement une pipelineVersion serving. Il ne contient
aucune collection, AUTH, factory protected/unprotected, lookup, `MAX` ou registry.

Le futur fournisseur serving 7.14.1 garantit que `servingPipeline` est bien le producteur de
`projectionType`. Le Query Kernel consomme le couple sans le redécouvrir et sans choisir une autre
pipelineVersion. `ProjectionType` et `PipelineId` restent distincts.

### 7.2 `TerminalProjectionState`

Ajouter un value object framework-free :

```java
public record TerminalProjectionState(
    long businessVersion,
    ProjectionStatus status) {

    public TerminalProjectionState {
        if (businessVersion < 1) {
            throw new IllegalArgumentException(...);
        }
        Objects.requireNonNull(status);
        if (status != ProjectionStatus.READY
                && status != ProjectionStatus.FAILED) {
            throw new IllegalArgumentException(...);
        }
    }
}
```

Placement :

```text
module  : engine-query
package : com.kartaguez.pocoma.engine.port.out.query
fichier : TerminalProjectionState.java
```

Il représente une businessVersion réellement terminale et son statut. `NOT_READY` est interdit par
construction.

### 7.3 `ProjectionReadinessQueryPort`

Remplacer :

```java
OptionalLong findHighestReadyBusinessVersionAtOrBelow(
    ProjectionGenerationIdentity generation,
    long upperBoundInclusive);
```

par :

```java
Optional<TerminalProjectionState> findHighestTerminalAtOrBelow(
    ProjectionGenerationIdentity generation,
    long upperBoundInclusive);
```

Sémantique :

- la génération est exactement celle construite depuis `QueryProjectionSelection.servingPipeline()` ;
- la recherche ne porte jamais sur toutes les pipelineVersions d'une projection ;
- le résultat est la plus haute businessVersion `READY` ou `FAILED` sous la borne inclusive ;
- `NOT_READY` n'est pas terminal ;
- vide signifie qu'aucune version terminale de la génération serving n'existe sous la borne ;
- trous et persistence hors ordre sont normaux ;
- aucune continuité, aucun scan numérique et aucun `ProjectionHead` ;
- les artifacts d'autres Pot, ProjectionType, PipelineId ou pipelineVersion sont ignorés.

Retourner la version et le statut ensemble évite un second lookup et permet au futur CURRENT de
produire directement `RESOLVED` ou `PROJECTION_FAILED`.

L'applicabilité ne provoque aucune recherche multi-pipeline :

- EXACT vérifie `servingPipeline.appliesTo(V)` avant `statusAt` ;
- CURRENT recherche les artifacts réellement attachés à la génération serving ;
- un artifact hors applicabilité dans cette génération constitue une violation de production, pas
  un cas que le Query Kernel compense par fallback.

## 8. Contrats supprimés

### 8.1 `QueryViewDefinition`

Supprimer le fichier et son test parce qu'il :

- représente zéro, une ou plusieurs projections ;
- contient AUTH dans le contrat de résolution ;
- expose `requiredComponents()` devenu inutile ;
- encode protected/unprotected, AUTH-only et vue vide, désormais hors modèle.

Ne pas conserver son nom pour un type monoprojection : la notion de « view definition » maintiendrait
une ambiguïté de composition.

### 8.2 `QueryPipelineSelection`

Supprimer le fichier et son test parce que sa `Map` encode une sélection multi-composants. La map
vide et `requireFor(component)` n'ont plus de sens lorsque le contrat porte exactement une projection.

Aucun shim deprecated, alias ou constructeur de compatibilité n'est nécessaire.

## 9. Résultats préparatoires de résolution

Ne pas introduire le sealed result du resolver dans cette révision. Le Lot 7.9.1 reste propriétaire
des intentions, de la sélection monoprojection, des ports read-only et de l'enveloppe de succès.

Les résultats fonctionnels appartiennent à la future reconception 7.9.2 :

```text
RESOLVED
PROJECTION_FAILED
NOT_READY
NOT_APPLICABLE
```

Les nouveaux contrats les rendent possibles sans les implémenter :

- terminal `READY` → `RESOLVED` ;
- terminal `FAILED` → `PROJECTION_FAILED` ;
- terminal ou latest-known absent → `NOT_READY` ;
- serving pipeline non applicable en EXACT → `NOT_APPLICABLE`.

Ne plus préparer `LATEST_KNOWN_ABSENT`, `EXACT_ABOVE_LATEST_KNOWN`, `NO_COMMON_READY_VERSION`,
diagnostics multi-projections, intersection ou ancre.

## 10. Impacts sur les tests

### 10.1 Tests conservés

Conserver sans modification :

- `QueryVersionIntentTest` ;
- `VersionedQueryResponseTest` ;
- `LatestKnownVersionQueryPortContractTest`.

### 10.2 Tests supprimés

Supprimer :

- `QueryViewDefinitionTest` ;
- `QueryPipelineSelectionTest`.

Disparaissent avec eux les scénarios uniquement liés aux vues protected/unprotected, AUTH-only,
vides ou multi-composants, à l'ordre des composants et aux maps de sélection.

### 10.3 Nouveau `QueryProjectionSelectionTest`

Tester :

- conservation exacte du `ProjectionType` ;
- conservation exacte de la `PipelineVersionDefinition` serving ;
- projection nulle refusée ;
- pipeline nulle refusée ;
- value semantics naturelles ;
- absence de collection, sélection automatique, `MAX`, registry ou lookup par composant.

Ne pas tester que le type prouve la relation producteur/projection : cette cohérence appartient au
serving provider.

### 10.4 Nouveau test de `TerminalProjectionState`

Tester :

- version positive avec `READY` ;
- version positive avec `FAILED` ;
- version zéro ou négative refusée ;
- statut null refusé ;
- `NOT_READY` refusé ;
- value semantics naturelles.

### 10.5 Révision de `ProjectionReadinessQueryPortContractTest`

Scénarios obligatoires :

| État persistant | Borne | Résultat |
| --- | ---: | --- |
| V15 READY | 15 | V15 READY |
| V15 FAILED | 15 | V15 FAILED |
| V15 NOT_READY, V13 READY | 15 | V13 READY |
| V15 NOT_READY, V13 FAILED | 15 | V13 FAILED |
| V15 READY, V13 FAILED, V8 READY | 14 | V13 FAILED |
| aucun terminal | 15 | empty |
| V16 terminal, borne 15 | 15 | V16 ignorée |
| trous V15, V10, V3 | bornes variées | plus haut terminal réel |
| insertions hors ordre | même borne | même résultat |

Conserver `statusAt` et ses cas exacts, notamment une V14 `NOT_READY` entre des versions terminales.

Prouver l'isolation entre générations avec :

- même PotId et même ProjectionType, pipelineVersion différente ;
- même PotId et même ProjectionType, PipelineId différent ;
- PotId différent ;
- ProjectionType différent.

### 10.6 Tests explicites de non-fallback inter-pipelineVersion

Premier scénario :

```text
same ProjectionType = READ_POT
same PotId
old P/v2 contains READY V15
serving P/v3 contains READY V13 only
latestKnown = 15

lookup(generation P/v3, 15) → V13 READY
```

Le fake doit échouer s'il indexe seulement par `ProjectionType + PotId` et oublie la pipelineVersion.

Second scénario :

```text
old P/v2 contains READY V15 and V13
serving P/v3 contains no terminal version

lookup(generation P/v3, 15) → empty
```

La présence d'anciennes générations ne change jamais la réponse. L'ordre d'insertion des données des
deux générations ne doit avoir aucune incidence.

## 11. Impacts sur la documentation

### 11.1 Documents canoniques à mettre à jour pendant l'exécution

`docs/architecture/read-side-target.md` :

- remplacer les vues composées par le modèle monoprojection ;
- supprimer l'intersection AUTH/projections métier ;
- définir CURRENT par la plus haute version terminale de la génération serving ;
- expliciter que serving vaut aussi pour l'historique ;
- interdire tout fallback inter-pipelineVersion ;
- déplacer AUTH après la résolution métier, en EXACT(servedVersion) ;
- conserver le refus AUTH terminal et sans fallback ;
- réécrire les exemples READ_POT/BALANCE, l'observabilité et les invariants consolidés.

`docs/plans/lot-7-read-side-implementation-plan.md` :

- réviser 7.9.1 avec les nouveaux contrats ;
- sortir AUTH et toute intersection de 7.9.2 ;
- inverser l'ancienne règle autorisant un READY plus ancien sous un FAILED récent ;
- supprimer les scénarios vides/multi-composants ;
- inscrire l'autorité historique de la pipelineVersion serving ;
- conserver 7.14.1 comme fournisseur de la sélection serving ;
- corriger critères transverses et `NEXT IMPLEMENTATION STEP`.

`docs/architecture/type-ownership.md` :

- remplacer `QueryViewDefinition` et `QueryPipelineSelection` par `QueryProjectionSelection` ;
- ajouter `TerminalProjectionState` à l'ownership `engine-query`.

`docs/architecture/module-dependency-matrix.md` :

- décrire `engine-query` comme monoprojection ;
- mentionner la recherche terminale `READY | FAILED` dans la génération serving ;
- supprimer le vocabulaire de vues et producteurs serving au pluriel ;
- conserver les frontières de dépendances existantes.

`docs/architecture/read-side-current-state.md` : à corriger après le code afin de rester factuel :

- nouveaux contrats présents ;
- anciens contrats retirés ;
- resolver toujours absent ;
- AUTH toujours hors resolver et non branché.

### 11.2 Plans à amender ou supersede

`docs/plans/lot-7.9.1-versioned-query-contracts-plan.md` doit être amendé en place avec un historique
de révision, les contrats monoprojection, le port terminal, les nouveaux tests et critères. L'historique
Git suffit à conserver l'ancien design.

`docs/plans/lot-7.9.2-query-version-resolution-design.md` doit seulement recevoir un bandeau :

```text
SUPERSEDED — repose sur l'ancien modèle multi-projection.
Ne pas utiliser pour implémenter 7.9.2.
Une nouvelle conception sera produite après audit de 7.9.1 révisé.
```

Il ne doit pas être réécrit pendant la révision 7.9.1. Son blocker sur la découverte des versions
FAILED est absorbé par la recherche terminale monoprojection.

Deux plans historiques contiennent des formulations cibles devenues fausses et doivent recevoir une
note locale `SUPERSEDED`, sans réécriture complète :

- `docs/plans/lot-7.1-read-side-documentation-alignment-plan.md` ;
- `docs/plans/lot-7.7-pot-version-user-indexes-and-keyset-pagination-plan.md`.

## 12. Impacts sur les call sites et compatibilité

Inventaire Java actuel :

| Call site | Action |
| --- | --- |
| `QueryViewDefinitionTest` | Supprimer |
| `QueryPipelineSelectionTest` | Supprimer |
| `ProjectionReadinessQueryPortContractTest` | Réécrire pour terminal et non-fallback |
| Services `engine-query` existants | Aucun impact |
| Controllers/GET | Aucun impact |
| Adapters persistence | Aucun : le port n'a pas encore d'adapter |
| Resolver 7.9.2 | Aucun : il n'existe pas |
| Architecture tests | Adapter seulement les assertions de types/packages nécessaires |

La migration est additive puis destructive dans une même passe atomique : introduire les nouveaux
contrats et tests, supprimer les anciens, puis réconcilier la documentation. Aucun alias deprecated,
bridge, surcharge legacy ou double API n'est justifié.

## 13. Frontières de modules

La révision reste dans `engine-query`, framework-free, avec les dépendances directes déjà autorisées
vers les types de domaine Pot, projection et pipeline.

Ne pas introduire de dépendance vers :

- Spring, JPA, HTTP ou Jackson ;
- infrastructure ou runtime ;
- processing engine ;
- `engine-read-projection` ;
- policy AUTH ou `TokenCapabilities` ;
- read model métier concret.

Aucune modification Maven n'est attendue.

## 14. Ordre d'implémentation de la révision

1. **Sécuriser la baseline**
   - vérifier le worktree et exécuter les tests ciblés `engine-query` ;
   - confirmer une dernière fois l'absence de consommateur de production.

2. **Introduire `TerminalProjectionState`**
   - ajouter le record et ses validations ;
   - ajouter ses tests ;
   - critère : `NOT_READY` est impossible dans ce type.

3. **Faire évoluer `ProjectionReadinessQueryPort`**
   - remplacer highest READY par highest terminal ;
   - conserver `statusAt` ;
   - réécrire le test contractuel, y compris l'isolation serving/ancienne génération ;
   - critère : trous, FAILED, bornes et ordre d'insertion sont couverts sans scan ni head.

4. **Introduire `QueryProjectionSelection`**
   - ajouter le record et son test ;
   - critère : exactement une projection et une pipelineVersion serving, applicables aussi aux
     lectures historiques.

5. **Supprimer le modèle abandonné**
   - supprimer `QueryViewDefinition` et son test ;
   - supprimer `QueryPipelineSelection` et son test ;
   - rechercher tous les symboles supprimés ;
   - critère : aucune API multi-composants ne subsiste dans `engine-query`.

6. **Renforcer les règles d'architecture**
   - vérifier framework-freedom et dépendances ;
   - ajuster `HexagonalArchitectureTest` uniquement si nécessaire ;
   - critère : aucune nouvelle dépendance extérieure.

7. **Mettre à jour les documents canoniques**
   - cible read side, plan directeur, ownership, matrice de modules, état courant ;
   - critère : monoprojection et autorité historique du serving sont normatives.

8. **Réconcilier les plans**
   - amender le plan 7.9.1 ;
   - marquer le design 7.9.2 actuel `SUPERSEDED` ;
   - annoter localement les plans historiques 7.1 et 7.7.

9. **Validation finale**
   - tests `engine-query` et architecture tests ;
   - recherche globale des abstractions abandonnées et formulations contradictoires ;
   - confirmer qu'aucun resolver 7.9.2 n'a été commencé.

## 15. Critères de fin

La révision est terminée lorsque :

1. une query versionnée représente exactement une projection métier ;
2. zéro et plusieurs projections sont impossibles par construction ;
3. AUTH n'apparaît dans aucun contrat de résolution de businessVersion ;
4. `QueryViewDefinition`, `QueryPipelineSelection` et `requiredComponents()` ont disparu ;
5. aucune collection de projections ou de pipelines ne subsiste dans ces contrats ;
6. `QueryProjectionSelection` lie explicitement projection et génération serving ;
7. la `PipelineVersionDefinition` serving fait autorité pour les lectures historiques comme récentes ;
8. une query ne fallback jamais vers une ancienne pipelineVersion non-serving, même si cette
   génération possède un artifact READY plus récent ;
9. le port retourne la plus haute version `READY` ou `FAILED` sous la borne dans la seule génération
   fournie ;
10. `TerminalProjectionState` porte ensemble businessVersion et statut terminal ;
11. `statusAt` reste disponible pour EXACT ;
12. les trous et traitements hors ordre restent supportés ;
13. aucun scan numérique, aucune continuité et aucun `ProjectionHead` ;
14. les générations sont isolées par projection, PipelineId, pipelineVersion et PotId ;
15. `QueryVersionIntent`, `VersionedQueryResponse` et `LatestKnownVersionQueryPort` sont inchangés ;
16. la documentation ne décrit plus d'intersection, de vue vide ou de fallback inter-pipeline ;
17. le design 7.9.2 actuel est explicitement non utilisable ;
18. aucun resolver 7.9.2 n'est implémenté.

## 16. Risques et pièges

- **Best artifact cross-generation** : rechercher le meilleur artifact parmi plusieurs générations
  violerait le serving et pourrait exposer un modèle produit par une pipelineVersion non autoritative.
- **Serving limité aux nouveautés** : considérer la pipeline serving seulement pour les nouvelles
  businessVersions réintroduirait implicitement un fallback historique.
- **Version sans statut** : retourner seulement une businessVersion terminale imposerait un second
  lookup ; le couple version/statut évite cette ambiguïté.
- **Ancien vocabulaire de vue** : conserver `QueryViewDefinition` avec un seul champ maintiendrait une
  généralité et un nom trompeurs.
- **Deux paramètres sans contrat** : passer partout `ProjectionType` et `PipelineVersionDefinition`
  séparément affaiblirait leur association serving ; le petit record a une sémantique propre.
- **Relation producteur non prouvable localement** : le record ne doit pas inventer un registry ; le
  serving provider garantit la cohérence du couple.
- **NOT_READY terminal** : `TerminalProjectionState` doit le refuser explicitement.
- **Artifact au-dessus de la borne** : il doit être ignoré, sans réduire la borne ni prouver une
  continuité.
- **Artifact hors applicabilité** : c'est une violation de production dans la génération, pas un
  motif de fallback vers une autre pipelineVersion.
- **Documentation récente mais obsolète** : une recherche globale est nécessaire car le nouveau
  cadrage inverse explicitement la règle qui masquait un FAILED récent par un READY ancien.

## 17. Vérifications documentaires finales

Après exécution, rechercher et éliminer ou marquer superseded toute formulation laissant entendre :

- qu'une query versionnée peut utiliser zéro ou plusieurs projections métier ;
- qu'AUTH participe à la résolution de `servedVersion` ;
- que CURRENT cherche la dernière version READY plutôt que terminale ;
- qu'un `FAILED` récent peut être masqué par un ancien `READY` ;
- qu'une ancienne pipelineVersion peut servir de fallback ;
- que serving ne concerne que les nouvelles businessVersions ;
- que le resolver choisit lui-même la pipelineVersion serving ;
- que `ProjectionHead` ou une continuité intervient dans la résolution.

## 18. Questions ouvertes / blockers

`NONE`.

Le cadrage détermine suffisamment la révision :

- les deux contrats multi-composants disparaissent ;
- `QueryProjectionSelection` porte le couple monoprojection/génération serving ;
- le port retourne un `TerminalProjectionState` dans cette génération seulement ;
- `statusAt` reste exact ;
- les résultats fonctionnels restent dans le futur Lot 7.9.2.

# DECISIONS CONFIRMED

- Une query versionnée porte exactement une projection métier.
- AUTH est demandé séparément en EXACT(servedVersion) pour une query protégée.
- CURRENT sélectionne la plus haute version terminale `READY` ou `FAILED`.
- Un `FAILED` récent n'est jamais masqué par un ancien `READY`.
- L'absence de latest-known ou de terminal produit `NOT_READY`.
- EXACT ne fallback jamais.
- La pipelineVersion serving fait autorité pour les lectures récentes et historiques.
- Aucun fallback inter-pipelineVersion n'existe.
- latest-known reste uniquement une borne d'exposition.
- Aucun head, scan numérique ou invariant de continuité.

# PROPOSED CONTRACT CHANGES

- Supprimer `QueryViewDefinition`.
- Supprimer `QueryPipelineSelection`.
- Ajouter `QueryProjectionSelection(ProjectionType, PipelineVersionDefinition)`.
- Ajouter `TerminalProjectionState(businessVersion, READY | FAILED)`.
- Remplacer `findHighestReadyBusinessVersionAtOrBelow` par `findHighestTerminalAtOrBelow`.
- Conserver `statusAt`, `QueryVersionIntent`, `VersionedQueryResponse` et
  `LatestKnownVersionQueryPort`.
- Reporter le résultat typé simplifié au futur Lot 7.9.2.

# FILES TO CHANGE

## Code

- `app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryProjectionSelection.java` — nouveau ;
- `app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/out/query/TerminalProjectionState.java` — nouveau ;
- `app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/out/query/ProjectionReadinessQueryPort.java` — recherche terminale.

## Tests

- nouveau `QueryProjectionSelectionTest` ;
- nouveau `TerminalProjectionStateTest` ;
- révision de `ProjectionReadinessQueryPortContractTest` ;
- adaptation éventuelle de `HexagonalArchitectureTest`.

## Documentation lors de l'exécution ultérieure

- `docs/architecture/read-side-target.md` ;
- `docs/architecture/read-side-current-state.md` ;
- `docs/architecture/type-ownership.md` ;
- `docs/architecture/module-dependency-matrix.md` ;
- `docs/plans/lot-7-read-side-implementation-plan.md` ;
- `docs/plans/lot-7.9.1-versioned-query-contracts-plan.md` ;
- notes superseded ciblées dans les plans 7.1 et 7.7.

# FILES TO DELETE OR SUPERSEDE

## À supprimer

- `app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryViewDefinition.java` ;
- `app/engine-query/src/test/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryViewDefinitionTest.java` ;
- `app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryPipelineSelection.java` ;
- `app/engine-query/src/test/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryPipelineSelectionTest.java`.

## À marquer `SUPERSEDED`

- `docs/plans/lot-7.9.2-query-version-resolution-design.md`.

# OPEN QUESTIONS / BLOCKERS

`NONE`.
