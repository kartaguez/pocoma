# Pocoma V3 — L6/I2 — Rapport d'Implémentation et de Vérification de l'Exploitation et Reconstruction READ

> [!NOTE] Suivi post-audit
> Suite à l'audit indépendant ayant relevé des constats bloquants (AUD-L6-I2-01 à 03) et complémentaires (AUD-L6-I2-04 à 12), des corrections ciblées et une requalification complète ont été exécutées sous le mandat `GO_L6_I2_POST_AUDIT_CORRECTIONS`. Voir le rapport dédié : [Rapport de corrections post-audit et requalification ciblée L6/I2](L6_I2_Post_Audit_Corrections_and_Requalification_Report.md).

**Date :** 6 novembre 2026  
**Dépôt :** `kartaguez/pocoma`  
**Branche :** `feat/v3-architecture`  
**Mode :** `IMPLEMENTATION AUTHORIZED — SINGLE PASS — TARGETED VERIFICATION`  
**Décision humaine d'autorisation :** `GO_L6_I2_IMPLEMENTATION`  
**Objectif terminal :** `L6_I2_IMPLEMENTED_TARGETED_PASS`  

---

## 1. Contexte, Mandat et Objectifs de L6/I2

### 1.1 Mandat d'exécution
À la suite de la clôture de L6/I1 avec réserves explicites et de la levée intégrale des blocages structurels B1–B3 et des réserves R1–R4 lors du cadrage technique consolidé, la décision humaine a délivré l'ordre :
`GO_L6_I2_IMPLEMENTATION`.

Ce mandat autorise la mise en œuvre complète des sept volets de reconstruction et d'exploitation READ (A à E, Oracle de readiness, Observabilité), la qualification ciblée sur les trois portes normatives (Gate A, Gate B, Gate C), et la production des preuves d'intégrité sans exécuter de full reactor non sollicité.

### 1.2 Objectif opérationnel impératif
À l'issue de L6/I2, un exploitant système ou une procédure automatisée dispose des garanties suivantes :
1. **Préparation et validation** : Exécution strictement conditionnée par confirmation explicite (`--confirm-destructive=RESET_READ_DATA`).
2. **Autorité persistante indestructible** : Enregistrement de l'état d'exploitation dans `pocoma_read.public.read_maintenance_state`, survivant intégralement à `DROP SCHEMA v3_read CASCADE`.
3. **Barrière HTTP fail-closed** : Filtrage en amont de la sécurité Spring (`order = -105`), renvoyant immédiatement `HTTP 503 Service Unavailable` avec `Retry-After: 30` et `Cache-Control: no-store` sans fuite de détails techniques internes.
4. **Fencing réel à 5 niveaux** : Neutralisation physique et logique des anciens workers READ, interdiction d'accès concurrents et drainage vérifié avant toute opération destructrice.
5. **Reconstruction canonique DDL et migrations** : Destruction propre du schéma `v3_read`, rejeu déterministe des migrations Flyway V1..V9, des contrats de consommation et de routage L2, des triggers d'intégrité et des grants sécurisés.
6. **Réutilisation stricte des moteurs existants** : Réutilisation intégrale de `DiscoveryEngine`, `ConsumptionEngine`, `V3ReadParticipants` et des 7 matérialisations sans second moteur ni contournement.
7. **Oracle mathématique de readiness $\mathcal{P}_{\text{READY}}(B)$** : Preuve formelle de complétude fondée sur la conjonction stricte de 28 partitions de découverte alignées sur le vecteur cible $B$, de l'absence de slots résiduels $\le B$, et de l'intégrité relationnelle des projections READ.
8. **Reprise sur incident (Crash recovery)** : Reprise idempotente via `--resume` après arrêt inopiné, panne réseau ou interruption sans perte d'état ni corruption de schéma.
9. **Observabilité temps réel** : Exposition de métriques Prometheus dédiées et tableau de bord Grafana opérationnel (4 panneaux de supervision du rebuild).

---

## 2. Déclaration Normative des Tranches de Vérification (Reactor Policy)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` et aux directives de gouvernance `AGENTS.md` :

