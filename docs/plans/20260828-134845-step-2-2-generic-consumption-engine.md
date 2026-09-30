# Sous-étape 2.2 — Généralisation de engine-consumption

## Objectif

Faire de `engine-consumption` un engine de réservation totalement indépendant de la nature du
travail. Il manipule seulement `ConsumptionKey`, `ConsumptionSlot`, `Claim`, les tokens et les
transitions de consommation.

## API cible

- `TryAcquireConsumptionUseCase` reçoit une clé, un worker et un lease, puis retourne
  éventuellement le claim acquis.
- `CompleteConsumptionUseCase`, `FailConsumptionUseCase` et `ReleaseConsumptionUseCase`
  reçoivent une clé et le fencing token attendu.
- Le failure use case reçoit en plus un `ProcessingFailure`.
- `ClaimPort` reste l’unique port sortant et garantit les mutations atomiques du slot et de
  l’historique des claims.

## Suppressions

Retirer de ce module `ConsumableCommand`, `CommandPort`, `CommandClaimResult`, les inputs,
use cases, services et décorateurs transactionnels spécialisés Command. Supprimer également la
dépendance Maven vers `engine-pot-command`.

## Limite avec la 2.3

La sélection, l’ordre, la segmentation et les transitions de la Command ne sont pas recréés ici.
Ils seront portés par `engine-processing-command` en 2.3, qui composera son port Command avec les
use cases génériques de consommation.

## Tests

Tester acquisition paresseuse, CAS concurrent, reclaim après expiration, fencing token sur les
quatre transitions, failure history, indépendance des namespaces et décorateurs transactionnels.
Ajouter une règle ArchUnit interdisant dans `engine-consumption` les concepts Command, Event,
Task, Pot, Pipeline ainsi que les dépendances vers les autres engines fonctionnels.
