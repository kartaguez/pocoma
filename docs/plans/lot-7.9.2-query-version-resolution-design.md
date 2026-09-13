# Lot 7.9.2 — Conception de la résolution CURRENT / EXACT du Query Kernel monoprojection

Statut : **DESIGN READY**.

Ce document remplace intégralement l'ancien design 7.9.2 multi-projection, désormais abandonné.
Il constitue la référence de conception entre les contrats livrés par le Lot 7.9.1 et le futur plan
d'implémentation de 7.9.2. Il fige les décisions fonctionnelles et la forme conceptuelle de l'API,
sans constituer un plan d'implémentation et sans livrer de code.

## 1. Statut et objectif

Le Lot 7.9.2 doit concevoir un resolver framework-free qui répond à une seule question :

> Pour un Pot, une intention `CURRENT` ou `EXACT(V)` et l'unique génération de projection métier
> sélectionnée pour serving, quelle businessVersion peut être servie, ou quel état fonctionnel empêche
> cette résolution ?

La signature conceptuelle cible est :

```java
QueryVersionResolution resolve(
    PotId potId,
    QueryVersionIntent requestedVersion,
    QueryProjectionSelection projectionSelection);
```

Le resolver dépend uniquement de :

```text
LatestKnownVersionQueryPort
ProjectionReadinessQueryPort
```

Il expose une seule méthode publique `resolve(...)`. Le dispatch entre `CURRENT` et `EXACT(V)` reste
un détail interne.

Sources vérifiées pour cette conception :

- `docs/architecture/read-side-target.md` ;
- `docs/architecture/read-side-current-state.md` ;
- `docs/plans/lot-7-read-side-implementation-plan.md` ;
- `docs/plans/lot-7.9.1-versioned-query-contracts-plan.md` ;
- `docs/plans/lot-7.9.1-monoprojection-contract-revision-plan.md` ;
- `docs/architecture/type-ownership.md` ;
- `docs/architecture/module-dependency-matrix.md` ;
- les contrats Java réellement livrés dans `engine-query`, `domain-projection` et
  `domain-pipeline`.

## 2. Contexte et décisions héritées de 7.9.1

Le repository livre déjà les contrats préparatoires suivants :

- `QueryVersionIntent` représente `Current` ou `Exact(long businessVersion)` et garantit qu'une
  version exacte est positive ;
- `QueryProjectionSelection` lie exactement un `ProjectionType` métier à la
  `PipelineVersionDefinition` serving fournie par l'appelant ;
- `ProjectionReadinessQueryPort.findHighestTerminalAtOrBelow(...)` retourne le plus haut état
  terminal réellement présent dans une génération exacte, sous une borne inclusive ;
- `TerminalProjectionState` associe une businessVersion positive à `READY` ou `FAILED`, jamais à
  `NOT_READY` ;
- `ProjectionReadinessQueryPort.statusAt(...)` retourne le statut d'une `ProjectionIdentity`
  exacte ;
- `LatestKnownVersionQueryPort.findByPotId(...)` retourne éventuellement la borne d'exposition du
  Pot ;
- `VersionedQueryResponse<T>` reste l'enveloppe d'une réponse réussie produite après lecture des
  données. Il n'est pas le résultat du resolver.

Les identités existantes sont réutilisées sans duplication :

```text
ProjectionGenerationIdentity
= ProjectionType + PipelineDefinition + PotId

ProjectionIdentity
= ProjectionGenerationIdentity + businessVersion

PipelineVersionDefinition
= PipelineDefinition identity + VersionApplicability
```

`PipelineDefinition.pipelineVersion` est une **pipelineVersion**. Les versions portées par
`QueryVersionIntent.Exact`, `TerminalProjectionState`, `ProjectionIdentity.potVersion` et les
résultats de résolution sont des **businessVersions**. Ces deux axes ne sont jamais interchangeables.

Invariant principal :

> Toute query versionnée porte exactement une projection métier, jamais zéro et jamais plusieurs.

## 3. Responsabilités et non-responsabilités

### 3.1 Responsabilités

Le resolver doit seulement :

1. valider ses trois entrées ;
2. lire latest-known pour le Pot ;
3. construire l'identité de la génération serving ;
4. dispatcher en interne selon `CURRENT` ou `EXACT(V)` ;
5. consulter le port de readiness terminale ou exacte ;
6. vérifier l'applicabilité pour `EXACT(V)` ;
7. produire l'un des quatre variants de `QueryVersionResolution`.

Le service est sans état métier mutable. Ses seules collaborations sont les deux ports read-only.

### 3.2 Non-responsabilités

Le resolver ne doit pas :

- lire ou composer les données métier de projection ;
- charger un artifact ;
- construire `VersionedQueryResponse<T>` ;
- connaître AUTH, des droits, une policy ou `TokenCapabilities` ;
- sélectionner la pipelineVersion serving ;
- interroger un catalogue ou un registry de producteurs ;
- consulter une autre génération que celle fournie ;
- lire `ProjectionHead` ;
- inspecter Tasks, Slots, Claims, leases ou tout état de processing ;
- diagnostiquer ou exposer `ProjectionFailure` ;
- mapper vers HTTP ;
- lire le write side primaire ;
- déclencher une projection ou attendre sa convergence ;
- gérer pagination, listes ou scan de candidats ;
- dépendre de Spring, JPA, HTTP, d'une infrastructure, d'un runtime ou d'un processing engine.

Ces exclusions séparent strictement 7.9.2 de la composition et de l'autorisation ultérieures de
7.9.3.

## 4. Entrée du resolver

Le resolver reçoit directement :

```java
PotId potId
QueryVersionIntent requestedVersion
QueryProjectionSelection projectionSelection
```

Les trois valeurs sont obligatoires et refusées si elles sont nulles.

`QueryProjectionSelection` contient :

```text
projectionType
servingPipeline
```

La sélection est déjà établie par l'appelant. Le resolver ne fait aucun `MAX(pipelineVersion)`, ne
cherche pas de génération alternative et ne vérifie pas la relation producteur/projection dans un
registry. La cohérence de cette sélection est la précondition garantie par le futur fournisseur
serving du Lot 7.14.1.

Une query protégée n'ajoute aucune entrée au resolver : l'autorisation sera orchestrée après une
résolution métier réussie.

## 5. `QueryVersionResolution`

### 5.1 Forme scellée proposée

Le résultat est un type scellé possédant exactement quatre variants :

```java
public sealed interface QueryVersionResolution {

    PotId potId();

    QueryVersionIntent requestedVersion();

    record Resolved(
        PotId potId,
        QueryVersionIntent requestedVersion,
        long servedVersion,
        long latestKnownVersion)
        implements QueryVersionResolution {}

    record ProjectionFailed(
        PotId potId,
        QueryVersionIntent requestedVersion,
        long failedVersion,
        long latestKnownVersion)
        implements QueryVersionResolution {}

    record NotReady(
        PotId potId,
        QueryVersionIntent requestedVersion,
        OptionalLong latestKnownVersion)
        implements QueryVersionResolution {}

    record NotApplicable(
        PotId potId,
        QueryVersionIntent requestedVersion,
        long latestKnownVersion)
        implements QueryVersionResolution {}
}
```

Les records imbriqués maintiennent une petite surface publique et rendent les quatre issues visibles
au même endroit. Aucun variant additionnel n'est prévu.

### 5.2 Champs communs

Tous les résultats portent :

- le `PotId` résolu ;
- l'intention `QueryVersionIntent` originale.

`requestedVersion` reste `CURRENT` ou `EXACT(V)` exactement comme demandé. Il n'est jamais remplacé
par la version servie, échouée ou latest-known.

### 5.3 Asymétrie latest-known

L'asymétrie suivante est volontaire :

```text
Resolved         -> latestKnownVersion obligatoire
ProjectionFailed -> latestKnownVersion obligatoire
NotApplicable    -> latestKnownVersion obligatoire
NotReady         -> OptionalLong latestKnownVersion
```

`NotReady` est le seul résultat possible quand latest-known est absent. Il doit donc pouvoir porter
explicitement cette absence. Les trois autres variants ne peuvent être produits qu'après lecture
d'une borne existante.

L'interface ne doit pas introduire un accesseur commun `OptionalLong latestKnownVersion()` seulement
pour uniformiser artificiellement les variants.

## 6. Invariants des quatre variants

### 6.1 Invariants communs

Tous les records valident localement :

```text
potId != null
requestedVersion != null
```

Les constructeurs compacts utilisent les conventions existantes : `requireNonNull` pour les
références et `IllegalArgumentException` pour les versions invalides.

### 6.2 `Resolved`

`Resolved` signifie qu'une businessVersion exposable est `READY` dans la génération serving.

Validations :

```text
servedVersion >= 1
latestKnownVersion >= 1
servedVersion <= latestKnownVersion
requestedVersion == EXACT(V) => servedVersion == V
```

`CURRENT` peut naturellement résoudre une version plus basse que latest-known.

### 6.3 `ProjectionFailed`

`ProjectionFailed` signifie que la businessVersion choisie par la sémantique de la requête est
terminale `FAILED` dans la génération serving.

Validations :

```text
failedVersion >= 1
latestKnownVersion >= 1
failedVersion <= latestKnownVersion
requestedVersion == EXACT(V) => failedVersion == V
```

Le résultat ne transporte ni `ProjectionFailure`, ni message d'infrastructure, ni diagnostic de
processing. La businessVersion échouée suffit à la décision de résolution.

### 6.4 `NotReady`

`NotReady` agrège volontairement les cas suivants :

- latest-known absent ;
- `EXACT(V)` au-dessus de latest-known ;
- statut exact `NOT_READY` ;
- aucune version terminale dans la génération serving sous la borne pour `CURRENT`.

Validations :

```text
latestKnownVersion != null
latestKnownVersion présent => valeur >= 1
```

Il n'existe pas de variants `LatestKnownAbsent`, `ExactAboveLatestKnown` ou
`NoTerminalVersion`.

### 6.5 `NotApplicable`

`NotApplicable` signifie exclusivement que la pipelineVersion serving ne s'applique pas à la
businessVersion demandée par `EXACT(V)`.

Validations :

```text
latestKnownVersion >= 1
requestedVersion est QueryVersionIntent.Exact
requestedVersion.businessVersion <= latestKnownVersion
```

La validation rend `NotApplicable(CURRENT, ...)` impossible. La version exacte n'est pas dupliquée
dans un champ supplémentaire : elle est déjà portée par `QueryVersionIntent.Exact`.

Le résultat n'expose ni `ProjectionType`, ni `PipelineVersionDefinition`, ni
`VersionApplicability`.

## 7. Génération serving

Pour chaque appel, le resolver construit exactement une génération :

```java
ProjectionGenerationIdentity generation = new ProjectionGenerationIdentity(
    projectionSelection.projectionType(),
    projectionSelection.servingPipeline().identity(),
    potId);
```

La `PipelineVersionDefinition` complète reste disponible séparément pour le contrôle
d'applicabilité de `EXACT(V)`. Son `identity()` fournit le `PipelineDefinition` inclus dans
`ProjectionGenerationIdentity`.

La pipelineVersion serving fait autorité pour les lectures récentes **et historiques**. Les états
d'une ancienne génération ne sont jamais consultés.

Exemple canonique :

```text
latestKnown = 100
serving = P/v3

P/v3 : V100 NOT_READY, V99 NOT_READY, V98 READY
P/v2 : V100 READY, V99 READY

CURRENT -> P/v3 / V98 -> RESOLVED(98)
```

Le resolver ne sert jamais `P/v2 / V100`. Si `P/v3` n'a aucun état terminal sous la borne, le
résultat est `NOT_READY`, même si une ancienne génération est `READY`.

## 8. Algorithme `CURRENT`

Algorithme normatif :

