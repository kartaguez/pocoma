# POT_E2E — audit de la latence Command acceptée → READ_POT READY

## Conclusion

Classement : **B — mesure approximative seulement**.

Le schéma permet déjà de corréler sans ambiguïté une Command `APPLIED` à la clé exacte
`READ_POT / POT / potId / resultingVersion`. Il permet aussi d'obtenir un proxy durable de T1 grâce
au `done_at` du slot de consommation `PROJECTION_TASK` correspondant. Ce slot est terminalisé après
la publication de la projection, dans la même transaction que `projection_root` et ses artifacts.

La mesure n'est toutefois pas exacte au sens « instant de visibilité au commit » :

- `recorded_commands.submitted_at` est fourni par l'horloge du runtime Web avant le commit
  d'admission ;
- `consumption_slots.done_at` est fourni par l'horloge du task worker après l'effet de publication,
  mais avant le commit de la transaction de finalisation ;
- `pocoma_read.projection_root` ne porte aucun `ready_at` ;
- les deux bornes proviennent potentiellement d'horloges de processus/hôtes différents.

Le proxy actuel est utile pour un diagnostic et un premier benchmark sur des hôtes synchronisés,
mais son biais inclut la fin de la transaction d'admission, exclut la fin de la transaction de
publication et peut subir le clock skew. Sous forte contention PostgreSQL, le délai pré-commit
exclu de T1 peut précisément devenir significatif.

## 1. Timestamps existants et sémantique

| Source | Timestamp | Sémantique réelle |
|---|---|---|
| `recorded_commands` | `submitted_at` | `Clock.instant()` du runtime Web, calculé dans la transaction juste avant l'insert ; la réponse `202` n'est émise qu'après le commit |
| slot `COMMAND` | `created_at` | création technique du lifecycle à la première acquisition, pas acceptation de la Command |
| claim `COMMAND` | `claimed_at`, `ended_at` | acquisition puis fin d'une tentative worker |
| slot `COMMAND` | `done_at` | terminalisation de la consommation Command |
| `command_outcomes` | `resolved_at` | horloge du command worker pour le résultat terminal |
| `command_terminal_events` | `recorded_at` | même instant que `resolved_at` |
| `business_event_outbox` | `created_at` | horloge du command worker lors de l'append de l'Event, avant le commit de la mutation Command |
| slot Event/`READ_POT` | `created_at`, `done_at` | lifecycle de la transformation Event → ProjectionTask READ_POT |
| `projection_tasks` | `created_at` | création durable de la tâche exacte par l'Event worker |
| claim `PROJECTION_TASK` | `claimed_at`, `ended_at` | acquisition puis fin de la tentative du task worker |
| slot `PROJECTION_TASK` | `done_at` | horloge du task worker, prise après `ProjectionWritePort.publish`, puis persistée atomiquement avec la projection |
| `projection_failure` | `failed_at` | échec terminal de projection ; ne date pas un READY |
| `projection_root` | aucun | l'existence de la clé signifie READY, sans date propre |

Les anciens champs de `projection_tasks_legacy` (`accepted_at`, `started_at`, `done_at`, etc.) ne
participent pas au pipeline canonique et ne doivent pas être utilisés.

## 2. Corrélation exacte

La chaîne fonctionnelle est :

```text
recorded_commands.command_id
  = command_outcomes.command_id
command_outcomes.APPLIED
  -> pot_id + resulting_version
pocoma_read.projection_root
  -> projection_type = READ_POT
  -> target_object_type = POT
  -> target_object_id = pot_id::text
  -> target_version = resulting_version
```

Le proxy T1 est relié à la même clé par la convention durable de `ProjectionTaskKeys` :

```text
consumption_slots.consumable_type = PROJECTION_TASK
consumable_components = [READ_POT, POT, potId::text, resultingVersion::text]
consumer_type = PROJECTION_EXECUTOR
consumer_components = [READ_POT]
status = DONE
terminal_outcome = SUCCESS
```

