# EPT — Event → ProjectionTask

Ce document est la référence architecturale active du step EPT. Il décrit l'état cible de la
frontière `Event → ProjectionTask`. L'avancement et le séquencement de réalisation sont suivis dans
[`Step_Plan.md`](Step_Plan.md).

## 1. Purpose

EPT transforme un `BusinessEvent` durable en demandes canoniques de projections :

```text
Business Event durable
    ↓
décision transverse EventType → ProjectionType
    ↓
Consumption indépendante EventId × ProjectionType
    ↓
ProjectionTask canonique
```

Cette chaîne est la frontière entre les Events métier écrits dans `business_event_outbox` et le
Projection Engine générique déjà livré. Elle décide quelles projections sont dues et matérialise
les `ProjectionTask`; elle ne calcule ni ne publie elle-même les projections.

## 2. Scope

EPT comprend :

- l'identité `EventType` canonique et sa persistence ;
- la policy exhaustive `EventType → Set<ProjectionType>` ;
- une discovery fondée uniquement sur les métadonnées durables de l'Event ;
- une Consumption indépendante pour chaque couple `EventId × ProjectionType` ;
- la création idempotente d'une `ProjectionTask` par `ProjectionKey` ;
- le fencing de l'effet durable et son atomicité avec la terminaison du Claim et du slot ;
- des workers configurables pour un ou plusieurs `ProjectionType` ;
- le cutover du runtime Event vers cette chaîne ;
- les preuves PostgreSQL et E2E nécessaires à son autorité.

Sont hors scope :

- le producer `AUTH` ;
- `ProjectionTask → Projection`, déjà pris en charge par le Projection Engine ;
- une refonte de Consumption, de ses slots, Claims, leases ou règles de fencing ;
- un DAG, un ordre entre versions ou une dépendance à la version précédente ;
- la résolution `latest` ou `CURRENT` ;
- la topologie Kubernetes et le load testing ;
- la suppression générale du legacy avant preuve et cutover du nouveau chemin.

## 3. Canonical flow

```text
BusinessEvent
    ↓
business_event_outbox
    ↓
metadata-only discovery
    ↓
ProjectionMaterializationPolicy
    ↓
candidate EventId × ProjectionType
    ↓
Acquire Consumption
    ↓
fenced finalization
    ↓
ProjectionTaskStore.ensure(ProjectionKey)
    ↓
ProjectionTask canonique
```

La chaîne aval reste extérieure à EPT :

```text
ProjectionTask
    → generic Projection Engine
    → Projection
```

## 4. Canonical identities

L'envelope canonique d'un Event expose directement :

```text
eventId
eventType
targetObjectType
targetObjectId
targetVersion
recordedAt
```

Pour Pocoma aujourd'hui :

```text
targetObjectType = POT
targetObjectId   = event.potId()
targetVersion    = event.version()
```

Un Event portant un `expenseId` reste donc ciblé sur le Pot pour EPT. L'identifiant de l'Expense
n'entre pas dans la `ProjectionKey`.

L'identité de consommation est :

```text
ConsumableIdentity
    type       = EVENT
    components = [eventId]

ConsumerIdentity
    type       = PROJECTION_TASK_MATERIALIZER
    components = [projectionType]
```

L'unité fonctionnelle est exactement `EventId × ProjectionType`. `EventType`, l'objet cible et la
version ne sont pas des composants supplémentaires de la `ConsumptionKey`.

L'effet durable dérive mécaniquement :

```text
ProjectionKey(
    projectionType,
    event.targetObjectType,
    event.targetObjectId,
    event.targetVersion
)
```

Une `ProjectionTask` a exactement l'identité sémantique de cette `ProjectionKey`. L'identifiant de
ligne de `projection_tasks` reste technique.

## 5. EventType invariant

`EventType` est une identité sémantique stable, indépendante de toute représentation Java :

