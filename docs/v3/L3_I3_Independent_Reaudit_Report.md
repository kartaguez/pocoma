# Rapport d’audit contradictoire indépendant — Contre-vérification des résolutions L3/I3

> Publication documentaire du 31 octobre 2026. Rapport indépendant de **Gemini**,
> transmis par le responsable du programme pour la clôture I3. Le texte ci-dessous
> est extrait du rapport fourni après le journal d'activité ; seuls les liens
> locaux `file:///tmp/pocoma-l3-i3-reaudit/` sont adaptés au dépôt et une fin de
> ligne finale est ajoutée. Le journal d'activité n'est pas reproduit.
> SHA-256 de la pièce complète reçue : `7a6067e543418a7a3c1ea94ee34b0b4d8f0f12508c1dbc829c7a2c12ece56e26`.
> SHA-256 du corps original extrait (UTF-8, avant adaptation) : `08c2e46d073344e8d3554f0e7ab43e1c6e1c81999e1e0e686b0216767c7d9280`.
>
> Les observations et exécutions ci-dessous sont attribuées à Gemini, sans
> réexécution par cette publication. Les scripts propres au ré-audit
> (`test_ct002_all_8.py`, `test_ct003.py`, `test_ct004.py`), journaux bruts et XML
> indépendants évoqués dans le texte n'ont pas été joints à la pièce reçue et ne
> sont pas présentés comme archivés ici. Les preuves Codex préexistantes restent
> accessibles dans le [dossier de résolution](../testing/evidence/L3-I3-resolution/README.md).
> Le verdict du ré-audit porte sur les quatre findings et le profil DEV fermé ;
> il ne vaut pas PASS canonique. La [décision humaine](L3_I3_Acceptance_Decision.md)
> fixe l'acceptation, les réserves et le seul GO de cadrage/préparation I4.

**Verdict : CONTRADICTORY RE-AUDIT PASS.**

Ce rapport constitue la contre-vérification indépendante des corrections apportées par Codex aux quatre findings de l’audit contradictoire I3 initial ([`L3_I3_Contradictory_Audit_Report.md`](L3_I3_Contradictory_Audit_Report.md)).

L'ensemble des contre-exemples adversariaux originaux a été rejoué dans un environnement PostgreSQL 17.11 indépendant et confronté au code corrigé. Les deux findings majeurs (**CT-002**, **CT-003**) sont **neutralisés physiquement** sans possibilité de contournement résiduel dans le profil DEV fermé, et les deux findings mineurs (**CT-001**, **CT-004**) sont **convenablement circonscrits et traités**, sans régression sur les garanties architecturales ou métier préexistantes.

---

## A. Périmètre et environnement

### Références Git auditées
- **SHA initial audité** : `2b3567b1f77051c399772ee7ac787f7dd61ba407` (verdict initial : *FAIL — CORRECTIONS REQUIRED*).
- **SHA du correctif gelé** : `c1172bf4a758a9077415d827ca88b359b6360ebb`.
- **SHA de publication documentaire et des preuves** : `08febf1c6ec56e4ee052d14b7b2e18d4356437e3`.
- **Checkout audité** : clone local isolé sous `/tmp/pocoma-l3-i3-reaudit`, HEAD détachée à `08febf1c`.
- **Dépôt d'origine** : `/Users/julien.guezennec/Dev/projects/pocoma` préservé intact sans modification ni commit.

