# Dossier de preuves L2 / I3

Baseline approuvée : `bb33b56dc86a8a1d31c577f983d7ea1a07bc8128`.
Code/tests/scripts finaux figés : `1d7cf51c13671c7c85a5c9c1281f526be2b2abfb`.
Le commit `bdee72b0` corrige les stimuli C05-B/K ; `e67011af` expose le préfixe d’acquisition examiné requis par R10, avec un seul record de résultat. Le commit `1d7cf51c` corrige la liste blanche de deux valeurs pures dans les tests d’architecture ; les tests négatifs sont conservés. Les essais antérieurs et le gate architecture échoué sont explicitement remplacés.

Les fichiers `*-1d7cf51c.tar.gz` finaux contiennent `maven.log`, `result.json`, les XML Surefire frais et les traces physiques. `Gate-index.json` porte leurs SHA256. Un PASS Maven sans les méthodes et sous-variantes requises ne suffit pas au collecteur.

Les archives `*-e9a6848f.tar.gz` et `diagnostics/` sont conservées comme essais antérieurs. Leur PASS sous l’inventaire initial ne certifie pas les stimuli corrigés ; index explicite `diagnostics/Earlier-gate-index.json`. Aucun résultat antérieur n’est recyclé comme preuve fraîche du SHA final.

Le [rapport](../../../v3/L2_I3_Execution_Report.md) est l’entrée d’audit ; le [manifeste](../../../v3/L2_I3_Proof_Manifest.json) alloue méthodes, autorités, sous-variantes et limites. `Environment-and-inputs.json` fige paramètres/versions et empreintes des entrées à la référence de code ; les résultats enrichis du manifeste publié peuvent différer par métadonnées documentaires seulement. `Architecture-inventory.json`, `SQL-delivery-diff.patch`, `Delivery-code-files.txt`, les vecteurs et contrôles documentaires complètent le dossier.

Pour reproduire : checkout du SHA de code, PostgreSQL Testcontainers/Docker et Java 21 disponibles ; exécuter depuis la racine `python3 -B scripts/testing/verify-v3-l2-i3.py <gate> <nouveau-dossier>`, avec gate `command`, `read`, `monolith` ou `architecture`. Le script appelle depuis `app/` la commande canonique exacte sans filtre. Les rôles et bases sont des fixtures neuves jetables ; ne jamais cibler une autorité existante. Les profils historiques transitifs des gates sont conservés, sans devenir un bootstrap I3.

Le commit de publication après le gel ne modifie que documentation/preuves. Son SHA exact se lit avec `git rev-parse HEAD` à la livraison et figure dans le reçu de publication. Vérifier `git diff 1d7cf51c HEAD -- app scripts` vide et la concordance avec `origin/feat/v3-architecture`. Le SHA d’un document Git ne peut pas être inscrit dans son propre contenu sans changer ce SHA ; le commit et le reçu résolvent cette identité, sans prétendre que les tests ont été rejoués pour des octets documentaires.

Portée globale : INCONCLUSIVE. Aucun G1/G2/G3, aucun ACCEPTED L2, aucune activation de production. I4/I5 et les producteurs/projections absents restent leurs lots receveurs. Arrêt I3 et audit indépendant avant nouvelle décision humaine.

Les logs de diagnostic bruts, ignorés par le `.gitignore` général, sont conservés dans `Diagnostics-raw-logs.tar.gz` avec leurs empreintes dans `Diagnostics-log-index.json`. Les archives finales contiennent chacune leur propre log Maven et tous les XML/SQL frais.
