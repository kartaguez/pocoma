# Pocoma — Lot 7.5 — Scheduling durable des projections applicables

## 1. Objectif et définition du done

Le Lot 7.5 remplace le producer Event→Task configuré génération par génération par un unique
producer logique piloté par le catalogue canonique. Pour chaque `BusinessEvent` durable E portant la
version V, il assure une intention de projection pour chaque `PipelineVersionDefinition` pertinente
et applicable à V.

```text
BusinessEvent E @ V
  -> pipelineIds pertinents pour E
  -> définitions canoniques de ces pipelineIds dont appliesTo(V) == true
  -> une Task durable par (eventId, pipelineId, pipelineVersion)
```

Le lot est terminé lorsque :

- un seul runtime logique, réplicable horizontalement, évalue toutes les définitions canoniques ;
- une Task Event-derived a l'identité durable `(eventId, pipelineId, pipelineVersion)` et contient le
  payload d'exécution `(pipelineId, pipelineVersion, potId, potVersion)` ;
- toutes les générations pertinentes et applicables sont assurées atomiquement avant le succès de
  l'exécution de scheduling ;
- une nouvelle définition applicable rend immédiatement les anciens Events candidats sous le runtime
  normal, sans replay spécial ni mutation des anciens slots ;
- les duplicates, courses, crashes et reprises convergent sans overwrite ;
- le producer ne lit aucune donnée du read store et ne consulte ni watermark ni stratégie reader ;
- aucune projection n'est exécutée dans ce lot.

## 2. Sources canoniques consultées

Documents lus avant l'inspection du code :

- `docs/architecture/read-side-target.md` ;
- `docs/architecture/read-side-current-state.md` ;
- `docs/architecture/write-side-closure.md` ;
- `docs/architecture/module-dependency-matrix.md` ;
- `docs/plans/lot-7-read-side-implementation-plan.md` ;
- `docs/plans/lot-7.3-generic-projection-foundation-plan.md` ;
- `docs/plans/lot-7.3.1-pipeline-applicability-alignment-plan.md` ;
- `docs/plans/lot-7.4-source-version-watermark-plan.md` ;
- `docs/README.md` ;
- `docs/architecture/consumption-event-pull-runtime.md` ;
- `docs/architecture/consumption-task-balance-runtime.md`.

Le présent plan ne corrige pas le plan directeur. Aucune contradiction canonique bloquante n'a été
identifiée. La table `event_4_pipeline_materialization_status`, décrite comme donnée de transport
legacy dans l'état actuel, doit toutefois disparaître au profit de l'identité portée directement par
la Task ; ce retrait est une convergence vers la cible, pas une modification de celle-ci.

## 3. État actuel vérifié dans le code

### 3.1 Catalogue et applicabilité

- `PocomaPipelineDefinitions.all()` expose actuellement une liste immuable contenant
  `balance-projection/v2`, applicable depuis V1.
- `PipelineDefinitionRegistry` indexe exactement par `PipelineDefinition`, refuse les doublons,
  expose `all()` et ne fait ni `MAX` ni fallback.
- `PipelineVersionDefinition.appliesTo(long)` délègue à `VersionApplicability`, dont les bornes sont
  directement lisibles.
- Le registry sait déjà énumérer toutes les définitions ; aucune nouvelle recherche de « version
  active » n'est nécessaire.

### 3.2 Runtime Event actuel

- `EventConsumptionRuntimeConfiguration` exige aujourd'hui
  `pocoma.event-consumption.pipeline-id` et `pipeline-version` et construit un seul
  `PipelineDefinition`.
- `EventConsumptionLocator` traite un couple Event/génération et forme la clé :

  ```text
  EVENT[eventId] / PIPELINE[pipelineId,pipelineVersion]
  ```

- `JpaEventConsumptionDiscoveryAdapter` et
  `JpaEventConsumptionDiscoveryRepository.findNextEligible` reçoivent cette génération exacte.
- La requête joint `consumption_slots` sur cette clé et ne retourne que les slots absents ou `PENDING`
  à nouveau claimables. Un slot `DONE` exclut donc définitivement l'Event pour cette génération.
- La requête ordonne par `(event.version, event.created_at, event.id)` et le cursor
  `EventOrderingKey` est local à un `ConsumptionSearch`.
- Après chaque exécution acquise, `SequentialConsumptionOrchestrator` ferme la recherche puis en ouvre
  une nouvelle ; aucun cursor n'est conservé entre cycles ou après une exécution.

Le runtime actuel pourrait redécouvrir un ancien Event en démarrant un deuxième process configuré
pour une nouvelle génération, mais ce serait un producer logique par génération. Cette forme ne
satisfait pas la cible catalog-driven du Lot 7.5.

### 3.3 Planning et création actuels

- `TaskCreationStrategy` est indexée par `PipelineDefinition` exacte et porte actuellement aussi le
  mapping fonctionnel via `supports(BusinessEvent)`. Cette forme mélange pertinence du pipeline et
  construction propre à une génération ; le Lot 7.5 doit séparer ces responsabilités.
- `PlanTasksForEventService` choisit une stratégie exacte et retourne actuellement zéro à N
  `TaskDescriptor`.
- `CreateTasksForEventService` planifie puis appelle `TaskCreationPort.createIfAbsent` pour une seule
  génération.
- `JpaTaskCreationAdapter` écrit sous `Propagation.MANDATORY` : ses écritures rejoignent donc la
  transaction ouverte par `TransactionalExecuteConsumptionUseCase`.
