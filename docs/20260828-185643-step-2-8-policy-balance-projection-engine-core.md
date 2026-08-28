# Étape 2.8 — Policies Pot, projection Balance et clarification d'engine-core

## Objectif

- Renommer `domain-policy` en `domain-pot-policy` et placer ses types sous `domain.pot.policy`.
- Renommer `domain-projection` en `domain-projection-balance` et placer son modèle sous
  `domain.projection.balance`.
- Rattacher les ports de balances et de shareholders aux engines qui les consomment.
- Remplacer l'append d'événement fondé sur `Object` par un port acceptant `BusinessEvent`.
- Réduire `engine-core` aux contrats partagés et isoler ses types legacy.

## Découpage

1. Renommer le module et les packages des policies Pot, sans changement de comportement.
2. Renommer le module et les packages du domaine de projection Balance.
3. Remplacer `PotBalancesPort` par `PotBalancesQueryPort` et `PotBalanceProjectionPort`, puis
   déplacer `PotBalanceProjectionState` dans `engine-projection`.
4. Déplacer le port d'écriture des shareholders dans `engine-command` et créer un port de lecture
   dédié dans `engine-projection`.
5. Créer `BusinessEventAppendPort` dans `engine-command`, typé par `BusinessEvent`, et découpler
   le port d'outbox legacy.
6. Déplacer `PotGlobalVersion` sous `engine.pot.version` et éliminer les packages génériques
   `engine.model` et `engine.port.out.persistence` d'`engine-core`.
7. Isoler `BusinessEventEnvelope`, `PotPartitioner` et `ProjectionPartition` sous
   `engine.legacy` sans modifier les workers actuels.
8. Adapter Maven, l'infrastructure, le wiring Spring et les tests.
9. Renforcer ArchUnit et valider le build agrégé.

## Contraintes

- Aucun changement de schéma SQL, payload JSON, endpoint, worker ou mécanisme de claim.
- Aucun alias de compatibilité pour les anciens modules ou packages.
- Les adapters JPA peuvent implémenter plusieurs ports, sans orchestration applicative.
- Aucun nouveau code fonctionnel ou processing ne dépend de `engine.legacy`.

## Résultat attendu

`domain-pot-policy` et `domain-projection-balance` deviennent explicites. Query, Command et
Projection possèdent leurs ports sortants. `engine-core` ne conserve que les contrats réellement
partagés, tandis que les représentations nécessaires aux anciens workers sont clairement marquées
legacy.
