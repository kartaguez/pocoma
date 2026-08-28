# Étape 2.9 — Consolidation des tests de consommation et de processing

## Objectif

Renforcer, sans infrastructure PostgreSQL ni worker, les garanties de `domain-consumption`,
`engine-consumption` et des processing engines Command, Event et Task.

## Travaux

1. Compléter les invariants de slot, claim, lease, failure et états terminaux.
2. Tester avec de vrais threads le CAS, la création paresseuse du slot, le reclaim et le fencing.
3. Vérifier concurrence, ordre et segmentation propres aux Commands.
4. Vérifier la multi-consommation indépendante des Events par pipeline et version.
5. Vérifier la mono-consommation et l'ordre des Tasks.
6. Vérifier l'isolation des namespaces Command, Event et Task.
7. Consolider les tests des décorateurs transactionnels.

Les tests concurrents utilisent des barrières, futures bornées, horloges fixes et identifiants
déterministes. Aucun délai arbitraire n'est autorisé.

## Limite

Ces tests valident les contrats des ports en mémoire. L'atomicité SQL, le CAS PostgreSQL et le
rollback réel seront vérifiés avec le futur adapter PostgreSQL de `ClaimPort`.
