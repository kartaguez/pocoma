# L7 Local Playground — preuves de reprise I3

14 novembre2026, Europe/Paris. Verdict proposé **I3_BRUNO_PARTIAL**.
L'acceptation GUI humaine reste en attente ; aucun certificat global V3.

- **[7d951a34/qualification.json](7d951a34/qualification.json)** : campagne finale au SHA 7d951a34754c67d8fa88be669c3929ea7c7b2d04, worktree propre.
- **[7d951a34/commands.json](7d951a34/commands.json)** : start/GUI/CLI/reset/rejeu réels, argv et durées.
- **[7d951a34/fresh-cli/bruno-receipt.json](7d951a34/fresh-cli/bruno-receipt.json)** :118 requêtes,236 tests,731 assertions, 220 appels physiques.
- **[7d951a34/after-reset-cli/bruno-receipt.json](7d951a34/after-reset-cli/bruno-receipt.json)** :118 requêtes,236 tests,731 assertions, 221 appels physiques.
- **[7d951a34/native-421.json](7d951a34/native-421.json)** : parsing 171 fichiers et moteur installé 4.2.1,118 requêtes/236 tests/730 assertions ; cache natif et HTTP réels, sans rendu/login GUI revendiqué.
- **[7d951a34/technical/receipt.json](7d951a34/technical/receipt.json)** :4 requêtes/8 tests.
- **[7d951a34/gui-observations.json](7d951a34/gui-observations.json)** : observations réelles et limites CUA.
- **[7d951a34/unit-controls.log.gz](7d951a34/unit-controls.log.gz)** :27 tests locaux.
- **[7d951a34/publication-input-comparison.json](7d951a34/publication-input-comparison.json)** : entrées opérationnelles identiques pour la publication documentaire.
- **[7d951a34/secret-scan.json](7d951a34/secret-scan.json)** et **[7d951a34/SHA256SUMS.json](7d951a34/SHA256SUMS.json)** : scan et intégrité des bytes conservés.
- **[587b729a/qualification.json](587b729a/qualification.json)** : première campagne complète, avant correction des libellés du guide ; attribution séparée.
- **[587b729a/attempts](587b729a/attempts)** : diagnostics préparatoires conservés, dont échec de collecte après runtime READY.

Les JUnit et JSONL sont frais pour chaque exécution, jamais issus de target.
JSONL contient identités de requête, méthodes, chemins, statuts et compteurs,
sans body, bearer, binding, invitationToken ni mot de passe. Le JUnit conserve
les assertions booléennes. Les logs de package/tests sont compressés sans
modification de leur contenu ; les consoles HTTP Bruno ne sont pas conservées.
Le scan couvre les credentials connus des instances actuelles et les formes
JWT ; il complète l'allowlist et ne prétend pas retrouver tout secret historique
inconnu. L'arbre privé .pocoma-local n'est jamais publié.

Reproduire la campagne sur une cible jetable **neuve**, après arrêt explicite
de toute autre instance active :

~~~sh
python3 -B scripts/local/test_bruno_playground_installation.py --instance i3-new --output /tmp/pocoma-i3-new-evidence
~~~

Le script refuse une cible/output existants et reset seulement sa nouvelle
fixture. Images/caches hôte sont partagés ; aucune installation tous caches
froids, campagne SPEC09 ou gate Maven globale n'est revendiquée.
