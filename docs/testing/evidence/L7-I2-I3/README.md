# L7.2–L7.3 — Preuves locales

Les tentatives sont conservées sous des identifiants distincts. Les journaux compressés portent les octets de chaque journal brut et une empreinte dans `intermediate-log-index.json`. Les reçus Bruno conservent le SHA déclaré, l'état Git, les empreintes des JARs, les images et les scénarios effectivement observés.

| Campagne | Portée | État observé |
|---|---|---|
| `web-attempt-01` | Tranche WEB canonique | FAIL sur l'oracle de retrait, POST rejeté avant recherche de route faute de type JSON. |
| `identity-retirement-attempt-01` | Test ciblé après nettoyage des classes | FAIL par indisponibilité du port Ryuk du fork COMMAND. |
| `identity-retirement-attempt-02` | Tranches ciblées Identity, HTTP, PostgreSQL, Invitation et reconstruction | 99 tests sélectionnés PASS sur un état intermédiaire. |
| `bruno-fresh-01/02` | PostgreSQL, Keycloak, démarrage cinq processus | FAIL d'amorçage du banc avant Bruno. |
| `bruno-fresh-03/04` | Admission, Invitation et diagnostics séparés | B2 complet. B4 a révélé une mauvaise URL d'admission puis une attente READ manquante dans le harnais. |
| `bruno-fresh-05` | Installation vierge, Bruno et SQL | 30/30 Bruno, égalité 32 résultats Command et 6 Identity, aucun slot ni demande en attente. Le premier oracle SQL du rôle de statut n'a pas pu attribuer le refus faute de qualification du schéma. |
| `read-replay-attempt-01/02/03/04` | Retrait contrôlé et rejeu de valeurs | Assertions intermédiaires et reprise avant expiration physique du bail. |
| `bruno-fresh-06` | Recette vierge du candidat `85c68b80` | PASS, 30/30, 38 résultats corrélés, cinq refus 42501, six principaux applicatifs et cinq processus prêts puis fermés. |
| `read-replay-attempt-05` | Rejeu exact et reprise après expiration du bail | PASS en 4 min 12 s, valeurs et préfixes dans `read-replay-values.txt`. Les entrées `app/` sont celles du commit `133b84ed`, identiques au candidat Maven. |
| `read-replay-live-lease.json` | Connexion SQL indépendante au conteneur de `read-replay-attempt-05` | Les 28 baux restent détenus à l'instant observé alors que l'autorité est READY. |
| `architecture-attempt-01` | Gate intégré du candidat `85c68b80` | FAIL avec 2 erreurs d'une fixture Architecture parmi 1 056 tests. Le superviseur s'est arrêté avant l'agrégation. Les XML et le journal ont permis de reconstituer le reçu. |
| `full` | Première tentative de réacteur complet | FAIL après 876 tests, `i5_r12` observe un âge initial de 5,796 s pour une limite de 5 s. |
| `read-partial-reset-acl.json` | Refus effectifs du principal READ après bootstrap | PASS, quatre 42501 et un 23514, vecteur des sept checkpoints intact. La première tentative a échoué au chargement du témoin. |
| `r05-after-fixture` | Rejeu du scénario à un Worker | PASS sur fixture corrigée. |
| `r12-isolated` | Reproduction ciblée `i5_r12` | FAIL, 5,108 s avec la fixture de démarrage séquentiel. |
| `r12-after-fixture` | Rejeu isolé sur fixture corrigée | PASS, âge initial maximal 0.322 s, mêmes oracles. |
| `r12-initial-physical-age.json` | Comparaison des snapshots du premier cycle | Âges calculés en PostgreSQL, bornes et empreintes des traces. |
| `architecture-interrupted` | Architecture lancée avant correction de la fixture `i5_r12` | Arrêt explicite après le diagnostic pour stabiliser les entrées. |
| `l2-oracle-reconciliation.json` | Rapprochement par ID historique | Index de 380 lignes L2M, avec les limites de leurs oracles et leurs sources archivées. |
| `functional-current-oracles.json`, `operational-current-oracles.json`, `l2-current-oracles.json` | Réconciliation sur XML du candidat Architecture | Correspondances par ID et limites du composant, de la méthode et du stimulus. |
| `bruno-b1-01` | B01–B32 sur la fixture PostgreSQL READ | PASS 32/32 avec publication contrôlée, au SHA `2922a982`. |
| `architecture-fixture-retest.json` | Deux tests de la fixture corrigée | PASS 2/2 au SHA `2922a982`, preuve ciblée. |
| `spec09-static-plan-04.json`, `spec09-k6-inspect.json` | Préparation SPEC-09 | Rendu statique des commandes et inspection des paliers 1, 10 et 50/s par k6. Les versions 01 et 02 gardent les états précédents. |

Les campagnes Maven utilisent des fixtures Testcontainers PostgreSQL 17.11. Les recettes HTTP emploient PostgreSQL 17.11 et Keycloak 26.7.0. Le réacteur et les tests de recette restent sérialisés. Les journaux d'échec ne constituent pas une preuve de succès de la campagne, même lorsque des sous-scénarios passent.
