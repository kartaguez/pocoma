# Lot 7.9.2 — Conception de la résolution CURRENT / EXACT du Query Kernel

> **SUPERSEDED — repose sur l'ancien modèle multi-projection.**
>
> Ne pas utiliser ce document pour implémenter 7.9.2. Une nouvelle conception monoprojection sera
> produite après audit des contrats 7.9.1 révisés. Le contenu ci-dessous est conservé uniquement comme
> trace de la décision antérieure.

Statut : **SUPERSEDED**.

Ce document est la référence intermédiaire entre l’architecture cible du read side, les contrats livrés par le Lot 7.9.1 et le futur plan d’implémentation du Lot 7.9.2. Il décrit les décisions fonctionnelles déjà fermées, puis les choix de conception proposés. Il ne constitue ni un plan d’implémentation ni une implémentation.

## 1. Contexte et autorité

Les sources relues pour cette conception sont, par ordre d’autorité dans leur domaine :

- `docs/architecture/read-side-target.md` pour la cible normative du read side ;
- `docs/plans/lot-7-read-side-implementation-plan.md` pour le périmètre et les dépendances du Lot 7 ;
- `docs/architecture/read-side-current-state.md` pour l’état réellement livré ;
- `docs/plans/lot-7.9.1-versioned-query-contracts-plan.md` pour les contrats préparatoires ;
- `docs/architecture/type-ownership.md` et `docs/architecture/module-dependency-matrix.md` pour l’ownership et les dépendances de modules.

Le code du Lot 7.9.1 confirme les contrats suivants dans `engine-query` :

- `QueryVersionIntent` distingue `Current` et `Exact(long businessVersion)` ;
- `QueryViewDefinition` distingue les vues protégées et non protégées et expose `requiredComponents()` ;
- `QueryPipelineSelection` fournit explicitement une `PipelineVersionDefinition` par `ProjectionType` requis et autorise une sélection vide ;
- `ProjectionReadinessQueryPort` expose une recherche du plus haut `READY` sous une borne et le statut d’une `ProjectionIdentity` exacte ;
- `LatestKnownVersionQueryPort` lit la borne d’exposition d’un Pot ;
- `VersionedQueryResponse<T>` est l’enveloppe d’une réponse réussie, pas le résultat du resolver.

Les types existants ont les responsabilités suivantes :

```text
ProjectionGenerationIdentity
= ProjectionType + PipelineDefinition + PotId

ProjectionIdentity
= ProjectionGenerationIdentity + businessVersion

PipelineVersionDefinition
= PipelineDefinition + VersionApplicability
```

`PipelineDefinition.pipelineVersion` est une **pipelineVersion**. `ProjectionIdentity.potVersion` et les versions résolues sont des **businessVersions**. Elles ne sont jamais interchangeables.

## 2. Objectif

Le Lot 7.9.2 doit fournir un resolver framework-free réalisant :

```text
PotId
+ QueryVersionIntent
+ QueryViewDefinition
+ QueryPipelineSelection
+ LatestKnownVersionQueryPort
+ ProjectionReadinessQueryPort
→ QueryVersionResolution
```

Il répond uniquement à la question :

> Quelle businessVersion peut être servie, ou pour quelle cause typée aucune businessVersion ne peut-elle être résolue ?

Il produit une décision de version. Il ne produit pas encore de `VersionedQueryResponse<T>`, car il ne lit aucune donnée et ne possède ni `data` ni l’instant final de génération de la réponse.

## 3. Non-objectifs

Le Lot 7.9.2 ne doit pas :

- lire ou composer les données métier ;
- lire le contenu d’un artifact `AUTH(V)` ni évaluer une permission ;
- construire la réponse HTTP ou masquer les informations pour un client ;
- créer `TokenCapabilities` ou une policy d’autorisation ;
- choisir la pipelineVersion serving ;
- implémenter le lifecycle `declared / active / serving` ;
- lire `ProjectionHead`, des Tasks, Slots, Claims, leases ou tout état de processing ;
- créer un adapter de persistence ;
- créer `AUTH(V)` ;
- modifier les GET existants ;
- charger en mémoire l’ensemble de l’historique des versions `READY` ;
- exposer les diagnostics internes comme un contrat HTTP ;
- introduire un registry global de composants ou de producteurs ;
- dépendre de Spring, JPA, HTTP, d’un runtime, d’une infrastructure ou d’un moteur de processing.

