# DEBT-TEST-01 — Flakiness du test PostgreSQL de takeover

- Statut : **OPEN**
- Severity : **UNRATED**
- Défaut runtime actuel : **NOT ESTABLISHED**
- Impact sur le modèle canonique : **NONE ESTABLISHED**
- Urgence : non bloquante

## Observation

Lors du chantier documentaire de qualité du repository livré par le commit `ed4f01c7`, la première exécution du reactor complet a vu échouer une fois le scénario PostgreSQL :

`CommandResultConsumptionChainPostgresTest#takeoverFencesStaleWorkerAndConvergesToOneResult`

Le retry ciblé du test a réussi, puis la relance complète du reactor a réussi. La CI GitHub du commit livré est également verte.

Cette observation suffit à enregistrer un risque de flakiness, mais **ne démontre pas** un défaut du mécanisme runtime de claim, lease ou takeover.

## Risque

Le scénario dépend de l'expiration d'une lease et d'une course entre ancien et nouveau worker. Une sensibilité excessive :

- à l'horloge murale ;
- aux délais d'ordonnancement ;
- à des attentes temporelles trop serrées ;
- ou à l'ordre relatif de commits concurrents

peut rendre le test intermittent.

À l'inverse, si le test est robuste, l'échec initial pourrait révéler une fenêtre de concurrence réelle dans le comportement testé. Ce point reste à établir.

## Direction d'investigation

- Reproduire le scénario de manière répétée et isolée avant toute modification.
- Identifier précisément l'assertion et l'état persistant observés lors d'un échec.
- Auditer l'usage du temps dans le test et dans la gestion des leases.
- Préférer, si possible, une horloge contrôlée ou des conditions déterministes à des attentes basées sur des délais réels.
- Vérifier séparément les invariants :
  - le stale worker ne peut plus finaliser après perte du claim ;
  - le takeover peut progresser ;
  - un seul résultat autoritaire converge.
- Ne modifier le runtime que si une violation fonctionnelle reproductible est démontrée.

## Critère de résolution

La dette peut être résolue lorsque l'une des conclusions suivantes est établie et prouvée :

1. la flakiness provenait du test, qui a été rendu déterministe sans affaiblir la preuve ; ou
2. un défaut runtime a été identifié, corrigé et couvert par une preuve de non-régression ; ou
3. une investigation reproductible démontre que l'échec initial ne correspond plus à un scénario possible, avec justification documentée.

## Provenance

Observation issue de la vérification du chantier `docs: improve repository quality and project overview` (`ed4f01c7165cc5dbc2e1453bb55bba6ea63da8c4`).

Test concerné : [CommandResultConsumptionChainPostgresTest](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandResultConsumptionChainPostgresTest.java).
