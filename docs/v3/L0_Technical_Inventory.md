# V3-6 L0-A — Inventaire technique contradictoire

Référence inspectée : `4405b9c338ab8d5e9a3d27be33ccea06bd1daff2` (`feat/v3-architecture`).
Le rapport initial a été livré en lecture seule dans la conversation, puis son périmètre L0 validé par le responsable du projet.
Cette synthèse versionnable conserve les observations déterminantes ; les SPEC-01 à SPEC-10 définissent la cible.

## Observations et décisions

Les chemins Java ci-dessous sont relatifs à `app/`. OBSERVÉ désigne une lecture de source ; les décisions sont PROPOSÉES pour V3, pas des certifications.

| Composant / fichier déterminant | Responsabilité V2 observée | SPEC | Décision | Changement / impact | Tests existants |
|---|---|---|---|---|---|
| `domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/id/` | Identités stables typées | 01 | REUSE | Conserver les types et validations intrinsèques | Tests des IDs |
| `domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/ExternalIdentity.java` | Couple issuer/subject opaque | 02 | REUSE | Conserver comme clé externe ; ne pas confondre avec l'identifiant EUI V3 | Domaine Identity |
| `domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Fraction.java` | Calcul exact, BigInteger intermédiaire, stockage long | 01/04 | ADAPT | Qualifier la capacité ; `longValueExact()` refuse les dépassements | FractionTest |
| Même répertoire, `Amount.java`, `Weight.java` | Montant et poids non négatifs | 01 | ADAPT | Amount compatible ; Weight doit refuser zéro | AmountTest, WeightTest |
| `domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/aggregate/PotHeader.java`, `ExpenseHeader.java` ; `entity/Shareholder.java` | Regroupements V2 ; Headers mutables encapsulés, Shareholder immuable | 01 | ADAPT | Séparer huit composantes, versions locales, no-op ; L1, hors L0 | Tests agrégats/contextes |
| `domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/authorization/PotBusinessAuthorizationPolicy.java` | Matrice pure, droits plus restrictifs V2 | 01/06 | ADAPT | Alignement droits participants, créateur, rattachement/détachement | Tests du kernel/politique |
| `engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotDetailsService.java` | Contexte, droits, mutation, CAS, événement | 01/03 | ADAPT | Versions locales, succès sans effet, événement unique | Services et CommandObsolescencePostgresTest |
| `engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/execution/ExecuteRecordedCommandService.java` | Binding observé/fencé, décodage, exécution, expiration d'attestation | 02/03/05 | ADAPT | Token V3 et frontière temporelle des attestations | Tests Command, PotCasConsumptionPostgresTest |
| `engine-admit-command/src/main/java/com/kartaguez/pocoma/engine/admit/command/SubmitRecordedCommandService.java` | Admission durable distincte du métier | 03/06 | ADAPT | Idempotence portée/clé/intention atomique | CommandAdmissionPostgresTest |
| `domain-user-identity/src/main/java/com/kartaguez/pocoma/domain/useridentity/BindingId.java`, `BindingRevision.java` ; ports Binding et occurrences persistées | Historique séparé du rattachement | 02 | RETIRE | Retirer le modèle autonome au profit d'EUI@version ; préserver le savoir-faire de fencing | Tests lifecycle/authority |
| `domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/claim/ConsumptionSlot.java` ; `engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/FinalizeConsumptionService.java` | PENDING/DONE conservé, claims/provenance séparés, CAS terminal | 05 | ADAPT | Slot supprimé après succès, claim valide au verrou final, retryAfter, compteur et quarantaine | Suites Consumption |
| `orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java` ; `locator/ConsumptionLocator.java` | Recherche/exécution séquentielle via Locator | 05 | RETIRE | Deux boucles Discovery/Engine ; réutiliser les outils d'attente/lifecycle utiles | Tests orchestrateurs/polling |
| `engine-produce-projection-task`, `engine-consume-projection-task`, supras et runtime associés | Event → tâche → projection | 03/04/05 | RETIRE | Trois matérialisations Pot directes ; transférer les oracles utiles | Tests Event/ProjectionTask |
| `engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/consume/projectiontask/input/CalculatePotBalancesAtVersionService.java` ; calcul par Expense | Voie de recalcul complet à V, avec zéros | 01/04 | ADAPT | Poids par défaut à V, lectures groupées ; retirer API différentielle depuis balance précédente | Tests balances/projections |
| `infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/core/JpaPotHeaderRepository.java` | Sélection semi-ouverte à V | 01/04 | ADAPT | Conserver prédicat ; huit composantes et intégrité temporelle à prouver | Tests historiques/contextes |
| `infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/projection/JpaHistoricalPotSnapshotSourceAdapter.java` | Reconstruction directe REPEATABLE_READ, détection de doublons/mauvais Pot | 01/04 | ADAPT | Chargement groupé ; boucle actuelle lisant les parts par Expense (motif N+1) | Tests historiques |
| `infra-persistence-projection-jdbc`, `port-projection`, Networknt | Store JSON générique | 04 | DEFER | Décider après contrats physiques des neuf projections ; ne pas conserver la généricité par principe | JdbcProjectionStoreAdapterPostgresTest |
| `engine-materialize-command-result`, résultats Registration et lecture exact identity | Résultat terminal immuable appartenant à l'EUI | 03/04/06 | ADAPT | requesterEui et autorité READ ; aucune expiration fonctionnelle ajoutée | Tests result/chain/takeover |
| Engines/supra/runtime LKV | Fraîcheur source autonome | 04/06 | RETIRE | Aucun besoin autonome établi dans la cible CurrentPot/minVersion | Tests monotonie LKV |
| `infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunner.java` | Frontière transactionnelle simple | 05/10 | REUSE | Ne garantit pas seul fencing/poisoning de chaque writer | SpringTransactionRunnerTest (H2) |
| `infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/PrimaryEventTransactionRunner.java` ; `composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/publication/PrimaryEventProtocol.java` | Owner physique, guards, flush, suffixe, refus des ressources incohérentes | 03/05/10 | ADAPT | Préserver garanties ; adapter familles et parcours V3 | PrimaryEventTransactionPostgresTest |
| `infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/JdbcEventTransactionSequencer.java` | Compteur transactionnel, quatre familles, suffixe sans nouvelle transaction | 03 | ADAPT | Cinq familles V3 ; pas de cutover de compatibilité fictif | Suites EventSequencing |
| `composition-runtime-spring`, applications runtime | Imports explicites, injection par constructeur, assemblage partagé | 05/06 | ADAPT | Qualifier les autorités ; ne pas importer tout le runtime dans L0 | Tests runtime/monolithe |
| `architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/HexagonalArchitectureTest.java`, `Wp5TargetTopologyTest.java`, `EventPublicationContractArchitectureTest.java` | Bytecode, POM, guards, fixtures négatives ; assertions de forme V2 | 05/10 | ADAPT | Préserver direction/pureté ; remplacer les règles historiques quand leurs invariants changent | Suites d'architecture |
| `scripts/testing/summarize-4e.py` | XML frais, skips, log et empreinte | 10 | ADAPT | Manifeste des méthodes attendues, SHA/sources/environnement et verdict borné | Nouveau collecteur L0-F |

