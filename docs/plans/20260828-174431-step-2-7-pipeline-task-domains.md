# Étape 2.7 — Séparation des domaines Pipeline et Task

## Objectif

Remplacer `domain-pipeline-tasks` par deux domaines minimaux : `domain-pipeline`
pour l'identité/version des pipelines et `domain-task` pour le contrat des payloads
fonctionnels typés. Tous les objets de persistence, claiming, configuration de
worker ou sérialisation sortent des modules de domaine.

## Répartition

- `PipelineId`, `PipelineDefinition` → `domain-pipeline`.
- `PipelineTaskPayload` → `domain-task.TaskPayload`.
- `TaskDescriptor` → `engine-core`, comme instruction applicative transitoirement
  sérialisée.
- `PipelineTask` → `engine-task-execution` sous le nom `LegacyPipelineTask`.
- `ConfiguredTaskExecutionBinding` → legacy d'`engine-task-execution`.
- `PipelineTaskStatus` → `infra-persistence-jpa` sous le nom
  `JpaPipelineTaskStatus`.
- `PipelineTaskClaim` → suppression.

## Invariants

- Les nouveaux domaines dépendent uniquement du JDK.
- `ComputeBalancesTask` reste dans le runtime de son pipeline et implémente
  `TaskPayload`.
- Les tables, colonnes, valeurs d'enum, JSON, clés de consommation, ordre,
  segmentation et workers restent inchangés.
- Les packages fonctionnels typés ne dépendent jamais du modèle legacy.
- L'ancien module est supprimé sans artifact ni alias de compatibilité.

## Validation

Valider successivement les deux domaines, les engines task creation/execution et
processing Event/Task, le legacy materialization, les workers, la persistence,
les runtimes, ArchUnit et enfin le reactor Maven complet.

## Étape suivante

L'étape 2.8 traitera `domain-policy`, `domain-projection` et le reliquat
d'`engine-core`.
