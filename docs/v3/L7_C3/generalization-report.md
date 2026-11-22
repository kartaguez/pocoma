# L7-C3 — Généralisation des événements de disponibilité

## Périmètre et décision

La décision humaine I1–I6 autorise la généralisation sur `feat/v3-l7`. Le HEAD de départ est `d7b4b6d73963cab7efeb658ef67478d52de03c32`. Le pilote, sa V5 isolée, ses tests et les preuves complémentaires étaient présents dans le répertoire de travail. L’index était vide. Le conteneur C2 existant a été inspecté en lecture seule. Il était sous PostgreSQL 17.12, avec READ V4, 28 checkpoints à zéro et aucun objet C3. Les décisions C3-R1 et C3-R2 s’appliquent au dispositif généralisé. L’activation sur stock READ exige la reconstruction exclusive L6/I2. [Registre des décisions](arbitrages.md) · [Qualification complémentaire du pilote](../L7_C3_CommandResult_Complementary_Gate_Report.md)

## Huit familles

| Famille | Publication en READ |
| --- | --- |
| `POT_VERSION_AVAILABLE` | Événement immuable avec UUID et position dense pour chaque manifeste nouveau, y compris une ancienne version et la version supprimée. |
| `POT_BALANCE_AVAILABLE` | Événement immuable pour chaque nouveau pointeur de balance actif. Un contenu mutualisé ou l’absence d’agrégat ne suppriment pas l’événement. À la version terminale, bilan, agrégat et disponibilité sont absents. |
| `COMMAND_RESULT_AVAILABLE` | Le pilote conserve son identité par demande et le protocole de finalisation. Le même store est raccordé à la V5 canonique. |
| `IDENTITY_REQUEST_RESULT_AVAILABLE` | Événement immuable pour chaque résultat terminal, accepté ou rejeté, avec la demande pour clé logique. |
| `CURRENT_POT_CHANGED` | Registre du dernier pointeur, y compris supprimé. Quand le pointeur avance, sa publication et celle du manifeste appartiennent au même commit READ. |
| `POT_VISIBILITY_CHANGED` | Registre de l’évaluation la plus récente. Une évaluation plus récente à ensemble visible identique avance le marqueur. À la suppression, l’ensemble devient vide. |
| `CURRENT_BINDING_CHANGED` | Registre de la version EUI réellement avancée, avec état inactif observable après Unlink. |
| `CURRENT_INVITATION_CHANGED` | Registre de la version réellement avancée. Les comparaisons de provenance couvrent aussi les tentatives dépassées par une version supérieure. |