```text
1. latestKnown = latestKnownVersionQueryPort.findByPotId(potId)

2. si absent
   -> NotReady(potId, CURRENT, OptionalLong.empty())

3. sinon L = latestKnown.latestKnownVersion

4. construire la ProjectionGenerationIdentity serving

5. terminal = projectionReadinessQueryPort.findHighestTerminalAtOrBelow(generation, L)

6. si terminal absent
   -> NotReady(potId, CURRENT, OptionalLong.of(L))

7. si terminal = TerminalProjectionState(V, READY)
   -> Resolved(potId, CURRENT, V, L)

8. si terminal = TerminalProjectionState(V, FAILED)
   -> ProjectionFailed(potId, CURRENT, V, L)
```

`CURRENT` sélectionne le plus haut état **terminal**, pas le plus haut `READY` :

```text
latestKnown = 15
V15 NOT_READY
V14 FAILED
V13 READY

CURRENT -> PROJECTION_FAILED(14)
```

Le `FAILED` terminal plus récent ne peut jamais être masqué par l'ancien `READY` à V13. Les trous
sont normaux et aucune continuité n'est supposée.

Le resolver ne scanne pas numériquement `L, L-1, ...`. Un unique appel au lookup terminal confie au
stockage la recherche efficace de la plus haute version terminale réelle. Le resolver ne connaît ni
profondeur de recherche, ni `maxDepth`, ni `maxFallback`, ni `lookback`.

## 9. Algorithme `EXACT(V)`

La businessVersion `V` vient de `QueryVersionIntent.Exact`, qui garantit déjà `V >= 1`.

Algorithme normatif :

```text
1. latestKnown = latestKnownVersionQueryPort.findByPotId(potId)

2. si absent
   -> NotReady(potId, EXACT(V), OptionalLong.empty())

3. sinon L = latestKnown.latestKnownVersion

4. si V > L
   -> NotReady(potId, EXACT(V), OptionalLong.of(L))

5. construire la ProjectionGenerationIdentity serving

6. si projectionSelection.servingPipeline().appliesTo(V) == false
   -> NotApplicable(potId, EXACT(V), L)

7. identity = new ProjectionIdentity(generation, V)

8. status = projectionReadinessQueryPort.statusAt(identity)

9. READY
   -> Resolved(potId, EXACT(V), V, L)

10. FAILED
    -> ProjectionFailed(potId, EXACT(V), V, L)

11. NOT_READY
    -> NotReady(potId, EXACT(V), OptionalLong.of(L))
```

`EXACT(V)` ne consulte aucune autre businessVersion et aucune autre pipelineVersion.

## 10. Ordre des contrôles

### 10.1 Ordre commun

Les validations structurelles des arguments ont lieu à l'entrée. La lecture latest-known précède
toute consultation de readiness ou d'applicabilité fonctionnelle.

### 10.2 Ordre normatif pour `EXACT(V)`

L'ordre suivant est figé :

```text
1. latest-known présent ?
2. V <= latest-known ?
3. pipelineVersion serving applicable à V ?
4. statusAt(V)
```

Cet ordre porte une sémantique d'exposition. Exemple :

```text
latestKnown = 14
EXACT(15)
pipeline non applicable à 15

-> NOT_READY(14)
```

Le résultat n'est pas `NOT_APPLICABLE`, car V15 n'est pas encore exposable. Le resolver ne révèle
pas une propriété interne de la génération au-delà de la borne latest-known.

Pour la même raison, `statusAt(V)` n'est jamais appelé avant les trois premiers contrôles.

## 11. `latestKnownVersion`

`latestKnownVersion` est uniquement une borne d'exposition :

```text
production possible > latestKnownVersion
exposition toujours <= latestKnownVersion
```

Il ne prouve :

- ni `READY` ;
- ni `FAILED` ;
- ni continuité ;
- ni existence d'une projection ;
- ni position d'un head.

Son absence produit toujours `NotReady(..., OptionalLong.empty())`. Lorsqu'elle existe, elle est
recopiée dans tous les résultats, y compris `NotReady`.

