# Plan de matérialisation des événements en tâches de pipeline

## Objectif

Ce document prépare l'implémentation d'une architecture générique où des événements métier durables sont matérialisés en tâches de pipeline, puis exécutés par une seconde famille de workers. L'objectif est de conserver les qualités déjà présentes dans Pocoma : file durable en base, idempotence, exécution at-least-once, back pressure observable, workers segmentables, et logique métier confinée dans les stratégies de pipeline.

Architecture cible :

```text
business_event_outbox / events
  -> EventToPipelineTaskMaterializerWorker
  -> event_4_pipeline_materialization_status
  -> tasks_4_pipeline
  -> TaskExecutorWorkers
```

Le worker de matérialisation orchestre uniquement la lecture, la sélection des stratégies, la persistance du registre et la création des tâches. Il ne doit jamais exécuter les tâches produites.

## État actuel du repository

### Modules et patterns réutilisables

- `orchestrator-claimable-work-dispatcher` fournit le socle générique de claimable work : `ClaimableWorkLifecycle`, `ClaimWorkRequest`, `ClaimedWork`, dispatcher, wake bus et pool segmenté.
- `supra-dispatcher-business-events-outbox-nats` adapte ce socle à la transformation `business_event_outbox -> projection_tasks` via `BusinessEventWorkSource`, `ProjectionTaskBuilderWorker` et `SegmentedProjectionTaskBuilder`.
- `supra-dispatcher-balance-calculation-tasks-outbox-nats` adapte le même socle à l'exécution de `projection_tasks` via `ProjectionTaskWorkSource`, `ProjectionTaskExecutorWorker` et `SegmentedProjectionTaskExecutor`.
- `engine-projection` contient déjà une séparation claire entre cas d'usage (`BuildProjectionTasksUseCase`, `ExecuteProjectionTasksUseCase`), services métier (`BuildProjectionTasksService`, `ExecuteProjectionTasksService`) et ports de persistance (`BusinessEventOutboxPort`, `ProjectionTaskPort`).
- `infra-persistence-jpa` contient les adaptateurs JPA qui implémentent les claims, transitions de statut, heartbeats et compteurs de backlog.
- Les migrations Flyway sont centralisées dans `runtime-monolith/src/main/resources/db/migration`, puis réutilisées par les runtimes séparés.
- L'observabilité actuelle expose déjà des compteurs/gauges de backlog et des timers de projection via Micrometer.

### Pattern de claim existant

Le pattern actuel est robuste et doit être réutilisé autant que possible :

1. Le dispatcher ne claim que si le pool local a de la capacité.
2. Le claim s'appuie sur une transaction courte et une requête SQL `for update skip locked`.
3. Chaque claim reçoit un `claim_token` servant de fencing token.
4. Le dispatcher marque le travail `ACCEPTED`, puis le pool marque `RUNNING` au démarrage effectif.
5. Un heartbeat prolonge le bail pendant le traitement.
6. Les transitions `DONE` / `FAILED` sont protégées par le `claim_token`.
7. En cas de crash, le bail expire et le travail redevient claimable.
8. Le wake signal réduit la latence, mais le polling reste le mécanisme de sûreté.

### Écart avec la cible

Le système actuel matérialise un pipeline de projection unique et coalescé : `business_event_outbox -> projection_tasks`, avec une tâche active par pot et par type. La cible demande un registre explicite par triplet `(event_id, pipeline_id, pipeline_version)` et une table de tâches générique `tasks_4_pipeline`, capable d'accueillir plusieurs pipelines, plusieurs types de tâches et des stratégies métier isolées.

## Recommandation d'architecture

### Décision principale

Implémenter la nouvelle architecture comme une généralisation du pipeline actuel, sans casser le chemin projection existant dans un premier temps :

- conserver le socle `orchestrator-claimable-work-dispatcher` ;
- introduire des modèles et ports génériques de pipeline dans un module engine dédié ou dans `engine-projection` si l'objectif reste limité aux projections ;
- créer des adaptateurs JPA pour `event_4_pipeline_materialization_status` et `tasks_4_pipeline` ;
- créer un worker de matérialisation plus simple que le `ProjectionTaskBuilderWorker` : il ne claim pas le travail, il lit par batch les couples `event x pipeline` absents du registre et les matérialise immédiatement ;
- créer des exécuteurs équivalents au `ProjectionTaskExecutorWorker`, mais dispatchant selon `pipeline_id`, `pipeline_version` et `task_type` ;
- migrer progressivement le pipeline de balances vers cette abstraction après validation.

Cette approche maximise la réutilisation et limite les risques opérationnels.


## Plan dédié : mécanique de matérialisation sans claim

Cette section remplace explicitement l'idée d'un claim pour la création des tâches. La matérialisation n'est pas une file de travail réservée ; c'est une boucle idempotente qui détecte les couples `event x pipeline` absents et tente de les écrire. La correction vient des contraintes d'unicité en base.

### Principe

```text
select missing event/pipeline pairs
  -> for each pair:
       load event envelope
       resolve pipeline strategy
       if supports(event): materialize TaskDescriptor list
       write registry + tasks in one transaction
       ignore uniqueness conflicts as already materialized
```

Deux materializers peuvent sélectionner le même couple au même moment. Ce n'est pas un bug : le premier qui commit crée le registre et les tâches ; le second rencontre un conflit d'unicité et abandonne proprement ce couple.

### Requête de sélection des couples candidats

La requête doit retourner des couples et non des lignes à réserver. Conceptuellement :

```sql
select e.id as event_id,
       p.pipeline_id,
       p.pipeline_version
from business_event_outbox e
join pipeline_definitions p on p.enabled = true
left join event_4_pipeline_materialization_status m
  on m.event_id = e.id
 and m.pipeline_id = p.pipeline_id
 and m.pipeline_version = p.pipeline_version
where m.event_id is null
  and e.created_at <= :upperBound
  and (:segmentCount = 1 or mod(e.pot_partition_hash, :segmentCount) = :segmentIndex)
order by e.created_at, e.id, p.pipeline_id, p.pipeline_version
limit :limit
```

Si les pipelines ne sont pas stockés en base, le même principe peut être appliqué avec une requête par pipeline actif connu du registry applicatif. Le point important est que l'absence dans le registre est le critère de sélection.

