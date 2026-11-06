# Pocoma V3 — L6/I2 — Plan Technique de Cadrage et d'Implémentation
## Exploitation & Reconstruction READ

**Date :** 6 novembre 2026
**Dépôt :** `kartaguez/pocoma`
**Branche :** `feat/v3-architecture`
**SHA de référence :** `38478787edfb7ee3fecf18739165f96faf2b8727`
**Autorisation humaine :** `GO_L6_I2_SCOPING`
**Statut du document :** `SCOPING COMPLETE — DESIGN READY FOR HUMAN REVIEW — IMPLEMENTATION NOT AUTHORIZED`
**Autorité normative :** [V3 Master Plan](V3_Implementation_Master_Plan.md), [L6 Design & Verification Plan](L6_Design_and_Verification_Plan.md), [SPEC-04](SPEC-04_Read_Projections.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-08](SPEC-08_Observability.md), [SPEC-10 §§10–12](SPEC-10_Test_Certification.md), [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md), [Décision L6/I1](L6_I1_Acceptance_and_Closure_Decision.md).

---

## 1. Contexte et Mandat

### 1.1 Contexte du Programme Pocoma V3
À l'issue de la clôture formelle et définitive de l'incrément L6/I1 le 6 novembre 2026 (`L6/I1 — CLOSED — LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS`, SHA `27cf9ca3`), le système Pocoma V3 dispose d'une chaîne complète d'admission, d'exécution asynchrone des commandes, de matérialisation des projections READ et d'une stack locale fonctionnelle d'observabilité (Prometheus, Grafana 18 panneaux, Micrometer, métriques JDBC HikariCP et workers).

Le programme Pocoma V3 aborde désormais l'incrément **L6/I2**, consacré à **l'exploitation opérationnelle et à la reconstruction intégrale des projections READ**.

### 1.2 Mandat de Cadrage
En application stricte de l'autorisation humaine `GO_L6_I2_SCOPING` :
- **Mode :** `SCOPING & DESIGN — ANALYSIS FIRST — NO IMPLEMENTATION`.
- **Interdictions formelles :** Aucune modification de code productif, de tests, de scripts opérationnels, de migrations SQL ou de configuration Docker Compose ; aucun démarrage de service ; aucun test Maven ni réacteur complet n'est exécuté pendant cette phase.
- **Objectif :** Produire un plan technique et architectural complet, rigoureux et autonome, permettant une décision humaine éclairée `GO/NO-GO` d'implémentation sans réouverture d'une phase d'étude générale.

---

## 2. Périmètre Exact de L6/I2

### 2.1 Périmètre Inclus (Travail I2)
1. **Reconstruction READ Totale à Blanc (Scénarios R1, R2, R4, R5, R6) :**
   - Réinitialisation coordonnée et atomique de l'espace de données READ (`v3_read` : projections, slots, claims/leases et checkpoints) sans altération de WRITE.
   - Rattrapage intégral à partir des **cinq séquences d'événements immuables WRITE** au moyen des moteurs existants (`DiscoveryEngine`, `ConsumptionEngine`) et des **sept participants de matérialisation READ existants** (`V3ReadParticipants`), en réutilisant leurs identifiants stables (`consumptionId`).
   - Barrière de maintenance exclusive garantissant une indisponibilité totale temporaire des consultations READ (HTTP 503 contrôlé), empêchant tout commit tardif d'anciens workers et toute délivrance d'état partiel ou de faux 404.
   - Définition et vérification formelle de **l'oracle de disponibilité READY** basé sur la capture d'un vecteur de préfixes sûrs $B = (B_{pot}, B_{inv}, B_{id}, B_{cmd}, B_{id\_comp})$ et l'achèvement de tous les slots enrôlés $\le B$.
2. **Reprise après Interruption (Scénario R4) :**
   - Tolérance aux pannes (crash processus, redémarrage PostgreSQL) en cours de reconstruction : reprise déterministe à partir des checkpoints durables sans corruption ni duplication.
3. **Coexistence avec le Flux WRITE Continu (Scénario R5) :**
   - Poursuite sans entrave des admissions et exécutions WRITE pendant la reconstruction READ, les événements postérieurs à $B$ étant traités en continu après atteinte du jalon READY.
4. **Outillage d'Exploitation Minimal et Sûr :**
   - Script CLI d'exploitation unifié (`v3-rebuild-read.py`) supportant préconditions, capture de préfixe, confirmation explicite contre toute destruction accidentelle (`--confirm-destructive`), exécution du rattrapage et vérification d'oracle.
   - Diagnostic de retard, état des workers, santé et absence de verrous résiduels.
5. **Observabilité Dédiée de la Reconstruction :**
   - Métriques Micrometer/Prometheus à cardinalité bornée répondant aux 9 questions clés de l'opérateur (état, progression, backlog restant, erreurs, achèvement).
   - Panneaux Grafana dédiés sans redondance avec I1.

### 2.2 Périmètre Hors Périmètre (Exclusions Formelles)
- **Reconstruction Ciblée par Projection (Scénario R3) :** Exclue en I2 (détaillée et rejetée en section 7 pour violation de cohérence relationnelle).
- **Dimensionnement et Benchmarks de Charge :** Relèvent exclusivement de **L6/I3** (campagnes k6 SPEC-09 / PERF-01 F01-A…J).
- **Modification des Invariants L1–L5 :** Quota READ $Q=2$, durée de lease 240 s, recalcul soldes $O(N)$, SQL `openComponent`, structure des tokens JWT et modèles transactionnels restent inchangés.
- **Convergence V2/V3 et Décommissionnement DDL :** Relèvent exclusivement de **L7**.
- **Distribution Finale et Sécurisation Production :** Relèvent exclusivement de **L8**.
- **Certification Globale SPEC-10 et Full Reactor :** Non requis pour I2.

---

## 3. Exigences et Traçabilité Normative

