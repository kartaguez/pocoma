# DEBT-TEST-01 — Flakiness du test PostgreSQL de takeover

- Statut : **RESOLVED** (2026-10-15)
- Historical incident diagnosis: **INCONCLUSIVE**.
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

Les critères d'investigation initiaux étaient les suivants :

1. la flakiness provenait du test, qui a été rendu déterministe sans affaiblir la preuve ; ou
2. un défaut runtime a été identifié, corrigé et couvert par une preuve de non-régression ; ou
3. une investigation reproductible démontre que l'échec initial ne correspond plus à un scénario possible, avec justification documentée.

## Décision de clôture — 2026-10-15

La clôture est validée comme résolution de la **dette de qualité et de couverture des tests** : la fragilité temporelle est supprimée par une horloge contrôlée, une preuve directe du CAS de fencing est ajoutée et le rollback des effets du stale worker est vérifié. Les courses déterministes sur PostgreSQL réel éprouvent aussi la sérialisation des acquisitions/finalisations, la publication concurrente et la reprise après rollback. Aucun défaut runtime n'a été identifié ; aucun code de production, schéma ou contrat métier n'a été modifié.

Cette décision précise le périmètre de résolution : elle ne prétend satisfaire rétrospectivement l'attribution causale du premier critère historique. **Historical incident diagnosis: INCONCLUSIVE.** La cause de l'échec initial reste indéterminée. La résolution ne constitue pas une preuve d'inexistence de toute race condition ; les garanties sont éprouvées dans les entrelacements et l'environnement documentés.

Preuves acceptées, sans réécriture des rapports historiques :

- Audit initial : commit `eef30128`, [rapport d'audit](Audit_2026-10-15.md).
- Fiabilisation et couverture directe du fencing : commit `fa9be0a1`, [rapport de correction](Implementation_2026-10-15.md). Son statut initial NOT_EXECUTED décrit les blocages de cette première livraison.
- Validation PostgreSQL : commit `4a07b111`, [rapport de validation](Validation_2026-10-15.md) : baseline 100/100, deux scénarios corrigés 100/100 chacun et 21 tests complémentaires, soit 321 réussites.
- Concurrence déterministe : commit `53fee43c`, [rapport de concurrence](Concurrent_Takeover_2026-10-15.md) : 500/500, 500 attentes PostgreSQL effectivement observées, plus 15/15 passages complémentaires, soit 515 réussites.

Les campagnes exécutées sur PostgreSQL totalisent 836 réussites, aucun échec, erreur ou skip ; les anciennes tentatives bloquées avant exécution restent documentées séparément. Les recommandations OPEN des rapports correspondent à leurs dates de livraison et sont remplacées par la présente décision de clôture, sans modifier les preuves ni le diagnostic historique.

L'intégration dans la branche de développement et la réutilisation des preuves sont détaillées dans le [rapport de clôture et d'intégration](Closure_2026-10-15.md).

## Provenance

Observation issue de la vérification du chantier `docs: improve repository quality and project overview` (`ed4f01c7165cc5dbc2e1453bb55bba6ea63da8c4`).

Test concerné : [CommandResultConsumptionChainPostgresTest](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandResultConsumptionChainPostgresTest.java).
