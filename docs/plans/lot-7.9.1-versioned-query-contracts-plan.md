# Lot 7.9.1 — Contrats de query versionnée monoprojection

Statut : **DONE — révision monoprojection livrée**.

Historique : la première version de ce plan décrivait des vues composées, AUTH inclus dans la
résolution et une sélection multi-pipelines. Ce modèle est superseded par la révision décrite dans
`lot-7.9.1-monoprojection-contract-revision-plan.md` et remplacé intégralement par le présent contrat.

## 1. Contexte et sources

Sources canoniques :

- `docs/architecture/read-side-target.md` ;
- `docs/architecture/read-side-current-state.md` ;
- `docs/plans/lot-7-read-side-implementation-plan.md` ;
- `docs/architecture/type-ownership.md` ;
- `docs/architecture/module-dependency-matrix.md`.

Le Lot 7.9.1 fournit uniquement les contrats framework-free préparant le futur Query Version
Resolver. Aucun resolver, adapter, controller ou mapping HTTP n'est livré ici.

## 2. Objectif

Une query versionnée porte exactement :

```text
une ProjectionType métier
+ sa PipelineVersionDefinition serving
+ QueryVersionIntent CURRENT | EXACT(V)
```

Jamais zéro projection métier. Jamais plusieurs. AUTH reste hors du resolver de version et sera
demandé séparément en `EXACT(servedVersion)` pour une query protégée.

## 3. Non-objectifs

Ce lot n'implémente pas :

- les algorithmes CURRENT ou EXACT ;
- les résultats `RESOLVED`, `PROJECTION_FAILED`, `NOT_READY`, `NOT_APPLICABLE` ;
- AUTH, `TokenCapabilities` ou une policy d'autorisation ;
- le fournisseur serving ou son lifecycle ;
- un adapter de persistence ;
- HTTP, controllers ou migration des GET ;
- pagination, Balance générique ou observabilité.

## 4. Invariants structurants

- Events et Tasks sont sûrs hors ordre.
- `ProjectionHead` ne prouve aucune continuité et n'est pas consulté.
- `pipelineVersion != businessVersion`.
- latest-known ne bloque jamais la production et borne uniquement l'exposition.
- EXACT ne fallback jamais.
- CURRENT utilise le plus haut état terminal `READY | FAILED` de la génération serving sous la borne.
- Un `FAILED` récent n'est jamais masqué par un ancien `READY`.
- La génération serving fait autorité pour les lectures récentes et historiques.
- Il n'existe aucun fallback vers une ancienne pipelineVersion non-serving.
- AUTH ne participe pas à la résolution de la businessVersion métier.

## 5. Contrats livrés

### 5.1 `QueryVersionIntent`

Fichier :

```text
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryVersionIntent.java
```

Le sealed interface expose `Current`, `Exact(long businessVersion)`, `current()` et `exact(V)`.
`Exact.businessVersion` doit être strictement positive.

### 5.2 `VersionedQueryResponse<T>`

Fichier :

```text
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/result/VersionedQueryResponse.java
```

Contrat :

```text
requestedVersion
servedVersion
latestKnownVersion
generatedAt
data
```

Les références sont non nulles, les versions positives, `servedVersion <= latestKnownVersion` et
EXACT impose `servedVersion == requestedVersion`. Aucun champ `stale` n'existe.

### 5.3 `QueryProjectionSelection`

Fichier :

```text
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/in/query/version/QueryProjectionSelection.java
```

Contrat :

```java
record QueryProjectionSelection(
    ProjectionType projectionType,
    PipelineVersionDefinition servingPipeline)
```

Les deux valeurs sont obligatoires. Le couple signifie : projection métier demandée et génération
actuellement serving qui fait autorité pour cette query, y compris pour une lecture historique.

Le fournisseur 7.14.1 garantit que cette pipeline produit cette projection. Le Query Kernel ne
recherche pas cette relation, ne choisit aucun `MAX(pipelineVersion)` et ne consulte aucune ancienne
génération. `ProjectionType` reste distinct de `PipelineId`.

### 5.4 `TerminalProjectionState`

Fichier :

```text
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/out/query/TerminalProjectionState.java
```

Contrat :

```java
record TerminalProjectionState(
    long businessVersion,
    ProjectionStatus status)
```

La version est positive et `status` vaut exclusivement `READY` ou `FAILED`. `NOT_READY` est refusé
par construction. Le couple évite une seconde lecture après la découverte de la version terminale.

### 5.5 `ProjectionReadinessQueryPort`

Fichier :

```text
app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/out/query/ProjectionReadinessQueryPort.java
```

Contrat :

```java
Optional<TerminalProjectionState> findHighestTerminalAtOrBelow(
    ProjectionGenerationIdentity generation,
    long upperBoundInclusive);

ProjectionStatus statusAt(ProjectionIdentity identity);
```

La première opération retourne le plus haut état réellement terminal sous la borne dans exactement
la génération fournie. Elle ne suppose aucune continuité, ne scanne pas chaque businessVersion et ne
consulte aucun head. Un résultat vide signifie qu'aucun terminal de cette génération n'existe sous
la borne.

La génération contient `ProjectionType + PipelineDefinition + PotId`. Cette identité complète isole
notamment les pipelineVersions. Le port ne cherche jamais dans plusieurs générations.

`statusAt` reste la lecture exacte nécessaire au futur EXACT(V). L'applicabilité est contrôlée depuis
`PipelineVersionDefinition` avant cette lecture et n'est jamais confondue avec `NOT_READY`.

### 5.6 `LatestKnownVersionQueryPort`

Le port read-only existant reste inchangé :

```java
Optional<LatestKnownVersion> findByPotId(PotId potId);
```

L'absence sera traduite par le futur resolver en `NOT_READY`.

## 6. Identités réutilisées

```text
ProjectionGenerationIdentity
= ProjectionType
+ PipelineDefinition serving
+ PotId

ProjectionIdentity
= ProjectionGenerationIdentity
+ businessVersion
```

Ces identités adressent les artifacts persistés. Elles ne sont pas dupliquées dans un nouveau modèle.

## 7. Sémantique serving

Pour une `ProjectionType`, le fournisseur serving sélectionne une unique
`PipelineVersionDefinition`. Cette sélection s'applique aux lectures actuelles et historiques.

```text
READ_POT, serving P/v3, latestKnown 100

P/v3 : V100 NOT_READY, V99 NOT_READY, V98 READY
P/v2 : V100 READY, V99 READY, V98 READY

CURRENT -> P/v3 / V98
```

P/v2/V100 n'est jamais candidat. Si P/v3 ne contient aucun terminal, CURRENT sera `NOT_READY`, même
si P/v2 contient des artifacts READY.

Pour CURRENT, un artifact hors applicabilité dans la génération serving est une violation de
production, pas un cas compensé par fallback. Pour EXACT(V), une pipeline serving non applicable à V
produira `NOT_APPLICABLE`.

## 8. Placement et dépendances

| Type | Module | Package | Dépendances |
| --- | --- | --- | --- |
| `QueryVersionIntent` | `engine-query` | `engine.port.in.query.version` | JDK |
| `QueryProjectionSelection` | `engine-query` | `engine.port.in.query.version` | projection, pipeline |
| `TerminalProjectionState` | `engine-query` | `engine.port.out.query` | projection |
| `ProjectionReadinessQueryPort` | `engine-query` | `engine.port.out.query` | projection |
| `LatestKnownVersionQueryPort` | `engine-query` | `engine.port.out.query` | Pot, projection |
| `VersionedQueryResponse<T>` | `engine-query` | `engine.port.in.query.result` | JDK, intent |

`engine-query` ne dépend ni d'infrastructure, ni de runtime, ni de processing, ni de
`engine-read-projection`, Spring, JPA ou HTTP.

## 9. Tests livrés

### Intent et réponse

- CURRENT et EXACT positifs ;
- EXACT invalide refusé ;
- invariants de l'enveloppe, exactitude de l'intention et absence de `stale`.

### Sélection monoprojection

- projection et pipeline serving exactes conservées ;
- valeurs nulles refusées ;
- value semantics naturelles.

### État terminal

- READY et FAILED acceptés ;
- NOT_READY, null et versions invalides refusés.