### 3.1 Matrice d'Exigences
| Exigence | Source Normative | Déjà Disponible (L1–L5, L6/I1) | Travail Spécifique I2 | Hors Périmètre I2 |
|---|---|---|---|---|
| **REC-01** | SPEC-10 §10, SPEC-05 §9 | 7 Consumptions READ (`V3ReadParticipants`), moteurs `DiscoveryEngine` et `ConsumptionEngine`, tables `v3_read` | Procédure de reset coordonné, barrière d'indisponibilité READ (503), capture préfixes $B$, oracle READY, qualification RCV01-A…N | Rejeu sous nouveau `consumptionId` avec destination tierce (REPLAY isolé) |
| **OPS-01 (ciblé)** | SPEC-10 §11, SPEC-05 §1 | 2 boucles adaptatives Discovery/Engine, round-robin, backoff exponentiel, claims et leases | Script de diagnostic et contrôle de progression sous backlog de reconstruction | Qualification de saturation et benchmark d'absence de famine (L6/I3) |
| **RCV01-A** | SPEC-10 §10 | Schémas Flyway V1..V7, V10 de `v3_read` | Reset complet sans survivants de projections, slots et checkpoints | — |
| **RCV01-B** | SPEC-10 §10 | Fencing par lease dans `partition_ownership` | Neutralisation physique des anciens writers et barrière anti-commit tardif | — |
| **RCV01-C** | SPEC-10 §10 | Facade READ retournant `Unavailable` (503) | Indicateur de disponibilité (porte READ) retournant 503 au lieu de 404 pendant le reset | — |
| **RCV01-D** | SPEC-10 §10 | 5 séquences WRITE (`v3_event_counter`, `v3_event_position`) | Rattrapage avec mêmes `consumptionId` depuis position 0 | — |
| **RCV01-E** | SPEC-10 §10 | 3 participants Pot (`MaterializePotVersion`, `MaterializePotBalance`, `MaterializePotVisibility`) | Preuve de traitement correct sans collision ni régression d'état | — |
| **RCV01-F** | SPEC-10 §10 | Participants Invitation, CurrentBinding, Résultats | Preuve de convergence indépendante sans ordonnancement artificiel | — |
| **RCV01-G** | SPEC-10 §10 | Checkpoints atomiques par lot de slots | Reprise déterministe après interruption en cours de reconstruction | — |
| **RCV01-H** | SPEC-10 §10 | Contraintes de slot et checkpoint | Refus d'avancement si preuve de complétion cassée | — |
| **RCV01-I** | SPEC-10 §10 | Table `consumption_slot` | Élimination de tous slots antérieurs (y compris `QUARANTINED`) lors du reset | — |
| **RCV01-L** | SPEC-10 §10 | Isolement strict WRITE / READ | Preuve de non-régression sous arrivées WRITE continues | — |
| **RCV01-M** | SPEC-10 §10 | Authentification EUI et `CurrentBinding` | Indisponibilité (503) pendant rebuild, autorisation normale après READY | — |
| **RCV01-N** | SPEC-10 §10 | Oracles métier L1–L5 | Concordance stricte de l'état reconstruit avec l'état attendu | — |
| **O-01 (rebuild)**| SPEC-08, L6/I1 | Compteurs et timers Micrometer workers I1 | Instrumentation d'état (`pocoma_v3_rebuild_*`), backlog restant, panneaux Grafana | Tracing distribué, APM externe |

---

## 4. Inventaire des Mécanismes Existants

### 4.1 Projections READ Réellement Disponibles
L'audit statique exhaustif du schéma `v3_read` et du module `infra-persistence-read-jdbc` confirme l'existence des neuf projections logiques et de leurs structures physiques :

| Projection Logique | Table(s) PostgreSQL | Source des Faits | Ordre / Dépendances | Checkpoint & Slot | Contraintes & Triggers SQL | Comportement sur Panne / Idempotence |
|---|---|---|---|---|---|---|
| **`PotVersion(P,V)`** | `read_pot_version`, `read_current_pot` | `v3_pot_event(P,V)` + état WRITE@V | Indépendant ; composition complète à V | Checkpoint `POT_EVENT`, slot supprimé au commit | Trigger `read_pot_version_immutable` (UPDATE/DELETE/TRUNCATE interdits) ; `read_current_pot` monotone | Idempotent sur même version ; insertion divergente rejetée |
| **`SubobjectVersion`** | `read_subobject_version` | Composants Pot WRITE@V | Chargé à la demande par `MaterializePotVersion` | Lié au slot de `MaterializePotVersion` | Trigger `read_subobject_immutable` (UPDATE/DELETE/TRUNCATE interdits) ; validation schéma JSONB | Idempotent par clé `(potId, kind, objectId, localVersion)` |
| **`PotBalance(P,V)`** | `read_pot_balance` | Faits financiers WRITE@V | Indépendant ; soldes rationnels complets | Checkpoint `POT_EVENT`, slot supprimé au commit | Trigger `read_pot_balance_immutable` (UPDATE/DELETE/TRUNCATE interdits) ; absence à version terminale $D$ | Idempotent ; recalcul intégral sans dépendance à $V-1$ |
| **`PotVisibility`** | `read_pot_visibility_state`, `read_user_pot` | `v3_pot_event(P,V)` + acteurs WRITE@V | Indépendant ; navigation monotone | Checkpoint `POT_EVENT`, slot supprimé au commit | Verrou de ligne sur `read_pot_visibility_state` ; version monotone | Idempotent ; ignore versions inférieures à la version matérialisée |
| **`CurrentBinding(EUI)`**| `read_current_binding` | `v3_identity_event(EUI,V)` + token WRITE@V | Indépendant ; filtrage des seuls événements EUI | Checkpoint `IDENTITY_EVENT`, slot supprimé au commit | `eui_version` strictement croissante ; unicité `eui_id` | Idempotent ; mise à jour monotone |
| **`CommandResult`** | `read_command_result` | `v3_command_completion_event` + issue WRITE | Indépendant ; issu de l'admission terminale | Checkpoint `COMMAND_COMPLETION_EVENT`, slot supprimé | Trigger `read_command_result_immutable` (UPDATE/DELETE/TRUNCATE interdits) | Idempotent ; insertion ou vérification d'identité exacte |
| **`IdentityRequestResult`**| `read_identity_request_result` | `v3_identity_completion_event` + issue WRITE | Indépendant ; issu de l'admission terminale | Checkpoint `IDENTITY_COMPLETION_EVENT`, slot supprimé | Trigger `read_identity_result_immutable` (UPDATE/DELETE/TRUNCATE interdits) | Idempotent ; insertion ou vérification d'identité exacte |
| **`CurrentInvitation`** | `read_current_invitation` | `v3_invitation_event` + invitation WRITE@V | Indépendant ; lien protégé restitué au créateur | Checkpoint `INVITATION_EVENT`, slot supprimé | Trigger `read_invitation_monotone` ; `read_invitation_no_truncate` | Idempotent ; progression monotone par version |

> [!CRITICAL]
> **Constat Majeur de l'Audit : Immutabilité SQL Renforcée**
> Les tables `read_subobject_version`, `read_pot_version`, `read_pot_balance`, `read_command_result`, `read_identity_request_result` et `read_current_invitation` sont protégées par des triggers PostgreSQL avec clause `ENABLE ALWAYS` interdisant formellement `UPDATE`, `DELETE` et `TRUNCATE`.
> Par conséquent, **un simple `DELETE` ou `TRUNCATE` SQL échoue systématiquement**, même exécuté par un utilisateur privilégié, tant que les triggers sont actifs. Une réinitialisation in-place propre exige soit la recréation du schéma via les migrations canoniques, soit la désactivation explicite et temporaire des triggers dans une session administrative unique.

### 4.2 Moteurs et Briques Génériques Réutilisables
1. **`DiscoveryEngine` (`engine-consumption`) :**
   - Garantit : Lecture cohérente de fenêtres d'événements commis $(K, H]$ sur les cinq flux WRITE sans trous de concurrence ; création atomique des slots libres dans `consumption_slot` et avance de checkpoint dans `discovery_checkpoint`.
   - Ne garantit pas : L'exécution des projections (déléguée à l'Engine).
   - Réutilisation : 100% réutilisable sans modification.
2. **`ConsumptionEngine` (`engine-consumption`) :**
   - Garantit : Acquisition exclusive par lease/token de claim (`consumption_slot`), exécution de la tentative complète via `ConsumptionParticipant`, suppression atomique du slot et commit des effets en READ. Gestion des retries et de la quarantaine.
   - Ne garantit pas : La synchronisation globale entre projections indépendantes.
   - Réutilisation : 100% réutilisable sans modification.
3. **`V3ReadParticipants` (`composition-runtime-spring`) :**
   - Garantit : L'assemblage inerte des 7 participants de matérialisation READ avec leurs sources WRITE en lecture seule et leurs stores READ (`JdbcPotVersionStore`, `JdbcPotBalanceStore`, etc.).
   - Réutilisation : 100% réutilisable comme catalogue de référence.
