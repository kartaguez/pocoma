# Correction transversale — ConsumptionSlot autoritatif

## Objectif

Appliquer avant 3.3 le modèle suivant :

```text
ExecutionGuard  = vérité sur l'effet commité
ConsumptionSlot = vérité sur le lifecycle du processing
Command/Task status = matérialisation réconciliable du slot
```

L'ordre obligatoire est transition et commit du slot, puis matérialisation du statut durable.
L'état temporaire `slot terminal + durable READY/PENDING` est accepté ; l'inverse ne doit pas être
produit par les services.

## Contrat générique

Remplacer le résultat `Optional<Claim>` de `TryAcquireConsumptionUseCase` par le résultat scellé :

- `Acquired(Claim)` ;
- `NotAcquiredBusy` ;
- `AlreadyCompleted` ;
- `AlreadyFailed(ProcessingFailure)`.

Ajouter `ClaimPort.findTerminalFailure(ConsumptionKey)`. Un slot `FAILED` sans failure terminale
exacte provoque `MissingTerminalConsumptionFailureException`; aucune failure n'est synthétisée.

L'acquisition examine d'abord le slot. Après une perte de CAS, elle le relit afin de distinguer une
course encore READY d'une transition concurrente vers COMPLETED ou FAILED. Le reclaim expiré reste
pris en charge atomiquement par `ClaimPort`.

## Réconciliation spécialisée

`ClaimNextCommandService` et `ClaimNextTaskService` traitent les résultats terminaux avant de
poursuivre leur curseur :

```text
AlreadyCompleted → markCompleted → candidat suivant
AlreadyFailed    → markFailed(failure exacte) → candidat suivant
Busy             → candidat suivant
Acquired         → retourner le travail et son claim
```

Cette réparation n'appelle ni métier, ni handler, ni ExecutionGuard et ne crée aucun claim.
Une erreur de matérialisation est propagée ; le slot reste terminal et un polling ultérieur retente
la réparation.

`ClaimNextEventService` saute busy/completed/failed pour la clé pipeline/version/Event et poursuit
son curseur. Il ne modifie jamais l'Event source et n'introduit aucun statut global Event.

## Transactions

Supprimer les décorateurs complete/fail spécialisés Command et Task. Les use cases génériques
complete/fail committent slot, claim et historique. Les services spécialisés matérialisent ensuite
le statut Command/Task hors de cette transaction.

Conserver les décorateurs claim/release Command et Task ainsi que tous les décorateurs Event.
Conserver séparément la transaction `ExecutionJournal + effet métier + outbox` du guard.

## Tests

Vérifier :

- les quatre variantes du résultat d'acquisition ;
- la relecture après course, le reclaim et le fencing ;
- la failure terminale exacte et la corruption en son absence ;
- la réconciliation de plusieurs Command/Task terminales avant un candidat claimable ;
- l'absence de mutation Event ;
- l'ordre lifecycle puis matérialisation ;
- l'échec de matérialisation laissant le slot terminal, puis la convergence au polling suivant ;
- l'impossibilité pour les services normaux de produire durable terminal + slot READY ;
- les frontières ArchUnit et l'absence des quatre décorateurs retirés.

## Impact roadmap

- Étape 3 : les processing engines cachent la réconciliation aux workers.
- Étape 4 : `JpaClaimPort` expose la failure terminale ; aucun commit commun slot/statut durable.
- Étape 5 : wiring generic lifecycle transactionnel puis service spécialisé non décoré ; métriques
  de réconciliation et de retard.
- Étape 6 : statuts legacy traités comme projections avant leur retrait.
- Étape 7 : scénarios E2E d'échec de matérialisation et réparation sans nouvel effet métier.

## Hors périmètre

Aucun changement de `domain-consumption`, d'ExecutionGuard, des workers, du schéma SQL ou des
adapters PostgreSQL. Ces derniers seront implémentés à l'étape 4.
