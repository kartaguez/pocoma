> **Complément du 27-10-2026 :** les réserves post-audit R-AUD-I3-01/02 sont corrigées et vérifiées par quatre gates frais sur `3ee575940669feb56b73b10122b5a3406bce6a08`. Voir le [rapport dédié](L2_I3_Post_Audit_Corrections_Report.md) et ses archives. Le rapport historique ci-dessous conserve ses SHA/preuves ; aucun PASS global L2 ni garantie de ressources I5 supplémentaire.

# L2 / I3 — Consumption Lifecycle : rapport d’exécution

**27 octobre 2026 — I3 CLOSED / LOCAL BOUNDED PASS — AUDIT INDÉPENDANT CODE + PREUVES PENDING.**

Mandat humain : implémenter I3 après audit indépendant du plan **PASS WITH MINOR AMENDMENTS**, avec R-I3-01/02/03. Référence approuvée et distante au départ : `bb33b56dc86a8a1d31c577f983d7ea1a07bc8128` ; baseline I2 : `03f0725c690bcbd9748d3ea59b62136e1a911e05`. Le delta I2→plan est documentaire. Le checkout principal `feat/runtime-monolith` à `90a3e9bc` et ses travaux préexistants, ainsi que la branche locale cible occupée par un ancien worktree, sont préservés. Livraison isolée : `/private/tmp/pocoma-v3-i3-execution`, HEAD détaché. Push autorisé vers `origin/feat/v3-architecture`, sans force, seulement après vérification du distant.

## Périmètre et architecture

Engine orchestre acquisition de partition, renouvellement, réservation M_C/M_W, acquisition conditionnelle, une seule tentative R1/R9, classification après résolution, cession éventuelle, puis restitution du ticket. Une acquisition n’exécute jamais le métier. Une tentative ne reboucle jamais sous le même token. Les échéances et transitions restent durables dans les quatre tables I1 ; aucun nouvel état/table/index/migration/séquence. R1, R9, `SpringAttemptTransactions` et `ExecutePotCommand` restent inchangés dans leurs protocoles et leur code productif.

Le résultat `Acquisition(claims, examinedCount)` rend explicite le préfixe réellement examiné : r après l’opération technique committée, zéro sans place ou ownership. La liste des seuls gains était insuffisante pour appliquer R10 à une page partiellement couverte par capacité. Ce record de résultat évite un curseur fictivement avancé en I5 sans implémenter sa navigation. Un UNKNOWN ne retourne aucune preuve d’avance. Les tests vérifient r=2/gain=1, reliquat intact, capacité pleine=0 clé/SQL et ownership refusé=0.

Nouveaux types productifs : `PartitionLease` (valeur DB), `LifecycleSettings` et son RetryPolicy immuable (bornes cohérentes), `WorkerCapacity` (deux plafonds atomiques et tickets idempotents), `PartitionOwnershipStore` (lease/routage), `AttemptFailureClassifier` (cause indépendante de la résolution), `JdbcPartitionOwnershipStore` (SQL), `V3ConsumptionLifecycleComposition` (fabrique explicite inerte). Le store de slots existant porte acquisition/renewal/cession/inspection. Les valeurs auxiliaires sont imbriquées dans les ports ; le handle est imbriqué dans l’Engine. Aucun framework/coordinateur/manager/pool/scheduler ajouté. Le renouvellement ignore les places métier et l’ownership de partition. Les paramètres partagés sont injectés, jamais alloués implicitement par chaque Engine productif. Une acquisition de lease dont le résultat physique est UNKNOWN ne retourne aucune propriété utilisable : l’incarnation incertaine est abandonnée et la réacquisition normale attend sa libération/expiration, avec un token neuf. I3 ne maintient pas de registre de recovery de leases ; ce choix conservateur peut retarder l’accès à la partition, sans droit de démarrer supposé.

