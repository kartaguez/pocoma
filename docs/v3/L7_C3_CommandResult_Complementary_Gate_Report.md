# L7-C3 — Qualification complémentaire du pilote CommandResult

**`L7_C3_PILOT_GATE_INCOMPLETE`**

La qualification porte sur `feat/v3-l7` au HEAD `d7b4b6d73963cab7efeb658ef67478d52de03c32`, avec le diff du pilote présent au départ. Le journal historique ne publie que `COMMAND_RESULT_AVAILABLE`. C3-R1 ratifie le registre coalescé des courants par famille. C3-R2 ratifie la reconstruction intégrale lors de l’activation sur stock READ. Toute extension requiert une décision explicite. [Rapport initial](L7_C3/pilot-report.md) · [Plan](L7_C3/plan.md) · [Arbitrages](L7_C3/arbitrages.md) · [Architecture](L7_C3/architecture.md)

## 1. Références et périmètre de vérification

- Tranche primaire **PROJECTION**, sans tranche secondaire. Le gate non filtré associe l’ancre `runtime-worker-materialize` à la gate globale `architecture-tests`, requise pour l’ownership de la migration V5 et par le mandat. Les 42 modules enfants et l’agrégateur appartiennent à la fermeture Maven attendue. La présence de COMMAND, WEB, FOUNDATION et Monolith dans cette fermeture reflète les dépendances de test. [Politique du réacteur](../testing/Reactor_Verification_Policy.md)
- Impact de cette livraison dans les essais isolés, l’outillage et le dossier de décision. Aucune modification supplémentaire sous `app/`, aucun franchissement inattendu de tranche. Aucun réacteur sans sélection d’ancres n’a été lancé. Cette certification relève de la clôture L7.
- Vérification **CURRENT** sur PostgreSQL 17.11, schémas READ V1–V4 et V5 du pilote installée explicitement. Les suites du gate conservent leurs chaînes historiques transitives. La campagne complémentaire utilise des conteneurs jetables et la fixture à autorités READ et WRITE séparées. La V5 reste hors du chemin Flyway déployé.
- Les empreintes des trois archives historiques et de leurs membres sont vérifiées. Les manifestes archivés de la qualification ciblée et du premier gate, respectivement 1 176 et 7 548 chemins, correspondent aux contenus présents dans le checkout. Les versions PostgreSQL du pilote et du nouvel essai réutilisent la même image épinglée. Le premier gate historique reste un échec environnemental, avec deux erreurs Bruno et architecture ignorée, même si les relectures ciblées ont réussi. [Ciblé](../testing/evidence/L7-C3/command-result-pilot-targeted.json) · [Premier gate](../testing/evidence/L7-C3/command-result-pilot-gates.json) · [Relectures ciblées](../testing/evidence/L7-C3/command-result-pilot-followup.json)

## 2. Gate Maven composé

```sh
cd app && ./mvnw --fail-at-end -pl runtime-worker-materialize,architecture-tests -am test
```

Le dépôt complet fournit les scripts et contrats de test. Bruno CLI 4.3.0 avec Node 24.15.0 s’exécute depuis `scripts/bruno-v3/node_modules/.bin/bru`, le chemin employé par Surefire dans Monolith. Le wrapper utilise Maven 3.9.14 et Java 21. Docker Desktop 29.9.2 fournit PostgreSQL. Le tag local non épinglé `postgres:17-alpine` expose `psql` 17.12. L’image C3 épinglée exécute PostgreSQL 17.11.

Le premier rejeu dans le checkout partagé a exécuté **943 tests**, sans échec, erreur ou skip Surefire dans les modules exécutés. Au démarrage des tests de Materialize, JUnit a échoué sur `NoClassDefFoundError: ReadParticipantsFixture`. Le bytecode de test incrémental préexistant contenait le descripteur `LReadParticipantsFixture;` au lieu du nom de package complet. Cette erreur de découverte ne produit aucun cas Surefire et bloque Monolith et architecture. La sélection ne comportait aucune exclusion de test. La relance utilise un clone local au même HEAD, avec les fichiers du pilote identiques et Bruno copié avant la compilation. [Reçu du premier rejeu](../testing/evidence/L7-C3/complement/composed-local-attempt/maven-gate.json) · [Journal brut](../testing/evidence/L7-C3/complement/composed-local-attempt/maven-gate.tar.gz) · [Descripteurs fautifs](../testing/evidence/L7-C3/complement/composed-local-attempt/stale-bytecode.txt)