### Algorithme du worker

1. Attendre soit le prochain tick de polling, soit un wake signal.
2. Calculer un `upperBound` stable, par exemple `now - safetyDelay`, pour éviter de courir après des transactions d'événements tout juste commités.
3. Lire un batch de couples candidats avec `findUnmaterializedEventPipelinePairs(limit, partition, upperBound)`.
4. Pour chaque couple :
   - charger l'enveloppe événement complète ;
   - récupérer la stratégie `(pipeline_id, pipeline_version)` ;
   - appeler `supports(event)` ;
   - si non supporté, ne rien écrire par défaut, ou écrire `SKIPPED` si cette observabilité est décidée ;
   - appeler `materializeTasks(event)` ;
   - ouvrir une transaction courte d'écriture ;
   - insérer le registre avec contrainte unique ;
   - insérer les tâches avec clé unique ;
   - marquer le registre `MATERIALIZED` ;
   - publier le wake signal des tâches après commit.
5. Si un conflit unique se produit sur le registre, considérer le couple comme déjà traité et continuer.
6. Si un conflit unique se produit sur une tâche, relire/continuer selon la politique choisie ; la recommandation est de rendre l'insertion de tâche idempotente avec `on conflict do nothing` sur la clé `(pipeline_id, pipeline_version, event_id, task_key)`.

### Écriture transactionnelle recommandée

Pseudo-code applicatif :

```text
for pair in missingPairs:
    event = eventPort.get(pair.eventId)
    strategy = registry.get(pair.pipelineId, pair.pipelineVersion)

    if !strategy.supports(event):
        continue

    descriptors = strategy.materializeTasks(event)

    transaction:
        materializationId = insert materialization if absent
        if materialization already exists:
            return ALREADY_DONE

        insert each task descriptor on conflict do nothing
        mark materialization MATERIALIZED
```

Cette transaction ne contient pas de claim, pas de lease et pas de heartbeat. `attempt_count` peut être incrémenté seulement lorsqu'une erreur de matérialisation est persistée, mais il ne pilote pas une réservation.

### Statuts minimaux du registre

Pour cette mécanique, les statuts du registre doivent rester simples :

- `MATERIALIZED` : le couple a produit ses tâches, y compris éventuellement zéro tâche si c'est un résultat valide de la stratégie.
- `SKIPPED` : optionnel, utilisé seulement si l'on veut tracer explicitement les événements non supportés.
- `FAILED` : une erreur a été persistée pour observabilité ou intervention.

Un statut `MATERIALIZING` est possible pour diagnostiquer une transaction en deux phases, mais il ne doit pas être nécessaire au chemin nominal et ne doit pas remplacer les contraintes uniques.

### Backfill et nouveau pipeline

L'ajout d'un pipeline ne nécessite pas de pré-créer des claims. Il suffit d'activer le pipeline dans le registry ou dans `pipeline_definitions`. La requête `events x pipelines - registry` fera naturellement ressortir l'historique absent pour ce nouveau `(pipeline_id, pipeline_version)`. Un backfill peut donc être limité par plage d'événements ou par date sans modifier la logique de matérialisation.

### Conséquences pour le code Pocoma

- Le module `orchestrator-claimable-work-dispatcher` reste essentiel pour `TaskExecutorWorkers`, mais ne doit pas être imposé à `EventToPipelineTaskMaterializerWorker`.
- Le materializer peut réutiliser seulement les briques de wake/polling si elles sont suffisamment découplées ; sinon une boucle Spring simple suffit.
- Les adaptateurs JPA de matérialisation doivent surtout fournir une requête de sélection, des inserts idempotents et des compteurs de backlog.
- Les tests prioritaires doivent simuler deux workers qui sélectionnent le même couple et vérifier que les contraintes DB empêchent les doublons de registre et de tâches.


## 1. Lecture des événements

### Options analysées

#### Balayage complet

- Avantage : simple pour backfill et bootstrap.
- Inconvénient : coûteux, difficile à borner, risque de relire trop souvent l'historique.
- Usage recommandé : commandes d'administration et jobs de replay, pas boucle normale.

#### Curseur global

- Avantage : simple si tous les pipelines avancent ensemble.
- Inconvénient : incompatible avec l'ajout tardif d'un pipeline, car un nouveau pipeline doit revoir l'historique alors que le curseur global a avancé.
- Usage recommandé : éviter comme source d'idempotence principale.

#### Watermark par pipeline

- Avantage : utile pour observer l'avancement d'un pipeline.
- Inconvénient : insuffisant seul pour les événements échoués ou ignorés ; ne remplace pas un registre par événement.
- Usage recommandé : métrique/optimisation, pas source de vérité.

#### Polling

- Avantage : robuste et déjà éprouvé dans Pocoma.
- Inconvénient : latence minimale liée à l'intervalle de polling.
- Usage recommandé : mécanisme de sûreté permanent.

#### Wake signal

- Avantage : réduit la latence quand un événement arrive.
- Inconvénient : un signal peut être perdu ; il ne doit pas être source de vérité.
- Usage recommandé : optimisation, combinée au polling.

#### Combinaison polling + wake signal

- Recommandation : adopter ce modèle, car il correspond au pattern existant.
- Le wake signal réveille les workers plus tôt ; le polling garantit que les événements seront finalement vus.

### Recommandation concrète

Pour la boucle normale, la matérialisation ne doit pas utiliser de claim. Le worker exécute une requête qui retourne directement un batch de couples `event x pipeline` non encore matérialisés, puis matérialise chaque couple dans la foulée. La concurrence est absorbée par les contraintes d'unicité et par des insertions idempotentes.

Recommandation concrète :

1. Les pipelines actifs sont connus par configuration ou par une petite table `pipeline_definitions`.
2. Une requête SQL produit les couples candidats en croisant les événements avec les pipelines actifs, puis en excluant les lignes déjà présentes dans `event_4_pipeline_materialization_status`.
3. Le worker appelle `supports(event)` et `materializeTasks(event)` pour chaque couple candidat.
4. Dans une transaction courte par couple, il insère la ligne de registre et les tâches avec des clés uniques.
5. Si une autre instance a déjà matérialisé le même couple, l'insert du registre ou des tâches est ignoré/absorbé par la contrainte unique.

