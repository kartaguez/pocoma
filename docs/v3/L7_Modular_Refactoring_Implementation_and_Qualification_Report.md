# Pocoma V3 — L7 — Implémentation et qualification

Mandat `GO_L7_MODULAR_REFACTORING_FULL_EXECUTION`. Branche `feat/v3-architecture`.

**Verdict `L7_MODULAR_REFACTORING_IMPLEMENTED_WITH_RESERVATIONS`.** Le réacteur
Maven complet a passé ses 1 088 tests et les cinq Runtimes ont démarré sur la
pile PostgreSQL et Keycloak isolée. Les réserves concernent la qualification
des performances A/B et les scénarios SPEC-09 hors du banc exécuté.

## État Git et référence

Le SHA initial `f2bd18908af0c43a6dcde981246727d35d8dffe1` était synchronisé avec
`origin/feat/v3-architecture`. Le worktree était propre. Les changements attribués à
Gemini par le mandat sont dans `0f31f5ac`, `027e200d` et `f2bd1890`. L'historique
Git attribue les commits à Dude Kartaguez. Le contenu couvre les fixtures L7/I2,
la topologie à cinq processus et les contrôles HTTP/worker. L'état historique
`7fd509d4` n'a pas été rétabli.

Les corrections E00 de référence sont dans `806cbf73`, `96e0cd64`, `1100b4ca`,
`747580e0`, `706188b4` et `123b3fd2`. La refactorisation a été préparée dans un
worktree distinct. Le lot `779d7e3e` est intégré par `1375b366056482ca8e74d80ef6c79a2c2b1b1040`.
Les huit conflits ont été résolus individuellement en préservant les réserves
de connexion, les tests HTTP, le pool de maintenance et le provisionnement des
fixtures. L'intégration conserve les deux historiques. La première certification
après la correction des chemins a exécuté
`f31cc52d439581cd72a5c1cc932c8a43fefabba5`. Le commit
`08014591cccc9417a510288413607fd3ae5f6276` porte sur la sonde de
reconstruction READ, hors des entrées du réacteur Maven, et a été vérifié sur
la pile physique de livraison. `8ab353c851f21d80f4c406520234ade185ab99e7`
réoriente les anciens lanceurs L2. `b989896ced44e57288ac94f30fb00c1ed0ca7b57`
et `7c1263e1f3297d7dd5ca1e93dd9c780448d56787` corrigent les deux fixtures
Process après cette certification. `d7f9a1a2380d2d911d4bf35611c4b769c4ec774e`
attend la convergence durable avant le drain dans la fixture de rejeu READ.
`cf31ff9e5d7124d733c42496f02a650a8b29a824` réconcilie les règles hexagonales
avec le catalogue V3. `966eb25aaddf5a908cf72c832591f0eecbdbb9fe` aligne le
pilote de test `SKIP LOCKED` sur la fenêtre de polling du scénario. La dernière
certification a repris ses mêmes entrées Maven et SQL sur secteur, avec une
assertion temporaire de maintien éveillé attachée au processus de vérification.
Le SHA final d'implémentation est `966eb25aaddf5a908cf72c832591f0eecbdbb9fe`.
Le reçu a été produit depuis `8e1e4a10273fffcef5d0746ea5275d67da9f0b2a`,
qui ajoute les preuves de la tentative précédente. Le diff entre ces SHA est
vide sous `app/` et `scripts/`.

## Périmètre de vérification déclaré

| Élément | Décision |
|---|---|
| Impact | Extraction des contrats, transfert de l'autorité L2, séparation des infrastructures et des Composes, retrait du support V2 exécutable, packaging et configuration |
| Tranches primaires | COMMAND pour Burnable, WEB pour admission et consultations, PROJECTION pour NonBurnable et READ |
| Tranches secondaires | Les deux autres tranches V3 lorsque le contrat partagé change. Monolith pour le co-hébergement. Les anciennes ancres V2 ont servi à inventorier les preuves avant retrait |
| Invariants | Atomicité, checkpoint, leases, fencing, recovery, commit incertain, publication dense, limites de capacité, sécurité, formats et migrations conservés |
| Gate Architecture | Requise pour le graphe, les frontières, la distribution et le retrait V2 |
| Full reactor | Requis pour la clôture intégrée. Les sélections `-Dtest` constituent des preuves bornées |
| PostgreSQL | Fixtures CURRENT jetables sur PostgreSQL 17.11. Les tests historiques physiquement applicables gardent leurs migrations et oracles |
| Croisement constaté | Les grants source WRITE et les contrôles de reconstruction relient COMMAND à PROJECTION. La maintenance HTTP READ relie WEB à PROJECTION. Ces frontières étaient prévues. Les budgets et fixtures ont été requalifiés dans les tranches concernées |

Les commandes canoniques depuis `app/` sont documentées dans la [politique de
vérification](../testing/Reactor_Verification_Policy.md).

