# L5/I3 — preuves ciblées du 5 novembre 2026

HEAD de départ `8ff6722220049f3ce537b2f4aeeb2ffa725f4619`, branche `feat/v3-architecture`. Les campagnes portent sur le worktree d'implémentation inventorié, pas sur un ancien SHA rendu arbitrairement frais. [Manifeste](manifest.json) : hashes, commandes exactes, durées externes, deltas et inventaires. Chaque archive conserve `plan.json`, `provenance.json`, `result.json`, modèle effectif, logs Maven, XML isolés et marqueur d'invocation ; le dernier dossier Bruno conserve en plus claims sans JWT, reçus HTTP et SQL indépendants. `preparation.tar.gz` contient les deux compilations sans tests et le superviseur de budget réellement utilisé.

| Archive | Campagne | Durée totale s | JUnit tests/failures/errors/skips | Verdict du collecteur |
|---|---|---:|---|---|
| `l5-i3-fast.tar.gz` | FAST | 4.149570 | 13/0/0/0 | PASS |
| `l5-i3-pg.tar.gz` | PG | 21.627728 | 18/1/1/0 | FAIL |
| `l5-i3-pg-corrected.tar.gz` | PG | 35.982907 | 19/0/1/0 | FAIL |
| `l5-i3-fast-final.tar.gz` | FAST | 4.300984 | 13/0/0/0 | PASS |
| `l5-i3-pg-fresh.tar.gz` | PG | 53.422646 | 25/1/0/0 | FAIL |
| `l5-i3-http-correction.tar.gz` | PG | 13.093016 | 1/0/0/0 | PASS |
| `l5-i3-bruno.tar.gz` | BRUNO | 10.740757 | 1/1/0/0 | FAIL |
| `l5-i3-bruno-corrected.tar.gz` | BRUNO | 22.369118 | 1/0/0/0 | PASS |

Durée incluant discovery/compilation de campagne/collecte : **165.686726 s**, toutes reprises comprises. FAST 8.450554 s / 120 ; PG 124.126297 s / 480 ; Bruno 33.109874 s / 600 ; total / 1200. Les compilations préparatoires séparées n'exécutent aucun test (7,150 s puis clean test-compile 20,160 s).

Preuve finale **composite et bornée** : 13 FAST frais, 24 scénarios PG/HTTP réussis dans `pg-fresh`, et le seul scénario HTTP corrigé réussi dans `http-correction`. Aucun source productif/POM/migration modifié après le FAST final. Les seuls deltas applicatifs ultérieurs sont l'oracle de verrou HTTP (ACCESS EXCLUSIVE → EXCLUSIVE pour autoriser les métadonnées tout en bloquant les verrous métier) puis le nom du lanceur Bruno sans paramètre `TestInfo`. Ils sont recensés et ne changent aucun contrat productif ni oracle FAST/L3. Aucun verdict FAIL original n'est réécrit en PASS. Le passage 25 tests n'est pas présenté comme un run intégral PASS.

Bruno : premier lanceur FAIL avant toute requête Bruno (écriture `Path.open(opener=...)` et identité JUnit paramétrée), puis lanceur corrigé PASS. **10 scénarios / 70 requêtes** : 10 principales CLI + 60 helper, zéro skip ; CLI 10 tests PASS. Keycloak **26.7.0** réel, issuer `http://localhost:8081/realms/pocoma`, audience `pocoma-api`, trois sessions PKCE indépendantes. Resource Server vérifie les signatures avec le JWKS réel ; signature altérée/absence refusées. Mauvais issuer/audience/expiration de JWT signés : preuves HTTP locales, pas jetons Keycloak réellement expirés. SQL indépendant : 1 proposition, 1 Link, 1 association Link, 2 résultats CHANGED/versions (Registration E1 + Link E2).

Les canaux archivés ont été scannés sans JWT sérialisé ; fichier éphémère des bearers supprimé. Les premières archives FAIL conservent des canaries de tests, y compris des erreurs injectées sous administrateur hors rôle de runtime ; aucune qualification globale des journaux admin/proxy n'est revendiquée.

NOT RUN / DEFERRED : WEB+COMMAND canonique non filtré, Architecture globale (§17), full reactor, Q5/B5 et matrice ID-02/C-01 complète. Scénarios Bruno planifiés B306/B311/B312/B314 non lancés dans ce delta ; leurs invariants expiration/révocation E1/cible occupée/course/commit sont couverts localement par PG, sans équivalence externe complète. Multi-issuer opérationnel, production et capacité/rétention longue non qualifiés. I3 NON CLOSED, L5 OPEN ; aucune certification SPEC-10.
