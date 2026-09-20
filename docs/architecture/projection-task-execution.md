# Lot 5 — Exécution canonique des tâches de projection

## 1. Statut et portée

Ce document est la référence normative du Lot 5 pour l'exécution des tâches de projection et leur
articulation avec le moteur générique de consommation. Il complète
[`read-side-target.md`](read-side-target.md) et
[`consumption-transactional-execution.md`](consumption-transactional-execution.md).
La composition interne de la préparation multi-type est précisée par
[`projection-engine.md`](projection-engine.md), qui fait autorité pour la façade unique et le
catalogue cohérent de producteurs.

La cible sépare strictement :

- l'identité et la préparation d'une projection ;
- la possession temporaire du travail par Consumption ;
- l'effet durable propre au consommateur ;
- la finalisation atomique et fenced de la consommation.

Le code actuel n'est pas la spécification. Les noms de composants et les mécanismes de composition
non arrêtés dans ce document restent ouverts jusqu'à l'audit d'écart préalable à l'implémentation.
Le document [`consumption-task-balance-runtime.md`](consumption-task-balance-runtime.md) décrit le
runtime transitionnel actuellement livré ; lorsqu'il conserve une identité de pipeline, une
comparaison de contenu ou une provenance générique contraire à la présente cible, ce document-ci
prévaut.

## 2. Identité d'une ProjectionTask

Une `ProjectionTask` est sémantiquement entièrement identifiée par une `ProjectionKey` :

```text
ProjectionTask
    └── ProjectionKey(
            ProjectionType,
            TargetObjectType,
            TargetObjectId,
            targetVersion
        )
```

La clé exprime tout le résultat demandé : « produire cette projection, pour cet objet, à cette
version ». Une identité technique de ligne, de slot ou de tentative peut exister dans la
persistence, mais elle ne constitue pas une seconde identité métier du travail.

Ne font notamment pas partie de l'identité sémantique d'une `ProjectionTask` :

- une pipeline ou sa version ;
- un `taskType` redondant ;
- une version de producteur ;
- un handler, un worker ou un Claim ;
- l'Event ayant permis de découvrir le travail.

## 3. Indépendance des tâches

Le moteur générique n'impose aucun ordre entre des `ProjectionTask`, y compris lorsqu'elles visent
le même objet :

```text
READ_POT / POT / 123 / 40
READ_POT / POT / 123 / 41
READ_POT / POT / 123 / 42
```

Ces trois tâches peuvent être localisées, acquises, calculées et finalisées indépendamment. Il
n'existe aucun invariant générique de :

- progression croissante des versions ;
- continuité entre versions ;
- dépendance à la version précédente ;
- séquencement par agrégat ;
- DAG de projections.

Une dépendance de production est exprimée par les données exactes dont le calcul a besoin. Si
`POT_BALANCES@42` nécessite `READ_POT@42`, le loader tente de charger exactement `READ_POT@42`. Il ne
substitue ni une version antérieure, ni une projection `CURRENT`. Si cette dépendance n'est pas
encore disponible, la tentative échoue temporairement et le travail pourra être repris.

## 4. Discovery, locator et acquisition

Le locator répond exclusivement à la question :

> Quel travail potentiel puis-je essayer de consommer ?

Il découvre des tâches candidates. Une tâche retournée par le locator n'est ni réservée, ni
autorisée à s'exécuter, ni autorisée à produire un effet durable. La forme concrète du futur locator
reste ouverte : la cible n'impose pas de conserver les contrats ou modèles actuels.

L'acquisition du `ConsumptionSlot` est l'autorité qui accorde effectivement le travail à un worker.
La discovery peut donc retourner simultanément le même candidat à plusieurs workers sans
compromettre la correction. Seule une acquisition réussie installe le Claim courant.

## 5. Frontière de engine-consumption

`engine-consumption` reste générique. Il possède les mécanismes de réservation et de terminaison
sûre d'un travail consommable :

- `ConsumptionSlot` et son lifecycle autoritatif ;
- `Claim`, `ClaimId` et identité du worker ;
- lease et fencing ;
- acquisition atomique et takeover ;
- historique durable des tentatives ;
- release ou traitement d'un échec temporaire ;
- terminalisation ;
- finalisation transactionnelle fenced.

Il ne connaît aucun concept de production de projection :

- ni `Projection`, `ValidatedProjection` ou `ProjectionDefinition` ;
- ni `ProjectionWritePort` ;
- ni loader ou projector ;
- ni artifact de projection ;
- ni `AUTH`, `READ_POT`, `POT_BALANCES` ou autre type métier.

Consumption gère l'autorité d'exécution et de finalisation. Il n'exécute pas lui-même le métier de
projection et ne possède pas le writer métier du consommateur.

## 6. Claim, tentative, lease et fencing

### 6.1 Historique des tentatives

Chaque acquisition réussie crée une tentative durable et observable. Le `Claim` existant peut
porter ce rôle s'il satisfait les invariants de la cible ; aucune seconde abstraction `TaskAttempt`
n'est requise par principe.

Un Claim documente notamment :

- l'identité de la tentative ;
- le worker qui l'a acquise ;
- son instant d'acquisition ;
- son lease ;
- son issue lorsqu'elle est connue.

L'historique des Claims est une trace durable des tentatives, mais n'est ni une provenance métier
ni un rapport fonctionnel d'exécution. Il n'est pas non plus l'autorité de fencing. Cette autorité
est le `ClaimId` actuellement installé sur le `ConsumptionSlot`. Une tentative interrompue par un
crash peut rester historiquement incomplète ; elle n'empêche ni takeover ni progression du système.

### 6.2 Rôles distincts

Le lease répond à :

> À partir de quand un autre worker peut-il tenter un takeover ?

Le `ClaimId`, employé comme fencing token, répond à :

> Quel Claim est actuellement autorisé à modifier définitivement cette consommation ?

L'expiration du lease ne retire pas, à elle seule, l'autorité du Claim courant. Tant qu'aucun
takeover n'a installé un nouveau Claim, le Claim expiré reste autorisé à finaliser :

```text
Claim A / fence 17
lease expiré

aucun takeover
    A peut encore finaliser

Claim B acquis / fence 18
    B devient le Claim courant
    A ne peut plus finaliser
```

Toute mutation autoritaire du slot effectuée au nom d'une tentative est fenced par le Claim
courant. Une vérification fondée seulement sur l'heure, la révision ou l'existence historique du
Claim est insuffisante.

## 7. Préparation hors transaction finale

Après acquisition, la projection est préparée hors de la transaction locale de finalisation :

```text
ProjectionKey
    ↓
Loader<I>
    ↓
Input I
    ↓
Projector<I>
    ↓
Projection
    ↓
ProjectionValidator
    ↓
ValidatedProjection
```

Le loader charge les données exactes nécessaires à la clé demandée. Selon la projection, elles
peuvent provenir de la donnée primaire versionnée, de projections existantes ou des deux. Une
dépendance versionnée est toujours chargée à sa clé exacte ; aucun fallback implicite vers un état
current n'est permis.

Le projector est pur vis-à-vis des écritures. Conceptuellement :

```text
ProjectionKey + Input I -> Projection
```

Il ne publie rien, ne finalise ni Task ni Claim, et ne connaît pas le `ConsumptionSlot`. La
`Projection` candidate est ensuite vérifiée par `ProjectionValidator`, seul passage normal vers une
`ValidatedProjection` publiable.

Pour un `ProjectionType`, loader, projector et `ProjectionDefinition` forment nécessairement un
ensemble cohérent. Le type d'entrée `I` relie au minimum loader et projector. Un assemblage tel que
`ReadPotLoader + BalanceProjector + AuthProjectionDefinition` doit être empêché au wiring ou détecté
avant traitement utile.

Cette exigence ne décide pas à elle seule le nom ni la forme Java de l'abstraction de composition.
Après implémentation concrète de `READ_POT` et `POT_BALANCES`, la cible retient désormais une façade
unique et un petit catalogue de déclarations cohérentes, décrits dans
[`projection-engine.md`](projection-engine.md). Ce catalogue n'est ni un nouveau pipeline, ni un
DAG, ni un registry indépendant de loaders, projectors et définitions.