```bash
./mvnw -pl runtime-web-write,runtime-web-read -am test
./mvnw -pl runtime-worker-process -am test
./mvnw -pl runtime-worker-materialize -am test
./mvnw -pl architecture-tests -am test
```

La certification est exécutée depuis la racine avec les commandes suivantes.
Le sélecteur appelle `./mvnw --batch-mode --no-transfer-progress test` avec
les propriétés de provenance et un dossier de rapports neuf pour chaque
invocation. Le plan du reçu conserve la ligne Maven exacte.

```bash
python3 -Bu scripts/testing/verify.py CERTIFICATION --output certification-raw/l7-certification-e7938267
python3 -Bu scripts/testing/verify.py CERTIFICATION --output certification-raw/l7-certification-f31cc52d
python3 -Bu scripts/testing/verify.py CERTIFICATION --output certification-raw/l7-certification-cf31ff9e
python3 -Bu scripts/testing/verify.py CERTIFICATION --output certification-raw/l7-certification-966eb25a
caffeinate -is python3 -Bu scripts/testing/verify.py CERTIFICATION --output certification-raw/l7-certification-966eb25a-awake
```

Le packaging de livraison a été exécuté séparément depuis `app/`. Les tests
sont exécutés par les tranches et par la certification native.

```bash
./mvnw -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize,runtime-monolith -am package -DskipTests
```

Les entrées `verify-v3-l2-i2.py` à `verify-v3-l2-i5.py` passent par le sélecteur
L7 et produisent ses reçus actuels. Les sélections `command`, `read`,
`transactions`, `monolith` et `architecture` ciblent respectivement Process,
Materialize, Transactions, Monolith et le gate Architecture déclaré. Le mode `F`
explicite d'I5 utilise `CERTIFICATION`. Le lanceur `run-i5-temporal-final.py`
continue à vérifier `i5_r12` et ses traces physiques depuis Process. Pour
reproduire les reçus de jalon I2–I5, utiliser le SHA indiqué dans chaque reçu.
Les scripts `verify-4c-previous-runtime.sh`, `verify-4d-previous-runtime.sh`,
`verify-4e*.sh` et `run-i5-temporal-v2.py` restent des reproductions historiques.
Leurs commandes et rapports sont conservés pour les SHA de leurs campagnes.
Les relances ciblées après correction figurent dans les [commandes exécutées](../testing/evidence/L7-full-execution/targeted-requalification-commands.json).

## Inventaire réalisé

Le POM `app/pom.xml` agrège **42 enfants**. Le graphe possède **182 arêtes internes
de production et 63 arêtes de test**. Les arêtes sont orientées du consommateur
vers le module requis. Le graphe complet est disponible en
[JSON](../testing/evidence/L7-full-execution/maven-dependency-graph.json) et
[Graphviz DOT](../testing/evidence/L7-full-execution/maven-dependency-graph.dot).

| ID | Module | Catégorie | Dépendances de production |
|---|---|---|---|
| C01 | `domain-pot` | D/PF | ∅ |
| C02 | `domain-identity` | D/PF | ∅ |
| C03 | `domain-projection` | D/DC/PF | C01, C02 |
| C04 | `domain-consumption` | D/DC | ∅ |
| C05 | `contract-authentication` | DC | C02 |
| C06 | `contract-write-admission` | DC/CC | C01, C02, C05 |
| C07 | `contract-read-query` | DC/CC | C01, C02, C03, C05, C08 |
| C08 | `port-transaction` | P | ∅ |
| C09 | `port-projection` | P | C01, C02, C03, C08 |
| C11 | `engine-admit-command` | E/P | C01, C02, C05, C06, C08 |
| C12 | `engine-admit-identity` | E/P | C02, C05, C06, C08 |
| C13 | `engine-write-pot` | E/P | C01, C04, C06, C08, C39 |
| C14 | `engine-write-identity` | E/P | C02, C04, C06, C08, C39 |
| C15 | `engine-materialize-pot` | E/P | C01, C03, C04, C08, C09, C39 |
| C16 | `engine-materialize-identity` | E/P | C02, C03, C04, C08, C09, C39 |
| C17 | `engine-materialize-results` | E/P | C03, C04, C08, C09, C39 |
| C18 | `engine-read-pot` | E | C01, C02, C03, C07, C08, C09 |
| C19 | `engine-read-identity` | E | C02, C03, C07, C08, C09 |
| C20 | `engine-read-results` | E | C02, C03, C07, C08, C09 |
| C21 | `orchestrator-consumption` | O | C04, C08, C39 |
| C22 | `supra-polling-consumption` | S | C04, C39 |
| C23 | `supra-web-write` | S | C01, C02, C05, C06 |
| C24 | `supra-web-read` | S | C01, C02, C03, C05, C07, C09 |
| C25 | `infra-tx-spring` | I | C08, C39 |
| C26 | `infra-security-spring` | I | C01, C02, C05 |
| C27 | `infra-postgres-write` | I | C01, C02, C06, C08, C11, C12, C13, C14, C25, C26, C40 |
| C28 | `infra-postgres-read` | I | C01, C02, C03, C08, C09, C25, C40 |
| C29 | `compose-web-write-postgres` | C | C05, C06, C11, C12, C23, C25, C26, C27, C42 |
| C30 | `compose-polling-burnable-write-postgres` | C | C04, C08, C13, C14, C21, C22, C25, C27, C40, C42 |
| C31 | `compose-polling-nonburnable-materialize-postgres` | C | C04, C08, C09, C15, C16, C17, C21, C22, C25, C28, C40, C41, C42 |
| C32 | `compose-web-read-postgres` | C | C05, C07, C09, C18, C19, C20, C24, C25, C26, C28, C42, C43 |
| C33 | `runtime-web-write` | R | C29 |
| C34 | `runtime-web-read` | R | C32 |
| C35 | `runtime-worker-process` | R | C30 |
| C36 | `runtime-worker-materialize` | R | C31 |
| C37 | `runtime-monolith` | R | C29, C30, C31, C32 |
| C38 | `architecture-tests` | Gate | ∅ |
| C39 | `contract-consumption` | CC/DC/P | C04, C08 |
| C40 | `infra-postgres-core` | I | C01, C04, C08, C25, C39 |
| C41 | `infra-postgres-write-source-read` | I | C01, C02, C03, C08, C15, C16, C17, C25, C39, C40 |
| C42 | `infra-observability-micrometer` | I | C04, C07, C39 |
| C43 | `engine-read-execution` | E | C02, C03, C07, C08, C09 |