`AcquiredClaim` contient clé/token, ticket, Engine d’origine et contrôle local à usage unique ; aucune copie d’état AVAILABLE/Q, compteur ou échéance durable. Le suivi `pending` ne conserve que des réservations en cours d’appel ou incertaines, majorées par M_W. Le corps I2 devient privé ; les adaptations I2 sont des helpers exclusivement de test, avec réservation réelle et NON_COUNTABLE explicite. Les anciennes cessions SQL du harnais L1 restent des stimuli historiques, distincts des opérations productives I3.

## Fichiers et surface productive

La liste intégrale de livraison sera dans `docs/testing/evidence/L2-I3/Delivery-files.txt` ; l’inventaire des entrées SQL et l’absence de delta POM/migrations/owner sont archivés dans `Architecture-inventory.json`.

| Module | Fichiers productifs modifiés ou créés |
|---|---|
| `engine-consumption` | `ConsumptionEngine.java`, `PartitionLease.java`, `LifecycleSettings.java`, `WorkerCapacity.java`, `port/ConsumptionSlotStore.java`, `port/PartitionOwnershipStore.java`, `port/AttemptFailureClassifier.java`, sous `engine/consumption/v3` |
| `infra-persistence-primary-jpa` | `v3/consumption/JdbcConsumptionSlotStore.java`, `JdbcPartitionOwnershipStore.java` |
| `composition-runtime-spring` | `runtime/command/v3/V3PotCommandComposition.java`, `runtime/consumption/v3/V3BootstrapGuard.java`, `V3ConsumptionLifecycleComposition.java` |

Les tests sont dans Engine (capacité/classification), PRIMARY (banc et 41 scénarios WRITE/READ + helper I2), READ (ACL effectives), worker Command (composition/ressources + helper I2), et architecture (règle négative sans contournement par claim nu). Les seuls tests historiques adaptés sont les handoffs de `L1CommandCompatibilityHarness`, `L2ManifestProof` et `V3PotCommandCompositionPostgresTest`. Les suites I1/bootstrap/monolithe existantes sont exécutées sans assouplissement. Scripts SQL : bootstrap, grants et réactivation ; collecte : `verify-v3-l2-i3.py`, six tests `test_i3_evidence.py`. Documentation : plan/rapport/manifeste I3, couverture L2, conception L2 et plan directeur V3, plus les archives et reçus.

## Réserves de l’audit

**R-I3-01.** Un ticket incertain n’est rendu qu’après fin locale effective **et** résolution physique ou observation technique suffisante de l’ancienne protection. `settlePending()` saute tout appel local actif. L’inspection prend le verrou du slot sans DML ; une ligne sautée conserve la place. Une ligne verrouillée avec succès peut résoudre la protection, sans inférer succès/échec métier ni céder. Après une tentative, l’absence exige R9 COMPLETED cohérent ; une absence après acquisition seule ne prétend pas prouver un effet. Timeout, expiration et `Future.cancel(true)` ne libèrent rien. Les tests laissent réellement une transaction finale PostgreSQL ouverte après échec du commit/rollback local et observent le verrou indépendamment ; ils contrôlent aussi la continuation locale après résolution physique, les ACK perdus, R9 incohérent et la double restitution.

**R-I3-02.** Lots bornés, SKIP LOCKED, même token, heure DB post-lock, jamais de résurrection. Le banc sature quatre accès métier et un contrôle, puis établit trois emprunts concurrents de renouvellement dans le pool unique de huit connexions, avec PID distincts. S2 committe pendant que S1 reste verrouillé. Ce harnais maîtrise son inventaire ; le code livré ne maîtrise pas encore tous les emprunteurs/exécuteurs d’un Worker actif. La fabrique vérifie M_W+4 déclaré, le maximum Hikari lorsqu’exposé et les droits des sources réellement injectées ; cela **ne réserve pas** des connexions. La garantie intégrée sous saturation, les cadences et les exécuteurs restent INCONCLUSIVE avant I5. Aucun pool/transaction manager/ordonnanceur supplémentaire.