Il n'existe pas de clé étrangère entre `projection_root`, `projection_tasks` et
`consumption_slots`; la jointure se fait par cette identité canonique complète. La contrainte unique
du lifecycle garantit un seul slot par clé de consommation.

Pour une décomposition Event, l'Event métier peut être retrouvé par `pot_id + version`. Le schéma ne
porte cependant pas de `command_id` dans `business_event_outbox`; cette jointure repose donc sur
l'invariant métier d'une unique version produite pour un Pot, pas sur une FK explicite. `trace_id`
est un contexte de diagnostic, pas une clé relationnelle garantie de bout en bout.

## 3. Définitions proposées

### T0 — Command acceptée

Avec le schéma actuel : `recorded_commands.submitted_at`.

C'est le meilleur timestamp disponible : il est serveur, persiste dans la même transaction que la
Command et ordonne sa discovery. Il précède toutefois le commit qui rend la ligne éligible ; il doit
donc être présenté comme l'instant logique de soumission durable, et non comme le commit exact.

### T1 — READ_POT READY

Avec le schéma actuel : `consumption_slots.done_at` du slot `PROJECTION_TASK` exact, uniquement si
`terminal_outcome = SUCCESS` et si la `projection_root` exacte existe.

`ProjectionTaskConsumptionService` appelle la publication comme durable effect, puis
`FinalizeConsumptionService` capture `done_at`. `TransactionalFinalizeConsumptionUseCase` englobe
ces deux opérations dans une transaction unique. Le proxy est donc après le calcul et l'écriture de
la projection, et non au début de la Task. Il précède néanmoins le commit effectif de quelques
instructions et ne constitue pas un timestamp intrinsèque de `projection_root`.

## 4. Requête SQL utilisable aujourd'hui

Cette requête calcule la distribution du **proxy** `task slot done_at - submitted_at` pour les
Commands appliquées dont READ_POT existe réellement :

```sql
with applied as (
    select rc.command_id,
           rc.submitted_at,
           co.pot_id,
           co.resulting_version
    from recorded_commands rc
    join command_outcomes co using (command_id)
    where co.outcome_type = 'APPLIED'
), read_pot_done as (
    select cs.consumable_components ->> 2 as pot_id,
           (cs.consumable_components ->> 3)::bigint as target_version,
           cs.done_at
    from consumption_slots cs
    where cs.consumable_type = 'PROJECTION_TASK'
      and cs.consumer_type = 'PROJECTION_EXECUTOR'
      and cs.consumer_components = '["READ_POT"]'::jsonb
      and cs.status = 'DONE'
      and cs.terminal_outcome = 'SUCCESS'
), samples as (
    select a.command_id,
           extract(epoch from (d.done_at - a.submitted_at)) * 1000.0 as latency_ms
    from applied a
    join read_pot_done d
      on d.pot_id = a.pot_id::text
     and d.target_version = a.resulting_version
    join pocoma_read.projection_root pr
      on pr.projection_type = 'READ_POT'
     and pr.target_object_type = 'POT'
     and pr.target_object_id = a.pot_id::text
     and pr.target_version = a.resulting_version
)
select count(*) as command_count,
       min(latency_ms) as min_ms,
       avg(latency_ms) as mean_ms,
       percentile_cont(0.50) within group (order by latency_ms) as p50_ms,
       percentile_cont(0.90) within group (order by latency_ms) as p90_ms,
       percentile_cont(0.95) within group (order by latency_ms) as p95_ms,
       percentile_cont(0.99) within group (order by latency_ms) as p99_ms,
       max(latency_ms) as max_ms
from samples;
```

La répartition des issues Command se calcule séparément afin que seules les `APPLIED` alimentent la
latence READ :

