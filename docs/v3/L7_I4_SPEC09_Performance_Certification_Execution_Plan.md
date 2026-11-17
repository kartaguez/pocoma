# L7.4 — Protocole d'exécution de SPEC-09

**Mise à jour du 13 novembre 2026 : `L7_I4_TECHNICAL_EXECUTION_AUTHORIZED`.** Le mandat `GO_L7_I4_TECHNICAL_EXECUTION_WITH_I3_CERTIFICATION_DEFERRED` autorise l'exécution technique avant certification L7.3. E1 et un crash/reprise E3 sont exécutés ; les campagnes contractuelles restent NOT_RUN, faute de topologie A/B. [Bilan et décisions](L7_I4_SPEC09_Technical_Qualification_Report.md), [banc](L7_I4_Bench_Architecture_and_Instrumentation.md), [matrice contractuelle](L7_I4_SPEC09_Contract_Matrix.csv). Le verdict historique de préparation bloquée et les préparations décrites ci-dessous ne valent pas interdiction du nouveau mandat ; leurs limites de certification restent applicables.

## 1. Comparaison historique et qualification directe

La comparaison de refactoring A/B désigne l'ancien et le nouveau logiciel. Le dossier historique utilise A `123b3fd2` et B `779d7e3e` avec patch, 17 comparaisons sous Docker monohôte, et une métrologie centrée sur les projections. Les jeux H1–H5 et les populations effectivement mesurées sont conservés. Une conclusion comparative exige deux builds reproductibles, les mêmes snapshots, la même offre réelle, les mêmes oracles et un ordre de passage alterné. Comparer d'abord H1 aux paliers 1 et 10/s. Ajouter H4 à 10/s si la profondeur ou les quarantaines expliquent un écart. Garder le verdict A/B indéterminé si les entrées ne sont pas alignées. [Conditions et campagnes L7](../testing/evidence/L7-full-execution/performance/conditions.json), [profils historiques](../testing/evidence/L7-full-execution/performance/profile-populations.json).

La certification directe s'applique au gel courant indépendamment de cette comparaison. Les configurations A et B de SPEC-09 désignent l'environnement. A utilise PostgreSQL dédié 4 vCPU/8 Gio, les Runtimes sur une seconde machine 4 vCPU/8 Gio et AuthN simulée avec AuthZ réelle. B conserve ce socle et utilise un Keycloak réel isolé. Séparer le générateur et les observateurs quand les ressources le permettent. [SPEC-09](SPEC-09_Performance.md), [protocoles F01](SPEC-10_Test_Certification.md).

R4 invalide le plafond déduit d'un observateur saturé. R5 isole la concurrence fonctionnelle et la famine de Pots. R6 établit les jalons de handoff. R7 décrit les réglages retenus partiellement. R8–R11 documentent Discovery, quarantaine et résilience. Les observations R11 N1/N2/N3 sont des FAIL locaux sous conditions différentes de la configuration A, avec censure et offre effective à vérifier. [R4](L6_I3_R4_Final_Metrology_and_Sustainable_Capacity_Qualification_Report.md), [R5](L6_I3_R5_Realistic_Business_Workload_Qualification_Report.md), [R6](L6_I3_R6_Async_Stage_Handoff_Latency_Qualification_Report.md), [R7](L6_I3_R7_Controlled_Async_Performance_Optimization_Report.md), [R9](L6_I3_R9_Consumption_Resilience_and_Requalification_Report.md), [R10](L6_I3_R10_Performance_Resilience_and_Quarantine_Consolidation_Report.md), [R11](L6_I3_R11_Final_SPEC09_E2E_SLA_Qualification_Report.md).

## 2. Configurations et commandes prévues

Le [manifeste](../../scripts/performance/l7-spec09-campaigns.json) fixe trois répétitions, les paliers, fenêtres, profils 5/20, 20/200 et 100/2 000, les trois volumes globaux, la profondeur d'historique et les seuils inchangés. Le rendu produit les paramètres de stimulus et distingue les préparations encore nécessaires. Les commandes rendues supposent une cible jetable, les URL WRITE/READ, le JWT et le binding injectés en environnement, les snapshots prévalidés et un collecteur indépendant. La commande de rendu est statique.

```sh
python3 -B scripts/performance/plan-l7-spec09.py
node --input-type=module --check < scripts/k6/v3-performance.js
node --input-type=module --check < scripts/k6/lib/v3-api.js
```

Pour préparer le Pot moyen nominal dans un répertoire de preuves neuf, renseigner `POCOMA_READ_URL`, `POCOMA_WRITE_URL`, `POCOMA_TOKEN`, `POCOMA_BINDING_TOKEN` et l'observateur SQL jetable hors Git. Choisir des chemins absolus neufs pour les deux sorties. La création des jeux de données et l'injection s'exécutent pendant L7.4.

```sh
python3 -B scripts/performance/v3-seed.py --profile medium --pots 10 \
  --seed-id l7-spec09-medium-10 --materialize \
  --output "$POCOMA_SEED_RECEIPT" --fixture-output "$PRE_SEEDED_POTS_FILE"
```

Les anciens exemples de comparaison utilisant `v3_populated_db_generator.py` sont retirés de la procédure exécutable : ce générateur refuse désormais l'exécution depuis L73-ADMIN-01. Les populations et résultats historiques restent consultables ; leur comparaison exige un préparateur admissible neuf. La comparaison historique n'a pas été exécutée pendant L7.4.

```sh
# Références historiques seulement, ne pas lancer ces anciens exemples.
# Voir L7_I3_Completion_Anomalies_and_Gaps_Register.md, L73-ADMIN-01.
```

Le `v3-run.py` historique embarque des hypothèses Monolith, un budget propre à L6 et des destinations de collecte historiques. Les commandes rendues du banc courant préparent l'injection k6 sur deux URL. Leur rendu et leur syntaxe ont été contrôlés. Le rendu fournit la commande de préparation pour chaque profil indépendant. Le générateur déterministe enregistre un reçu de population, vérifie chaque résultat terminal, compare les identifiants et la version en HTTP READ puis vérifie l'état en SQL. Il écrit un manifeste compact consommé par k6 via `PRE_SEEDED_POTS_FILE`. Le stimulus réserve un Pot par VU et impose 10, 100 et 250 Pots à 1, 10 et 50/s. Toute itération abandonnée ou famine de l'injecteur invalide la preuve de l'offre. Le Grand Pot hors ligne contient 100 Shareholders, 2 000 Expenses et jusqu'à 200 000 participations. La matérialisation du Grand Pot et le coût du collecteur restent à calibrer. Identity, le rattrapage, les grandes volumétries et DeletePot exigent les préparations indiquées au manifeste. [Rendu final](../testing/evidence/L7-I2-I3/spec09-static-plan-05.json), [inspection k6](../testing/evidence/L7-I2-I3/spec09-k6-inspect.json).

### Authentification de test pour la configuration A

Sur l'hôte des Runtimes, démarrer l'émetteur avec une liste JSON de sujets de test et une durée de vie couvrant la fenêtre, le warm-up et le drain. Le fichier de jetons doit être neuf et extérieur au dépôt. Configurer les deux processus WEB avec `pocoma.v3.http.issuer=$POCOMA_TEST_ISSUER`, leur audience de test et `pocoma.v3.http.jwk-set-uri=http://127.0.0.1:18090/jwks.json`. La validation JWT et l'autorisation Pocoma restent actives. Le serveur publie la clé publique et les jetons sont remis localement.

```sh
node scripts/performance/spec09-test-issuer.mjs --port 18090 \
  --issuer "$POCOMA_TEST_ISSUER" --audience "$POCOMA_TEST_AUDIENCE" \
  --subjects "$POCOMA_TEST_SUBJECTS" --token-file "$POCOMA_TEST_TOKEN_FILE" \
  --ttl-seconds "$POCOMA_TEST_TOKEN_TTL"
```

