# EPT — Event → ProjectionTask — implementation tracker

```text
Step: EPT — Event → ProjectionTask
Current lot: EPT.5
Overall status: IN_PROGRESS
```

EPT.4 est audité et accepté. EPT.5 est le prochain lot et reste `TODO` jusqu'à son démarrage explicite.
La source architecturale de ce tracker est [`Step_Canon.md`](Step_Canon.md).

| Lot | Sujet | Statut |
|-----|-------|--------|
| EPT.1 | EventType et persistence canonique | DONE |
| EPT.2 | Policy exhaustive | DONE |
| EPT.3 | Discovery metadata-only | DONE |
| EPT.4 | Consumption Event → ProjectionTask | DONE |
| EPT.5 | Cutover runtime Event | TODO |
| EPT.6 | Preuve E2E distribuée | TODO |

## EPT.1 — EventType et persistence canonique

### Status

`DONE`

### Goal

Découpler définitivement l'identité durable d'un Event de son nom de classe Java et migrer
l'historique vers dix valeurs sémantiques stables.

### Preconditions

- Les dix Events Pot existaient déjà dans `domain-pot`.
- `business_event_outbox` portait déjà `id`, `event_type`, `pot_id`, `aggregate_id`, `version`,
  `payload_json` et `created_at`.
- Les consumers legacy devaient continuer à reconstruire leurs Events.

### Implementation

Livré au HEAD :

- `domain-event/EventType`, value object non null et non blank ;
- `BusinessEvent.eventType()` sans fallback par défaut ;
- `domain-pot/.../PocomaEventTypes`, catalogue immutable des dix types ;
- implémentation explicite d'`eventType()` par les dix records Pot ;
- écriture et dispatch canoniques dans `BusinessEventRecordMapper` ;
- propagation du type canonique vers l'observabilité et le listener Balance legacy ;
- migration Flyway `V15__canonical_business_event_types.sql` des noms Java historiques ;
- migration conjointe du champ dupliqué `payload_json.eventType` lorsqu'il existe ;
- rejet des types inconnus, des incohérences envelope/payload, de JSON null et des valeurs JSON
  non textuelles ;
- conservation de l'absence historique du champ payload ;
- contrainte SQL limitant `event_type` aux dix valeurs canoniques.

Commits :

- `144f810704347716bab67a0d1faf5e45e157bde6` — `feat: add canonical business event types` ;
- `9b2471ee790b665e83c6ef0455f9c5501d204e3f` —
  `fix: reject incoherent canonical event type migration`.

### Tests

- validation de `EventType` ;
- correspondance exacte des dix Events et immutabilité du catalogue ;
- round-trip des dix formes dans `BusinessEventRecordMapper` ;
- commande réelle écrivant `POT_CREATED` dans colonne et payload ;
- migration PostgreSQL d'un ancien type cohérent ;
- conservation d'un type déjà canonique ;
- rejet mismatch, JSON null, non-textuel et type inconnu ;
- conservation d'un payload sans propriété `eventType` ;
- reactor Maven et tests d'architecture verts lors de la livraison.

### Exit criteria

- Le nom de classe Java n'est plus une identité fonctionnelle ou persistée.
- Colonne et payload explicite sont canoniques et cohérents.
- L'historique connu est migré et protégé en base.
- Les consumers legacy actifs continuent à fonctionner.

### Notes / findings

- Le payload reste présent pour les consumers legacy, mais ne sera pas utilisé par la discovery EPT.
- L'index de discovery a été volontairement reporté à EPT.3 afin d'être conçu avec la requête
  réelle.

## EPT.2 — Policy exhaustive

### Status

`DONE`

### Goal

Introduire la vérité fonctionnelle transverse et immutable :

```text
EventType → Set<ProjectionType>
```

### Preconditions

- EPT.1 est `DONE` et `PocomaEventTypes.all()` est exhaustif.
- `READ_POT` et `POT_BALANCES` sont les deux projections canoniques productibles.
- `AUTH` ne doit pas être activé.