Le contrôle ciblé sur cette copie compilée de zéro a réussi **14 tests**, dont **11 C3**, sans échec, erreur ni skip. Le descripteur recompilé contient le package complet. Cette preuve explique le défaut d’environnement du premier rejeu. Elle reste distincte du gate composé. [Journaux, XML et descripteurs frais](../testing/evidence/L7-C3/complement/targeted-fresh.tar.gz)

**PASS**, sortie Maven 0, durée 01:20 h. La fermeture contient 43 modules, dont 43 effectivement entrés. Surefire a recensé **1 163 invocations**. Compte distinct des échecs, erreurs et skips, dans cet ordre `0 / 0 / 0`. Les trois modules de décision présentent 128 cas côté matérialisation, 40 côté Monolith et 52 côté architecture. La commande contractuelle n’a appliqué aucune exclusion ou filtre d’essai. Les quarantaines nominatives n’ont pas été exclues. Les modules sans test propre et les modules éventuellement ignorés sont identifiés dans le reçu.

[Reçu par module, exclusions et cas Bruno](../testing/evidence/L7-C3/complement/maven-gate.json) · [Journal, XML Surefire et reçus bruts](../testing/evidence/L7-C3/complement/maven-gate.tar.gz)

Bruno 4.3.0 a exécuté les deux corpus dans ce même réacteur. B2 couvre 24 scénarios et 151 requêtes recensées. B4 couvre 4 scénarios et 124 appels auxiliaires. L’assertion d’ownership de la ressource pilote fait partie des cas d’architecture réussis. L’inventaire frais couvre les 1 163 identités précédemment observées dans les gates historiques, avec l’architecture issue de sa relecture ciblée.

## 3. Matrice de sûreté transactionnelle

| Obligation | Témoin et résultat |
| --- | --- |
| Atomicité et rollback | Les 11 cas C3 historiques établissent l’unité physique par `(pid,xid)` du résultat, du DELETE du slot, du compteur et de l’événement. Les contraintes différées rejettent une publication absente. Une erreur de suffixe prise en défaut après allocation restaure le slot et annule les écritures même si le callback capture l’erreur. |
| Unicité et densité | La PK `(family, position)`, les contraintes uniques sur `event_id` et `(family, projection_key)`, et l’immutabilité du journal gardent le préfixe historique. La migration pilote limite le catalogue à une famille. Les compteurs et positions des autres familles historiques ont été contrôlés dans le harnais de coût. |
| Ordre au COMMIT et lecteur paginé | Une position déjà committée est suivie de deux writers sur deux résultats distincts. T1 est suspendu après l’INSERT d’événement, T2 attend sur le compteur avec le PID bloqueur observé. Le lecteur SQL voit `H=1` et une page vide après 1, puis `H=2` et la position 2 pendant que l’événement de T2 est encore privé, puis la position 3 à son COMMIT. |
| Rollback et reprise | Une terminaison du backend avant COMMIT laisse T1 en issue JDBC inconnue et le résultat absent. T2 réutilise la position 2 jamais visible, le rejeu de T1 reçoit 3. En complément, le processus PostgreSQL tué pendant le suffixe déclenche une vraie reprise WAL du postmaster. Le premier événement conserve son UUID et sa position, la position 2 préparée reste invisible et le préfixe finit à 3 après reprise. |
| Lease de partition et fencing | Le nouveau propriétaire ne peut acquérir un claim individuel vivant. Après expiration et remplacement du claim, l’ancien token reçoit `CLAIM_LOST` et le nouveau worker publie à la position suivante. Dans un cas indépendant, un claim vivant termine une fois après révocation du lease de partition. C’est le contrat L2. |
| ACK et stabilité au replay | La coupure filaire avant COMMIT mène à `NOT_COMPLETED` puis reprise du slot expiré. La coupure après le COMMIT physique mène à `COMPLETED` et conserve exactement la ligne publiée. Une répétition contrôlée du même effet committé conserve son UUID et sa position. Le test vérifie aussi la séparation de l’UUID d’événement avec la clé et l’UUID WRITE. |
| Conditions adverses et privilèges | L’événement sans effet, l’allocation sans événement, le pas de deux et la mutation de l’historique sont rejetés. Une V4 avancée est refusée. Les rôles propriétaires et d’exploitation restent le périmètre de confiance de l’architecture. |