Le store contrôle le résultat métier et sa disponibilité au cours de la finalisation. Après le DELETE protégé du slot, le callback JDBC ferme la publication et alloue les seules positions dues. Le suffixe n’écrit que les métadonnées C3. L’ordre du double suffixe est `POT_VERSION_AVAILABLE` puis `CURRENT_POT_CHANGED`. Les contraintes différées associent les positions, les publications et les effets. Le moteur, le contrat de complétion et les propriétaires de transaction restent ceux de L2. [Architecture C3](architecture.md#protocole-transactionnel)

La fonction SQL de pagination renvoie la tête, le curseur proposé et les marqueurs triés dans le snapshot de l’instruction. Une page pleine avance à son dernier marqueur. Une page courte avance à la tête. Un curseur au-delà de la tête est rejeté. Une modification qui remplace un marqueur après le snapshot reçoit une position ultérieure et redevient éligible. La reprise non destructive garde les positions. Une reconstruction intégrale exige la resynchronisation coordonnée des futurs lecteurs. [Contrat courant](architecture.md#lecture-paginée-des-courants)

## Changements par module

| Module | Changements |
| --- | --- |
| `port-projection` | Reçus d’insertion et d’avance, finalisation typée et preuve de récupération là où elle manque. |
| `engine-materialize-pot` | Manifeste et pointeur courant, pointeur de balance, visibilité, invitation. Les calculs, les hashes et les chemins de stockage C2 gardent leurs contrats. |
| `engine-materialize-identity`, `engine-materialize-results` | Publication du binding et des résultats terminaux. Réutilisation du modèle de réception et de recovery du pilote. |
| `infra-postgres-read` | Suffixe JDBC interne partagé et V5 canonique avec trois tables, une vue invoker de correspondance, contraintes différées, catalogue et prédicat de convergence. |
| `infra-postgres-core`, `compose-polling-nonburnable-materialize-postgres` | Contrôle V5 dans la composition qui écrit les effets READ, raccord des sept participants, vérification C3 avant convergence. |
| `runtime-worker-materialize`, `runtime-monolith`, `architecture-tests` | Fixtures des vrais workers sur V5, preuves SQL, tests de replay et inventaire des ressources. Les fixtures HTTP qui construisent volontairement des effets partiels gardent la V4. |
| `scripts/operations`, `scripts/performance` | Installation atomique, refus d’un catalogue incomplet, contrôle de la reprise et restauration des snapshots C2 pour les comparaisons E2E. |

La migration canonique se trouve dans `db/read/availability/migration`. Les opérations la déploient explicitement après V4 et les vrais matérialiseurs exigent son catalogue. Cette décision évite que la migration charge ses gardes différées dans les fixtures V4 qui injectent des effets partiels. Elle permet également de conserver et rejouer la V5 du pilote sans conflit de version Flyway. La vue invoker partage une seule définition des clés et versions avec les contrôles SQL. [Migration V5](../../../app/infra-postgres-read/src/main/resources/db/read/availability/migration/V5__projection_availability.sql)

## Preuves fonctionnelles initiales

- I1 a recompilé la tranche ciblée et exécuté 11 tests du pilote CommandResult et 3 tests de stockage des résultats, sans échec ni skip. Le premier essai avait chargé une classe de test obsolète et a échoué avant l’exécution du pilote. [Journaux I1](../../testing/evidence/L7-C3/generalization/)
- I2 a exécuté 12 tests ciblés, dont les incréments PotVersion et PotBalance et les tests PostgreSQL de stockage conjoints. Ces cas exercent les écritures métier C2 et les ports modifiés. [Journal I2](../../testing/evidence/L7-C3/generalization/i2.log.gz)
- La première V5 a échoué sur la syntaxe d’activation des triggers. Le rejeu ciblé corrigé a exécuté huit cas, dont l’arrivée hors ordre après DeletePot, le rollback du double suffixe, les positions remplacées, les avances d’invitation, le refus V4 peuplé, le démarrage réel et la convergence de la reconstruction. [Journaux I3–I4](../../testing/evidence/L7-C3/generalization/)
- Sur PostgreSQL 17.12 jetable, le bootstrap CLI a installé les huit compteurs et son rejeu a reconnu V5. Un échec injecté après le DDL et les grants a annulé l’installation entière, y compris le trigger de projection. L’installation atomique sur ce V4 vide a ensuite réussi. Seul le rôle matérialiseur possède les droits sur les objets C3 parmi les rôles applicatifs inspectés. [Preuve PostgreSQL](../../testing/evidence/L7-C3/generalization/postgresql-operations.log)

## Reconstruction

Le rejeu Java et le script L6/I2 vérifient les 28 checkpoints, les slots et l’intégrité READ. La V5 ajoute un prédicat de catalogue, la correspondance dans les deux sens et les bornes de positions. La densité est requise pour les historiques. Le contrôle des courants vérifie que la tête existe et accepte les trous dus à la coalescence. Un oracle de test indépendant projette les clés et versions attendues depuis les cinq familles WRITE. Les historiques déjà résolus gardent leur UUID et leur position à la reprise non destructive. [Test de reconstruction](../../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/ReadReconstructionPostgresTest.java)

Le smoke à cinq runtimes a restauré le snapshot WRITE Medium, reconstruit READ V5, puis observé les 10 commandes mesurées et les 2 d’échauffement. Le journal initial contenait 404 manifestes, 404 balances, 404 résultats Command et un résultat Identity. Les registres et leurs compteurs satisfaisaient le prédicat de convergence avant et après la charge. Le snapshot ne contenait aucune invitation. Cette exécution établit la convergence sur ce corpus fonctionnel. [Manifeste cinq runtimes](../../testing/evidence/L7-C3/generalization/five-runtimes-smoke.json)

Le rejeu PostgreSQL ciblé final a exécuté huit cas sans échec ni skip. La perte de l’ACK PostgreSQL a donné `UNKNOWN` au transport et `COMPLETED` à l’observation, y compris après un commit plus récent sur le même Pot. L’événement historique initial est resté identique. Le test L6/I2 a comparé les clés et versions publiées à un oracle WRITE, reconstruit les mêmes effets après reset, repris après expiration des leases et retrouvé les UUID et positions déjà committés. Le contrôle de réouverture a rejeté la suppression injectée d’événements. [Journal et tests de reprise](../../testing/evidence/L7-C3/generalization/recovery.log.gz)

### Activation et reprise avec le CLI L6/I2

Le CLI a arrêté les anciens matérialiseurs sur un READ V4 peuplé, reconstruit READ V5 à partir du snapshot WRITE Medium et réouvert le service. La photographie finale contenait 28 checkpoints, zéro slot et 1 213 événements historiques. Le HTTP READ a de nouveau répondu `401` sans authentification après READY.

Un second reset a été interrompu durant `REBUILDING/REPLAYING`, après observation de six événements committés. `--resume` a réutilisé la même incarnation et retrouvé ces six UUID et positions parmi les 1 213 événements finaux. Les deux reconstructions ont produit la même empreinte des clés et versions logiques, avec des UUID régénérés par le reset. L’empreinte WRITE est restée identique et l’autorité est revenue à `READY/IDLE` avec 28 checkpoints et zéro slot. [Journaux et manifeste du CLI](../../testing/evidence/L7-C3/generalization/reconstruction/)

## Comparaison de performance C2 / C3

Référence C2 exacte `d25309256aae47f3608629901184de8c316828c6`. Les deux variantes utilisent les mêmes images PostgreSQL 17.12 et Java 21, le même banc, les mêmes profils des cinq runtimes et les mêmes ressources Docker. Medium recrée les mêmes formes de données et la même charge. Large restaure le même dump WRITE de 4 004 versions. Les fenêtres WAL incluent la charge applicative et le polling du cluster de test. La gate Maven tournait sur le même hôte pendant ces comparaisons. Le relevé de charge permet de contextualiser ces paires uniques. [Manifeste des JAR](../../testing/evidence/L7-C3/generalization/runtime-jars.json) · [Résultats bruts et synthèse](../../testing/evidence/L7-C3/generalization/performance/)

| Indicateur | Medium à 5/s, C2 → C3 | Large à 1/s, C2 → C3 |
| --- | ---: | ---: |
| Commandes observées | 150 → 150 | 60 → 60 |
| Débit observé (commandes/s) | 5,001 → 5,016 | 1,014 → 1,015 |
| C‑03 p95, émission → observation (ms) | 172,96 → 175,29 | 225,37 → 226,72 |
| Balance observable p95 (ms) | 168,93 → 170,91 | 202,23 → 199,35 |
| CommandResult observable p95 (ms) | 119,49 → 125,41 | 164,46 → 133,67 |
| PotVersion, temps utile p95 (ms) | 14,48 → 18,14 | 14,25 → 13,99 |
| PotBalance, temps utile p95 (ms) | 7,57 → 8,48 | 6,31 → 6,64 |
| WAL cluster (octets) | 10 048 643 → 10 389 691 | 11 630 769 → 11 942 979 |
| Taille READ finale (octets) | 16 692 915 → 18 503 347 | 60 962 483 → 65 394 355 |
| Reconstruction depuis workers opérationnels (s) | — | 61,99 → 56,43 |
| Deadlocks READ et observations de verrous bloquants | 0 / 0 → 0 / 0 sur 8 échantillons par variante | 0 / 0 → 0 / 0 sur 16 échantillons par variante |

La V5 a occupé 1 613 824 octets pour ses trois tables et leurs index après Medium et 4 284 416 octets après Large. Les deltas WAL cluster observés sont de 3,4 % et 2,7 %. La fenêtre Large a observé un p95 C‑03 inférieur au budget nominal de 2 s. Le test de pagination montre une attente réelle sur le compteur en concurrence forcée. L’échantillonnage PostgreSQL des fenêtres de débit couvre uniquement les attentes visibles à chaque instant de collecte. [Synthèse appariée](../../testing/evidence/L7-C3/generalization/performance/paired-summary.json)

Sur le store PotBalance XLARGE à 500 shareholders, la situation D de 100 versions financières est passée de 4 443 à 4 982 octets WAL par version et de 3,81 à 5,96 ms p95. La situation A de 50 versions à contenu réutilisé est passée de 690 à 1 245 octets WAL par version et de 3,01 à 4,11 ms p95. La V5 a produit 150 événements et l’oracle SQL a vérifié leur correspondance. Le surcoût est particulièrement visible lorsque le stockage métier mutualise le contenu. Le regroupement SQL reste une décision ouverte. [Mesures XLARGE](../../testing/evidence/L7-C3/generalization/performance/xlarge.json)

Au seuil D5, C2 et C3 ont relié 59 changements à l’oracle rationnel sans divergence et persisté zéro agrégat au-dessus du plafond. Pour les 30 changements proches du seuil, le p95 C‑03 est passé de 294,43 à 317,92 ms. Pour les 29 changements de franchissement, il est passé de 708,62 à 594,03 ms. Le WAL du chemin XLARGE mutualisé est le delta proportionnel le plus marqué parmi ces mesures. Des répétitions à charge plus élevée restent nécessaires pour établir la dispersion des mesures. [D5 C2](../../testing/evidence/L7-C3/generalization/performance/c2-d5.json) · [D5 C3](../../testing/evidence/L7-C3/generalization/performance/c3-d5.json) · [Échantillons et charge concurrente](../../testing/evidence/L7-C3/generalization/performance/measurements.tar.gz)

Le banc du pilote adapté à la V5 canonique a exécuté 18 fenêtres et 1 408 finalisations mesurées sur PostgreSQL 17.11. Le témoin V4 utilise les mêmes participants sans publication. Dans la fenêtre mixte à quatre workers, les 192 finalisations ont produit 256 publications, dont les doubles publications PotVersion. Le débit est passé de 98,0 à 82,6 finalisations/s. Cinq échantillons ont observé une attente sur le compteur C3. Aucun deadlock n’a été observé et le WAL attribué aux instructions C3 a atteint 581 octets par publication dans cette fenêtre. L’écart porte sur la finalisation sous concurrence du banc. Les parcours HTTP utilisent les fenêtres de mesure Medium, Large et D5 ci-dessus. [Métriques du banc de contention](../../testing/evidence/L7-C3/generalization/performance/concurrency.json) · [Instructions et attentes PostgreSQL](../../testing/evidence/L7-C3/generalization/performance/concurrency-measurements.tar.gz)

## Vérification et livraison

Tranche principale **PROJECTION**. Tranche secondaire **WEB** pour les privilèges et la réouverture HTTP. La gate globale d’architecture est requise pour l’inventaire des ressources READ. L’impact de production est resté dans les frontières déclarées. Les tests de COMMAND, FOUNDATION et Monolith appartiennent à la fermeture de la gate composée. Aucun `./mvnw test` sans sélection n’a été lancé. La certification globale L7 relève d’un jalon distinct.

Vérification PostgreSQL **CURRENT**, avec V4 et V5 sur des bases jetables. Les migrations plus anciennes n’ont été exécutées que lorsqu’une fixture des dépendances Maven les chargeait. Aucune activation ni manipulation destructive n’a visé un stock à conserver.

### Commandes exécutées

Depuis `app/`, les commandes Maven ciblées ci-dessous ont produit les journaux du dossier de preuves. Les deux premiers essais I1 et I3 ont documenté le bytecode obsolète et l’erreur SQL, respectivement.

```sh
./mvnw -pl runtime-worker-materialize -am -Dtest=CommandResultAvailabilityPostgresTest,TerminalResultsReadStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-worker-materialize -am -Dtest=CommandResultAvailabilityPostgresTest,TerminalResultsReadStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false clean test
./mvnw -pl runtime-worker-materialize -am -Dtest=TerminalResultsReadStorePostgresTest,PotVersionAndBalanceJointIncrementalPostgresTest,MaterializePotVersionIncrementalTest,MaterializePotBalanceIncrementalTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-worker-materialize -am -Dtest=ReadRuntimeAssemblyPostgresTest,CommandResultAvailabilityPostgresTest,CurrentBindingReadStorePostgresTest,PotVisibilityReadStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false clean test
./mvnw -pl runtime-worker-materialize -am -Dtest=ReadAvailabilityPostgresTest,ReadRuntimeAssemblyPostgresTest,ReadReconstructionPostgresTest#rcv01D_rebuildReplaysFromFiveWriteFamiliesToConvergence+rcv01H_readinessOracleDetectsIntegrityViolationsAndQuarantines -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize -am -Dtest=ReadAvailabilityPostgresTest,ReadReconstructionPostgresTest#readReplayPreservesExactValuesAndCatchesUpWithNewWritePublication+rcv01H_quarantineAndIntegrityViolations_blockReady -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize,runtime-web-write,runtime-web-read,architecture-tests -am test
./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-process,runtime-worker-materialize,runtime-web-write,runtime-web-read -am -Dmaven.test.skip=true package
```

La commande composée a été lancée dans une copie fraîche avec les empreintes Maven et le CLI Bruno local. Les deux exécutions `package` fournissent les JAR C2 et C3 pour le banc. La sélection I3–I4 initiale a exécuté le cas `rcv01D`. Le test de rejet `rcv01H` a exécuté sa méthode exacte dans le rejeu ciblé suivant.

Les vérifications Python ont été exécutées avec `python3 -B scripts/operations/test_v3_operations.py`, `python3 -B -m unittest scripts.operations.test_v3_operations` et `python3 -B scripts/performance/test_l7_4a_baseline.py`. Le chemin d’exploitation a exécuté le bootstrap CLI, la faute après DDL avec `psql --single-transaction`, `v3-rebuild-read.py --confirm-destructive=RESET_READ_DATA` et `v3-rebuild-read.py --resume` sur des projets jetables. Le banc a exécuté `smoke-medium` sur snapshot, `med-5`, `large-1` sur snapshot, D5 et le comparateur XLARGE. [Empreintes des entrées Maven](../../testing/evidence/L7-C3/generalization/maven-app-inputs.json) · [Commandes du banc](../../testing/evidence/L7-C3/generalization/performance/commands.sh) · [Journaux PostgreSQL](../../testing/evidence/L7-C3/generalization/postgresql-operations.log)

Une exécution ciblée distincte de la gate composée a exécuté les 52 assertions d’architecture et quatre cas Monolith, dont les parcours Bruno B2 et B4. Aucun de ces 56 tests n’a échoué ni été ignoré. La commande est `./mvnw --batch-mode --no-transfer-progress -pl architecture-tests -am -Dtest=<sélection archivée> -Dsurefire.failIfNoSpecifiedTests=false test`. [Sélection complète](../../testing/evidence/L7-C3/generalization/architecture-targeted-selection) · [Journal](../../testing/evidence/L7-C3/generalization/architecture-targeted.log.gz)

### Reprise de la gate canonique

L’exécution composée initiale s’est interrompue pendant `WorkerMetricsTest`, avant le récapitulatif Maven. Les 626 entrées applicatives de la copie, du dépôt et du premier inventaire ont été vérifiées avant reprise. Les artefacts des mêmes sources ont été installés dans un dépôt Maven local à la copie. `WorkerMetricsTest` a ensuite exécuté ses trois cas. Les rapports du préfixe totalisent 943 cas exécutés. Les trois modules restants ont été repris sans filtre à partir de `runtime-worker-materialize`.

```sh
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=<dépôt-isolé> -pl runtime-worker-materialize,runtime-web-write,runtime-web-read,architecture-tests -am -DskipTests install
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=<dépôt-isolé> -pl runtime-worker-process -am -Dtest=WorkerMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=<dépôt-isolé> -pl runtime-worker-materialize,runtime-web-write,runtime-web-read,architecture-tests -am -rf :pocoma-runtime-worker-materialize test
```

`runtime-worker-materialize` a exécuté ses 134 tests, sans échec, erreur ni skip. [Rejeu de WorkerMetrics](../../testing/evidence/L7-C3/generalization/worker-metrics-resume.log.gz)

Le premier passage Monolith a exposé deux fixtures Invitation qui écrivaient une projection V5 via le chemin V4. La contrainte différée a refusé les commits. La première fixture utilise désormais le reçu du store, sa validation et le suffixe C3. Le rejeu d’invitation utilise le participant produit par la composition de matérialisation et compare aussi les marqueurs courants. Le rejeu ciblé a exécuté les cinq cas de la classe sans échec, erreur ni skip. La fixture qui injecte une perte d’ACK sur PotVersion utilisait la même sélection V4. Elle passe à la composition C3. Les deux modifications après la première reprise sont limitées à ces fixtures Monolith. Aucun délai de test n’a été changé. [Rejeu des cas d’invitation](../../testing/evidence/L7-C3/generalization/invitation-v5-fix.log.gz)

```sh
./mvnw --batch-mode --no-transfer-progress -pl runtime-monolith -am -Dtest=InvitationReadPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Le premier passage de `ReadWorkerResourcesPostgresTest` a confirmé la régression de fixture. Son attente sur la perte d’ACK a expiré avec le participant V4. Le rejeu Monolith non filtré avec les deux fixtures corrigées a réussi les 40 tests, dont les deux tests de ressources et les cas Bruno B2/B4. Les 52 assertions d’architecture ont ensuite réussi, y compris l’inventaire des migrations READ.

```sh
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=<dépôt-isolé> -pl runtime-worker-materialize,runtime-web-write,runtime-web-read,architecture-tests -am -rf :pocoma-runtime-monolith test
```

La couverture composée après reprise est complète. Les rapports retenus correspondent à **1 169 tests exécutés sur 43 modules Maven, avec zéro échec, erreur ou skip final**. L’inventaire a comparé les identités des cas aux 1 163 cas déjà recensés et aux six nouvelles méthodes C3. Les journaux conservent l’interruption initiale et les trois échecs de fixtures du premier passage. Les deux seuls fichiers applicatifs modifiés entre les reprises appartiennent aux tests Monolith. Les sources de production et les entrées des mesures sont restées identiques. [Reçu de couverture](../../testing/evidence/L7-C3/generalization/maven-gate.json) · [Journaux, rapports et preuves Bruno](../../testing/evidence/L7-C3/generalization/maven-gate.tar.gz)

Le HEAD final est celui du départ, `d7b4b6d7`. La divergence avec l’upstream est `0/0`. L’index est identique et les 119 fichiers du statut initial sont présents. Parmi eux, 110 sont identiques octet par octet et neuf ont été actualisés dans le périmètre du chantier. Le répertoire de travail conserve le diff volontaire. Aucun commit ni push. [Contrôle Git](../../testing/evidence/L7-C3/generalization/git-preservation.json)

## Verdict proposé

**Clôturer C3 sur le périmètre qualifié.** Les huit familles sont mises en œuvre. Les essais ont vérifié le commit commun avec le slot, les positions historiques denses, la découverte des courants coalescés, le cas terminal et la reprise après reset. La gate composée dispose d’une couverture complète après correction et reprise.

Les mesures locales montrent un coût mesurable du suffixe, notamment sur PotBalance à contenu mutualisé et lors de la concurrence au compteur. Le regroupement JDBC reste ouvert. Les résultats donnent une borne locale aux profils exercés. Les mesures sous charge nominale, soutenue et de pointe de la certification L7 restent propres à cette certification. La première activation sur un stock à conserver relève d’une décision d’exploitation distincte et du protocole C3-R2. La resynchronisation des futurs lecteurs reste requise après reset intégral.