**R-I3-03.** Séparation lisible : claim durable = droit fenced ; ticket = capacité locale ; Engine = seule orchestration. Pas de registre des tentatives SQL, de machine à états supplémentaire ou de recovery concurrent. Les seuls booléens du handle protègent le démarrage unique et la fin locale ; l’inspection technique sert uniquement les réservations incertaines.

## Droits et écarts minimaux

Scripts livrés : bootstrap avec ACCESS EXCLUSIVE du contrat, grants post-bootstrap ciblés, réactivation CAS sur C/id/QUARANTINED avec timeouts et assertions de cible/rôle. Le runtime n’a ni INSERT de slots/checkpoints, ni mutation du routage, ni DDL ; le rôle de réactivation n’est jamais hérité. Le DELETE runtime reste limité par les entrées R1 de confiance : une ACL de table ne distingue pas une completion d’un SQL arbitraire contournant le port. Cette limite n’est pas dissimulée.

Le test réel de réactivation a démontré SQLSTATE 42501 dans le trigger invoker existant : il lit la famille du contrat. Ajout minimal documenté avant correction : SELECT(consumption_id,family) au rôle administratif de réactivation. Aucun droit de mutation ni SECURITY DEFINER. Les vrais grants Command couvrent explicitement les écrivains/triggers I2 ; Identity demeure une table de fixture avec son seul droit SELECT/UPDATE nécessaire.

Les nouveaux bancs PRIMARY restent dans le package de test `v3` afin de réutiliser le proxy filaire existant sans dupliquer/exposer son API. Le motif `i3_c03_*` du plan désigne une famille : A…L sont exigés individuellement, aucun faux test `i3_c03_`. Les méthodes de capacité à usage unique se trouvent dans le test Engine de classification, raccord exact dans le manifeste.

## Vérification déclarée et preuves

Impact attendu et observé : Engine/ports/capacité, deux adaptateurs SQL techniques, composition/gardes et scripts administratifs ; pas de modification des propriétaires/suffixe/métier ni d’autre runtime. Primaire **COMMAND** ; secondaires canoniques **NONE**. Preuves directes **infra-persistence-read-jdbc**, **runtime-monolith**. Gate global **architecture-tests REQUIRED** à clôture. Full reactor **NOT REQUIRED / NON EXÉCUTÉ** : aucune restructuration Maven, aucune clôture majeure L2/intégration main. Aucun franchissement de slice inattendu. DB **CURRENT V3 neuf**, migrations existantes uniquement ; les tests historiques transitifs exécutés par les gates seront signalés séparément.