[Observations de pagination et de crash](../testing/evidence/L7-C3/complement/order.txt) · [Journal de l’essai](../testing/evidence/L7-C3/complement/order.log.gz) · [Règles de fencing](SPEC-05_Consumption_Discovery.md#4-acquisition-protection-et-transactions)

Le premier essai complémentaire attendait à tort qu’une révocation de partition invalide immédiatement le claim. Il a observé un COMMIT. L’oracle corrigé sépare la survie d’un claim vivant du refus après expiration et takeover. Les deux comportements ont été rejoués sur PostgreSQL. Le diagnostic explique la correction de l’essai, à garanties et code de production identiques.

## 4. Densité, ordre et contrat de lecture

Pour une famille historique et une incarnation READ, le compteur part de zéro. Une transaction autorisée effectue un seul pas et garde le verrou de ligne jusqu’à résolution. L’INSERT exige le xid de cette transaction, et le COMMIT exige l’événement à la position allouée. En cas de rollback, les deux mutations sont annulées. Un successeur ne peut prendre le compteur qu’après cette résolution. La PK et l’immutabilité maintiennent l’induction `1..H`, y compris lorsque des transactions commencent dans un ordre différent de leur publication. Une position réservée et annulée peut être attribuée ensuite, puisqu’aucun lecteur ordinaire n’a pu l’acquitter. Une position déjà committée ne change plus pendant la même incarnation. La preuve de durabilité a été exécutée avec `fsync=on` et `synchronous_commit=on`, sur la même autorité après reprise WAL. Un basculement qui perdrait des commits déjà visibles exigerait un contrat de conservation et de reprise de curseur dédié. [DDL V5 pilote](../../app/infra-postgres-read/src/main/resources/db/read/pilot/migration/V5__command_result_availability.sql) · [Verrous PostgreSQL 17](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS)

Le contrat de lecture historique tient par famille sur l’autorité READ et dans un snapshot cohérent par page. Pour un curseur acquitté `K ≥ 0`, lire le compteur visible `H` et les lignes `K < position ≤ H`, triées par position, avec une limite positive. Une requête unique sous `READ COMMITTED` offre le snapshot requis. Plusieurs requêtes pour la même page exigent un snapshot commun. Une page pleine avance au dernier événement rendu. Une page non pleine contient tout le reste de ce snapshot et peut avancer à `H`. L’acquittement suit soit les effets durables, soit la remise durable de la page, selon le futur protocole du lecteur. Une page vide peut être suivie de nouveaux commits et sa reprise repart du curseur acquitté. Aucune position plus grande observée n’occulte un événement historique plus petit dans cette même incarnation. [Isolation PostgreSQL 17](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED)

Un snapshot fixe peut rester en retard et une réplique doit respecter ses garanties de visibilité pour servir ce contrat. Les positions sont indépendantes entre familles et ne représentent ni l’ordre WRITE, ni un ordre de versions de Pot. Le journal pilote n’installe aucun lecteur externe ni API de pagination. Pour les courants, C3-R1 garantit la reprise par famille avec coalescence, positions clairsemées, mêmes bornes de snapshot et couverture d’une clé stabilisée sous progression du lecteur. Les états intermédiaires peuvent disparaître. La qualification du registre courant reste ouverte. [Contrat de pagination C3](L7_C3/architecture.md#lecture-paginée-des-courants)

## 5. Performance et comparabilité

La baseline expérimentale du pilote est conservée avec **100 finalisations par variante**, **+2,33 ms au p50**, **+3 instructions JDBC** et **environ +537 octets de WAL par résultat**. Elle chronomètre l’unité finale READ sur le store JDBC, connexion comprise. La campagne ci-dessous utilise l’Engine et sépare la durée de l’appel `execute` de `acquire+execute`. Les latences absolues entre les deux protocoles ont des frontières différentes. [Baseline et échantillons](../testing/evidence/L7-C3/command-result-pilot-targeted.json)

Les fenêtres A/B ont le même nombre de finalisations, les mêmes opérations, les rôles physiques identiques et le même conteneur par campagne. Les identifiants générés sont propres à chaque fixture. Les p99 sont descriptifs sur cent valeurs par famille et variante. Les cas purs couvrent CommandResult, IdentityRequestResult, PotVersion et PotBalance avec un ou quatre workers. Le cas mixte enchaîne les trois projections d’un `CreatePot`, sur quatre workers, avec la publication activée seulement pour CommandResult. Chaque worker concurrent traite une partition L2. Les nombres par partition sont conservés dans les échantillons pour rendre visibles les variations de répartition. Chaque famille pure et chaque composante mixte ont douze échauffements exclus des mesures. Pour CommandResult, le mode activé utilise le pilote réel. Pour les trois autres profils, une fixture décore le participant existant et simule les trois instructions du suffixe dans la transaction READ réelle. Un compteur par famille et des contraintes différées bornent ce suffixe. Ce modèle n’implémente ni le registre des courants, ni la double publication PotVersion/CurrentPot, ni l’observation de récupération C3 de ces familles. [Sources du harnais](../../experiments/l7-c3-gate/README.md)

L’échantillon Pot couvre des créations sur Pots distincts, avec les stores C2 réels et un contenu de balance susceptible d’être partagé. Sont encore à mesurer les mises à jour financières et non financières des références C2, les stockages sous volumétrie et les scénarios E2E des cinq runtimes. Le banc SPEC-09 existant mesure ces derniers dans une composition V4 qui ne dispose pas d’un interrupteur C3. Une campagne HTTP A/B y exigerait une instrumentation expérimentale de composition distincte et la preuve d’un même corpus. Les temps du harnais qui excluent admission, polling et HTTP ne mesurent pas C-03. [Comparabilité C2](L7_C3/plan.md#comparabilité-des-mesures) · [Oracle utilisateur et budget SPEC-09](SPEC-09_Performance.md#4-c-03--oracle-de-disponibilité-utilisateur-et-soldes)

La campagne principale a utilisé les classes de la copie isolée, entre le contrôle ciblé et le gate composé.
Elle comprend **18 fenêtres et 2 200 finalisations mesurées**, avec cent valeurs par famille et variante.
Les percentiles sont calculés au rang supérieur sur les durées d’`execute`, en millisecondes. Les flèches indiquent OFF → ON.

| Workers | Profil | p50/p95/p99 OFF → ON (ms) | Débit OFF → ON (final./s) | Δ WAL (o/événement) |
| ---: | --- | --- | --- | ---: |
| 1 | COMMAND_RESULT | 12,03/14,02/15,88 → 13,62/15,59/18,81 | 50,11 → 48,02 | 541,7 |
| 1 | IDENTITY_REQUEST_RESULT | 10,95/13,69/15,02 → 14,37/17,54/19,08 | 53,86 → 44,17 | 522,2 |
| 1 | POT_VERSION | 33,97/39,52/42,93 → 36,43/39,42/41,02 | 24,17 → 23,09 | 2833,6 |
| 1 | POT_BALANCE | 22,83/27,19/29,97 → 26,20/29,30/29,59 | 32,99 → 29,54 | 620,8 |
| 4 | COMMAND_RESULT | 13,73/17,52/19,90 → 15,14/19,06/20,34 | 153,31 → 146,79 | 541,3 |
| 4 | IDENTITY_REQUEST_RESULT | 13,59/14,84/15,51 → 15,47/19,57/35,11 | 138,95 → 141,93 | 528,9 |
| 4 | POT_VERSION | 39,08/41,56/41,64 → 37,95/43,28/47,62 | 62,84 → 69,18 | 565,6 |
| 4 | POT_BALANCE | 25,77/29,12/29,55 → 26,64/40,88/49,36 | 87,77 → 88,34 | 524,8 |

Le débit mixte passe de **110,83 à 107,90 finalisations/s** (-2,6 %). Le delta WAL cluster rapporté aux seules publications CommandResult est de **435,3 o/événement**.

| Famille dans le mixte | p50/p95/p99 OFF (ms) | p50/p95/p99 ON (ms) |
| --- | --- | --- |
| COMMAND_RESULT | 12,07/14,21/15,72 | 14,78/18,23/19,11 |
| POT_VERSION | 35,05/45,33/48,50 | 35,31/41,98/44,72 |
| POT_BALANCE | 23,27/29,63/35,40 | 23,36/28,44/29,95 |

Chaque publication ajoute **3 instructions JDBC READ et 0 transaction**. Les comptes par finalisation pure, par autorité, sont les mêmes avec 1 ou 4 workers. Le seul changement JDBC du profil mixte est le suffixe de ses 100 résultats CommandResult. Au total, les fenêtres ont exécuté 5 000 COMMIT READ et 4 000 COMMIT WRITE.

| Profil | JDBC READ OFF → ON | JDBC WRITE | COMMIT READ | COMMIT WRITE |
| --- | --- | ---: | ---: | ---: |
| COMMAND_RESULT | 10 → 13 | 1 | 2 | 1 |
| IDENTITY_REQUEST_RESULT | 10 → 13 | 1 | 2 | 1 |
| POT_VERSION | 17 → 20 | 10 | 3 | 3 |
| POT_BALANCE | 14 → 17 | 10 | 2 | 2 |

[Mesures réduites](../testing/evidence/L7-C3/complement/cost-fresh/cost-summary.json) · [Fenêtres et CPU PostgreSQL](../testing/evidence/L7-C3/complement/cost-fresh/windows.tsv) · [Échantillons et CPU workers](../testing/evidence/L7-C3/complement/cost-fresh/samples.tsv) · [Temps et WAL SQL](../testing/evidence/L7-C3/complement/cost-fresh/sql.tsv) · [Échantillonnage des waits](../testing/evidence/L7-C3/complement/cost-fresh/waits.tsv) · [Journal des verrous](../testing/evidence/L7-C3/complement/cost-fresh/postgres-locks.txt) · [Relations et index](../testing/evidence/L7-C3/complement/cost-fresh/storage.tsv)

## 6. Contention et portée des coûts

La position dense rend le COMMIT des publications d’une même famille séquentiel. Le compteur est pris après le DELETE du slot et reste détenu pendant l’INSERT, les contraintes différées et la résolution physique du COMMIT. Une longue attente sur cette ligne allonge aussi la détention du slot et des verrous métier déjà pris. Les compteurs de familles distinctes n’imposent pas de verrou logique commun. Ils partagent les ressources PostgreSQL, JDBC, CPU et WAL. Lorsque PotVersion publiera aussi CurrentPot, les deux compteurs devront être pris dans un ordre fixe, à éprouver avec les conflits de contenu C2 et les autres writers.

Les durées SQL sont les temps d’exécution PostgreSQL rapportés par `pg_stat_statements` pour les rôles de test, COMMIT compris lorsqu’il est remonté et indépendamment du temps JDBC client. L’UPDATE du compteur inclut sa propre attente. Les journaux `log_lock_waits` donnent les attentes achevées après un seuil de diagnostic de 5 ms. Les samples `pg_stat_activity` à environ 5 ms distinguent les verrous du compteur, les autres verrous, LWLock et I/O. Un zéro dans une de ces sources ne donne pas de borne sur les attentes plus courtes. Le WAL est un delta LSN du cluster jetable sur la fenêtre. Le CPU PostgreSQL est le delta cgroup du conteneur, avec la sonde et les tâches de fond. Le CPU worker est la somme du CPU des threads du harnais pendant `acquire+execute` et exclut polling et seed.

| Workers | Profil | SQL cumulé OFF → ON (ms/final.) | CPU PostgreSQL OFF → ON (ms/final.) | CPU worker OFF → ON (ms/final.) | UPDATE compteur ON (ms/appel) |
| ---: | --- | --- | --- | --- | ---: |
| 1 | COMMAND_RESULT | 1,10 → 1,57 | 11,54 → 13,67 | 7,04 → 5,43 | 0,32 |
| 1 | IDENTITY_REQUEST_RESULT | 1,14 → 1,49 | 11,90 → 13,46 | 5,43 → 7,38 | 0,27 |
| 1 | POT_VERSION | 1,97 → 2,22 | 24,69 → 25,82 | 12,44 → 13,17 | 0,13 |
| 1 | POT_BALANCE | 1,47 → 1,90 | 17,87 → 19,62 | 9,04 → 10,45 | 0,28 |
| 4 | COMMAND_RESULT | 1,28 → 2,54 | 13,18 → 16,56 | 5,06 → 2,89 | 1,10 |
| 4 | IDENTITY_REQUEST_RESULT | 1,29 → 2,73 | 13,34 → 16,43 | 4,88 → 3,15 | 1,29 |
| 4 | POT_VERSION | 2,20 → 3,50 | 27,04 → 30,55 | 10,96 → 6,28 | 1,10 |
| 4 | POT_BALANCE | 1,64 → 3,17 | 19,92 → 23,42 | 7,46 → 4,91 | 1,31 |
| 4 | MIXED | 1,83 → 2,15 | 22,08 → 23,02 | 4,32 → 4,48 | 0,50 |

Les nombres d’échantillons d’attente sur le compteur avec quatre workers sont COMMAND_RESULT 20, IDENTITY_REQUEST_RESULT 14, POT_VERSION 9, POT_BALANCE 15, MIXED 0.
Le journal marque **2 attentes du compteur** au-delà du seuil de 5 ms, au maximum 6,20 ms.
Les autres attentes sont regroupées dans les reçus, dont la synchronisation WAL, les écritures WAL et les conflits liés au contenu partagé côté Balance.
Les observations de contention sont bornées à quatre workers, aux fenêtres échantillonnées et à ces créations de petits Pots.
Aucun deadlock ni rollback n’a été observé dans les fenêtres retenues.

Les écarts WAL bruts des chemins Pot agrègent la distribution des écritures, les pages et les full-page images. Le reçu SQL ventile les images et les enregistrements par requête. Le delta LSN cluster reste un coût net observé, qui peut être négatif sur un appariement local et ne constitue pas une attribution au journal C3.

Les instructions du suffixe attribuent directement **519 à 623 octets WAL par événement** selon le profil et la concurrence. Pour PotVersion sériel, cela représente 562 octets par événement contre un delta brut cluster de 2834 octets. Les statistiques SQL des rôles du test ne signalent aucune full-page image dans cette paire. Ce résidu cluster réapparaît dans une autre campagne de débit. Une campagne diagnostique séparée au moyen de `pg_walinspect` retrouve **570 o/événement** de delta brut pour PotVersion sériel. L’écart se déplace vers une fenêtre PotBalance OFF, où le cluster a produit **151 188 octets d’images complètes de pages**. Cela confirme que les fenêtres courtes de WAL cluster peuvent être dominées par ces sources. Le résidu de la campagne principale reste sans attribution directe à C3. [Enregistrements WAL et fenêtres](../testing/evidence/L7-C3/complement/wal-diagnostic/README.md) · [pg_walinspect sur PostgreSQL 17](https://www.postgresql.org/docs/17/pgwalinspect.html)

Les compteurs et l’historique CommandResult totalisent 131 072 octets, index inclus, après 112 événements sur la fixture à un worker. Ils totalisent 180 224 octets après 224 événements dans la variante à quatre workers, mixte compris. Ces volumes incluent les pages initiales et la croissance des index sur ce petit corpus.

## 7. Coût estimé et possibilités de regroupement

Le pilote effectue trois interactions supplémentaires pour un résultat nouveau. La preuve de disponibilité en fin de validation lit le journal et son compteur. Le suffixe après fermeture alloue la position, puis insère son événement. Les **trois rôles logiques** restent nécessaires pour conserver le contrat. Ils peuvent coûter moins de **trois instructions JDBC**.

1. Regrouper l’UPDATE du compteur et l’INSERT de l’événement via `UPDATE … RETURNING` dans une CTE consommée par l’INSERT. Le test direct sur V5 choisit le résultat exact par `RETURNING`, valide le cas nominal et injecte une collision UUID `23505`. Le résultat et le compteur reviennent à leur état précédent après l’échec. PostgreSQL transmet le numéro par `RETURNING`, les sous-instructions partageant le snapshot de la commande. Le test sur les triggers réels est important pour la garde xid à l’INSERT. Ce résultat cible une instruction JDBC au lieu de deux dans le suffixe. [Essai SQL](../testing/evidence/L7-C3/complement/grouped-sql.txt) · [CTE modifiantes PostgreSQL 17](https://www.postgresql.org/docs/17/queries-with.html#QUERIES-WITH-MODIFYING)
2. Fusionner, dans une étude séparée, la relecture métier de `requireCompletionReady` et la preuve de publication par jointures à partir d’une clé demandée. Garder la distinction entre résultat manquant, événement manquant, preuve exacte et événement orphelin ou au-delà du compteur. Valider que les états `inserted` et `equivalent` gardent leurs oracles et leurs cas d’erreur. Avec la CTE, le surcroît cible devient une instruction JDBC pour les nouveaux résultats. La lecture d’observation de recovery doit garder la même sémantique si elle partage la requête.
3. Préparer une décision séparée pour ce regroupement. Avant de modifier le pilote, couvrir les mêmes entrelacements sous le suffixe CTE, les erreurs rattrapées, le fencing au verrou final, les interruptions de transport, les collisions d’identité, le cas sans allocation et les supports de projection à deux familles. Comparer l’attente au compteur, le débit, le coût des triggers, le WAL et le temps total sur les mêmes profils. Le gain d’un aller-retour réduit potentiellement la section critique. Il conserve la sérialisation jusqu’au COMMIT. Une fonction invoker reste une option si la CTE ne conserve pas les oracles sous concurrence. Une allocation hors transaction par séquence, par lot ou par partition modifierait les garanties ratifiées et relève d’un autre arbitrage.

L’ordre de grandeur historique du surcroît est **environ 0,54 Go de WAL par million de résultats** au ratio initial, hors stock durable, amplification de réplication et variations de checkpoints. Généraliser demande de dimensionner les quatre journaux historiques et les quatre familles courantes par leurs débits respectifs. Une avance courante ajoute un UPDATE indexé avec tuples morts et vacuum. PotVersion/CurrentPot fait intervenir deux allocations au même COMMIT, avec des coûts de verrouillage qui dépendent du recouvrement des charges. L’estimation de stockage et de budget SPEC-09 doit être faite par famille et profil avant approbation. Aucune extrapolation linéaire de la latence ou du débit à partir du pilote simple.

## 8. Conditions de reconstruction READ

C3-R2 impose la procédure exclusive L6/I2 sur toute première activation avec READ déjà peuplé. Il faut fermer et drainer les writers/readers selon le protocole, borner les sources WRITE et conserver leur autorité, recréer READ, appliquer la V5 canonique et ses droits en une transaction après qualification de tous ses participants, vérifier les privilèges effectifs et le catalogue, puis rejouer depuis WRITE. Les opérations `--resume`, `--recover-destructive` et `--reset-failed` doivent réévaluer les contrôles de schéma et le même oracle avant la transition READY. La réserve L6/I2 sur le contrôle et le CAS READY en étapes distinctes reste applicable. [Décision L6/I2](L6_I2_Acceptance_and_Closure_Decision.md) · [Plan C3, reprise et oracles](L7_C3/plan.md)

L’oracle doit prouver dans une photographie READ cohérente l’exhaustivité par rapport aux 28 checkpoints et au préfixe WRITE retenu, l’absence de résidus et la correspondance dans les deux sens entre faits et publications historiques. Pour chaque historique, vérifier `count=H`, l’unicité, les bornes `1..H` et le cas zéro. Pour chaque courant, comparer le dernier état à sa projection, vérifier sa borne de position et l’existence de la tête quand `H>0`. Il faut un oracle de clés et versions attendues issu de WRITE, puisque deux objets absents peuvent préserver une bijection interne. Inclure l’absence intentionnelle de la balance terminale et tester l’interruption puis la reprise avant READY.

La reprise d’un rejeu interrompu garde la même incarnation, et conserve UUID et positions déjà committés. Un reset intégral crée une incarnation nouvelle reconstruite selon un nouvel ordonnancement. La comparaison entre deux reconstructions porte sur les clés et versions logiques, les effets indépendants et la densité propre à chacune. La détection `K>H` observe certaines ruptures. Un ancien curseur peut rester inférieur à la nouvelle tête, ce qui rend obligatoire la resynchronisation coordonnée avant un futur lecteur. Aucun reset opérationnel n’a été mené dans ce mandat.

## 9. Réserves et proposition de décision

**Maintenir le pilote isolé.** Le gate Maven et la sûreté du pilote sont clos. Les mesures locales montrent le coût et la sérialisation, mais le coût acceptable de la généralisation reste à établir pour les profils C2 représentatifs, notamment le chemin léger Balance à contenu partagé, la double publication PotVersion/CurrentPot et les registres courants, avec leur incidence E2E. L’acceptation doit se prononcer sur les budgets SPEC-09, les distributions sous charge et la reconstruction C3-R2. Une décision distincte peut autoriser la qualification SQL du regroupement avant les coûts de généralisation. Le lot diff proposé peut être revu avec le pilote existant et le dossier complémentaire, séparément du prototype C2. Le commit attend une validation explicite.

## 10. Journal de livraison

- HEAD de départ et de fin `d7b4b6d73963cab7efeb658ef67478d52de03c32`. Branche locale et upstream à divergence `0 / 0` lors de la vérification. Le diff préexistant des cinq fichiers suivis, l’index et les empreintes des 63 fichiers non suivis d’origine ont été préservés.
- Contrôle ciblé exécuté depuis le module agrégateur de la copie isolée via `cd app && ./mvnw -pl runtime-worker-materialize -am -Dtest=CommandResultAvailabilityPostgresTest,TerminalResultsReadStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test`. Il vérifie le diagnostic de compilation avant la nouvelle exécution composée.
- Commandes d’essai complémentaires `bash experiments/l7-c3-gate/run.sh <dossier> order`, `bash experiments/l7-c3-gate/run.sh <dossier> cost 100` et `bash experiments/l7-c3-gate/run.sh <dossier> wal 100`. Des runs à 8 échantillons par variante ont contrôlé l’outillage avant la campagne. L’inventaire et la réduction sont produits avec `python3 -B experiments/l7-c3-gate/collect-gate.py <run> <preuves>` et `python3 -B experiments/l7-c3-gate/summarize-cost.py <dossier>`. Les vérifications Git et SHA-256 sont documentées dans le répertoire de preuves.
- Aucun commit ni push tenté. La proposition de publication comporte deux patchs vérifiés, le pilote préexistant puis le dossier complémentaire avec son harnais et ses preuves. Le prototype C2 reste hors publication. L’index conserve son état de départ.
