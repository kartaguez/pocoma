# Étape 3.2 — `supra-worker-command`

## Résumé

Créer le premier worker cible autour de la boucle séquentielle 3.1 :

```text
SingleItemPullLoop
→ ClaimNextCommandUseCase
→ ExecutionGuard<UUID>
→ ExecuteCommandUseCase
→ CompleteCommandProcessingUseCase
```

Le worker traite au maximum une Command, ne contient aucun métier, ne connaît ni PostgreSQL,
JSON, JPA ou Spring, utilise le claimToken pour le lifecycle et un guard distinct pour empêcher la
répétition d'un effet déjà commité.

## 3.2.1 — Archivage

Archiver le présent plan et amender la roadmap 3–7 sans changer l'ordre des étapes.

## 3.2.2 — `engine-execution-guard`

Créer un engine technique générique composé de :

```text
port/in/execution/result/ExecutionOutcome
port/in/execution/usecase/ExecutionGuard
port/out/execution/ExecutionJournalPort
service/execution/ExecutionGuardService
service/execution/transaction/TransactionalExecutionGuard
```

`ExecutionOutcome` contient `EXECUTED` et `ALREADY_EXECUTED`.
`ExecutionGuard<K>.executeOnce(K, Runnable)` tente d'enregistrer la clé avant le callback dans la
même transaction. Une clé existante n'appelle pas le callback. Une exception provoque le rollback
du journal et de l'effet. L'engine dépend uniquement d'engine-core/JDK et ignore consommation,
Command, Task, workers et frameworks. PostgreSQL reste en étape 4.

## 3.2.3 — `supra-worker-command`

Créer le module et les types : `CommandWorker`, `CommandWorkerIteration`, `CommandWorkerSettings`,
observation/noop, outcomes, observation data et mapper de failure. Il dépend des ports entrants de
Command, processing Command, execution guard, domain-consumption, engine-core et orchestrateur.

## 3.2.4 — Settings

Les settings contiennent enabled, workerId, pollingInterval, leaseDuration,
maxNormalProcessingDuration, WorkerSegment et wakeSignalsEnabled. Toutes les durées sont positives
et le démarrage exige, sans overflow :

```text
leaseDuration >= 3 × maxNormalProcessingDuration
```

La durée normale n'est pas un timeout. Il n'existe ni heartbeat, batch, queue, pool ou retry.

## 3.2.5 — Itération

Une itération appelle claimNext une fois. Sans travail elle retourne false. Après claim, un arrêt
avant effet provoque release. Sinon elle appelle le guard avec `commandId`, qui appelle
`ExecuteCommandUseCase` avec l'input reconstruit, puis complete avec le token du claim.

Le worker ne construit ni clé de consommation, ordre, segment propriétaire ou statut durable.

## 3.2.6 — Erreurs

- exception du callback métier/applicatif : rollback du guard, mapping en `ProcessingFailure`, fail ;
- erreur technique/commit inconnu du guard : ni fail ni release, propagation et expiration du claim ;
- erreur de complete/fail/release : aucune compensation, propagation ;
- ownership perdu : observation, aucune autre transition, cycle local terminé.

Le mapper utilise une catégorie stable, un message assaini sans stack trace et un Clock.

## 3.2.7 — Guard Command

La clé est uniquement `commandId`. `EXECUTED` et `ALREADY_EXECUTED` conduisent tous deux à complete,
mais le second n'appelle pas le métier. Cela couvre le crash après effet+journal commit et avant
complete. Le guard ne reçoit aucun claim, token, worker, lease ou RecordedCommand.

## 3.2.8 — Lifecycle

`CommandWorker` expose start, stop, isRunning et runOnce autour de SingleItemPullLoop. Stop signale
d'abord l'arrêt : un claim sans effet commencé est libéré, un effet commencé n'est ni interrompu ni
libéré. Le polling suffit ; le bus noop est utilisable et les notifications restent optionnelles.

## 3.2.9 — Observation

Observer les outcomes bornés IDLE, succès exécuté/déjà exécuté, failure, release, ownership lost,
erreur technique, warning lease et dépassement. Les données contiennent seulement outcome, durée,
lease et segment. Aucun identifiant métier ou token. Warning à 80 %, exceeded à 100 %. Aucun
Micrometer dans le module.

## 3.2.10 — Ordre et segmentation

Le worker passe seulement WorkerId, ClaimLease et WorkerSegment au use case. L'ordre reste
createdAt/commandId. Les Commands avec potId sont segmentées ; celles sans potId sont visibles par
tous et départagées par le claim. Aucun verrou Pot ou coordinateur.

## Tests

- guard : première exécution, déjà exécuté, clés distinctes, exception exacte, rollback commun ;
- itération : idle, exécution, déjà exécuté, failure, arrêt/release, erreurs techniques, stale token ;
- concurrence : sérialisation par worker, parallélisme entre instances, une victoire de claim ;
- crash : effet journalisé + complete échoué + reclaim sans répétition ;
- lease : ratio 3×, warning, dépassement sans interruption, fencing ;
- lifecycle : disabled, start/stop idempotents, réveil d'arrêt, reprise après erreur ;
- ArchUnit : aucun port sortant/repository/framework dans le worker, guard indépendant de consumption.

## Critères de fin

Le guard générique et le worker compilent, une seule Command est traitée par itération, tout effet
passe par ExecuteCommandUseCase, une Command journalisée n'est pas rejouée, le token courant clôt le
cycle, les failures fonctionnelles sont terminales après rollback, les issues inconnues ne sont pas
faussement marquées FAILED, le ratio 3× est validé/observé et le build global est vert.

## Étape suivante — 3.3

Créer `supra-worker-event` autour de claim Event → CreateTasksForEventUseCase → complete, avec une
instance par pipeline/version/segment, multi-consommation indépendante, zéro tâche comme succès,
idempotence pipeline/version/event et le même invariant de lease sans heartbeat.
