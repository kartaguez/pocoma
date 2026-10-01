# Lot 4 — Orchestration pull générique et migration transactionnelle Event

Ce lot introduit la chaîne pérenne `Runtime → SupraConsumptionWorker → ConsumptionOrchestrator →
ConsumptionLocator → engine-consumption`, puis migre Event vers elle sans modifier Command ni Task.

## Invariants

- Le locator ne connaît ni worker, ni lease, ni budget, ni polling et ne garde aucune transaction ouverte.
- Une recherche est abandonnée dès qu'un Claim est acquis et recommence du début après l'exécution.
- `Busy.leaseUntil` et `NotReady.nextClaimAt` alimentent une échéance minimale indicative.
- Les Tasks, la provenance et le CAS `currentClaimId` sont écrits dans la même transaction.
- `LostClaimException` provoque uniquement le rollback de l'exécution stale et n'est jamais classifiée.
- Les autres erreurs d'exécution sont classifiées puis traitées dans une transaction courte distincte.
- Le Supra V1 est strictement séquentiel et termine gracieusement toute consommation déjà acquise.

## Lots vérifiables

1. Contrats génériques, orchestrateur et évolution de `Busy`.
2. Supra générique et politique de cadence.
3. Création transactionnelle de Tasks retournant leurs références durables.
4. Locator Event, clé structurelle, provenance et sélection PostgreSQL.
5. Runtime Spring dédié à une pipeline et un segment configurés.
6. Retrait du lifecycle Event legacy, tests PostgreSQL, règles d'architecture et documentation.

L'historique est adopté paresseusement : les Tasks déjà matérialisées sont relues et référencées par la
provenance du nouveau slot, sans duplication. L'ancien matérialiseur et le nouveau runtime ne doivent pas
être actifs simultanément pour la même pipeline pendant la bascule.