Le polling reste le mécanisme de sûreté. Un wake signal peut simplement réveiller plus tôt la boucle de sélection, mais il ne réserve aucun travail.

## 2. Concurrence

### Plusieurs materializer workers

- Ne pas introduire de claim pour la création des tâches.
- Tous les materializers peuvent exécuter la même requête de couples candidats ; ils peuvent donc voir le même couple en parallèle.
- L'absence de doublons repose sur `unique(event_id, pipeline_id, pipeline_version)` côté registre et sur une clé unique stable côté tâches.
- Une transaction par couple fait `insert registry`, `insert tasks`, puis `mark MATERIALIZED` ; en cas de conflit d'unicité, le worker considère que le couple a déjà été pris en charge et passe au suivant.
- Une segmentation optionnelle par `pot_partition_hash` ou hash de `event_id` peut réduire les conflits, mais ne doit pas être nécessaire à la correction.

### Plusieurs task executor workers

- Réutiliser la mécanique existante : `PENDING -> CLAIMED -> ACCEPTED -> RUNNING -> DONE|FAILED`.
- Ajouter `pipeline_id`, `pipeline_version` et `task_type` comme dimensions de claim, d'observabilité et de routage.
- Segmenter les tâches par clé métier stable : idéalement `partition_key` porté par le `TaskDescriptor`, avec défaut `pot_id` quand disponible.

### Absence de doublons

- Le registre impose `unique(event_id, pipeline_id, pipeline_version)`.
- L'insertion du registre et des tâches doit être transactionnelle.
- Les tâches doivent avoir une clé d'idempotence stable, par exemple `(materialization_id, task_type, task_key)` ou un `task_deduplication_key` produit par la stratégie.
- Les exécuteurs doivent rester idempotents, car l'exécution est at-least-once.

### Reprise après crash

- Crash avant commit : aucun effet visible ; le travail sera repris.
- Crash après sélection du batch mais avant écriture : aucun état réservé ; le couple ressortira dans une requête ultérieure.
- Crash pendant la transaction de matérialisation : rollback ; reprise.
- Crash après création des tâches mais avant statut `MATERIALIZED` : à éviter par transaction unique. Si impossible, la clé unique des tâches doit absorber le replay et permettre de finaliser le registre.
- Crash pendant exécution de tâche : bail expire ; un autre worker peut reprendre ; l'exécuteur doit être idempotent.

## 3. Idempotence

### Idempotence de matérialisation

Source de vérité : `event_4_pipeline_materialization_status`.

Contrainte obligatoire :

```sql
unique(event_id, pipeline_id, pipeline_version)
```

Flux recommandé :

1. La requête de sélection retourne uniquement les couples absents du registre.
2. Pour chaque couple, le worker évalue `supports(event)`.
3. Si la stratégie ne supporte pas l'événement, le worker peut soit ne rien écrire, soit écrire `SKIPPED` si l'observabilité du non-support est nécessaire.
4. Si la stratégie supporte l'événement, le worker calcule les `TaskDescriptor`.
5. Dans une transaction courte, le worker insère le registre et les tâches ; si l'insert du registre échoue sur conflit, il abandonne le couple sans erreur fonctionnelle.
6. Le statut final du registre est `MATERIALIZED` dans la même transaction que les tâches.

### Idempotence des tâches

Ajouter une clé stable par tâche :

```text
pipeline_id
pipeline_version
source_event_id
strategy_task_key
```

ou :

```text
materialization_id
task_key
```

La stratégie doit fournir `task_key`, `task_type`, `task_payload` et `partition_key`. Le worker ne doit pas construire de clés métier implicites.

## 4. Gestion des erreurs et statuts

### Registre de matérialisation

Statuts recommandés :

- `MATERIALIZING` : optionnel, utile seulement si l'on choisit de créer le registre avant de calculer les tâches.
- `MATERIALIZED` : tâches créées, matérialisation terminée.
- `SKIPPED` : pipeline évalué comme non applicable, si l'on décide de tracer les non-supports.
- `FAILED_RETRYABLE` : erreur temporaire.
- `FAILED_PERMANENT` : erreur de stratégie ou payload non supporté, à rejouer seulement manuellement.
- `CANCELLED` ou `SUPERSEDED` : réservé aux changements de version de pipeline si nécessaire.

Pour rester simple et conforme à une matérialisation sans claim, le premier incrément peut se limiter à `MATERIALIZED`, `SKIPPED` et `FAILED`, avec un champ `failure_kind` pour distinguer retryable/permanent. Si l'on veut tracer les tentatives longues, `MATERIALIZING` peut être ajouté, mais il ne doit pas servir de verrou logique durable.

### Tâches exécutables

Statuts recommandés :

- `PENDING`
- `CLAIMED`
- `ACCEPTED`
- `RUNNING`
- `DONE`
- `FAILED_RETRYABLE`
- `FAILED_PERMANENT`
- `CANCELLED`
- `SUPERSEDED`

Pour un premier incrément compatible avec l'existant : `PENDING/CLAIMED/ACCEPTED/RUNNING/DONE/FAILED/SUPERSEDED`, avec `failure_kind` optionnel.

### Classification des erreurs

- **Erreur de lecture** : erreur DB ou désérialisation de l'enveloppe. Log en erreur, métrique `materializer.read.errors`, pas de registre si l'événement n'est pas lisible ; alerte si répétitif.
- **Erreur de sélection de pipeline** : registre en `FAILED_PERMANENT` pour le pipeline concerné si l'erreur est déterministe ; sinon `FAILED_RETRYABLE`.
- **Erreur de matérialisation** : registre en échec, sans tâche partielle visible si transaction unique.
- **Erreur d'écriture** : rollback complet ; le couple ressortira plus tard sauf si un concurrent l'a déjà matérialisé.
- **Erreur d'exécution de tâche** : statut de tâche failed, retries dans le pool, puis DLQ logique via `FAILED_PERMANENT` ou intervention manuelle.

## 5. Ajout d'un nouveau pipeline

### Problème

Un nouveau pipeline `Y v1` doit pouvoir matérialiser les anciens événements sans toucher aux registres des autres pipelines et sans dupliquer ses propres tâches.

### Recommandation

