# Cutover du scheduler Event vers les Tasks de projection applicables

Le scheduler Event du Lot 7.5 est piloté par le catalogue canonique. Il découvre une combinaison
`Event + PipelineVersionDefinition` tant que la Task Event-derived exacte
`(eventId, pipelineId, pipelineVersion)` manque. Son slot porte la même génération exacte :

```text
consumable = EVENT[eventId]
consumer   = PROJECTION_TASK_SCHEDULER[pipelineId,pipelineVersion]
```

Un slot terminal d'une ancienne génération ne ferme donc pas l'Event aux générations ajoutées plus
tard. Le runtime ne requiert plus de propriétés `pipeline-id` ou `pipeline-version`.

## Procédure

1. Arrêter les anciens workers Event/materialization et bloquer leur redémarrage.
2. Appliquer la migration V10. Son preflight doit échouer plutôt que dédupliquer des Tasks ambiguës.
3. Exécuter `event-consumption-preflight.sql`. Il vérifie la disparition du parent legacy, la cohérence
   Event/Pot/version et l'unicité Event-derived directe.
4. Exécuter `event-consumption-validate.sql`, puis activer un segment pilote du scheduler catalog-driven.
5. Contrôler que les slots créés utilisent `PROJECTION_TASK_SCHEDULER[pipelineId,pipelineVersion]` et
   que chaque Task porte directement `event_id`, `pipeline_id`, `pipeline_version`, `pot_id` et
   `target_version`.

Le scheduler ne consulte ni le read store ni `PipelineSelectionStrategy`. Une définition applicable
ajoutée au catalogue rend naturellement les anciens Events éligibles si leur Task exacte manque ;
aucun reset de slot et aucune API de replay ne sont nécessaires.

L'unicité actuelle vise les Tasks Event-derived. Elle ne définit pas l'identité universelle des futures
Tasks administratives du Lot 7.8.
