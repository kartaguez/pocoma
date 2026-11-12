# L7.2 — Qualification fonctionnelle, sécurité et Bruno

**Verdict `L7_I2_CERTIFIED_WITH_RESERVATIONS` pour les parcours locaux démontrés.** Mandat `GO_L7_I2_I3_FULL_EXECUTION`. Référence initiale `d5da8f4200c4f60832d9590edfaff224ddaec9b9`, worktree propre et SHA identique sur `origin/feat/v3-architecture` avant intervention.

## 1. Retrait et contrats publics

Suppression du contrôleur des trois routes Link, du service d'admission, des DTO et du port, du store de propositions et de la configuration Spring/TTL associée. Le principal d'admission ne reçoit plus d'accès à la table et à la colonne Link. Le gate de démarrage Identity exige que les demandes déjà admises soient terminales avant bascule. Les migrations et le discriminant de lecture historique `LINK` sont conservés. [Décision et procédure d'upgrade](L7_I2_Link_Retirement.md), [empreintes des 64 migrations et inventaire des modules](../testing/evidence/L7-I2-I3/link-retirement-static.json), [inspection des quatre archives exécutables](../testing/evidence/L7-I2-I3/link-retirement-executable-archives.json).

| Contrat actif sous `/api/v3` | Parcours et preuve attendue |
|---|---|
| `POST /identity/requests`, `GET /identity/requests/{id}/result`, `GET /me/binding` | Registration sur EUI inconnue ou libre, Unlink de soi et d'une EUI historiquement affiliée au même User, refus des cibles étrangères et de l'ancien token. Résultat durable propre à l'EUI, binding courant indépendant. |
| `POST /commands`, `GET /commands/{id}/result` | Création, gestion des participants, dépenses et corrections, suppression. Clé idempotente par intention et EUI, admissions sans effet synchrone, résultat terminal indépendant de la matérialisation du Pot. |
| `GET /pots/{id}`, `GET /pots/{id}/versions/{v}`, `GET /pots/{id}/versions/{v}/balance`, `POST /pots/{id}/versions/{v}/expenses/query`, `GET /me/pots` | Pot courant et historique, composants et balances à version exacte, recherche bornée, visibilité, pagination, 404 indistinguable et contrôle du token de binding. |
| `GET /invitations/by-creation-request/{id}`, `POST /invitations/available-shareholders/query` | Invitation créée et acceptée via commandes métier, secret remis au créateur, places disponibles, expiration, masquage et rejouabilité selon le contrat. |

Le modèle User/EUI, l'identité par `(issuer,subject)`, la logique de Registration/Unlink et la relecture READ restent indépendants de Keycloak. Les tests intégrés utilisant deux EUI du même User injectent un historique terminé dans un schéma PostgreSQL jetable. L'injection est réservée aux tests. Les transitions actives sont exécutées par le Worker livré.

## 2. Vérification déclarée et oracles

WEB est primaire pour le retrait et les contrats HTTP. COMMAND vérifie l'arrêt du chemin d'exécution et les transitions actives. PROJECTION vérifie la lecture des résultats et de l'historique. FOUNDATION fournit le socle transactionnel L7.3. L'architecture et le réacteur complet constituent les gates finales exigées. Le périmètre observé correspond aux slices déclarées. Les quatre commandes canoniques sont regroupées dans le sélecteur d'intégration. Aucun POM modifié. Le bootstrap courant exécute ses migrations sur des bases jetables, selon la politique et l'absence de baseline de confiance. [Politique](../testing/Reactor_Verification_Policy.md).

Les oracles réutilisés comprennent `V3ReadHttpContractTest` (JWT, visibilité, versions, masquage), `V3AdmissionPostgresTest` (canonicalisation, idempotence et autorités), `V3AdmissionFlowPostgresTest` (admission à résultat et READ), les suites `Identity*PostgresTest` et les suites Invitation. `V3LinkRetirementHttpTest` ajoute la vérification des trois routes, le refus de la commande Link directe et l'absence d'écriture. Les cas retardés et corpus volumétriques restent exercés par B1 sous fixture.

La première tranche WEB a atteint le nouvel oracle de retrait. Un POST dépourvu de type de contenu s'arrêtait au filtre READ sur HTTP 415. Le test fournit maintenant un type JSON valide pour atteindre la recherche de route. Le premier passage ciblé suivant a rencontré une indisponibilité Testcontainers Ryuk lors de l'ouverture du fork COMMAND. Les reçus intermédiaires sont conservés. La relance utilise l'adresse hôte explicite `127.0.0.1` et maintient l'hôte éveillé. [Reçus](../testing/evidence/L7-I2-I3).

## 3. Installation vierge et recevabilité

La collection réutilise B2 et B4 sur cinq processus, avec URL WRITE et READ séparées, sessions Keycloak réelles et identifiants générés. B501–B502 vérifient l'authentification, la disparition des routes et les diagnostics. Le lanceur prévoit un inventaire strict de 30 scénarios, des sondages bornés, les reçus par corpus et une comparaison SQL des résultats. La campagne spécialisée B1 utilise un autre PostgreSQL jetable et une publication contrôlée B30. Elle a passé 32/32 en 18,60 s sur `2922a982`. [Reçu B1](../testing/evidence/L7-I2-I3/bruno-b1-01/receipt.json), [provenance B1](../testing/evidence/L7-I2-I3/bruno-b1-01/provenance.json). [Recette et commandes](../../scripts/bruno-v3/README.md).