- L'adapter utilise `event_4_pipeline_materialization_status` comme ledger d'idempotence. Son index
  unique `(event_id, pipeline_id, pipeline_version)` porte aujourd'hui l'identité logique ; les Tasks
  physiques sont ensuite liées par `materialization_id`.
- `tasks_4_pipeline` autorise encore plusieurs lignes pour un même triplet lorsque leurs `task_key`
  diffèrent. Ses index uniques actuels sont `(materialization_id, task_key)` et
  `(pipeline_id, pipeline_version, event_id, task_key)`.
- `tasks_4_pipeline` contient déjà `event_id`, `pipeline_id`, `pipeline_version`, `target_version` et
  `partition_key`. Le `potId` est actuellement surchargé dans `partition_key` sous forme de texte,
  sans colonne structurelle dédiée.
- `BalanceTaskCreationStrategy` crée actuellement exactement un descriptor pour tout
  `BusinessEvent`, mais son JSON ne répète que `potId` et `targetVersion`.
- `RecordedTask` reconstitue déjà au runtime l'identité de pipeline, le `potId` et la version cible ;
  `ComputeBalancesRecordedTaskMapper` vérifie la cohérence du JSON avec les colonnes durables.

### 3.4 Transaction, provenance et fencing

Le chemin actuel confirmé est :

```text
discovery best effort
  -> acquire court sur ConsumptionKey
  -> transaction Execute
       -> reload Event autoritatif via JpaEventPort
       -> création/adoption Task sous transaction MANDATORY
       -> ConsumptionInput Event
       -> ConsumptionResult Task(s)
       -> CAS terminal currentClaimId
  -> commit
```

`TransactionalExecuteConsumptionUseCase` englobe le callback métier, la provenance et le CAS final.
Une perte de claim lève `LostClaimException` et rollbacke l'ensemble. Les tests PostgreSQL existants
du runtime Event prouvent déjà l'atomicité d'une génération, le retry et le takeover ; le Lot 7.5 doit
étendre cette preuve au batch de toutes les générations applicables.

### 3.5 Conclusion de l'inspection

Ce qui est réutilisable :

- catalogue, registry et `appliesTo` ;
- le comportement métier actuellement contenu dans `TaskCreationStrategy.supports`, à extraire ou
  encadrer comme relation stable Event→`pipelineId` ;
- moteur générique de consumption, claims, leases, fencing et provenance ;
- relecture autoritative de l'Event ;
- transaction Execute et adoption concurrente PostgreSQL ;
- runtime Task/projector, qui reste inchangé fonctionnellement.

Ce qui doit changer :

- composition Event catalog-driven plutôt que propriétés pipeline/version ;
- discovery de couples Event/définition depuis tout le catalogue ;
- orchestration de planning de toutes les définitions pertinentes et applicables ;
- identité technique du consumer permettant une nouvelle génération sans invalider les anciens slots ;
- Task physique unique et autonome par triplet, sans parent materialization legacy ;
- vérification immédiate du payload lors d'une adoption.

## 4. Invariants verrouillés

1. `PipelineVersionDefinition.appliesTo(potVersion)` est l'unique règle de production.
2. La pertinence est une relation `(BusinessEvent, pipelineId)`, identique pour toutes les générations
   d'un même pipeline. Elle ne constitue pas une policy par génération.
3. `PipelineSelectionStrategy` est reader-only et n'est importée par aucun chemin du producer.
4. Il n'existe aucune policy `active`, `preferred`, `enabledForProduction` ou sélection par `MAX`.
5. L'identité Event-derived est `(eventId, pipelineId, pipelineVersion)`.
6. Cette identité n'est pas universelle : les futures Tasks administratives auront une provenance et
   une identité propres, sans `eventId`.
7. Deux Events distincts restent distincts, même avec les mêmes `potId` et `potVersion`.
8. Une même génération applicable produit au plus une Task physique pour un Event.
9. La Task porte structurellement `pipelineId`, `pipelineVersion`, `potId` et `potVersion` ; `eventId`
   reste son origine/identité Event-derived, pas une composante du payload d'exécution commun.
10. Une adoption avec un autre `potId` ou une autre `potVersion` échoue sans overwrite.
11. Le producer ne consulte jamais artifact, failure, head, status, watermark, Query Kernel ou read
   selection.
12. Une exécution de scheduling n'est `SUCCESS` qu'après assurance de toutes les intentions
    pertinentes et applicables calculées depuis le catalogue courant.
13. Un Event sans génération applicable ne crée aucune Task et ne boucle pas inutilement.
14. Une nouvelle définition crée naturellement une nouvelle identité de consommation pour tout ancien
    Event auquel elle s'applique.
15. Les projectors et le runtime Task ne sont pas modifiés pour attendre le watermark.

## 5. Modèle cible Event→Tasks

Le use case entrant devient conceptuellement un scheduling de l'Event entier, et non la création pour
une génération fournie par le runtime :

```text
schedule(RecordedEvent E, triggerDefinition)
  -> reload déjà effectué par le locator
  -> déterminer une fois les pipelineIds pertinents pour E
  -> registry.all(), ordre déterministe pipelineId puis pipelineVersion
  -> conserver les définitions de ces pipelineIds
  -> filtrer appliesTo(E.version)
  -> récupérer le builder exact de chaque génération
  -> planifier toutes les intentions sans écrire
  -> si toutes sont valides, ensure/adopt toutes les Tasks
  -> résultat par génération : Created ou Adopted
```

`triggerDefinition` identifie la paire Event/définition qui a rendu le travail découvrable et la clé
de slot acquise. Elle ne limite pas le batch : après relecture autoritative, le service réévalue tout
le catalogue courant et assure toutes les définitions applicables pertinentes.

Le planning doit précéder toute écriture. Une rejection déterministe d'une stratégie ou une erreur de
configuration ne doit donc jamais laisser v1 créée alors que v2 a échoué. Une erreur technique après
le début des writes est couverte par le rollback de la transaction Execute.

Le contrat existant zéro-à-N doit converger, pour les Tasks de projection Event-derived, vers zéro ou
une Task par définition applicable d'un pipeline pertinent :

- pipeline non pertinent pour l'Event : zéro intention pour toutes ses générations ;
- pipeline pertinent et définition non applicable : zéro intention pour cette définition ;
- pipeline pertinent et définition applicable : exactement une intention construite par le binding
  exact de la génération ;
- une stratégie retournant plus d'une Task pour une définition est une erreur de configuration.

La forme Java peut conserver temporairement `TaskCreationPlan` et sa liste si la cardinalité est
validée explicitement ; il n'est pas nécessaire de créer une hiérarchie de payloads. En revanche, le
port de persistence doit recevoir un batch immuable de toutes les intentions planifiées ou être appelé
en boucle uniquement sous la transaction Execute déjà ouverte. Un port batch est préférable car il
rend l'atomicité et les résultats `Created`/`Adopted` explicites.

## 6. Catalogue, applicabilité et scope des pipelines

Chaque composition root du producer construit :

```text
new PipelineDefinitionRegistry(PocomaPipelineDefinitions.all())
```

`PipelineDefinitionRegistry.all()` est suffisant pour l'énumération. Aucune méthode de sélection de
version ne doit être ajoutée. L'ordre du `Map.copyOf` n'étant pas un contrat d'itération, le service
trie une copie locale par `pipelineId.value()` puis `pipelineVersion` uniquement pour obtenir des
résultats et tests déterministes ; cet ordre n'est pas une priorité fonctionnelle.

Le mapping Event→pipelines doit être explicite et stable au niveau `pipelineId` :

- un composant de pertinence associe un `BusinessEvent` à zéro ou plusieurs `pipelineId` ;
- toutes les définitions d'un même `pipelineId` héritent de cette unique décision ;
- chaque binding exact par `PipelineDefinition` construit ensuite la forme de Task/payload de sa
  génération, sans pouvoir rejeter l'Event pour une raison de pertinence ;
- le runtime assemble exactement un composant de pertinence par `pipelineId` supporté et exactement
  un binding de construction pour chaque définition canonique qu'il doit produire ;
- absence ou doublon à l'un de ces deux niveaux est rejeté au démarrage.

L'implémentation peut adapter les `TaskCreationStrategy` existantes sans refonte large, mais leur
`supports(BusinessEvent)` ne doit plus être une décision libre par version. Si la méthode est conservée
temporairement, un wrapper de famille calcule la pertinence une seule fois et un garde-fou de wiring
vérifie que toutes les stratégies d'un même `pipelineId` partagent le même objet/contrat de pertinence.
Un test doit rendre impossible `v1.supports(E) != v2.supports(E)` sous le même `pipelineId`.

Cette séparation n'introduit pas une troisième policy de production :

```text
relevant(Event, pipelineId) AND definition.appliesTo(Event.version)
  -> construire/assurer la Task de cette définition
```

Elle ne suppose pas un seul pipeline : Balance, READ_POT et toute future famille possèdent chacune
leur pertinence stable et leurs bindings exacts. Pour le catalogue actuel, le runtime assemble la
famille Balance et un binding pour chaque définition dont le `pipelineId` vaut
`BalancePipeline.PIPELINE_ID`. L'ajout futur de READ_POT ajoutera sa famille lors du lot qui fixe son
identité ; il ne doit pas être anticipé ici.

Le service revérifie toujours `definition.appliesTo(event.version())` après le reload. Les bornes
éventuellement utilisées par le discovery ne sont qu'une présélection structurelle issue de la même
définition.

## 7. Consumer identity et slots

La clé cible est :

```text
consumable = EVENT[eventId]
consumer   = PROJECTION_TASK_SCHEDULER[pipelineId,pipelineVersion]
```

Le type `PROJECTION_TASK_SCHEDULER` distingue le nouveau producer catalog-driven du legacy
`PIPELINE`. Les composantes de génération ne signifient ni génération active ni runtime séparé :
elles constituent la révision naturelle et immuable du travail de scheduling.

Cette clé ferme le problème de terminalité :

- le slot `(E, scheduler, v1)` peut rester `DONE` ;
- l'ajout de v2 crée la nouvelle clé `(E, scheduler, v2)` ;
- aucun slot n'est réouvert, supprimé ou modifié manuellement ;
- plusieurs replicas du même runtime utilisent les mêmes clés et sont arbitrés par acquire/fencing ;
- tous les replicas chargent le même catalogue statique du build.

Une clé globale `PROJECTION_TASK_SCHEDULER[]` est interdite : son slot terminal signifierait à tort que
E est planifié pour toutes les générations futures. Conserver `PIPELINE[...]` comme type est également
écarté afin de ne pas confondre ancien producer configuré par génération et nouveau scheduler unique.

Le runtime est unique logiquement parce qu'une seule composition catalog-driven produit toutes les
clés et toutes les intentions. Il reste réplicable : plusieurs workers partagent ce consumer logique,
les mêmes définitions et les mêmes contraintes durables.

## 8. Discovery et re-sélection des anciens Events

### 8.1 Candidat structurel

Le discovery doit exposer un candidat contenant au minimum :

```text
eventId, potId, potVersion, recordedAt, trigger PipelineDefinition
```

Il reçoit la collection immuable des `PipelineVersionDefinition` du registry. Sa requête est une
présélection best effort ; elle ne décode pas le payload Event et n'exécute pas la policy métier
`supports`.