## Qualité et limites

OBSERVÉ : domaine expressif sans Spring/JPA ; invariants encapsulés, identifiants typés, injection par constructeur, ports de persistance/transaction, composition explicite.
OBSERVÉ : pas de service métier gigantesque dans les représentants lus ; complexité surtout répartie entre relais, représentations et couches historiques. La modularité seule ne prouve pas la qualité.
OBSERVÉ : plusieurs paramètres distincts d'un service ont le même type de port ; supprimer les doublons de rôle seulement si aucune frontière réelle ne les justifie.
INFÉRÉ : les conversions payload/commande/invocation/input/contexte/agrégat/snapshot/événement/artefact/provenance doivent être réévaluées une à une, sans refonte générale.

OBSERVÉ : `TransactionalConsumptionExecutionPostgresTest` accepte un claim expiré sans takeover (`expiredLeaseStillCommitsWithoutTakeover`, `expiredCurrentClaimCanStillFinalizeWithoutTakeover`). Cet oracle contredit V3, qui exige sa validité lors de la prise du verrou final.
OBSERVÉ : `PrimaryEventTransactionPostgresTest` vérifie PID/XID communs JPA/JDBC, flush, erreurs interceptées, résolution et nettoyage ; `CommitAcknowledgementProxy` coupe la réponse COMMIT réelle. Ces outils restent précieux, sans transfert de PASS V2.
NON VÉRIFIÉ par l'inventaire : réussite des suites, stabilité et coûts réels. Les preuves L0 ajoutées ultérieurement sont consignées séparément dans le rapport d'exécution.

## Migrations et contraintes

OBSERVÉ : PRIMARY possède V1–V31 sous `app/infra-persistence-primary-jpa/src/main/resources/db/migration/` ; READ V1–V14 sous `app/infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/`.
V1 installe les regroupements métier ; V2–V16 font évoluer Consumption/projections/tâches ; V17–V26 commandes, Identity et résultats ; V27–V29 Registration ; V30–V31 séquencement/administration.
`PrimaryMigrationsPostgresTest.runtimeClasspathAppliesAndValidatesMigrationsV1ThroughV31` en contrôle la chaîne ; la politique conserve « Trusted baseline: NONE » et son candidat historique V23 n'est pas un état V31 certifié.
OBSERVÉ : unicités/FK/checks Consumption/Binding/résultats, trigger d'immuabilité des résultats, positions uniques, contrôles différés et compteurs transactionnels. Les index temporels V1 ne prouvent pas l'absence de trou/chevauchement.
OBSERVÉ : `ReadStoreMigrator` configure un schéma, mais des migrations historiques nomment `pocoma_read` directement. Un changement de defaultSchema ne suffit pas à isoler toute la chaîne.
PROPOSÉ : bootstrap V3 neuf et explicitement localisé, sans prétendre une équivalence de baseline V2. Retirer à terme les anciennes chaînes du bootstrap V3, sans les supprimer durant L0.

## L0 et décisions

Le [plan L0](L0_Verification_Plan.md) retient les modules existants, un banc PostgreSQL isolé dans `infra-tx-spring`, des règles dans `architecture-tests` et une collecte exhaustive des preuves locales. Pas de Pot en L0 ; SPEC-01 fonctionnelle relève de L1.
Les choix de banc retenus ne prescrivent pas la topologie de production. N, consentement, protection des invitations et budgets restent à qualifier aux étapes pertinentes ; aucun arbitrage conceptuel n'est rouvert.
Les PASS historiques ne sont jamais une certification V3 ; aucun parcours Bruno/k6 nouveau n'est attendu après L0.

## Graphe Maven de référence

Le réacteur possède 60 modules : 6 domaines, 4 contrats, 3 ports, 17 engines, 10 supras, 2 orchestrateurs, 1 projecteur, 5 infrastructures, 1 composition, 10 runtimes et architecture-tests.
Le graphe complet des dépendances internes est conservé dans [L0_Maven_Graph.json](L0_Maven_Graph.json), scopes et optional inclus. Il est extrait des POM de la référence ; l'ajout L0 ne change que des dépendances externes de test.