- `BusinessEvent` expose explicitement `eventType()` ;
- `PocomaEventTypes` est le catalogue exhaustif actuel des dix Events Pot ;
- chaque Event retourne une constante explicite de ce catalogue ;
- `business_event_outbox.event_type` persiste la valeur canonique ;
- le payload legacy dupliqué, lorsqu'il contient `eventType`, porte la même valeur ;
- les anciens noms de classes connus sont migrés vers les valeurs canoniques ;
- une propriété historique explicite incohérente, JSON null ou non textuelle fait échouer la
  migration ;
- l'absence historique de `payload_json.eventType` reste acceptée et n'est pas réparée ;
- aucun fallback vers `getSimpleName()` n'est autorisé pour l'identité fonctionnelle ou durable.

EPT.1 a déjà implémenté cet invariant. Le nom futur d'une classe Java peut changer sans changer
l'identité persistée de l'Event.

## 6. ProjectionMaterializationPolicy

La policy transverse canonique possède seule la décision :

> Quelles projections doivent être demandées en conséquence de cet EventType ?

Elle expose une table explicite :

```text
EventType → Set<ProjectionType>
```

Elle est immutable, indépendante des workers, du SQL, de Consumption et des producers du
Projection Engine. Son domaine est exactement égal au catalogue connu : un type manquant ou
inconnu est une erreur de configuration. Un type connu qui ne produit rien doit être déclaré avec
un ensemble vide.

La déclaration Pocoma initiale est :

```text
chacun des 10 PocomaEventTypes
    → { READ_POT, POT_BALANCES }
```

`AUTH` n'est pas activé par EPT. Modifier ultérieurement la policy rend les Events historiques
concernés naturellement découvrables tant que leur nouvelle Consumption n'est pas `DONE`; la
policy n'est ni persistée ni versionnée.

## 7. Discovery invariants

La discovery :

- lit uniquement `eventId`, `eventType`, cible, version, instant d'enregistrement et information
  de segmentation ;
- ne charge ni ne désérialise `payload_json` ;
- ne dépend d'aucune pipeline, generation ou stratégie de création legacy ;
- ne crée aucun `ConsumptionSlot` ;
- exclut uniquement un couple `EventId × ProjectionType` dont le slot canonique est déjà `DONE` ;
- ne ferme jamais les autres conséquences du même Event ;
- conserve la segmentation opérationnelle par Pot ;
- utilise seulement un ordre et un curseur locaux stables pour paginer ;
- ne possède aucun watermark ou cursor durable ;
- n'impose aucun ordre fonctionnel entre Events ou versions.

La policy reste en Java. L'adapter SQL peut recevoir les routes concrètes qui en sont dérivées,
mais il ne connaît pas la policy elle-même. La vérité reconstructible reste :

```text
Events + policy courante + Consumption
```

Un nouveau scan peut repartir du début. Une évolution de policy retrouve ainsi les Events
historiques auxquels manque la nouvelle Consumption.

## 8. Consumption and fencing invariants

Le moteur Consumption existant est réutilisé :

- le slot est créé paresseusement et de manière idempotente pendant `acquire()` ;
- chaque couple `EventId × ProjectionType` possède son slot, ses Claims et ses retries ;
- les conséquences différentes d'un même Event n'ont aucune atomicité globale entre elles ;
- plusieurs workers peuvent servir des types différents ou concourir pour le même type ;
- le Claim référencé par `current_claim_id` est l'autorité ;
- l'expiration du lease permet un takeover mais ne révoque pas seule le Claim courant ;
- un takeover installe un nouveau Claim et fence immédiatement l'ancien ;
- `ProjectionTaskStore.ensure` n'est invoqué qu'après verrouillage et vérification du Claim courant ;
- la Task, la clôture `SUCCESS` du Claim et le passage `DONE/SUCCESS` du slot committent dans la
  même transaction locale ;
- un Claim stale ne produit aucun effet durable.

**Il n'existe pas d'atomicité globale entre les différentes projections issues d'un même Event.**
Chaque conséquence est une Consumption autonome et converge indépendamment.

## 9. Failure semantics

`Event → ProjectionTask` n'a qu'un résultat fonctionnel terminal normal : `SUCCESS`.

