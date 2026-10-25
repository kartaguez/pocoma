# L1-C — Transactions, CAS et reprises : checkpoint d’audit

2026-10-25 — Dépôt `kartaguez/pocoma`, branche de publication `feat/v3-architecture`.

**L1-C : PASS technique borné sur toutes les méthodes internes obligatoires. Les trois gates Maven terminent BUILD SUCCESS. Couverture globale complète consommateurs/architecture INCONCLUSIVE : un skip exploratoire V2 conservé, aucun skip obligatoire C. Audit indépendant C attendu ; aucune acceptation définitive, certification V3 complète ni activation. L1-D/E ne sont pas commencés.**

## 1. Références et autorisation

Le GO humain du 2026-10-25 confirme les revues indépendantes A/B acceptables pour poursuivre et autorise C seulement. Il ne transforme pas les validations A/B en certification V3. Référence publiée et tête distante vérifiée avant implémentation : `56d658f9fd5313db2744fa11ff36699f4376aacb`. Plan approuvé conservé : `8221692287420cd69feebd170df7c9ea6c279fdd`, [plan L1](L1_Design_and_Verification_Plan.md), notamment §§15/18/21–24. Aucun arbitrage C-01/C-02/N-01 rouvert, aucune SPEC modifiée.

**Commit initial C : `be4c0d56103e55f45fd70e92818d947a51b374b9`** (`feat(v3-l1-c): execute durable Pot transitions with fenced CAS and claim recovery`). Deux corrections de preuve ont suivi :

| SHA | Contenu |
|---|---|
| `be4c0d56103e55f45fd70e92818d947a51b374b9` | Implémentation C, tests, contrat et collecteur |
| `2494a4e17a212281ed7d8602608b3d6b8ab11bcf` | Isolation de la fixture JPA C hors du scan B |
| `2825f7b4c90e6b0c4b064e1166de6dd9a93f2bcf` | Oracle de temps discriminant contre une lecture à l’instant de début de transaction |

**SHA final d’entrées exécutables testé : `2825f7b4c90e6b0c4b064e1166de6dd9a93f2bcf`.** Tous les gates retenus repartent de ce SHA avec targets frais. Les résultats précédents restent archivés, marqués remplacés. Le constat de publication et sa provenance figurent au §10 ci-dessous.

Le checkout initial est sur `feat/runtime-monolith` à `90a3e9bc`, avec des modifications préexistantes ; le worktree qui porte la branche locale V3 est à `ddca2827`, avec ses documents indexés. Ils sont préservés. Notre travail est réalisé dans le worktree détaché neuf `/private/tmp/pocoma-v3-l1-c`, issu du SHA distant exact. Aucun reset, stash, déplacement de branche locale ou fichier préexistant intégré.

## 2. Fonctionnalités et architecture réalisées

Le point d’entrée `ExecutePotCommand.execute(requestId,claimToken)` exécute une tentative complète depuis la demande durable : préparation sélective, résolution de l’acteur, transition pure A, protections finales, éventuel effet B, terminalisation/résultat/événements/slot indivisibles, puis résolution physique. Toutes les créations/modifications/suppressions Pot et enfants prises en charge par A sont orchestrées, avec versions locales indépendantes et rationnels exacts. Aucun nouvel algorithme métier dans l’engine.

Les contrats explicites sont regroupés sous `engine-write-pot.port.execution.v3` : intention admise immuable, source/slot/résultat/publication, autorité EUI, et frontières transactionnelles de cette exécution Pot. `PotHistoryPort` et `PotRevisionPort` B sont réutilisés. La proposition typée est conservée dans une closure privée de l’engine ; seul son ensemble effectif est écrit. Pas de registre universel de mutations, nouveau module, POM modifié, framework générique de commandes/transactions ou Service par commande.

Les trois nouveaux composants d’infrastructure sont `PotCommandJdbcTransactions`, `JdbcPotCommandStore`, `PotCommandJson`. La composition réelle `V3PotCommandComposition.assemble` injecte une seule source WRITE et un JpaTransactionManager cohérent, avec l’autorité explicite. Son test PostgreSQL appelle effectivement cette factory. Aucun composant n’active automatiquement ce chemin dans les runtimes V2 ou HTTP.