```sql
select count(*) as accepted_commands,
       count(*) filter (where co.outcome_type = 'APPLIED') as applied,
       count(*) filter (where co.outcome_type = 'REJECTED') as rejected,
       count(*) filter (where co.outcome_type = 'FAILED') as failed,
       count(*) filter (where co.command_id is null) as unresolved
from recorded_commands rc
left join command_outcomes co using (command_id);
```

## 5. Décomposition disponible

Les données durables permettent d'approcher les segments suivants :

- acceptation → premier claim Command : `recorded_commands.submitted_at` → claim `COMMAND.claimed_at` ;
- exécution Command : claim `COMMAND.claimed_at` → slot `COMMAND.done_at` ou
  `command_outcomes.resolved_at` ;
- Event durable → ProjectionTask créée : `business_event_outbox.created_at` →
  `projection_tasks.created_at` ;
- ProjectionTask créée → claimée : `projection_tasks.created_at` → claim
  `PROJECTION_TASK.claimed_at` ;
- ProjectionTask claimée → proxy READY : claim `PROJECTION_TASK.claimed_at` → slot
  `PROJECTION_TASK.done_at`.

`consumption_claims` expose en plus tentatives, takeover, failure et fin de claim;
`consumption_slots` expose retry (`last_attempt_number`, `next_claim_at`) et issue terminale;
`consumption_results.created_at` fournit la provenance des effets lorsque le worker en produit.

## 6. Métriques, traces et logs existants

Le command worker expose actuellement via Micrometer :

- `pocoma.consumption.worker.running` ;
- `pocoma.consumption.poll.cycles` ;
- `pocoma.consumption.poll.candidates` ;
- `pocoma.consumption.poll.executions` ;
- `pocoma.consumption.poll.cycle.duration` ;

avec le tag `family=command`. Elles expliquent activité, débit de polling et saturation de budget,
mais ne corrèlent pas une Command à son READ_POT. Les runtimes Event et ProjectionTask ne publient
pas aujourd'hui un équivalent Micrometer spécialisé de cette observation.

Le Web initialise `X-Trace-Id`, MDC et `TraceContextHolder`. `business_event_outbox` et
`command_terminal_events` possèdent un `trace_id`, mais cette propagation n'est pas une chaîne
relationnelle garantie entre l'admission HTTP et tous les workers. Les logs/traces restent donc des
outils d'explication, pas la source de vérité du benchmark.

## 7. Trous et instrumentation minimale recommandée

Pour une mesure de référence robuste, ajouter dans un lot ultérieur deux timestamps sémantiques
pilotés par l'horloge PostgreSQL :

1. `recorded_commands.accepted_at`, fixé lors de l'insert d'admission ;
2. `pocoma_read.projection_root.ready_at`, fixé après l'insertion de tous les artifacts de la
   projection, dans la transaction de publication.

Les deux colonnes doivent utiliser la même source temporelle DB (`clock_timestamp()` ou équivalent
explicitement choisi), être immuables, et être testées avec rollback/idempotence. `ready_at` ne doit
être fixé qu'une fois la projection complète; une adoption `ALREADY_EXISTS` conserve la valeur du
premier publish gagnant. La requête de benchmark devient alors simplement
`projection_root.ready_at - recorded_commands.accepted_at` via `command_outcomes`.

Ce couple évite le clock skew entre runtimes et donne des bornes appartenant directement aux deux
objets fonctionnels mesurés. Comme tout timestamp écrit dans une transaction, `ready_at` précède de
peu le commit physique; sa sémantique doit être définie comme l'instant DB de la transition logique
atomique vers READY. Mesurer le commit WAL exact imposerait un mécanisme PostgreSQL beaucoup plus
lourd et n'est pas recommandé pour ce besoin.

Ne pas créer entre-temps une métrique Micrometer à cardinalité `commandId`/`potId`. La distribution
min/moyenne/p50/p90/p95/p99/max doit être calculée hors ligne depuis les lignes durables, tandis que
les métriques runtime à faible cardinalité servent uniquement à expliquer les goulots.
