# Correction de l'étape 2.3 — nommage du processing des commandes

## Décisions

- Renommer `engine-command-processing` en `engine-processing-command`.
- Employer systématiquement la hiérarchie `processing.command` dans les packages.
- Préparer ainsi les modules homologues `engine-processing-event` et `engine-processing-task`.
- Renommer `DurableCommand` en `RecordedCommand`.
- Placer `RecordedCommand` dans `port.out.processing.command.model`, car il constitue le contrat de données retourné par `CommandPort`.
- Retirer `ConsumptionStatus` de `RecordedCommand` : `findNextReady` garantit déjà l'éligibilité et l'état persistant reste une responsabilité de l'adapter sortant.

## Structure corrigée

```text
engine-processing-command
└── com.kartaguez.pocoma.engine
    ├── processing.command.ordering
    ├── port.in.processing.command
    │   ├── input
    │   ├── result
    │   └── usecase
    ├── port.out.processing.command
    │   ├── CommandPort
    │   └── model.RecordedCommand
    └── service.processing.command
        └── transaction
```

Cette correction ne change ni le cycle de claim, ni l'ordre, ni la segmentation, ni les frontières transactionnelles définies par l'étape 2.3.
