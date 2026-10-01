# Étape 2.3 — Création de engine-processing-command

> Décision remplacée le 2026-08-29 : `ConsumptionSlot` est désormais autoritatif. Les statuts
> Command sont des matérialisations réconciliables et ne partagent plus une transaction commune
> avec complete/fail. Voir `20260829-133952-consumption-slot-authoritative-reconciliation.md`.

> Le nommage et la représentation de la commande enregistrée ont été précisés par
> `20260828-142538-step-2-3-command-processing-naming-correction.md`.

## Objectif

Créer `engine-processing-command` pour porter exclusivement le cycle technique des commandes
durables : sélection, acquisition générique, completion, failure et release. Le module ne contient
ni boucle de worker ni exécution métier.

## Modèle et ports

- `RecordedCommand` contient commandId, potId optionnel, createdAt, UserContext figé et
  CommandIntent.
- `CommandPort` sélectionne la prochaine commande READY par createdAt puis commandId et applique
  les transitions COMPLETED/FAILED.
- La clé de consommation est `ConsumptionKey("command", [commandId])`.
- `CommandOrderingKey` quitte engine-core pour ce module ; WorkerSegment et PartitionHash restent
  temporairement partagés dans engine-core.

## Use cases

- `ClaimNextCommandUseCase` compose sélection et `TryAcquireConsumptionUseCase`, continue après
  une perte de CAS et retourne au plus une Command avec son Claim.
- Complete et fail modifient la Command uniquement si la transition générique retourne APPLIED.
- Release ne modifie jamais la Command.
- Un décorateur transactionnel externe couvre CommandPort, slot et historique du claim.

## Frontières

Le futur worker appelle successivement claim, `ExecuteCommandUseCase`, puis complete/fail/release.
Le module ne crée ni table, ni adapter PostgreSQL, ni controller, ni worker. `engine-pot-command` reste
indépendant de la consommation et du processing.

## Tests

Tester ordre, segmentation, commandes sans potId, perte de CAS, reclaim, fencing token,
transitions conditionnelles, reconstruction de ExecuteCommandInput, transactions et règles
ArchUnit. Valider le module, architecture-tests, puis le build Maven complet.