Le contrôle local vérifie les signatures et les claims de deux sujets, la permission 0600 et l'absence de route d'émission. La configuration B utilise le Keycloak isolé et les contrats HTTP déjà vérifiés en recette. [Contrôle de l'émetteur](../testing/evidence/L7-I2-I3/spec09-test-issuer.json).

### Identity nominale F01-E

La décision humaine du 12 novembre 2026 remplace la lecture d'un Pot déjà matérialisé par la première réponse autorisée de `/api/v3/me/pots?limit=1` avec le nouveau binding et le JWT de la même EUI. Pour Registration, le nouveau User a une liste vide. Le seuil p95 reste fixé à 500 ms. L'ancien jalon dépendait d'un droit que le nouveau User ne pouvait pas posséder avant sa création.

Générer 1 380 EUI nouvelles par répétition, puis utiliser leurs sujets avec l'émetteur Configuration A décrit plus haut. Les 180 premières sont le warm-up et les 1 200 suivantes l'échantillon à une offre de 1/s. Utiliser un UUID de répétition inédit et des fichiers hors Git. Les services libpq sont deux observateurs en lecture seule.

```sh
python3 -B scripts/performance/run-l7-spec09-registration.py --run-id "$POCOMA_F01E_RUN_UUID" --subjects "$POCOMA_TEST_SUBJECTS" --report "$POCOMA_F01E_PLAN"
python3 -B scripts/performance/run-l7-spec09-registration.py --execute --isolated-target --run-id "$POCOMA_F01E_RUN_UUID" --subjects "$POCOMA_TEST_SUBJECTS" --tokens "$POCOMA_TEST_TOKEN_FILE" --write-url "$POCOMA_WRITE_URL" --read-url "$POCOMA_READ_URL" --write-service pocoma-spec09-write --read-service pocoma-spec09-read --report "$POCOMA_F01E_RECEIPT"
```

Démarrer l'émetteur entre les deux commandes. Le générateur refuse un sujet déjà lié et sépare admission, obtention du binding et réponse de navigation. Il mesure sur une horloge monotone. Le commit se situe entre l'envoi du POST et la réception du 202 : le rapport calcule les deux bornes p50/p95/p99. Il crédite PASS si la borne haute p95 est au plus 500 ms, FAIL si la borne basse dépasse 500 ms, et INCONCLUSIVE si l'intervalle chevauche le seuil. Un abandon d'offre, une preuve HTTP incohérente ou un rapprochement SQL incomplet invalide la campagne. Une attente de navigation au-delà de huit secondes reste censurée dans le dénominateur. Après l'échantillon, deux lectures SQL rapprochent les résultats terminaux et l'identité du User par demande, sans écrire ni exporter les bindings.

La syntaxe, l'oracle HTTP, les bornes, la censure et le rapprochement SQL simulé ont passé hors ligne. La cadence de sondage et son coût doivent être mesurés lors de la préparation de la topologie contractuelle. [Preuve statique F01-E](../testing/evidence/L7-I2-I3/spec09-registration-static.json).

### Keycloak intégré F01-F

Préparer un realm jetable `pocoma-spec09-<identifiant>` et ses comptes inédits, puis importer le fichier sous Keycloak 26.7 sur l'hôte réservé. Les sorties du préparateur sont privées (0600) et restent hors Git. Configurer les deux Runtimes WEB pour cet issuer et l'audience `pocoma-api`. Le client conserve l'Authorization Code et PKCE S256, avec Direct Access Grants désactivé.

```sh
python3 -B scripts/performance/run-l7-spec09-keycloak.py prepare --run-id "$POCOMA_F01F_RUN_UUID" --subjects "$POCOMA_TEST_SUBJECTS" --realm "$POCOMA_B_REALM" --realm-file "$POCOMA_B_REALM_IMPORT" --accounts "$POCOMA_B_ACCOUNTS" --report "$POCOMA_F01F_PLAN"
python3 -B scripts/performance/run-l7-spec09-keycloak.py execute --isolated-target --run-id "$POCOMA_F01F_RUN_UUID" --issuer "$POCOMA_B_ISSUER" --accounts "$POCOMA_B_ACCOUNTS" --report "$POCOMA_F01F_RECEIPT" --write-url "$POCOMA_WRITE_URL" --read-url "$POCOMA_READ_URL" --write-service pocoma-spec09-write --read-service pocoma-spec09-read
```

À 1 nouveau parcours/s, 180 sujets servent au warm-up et 600 à l'échantillon. Mesurer séparément login et autorisation, échange de code, Registration et Unlink. Vérifier la version 1 du binding actif, la version 2 inactive, le refus du mauvais et de l'ancien token, puis rapprocher les deux résultats terminaux et les slots dans les bases séparées. Les refus, attentes et abandons restent dans les reçus. Aucun SLO chiffré B n'est ajouté.

Pour la comparaison de Registration A/B, utiliser un autre snapshot B neuf et le mode `issue-tokens` du même script avant la fenêtre évaluée. Réutiliser ensuite le même scénario, la même offre et les mêmes jalons F01-E. Les durées OIDC du parcours B restent une mesure distincte ; ne pas soustraire les percentiles. [Contrôle statique du scénario B](../testing/evidence/L7-I2-I3/spec09-keycloak-static.json).

### Stock initial F01-G

Arrêter le traitement et Discovery avant de constituer le stock. Garder l'admission HTTP WRITE disponible. Préparer un export vérifié de 10 000 Pots moyens distincts. Le chargeur valide la structure hors ligne, puis admet une seule commande `CreateExpense` par Pot avec une intention et une clé propres. Une réponse incertaine, refusée ou dupliquée arrête l'envoi après le lot en cours ; elle est conservée au reçu et aucun inventaire admissible n'est publié. Après un stock incomplet, reprendre sur un environnement jetable neuf.

```sh
python3 -B scripts/performance/stock-l7-spec09-backlog.py --fixtures "$POCOMA_F01G_FIXTURES" --report "$POCOMA_F01G_PLAN" --concurrency 8
python3 -B scripts/performance/stock-l7-spec09-backlog.py --fixtures "$POCOMA_F01G_FIXTURES" --report "$POCOMA_F01G_STOCK" --concurrency 8 --execute-stock --isolated-and-workers-stopped --write-url "$POCOMA_WRITE_URL" --run-id "$POCOMA_F01G_RUN_UUID" --requests "$POCOMA_INITIAL_REQUESTS"
```

Les deux commandes exigent des chemins de reçus neufs. Le mode d'admission s'exécute pendant L7.4, après vérification que la cible est jetable et les Workers arrêtés. Comparer l'inventaire des HTTP 202 aux publications durables pendant que le traitement reste arrêté. Le test statique a validé 10 000 descripteurs synthétiques sans trafic HTTP. [Reçu du chargeur](../testing/evidence/L7-I2-I3/spec09-stock-static.json). Utiliser deux services libpq d'observation distincts, définis hors Git. Le script effectue deux lectures SQL bornées en transactions `READ ONLY`.

```sh
python3 -B scripts/performance/observe-l7-spec09-backlog.py capture \
  --write-service pocoma-spec09-write --requests "$POCOMA_INITIAL_REQUESTS" \
  --output "$POCOMA_INITIAL_RECEIPT"
python3 -B scripts/performance/observe-l7-spec09-backlog.py reconcile \
  --write-service pocoma-spec09-write --read-service pocoma-spec09-read \
  --requests "$POCOMA_INITIAL_REQUESTS" --before "$POCOMA_INITIAL_RECEIPT" \
  --output "$POCOMA_OBSERVATION_RECEIPT"
```

Horodater le redémarrage et alimenter simultanément à 1 nouvelle commande/s, hors stock initial. Choisir un nouveau chemin de sortie par observation. Le résultat `CONVERGED` demande le résultat WRITE appliqué, le résultat READ identique, les versions, les balances et la visibilité attendues, les composants de l'événement et les quatre couvertures de Consumption, avec zéro slot des publications contrôlées. Le chronométrage de 300 secondes inclut l'observation de l'état convergé. La préparation des Pots à grande échelle et les coûts du chargeur et du rapprochement restent à mesurer sur la topologie contractuelle. [Vérification statique SQL](../testing/evidence/L7-I2-I3/spec09-backlog-static.json).

## 3. Admissibilité et réconciliation

Pour chaque répétition, sceller SHA productif, état Git, empreintes JAR, images et versions JVM/PostgreSQL/Keycloak/k6. Joindre CPU/RAM effectifs, limites de conteneurs, disque, réseau, horloges, version de schéma, ACL, WAL/fsync, pools Business/Control/Source, quotas HTTP et paramètres Consumption. Figer partitions, workers, soft cap, batch, fenêtres, cadence, claims, leases, renewal, retry et quarantaine. Le réglage local de référence utilise quatre partitions, polling 2–20 ms et lease 240 s. Les pools WRITE sont 4+8, les pools projection 4/4/8 et 5/5/9 selon rôle. La configuration mesurée doit être jointe au reçu. [Configurations locales](../../docker/v3-five-runtimes).

Recréer le même snapshot initial entre répétitions, mesurer son empreinte et distinguer warm-up, fenêtre évaluée et drainage. Maintenir la machine éveillée, exclure Maven et les autres charges, horodater en UTC et utiliser une horloge monotone par client. Observer CPU, RSS, GC, I/O, WAL, verrous, connexions, pools, backlog, âge et progression par Consumption. Calibrer une cadence de sondage de 25 ms avec un coût mesuré. Une dérive de provenance, de l'offre ou de l'oracle rend la campagne inconclusive. Une violation physique ou un seuil dépassé dans des conditions admissibles donne FAIL. Les timeouts et censures restent dans les reçus et dans les dénominateurs.

Réconcilier offre, abandons du générateur, requêtes tentées, admissions durables, résultats WRITE, résultats READ, effets attendus et sondages clients. Rapporter p50/p95/p99, issues et échantillons par population. Un succès C-03 demande une première réponse authentifiée et complète avec `minVersion=V`, W≥V et balance de W. Mesurer aussi l'exactitude et la disponibilité de la balance de V, ainsi que visibilité et complétion indépendantes. Pour DeletePot, chronométrer `CurrentPot → PotVersion(D).deleted=true` depuis l'admission durable et vérifier toutes les balances actives antérieures. Un 404 seul est insuffisant.

## 4. Ordre et enveloppe

1. Valider les entrées de métrologie et préparer des snapshots indépendants.
2. Exécuter nominal A, puis 10/s soutenu et 50/s avec retour nominal. Distinguer la charge offerte, la capacité soutenable et la contention mono-Pot.
3. Exécuter Grand Pot, Registration, puis les campagnes Keycloak B.
4. Exécuter rattrapage de 10 000 admissions initiales avec 1 nouvelle/s, puis profondeur, repos et suppressions appariées.
5. Réserver des séries distinctes pour la rampe de saturation et l'endurance. Utiliser l'endurance pour préciser la stabilité si la fenêtre soutenue est insuffisante.
6. Rejouer la comparaison historique uniquement dans la portée où ses deux côtés sont admissibles.

Compter 7–12 h de machine pour la première enveloppe de qualification directe, hors chargement des plus gros snapshots, répétitions supplémentaires et endurance. Le préchargement de 10 000 à 1/s prendrait 2 h 47 à lui seul. Le temps final dépend du préchargement accéléré, des oracles de complétion et du retour au régime nominal. [Estimation L7.1](L7_I1_Certification_Coverage_and_Execution_Plan.md#12-effort-et-ordonnancement).

Le manifeste final rend 13 familles de campagnes, avec les commandes explicites F01-E/F/G. Les 17 contrôles hors ligne des trois nouveaux scripts passent ; leur reçus n'attestent aucune latence réelle. La préparation reste bloquée pour la collecte CPU/RAM/GC/WAL/pools sur la topologie contractuelle, les snapshots volumétriques et l'ordonnancement du jalon interne DeletePot. Aucun benchmark SPEC-09 ni lot L7.5 n'a été exécuté. Ces travaux restent autorisés dans la préparation du mandat ; l'arbitrage F01-E ne requiert plus de décision.