## 8. Publication immutable

Une `ProjectionKey` désigne une projection immutable. Une projection publiée sous cette clé n'est
jamais remplacée ni mise à jour. La contrainte d'unicité du store est l'autorité concurrente.

La sémantique de `ProjectionWritePort.publish` reste :

```text
clé absente
    écrire atomiquement projection et artifacts
    PUBLISHED

clé déjà présente
    ne rien écrire
    ALREADY_EXISTS
```

`ALREADY_EXISTS` est un succès idempotent qui satisfait la `ProjectionTask`. Il ne signifie pas que
le contenu a été comparé. La cible interdit, pour ce cas :

- tout écrasement ou `UPDATE` ;
- toute comparaison des payloads ou de la projection ;
- tout digest de vérification ;
- tout résultat `DivergentDuplicate`.

Le principe est `first published wins`.

## 9. Échecs temporaires et ProjectionFailure

Un échec temporaire signifie que cette tentative ne peut pas préparer la projection maintenant,
sans conclure qu'elle est définitivement impossible. Une dépendance exacte encore absente en est
un exemple.

Dans ce cas :

- aucune projection n'est publiée ;
- aucun `ProjectionFailure` terminal n'est enregistré ;
- la `ProjectionTask` n'est pas terminalisée ;
- le Claim documente l'issue connue de la tentative ;
- le slot reste ou redevient consommable au moyen d'une mutation fenced.

Le calendrier de retry, le backoff et la prévention des hot-loops sont des décisions
opérationnelles laissées ouvertes. Ils ne modifient ni l'identité de la Task ni son indépendance par
rapport aux autres versions.

`ProjectionFailure` représente au contraire l'impossibilité définitive de produire la projection
demandée. Il n'est jamais le journal de toutes les tentatives échouées. Lorsqu'une impossibilité
terminale est décidée, son enregistrement et la terminalisation correspondante doivent respecter la
même atomicité et le même fencing que toute finalisation autoritaire.

## 10. Finalisation transactionnelle

Le chargement, le calcul et la validation sont terminés avant d'ouvrir la transaction finale. Une
transaction locale courte effectue ensuite, comme une seule unité atomique :

1. la vérification fenced du droit de finaliser ;
2. l'effet durable propre au consommateur ;
3. la clôture du Claim ;
4. la terminalisation du `ConsumptionSlot` ;
5. le commit.

Pour une `ProjectionTask` préparée avec succès, l'effet durable est :

```text
ProjectionWritePort.publish(validatedProjection)
    -> PUBLISHED | ALREADY_EXISTS
```

Les deux résultats permettent de terminer la Task avec succès. La publication et la terminaison de
la consommation rejoignent la même transaction locale et la même ressource transactionnelle. Il ne
doit donc exister durablement ni Task terminée sans son effet requis, ni projection committée sans
la terminaison correspondante.

Une vérification initiale du Claim ne suffit pas si un takeover peut committer avant la fin de la
transaction. La finalisation et le takeover doivent se sérialiser sur l'autorité du slot. Si le
fencing final échoue, la transaction complète, publication incluse, rollbacke.

`engine-consumption` fournit la capacité générique de finalisation transactionnelle fenced. Le
traitement de projection y associe son propre effet durable. Une API de callback transactionnel est
une possibilité de composition, pas une décision canonique de ce lot.

## 11. Concurrence obligatoire

Plusieurs workers peuvent calculer simultanément la même `ProjectionTask`. Le calcul concurrent est
acceptable ; seule la finalisation autoritaire est exclusive.

```text
A possède Claim 17
le lease expire
B acquiert Claim 18

A termine son calcul
    le fencing de A échoue
    aucun effet durable de A n'est committé

B peut finaliser
```

Inversement, si A gagne et commit sa finalisation avant que B n'installe un nouveau Claim, A peut
terminer même avec un lease expiré. Le takeover ne peut alors acquérir une consommation déjà
terminale. Le mécanisme relationnel exact assurant cette sérialisation reste une décision
d'implémentation ; le comportement observable est obligatoire.

