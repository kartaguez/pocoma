# Pocoma — Lot 7.4 — LatestKnownVersion

Ce fichier remplace le plan historique « SourceVersionWatermark express ». Son chemin est conservé
pour ne pas casser les liens documentaires ; le concept Java et documentaire est désormais
`LatestKnownVersion`.

## Vérification du modèle Event / Task

### 1. Que représente une Task aujourd'hui ?

Le noyau Task est techniquement générique : `TaskPayload` est un marker et le lifecycle de
consumption sait acquérir et exécuter un travail durable. Le chemin qui dérive actuellement des Tasks
depuis un Event ne l'est toutefois pas sémantiquement :

- `ScheduleProjectionTasksForEventUseCase` programme des projections applicables ;
- l'identité persistée de ce consumer est `PROJECTION_TASK_SCHEDULER` ;
- les Tasks enregistrées portent `PipelineDefinition`, `PotId` et `targetVersion` ;
- `TaskExecutionReport.artifacts` décrit les résultats de projection ;
- le runtime Task compose les handlers de pipelines et la matérialisation d'artifacts versionnés.

Une Task pourrait donc être généralisée, mais le chemin Event-derived Task actuel est orienté vers la
construction de projections versionnées. Généraliser ce chemin uniquement pour
`LatestKnownVersion` serait artificiel et hors scope.

### 2. La Task est-elle indépendante des projections versionnées ?

Le lifecycle générique de consumption d'une Task l'est largement. La création Event→Task, son
descriptor, ses résultats et son wiring ne le sont pas. La conclusion est donc nuancée : le noyau est
réutilisable, mais le protocole Event→Task réellement exposé aujourd'hui suppose une projection.

### 3. Qu'apporterait une Task ?

Une Task créerait un handoff durable supplémentaire : après terminalisation du slot Event, la Task
porterait seule la responsabilité du travail, avec sa capacité, son backlog et ses retries propres.
Cette propriété est déterminante pour un traitement long ou autonome. Elle n'apporte ici aucune
garantie décisive supplémentaire, car le max-upsert, la provenance et le CAS terminal peuvent être
committés dans la même transaction PostgreSQL.

### 4. Existe-t-il une consommation directe fiable d'Events ?

Oui. `ConsumptionLocator`, `SequentialConsumptionOrchestrator`, `AcquireConsumptionUseCase`,
`TransactionalExecuteConsumptionUseCase`, les slots, claims, leases, retries et le fencing sont
indépendants des Tasks. `ExecuteConsumptionService` exécute le callback, persiste sa provenance puis
effectue le CAS terminal. Le wrapper transactionnel couvre ces trois étapes. La discovery est best
effort ; l'Event est rechargé par son identifiant après acquisition.

### 5. Architecture retenue

```text
BusinessEvent
  -> LatestKnownVersionConsumptionLocator
  -> consumption slot / claim / lease / fencing / retry
  -> transaction PostgreSQL
       max-upsert LatestKnownVersion
       provenance de l'Event
       CAS terminal du slot
```

La voie directe est retenue. `max(current, candidate)` est déterministe, naturellement idempotent,
borné, court, sans appel externe et local à la même ressource transactionnelle. Une Task ajouterait
un polling et un handoff durable qui ne renforceraient pas cette opération locale.

Si plusieurs cas Event-derived non projectifs nécessitent plus tard un handoff autonome, il pourra
devenir pertinent de généraliser réellement le modèle Event→Task. Aucun contrat de Task, scheduler,
`TaskExecutionReport.artifacts` ou pipeline n'est généralisé dans ce lot.

## 1. Invariant architectural central : atomicité transactionnelle

> Un Event consumer peut produire directement un état dérivé du read model uniquement si son effet
> métier et la terminalisation fencée de sa consumption peuvent être rendus atomiques dans la même
> frontière transactionnelle durable.

Pour `LatestKnownVersion`, la transaction gagnante contient obligatoirement :

1. le max-upsert ;
2. la provenance `ConsumptionInput` de l'Event candidat ;
3. le CAS terminal du consumption slot.

`TransactionalExecuteConsumptionUseCase` constitue cette frontière. Le JDBC read store utilise le
même `DataSource`/`PlatformTransactionManager` PostgreSQL et exige une transaction existante avec
`Propagation.MANDATORY`.

Les comportements exigés sont :

- crash ou exception avant commit : aucune avance, aucune provenance et aucun slot terminal ne
  subsistent ; l'Event demeure consommable ;
- perte du claim ou échec du fencing : le CAS échoue et toute mutation faite dans le callback est
  rollbackée ;
- commit réussi : état, provenance et slot terminal sont durablement cohérents ;
- crash après commit : le slot terminal empêche une nouvelle application nominale, même si l'upsert
  resterait idempotent.

Les compteurs métier `advanced|unchanged` ne sont publiés qu'après commit. Les erreurs et retries du
lifecycle restent observés par le moteur générique.

## 2. Règle générale des consumers directs