Le [contrat transactionnel réalisé](L1_C_Transaction_Contract.md) est autoporteur : ordre des protections, autorités, inventaire de tous les writers, interfaces d’intégration, reprise et limites. Les sources/domaines A ne sont pas modifiés. Le contrat historique B et les rapports A/B restent consultables sans réécriture de leurs preuves.

## 3. Protocole physique effectif

1. Snapshot REPEATABLE_READ : charger la demande et tous les faits utiles à la même V, observer l’autorité Identity et produire la décision pure. L’observation de la requête reste constante.
2. Transaction finale READ_COMMITTED : verrouiller exclusivement le slot ; vérifier token/état/échéance avec `clock_timestamp()` dans une requête distincte **après** l’attente. Aucune dépendance à un ownerToken de partition.
3. Recharger l’intention immuable ; protéger l’autorité EUI et comparer son témoin ; protéger le Pot, y compris son absence, puis comparer V à celle de la préparation.
4. Si Changed : CAS conditionnel du curseur V→V+1 déclenché par l’insertion de métadonnée, puis écritures typées B dans cette même transaction. `writer_xid xid8` reste contrôlé. Si no-op/rejet : aucune écriture/version métier ; mêmes protections jusqu’au commit.
5. Écrire résultat/TERMINAL/suppression du slot et les événements minimaux. Comparer les changedComponents à l’ensemble exact des révisions réellement écrites dans les huit tables à la candidate. Flush JPA et vérification des contraintes différées B avant séquencement.
6. Fermer le propriétaire aux writers supplémentaires. Suffixe dense POT → COMMAND_COMPLETION, compteurs transactionnels et positions dans la même unité. Aucun nouveau verrou métier après ceux des compteurs. Les dernières contraintes de complétude sont des lectures sans acquisition de verrou métier.
7. Confirmer le commit ou rollback total. Une proposition pure n’est jamais déclarée durable par présomption.

L’autorité globale du Pot protège toutes ses dépendances : activité, versions des composantes, rattachements et recherche exhaustive d’usage, incluant créations/modifications financières concurrentes. Le fait Identity est externe à V et conserve **sa propre protection**, pour Changed comme pour no-op/rejet. L’absence de Pot est protégée par verrou transactionnel avant comparaison de l’existence courante ; elle n’est pas déduite d’un ancien snapshot après une création concurrente.

## 4. Reprises, erreurs et atomicité

Une collision produit rollback puis comptabilisation conditionnelle d’un échec confirmé ; elle ne devient pas directement un rejet métier. La nouvelle acquisition recharge tous les faits et recalcule avec l’observation initiale. Il n’existe aucune boucle sous un même claim. Les issues distinguent complétion, reprise temporisée, quarantaine, claim perdu et récupération indéterminée ; les causes distinguent collision, incident technique, faits incohérents et commit incertain.

N et retryAfter sont configurables ; le N-ième échec confirmé conserve le slot QUARANTINED et la source PENDING. Ancien token, expiration, crash et issue de commit indéterminée ne consomment pas de budget arbitraire. Les autres commandes, y compris sur le même Pot, progressent. La réactivation testée est une fixture administrative explicite et atomique, pas un endpoint livré.

Le propriétaire garde l’identité effective ConnectionHolder/EntityManagerHolder, refuse l’ambiant, détecte une transaction indépendante injectée et condamne les erreurs interceptées. **Évolution B minimale sémantique :** chacune des douze primitives de révision reçoit une garde qui marque rollback-only en cas d’erreur, même interceptée. Le formatage des corps entourés explique le volume du diff ; les opérations SQL et contraintes V1/xid8 ne sont pas contournées. Les chemins JDBC et JPA sont revérifiés.

Un refus SQL au commit résout par rollback ; une erreur transport au COMMIT demeure indéterminée malgré un callback de manager pouvant annoncer rollback. Le proxy PostgreSQL coupe effectivement avant COMMIT ou retient la réponse COMMIT/ReadyForQuery déjà reçue du serveur. La récupération relit demande/résultat/slot ; aucune répétition aveugle et aucun état UNKNOWN durable. Les mêmes deux fautes sont testées sur la cession d’échec : compteur durable une fois ou zéro selon la résolution réelle.

