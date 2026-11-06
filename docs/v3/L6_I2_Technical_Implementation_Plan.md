# Pocoma V3 — L6/I2 — Plan Technique Consolidé d'Implémentation
## Exploitation & Reconstruction READ

**Date :** 6 novembre 2026
**Dépôt :** `kartaguez/pocoma`
**Branche :** `feat/v3-architecture`
**SHA de référence :** `6a1c04fa3f5b419b800754893e56790702a13a5b`
**Autorisation humaine :** `GO_L6_I2_SCOPING`
**Statut du document :** `CONSOLIDATED DESIGN COMPLETE — READY FOR IMPLEMENTATION APPROVAL`
**Autorité normative :** [V3 Master Plan](V3_Implementation_Master_Plan.md), [L6 Design & Verification Plan](L6_Design_and_Verification_Plan.md), [SPEC-03](SPEC-03_Events_Requests_Consumables.md), [SPEC-04](SPEC-04_Read_Projections.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-08](SPEC-08_Observability.md), [SPEC-10 §§10–12](SPEC-10_Test_Certification.md), [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md), [Décision L6/I1](L6_I1_Acceptance_and_Closure_Decision.md).

---

## 1. Contexte, Mandat et Revue Critique

### 1.1 Contexte du Programme Pocoma V3
À l'issue de la clôture formelle de l'incrément L6/I1 (`L6/I1 — CLOSED — LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS`, SHA `27cf9ca3`), le système Pocoma V3 dispose d'une infrastructure complète et instrumentée. Le cadrage initial de **L6/I2** a établi la faisabilité de la reconstruction intégrale READ.

La revue architecturale humaine a émis le verdict :
`DESIGN_ACCEPTED_SUBJECT_TO_CORRECTIONS`.

### 1.2 Mandat de Consolidation
Le présent document intègre les corrections exhaustives demandées sur :
- **Correction A :** Barrière de maintenance distribuée, autorité persistante et fail-closed multi-instances.
- **Correction B :** Exclusion réelle des workers, clôture des sessions et garanties anti-commit tardif.
- **Correction C :** Protocole exact de reconstruction DDL, Flyway et gestion des pools HikariCP.
- **Correction D :** Formalisation mathématique et SQL de l'oracle de convergence `READY`.
- **Correction E :** Machine à états (FSM) et reprise après crash à toutes les phases.
- **Correction F :** Définition ultra-ciblée des gates de qualification et budgets stricts.
- **Vérifications complémentaires :** Dérivation dynamique du catalogue et analyse du débit de rattrapage face au flux WRITE continu.

---

## 2. Architecture Finale et Principes Directeurs

### 2.1 Principes Architecturaux
1. **Zéro second moteur :** La reconstruction compose exclusivement les briques existantes (`DiscoveryEngine`, `ConsumptionEngine`, `V3ReadParticipants`).
2. **Étanchéité WRITE / READ :** Accès en lecture seule (`SELECT`) exclusif sur la base `pocoma_write` (`v3_write`). Aucune transaction distribuée.
3. **Reconstruction en place coordonnée :** Rejet motivé de l'architecture Blue/Green pour I2 (complexité opérationnelle et défaillances de plans préparés HikariCP non justifiées face à une indisponibilité acceptée par SPEC-10).
4. **Fail-closed absolu :** Tout doute, panne ou indisponibilité de l'autorité de maintenance se traduit immédiatement par `503 Service Unavailable`.
5. **Préfixe figé B :** Le rattrapage cible un point de synchronisation temporel fixe $B$, rendant le volume de travail de maintenance fini et indépendant du débit WRITE concurrent.

