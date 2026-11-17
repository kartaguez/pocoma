# Lot 4C — Schéma et séquenceur transactionnel dormants

Date : 2026-10-20. **4C CLOSED / PASS ; séquenceur certifié dormant ; aucune activation de production.**
Autorité : [spécification Étape 4, §4.3, §6/4C et §8/V-C](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md).
Suivi : [canon](../steps/current/DISCOVERY/Step_Canon.md), [plan](../steps/current/DISCOVERY/Step_Plan.md).
Preuves : [JSON](../testing/evidence/Event_Transactional_Sequencer_Step_4C_2026-10-20.json),
[extraits](../testing/evidence/Event_Transactional_Sequencer_Step_4C_2026-10-20.txt).

## Référence et préservation

Branche `feat/consumption-transaction-protocol`, worktree existant
`/private/tmp/pocoma-consumption-transaction-protocol`. Start HEAD :
`1771514a51c74452e019a2a259b40d0303cf2cde`, clôture documentaire 4B ; livraison 4B auditée :
`5640220b94f6208aa4c156e248d95e2eced1522f`. Après fetch ciblé, divergence initiale **0/0**.
Aucun fichier suivi modifié initialement dans ce worktree. Non-suivi préexistant conservé et exclu :
`Architecture_Last_Skip_Audit_2026-10-20.md`, SHA-256
`e34013dbb00ea3f3ff5150a8ba9e850d49fb116b4d7f6703edb4db9a19af3092`.
Le checkout courant initial était sur `feat/runtime-monolith`, avec des travaux préexistants ;
il n'a pas été utilisé pour les modifications. Aucun autre worktree modifié, aucun reset/stash/rebase.
Le SHA final et la divergence après push non forcé figurent dans la livraison Git, sans autoréférence
circulaire du présent commit. Les empreintes des sources testées figurent dans le JSON.

## Verification scope déclaré avant implémentation

- Impact attendu : migration additive V30 PRIMARY, séquenceur JDBC dormant dans
  `infra-persistence-primary-jpa`, tests explicites COMMAND/BINDING, documentation et script de preuve.
- Slice primaire **COMMAND** ; secondaire **BINDING**. Commande canonique depuis `app/` :
  `./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test`.
- Interdits : raccordement des vrais owners/writers, changement métier/Consumption/P/B/S/TA/TE/TR/TH,
  retry/classification, Discovery/READ, cutover/backfill/activation ou dépendance/POM.
- Preuve locale : vraie PostgreSQL 17, schéma CURRENT, upgrade V29→V30, contraintes et rôle restreint,
  barrières concurrentes bornées et observation serveur ; aucune simulation H2 pour E.
- Gate architecture global : **NOT REQUIRED, non exécuté** ; aucune condition §17.2 rencontrée.
- Full reactor : **NOT REQUIRED, non exécuté** ; pas de clôture de vague ni intégration main.
- DB : **CURRENT + nouveau DDL**. Baseline trusted **NONE** ; les bootstraps existants exécutent
  V1–V30. L'upgrade crée V29 par son mécanisme existant puis applique V30 seul ; aucune migration
  historique changée, aucune certification de baseline inventée ni campagne historique supplémentaire.
- Complément prévu par la compatibilité 4C : compilation et deux démarrages du runtime initial
  dans un export Git temporaire, sur V30 PREPARED, sans toucher à un checkout existant.
- Escalade : nouveau store/owner/manager, dépendance/POM/frontière, autre comportement de slice,
  besoin de métier sous E ou savepoint. **Aucune**. Prochain gate global obligatoire : 4D ;
  gate majeur/full : 4E puis intégration main.

## Mise en œuvre opérationnelle

1. [V30](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V30__prepared_event_transactional_sequencing.sql)
   crée un état singleton `(protocol_version=1, mode=PREPARED)`, quatre autorités à zéro,
   les quatre tables typées `(event_id UNIQUE NOT NULL, position PRIMARY KEY CHECK > 0)`, leurs FK
   immédiates restrictives vers les quatre vraies sources et la vue UNION ALL `event_position`.
   Aucune lecture de l'historique pour numéroter, aucun backfill ni modification de payload.
2. Chaque source reçoit un constraint trigger AFTER INSERT, DEFERRABLE INITIALLY DEFERRED.
   En ACTIVE, il vérifie uniquement l'existence indexée de la position du NEW UUID. Il ne lit ni
   la source, ni P/B/S, ni E ; il ne réserve rien et ne modifie rien. En PREPARED, il n'exige aucune
   position. La FK protège l'autre direction. Les triggers d'immutabilité et les droits interdisent
   UPDATE/DELETE des positions ; pas d'upsert, remplacement ou réutilisation silencieuse.