Conceptuellement, pour chaque définition D fournie :

```text
D.applicability contains event.version
and no Task(event.id, D.pipelineId, D.pipelineVersion)
and scheduler slot EVENT[event.id]/PROJECTION_TASK_SCHEDULER[D] is absent
    or is PENDING and claimable now
```

Le SQL peut matérialiser les définitions fournies sous forme d'un `VALUES` paramétré
`(pipeline_id, pipeline_version, from_version, through_version)` puis joindre les Events. Il ne
recalcule pas l'applicabilité et ne charge aucun catalogue depuis SQL : les bornes sont des données
issues des objets canoniques. Une alternative avec une courte requête par définition n'est acceptable
que si elle conserve l'ordre global et la fairness décrits ci-dessous.

La décision autoritative reste dans le callback : reload de E, calcul de la pertinence par
`pipelineId`, lookup exact de D, puis `appliesTo`. Un candidat devenu obsolète entre discovery et
Execute est adopté/idempotent ou termine en succès sans Task si son pipeline n'est finalement pas
pertinent.

La Task Event-derived est une intention durable : le Lot 7.5 ne prévoit aucune suppression courante
de ces lignes. Grâce au commit commun Task + slot terminal, un slot scheduler `DONE` avec la Task du
triplet absente est donc une corruption à détecter, et non un état normal que le discovery devrait
masquer ou réparer. Une future politique explicite de rétention devra traiter ensemble cet invariant
et la rééligibilité avant d'autoriser la suppression d'une Task.

### 8.2 Pourquoi un ancien Event réapparaît

À T0, le catalogue contient v1. E@73 produit la Task v1 et le slot scheduler v1 devient terminal.

À T1, le build contient v1 et v2, avec `v2.appliesTo(73) == true` :

- le registry fournit désormais v2 au discovery ;
- la jointure trouve E@73 dans les bornes de v2 ;
- aucune Task `(E, pipeline, v2)` n'existe ;
- aucune clé `PROJECTION_TASK_SCHEDULER[pipeline,v2]` n'existe pour E ;
- E@73 est donc retourné sans mode replay ;
- l'exécution assure v2 et adopte v1 sans la modifier.

Le slot v1 terminal n'intervient jamais dans la jointure v2. La colonne legacy `status` de
`business_event_outbox` n'intervient pas non plus.

### 8.3 Zéro génération applicable

Si aucune définition ne couvre V, la jointure catalogue/Event ne produit aucun candidat : aucune
Task et aucun slot scheduler ne sont créés. Ce choix évite une boucle vide et laisse l'Event
naturellement découvrable lorsqu'une future définition couvrant V apparaît.

Si un candidat best effort acquis devient non applicable/non pertinent après relecture, l'exécution
termine `SUCCESS` avec l'Event comme input et zéro result. Ce succès ne vaut que pour la clé de la
définition déclencheuse ; il ne ferme aucune génération future.

### 8.4 Cursor, pagination et fairness

L'ordre cible étend `EventOrderingKey` par la définition déclencheuse :

```text
(event.version, event.created_at, event.id, pipelineId, pipelineVersion)
```

Une requête unifiée ordonne et pagine sur ce tuple. Elle évite qu'une première génération très en
retard affame les autres. Le cursor reste local au `ConsumptionSearch`. Après une acquisition et une
exécution, `SequentialConsumptionOrchestrator` ouvre une nouvelle recherche depuis le début ; après
un cycle idle ou budget-exhausted, le prochain polling fait de même.

L'ordre ascendant remet donc naturellement un ancien Event nouvellement candidat devant les nouveaux
Events. Le budget `maxCandidatesInspected` borne chaque cycle, et l'anti-join sur les Tasks/slots fait
disparaître les éléments traités. Aucun offset durable, watermark de scheduling ou index de replay
n'est ajouté.

## 9. Identité, payload et provenance des Tasks

### 9.1 Identité durable

La Task physique Event-derived est directement unique sur :

```text
(event_id, pipeline_id, pipeline_version)
```

`task_id` reste sa clé technique pour le runtime Task. `task_key` peut rester comme donnée de binding
pendant la transition, mais ne participe plus à l'identité logique Event→Task.

Cette contrainte est celle du sous-type Event-derived introduit par le Lot 7.5, pas l'identité
universelle des Projection Tasks. Le Lot 7.8 doit pouvoir ajouter des Tasks administratives sans
Event, identifiées par `(campaignId,potId,potVersion,pipelineId,pipelineVersion)`. Le schéma et les
mappings ne doivent donc pas ensevelir l'hypothèse `event_id` dans le payload ou dans l'identité
commune du runtime Task. Si `event_id NOT NULL` reste en 7.5, cette contrainte est explicitement
transitoire et limitée aux lignes Event-derived ; son évolution future ne changera pas le payload
d'exécution commun.

### 9.2 Payload d'exécution

Le payload structurel durable est :

```text
pipeline_id
pipeline_version
pot_id
pot_version
```

Le code actuel peut représenter ce contrat sans nouveau type universel : `RecordedTask.pipeline`,
`RecordedTask.potId` et `RecordedTask.targetVersion` forment déjà ces quatre valeurs. La colonne
`target_version` devient explicitement le `potVersion` du contrat ; une nouvelle colonne `pot_id uuid`
remplace l'usage sémantique de `partition_key`. `partition_key` reste une donnée technique de
segmentation et doit être dérivée de `pot_id`, jamais l'inverse après migration.