```
                           +---------------------------------------------+
                           |           FLUX WRITE CONTINU                |
                           |   (Admissions et commandes en continu)      |
                           +---------------------------------------------+
                                              |
                                              | (Événements immuables denses)
                                              v
        +---------------------------------------------------------------------------------+
        |                        POCOMA_WRITE (v3_write)                                   |
        |   v3_event_counter / v3_event_position : Séquences [POT, INVIT, ID, CMD, ID_COMP]  |
        +---------------------------------------------------------------------------------+
                                              |
                               (1) Freeze Préfixe B | (SELECT read-only par Discovery)
                                              v
+-------------------------------------------------------------------------------------------------+
|                                     ORCHESTRATEUR CLI L6/I2                                     |
|                              (scripts/operations/v3-rebuild-read.py)                            |
+-------------------------------------------------------------------------------------------------+
          |                                                                   |
          | (2) Bascule MAINTENANCE (read_maintenance_state = 'MAINTENANCE')  | (7) Surveillance
          | (3) Drainage requêtes HTTP (T_drain = 3s)                         |     progression vers B
          | (4) Révocation DML & neutralisation workers                       |     et évaluation
          | (5) Reset DDL v3_read & Flyway migrations                         |     de P_READY(B)
          | (6) Lancement 7 Consumptions READ                                 |
          v                                                                   v
+-------------------------------------------------------------------------------------------------+
|                                 POCOMA_READ (v3_read)                                           |
|   +-----------------------------------------------------------------------------------------+   |
|   | AUTORITÉ DE MAINTENANCE : read_maintenance_state (READY | MAINTENANCE | REBUILDING)     |   |
|   +-----------------------------------------------------------------------------------------+   |
|   | 7 Consumptions READ (V3ReadParticipants) :                                              |   |
|   | - MaterializePotVersion (POT_EVENT)         - MaterializePotBalance (POT_EVENT)         |   |
|   | - MaterializePotVisibility (POT_EVENT)      - MaterializeInvitation (INVITATION_EVENT)  |   |
|   | - MaterializeCurrentBinding (IDENTITY_EVENT)- MaterializeCommandResult (CMD_COMPLETION) |   |
|   | - MaterializeIdentityRequestResult (IDENTITY_COMPLETION)                                |   |
|   +-----------------------------------------------------------------------------------------+   |
|   | 28 Partitions durables (7 consumptions x 4 partitions) dans partition_ownership         |   |
+-------------------------------------------------------------------------------------------------+
                                              |
                               (8) Bascule READY (read_maintenance_state = 'READY')
                                              v
                           +---------------------------------------------+
                           |          FAÇADE HTTP READ (v3-http-read)    |
                           |     Fail-closed 503 pendant maintenance     |
                           |             200 OK après READY              |
                           +---------------------------------------------+
```

---

## 3. Matrice des Composants et Mécanismes Réutilisés