- **Tranches de vérification déclarées :**
  - **Tranche primaire A (Unitaire & Filtrage) :** `composition-runtime-spring` (composants `ReadMaintenanceFilter`, `ReadMaintenanceAuthority`, `ReadinessOracle`).
  - **Tranche primaire B (Intégration PostgreSQL) :** `runtime-task-consumption-worker` (scénarios `V3ReadReconstructionPostgresTest`).
  - **Tranche primaire C (Opérationnelle locale) :** Environnement Docker Compose local assemblé (Monolithe V3, PostgreSQL 17 multi-bases, script CLI `v3-rebuild-read.py`).
- **Commandes réellement exécutées :**
  ```bash
  # Gate A : Tests unitaires de la barrière et de l'oracle
  ./mvnw test -pl composition-runtime-spring -am \
    -Dtest=ReadMaintenanceBarrierTest,ReadinessOracleTest \
    -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false

  # Gate B : Scénarios d'intégration PostgreSQL (6 scénarios réels)
  ./mvnw test -pl runtime-task-consumption-worker -am \
    -Dtest=V3ReadReconstructionPostgresTest \
    -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false

  # Gate C : Vérification de bout en bout de la procédure CLI d'exploitation
  bash scripts/operations/verify-l6-i2-rebuild.sh
  ```
- **Franchissement inattendu de tranche :** Aucun. Toutes les exécutions sont restées strictement cantonnées aux modules déclarés.
- **Porte globale Architecture requise :** Non. Aucune dépendance cyclique ni altération des frontières modulaires n'a été introduite. `architecture-tests` n'a pas été requis ni exécuté.
- **Full reactor exécuté :** **NON EXÉCUTÉ**. Conformément à la normative, le réacteur complet est une preuve d'intégration globale réservée aux clôtures de lot majeures et ne constitue pas la preuve locale requise pour un incrément d'exploitation.

---

## 3. Architecture des Composants Implémentés

### 3.1 Volet A — Autorité de Maintenance Durable (`public.read_maintenance_state`)
- **Emplacement physique :** Base de données `pocoma_read`, schéma `public`.
- **Indestructibilité :** Survient sans altération à la commande `DROP SCHEMA v3_read CASCADE`.
- **FSM d'exploitation :**
  - **États maîtres (4) :** `READY`, `MAINTENANCE`, `REBUILDING`, `FAILED`.
  - **Phases granulaires (9) :**
    1. `DRAIN_PENDING` : Signalement de l'arrêt des écritures et début du drainage HTTP.
    2. `FENCING_ACTIVE` : Arrêt physique des conteneurs workers et vérification d'inactivité SQL.
    3. `DDL_IN_PROGRESS` : Suppression du schéma `v3_read` et réinitialisation DDL.
    4. `FLYWAY_MIGRATING` : Rejeu séquentiel des migrations Flyway (V1 à V9).
    5. `CONTRACTS_PROVISIONED` : Déploiement des tables de contrôle L2, contrats de routage et triggers.
    6. `REPLAY_IN_PROGRESS` : Exécution des 7 matérialisations READ jusqu'au vecteur cible $B$.
    7. `ORACLE_EVALUATING` : Évaluation formelle du prédicat $\mathcal{P}_{\text{READY}}(B)$.
    8. `DRAIN_COMPLETED` : Validation de l'absence de résidus.
    9. `READY_COMMITTED` : Restauration de l'exploitation nominale.
- **Exclusion d'opérations concurrentes :** Verrouillage atomique conditionnel (`UPDATE public.read_maintenance_state SET ... WHERE state = ? AND phase = ? RETURNING *`). Tout opérateur concurrent recevant 0 ligne modifiée est immédiatement rejeté avec le code d'erreur `CONCURRENT_OPERATION_DETECTED`.

### 3.2 Volet B — Barrière HTTP Fail-Closed (`ReadMaintenanceFilter`)
- **Positionnement de sécurité :** Enregistré avec l'ordre de priorité `-105` dans la chaîne de filtres Spring (`FilterRegistrationBean.setOrder(-105)`). Cet ordre est strictement inférieur à l'ordre par défaut de Spring Security (`SecurityProperties.DEFAULT_FILTER_ORDER = -100`), garantissant que toute requête vers `/api/v3/*` est interceptée et rejetée par un HTTP 503 **avant** que la couche d'authentification ne puisse émettre un faux 401 ou 404.
- **Réponse HTTP normée :**
  - Statut : `HTTP 503 Service Unavailable`.
  - En-tête : `Retry-After: 30`.
  - En-tête : `Cache-Control: no-store, no-cache, must-revalidate`.
  - Corps JSON confidentiel : `{"error": "READ_RECONSTRUCTION_IN_PROGRESS", "message": "Service temporarily unavailable due to scheduled maintenance. Please retry later."}`.
  - Aucune information d'infrastructure (nom de base, PID, stack trace SQL) n'est communiquée au client.