Toute matérialisation du read model issue du write side doit passer par un lifecycle de consommation
durable, fencé, observable et rejouable. Selon la nature du traitement, ce lifecycle peut produire
directement un petit effet transactionnel ou effectuer un handoff vers une Task durable.

L'effet direct n'est autorisé que s'il est :

- déterministe ou naturellement idempotent ;
- borné et court ;
- local à la même ressource transactionnelle que le slot ;
- sans appel externe ni traitement long ;
- sans besoin de handoff durable autonome.

Une Task est requise quand le travail doit survivre comme unité autonome après consommation de
l'Event, notamment s'il est long, découplé en capacité, non atomique avec la consumption ou dépend
d'une autre ressource transactionnelle. Les projections versionnées utilisent ce deuxième mode.

Cette règle remplace l'ancienne formulation « seuls les Task Executors écrivent le read model ».
`LatestKnownVersion = max(current, candidate)` appartient clairement au premier mode ; il ne crée pas
un précédent pour exécuter des projections arbitraires dans un callback Event.

## 3. Sémantique fonctionnelle

```text
LatestKnownVersion(potId)
  = max(version des BusinessEvents effectivement matérialisés par ce consumer)
```

La valeur est mutable, unique par Pot et strictement monotone. Elle supporte Events hors ordre,
duplicates, retries et replays anciens, sans contrôle `N-1` ni garantie de continuité.

Exemple : avec un état V12, traiter V10 donne `unchanged`, candidate=10 et résultat=12. La provenance
atteste l'entrée V10 ; elle ne prétend jamais que V10 a produit V12.

`LatestKnownVersion` n'est ni :

- la dernière projection disponible ;
- un watermark de continuité ;
- la preuve que toutes les versions `<= N` ont été traitées ;
- la preuve que la projection N existe ;
- un mécanisme de coordination des autres pipelines.

Les états suivants sont normaux :

```text
latestKnownVersion = 15, bestProjectedVersion = 13
bestProjectedVersion = 15, latestKnownVersion = 14
```

Les pipelines étant indépendants et asynchrones, cette connaissance read-side n'est pas une lecture
synchrone absolue du write side. Aucun reader ou projector ne doit utiliser la valeur comme barrière.

## 4. Modèle, ports et persistance

Le vocabulaire Java est :

- `LatestKnownVersion(potId, latestKnownVersion)` ;
- `AdvanceLatestKnownVersionInput(potId, candidateVersion, observedAt)` ;
- `AdvanceLatestKnownVersionUseCase.advanceToAtLeast(...)` ;
- `LatestKnownVersionPersistencePort.advanceToAtLeast(...)` ;
- `LatestKnownVersionUpdate.Advanced|Unchanged`.

La persistence réalise un unique `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE excluded > current`.
`advanced_at` ne change que lors d'une avance effective et reste une métadonnée technique.

Les noms SQL historiques sont conservés pendant ce lot :

```text
pocoma_read.source_version_watermarks
  pot_id uuid primary key
  latest_version_seen bigint not null
  advanced_at timestamptz not null
```

Aucune migration Flyway de renommage n'est nécessaire. Un renommage physique éventuel sera une
migration forward-only ultérieure ; aucune migration déjà appliquée ne doit être rollbackée ou
réécrite.

## 5. Discovery, identité et lifecycle

`LatestKnownVersionConsumptionLocator` découvre les Events éligibles par ordre stable et segmentation
de Pot, recharge le `RecordedEvent` autoritatif via `EventPort`, extrait `potId` et `version`, invoque
le max-upsert, puis retourne un succès avec l'Event comme `ConsumptionInput` et aucun
`ConsumptionResult` artificiel.

L'identité persistée reste exactement :

```text
consumable = EVENT[eventId]
consumer   = SOURCE_VERSION_WATERMARK[]
```

`SOURCE_VERSION_WATERMARK` est désormais un identifiant technique/protocolaire opaque de
compatibilité. Son nom historique ne décrit plus le concept métier. Le changer constituerait une
migration protocolaire distincte et provoquerait de nouveaux slots ; ce travail est inutile ici.

La discovery SQL traite comme éligibles les Events sans slot, les slots pending disponibles et les
claims expirés. Elle exclut les slots terminaux de la même identité. Aucun mécanisme de claim, lease,
retry ou fencing n'est réimplémenté localement.

## 6. Runtime, performance et observabilité

Le runtime et son pool sont dédiés afin de régler indépendamment capacité, segmentation, lease,
budgets et fréquence de polling. Cela réduit le lag sans en faire une précondition fonctionnelle : la
correction repose sur le max-upsert et le lifecycle, pas sur la vitesse.

Le runtime générique reste utilisé tant qu'il satisfait les objectifs de latence. Aucun réveil,
ordonnancement ou chemin rapide spécial n'est ajouté. Le coût évité est celui d'un handoff et d'un
polling Task supplémentaires.

