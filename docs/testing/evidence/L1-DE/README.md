# Preuves L1-D / L1-E

Les résultats complets sont dans `Gate-index.json` et la matrice instanciée dans `SPEC10-matrix.json` (créés après gates). Les rapports autoporteurs sont `docs/v3/L1_D_Execution_Report.md` et `docs/v3/L1_E_Execution_Report.md`. Aucun PASS global V3 ni acceptation indépendante L1.

Depuis ce répertoire, extraire les archives :

```bash
tar -xzf D-gates-raw.tar.gz
tar -xzf E-gates-raw.tar.gz
tar -xzf E-superseded-raw.tar.gz
```

Chaque répertoire nommé dans l'index contient commande/SHA, environment.json, inputs/before/after, XML Surefire frais, logs Maven et snapshots PostgreSQL. E ajoute external-before/after/verdict, classpaths, inventaire des classes/ressources/SQL et receipt.json. Les SHA-256 adjacents protègent les archives. Les diagnostics sont séparés des gates retenus ; aucune exécution remplacée ou en échec n'est effacée ni requalifiée PASS.

Reproduction des gates depuis le SHA testé, avec JDK/Maven/Docker qualifiés et worktree propre :

```bash
python3 -B scripts/testing/verify-v3-l1.py --gate e-consumers
python3 -B scripts/testing/verify-v3-l1.py --gate e-arch
python3 -B scripts/testing/verify-v3-l1.py --gate e-full
```

Ces commandes se lancent depuis la racine du dépôt et exécutent les commandes Maven canoniques depuis app/. Elles suppriment les targets de leur propre worktree et refusent une entrée non prise en compte. Les dépendances externes doivent être déjà résolues et stables ; tout téléchargement pendant le gate nécessite une nouvelle exécution fraîche. Le manifeste distingue les méthodes obligatoires du skip exploratoire V2 `step3b.measure`, conservé INCONCLUSIVE pour la couverture complète.

Après extraction des preuves retenues, contrôle de publication reproductible depuis la racine : employer `--tested` avec le SHA H du rapport, `--published` avec le HEAD publié, `--remote`, et `--proof-index docs/testing/evidence/L1-DE/Gate-index.json`. Le vérificateur contrôle les gates, tous les commits postérieurs à H, les entrées et la tête distante. Une publication documentaire ultérieure doit repasser ce contrôle ; toute entrée exécutable nouvelle exige les gates affectés.

Pour générer la matrice avec build-l1-matrix.py, fournir à `--summaries` les chemins absolus des summary.json retenus (le script enregistre ensuite leurs chemins relatifs au dépôt). Les méthodes et oracles sont liés aux sources et XML de chaque gate ; les contributions partielles ne deviennent jamais des PASS de scénario global.
