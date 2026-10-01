# Lot 5 — Migration transactionnelle du flux Task et projection Balance immuable

Ce lot migre le traitement de `tasks_4_pipeline` vers le moteur de consommation transactionnel.
La clé cible est `TASK[taskId] / TASK_EXECUTOR[]`; le provider Task ne consulte ni le lifecycle
Consumption ni le lifecycle Task legacy.

L'exécution gagnante recharge la Task et la version historique exacte du Pot, calcule puis
persiste une projection Balance immuable identifiée par pipeline/version et Pot/version, écrit la
provenance et termine le slot par le CAS `current_claim_id` dans une seule transaction. Une perte
de Claim rollbacke l'ensemble. Les failures techniques sont traitées ensuite dans une transaction
courte; une rejection fonctionnelle produit `DONE/REJECTED`.

Le stockage cible introduit `target_version` sur les Tasks et deux tables immuables
`balance_projection_artifacts` / `balance_projection_entries`. Une projection identique déjà
présente est un succès idempotent; une projection différente pour la même identité produit la
failure terminale `BALANCE_PROJECTION_CONFLICT`.

Le cutover arrête les claimers legacy, attend l'expiration des claims, laisse `PENDING` sans slot,
traduit `DONE/FAILED/SUPERSEDED` en slots `SUCCESS/FAILED/ABANDONED`, puis active le nouveau
runtime. Les colonnes legacy restent temporairement physiques mais ne sont plus lues par le
nouveau chemin.

Les modules cibles sont `pipeline-balance`, `locator-consumption-task` et
`runtime-task-consumption-worker`. Les anciens modules Task, leurs `ClaimToken`, services
Complete/Fail/Release et leur usage d'ExecutionGuard sont supprimés après validation du cutover.