## Matrice de migration exécutée

Le [relevé CSV](../testing/evidence/L7-full-execution/maven-module-migration.csv)
joint la destination normative et les déplacements détectés par Git. Les extractions
et classes réécrites peuvent échapper à la détection de renommage. Les preuves
transférées et les chemins productifs retirés sont consignés dans le
[registre V2/V3](../testing/evidence/L7-full-execution/v2-retirement-and-v3-proof-transfers.json).

| ID | Module initial | Opération | Destinations |
|---|---|---|---|
| A01 | `domain-authorization` | DELETE | ∅ |
| A02 | `domain-event` | DELETE | ∅ |
| A03 | `domain-user-identity` | RENAME | `domain-identity` |
| A04 | `contracts-authentication` | RENAME | `contract-authentication` |
| A05 | `domain-pot` | KEEP | `domain-pot` |
| A06 | `domain-projection` | KEEP | `domain-projection` |
| A07 | `domain-consumption` | KEEP | `domain-consumption` |
| A08 | `port-transaction` | KEEP | `port-transaction` |
| A09 | `port-binding-authority` | DELETE | ∅ |
| A10 | `engine-consumption` | SPLIT | `orchestrator-consumption`, `contract-consumption` |
| A11 | `engine-consume-command` | DELETE | ∅ |
| A12 | `supra-consume-command` | DELETE | ∅ |
| A13 | `supra-consume-command-result` | DELETE | ∅ |
| A14 | `engine-read-command-result` | SPLIT | `engine-read-results`, `domain-projection`, `port-projection`, `contract-read-query` |
| A15 | `engine-materialize-command-result` | MERGE | `engine-materialize-results` |
| A16 | `contracts-command` | MERGE | `contract-write-admission` |
| A17 | `contracts-registration` | MERGE | `contract-write-admission` |
| A18 | `engine-admit-registration` | SPLIT | `engine-admit-identity`, `contract-write-admission` |
| A19 | `engine-consume-registration` | RENAME | `engine-write-identity` |
| A20 | `supra-consume-registration` | DELETE | ∅ |
| A21 | `supra-consume-registration-result` | DELETE | ∅ |
| A22 | `engine-read-registration-result` | SPLIT | `engine-read-results`, `domain-projection`, `port-projection`, `contract-read-query` |
| A23 | `engine-materialize-registration-result` | MERGE | `engine-materialize-results` |
| A24 | `engine-produce-projection-task` | DELETE | ∅ |
| A25 | `engine-write-pot` | SPLIT | `engine-write-pot`, `contract-write-admission` |
| A26 | `port-projection` | KEEP | `port-projection` |
| A27 | `engine-read-projection` | DELETE | ∅ |
| A28 | `engine-consume-projection-task` | SPLIT | `engine-materialize-pot`, `domain-pot` |
| A29 | `supra-consume-projection-task` | DELETE | ∅ |
| A30 | `engine-read-pot` | SPLIT | `engine-read-pot`, `contract-read-query` |
| A31 | `projector-pot` | DELETE | ∅ |
| A32 | `engine-read-current-binding` | SPLIT | `engine-read-identity`, `contract-read-query` |
| A33 | `engine-materialize-current-binding` | SPLIT | `engine-materialize-identity`, `port-projection` |
| A34 | `engine-materialize-latest-known-version` | DELETE | ∅ |
| A35 | `infra-persistence-read-jdbc` | SPLIT | `infra-postgres-read`, `infra-postgres-core` |
| A36 | `contracts-observability` | DELETE | ∅ |
| A37 | `infra-tx-spring` | KEEP | `infra-tx-spring` |
| A38 | `infra-persistence-primary-jpa` | SPLIT | `infra-postgres-write`, `infra-postgres-core`, `infra-postgres-write-source-read` |
| A39 | `infra-persistence-projection-jdbc` | DELETE | ∅ |
| A40 | `infra-projection-validation-networknt` | DELETE | ∅ |
| A41 | `orchestrator-consumption` | MERGE | `orchestrator-consumption` |
| A42 | `engine-admit-command` | SPLIT | `engine-admit-command`, `contract-write-admission` |
| A43 | `orchestrator-poll-consumption` | SPLIT | `supra-polling-consumption`, `orchestrator-consumption`, `contract-consumption` |
| A44 | `supra-consume-event` | DELETE | ∅ |
| A45 | `supra-consume-binding` | DELETE | ∅ |
| A46 | `supra-consume-lkv` | DELETE | ∅ |
| A47 | `supra-http-write` | RENAME | `supra-web-write` |
| A48 | `supra-http-read` | RENAME | `supra-web-read` |
| A49 | `composition-runtime-spring` | SPLIT | `compose-web-write-postgres`, `compose-polling-burnable-write-postgres`, `compose-polling-nonburnable-materialize-postgres`, `compose-web-read-postgres`, `infra-security-spring`, `infra-postgres-read`, `infra-observability-micrometer`, `engine-read-execution`, `orchestrator-consumption`, `contract-consumption` |
| A50 | `runtime-monolith` | KEEP | `runtime-monolith` |
| A51 | `runtime-web-api` | SPLIT | `runtime-web-write`, `runtime-web-read` |
| A52 | `runtime-event-consumption-worker` | DELETE | ∅ |
| A53 | `runtime-command-result-consumption-worker` | DELETE | ∅ |
| A54 | `runtime-registration-result-consumption-worker` | DELETE | ∅ |
| A55 | `runtime-registration-consumption-worker` | DELETE | ∅ |
| A56 | `runtime-latest-known-version-consumption-worker` | DELETE | ∅ |
| A57 | `runtime-binding-consumption-worker` | DELETE | ∅ |
| A58 | `runtime-task-consumption-worker` | RENAME | `runtime-worker-materialize` |
| A59 | `runtime-command-consumption-worker` | RENAME | `runtime-worker-process` |
| A60 | `architecture-tests` | KEEP | `architecture-tests` |