La contrainte unique sur `ProjectionKey` protège en outre contre des publications concurrentes
issues de chemins distincts. Elle applique indépendamment la règle `first published wins`.

## 12. Flux canonique

```text
ProjectionTask(ProjectionKey)
          │
          ▼
        LOCATE
          │
          ▼
       ACQUIRE
          │
   ConsumptionSlot
   Claim + lease + fence
          │
          ▼

    HORS TRANSACTION

      Loader<I>
          ↓
       Input I
          ↓
      Projector<I>
          ↓
      Projection
          ↓
  ProjectionValidator
          ↓
  ValidatedProjection
          │
          ▼

   TRANSACTION COURTE

   fenced finalization
          ↓
   publish projection
   PUBLISHED
      ou
   ALREADY_EXISTS
          ↓
    finish Claim
          ↓
    finish Slot
          ↓
        COMMIT
```

Le chemin temporairement indisponible est distinct :

```text
load / preparation
      ↓
temporary failure
      ↓
fenced release / failure handling
      ↓
Claim documente la tentative
      ↓
Slot reste ou redevient consommable
```

## 13. Conséquences pour l'architecture existante

Les abstractions existantes suivantes ne sont pas des contraintes de la cible. L'audit
d'implémentation devra vérifier leurs usages avant de décider de les supprimer, les simplifier ou
les déplacer :

- pipeline et `taskType` dans l'identité d'une ProjectionTask ;
- `RecordedTaskExecutionMapperRegistry` et `TaskExecutionHandlerRegistry` ;
- `TaskExecutionReport` ;
- `BusinessObjectVersion` et `ProducedArtifactReference` ;
- provenance métier générique portée par `engine-consumption` ;
- handlers qui chargent, calculent et persistent eux-mêmes une projection ;
- `ProjectionMaterializationService` legacy ;
- `createOrVerify` et `DivergentDuplicate` ;
- ordonnancement générique par version et continuité avec la version précédente ;
- dépendances de projections encodées comme DAG dans le moteur.

Cette liste n'ordonne pas leur suppression automatique. Elle établit qu'aucun de ces concepts ne
doit être préservé uniquement parce qu'il existe. L'audit doit les confronter aux responsabilités et
invariants de ce document.

## 14. Décisions volontairement ouvertes

Le Lot 5 ne fixe pas :

- le nom ou la forme de l'abstraction liant loader, projector et définition ;
- la représentation Java du catalogue désormais requis ;
- l'implémentation concrète du routing depuis `ProjectionType` ;
- l'API exacte de finalisation transactionnelle de `engine-consumption` ;
- la stratégie de retry, de backoff ou d'anti-hot-loop ;
- le wiring Spring ;
- la migration détaillée des anciennes classes ;
- le découpage de l'implémentation en commits ou sous-lots.

Ces choix nécessitent un audit d'écart entre la cible et le code actuel. Ils devront préserver les
invariants établis ici sans introduire une nouvelle identité métier, une dépendance à l'ordre des
versions ou un couplage de Consumption au domaine des projections.

## 15. Invariants récapitulatifs

1. `ProjectionTask` a exactement l'identité sémantique de sa `ProjectionKey`.
2. Les tâches et versions sont indépendantes ; aucun ordre implicite n'est imposé.
3. Le locator découvre ; seule l'acquisition du slot autorise l'exécution.
4. Le Claim courant du slot, et non l'expiration du lease, porte l'autorité de fencing.
5. Chaque acquisition produit une tentative durable ; une tentative crashée peut rester incomplète.
6. Loader, projector et définition sont cohérents, sans que leur abstraction de composition soit
   encore figée.
7. Le projector calcule sans écrire.
8. Une projection publiée est immutable ; `ALREADY_EXISTS` est un succès sans comparaison.
9. Un échec temporaire ne crée pas de `ProjectionFailure` et ne terminalise pas la Task.
10. L'effet durable et la terminaison de consommation sont atomiques dans une transaction courte.
11. Toute finalisation perdant son fencing rollbacke intégralement.
12. `engine-consumption` ne connaît ni projections, ni loaders, ni projectors, ni writer métier.
