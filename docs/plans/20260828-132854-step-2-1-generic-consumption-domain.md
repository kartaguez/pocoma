# Sous-étape 2.1 — Généralisation de domain-consumption

## Objectif

Faire de `domain-consumption` un domaine ignorant totalement le type d’objet réservé. Cette
sous-étape conserve temporairement les API spécialisées de `engine-consumption`, qui seront
remplacées en 2.2.

## Modèle

- Remplacer les clés Command/Event/Task par `ConsumptionKey(namespace, components)`.
- Valider le namespace, la présence des composants et leur contenu, puis en faire une copie
  immuable.
- Remplacer le slot par `ConsumptionSlot(key, revision, status)`.
- Limiter `ConsumptionStatus` à `READY`, `COMPLETED`, `FAILED`.
- Ne stocker aucun `currentClaimId` : la réservation est déduite d’un claim actif.
- Ajouter une failure optionnelle à l’historique immutable du claim.
- Refuser acquisition, completion, failure ou release depuis un slot terminal.

## Sortie du processing

Déplacer temporairement ordering et segmentation dans `engine-core`, sous
`engine.processing.ordering` et `engine.processing.segmentation`. Supprimer du domaine les clés
spécialisées et adapter provisoirement `engine-consumption` avec des clés opaques locales.

## Tests et validation

Tester les invariants des clés, les transitions du slot, le cycle du claim, l’indépendance des clés
et l’absence de concepts Command/Event/Task/Pot/Pipeline dans le domaine. Exécuter les tests de
`domain-consumption`, `engine-consumption`, task creation/execution, ArchUnit, puis le build global.