### Implementation

Livré et accepté après review :

- contrat minimal `ProjectionMaterializationPolicy` sur `EventType` et
  `ProjectionType`, sans persistence, SQL, worker ou Consumption.
- `PocomaProjectionMaterializationPolicy` déclarée comme table explicite immutable au niveau du
  runtime Event.
- chacun des dix `PocomaEventTypes` mappé vers `{READ_POT, POT_BALANCES}`.
- validation à la construction de l'égalité exacte entre catalogue connu et clés déclarées : aucun type
  manquant, aucun type inconnu.
- support explicite d'un ensemble vide pour un type connu.
- dérivation des matérialisations pertinentes depuis le seul ensemble de `ProjectionType` servi
  par un worker ; ne jamais configurer séparément des EventTypes.
- contrat générique conservé dans `engine-processing-event` avec des dépendances explicites vers
  `domain-event` et `domain-projection` ; déclaration Pocoma composée au niveau du runtime Event,
  où les constantes `PocomaEventTypes` et `domain-pot-projection` sont réunies sans créer de
  dépendance engine vers les projections concrètes.
- aucun module, registry de policies ou framework de plugins créé.

### Tests

- `PocomaEventTypes.all()` égale exactement les clés de la policy ;
- type connu manquant rejeté ;
- type inconnu déclaré rejeté ;
- mapping vide accepté et conservé ;
- mapping initial exact des dix types vers les deux projections ;
- aucune matérialisation `AUTH` ;
- dérivation correcte pour worker `{READ_POT}`, `{POT_BALANCES}` et
  `{READ_POT, POT_BALANCES}`.

### Exit criteria

- Une seule policy explicite porte toute la décision EventType → ProjectionType.
- Le catalogue et la policy sont prouvés exhaustifs sans reflection.
- La dérivation opérationnelle des EventTypes ne nécessite comme entrée que le
  `Set<ProjectionType>` servi.
- Aucun élément de discovery, locator ou runtime cutover n'est introduit.

### Notes / findings

- La déclaration Pocoma est placée dans `runtime-event-consumption-worker`, niveau de composition
  actuel réunissant le catalogue d'Events et les projections concrètes sans dépendance
  `engine-processing-event → domain-pot-projection`.
- La vue dérivée par projections servies omet une entrée lorsque son intersection est vide ; la
  table canonique exhaustive conserve, elle, les mappings vides explicitement déclarés.
- Le lot a été audité et accepté après review.

## EPT.3 — Discovery metadata-only

### Status

`DONE`

### Goal

Découvrir efficacement les conséquences Event × ProjectionType encore dues, à partir des seules
métadonnées persistées et sans accorder d'autorité d'exécution.

### Preconditions

- EPT.2 fournit une policy exhaustive et les routes dérivées pour le worker.
- V15 garantit des `event_type` canoniques.
- La structure des `consumption_slots` permet l'anti-join sur l'identité canonique.

### Implementation

- Nouveau `ProjectionMaterializationDiscoveryPort` canonique, distinct de
  `EventConsumptionDiscoveryPort` legacy, recevant seulement les routes
  `Map<EventType, Set<ProjectionType>>`, un `WorkerSegment`, une ordering key locale optionnelle et
  une limite. Ces routes sont dérivées en amont de la policy EPT.2 ; le port et l'adapter ne
  reçoivent ni ne connaissent `ProjectionMaterializationPolicy`.