Le JSON spécifique au task type peut continuer d'exister pour le mapper/executor, mais sa cohérence
avec les quatre colonnes structurelles reste vérifiée. Il n'est pas nécessaire de répéter `eventId`
dans ce JSON. `event_id` reste durable dans la ligne pour l'origine et l'idempotence du producer ; le
runtime Task n'a pas besoin de relire l'Event.

### 9.3 Adoption et conflit

L'opération d'ensure effectue un insert atomique avec `ON CONFLICT` ou équivalent sur le triplet. Si
elle perd la course, elle recharge la Task gagnante et vérifie :

- même Event, pipelineId et pipelineVersion par construction de l'index ;
- même `pot_id` que l'Event autoritatif ;
- même `pot_version/target_version` que l'Event autoritatif ;
- binding/task type attendu pour la stratégie exacte ;
- payload spécifique cohérent lorsque sa comparaison canonique est disponible.

Un écart de `potId` ou `potVersion` lève une violation technique non retryable, laisse la Task
existante intacte et fait rollbacker toute nouvelle écriture du batch. Il n'existe aucun update de
payload dans ce chemin.

### 9.4 Provenance

Chaque exécution acquise persiste :

- un `ConsumptionInput(EVENT, eventId, eventVersion)` ;
- un `ConsumptionResult` par Task logique assurée par le batch, qu'elle soit nouvellement insérée ou
  adoptée.

Le résultat pointe le `taskId` durable réellement gagnant. Le résultat applicatif distingue
`Created` et `Adopted` pour les métriques, mais cette différence ne modifie pas la provenance
fonctionnelle. Zéro insert physique peut donc être un succès avec plusieurs Tasks adoptées.

## 10. Transaction, terminalité, crash et retry

L'unité cible, avec le wiring PostgreSQL déjà utilisé par le runtime Event, est :

```text
reload Event
  -> construire tous les plans depuis le catalogue courant
  -> si tous valides, ensure/adopt toutes les Tasks applicables pertinentes
  -> écrire input Event et results Task
  -> CAS terminal du slot déclencheur
  -> commit unique
```

La consommation est `SUCCESS` uniquement lorsque toutes les intentions du batch sont assurées. Une
rejection déterministe pendant la phase de planification retourne `REJECTED` sans aucune Task ; une
erreur technique ou violation pendant persistence rollbacke tout et suit la failure policy existante.

Cas obligatoires :

- crash avant toute écriture : rien n'est visible, le claim est repris ;
- v1 insérée puis échec lors de v2 : v1, provenance et terminalisation rollbackées ;
- toutes les Tasks écrites mais échec avant CAS : rollback complet ;
- commit réussi puis crash : Tasks, provenance et slot terminal sont déjà durables ;
- perte de claim : le CAS échoue et annule le batch ;
- retry après résultat incertain : les contraintes uniques et l'adoption convergent vers les mêmes
  taskIds.

La transaction doit continuer à être ouverte par `TransactionalExecuteConsumptionUseCase`. Les ports
de création restent `MANDATORY`; le locator, le domaine et le discovery ne démarrent aucune
transaction imbriquée.

## 11. Concurrence

Deux replicas découvrant le même couple `(E,D)` forment la même `ConsumptionKey`. Un seul claim est
actif ; lease et takeover conservent le fencing existant.

Deux couples déclencheurs `(E,D1)` et `(E,D2)` peuvent néanmoins être acquis simultanément. Comme
chaque callback réévalue toutes les définitions, les deux transactions peuvent tenter les mêmes Tasks.
La correctness repose alors sur les contraintes uniques directes :

- le premier insert gagne ;
- le second attend/observe le gagnant puis adopte après contrôle du payload ;
- une différence de contenu est une violation, jamais un overwrite ;
- chaque triplet possède finalement une seule Task.

Deux Events E1 et E2 avec le même Pot/version ont des `event_id` différents : ils forment des slots et
des triplets différents et créent deux Tasks légitimes. Aucun verrou ni index ne doit les fusionner.

## 12. Schéma, migration et indexes

Une migration primaire forward-only est nécessaire. Le dernier numéro vérifié est V9 ; le nom attendu
pendant l'implémentation est donc :

```text
V10__align_projection_task_scheduling.sql
```

Elle doit :

1. ajouter `pot_id uuid` à `tasks_4_pipeline` ;
2. backfiller depuis `partition_key` seulement après un preflight prouvant que chaque valeur concernée
   est un UUID et correspond au payload/à l'Event ;
3. rendre `pot_id` non nul et ajouter les checks positifs de pipeline/version déjà manquants ;
4. refuser l'upgrade si plusieurs Tasks existent pour un même
   `(event_id,pipeline_id,pipeline_version)` ; aucune déduplication destructive automatique ;
5. ajouter l'unicité directe sur `(event_id,pipeline_id,pipeline_version)` ;
6. retirer la FK et la colonne `materialization_id` de `tasks_4_pipeline` ;
7. supprimer `event_4_pipeline_materialization_status`, désormais redondante avec la Task unique et le
   slot de consommation ;
8. conserver `task_id`, `task_type`, `task_payload`, `partition_key`, `partition_hash`,
   `target_version`, timestamps et colonnes lifecycle legacy encore nécessaires au cutover Task ;
9. ajouter un index Event pour le discovery ordonné
   `(version, created_at, id)` si l'EXPLAIN PostgreSQL montre que les index actuels
   `business_event_outbox(pot_id,version)` et legacy claimable ne suffisent pas ;
10. vérifier que l'unique index de Task couvre l'anti-join Event/définition avant d'ajouter tout index
    supplémentaire.

