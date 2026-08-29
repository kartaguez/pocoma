# Étape 3.6 — Consolidation transversale des tests des workers

## Objectif

Clore l'étape 3 en démontrant de manière homogène les contrats de `CommandWorker`, `EventWorker` et
`TaskWorker` : une unité par itération, séquentialité par instance, parallélisme entre instances,
transitions lifecycle, fencing, lease V1 et récupération idempotente après un crash post-commit.

Les tests restent déterministes et en mémoire. Ils ne prouvent pas encore le CAS, l'index unique ou
les transactions PostgreSQL de l'étape 4.

## Changements

- Compléter les tests de façade des trois workers par la preuve que deux instances distinctes
  travaillent réellement en parallèle tandis qu'une instance reste séquentielle.
- Renforcer Command avec le scénario guard committé puis completion perdue.
- Renforcer Event avec création idempotente committée puis completion perdue, y compris la voie
  `ALREADY_CREATED`.
- Renforcer Task avec effet et journal committés puis completion perdue, suivi d'un reclaim
  `ALREADY_EXECUTED` sans mapper ni handler.
- Conserver les preuves de complete/fail/release, ownership lost, warnings de lease, dépassement
  sans interruption et classification conservatrice des erreurs.
- Référencer les tests de processing existants pour la multi-consommation Event, la segmentation,
  l'ordre et la réconciliation autoritative Command/Task.
- Renforcer ArchUnit afin que chaque worker reste un orchestrateur entrant sans repository,
  persistence, clé de consommation, framework ou legacy.

## Contraintes de test

- `CountDownLatch`, barrières et futures bornées uniquement ; aucun `Thread.sleep`.
- Aucun module ou framework générique de test-support.
- Aucun changement de production sauf correction d'un défaut contractuel révélé.
- Aucun worker cible activé dans un runtime.

## Validation

```text
./mvnw -pl supra-worker-command,supra-worker-event,supra-worker-task -am test
./mvnw -pl engine-consumption,engine-processing-command,engine-processing-event,engine-processing-task -am test
./mvnw -pl runtime-pipeline-task-executor,architecture-tests -am test
./mvnw clean test
```

L'étape est close lorsque la matrice des contrats pointe vers des tests verts et que les limites
mémoire/PostgreSQL sont explicites.

## Étape suivante — 4.1

Introduire `consumption_slots` et `consumption_claims`, le CAS de révision, l'index unique partiel
des claims actifs, le reclaim atomique et les tests Testcontainers associés.