- Versionner explicitement chaque pipeline : `pipeline_id`, `pipeline_version`.
- Ne jamais utiliser un curseur global comme preuve que l'historique est traité.
- Pour un backfill, lancer un mode `replay` qui parcourt les événements par plage temporelle ou par id/version.
- Ne pas pré-créer de lignes `PENDING` pour réserver l'historique.
- Laisser la requête `events x pipeline - registry` faire ressortir les couples historiques absents ; les workers les matérialisent directement avec insertions idempotentes.

### Rejouer l'historique

Étapes :

1. Déclarer le nouveau pipeline et sa version.
2. Activer le pipeline dans le registry ou dans `pipeline_definitions`.
3. Observer le backlog calculé par requête `events x pipeline - registry`.
4. Laisser les materializers sélectionner les couples manquants et produire les tâches.
5. Laisser les executors traiter `tasks_4_pipeline`.

### Éviter les doublons

- La contrainte unique du registre bloque la double matérialisation logique.
- La clé unique de tâche bloque la double création si une transaction a partiellement réussi ou si un replay manuel est relancé.
- Les exécuteurs doivent persister leurs résultats avec leurs propres clés d'idempotence.

## 6. Transactions

### Transaction obligatoire : écriture d'un couple event/pipeline

La sélection des couples candidats n'est pas transactionnelle au sens réservation : elle peut être répétée et peut retourner le même couple à plusieurs workers. La transaction critique est uniquement l'écriture idempotente d'un couple.

Dans une seule transaction courte :

1. Insérer la ligne de registre `(event_id, pipeline_id, pipeline_version)` avec statut temporaire ou directement avec le statut final prévu.
2. Si l'insert du registre rencontre un conflit d'unicité, arrêter le traitement du couple : un concurrent l'a déjà matérialisé ou est en train de le finaliser.
3. Insérer les tâches avec leur clé unique stable.
4. Marquer le registre `MATERIALIZED`.
5. Publier un wake signal après commit.

La stratégie doit rester pure et rejouable. Le calcul des `TaskDescriptor` peut donc être fait avant la transaction pour éviter des transactions longues ; la transaction refait uniquement les inserts idempotents et les transitions finales.

### Ce qui peut être rejoué

- Lecture des événements.
- Requête de couples candidats `events x pipelines - registry`.
- Sélection `supports(event)`.
- Matérialisation pure en `TaskDescriptor`.
- Tentatives d'insertion avec `on conflict do nothing`.
- Publication de wake signals.

### Ce qui ne doit pas être non transactionnel

- Marquer un registre `MATERIALIZED` sans avoir inséré les tâches attendues.
- Insérer des tâches sans lien vers le registre.
- Marquer une tâche `DONE` avant que ses effets métier soient commités.

### Éviter les états incohérents

- `tasks_4_pipeline.materialization_id` doit référencer le registre.
- Une ligne `MATERIALIZED` doit impliquer que toutes les tâches déclarées par la stratégie existent ; si la stratégie produit zéro tâche, ce cas doit être explicitement accepté et observable.
- Publier les wake signals uniquement après commit, comme les ports transaction-aware actuels.
- Utiliser des contraintes DB plutôt que des vérifications applicatives seules.

## 7. Observabilité

### Métriques recommandées

Backlog et état :

- `pocoma.pipeline.materialization.pending{pipeline_id,pipeline_version}`
- `pocoma.pipeline.materialization.failed{pipeline_id,pipeline_version,failure_kind}`
- `pocoma.pipeline.tasks.pending{pipeline_id,pipeline_version,task_type}`
- `pocoma.pipeline.tasks.in_progress{pipeline_id,pipeline_version,task_type}`
- `pocoma.pipeline.tasks.failed{pipeline_id,pipeline_version,task_type,failure_kind}`

Débits :

- `pocoma.pipeline.materialization.created.total{pipeline_id,pipeline_version}`
- `pocoma.pipeline.tasks.created.total{pipeline_id,pipeline_version,task_type}`
- `pocoma.pipeline.tasks.completed.total{pipeline_id,pipeline_version,task_type}`
- `pocoma.pipeline.tasks.failed.total{pipeline_id,pipeline_version,task_type}`

Latences :

- `pocoma.pipeline.materialization.latency` : événement créé -> registre materialized.
- `pocoma.pipeline.task.start.latency` : tâche créée -> tâche running.
- `pocoma.pipeline.task.processing.duration` : running -> done/failed.
- `pocoma.pipeline.end_to_end.latency` : événement créé -> dernière tâche done.

Retries et leases :

- `pocoma.pipeline.materialization.retry.total`
- `pocoma.pipeline.task.retry.total`
- `pocoma.pipeline.claim.expired.total`
- `pocoma.pipeline.heartbeat.failed.total`

### Logs

Inclure systématiquement :

- `traceId` ;
- `eventId` ;
- `pipelineId` ;
- `pipelineVersion` ;
- `materializationId` ;
- `taskId` ;
- `taskType` ;
- `claimToken` uniquement en debug si nécessaire ;
- `workerId` ;
- statut précédent/nouveau ;
- type d'erreur et message tronqué.

### Dashboards

Dashboard minimal :

1. Backlog événements non matérialisés par pipeline.
2. Échecs de matérialisation par pipeline et type d'erreur.
3. Latence P50/P95/P99 de matérialisation.
4. Tâches créées par minute.
5. Backlog de tâches par pipeline/type.
6. Taux de succès/échec des exécuteurs.
7. Durée d'exécution P50/P95/P99.
8. Claims expirés et retries.

### Alertes

- Backlog de matérialisation croissant pendant plus de N minutes.
- `FAILED_PERMANENT` non nul sur une fenêtre glissante.
- Taux d'échec exécuteur supérieur à un seuil.
- P95 de matérialisation au-dessus du SLO.
- Absence de tâches consommées alors que backlog > 0.
- Heartbeats échoués ou leases expirés en hausse.

## Modèle de données proposé

### `event_4_pipeline_materialization_status`

Colonnes de base :

```text
id uuid primary key
event_id uuid not null
pipeline_id varchar(128) not null
pipeline_version integer not null
status varchar(32) not null
attempt_count integer not null
failure_kind varchar(64)
last_error text
created_at timestamptz not null
updated_at timestamptz not null
materialized_at timestamptz
failed_at timestamptz
```

