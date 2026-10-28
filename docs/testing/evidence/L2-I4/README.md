# Preuves I4

Code figé : `0b08d3bf0bfce1334260cfd5249b13c3040a67bb`. Référence approuvée : `489259dbad757e3256572f8ce2041a187adeb38d`.

Les répertoires `final-command`, `final-read`, `final-monolith`, `final-architecture` contiennent le log Maven intégral et `result.json`. Le champ `command` est l’invocation canonique réellement exécutée depuis `app/`, sans filtre. Chaque résultat contient les cas JUnit uniques, échecs/skips, exigences vérifiées, empreintes des entrées avant/après, modes Git, classes compilées et preuves physiques fraîches. Un verdict Maven seul ne suffit pas.

Après collecte, les XML et traces sont conservés dans `final-<gate>.tar.gz`, sous `xml/` et `physical/`. `Evidence-Index.json` fournit les tailles et SHA256 des archives et vérifie chaque entrée par rapport aux empreintes de `result.json`. Extraire seulement dans un répertoire neuf, puis comparer les SHA256. Les fichiers diagnostiques `I4-*` restent distincts ; leurs résultats ne se substituent pas aux gates figés. Le détail des commandes, erreurs et limites des premières captures est dans `Diagnostics-Summary.json` et le rapport d’exécution.

Pour reproduire, utiliser un checkout isolé du SHA figé, Java 21, Python 3, Docker local et le wrapper Maven du dépôt. Depuis la racine de ce checkout, exécuter séquentiellement :

```sh
python3 scripts/testing/verify-v3-l2-i4.py command /private/tmp/i4-repro-command
python3 scripts/testing/verify-v3-l2-i4.py read /private/tmp/i4-repro-read
python3 scripts/testing/verify-v3-l2-i4.py monolith /private/tmp/i4-repro-monolith
python3 scripts/testing/verify-v3-l2-i4.py architecture /private/tmp/i4-repro-architecture
```

Chaque destination doit être inexistante ; le collecteur la crée sans écrasement. Le code, scripts et fichier d’entrées doivent être propres et committés. Les tests créent leurs bases/schémas et vrais logins non propriétaires dans des conteneurs jetables. Aucun script administratif ne doit être exécuté sur une base utilisateur pour reproduire ces preuves. Le digest PostgreSQL est épinglé dans la fixture I3 réutilisée ; les anciennes migrations transitives des gates restent identifiées comme historiques.

Reproduction des entrées indépendantes :

```sh
python3 scripts/testing/verify-v3-l2-i4.py generate-inputs /private/tmp/i4-reproduced-inputs.json
cmp docs/testing/evidence/L2-I4/Performance-inputs.json /private/tmp/i4-reproduced-inputs.json
python3 -B -m unittest scripts/testing/test_i4_evidence.py
```

Les entrées proviennent de Python hashlib, sans appel au routage Java/SQL productif. Les snapshots, pages, PID/xid, horloges, attentes et résolutions physiques constituent les preuves ; les noms de fixtures ne sont pas des producteurs métier. Command/POT/COMMAND_COMPLETION passent par le vrai finaliseur I2 ; Identity authority, admissions internes et autres familles absentes restent explicitement techniques. Aucun PASS L3/L4/L5 ni de Worker I5 n’est attribué à ces analogues.

Les coûts incluent l’instrumentation et le dispatch d’un harnais de test, avec connexions séquentielles conservées. Les bornes de possession de verrous sont conservatrices et mesurées autour de l’acquisition et de la résolution physiques. Allocations cumulées et WAL global du cluster ne sont ni une mesure de heap retenu, ni un WAL exclusif par transaction, ni un SLO de production.

Le rapport d’implémentation et ces contrôles ne constituent pas un audit indépendant. L2 global reste INCONCLUSIVE ; aucun G1/G2/G3 ni GO I5 n’en découle.
