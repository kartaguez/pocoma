# Moteur de consommation — Lot 1 : modèle de domaine cible

## Objectif

Faire évoluer `domain-consumption` pour exprimer le modèle cible sans modifier encore la persistence,
les frontières transactionnelles ou le comportement des workers. Le reactor doit rester compilable grâce
à une compatibilité explicitement dépréciée.

## Modèle cible

- Remplacer la clé `namespace/components` par `ConsumableIdentity + ConsumerIdentity`, tout en conservant
  temporairement le constructeur historique et ses accesseurs.
- Faire porter au slot `PENDING/DONE`, `TerminalOutcome`, `currentClaimId`, `nextClaimAt`, `createdAt`,
  `doneAt` et `revision`.
- Faire de `ClaimId` l'identifiant de tentative et le fencing token. Conserver `ClaimToken` uniquement comme
  adaptateur déprécié dérivé du `ClaimId`.
- Ajouter `attemptNumber`, `ClaimEndReason` et les transitions explicites de fin/invalidation au Claim.
- Introduire `FailureContext`, `FailureDecision`, `ConsumptionFailurePolicy` et une policy V1 : retry après
  1 s, 5 s et 30 s, puis échec terminal à partir de la quatrième tentative.
- Introduire le résultat d'acquisition cible `ACQUIRED/BUSY/NOT_READY/ALREADY_DONE`.
- Introduire `ConsumptionInput` et `ConsumptionResult` comme objets de provenance génériques.

## Invariants

- `PENDING` implique l'absence de terminal outcome et de `doneAt`.
- `DONE` exige un terminal outcome et un `doneAt`, et interdit un claim courant.
- `DONE` est irréversible.
- L'expiration du lease n'invalide pas le Claim ; elle autorise seulement un futur takeover.
- Toute création de Claim possède un `attemptNumber >= 1`.
- Une failure n'est présente qu'avec `PROCESSING_FAILURE`.
- Toutes les dates et identifiants sont fournis par l'appelant afin de garder le domaine déterministe.

## Compatibilité temporaire

- Les anciennes formes de `ConsumptionKey`, `ClaimToken` et résultats d'acquisition restent disponibles et
  sont annotées `@Deprecated(forRemoval = true)`.
- Aucun nouveau code ne doit utiliser ces API.
- Les adapters seront supprimés dans les lots de migration du moteur et des workers.
- Aucun ancien statut n'est ajouté au nouveau lifecycle `PENDING/DONE`.

## Découpage

1. Identités, clé, terminal outcome et nouveau slot.
2. Claim enrichi et compatibilité `ClaimToken`.
3. Failure policy, résultat d'acquisition cible et provenance.

## Tests et critères de fin

- Tester validations, égalité structurelle et compatibilité des clés Command/Event/Task.
- Tester toutes les combinaisons valides et invalides du slot, son irréversibilité et sa revision.
- Tester expiration sans invalidation, toutes les fins de Claim et le token dérivé.
- Tester les quatre décisions de la policy V1.
- Tester la cohérence des objets de provenance.
- Exécuter les tests ciblés puis `./mvnw test`.
- Ne créer aucune migration SQL et ne modifier aucune frontière transactionnelle dans ce lot.