La séquence de sécurité, la lecture d’`AUTH(servedVersion)` et la lecture/composition des données appartiennent au Lot 7.9.3.

## 4. Invariants structurants

### 4.1 Versions et exposition

- Les Events et Tasks peuvent être traités hors ordre.
- `ProjectionHead` ne prouve aucune continuité et n’est pas une entrée du resolver.
- `pipelineVersion != businessVersion`.
- `latestKnownVersion` ne bloque jamais la production.
- L’exposition ne sert jamais une businessVersion supérieure à `latestKnownVersion`.
- L’absence de latest-known produit `LATEST_KNOWN_ABSENT` pour `CURRENT` comme pour `EXACT`.
- `EXACT(V)` ne fallback jamais vers une autre businessVersion.
- `CURRENT` résout une seule businessVersion commune à tous les composants requis.

### 4.2 Vues protégées et non protégées

Pour une vue protégée, `requiredComponents()` contient le composant d’autorisation et les composants métier. La readiness d’`AUTH(V)` participe donc à la résolution exactement comme celle des autres artifacts.

Le contenu des droits d’`AUTH(V)` ne participe jamais à la recherche. Après résolution, le Lot 7.9.3 décidera depuis `AUTH(servedVersion)` ; un refus sera terminal et n’entraînera aucun fallback vers une version plus ancienne.

Pour une vue non protégée, `requiredComponents()` ne contient que les composants métier. Le resolver n’impose ni AUTH ni `TokenCapabilities`.

Pour `QueryViewDefinition.unprotectedView(Set.of())`, aucun lookup de readiness n’est requis :

- `CURRENT` résout `latestKnownVersion` lorsqu’elle existe ;
- `EXACT(V)` résout `V` si `V <= latestKnownVersion` ;
- `servedVersion` situe alors la réponse sous la borne d’exposition sans prétendre qu’un artifact existe à cette version.

Une vue protégée AUTH-only n’est pas vide : son ensemble requis contient AUTH.

### 4.3 Applicabilité, readiness et configuration

- Une pipeline non applicable à V n’est pas `NOT_READY` à V : aucun artifact n’est attendu pour cette génération à V.
- Une sélection ne contenant pas un composant requis est une erreur de configuration/programmation. Le resolver échoue immédiatement via `QueryPipelineSelection.requireFor(...)` ; il ne traduit pas cette erreur en résultat fonctionnel.
- Une version `FAILED`, `NOT_READY` ou non applicable n’empêche jamais `CURRENT` de trouver une version servable plus ancienne.

## 5. API proposée

### 5.1 Resolver

Nom recommandé : `QueryVersionResolver`.

Package recommandé :

```text
com.kartaguez.pocoma.engine.service.query.version
```

Signature publique proposée :

```java
public final class QueryVersionResolver {

    public QueryVersionResolver(
        LatestKnownVersionQueryPort latestKnownVersionQueryPort,
        ProjectionReadinessQueryPort projectionReadinessQueryPort);

    public QueryVersionResolution resolve(
        PotId potId,
        QueryVersionIntent intent,
        QueryViewDefinition view,
        QueryPipelineSelection pipelineSelection);
}
```

Le nom `Resolver` décrit mieux sa responsabilité qu’un use case public : ce service est une brique interne du futur Query Kernel, appelée ensuite par la composition 7.9.3.

Le resolver est stateless. Il reçoit les deux ports read-only par construction et tous les éléments propres à une requête dans `resolve(...)`. Il ne reçoit ni Clock, ni reader métier, ni policy d’autorisation.