## Code, configuration et retrait V2

- `contract-consumption` porte les capacités d'invocation, la participation et les SPI.
  Les variantes Burnable et NonBurnable sont explicites. `orchestrator-consumption`
  possède le noyau de protocole, la capacité mutable et les décisions L2. Les
  handles opaques restent liés à leur émetteur. `supra-polling-consumption` planifie
  des appels via les capacités et respecte la continuation confirmée par le protocole.
- Les transports HTTP utilisent les contrats d'admission et de requête. Les Composes
  fournissent les implémentations, la sécurité, les observations et les ressources.
  Le Monolith héberge explicitement les Composes et associe leurs observations locales.
  Les deux workers co-hébergés disposent de capacités et de ressources indépendantes.
- Process ouvre deux pools WRITE. Materialize ouvre un pool source WRITE en lecture
  seule et deux pools READ. Les adaptateurs source lisent directement les historiques,
  les événements et les terminaux. Les transactions de préparation WRITE et de
  finalisation READ restent des unités physiques distinctes.
- Web Read dispose d'un pool de deux consultations et d'un pool de maintenance à
  une connexion. Les principaux de consultation, de maintenance, de matérialisation,
  de lecture source et d'admission ont des grants explicites.
- Les POM historiques, les roots V2, les sélections Legacy, les assemblages V2 et
  les Compose Docker qui les lançaient ont été retirés. Les CLI DEV et le pilote
  inerte historique utiles aux preuves sont dans les sources de test. Les
  **64 ressources SQL historiques** ont un propriétaire unique et un SHA-256
  conservé. [Inventaire SQL](../testing/evidence/L7-full-execution/sql-migration-ownership.json)
- Le Dockerfile construit l'artefact correspondant à la racine sélectionnée. Le
  provisionnement et la reconstruction réappliquent les grants des nouveaux rôles
  après migration. [Commandes et configurations](../operations/cmd-start-runtimes.md)

## Qualification et régressions traitées

Les [reçus de référence](../testing/evidence/L7-full-execution/baseline-certifications.json)
décrivent les exécutions A interrompues ou en échec. Aucun commit ni push n'a
été considéré comme une qualification. La baseline mesurée `123b3fd2` intègre
les corrections d'admission séquentielle, de réserve Hikari et d'isolation
de la sonde de maintenance. Sa certification native complète reste distincte.