3. La fonction technique `reserve_event_positions(text,bigint)` protège les droits : un unique
   `UPDATE ... last_position + N ... RETURNING`, avec garde de capacité avant addition. N invalide
   est refusé (22023) ; autorité absente/capacité dépassée : 22003 ; PREPARED : 55000 avant E.
   Cette fonction n'ouvre/résout aucune transaction. Le rôle runtime ne peut modifier librement
   les compteurs. Ce n'est ni une séquence PostgreSQL ni une nouvelle API métier.
4. Les fonctions SECURITY DEFINER ont un chemin fixé à `pg_catalog, <schéma d'installation>, pg_temp` ;
   elles ne dépendent pas du chemin appelant. Le privilège EXECUTE PUBLIC est retiré.
   Le groupe NOLOGIN `pocoma_event_runtime` obtient lecture mode/vue/positions, INSERT positions et
   EXECUTE de réservation ; aucun droit DDL, UPDATE/DELETE/TRUNCATE ou désarmement de contrainte.
   Le propriétaire de migration/maintenance est distinct du rôle runtime. Aucune appartenance
   d'un login existant n'est accordée par V30. Le compte de migration doit pouvoir provisionner
   ce groupe ; un groupe préexistant avec privilèges administratifs est refusé.
5. [JdbcEventTransactionSequencer](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/transaction/event/JdbcEventTransactionSequencer.java)
   implémente le suffixe 4B, sans annotation/composant/configuration. Il exige un EM PRIMARY actif
   et son holder JDBC déjà lié ; `JdbcTemplate` utilise cette transaction JPA. Aucun fallback,
   transaction autonome, suspension, savepoint, commit local ou retry. Le runner 4B reste inchangé.
6. Snapshot vide : retour avant même la requête de mode. Snapshot non vide PREPARED : validation
   version/mode puis retour sans SQL E/association. ACTIVE isolé : familles fixes Business → Terminal
   → Binding → UserCreated, uniquement non vides, un bloc par famille et INSERT strict dans l'ordre
   de déclaration. Le dernier incrément Java reste représentable jusqu'à `Long.MAX_VALUE` inclus.
   Les verrous E restent détenus jusqu'à résolution physique.
7. Aucune configuration runtime ne construit le séquenceur. `verifyCompatibleMode()` est un
   contrôle explicite utilisable par la future composition 4D ; il n'active rien. Le contrôle de
   démarrage complet des owners/versions réels reste 4D. La bascule durable reste exclusivement 4E.

### Droits et périmètre de déploiement

Les droits sont éprouvés avec un vrai rôle PostgreSQL restreint (`SET LOCAL ROLE`), pas un mock.
Un compte superuser ou propriétaire de migration conserve par définition ses pouvoirs administratifs :
il ne doit pas servir de rôle runtime déployé. La migration n'altère pas les identifiants existants,
les secrets ou le provisioning de l'environnement ; elle installe le groupe et les grants requis.
Cette livraison n'atteste aucune configuration de comptes d'un environnement déployé. Les bases
Testcontainers utilisent un administrateur pour les seules opérations de maintenance/fixtures,
notamment l'ACTIVE isolé, puis le rôle restreint pour la preuve de droits.

## Matrice V-C

Sources principales : [tests PostgreSQL](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/EventSequencingPostgresTest.java),
[upgrade et schéma installé](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/EventSequencingUpgradePostgresTest.java),
[fixtures sources](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/eventprotocol/EventSequencingSchemaFixture.java).

