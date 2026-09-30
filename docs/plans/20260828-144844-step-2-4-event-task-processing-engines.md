# Étape 2.4 — Processing Event et Task

## Objectif

Créer `engine-processing-event` et `engine-processing-task` sur le modèle de
`engine-processing-command`, sans worker, persistence PostgreSQL ou wiring Spring.

## Différence structurante

- Command et Task sont mono-consommation : leur clé est fondée sur leur identifiant propre.
- Event est multi-consommation : sa clé est `(pipelineId, pipelineVersion, eventId)`.
- Un Event ne porte aucun statut global. Complete, fail et release affectent uniquement la
  consommation du pipeline concerné.

## Sous-étapes

1. **2.4.1** — Partager `RecordedEvent` et `EventTraceMetadata` depuis `engine-core` et enrichir
   le contrat `BusinessEvent` avec `potId` et `version`.
2. **2.4.2** — Créer `engine-processing-event` et y déplacer l'ordre Event.
3. **2.4.3** — Introduire `EventPort`, les inputs, résultats et quatre use cases de processing.
4. **2.4.4** — Implémenter claim, complete, fail, release et leurs décorateurs transactionnels.
5. **2.4.5** — Créer `engine-processing-task` et une représentation `RecordedTask` sans claim.
6. **2.4.6** — Introduire les ports, use cases et services Task mono-consommation.
7. **2.4.7** — Stabiliser la segmentation commune `pipelineId + potId`.
8. **2.4.8** — Compléter tests, règles d'architecture et documentation du legacy.

## Ordre et clés

```text
Event : version métier → recordedAt → eventId
key   : ConsumptionKey("event", [pipelineId, pipelineVersion, eventId])

Task  : targetVersion → createdAt → taskId
key   : ConsumptionKey("task", [taskId])
```

Une table commune future stockera les consommations Event avec unicité logique sur
`(pipeline_id, pipeline_version, event_id)`. Toute tâche durable portera un `targetVersion`
métier explicite. Un appel à `claimNext` ciblera un seul `PipelineDefinition`.

## Limites

Pas de changement de table, worker, payload JSON, heartbeat ou retry d'un état terminal pendant
cette étape.