| Exécution | Résultat observé | Reçu |
|---|---|---|
| Tranches V3 initiales, `test` sur les trois anciennes ancres | Échec d'accès Docker en sandbox, puis échecs des assertions de progression et fixtures Link | `executions/initial-slices.log.gz`, `executions/initial-slices-docker.log.gz` |
| Retest initial Discovery/Identity/Link | 8 tests passés | `executions/baseline-corrections.log.gz` |
| Full A au SHA `96e0cd64` | 1 744 tests exécutés, 7 failures, 44 errors | Reçus de référence |
| Full A au SHA `1100b4ca` | Interrompu pendant la suite après identification des anciens oracles Hikari | Reçus de référence |
| Full A au SHA `747580e0` | Échec de l'oracle de restitution de capacité `i5_u02`. Les 13 tests du worker repassent après correction | Reçus de référence, `executions/baseline-worker-handoff-oracle.log.gz` |
| Dernières tranches A au SHA `706188b4` | 117 tests sur 118 dans l'ancien runtime Projection. Échec de l'observation asynchrone doublée avant le contrôle READ READY | `executions/baseline-late-slices.log.gz` |
| Compilation fraîche des cinq racines et artefacts B | 5 manifests Spring Boot vérifiés. Le premier packaging produisait des JARs simples à cause de `skip=true` hérité. Les 5 POM activent explicitement repackage | [Manifests et dépendances](../testing/evidence/L7-full-execution/packaging-b-executables.json) |
| Monolith Identity, 7 tests | PASS borné après fixture d'admission positionnée et garde des deux principaux SQL | `executions/integrated-bounded-1.log.gz` |
| Monolith, pilote inerte, Bruno B2 et HTTP | 6 tests passés dans la relance ciblée, dont les 24 scénarios B2. Les erreurs initiales de chemins Bruno et de résolution de beans ont été corrigées | `executions/integrated-bounded-2.log.gz` |
| Materialize, démarrage et pools | 5 tests passés dans le sous-ensemble de démarrage/paramètres. Les tests du principal source valident les droits effectifs sur PostgreSQL | `executions/architecture-runtime-2.log.gz`, `executions/architecture-runtime.log.gz` |
| Coexistence et Invitation, racines physiques | 1 test de coexistence et 2 tests Invitation passés. Reprise après échéance réelle des leases | `executions/monolith-1.log.gz` |
| Outillage de vérification et smoke | 35 tests de sélection/provenance et 8 oracles de smoke passés | `executions/final-python-verifier.log.gz`, `executions/smoke-oracles.log.gz` |
| Lanceurs de qualification I2–I5 | 29 tests de collecteurs passés et 17 sélections CLI contrôlées avec l'exécution Maven simulée | `executions/qualification-launchers.log.gz` |
| Gate Architecture ciblée sur le code intégré `e7938267` | 35 tests passés | `executions/release-architecture-e7938267.log.gz` |
| Identity, Pot, Process, métriques | 25 tests ciblés passés, dont les deux fenêtres de perte de réponse COMMIT PostgreSQL | `executions/integrated-bounded-4.log.gz` |
| Certification `e7938267` | Échec après 708 tests. Les 103 failures de Discovery, SlotNavigation et Lifecycle provenaient du chemin de fixture READ. Les deux errors concernaient le chemin SQL de `CommandExecutionPostgresTest#f05` et celui de la source de délégation L2. Les fichiers de test corrigés ont changé pendant cette exécution en échec | `certification-e7938267/result.json` |
| Reprises ciblées des oracles de chemins | Trois tests sur les fixtures de migration et un test de contrainte SQL passés | `executions/migration-path-oracles.log.gz`, `executions/constraint-source-oracle.log.gz` |
| Oracles historiques de quarantaine | `i5_r05` passé. `i5_r12` a mesuré 5,670 s pour une limite de 5 s, pendant l'exécution simultanée de la certification et d'une charge A/B | `executions/historical-quarantine-oracles.log.gz` |
| Certification `f31cc52d` | Échec après 908 tests, 1 failure, 1 error, aucun skip. PostgreSQL WRITE a passé ses 480 tests et les 44 tests HTTP sont passés. Les deux scénarios historiques `i5_r05` et `i5_r12` sont passés. Les 225 invocations après Process n'ont pas été exécutées dans cette tentative | `certification-f31cc52d/result.json` |
| Reprises ciblées Process | Les six coupures `i5_w14` ont passé. L'interleaving renouvellement a exposé les anciennes signatures de permis et de cycle de vie dans sa fixture. Après correction, l'omission `SKIP LOCKED` sur un deuxième PID, le CAS au même token et le drain ont passé | `executions/w14-six-physical-boundaries.tar.gz`, `executions/renewal-and-materialize.log.gz` |
| Reprise Materialize | 99 sur 100 tests au premier passage. `rcv01D` drainait le worker après la première projection Pot alors que trois checkpoints restaient ouverts. Après attente de l'oracle de convergence complet, `rcv01D` a passé avec les mêmes assertions après le drain | `executions/renewal-and-materialize.log.gz`, `executions/reconstruction-monolith-and-original-architecture.log.gz` |
| Reprise Monolith | 33 tests passés. Les prises de relais ont attendu les échéances PostgreSQL de 180 s ou 240 s selon le scénario | `executions/reconstruction-monolith-and-original-architecture.log.gz` |
| Gate Architecture actualisée | 47 tests passés. L'ancienne suite hexagonale échouait sur 38 règles qui exigeaient des classes, dépendances ou noms de V2. Les règles réécrites examinent les classes compilées V3 et exigent que les sélecteurs retrouvent des classes | `executions/requalified-architecture-final.log.gz` |
| Certification `cf31ff9e` | Échec après 908 tests, un échec et aucun skip. `i5_r05`, `i5_r12` et les six coupures `i5_w14` sont passés. Le pilote `SKIP LOCKED` a terminé sa seconde tentative 36 µs avant la fenêtre de polling attendue par son oracle. Les 180 invocations après Process restent hors de ce reçu | `certification-cf31ff9e/result.json` |
| Reprises ciblées de la fenêtre de polling | Trois exécutions PostgreSQL passées après alignement sur l'éligibilité réelle du renouvellement. Les seuils et les assertions de l'oracle sont inchangés | `executions/renewal-poll-1.log.gz`, `executions/renewal-poll-2.log.gz`, `executions/renewal-poll-3.log.gz` |
| Certification `966eb25a` avant maintien éveillé | Échec après 908 tests, cinq échecs et aucun skip. Le système est entré en hibernation à 1 % de batterie pendant `i5_w16` et a repris 800 s plus tard. Le worker est passé en drain après perte de connexions et six threads de cette fixture ont ensuite déclenché les quatre contrôles de refus de composition. `i5_r05`, `i5_r12` et l'interleaving `SKIP LOCKED` corrigé sont passés dans cette tentative | `certification-966eb25a/result.json`, `host-power-966eb25a.log.gz` |
| Certification finale sur secteur | **PASS**. 1 088 invocations dans 145 classes, zéro échec, zéro erreur, zéro skip, aucun écart de provenance. Temps Maven de 1 h 15 min 38 s. Les deux scénarios historiques, le test de verrou `SKIP LOCKED`, les tests de crash et d'inactivité du worker et le rejeu READ ont tous passé dans la même exécution | [Reçu final](../testing/evidence/L7-full-execution/certification-966eb25a-awake/result.json) |