- cette chaîne ne produit jamais de `ProjectionFailure` ;
- elle ne possède aucune failure métier terminale ;
- une erreur technique rollbacke ou se propage et laisse la Consumption rejouable ;
- elle ne transforme pas le slot en `DONE/FAILED` ;
- aucun nombre de tentatives ne permet d'abandonner définitivement une conséquence due ;
- une incohérence de configuration ou d'invariant remonte opérationnellement sans être convertie
  en succès ou en failure métier.

Après crash ou erreur, l'acquisition suivante ou le takeover permet la convergence. Tant que la
Task déclarée par la policy n'a pas été assurée, le système la doit toujours.

## 10. Worker topology

Un worker EPT est configuré uniquement avec un `Set<ProjectionType>`, par exemple :

```text
{ READ_POT }
{ POT_BALANCES }
{ READ_POT, POT_BALANCES }
```

Les `EventType` pertinents et les routes sont dérivés exclusivement de la policy. Le worker ne
configure ni EventTypes, ni pipelines, ni generations, ni task types legacy. Des workers mono ou
multi-projections utilisent le même algorithme ; leur configuration exprime seulement une
topologie opérationnelle.

## 11. Transaction boundary

Discovery, construction du candidat et acquisition ne font pas partie de l'effet durable final.
Après acquisition, la `ProjectionKey` est dérivée en mémoire, puis une transaction locale courte
effectue :

```text
transaction {
    lock/fence current Claim
    ProjectionTaskStore.ensure(key)
    finish Claim SUCCESS
    finish Slot DONE/SUCCESS
    commit
}
```

`FinalizeConsumptionService` et `TransactionalFinalizeConsumptionUseCase` fournissent déjà cette
frontière générique. `leaseUntil` n'est pas testé pendant la finalisation. Aucun reload de payload,
calcul métier long ou parcours de catalogue legacy n'entre dans cette transaction.

## 12. Validation strategy

La conformité est établie par des preuves séparées mais reliées :

- tests unitaires des identités, de la policy, des routes et de leur exhaustivité ;
- tests PostgreSQL de migration EventType ;
- tests PostgreSQL de discovery metadata-only, pagination, segmentation et anti-join exact ;
- tests d'acquisition concurrente, takeover et fencing ;
- tests d'idempotence et de concurrence de `ProjectionTaskStore.ensure` ;
- tests workers mono et multi-projections ;
- tests de replay après erreur, de Claim stale et de rollback du durable effect ;
- preuve qu'une évolution de policy retrouve un Event historique sans watermark ;
- E2E distribués à travers les frontières persistantes :

```text
Command → durable Event
durable Event → ProjectionTask
ProjectionTask → Projection
```

Un mégatest unique n'est pas requis si chaque frontière persistante et les identités qui les
relient sont vérifiées exactement. Les preuves finales couvrent `READ_POT` et `POT_BALANCES`.

## 13. Architectural boundaries

Le chemin canonique EPT ne dépend pas de :

- `PipelineDefinitionRegistry` ou des pipeline generations ;
- `EventPipelineRelevanceRegistry` ;
- `TaskCreationStrategyRegistry` ;
- `tasks_4_pipeline` ;
- `payload_json` pour la discovery ;
- la transaction longue `TransactionalExecuteConsumptionUseCase` du chemin Event legacy ;
- la provenance générique obligatoire `ConsumptionInput` / `ConsumptionResult` ;
- `ProjectionFailure` ;
- un DAG, un watermark durable ou un registry générique de conséquences.

## 14. Legacy strategy

La migration suit strictement :

```text
build new canonical path
    → prove it
    → cut over runtime
    → audit legacy
    → delete legacy separately
```

Les classes, modules, tables et documents legacy peuvent rester compilés pendant la construction.
Ils ne sont retirés du bean graph qu'au cutover, puis supprimés dans un chantier distinct après
preuve que le chemin canonique est autoritaire. Aucun nettoyage opportuniste n'est un prérequis
d'EPT.
