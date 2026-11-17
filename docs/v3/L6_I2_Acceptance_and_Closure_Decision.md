# Pocoma V3 — L6/I2 — Décision d'Acceptation et de Clôture Formelle de l'Incrément I2

## Exploitation et Reconstruction READ

**Date de décision :** 6 novembre 2026  
**Dépôt :** `kartaguez/pocoma`  
**Branche :** `feat/v3-architecture`  
**Décideur :** Autorité humaine du projet Pocoma  
**SHA technique accepté :** `fb543e920923a171c3bae2f0a7fe5eaed3dbccaa`  
**SHA documentaire de clôture :** Référencé lors de la publication Git du présent acte  
**Décision humaine d'autorisation :** `GO_L6_I2_FORMAL_CLOSURE`  
**Verdict formel :** `L6_I2_CLOSED_LOCAL_TARGETED_PASS_WITH_EXPLICIT_RESERVATIONS`  
**Formulation humaine :** L6/I2 — CLOSED — LOCAL TARGETED PASS WITH EXPLICIT RESERVATIONS  

---

## 1. Identification et Contexte

Le présent document constitue l'acte autonome et officiel de clôture de l'incrément **L6/I2 (« Exploitation et Reconstruction READ »)** du programme Pocoma V3.

L'incrément L6/I2 a suivi le cycle complet de gouvernance et de maturation technique suivant :
1. Cadrage technique initial (`L6_I2_Scoping_and_Design_Report.md`).
2. Consolidation architecturale et levée des blocages préliminaires (`L6_I2_Technical_Implementation_Plan.md`).
3. Correction finale du protocole de reconstruction READ (`fa8167a1`).
4. Implémentation technique complète sous mandat `GO_L6_I2_IMPLEMENTATION` (`3fed1b5d`).
5. Audit indépendant d'implémentation ayant émis les constats `AUD-L6-I2-01` à `AUD-L6-I2-12`.
6. Corrections post-audit et requalification sous mandat `GO_L6_I2_POST_AUDIT_CORRECTIONS` (`71597c14`).
7. Traitement des constats de sûreté finaux `AUD-L6-I2-13` à `AUD-L6-I2-19` sous mandat `GO_L6_I2_FINAL_SAFETY_CORRECTIONS` (`fb543e92`).
8. Revue humaine finale ayant validé l'autorisation `GO_L6_I2_FORMAL_CLOSURE`.

Le dernier verdict technique enregistré sur le commit `fb543e920923a171c3bae2f0a7fe5eaed3dbccaa` est :
`L6_I2_FINAL_SAFETY_CORRECTED_TARGETED_PASS — READY FOR HUMAN REVIEW`.

La présente mission consiste exclusivement à enregistrer et publier la décision humaine de clôture formelle, avec réserves explicites, sans nouveau développement ni rejeu des campagnes de tests.

---

## 2. Décision Formelle de Clôture

En vertu de l'autorité humaine du projet et sur le fondement de l'autorisation `GO_L6_I2_FORMAL_CLOSURE` :

> ### L6/I2 — CLOSED — LOCAL TARGETED PASS WITH EXPLICIT RESERVATIONS

Cette décision est expressément fondée sur les preuves techniques déjà archivées et sur la revue humaine finale, sans nouvelle exécution de qualification.