4. **`V3BootstrapGuard` (`composition-runtime-spring`) :**
   - Garantit : La validation statique et dynamique des privilèges SQL minimaux (aucun droit d'écriture sur WRITE pour le rôle READ, conformité du catalogue).
   - Réutilisation : Réutilisé pour sécuriser l'environnement de reconstruction.

### 4.3 Outillage Opérationnel Existant
- `scripts/operations/setup-v3-database.py` : Script Python idempotent provisionnant les bases `pocoma_write` et `pocoma_read`, exécutant les migrations Flyway et configurant les rôles avec privilèges étanches.
- `scripts/v3/bootstrap-l2-routing.sql` : Initialise `consumption_routing_contract`, les checkpoints à 0 et les ownerships de partitions pour chaque famille.
- `scripts/v3/grant-l2-lifecycle.sql` et `grant-l2-discovery.sql` : Octroient les privilèges DML minimaux aux rôles applicatifs.

---

## 5. Scénarios de Reconstruction

### 5.1 R1 — Construction Initiale (Base READ Vierge)
- **Préconditions :** Base `pocoma_write` peuplée d'événements historiques ; base `pocoma_read` créée avec schéma `v3_read` vierge.
- **Opérations :** Application des migrations Flyway READ V1..V7, V10 ; application des scripts de routing et de grants ; capture du préfixe $B$ dans WRITE ; démarrage des workers de reconstruction sur les 7 Consumptions READ ; surveillance de l'achèvement jusqu'à $B$ ; activation du statut READY.
- **Critère de Succès :** 100% des projections créées, concordance avec les faits WRITE, zéro slot résiduel $\le B$.
- **Inclusion I2 :** OBLIGATOIRE.

### 5.2 R2 — Reconstruction Complète (Après Corruption ou Perte)
- **Préconditions :** Base READ compromise, désynchronisée ou corrompue ; flux WRITE en fonctionnement nominal.
- **Opérations :**
  1. Bascule de READ en mode `MAINTENANCE` (l'API HTTP READ renvoie immédiatement 503).
  2. Arrêt physique et drainage de tous les workers READ actifs (vérification de libération des baux `partition_ownership` et absence de requêtes actives dans `pg_stat_activity`).
  3. Réinitialisation administrative complète du schéma `v3_read`.
  4. Capture du vecteur de préfixes sûrs $B = (B_{pot}, B_{inv}, B_{id}, B_{cmd}, B_{id\_comp})$.
  5. Relance des 7 Consumptions READ depuis la position 0.
  6. Rattrapage jusqu'à $B$ et résorption de tous les slots $\le B$.
  7. Vérification des oracles de cohérence métier.
  8. Bascule du mode `MAINTENANCE` à `READY` (l'API HTTP READ reprend les réponses 200).
- **Inclusion I2 :** OBLIGATOIRE (Scénario de référence).

### 5.3 R3 — Reconstruction Ciblée d'une Famille de Projections
- **Analyse :** Reconstruction isolée d'une projection (ex. `PotBalance`) sans toucher aux autres.
- **Risques :**
  - Rupture de la cohérence relationnelle : un Pot à la version 10 avec un solde à la version 2 provoquerait des 503 massifs et imprévisibles sur les consultations client.
  - Verrous et triggers d'immutabilité empêchant le nettoyage sélectif d'une table sans intervention manuelle risquée.
  - Complexité opérationnelle disproportionnée sans besoin métier démontré.
- **Décision :** **HORS PÉRIMÈTRE I2**. La recommandation normative de SPEC-10 §10 ("Aucune remise à zéro isolée des checkpoints ou slots n'est sûre") est maintenue.

### 5.4 R4 — Reprise après Interruption en Cours de Reconstruction
- **Préconditions :** Panne matérielle, coupure réseau ou crash PostgreSQL survenant pendant le rattrapage R2 (checkpoints et slots partiellement créés).
- **Comportement Garanti :**
  - La porte de maintenance reste à `MAINTENANCE` : aucune lecture client n'est autorisée.
  - Au redémarrage, les moteurs `DiscoveryEngine` et `ConsumptionEngine` reprennent exactement là où ils s'étaient arrêtés (checkpoints et slots persistés durablement).
  - Alternativement, l'opérateur peut relancer un reset propre.
- **Critère de Succès :** Aucune perte de données, reprise sans blocage, statut READY atteint dès que tous les slots $\le B$ sont résorbés.
- **Inclusion I2 :** OBLIGATOIRE.

### 5.5 R5 — Coexistence avec le Runtime Normal (Arrivées WRITE Continues)
- **Préconditions :** Admissions HTTP et exécutions de commandes WRITE continuent à produire des événements dans `pocoma_write` pendant la reconstruction READ.
- **Comportement Garanti :**
  - WRITE n'accède jamais à `pocoma_read` : débit d'admission et temps de commande 100% préservés.
  - La borne de reconstruction $B$ est figée au début du rattrapage.
  - Les événements $V > B$ découverts ou enrôlés ne retardent pas l'évaluation de l'oracle READY.
  - Dès que l'oracle READY est validé pour $B$, READ est réouvert et les événements postérieurs à $B$ sont dépilés selon le cours nominal d'éventuelle cohérence.
- **Inclusion I2 :** OBLIGATOIRE.

### 5.6 R6 — Retour en Service Contrôlé (Gate READY)
- **Critères Formels de Bascule :**
  1. $\forall f \in [1..5], \forall p \in [0..3], \text{checkpoint}(f, p) \ge B_f$.
  2. $\forall f \in [1..5], \text{COUNT}(\text{slots non terminés avec position} \le B_f) = 0$.
  3. Oracles de cohérence interne validés (absence de référence d'Expense orpheline, concordance `CurrentPot` $\rightarrow$ `PotVersion`).
  4. Bascule de l'indicateur de disponibilité vers `READY`.
- **Inclusion I2 :** OBLIGATOIRE.

---

## 6. Architecture de Référence Retenue

### 6.1 Architecture Recommandée : Reconstruction en Place avec Barrière de Maintenance Exclusive

L'architecture sélectionnée pour Pocoma V3 L6/I2 est la **reconstruction en place coordonnée avec barrière de maintenance exclusive**.

```
                   +---------------------------------------------+
                   |           FLUX WRITE CONTINU                |
                   |   (Commandes, Admissions, Événements)       |
                   +---------------------------------------------+
                                      |
                                      | (Événements immuables denses)
                                      v
+---------------------------------------------------------------------------------+
|                        POCOMA_WRITE (v3_write)                                   |
|   v3_event_counter / v3_event_position : Séquences [POT, INVIT, ID, CMD, ID_COMP]  |
+---------------------------------------------------------------------------------+
                                      |
                       (1) Freeze Préfixe B | (Lecture seule par Discovery)
                                      v
+---------------------------------------------------------------------------------+
|                                 L6/I2 ORCHESTRATEUR                             |
|                           (scripts/operations/v3-rebuild-read.py)               |
+---------------------------------------------------------------------------------+
          |                                                   |
          | (2) Arrêt des Workers READ                        | (5) Surveillance
          | (3) Reset v3_read (DDL canonique)                 |     checkpoints >= B
          | (4) Démarrage 7 Workers READ                      |     slots <= B == 0
          v                                                   v
+---------------------------------------------------------------------------------+
|                        POCOMA_READ (v3_read)                                    |
|   +-------------------------------------------------------------------------+   |
|   | Barrière de Disponibilité : read_readiness_state = 'MAINTENANCE'        |   |
|   +-------------------------------------------------------------------------+   |
|   | 7 Consumptions READ (V3ReadParticipants) :                              |   |
|   | - MaterializePotVersion            - MaterializePotBalance              |   |
|   | - MaterializePotVisibility         - MaterializeCurrentBinding          |   |
|   | - MaterializeCommandResult         - MaterializeIdentityRequestResult   |   |
|   | - MaterializeInvitation                                                 |   |
|   +-------------------------------------------------------------------------+   |
|   | Projections : PotVersion, Subobjects, Balances, Visibility, etc.        |   |
+---------------------------------------------------------------------------------+
                                      |
                       (6) Bascule READY (read_readiness_state = 'READY')
                                      v
                   +---------------------------------------------+
                   |           API HTTP READ (v3-http-read)      |
                   |      503 pendant maintenance -> 200 OK      |
                   +---------------------------------------------+
```

### 6.2 Déroulement Séquentiel de l'Opération
1. **Activation de la Barrière :** L'indicateur de disponibilité `read_readiness_state` est positionné à `MAINTENANCE`. Tout appel sur l'API HTTP READ renvoie immédiatement `503 Service Unavailable`.
2. **Neutralisation Physique des Écrivains :**
   - Interruption contrôlée du service worker READ.
   - Vérification de l'absence de baux actifs : `SELECT COUNT(*) FROM v3_read.partition_ownership WHERE owned_until > now()` doit être nul.
   - Vérification de l'absence de requêtes en vol : `pg_stat_activity` sur la base `pocoma_read` pour le rôle `pocoma_read`.
3. **Réinitialisation Administrative du Schéma READ :**
   - Exécution par le rôle administrateur `pocoma` de la recréation du schéma `v3_read` via les migrations Flyway canoniques (`v3-consumption-read`, `v3-read-business`, `v3-read-invitation`) et les scripts d'initialisation de routing (`bootstrap-l2-routing.sql`, `grant-l2-lifecycle.sql`, `grant-l2-discovery.sql`).
   - Le schéma est rétabli dans un état vierge, avec `consumption_routing_contract` configuré pour les 7 Consumptions, les 28 checkpoints à 0 et 0 slots.
4. **Capture du Vecteur de Préfixes Sûrs $B$ :**
   - Requête atomique sur `pocoma_write` :
     ```sql
     SELECT family, position FROM v3_write.v3_event_counter;
     ```
   - Enregistrement du tuple $B = (B_{pot}, B_{inv}, B_{id}, B_{cmd}, B_{id\_comp})$.
5. **Démarrage du Rattrapage :**
   - Activation des 7 participants via le worker standard avec les mêmes `consumptionId`.
   - `DiscoveryEngine` découvre par lots de taille 32..128 depuis 0.
   - `ConsumptionEngine` traite les slots et matérialise les projections de manière purement asynchrone et concurrente.
6. **Contrôle de Progression et Détection de Blocage :**
   - L'orchestrateur sonde à intervalle régulier les métriques Prometheus et les tables de checkpoints.
7. **Validation de l'Oracle READY :**
   - Confirmation que pour chaque famille, toutes les partitions ont dépassé $B_f$ et qu'aucun slot $\le B_f$ ne subsiste.
   - Contrôles d'intégrité référentielle READ (non-régression).
8. **Retour en Service :**
   - Positionnement de `read_readiness_state` à `READY`.
   - L'API HTTP READ dessert à nouveau le trafic client nominal (200 OK).

---

## 7. Alternatives Écartées et Justification

### 7.1 Comparatif des Alternatives Architecturales
| Critère | Option 1 : In-Place Reset (Retenue) | Option 2 : Schéma Séparé (Blue/Green) + Rename | Option 3 : Reconstruction Ciblée | Option 4 : Rejeu sans Reset |
|---|:---:|:---:|:---:|:---:|
| **Simplicité architecturale** | **Maximale** (réutilise 100% des briques) | Moyenne (gestion double schéma / DDL) | Faible (filtrage fin complexe) | Inapplicable (erreurs DDL) |
| **Sécurité des données WRITE** | **Absolue** (READ-ONLY strict) | **Absolue** (READ-ONLY strict) | **Absolue** | **Absolue** |
| **Intégrité relationnelle READ** | **Parfaite** (schéma vierge certifié) | **Parfaite** | **Dangereuse** (incohérences de versions) | Nulle (collisions de clés primaires) |
| **Compatibilité HikariCP** | **Excellente** (pas d'invalidation d'OID) | **Médiocre** (invalidation OID sur renommage) | Bonne | N/A |
| **Disponibilité READ** | Indisponibilité totale temporaire acceptée | Maintien théorique d'anciennes lectures | Partielle mais incohérente | N/A |
| **Reprise après panne (R4)** | **Triviale** (reprise checkpoint ou re-reset)| Complexe (nettoyage schéma temporaire) | Difficile | N/A |
| **Conformité aux SPEC V3** | **100% conforme** (SPEC-05 §9, SPEC-10 §10) | Contredit SPEC-05 ("dans la même base")| Contredit SPEC-10 §10 | Contredit SPEC-04/05 |
| **Coût de qualification** | **Faible & borné** ($\le 10$ min) | Élevé (tests de bascule et caches SQL) | Très élevé | N/A |

### 7.2 Motifs d'Élimination des Alternatives 2, 3 et 4
- **Rejet de l'Option 2 (Schéma séparé / Blue-Green) :** Le renommage de tables ou de schémas en PostgreSQL invalide les plans d'exécution préparés en cache JDBC (`cached plan must not change result type`) et provoque des défaillances de connexion dans HikariCP. De plus, SPEC-10 accepte explicitement l'indisponibilité totale temporaire de READ ; introduire un système Blue/Green violerait le principe de simplicité (Section 2.2).
- **Rejet de l'Option 3 (Reconstruction ciblée) :** Les projections READ forment un ensemble cohérent pour les consultations utilisateur (le détail d'un Pot assemble `PotVersion`, `SubobjectVersion` et `PotBalance`). Une reconstruction partielle produirait des états temporairement incohérents et risquerait de contourner les triggers d'immutabilité.
- **Rejet de l'Option 4 (Rejeu sans reset) :** Impossible en pratique car les triggers `ENABLE ALWAYS` et les clés primaires interdisent toute réinsertion sur des versions existantes.

---

## 8. Invariants et Sécurité

### 8.1 Préservation des Invariants L1–L5
- **Immutabilité des faits :** Aucun événement WRITE n'est modifié, renuméroté ou supprimé. Les droits PostgreSQL du rôle de reconstruction sur `pocoma_write` sont strictement limités à `SELECT`.
- **Ordre logique et monotonie :** Les séquences d'événements restent strictement ordonnées par `(family, position)`. Les checkpoints READ progressent de manière strictement monotone.
- **Séparation WRITE / READ :** Aucune transaction distribuée ; aucune transaction WRITE ne dépend de l'état de READ.
- **Idempotence et Fencing :** Les slots de reconstruction utilisent les mécanismes de lease et de claim UUID standard, éliminant tout risque de double exécution.

### 8.2 Barrière Opérationnelle et Anti-Commit Tardif
Pour satisfaire l'exigence **RCV01-B** (neutralisation d'une transaction READ en vol lors de la demande de reset) :
1. Le script de reconstruction vérifie l'expiration ou la révocation de tous les baux dans `v3_read.partition_ownership`.
2. Si des transactions résiduelles existent sur `pocoma_read`, une instruction administrative `pg_terminate_backend(pid)` est envoyée pour clore physiquement les connexions orphelines.
3. Le drop/recréation du schéma acquiert un verrou `ACCESS EXCLUSIVE` sur les tables du schéma `v3_read`, garantissant qu'aucune transaction antérieure ne peut commettre d'effets après le reset.

### 8.3 Protection contre les Destructions Accidentelles
Pour interdire toute destruction accidentelle de données :
- Le script `v3-rebuild-read.py` refuse d'agir si l'argument `--confirm-destructive` n'est pas explicitement fourni avec la valeur littérale `RESET_READ_DATA`.
- Le script vérifie par prévol que la base cible est explicitement nommée `pocoma_read` et que la base WRITE n'est jamais ciblée par une commande destructive.

---

## 9. Modèle d'Exploitation Opérationnelle

### 9.1 Composant Minimal : Script CLI `v3-rebuild-read.py`
En application du principe de simplicité (Section 2.2), aucun framework complexe d'orchestration ni aucune nouvelle API HTTP d'administration ne sont introduits. L'ensemble des capacités opérationnelles est porté par un script CLI Python standard situé dans `scripts/operations/v3-rebuild-read.py`.

#### Réponses aux 5 Questions Fondamentales de Conception (Section 2.2) :
1. **Pourquoi est-il nécessaire ?**
   Pour fournir à l'exploitant un outil déterministe, reproductible et sécurisé orchestrant les étapes de maintenance (barrière, reset, freeze préfixe, relance, validation READY).
2. **Quel composant existant ne peut pas remplir ce rôle ?**
   `setup-v3-database.py` provisionne l'environnement initial mais ne gère ni la barrière de maintenance, ni la capture des préfixes $B$, ni le suivi d'achèvement des slots, ni l'oracle de remise en service.
3. **Quelle responsabilité exacte porte-t-il ?**
   L'orchestration séquentielle de la procédure de reconstruction, la vérification des préconditions, la sécurisation des actions destructives et l'évaluation de l'oracle READY.
4. **Peut-il être supprimé ou simplifié ?**
   Il est déjà minimal : un script unique CLI en Python 3 sans dépendance externe lourde (utilise `psql` ou le driver PostgreSQL standard).
5. **Quel est son coût de maintenance ?**
   Très faible, comparable à `setup-v3-database.py`.

### 9.2 Interface en Ligne de Commande (CLI)
```sh
# 1. Vérification des préconditions et diagnostic d'état
python3 scripts/operations/v3-rebuild-read.py --status

# 2. Exécution complète avec barrière, reset et rattrapage sécurisé
python3 scripts/operations/v3-rebuild-read.py --rebuild --confirm-destructive=RESET_READ_DATA

# 3. Mode pas-à-pas (opérations unitaires)
python3 scripts/operations/v3-rebuild-read.py --freeze-prefix
python3 scripts/operations/v3-rebuild-read.py --reset-schema --confirm-destructive=RESET_READ_DATA
python3 scripts/operations/v3-rebuild-read.py --await-ready --timeout=300
python3 scripts/operations/v3-rebuild-read.py --verify-oracles
python3 scripts/operations/v3-rebuild-read.py --set-ready
```

### 9.3 Diagnostic et Requêtes SQL Opératoires
L'exploitant dispose de requêtes SQL simples et non bloquantes pour auditer le retard :
```sql
-- Retard par famille de consommation READ
SELECT
    rc.family,
    rc.consumption_id,
    c.position AS current_write_position,
    MIN(dc.position) AS min_read_checkpoint,
    MAX(c.position) - MIN(dc.position) AS lag_positions,
    COUNT(cs.consumable_id) FILTER (WHERE cs.state <> 'QUARANTINED') AS pending_slots,
    COUNT(cs.consumable_id) FILTER (WHERE cs.state = 'QUARANTINED') AS quarantined_slots
FROM v3_read.consumption_routing_contract rc
JOIN v3_write.v3_event_counter c ON c.family =
    CASE rc.family
        WHEN 'POT_EVENT' THEN 'POT'
        WHEN 'INVITATION_EVENT' THEN 'INVITATION'
        WHEN 'IDENTITY_EVENT' THEN 'IDENTITY'
        WHEN 'COMMAND_COMPLETION_EVENT' THEN 'COMMAND_COMPLETION'
        WHEN 'IDENTITY_COMPLETION_EVENT' THEN 'IDENTITY_COMPLETION'
    END
LEFT JOIN v3_read.discovery_checkpoint dc ON dc.consumption_id = rc.consumption_id
LEFT JOIN v3_read.consumption_slot cs ON cs.consumption_id = rc.consumption_id
GROUP BY rc.family, rc.consumption_id, c.position;
```

---

## 10. Observabilité de la Reconstruction

### 10.1 Réponse aux 9 Questions Clés de l'Opérateur
| Question Opérateur | Instrument / Métrique Micrometer-Prometheus | Source / Mécanisme |
|---|---|---|
| **1. La reconstruction est-elle démarrée ?** | `pocoma_v3_rebuild_status{state="idle|in_progress|ready|failed"}` | Jauge globale d'état (1 = actif) |
| **2. Quelle famille de projection traite-t-elle ?** | `pocoma_v3_worker_polling_cycles_total{family="..."}` | Compteur de cycles par famille |
| **3. Progresse-t-elle ?** | `pocoma_v3_rebuild_checkpoint{family="...", partition="0..3"}` | Jauge du watermark de checkpoint par partition |
| **4. Est-elle bloquée ?** | `pocoma_v3_rebuild_stalled{family="..."}` | Jauge binaire (1 si aucun progrès depuis > 30s) |
| **5. Combien d'éléments restent à traiter ?** | `pocoma_v3_rebuild_remaining_events{family="..."}`, `pocoma_v3_rebuild_remaining_slots{family="..."}` | Événements au-delà du checkpoint jusqu'à $B$, slots ouverts $\le B$ |
| **6. Des erreurs se produisent-elles ?** | `pocoma_v3_attempt_outcomes_total{outcome="FAILED|QUARANTINED"}` | Compteur d'échecs de tentative |
| **7. Consommation normale des ressources ?** | `hikaricp_connections_active`, `hikaricp_connections_acquire_seconds_max`, `jvm_memory_used_bytes` | Métriques JDBC et JVM L6/I1 |
| **8. La reconstruction est-elle terminée ?** | `pocoma_v3_rebuild_status{state="ready"}` = 1 | Jauge d'état validée après l'oracle READY |
| **9. Le résultat est-il cohérent ?** | `pocoma_v3_rebuild_consistency_check_total{status="pass|fail"}` | Compteur de validation des oracles métier |

### 10.2 Cardinalité et Sécurité Métrologique
- **Règle absolue :** Aucun identifiant métier non borné (`potId`, `eui`, `requestId`, `userId`) n'est présent dans les labels.
- **Labels autorisés :** `family` (7 valeurs finies), `partition` (4 valeurs finies `0..3`), `state` (4 valeurs finies), `outcome` (valeurs finies de `ConsumptionOutcome`).

### 10.3 Enrichissement du Tableau de Bord Grafana
Le tableau de bord provisionné `pocoma-v3-observability.json` (18 panneaux) est enrichi d'une rangée collapsible dédiée « V3 READ Reconstruction & Maintenance » comprenant 4 panneaux ciblés :
1. **Statut & Phase de Maintenance :** Stat panel affichant l'état courant (`READY`, `REBUILDING`, `MAINTENANCE`).
2. **Backlog & Éléments Restants par Famille :** Time series affichant `pocoma_v3_rebuild_remaining_events` et `remaining_slots`.
3. **Avancement des Checkpoints vs Borne B :** Bar gauge montrant la position relative de chaque partition face à $B$.
4. **Débit de Matérialisation & Taux d'Erreurs :** Time series combinant tentatives réussies et échecs.

---

## 11. Performances et Maîtrise de la Charge

### 11.1 Impact Anticipé sur l'Infrastructure
- **Base WRITE (`pocoma_write`) :**
  - Charge minimale : uniquement des lectures séquentielles ou indexées sur les événements commis.
  - Zero lock contention : aucune mise à jour ni verrou sur les tables de commandes ou d'événements.
  - Absence de transactions longues : lectures de fenêtres bornées par lot de 32..128 éléments.
- **Base READ (`pocoma_read`) :**
  - Écritures en rafale (batch inserts) dans les tables de projections et de slots.
  - Index PostgreSQL sollicités sur les clés primaires.
  - Taille des transactions strictement bornée par slot : aucune transaction géante risquant de saturer le WAL.
- **Ressources Mémoire & Pools HikariCP :**
  - Utilisation des pools existants configurés (`maximumPoolSize=4` par pool en environnement local).
  - Capacité worker régulée par `WorkerCapacity` (évite toute accumulation d'objets en mémoire JVM).

### 11.2 Articulation avec L6/I3 et SPEC-09
Aucun benchmark de saturation ni mesure de SLO de latence n'est réalisé pendant L6/I2. L6/I2 s'assure uniquement que la reconstruction s'exécute de manière bornée, fluide et sans contention excessive. Les mesures chiffrées de débit sous charge et les tests de performance font l'objet exclusif de l'incrément L6/I3.

---

## 12. Modifications Prévues par Module

Conformément à la politique normative [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md), les modules touchés lors de l'implémentation sont strictement déclarés :

| Module Maven / Répertoire | Type de Fichier | Modifications Prévues | Justification |
|---|---|---|---|
| `composition-runtime-spring` | Code Java | 1. Ajout d'une table / composant léger `read_readiness_state` injecté dans `V3ReadFacadeComposition` pour renvoyer 503 en mode maintenance.<br>2. Ajout de l'instrumentation métrique de reconstruction (`RebuildMetrics`). | Indisponibilité 503 obligatoire (RCV01-C/M) et observabilité de la maintenance |
| `scripts/operations/` | Script Python | Création de `v3-rebuild-read.py` (CLI d'exploitation et d'orchestration de la reconstruction). | Outillage opérateur d'exploitation unifié |
| `docker/grafana/dashboards/` | JSON | Ajout de 4 panneaux dédiés à la reconstruction dans `pocoma-v3-observability.json`. | Visualisation en temps réel de l'opération |
| `runtime-task-consumption-worker` | Tests Java | Ajout de la suite de tests PostgreSQL de référence `V3ReadReconstructionPostgresTest` couvrant RCV01-A à RCV01-N. | Preuve automatisée des invariants de reconstruction |
| `runtime-web-api` | Tests Java | Test de non-régression HTTP vérifiant le 503 pendant maintenance et le 200 après READY. | Preuve du comportement de la façade Web |

**Impacts de production strictement interdits :**
- Aucun changement dans `domain-pot`, `domain-useridentity`, `domain-consumption`.
- Aucun changement dans `engine-consumption` (le moteur générique reste intact).
- Aucun changement dans `infra-persistence-primary-jpa` (WRITE reste 100% étanche).
- Aucun changement dans les schémas DDL de WRITE.

---

## 13. Matrice de Qualification Strictement Bornée

### 13.1 Matrice des Scénarios de Test
| Scénario | Invariant Vérifié | Test Existant Réutilisable | Nouveau Test Nécessaire | Oracle de Réussite | Durée Estimée |
|---|---|---|---|---|:---:|
| **RCV01-A** | Reset complet de `v3_read` | `setup-v3-database.py` | `V3ReadReconstructionPostgresTest#rcv01_a_resetTotal` | Projections, slots et checkpoints nuls ; WRITE intact | ~15 s |
| **RCV01-B** | Barrière writer tardif | `ReadParticipantsIntegrationPostgresTest` | `V3ReadReconstructionPostgresTest#rcv01_b_lateWriterBarrier` | Transaction ancienne rejetée ; aucun effet tardif commis | ~20 s |
| **RCV01-C** | Indisponibilité READ (503) | `V3ReadHttpContractTest` | `V3ReadReconstructionPostgresTest#rcv01_c_readIndisponible` | Requête client renvoie 503 au lieu d'un 404 trompeur | ~10 s |
| **RCV01-D** | Rattrapage 5 séquences | `V3InvitationReadPostgresTest` | `V3ReadReconstructionPostgresTest#rcv01_d_rebuildFromFiveSequences` | Checkpoints progressent de 0 à $B$ avec mêmes `consumptionId` | ~25 s |
| **RCV01-E** | 3 Consumptions directes Pot | `V3WorkerCompositionPostgresTest` | `V3ReadReconstructionPostgresTest#rcv01_e_threePotConsumptions` | `PotVersion`, `PotBalance`, `PotVisibility` matérialisés sans collision | ~20 s |
| **RCV01-F** | Désynchronisation inter-flux | `ReadParticipantsIntegrationPostgresTest` | `V3ReadReconstructionPostgresTest#rcv01_f_interleavedEvents` | Convergence globale sans ordonnancement artificiel | ~20 s |
| **RCV01-G** | Crash et reprise (R4) | `V3WorkerPostgresTest` | `V3ReadReconstructionPostgresTest#rcv01_g_crashAndRecovery` | Reprise déterministe depuis checkpoint persistant | ~25 s |
| **RCV01-H** | Refus restauration partielle | `SlotNavigationPostgresTest` | `V3ReadReconstructionPostgresTest#rcv01_h_partialRestoreRefused` | Détection d'incohérence checkpoint/slot et refus READY | ~15 s |
| **RCV01-L** | WRITE continu pendant rebuild | `V3PotCommandCompositionPostgresTest` | `V3ReadReconstructionPostgresTest#rcv01_l_writeConcurrency` | Événements $V>B$ absorbés après READY sans bloquer la bascule | ~30 s |
| **RCV01-M** | Consultation après READY | `V3ReadHttpContractTest` | `V3ReadReconstructionPostgresTest#rcv01_m_readReadySuccess` | Lectures 200 OK autorisées avec `CurrentBinding` actif | ~15 s |
| **RCV01-N** | Intégrité et oracles métier | `PotHistoryPostgresTest` | `V3ReadReconstructionPostgresTest#rcv01_n_oracleIntegrity` | Équivalence bit-à-bit avec l'état autoritatif WRITE | ~20 s |

### 13.2 Structure des Gates Proposées
Conformément aux instructions, la qualification est structurée en **trois gates étanches au maximum** :

```
+-------------------------------------------------------------------------------+
| GATE A — Contrats et Mécanismes Ciblés (Unit / Spring Context)                |
| Commande : ./mvnw -pl composition-runtime-spring -am test                     |
| Objectif : Valider l'indicateur de maintenance, le calcul de préfixe B et     |
|            l'instrumentation métrique Rebuild.                                |
| Budget Plafond : 120 secondes                                                 |
+-------------------------------------------------------------------------------+
                                        | (PASS requis)
                                        v
+-------------------------------------------------------------------------------+
| GATE B — Reconstruction Fonctionnelle PostgreSQL (RCV01-A...N)                |
| Commande : ./mvnw -pl runtime-task-consumption-worker -am                    |
|            -Dtest=V3ReadReconstructionPostgresTest test                       |
| Objectif : Prouver les scénarios déterministes RCV01-A à RCV01-N sur          |
|            environnement PostgreSQL réel avec injection d'incidents.           |
| Budget Plafond : 240 secondes                                                 |
+-------------------------------------------------------------------------------+
                                        | (PASS requis)
                                        v
+-------------------------------------------------------------------------------+
| GATE C — Exploitation Locale & Validation Opérationnelle                      |
| Commande : python3 scripts/operations/verify-l6-i2-rebuild.sh                 |
| Objectif : Valider le script CLI v3-rebuild-read.py, les garde-fous contre     |
|            les actions destructives, la bascule 503 -> 200 et Prometheus.      |
| Budget Plafond : 180 secondes                                                 |
+-------------------------------------------------------------------------------+
```

---

## 14. Budgets et Conditions d'Arrêt

### 14.1 Plafonds d'Exécution
- **Gate A :** Plafond strict de **2 minutes** (120 s).
- **Gate B :** Plafond strict de **4 minutes** (240 s).
- **Gate C :** Plafond strict de **3 minutes** (180 s).
- **Budget cumulé de qualification L6/I2 :** $\le$ **9 minutes** au total.

### 14.2 Conditions d'Arrêt Immédiat et Discipline de Rejeu
- **Aucune boucle de rejeu automatique :** Tout échec (`FAIL` ou `ERROR`) entraîne l'arrêt immédiat de la campagne.
- **Règle du Diagnostic Unique :** Un échec donne droit à une analyse de cause racine et à un unique rejeu ciblé après correction documentée.
- **Dépassement de budget :** Tout test dépassant son budget plafond est interrompu et qualifié en anomalie de performance locale.
- **Aucun Full Reactor automatique :** Conformément à la politique de vérification, le full reactor Maven (`./mvnw test`) et la gate globale d'architecture (`architecture-tests`) sont formellement **interdits** pour la clôture locale de I2 et sont réservés au jalon majeur de clôture L6 (G6).

---

## 15. Risques et Réserves

### 15.1 Registre des Risques Spécifiques I2
| Réf. | Risque Identifié | Probabilité | Impact | Stratégie d'Atténuation L6/I2 |
|---|---|:---:|:---:|---|
| **RSK-I2-01** | Trigger d'immutabilité bloquant le reset in-place | Élevée | Majeur | Réinitialisation par recréation administrative du schéma `v3_read` via Flyway plutôt que par `DELETE/TRUNCATE` direct. |
| **RSK-I2-02** | Faux 404 renvoyé au client pendant le rattrapage au lieu d'un 503 | Élevée | Majeur | Ajout d'une barrière de disponibilité explicite (`read_readiness_state`) vérifiée en tête de la façade READ. |
| **RSK-I2-03** | Commit tardif d'un worker READ après le reset | Moyenne | Critique | Vérification de l'absence de baux actifs dans `partition_ownership` et déconnexion forcée via `pg_terminate_backend`. |
| **RSK-I2-04** | Destruction accidentelle de la base de production | Faible | Catastrophique| Protection CLI exigeant l'argument obligatoire `--confirm-destructive=RESET_READ_DATA` et validation du nom de base `pocoma_read`. |
| **RSK-I2-05** | Arrivées massives WRITE pendant le rebuild empêchant d'atteindre READY | Moyenne | Moyen | L'oracle READY est strictement borné au préfixe figé $B$ ; les événements $> B$ sont traités en flux nominal après ouverture. |

### 15.2 Réserves Héritées L6/I1 et L1–L5
- **AUD-L6-I1-07 (Allocations chemins chauds) :** Différé post-L6.
- **AUD-L6-I1-08 (Compteur admission redondant) :** Maintenu pour compatibilité métrologique.
- **FIND-L4-01 (Quota READ $Q=2$) :** Maintenu inchangé à 2 permis.
- **FIND-L2-01 (Lease READ 240 s) :** Maintenu inchangé.
- **FIND-L4-03 (Recalcul Balance $O(N)$) :** Maintenu inchangé.
- **Sécurisation de production et mTLS :** Reste réservé à L8.

---

## 16. Décisions Humaines Nécessaires avant GO Implémentation

Pour autoriser le passage en phase d'implémentation de L6/I2, l'arbitrage humain doit expressément statuer sur les points suivants :

1. **Ratification de l'Architecture de Référence :**
   Validation du choix de la **reconstruction en place coordonnée avec barrière de maintenance exclusive** et rejet formel de la reconstruction ciblée (R3) et du Blue/Green schema (Option 2).
2. **Validation du Protocole de Reset :**
   Approbation de la recréation du schéma `v3_read` via les migrations Flyway canoniques et scripts de routing sous rôle administrateur `pocoma`, comme mécanisme d'effacement propre respectant les triggers d'immutabilité.
3. **Ratification de la Barrière d'Indisponibilité (RCV01-C) :**
   Approbation de l'introduction de l'indicateur de maintenance `read_readiness_state` assurant le renvoi systématique de HTTP 503 pendant la reconstruction, interdisant la délivrance de faux 404.
4. **Approbation des Budgets de Qualification :**
   Ratification des plafonds d'exécution des trois gates A (120 s), B (240 s) et C (180 s), sans full reactor Maven.

---

## 17. Critères d'Acceptation de l'Incrément I2

L'incrément L6/I2 sera considéré comme achevé et éligible à la clôture formelle lorsque :
1. Le script d'exploitation `v3-rebuild-read.py` est implémenté, documenté et opérationnel.
2. La barrière d'indisponibilité (503) et l'oracle READY sont démontrés fonctionnellement.
3. Les 11 scénarios déterministes RCV01-A à RCV01-N sont validés avec succès sur PostgreSQL réel sans erreur ni skip (Gate B PASS).
4. Le parcours d'exploitation locale et la progression des métriques de reconstruction dans Prometheus/Grafana sont validés (Gate C PASS).
5. Aucun composant WRITE ni aucune frontière d'isolation n'ont été altérés.
6. Le budget global de test reste strictement inférieur à 9 minutes.

---

## 18. Plan d'Exécution Proposé

Sous réserve de l'obtention du mandat formel `GO_L6_I2_IMPLEMENTATION`, l'implémentation sera exécutée en **une seule passe groupée** :

1. **Étape 1 — Barrière de Maintenance et Observabilité (Java) :**
   - Implémentation de `read_readiness_state` et condition 503 dans `V3ReadFacadeComposition`.
   - Instrumentation Micrometer du statut et des backlogs de reconstruction.
2. **Étape 2 — Outillage d'Exploitation CLI (Python / SQL) :**
   - Implémentation de `scripts/operations/v3-rebuild-read.py`.
   - Ajout des panneaux Grafana correspondants.
3. **Étape 3 — Suite de Qualification PostgreSQL (Tests) :**
   - Implémentation de `V3ReadReconstructionPostgresTest` (scénarios RCV01-A..N).
   - Validation Gate A et Gate B.
4. **Étape 4 — Qualification Opérationnelle Locale & Rapport :**
   - Exécution de la Gate C (script d'exploitation de bout en bout).
   - Publication du rapport de qualification et soumission à revue humaine.
