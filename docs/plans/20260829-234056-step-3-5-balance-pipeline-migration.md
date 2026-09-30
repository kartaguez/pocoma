# Étape 3.5 — Migration du pipeline Balance vers le TaskWorker cible

## Résumé

Adapter `balance-projection`, version 1, au flux cible :

```text
RecordedTask
→ ComputeBalancesRecordedTaskMapper
→ ComputeBalancesTask
→ ExecuteTaskUseCase
→ ExecuteBalanceProjectionTaskHandler
→ ComputePotBalancesUseCase
```

Le flux legacy reste seul actif mais partage exactement le même décodage. Aucun TaskWorker cible,
adapter PostgreSQL, guard durable ou propriété d'activation cible n'est créé en 3.5.

## Contrats et mapping

- Conserver `ComputeBalancesTask` comme payload fonctionnel (`potId`, `targetVersion >= 1`) et comme
  propriétaire des constantes pipeline/version/type.
- Créer `ComputeBalancesRecordedTaskMapper`, implémentant
  `RecordedTaskExecutionMapper<ComputeBalancesTask>`, et le DTO JSON package-private
  `ComputeBalancesSerializedPayload`.
- Préserver le JSON `{potId,targetVersion}` et vérifier syntaxe, UUID, version positive, cohérence
  avec `RecordedTask.potId` et `RecordedTask.targetVersion`, pipeline et type.
- Toute donnée durable définitivement invalide produit `INVALID_TASK_PAYLOAD` avec un message stable
  sans identifiant ni payload. Les incohérences de wiring conservent leurs codes dédiés.
- Le mapper offre une entrée legacy transitoire pour `LegacyPipelineTask`, qui vérifie le `potId` et
  utilise la version décodée puisque le modèle legacy ne la porte pas séparément.

## Pont legacy et wiring

- Retirer Jackson, le DTO privé et le décodage de `ComputeBalancesPipelineTaskExecutionStrategy` ;
  injecter le mapper et déléguer à son entrée legacy.
- Enregistrer le mapper et `RecordedTaskExecutionMapperRegistry` dans la configuration Spring du
  runtime, tout en conservant le handler typé, son registre et `ExecuteTaskUseCase`.
- Ajouter au runtime la dépendance vers `supra-worker-task` pour ses seuls contrats de mapping.
- Ne déclarer aucun bean `TaskWorker`, guard, processing adapter ou nouvelle propriété. Le binding
  legacy Balance reste seul actif. L'exclusion stricte legacy XOR cible sera câblée en étape 5.
- Le mapper n'ouvre aucune transaction. La future transaction du guard couvrira journal et effet ;
  les transactions legacy restent inchangées.

## Tests

- Mapper : nominal, JSON/UUID/version invalides, champs absents, divergences Pot/version,
  pipeline/type incorrects, codes et messages sûrs.
- Flux typé : registre vers handler puis `ComputePotBalancesUseCase`, sans modèle legacy.
- Pont legacy : même `ExecuteTaskInput` que la voie cible, une seule implémentation de codec,
  payload incohérent refusé et exception du handler propagée.
- Wiring : un mapper et un registre présents, handler et stratégie legacy branchés, aucun TaskWorker
  cible, binding legacy conservé.
- Architecture : Jackson limité au runtime mapper, handler et payload indépendants du legacy,
  aucun engine ne dépend du runtime.

## Validation

```text
./mvnw -pl runtime-pipeline-task-executor -am test
./mvnw -pl supra-worker-task,engine-task-execution -am test
./mvnw -pl architecture-tests -am test
./mvnw clean test
```

Il ne doit y avoir aucun changement SQL/JSON, heartbeat, retry, batch, préfetch ou double activation.

## Étape suivante — 3.6

Consolider les tests transverses Command/Event/Task : séquentialité, parallélisme entre instances,
fencing, dépassement de lease, crash post-commit, idempotence et pureté des workers.