### 5.2 Prévalidation structurelle commune

Avant toute lecture d’état fonctionnel, le resolver :

1. refuse les arguments nuls ;
2. obtient `requiredComponents()` ;
3. appelle `pipelineSelection.requireFor(component)` pour chaque composant requis ;
4. construit les contextes de génération avec le même `PotId`.

Cette étape est indépendante de CURRENT/EXACT et précède la lecture latest-known. Elle garantit que l’erreur de configuration est immédiate et ne dépend pas de l’état courant du Pot. Pour une vue non protégée vide, elle n’effectue naturellement aucune recherche de pipeline.

Chaque contexte interne contient seulement :

```text
ProjectionType
PipelineVersionDefinition fournie
ProjectionGenerationIdentity correspondante
```

Il ne constitue pas un nouveau contrat public ni un registry.

## 6. Résultat typé

### 6.1 Forme recommandée

Créer un contrat scellé dans :

```text
module  : engine-query
package : com.kartaguez.pocoma.engine.port.in.query.version
type    : QueryVersionResolution
```

Forme conceptuelle précise :

```java
public sealed interface QueryVersionResolution {

    PotId potId();

    record Resolved(
        PotId potId,
        long servedVersion,
        long latestKnownVersion)
        implements QueryVersionResolution {}

    record LatestKnownAbsent(
        PotId potId)
        implements QueryVersionResolution {}

    record ExactAboveLatestKnown(
        PotId potId,
        long requestedVersion,
        long latestKnownVersion)
        implements QueryVersionResolution {}

    record ProjectionFailed(
        PotId potId,
        long latestKnownVersion,
        long businessVersion,
        Set<ProjectionFailureDetail> failures)
        implements QueryVersionResolution {}

    record PipelineNotApplicable(
        PotId potId,
        long latestKnownVersion,
        long businessVersion,
        Set<PipelineNotApplicableDetail> details)
        implements QueryVersionResolution {}

    record NoCommonReadyVersion(
        PotId potId,
        long latestKnownVersion)
        implements QueryVersionResolution {}

    record ProjectionFailureDetail(
        ProjectionType projectionType,
        PipelineDefinition pipeline,
        long businessVersion) {}

    record PipelineNotApplicableDetail(
        ProjectionType projectionType,
        PipelineVersionDefinition pipeline,
        long businessVersion) {}
}
```

Les records imbriqués limitent la surface de fichiers et gardent les diagnostics attachés au résultat qui les porte. Ils ne changent pas l’ownership des types domaine réutilisés.

### 6.2 Validations locales

Tous les records refusent les références nulles et toute version inférieure à 1.

Validations supplémentaires :

- `Resolved` exige `servedVersion <= latestKnownVersion` ;
- `ExactAboveLatestKnown` exige `requestedVersion > latestKnownVersion` ;
- les résultats diagnostiques exigent `businessVersion <= latestKnownVersion` ;
- `failures` et `details` sont non vides, sans null, copiés défensivement et immuables ;
- chaque détail porte la même `businessVersion` que le résultat global ;
- un diagnostic ne contient pas deux détails pour le même `ProjectionType` ;
- `ProjectionFailureDetail` porte la `PipelineDefinition` productrice, sans format d’erreur d’infrastructure ;
- `PipelineNotApplicableDetail` porte la `PipelineVersionDefinition` complète, puisque `VersionApplicability` fait partie du diagnostic.

Le `PotId` est présent une seule fois au niveau global. `NoCommonReadyVersion` reste volontairement léger et ne liste pas les versions `NOT_READY`.

## 7. Algorithme EXACT

Après la prévalidation structurelle décrite en 5.2 :

1. lire latest-known pour le Pot ;
2. si elle est absente, retourner `LatestKnownAbsent(potId)` ;
3. si la version exacte demandée est supérieure à latest-known, retourner `ExactAboveLatestKnown` ;
4. si `requiredComponents()` est vide, retourner `Resolved(potId, requestedVersion, latestKnownVersion)` sans lookup readiness ;
5. évaluer `PipelineVersionDefinition.appliesTo(requestedVersion)` pour tous les composants ;
6. si au moins une pipeline est non applicable, retourner un unique `PipelineNotApplicable` contenant tous les composants non applicables à V ;
7. construire les `ProjectionIdentity` exactes et lire tous les `statusAt(...)` ;
8. si au moins un statut est `FAILED`, retourner un unique `ProjectionFailed` contenant tous les composants `FAILED` à V ;
9. sinon, si au moins un statut est `NOT_READY`, retourner `NoCommonReadyVersion` ;
10. sinon tous les composants sont `READY` : retourner `Resolved` à V.

L’ordre applicabilité puis statuts n’affaiblit pas la priorité `PROJECTION_FAILED > PIPELINE_NOT_APPLICABLE` : une version comportant une pipeline non applicable ne satisfait pas la définition d’une version terminalement bloquée par projection, laquelle exige que toutes les pipelines soient applicables.

EXACT effectue au plus une lecture latest-known et un `statusAt` par composant applicable. Il n’appelle jamais `findHighestReadyBusinessVersionAtOrBelow`.

## 8. Algorithme CURRENT

CURRENT comporte deux phases conceptuellement distinctes :

1. recherche optimisée de la plus haute version servable ;
2. uniquement en l’absence de succès, classification canonique indépendante du parcours.

### 8.1 Recherche d’un succès

Après prévalidation :

1. lire latest-known ; si absente, retourner `LatestKnownAbsent` ;
2. si aucun composant n’est requis, retourner `Resolved` à latest-known sans lookup readiness ;
3. choisir un composant d’ancrage parmi les composants requis ;
4. appeler `findHighestReadyBusinessVersionAtOrBelow(anchorGeneration, upperBound)` avec `upperBound = latestKnownVersion` ;
5. pour la candidate V retournée, vérifier l’applicabilité de **toutes** les pipelines requises ;
6. si toutes sont applicables, lire les statuts exacts de tous les composants à V ;
7. si tous sont `READY`, retourner `Resolved(V)` ;
8. sinon, reprendre la recherche de l’ancre strictement sous V, avec la borne `V - 1` ;
9. si l’ancre n’a plus de version READY sous la borne, passer à la classification finale.

Cette stratégie ne suppose aucun trou rempli et ne synthétise jamais V-1 : `V - 1` est seulement la nouvelle **borne exclusive traduite en borne inclusive**, et le port retourne la prochaine version réellement `READY`, par exemple 15 puis 13 puis 8.

La première candidate servable trouvée est la plus haute version commune servable : toute version servable doit avoir l’ancre `READY`, et les versions réellement `READY` de l’ancre sont visitées en ordre décroissant.

### 8.2 Choix de l’ancre

Le choix de l’ancre est une optimisation interne. Une implémentation simple peut prendre le premier composant selon un ordre canonique stable de `ProjectionType`, mais cet ordre ne devient pas une règle fonctionnelle.

La preuve précédente vaut pour n’importe quel composant requis. Par conséquent, changer l’ancre peut changer le nombre d’appels aux ports, jamais :

- la `servedVersion` ;
- la catégorie finale ;
- la businessVersion diagnostique ;
- les détails retournés à cette version.

Pour rendre cette propriété testable sans exposer une stratégie dans l’API publique, la conception recommande d’isoler la recherche CURRENT dans un collaborateur package-private ou une fonction pure package-private qui accepte explicitement le contexte d’ancrage. Les tests peuvent alors exécuter le même état avec chaque ancre. Ce seam reste interne à `engine-query` et n’est ni un registry ni un mécanisme de configuration.

## 9. Classification des échecs

### 9.1 Ordre canonique

Si aucune version servable n’existe, la catégorie est choisie selon :

```text
PROJECTION_FAILED
> PIPELINE_NOT_APPLICABLE
> NO_COMMON_READY_VERSION
```