### Port readiness

- READY ou FAILED à la borne ;
- NOT_READY à la borne avec terminal inférieur ;
- trous et absence de terminal ;
- version terminale supérieure à la borne ignorée ;
- persistence hors ordre sans incidence ;
- `statusAt` exact indépendant des voisins ;
- isolation par PotId, ProjectionType, PipelineId et pipelineVersion ;
- ancienne génération READY plus récente jamais utilisée pour la génération serving ;
- génération serving vide restant vide malgré des artifacts d'une ancienne génération.

## 10. Contrats supprimés

Les types suivants et leurs tests ont été retirés :

- `QueryViewDefinition` ;
- `QueryPipelineSelection`.

Il ne subsiste donc aucun `requiredComponents()`, `Set<ProjectionType>`,
`Map<ProjectionType, PipelineVersionDefinition>`, vue protected/unprotected, vue vide ou sélection
multi-pipeline dans les contrats préparatoires.

## 11. Conséquences pour CURRENT

Le futur resolver recevra une seule `QueryProjectionSelection`, construira la
`ProjectionGenerationIdentity` serving et appellera :

```java
findHighestTerminalAtOrBelow(generation, latestKnownVersion)
```

Résultats attendus :

```text
latest-known absent   -> NOT_READY
terminal absent       -> NOT_READY
highest terminal READY  -> RESOLVED(V)
highest terminal FAILED -> PROJECTION_FAILED(V)
```

Le resolver ne cherche pas un READY plus ancien sous un FAILED et ne consulte jamais une ancienne
pipelineVersion.

## 12. Conséquences pour EXACT

Le futur resolver :

1. exige latest-known présent et `V <= latestKnownVersion`, sinon `NOT_READY` ;
2. vérifie `servingPipeline.appliesTo(V)`, sinon `NOT_APPLICABLE` ;
3. construit la `ProjectionIdentity` exacte ;
4. lit `statusAt(identity)` ;
5. traduit READY, FAILED ou NOT_READY sans fallback.

AUTH ne participe pas à ces étapes. Pour une query protégée, 7.9.3 demandera ensuite AUTH en
`EXACT(servedVersion)` et un refus sera terminal.

## 13. Risques verrouillés

- Ne jamais chercher le « meilleur artifact » parmi plusieurs générations.
- Ne jamais interpréter serving comme limité aux nouvelles businessVersions.
- Ne jamais accepter NOT_READY dans `TerminalProjectionState`.
- Ne jamais réintroduire AUTH sous la forme d'un composant technique du resolver.
- Ne jamais utiliser un head ou supposer une continuité.
- Ne jamais scanner les businessVersions une par une.
- Ne jamais confondre pipelineVersion et businessVersion.

## 14. Critères d'acceptation

1. Une query représente exactement une projection métier et une génération serving.
2. AUTH est absent des contrats de résolution de version.
3. La recherche descendante considère READY et FAILED comme terminaux.
4. Un FAILED récent n'est pas masqué par un READY ancien.
5. La génération serving est la seule consultée, y compris pour l'historique.
6. Aucune ancienne pipelineVersion ne sert de fallback.
7. `statusAt` reste disponible pour EXACT.
8. Les trous, l'ordre de persistence et les générations coexistantes sont sûrs.
9. Aucun framework, adapter, resolver ou GET n'est introduit.
10. Les anciens contrats multi-composants ne subsistent plus.

## 15. Fichiers livrés ou retirés

Ajoutés :

- `QueryProjectionSelection.java` et son test ;
- `TerminalProjectionState.java` et son test.

Modifiés :

- `ProjectionReadinessQueryPort.java` ;
- `ProjectionReadinessQueryPortContractTest.java`.

Retirés :

- `QueryViewDefinition.java` et son test ;
- `QueryPipelineSelection.java` et son test.

## 16. OPEN QUESTIONS / BLOCKERS

`NONE`.

La prochaine étape n'est pas l'implémentation immédiate du resolver : elle consiste à auditer ces
contrats révisés puis à produire une nouvelle conception monoprojection du Lot 7.9.2. Le document
`lot-7.9.2-query-version-resolution-design.md` précédent est superseded.