Contraintes et indexes :

```text
unique(event_id, pipeline_id, pipeline_version)
index(status, updated_at, created_at)
index(pipeline_id, pipeline_version, status)
index(event_id)
```

### `tasks_4_pipeline`

Colonnes de base :

```text
id uuid primary key
materialization_id uuid not null references event_4_pipeline_materialization_status(id)
event_id uuid not null
pipeline_id varchar(128) not null
pipeline_version integer not null
task_type varchar(128) not null
task_key varchar(255) not null
task_payload jsonb/text not null
partition_key varchar(255)
partition_hash integer not null
status varchar(32) not null
claim_token uuid
claimed_by varchar(255)
lease_until timestamptz
attempt_count integer not null
failure_kind varchar(64)
last_error text
created_at timestamptz not null
updated_at timestamptz not null
claimed_at timestamptz
accepted_at timestamptz
started_at timestamptz
done_at timestamptz
failed_at timestamptz
```

Contraintes et indexes :

```text
unique(materialization_id, task_key)
unique(pipeline_id, pipeline_version, event_id, task_key)
index(status, lease_until, partition_hash, updated_at, created_at)
index(pipeline_id, pipeline_version, status)
index(event_id)
```

## Interfaces proposées

### Stratégies

```java
interface PipelineStrategy {
    PipelineId pipelineId();
    int pipelineVersion();
    boolean supports(BusinessEventEnvelope event);
    List<TaskDescriptor> materializeTasks(BusinessEventEnvelope event);
}
```

`TaskDescriptor` doit contenir au minimum :

```text
pipeline_id
pipeline_version
task_type
task_key
task_payload
partition_key
```

### Ports engine

- `PipelineMaterializationPort`
  - `findUnmaterializedEventPipelinePairs(...)`
  - `tryInsertMaterialization(...)`
  - `insertTasksForMaterialization(...)`
  - `markMaterialized(...)`
  - `markFailed(...)`
  - `countUnmaterialized(...)`
- `PipelineTaskPort`
  - `insertTasks(materializationId, descriptors)`
  - `claimPending(...)`
  - `markAccepted/Running/Done/Failed/release/heartbeat(...)`
  - `countPendingOrInProgress(...)`
- `PipelineRegistry`
  - liste les stratégies actives ;
  - trouve une stratégie par `pipeline_id/version/task_type` pour l'exécution.

## Plan de travail committable

### Étape 1 — ADR et vocabulaire

- Ajouter une ADR décrivant la séparation `materializer` / `executor`.
- Définir les termes : événement, pipeline, version, matérialisation, tâche, task key, partition key.
- Décision à prendre : nouveau module `engine-pipeline` ou extension de `engine-projection`.

### Étape 2 — Modèle engine sans persistance

- Ajouter `PipelineId`, `PipelineVersion`, `PipelineTaskType`, `TaskDescriptor`, `PipelineMaterializationStatus`, `PipelineTaskStatus`.
- Ajouter `PipelineStrategy` et `PipelineRegistry`.
- Tests unitaires de validation des records/enums.

### Étape 3 — Ports de persistance

- Ajouter `PipelineMaterializationPort` et `PipelineTaskPort`.
- Définir une méthode de sélection des couples `event x pipeline` non matérialisés, sans claim ni lease.
- Définir les opérations d'insertion idempotente du registre et des tâches.
- Tests de contrat au niveau engine avec fake in-memory si pertinent.

### Étape 4 — Migration SQL

- Ajouter les tables `event_4_pipeline_materialization_status` et `tasks_4_pipeline`.
- Ajouter contraintes uniques, indexes de sélection des couples non matérialisés et indexes claimables uniquement pour `tasks_4_pipeline`.
- Décision : `jsonb` PostgreSQL versus `text` compatible H2. Pragmatique : `text` au début si H2 doit rester simple.

### Étape 5 — Entités et repositories JPA

- Ajouter entités JPA alignées sur le modèle existant des outbox/tasks.
- Ajouter repositories avec requête de couples non matérialisés, insertions `on conflict`/gestion de `DataIntegrityViolationException`, compteurs par pipeline.
- Tests JPA ciblant concurrence entre materializers, contraintes d'unicité et absence de doublons de tâches.

### Étape 6 — Service de matérialisation

- Implémenter un service `MaterializeEventForPipelineService` sans threads.
- Le service reçoit un événement et une stratégie, produit les tasks, écrit atomiquement registre + tâches.
- Tests : stratégie non applicable, stratégie applicable sans tâche, plusieurs tâches, erreur stratégie, idempotence.

### Étape 7 — Worker de matérialisation

- Créer `EventToPipelineTaskMaterializerWorker` comme boucle de polling/wake simple, sans `ClaimableWorkLifecycle`.
- Le worker appelle `findUnmaterializedEventPipelinePairs(limit, partition)` puis matérialise les couples retournés immédiatement.
- Ajouter wake signal `PIPELINE_MATERIALIZATION_AVAILABLE` ou réutiliser un signal événement existant avec une constante dédiée ; le signal ne réserve aucun travail.
- Tests core : deux workers voient le même couple, un seul registre logique et un seul ensemble de tâches sont créés.

### Étape 8 — Exécuteur de tâches générique

- Créer `TaskExecutorWorker` au-dessus de `tasks_4_pipeline`.
- Créer un registry d'exécuteurs par `(pipeline_id, pipeline_version, task_type)`.
- Réutiliser retries, heartbeat et release du pool existant.
- Tests : routage correct, handler absent, retry, failed, done.

### Étape 9 — Observabilité

- Étendre `PocomaObservation` ou créer une observation pipeline dédiée.
- Ajouter gauges de backlog matérialisation/tâches.
- Ajouter timers et counters.
- Mettre à jour dashboard Grafana.
- Tests légers de wiring Spring si possible.

### Étape 10 — Runtime Spring

- Ajouter configuration properties : batch size, lease duration, polling interval, wake enabled, segment index/count, enabled.
- Ajouter lifecycle Spring pour les nouveaux workers.
- Décider si runtime dédié ou intégration dans les runtimes actuels.

### Étape 11 — Pipeline pilote

- Implémenter un pipeline simple en stratégie dédiée.
- Le brancher au registry.
- Vérifier backfill, flux courant, idempotence et observabilité.
- Ne migrer le calcul de balances qu'après validation du pipeline pilote.

