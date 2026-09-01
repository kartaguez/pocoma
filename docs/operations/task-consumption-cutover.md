# Cutover du runtime Task transactionnel

Ce cutover sépare volontairement la migration du lifecycle legacy du nouveau provider Task. Après
activation, `JpaTaskPort` lit seulement l'identité métier, le binding, le payload, la version cible et
le curseur technique. Il ne lit plus `status`, `claim_token`, `claimed_by`, `lease_until` ou
`attempt_count`.

## Préconditions

1. Déployer Flyway V5 et le runtime Task avec `pocoma.task-consumption.enabled=false`.
2. Arrêter `runtime-pipeline-task-executor` et toute autre instance claimant `tasks_4_pipeline`.
3. Bloquer leur redémarrage automatique dans l'orchestrateur de déploiement et conserver dans le
   dossier de changement l'inventaire des workloads arrêtés. SQL ne peut pas prouver qu'un ancien
   binaire ne redémarrera pas.
4. Exécuter `sql/task-consumption-preflight.sql`.
5. Attendre la fin ou l'expiration de tous les claims signalés. Un claim sans échéance doit être
   résolu manuellement ; le script refuse de l'adopter silencieusement.

Le preflight refuse également toute Task du pipeline `balance-projection` dont `partition_key`
est absent ou n'est pas un UUID PostgreSQL valide. Il contrôle les autres champs structurels lus
avant acquisition (`pipeline_version`, `target_version`, `task_type` et présence du payload opaque),
mais ne décode pas le JSON métier de `task_payload` : ce décodage reste une responsabilité de
l'exécution après acquisition.

Conserver l'inventaire des lignes à corriger avant de relancer le preflight :

```sql
select id, pipeline_id, pipeline_version, task_type, partition_key, target_version
from tasks_4_pipeline
where pipeline_id = 'balance-projection'
  and (partition_key is null or not pg_input_is_valid(partition_key, 'uuid')
       or pipeline_version < 1 or target_version is null or target_version < 1
       or task_type is null or btrim(task_type) = ''
       or task_payload is null or btrim(task_payload) = '');
```

## Migration logique

Exécuter `sql/task-consumption-cutover.sql` dans une fenêtre sans ancien exécuteur :

- `PENDING` reste sans slot et sera acquise normalement ;
- `CLAIMED`, `ACCEPTED` et `RUNNING` expirées redeviennent techniquement `PENDING`, sans slot ;
- `DONE` devient un slot `DONE/SUCCESS` ;
- `FAILED` devient un slot `DONE/FAILED` ;
- `SUPERSEDED` devient un slot `DONE/ABANDONED`.

Les slots utilisent `TASK[taskId] / TASK_EXECUTOR[]`. Aucun Claim synthétique n'est inventé : le
compteur reste à zéro et l'audit des anciennes tentatives reste dans `tasks_4_pipeline`.

Le script est idempotent. Il refuse cependant un slot préexistant dont l'outcome contredit l'état
legacy : cette situation doit être investiguée, pas écrasée.
Il répète sous transaction les contrôles structurels du preflight afin qu'un preflight ancien,
ignoré ou devenu obsolète ne puisse laisser entrer une Task qui bloquerait le discovery.

## Activation

1. Exécuter `sql/task-consumption-validate.sql` et conserver les résultats avec le dossier de
   déploiement.
2. Activer une instance du nouveau runtime sur un segment pilote.
3. Vérifier acquisitions, `ALREADY_DONE`, provenance et projections immuables.
4. Étendre aux autres segments.
5. Supprimer les anciens binaires et configurations d'exécution Task.

Les colonnes lifecycle legacy restent physiquement présentes pour l'audit et leurs contraintes
historiques. Leur suppression appartient à une migration SQL ultérieure. Aucun code du chemin cible
ne doit les consulter après le cutover.

La validation reproduit la clé et la condition du nouveau discovery. Elle échoue si une Task
terminale traduite pourrait être sélectionnée, indépendamment de ses colonnes lifecycle legacy.
