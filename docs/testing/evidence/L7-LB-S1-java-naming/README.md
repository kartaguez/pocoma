# Preuves nouvelles L7-LB/S1 — nomenclature Java

Mandat `GO_L7_LB_S1_JAVA_NAMING`, source initiale `a138a368dd3386e4b9e714e45d5fefbd2abab2e6`.

- `rename-plan.json` : déclaration de périmètre et correspondances par package/symbole/fichier ; littéraux internes adaptés explicitement.
- `preservation.json` et `verify_preservation.py` : comparaison indépendante des tokens/commentaires, tous les littéraux non autorisés, fichiers protégés et sélection de tests. Aucun accès aux instances.
- `retained-v3-references.json` : exceptions contractuelles, de configuration, SQL et historiques conservées ; aucune ancienne référence Java qualifiée.
- `selector-tests.log`, `read-selector-tests.log`, `bruno-contract-tests.log` : 36 + 10 + 10 tests de sélection/contrats, aucun parcours HTTP/Bruno réel.

La gate PostgreSQL/Java composée est en cours dans un checkout isolé ; aucun PASS de celle-ci n'est anticipé ici. Les rapports et l'archive de provenance seront ajoutés après collecte. Les fichiers de qualification historiques restent inchangés.
