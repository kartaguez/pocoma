# Preuves L1-F

Baseline `2fb9a536f3894e797923c0b49aeba1a9b5adfda2`, entrées finales testées `c94e7a4bec479ccbf22b058fd341ae0350c2ca67`.

- `Gate-index.json` : commandes canoniques, comptes, méthodes obligatoires, skip et empreintes.
- `gates-raw.tar.gz` : deux campagnes fraîches ; XML complets, maven.log, entrées avant/après, classes/ressources générées, JARs et classpaths, outils/image PostgreSQL et snapshots métier/catalogue/EXPLAIN.
- `performance-raw.tar.gz` : six JVM appariées, sources/classes du calculateur baseline, empreintes du harnais et des classes finales, deux mesures RSS supplémentaires et première tentative RSS refusée dans le sandbox.
- `Performance.json` : échantillons et médianes, heap retenu et RSS avec leurs périmètres distincts. Aucune certification SLO.
- `diagnostics-raw.tar.gz` et `Diagnostics.json` : diagnostics intermédiaires, FAIL Docker, refus de précontrôle d'un bytecode Python non suivi, JFR/allocation samples et exploration hétérogène interrompue (Grand non mesuré, jamais utilisé pour le bilan).
- `preserved-*.txt` : inventaire final en lecture seule des checkouts/branches préexistants.

Vérifier/extrait depuis ce répertoire :

```bash
shasum -a 256 -c gates-raw.tar.gz.sha256
shasum -a 256 -c performance-raw.tar.gz.sha256
shasum -a 256 -c diagnostics-raw.tar.gz.sha256
tar -xzf gates-raw.tar.gz
tar -xzf performance-raw.tar.gz
tar -xzf diagnostics-raw.tar.gz
```

Reproduire depuis la racine d'un checkout propre du SHA testé, avec JDK 21 et Docker :

```bash
python3 -B scripts/testing/verify-v3-l1-f.py consumers
python3 -B scripts/testing/verify-v3-l1-f.py architecture
bash scripts/testing/profile-v3-l1-f.sh
```

Les deux premières commandes exécutent les commandes Maven canoniques depuis `app/`, suppriment uniquement les targets de ce checkout, et utilisent le manifeste L1 historique inchangé + les 17 méthodes du manifeste L1-F. Les versions CURRENT V3 V1/V2/V3 sont isolées. Les migrations historiques transitives des suites existantes restent visibles ; aucun gate historique séparé ni full reactor. Les 100/108 méthodes obligatoires passent exactement une fois ; la couverture globale demeure INCONCLUSIVE pour le seul skip exploratoire identifié dans chaque summary.json. Aucun résultat historique n'est requalifié.