Commandes de clôture, depuis `app/`, sans filtre/exclusion :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-persistence-read-jdbc -am test
./mvnw -pl runtime-monolith -am test
./mvnw -pl architecture-tests -am test
```

Collecteur : [verify-v3-l2-i3.py](../../scripts/testing/verify-v3-l2-i3.py). Inventaire figé : [manifeste I3](L2_I3_Proof_Manifest.json). Il exige méthodes exactes une fois par gate, XML frais sans skip obligatoire, sous-variantes WRITE/READ et coupures filaires présentes dans les traces fraîches, code/scripts propres au SHA d’entrée ; les six tests du collecteur refusent absence, skip, doublon, XML échoué, trace périmée et sous-variante manquante. Les réexécutions ne multiplient pas les garanties.

Les diagnostics sont conservés, notamment : course du harnais utilisant la cancellation du Future comme fin ; mauvais package de fixture ; manager JDBC de test laissant NESTED autorisé par défaut ; droits de lecture manquants du rôle de réactivation ; tentative d’expirer une ligne ownership encore libre. Ces échecs ne valent aucun PASS et les corrections ne réduisent aucun oracle. Les compilations avec `-DskipTests` sont des diagnostics, jamais des gates.

Revue des preuves après le premier gel `e9a6848f` : les gates COMMAND (1497/0/0/1, 249 méthodes), READ (361/0/0/0) et MONOLITH ont passé leur inventaire d’alors. Ils sont conservés comme diagnostics de cette révision et **remplacés pour la clôture** : le stimulus C05-K interrompait trop tôt, avant la préparation. Correction du test : rollback physique RO réellement confirmé, puis arrêt injecté dans la classification avant toute cession. C05-B ajoute aussi une contrainte réellement différée au commit ; le collecteur exige les deux nouvelles traces WRITE/READ. Aucun code productif n’est changé par cette correction de preuve, aucun oracle réduit.

Le second gel `bdee72b0` a été rouvert après constat de cette lacune de retour d’acquisition R10. La campagne architecture en cours a été arrêtée et conservée comme diagnostic incomplet, jamais comme PASS final. La correction est limitée au résultat de l’Engine, à ses appelants de tests et à leur preuve de préfixe ; aucun algorithme I4/I5 ni franchissement de slice. Tous les gates finaux repartiront sur le même nouveau SHA.

Le gate architecture complet `e67011af` a ensuite exécuté 1 761 tests : **un échec**, la liste blanche historique des valeurs de ports dans `V3FoundationArchitectureTest`, qui ne connaissait pas PartitionLease/RetryPolicy. Correction minimale du test d’inventaire annoncée avant modification : deux noms précis ajoutés, les implémentations Engine/Spring/JDBC/JPA et tous les exemples négatifs restant interdits. Cette adaptation de test d’architecture est le seul fichier ajouté au tableau prospectif du plan ; aucune frontière productive ni dépendance Maven nouvelle. Le gate échoué et ses XML/SQL restent archivés ; tous les gates finaux sont rejoués au SHA intégrant la correction.

SHA final de code/tests/scripts vérifié : **`1d7cf51c13671c7c85a5c9c1281f526be2b2abfb`**. Commits créés avant publication documentaire :

- `e9a6848f0ad98a6057942f92bf4e407eef172ea5` — primitives, capacité, cession, droits, composition et preuves initiales.
- `bdee72b02d65547e898fa7e09f177c7c216a1515` — stimuli réels de crash après rollback et rejet différé.
- `e67011af0b41077d7a67b23f73eed7ec6a450918` — résultat `Acquisition` avec préfixe examiné committé.
- `1d7cf51c13671c7c85a5c9c1281f526be2b2abfb` — inventaire précis des deux valeurs pures des ports, négatifs conservés.

Le commit documentaire final contient ce rapport, le manifeste enrichi et les preuves ; il ne modifie aucun octet `app/` ou `scripts/`. Son SHA exact est fourni au reçu de publication/Git HEAD, sans circularité d’auto-inscription dans son propre contenu.

| Gate final sur le même SHA de code | Tests | Échecs/erreurs | Skips | Méthodes obligatoires contrôlées | Verdict |
|---|---:|---:|---:|---:|---|
| COMMAND | 1497 | 0 | 1 | 249 | PASS |
| READ | 361 | 0 | 0 | 1 | PASS |
| MONOLITH | 1266 | 0 | 0 | 246 | PASS |
| ARCHITECTURE | 1761 | 0 | 1 | 270 | PASS |

Les 41 méthodes PostgreSQL lifecycle fournissent chacune une trace WRITE et READ : 82 variantes bornées, sans les compter comme 82 méthodes JUnit. Les cinq tests de composition, les six tests Engine, la preuve ACL READ, les négatifs d’architecture et les groupes I2/L1-C/F/F2 requis sont présents et passent. Les exécutions répétées entre gates ne sont pas additionnées comme garanties nouvelles.

Le seul skip, dans COMMAND et architecture, est `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, essai historique V2 hors obligations I3. READ et MONOLITH n’ont aucun skip. Aucun test absent, ignoré ou prévisionnel ne vaut PASS.