### Déclaration de politique de vérification (`Reactor_Verification_Policy.md`)
Conformément à la directive normative :
- **Slice primaire déclarée** : `COMMAND` (ancre `runtime-command-consumption-worker`), avec vérification directe de la seconde racine `runtime-monolith`.
- **Slices secondaires** : `NONE`.
- **Global Architecture Gate** : `NOT REQUIRED` pour cet audit ciblé (les preuves Codex et les écarts de bytecode documentés ont été inspectés statiquement et qualifiés).
- **Full reactor** : `NOT REQUIRED / NOT RUN` (aucune modification de POM, de contrat Maven transverse ou d'intégration `main`).
- **Franchissement inattendu** : aucun.
- **Base de données** : CURRENT V3 WRITE (V1–V6) et READ technique (V1) sur conteneur PostgreSQL jetable dédié.

### Environnement d'exécution indépendant
- **Hôte** : macOS 27.0 (Darwin 24.3.0 aarch64), Temurin OpenJDK 21.0.0+35-LTS, Maven 3.9.14.
- **Serveur de test** : conteneur Docker dédié `pocoma-l3-i3-independent-reaudit` exécutant l'image canonique exacte `postgres:17-alpine` (identifiant inspecté `18cfe3ef5e68`, digest `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`), lancé sur port isolé `127.0.0.1:55449` avec :
  `-c "log_line_prefix=%m [%p] xid=%x state=%e "`
- **Bases et rôles isolés** : bases `audit_write` et `audit_read`, schéma `audit`, rôles sans privilèges système `audit_w` (WRITE Business & Control), `audit_r` (READ), `audit_a` (réactivateur), `audit_i` (injecteur DEV), propriétaire administratif `postgres`.

---

## B. Matrice des findings

| Finding | Gravité initiale | Résultat après correction | Verdict ré-audit |
|---|---|---|---|
| **CT-002** | **MAJOR** | Tous les chemins d'accès indirects (vues, vues matérialisées, fonctions SECURITY DEFINER, SQL dynamique, autre schéma, PUBLIC, appartenance indirecte, `NOINHERIT` + `SET ROLE`) sont systématiquement refusés par le préflight et les scripts administratifs. Le Worker refuse physiquement de démarrer. | **CLOSED** |
| **CT-003** | **MAJOR** | Toute tentative de grant `DELETE`, `TRUNCATE` ou `TRIGGER` sur les 7 tables Identity aux rôles auxiliaires (directe, PUBLIC, héritée ou via `SET ROLE`) est bloquée à la validation administrative. PostgreSQL refuse physiquement l'opération (`42501`) sous profil normal ; les données restent intactes après commit ou rollback. | **CLOSED** |
| **CT-001** | **MINOR** | L'inaccessibilité du contournement est démontrée sur les deux racines livrées (Worker et Monolithe) qui utilisent exclusivement `JpaTransactionManager` sans sélecteur de substitution. Tout holder JDBC prélié est rejeté avant invocation du callback métier. | **CLOSED WITH SCOPE LIMITATION** |
| **CT-004** | **MINOR** | La politique de journalisation PostgreSQL (`terse`, suppression des DETAIL sensibles sur `23505` et `23514`) est appliquée par `worker-dev.py`, vérifiée par le préflight, et les surcharges de rôles sont interdites. Les métadonnées techniques (SQLSTATE, PID, XID) sont conservées sans exposition des secrets. | **CLOSED WITH SCOPE LIMITATION** |

---

## C. Preuves matérielles indépendantes

### 1. CT-002 — Accès indirect aux tokens et contexte d’admission

Huit contre-exemples indépendants ont été rejoués sur la base `audit_write` préparée avec les migrations V1–V6. Pour chaque scénario, l'accès direct a été accordé temporairement pour vérifier la faisabilité PostgreSQL de l'attaque avant détection, puis la détection par les scripts administratifs et par `V3BootstrapGuard` a été contrôlée, suivie de la vérification de l'acceptation de la configuration légitime après nettoyage.

| N° | Scénario adversarial | Accès PG avant contrôle | Script administratif (`grant-l2-discovery.sql`) | Préflight Java (`V3BootstrapGuard`) | Contrôle exact responsable du refus |
|---|---|---|---|---|---|
| **1** | Vue propriétaire sur `v3_eui_version.binding_token` | **AUTORISÉ** (agrégat > 0) | **REFUSÉ** (SQLSTATE `42501`) | **REFUSÉ** (`IllegalStateException`) | Requête catalogue : `c.relkind in ('v','m') and has_table_privilege(reader, c.oid, 'SELECT')` |
| **2** | Vue propriétaire sur `v3_identity_request.admission_context` | **AUTORISÉ** (agrégat > 0) | **REFUSÉ** (SQLSTATE `42501`) | **REFUSÉ** (`IllegalStateException`) | Requête catalogue : `c.relkind in ('v','m') and has_table_privilege(reader, c.oid, 'SELECT')` |
| **3** | Fonction SQL `SECURITY DEFINER` dans `audit_aux` exposant les tokens | **AUTORISÉ** (données retournées) | **REFUSÉ** (SQLSTATE `42501`) | **REFUSÉ** (`IllegalStateException`) | Requête catalogue : `p.prosecdef and n.nspname not in ('pg_catalog','information_schema')` |
| **4** | Fonction PL/pgSQL avec SQL dynamique (`EXECUTE 'select binding_token...'`) | **AUTORISÉ** (données retournées) | **REFUSÉ** (SQLSTATE `42501`) | **REFUSÉ** (`IllegalStateException`) | Détection systématique de `prosecdef = true` sans dépendre d'une analyse statique du corps |
| **5** | Vue située dans un autre schéma (`audit_aux.exposed`) | **AUTORISÉ** (agrégat > 0) | **REFUSÉ** (SQLSTATE `42501`) | **REFUSÉ** (`IllegalStateException`) | Contrôle multi-schémas : `nspname not in ('pg_catalog','information_schema')` |
| **6** | Vue matérialisée accessible par `PUBLIC` | **AUTORISÉ** (agrégat > 0) | **REFUSÉ** (SQLSTATE `42501`) | **REFUSÉ** (`IllegalStateException`) | `has_table_privilege` évalue les privilèges effectifs incluant le pseudo-rôle `PUBLIC` |
| **7** | Rôle READ avec appartenance indirecte (`grant audit_group to audit_r`) | **AUTORISÉ** (agrégat > 0) | **REFUSÉ** (SQLSTATE `42501`) | **REFUSÉ** (`IllegalStateException`) | Requête catalogue : `exists(select 1 from pg_roles where rolname<>reader and pg_has_role(reader,oid,'MEMBER'))` |
| **8** | Rôle READ `NOINHERIT` suivi de `SET ROLE audit_reader` | **AUTORISÉ après SET ROLE** | **REFUSÉ** (SQLSTATE `42501`) | **REFUSÉ** (`IllegalStateException`) | `pg_has_role(..., 'MEMBER')` inspecte l'appartenance indépendamment de l'attribut `INHERIT` |

#### Vérification du démarrage du Worker
L'invocation réelle de `PocomaCommandConsumptionWorkerApplication` en profil DEV avec une vue propriétaire exposée (`IndependentAudit boot-view`) échoue immédiatement lors de la phase de post-processing Spring (`V3ConsumptionWorkerConfiguration$Preflight`) :
```text
java.lang.IllegalStateException: L2 pilot refused: Identity indirect exposure or unsafe READ principal on WRITE
    at com.kartaguez.pocoma.runtime.consumption.v3.V3BootstrapGuard.require(V3BootstrapGuard.java:809)
    at com.kartaguez.pocoma.runtime.consumption.v3.V3BootstrapGuard.validateIdentitySource(V3BootstrapGuard.java:447)
```
Le Worker refuse catégoriquement de démarrer. Une fois l'objet non autorisé révoqué et supprimé, le préflight et le démarrage Spring réussissent (`AUDIT B.preflight PASS`).

---

### 2. CT-003 — Contrôle des rôles auxiliaires et immuabilité de l’historique

#### Rejeu des deux scénarios destructifs initiaux
1. **Scénario réactivateur (`audit_a`) sur `v3_identity_event`** :
   - `GRANT TRUNCATE ON audit.v3_identity_event TO audit_a` appliqué par l'administrateur.
   - Exécution de `grant-l2-discovery.sql` : **ÉCHEC immédiat** avec `ERROR: unsafe Identity rights` (SQLSTATE `P0001` / exit code 3).
   - Révocation effective de l'excès (`REVOKE TRUNCATE`).
   - Réexécution de `grant-l2-discovery.sql` : **SUCCÈS** (exit code 0).
   - Connexion directe sous `audit_a` et exécution de `BEGIN; TRUNCATE audit.v3_identity_event; COMMIT;` : **REFUS PHYSIQUE POSTGRESQL** avec `ERROR: 42501`.
   - Relecture administrative : 2 événements initiaux présents, **0 ligne supprimée** (intégrité préservée).
2. **Scénario injecteur (`audit_i`) sur `v3_identity_completion_event`** :
   - `GRANT TRUNCATE ON audit.v3_identity_completion_event TO audit_i` appliqué.
   - Exécution de `grant-l2-worker-dev.sql` : **ÉCHEC immédiat** avec `ERROR: unsafe Identity internal injector table rights on v3_identity_completion_event` (SQLSTATE `42501` / exit code 3).
   - Révocation effective de l'excès.
   - Réexécution de `grant-l2-worker-dev.sql` : **SUCCÈS** (exit code 0).
   - Connexion directe sous `audit_i` et exécution de `BEGIN; TRUNCATE audit.v3_identity_completion_event; COMMIT;` : **REFUS PHYSIQUE POSTGRESQL** avec `ERROR: 42501`.
   - Relecture administrative : 1 événement de complétion initial présent, **0 ligne supprimée** (intégrité préservée).

#### Matrice complète 2 rôles × 7 tables × 3 privilèges
L'ensemble des 42 combinaisons (rôles `audit_a` et `audit_i` sur les 7 tables Identity pour `DELETE`, `TRUNCATE`, `TRIGGER`) a été éprouvé :
- **42 tentatives sur 42 ont été rejetées** par les scripts administratifs de certification (`grant-l2-discovery.sql` et `grant-l2-worker-dev.sql`).
- Les grants vers `PUBLIC` sur les 7 tables (21 vérifications) sont **tous rejetés**.
- Les privilèges destructifs hérités via un rôle parent (`audit_aux_parent`) (8 vérifications) sont **tous rejetés**.
- L'attribution indirecte avec `NOINHERIT` suivie de `SET ROLE` (2 vérifications) est **rejetée**.

#### Analyse de l'ordre d'exécution des scripts et frontière de confiance
- L'inversion de l'ordre d'exécution (`grant-l2-worker-dev.sql` puis `grant-l2-discovery.sql` et inversement) ne permet aucun contournement : chaque script applique des assertions strictes sur son rôle dédié (`audit_a` dans Discovery, `audit_i` dans Worker DEV).
- **Distinction primordiale** : Le refus d'un script administratif fait échouer la certification de l'environnement DEV mais **ne révoque pas magiquement** un privilège déjà accordé dans le moteur PostgreSQL. L'obligation de provisionnement impose à l'administrateur d'exécuter un `REVOKE` explicite avant de relancer la certification. En fonctionnement normal certifié, le refus physique `42501` de PostgreSQL empêche toute mutation destructive.

---

### 3. CT-001 — Composition transactionnelle et durée de vie du holder JDBC

#### Examen des compositions Spring livrées
1. **Gestionnaire de transactions construit** :
   Dans [`V3ConsumptionWorkerConfiguration.java#L341-L344`](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java#L341-L344), l'autorité WRITE Business est instanciée avec `JpaTransactionManager` lié à `LocalContainerEntityManagerFactoryBean` :
   ```java
   var manager = new JpaTransactionManager(Objects.requireNonNull(emf.getObject()));
   manager.setDataSource(writeBusiness);
   manager.setNestedTransactionAllowed(false);
   writeEffects = new SpringAttemptTransactions(writeBusiness, manager, 60);
   ```
2. **Absence de substitution possible** :
   Aucun profil, sélecteur Spring ou conditionnel `@ConditionalOnMissingBean` ne permet de remplacer ce gestionnaire par `DataSourceTransactionManager` pour `writeEffects`. `DataSourceTransactionManagerAutoConfiguration` est explicitement exclue dans `application-v3-consumption-worker-dev.properties` et `application-v3-consumption-pilot.properties`.
3. **Comportement du gestionnaire JPA face à un holder prélié** :
   Le test unitaire dédié [`l3_delivered_jpa_rejects_prebound_holder`](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3IdentityRequestCompositionPostgresTest.java#L781) et le rejeu indépendant démontrent que si un `ConnectionHolder` JDBC est attaché à la source avant l'appel (`TransactionSynchronizationManager.bindResource`), `JpaTransactionManager.doBegin()` lève immédiatement une exception avant même que le callback applicatif ne soit atteint :
   `A.actual.JPA.prebound: REFUSED before callback; CannotCreateTransactionException`
4. **Discipline du participant métier** :
   Dans `ExecuteIdentityRequest.finalizeIn`, l'appel à `authority.lockInCanonicalOrder(keys)` est toujours exécuté avant `authority.current(req.target())`, garantissant la pose effective du verrou PostgreSQL (`55P03` en observation concurrente) lors de chaque tentative légitime.
5. **Rejeu du contre-exemple initial** :
   L'exécution de `IndependentAudit retained` sur une composition manuelle `DataSourceTransactionManager` reproduit toujours l'anomalie Java :
   `A.retained.current ALLOWED_WITHOUT_PHYSICAL_LOCK (T1 holder=823914581, T2 holder=823914581)`
   Ce contre-exemple reste physiquement possible dans une composition découplée non livrée.

**Conclusion CT-001** : L'inaccessibilité du défaut au sein des runtimes livrés (Worker et Monolithe) étant rigoureusement prouvée, la décision est **CLOSED WITH EXPLICIT SCOPE LIMITATION**. Toute future tentative d'introduire un gestionnaire JDBC direct pour `writeEffects` devra obligatoirement lever cette réserve.

---

### 4. CT-004 — Confidentialité des diagnostics PostgreSQL

#### Vérification du dispositif technique
1. **Configuration SQL** :
   [`scripts/v3/configure-l3-identity-logging.sql`](../../scripts/v3/configure-l3-identity-logging.sql) applique sur la base `audit_write` les réglages de niveau base :
   `log_error_verbosity = 'terse'`, `log_min_messages = 'warning'`, `log_min_error_statement = 'panic'`, `log_statement = 'none'`, `log_parameter_max_length = 0`, `log_parameter_max_length_on_error = 0`.
2. **Raccordement administratif** :
   `scripts/v3/worker-dev.py prepare` injecte automatiquement cette étape en position 0 avant les scripts d'autorisations lorsque `identity_authority == 'real'` et `authority == 'write'`.
3. **Prise en compte par session** :
   Les directives `ALTER DATABASE ... SET` s'appliquent aux nouvelles connexions. Les pools existants doivent être redémarrés.
4. **Contrôle préflight** :
   `V3BootstrapGuard` exécute une requête SQL au démarrage vérifiant que chaque paramètre effectif de session correspond exactement à la politique requise ainsi que la présence de `%e`, `%p`, `%x` dans `log_line_prefix`.
5. **Rejet des surcharges de rôles** :
   Si un rôle tente de restaurer la verbosité (`ALTER ROLE audit_w SET log_error_verbosity='default'`), le préflight échoue immédiatement avec :
   `IllegalStateException: L2 pilot refused: Identity PostgreSQL logging policy required`.

#### Épreuve adversariale de collision et violation de contrainte
Un test indépendant (`test_ct004.py`) a injecté deux pannes avec des marqueurs synthétiques uniques :
1. Collision UNIQUE sur `v3_eui_version.binding_token` avec `AUDIT_SYNTHETIC_TOKEN_ONLY` via l'autorité Java.
2. Violation CHECK sur `v3_identity_request` (opération `INVALID_OP` contenant un contexte d'admission synthétique privé `SYNTHETIC_REAUDIT_PRIVATE_CONTEXT_...`).

L'inspection en mémoire des journaux du conteneur Docker a démontré :
- **Absence totale du token synthétique** dans les logs serveur (`token_leaked = False`).
- **Absence totale du contexte d'admission** dans les logs serveur (`context_leaked = False`).
- **Conservation des diagnostics techniques d'erreur** :
  - `state=23505` (UNIQUE violation) présent avec PID et XID.
  - `state=23514` (CHECK violation) présent avec PID et XID.
  - Ligne de préfixe `[%p]` et `xid=%x` conservée pour la corrélation technique.

**Conclusion CT-004** : Le diagnostic PostgreSQL d'erreur est purgé de ses détails sensibles sur le canal standard stderr/Docker tout en conservant les métadonnées de corrélation. Le finding est **CLOSED WITH EXPLICIT SCOPE LIMITATION** (cette garantie s'applique à la base de données qualifiée et ne dispense pas d'une qualification propre pour les agents de collecte ou d'audit tiers en environnement de production).

---

## D. Vérification de non-régression

L'analyse intégrale du diff entre le SHA initial `2b3567b1` et le SHA corrigé `c1172bf4` confirme la stricte localisation des modifications :
1. **Sources productives applicatives** :
   - Une seule classe Java modifiée : [`V3BootstrapGuard.java`](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3BootstrapGuard.java) (+41 lignes de contrôles SQL).
   - Aucun changement dans `JdbcIdentityAuthority.java`, `SpringAttemptTransactions.java`, `ExecuteIdentityRequest.java`, ni dans les entités ou oracles du domaine Identity.
2. **Sources SQL et migrations** :
   - La migration V6 [`V6__identity_authority_and_requests.sql`](../../app/infra-persistence-primary-jpa/src/main/resources/db/v3-identity/migration/V6__identity_authority_and_requests.sql) est **strictement inchangée** (aucune altération des triggers d'immuabilité ni des contraintes).
   - Ajout du script administratif `configure-l3-identity-logging.sql` (+30 lignes).
   - Renforcement des clauses d'inspection dans `grant-l2-discovery.sql`, `grant-l2-lifecycle.sql`, et `grant-l2-worker-dev.sql`.
3. **Fonctionnement légitime préservé** :
   - Les nouveaux contrôles n'empêchent en rien le fonctionnement d'un environnement DEV sécurisé et conforme.
   - Les suites complètes de non-régression exécutées via Maven valident sans aucune régression :
     - `V3IdentityRequestCompositionPostgresTest` : **8 tests exécutés, 0 échec, 0 erreur, 0 sauté** (durée : 71,46 s).
     - `V3IdentityRequestMonolithPostgresTest` : **5 tests exécutés, 0 échec, 0 erreur, 0 sauté** (durée : 57,75 s).

---

## E. Qualification des tests et des déclarations Codex

L'examen du dossier de preuve [`docs/testing/evidence/L3-I3-resolution/`](../testing/evidence/L3-I3-resolution/) corrobore point par point les déclarations de Codex :
1. **Gate non filtrée** :
   L'exécution amont `./mvnw -pl runtime-command-consumption-worker,runtime-monolith,architecture-tests -am test` a échoué sur la réserve historique connue `V3WorkerResourcesPostgresTest.i5_r12:896` (délai de fermeture runtime), ce qui a provoqué le statut `SKIPPED` du module downstream `architecture-tests`.
2. **Écarts de bytecode documentés** :
   L'archive `architecture-stale-output.tar.gz` conserve les 2 échecs initiaux sur 162 contrôles d'architecture. L'examen des fichiers `javap` comparatifs confirme que ces échecs provenaient d'artefacts compilés obsolètes dans le répertoire `target/` préexistant (`MissingTerminalConsumptionFailureException` dans un ancien package, et nommage de lambda `lambda$0` au lieu de `lambda$append$0`).
3. **Vérification en build frais** :
   Une compilation propre à partir du code source exact confirme que les 162 tests d'architecture et les 70 tests Identity/DEV réussissent intégralement (232 tests PASS).
4. **Cohérence globale** :
   Codex n'a pas prétendu avoir franchi une gate canonique globale ni sollicité indûment le full reactor. Les résultats présentés sont authentiques, transparents et vérifiables.

---

## F. Recommandations et conclusion pour I3

### Réponses explicites aux cinq questions du mandat :
1. **Clôture des findings** :
   Les quatre findings de l'audit contradictoire initial sont formellement **clôturés** :
   - `AUD-L3-I3-CT-002` : **CLOSED**
   - `AUD-L3-I3-CT-003` : **CLOSED**
   - `AUD-L3-I3-CT-001` : **CLOSED WITH SCOPE LIMITATION**
   - `AUD-L3-I3-CT-004` : **CLOSED WITH SCOPE LIMITATION**
2. **Acceptabilité des corrections Codex** :
   Les solutions mises en œuvre (politique conservatrice du préflight sur la base WRITE, allowlists d'attributs de colonnes et refus strict `DELETE`/`TRUNCATE`/`TRIGGER` pour les auxiliaires, purge de verbosité `terse` PostgreSQL) sont **parfaitement acceptables**, robustes et proportionnées.
3. **Réserves à conserver** :
   - **Composition JDBC manuelle** : le gestionnaire de transaction de `writeEffects` reste contractuellement lié à `JpaTransactionManager` ; aucune composition JDBC directe non certifiée ne doit être déployée sans réévaluation du risque de persistance du holder.
   - **Frontière de confiance administrative** : un superutilisateur ou un administrateur système PostgreSQL peut altérer les ACL ou les journaux hors préflight ; les contrôles valident l'état avant démarrage mais ne constituent pas un IDS temps réel.
   - **Pipelines de journalisation externes** : la suppression du détail sensible concerne le serveur PostgreSQL natif et son collecteur Docker ; tout collecteur tiers, extension d'audit ou APM de production requiert sa propre qualification.
   - **Réserves historiques ouvertes** : les réserves historiques `i5_r05`, `i5_r12` et l'état de la gate canonique globale restent sous leur statut préexistant, indépendant du présent audit.
4. **Nouveau développement correctif** :
   **Aucun nouveau développement n'est nécessaire** sur le périmètre de ces findings.
5. **Obstacle à la progression vers I4** :
   **Aucun obstacle propre aux quatre findings de l'audit Identity n'interdit désormais le passage aux étapes ultérieures.**

*(Rappel : conformément aux règles d'intervention, ce rapport ne prononce pas le GO I4 formel, dont la décision relève de l'autorité d'intégration du projet).*