La recette `bruno-fresh-06` sur `85c68b80705bed7faa4d12c74bb3727c0775f2ca` a passé B2 24/24, B4 4/4 et les contrôles d'installation 2/2 en 54,18 s, bootstrap et nettoyage inclus. PostgreSQL 17.11 et Keycloak 26.7.0 ont servi cinq processus. Les 32 résultats Command et 6 résultats Identity concordent de WRITE à READ. Les refus, le sondage des résultats, l'effet READ et les lectures HTTP restent des observations distinctes. Le scan de reçus et journaux a passé sur les secrets de la recette. [Reçu de recette](../testing/evidence/L7-I2-I3/bruno-fresh-06/receipt.json).

Le premier B4 séparé envoyait la Registration vers READ. Après correction de l'URL WRITE, un deuxième passage a révélé le délai indépendant de matérialisation des places. Le scénario attend désormais le Pot courant et ses places libres à la version renvoyée avant d'interroger l'Invitation. La limite de sondage est inchangée. Les journaux précédents restent consultables dans les preuves.

## 4. Gates et anomalies de qualification

La première commande `python3 -Bu scripts/testing/verify.py INTEGRATION WEB,COMMAND,PROJECTION,FOUNDATION,ARCHITECTURE --output certification-raw/l7-i2-i3-architecture` a exécuté 1 056 tests, dont deux erreurs du même test d'outillage Architecture. Le Maven avait terminé et le superviseur n'a pas écrit son agrégat. Le reçu reconstruit conserve les XML de l'invocation, le journal et les diagnostics physiques. La fixture présentait Link comme intention active. Elle utilise maintenant Unlink et vérifie la stabilité de l'intention canonique, la discrimination du token et de la cible, les permissions de fichier et le refus explicite de Link. Le test ciblé corrigé est passé 2/2. [Tentative initiale](../testing/evidence/L7-I2-I3/architecture-attempt-01/result.json), [retest 2/2](../testing/evidence/L7-I2-I3/architecture-fixture-retest.json).

Les gates Architecture et réacteur complet ont passé sur les entrées stabilisées. Le niveau de preuve par variante reste dans la matrice. La recette vierge couvre l'absence de JWT, la preuve invalide, l'expiration et la mauvaise audience réelle. L'issuer erroné, la signature indépendante et la matérialisation retardée contrôlée sont exercés par les oracles de fixture. Le registre L7.1 garde les résultats historiques et leurs SHA. L'index courant relie les méthodes fraîches à leur variante. [Oracles courants](../testing/evidence/L7-I2-I3/functional-current-oracles.json). ID-02 et L2M-E02-E-LINK portent le statut de retrait. Les variantes mixtes restent dues pour les contrats conservés. [Matrice](L7_I1_Certification_Coverage_Matrix.csv), [réserves](L7_I1_Residual_Reservations_Register.md).

| Gate non filtrée | SHA testé | Exécutions | Durée Maven |
|---|---|---:|---:|
| Réacteur complet | `5ae89228c16347ed536ac18eaf9a8946a5ac4d32` | 1 057 PASS, zéro échec, erreur ou skip | 5654.87 s |
| Architecture et sa fermeture Maven | `5ae89228c16347ed536ac18eaf9a8946a5ac4d32` | 1 057 PASS, dont 47 du module Architecture | 5566.75 s |

`python3 -Bu scripts/testing/verify.py CERTIFICATION --output certification-raw/l7-i2-i3-full-after-w19` exécute `./mvnw test` avec un identifiant de preuve neuf. `python3 -Bu scripts/testing/verify.py INTEGRATION ARCHITECTURE --output certification-raw/l7-i2-i3-architecture-after-w19` exécute l'ancre Architecture, son amont et le Monolith. Les deux reçus donnent la commande Maven complète et l'inventaire. Les campagnes ont été exécutées en série, avec les slices déclarées WEB, COMMAND, PROJECTION et FOUNDATION. [Réacteur complet](../testing/evidence/L7-I2-I3/full-after-w19/result.json), [Architecture](../testing/evidence/L7-I2-I3/architecture-after-w19/result.json).

Les réserves fonctionnelles portent sur l'attribution fine des interleavings Invitation et du résultat HTTP en panne du store, ainsi que sur les fenêtres de propagation exercées en fixture. L'admission, l'effet WRITE, le résultat terminal, la projection et la réponse READ sont crédités sur leurs témoins respectifs.

Les durées Maven des deux gates finales totalisent 11 221,62 s (3 h 07 min 02 s). La somme des exécutions tracées de la mission, échecs et interruptions inclus, est de 26 423,59 s (7 h 20 min 24 s). Cette somme exclut l'analyse, le développement, les pauses et les contrôles statiques courts ; les heures CPU et l'effort humain équivalent n'ont pas été instrumentés. Les reçus ne permettent pas de les présenter comme des mesures. [Synthèse](../testing/evidence/L7-I2-I3/mission-summary.json).