### Étape 12 — Rejouage/backfill

- Ajouter un mode backfill qui active temporairement la sélection historique des couples `events x pipeline - registry`.
- Ajouter garde-fous : plage d'événements, dry-run, limite de batch, métriques de progression.
- Tests : relance du backfill sans doublons, sans pré-créer de claims.

## Plan d'implémentation détaillé — première partie : worker de matérialisation

Cette première partie d'implémentation doit livrer une mécanique complète mais volontairement limitée : sélectionner les couples `event x pipeline` non matérialisés, appeler les stratégies, écrire registre + tâches de façon idempotente, puis réveiller les exécuteurs. Elle ne doit pas encore migrer les pipelines existants ni implémenter tous les exécuteurs génériques.

### Objectif de l'incrément

Livrer un `EventToPipelineTaskMaterializerWorker` fonctionnel, testable et observable, sans claim côté matérialisation, en factorisant la mécanique wake/poll déjà présente pour qu'elle puisse servir à deux familles de workers :

- les workers à claim existants, qui utilisent `ClaimableWorkDispatcher` ;
- les workers sans claim, comme le materializer, qui ont seulement besoin d'une boucle `wake or timeout -> runOnce until drained -> wait again`.

### Étape 1 — Extraire une boucle wake/poll générique

**Ce qui est mis en place**

- Introduire une petite abstraction dans `orchestrator-claimable-work-dispatcher`, par exemple `WakePollingRunner` ou `WakePollingLoop` non dépréciée.
- Cette abstraction reçoit :
  - un `Runnable`/`IntSupplier runOnce` ;
  - un prédicat `shouldContinueDraining`, typiquement `processedCount > 0` ;
  - les paramètres `enabled`, `workerId`, `pollingInterval`, `wakeSignalsEnabled` ;
  - un `WorkWakeBus<S, K>`, des signaux et un prédicat de clé.
- `ClaimableWorkDispatcher` délègue sa boucle actuelle à cette abstraction au lieu de posséder lui-même toute la logique `while running -> runOnce -> awaitWakeUp`.
- Le materializer pourra réutiliser exactement cette boucle sans implémenter `ClaimableWorkLifecycle`.

**Impacts**

- Aucun changement fonctionnel attendu pour les dispatchers existants.
- La responsabilité de wake/poll devient partagée et réutilisable, tandis que le claim reste isolé dans `ClaimableWorkDispatcher`.
- Le risque principal est une régression de lifecycle (`start`, `stop`, interruption, wait timeout). Il faut donc garder l'extraction très mécanique.

**Tests ajoutés ou mis à jour**

- Ajouter des tests unitaires du nouveau runner :
  - `runOnce` est appelé au démarrage ;
  - le runner draine tant que `runOnce > 0` ;
  - un wake signal déclenche une nouvelle itération avant le timeout ;
  - `stop` interrompt l'attente proprement ;
  - les exceptions de `runOnce` sont loggées et la boucle continue après attente.
- Mettre à jour les tests existants de `ClaimableWorkDispatcher`/workers de projection pour vérifier que le comportement wake/poll n'a pas changé.

### Étape 2 — Introduire le modèle minimal de pipeline côté engine

**Ce qui est mis en place**

- Ajouter les value objects et records nécessaires au materializer :
  - `PipelineId` ;
  - `PipelineVersion` ou un `int` validé ;
  - `PipelineDefinition` ;
  - `EventPipelineMaterializationCandidate` ;
  - `TaskDescriptor` ;
  - `MaterializationResult`.
- Ajouter `PipelineStrategy` avec `supports(event)` et `materializeTasks(event)`.
- Ajouter `PipelineRegistry` qui expose les stratégies actives et permet de retrouver une stratégie par `(pipeline_id, pipeline_version)`.

**Impacts**

- Les stratégies deviennent le seul endroit où vit l'intelligence métier de transformation.
- Le worker reste orchestrateur et ne connaît pas les détails de chaque pipeline.
- Cet incrément peut rester sans dépendance Spring pour garder les tests rapides.

**Tests ajoutés ou mis à jour**

- Tests unitaires de validation des records : champs obligatoires, version positive, `task_key` non vide.
- Test du registry : refus des doublons `(pipeline_id, pipeline_version)`, recherche d'une stratégie active, comportement si stratégie absente.

### Étape 3 — Ajouter les ports de matérialisation sans claim

**Ce qui est mis en place**

- Ajouter un port de lecture/sélection, par exemple `PipelineMaterializationPort` :
  - `findUnmaterializedEventPipelinePairs(limit, partition, upperBound, activePipelines)` ;
  - `materialize(candidate, descriptors)` ou des opérations plus fines `tryInsertMaterialization`, `insertTasks`, `markMaterialized` ;
  - `markFailed(candidate, failureKind, error)` si l'on choisit de persister les erreurs ;
  - `countUnmaterialized(...)` pour l'observabilité.
- Le port ne contient aucune méthode `claim`, `release`, `heartbeat`, `markAccepted` ou `markRunning`.
- Ajouter un port de publication de wake signal pour indiquer que de nouvelles tâches sont disponibles après commit.

**Impacts**

- Le contrat de persistance exprime explicitement la mécanique souhaitée : sélection non réservante + écriture idempotente.
- Les contraintes d'unicité sont considérées comme une partie normale du protocole de concurrence.
- Les futurs adaptateurs JPA devront distinguer `ALREADY_MATERIALIZED` d'une vraie erreur d'écriture.

**Tests ajoutés ou mis à jour**

- Tests de contrat avec fake in-memory :
  - un couple absent est retourné ;
  - un couple déjà `MATERIALIZED` n'est plus retourné ;
  - deux appels concurrents à `materialize` sur le même couple aboutissent à un seul résultat logique ;
  - les tâches sont dédupliquées par `task_key`.

### Étape 4 — Créer le service applicatif de matérialisation d'un couple

**Ce qui est mis en place**