| Composant V3 Existant | Module Source | Rôle dans la Reconstruction | Niveau de Réutilisation | Dépendance à l'État READ |
|---|---|---|:---:|---|
| **`DiscoveryEngine`** | `engine-consumption` | Découverte des événements $(K, H]$ par fenêtre, insertion des slots et avancée atomique de checkpoint. | **100% tel quel** | Aucune (crée l'état initial des slots). |
| **`ConsumptionEngine`** | `engine-consumption` | Acquisition des claims, exécution de la tentative, commit des projections et suppression du slot. | **100% tel quel** | Aucune (gère la création/mise à jour). |
| **`V3ReadParticipants`** | `composition-runtime-spring` | Catalogue inerte fournissant les instances des 7 participants READ et leurs stores JDBC associés. | **100% tel quel** | Aucune (sources en lecture seule sur WRITE). |
| **`v3_event_counter` / `v3_event_position`** | `infra-persistence-primary-jpa` | Fournit les séquences denses immuables et le point de capture du préfixe $B$. | **100% tel quel** | Aucune (tables WRITE). |
| **Migrations Flyway READ** | `infra-persistence-read-jdbc` | V1..V7, V10 créant les structures SQL, index et triggers d'immutabilité. | **100% tel quel** | Recrée le schéma à blanc. |
| **`bootstrap-l2-routing.sql`** | `scripts/v3/` | Initialisation de `consumption_routing_contract` et des 28 partitions. | **100% tel quel** | Exige des tables de consommation vides. |
| **`grant-l2-lifecycle.sql` / `discovery.sql`**| `scripts/v3/` | Attribution des privilèges DML minimaux au rôle applicatif. | **100% tel quel** | Restaure les privilèges après reset. |
| **`MicrometerV3ConsumptionObservation`** | `composition-runtime-spring` | Collecte des métriques d'acquisition, de polling et de matérialisation. | **100% tel quel** | Aucune. |

---

## 4. Correction A — Barrière de Maintenance Distribuée

### 4.1 Autorité Persistante et Localisation
L'état de maintenance est gouverné par une autorité unique, transactionnelle et persistée dans la base `pocoma_read` :
```sql
CREATE TABLE v3_read.read_maintenance_state (
    state TEXT PRIMARY KEY CHECK (state IN ('READY', 'MAINTENANCE', 'REBUILDING', 'FAILED')),
    target_prefix JSONB NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    operator TEXT NOT NULL
);
```
- **Propriétaire :** Rôle administrateur `pocoma`.
- **Permissions :** `SELECT` accordé au rôle applicatif `pocoma_read`. `INSERT/UPDATE` strictement réservé à l'administrateur `pocoma`.

### 4.2 Transitions Atomiques Autorisées
```
                +--------------+
                |    READY     | <------------------------------------+
                +--------------+                                      |
                       |                                              |
            (Entrée en maintenance)                                   |
                       v                                              |
                +--------------+                                      |
                | MAINTENANCE  |                                      |
                +--------------+                                      |
                       |                                              |
             (DDL Reset & Capture B)                           (Oracle READY)
                       v                                              |
                +--------------+                                      |
                |  REBUILDING  | -------------------------------------+
                +--------------+
                       |
             (Incident bloquant / Timeout)
                       v
                +--------------+
                |    FAILED    |
                +--------------+
```

### 4.3 Comportement Multi-Instances et Fail-Closed
1. **Contrôle en Façade HTTP :**
   Dans `V3ReadFacadeComposition`, chaque consultation client interroge l'état avant d'allouer un permis de quota SQL :
   ```java
   public boolean isReadReady() {
       try {
           String state = sql.queryForObject(
               "SELECT state FROM v3_read.read_maintenance_state", String.class);
           return "READY".equals(state);
       } catch (Exception ex) {
           // FAIL-CLOSED ABSOLU : Si la table n'existe pas (drop schema),
           // si la base est inaccessible ou si l'état est MAINTENANCE/REBUILDING/FAILED
           return false;
       }
   }
   ```
2. **Garantie Fail-Closed :**
   Si `isReadReady()` renvoie `false`, la requête lève immédiatement une `V3ReadTransport.Failure(503)`. Aucun permis du sémaphore $Q=2$ n'est consommé, aucune table de projection n'est interrogée.
3. **Absence de faux 404 :**
   Pendant la reconstruction, un utilisateur appelant `GET /api/v3/pots/{potId}` ou `/me/binding` reçoit un `503 Service Unavailable` explicite et non un `404 Not Found` (satisfaisant formellement **RCV01-C** et **RCV01-M**).
4. **Garantie de Drainage des Requêtes en Vol :**
   - Les Attempt Transactions de Spring possèdent une limite stricte `resource.setTimeout(2)` (2 secondes).
   - Les connexions PostgreSQL sont configurées avec `statement_timeout = 2000` (2 secondes).
   - *Protocole de drainage :* Après l'écriture de `state = 'MAINTENANCE'`, l'orchestrateur attend une période de grâce incompressible de **$T_{drain} = 3$ secondes** avant toute action destructive. À $T + 3\text{s}$, aucune transaction READ antérieure ne peut subsister.

---

## 5. Correction B — Exclusion Réelle des Workers et Fencing

### 5.1 Analyse des Risques de Concurrence des Workers
L'inspection fine de `ConsumptionEngine` a démontré qu'un simple contrôle de `partition_ownership` est insuffisant :
- `executeAttemptOperation` ne re-vérifie pas le bail de partition lors de `finalizeAttemptOperation`. Il s'appuie sur `slots.protectFinal(claim)` qui valide le `claim_token` et `claimed_until` dans `consumption_slot`.
- Un worker dont le bail de partition a expiré mais dont le claim de slot est valide (durée de claim pouvant atteindre 180s ou 240s) pourrait exécuter `finalizeIn` et commettre une écriture tardive.
- Un worker arrêté puis relancé par un superviseur automatique (Docker/Kubernetes) pourrait acquérir un nouveau bail pendant la phase de reconstruction.

### 5.2 Protocole d'Exclusion en Deux Barrières

Pour apporter une **preuve de conception mathématique** qu'aucun ancien worker ne peut modifier READ avant, pendant ou après le reset, le protocole suivant est strictement appliqué :

```
Étape 1 : Bascule administrative MAINTENANCE (read_maintenance_state = 'MAINTENANCE')
   |
Étape 2 : Arrêt physique ordonné du conteneur/service worker READ
   |
Étape 3 : Révocation DML immédiate sur la base pocoma_read :
          REVOKE INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA v3_read FROM pocoma_read;
          REVOKE USAGE ON SCHEMA v3_read FROM pocoma_read;
   |      --> Toute tentative d'un worker résiduel d'exécuter finalizeIn, acquire ou
   |          renew échoue immédiatement avec SQLSTATE 42501 (permission denied).
   |
Étape 4 : Attente du drainage naturel (3 secondes).
   |
Étape 5 : Contrôle d'absence de sessions actives dans PostgreSQL :
          SELECT pid, state, query FROM pg_stat_activity
          WHERE datname = 'pocoma_read' AND usename = 'pocoma_read'
            AND state IN ('active', 'idle in transaction');
   |      --> Si des sessions persistent au-delà de 5s :
   |          Appel ciblé de pg_terminate_backend(pid) avec journalisation d'alerte.
   |
Étape 6 : Exécution du reset DDL sous rôle propriétaire pocoma.
```

### 5.3 Statut de `pg_terminate_backend`
`pg_terminate_backend` n'est **pas** le mode nominal d'arrêt. Il constitue une mesure de sauvegarde ultime (failsafe) déclenchée uniquement si une connexion refuse d'obtempérer après la révocation des permissions et le délai de grâce.

---

## 6. Correction C — Protocole Exact de Reconstruction DDL et Flyway

### 6.1 Pourquoi la Recréation de Schéma est Indispensable
L'audit a révélé que les tables `read_subobject_version`, `read_pot_version`, `read_pot_balance`, `read_command_result`, `read_identity_request_result` et `read_current_invitation` sont verrouillées par des triggers `ENABLE ALWAYS` (`read_subobject_immutable`, `read_invitation_monotone`) levant une exception `23514` sur `UPDATE`, `DELETE` ou `TRUNCATE`.
Désactiver ces triggers sur des tables peuplées créerait un risque majeur de sécurité si un trigger venait à ne pas être réactivé. La suppression et recréation intégrale du schéma garantit un retour à l'état vierge certifié.

### 6.2 Procédure Séquentielle DDL
1. **Contrôle d'Identité Strict :**
   Le script vérifie formellement la cible via :
   ```sql
   SELECT current_database(), current_schema(), current_user;
   ```
   Refus absolu d'exécution si `current_database() != 'pocoma_read'` ou si une table WRITE (`v3_command`, `v3_pot_event`) est détectée.
2. **Destruction du Schéma :**
   ```sql
   DROP SCHEMA IF EXISTS v3_read CASCADE;
   CREATE SCHEMA v3_read AUTHORIZATION pocoma;
   ```
   *Ce qui est détruit :* Toutes les tables de projections, les tables de slots, de baux et de checkpoints de `v3_read`, ainsi que la table `flyway_schema_history` locale à `v3_read`.
   *Ce qui n'est JAMAIS détruit :* La base `pocoma_write` (100% étanche sur une base distincte), les rôles PostgreSQL, l'extension `pgcrypto`.
3. **Application des Migrations Flyway Canoniques (Ordre Strict) :**
   - `V1__consumption_foundation.sql` (Crée `consumption_routing_contract`, `partition_ownership`, `discovery_checkpoint`, `consumption_slot`)
   - `V2__subobject_version.sql` (Crée `read_subobject_version`)
   - `V3__pot_version.sql` (Crée `read_pot_version`, `read_current_pot`)
   - `V4__pot_balance.sql` (Crée `read_pot_balance`)
   - `V5__pot_visibility.sql` (Crée `read_pot_visibility_state`, `read_user_pot`)
   - `V6__current_binding.sql` (Crée `read_current_binding`)
   - `V7__terminal_results.sql` (Crée `read_command_result`, `read_identity_request_result`)
   - `V10__current_invitation.sql` (Crée `read_current_invitation`)
4. **Bootstrap du Routing et Permissions :**
   - Application de `scripts/v3/bootstrap-l2-routing.sql` avec `authority = 'READ'` (insère les 7 définitions et 28 partitions).
   - Application de `scripts/v3/grant-l2-lifecycle.sql` et `grant-l2-discovery.sql`.
   - Initialisation de `v3_read.read_maintenance_state` à `'REBUILDING'`.
5. **Gestion des Pools HikariCP :**
   L'orchestrateur déclenche l'éviction douce (`softEvictConnections()`) des pools `readBusiness` et `readControl` des runtimes actifs afin de purger les descripteurs d'anciens OIDs PostgreSQL.

---

## 7. Correction D — Oracle de Convergence et Condition READY

### 7.1 Capture Atomique du Vecteur de Préfixes Sûrs $B$
Avant d'entamer le rattrapage, l'orchestrateur lit atomiquement le catalogue de positions WRITE dans une transaction READ COMMITTED sur `pocoma_write` :
```sql
SELECT family, position FROM v3_write.v3_event_counter;
```
Le vecteur capturé est figé :
$$B = \Big( B_{\text{POT}}, B_{\text{INVITATION}}, B_{\text{IDENTITY}}, B_{\text{COMMAND\_COMPLETION}}, B_{\text{IDENTITY\_COMPLETION}} \Big)$$
Ce vecteur est immédiatement consigné dans `v3_read.read_maintenance_state(target_prefix)`.

### 7.2 Formalisation Mathématique du Prédicat $\mathcal{P}_{\text{READY}}(B)$
L'espace READ est déclaré **READY** si et seulement si les quatre conditions suivantes sont simultanément vraies :

$$\mathcal{P}_{\text{READY}}(B) \iff \mathcal{C}_{\text{checkpoints}}(B) \;\land\; \mathcal{C}_{\text{slots}}(B) \;\land\; \mathcal{C}_{\text{quarantine}}(B) \;\land\; \mathcal{C}_{\text{integrity}}$$

#### Condition 1 : Découverte Complète de Toutes les Partitions ($\mathcal{C}_{\text{checkpoints}}$)
Pour chaque Consumption $c \in \{1..7\}$ et pour chaque partition $p \in \{0..3\}$ :
$$\text{checkpoint}(c, p) \ge B_{F(c)}$$
*Preuve SQL :*
```sql
SELECT COUNT(*) FROM v3_read.discovery_checkpoint dc
JOIN v3_read.consumption_routing_contract rc USING (consumption_id)
JOIN v3_write.v3_event_counter c ON c.family =
    CASE rc.family
        WHEN 'POT_EVENT' THEN 'POT'
        WHEN 'INVITATION_EVENT' THEN 'INVITATION'
        WHEN 'IDENTITY_EVENT' THEN 'IDENTITY'
        WHEN 'COMMAND_COMPLETION_EVENT' THEN 'COMMAND_COMPLETION'
        WHEN 'IDENTITY_COMPLETION_EVENT' THEN 'IDENTITY_COMPLETION'
    END
WHERE dc.position < (target_prefix->>c.family)::bigint;
-- Doit être STRICTEMENT égal à 0.
```

#### Condition 2 : Résorption Totale des Slots Enrôlés $\le B$ ($\mathcal{C}_{\text{slots}}$)
Aucun slot associé à un événement de position $\le B$ ne doit subsister dans `consumption_slot` :
```sql
SELECT COUNT(*) FROM v3_read.consumption_slot cs
JOIN v3_read.consumption_routing_contract rc ON rc.consumption_id = cs.consumption_id
JOIN v3_write.v3_event_position ep ON ep.event_id = cs.consumable_id
WHERE ep.position <= (target_prefix->>ep.family)::bigint;
-- Doit être STRICTEMENT égal à 0.
```

#### Condition 3 : Absence de Quarantaine $\le B$ ($\mathcal{C}_{\text{quarantine}}$)
Aucun slot relatif au périmètre $\le B$ ne doit être à l'état `QUARANTINED` :
```sql
SELECT COUNT(*) FROM v3_read.consumption_slot cs
JOIN v3_write.v3_event_position ep ON ep.event_id = cs.consumable_id
WHERE ep.position <= (target_prefix->>ep.family)::bigint AND cs.state = 'QUARANTINED';
-- Doit être STRICTEMENT égal à 0.
```

#### Condition 4 : Concordance des Oracles Métier ($\mathcal{C}_{\text{integrity}}$)
- $\forall P$, `read_current_pot(P).pot_version` pointe vers une entrée valide de `read_pot_version`.
- $\forall P, \forall V \le \text{CurrentPot}(P)$, si $V$ est actif (non supprimé), `read_pot_balance(P, V)` est présent et exact.
- $\forall \text{EUI}$ active dans WRITE, son `CurrentBinding` est présent et utilisable.
- Toutes les références de sous-objets citées dans `read_pot_version` existent dans `read_subobject_version`.

---

## 8. Correction E — Crash Recovery et Relance du Rebuild

### 8.1 Matrice de Comportement sur Interruption
| Phase de l'Interruption | État Persistant Détecté | Comportement de Reprise Automatique | Action Manuelle Requise |
|---|---|---|:---:|
| **Avant activation de MAINTENANCE** | `READY` | Aucune corruption. Le script ré-exécute la procédure depuis le début. | Aucune |
| **Pendant drainage des requêtes** | `MAINTENANCE` | READ renvoie 503. Le script reprend le drainage ($T_{drain}$). | Aucune |
| **Pendant l'arrêt des workers** | `MAINTENANCE` | Vérification de la libération des baux et des permissions. | Aucune |
| **Pendant le reset DDL / Flyway** | Incomplet / Absence de table | Détection d'un schéma corrompu/incomplet. Drop schema propre et ré-application intégrale Flyway. | Aucune |
| **Pendant le rattrapage (Catch-up)**| `REBUILDING` avec `target_prefix` | **REPRISE SANS RESET :** Le script relit le vecteur $B$ stocké dans `target_prefix`, réactive les workers et reprend la surveillance des checkpoints et slots. | Aucune |
| **Pendant l'évaluation READY** | `REBUILDING` | Le script ré-évalue $\mathcal{P}_{\text{READY}}(B)$. S'il est satisfait, il bascule à `READY`. | Aucune |
| **Pendant la remise en service** | `READY` ou `REBUILDING` | Transaction unitaire SQL : soit validée (READY), soit reprise. | Aucune |

### 8.2 Invariants Absolus de Tolérance aux Pannes
1. **Jamais de mise en service partielle :** Tant que $\mathcal{P}_{\text{READY}}(B)$ n'a pas été formellement validé, `read_maintenance_state` reste à `MAINTENANCE` ou `REBUILDING`, bloquant toute lecture client (503).
2. **Prévention des doubles resets destructifs :** Si le script détecte l'état `REBUILDING` avec un préfixe $B$ valide, il refuse de réinitialiser le schéma par défaut et reprend le rattrapage en cours. Une réinitialisation totale forcée exige le drapeau explicite `--force-reset`.
3. **Fail-Closed Permanent :** Le redémarrage inopiné d'un conteneur ou d'une instance Spring ne peut en aucun cas réinitialiser l'état à `READY` de manière autonome.

---

## 9. Correction F — Gates et Budgets de Qualification

### 9.1 Matrice des Gates d'Implémentation
Conformément aux exigences de [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md), la qualification est rigoureusement bornée à **trois gates ciblées** sans full reactor :

```
========================================================================================
GATE A — Contrats et Mécanismes Ciblés (Unitaires / Spring Bounded)
Commande : ./mvnw -pl composition-runtime-spring \
           -Dtest=RebuildReadinessStateTest,RebuildPrefixCalculatorTest,RebuildMetricsTest test
Objectif : Prouver la table d'autorité, le fail-closed, le calcul de préfixe B et
           l'instrumentation métrique Rebuild.
Budget Cible : 25 s | Budget Plafond Strict : 60 s
========================================================================================
                                           | (PASS requis)
                                           v
========================================================================================
GATE B — Reconstruction Fonctionnelle PostgreSQL (RCV01-A..N)
Commande : ./mvnw -pl runtime-task-consumption-worker \
           -Dtest=V3ReadReconstructionPostgresTest test
Objectif : Valider les 11 scénarios déterministes RCV01-A à RCV01-N sur PostgreSQL
           réel (Testcontainers), incluant writer tardif, crash et reprise FSM.
Budget Cible : 110 s | Budget Plafond Strict : 240 s
========================================================================================
                                           | (PASS requis)
                                           v
========================================================================================
GATE C — Exploitation Locale & Validation Opérationnelle
Commande : python3 scripts/operations/verify-l6-i2-rebuild.py
Objectif : Valider le script CLI v3-rebuild-read.py, les barrières de sécurité
           (--confirm-destructive), la bascule 503 -> 200 et Prometheus.
Budget Cible : 70 s | Budget Plafond Strict : 180 s
========================================================================================
```

### 9.2 Budget Global et Discipline de Qualification
- **Budget Cible Cumulé :** **3 minutes 25 secondes** (205 s).
- **Budget Plafond Strict Cumulé :** **8 minutes** (480 s $\le$ 9 min).
- **Discipline :** Arrêt immédiat au premier échec ; un unique rejeu après diagnostic ; pas de rejeu infini ; aucun full reactor (`./mvnw test`) ni gate globale d'architecture (`architecture-tests`).

---

## 10. Vérification Complémentaire 1 — Catalogue READ et Checkpoints

### 10.1 Dérivation Dynamique du Catalogue
Le nombre total de checkpoints et de partitions n'est pas codé en dur dans le code d'exploitation. Il est dérivé dynamiquement de `consumption_routing_contract` :

| Nom Stable du Participant | Famille Événementielle | Consumable ID | Partitions | Table de Checkpoint | Store d'Effets |
|---|---|---|:---:|---|---|
| `MaterializePotVersion` | `POT_EVENT` | `3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a` | 4 (0..3) | `discovery_checkpoint` | `read_pot_version`, `read_subobject_version`, `read_current_pot` |
| `MaterializePotBalance` | `POT_EVENT` | `190a93c6-b8af-5ac7-8001-72e2ec53c2df` | 4 (0..3) | `discovery_checkpoint` | `read_pot_balance` |
| `MaterializePotVisibility`| `POT_EVENT` | `b4d04cce-b5da-5d1b-9698-5ff9af9a46fa` | 4 (0..3) | `discovery_checkpoint` | `read_pot_visibility_state`, `read_user_pot` |
| `MaterializeInvitation` | `INVITATION_EVENT` | `924cd9e3-435d-5804-9d1e-c6aecadb9ab4` | 4 (0..3) | `discovery_checkpoint` | `read_current_invitation` |
| `MaterializeCurrentBinding`| `IDENTITY_EVENT` | `7b8d2838-bf7a-5117-868a-e5c14b4c0221` | 4 (0..3) | `discovery_checkpoint` | `read_current_binding` |
| `MaterializeCommandResult`| `COMMAND_COMPLETION_EVENT` | `63664303-d33e-5d42-8cce-c4dae8023311` | 4 (0..3) | `discovery_checkpoint` | `read_command_result` |
| `MaterializeIdentityRequestResult`| `IDENTITY_COMPLETION_EVENT` | `1d8ff246-358f-5ddd-a7f5-416efcfd7445` | 4 (0..3) | `discovery_checkpoint` | `read_identity_request_result` |
| **TOTAL** | **5 familles** | **7 participants** | **28 flux** | **28 checkpoints** | **10 tables de projections** |

Le script d'exploitation lit `consumption_routing_contract` pour instancier la surveillance de chaque partition sans dépendre d'une constante statique.

---

## 11. Vérification Complémentaire 2 — Performance, Débit et Rattrapage

### 11.1 Convergence Fonctionnelle vs Dimensionnement Opérationnel
- **Convergence Fonctionnelle :**
  Puisque le préfixe $B$ est figé à $T_{freeze}$, le volume d'événements à rattraper pour atteindre READY est **strictement fini et invariant** :
  $$N_{\text{total}} = \sum_{f} B_f$$
  Par conséquent, même si le débit d'arrivée WRITE ($R_{\text{write}}$) est supérieur au débit de consommation ($R_{\text{consume}}$), l'oracle READY converge mathématiquement vers 0 événements restants.
- **Convergence Opérationnelle :**
  Le temps de maintenance est directement borné par :
  $$T_{\text{rebuild}} \approx \frac{N_{\text{total}}}{R_{\text{consume}}}$$
  Pour un historique de 10 000 événements et un débit mesuré de 200 événements/seconde, l'indisponibilité durera environ 50 secondes.
- **Maîtrise du Risque de Débit Insuffisant :**
  Si le débit de consommation s'avère insuffisant lors de campagnes massives, la calibration des paramètres `batchSize`, du parallélisme des partitions et du dimensionnement matériel relève de **L6/I3** (SPEC-09).

### 11.2 Métrologie Finale Retenue (Prometheus)
- `pocoma_v3_rebuild_status{state="idle|maintenance|rebuilding|ready|failed"}` (Jauge d'état)
- `pocoma_v3_rebuild_remaining_events{family="..."}` (Événements restants jusqu'à $B_f$)
- `pocoma_v3_rebuild_remaining_slots{family="..."}` (Slots ouverts $\le B_f$)
- `pocoma_v3_rebuild_throughput_events_total{family="..."}` (Compteur d'événements traités)
- `pocoma_v3_rebuild_stalled{family="..."}` (Jauge d'alerte si aucun progrès > 30s)

---

## 12. Justification Nuancée du Rejet de l'Architecture Blue/Green

L'alternative d'une reconstruction en arrière-plan sur un schéma séparé (`v3_read_stage`) suivie d'une bascule atomique par renommage DDL (`ALTER SCHEMA ... RENAME`) a été réévaluée.

**Constat technique :** Cette approche est techniquement réalisable sous PostgreSQL et ne viole pas de contrainte physique fondamentale.

**Motif de rejet pour L6/I2 :**
1. **Complexité d'exploitation injustifiée :** Elle imposerait une double gestion des migrations Flyway, une duplication temporaire de l'espace disque et la gestion d'un routeur de schémas dans les pools HikariCP.
2. **Invalidation des caches JDBC :** Le renommage de tables sous-jacentes invalide les plans préparés dans le driver PostgreSQL JDBC (`cached plan must not change result type`), imposant une purge de pool équivalente à un redémarrage.
3. **Acceptation explicite de SPEC-10 :** SPEC-10 §10 accepte formellement une **indisponibilité totale temporaire** de READ lors des opérations exceptionnelles de maintenance.
4. Par conséquent, en application du principe de simplicité (Section 2), l'architecture in-place avec barrière exclusive est la solution optimale.

---

## 13. Inventaire Précis des Fichiers et Découpage de l'Implémentation

### 13.1 Fichiers Modifiés ou Créés lors de l'Implémentation
| Fichier | Type | Nature de la Modification |
|---|---|---|
| `app/composition-runtime-spring/.../v3/V3ReadFacadeComposition.java` | Java Prod | Intégration de la vérification `isReadReady()` renvoyant 503 si non-READY. |
| `app/composition-runtime-spring/.../v3/RebuildReadinessState.java` | Java Prod | Composant d'accès SQL à la table `v3_read.read_maintenance_state`. |
| `app/composition-runtime-spring/.../v3/RebuildMetrics.java` | Java Prod | Instrumentation Micrometer des jauges et compteurs de reconstruction. |
| `scripts/operations/v3-rebuild-read.py` | Python Script | CLI unifié d'orchestration (vérification, barrière, reset, rattrapage, oracle). |
| `scripts/operations/verify-l6-i2-rebuild.py` | Python Script | Script de test opérationnel Gate C. |
| `docker/grafana/dashboards/pocoma-v3-observability.json` | JSON | Ajout de 4 panneaux dédiés à la reconstruction. |
| `app/composition-runtime-spring/.../RebuildReadinessStateTest.java` | Java Test | Tests unitaires Gate A (fail-closed, transitions d'état). |
| `app/runtime-task-consumption-worker/.../V3ReadReconstructionPostgresTest.java` | Java Test | Suite de tests PostgreSQL Gate B (RCV01-A à RCV01-N). |

### 13.2 Découpage en Passe Unique
L'implémentation sera menée en **une seule passe groupée** :
1. Étape 1 : Autorité persistante, barrière 503 et métrologie (Java).
2. Étape 2 : Script CLI d'orchestration et réinitialisation DDL (Python / SQL).
3. Étape 3 : Implémentation des tests PostgreSQL déterministes RCV01-A..N (Tests).
4. Étape 4 : Qualification locale Gate C et validation Grafana/Prometheus.

---

## 14. Matrice des Constats et Décisions de Revue

| ID | Constat de Revue | Risque | Décision Technique Consolidée | Statut |
|---|---|---|---|:---:|
| **CST-I2-01** | Barrière distribuée insuffisamment spécifiée | Incohérence multi-instances, faux 404 | Table `read_maintenance_state`, fail-closed, $T_{drain} = 3\text{s}$ | **RESOLVED (MAJOR)** |
| **CST-I2-02** | Exclusion workers incomplète | Commit tardif après reset | Révocation DML, vérification `pg_stat_activity`, failsafe `terminate` | **RESOLVED (BLOCKING)** |
| **CST-I2-03** | Triggers d'immutabilité bloquant le reset | Échec de purge SQL | Recréation complète du schéma via Flyway sous rôle `pocoma` | **RESOLVED (MAJOR)** |
| **CST-I2-04** | Oracle READY incomplet | Déclaration prématurée de fin | Prédicat formel $\mathcal{P}_{\text{READY}}(B)$ sur 4 conditions strictes | **RESOLVED (BLOCKING)** |
| **CST-I2-05** | Comportement crash recovery flou | Rebuild bloqué ou double reset | FSM à 4 états persistants avec conservation de $B$ pour reprise directe | **RESOLVED (MAJOR)** |
| **CST-I2-06** | Commandes Maven trop larges | Dépassement des budgets | Commandes `-Dtest` ciblées sur 3 gates étanches ($\le 8$ min au total) | **RESOLVED (MAJOR)** |
| **CST-I2-07** | Nombre 28 codé en dur | Fragilité du catalogue | Dérivation dynamique depuis `consumption_routing_contract` | **RESOLVED (MINOR)** |
| **CST-I2-08** | Risque de saturation WRITE/READ | Maintenance interminable | Prise en compte de $B$ fixe (volume fini) et métrologie de débit | **RESOLVED (MINOR)** |
| **CST-I2-09** | Justification Blue/Green abrupte | Fragilité argumentative | Nuancement argumentaire : rejet pour complexité et non impossibilité | **RESOLVED (MINOR)** |

---

## 15. Critères d'Acceptation de l'Implémentation

L'implémentation sera formellement acceptée lors de la présentation de :
1. **Gate A PASS :** Tests unitaires validant le fail-closed et les calculs de préfixe en $\le 60\text{s}$.
2. **Gate B PASS :** Scénarios RCV01-A à RCV01-N validés sans erreur sur PostgreSQL en $\le 240\text{s}$.
3. **Gate C PASS :** Démonstration CLI locale avec transition 503 $\rightarrow$ 200 et métriques Prometheus en $\le 180\text{s}$.
4. **Zéro altération de WRITE :** Preuve d'absence totale d'écriture sur `pocoma_write`.
5. **Budget total tenu :** Durée totale d'exécution inférieure à 8 minutes.

---

## 16. Verdict de Consolidation

```
========================================================================================
POCOMA V3 — LOT L6 / INCRÉMENT I2 : VERDICT DE CONSOLIDATION DU PLAN
Statut officiel : L6_I2_DESIGN_READY_FOR_IMPLEMENTATION_APPROVAL

Dépôt : kartaguez/pocoma
Branche : feat/v3-architecture
SHA de référence : 6a1c04fa3f5b419b800754893e56790702a13a5b
Autorisation initiale : GO_L6_I2_SCOPING
Statut revue : DESIGN_ACCEPTED_SUBJECT_TO_CORRECTIONS -> CONSOLIDATED

Tous les constats bloquants (CST-02, CST-04) et majeurs (CST-01, 03, 05, 06) sont RÉSOLUS.
L'ensemble des spécifications techniques est désormais directement exploitable.
Statut d'exécution : IMPLEMENTATION NOT AUTHORIZED (En attente du GO formel)
========================================================================================
```