| Critère | Preuve exécutée PostgreSQL / portée |
|---|---|
| Additif CURRENT | V29 peuplé des quatre sources → V30 seul, ensembles UUID conservés ; mode PREPARED, E=0, positions vides ; réexécution Flyway sans migration ni repair |
| Bloc N et ordre | Trois UUID par famille en ordre descendant, appels entrelacés/familles inversées ; associations exactes dans cet ordre, positions 1..3 ; même UUID légal entre Business et Terminal ; quatre UPDATE seulement |
| Transaction PRIMARY | JPA/JDBC PID/xid identiques ; traces DB de toutes sources, E et associations avec un seul PID/xid ; INSERT sources matérialisés avant première E |
| S/Claim avant E | Vrai `TransactionalExecuteConsumptionUseCase`, finalizer CURRENT et wrapper `ConsumptionOwnerTransactionRunner` : UPDATE S → clôture Claim → E → associations, même PID/xid ; DONE et ended_at vérifiés |
| Échec du vrai suffixe après S/Claim | Exception Java après toutes associations réelles : phase COMMIT / ROLLED_BACK ; S PENDING, Claim ouvert, Events/associations/E et traces annulés |
| Ordres concurrents opposés | Deux transactions déclarent les familles en sens opposés ; trace de réservations canonique dans les deux ; ensembles exacts en positions 1 puis 2, aucune visibilité avant premier commit |
| Contention réelle | `pg_stat_activity` montre `reserve_event_positions`, wait_event_type=Lock, wait_event=transactionid et `pg_blocking_pids` attendu ; aucune temporisation pour fabriquer l'ordre |
| E jusqu'à résolution / bloc annulé réutilisé | Premier suffixe entièrement réussi, second producteur observé bloqué ; rollback du premier, second obtient exactement 1..2 dans les quatre familles |
| Rollback après chaque E/INSERT | Pour chacune des quatre familles, trigger SQL AFTER UPDATE E puis AFTER INSERT association lève : erreur injectée vérifiée, aucune source/association/avance E durable ; transaction suivante repart à 1 |
| Collisions/intégrité | Dans chaque famille : collision UUID source et association (23505), position déjà utilisée (23505), FK vers UUID absent (23503), déclaration fictive après réservation (23503), conservation des couples existants |
| Complétude ACTIVE | INSERT réel réussit dans la transaction ; commit échoue 23514 pour absence de position sur chacune des quatre sources, ROLLED_BACK observé ; PREPARED accepte sans association |
| Overflow | Quatre familles : bloc de deux refusé à MAX−1, toutes autres réservations antérieures annulées ; une dernière position MAX acceptée puis refus suivant ; entrée invalide/autorité absente refusées explicitement |
| Droits | Suffixe complet sous `pocoma_event_runtime` réussit ; UPDATE/DELETE/TRUNCATE positions, mutation E/mode, DDL, désarmement triggers et session_replication_role refusés par PostgreSQL (42501) ; immutabilité DML aussi vérifiée sous owner |
| Pas d'E pour famille vide | Les trois autres lignes E sont verrouillées FOR UPDATE ailleurs ; production d'une seule famille commit avec lock_timeout=300ms, seule E correspondante tracée |
| Lot vide/PREPARED | Toutes E tenues ACCESS EXCLUSIVE ailleurs : lot vide avec effet indépendant commit ; lot PREPARED de quatre Events commit ; zéro verrou E du producteur et aucune association/avance E |
| Contraintes sans nouveau verrou métier | Sous E, exécution des vrais contrôles différés via SET CONSTRAINTS ALL IMMEDIATE : ensemble de verrous sources/parents inchangé ; P/B/S inutilisés tenus ACCESS EXCLUSIVE ailleurs jusqu'au commit, sans attente |
| Coût indexé | 3 000 associations réelles par famille ; huit EXPLAIN (ANALYZE, BUFFERS), hit/miss UUID, choisissent Index Only Scan sur index UNIQUE event_id sans forcer le planner ; lectures bornées à la sonde |
| Schéma SECURITY DEFINER | CURRENT V29 créé par ses migrations puis tables déplacées dans une base jetable ; V30 appliqué seul dans ce schéma ; son mode ACTIVE contrôle le commit malgré un mode PREPARED leurre dans public, 23514 attendu |
| Writers CURRENT compatibles | Writer `JdbcUserCreatedFactAdapter` inchangé exécuté deux fois avec owner standard : commits PREPARED sans positions ; suite canonique rejoue aussi les writers CURRENT Command/Binding/Registration présents dans les slices |
| Ancien runtime répété | Export du SHA initial, recompilation de ses seules sources productives, deux contextes Spring successifs et writer CURRENT inchangé sur V30 PREPARED ; absence vérifiée de la classe séquenceur 4C dans ce classpath |
| Dormance démarrage | Vrais roots COMMAND et BINDING : aucun bean séquenceur/runner spécialisé, mode PREPARED/E=0/positions=0 ; règle locale bytecode exclut callbacks métier, résolution autonome et stéréotype du séquenceur |

Les traces, plans et résumés exacts sont conservés dans les extraits et le JSON ; les compteurs de
tests ne remplacent pas ces assertions.

## Limites de certification

Les quatre **sources, FK, autorités, associations et triggers sont réels**. Les producteurs du
nouveau séquenceur sont des fixtures explicites : Business via `persist` JPA, autres sources via SQL
avec leurs vrais parents FK. Aucun writer réel n'est raccordé au runner/séquenceur. L'échec terminal
après associations est une injection Java ; les autres fautes AFTER et les contraintes sont de vrais
échecs PostgreSQL. Il ne s'agit pas d'une perte d'accusé réseau ni d'une preuve de tous les modes UNKNOWN.
Les preuves 4B des résolutions restent inchangées et sont rejouées dans la slice.