L'observabilité réutilise en priorité les métriques génériques de consumption : pending, lag,
claims, retries, failures et durée. Le seul compteur métier additionnel est borné par le tag
`outcome=advanced|unchanged|error`. Les logs structurés portent `candidateVersion`,
`resultingLatestKnownVersion` quand disponible et l'outcome. `potId` peut apparaître dans les logs
selon les conventions, jamais comme label métrique.

## 7. Migration et cutover nominal

Le moteur ne fabrique pas les slots de tous les Events à l'avance : ils apparaissent lors de
l'acquisition. Conserver la même identité persistée permet néanmoins au consumer renommé de retrouver
les slots historiques terminaux, pending ou expirés. L'état SQL existant sert d'état initial ; le
max-upsert rend sans effet les Events anciens éventuellement encore dépourvus de slot.

Cette stratégie est préférable à une reconstruction intégrale : elle préserve la connaissance déjà
matérialisée et évite qu'un backlog historique retarde inutilement les Events récents. Les Events sans
slot restent toutefois consommés par le lifecycle normal, afin que leur provenance soit honnête.

Chemin nominal :

1. déployer le code renommé avec le runtime désactivé si nécessaire ;
2. exécuter le préflight read-only ;
3. vérifier la cohérence entre état existant et slots ;
4. arrêter proprement l'ancien runtime ;
5. attendre la libération ou l'expiration des claims actifs ;
6. activer le runtime renommé avec le même `consumer_type`, les mêmes tables et le même état initial ;
7. vérifier nouveaux Events, reprise des pending, lag, outcomes et absence de Tasks/artifacts/heads ;
8. retirer le vocabulaire opérationnel legacy « express ».

Le scénario nominal ne crée jamais massivement des slots `DONE/SUCCESS`. Un slot terminal signifie
qu'un Event a réellement suivi le lifecycle du consumer, et non qu'il est seulement subsumé par une
valeur supérieure.

Le préflight mesure au minimum : Events sans slot, slots pending/terminal, claims actifs ou expirés,
Pots sans état initial et incohérences entre provenance terminalisée et valeur latest-known.

## 8. Réparation exceptionnelle

Une réparation n'appartient pas au cutover nominal. Elle n'est jamais exécutée automatiquement et
requiert un diagnostic explicite d'une incohérence historique réelle et volumineuse.

- Réparation de `LatestKnownVersion` : reconstruire le max depuis les Events ou slots attestés.
- Réparation de slots : à éviter par défaut et à justifier par une anomalie documentée précise.

La réparation de l'état dérivé est toujours préférée à la fabrication d'un historique de consumption
qui n'a pas eu lieu. Un backlog, même important, ne suffit pas à justifier des slots terminaux
artificiels.

## 9. Tests de validation

Tests fonctionnels et persistence :

- V1 → 1 ; V3 puis V2 → 3 ; duplicate V3 ; replay V1/V2 ;
- concurrence V10/V11 → 11 ; aucune contrainte N-1 ;
- `advanced_at` ne change pas pour equal/lower ;
- aucune dépendance des autres pipelines à `LatestKnownVersion`.

Tests transactionnels PostgreSQL :

- exception avant commit : ni latest-known avancé, ni provenance, ni slot terminal ;
- perte de claim avant CAS : rollback du max-upsert et de la provenance, claim `TAKEN_OVER`, slot
  pending reprenable ;
- succès : max-upsert, input de provenance et CAS terminal visibles ensemble ;
- second passage après commit : le slot terminal évite une nouvelle application nominale.

Tests architecturaux :

- aucune Task créée, aucun artifact, `ProjectionHead` ou reader de projection ;
- aucun import de scheduler, Task executor ou `TaskExecutionReport` par le locator ;
- usage exclusif des abstractions génériques de consumption pour claim/lease/fencing/retry ;
- le chemin de projection N reste indépendant même si latest-known vaut N-1.

Tests de cutover :

- les slots historiques sont reconnus après renommage Java grâce à `SOURCE_VERSION_WATERMARK` ;
- aucun replay intégral artificiel n'est provoqué ;
- un Event réellement sans slot reste éligible ;
- un slot pending au claim expiré est repris ;
- aucune création nominale de slots `DONE` n'est nécessaire.

## 10. Documentation canonique

Les documents d'architecture présentent les deux modes autorisés :

```text
Effet direct transactionnel
Event -> consumption fiable -> petit effet local atomique
Exemple : LatestKnownVersion

Handoff durable
Event -> Task -> Task Executor -> travail autonome
Exemple : projections versionnées
```

Le critère est la possibilité d'inclure l'effet dans la transaction fencée de consumption et le
besoin éventuel d'autonomie durable, non le simple caractère mutable ou immutable du résultat.

## 11. Critères de sortie

- atomicité commune prouvée par les tests de rollback et de takeover ;
- max-upsert monotone et concurrent-safe ;
- identité persistée et schéma SQL legacy inchangés ;
- runtime dédié, observable et réglable sans protocole spécial ;
- aucun handoff Task et aucune matérialisation de projection ;
- cutover sans fabrication de slots terminaux ;
- documentation débarrassée du concept métier « LatestKnownVersion express ».