- Ajouter `MaterializeEventForPipelineService` ou `PipelineMaterializationService`.
- Entrée : un `EventPipelineMaterializationCandidate`.
- Orchestration :
  1. charger ou recevoir l'enveloppe événement ;
  2. retrouver la stratégie ;
  3. appeler `supports(event)` ;
  4. appeler `materializeTasks(event)` si applicable ;
  5. demander au port d'écrire registre + tâches dans une transaction idempotente ;
  6. retourner un résultat typé : `MATERIALIZED`, `SKIPPED`, `ALREADY_MATERIALIZED`, `FAILED`.
- Le service ne fait pas de polling, pas de thread, pas de claim.

**Impacts**

- La logique est testable sans worker et sans Spring lifecycle.
- Les erreurs de stratégie peuvent être classées avant d'être persistées.
- Le worker pourra se limiter à sélectionner des candidats et appeler ce service.

**Tests ajoutés ou mis à jour**

- Tests unitaires :
  - stratégie absente ;
  - `supports=false` ;
  - `supports=true` avec zéro tâche ;
  - `supports=true` avec plusieurs tâches ;
  - exception dans `supports` ;
  - exception dans `materializeTasks` ;
  - résultat `ALREADY_MATERIALIZED` venant du port.

### Étape 5 — Implémenter les migrations SQL du registre et des tâches

**Ce qui est mis en place**

- Ajouter une migration Flyway pour `event_4_pipeline_materialization_status` sans colonnes de claim.
- Ajouter une migration Flyway pour `tasks_4_pipeline` avec colonnes de claim, car l'exécution des tâches reste claimable.
- Ajouter les contraintes :
  - `unique(event_id, pipeline_id, pipeline_version)` sur le registre ;
  - `unique(pipeline_id, pipeline_version, event_id, task_key)` sur les tâches ;
  - index de sélection des événements et index de claim des tâches.

**Impacts**

- La base garantit l'idempotence de matérialisation.
- Les materializers concurrents peuvent faire des tentatives redondantes sans créer de doublons.
- Le coût principal est le volume du registre ; les indexes doivent être pensés pour la requête `events x pipeline - registry`.

**Tests ajoutés ou mis à jour**

- Tests JPA ou tests de migration :
  - contraintes uniques effectives ;
  - insertion de deux matérialisations identiques impossible ;
  - insertion de deux tâches avec même `task_key` impossible ;
  - compatibilité PostgreSQL/H2 selon le support attendu.

### Étape 6 — Implémenter l'adaptateur JPA de matérialisation

**Ce qui est mis en place**

- Ajouter entités/repositories pour le registre et `tasks_4_pipeline`.
- Ajouter une projection JPA/native query pour `findUnmaterializedEventPipelinePairs`.
- Implémenter l'écriture transactionnelle :
  - insert registre ;
  - insert tâches ;
  - statut final `MATERIALIZED` ;
  - conversion des conflits uniques en `ALREADY_MATERIALIZED` ou insertion idempotente.
- Garder la publication de wake signal hors transaction ou transaction-aware après commit.

**Impacts**

- C'est l'étape où la concurrence réelle est validée.
- L'adaptateur doit éviter de masquer les erreurs DB non liées aux conflits d'unicité.
- Si les pipelines actifs sont seulement en mémoire, la requête pourra être exécutée par pipeline actif plutôt que via `pipeline_definitions`.

**Tests ajoutés ou mis à jour**

- Tests JPA :
  - la requête retourne un couple absent ;
  - elle ne retourne plus un couple matérialisé ;
  - deux transactions concurrentes tentent le même couple et ne produisent qu'une matérialisation ;
  - un conflit tâche ne crée pas de doublon ;
  - `countUnmaterialized` reflète le backlog attendu.

### Étape 7 — Implémenter le worker core sans Spring

**Ce qui est mis en place**

- Ajouter `EventToPipelineTaskMaterializerWorker` dans un module supra/core dédié.
- Le worker reçoit :
  - le port de sélection ;
  - le service de matérialisation ;
  - le registry des pipelines ;
  - le runner wake/poll factorisé ;
  - les settings `enabled`, `batchSize`, `pollingInterval`, `wakeSignalsEnabled`, `segmentIndex`, `segmentCount`, `safetyDelay`.
- `runOnce` :
  1. récupérer les pipelines actifs ;
  2. calculer `upperBound`;
  3. lire un batch de couples ;
  4. matérialiser chaque couple ;
  5. retourner le nombre de couples effectivement examinés ou matérialisés pour permettre le drain.

**Impacts**

- Le worker reste simple et ne dépend pas du claimable lifecycle.
- La factorisation wake/poll évite de dupliquer la boucle de fond déjà éprouvée.
- Le réglage de `runOnce` doit éviter une boucle infinie si tous les couples candidats sont déjà matérialisés par des concurrents entre sélection et écriture.

**Tests ajoutés ou mis à jour**

- Tests core :
  - `runOnce` appelle le port avec le bon `limit`, la bonne partition et le bon `upperBound` ;
  - chaque candidat est envoyé au service ;
  - les résultats `ALREADY_MATERIALIZED` ne sont pas des erreurs ;
  - les exceptions d'un candidat n'empêchent pas le traitement des candidats suivants si cette politique est retenue ;
  - le worker draine plusieurs batches tant que la sélection retourne du travail.

### Étape 8 — Ajouter le wiring Spring et les propriétés

**Ce qui est mis en place**

- Ajouter une configuration Spring pour instancier :
  - settings du materializer ;
  - worker core ;
  - lifecycle `SmartLifecycle` ou équivalent ;
  - subscriber wake sur événement métier disponible ;
  - publisher wake vers tâches disponibles après matérialisation.
- Réutiliser les conventions de propriétés existantes : `enabled`, `batch-size`, `polling-interval`, `wake-signals-enabled`, `segment-index`, `segment-count`.
- Ajouter `safety-delay` pour la borne supérieure de lecture.

**Impacts**

- Le materializer devient activable/désactivable par profil/runtime.
- Le wake signal peut être NATS/Spring selon le runtime, comme pour les dispatchers existants.
- Il faut veiller à ne pas lancer deux mécanismes concurrents sur le même pipeline existant pendant la phase de transition.

**Tests ajoutés ou mis à jour**

- Tests Spring de configuration :
  - les beans sont créés quand `enabled=true` ;
  - le lifecycle démarre/arrête le worker ;
  - les propriétés segment/polling sont correctement bindées ;
  - aucun bean n'est créé ou aucun thread ne démarre quand `enabled=false`.