- `ProjectionMaterializationCandidate` porte directement l'envelope metadata-only canonique
  (`eventId`, `eventType`, cible, version, instant d'enregistrement) et le `ProjectionType` de la
  conséquence ; aucun envelope ou type public `Route` supplémentaire.
- `ProjectionMaterializationOrderingKey` locale et non durable sur
  `(recordedAt, eventId UUID, projectionType)`.
- `JdbcProjectionMaterializationDiscoveryAdapter` interroge `business_event_outbox` sans
  sélectionner `payload_json`, aplatit les routes dans une CTE `VALUES` privée à l'adapter et
  développe les lignes `Event × ProjectionType`. Il reconstruit la cible Pocoma depuis `pot_id` et
  sa version depuis `version`.
- La segmentation SQL normalise le modulo pour reproduire `Math.floorMod` sur
  `pot_partition_hash`, sans segmentation par ProjectionType.
- L'anti-join exclut uniquement le slot `DONE` dont l'identité est exactement :

```text
EVENT[eventId] / PROJECTION_TASK_MATERIALIZER[projectionType]
```

- Les slots absents ou non `DONE`, y compris actifs ou retardés, restent découvrables ; la requête
  ne lit ni Claims, ni leases, ni `now` et n'accorde aucune autorité d'exécution.
- La keyset pagination utilise le même triplet UUID natif dans le prédicat et l'ordre SQL :
  `(created_at, event_id, projection_type)` ; aucun `OFFSET`.
- Aucun wiring runtime autoritaire, cursor durable, watermark ou nouvel index n'est introduit.

### Tests

- Tests de contrat du Candidate, de ses métadonnées et de son ordering key.
- Tests PostgreSQL de l'expansion une ou plusieurs routes et de la restriction aux routes fournies.
- `payload_json` contenant un texte non JSON et impossible à désérialiser : discovery inchangée et
  règle d'architecture interdisant Jackson et les modèles d'Events métier.
- Aucun slot, slot actif, slot retardé, `DONE` sur une autre projection et `DONE` exact.
- Segmentation PostgreSQL, y compris un hash négatif, sans segmentation par ProjectionType.
- Ordre et keyset pagination `LIMIT 1` sur timestamps distincts ou égaux, UUIDs et plusieurs
  projections du même Event, sans trou ni doublon.
- Nouveau scan sans cursor, terminaison du scan et changement concurrent vers `DONE`.
- Backfill explicite : après parcours complet d'un Event historique pour `READ_POT` et clôture de
  cette Consumption, un nouveau scan depuis le début avec la route ajoutée retrouve
  `POT_BALANCES`, sans cursor ni watermark durable.
- `EXPLAIN (ANALYZE, BUFFERS)` sur PostgreSQL 17 avec 200 000 Events, 50 000 slots `DONE`, quatre
  routes, segmentation 3/16, première page et reprise keyset à mi-scan.

### Exit criteria — satisfaits

- La discovery retourne des candidats metadata-only portant `eventId`, `eventType`, cible, version,
  instant d'enregistrement et `projectionType`, et reste best-effort.
- Le payload et les abstractions pipeline/generation sont absents du nouveau contrat et de sa
  requête.
- Seul `DONE` ferme un couple exact.
- L'ordre et le cursor locaux utilisent exactement
  `(created_at, event_id UUID, projection_type)` sans cursor durable.
- Un scan est entièrement reconstructible depuis Events, routes dérivées de la policy courante et
  Consumption ; l'évolution de ces routes retrouve les Events historiques auxquels manque une
  conséquence.
- La discovery ne crée ni slot, ni Claim, ni Task et n'introduit aucune autorité d'exécution.
- Aucun watermark ou cursor durable n'est nécessaire : un nouveau scan repart du début et converge
  par l'exclusion des seules Consumptions exactes `DONE`.

### Notes / findings

- Les types indicatifs `ProjectionMaterializationEvent`, `Route`, `Candidate` et `OrderingKey` ne
  sont pas une taxonomie obligatoire : les introduire seulement si la signature réelle les exige.
- La map issue d'EPT.2 suffit comme représentation publique des routes ; l'adapter utilise seulement
  un record privé de binding SQL.
- `business_event_outbox.payload_json` est physiquement `text` au HEAD. La preuve metadata-only
  utilise donc un texte non JSON, sans mapper métier.
- La mesure PostgreSQL utilise avec les indexes existants un `Parallel Seq Scan` segmenté, un
  `Gather Merge`/tri borné par `LIMIT` et `uk_consumption_slots_key` pour chaque anti-join exact ;
  temps observés : environ 7,4 ms en première page et 6,2 ms à mi-scan.
- Un index couvrant candidat `(created_at, id) INCLUDE (event_type, pot_id, version,
  pot_partition_hash)` a été mesuré, pas supposé : environ 0,8 ms en première page mais 8,2 ms à
  mi-scan, PostgreSQL devant parcourir près de 100 000 entrées avant d'appliquer le curseur développé.
  Le gain global n'étant pas établi et la reprise régressant, aucun index ni migration n'est ajouté.
- Le lot a été audité et accepté après ajout des preuves de backfill, metadata-only et du plan SQL.

## EPT.4 — Consumption Event → ProjectionTask

### Status

`DONE`

### Goal

Acquérir chaque conséquence indépendamment et assurer sa `ProjectionTask` dans une finalisation
courte, atomique et fenced.

### Preconditions

- EPT.3 produit des candidats `EventId × ProjectionType` avec une cible et une version exactes.
- `AcquireConsumptionService`, `FinalizeConsumptionService` et leurs wrappers transactionnels sont
  disponibles.
- `JdbcProjectionTaskStoreAdapter.ensure` est idempotent et partage le même transaction manager.

### Implementation

- Centraliser la clé canonique :

```text
ConsumableIdentity(EVENT, [eventId])
ConsumerIdentity(PROJECTION_TASK_MATERIALIZER, [projectionType])
```

- Ajouter le locator canonique en parallèle de `EventConsumptionLocator` legacy.
- Laisser `acquire()` créer le slot paresseusement, créer le Claim et gérer Busy, NotReady,
  AlreadyDone et takeover.
- Après acquisition, dériver mécaniquement la `ProjectionKey` depuis le candidat, sans recharger ni
  désérialiser l'Event.
- Appeler `TransactionalFinalizeConsumptionUseCase` avec une finalisation `Success` et le durable
  effect `ProjectionTaskStore.ensure(key, recordedAt)`.
- Ne pas utiliser `TransactionalExecuteConsumptionUseCase`, provenance, callback long ni
  `ProjectionFailure` sur ce chemin.
- Sur erreur du durable effect, laisser rollbacker et remonter l'erreur ; ne pas terminaliser le
  slot en `FAILED` et ne pas introduire de limite de retries.
- Réutiliser l'orchestration/polling générique quand ses contrats respectent ces semantics ; ajouter
  seulement l'adaptation minimale nécessaire au flux locate → acquire → finalize.

### Tests

- une acquisition réussie crée exactement un Claim durable et un slot paresseux ;
- deux workers découvrant le même candidat : une seule autorité acquise ;
- deux ProjectionTypes du même Event possèdent slots, Claims et issues indépendants ;
- lease expiré sans takeover : le Claim courant peut finaliser ;
- takeover : le Claim stale ne peut appeler aucun durable effect ;
- `ensure` appelé après le lock/fencing ;
- replay du même Event/ProjectionType idempotent ;
- deux Events menant à la même `ProjectionKey` convergent vers une seule Task ;
- failure de `ensure` rollbacke et laisse la Consumption rejouable ;
- Task + Claim `SUCCESS` + Slot `DONE/SUCCESS` sont atomiques ;
- aucune `ProjectionFailure`, `DONE/FAILED` ou provenance obligatoire.

### Exit criteria

- Le nouveau chemin materialize une Task exclusivement sous Claim courant.
- La clé de Consumption est exactement EventId × ProjectionType.
- La contrainte unique de `projection_tasks` reste l'autorité d'idempotence.
- Les garanties de lease/takeover/fencing de Consumption restent inchangées.

### Notes / findings

- Le motif `acquire → fenced finalize` est extrait dans
  `AcquireThenFinalizeConsumptionOrchestrator<C>` avec trois petits contrats typés : source,
  recherche paginée et finalizer. Il ne dépend ni d'Event, ni de ProjectionTask, ni de provenance,
  ni d'un failure handler.
- `ProjectionTaskConsumptionOrchestrator` délègue sa boucle à cette abstraction. L'adaptation locale
  convertit `LOST_CLAIM` en issue fenced normale et garde `FINALIZED` / `RETRY_SCHEDULED` hors du
  cœur générique. Elle conserve aussi son comportement historique : après le premier candidat acquis,
  elle abandonne la page courante et relit les candidats depuis le curseur effectivement inspecté.
- `ProjectionMaterializationConsumptionKeys` fixe l'identité exacte
  `EVENT/[eventId] × PROJECTION_TASK_MATERIALIZER/[projectionType]`.
- `ProjectionMaterializationConsumptionSource` adapte la discovery EPT.3 sans créer de Slot, Claim
  ou Task et conserve le candidat metadata-only jusqu'à la finalisation.
- `ProjectionMaterializationConsumptionService` dérive la `ProjectionKey` depuis le candidat et
  appelle `ProjectionTaskStorePort.ensure` comme durable effect de
  `TransactionalFinalizeConsumptionUseCase`.
- La composition EPT.4 reste explicite dans les tests PostgreSQL : aucun worker Event supplémentaire
  ni changement du bean graph Event actif n'est introduit avant EPT.5.
- Les preuves PostgreSQL couvrent la visibilité atomique de Task + Claim `SUCCESS` + Slot
  `DONE/SUCCESS`, le fencing avant invocation de `ensure`, lease/takeover, rollback de `ensure`,
  rollback après écriture avant commit, replay, concurrence, idempotence aux deux niveaux et payload
  invalide non désérialisé.

- `FinalizeConsumptionService` verrouille déjà le slot et vérifie `current_claim_id` avant
  `durableEffect.apply()` ; aucune nouvelle abstraction transactionnelle n'est attendue.

## EPT.5 — Cutover runtime Event

### Status

`TODO`

### Goal

Rendre le nouveau locator/orchestrateur EPT autoritaire dans le runtime Event tout en conservant le
legacy compilé jusqu'à la preuve finale.

### Preconditions

- EPT.2 à EPT.4 sont testés en isolation et avec PostgreSQL.
- Le runtime peut composer policy, routes, discovery, Consumption et store sur la même base locale.

### Implementation

- Remplacer la configuration fonctionnelle pipeline par un unique ensemble
  `projection-types` servi par le worker.
- Dériver les EventTypes/routes depuis la policy ; ne pas ajouter une seconde propriété
  `event-types`.
- Assembler des workers mono `{READ_POT}` / `{POT_BALANCES}` ou multi
  `{READ_POT,POT_BALANCES}` avec le même chemin fonctionnel.
- Remplacer dans le bean graph actif `EventConsumptionLocator`,
  `PipelineDefinitionRegistry`, `EventPipelineRelevanceRegistry`,
  `TaskCreationStrategyRegistry` et `ScheduleProjectionTasksForEventUseCase` par le chemin EPT.
- Retirer du chemin actif la transaction longue, la provenance obligatoire et le dual-write
  `CanonicalProjectionTaskScheduler`.
- Conserver temporairement classes, modules et tables legacy s'ils ne créent pas une seconde
  autorité active ; aucune suppression physique générale dans ce lot.
- Adapter propriétés, configurations versionnées, compose, runbooks et tests runtime du repository
  dans le même cutover, sans alias non utilisé.

### Tests

- binding d'un worker mono et d'un worker multi-projections ;
- les EventTypes sont dérivés, jamais configurés ;
- catalogue worker vide/inconnu ou route incohérente échoue au démarrage ;
- plusieurs workers du même ProjectionType concourent via Consumption ;
- aucun bean legacy de scheduling Event n'est autoritaire ;
- smoke PostgreSQL du nouveau runtime et tests de lifecycle polling/stop.

### Exit criteria

- Le runtime Event actif ne dépend fonctionnellement ni des pipelines ni des generations.
- Sa seule configuration fonctionnelle est le set de ProjectionTypes.
- Le nouveau chemin est l'unique créateur autoritaire de `projection_tasks` depuis les Events.
- Le legacy reste seulement compilé ou historique.

### Notes / findings

- Le runtime actuel assemble encore `EventConsumptionLocator`, les registries pipeline/relevance/
  strategy et `TransactionalExecuteConsumptionUseCase`; leur retrait du bean graph constitue le
  cutover, pas leur suppression physique.

## EPT.6 — Preuve E2E distribuée

### Status

`TODO`

### Goal

Établir une chaîne de preuve sans trou à travers les trois frontières persistantes, puis identifier
le legacy supprimable dans un chantier séparé.

### Preconditions

- Le runtime EPT canonique est autoritaire.
- Le Projection Engine canonique exécute déjà `READ_POT` et `POT_BALANCES`.

### Implementation

- Relier les identités et assertions des trois suites :

```text
1. Command → durable Event
2. durable Event → ProjectionTask
3. ProjectionTask → Projection
```

- Préférer des suites distribuées exactes à un mégatest fragile ; chaque sortie persistante d'une
  suite devient l'entrée vérifiée de la suivante.
- Produire un inventaire final du legacy devenu inutilisé, sans le supprimer dans EPT.6 sauf
  nécessité empêchant l'autorité unique.

### Tests

- Event command-side durable avec EventType, Pot cible et version canoniques ;
- deux conséquences indépendantes `READ_POT` et `POT_BALANCES` ;
- idempotence de replay et unicité de `ProjectionTask` ;
- deux Events conduisant à la même clé sans duplication ;
- évolution de policy retrouvant un Event historique ;
- Claim stale sans création de Task ;
- failure du durable effect sans terminaison ;
- takeover/replay convergent ;
- `READ_POT` réel publié par le Projection Engine ;
- `POT_BALANCES` réel publié avec rationnels signés ;
- pour chaque Task consommée : projection durable et slot aval `DONE/SUCCESS`.

### Exit criteria

- Les trois suites partagent exactement `eventId`, `ProjectionKey` et identités de Consumption
  attendues ; aucun état implicite ne comble une frontière.
- Backfill par évolution de policy, rollback et fencing sont prouvés sous PostgreSQL.
- `READ_POT` et `POT_BALANCES` atteignent leur store canonique depuis un Event durable.
- Le legacy à retirer ultérieurement est inventorié et n'est plus autoritaire.

### Notes / findings

- La suppression générale de `tasks_4_pipeline`, des modules pipeline et de la provenance reste un
  lot ultérieur, après acceptation de ces preuves.

## Maintenance rule

- Avant de commencer un lot, passer son statut à `IN_PROGRESS`.
- Lorsque l'implémentation est terminée et soumise à audit, passer son statut à `REVIEW`.
- Après audit accepté et corrections éventuelles, passer son statut à `DONE`.
- Faire alors pointer `Current lot` vers le prochain lot.
- Inscrire toute découverte modifiant le plan dans `Notes / findings` du lot concerné.
- Si une découverte modifie l'architecture cible, mettre également à jour `Step_Canon.md`.
- `Step_Canon.md` ne contient pas l'avancement courant.
- `Step_Plan.md` ne devient pas une seconde spécification divergente du Canon.

Pour tout futur travail EPT, Codex commence par lire :

```text
docs/steps/EPT/Step_Canon.md
docs/steps/EPT/Step_Plan.md
```

puis uniquement les documents et le code supplémentaires nécessaires au lot courant. Cette règle
réduit le contexte chargé tout en gardant une source architecturale et un tracker uniques.