Le resolver ne participe pas à sa production et ne l'utilise jamais pour bloquer scheduling,
Tasks ou materialization.

## 12. Applicabilité

`VersionApplicability` appartient à la `PipelineVersionDefinition` serving fournie. Une pipeline non
applicable à V n'est pas `NOT_READY` à V : elle ne désigne aucun artifact attendu pour cette
génération à cette businessVersion.

Pour `EXACT(V)`, après validation de la borne d'exposition :

```java
projectionSelection.servingPipeline().appliesTo(V)
```

retourne `false` -> `NotApplicable`.

Pour `CURRENT`, le resolver ne produit jamais `NotApplicable`. Il demande le plus haut terminal
réel de la génération serving sous latest-known :

- aucun terminal -> `NotReady` ;
- terminal `READY` -> `Resolved` ;
- terminal `FAILED` -> `ProjectionFailed`.

Un artifact persisté hors de la plage d'applicabilité de sa génération constitue une violation de
production. `CURRENT` ne la compense ni par un scan, ni par un fallback, ni par une nouvelle
classification fonctionnelle.

## 13. Absence de fallback

Le resolver n'effectue aucun fallback :

- `EXACT(V)` n'essaie jamais une autre businessVersion ;
- `CURRENT` respecte le plus haut terminal, y compris quand il est `FAILED` ;
- aucune ancienne pipelineVersion n'est consultée ;
- aucune génération alternative n'est construite ;
- aucun `ProjectionHead` n'est utilisé pour proposer une version ;
- aucun trou n'est interprété comme une preuve de continuité ou d'absence globale.

`findHighestTerminalAtOrBelow(generation, L)` est une recherche dans l'unique génération serving,
pas une permission de basculer entre générations.

## 14. AUTH hors du resolver

Le Query Version Resolver ne connaît pas AUTH.

Pour une query protégée, l'enchaînement futur est :

```text
business resolver
-> Resolved(servedVersion = V)
-> AUTH demandé en EXACT(V)
-> décision d'autorisation depuis AUTH(V)
-> lecture métier à V si autorisée
```

La readiness et le contenu d'AUTH ne participent pas à la sélection de la businessVersion métier.
Si AUTH(V) est `NOT_READY`, `FAILED` ou refuse l'accès, aucun fallback vers une businessVersion
métier plus ancienne n'est permis.

Cette orchestration appartient à une étape ultérieure. 7.9.2 n'introduit ni `AUTH ProjectionType`,
ni `authorizationComponent`, ni `requiredComponents`, ni intersection business/AUTH, ni policy.

## 15. Placement, ownership et dépendances

Les conventions actuelles d'`engine-query` séparent les contrats exposés sous `port.in.query` des
services sous `service.query`. Le placement recommandé est :

| Type | Module | Package | Responsabilité |
| --- | --- | --- | --- |
| `QueryVersionResolution` | `engine-query` | `com.kartaguez.pocoma.engine.port.in.query.version` | Résultat fonctionnel scellé de la résolution |
| `QueryVersionResolver` | `engine-query` | `com.kartaguez.pocoma.engine.service.query.version` | Résolution CURRENT/EXACT monoprojection |

`QueryVersionResolver` est un service framework-free construit avec
`LatestKnownVersionQueryPort` et `ProjectionReadinessQueryPort`. Son API publique unique est
`resolve(...)`.

Les dépendances Maven déjà présentes d'`engine-query` vers `domain-pot`, `domain-projection` et
`domain-pipeline` suffisent. Aucune nouvelle dépendance Maven n'est nécessaire.

Les dépendances autorisées des nouveaux types sont limitées à :

- JDK (`OptionalLong`) ;
- `PotId` ;
- les contrats 7.9.1 d'`engine-query` ;
- `ProjectionGenerationIdentity`, `ProjectionIdentity` et `ProjectionStatus` ;
- `PipelineVersionDefinition` par l'intermédiaire de `QueryProjectionSelection`.

Sont interdits : Spring, JPA, HTTP, Jackson, infrastructure, runtime, moteurs de processing,
`engine-read-projection`, policy AUTH et reader de `ProjectionHead`.

## 16. Matrice de tests de conception

Cette matrice est normative pour le futur plan d'implémentation. Elle ne demande aucun test dans la
présente passe documentaire.

### 16.1 `CURRENT`

| Cas | État | Résultat attendu | Accès importants |
| --- | --- | --- | --- |
| latest-known absent | aucun latest-known | `NotReady(CURRENT, empty)` | aucun lookup terminal |
| aucun terminal | latest-known 15 | `NotReady(CURRENT, 15)` | lookup génération serving seulement |
| terminal READY à la borne | V15 READY | `Resolved(CURRENT, 15, 15)` | un lookup terminal |
| terminal FAILED à la borne | V15 FAILED | `ProjectionFailed(CURRENT, 15, 15)` | un lookup terminal |
| FAILED récent, READY ancien | V15 NOT_READY, V14 FAILED, V13 READY | `ProjectionFailed(CURRENT, 14, 15)` | aucun fallback V13 |
| READY sparse | V15 NOT_READY, V13 READY | `Resolved(CURRENT, 13, 15)` | trous acceptés |
| terminal au-dessus de la borne | V16 FAILED, latest-known 15, V13 READY | `Resolved(CURRENT, 13, 15)` | V16 ignoré |
| ancienne génération plus avancée | serving v3 V98 READY, v2 V100 READY | `Resolved(CURRENT, 98, 100)` | v2 jamais consultée |
| serving sans terminal | v3 vide, v2 V100 READY | `NotReady(CURRENT, 100)` | aucun fallback inter-génération |
| ordre de persistance différent | mêmes terminaux insérés hors ordre | résultat identique | ordre non fonctionnel |

### 16.2 `EXACT(V)`

| Cas | État | Résultat attendu | Accès importants |
| --- | --- | --- | --- |
| latest-known absent | EXACT(15) | `NotReady(EXACT(15), empty)` | aucune applicabilité/readiness |
| au-dessus latest-known | EXACT(15), latest-known 14 | `NotReady(EXACT(15), 14)` | aucun `appliesTo`, aucun `statusAt` |
| non applicable | EXACT(15), latest-known 15 | `NotApplicable(EXACT(15), 15)` | aucun `statusAt` |
| READY exact | EXACT(15), V15 READY | `Resolved(EXACT(15), 15, 15)` | `statusAt` serving V15 |
| FAILED exact | EXACT(15), V15 FAILED | `ProjectionFailed(EXACT(15), 15, 15)` | aucun fallback |
| NOT_READY exact | EXACT(15), V15 NOT_READY | `NotReady(EXACT(15), 15)` | aucun fallback |
| ancienne génération READY | serving V15 NOT_READY, ancienne V15 READY | `NotReady(EXACT(15), 15)` | ancienne génération ignorée |

### 16.3 Invariants des résultats

Tester séparément les quatre variants :

- `potId` null refusé ;
- `requestedVersion` null refusée ;
- versions servie, échouée ou latest-known inférieures à 1 refusées ;
- `servedVersion > latestKnownVersion` refusé ;
- `failedVersion > latestKnownVersion` refusé ;
- `Resolved(EXACT(V))` avec `servedVersion != V` refusé ;
- `ProjectionFailed(EXACT(V))` avec `failedVersion != V` refusé ;
- `NotApplicable(CURRENT, ...)` refusé ;
- `NotApplicable(EXACT(V), latestKnown < V)` refusé ;
- `NotReady` accepte `OptionalLong.empty()` ;
- `NotReady` accepte un latest-known positif présent ;
- `NotReady` refuse un `OptionalLong` null ou une valeur présente non positive ;
- value semantics naturelles des records ;
- exactement quatre variants permis.

### 16.4 Resolver et architecture

Les tests du resolver doivent également prouver :

- une seule méthode publique de résolution ;
- construction avec les deux seuls ports read-only ;
- même `PotId`, `ProjectionType`, `PipelineId` et pipelineVersion exacte dans la génération ;
- isolation stricte entre générations ;
- aucun appel readiness lorsque latest-known est absent ;
- aucun appel `statusAt` pour `CURRENT` ;
- aucun appel terminal pour `EXACT` ;
- respect de l'ordre des contrôles EXACT ;
- aucun scan numérique ;
- aucune dépendance à `ProjectionHead`, AUTH, Spring, JPA, HTTP, infrastructure, runtime ou
  processing.

## 17. Risques et pièges

1. **Chercher le plus haut READY** au lieu du plus haut terminal masquerait un `FAILED` récent.
2. **Scanner V par V** réintroduirait une hypothèse de continuité et une complexité dépendante de
   latest-known.
3. **Consulter une ancienne génération** contredirait le caractère autoritatif du serving pour
   l'historique.
4. **Tester l'applicabilité avant latest-known** pourrait révéler un état interne au-delà de la borne
   d'exposition.
5. **Transformer la non-applicabilité en NOT_READY** confondrait absence d'artifact attendu et retard
   de production.
6. **Produire NotApplicable en CURRENT** ajouterait une catégorie que le lookup terminal n'a pas à
   inventer.
7. **Inclure AUTH dans la sélection** recréerait le modèle multi-projection abandonné.
8. **Exposer ProjectionFailure** étendrait 7.9.2 au diagnostic de processing.
9. **Uniformiser latest-known en OptionalLong sur tous les variants** affaiblirait les invariants des
   issues qui exigent une borne existante.
10. **Confondre pipelineVersion et businessVersion** construirait une identité ou un contrôle
    d'applicabilité incorrect.

## 18. Critères de conception fermée

La conception est fermée lorsque les affirmations suivantes sont acceptées comme normatives :

1. le resolver traite exactement une projection métier ;
2. il reçoit la sélection serving et ne la calcule pas ;
3. il expose une seule méthode publique `resolve(...)` ;
4. il dépend uniquement des deux ports read-only 7.9.1 ;
5. il construit une unique `ProjectionGenerationIdentity` serving ;
6. `CURRENT` retourne le plus haut terminal `READY|FAILED` sous latest-known ;
7. un terminal `FAILED` récent masque tout ancien `READY` ;
8. `EXACT(V)` ne lit que le statut exact de V après les contrôles de borne et d'applicabilité ;
9. `NotApplicable` est réservé à `EXACT(V)` ;
10. latest-known absent, EXACT au-dessus de la borne, statut NOT_READY et absence de terminal sont
    tous représentés par `NotReady` ;
11. aucune businessVersion supérieure à latest-known n'est exposée ;
12. aucune autre businessVersion ou pipelineVersion ne sert de fallback ;
13. `ProjectionHead` n'est jamais lu ;
14. aucun scan numérique n'est réalisé ;
15. AUTH est entièrement hors du resolver ;
16. les quatre variants portent `PotId` et l'intention originale ;
17. `OptionalLong latestKnownVersion` existe uniquement dans `NotReady` ;
18. aucune nouvelle dépendance Maven ou framework n'est nécessaire.

## 19. Questions ouvertes / blockers

L'inspection du repository n'a révélé aucune contradiction entre les contrats 7.9.1 livrés, les
frontières de modules et cette conception.

```text
OPEN QUESTIONS / BLOCKERS
NONE
```

## DECISIONS CONFIRMED

