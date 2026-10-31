# Preuves indépendantes — audit d0927d94

Voir le [rapport autonome](../../../v3/Verification_Rationalization_Independent_Audit.md)
et l'[annexe des ensembles](../../../v3/Verification_Rationalization_Test_Sets.md).
Ces pièces sont des contrôles indépendants du 31 octobre 2026, pas une nouvelle
certification applicative. Le PASS du contre-exemple est un **défaut démontré du
collecteur**, et ne valide pas FOUNDATION.

Le [manifeste](manifest.json) donne le SHA-256 de l'archive et celui de chacun de
ses fichiers. Les preuves de livraison originales restent inchangées dans
[verification-optimization](../verification-optimization/).

Restaurer dans une destination neuve :

```sh
mkdir /private/tmp/pocoma-independent-audit-review
tar -xzf docs/testing/evidence/verification-independent-audit-d0927d94/audit-evidence.tar.gz -C /private/tmp/pocoma-independent-audit-review
```

Le préfixe `audit/` contient :

| Pièce | Objet |
|---|---|
| `git-remote.json`, `remote-before-publication.txt`, `git-and-files.json`, `audited.diff` | Références distantes réellement lues, état initial et diff exact des 46 fichiers |
| `independent-checks.py.txt`, `independent-checks.log` | Source documentaire et sortie du probe de reconstruction, utilisable avec Python |
| `campaign-rechecks.json`, `all-archives.json`, `physical-recheck.json`, `baseline-input-recheck.json` | Hashes, XML, 1 287 traces physiques, comparaisons des reçus |
| `sets.json`, `*-xml-reconstruction.json` | Ensembles exacts, compteurs, résultats et correspondance au diagnostic full |
| `canonical-plans.json`, `certification-plan.json`, `maven-graph.json` | Sélections et fermeture statique ; aucun nouveau test PG exécuté par ces pièces |
| `inventory-comparison.json`, `inventory-reproduction.log`, `inventory-reproduced.json` | Réextraction à l'identique de l'inventaire historique |
| `selector-tests.log` | 13 tests du sélecteur relancés |
| `fast-clean/` | Nouvelle exécution FAST : plan, provenance, résultat, log et archive XML fraîche |
| `hidden-filter-h2-command.json`, `counterexample.json`, `foundation-hidden-filter-h2/` | Contre-exemple réel : commande canonique, seulement quatre tests H2, faux PASS |
| `hidden-filter-command.json`, `foundation-hidden-filter/` | Première sonde filtrée PG : erreur d'accès Docker conservée, aucun PASS inféré |
| `local-warm-debug-command.json`, `local-warm-debug-excerpt.txt` | Compilation native sans mutation, source stale exacte et recompilation en cascade |
| `build-log-rechecks.json`, `supplementary-checks.py.txt`, `independent-compiler-recount.json` | Recompte singulier/pluriel des sources compilées, sans relance du benchmark |
| `external-inputs.json`, `i5-r12-diagnostic.json` | Dépendances SQL externes non hashées et marqueur intermédiaire avant l'échec de fermeture I5 |

Pour réexécuter les deux probes de lecture après extraction, sur le checkout
**d0927d94** et avec une destination neuve existante :

```sh
python3 -B /private/tmp/pocoma-independent-audit-review/audit/independent-checks.py.txt /chemin/checkout-d0927d94 /chemin/preuves-neuves
python3 -B /private/tmp/pocoma-independent-audit-review/audit/supplementary-checks.py.txt /chemin/checkout-d0927d94 /chemin/preuves-neuves
```

La première sonde importe le sélecteur audité seulement pour reconstruire les
plans ; le parseur de XML, les hashes et les opérations sur ensembles sont
indépendants. Elle n'exécute pas Maven. Les autres commandes réellement exécutées
et les limites de reproduction sont dans le rapport. Aucun cache `.m2` vide ni
second environnement PostgreSQL n'est qualifié.

Le log Maven `-X` local contient un dump de propriétés/environnement sans intérêt
probatoire : seule sa sélection de lignes de compilation est publiée, avec
numéros originaux et empreinte du log local. L'original n'est pas dans l'archive
publiée. Aucun log historique de la livraison n'est modifié par cette sélection.
Les XML des nouvelles exécutions et leurs archives brutes sont conservés sans
réécriture. Les anciens XML déplacés par le collecteur restent identifiés comme
anciens et ne sont pas comptés dans les résultats frais.
