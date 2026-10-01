# Lot 3 — Exécution atomique et fencing transactionnel

## Résumé

Construire dans `engine-consumption` un noyau générique garantissant que les effets métier, les outbox/tasks, la provenance et le CAS final sont exécutés dans une transaction PostgreSQL unique.

Le lot ne migre aucun worker. Il fournit les APIs, décorateurs transactionnels, tests PostgreSQL et documentation nécessaires au Lot 4.

## Contrat générique d’exécution

- Ajouter `BusinessConsumptionOutcome`, sealed, avec `Success` et `Rejected(String rejectionCode)`.
- Ajouter `ConsumptionExecutionResult`, contenant l’outcome, les `ConsumptionInput` et les `ConsumptionResult`.
- Ajouter `ConsumptionExecutionContext`, `ConsumptionExecution`, `ExecuteConsumptionInput` et `ExecuteConsumptionUseCase`.
- Ajouter `LostClaimException`, contenant `slotId` et `claimId`.
- Ne pas persister le code de rejet dans le slot : les détails durables appartiennent aux effets métier, Events, Tasks ou outbox.

## Service atomique et frontières transactionnelles

Implémenter `ExecuteConsumptionService` avec l’ordre strict suivant :

1. exécuter le callback métier ;
2. vérifier que toute la provenance référence le slot exécuté ;
3. persister les inputs ;
4. persister les résultats ;
5. traduire l’outcome en `SUCCESS` ou `REJECTED` ;
6. appeler `tryTerminalize(slotId, claimId, outcome, now)` ;
7. lever `LostClaimException` si le CAS échoue ;
8. retourner le résultat seulement après succès du CAS.

Ajouter les décorateurs `TransactionRunner` pour l’acquisition, le failure handling, l’abandon et l’exécution atomique. Les adapters JPA restent `Propagation.MANDATORY` et le CAS natif conserve son flush préalable.

## Chemins d’exécution

### SUCCESS et REJECTED

```text
BEGIN
callback métier
effets métier et outbox/tasks
ConsumptionInput
ConsumptionResult
CAS status=PENDING AND current_claim_id=:claimId
clôture du Claim
COMMIT
```

Un rejet métier est une exécution valide et committe en `DONE/REJECTED`.

### Échec technique

La couche spécialisée laisse l’exception sortir du wrapper d’exécution, attend le rollback complet, la transforme en `ProcessingFailure`, puis appelle le wrapper transactionnel de failure handling. `LostClaimException` n’est jamais classifiée comme failure technique.

### Effets externes

Le callback ne produit directement que des effets rollbackables dans la base principale. Tout effet externe est représenté par une Task ou une outbox transactionnelle.

## Lots d’implémentation

1. APIs, validations et exception de fencing.
2. Service atomique et quatre décorateurs transactionnels.
3. Tests PostgreSQL réels des commits gagnants, takeovers, rollbacks, rejets et failures.
4. Règles d’architecture séparant le nouveau chemin d’`ExecutionGuard`.
5. Documentation de la frontière transactionnelle et validation du réacteur complet.

## Hypothèses et limites

- Aucune migration SQL de production n’est nécessaire.
- Le code de rejet est retourné mais non persisté par le moteur.
- Le wiring dans les workers appartient au Lot 4.
- La transaction de failure handling commence après la sortie et le rollback de la transaction métier.
- L’autorité de commit repose exclusivement sur `currentClaimId`, jamais sur `revision` ou `leaseUntil`.