- Toute query versionnée porte exactement une projection métier, jamais zéro ni plusieurs.
- AUTH et les policies d'autorisation sont hors du Query Version Resolver.
- Le resolver expose une seule méthode publique `resolve(...)`.
- L'entrée est `PotId + QueryVersionIntent + QueryProjectionSelection`.
- La pipelineVersion serving fournie est autoritative pour les lectures récentes et historiques.
- `CURRENT` choisit le plus haut terminal `READY|FAILED` sous latest-known.
- Un `FAILED` récent n'est jamais masqué par un ancien `READY`.
- `CURRENT` sans terminal et toute résolution sans latest-known produisent `NotReady`.
- `EXACT(V)` au-dessus de latest-known produit `NotReady`.
- `NotApplicable` est réservé à `EXACT(V)` exposable mais hors applicabilité serving.
- `EXACT(V)` n'effectue aucun fallback de businessVersion.
- Aucun fallback inter-pipelineVersion n'existe.
- `PotId` et l'intention originale figurent dans tous les résultats.
- latest-known figure dans tous les résultats lorsqu'il existe ; seul `NotReady` le porte en
  `OptionalLong`.
- Le résultat contient exactement `Resolved`, `ProjectionFailed`, `NotReady` et `NotApplicable`.
- Aucun diagnostic détaillé de failure ou de pipeline n'est exposé.
- `ProjectionHead` n'est pas consulté et aucun scan numérique n'est permis.

## PROPOSED TYPES

```java
package com.kartaguez.pocoma.engine.port.in.query.version;

public sealed interface QueryVersionResolution {

    PotId potId();

    QueryVersionIntent requestedVersion();

    record Resolved(
        PotId potId,
        QueryVersionIntent requestedVersion,
        long servedVersion,
        long latestKnownVersion)
        implements QueryVersionResolution {}

    record ProjectionFailed(
        PotId potId,
        QueryVersionIntent requestedVersion,
        long failedVersion,
        long latestKnownVersion)
        implements QueryVersionResolution {}

    record NotReady(
        PotId potId,
        QueryVersionIntent requestedVersion,
        OptionalLong latestKnownVersion)
        implements QueryVersionResolution {}

    record NotApplicable(
        PotId potId,
        QueryVersionIntent requestedVersion,
        long latestKnownVersion)
        implements QueryVersionResolution {}
}
```

```java
package com.kartaguez.pocoma.engine.service.query.version;

public final class QueryVersionResolver {

    public QueryVersionResolver(
        LatestKnownVersionQueryPort latestKnownVersionQueryPort,
        ProjectionReadinessQueryPort projectionReadinessQueryPort);

    public QueryVersionResolution resolve(
        PotId potId,
        QueryVersionIntent requestedVersion,
        QueryProjectionSelection projectionSelection);
}
```

## ALGORITHMS

### CURRENT

```text
latest-known absent
-> NotReady(CURRENT, empty)

terminal = highest READY|FAILED in serving generation <= latest-known

terminal absent
-> NotReady(CURRENT, latest-known)

terminal READY(V)
-> Resolved(CURRENT, V, latest-known)

terminal FAILED(V)
-> ProjectionFailed(CURRENT, V, latest-known)
```

### EXACT(V)

```text
latest-known absent
-> NotReady(EXACT(V), empty)

V > latest-known
-> NotReady(EXACT(V), latest-known)

serving pipeline not applicable to V
-> NotApplicable(EXACT(V), latest-known)

statusAt(serving generation, V) == READY
-> Resolved(EXACT(V), V, latest-known)

statusAt(serving generation, V) == FAILED
-> ProjectionFailed(EXACT(V), V, latest-known)

statusAt(serving generation, V) == NOT_READY
-> NotReady(EXACT(V), latest-known)
```

## TEST MATRIX

- CURRENT : latest-known absent, aucun terminal, READY/FAILED à la borne, trous, FAILED récent avec
  READY ancien, terminal au-dessus de la borne, génération serving isolée des anciennes générations.
- EXACT : latest-known absent, V au-dessus de la borne, non-applicabilité, READY, FAILED, NOT_READY,
  ancienne génération ignorée et ordre de contrôle vérifié.
- Résultats : nulls, versions invalides, incohérences avec latest-known ou EXACT, asymétrie de
  latest-known et exactement quatre variants.
- Architecture : deux ports seulement, aucune dépendance interdite, aucun head, aucun scan numérique,
  aucun AUTH et aucune sélection serving interne.

## OPEN QUESTIONS / BLOCKERS

```text
NONE
```
