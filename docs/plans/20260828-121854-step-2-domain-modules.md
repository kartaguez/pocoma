# Étape 2 — Domaines, modules et consommation générique

## Objectifs

- Rendre `domain-consumption` et `engine-consumption` indépendants de Command, Event et Task.
- Introduire les engines techniques `engine-processing-command`, `engine-processing-event` et
  `engine-processing-task`.
- Renommer le domaine principal en `domain-pot` et y placer les événements métier.
- Séparer `domain-pipeline` et `domain-task`.
- Conserver `engine-core` pour les contrats applicatifs réellement partagés.
- Préserver les workers, les tables et les formats persistés pendant la migration.

## Sous-étapes

1. **2.1 — Domaine de consommation générique** : clé opaque, slot READY/COMPLETED/FAILED,
   historique de claims, sortie des concepts d’ordre et de segmentation.
2. **2.2 — Engine de consommation générique** : remplacer les use cases Command par
   tryAcquire/complete/fail/release fondés uniquement sur `ConsumptionKey`.
3. **2.3 — Command processing** : créer `engine-processing-command` et y placer sélection,
   ordre, segmentation et transaction Command + Claim.
4. **2.4 — Event et Task processing** : créer les engines techniques spécialisés et y maintenir
   temporairement les routes legacy des workers.
5. **2.5 — Domaine Pot** : renommer `domain` en `domain-pot`, artifact et packages inclus.
6. **2.6 — Événements métier** : déplacer les événements typés dans `domain-pot` sans changer
   leurs noms simples ni leur format persistant.
7. **2.7 — Pipeline et Task** : remplacer `domain-pipeline-tasks` par `domain-pipeline` et
   `domain-task`, sans objets durables.
8. **2.8 — Policy, Projection et engine-core** : expliciter les packages et isoler le legacy.
9. **2.9 — Tests** : concurrence, fencing token, états terminaux et processing spécialisé.
10. **2.10 — Architecture et clôture** : règles ArchUnit et build global.

## Architecture cible

```text
domain-pot                 métier Pot et événements
domain-policy              policies Pot
domain-projection          calculs de projection
domain-pipeline            identité/version de pipeline
domain-task                tâches fonctionnelles typées
domain-consumption         invariants génériques slot/claim

engine-consumption         réservation générique
engine-processing-command  sélection et cycle durable Command
engine-processing-event    sélection et cycle durable Event
engine-processing-task     sélection et cycle durable Task
```

Le slot générique ne porte que `READY`, `COMPLETED` ou `FAILED`. Une réservation en cours est
déduite de l’existence d’un claim actif. L’ordre, la segmentation et les transitions des objets
durables appartiennent aux processing engines, jamais à `engine-consumption`.

## Hors périmètre

Pas de changement de tables, JSON, contrats HTTP, boucle de polling, retry, heartbeat ou stratégie
de déploiement horizontal pendant cette étape.