Les erreurs structurelles de sélection ont déjà échoué avant cette classification. `LATEST_KNOWN_ABSENT` et `EXACT_ABOVE_LATEST_KNOWN` sont traitées avant l’exploration fonctionnelle, après la prévalidation structurelle.

La priorité s’applique d’abord à la catégorie, puis à la version : dans la catégorie gagnante, le diagnostic porte la plus haute businessVersion qui la matérialise.

### 9.2 Version terminalement FAILED

Une businessVersion V matérialise `PROJECTION_FAILED` si et seulement si :

```text
toutes les pipelines requises sont applicables à V
ET
tous les statuts exacts sont dans {READY, FAILED}
ET
au moins un statut est FAILED
```

Ainsi :

```text
READ_POT FAILED + AUTH READY     → PROJECTION_FAILED à V
READ_POT FAILED + AUTH NOT_READY → pas de PROJECTION_FAILED à V
```

Un `FAILED` récent n’interrompt jamais la recherche d’un succès plus ancien. La catégorie `PROJECTION_FAILED` n’est produite qu’après avoir prouvé qu’aucune version servable n’existe.

À la version canonique retenue, le résultat contient tous les composants `FAILED`, mais aucune failure d’une autre version.

### 9.3 Pipeline non applicable

Une businessVersion V matérialise la catégorie `PIPELINE_NOT_APPLICABLE` lorsqu’au moins une pipeline requise n’est pas applicable à V. Le diagnostic canonique contient toutes les pipelines non applicables à la plus haute V matérialisant cette catégorie, jamais l’historique complet.

La structure actuelle de `VersionApplicability` est un intervalle contigu. La plus haute version non applicable sous latest-known peut donc être calculée depuis les bornes des définitions, sans transformer cette absence d’applicabilité en statut et sans interroger la persistence. Les détails sont ensuite recomputés exhaustivement à cette seule version.

Cette catégorie n’est retenue que si aucune version servable et aucune version `PROJECTION_FAILED` pertinente n’existent.

### 9.4 Absence de cause plus prioritaire

Si aucune version n’est servable, qu’aucune version ne matérialise `PROJECTION_FAILED` et qu’aucune version ne matérialise `PIPELINE_NOT_APPLICABLE`, le résultat est `NoCommonReadyVersion(potId, latestKnownVersion)`.

Il ne contient pas d’inventaire de statuts : une telle exhaustivité serait coûteuse, instable et inutile au contrat.

## 10. Applicabilité

Pour chaque candidate V, l’ordre obligatoire est :

```text
PipelineVersionDefinition.appliesTo(V)
  false → génération non candidate à V ; aucun statusAt pour cet artifact
  true  → construire ProjectionIdentity puis consulter statusAt
```

La `ProjectionGenerationIdentity` utilise `PipelineVersionDefinition.identity()`, donc la `PipelineDefinition` exacte fournie par la sélection serving. Le resolver ne compare pas les pipelineVersions, ne cherche pas un `MAX` et ne substitue jamais une autre génération lorsqu’une définition n’est pas applicable.

Pour EXACT, toute non-applicabilité à V produit immédiatement la catégorie dédiée avec l’ensemble des détails à V. Pour CURRENT, elle élimine seulement la candidate de la recherche de succès ; une version plus ancienne peut rester servable.

## 11. Indépendance du parcours

Le résultat ne doit pas être dérivé des seuls états rencontrés pendant la recherche optimisée. La conception impose les propriétés suivantes :

1. **Prévalidation exhaustive des composants requis** : une sélection incomplète échoue indépendamment de l’ordre.
2. **Recherche de succès complète sur l’ancre** : toute version servable appartient à l’ensemble READY de chaque ancre possible.
3. **Relecture exhaustive de la candidate** : à V, tous les composants sont contrôlés ; aucune décision ne dépend du premier statut rencontré.
4. **Classification séparée** : si la recherche échoue, les observations accidentelles de cette recherche sont ignorées comme source de vérité du diagnostic.
5. **Catégorie puis version** : la classification cherche d’abord toute occurrence de la catégorie la plus prioritaire, puis retient sa version maximale.
6. **Diagnostic recomputé à V** : tous les détails de la version canonique, et seulement eux, sont collectés.
7. **Collections sans ordre fonctionnel** : les sorties diagnostics sont des ensembles à value semantics ; les tests comparent leur contenu, pas leur ordre.