L'unicité de l'étape 5 est explicitement partielle au modèle Event-derived du Lot 7.5. Elle ne doit
pas être nommée ni documentée comme identité universelle d'une Projection Task. `event_id` peut rester
`NOT NULL` dans ce lot parce qu'aucune Task administrative n'est créée, mais le mapping commun et les
ports d'exécution doivent rester centrés sur `(pipelineId,pipelineVersion,potId,potVersion)`. Le Lot
7.8 pourra introduire une provenance administrative et son unicité propre sans réécrire ce payload.

La suppression de `event_4_pipeline_materialization_status` ne réintroduit ni coverage, expectation,
projection state ou state par version. Les `consumption_slots` restent exclusivement un lifecycle
technique.

Un script de preflight read-only doit lister, puis bloquer sur :

- duplicate triplet Task ;
- Task sans Event ;
- identité Task différente de son parent materialization legacy ;
- parent `MATERIALIZED` sans exactement une Task ;
- parent `SKIPPED` possédant une Task ;
- `partition_key` absent/non UUID ou différent du Pot de l'Event ;
- `target_version` différent de la version de l'Event ;
- pipelineVersion non positive.

Le cutover arrête tous les anciens Event producers avant migration. Un rollback applicatif conserve la
migration et désactive le nouveau runtime ; aucune down migration recréant le ledger legacy n'est
prévue. L'application doit échouer clairement si elle démarre avec le nouveau mapping avant V10.

## 13. Indépendance du read store et du watermark

Les seules données consultées par le scheduling sont :

- `business_event_outbox` ;
- `tasks_4_pipeline` ;
- `consumption_slots` et `consumption_claims` pour l'éligibilité technique ;
- le catalogue et les stratégies statiques en mémoire.

Les requêtes du producer ne doivent contenir aucun accès à :

```text
pocoma_read.projection_artifacts
pocoma_read.projection_failures
pocoma_read.projection_heads
pocoma_read.source_version_watermarks
```

Un artifact déjà `READY` ne supprime jamais une intention manquante. De même :

```text
latestVersionSeen = 72
Event E @ 73
definition.appliesTo(73) == true
  -> Task E/definition créée normalement
```

Le test d'architecture `projectionNDoesNotRequireSourceVersionWatermarkAtLeastN` existe déjà pour le
runtime Task/projector. Le Lot 7.5 doit l'étendre au nouveau scheduler ou ajouter une règle sœur
interdisant au producer de dépendre de `engine-read-projection` et `infra-read-persistence`.

## 14. Observabilité

Ajouter des compteurs bornés au niveau du use case/runtime :

- Events/candidats inspectés via les compteurs génériques ;
- exécutions avec au moins une définition applicable pertinente ;
- Tasks créées ;
- Tasks adoptées ;
- exécutions terminées avec zéro intention après relecture ;
- rejections de planning ;
- violations d'invariant ;
- retries/failures via le lifecycle générique.

Ne pas taguer par `eventId` ou `potId`. Éviter également `pipelineVersion` comme tag non borné ; un tag
`pipelineId` n'est acceptable que si la liste statique et sa cardinalité sont explicitement bornées.
Les logs de diagnostic peuvent porter les identités exactes.

Un changement de `PipelineSelectionStrategy` ne modifie ni candidats, ni compteurs de création : la
stratégie n'est pas chargée dans le process.

## 15. Tests obligatoires

### 15.1 Domaine/catalogue et scheduling unitaire

- V40, v1 `[1..49]`, v2 `[50..∞]` : seule v1 est planifiée.
- V73 avec v1 et v2 toutes deux applicables : deux intentions.
- aucune définition applicable : zéro intention et zéro appel persistence.
- plusieurs pipelineIds pertinents : chacun est évalué indépendamment.
- pipeline non pertinent : aucune Task pour aucune de ses générations.
- v1 et v2 du même `pipelineId` partagent obligatoirement la même relation de pertinence ; un wiring
  permettant des réponses divergentes est rejeté.
- définition cataloguée sans stratégie exacte : erreur de configuration, idéalement au wiring.
- ordre d'énumération déterministe sans signification de priorité.
- changement de `PipelineSelectionStrategy`, si une fixture la rend visible : aucun changement du
  résultat du scheduler ; de préférence l'architecture interdit simplement cette dépendance.

### 15.2 Identité et payload

- même Event + même génération, appels répétés : une seule Task et même taskId adopté.
- même Event + nouvelle génération : seconde Task légitime.
- deux Events distincts avec mêmes `potId/potVersion` : deux Tasks.
- Task existante sous le même triplet et même payload : adoption.
- même triplet mais `potId` différent : violation, aucune mutation.
- même triplet mais `potVersion` différent : violation, aucune mutation.
- `eventId` absent du payload commun remis à l'executor, mais présent comme origine durable.
- le mapper Balance valide la cohérence entre colonnes structurelles et payload spécifique.

### 15.3 Old Event rediscovery — test PostgreSQL/runtime obligatoire

Le test doit exécuter exactement :

1. catalogue de fixture `{READ_POT/v1}` ;
2. Event E à V73 ;
3. runtime normal : création de la Task `(E, READ_POT, v1)` et terminalisation du slot scheduler v1 ;
4. nouvelle composition de fixture `{READ_POT/v1, READ_POT/v2}` avec v2 applicable à 73 ;
5. nouvelle recherche normale, sans API replay ni reset de slot ;
6. vérification que E est retourné avec v2 comme définition déclencheuse ;
7. création/adoption exacte de la Task v2 ;
8. vérification que la Task v1 garde son id, son payload et ses timestamps ;
9. nouvel appel concurrent/répété : toujours exactement une Task v1 et une Task v2.

