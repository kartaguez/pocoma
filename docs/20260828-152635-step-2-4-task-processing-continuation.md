# Étape 2.4 — Suite : processing des tâches

## Adaptations retenues

- Créer `engine-processing-task`, homogène avec `engine-processing-command` et
  `engine-processing-event`.
- Placer `RecordedTask` sous `port.out.processing.task.model`.
- Ne mettre aucun claim, token, lease ou statut dans `RecordedTask`.
- Porter un `targetVersion` métier explicite, distinct de `pipelineVersion`.
- Conserver le payload sérialisé opaque dans le processing ; son décodage reste un rôle du futur
  worker/adaptateur entrant.
- Utiliser la segmentation stable existante `PartitionHash.forPipelinePot(pipelineId, potId)`.
- Conserver tous les workers, adapters JPA, tables et formats legacy inchangés.

## Livraison

1. Déplacer `TaskOrderingKey` dans le nouveau module.
2. Créer `TaskPort`, `RecordedTask`, les inputs, résultats et quatre use cases.
3. Implémenter claim, complete, fail et release par composition d'`engine-consumption`.
4. Ajouter les quatre décorateurs transactionnels.
5. Tester ordre, segmentation, mono-consommation, fencing token et transactions.
6. Compléter ArchUnit et la cartographie des use cases.
