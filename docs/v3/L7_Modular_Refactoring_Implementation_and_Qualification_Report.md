# Pocoma V3 — L7 — Implémentation et qualification

Mandat `GO_L7_MODULAR_REFACTORING_FULL_EXECUTION`. Branche `feat/v3-architecture`.

**Qualification en cours.** Le verdict de clôture dépend du réacteur non filtré et de la
campagne A/B. Les résultats ci-dessous sont bornés à leurs exécutions.

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
fixtures. L'intégration conserve les deux historiques. Les SHA de code certifié
et de publication seront relevés après qualification.

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

Le reçu final utilise `python3 -B scripts/testing/verify.py CERTIFICATION --output …`
depuis la racine. Ce mode invoque le cycle natif `./mvnw --batch-mode --no-transfer-progress test`
et établit la provenance de ses rapports dans un dossier neuf.

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

Les fixtures utilisaient des positions absentes pour certaines admissions ou
introduisaient des migrations Link après V11. Elles écrivent désormais la demande
et sa position dans la même unité ou chargent les migrations dans leur ordre
canonique. Le quota SQL READ est maintenu à deux. Le polling distingue
l'acquisition directe de la page de parcours bornée et le test `i5_u02` attend la
restitution physique de capacité avant la nouvelle opportunité.

Les vérifications d'architecture et les nouvelles preuves physiques L2 et READ
sont en cours de consolidation. Les campagnes historiques `i5_r05` et
`i5_r12` restent dans le registre avec un statut historique qualifié. Elles font
partie des obligations du réacteur cible, sans exclusion ajoutée.

## PostgreSQL et HTTP

Sur la pile B, les cinq processus ont démarré avec PostgreSQL 17.11 et Keycloak
26.7.0 isolés. Les [ACL WRITE](../testing/evidence/L7-full-execution/postgres-b-write-authority.json)
et [ACL READ](../testing/evidence/L7-full-execution/postgres-b-read-authority.json)
confirment les droits SELECT attendus et l'absence de droits de mutation sur les
tables vérifiées. Le principal source ne lit pas le token de la table Command.
Le principal de statut ne consulte pas les tables métier. Le JAR Materialize
exclut les mutations WRITE et Hibernate. Le précontrôle `v3-rebuild-read.py --dry-run`
est passé sur un PostgreSQL jetable. Le réacteur qualifie la reconstruction et
la résolution transactionnelle avec des fixtures physiques.

## Performance A/B

Le protocole reprend les profils H1–H5 et les paliers L7/I2, ainsi que le seuil
de `SPEC-09` pour Pot moyen de 500 ms, 1 s et 3 s au p95 pour 1, 10 et 50 cmd/s.
Les durées observées jusqu'au résultat et à la disponibilité du READ sont
mesurées par commande. Le client utilise une horloge distincte des durées
monotones internes et mesure aussi le délai visible de bout en bout.

A utilise le checkout `123b3fd2`. B utilise le checkout `779d7e3e` avec le
[diff de packaging et préflight exécuté](../testing/evidence/L7-full-execution/packaging-b-worktree.patch).
Les deux piles sont isolées des services existants et utilisent le même hôte, le
même JRE, les mêmes limites Docker par service, PostgreSQL 17.11, Keycloak 26.7.0,
les mêmes participants, les mêmes profils de données et des ports identiques
réutilisés séquentiellement. Les différences de pools sont liées aux autorités
retirées ou séparées par L7. Les tables A/B, la variabilité et les réserves seront
consolidées à la fin des répétitions. Aucun verdict performance n'est attribué ici.

## Publication

La vérification finale inclut `git diff --check`, l'inventaire du réacteur,
la revue des reçus, l'état du worktree et la synchronisation avec
`origin/feat/v3-architecture`. Le dernier fetch contrôlé pointe toujours sur le
SHA initial. Le push final reste à effectuer.