Les sondes EXPLAIN mesurent les requêtes d'existence utilisées par les fonctions, sur le vrai index ;
elles ne constituent pas un profil de charge de tous les triggers, un débit global ou un SLO.
Les contrôles différés s'exécutent aussi réellement sous E ; les verrous sont observés avant/après.
Les FK d'association vers les nouveaux Events prennent les verrous relationnels normaux déjà
présents avant ce contrôle ; aucune promesse universelle d'absence de contention/deadlock n'est faite.

Le retour au runtime initial est une preuve de bytecode recompilé depuis ce SHA sur base jetable,
sans transactions en vol. Il ne remplace pas un drainage/déploiement d'un environnement réel.
PREPARED n'exige aucune complétude et ne numérote rien ; les sondes de coût ajoutent volontairement
des associations de fixture en maintenance isolée, sans prétendre représenter un état PREPARED déployé.
Aucune parité historique ni activation réelle certifiée. Les conditions A/B des writers et
compositions réelles restent 4D ; cutover, backfill, seuils et gates majeurs restent 4E.

## Commandes et résultats

Depuis `app/`, commandes ciblées réellement exécutées :

```bash
./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test -Dtest=EventSequencingPostgresTest,EventSequencingUpgradePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test -Dtest=EventSequencingPostgresTest,EventSequencingUpgradePostgresTest,PrimaryEventProtocolDormancyPostgresTest,BindingRuntimePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test -Dtest=EventSequencingPostgresTest,EventSequencingUpgradePostgresTest,PrimaryEventProtocolStructureTest,PrimaryMigrationsPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test
```

Depuis la racine :

```bash
scripts/testing/verify-4c-previous-runtime.sh
```

Ce script exporte `1771514a51c74452e019a2a259b40d0303cf2cde` dans un dossier temporaire, copie V30
comme DDL de préparation et lance, depuis son `app/` :

```bash
./mvnw -pl runtime-command-consumption-worker -am test -Dtest=PreparedPreviousRuntimePostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Dstep4c.ddl=<dossier-temporaire>/compat-ddl
```

Résultats finaux :

- Campagne ciblée finale : **47 tests, 0 failure/error/skip** (34 V-C transactionnels,
  2 upgrade/schéma, 6 structure locale et 5 bootstrap CURRENT).
- Nouveau périmètre V-C : **36 tests PostgreSQL**, plus une nouvelle règle structurelle et
  un nouveau test de dormance BINDING. Les assertions de dormance COMMAND préexistantes sont étendues.
- Retour au runtime initial : **2 tests, 0 failure/error/skip**, deux contextes démarrés puis arrêtés
  sur le DDL final PREPARED ; aucune classe 4C productive dans ce bytecode.
- Commande canonique finale : **BUILD SUCCESS, 1 091 tests déclarés, 0 failure, 0 error, 1 skip**,
  204 classes de rapport, 52 modules SUCCESS dans la fermeture `-am` ; durée 2 min 15 s.
  Le skip préexistant est `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`,
  activable par `-Dstep3b.measure=true`. Aucune preuve V-C ni 4B sautée.
- Franchissements inattendus : **NONE**. Global architecture et full : **NOT REQUIRED, non exécutés**.
  Le premier gate canonique avait seulement deux assertions CURRENT à 29 au lieu de 30 ; corrigées
  dans la même frontière, puis gate entier rejoué. Les erreurs de migrations fail-closed présentes
  dans les logs de tests négatifs ne sont pas des erreurs de cette campagne finale.

Boucle de mise au point consignée : appel constructeur `PocomaUserId` corrigé ; sonde SQL de trace
multi-table corrigée (`NEW.family` non applicable aux autres tables) ; assertion de flush déplacée
avant toute requête native JPA ; volume du plan accru de 300 à 3 000 sans désactiver seqscan ;
deux compteurs CURRENT de migrations passés de 29 à 30, mêmes contrôles historiques ; bootstrap
isolé adapté au V16 historiquement qualifié public en déplaçant CURRENT V29, sans changer V16.
Une première commande de création de fixture utilisait un chemin relatif doublé `app/`, sans effet
sur les fichiers ; elle a été corrigée. Aucune assertion de rollback/ordre/intégrité supprimée pour
obtenir le vert ; les tests négatifs vérifient les SQLSTATE attendus et l'étape effectivement atteinte.

