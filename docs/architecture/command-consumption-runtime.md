# Runtime de consommation des Commands

## Topologie et responsabilités

L'admission HTTP et le traitement des Commands sont deux processus indépendants. L'API persiste une
demande et répond `202 Accepted`; cela ne signifie jamais que la Command a réussi. Le runtime
`runtime-command-consumption-worker` traite ensuite les demandes de manière éventuelle :

```text
recorded_commands
  -> ConsumptionPollingWorker
  -> SequentialConsumptionOrchestrator
  -> CommandConsumptionLocator
  -> discovery best effort
  -> acquire autoritatif
  -> reload / execute / append Events
  -> fencing / terminalisation ou retry
```

Le runtime ne contient aucune logique métier Command. `binding-pot-command-spring` assemble les
decoders et adapters Pot derrière les contrats d'`engine-command`; le polling reste entièrement
générique. Aucun slot n'est créé avant `acquire` et aucune queue locale ne mémorise le backlog.

La composition distribuée sépare explicitement les processus `runtime-web-api`,
`runtime-command-consumption-worker`, `runtime-event-consumption-worker` et
`runtime-task-consumption-worker`. Le service `pocoma-command-consumption-worker` partage PostgreSQL
avec les autres runtimes et active uniquement sa propre boucle avec
`POCOMA_COMMAND_CONSUMPTION_ENABLED=true`. Aucun `worker-id` fixe n'est configuré : l'identité UUID
générée au démarrage reste unique entre replicas. Le runtime Command n'est activé ni dans le web
runtime, ni dans le monolithe, et cette topologie ne réalise aucun cutover du chemin legacy.

## Polling et configuration

Les propriétés sont portées par le préfixe `pocoma.command-consumption` :

| Propriété | Défaut | Rôle |
|---|---:|---|
| `enabled` | `false` | active explicitement la boucle |
| `worker-id` | UUID de démarrage préfixé | identité stable pendant la vie du processus |
| `claim-lease` | `30s` | durée de lease générique |
| `max-candidates-inspected` | `100` | budget de discovery d'un cycle |
| `max-consumptions-executed` | `10` | budget d'exécutions d'un cycle |
| `poll-interval` | `1s` | attente après un cycle idle |
| `runtime-failure-backoff` | `5s` | attente après une défaillance runtime |

Un budget épuisé déclenche immédiatement un nouveau cycle. Un cycle idle attend au plus le poll
interval (ou une éligibilité connue plus proche). Une défaillance runtime applique le backoff afin
d'éviter une boucle agressive. Un worker n'exécute qu'une consommation à la fois ; plusieurs
processus fournissent le parallélisme horizontal sans coordination supplémentaire.

## Arrêt coopératif

Le `SmartLifecycle` Spring demande l'arrêt via `ConsumptionPollingWorker.requestStop(callback)`.
Le worker réveille une attente idle/backoff, n'accepte plus de nouveau cycle et laisse terminer sans
interruption une exécution déjà active. Le callback est invoqué exactement lorsque la boucle est
arrêtée. Il n'y a ni `join` non borné, ni release artificiel du claim.

La fenêtre maximale de grâce appartient au lifecycle Spring et vaut actuellement `30s` via
`spring.lifecycle.timeout-per-shutdown-phase`. Si le processus est forcé avant la fin, la
transaction non committée rollbacke puis la lease permet un takeover ultérieur.

## Observabilité

`ConsumptionPollingWorkerObservation` observe uniquement le runtime de polling. Le binding
Micrometer Command expose :

- `pocoma.consumption.poll.cycles`;
- `pocoma.consumption.poll.candidates`;
- `pocoma.consumption.poll.executions`;
- `pocoma.consumption.poll.cycle.duration`;
- `pocoma.consumption.worker.running`.

Les tags sont bornés à `family=command` et, lorsque pertinent,
`outcome=idle|budget_exhausted|runtime_failure`. Aucun `workerId`, `commandId`, slot ou claim n'est
utilisé comme tag. Les outcomes durables `SUCCESS`, `REJECTED`, `FAILED` et les retries restent dans
`ConsumptionSlot`, `Claim` et `ProcessingFailure`; le Lot 6.7 n'ajoute aucun callback à
`SequentialConsumptionOrchestrator` ou `engine-consumption` pour les dupliquer en métriques.

Dans la composition distribuée, le runtime expose sur son port interne `8080` :

- `/actuator/health` ainsi que les probes `/actuator/health/liveness` et
  `/actuator/health/readiness` ;
- `/actuator/prometheus`, alimenté par le registre Micrometer Prometheus.

Le job Prometheus `pocoma-command-consumption-worker` scrape directement cet endpoint sur le réseau
Docker. La santé PostgreSQL repose sur les contributors standards Spring Boot ; un résultat durable
`REJECTED` ou `FAILED` ne modifie pas à lui seul la santé du processus.

## Fermeture du write side Command

Le runtime cible n'expose plus les mutations synchrones Pot/Expense. La seule chaîne officielle est :

```text
POST /api/v1/commands
  -> recorded_commands (commit)
  -> runtime-command-consumption-worker
  -> use-case port métier Pot
  -> mutation primaire + BusinessEvent (commit atomique)
```

Les dix interfaces spécialisées `*UseCase` de `engine-pot-command` restent les inbound ports métier.
Les adapters Command durables les invoquent sans dépendre des classes de service concrètes ; seuls le
routeur `ExecuteCommandService`, ses wrappers transactionnels synchrones et l'ancien lifecycle
Command ont été retirés. Les GET, Event, Task et projections existants restent inchangés.

`WriteSideClosurePostgresTest` est un test d'intégration fonctionnel cross-runtime : il traverse la
vraie admission HTTP, le commit PostgreSQL, la vraie boucle de polling, le reload autoritatif, la
mutation Pot et l'append Event. Les deux côtés partagent uniquement l'état durable PostgreSQL. Comme
ils sont assemblés dans un même contexte de test, ce test ne prétend pas démontrer deux JVM, le réseau
Docker ou le scrape Prometheus ; ces propriétés relèvent des tests de composition et des runtimes.

## Sémantique opérationnelle

Une Command peut aboutir ultérieurement à `SUCCESS`, `REJECTED`, `FAILED` ou rester `PENDING` avec
un retry planifié. Deux workers peuvent découvrir le même candidat, mais un seul claim autorise le
commit. Une Command déjà `DONE` ne produit aucun second effet, même si elle réapparaît dans un scan.
Le runtime ne journalise jamais le payload, le bearer token ou le snapshot d'autorisation complet.