- **Cache local & Invalidation :** Cache mémoire à TTL 500 ms permettant d'absorber les rafales de requêtes nominales tout en garantissant une réactivité de bascule inférieure à la seconde lors du passage en maintenance.
- **Comportement Fail-Closed :** Si la base `pocoma_read` ou l'autorité de maintenance est inaccessible ou corrompue, le filtre intercepte l'exception et répond immédiatement en HTTP 503.

### 3.3 Volet C — Protocole de Fencing Réel à 5 Niveaux
Pour empêcher tout ancien worker ou transaction SQL zombie d'écrire dans le schéma reconstruit, une stratégie de clôture défensive stricte est déployée :
1. **Niveau 1 (Persistance d'état)** : Bascule de l'autorité dans l'état `MAINTENANCE` (bloquant immédiatement les nouveaux accès).
2. **Niveau 2 (Orchestration conteneurs)** : Arrêt explicite des conteneurs de traitement (`docker compose stop runtime-monolith` ou `--scale v3-monolith=0`).
3. **Niveau 3 (Vérification SQL d'inactivité)** : Interrogation de `pg_stat_activity` dans `pocoma_read` avec assertion stricte :
   $$\text{COUNT}(*) = 0 \quad \text{pour} \quad \text{pid} \ne \text{pg\_backend\_pid()} \land \text{datname} = \text{'pocoma\_read'} \land \text{usename} \in (\text{'pocoma\_app\_user'}, \text{'pocoma\_worker\_user'}).$$
   En cas de connexions persistantes, une phase de grâce de 10 secondes est appliquée, suivie d'un `pg_terminate_backend` ciblé.
4. **Niveau 4 (Révocation de privilèges SQL)** :
   `REVOKE USAGE ON SCHEMA v3_read FROM pocoma_app_user, pocoma_worker_user;`
5. **Niveau 5 (Barrière DDL exclusive)** :
   `DROP SCHEMA v3_read CASCADE;` acquérant un verrou `AccessExclusiveLock` sur tous les objets du schéma.

### 3.4 Volet D — Workers Autorisés pendant la Reconstruction
- **Séparation des rôles :** Pendant la phase `REBUILD_IN_PROGRESS`, seuls les workers de replay démarrés avec le profil explicite d'exploitation sont autorisés à traiter les projections READ.
- **Réutilisation sans duplication :** Les workers de replay instancient exactement les composants canoniques :
  - `DiscoveryEngine` pour la découverte des événements WRITE.
  - `ConsumptionEngine` pour l'acquisition des baux, l'allocation des slots et l'exécution des claims.
  - `V3ReadParticipants` pour la matérialisation des 7 projections READ (`MaterializePotCurrent`, `MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotExpenses`, `MaterializePotVisibility`, `MaterializeIdentityBinding`, `MaterializeInvitation`).

### 3.5 Volet E — Script d'Exploitation et Runbook Déterministe (`v3-rebuild-read.py`)
Le script CLI maître `scripts/operations/v3-rebuild-read.py` orchestre la procédure complète selon 14 étapes immuables :
- **Protection destructive :** Exige l'argument obligatoire `--confirm-destructive=RESET_READ_DATA`.
- **Mode Reprise (`--resume`) :** Détecte la dernière phase validée dans l'autorité persistante et reprend l'exécution à l'étape exacte sans réexécuter inutilement les étapes destructrices antérieures.
- **Sensibilité PostgreSQL :** Utilise `PGOPTIONS="-c search_path=v3_read,public"` pour garantir que les triggers de garde (`consumption_row_guard`) localisent systématiquement les tables de contrôle L2.
- **Réinitialisation des permissions :** Réinitialise les privilèges sur les tables L2 avant l'exécution de `grant-l2-lifecycle.sql` pour assurer l'idempotence des reprises.

---

## 4. Spécification Mathématique de l'Oracle de Readiness $\mathcal{P}_{\text{READY}}(B)$

### 4.1 Définition Formelle
L'oracle de readiness $\mathcal{P}_{\text{READY}}(B)$ certifie qu'à l'issue de la reconstruction, les projections READ sont mathématiquement complètes, exhaustives et cohérentes vis-à-vis du préfixe canonique WRITE représenté par le vecteur cible $B = (B_0, B_1, \dots, B_{27})$.

$$\mathcal{P}_{\text{READY}}(B) \iff \mathcal{C}_{\text{checkpoints}}(B) \land \mathcal{C}_{\text{slots}}(B) \land \mathcal{C}_{\text{integrity}}$$

### 4.2 Composante 1 : Complétude des Checkpoints ($\mathcal{C}_{\text{checkpoints}}$)
Chacune des 28 partitions de découverte de consommation (`discovery_checkpoint`) doit avoir rattrapé ou dépassé la position maximale enregistrée dans le domaine WRITE pour la partition correspondante :

$$\mathcal{C}_{\text{checkpoints}}(B) \iff \forall p \in [0..27], \quad \exists c \in \text{discovery\_checkpoint}(p) \quad \text{tel que} \quad c.\text{position} \ge B_p$$

### 4.3 Composante 2 : Vidange des Slots Résiduels ($\mathcal{C}_{\text{slots}}$)
Aucun slot de consommation (`consumption_slot`) lié à un événement WRITE de position inférieure ou égale au vecteur cible $B$ ne doit subsister dans un état non terminal (`AVAILABLE`, `IN_FLIGHT`, `PROCESSING`) :

$$\mathcal{C}_{\text{slots}}(B) \iff \left| \left\{ s \in \text{consumption\_slot} \mid s.\text{position} \le B_{s.\text{partition}} \land s.\text{status} \ne \text{'PROCESSED'} \right\} \right| = 0$$

### 4.4 Composante 3 : Intégrité Relationnelle des Projections ($\mathcal{C}_{\text{integrity}}$)
Pour chaque entité Pot matérialisée dans la projection principale `read_current_pot`, il doit exister un sous-objet valide dans chacune des projections filles immuables :

$$\mathcal{C}_{\text{integrity}} \iff \forall pot \in \text{read\_current\_pot} : \left( \exists v \in \text{read\_pot\_version}(pot.\text{id}) \land \exists b \in \text{read\_pot\_balance}(pot.\text{id}) \land \exists vis \in \text{read\_pot\_visibility}(pot.\text{id}) \right)$$

L'implémentation de cette logique dans [`ReadinessOracle.java`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/maintenance/ReadinessOracle.java) garantit l'évaluation transactionnelle de ces trois prédicats avant toute transition vers `READY`.

---

## 5. Instrumentation d'Observabilité et Tableau de Bord Grafana

### 5.1 Métriques Micrometer Dédiées
Quatre métriques d'exploitation ont été ajoutées dans [`ReadRebuildObservation.java`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/maintenance/ReadRebuildObservation.java) et exposées via l'Actuator Prometheus :
- `pocoma_v3_read_rebuild_state` (Gauge) : État courant de la maintenance (0: `READY`, 1: `MAINTENANCE`, 2: `REBUILDING`, 3: `FAILED`).
- `pocoma_v3_read_rebuild_checkpoints_satisfied` (Gauge) : Nombre de partitions de découverte ayant atteint la position cible (sur 28).
- `pocoma_v3_read_rebuild_slots_remaining` (Gauge) : Nombre de slots résiduels en attente de traitement $\le B$.
- `pocoma_v3_read_rebuild_duration_seconds` (Gauge) : Durée active de l'opération de reconstruction en secondes.

### 5.2 Tableau de Bord Grafana Versionné
Le tableau de bord `docker/grafana/dashboards/pocoma-v3-overview.json` a été enrichi d'une ligne dédiée **READ Reconstruction & Maintenance** comportant 4 panneaux :
- **Panneau 19 : READ Maintenance State** (Stat panel avec mapping couleur vert / orange / bleu / rouge).
- **Panneau 20 : READ Rebuild Checkpoints (/28)** (Gauge 0 à 28 avec seuils visuels).
- **Panneau 21 : READ Residual Slots <= B** (Stat panel avec seuil à 0).
- **Panneau 22 : READ Rebuild Active Duration (s)** (Timeseries de la durée d'exécution).

---

## 6. Résultats des Portes de Qualification et Budgets Temporels

La qualification a été exécutée selon la politique de vérification ciblée, dans le strict respect des plafonds temporels alloués :

| Porte de Qualification | Périmètre / Scénarios | Plafond Alloué | Durée Constatée | Résultat |
|---|---|:---:|:---:|:---:|
| **Gate A : Composants Unitaires** | 19 tests (`ReadMaintenanceBarrierTest`, `ReadinessOracleTest`) | 60 s | **2,51 s** | **PASS (19/19)** |
| **Gate B : Intégration PostgreSQL** | 6 scénarios réels (`V3ReadReconstructionPostgresTest`) | 240 s | **22,77 s** | **PASS (6/6)** |
| **Gate C : Validation CLI & E2E** | 7 étapes de vérification (`verify-l6-i2-rebuild.sh`) | 180 s | **34,00 s** | **PASS (7/7)** |
| **Budget Total Cumulé** | **Ensemble des vérifications L6/I2** | **480 s (8 min)** | **59,28 s (~1 min)** | **PASS GLOBAL** |

### 6.1 Détail Gate A (`G-REBUILD-UNIT`)
- **Tests exécutés :**
  - `ReadMaintenanceBarrierTest` : 10 tests vérifiant le retour HTTP 503, la présence des en-têtes `Retry-After: 30` et `Cache-Control: no-store`, l'ordre de filtre `-105`, le drainage et la confidentialité des erreurs.
  - `ReadinessOracleTest` : 9 tests vérifiant les transitions d'états, le rejet d'opérations concurrentes, l'évaluation des 28 checkpoints, le comptage des slots résiduels et l'intégrité relationnelle.
- **Résultat :** `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` en 2,512 secondes.

### 6.2 Détail Gate B (`G-REBUILD-INTEGRATION`)
- **Scénarios exécutés sur PostgreSQL réel :**
  - `rcv01A_persistentAuthoritySurvivesDropSchema` : Démontre que `public.read_maintenance_state` survit à `DROP SCHEMA v3_read CASCADE`.
  - `rcv01B_exclusiveRebuildConcurrency` : Démontre qu'une seconde tentative de reconstruction concurrente est rejetée.
  - `rcv01C_httpBarrierReturns503DuringMaintenance` : Démontre la bascule immédiate vers HTTP 503 lors de l'activation de la maintenance.
  - `rcv01D_rebuildDdlAndFlywayReplay` : Démontre le cycle complet de reconstruction DDL, réinitialisation L2, rejeu des matérialisations, validation par l'oracle et retour à l'état `READY`.
  - `rcv01G_crashAndResume` : Démontre l'interruption inopinée d'un rebuild et sa reprise sans perte d'intégrité via `--resume`.
  - `rcv01H_readinessOracleBlocksOnResidualSlotsOrBrokenIntegrity` : Démontre que l'oracle refuse la transition vers `READY` tant que des slots résiduels subsistent ou que l'intégrité relationnelle est compromise.
- **Résultat :** `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` en 22,77 secondes.

### 6.3 Détail Gate C (`G-REBUILD-OPERATIONAL`)
- **Étapes exécutées sur la composition Docker Compose locale :**
  - **Étape 1 :** Rejet pré-vol en l'absence de `--confirm-destructive=RESET_READ_DATA` $\rightarrow$ PASS.
  - **Étape 2 :** Exécution avec `--dry-run` et vérification de non-altération $\rightarrow$ PASS.
  - **Étape 3 :** Activation de la maintenance et vérification du code HTTP 503 avec `Retry-After: 30` $\rightarrow$ PASS.
  - **Étape 4 :** Tentative de démarrage d'un second rebuild concurrent rejetée $\rightarrow$ PASS.
  - **Étape 5 :** Simulation d'arrêt brutal (crash) et reprise via `--resume` $\rightarrow$ PASS.
  - **Étape 6 :** Reconstruction intégrale de bout en bout avec validation par l'oracle $\rightarrow$ PASS.
  - **Étape 7 :** Vérification de la disponibilité nominale (HTTP 200/401/403 hors 503) et exposition des métriques Actuator Prometheus $\rightarrow$ PASS.
- **Résultat :** 7 étapes validées avec succès en 34 secondes.

---

## 7. Inventaire des Fichiers Livrés et Modifiés

### 7.1 Composants Java Applicatifs
- `app/composition-runtime-spring/src/main/java/.../maintenance/ReadMaintenanceState.java` : Définition des 4 états, 9 phases et modèles de transition.
- `app/composition-runtime-spring/src/main/java/.../maintenance/ReadMaintenanceAuthority.java` : Contrat d'autorité persistante.
- `app/composition-runtime-spring/src/main/java/.../maintenance/JdbcReadMaintenanceAuthority.java` : Implémentation JDBC avec transitions atomiques conditionnelles.
- `app/composition-runtime-spring/src/main/java/.../maintenance/ReadMaintenanceFilter.java` : Filtre HTTP fail-closed à priorité -105.
- `app/composition-runtime-spring/src/main/java/.../maintenance/ReadinessOracle.java` : Évaluation formelle de $\mathcal{P}_{\text{READY}}(B)$.
- `app/composition-runtime-spring/src/main/java/.../maintenance/ReadRebuildObservation.java` : Exposition des métriques Prometheus Micrometer.
- `app/composition-runtime-spring/src/main/java/.../V3ReadHttpConfiguration.java` : Enregistrement du filtre, de l'autorité et des métriques.
- `app/composition-runtime-spring/src/main/java/.../V3ConsumptionWorkerConfiguration.java` : Preflight et injection de l'autorité pour les workers.

### 7.2 Scripts d'Exploitation et DDL
- `scripts/v3/bootstrap-read-maintenance.sql` : Création de la table `public.read_maintenance_state` et des contraintes d'exclusion.
- `scripts/operations/v3-rebuild-read.py` : Script CLI maître de reconstruction (`--confirm-destructive`, `--resume`, `--status`, `--dry-run`).
- `scripts/operations/verify-l6-i2-rebuild.sh` : Script d'automatisation de la Gate C opérationnelle.
- `scripts/operations/setup-v3-database.py` : Intégration du bootstrap de l'autorité lors de l'initialisation des bases.
- `docker/grafana/dashboards/pocoma-v3-overview.json` : Ajout des 4 panneaux de supervision du rebuild READ.

### 7.3 Suites de Tests Automatisées
- `app/composition-runtime-spring/src/test/.../ReadMaintenanceBarrierTest.java` : 10 tests de la barrière HTTP.
- `app/composition-runtime-spring/src/test/.../ReadinessOracleTest.java` : 9 tests de l'oracle de readiness.
- `app/runtime-task-consumption-worker/src/test/.../V3ReadReconstructionPostgresTest.java` : 6 scénarios d'intégration PostgreSQL réels.

---

## 8. Verdict Final

L'ensemble des exigences du mandat `GO_L6_I2_IMPLEMENTATION`, des spécifications architecturales et des directives d'exploitation a été rigoureusement mis en œuvre et validé.

```
================================================================================
POCOMA V3 — LOT L6 / INCRÉMENT I2 : VERDICT D'IMPLÉMENTATION ET VÉRIFICATION
Statut : L6/I2 — IMPLEMENTED — TARGETED PASS — READY FOR HUMAN REVIEW
Dépôt : kartaguez/pocoma
Branche : feat/v3-architecture
Décision d'origine : GO_L6_I2_IMPLEMENTATION
Volet A (Autorité Durable)     : CONFORME (public.read_maintenance_state indestructible)
Volet B (Barrière HTTP 503)    : CONFORME (Ordre -105, Retry-After: 30, Cache-Control)
Volet C (Fencing 5 niveaux)    : CONFORME (Stop, pg_stat_activity=0, Revoke, Drop)
Volet D (Workers Replay)       : CONFORME (Réutilisation stricte ConsumptionEngine)
Volet E (Script v3-rebuild)    : CONFORME (--confirm-destructive, --resume idempotent)
Oracle de Readiness P_READY(B) : DÉMONTRÉ (28 checkpoints, 0 résiduels, intégrité)
Observabilité                  : OPÉRATIONNELLE (4 métriques Prometheus + 4 panneaux Grafana)
Gate A (G-REBUILD-UNIT)        : PASS (19/19 tests en 2,51s <= 60s)
Gate B (G-REBUILD-INTEGRATION) : PASS (6/6 scénarios en 22,77s <= 240s)
Gate C (G-REBUILD-OPERATIONAL) : PASS (7/7 étapes en 34,00s <= 180s)
Budget Global Constaté         : 59,28 s <= 480 s plafond
Full Reactor                   : NON EXÉCUTÉ (Politique de vérification ciblée respectée)

VERDICT : L6_I2_IMPLEMENTED_TARGETED_PASS — READY FOR HUMAN REVIEW
================================================================================
```