Autres contrôles : lectures normatives, inventaires `rg`, `java -version`, `docker info`, état Git,
worktrees, fetch/divergence, `git diff --check`, liens documentaires, empreintes des sources et du
non-suivi. Les logs complets restent sous `/private/tmp/pocoma-4c-*.log` ; extraits et SHA-256 versionnés.

## Livraison et verdict

**PASS — 4C CLOSED.** V-C entièrement vert sur le vrai DDL, allocation dense transactionnelle,
ordre déclaré, intégrité/droits, absence d'E pour vide et compatibilité PREPARED répétés.
Impact observé borné à PRIMARY dormant et tests des slices
annoncées ; aucun POM/dépendance/owner/writer productif changé. Franchissement inattendu : **NONE**.
Le DDL additif reste PREPARED, les compteurs déployables initialisés à zéro et les associations vides.
Aucun déploiement ni activation fait par cette livraison. La clôture de 4C ne clôt pas 4D/4E ou la vague.

Fichiers productifs ajoutés : `JdbcEventTransactionSequencer.java` et
`V30__prepared_event_transactional_sequencing.sql`. Tests ajoutés :
`EventSequencingPostgresTest.java`, `EventSequencingSchemaFixture.java`,
`EventSequencingUpgradePostgresTest.java`. Tests étendus : `PrimaryMigrationsPostgresTest.java`,
`PrimaryEventProtocolStructureTest.java`, `PrimaryEventProtocolDormancyPostgresTest.java`,
`BindingRuntimePostgresTest.java`. Script ajouté :
[verify-4c-previous-runtime.sh](../../scripts/testing/verify-4c-previous-runtime.sh).
Documents actualisés : Architecture, canon, plan, Next Plan, statut de la spécification Étape 4 ;
présent rapport et preuves JSON/TXT ajoutés. L'inventaire exact est versionné dans le JSON.
Les preuves/livraisons 4B et l'audit non suivi initial restent inchangés.

## Clôture documentaire après audit indépendant

Audit indépendant statique du SHA livré `de5e4c373d21c1409f6e75a4faac327926e01f92`,
sur la migration V30, le séquenceur JDBC, les tests PostgreSQL et la documentation.
**Verdict : PASS statique, sans défaut bloquant démontré dans le périmètre 4C.**
L'auditeur n'a réexécuté ni Maven ni PostgreSQL. Les résultats expérimentaux ci-dessus sont ceux
de la livraison 4C depuis `1771514a51c74452e019a2a259b40d0303cf2cde`, pas de la relecture indépendante.

Observations conservées pour la vérification **4D/V-D** :

- **R1 — Densité et frontière de confiance.** Les contraintes SQL ne garantissent pas isolément
  qu'une réservation E est intégralement associée à des Events. La densité dépend aussi du respect
  du protocole par le séquenceur et les compositions autorisées. En 4D, vérifier l'absence de
  contournement par les producteurs réels, les droits effectifs et la correspondance exacte entre
  associations et publications acceptées.
- **R2 — Identité de transaction physique.** Les gardes du séquenceur vérifient une transaction
  PRIMARY présente, sans certifier à elles seules tous les participants réels. Rattachement à la
  **condition B existante** : même manager PRIMARY et même transaction physique JPA/JDBC pour
  Command, Registration, auxiliaires, chemins `new` et compositions monolithiques/distribuées.
- **R3 — Poisoning irréversible.** Les preuves transactionnelles 4C ne certifient pas tous les
  writers réels face à une exception interceptée. Rattachement à la **condition A existante** :
  toute erreur durable couverte doit empêcher le commit partiel, même sans remontée au runner.

La [spécification, §6/4D](Event_Transactional_Sequencing_Step_4_Implementation_Plan_2026-10-19.md#4d--raccordement-complet-production-prepared)
conserve la définition unique des conditions A/B et précise la frontière R1 ; le
[plan courant, §4D](../steps/current/DISCOVERY/Step_Plan.md#4d--producteurs-et-orchestrations-réels-prepared-complet)
en assure le suivi. Ces observations ne rouvrent pas 4C, n'élargissent pas sa certification et ne
constituent pas une dette nouvelle propre à 4C.

**4C CLOSED / PASS — 4D NEXT — 4E NOT STARTED.** Schéma PREPARED et séquenceur restent dormants,
sans activation ni raccordement des producteurs réels. Cette clôture est documentaire uniquement :
impact production/test/DB **NONE**, slices primaire/secondaires **NONE**, franchissement inattendu
**NONE** ; aucun Maven/PostgreSQL, gate global ou full reactor exécuté ou requis.
