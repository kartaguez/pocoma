# Lot 7.3.1 — Alignement sur l'applicabilité des pipelines

Ce lot remplace la coverage read-side par une règle fonctionnelle canonique partagée, sans câbler de runtime.

## Modèle et catalogue

`PipelineDefinition(pipelineId, pipelineVersion)` reste l'identité globale. Une
`PipelineVersionDefinition` immuable lui associe une `VersionApplicability` continue, avec borne basse
positive et borne haute inclusive optionnelle. `PipelineVersionDefinition.appliesTo` est l'unique règle.

`PocomaPipelineDefinitions` est statique, framework-free et append-only. Il conserve les générations
inactives et ne sélectionne aucune version active. Chaque composition root construit un registry local
depuis `PocomaPipelineDefinitions.all()` : mêmes valeurs, sans partage d'instances Java. Changer une
applicabilité publiée impose une nouvelle `pipelineVersion`.

Le registry recherche exactement `pipelineId + pipelineVersion`, refuse tout doublon et ne fait ni
`MAX`, ni fallback. Une absence lève `UnknownPipelineDefinitionException`.

## Résolution et scheduling

L'ordre est existence source, applicabilité, résultat. Le futur Query Kernel renvoie d'abord
`NOT_FOUND` lorsque `V > latestKnownVersion`. Ensuite : définition absente = erreur de configuration ;
non applicable = `NotApplicable` ; applicable avec artifact, failure ou aucun résultat = `READY`,
`FAILED` ou `NOT_READY`.

Tasks, backfills, repairs et retries décrivent ce qui est exécuté maintenant, jamais l'applicabilité.
Un backfill `90..100` d'une définition `[37..∞]` ne change donc pas la version 42. Le Lot 7.5 réutilisera
la même méthode `appliesTo` pour la création/adoption des Tasks.

## Persistence et concurrence

V2 reste inchangée. V3 retire les FK vers `projection_coverages`, reporte les validations d'identité
sur artifacts, failures et heads, puis supprime la table. Aucun parent de remplacement n'est créé.

Le verrou advisory conserve l'identité complète. Le premier terminal commit gagne, les duplicates
identiques sont adoptés, les divergents n'écrasent rien, et le head avance par maximum sans continuité.

## Validation

Les tests couvrent plages, registry exact et doublons, historique, `NotApplicable`, définition inconnue,
statuts, migrations, autonomie des tables, courses terminales et head `44→46→45`. La sortie exige la
suite Maven complète et `git diff --check`.