Le full reactor a vérifié 480 tests dans l'infrastructure PostgreSQL WRITE,
44 tests HTTP de consultation et d'admission, 96 tests Process, 100 tests
Materialize, 33 tests Monolith et 47 tests du module Architecture. Les 288
autres invocations couvrent les modules amont et les contrats. La collecte
reconnaît 1 088 invocations attendues et 1 088 exécutées, sans quarantaine
active ni exclusion permanente ajoutée. [Plan et périmètre](../testing/evidence/L7-full-execution/certification-966eb25a-awake/plan.json)

Les fixtures utilisaient des positions absentes pour certaines admissions ou
introduisaient des migrations Link après V11. Elles écrivent désormais la demande
et sa position dans la même unité ou chargent les migrations dans leur ordre
canonique. Le quota SQL READ est maintenu à deux. Le polling distingue
l'acquisition directe de la page de parcours bornée et le test `i5_u02` attend la
restitution physique de capacité avant la nouvelle opportunité.

La [correspondance des règles hexagonales](../testing/evidence/L7-full-execution/hexagonal-v2-to-v3-oracles.csv)
recense les 55 anciennes méthodes. Parmi elles, 33 sont reprises par des
contrôles adaptés, 18 restent couvertes par des suites V3 actives et 4 étaient
spécifiques au graphe LatestKnownVersion de V2. L'absence de ce graphe et la
progression indépendante des participants restent vérifiées.

Les campagnes historiques `i5_r05` et `i5_r12` sont présentes dans le registre
avec leur historique de qualification. Elles font partie des obligations du
réacteur cible et restent sélectionnées sans exclusion. Le scénario voisin
`i5_r05_discovery_skip_locked_interleaving` utilisait une lease SPI là où le
Supra accepte la partition émise par son contrat. La fixture acquiert cette
partition, puis ouvre la lease sous-jacente uniquement pour ses vérifications
SQL. Elle obtient un vrai permis de renouvellement et ferme le worker avec
son API de drain. `i5_w14` bloquait un thread de Consumption pendant qu'il pouvait détenir
le verrou de la paire dont Discovery a besoin. L'injection attend désormais
une admission confirmée par la transaction de Discovery avant de signaler la
coupure. Les six fenêtres de crash et les contrôles de reprise restent requis.

## Consumption L2