Ces règles donnent, pour les mêmes ports et entrées, le même résultat quelle que soit l’ancre, l’ordre des composants ou l’ordre d’insertion dans les fakes/adapters.

## 12. Complexité et accès aux ports

Soient :

- `C` le nombre de composants requis ;
- `R` le nombre de versions READY de l’ancre visitées avant succès ou épuisement ;
- `L` la valeur numérique de latest-known ;
- `D` le nombre de versions terminalement matérialisées (`READY` ou `FAILED`) d’une génération sous la borne.

### 12.1 Chemins sans ambiguïté de contrat

- vue vide : une lecture latest-known, aucun lookup readiness ;
- EXACT : une lecture latest-known et au plus `C` appels `statusAt`, soit `O(C)` ;
- recherche de succès CURRENT : `R + 1` recherches descendantes au maximum et au plus `R × C` lectures exactes, mémoire `O(C)` ;
- diagnostic de non-applicabilité : calcul sur les `C` intervalles puis détails à une version, `O(C)` et aucun accès readiness nécessaire.

Le resolver ne charge jamais un `Set<Long>` de tout l’historique READY et ne lit jamais de head.

### 12.2 Limite factuelle du port 7.9.1

Le port livré sait énumérer implicitement les versions `READY` d’une génération, mais pas ses versions `FAILED` :

```java
OptionalLong findHighestReadyBusinessVersionAtOrBelow(...);
ProjectionStatus statusAt(ProjectionIdentity identity);
```

Or une version canonique `PROJECTION_FAILED` peut avoir tous ses composants `FAILED`. Elle n’apparaît alors dans la recherche READY d’aucune ancre. Avec le seul contrat actuel, la seule méthode générale correcte pour prouver l’existence ou l’absence de la plus haute version terminalement FAILED consiste à sonder les businessVersions une par une de latest-known jusqu’à 1 avec `statusAt`.

Cette solution de référence serait déterministe et n’exposerait pas tout l’historique en mémoire, mais son coût maximal serait `O(L × C)` appels. Elle parcourt potentiellement toute la plage numérique, y compris les trous, ce qui est contraire à l’objectif opérationnel de recherche bornée efficace qui a motivé le port 7.9.1.

Une extension minimale possible serait une opération descendante sur les versions **terminalement matérialisées**, sans retourner leur ensemble :

```java
OptionalLong findHighestDeterminedBusinessVersionAtOrBelow(
    ProjectionGenerationIdentity generation,
    long upperBoundInclusive);
```

Ici, `determined` signifie exactement `status ∈ {READY, FAILED}`. Cela ne suppose aucune continuité. Une candidate `PROJECTION_FAILED` étant déterminée pour tous les composants, elle figure nécessairement dans la suite descendante de n’importe quelle ancre ; le resolver peut la vérifier exactement comme il vérifie une candidate READY. La complexité devient `O(D × C)` dans le pire cas, sans matérialiser l’historique.

Cette extension modifierait un contrat livré en 7.9.1. Elle n’est donc **pas décidée ni appliquée dans cette passe**. L’arbitrage entre cette extension et l’acceptation explicite du scan numérique exhaustif est le blocker décrit en section 16.

## 13. Placement modules et packages

| Élément proposé | Module | Package | Responsabilité | Dépendances |
| --- | --- | --- | --- | --- |
| `QueryVersionResolver` | `engine-query` | `com.kartaguez.pocoma.engine.service.query.version` | Orchestrer latest-known, applicabilité et readiness | ports 7.9.1, types domain Pot/projection/pipeline |
| `QueryVersionResolution` et variantes | `engine-query` | `com.kartaguez.pocoma.engine.port.in.query.version` | Résultat framework-free consommable par 7.9.3 | `PotId`, `ProjectionType`, `PipelineDefinition`, `PipelineVersionDefinition` |
| collaborateur CURRENT package-private éventuel | `engine-query` | `com.kartaguez.pocoma.engine.service.query.version` | Isoler et tester recherche/ancre sans API publique | mêmes contrats que le resolver |