Les quatre commandes exactes sont dans [Gate-index.json](../testing/evidence/L2-I3/Gate-index.json). Archives XML/logs/SQL/barrières/horloges/paramètres et empreintes : [dossier de preuves](../testing/evidence/L2-I3/README.md). L’[audit des archives](../testing/evidence/L2-I3/audit-archives.py) a vérifié 736/85/708/790 artefacts respectivement, leurs empreintes, les XML frais, les méthodes uniques, les marqueurs de sous-variantes, les entrées Git figées et l’absence de delta de code après le gel : [Integrity-check.json](../testing/evidence/L2-I3/Integrity-check.json). [Couverture par identifiant](../testing/evidence/L2-I3/Coverage-by-ID.json) : PASS des variantes primitives et contribution I3, statut global INCONCLUSIVE.

Contrôles complémentaires exécutés : `python3 -B docs/v3/check_l2_manifest.py`, `python3 -B scripts/v3/check-routing-vectors.py` (21 vecteurs indépendants), `python3 -B -m unittest scripts/testing/test_i3_evidence.py` (six tests), `git diff --check`, vérification des entrées et de leurs SHA256 via Git, recontrôles du distant et des worktrees. Le manifeste mécanique conserve 253 identifiants normatifs, 380 lignes de preuve, 324 obligations L2, 56 reports et 33 oracles sans orphelin ; ce contrôle ne remplace pas la revue sémantique.

Les suites historiques transitives ont effectivement rejoué leurs migrations V2 de test (notamment PRIMARY V1–V31/READ V1–V14 selon les modules) et des fixtures L0. Ce coût provient des fermetures Maven imposées ; le bootstrap I3 reste limité aux migrations V3 neuves WRITE et READ réelles. Aucun environnement existant n’a été migré ou purgé. Les logs conservent les avertissements historiques de pools de test après arrêt de conteneurs et de sortie tardive du JVM Surefire ; aucun scénario I3 en timeout ni échec n’est requalifié en PASS.

Commandes de diagnostic effectivement exécutées depuis la racine, sauf la première depuis `app/` (les relances et échecs sont conservés dans `diagnostics/`) :

```sh
./mvnw -pl engine-consumption -am test
./app/mvnw -f app/pom.xml -pl engine-consumption -am test
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am test -DskipTests
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=ConsumptionLifecyclePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am test -Dtest=ConsumptionLifecyclePostgresTest,ConsumptionLifecycleCompositionPostgresTest,V3PotCommandCompositionPostgresTest,ReadConsumptionLifecyclePrivilegesPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=ConsumptionLifecyclePostgresTest#i3_capacity_uncertainty -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl architecture-tests -am test -Dtest=ConsumptionLifecyclePostgresTest,ConsumptionLifecycleCompositionPostgresTest,V3PotCommandCompositionPostgresTest,ReadConsumptionLifecyclePrivilegesPostgresTest,WorkerCapacityTest,ConsumptionFailureClassificationTest,V3ConsumptionEngineArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am test -Dtest=ConsumptionLifecyclePostgresTest#i3_capacity_uncertainty,ConsumptionLifecycleCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=ConsumptionLifecyclePostgresTest#i3_c05_b+i3_c05_k+i3_c03_g -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl infra-persistence-primary-jpa -am test -Dtest=WorkerCapacityTest,ConsumptionFailureClassificationTest,ConsumptionLifecyclePostgresTest#i3_acquisition_partial -Dsurefire.failIfNoSpecifiedTests=false
./app/mvnw -f app/pom.xml -pl architecture-tests -am test -Dtest=V3FoundationArchitectureTest,V3ConsumptionEngineArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
```

Ces sélections et compilations ne sont jamais les commandes de clôture ; les quatre gates finaux sont sans filtre, au SHA unique ci-dessus. Aucun `-amd`, aucun `./mvnw test` full reactor. Le gate architecture global est exécuté parce qu’il est requis, pas ajouté à chaque boucle par défaut.


## Paramètres, horloges et limites temporelles