### Étape 9 — Ajouter l'observabilité minimale

**Ce qui est mis en place**

- Ajouter métriques :
  - backlog de couples non matérialisés ;
  - nombre de couples examinés ;
  - nombre de matérialisations réussies ;
  - nombre de conflits d'unicité absorbés ;
  - nombre d'échecs ;
  - durée de matérialisation.
- Ajouter logs structurés avec `eventId`, `pipelineId`, `pipelineVersion`, `materializationId`, `workerId`.

**Impacts**

- On peut valider en production que le worker avance sans regarder uniquement les tâches créées.
- Les conflits d'unicité deviennent visibles sans être considérés comme des erreurs.
- Attention à la cardinalité : pas d'`eventId` ou `materializationId` en label métrique.

**Tests ajoutés ou mis à jour**

- Tests unitaires de l'observation avec registry Micrometer simple si disponible.
- Tests de logs non indispensables, sauf si le projet a déjà une convention de capture.

### Étape 10 — Test d'intégration bout-en-bout minimal

**Ce qui est mis en place**

- Ajouter un pipeline de test très simple, par exemple `test_echo_pipeline v1`, qui supporte un événement connu et produit une tâche déterministe.
- Lancer le materializer sur un événement outbox existant.
- Vérifier :
  - registre `MATERIALIZED` ;
  - tâches créées avec `task_key` stable ;
  - relance du materializer sans doublons ;
  - deux materializers concurrents sans doublons.

**Impacts**

- Cet incrément prouve la mécanique sans migrer les projections de balances.
- Il donne un filet de sécurité pour ajouter ensuite les vrais pipelines.

**Tests ajoutés ou mis à jour**

- Test d'intégration JPA/Spring ou test runtime léger :
  - un événement produit une tâche ;
  - la relance est idempotente ;
  - la concurrence est idempotente ;
  - le wake signal de tâches est publié après commit si l'infrastructure de test permet de l'observer.

### Ordre de commits recommandé

1. Extraction wake/poll générique + tests de non-régression des dispatchers existants.
2. Modèle engine et registry de pipelines + tests unitaires.
3. Ports de matérialisation sans claim + service applicatif + tests avec fake.
4. Migrations SQL + entités/repositories JPA + tests de contraintes.
5. Adaptateur JPA de matérialisation + tests de concurrence.
6. Worker core sans Spring + tests de run loop/runOnce.
7. Wiring Spring + properties + tests de configuration.
8. Observabilité minimale + tests éventuels.
9. Test d'intégration bout-en-bout avec pipeline de test.

## Décisions d'architecture à prendre avant implémentation

1. **Table source d'événements** : `business_event_outbox` suffit-il comme table `events`, ou faut-il une table événement métier séparée ?
2. **Portée du nouveau modèle** : module générique `engine-pipeline` ou extension de `engine-projection` ?
3. **Traçage des non-supports** : créer une ligne `SKIPPED` pour chaque pipeline non applicable, ou ne tracer que les pipelines applicables ? Recommandation : ne tracer que les applicables pour limiter le volume.
4. **Version de pipeline** : entier manuel, semver string, ou hash de configuration ? Recommandation : entier explicite et documenté.
5. **Payload de tâche** : `jsonb` PostgreSQL ou `text` portable ? Recommandation : `text` au départ si H2 reste supporté.
6. **Backfill** : job applicatif interne, commande admin, ou script SQL ? Recommandation : service applicatif batchable avec dry-run.
7. **Classification d'erreur** : enum détaillé dès le départ ou simple `FAILED` + `failure_kind` ? Recommandation : `FAILED` + `failure_kind`, sans statuts de claim pour la matérialisation.
8. **Coalescing** : autoriser les stratégies à coalescer des tâches ou imposer une tâche par event ? Recommandation : laisser la stratégie produire des `task_key` stables ; coalescing explicite seulement par stratégie.
9. **Ordonnancement par agrégat** : faut-il garantir l'ordre par pot/aggregate ? Si oui, segmenter par clé métier et concevoir les tasks idempotentes face aux replays.
10. **Publication des wake signals** : Spring event transaction-aware, NATS, ou les deux ? Recommandation : même pattern que les projections actuelles.

## Risques techniques et compromis

### Volume du registre

Un registre par événement et pipeline peut croître vite. Compromis : ne créer des lignes que pour les pipelines applicables et archiver les lignes `MATERIALIZED` anciennes si les besoins d'audit le permettent.

### Verrous longs pendant la stratégie

Si `materializeTasks` devient coûteux, la transaction peut durer trop longtemps. Compromis : rendre la stratégie pure, calculer les descriptors hors transaction, puis ouvrir une transaction courte qui tente l'insert du registre et des tâches ; un conflit d'unicité indique simplement qu'un concurrent a déjà gagné.

### Idempotence incomplète des exécuteurs

Même avec des claims robustes, une tâche peut s'exécuter deux fois après expiration de bail. Compromis : imposer des clés d'idempotence dans les effets métier des handlers.

### Évolution de pipeline

Changer la logique sans changer `pipeline_version` rend les replays ambigus. Compromis : versionner systématiquement toute modification de matérialisation ou d'exécution non compatible.

### Complexité d'observabilité

Trop de labels peuvent exploser la cardinalité Prometheus. Compromis : labels bornés (`pipeline_id`, `pipeline_version`, `task_type`, `status`, `failure_kind`) et jamais `event_id` ou `task_id` en métrique.

### Migration depuis le pipeline de projection actuel

Remplacer directement `projection_tasks` est risqué. Compromis : implémenter la nouvelle mécanique en parallèle, valider avec un pipeline pilote, puis migrer les balances dans une étape séparée.

## Recommandation finale

Le chemin le plus simple et robuste consiste à séparer deux mécaniques : matérialisation sans claim, protégée par contraintes d'unicité, puis exécution avec le claimable work existant. Pour la matérialisation, la base reste la source de vérité via le registre `event_4_pipeline_materialization_status` et les clés uniques de `tasks_4_pipeline`. Pour l'exécution, les claims avec leases et fencing tokens restent pertinents. La nouveauté essentielle est donc le registre, qui devient la preuve d'idempotence et le point d'entrée des backfills/replays.