Le graphe reste :

```text
engine-query
→ domain-projection
→ domain-pipeline
→ domain (PotId, selon le graphe Maven existant)
```

Aucune dépendance vers `engine-read-projection`, un runtime, un module de processing, Spring, JPA ou HTTP n’est nécessaire.

## 14. Stratégie de tests

Les tests doivent utiliser des fakes in-memory des deux ports, sans adapter de production et sans head.

### 14.1 Validation et structure

- arguments nuls refusés ;
- sélection pipeline incomplète : exception explicite avant lecture latest-known/readiness ;
- invariants de chaque variante du résultat ;
- copies défensives, immutabilité et détails non dupliqués ;
- distinction stricte entre pipelineVersion et businessVersion dans les fixtures.

### 14.2 EXACT

- latest-known absent ;
- version demandée au-dessus de latest-known ;
- tous les composants READY ;
- un ou plusieurs composants FAILED, tous retournés ;
- composant NOT_READY ;
- une ou plusieurs pipelines non applicables, toutes retournées ;
- aucun fallback vers une autre businessVersion ;
- vue protégée AUTH-only ;
- vue non protégée avec composant métier ;
- vue non protégée vide résolue sous borne sans appel readiness.

### 14.3 CURRENT — succès

- un composant simple ;
- intersection sparse multi-composants ;
- FAILED récent puis version commune READY plus ancienne ;
- non-applicabilité récente puis version commune READY plus ancienne ;
- sélection systématique de la plus haute version réellement servable ;
- vue protégée AUTH-only ;
- vue non protégée avec composant métier ;
- vue non protégée vide : `latestKnown=15 → Resolved(15)` sans lookup readiness ;
- latest-known absent.

### 14.4 CURRENT — classification finale

- seulement des situations NOT_READY → `NO_COMMON_READY_VERSION` ;
- `FAILED + READY` à la version canonique → `PROJECTION_FAILED` ;
- `FAILED + NOT_READY` ne matérialise pas cette catégorie ;
- plusieurs versions FAILED : seule la plus haute de la catégorie et tous ses failures ;
- présence simultanée d’une cause FAILED et d’une cause non applicable : priorité FAILED ;
- aucune cause FAILED mais plusieurs versions non applicables : plus haute version de cette catégorie et tous ses détails ;
- diagnostics bornés à une seule businessVersion.

### 14.5 Indépendance du parcours

Exécuter les mêmes scénarios avec :

- ordre inverse des composants ;
- chaque composant utilisé comme ancre ;
- ordres d’insertion différents dans les maps des fakes ;
- statuses identiques mais parcours internes différents.

Les assertions portent sur la variante, la businessVersion canonique et l’ensemble complet des détails. Elles doivent être strictement identiques.

Les tests de ports 7.9.1 restent des tests de contrat ; les tests du resolver vérifient l’orchestration sans inventer d’algorithme dans les fakes.

## 15. Risques et pièges à éviter

- **Confondre succès et diagnostic** : les versions rencontrées par l’ancre ne suffisent pas à classifier un échec global.
- **Arrêter CURRENT sur FAILED** : un succès plus ancien doit encore être recherché.
- **Assimiler non-applicable à NOT_READY** : l’applicabilité doit être vérifiée avant `statusAt`.
- **Rendre l’ancre fonctionnelle** : une optimisation ne doit pas affecter la sortie.
- **Dépendre de l’ordre d’un Set/Map** : toute collecte à une version doit être exhaustive.
- **Supposer V-1 présent** : V-1 est une borne de reprise, jamais une version synthétisée.
- **Utiliser `ProjectionHead`** : il ne prouve ni readiness exacte ni continuité.
- **Retourner le premier échec observé** : la catégorie et la version doivent être canoniques.
- **Confondre readiness AUTH et droits AUTH** : seul le statut de l’artifact intervient en 7.9.2.
- **Transformer un résultat interne en mapping HTTP** : cette responsabilité vient plus tard.
- **Masquer une sélection incomplète en NOT_READY** : il s’agit d’une erreur de programmation.
- **Accepter implicitement un scan O(latestKnown)** : ce coût doit être décidé explicitement si le port n’est pas étendu.