Ce test doit aussi affirmer que le slot v1 reste terminal et qu'un nouveau slot v2 est créé. Il est le
critère principal du lot.

### 15.4 Discovery, pagination et zéro applicable

- le SQL ne lit pas le statut legacy de `business_event_outbox` ;
- un slot scheduler terminal n'exclut que son exact pipelineId/version ;
- un ancien Event situé avant un cursor d'un cycle précédent redevient visible dans un nouveau cycle ;
- pagination sur plusieurs définitions sans doublon ni omission ;
- une définition très chargée n'affame pas une autre définition ;
- aucun applicable : aucun candidat, aucun slot, aucune Task et cycles idle stables ;
- future définition applicable au même Event : candidat visible normalement.

### 15.5 Transaction, concurrence et crash

- deux workers sur le même candidat : un seul claim gagnant et une Task par définition.
- deux trigger definitions du même Event exécutées en concurrence : convergence par contraintes
  uniques.
- failure après insert v1 pendant ensure v2 : aucune Task du batch ne committe.
- failure après toutes les Tasks mais avant provenance/CAS : Tasks, input, results et terminalisation
  rollbackés.
- takeover avant CAS : transaction de l'ancien worker rollbackée.
- retry : mêmes Tasks adoptées, aucun duplicate.
- succès lorsque toutes les Tasks existent déjà : zéro insert, results de provenance vers les gagnants.

### 15.6 Indépendance

- artifact `READY` présent mais triplet Task absent : le producer crée la Task.
- watermark à 72, Event à 73 : la Task 73 est créée.
- test d'architecture : scheduler/Task creation ne dépend ni de `domain-projection`, ni de
  `engine-read-projection`, ni de `infra-read-persistence`, ni de `PipelineSelectionStrategy`.
- inspection/assertion des requêtes : aucune occurrence ou jointure vers `pocoma_read`.

### 15.7 Migration

- upgrade V1→…→V9→V10 sur schéma conforme ;
- installation fraîche ;
- duplicate triplet legacy : migration/preflight échoue sans suppression ;
- payload/pot/version incohérent : preflight échoue ;
- absence finale de `event_4_pipeline_materialization_status` et `materialization_id` ;
- présence de la contrainte unique directe et de `pot_id` ;
- démarrage du nouveau runtime après migration et échec lisible avant migration.

## 16. Ordre précis d'implémentation

1. Ajouter les tests unitaires de sélection applicabilité et old-catalog/new-catalog autour d'un
   registry de fixture.
2. Séparer la pertinence Event→`pipelineId` des bindings exacts de construction et ajouter les
   garde-fous de wiring inter-générations.
3. Faire évoluer le modèle candidat/cursor du scheduling pour inclure la définition déclencheuse.
4. Ajouter le discovery catalog-driven et ses tests repository PostgreSQL, sans toucher encore au
   runtime actif.
5. Refactorer le planning en deux phases : déterminer les pipelineIds pertinents, filtrer leurs
   définitions applicables, planifier toutes les intentions, puis persister le batch.
6. Restreindre la cardinalité à zéro/une Task par définition et ajouter les résultats
   `Created`/`Adopted`.
7. Écrire le preflight V10 et ses tests sur les données legacy conformes/incohérentes.
8. Ajouter V10, la colonne `pot_id`, l'unicité Event-derived directe, puis retirer le parent
   materialization legacy, sans fermer le futur sous-type administratif.
9. Réécrire `JpaTaskCreationAdapter` en ensure/adopt direct avec validation de payload.
10. Adapter `JpaTaskReadRepository`, `RecordedTask` mapping et le mapper Balance à `pot_id` structurel,
   sans modifier la logique du projector.
11. Refactorer `EventConsumptionLocator` pour la clé scheduler et l'exécution catalog-driven.
12. Refactorer `EventConsumptionRuntimeConfiguration` : registry depuis le catalogue, pertinences par
    pipelineId, tous les bindings exacts, suppression des propriétés pipeline/version.
13. Ajouter les tests runtime old Event→new definition, zéro applicable et no replay.
14. Ajouter les tests transactionnels batch, concurrence, adoption et payload divergent.
15. Ajouter les règles d'architecture/no-read-store et les métriques bornées.
16. Mettre à jour les scripts/runbooks de cutover Event et la documentation factuelle.
17. Exécuter tests ciblés, migrations, architecture, suite complète et `git diff --check`.

Chaque étape doit laisser les modules compilables. La migration et le changement de mapping sont
livrés dans le même déploiement mais le runtime reste désactivé jusqu'au preflight/cutover.

## 17. Modules et fichiers probablement touchés

### À modifier

- `domain-pipeline`
  - aucune nouvelle policy ; tests d'énumération/applicabilité seulement si nécessaires.
- `engine-processing-event`
  - port/candidat/cursor du scheduler catalog-driven.
- `engine-task-creation`
  - use case Event entier, planning deux phases, cardinalité zéro/une, résultat batch.
- `infra-persistence-jpa`
  - repository discovery, adapter de création/adoption, entité/repository Task, migration V10.
- `locator-consumption-event`
  - clé `PROJECTION_TASK_SCHEDULER`, reload et invocation du batch.
- `runtime-event-consumption-worker`
  - composition depuis `PocomaPipelineDefinitions.all()`, suppression de la configuration d'une
    génération unique, tests PostgreSQL.
- `pipeline-balance`
  - stratégie construite pour chaque définition Balance cataloguée et payload cohérent avec les
    colonnes structurelles ; aucun calcul Balance.
- `architecture-tests`
  - absence de dépendance read-store/watermark/reader selection.
- `docs/operations`
  - preflight et cutover Event après implémentation.