| Périmètre | Oracles de la qualification |
|---|---|
| Burnable Command et Identity Request | Admission et positions séquentielles, tentative transactionnelle, terminalisation, publication et vérification après perte de l'accusé COMMIT dans les suites Process et WRITE |
| NonBurnable | Command Completion, Identity Request Completion, Pot Event et Identity Event. Les suites Materialize vérifient les mappings physiques, la progression indépendante, la préparation WRITE, la finalisation READ et le rejeu |
| Protocole commun | Suites Discovery, SlotNavigation, Lifecycle et WorkerResources pour leases, fencing, takeover, checkpoints, renouvellement, quarantaines et restitution physique de capacité |
| Indépendance d'autorité | Suites InterAuthority pour clôture du contexte WRITE avant finalisation READ, refus des transactions imbriquées, perte d'accusé, recovery et drain |

Les reprises ciblées de Command et Identity ont validé les deux fenêtres de
perte d'accusé PostgreSQL, avant et après COMMIT. Le rejeu READ et la reprise
après échéance de lease sont également passés lors des exécutions physiques
décrites dans ce rapport. Les résultats de certification non filtrée sont
traités séparément des reprises ciblées.

## PostgreSQL et HTTP

Sur la pile B, les cinq processus ont démarré avec PostgreSQL 17.11 et Keycloak
26.7.0 isolés. Le packaging de livraison a produit [cinq JARs exécutables et
leurs inventaires](../testing/evidence/L7-full-execution/release-executable-inventory-e7938267.json).
Le diff entre `e7938267` et `966eb25a` est vide dans `app/` hors des sources de
test. Les JARs ont donc été démarrés avec les sources productives certifiées.
Le build Docker de Materialize a produit le manifeste
`sha256:2c6c437030764d15a9f80d300bb657b5eae9b8eed7371a0142d111b91df9682f`.
Les cinq sondes de santé sont passées et le smoke authentifié a obtenu les dix
résultats READ attendus pour dix Commands. Le profil Monolith READ est devenu
prêt. Un démarrage isolé avec le profil `legacy` a rejeté cette sélection.
[Journaux d'exécution](../testing/evidence/L7-full-execution/executions/),
[reçu du smoke](../testing/evidence/L7-full-execution/release-smoke/).

Les [ACL WRITE](../testing/evidence/L7-full-execution/postgres-b-write-authority.json)
et [ACL READ](../testing/evidence/L7-full-execution/postgres-b-read-authority.json)
confirment les droits SELECT attendus et l'absence de droits de mutation sur les
tables vérifiées. Le principal source ne lit pas le token de la table Command.
Le principal de statut ne consulte pas les tables métier. Le JAR Materialize
exclut les mutations WRITE et Hibernate.

Le précontrôle `v3-rebuild-read.py --dry-run` et la reconstruction physique READ
ont abouti sur PostgreSQL jetable. Le premier rejeu a convergé, puis sa sonde
HTTP anonyme a interrogé l'ancien port fixe 8080. La sonde utilise désormais
le port publié du service READ dans le même modèle Compose. Le rejeu suivant
a atteint 28 checkpoints sur 28 en 23 secondes, zéro slot, zéro quarantaine,
puis `READY/IDLE`. Les vecteurs SQL WRITE et les comptes READ sont identiques
avant et après. Le service READ reconstruit a répondu 401 à la requête anonyme
et 200 aux lectures authentifiées du binding, de la liste de 25 Pots et d'un
Pot individuel. [Vecteurs et réponses](../testing/evidence/L7-full-execution/release-reconstruction/)

## Performance A/B

A utilise le checkout `123b3fd2`, la référence V3 avant migration avec les
corrections E00. La certification native A complète reste en échec. B utilise
`779d7e3e` avec le [diff de packaging et préflight exécuté](../testing/evidence/L7-full-execution/packaging-b-worktree.patch).
Les ajustements ultérieurs de sélection au démarrage et de fixtures n'ont pas
changé les chemins mesurés. Les deux piles ont tourné successivement sur le même
hôte arm64, avec PostgreSQL 17.11, Keycloak 26.7.0, le même JRE et les mêmes
limites Docker par processus. Le moteur Docker disposait de 10 CPU logiques et
d'environ 7,75 Gio. Les autres services du poste sont restés actifs, avec un
recouvrement partiel des tests Maven. Les identifiants et valeurs d'horloge
des fixtures sont propres à chaque exécution. Les paramètres et empreintes sont
dans les [conditions expérimentales](../testing/evidence/L7-full-execution/performance/conditions.json).

Le banc reprend les profils H1–H5 de L7/I2 et les fenêtres courtes à 1, 10 et
50 commandes/s. Un palier H1 soutenu de 1 200 commandes à 10/s complète les
mesures. Les deux côtés ont admis 2 140 commandes, retrouvé 2 140 résultats READ
et satisfait 2 140 oracles HTTP du banc. Le p95 ci-dessous mesure la disponibilité
des projections C-03 selon les marqueurs internes. Les seuils de référence
500 ms, 1 000 ms et 3 000 ms viennent de [SPEC-09](SPEC-09_Performance.md).
Le pool du banc contient des Pots de 20 shareholders et des historiques évolutifs,
avec un oracle HTTP sur la balance historique. La campagne n'établit pas à elle
seule la qualification du Pot moyen à 200 dépenses et de la première lecture
complète au sens de SPEC-09.

| Campagne | Cmd/s offertes | Commandes par côté | A p95 ms | B p95 ms | B−A ms | B−A relatif | Seuil de référence ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| H1 initial | 1 | 30 | 231.05 | 227.00 | -4.05 | -1.8 % | 500 |
| H1 initial | 10 | 60 | 402.10 | 1478.95 | +1076.85 | +267.8 % | 1000 |
| H1 initial | 50 | 100 | 1154.35 | 1223.10 | +68.75 | +6.0 % | 3000 |
| H1 répétition | 1 | 30 | 2350.20 | 623.35 | -1726.85 | -73.5 % | 500 |
| H1 répétition | 10 | 60 | 388.30 | 313.25 | -75.05 | -19.3 % | 1000 |
| H1 répétition | 50 | 100 | 1018.50 | 1368.20 | +349.70 | +34.3 % | 3000 |
| H1 120 s | 10 | 1200 | 3017.15 | 1567.90 | -1449.25 | -48.0 % | 1000 |
| H2 | 1 | 30 | 271.10 | 389.55 | +118.45 | +43.7 % | 500 |
| H2 | 10 | 60 | 1910.90 | 1603.90 | -307.00 | -16.1 % | 1000 |
| H3 | 1 | 30 | 1265.40 | 640.80 | -624.60 | -49.4 % | 500 |
| H3 | 10 | 60 | 600.05 | 586.15 | -13.90 | -2.3 % | 1000 |
| H3 | 50 | 100 | 2092.05 | 2189.15 | +97.10 | +4.6 % | 3000 |
| H4 | 1 | 30 | 2522.25 | 1166.25 | -1356.00 | -53.8 % | 500 |
| H4 | 10 | 60 | 1310.90 | 2176.20 | +865.30 | +66.0 % | 1000 |
| H4 | 50 | 100 | 2178.95 | 2359.55 | +180.60 | +8.3 % | 3000 |
| H5 | 1 | 30 | 2895.55 | 1351.10 | -1544.45 | -53.3 % | 500 |
| H5 | 10 | 60 | 818.25 | 720.65 | -97.60 | -11.9 % | 1000 |

Les [quantiles et écarts](../testing/evidence/L7-full-execution/performance/latencies.csv)
donnent p50, p95 et p99 pour l'admission, la découverte, l'exécution, le résultat,
la projection et la lecture client. Le [relevé des campagnes](../testing/evidence/L7-full-execution/performance/campaigns.csv)
donne débits, nombres corrélés, contrôles des positions et maxima de commandes
en attente déduits des marqueurs. Les [populations](../testing/evidence/L7-full-execution/performance/profile-populations.json)
confirment en H5 100 000 commandes terminales, 2 000 commandes en quarantaine,
500 requêtes d'identité en quarantaine et 1 000 000 de positions d'événement
avant charge de mesure sur chaque côté. Les [ressources](../testing/evidence/L7-full-execution/performance/resource-peaks.json)
donnent les maxima de pools, CPU et mémoire échantillonnés. Les mesures et
[empreintes](../testing/evidence/L7-full-execution/performance/raw-campaigns-sha256.json)
sont archivées avec les [reçus bruts](../testing/evidence/L7-full-execution/performance/raw-campaigns.tar.gz).

Au palier soutenu, A matérialise 9,98 résultats/s et B 9,99 résultats/s. B réduit
le p95 de projection de 3 017,15 à 1 567,90 ms, avec 18 commandes admises en
attente d'exécution au maximum contre 23 sur A. Sur ce même palier, le p95
mesuré depuis la requête cliente jusqu'à la lecture C-03 passe de 5 358,36 à
2 326,10 ms (−56,59 %). Cette mesure inclut le temps de la requête d'admission
et le délai de sondage de la lecture. Le CPU PostgreSQL médian est
proche du quota d'un cœur dans les deux essais. Le maximum mesuré de connexions
en attente sur le pool de statut passe de 2 à 1. Ce palier dépasse le seuil de
projection de 1 s des deux côtés. À 10/s en H1 court, B dépasse 1 s au premier
essai puis mesure 313,25 ms à la répétition. En H4 à 10/s, B dépasse A de
865,30 ms sur une seule mesure. Ces écarts, la charge de fond et les différences
de population physique empêchent d'établir une garantie de non-régression
générale. Le grand Pot, le rattrapage de 10 000 commandes et le p95 Identity
au sens de SPEC-09 n'ont pas été requalifiés par ce banc.

**Performance avec réserves.** Les résultats A/B sont mesurés et archivés.
Les dépassements de seuil et la variabilité restent ouverts.

## Publication

`git diff --check` et la vérification du catalogue ont passé avant publication.
Les changements du worktree étaient limités au rapport et au reçu final. Le
dernier fetch avant publication a confirmé `origin/feat/v3-architecture` au
SHA initial, sans commit distant supplémentaire. Le commit de publication
contient ce rapport et le reçu final, avec une mise à jour Git fast-forward.