## 16. Questions ouvertes / blockers

### BLOCKER — découverte canonique des versions PROJECTION_FAILED

**Contradiction exacte**

La sémantique demandée exige de trouver la plus haute version `PROJECTION_FAILED`, y compris lorsqu’aucun composant n’est READY à cette version, tout en préservant une recherche descendante bornée qui ne parcourt pas toute la plage historique. Le port 7.9.1 permet de rechercher seulement la plus haute version READY ; `statusAt` exige de connaître préalablement la businessVersion à sonder.

**Fichiers concernés**

- `app/engine-query/src/main/java/com/kartaguez/pocoma/engine/port/out/query/ProjectionReadinessQueryPort.java` ;
- `docs/plans/lot-7.9.1-versioned-query-contracts-plan.md` ;
- le futur plan et l’implémentation 7.9.2.

**Options possibles**

1. conserver le port et accepter/documenter un scan décroissant de chaque businessVersion jusqu’à 1 pour la classification finale (`O(latestKnown × composants)`) ;
2. amender minimalement le port avec une recherche descendante de la plus haute version dont le statut est terminal (`READY` ou `FAILED`), puis conserver `statusAt` pour la vérification exacte multi-composants.

**Recommandation**

Choisir l’option 2 avant de rédiger le plan d’implémentation. Elle conserve les trous, l’absence de continuité, les diagnostics bornés et l’indépendance de l’ancre, tout en évitant de scanner chaque entier historique. Elle constitue toutefois une modification explicite du contrat 7.9.1 et nécessite donc un arbitrage préalable.

## DECISIONS CONFIRMED

- latest-known est une borne d’exposition obligatoire et ne bloque jamais la production ;
- CURRENT cherche la plus haute businessVersion commune applicable et READY ;
- EXACT ne fallback jamais ;
- AUTH intervient uniquement pour une vue protégée et seulement par sa readiness en 7.9.2 ;
- une vue non protégée vide se résout directement sous latest-known sans lookup readiness ;
- non-applicable, NOT_READY, FAILED et erreur de configuration restent distincts ;
- la priorité finale est `PROJECTION_FAILED > PIPELINE_NOT_APPLICABLE > NO_COMMON_READY_VERSION` ;
- le diagnostic est exhaustif à une seule version canonique, jamais sur tout l’historique ;
- l’ancre et l’ordre de parcours ne doivent pas modifier le résultat ;
- la pipelineVersion serving est fournie et n’est jamais choisie par le resolver.

## DESIGN PROPOSALS

- service stateless `QueryVersionResolver` dans `engine-query` ;
- sealed result `QueryVersionResolution` avec six variantes et diagnostics à value semantics ;
- prévalidation structurelle avant toute résolution fonctionnelle ;
- EXACT en une évaluation exacte de l’applicabilité puis des statuts ;
- CURRENT en deux phases : recherche optimisée du succès, puis classification canonique indépendante ;
- ancre injectable uniquement par un seam package-private de test, sans stratégie publique ;
- calcul analytique du diagnostic de non-applicabilité depuis `VersionApplicability` ;
- extension minimale recommandée du port pour parcourir les versions terminalement déterminées.

## OPEN QUESTIONS / BLOCKERS

- Arbitrer entre l’extension descendante `READY ou FAILED` du port 7.9.1 et l’acceptation explicite d’un scan numérique exhaustif pour classifier `PROJECTION_FAILED`.