### À inspecter ou adapter mécaniquement

- `engine-processing-task`, `locator-consumption-task`, `runtime-task-consumption-worker` : lecture de
  `pot_id` structurel et maintien de la compatibilité du payload ; aucune règle d'exécution nouvelle.
- `docs/architecture/read-side-current-state.md`,
  `docs/architecture/consumption-event-pull-runtime.md` et matrice des dépendances : mise à jour
  factuelle une fois le lot implémenté.

### À ne pas modifier fonctionnellement

- `domain-projection`, `engine-read-projection`, `infra-read-persistence` ;
- runtime SourceVersionWatermark ;
- calcul et matérialisation des projections ;
- Query Kernel, GET, autorisation et stratégies reader ;
- write-side Pot/Command ;
- Tasks administratives, backfill, repair et rebuild.

## 18. Risques et points ouverts

### Risques fermés par ce plan

- **Slot terminal éternel** : clé scheduler versionnée par définition, jamais globale.
- **Ancien cursor** : cursor local et recherche réouverte depuis le début.
- **Starvation inter-générations** : ordre unifié incluant la définition.
- **Partial batch** : planning avant writes et transaction Execute unique.
- **Fusion de deux Events** : unicité inclut `event_id`, jamais Pot/version seuls.
- **Read-store gate** : dépendances et SQL explicitement interdits.
- **Stratégie reader utilisée en production** : module/dépendance interdits.
- **Zero-applicable fermé à jamais** : aucun slot créé sans définition applicable.

### Gate réel avant migration

Le seul point dépendant des données de déploiement est la présence éventuelle de plusieurs Tasks
legacy sous un même `(eventId,pipelineId,pipelineVersion)`, forme autorisée par l'ancien modèle
zéro-à-N. Le preflight doit quantifier ces lignes. Toute occurrence bloque V10 jusqu'à résolution
opérationnelle explicite ; la migration ne choisit, fusionne ou supprime aucune Task automatiquement.

Ce gate n'est pas une ambiguïté d'architecture : la cible reste une Task de projection par triplet.

### Choix de forme non fonctionnels

- nom exact du nouveau use case et des records batch ;
- construction SQL `VALUES` ou abstraction JDBC équivalente ;
- conservation temporaire de `task_key` pour compatibilité ;
- ajout de l'index Event ordonné, décidé par `EXPLAIN (ANALYZE, BUFFERS)` sur un volume représentatif.

Ils ne doivent modifier aucun invariant ci-dessus.

## 19. Critères de sortie

- Le producer utilise exclusivement `PocomaPipelineDefinitions.all()` via un registry local.
- La pertinence est calculée une fois par `(Event,pipelineId)` et ne peut pas diverger entre
  générations ; `appliesTo` reste l'unique filtre propre à une `pipelineVersion`.
- Toutes les définitions pertinentes satisfaisant `appliesTo(V)` produisent une intention.
- Plusieurs générations applicables coexistent sans priorité ni fallback.
- Une seule Task durable existe par `(eventId,pipelineId,pipelineVersion)`.
- Cette unicité est explicitement Event-derived ; le payload et les mappings restent extensibles aux
  identités administratives du Lot 7.8.
- Une Task adoptée est comparée à l'Event autoritatif ; toute divergence Pot/version échoue sans
  overwrite.
- Deux Events identiques en Pot/version restent indépendants.
- Le batch de toutes les générations est atomique avec provenance et CAS terminal.
- La clé `PROJECTION_TASK_SCHEDULER[pipelineId,pipelineVersion]` permet à une nouvelle définition de
  redécouvrir les anciens Events sans rouvrir les slots précédents.
- Le test E@73/v1 puis ajout v2 prouve exactement une nouvelle Task v2 et v1 inchangée.
- Aucun mode replay, offset durable ou reset de slot n'est nécessaire.
- Aucun applicable crée zéro Task et ne ferme pas l'Event aux définitions futures.
- Aucun artifact, failure, head, watermark, status read-side ou stratégie reader n'est consulté.
- `latestVersionSeen=N-1` n'empêche pas le scheduling de N.
- `event_4_pipeline_materialization_status` et `materialization_id` ont disparu après V10.
- Le runtime Task/projector continue à exécuter les Tasks sans changement de règle métier.
- Les métriques sont bornées et les documents factuels/runbooks sont alignés.
- Suite Maven complète et `git diff --check` réussissent.

## 20. Commandes de validation

Depuis `app` :

```bash
./mvnw -pl domain-pipeline,engine-task-creation,pipeline-balance -am test
./mvnw -pl infra-persistence-jpa -am test
./mvnw -pl locator-consumption-event,runtime-event-consumption-worker -am test
./mvnw -pl runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
./mvnw test
git diff --check
```

La validation PostgreSQL doit inclure le preflight, l'upgrade V9→V10, l'installation fraîche, les
courses d'insert et l'absence de toute lecture `pocoma_read`. Le runtime Event legacy doit rester
arrêté pendant le cutover ; le runtime watermark demeure indépendant et peut continuer à fonctionner.

## 21. Hors périmètre explicite

Le Lot 7.5 ne réalise pas :

- exécution ou calcul d'une projection ;
- reconstruction du Pot primaire ;
- PotProjection ou Balance métier ;
- Query Kernel, GET ou autorisation ;
- `PipelineSelectionStrategy` ;
- Tasks administratives, backfill, repair ou rebuild ;
- fallback de génération ;
- état fonctionnel de projection supplémentaire ;
- stockage SQL du catalogue ou configuration dynamique.

Le résultat du lot est uniquement un ensemble correct, durable et observable d'intentions de
projection Event-derived.
