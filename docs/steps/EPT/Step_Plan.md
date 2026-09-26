# EPT — Event → ProjectionTask — implementation tracker

```text
Step: EPT — Event → ProjectionTask
Current lot: EPT.2
Overall status: IN_PROGRESS
```

EPT.2 est le prochain lot à implémenter ; il n'a pas encore commencé. La source architecturale de
ce tracker est [`Step_Canon.md`](Step_Canon.md).

| Lot | Sujet | Statut |
|-----|-------|--------|
| EPT.1 | EventType et persistence canonique | DONE |
| EPT.2 | Policy exhaustive | TODO |
| EPT.3 | Discovery metadata-only | TODO |
| EPT.4 | Consumption Event → ProjectionTask | TODO |
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

`TODO`

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

- Introduire le contrat minimal `ProjectionMaterializationPolicy` sur `EventType` et
  `ProjectionType`, sans persistence, SQL, worker ou Consumption.
- Déclarer `PocomaProjectionMaterializationPolicy` comme table explicite immutable.
- Mapper chacun des dix `PocomaEventTypes` vers `{READ_POT, POT_BALANCES}`.
- Valider à la construction l'égalité exacte entre catalogue connu et clés déclarées : aucun type
  manquant, aucun type inconnu.
- Supporter explicitement un ensemble vide pour un type connu.
- Exposer la dérivation des routes pertinentes depuis le seul ensemble de `ProjectionType` servi
  par un worker ; ne jamais configurer séparément des EventTypes.
- Garder le contrat générique dans `engine-processing-event` avec des dépendances explicites vers
  `domain-event` et `domain-projection`. Composer la déclaration Pocoma au niveau du runtime Event,
  où les constantes `PocomaEventTypes` et `domain-pot-projection` peuvent être réunies sans créer
  une dépendance engine vers les projections concrètes.
- Ne créer ni module, ni registry de policies, ni framework de plugins.

### Tests

- `PocomaEventTypes.all()` égale exactement les clés de la policy ;
- type connu manquant rejeté ;
- type inconnu déclaré rejeté ;
- mapping vide accepté et conservé ;
- mapping initial exact des dix types vers les deux projections ;
- aucune route `AUTH` ;
- dérivation correcte pour worker `{READ_POT}`, `{POT_BALANCES}` et
  `{READ_POT, POT_BALANCES}`.

### Exit criteria

- Une seule policy explicite porte toute la décision EventType → ProjectionType.
- Le catalogue et la policy sont prouvés exhaustifs sans reflection.
- La configuration opérationnelle ne contient que des ProjectionTypes.
- Aucun élément de discovery, locator ou runtime cutover n'est introduit.

### Notes / findings

- Vérifier pendant l'implémentation le placement package exact de la déclaration Pocoma ; sa
  contrainte est l'absence de dépendance `engine-processing-event → domain-pot-projection`, pas la
  création d'un nouveau module.

## EPT.3 — Discovery metadata-only

### Status

`TODO`

### Goal

Découvrir efficacement les conséquences Event × ProjectionType encore dues, à partir des seules
métadonnées persistées et sans accorder d'autorité d'exécution.

### Preconditions

- EPT.2 fournit une policy exhaustive et les routes dérivées pour le worker.
- V15 garantit des `event_type` canoniques.
- La structure des `consumption_slots` permet l'anti-join sur l'identité canonique.

### Implementation

- Définir seulement les contrats réellement nécessaires à la frontière : envelope metadata-only,
  route `EventType × ProjectionType`, candidat et ordering key local.
- Ajouter un discovery port canonique distinct de l'actuel
  `EventConsumptionDiscoveryPort`, lequel reste pipeline/generation pendant la coexistence.
- Implémenter l'adapter PostgreSQL sur `business_event_outbox` sans sélectionner `payload_json`.
- Passer les routes dérivées de la policy à la requête sous forme d'une CTE `VALUES`; l'adapter
  connaît des valeurs de routes, pas `ProjectionMaterializationPolicy`.
- Construire la cible Pocoma depuis `pot_id` : `TargetObjectType(POT)`, `TargetObjectId(pot_id)`,
  `targetVersion=version`.
- Exclure par anti-join uniquement le slot `DONE` dont l'identité est exactement :

```text
EVENT[eventId] / PROJECTION_TASK_MATERIALIZER[projectionType]
```

- Conserver les autres slots absents, pending, busy ou not-ready comme candidats potentiels ;
  l'éligibilité reste la responsabilité d'`acquire()`.
- Conserver la segmentation via `pot_partition_hash`.
- Paginer avec un ordre technique total sur les lignes développées, incluant au minimum
  `created_at`, `event_id` et `projection_type`, afin de ne pas sauter une seconde conséquence du
  même Event lorsqu'un batch coupe entre deux routes.
- Ajouter l'index uniquement après validation par `EXPLAIN` de cette requête ; ne créer aucun
  watermark ou cursor durable.

### Tests

- aucun accès ni parsing de `payload_json`, y compris avec un payload volontairement illisible ;
- expansion correcte d'un Event en une ou plusieurs routes ;
- exclusion du seul couple déjà `DONE` ;
- autre ProjectionType du même Event toujours découvert ;
- slot pending/busy/not-ready non interprété par la discovery ;
- segmentation et ordre/pagination sans trou ni doublon ;
- EventType non pertinent exclu par les routes ;
- évolution de policy : une nouvelle route retrouve un Event historique sans watermark ;
- plan PostgreSQL raisonnable et index non redondant.

### Exit criteria

- La discovery retourne des candidats metadata-only et reste best-effort.
- Le payload et les abstractions pipeline/generation sont absents du nouveau port et de sa query.
- Seul `DONE` ferme un couple exact.
- Un scan est entièrement reconstructible depuis Events, policy et Consumption.

### Notes / findings

- Les types indicatifs `ProjectionMaterializationEvent`, `Route`, `Candidate` et `OrderingKey` ne
  sont pas une taxonomie obligatoire : les introduire seulement si la signature réelle les exige.

## EPT.4 — Consumption Event → ProjectionTask

### Status

`TODO`

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