Cette décision couvre **l'incrément L6/I2 exclusivement**.  
Elle ne constitue ni une homologation de production, ni une certification globale SPEC-10, ni la clôture du lot L6 (dont l'incrément I3 et la gate G6 restent à réaliser).

---

## 3. Périmètre Formellement Accepté

L'acceptation au titre de L6/I2 reconnaît l'exactitude fonctionnelle, la cohérence architecturale et la complétude des mécanismes suivants, validés dans leur environnement local qualifié :

1. **Autorité persistante indestructible (`public.read_maintenance_state`) :**
   - Hébergement de la table de contrôle dans le schéma `public` de la base `pocoma_read`.
   - Survie inconditionnelle à l'instruction destructrice `DROP SCHEMA v3_read CASCADE`.
2. **Machine à états finis (FSM) de maintenance :**
   - États maîtres reconnus : `READY`, `MAINTENANCE`, `REBUILDING`, `FAILED`.
   - Phases granulaires tracées : `DRAIN_PENDING`, `FENCING_ACTIVE`, `DDL_IN_PROGRESS`, `FLYWAY_MIGRATING`, `CONTRACTS_PROVISIONED`, `REPLAY_IN_PROGRESS`, `ORACLE_EVALUATING`, `DRAIN_COMPLETED`, `READY_COMMITTED`.
3. **Barrière HTTP 503 fail-closed (`ReadMaintenanceFilter`) :**
   - Positionnement en priorité `-105` dans la chaîne de filtres Spring (en amont de Spring Security `-100`).
   - Interception immédiate des requêtes d'interrogation READ `/api/v3/*` avec retour systématique `HTTP 503 Service Unavailable`, en-tête `Retry-After: 30`, `Cache-Control: no-store` et corps d'erreur épuré sans fuite d'informations internes.
   - Refus systématique de tout faux 401 ou 404 lors des opérations de maintenance.
4. **Exclusivité de reconstruction et exclusion de concurrence :**
   - Transition atomique conditionnelle PostgreSQL (CAS) sur l'état `READY` et phase `IDLE` avec génération d'un `operation_id UUID`.
   - Verrouillage de session PostgreSQL par advisory lock (`pg_try_advisory_lock(3847291054321)`) via `PostgresRebuildLock`, garantissant le rejet immédiat de toute instance concurrente de rebuild ou de reprise et la libération automatique du verrou en cas de déconnexion ou crash du processus.
5. **Gestion et fencing des workers READ :**
   - Protocole de clôture en environnement local : signalement dans l'autorité, arrêt ordonné des conteneurs workers, attente gracieuse de drainage jusqu'à `pg_stat_activity = 0`, révocation des droits de schéma `USAGE ON SCHEMA v3_read` et suppression physique contrôlée.
6. **Reconstruction DDL et migrations READ :**
   - Suppression propre du schéma `v3_read` par `DROP SCHEMA IF EXISTS v3_read CASCADE`.
   - Rejeu séquentiel déterministe des scripts canoniques de migration DDL (V1 à V7, V10) avec substitution de paramètres et vérification physique de présence des 14 tables de projection.
   - Déploiement des tables de contrôle L2, contrats de routage, triggers d'intégrité et attribution des privilèges d'accès restreints.
7. **Réutilisation stricte des moteurs canoniques (Replay) :**
   - Réutilisation intégrale de `DiscoveryEngine`, `ConsumptionEngine`, `V3ReadParticipants` et de leurs baux sans second moteur propriétaire ni court-circuit des contrats de consommation.
   - Reconstruction intégrale des sept consommations READ (`MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`, `MaterializeInvitation`, `MaterializeCommandResult`, `IdentityRequestResult`, `CurrentBinding`).
8. **Oracle mathématique de readiness $\mathcal{P}_{\text{READY}}(B)$ :**
   - Évaluation formelle de la convergence sur le vecteur cible $B$ : présence obligatoire des 5 familles WRITE (`POT`, `IDENTITY`, `COMMAND_COMPLETION`, `IDENTITY_COMPLETION`, `INVITATION`).
   - Vérification stricte de 28 checkpoints `(consumption_id, partition_id)` avec position $\ge B$.
   - Absence totale de slots résiduels non résolus, en retard ($\le B$) ou placés en quarantaine dans WRITE.
   - Exécution des 4 vérifications d'intégrité relationnelle interne (versions pendantes, balances orphelines, visibilités incomplètes, bindings non conformes).
9. **Refus de READY en cas de non-convergence :**
   - Blocage strict de la réouverture nominale tant que l'ensemble des conditions de l'oracle n'est pas intégralement satisfait (fail-closed).
10. **Reprise non destructive (`--resume`) :**
    - Reprise sur incident après interruption ou crash, vérifiant l'intégrité des tables et réévaluant obligatoirement l'oracle avant toute transition vers `READY`.
11. **Récupération destructive confirmée (`--recover-destructive`) :**
    - Procédure de reprise dédiée depuis un état interrompu ou échoué (`MAINTENANCE`, `REBUILDING`, `FAILED`) avec confirmation impérative `--confirm-destructive=RESET_READ_DATA` pour reconstruire à partir d'un schéma corrompu ou partiel, sans risque de drop aveugle sur schéma sain.
12. **Protection stricte de `--reset-failed` :**
    - Interdiction formelle de contournement d'oracle via la commande d'acquittement : `--reset-failed` vérifie obligatoirement l'intégrité du schéma et le prédicat $\mathcal{P}_{\text{READY}}(B)$ avant d'autoriser la remise en service.
13. **Protection de la base WRITE :**
    - Utilisation stricte du rôle applicatif `pocoma_read` en lecture seule (`SELECT` uniquement sur `v3_event_counter` et `v3_event_position`) sans privilège d'écriture, les tentatives de mutation échouant systématiquement avec l'erreur PostgreSQL `42501 (permission denied)`.
14. **Observabilité et diagnostic :**
    - Métriques dédiées Micrometer/Prometheus (`pocoma_v3_rebuild_*`, durées, statuts, jauges d'état).
    - Provisionnement de 4 panneaux de supervision opérationnelle Grafana dans le tableau de bord V3.
15. **Outillage CLI d'exploitation locale :**
    - Script maître `scripts/operations/v3-rebuild-read.py` supportant `--confirm-destructive`, `--resume`, `--recover-destructive`, `--reset-failed`, `--status` et `--dry-run`.
    - Script de requalification locale `scripts/operations/verify-l6-i2-rebuild.sh`.

L'acceptation ne s'étend à aucun mécanisme non qualifié ou non documenté ci-dessus.

---

## 4. Références de Preuve Historiques

La présente décision de clôture s'appuie sur les preuves historiques enregistrées sur l'état technique publié (`fb543e920923a171c3bae2f0a7fe5eaed3dbccaa`) lors de la qualification finale :

| Gate | Intitulé / Périmètre technique | Résultat final enregistré | Durée finale constatée | Plafond imparti | Statut |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **Gate A** | Tests unitaires (Barrière HTTP, autorité JDBC, FSM, CAS, acquisition recovery, oracle conservateur) | **28/28 tests PASS** (0 fail, 0 skip) | **1,90 s** | $\le 60\text{ s}$ | **CONFORME** |
| **Gate B** | Tests d'intégration PostgreSQL réels (Concurrence CAS, exclusion session advisory lock, crash recovery, reprise DDL destructive, oracle CONVERGED, isolation des droits WRITE) | **12/12 scénarios PASS** (0 fail, 0 skip) | **27,84 s** | $\le 240\text{ s}$ | **CONFORME** |
| **Gate C** | Suite opérationnelle locale CLI (Concurrence réelle, exclusion advisory lock, rejet faux READY, sécurité reset-failed, reprise destructive confirmée, smoke tests HTTP) | **12/12 étapes PASS** | **63,00 s** | $\le 180\text{ s}$ | **CONFORME** |
| **Ensemble** | **Totalité des vérifications ciblées L6/I2** | **Toutes les gates PASS** | **92,74 s** | $\le 480\text{ s}$ (max 540 s) | **CONFORME** |

### Déclarations normatives relatives aux preuves :
1. **Nature des durées :** Ces durées correspondent aux exécutions finales rapportées lors de la passe de sûreté `GO_L6_I2_FINAL_SAFETY_CORRECTIONS`. Elles ne constituent pas le coût cumulé exhaustif de toutes les tentatives, diagnostics et itérations de mise au point antérieures.
2. **Conservation historique :** Les premières exécutions échouées, les rapports d'audit, les constatations contradictoires et les diagnostics successifs conservent l'intégralité de leur valeur historique et de traçabilité.
3. **Absence de rejeu :** Aucune mesure nouvelle n'a été effectuée pendant la présente mission de clôture documentaire.
4. **Absence de réacteur Maven global :** Conformément à [`docs/testing/Reactor_Verification_Policy.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/Reactor_Verification_Policy.md) et aux directives `AGENTS.md`, aucun full reactor n'a été exécuté pour cette qualification d'incrément.
5. **Absence de certification globale :** Aucune certification globale SPEC-10 n'est revendiquée sur la base de ces preuves ciblées.

---

## 5. Traçabilité des Audits et Corrections

L'acceptation formelle s'appuie sur le traitement rigoureux de l'ensemble des dix-neuf constats d'audit identifiés au fil de l'incrément, dont les identifiants et classifications historiques sont strictement préservés :

### 5.1 Constats du premier audit indépendant (`AUD-L6-I2-01` à `AUD-L6-I2-12`)
Traité dans le commit `71597c14b7bf499ebf7b95a02182db1a9f1d7310` :
- **AUD-L6-I2-01 (BLOCKING) :** Non-atomic operator exclusivity $\rightarrow$ **RESOLVED** (CAS conditionnel PostgreSQL et `operation_id UUID`).
- **AUD-L6-I2-02 (BLOCKING) :** Unsafe crash recovery $\rightarrow$ **RESOLVED** (Inversion de persistance après exécution, reprise non destructive `--resume` avec vérification physique).
- **AUD-L6-I2-03 (BLOCKING) :** Readiness Oracle non-conservative & duplicated $\rightarrow$ **RESOLVED** (Exigence des 5 familles WRITE, 28 checkpoints catalogue, blocage immédiat sur slots non résolus ou orphelins).
- **AUD-L6-I2-04 (MAJOR) :** WRITE isolation relied on admin superuser $\rightarrow$ **RESOLVED** (Rôle `pocoma_read` sans droit d'écriture, preuve PostgreSQL 42501).
- **AUD-L6-I2-05 (MAJOR) :** Systematic `pg_terminate_backend` $\rightarrow$ **RESOLVED** (Drainage gracieux prioritaire avec attente bornée `pg_stat_activity = 0`).
- **AUD-L6-I2-06 (MAJOR) :** Monolith coupling vs separate workers $\rightarrow$ **RESOLVED** (Limitation de composition monolithique documentée vs découplage distribué).
- **AUD-L6-I2-07 (MAJOR) :** Direct SQL executions claimed as Flyway $\rightarrow$ **RESOLVED** (Qualification documentaire exacte des scripts DDL canoniques).
- **AUD-L6-I2-08 (MAJOR) :** Hardcoded constant 28 checkpoints $\rightarrow$ **RESOLVED** (Dérivation dynamique depuis la configuration du catalogue de consommation).
- **AUD-L6-I2-09 (MAJOR) :** Slot polling overhead per slot $\rightarrow$ **RESOLVED** (Requêtes groupées batch SQL sur `v3_event_position`).
- **AUD-L6-I2-10 (MAJOR) :** WRITE isolation proof weak $\rightarrow$ **RESOLVED** (Preuve dynamique de refus 42501 + empreintes multi-tables WRITE).
- **AUD-L6-I2-11 (MAJOR) :** Test runtime reporting reconciliation $\rightarrow$ **RESOLVED** (Réconciliation intégrale des temps bruts et des relances de mise au point).
- **AUD-L6-I2-12 (MINOR) :** Smoke tests accepted 000 or false 401 $\rightarrow$ **RESOLVED** (Rejet strict des codes 000 et 503, assertion conjointe Actuator 200 et endpoint 401).

### 5.2 Constats de la revue de sûreté finale (`AUD-L6-I2-13` à `AUD-L6-I2-19`)
Traité dans le commit technique accepté `fb543e920923a171c3bae2f0a7fe5eaed3dbccaa` :
- **AUD-L6-I2-13 (BLOCKING) :** Risque de faux READY après reprise depuis l'état CONVERGED $\rightarrow$ **RESOLVED** (Factorisation stricte via `validate_and_restore_ready()` avec réévaluation obligatoire de l'oracle $\mathcal{P}_{\text{READY}}(B)$).
- **AUD-L6-I2-14 (BLOCKING) :** Risque de concurrence entre instances de reprise concurrentes $\rightarrow$ **RESOLVED** (Verrou de session PostgreSQL advisory lock `3847291054321` via `PostgresRebuildLock`).
- **AUD-L6-I2-15 (BLOCKING) :** Absence de procédure de récupération destructive après crash DDL $\rightarrow$ **RESOLVED** (Introduction de `--recover-destructive` avec confirmation explicite obligatoire `--confirm-destructive=RESET_READ_DATA` et méthode d'acquisition `tryAcquireRecovery`).
- **AUD-L6-I2-16 (MAJOR / SAFETY) :** Dérivation directe non prouvée de l'état READY par `--reset-failed` $\rightarrow$ **RESOLVED** (Contrôle physique des tables et réévaluation de l'oracle obligatoires avant remise à READY ; refus strict et maintien en `FAILED` si non conforme).
- **AUD-L6-I2-17 (MAJOR) :** Démonstration formelle de la cohérence de l'oracle entre Java et Python $\rightarrow$ **RESOLVED** (Option 2 d'équivalence démontrée retenue et prouvée par tests symétriques sur cas limites).
- **AUD-L6-I2-18 (MAJOR / DOC) :** Paramètres d'exploitation locaux non paramétrables et bornage $\rightarrow$ **RESOLVED** (Externalisation via variables d'environnement, sanitisation des logs, bornage local explicite).
- **AUD-L6-I2-19 (MINOR) :** Écarts de numérotation et classification entre audits $\rightarrow$ **RESOLVED** (Matrice unifiée et numérotation séquentielle pérennisée AUD-01 à AUD-19).

Toutes les corrections sont livrées dans l'historique Git et la présente décision s'appuie sur le dernier état technique publié.

---

## 6. Réserves Obligatoires Enregistrées

L'acceptation de l'incrément L6/I2 est formellement conditionnée par les sept réserves obligatoires suivantes, qui font partie intégrante de la décision :

### RES-L6-I2-01 — Qualification locale bornée
Les preuves techniques de L6/I2 démontrent le fonctionnement correct du rebuild READ exclusivement dans l'environnement local effectivement qualifié (Docker Compose mono-nœud avec conteneurs locaux).  
Elles ne démontrent pas une exploitation de production à grande échelle. Les comportements en environnement multi-nœuds, le déploiement distribué à haute disponibilité et les garanties de disponibilité associées ne sont pas homologués à ce stade.  
**Affectation :** Lot L8 (Distribution finale et exploitation industrielle).

### RES-L6-I2-02 — Continuité WRITE en composition monolithique
Dans la composition locale standard `docker-compose.functional.yml`, le conteneur `runtime-monolith` regroupe l'admission HTTP WRITE et les workers de consommation READ.  
Par conséquent, son arrêt opérationnel pendant la phase de reconstruction READ interrompt temporairement l'admission HTTP locale.  
La promesse architecturale de continuité WRITE continue et ininterrompue doit donc être formellement limitée aux compositions dans lesquelles les runtimes WRITE et READ sont réellement séparés. La présence d'un fichier `docker-compose.distributed.yml` distinct ne constitue pas, à elle seule, une preuve d'exploitation continue en conditions réelles distribuées.  
**Affectation :** Lot L8, avec conservation explicite de la limitation dans la documentation d'exploitation locale.

### RES-L6-I2-03 — Transition READY et stabilité de l'oracle
La validation finale de la remise en service nominale (`READY`) repose séquentiellement sur :
1. Une vérification physique et relationnelle des schémas et tables.
2. L'évaluation de l'oracle $\mathcal{P}_{\text{READY}}(B)$ sur le vecteur de rattrapage $B$.
3. Une transition conditionnelle persistante dans `public.read_maintenance_state`.

Ces étapes ne constituent pas une unique transaction atomique SQL couvrant l'ensemble des opérations. L'acceptation repose donc sur les invariants de conception garantissant la stabilité et la monotonie des projections READ pendant cet intervalle. Cette garantie est acceptée pour le périmètre local qualifié, mais ne doit pas être présentée comme une atomicité transactionnelle globale.  
**Affectation :** Obligation stricte de préservation lors des évolutions ultérieures des moteurs et de l'exploitation distribuée (notamment Lot L8).

### RES-L6-I2-04 — Outillage local et sécurité d'exploitation
La CLI d'exploitation `v3-rebuild-read.py` est outillée pour une composition locale Docker/PostgreSQL et exploite des paramètres d'environnement spécifiques.  
La gestion sécurisée des secrets (coffre-fort, HashiCorp Vault), l'orchestration multi-environnements (Kubernetes Jobs), la haute disponibilité et les procédures industrielles d'exploitation restent hors périmètre de cet incrément.  
**Affectation :** Lot L8.

### RES-L6-I2-05 — Performance, volumes et SLO
La reconstruction READ a été rigoureusement vérifiée sur le plan fonctionnel et opérationnel dans le périmètre ciblé.  
Elle n'a pas fait l'objet d'une qualification exhaustive relative aux dimensions suivantes :
- Volumes maximaux de données d'événements.
- Temps d'exécution de la reconstruction à grande échelle.
- Débit maximal de rattrapage des consommations.
- Mémoire consommée sous charge lourde.
- Respect quantitatif des SLO de reprise.
- Convergence en présence d'une charge d'écriture soutenue et durable.  
**Affectation :** Lot L6 / Incrément I3 (Bancs et qualification de performance SPEC-09 / OPS-01).

### RES-L6-I2-06 — Coût réel de qualification
Le cumul de 92,74 secondes représente strictement la somme des dernières durées d'exécution PASS déclarées pour les trois gates normatives lors du passage vert final.  
Il ne représente pas le temps total effectif consacré à la mise au point, qui a comporté plusieurs itérations de diagnostic, d'ajustement et de relance. L'acte conserve explicitement cette distinction afin d'assurer une parfaite sincérité métrologique et d'éviter toute confusion sur le coût réel des campagnes de qualification.  
**Affectation :** Traçabilité documentaire et politique future de vérification.

### RES-L6-I2-07 — Certification globale différée
La qualification de L6/I2 est locale et ciblée. Elle n'emporte :
- Aucune certification SPEC-10 globale.
- Aucun passage de full reactor Maven.
- Aucune homologation multi-nœuds.
- Aucune acceptation pour mise en production.
- Aucune clôture anticipée du lot L6 dans son ensemble.
- Aucune clôture des lots ultérieurs (L7, L8).  
**Affectation :** Jalons de qualification et clôtures majeures prévus dans le Master Plan (notamment G6, L7 et L8).

---

## 7. Synthèse des Réserves Héritées

La présente acceptation de L6/I2 maintient expressément l'ensemble des réserves ouvertes lors des incréments et lots antérieurs, qui ne sont en aucun cas levées implicitement par cette clôture :

1. **Réserves L6/I1 (`L6_I1_Acceptance_and_Closure_Decision.md`) :**
   - `AUD-L6-I1-07` : Allocations sur chemins chauds (mise en cache des timers/tags différée post-L6).
   - `AUD-L6-I1-08` : Coexistence des compteurs d'admission maintenue pour alignement métrologique.
   - Absence de benchmark de charge (affectée à L6/I3).
   - Absence d'homologation de sécurité en production pour l'endpoint Actuator Prometheus (affectée à L8).
   - Absence de corrélation individuelle des tentatives Workers (par respect de la cardinalité bornée).
   - Absence de certification globale SPEC-10 et dispense de full reactor.
2. **Réserves de clôture L5 (`L5_Q5D_Formal_Closure_Decision.md`) :**
   - `FIND-L4-01` : Quota READ $Q=2$ et gestion des refus de consultation sous saturation.
   - `FIND-L2-01` : Valeur du bail de claim READ ($240\text{ s}$).
   - `FIND-L4-03` : Calcul pur $\mathcal{O}(N)$ de la balance Pot sous volumes étendus.
   - `FIND-L1-01` : Comportement de `openComponent` sous historique très long.
   - `RES-ENV-01` : Représentativité des limites Docker locales par rapport à la production.
   - `FIND-TRANS-01` & `FIND-L2-02` : Restructurations modulaires et suppression du code legacy V2 (strictement différées à L7).
3. **Réserves de fondation L2, L3 et L4 :**
   - Les acceptations conditionnelles avec réserves de disponibilité et d'équivalence composite restent le socle normatif d'évaluation.

---

## 8. Relation avec L6/I3 et Poursuite du Programme

La clôture formelle de L6/I2 autorise la poursuite ordonnée des travaux du lot L6 conformément au plan consolidé [`docs/v3/L6_Design_and_Verification_Plan.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L6_Design_and_Verification_Plan.md) et au plan directeur [`docs/v3/V3_Implementation_Master_Plan.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/V3_Implementation_Master_Plan.md) :

1. **Incrément L6/I2 :** Déclaré formellement **CLOS (CLOSED)** avec réserves explicites.
2. **Incrément L6/I3 (« Bancs et qualification de performance ») :** Éligible à entrer dans sa prochaine phase de cadrage et de préparation technique. La qualification de charge, les bancs k6, les profils SPEC-09 et les SLO de rattrapage relèvent exclusivement de cette phase.
3. **Lot L6 :** Reste **EN COURS (OPEN)**. Aucune clôture globale du lot L6 n'est prononcée avant l'achèvement de l'incrément I3, l'exécution des vérifications requises pour G6 et l'audit associé.
4. **Interdiction :** Aucun travail d'implémentation ou d'exécution de bancs lourds sur L6/I3 n'est engagé dans le cadre de la présente mission.

---

## 9. Acte Officiel de Clôture Formelle

```
================================================================================
POCOMA V3 — LOT L6 / INCRÉMENT I2 : ACTE OFFICIEL DE CLÔTURE FORMELLE
Statut officiel : L6/I2 — CLOSED — LOCAL TARGETED PASS WITH EXPLICIT RESERVATIONS
Dépôt : kartaguez/pocoma
Branche : feat/v3-architecture
SHA technique accepté : fb543e920923a171c3bae2f0a7fe5eaed3dbccaa
Décision humaine : GO_L6_I2_FORMAL_CLOSURE
Date de décision : 6 novembre 2026

Périmètre accepté : Rebuild READ en place, autorité indestructible public.read_maintenance_state,
                     barrière fail-closed 503, verrou PostgreSQL advisory lock 3847291054321,
                     reprise non destructive --resume, reprise destructive --recover-destructive,
                     protection --reset-failed, oracle P_READY(B) sur 28 checkpoints,
                     isolation WRITE (rôle pocoma_read 42501), observabilité Prometheus/Grafana.

Preuves historiques ciblées (sans rejeu) :
- Gate A (Unit)            : 28/28 tests PASS (1,90 s <= 60 s)
- Gate B (Integration PG)  : 12/12 scénarios PASS (27,84 s <= 240 s)
- Gate C (Operational CLI) : 12/12 étapes PASS (63,00 s <= 180 s)
- Total gates ciblées      : 92,74 s <= 480 s plafond (max 540 s)
- Reactor global Maven     : NON EXÉCUTÉ (Conforme politique Reactor_Verification_Policy.md)
- Certification SPEC-10    : NON REVENDIQUÉE À CE STADE

Audits résolus : AUD-L6-I2-01 à AUD-L6-I2-12 (Commit 71597c14)
                 AUD-L6-I2-13 à AUD-L6-I2-19 (Commit fb543e92)
Réserves obligatoires : RES-L6-I2-01 à RES-L6-I2-07 enregistrées
Réserves héritées : L6/I1, L5/Q5-D, L4, L3, L2 expressément maintenues

Lot L6 : EN COURS (OPEN)
Incrément L6/I3 : AUTORISÉ AU CADRAGE TECHNIQUE
================================================================================
```
