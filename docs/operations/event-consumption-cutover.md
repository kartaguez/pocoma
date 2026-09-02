# Cutover Event vers `engine-consumption`

L'ancien statut global de `business_event_outbox` n'est jamais converti en slot : il ne permet pas
de reconstruire le consumer `PIPELINE[pipelineId,pipelineVersion]`. Les lignes
`event_4_pipeline_materialization_status` restent un bridge d'adoption par identité exacte.

1. Arrêter les anciens workers Event/materialization et bloquer leur redémarrage.
2. Exécuter `event-consumption-preflight.sql`. Toute identité ambiguë, materialization `FAILED`,
   Event absent ou ligne `SKIPPED` possédant des Tasks doit être résolu explicitement.
3. Exécuter `event-consumption-validate.sql`, puis activer un segment pilote du nouveau worker.

`MATERIALIZED` adopte les Tasks existantes. `SKIPPED` signifie historiquement que le pipeline ne
s'appliquait pas : il devient une consommation `SUCCESS` sans Task. L'adoption est volontairement
lazy afin que le slot et la provenance soient écrits par la transaction authoritative normale.
Aucun fallback vers `balance-projection/v1` n'est autorisé.
