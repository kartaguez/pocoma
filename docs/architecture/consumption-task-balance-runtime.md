# Runtime transactionnel Task et projection Balance

## Frontière d'exécution

Le provider Task effectue une recherche courte et best-effort. Il ne consulte ni
`consumption_slots`, ni les colonnes de claim et de statut legacy. La décision d'exécuter appartient
exclusivement à `AcquireConsumption` avec la clé suivante :

```text
TASK[taskId] / TASK_EXECUTOR[]
```

Après acquisition, `TaskConsumptionLocator` fournit une recette fermée sur le seul `taskId` et le
binding stable. `TransactionalExecuteConsumptionUseCase` ouvre alors la transaction qui contient :

```text
reload RecordedTask(taskId)
validate and decode the durable payload
load Pot at the exact targetVersion
calculate and persist the immutable projection
persist ConsumptionInput and ConsumptionResult
CAS status=PENDING and current_claim_id=myClaimId
commit
```

Un CAS perdu lève `LostClaimException` et annule projection, provenance et outcome. L'expiration du
lease seule ne retire aucune autorité : seul un takeover remplaçant `currentClaimId` fence l'ancien
worker.

## Version historique et projection immuable

`targetVersion` est la version historique exacte du Pot. Elle ne représente ni une version
courante, ni une borne, ni une contrainte d'ordre. Les Tasks V43 et V42 peuvent donc terminer dans
n'importe quel ordre.

Une projection Balance est identifiée par :

```text
(projectionType, pipelineId, pipelineVersion, potId, potVersion)
```

Une projection absente est insérée. Une projection identique est adoptée sans mutation. Un contenu
différent pour la même identité viole un invariant et produit la failure terminale
`BALANCE_PROJECTION_CONFLICT`.

La provenance fonctionnelle nominale est :

```text
input  = POT / potId / targetVersion
result = BALANCE_PROJECTION / POT_BALANCES / projectionId
         objectVersion = pipelineVersion
         subject = POT / potId / targetVersion
```

## Outcomes et failures

Un `TaskExecutionReport.Rejected` est traduit en `DONE/REJECTED` avec son code comme raison terminale
dans la transaction gagnante et ne passe jamais par le classifier. Une exception technique annule d'abord entièrement cette
transaction, puis `HandleConsumptionFailure` applique la policy dans une transaction courte
distincte. `LostClaimException` n'est jamais classifiée.

Les erreurs de configuration, d'entrée durable, de payload et les conflits de projection échouent
immédiatement avec leur catégorie comme raison terminale. Les autres failures techniques suivent le
backoff 1 s, 5 s, 30 s sans raison sur le slot, puis deviennent terminales avec leur catégorie.

## Cutover legacy

Le nouveau runtime ne lit et n'écrit aucun état lifecycle legacy de `tasks_4_pipeline`. Le cutover
est une opération explicite : arrêt de l'ancien runtime, extinction des claims, backfill des slots
terminaux, validation, puis activation du nouveau runtime. Les scripts et le runbook se trouvent
dans `docs/operations`.