Le pilote conserve P=4, B=32, M_C=2/M_W=4, N=3, retryDelay=30 s, lease=30 s, claim=60 s, cadence envisagée=10 s. Le harnais déclare ses variations avant stimulus : délai zéro pour les campagnes de budget, 300 ms pour retryAfter, échéances DB de 200/300 ms pour les courses, N=1/3/6 et capacité 1 pour l’incertitude. Les paramètres complets et versions sont dans `Environment-and-inputs.json`, les valeurs par essai dans les traces. Les durées SQL exigent une précision microseconde exacte ; configuration invalide refuse avant tentative.

Les unités techniques appliquent lock=500 ms, statement=2 s, transaction PostgreSQL 17=3 s par défaut ; les callbacks R1/R9 conservent le timeout I2 de 60 s. `settlePending` examine au plus min(B,M_W) réservations et au plus une observation R9 par absence : aucune boucle de retry ou attente d’expiration. Le temps d’accès à la connexion dépend de la datasource injectée ; le harnais Hikari contrôle 1 s, les autres fixtures déclarent leurs paramètres JDBC. Une taille de pool n’est ni une réservation ni, à elle seule, une borne E2E. La composition exhaustive des emprunteurs, dispatch et délais d’I5 reste à qualifier sous les hypothèses de disponibilité annoncées. Aucun timeout n’est interprété comme preuve de fin PostgreSQL.

## Portée de certification et arrêt

Les méthodes i3_c03_a…l contribuent aux primitives CON-03 ; la moitié Discovery de C03-B et l’instrumentation/boucle intégrée de C03-L restent I4/I5. Les méthodes i3_c05_a…m et compléments prouvent retry, budget, Q/admin ; les analogues READ restent techniques, sans rejet métier/projection réelle. Le pilote WRITE utilise Command/Pot réel et Identity fixture. Le contrat d’intention et les faits sont rechargés après vraie collision Pot ; le rejet obsolète est terminal normal et ne consomme pas le budget.

Les mesures SQL 100/1 000/10 000 slots par partition, mélange libre/actif/expiré/retry/Q et B=32 conservent plans ANALYZE/BUFFERS/WAL et rollback des sondes DML ; aucune qualification PERF-01/SLO ni coût physique constant revendiqué. Les quatre tables/index/triggers existants sont conservés.

Discovery, softCap réellement appliqué aux admissions, non-réenrôlement Q, navigation/équité entre tours, cadence et ressources de toutes les boucles, producteurs Identity/Invitation, projections et HTTP restent reportés aux lots prévus. Aucun report n’est transformé en PASS. **L2 global INCONCLUSIVE, non ACCEPTED ; G1/G2/G3 non franchis.** Arrêt après I3, audit indépendant code+preuves puis nouvelle décision humaine avant I4/I5. Aucun déploiement/activation production.


## Verdict et publication

**PASS local borné I3.** Toutes les preuves du périmètre livré et les quatre gates sont exécutés et archivés, avec les limites ci-dessus. Les trois réserves R-I3-01/02/03 sont traitées ; R10 reçoit le résultat de préfixe nécessaire à I5, sans navigation prématurée. Scope productif confirmé, aucun franchissement inattendu ; le fichier d’inventaire d’architecture supplémentaire est documenté, ses négatifs restent actifs. Full reactor non requis/non exécuté pour cette étape bornée.

La publication autorisée vise `origin/feat/v3-architecture`, sans force. Le distant est reconfirmé avant push ; la concordance SHA est vérifiée après. Le checkout utilisateur et les worktrees préexistants sont préservés ; la livraison reste dans le worktree isolé, sans déplacement de leur branche locale. Les reçus de vérification Git donnent l’état exact de livraison. Audit indépendant du code et des preuves à attendre, puis nouvelle décision humaine. **Arrêt après I3 : aucune poursuite I4/I5, aucune activation production, aucun ACCEPTED L2 ni G1/G2/G3.**