## 5. Scénarios, variantes et oracles

Le [contrat, inventaire obligatoire](L1_C_Transaction_Contract.md#obligatory-methodvariant-inventory) associe toutes les méthodes aux variantes et oracles SPEC-10 applicables ; le [manifeste](L1_Proof_Manifest.json) fixe leur présence dans les gates.

Nouvelles preuves C : **24 méthodes PostgreSQL**, **6 unitaires**, **1 composition PostgreSQL réelle**, **3 architecture**. Les méthodes A/B et les cinq gardes A-01 sont conservées. Les résultats de gates répétés ne sont pas additionnés comme tests uniques.

La matrice comprend notamment : même Pot/cible ; composantes distinctes compatibles ; Financials/Weight ; trois suppressions contre mutation ; rattachement contre droits ; autorité EUI révoquée ou négative devenue valide ; no-op/rejet périmé ; CAS suivi de réacquisition compatible ou obsolète ; même demande deux finaliseurs ; deux intentions équivalentes sous requestIds distincts ; cinq frontières de panne ; refus différé au commit ; erreurs JPA/JDBC interceptées ; manager incohérent ; transaction indépendante ; N-1 puis succès ; N puis quarantaine ; ancienne cession tardive ; claim expiré après attente réelle ; échéance franchie après protection ; séquence dense sous commit/rollback ; commits et cessions d’issue inconnue ; corruption empêchant tout faux fait négatif.

**D02-S transactionnel : huit variantes contrôlées**, création/modification Financials × payeur/participant × deux ordres de validation. Si Expense gagne : suppression refusée sur référence active ; si suppression gagne : mutation Expense refusée sur Header supprimé. Première proposition perdante annulée, puis nouvelle acquisition et recalcul. Assertions indépendantes sur les données durables : aucune Expense active référençant un Shareholder supprimé ; seules composantes effectives et unique V avancent ; anciennes Financials/versions restent exactes. Suppression d’Expense libérant la référence également démontrée.

Les entrelacements utilisent latches et observation `pg_stat_activity`/`pg_blocking_pids`, sans sleep comme mécanisme de preuve. Les XML capturent l’issue des assertions ; les snapshots de banc contiennent tables métier/histoire, résultats, événements/positions et slots expurgés des tokens, avec PID/XID JPA/JDBC et chronologies. Chaque variante avec nouveau schéma collecte son état avant remplacement du banc.

## 6. Vérification déclarée, modules et limites de portée

Déclaration enregistrée avant code : COMMAND primaire, EVENT + PROJECTION secondaires ; impact attendu engine-write-pot/ports, writers V3 de persistance et composition explicite, avec raccord borné Command. Global architecture REQUIRED par le plan §18/24 (transactions/fencing/contrats). **Full reactor NON requis et NON exécuté** : C n’est pas la clôture majeure L1 ; G-FULL reste dû à E. Aucun `-amd`.

Impact observé : `engine-write-pot`, `infra-persistence-primary-jpa`, `composition-runtime-spring` ; tests dans ces modules, `runtime-command-consumption-worker`, `architecture-tests` ; manifeste/collecteur et documentation. Aucun changement de production EVENT/PROJECTION/BINDING/WEB/LKV, domaine Identity, moteur générique, HTTP ou matérialisation READ. La fermeture Maven des trois slices ne signifie pas que tous ses modules ont changé. **Franchissement inattendu : aucun.**

Le raccord applicatif est directement le point d’entrée de l’engine-write-pot et la factory de composition. Aucun forwarding Service supplémentaire dans engine-consume-command : il ne ferait que déléguer ; le moteur V2 ne possède pas encore les contrats de claim/intention V3. Ce choix applique §18.5f/6 du plan : banc réel et raccord applicatif, sans modifier discrètement le cycle global. Les writers V2 et les exceptions ArchUnit nominatives associées restent historiques et à retirer lors de conversion de leurs consommateurs, sans nouvelle exception A-01 ni couche de compatibilité BindingId.

Base métier CURRENT V3 neuve : `db/v3-pot/migration` B inchangé + extension explicite `db/v3-pot-execution/migration/V2__command_execution.sql`. Aucun bootstrap L0 transformé en schéma métier, aucun ddl-auto, aucune baseline V2 TRUSTED supposée. Les suites non filtrées peuvent exécuter PRIMARY V1–V31 et READ V1–V14 dans leur fermeture ; le dossier consigne ces exécutions. Aucune campagne HISTORICAL séparée.

## 7. Gates et commandes réellement exécutées

Les trois gates ci-dessous ont été exécutés depuis `app/` par le collecteur sur **`2825f7b4c90e6b0c4b064e1166de6dd9a93f2bcf`**, avec compilation et XML frais. Les assertions de toutes les méthodes obligatoires sont satisfaites, présentes exactement une fois et sans failure/error/skip.

| Gate | Tests / failures / errors / skips | Méthodes obligatoires | Verdict obligatoire | Couverture complète |
|---|---|---|---|---|
| c-persistence | 886 / 0 / 0 / 0 | 63 | PASS | PASS |
| c-consumers (COMMAND + EVENT + PROJECTION) | 1270 / 0 / 0 / 1 | 64 | PASS | INCONCLUSIVE |
| c-arch (global non filtré) | 1475 / 0 / 0 / 1 | 72 | PASS | INCONCLUSIVE |

Le skip est exactement `com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, conditionné par la propriété exploratoire V2 `step3b.measure`. Sa présence et son identité sont contrôlées dans les XML bruts ; il n’est jamais requalifié PASS. Les **34 nouvelles méthodes C** (24 PG + 6 unitaires + 1 composition + 3 architecture) passent. Le gate architecture couvre aussi les 14 méthodes A, 19 B et 5 A-01, soit 72 obligatoires. Les résultats des répétitions ne sont pas additionnés comme tests uniques.

Commandes Maven exactes :

```bash
./mvnw -pl infra-persistence-primary-jpa -am test
./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```

Support : `python3 -B -m unittest discover -s scripts/testing -p test_summarize_v3.py` : 7 tests PASS. `git diff --check` exécuté. Les diagnostics ciblés ci-dessous sont des feedbacks, pas des substitutions aux gates :

```bash
./mvnw -pl infra-persistence-primary-jpa -am test -DskipTests
./mvnw -pl engine-write-pot -am test -Dtest=ExecutePotCommandTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=CommandExecutionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=CommandExecutionPostgresTest,ExecutePotCommandTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl infra-persistence-primary-jpa -am clean test -Dtest=CommandExecutionPostgresTest,ExecutePotCommandTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl architecture-tests -am test -Dtest=V3PotExecutionArchitectureTest,V3FoundationArchitectureTest,V3PotCommandCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl architecture-tests -am test -Dtest=CommandExecutionPostgresTest,V3PotExecutionArchitectureTest,V3FoundationArchitectureTest,V3PotCommandCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl architecture-tests -am test -Dtest=CommandExecutionPostgresTest,ExecutePotCommandTest,V3PotExecutionArchitectureTest,V3FoundationArchitectureTest,V3PotCommandCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

Certaines invocations initiales depuis la racine utilisaient `./app/mvnw -f app/pom.xml` ; elles sont équivalentes quant au projet sélectionné, et leurs logs bruts sont conservés. Le diagnostic ciblé passe toutes les méthodes C et A-01 avant figer le commit initial. Un diagnostic supplémentaire `./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=CommandExecutionPostgresTest#c14ClaimExpiryAfterActualLockWaitAndOldTokenNeverFinalizes -Dsurefire.failIfNoSpecifiedTests=false` passe l’oracle temporel renforcé avant son commit final. Le premier gate de persistance a été FAIL (886 tests, zéro failure, une erreur B due au scan JPA englobant la fixture C). Après isolation, les gates sur `2494a4e1` étaient PASS technique (persistance 886/0/0/0 ; consommateurs 1270/0/0/1), puis ont été remplacés après le renforcement volontaire de l’oracle temporel. Le skip consommateurs est le scénario exploratoire V2 connu, aucun test obligatoire C. Les diagnostics initiaux ont identifié/corrigé la fixture qui tentait de rattacher un tiers, une observation Weight mal placée, la résolution JDBC d’un refus au commit et une ressource compilée périmée après déplacement SQL. Les appels sans accès au socket Docker ont échoué à l’environnement puis été relancés avec cet accès. Aucun diagnostic échoué n’est requalifié PASS ; aucun gate obligatoire échoué n’est ignoré.

## 8. Preuves, provenance A-02 et environnement

Le collecteur compare automatiquement **toutes** les entrées à Git avant et après chaque commande, supprime les targets, archive XML/logs frais, inventorie dépendances JAR et ressources/classes générées, versions outils et configuration externe non secrète. Les seules exclusions documentaires autorisées demeurent rapports A/B/C, canon, répertoires de preuves L1-AB/L1-C. Sources, ressources, tests, SQL, POM, scripts, manifeste et contrat technique restent comparés.

Après commit documentaire P, le contrôle automatique H→P est requis et ne peut autoriser que ces exclusions ; après push il relit la tête distante exacte et vérifie encore son identité et ses entrées contre H. Le constat final de publication ci-dessous enregistre son résultat. Cette preuve de checkpoint ne clôt pas A-02 pour les lots D/E futurs.

Environnement constaté : PostgreSQL **17.11**, image `postgres:17-alpine`, digest `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73` ; Maven **3.9.14**, Temurin **21+35**, Docker **29.8.2**, macOS aarch64. Les environment.json de chaque gate conservent versions/options effectives ; les dépendances résolues sont inventoriées par empreinte. Les trois gates finaux comparent **1 482 entrées** à Git avant/après, digest `386b74e99de4013d018795371002f570b9197a1636a5338d49689a923c2e3c0a`.

Les suites métier C appliquent uniquement les migrations V3 V1+V2 explicites ; les suites transitives PRIMARY V1–V31 et READ V1–V14 sont effectivement visibles dans les logs des gates. Aucun historique séparé ni baseline V2 n’a été déclaré certifié.

Preuves durables publiées hors `target/` :

- [Index de tous les gates retenus et remplacés](../testing/evidence/L1-C/Gate-index.json), [évaluation automatique et skip exact](../testing/evidence/L1-C/Gate-assessment.json).
- [Archive brute des trois gates finaux](../testing/evidence/L1-C/Gates-raw.tar.gz) et [SHA-256](../testing/evidence/L1-C/Gates-raw.sha256).
- [Archive des gates remplacés](../testing/evidence/L1-C/Superseded-raw.tar.gz) et [SHA-256](../testing/evidence/L1-C/Superseded-raw.sha256), incluant le premier gate FAIL.
- [Diagnostics conservés](../testing/evidence/L1-C/Diagnostics.tar.gz) et [SHA-256](../testing/evidence/L1-C/Diagnostics.sha256).
- [Populations d’architecture](../testing/evidence/L1-C/Architecture-populations.json) : 17 classes de ports de premier niveau et 99 classes de ports imbriqués (types de valeur imbriqués inclus).
- [Inventaire exact des fichiers](../testing/evidence/L1-C/changed-files.json), [worktrees préexistants préservés](../testing/evidence/L1-C/Preserved-worktrees.json).

Reproduction : extraire `tar -xzf Gates-raw.tar.gz`. Chaque répertoire nommé dans l’index contient `maven.log`, XML Surefire bruts, `summary.json`, manifeste attendu, `before.json`/`after.json`, sources Git inventoriées, environnement, JARs résolus et empreintes des classes/ressources générées. Le sous-répertoire `app/infra-persistence-primary-jpa/target/v3-pot-evidence/` conserve les catalogues B et snapshots C de chaque schéma/variante, états expurgés des secrets, PID/XID, temps discriminants et chronologies de barrières. Les archives suffisent à l’audit sans accès à un `target/` local.

## 9. Écarts, limites de certification et risques D/E

Aucune décision fonctionnelle nouvelle, aucune SPEC amendée, aucune contrainte SQL neutralisée. Évolution B tracée : empoisonnement des primitives et contrôle de l’identité physique lorsqu’elles participent à l’unité C. Extension SQL C séparée pour préserver le contrat/catalogue B.

Fixtures explicitement nommées : source PENDING et acquisition/claim initiaux, autorité Identity PostgreSQL, état métier initial avec membre déjà rattaché, et reset administratif attendu. Les contrôles de verrou sont physiques ; aucune fixture n’est présentée comme Registration/Link/Unlink/Invitation/Discovery certifiée. L’expiration contrôlée après protection dans c15 simule le passage d’échéance par donnée de banc, sans sleep ; c14 constate le véritable blocage et affirme avant libération `début transaction finale < échéance < horloge DB fraîche` ; une implémentation utilisant `now()` au début de transaction accepterait à tort ce cas.

A-01 : gardes existantes réexécutées et trois gardes C supplémentaires ; aucune exception nouvelle. A-02 : correspondance automatisée des entrées/publication au checkpoint, clôture globale due à E. I-01 : inventaire C complet dans le contrat, suivi transversal encore ouvert pour Identity/Consumption/READ et retrait V2.

Restent à D : matrice complète droits/finances et calcul intégral rationnel ; à E : intégration interne conjointe, A-02 final, ARCH et full reactor au jalon majeur. Les fournisseurs réels Identity et acquisition/Discovery doivent reprendre les contrats/verrous prouvés dans le banc. Aucun PASS global DOM/EVT/CON/SEC/READ, aucune preuve Bruno/k6/SLO ni disponibilité READ. Les coûts des historiques, des verrous EUI et de l’advisory lock ne sont pas qualifiés ; aucune promesse de performance.

## 10. Publication et arrêt

Le commit de livraison code + rapport + archives **`0a6742944ac9eb5f1452d9a590b798e52ce732dc`** a été poussé sans force sur `feat/v3-architecture`, après relecture de la tête distante initiale exacte `56d658f9fd5313db2744fa11ff36699f4376aacb` et contrôle de son ascendance. La relecture distante a retourné exactement `0a6742944ac9eb5f1452d9a590b798e52ce732dc`. Divergence de cette livraison avec origin : **0 / 0**.

Le [contrôle automatique de publication](../testing/evidence/L1-C/Publication.json) est **PASS** : seules les exclusions documentaires/probatoires autorisées diffèrent depuis `2825f7b4c90e6b0c4b064e1166de6dd9a93f2bcf`, et le digest des 1 482 entrées reste `386b74e99de4013d018795371002f570b9197a1636a5338d49689a923c2e3c0a`. [Préflight local](../testing/evidence/L1-C/Publication-preflight.json), [sortie du push](../testing/evidence/L1-C/Push.log), [préservation des worktrees](../testing/evidence/L1-C/Worktree-preservation-verdict.json).

Ce constat est enregistré par un commit documentaire de suivi. Le contrôle H→tête distante est répété après sa publication ; son SHA final et son verdict sont communiqués dans la livraison, sans prétendre inclure le propre hash d’un commit dans son contenu. Aucun gate n’est invalidé : aucune source, ressource, migration, test, POM, configuration ou script exécutable ne change. Le worktree de livraison conserve des répertoires bruts d’extraction non suivis sous le chemin de preuves exclu ; aucune entrée exécutable n’est sale. Les autres worktrees et leur index sont inchangés.

Un premier préflight de lecture a refusé une référence documentaire mal recopiée ; aucun push n’avait été effectué. Le préflight et les contrôles retenus lisent le SHA directement depuis Git. Aucun résultat de gate n’a été requalifié pour cette erreur de référence.

**STOP au checkpoint après publication C et collecte des preuves.** L’audit indépendant doit examiner le protocole réel, les faits jusqu’au commit, les reprises, les frontières physiques, PostgreSQL et la simplicité. L1-C ne sera pas déclaré définitivement accepté par autoévaluation ; aucun démarrage D/E.
