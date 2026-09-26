# Cutover du runtime Event vers le matérialiseur canonique

Le runtime Event actif transforme les métadonnées durables d'un Event en `ProjectionTask` sous une
Consumption indépendante par projection :

```text
consumable = EVENT[eventId]
consumer   = PROJECTION_TASK_MATERIALIZER[projectionType]
```

Chaque processus doit déclarer explicitement `pocoma.event-consumption.projection-types`. Les
EventTypes et routes observés sont dérivés de la policy canonique ; ils ne sont jamais configurés
séparément. La segmentation utilise `business_event_outbox.pot_partition_hash`, conformément au
modèle EPT, et non l'ancien hash pipeline × Pot.

## Procédure

1. Arrêter tous les anciens workers Event capables d'appeler le scheduler pipeline legacy et empêcher
   leur redémarrage pendant le cutover.
2. Déployer la version EPT.5 avec `pocoma.event-consumption.enabled=false` et un set
   `projection-types` explicite sur chaque instance.
3. Exécuter `event-consumption-preflight.sql` pour vérifier les tables et identités canoniques.
4. Exécuter `event-consumption-validate.sql`. Les anciens slots
   `PROJECTION_TASK_SCHEDULER` peuvent rester comme historique ; ils ne sont plus une autorité.
5. Activer un segment pilote, puis vérifier que les nouveaux slots utilisent
   `PROJECTION_TASK_MATERIALIZER[projectionType]` et que les effets apparaissent dans
   `projection_tasks`, sans nouvelle ligne `tasks_4_pipeline` ni provenance Event legacy.
6. Activer les segments complémentaires avec le même `segment-count`. Leur union doit couvrir chaque
   Pot une fois selon `pot_partition_hash`.

Le runtime ne recharge pas `payload_json`, ne consulte aucune pipeline generation et n'utilise ni
`TransactionalExecuteConsumptionUseCase` ni `CanonicalProjectionTaskScheduler`. Une erreur technique
conserve la sémantique acquire/finalize livrée par EPT.4 ; ce runbook n'ajoute aucune policy de retry
ou de terminalisation.
