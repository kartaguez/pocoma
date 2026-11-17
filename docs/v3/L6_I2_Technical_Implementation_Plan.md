# Pocoma V3 — L6/I2 — Plan Technique d'Implémentation Consolidé
## Exploitation & Reconstruction READ — Protocole Final Consolidé

> [!NOTE] Clôture formelle
> Ce plan a été intégralement implémenté (`3fed1b5d`), corrigé post-audit (`71597c14`), fiabilisé lors des correctifs finaux de sûreté (`fb543e92`), et formellement clos sous mandat `GO_L6_I2_FORMAL_CLOSURE`. Voir l'[Acte d'acceptation et de clôture formelle L6/I2](L6_I2_Acceptance_and_Closure_Decision.md) (**L6/I2 — CLOSED — LOCAL TARGETED PASS WITH EXPLICIT RESERVATIONS**).

**Date :** 6 novembre 2026
**Dépôt :** `kartaguez/pocoma`
**Branche :** `feat/v3-architecture`
**SHA de référence initial :** `07f71693b2a05560d7820da514d90586850a704e`
**Autorisation humaine :** Mandat de correction finale des blocages du protocole de reconstruction READ
**Mode de mission :** `TARGETED DESIGN CORRECTION — DOCUMENTATION ONLY — NO IMPLEMENTATION — NO TEST EXECUTION`
**Statut L6/I1 :** `CLOSED — LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS`
**Statut L6/I2 :** `CLOSED — LOCAL TARGETED PASS WITH EXPLICIT RESERVATIONS` (cf. [Acte de clôture L6/I2](L6_I2_Acceptance_and_Closure_Decision.md))
**Verdict formel :** `L6_I2_DESIGN_FINAL_READY_FOR_GO` (Conception initiale ratifiée et désormais close)

---

## 1. Principes Directeurs et Périmètre Normatif

Conformément aux exigences de [SPEC-10 §10](SPEC-10_Test_Certification.md), [SPEC-05 §9](SPEC-05_Consumption_Discovery.md), [V3 Canon](V3_Canon_and_Roadmap.md) et aux orientations impératives du cadrage L6/I2 :
1. **Reconstruction en place coordonnée :** Réinitialisation déterministe de l'espace de lecture READ (`pocoma_read`) sans toucher à l'admission ni à la persistance WRITE (`pocoma_write`).
2. **WRITE continu et jamais réinitialisé :** Aucun verrou bloquant, aucune interruption de trafic, aucune perte d'événements sur WRITE pendant la maintenance ou le rattrapage.
3. **Réutilisation à 100% du socle V3 :** Zéro nouveau moteur de replay ou orchestrateur propriétaire. Le rattrapage réutilise les briques existantes : `DiscoveryEngine`, `ConsumptionEngine`, les sept participants READ de `V3ReadParticipants`, leurs baux et leurs tables de checkpoint/slot.
4. **Indisponibilité HTTP 503 explicite (fail-closed) :** Pendant toute la durée des opérations de destruction, migration et rattrapage, les façades HTTP READ répondent inconditionnellement HTTP 503 (`Retry-After: 30`), interdisant tout faux 404.
5. **Autorité de maintenance indestructible :** L'état persistant de maintenance et les métadonnées de reprise sont hébergés dans la table `public.read_maintenance_state` au sein de la base `pocoma_read`. Cet emplacement survit inconditionnellement au `DROP SCHEMA v3_read CASCADE;`.
6. **Double exclusion démontrable des workers :** Neutralisation physique des anciens workers (arrêt de processus/conteneurs, expiration des baux, vérification de 0 sessions actives dans `pg_stat_activity`, révocation SQL de `USAGE ON SCHEMA v3_read`) garantissant qu'aucun worker de l'ancienne génération ne peut committer dans les tables reconstruites.
7. **Prédicat mathématique de remise en service $\mathcal{P}_{	ext{READY}}(B)$ :** Réouverture du service uniquement lorsque les 28 checkpoints (7 consumptions $	imes$ 4 partitions) ont dépassé le préfixe figé $B$, qu'aucun slot $\le B$ ne subsiste dans `consumption_slot`, et que l'intégrité relationnelle interne est vérifiée.
8. **Qualification compacte $\le 8$ minutes sans full reactor :** 3 gates ciblées (Gate A 60s, Gate B 240s, Gate C 180s) totalisant $\le 480$ s ($\le 540$ s contractuel).

---

## 2. Autorité Indestructible de Maintenance (`public.read_maintenance_state`)

### 2.1 Emplacement et Justification Structurelle
Le défaut structurel majeur (Blocage B1) où `DROP SCHEMA v3_read CASCADE;` détruisait la table d'autorité `read_maintenance_state` est résolu par relocalisation dans le schéma `public` de la base de données `pocoma_read` :
- **Base de données :** `pocoma_read` (distincte physiquement de `pocoma_write`).
- **Schéma PostgreSQL :** `public` (schéma système standard, préservé lors du drop de `v3_read`).
- **Search Path :** Dans `pocoma_read`, le rôle applicatif `pocoma_read` possède le search path `v3_read, public` configuré au bootstrap (`ALTER ROLE pocoma_read IN DATABASE pocoma_read SET search_path = v3_read, public;`). Les requêtes qualifiées `public.read_maintenance_state` ou non qualifiées `read_maintenance_state` résolvent donc de manière transparente même lorsque le schéma `v3_read` est temporairement inexistant.
- **Étanchéité Flyway :** Flyway opère exclusivement avec `default-schema=v3_read` et `schemas=v3_read`. Flyway n'administre pas le schéma `public` et ne touche jamais à `public.read_maintenance_state`.

### 2.2 Définition DDL et Droits d'Accès
```sql
-- Créé lors du bootstrap initial ou par script d'administration idempotent
CREATE TABLE IF NOT EXISTS public.read_maintenance_state (
    id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    state VARCHAR(32) NOT NULL CHECK (state IN ('READY', 'MAINTENANCE', 'REBUILDING', 'FAILED')),
    phase VARCHAR(32) NOT NULL CHECK (phase IN (
        'IDLE', 'DRAINING', 'EXCLUDED', 'SCHEMA_DROPPED',
        'SCHEMA_CREATED', 'MIGRATED', 'ROUTED', 'REPLAYING', 'CONVERGED'
    )),
    target_prefix JSONB NULL,
    operator_id VARCHAR(64) NULL,
    started_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    error_detail TEXT NULL
);

-- Propriétaire exclusif : pocoma (administrateur superuser)
ALTER TABLE public.read_maintenance_state OWNER TO pocoma;

-- Privilèges stricts : lecture seule accordée à l'application
REVOKE ALL ON public.read_maintenance_state FROM PUBLIC;
REVOKE ALL ON public.read_maintenance_state FROM pocoma_read;
GRANT SELECT ON public.read_maintenance_state TO pocoma_read;

-- Initialisation à l'état nominal si la ligne est absente
INSERT INTO public.read_maintenance_state (id, state, phase, updated_at)
VALUES (1, 'READY', 'IDLE', clock_timestamp())
ON CONFLICT (id) DO NOTHING;
```

### 2.3 Propriétés et Garanties
1. **Immunité au reset :** Un `DROP SCHEMA v3_read CASCADE;` ne supprime pas le schéma `public`. L'état, la phase courante, l'identité de l'opérateur et le vecteur cible $B$ restent intacts en cas de crash machine ou de coupure réseau.
2. **Inviolabilité applicative :** `pocoma_read` ne dispose que du droit `SELECT`. Aucun bug applicatif ou injection dans l'API HTTP ne peut altérer l'état de maintenance. Seul l'administrateur `pocoma` (utilisé par le script CLI) peut effectuer des `UPDATE`.
3. **Exclusion mutuelle d'opérateur :** L'acquisition de l'exclusivité s'effectue par une transition atomique conditionnelle :
   ```sql
   UPDATE public.read_maintenance_state
   SET state = 'MAINTENANCE', phase = 'DRAINING', operator_id = :opId,
       started_at = clock_timestamp(), updated_at = clock_timestamp(), error_detail = NULL
   WHERE id = 1 AND state = 'READY';
   ```
   Si aucune ligne n'est mise à jour, un autre opérateur est déjà actif ou le système est en état d'incident (`FAILED`/`REBUILDING`).

---

## 3. Barrière d'Indisponibilité Distribuée et Drainage HTTP

### 3.1 Filtre HTTP Fail-Closed Multi-Instances
Chaque instance HTTP READ intègre un filtre de servlet en tête de chaîne (`ReadMaintenanceFilter`) :
- **Cache mémoire local :** Pour éviter de solliciter PostgreSQL à chaque requête HTTP entrante, l'état est mis en cache pendant au maximum **500 ms**.
- **Comportement Fail-Closed absolu :**
  - Si `state != 'READY'`, le filtre intercepte la requête et renvoie immédiatement HTTP 503 (`Service Unavailable`) avec l'en-tête standard `Retry-After: 30`.
  - Si la base de données `pocoma_read` est injoignable, en timeout ou en erreur réseau, le filtre intercepte l'exception et renvoie inconditionnellement HTTP 503.
  - Même lors du redémarrage d'une instance HTTP pendant le rebuild, l'instance interroge `public.read_maintenance_state` : elle constate immédiatement l'état `MAINTENANCE` ou `REBUILDING` et refuse le trafic sans faux 404.

### 3.2 Protocole de Drainage et Preuve Physique d'Inactivité
Pour lever la réserve R1 (insuffisance d'une simple temporisation fixe), le protocole combine une temporisation de grâce et un contrôle physique dans PostgreSQL :
1. **Bascule en `MAINTENANCE` :** `UPDATE public.read_maintenance_state SET state = 'MAINTENANCE', phase = 'DRAINING' WHERE id = 1;`.
2. **Propagation du filtre :** Toutes les nouvelles requêtes clientes sont rejetées en 503 en moins de 500 ms sur l'ensemble des instances HTTP.
3. **Délai de confort :** Temporisation de grâce $T_{drain} = 3$ secondes. Comme les requêtes de lecture dans `V3ReadController` sont purement synchrones avec un timeout de transaction Spring fixé à 2 secondes (`@Transactional(timeout = 2)`), les requêtes engagées avant la bascule s'achèvent.
4. **Vérification physique dans `pg_stat_activity` :** Le script d'exploitation interroge la vue système de PostgreSQL :
   ```sql
   SELECT count(*)
   FROM pg_stat_activity
   WHERE datname = 'pocoma_read'
     AND pid != pg_backend_pid()
     AND state IN ('active', 'idle in transaction');
   ```
   - Si `count == 0` : la preuve formelle est acquise qu'aucune requête HTTP ni transaction SQL n'est en cours sur `pocoma_read`.
   - Si `count > 0` : le script attend jusqu'à un plafond maximal de 10 secondes. Si des sessions persistent au-delà, l'opération est avortée avec alerte opérateur avant toute destruction.

---

## 4. Exclusion Réelle des Workers et Fencing Multi-Couches

### 4.1 Analyse Critique de `protectFinal(claim)`
L'inspection du code source de production dans [`JdbcConsumptionSlotStore.java`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcConsumptionSlotStore.java#L280-L304) démontre que :
```java
var rows = jdbc.queryForList(
    "select claim_token,claimed_until,state from consumption_slot where consumption_id=? and consumable_id=? for update",
    claim.consumptionId().value(), claim.consumableId());
...
return claim.claimToken().equals(row.get("claim_token"))
    && "AVAILABLE".equals(row.get("state"))
    && row.get("claimed_until") instanceof Timestamp until && until.toInstant().isAfter(now)
    ? Protection.VALID : Protection.STALE_OR_EXPIRED;
```
`protectFinal` valide uniquement la concordance du `claim_token` et la date d'expiration du claim sur la ligne de `consumption_slot`. **Elle n'effectue aucune vérification du bail de partition dans `partition_ownership` au moment du commit.**
Par conséquent, si un ancien worker détenait un claim non expiré, une perte de bail de partition ne suffit pas à bloquer son écriture finale s'il se réveille avant l'échéance de son claim.

### 4.2 Protocole d'Exclusion Démontrable en 5 Niveaux
Pour garantir qu'aucun worker de l'ancienne génération ne puisse corrompre les données reconstruites, le protocole met en œuvre cinq barrières successives étanches :

1. **Barrière 1 — Invalidation de coordination (Maintenance) :**
   Les workers en cours d'exécution vérifient `public.read_maintenance_state`. Dès que `state != 'READY'`, ils interrompent leurs boucles de polling et cessent d'enrôler de nouveaux slots.
2. **Barrière 2 — Arrêt contrôlé des conteneurs/processus :**
   Le script d'exploitation envoie un signal d'arrêt gracieux (`docker compose stop worker-read` ou `systemctl stop pocoma-worker-read` ou appel de `SmartLifecycle#stop()` / `V3ConsumptionWorker#requestDrain()`).
   Pour empêcher tout redémarrage automatique intempestif par l'orchestrateur (Docker restart policy) pendant la maintenance, le service worker est explicitement mis à l'échelle 0 (`docker compose up -d --scale worker-read=0`).
3. **Barrière 3 — Constat physique d'absence de session worker :**
   Le script contrôle dans `pg_stat_activity` qu'aucune connexion résiduelle émanant d'un worker n'est active :
   ```sql
   SELECT count(*)
   FROM pg_stat_activity
   WHERE datname = 'pocoma_read'
     AND usename = 'pocoma_read'
     AND application_name LIKE '%worker%';
   ```
   L'étape suivante n'est franchie que si ce compteur est strictement égal à 0.
4. **Barrière 4 — Révocation des privilèges d'accès au schéma :**
   L'administrateur `pocoma` révoque l'accès au schéma `v3_read` :
   ```sql
   REVOKE USAGE ON SCHEMA v3_read FROM pocoma_read;
   ```
   Même si une connexion fantôme ou un thread zombie se réveillait, toute tentative d'exécuter la moindre commande SQL sur une table de `v3_read` subit immédiatement : `ERROR: permission denied for schema v3_read`.
5. **Barrière 5 — Verrou exclusif et destruction des OIDs (Drop DDL) :**
   Lors de l'exécution de `DROP SCHEMA v3_read CASCADE;` par l'administrateur `pocoma`, PostgreSQL acquiert un verrou `AccessExclusiveLock`. Ce verrou est incompatible avec tous les autres verrous (y compris `RowExclusiveLock` des transactions de slots). Si une transaction était encore ouverte, le DROP bloquerait jusqu'à sa fin. Une fois le drop committé, toutes les tables de l'ancienne génération sont physiquement détruites. Toute transaction ultérieure référençant un ancien OID de table échoue avec `ERROR: relation does not exist`.

---

## 5. Machine à États Persistante et Résilience au Crash (FSM)

### 5.1 Définition des 4 États et des 9 Marqueurs de Phase
Pour lever le Blocage B3 (reprise incomplète en cas de crash lors des étapes DDL ou Flyway), la machine à états associe l'état persistant global (`state`) à un marqueur de progression fine (`phase`) stocké dans `public.read_maintenance_state` :

```
[READY / IDLE]
      │
      │ 1. Acquire & Drain
      ▼
[MAINTENANCE / DRAINING] ──(workers stopped)──> [MAINTENANCE / EXCLUDED]
                                                       │
                                                       │ 2. Drop schema
                                                       ▼
                                            [MAINTENANCE / SCHEMA_DROPPED]
                                                       │
                                                       │ 3. Create schema
                                                       ▼
                                            [MAINTENANCE / SCHEMA_CREATED]
                                                       │
                                                       │ 4. Flyway V1..V10
                                                       ▼
                                            [MAINTENANCE / MIGRATED]
                                                       │
                                                       │ 5. Routing & Grants
                                                       ▼
                                            [MAINTENANCE / ROUTED]
                                                       │
                                                       │ 6. Capture B & Start Replay
                                                       ▼
                                            [REBUILDING / REPLAYING] <──(Crash & --resume)
                                                       │
                                                       │ 7. Validate Oracle
                                                       ▼
                                            [REBUILDING / CONVERGED]
                                                       │
                                                       │ 8. Open Service
                                                       ▼
                                                 [READY / IDLE]
```

### 5.2 Matrice Déterministe des Points d'Interruption et Protocole `--resume`

| Point de Crash (Phase Enregistrée) | État Persistant | Risque Identifié | Action Déterministe lors de `v3-rebuild-read.py --resume` |
|---|---|---|---|
| `DRAINING` | `MAINTENANCE` | Requêtes HTTP ou workers encore en cours d'arrêt. | Maintient 503. Réitère l'arrêt des workers, applique le délai de grâce et vérifie `pg_stat_activity == 0`. Avance à `EXCLUDED`. |
| `EXCLUDED` | `MAINTENANCE` | Workers neutralisés mais DROP non amorcé. | Schéma `v3_read` intact. Révoque USAGE, exécute `DROP SCHEMA v3_read CASCADE;`. Avance à `SCHEMA_DROPPED`. |
| `SCHEMA_DROPPED` | `MAINTENANCE` | Schéma `v3_read` détruit. | Exécute `CREATE SCHEMA v3_read AUTHORIZATION pocoma;`. Avance à `SCHEMA_CREATED`. |
| `SCHEMA_CREATED` | `MAINTENANCE` | Schéma vierge sans tables. | Applique les migrations Flyway V1..V7 et V10. Avance à `MIGRATED`. |
| `MIGRATED` (pendant Flyway) | `MAINTENANCE` | Tables Flyway partiellement créées, état incertain. | Réinitialisation propre et idempotente : `DROP SCHEMA v3_read CASCADE; CREATE SCHEMA ...`, puis rejeu complet de Flyway. Aucun travail de replay n'étant engagé, ce re-drop est sans perte. |
| `ROUTED` | `MAINTENANCE` | Schéma migré, routage et grants OK, mais $B$ non capturé. | Capture le vecteur $B$ depuis `v3_write.v3_event_counter`, persiste $B$ dans `target_prefix`, passe `state = 'REBUILDING'`, `phase = 'REPLAYING'`. |
| `REPLAYING` | `REBUILDING` | Interruption pendant le rattrapage des 5 flux. | **Aucune destruction !** Le schéma est sain et $B$ est préservé. Le script relance le worker de rebuild autorisé et reprend la surveillance de l'oracle READY là où il s'était arrêté. |
| `CONVERGED` | `REBUILDING` | Oracle READY satisfait mais réouverture non committée. | Réévalue immédiatement l'oracle $\mathcal{P}_{	ext{READY}}(B)$. Si confirmé, transitionne vers `READY / IDLE` et émet `softEvictConnections()`. |
| État `FAILED` | `FAILED` | Blocage constaté (timeout de progrès, erreur fatale). | Refus de toute reprise aveugle. L'exploitant doit examiner `error_detail`. La relance exige soit `--resume` (après correction du worker bloqué), soit `--restart` avec `--confirm-destructive=RESET_READ_DATA`. |

---

## 6. Séquence DDL, Migrations Flyway et Restauration des Droits

Toutes les opérations d'administration de schéma sont exécutées sous l'utilisateur administrateur `pocoma` :

### 6.1 Destruction Contrôlée et Recréation Vierge
```sql
-- 1. Enregistrement de la phase
UPDATE public.read_maintenance_state SET phase = 'SCHEMA_DROPPED', updated_at = clock_timestamp() WHERE id = 1;

-- 2. Destruction inconditionnelle du schéma de lecture
DROP SCHEMA IF EXISTS v3_read CASCADE;

-- 3. Recréation du schéma avec pocoma comme propriétaire (propriété administrative)
CREATE SCHEMA v3_read AUTHORIZATION pocoma;

-- 4. Attribution de l'usage initial à pocoma_read
GRANT USAGE ON SCHEMA v3_read TO pocoma_read;

UPDATE public.read_maintenance_state SET phase = 'SCHEMA_CREATED', updated_at = clock_timestamp() WHERE id = 1;
```

### 6.2 Rejeu des Migrations Flyway Canoniques
Les migrations SQL canoniques de `infra-persistence-read-jdbc` sont appliquées dans l'ordre strict avec les variables de substitution requises :
- Variables : `expectedDatabase = pocoma_read`, `expectedSchema = v3_read`, `expectedAdmin = pocoma`, `expectedRuntime = pocoma_read`.
- Migrations :
  1. `V1__consumption_foundation.sql` (Tables `consumption_routing_contract`, `partition_ownership`, `discovery_checkpoint`, `consumption_slot`, triggers et fonctions de hachage).
  2. `V2__subobject_version.sql` (`read_subobject_version`).
  3. `V3__pot_version.sql` (`read_pot_version`, `read_current_pot`).
  4. `V4__pot_balance.sql` (`read_pot_balance`).
  5. `V5__pot_visibility.sql` (`read_pot_visibility_state`, `read_current_user_pot_list`).
  6. `V6__current_binding.sql` (`read_current_binding`).
  7. `V7__terminal_results.sql` (`read_command_result`, `read_identity_request_result`).
  8. `V10__current_invitation.sql` (`read_current_invitation`).

### 6.3 Routage et Réattribution des Privilèges Opérationnels
1. **Routage L2 :** Exécution de `scripts/v3/bootstrap-l2-routing.sql` avec `authority = 'READ'`.
   Ce script insère les 7 routes autoritatives dans `v3_read.consumption_routing_contract` avec leurs identifiants immuables (`consumption_id`).
2. **Attribution des droits de cycle de vie et découverte :**
   Exécution des scripts de droits canoniques :
   - `scripts/v3/grant-l2-lifecycle.sql` (accorde à `pocoma_read` les droits d'insertion, mise à jour et suppression nécessaires sur les tables de slots et de projections).
   - `scripts/v3/grant-l2-discovery.sql` (accorde à `pocoma_read` les droits de mise à jour des checkpoints de découverte).
3. **Éviction douce des pools de connexions :**
   Exécution de `softEvictConnections()` sur le pool HikariCP de chaque instance applicative connectée, afin de renouveler les sessions et vider les caches de requêtes préparées (`cached plan`) invalidés par la recréation des tables.
4. **Transition de phase :**
   `UPDATE public.read_maintenance_state SET phase = 'ROUTED', updated_at = clock_timestamp() WHERE id = 1;`.

---

## 7. Capture du Préfixe Immuable $B$ et Replay Coordonné

### 7.1 Instant Précis et Sémantique de Capture de $B$
Le vecteur $B$ n'est pas capturé au début de la maintenance, mais **exactement à l'étape 13**, juste avant de démarrer le worker de replay :
- **Source autoritative :** Table `v3_write.v3_event_counter` dans la base `pocoma_write` (interrogée via une connexion en lecture seule stricte).
- **Requête atomique :**
  ```sql
  SELECT family, position FROM v3_write.v3_event_counter;
  ```
- **Vecteur de référence $B$ :**
  $$B = \left( B_{	ext{POT}}, B_{	ext{IDENTITY}}, B_{	ext{COMMAND\_COMPLETION}}, B_{	ext{IDENTITY\_COMPLETION}}, B_{	ext{INVITATION}}
ight)$$
- **Persistance :**
  ```sql
  UPDATE public.read_maintenance_state
  SET state = 'REBUILDING', phase = 'REPLAYING', target_prefix = :prefixJson::jsonb, updated_at = clock_timestamp()
  WHERE id = 1;
  ```

### 7.2 Autorisation et Exécution du Replay Worker
- Le script CLI démarre le worker de reconstruction dédié (ex: `docker compose run --rm worker-rebuild` ou tâche CLI Spring dédiée).
- Ce worker acquiert les 4 partitions pour les 7 consommations dans `v3_read.partition_ownership`.
- `DiscoveryEngine` découvre les événements depuis le début des flux immuables WRITE (`position = 0`) et peuple `v3_read.consumption_slot`.
- `ConsumptionEngine` exécute les 7 participants de `V3ReadParticipants`, applique les mutations dans `v3_read` et supprime les slots finalisés.

---

## 8. Oracle de Convergence Mathématique et Prédicat Formel READY

### 8.1 Dérivation Dynamique du Catalogue de Vérification
Le catalogue comporte exactement 7 consommations associées aux 5 familles WRITE sur 4 partitions (0 à 3), soit **28 checkpoints distincts** :

| Consommation READ | Identifiant Immuable (`consumption_id`) | Famille WRITE Source | Nombre de Partitions |
|---|---|---|:---:|
| `POT_VERSION` | `3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a` | `POT` | 4 (0..3) |
| `POT_BALANCE` | `190a93c6-b8af-5ac7-8001-72e2ec53c2df` | `POT` | 4 (0..3) |
| `POT_VISIBILITY` | `b4d04cce-b5da-5d1b-9698-5ff9af9a46fa` | `POT` | 4 (0..3) |
| `CURRENT_BINDING` | `7b8d2838-bf7a-5117-868a-e5c14b4c0221` | `IDENTITY` | 4 (0..3) |
| `COMMAND_RESULT` | `63664303-d33e-5d42-8cce-c4dae8023311` | `COMMAND_COMPLETION` | 4 (0..3) |
| `IDENTITY_REQUEST_RESULT`| `1d8ff246-358f-5ddd-a7f5-416efcfd7445` | `IDENTITY_COMPLETION` | 4 (0..3) |
| `INVITATION` | `924cd9e3-435d-5804-9d1e-c6aecadb9ab4` | `INVITATION` | 4 (0..3) |

### 8.2 Expression Formelle Non Redondante du Prédicat $\mathcal{P}_{	ext{READY}}(B)$
Pour lever la réserve R2 (redondance entre C2 et C3 et sémantique de position), le prédicat est épuré en 3 conditions indépendantes et non redondantes :

$$\mathcal{P}_{	ext{READY}}(B) = \mathcal{C}_{	ext{checkpoints}} \wedge \mathcal{C}_{	ext{slots}} \wedge \mathcal{C}_{	ext{integrity}}$$

#### Condition 1 — Progression Exhaustive des Checkpoints ($\mathcal{C}_{	ext{checkpoints}}$)
Dans `v3_read.discovery_checkpoint`, chaque partition d'une famille parcourt la même séquence d'événements globale. Le checkpoint enregistre la position maximale de la fenêtre d'événements globale examinée par cette partition.
$$orall c \in \mathcal{C}, \quad orall p \in \{0, 1, 2, 3\}, \quad 	ext{checkpoint}(c, p) \ge B_{F(c)}$$
Vérification SQL :
```sql
SELECT count(*) = 28
FROM v3_read.discovery_checkpoint dc
JOIN v3_read.consumption_routing_contract crc ON dc.consumption_id = crc.consumption_id
WHERE dc.position >= (
    SELECT (target_prefix->>crc.family)::bigint
    FROM public.read_maintenance_state WHERE id = 1
);
```

#### Condition 2 — Résorption Complète des Slots Cibles ($\mathcal{C}_{	ext{slots}}$)
Dans `v3_read.consumption_slot`, un slot achevé avec succès est supprimé par `deleteProtected()`. Par conséquent, la table ne contient que les slots en attente (`AVAILABLE`) ou bloqués en erreur (`QUARANTINED`).
Le prédicat exige qu'il ne subsiste **strictement aucun slot** (qu'il soit disponible, réservé ou en quarantaine) dont l'événement source est antérieur ou égal au préfixe cible $B_{F(c)}$ :
$$|\{ s \in 	ext{consumption\_slot} \mid 	ext{pos}(s) \le B_{F(c)} \}| = 0$$
Vérification SQL :
```sql
SELECT count(*) = 0
FROM v3_read.consumption_slot cs
JOIN v3_read.consumption_routing_contract crc ON cs.consumption_id = crc.consumption_id
JOIN v3_write.v3_event_position ep ON cs.consumable_id = ep.event_id AND crc.family = ep.family
WHERE ep.position <= (
    SELECT (target_prefix->>crc.family)::bigint
    FROM public.read_maintenance_state WHERE id = 1
);
```
*Note sur la levée de redondance :* Cette requête compte inconditionnellement toutes les lignes quel que soit leur `state` ('AVAILABLE' ou 'QUARANTINED'). S'il y a un slot en quarantaine $\le B$, le résultat est $> 0$ et bloque immédiatement READY. En cas de non-satisfaction, le script produit une ventilation diagnostique (`AVAILABLE` vs `QUARANTINED`).

#### Condition 3 — Intégrité Relationnelle Interne des Projections ($\mathcal{C}_{	ext{integrity}}$)
Validation déterministe de la cohérence interne entre projections :
1. Pour tout Pot actif, `read_current_pot` pointe vers une version existante dans `read_pot_version`.
2. Pour tout Pot actif, `read_pot_balance` est présente pour la version courante.
3. Pour tout Pot actif, `read_pot_visibility_state` est présente et cohérente.
4. Pour toute identité, `read_current_binding` correspond aux requêtes enregistrées.

---

## 9. Analyse de Convergence et Watchdog de Progrès sous WRITE Continu

### 9.1 Traitement de la Réserve R3 — Préfixe Fini et Convergence Réelle
Il convient de distinguer formellement :
1. **Préfixe cible fini :** Le volume d'événements à traiter pour atteindre l'oracle READY est borné par le vecteur $B$. Contrairement à une cible mobile, le backlog de rattrapage est mathématiquement fini, quelle que soit la cadence d'écriture sur WRITE.
2. **Capacité réelle de convergence :** La terminaison physique du traitement dépend de la disponibilité continue des workers, de l'absence de blocages transactionnels et de l'avancement effectif des positions.
3. **Absorption des événements $> B$ :** Les événements committés sur WRITE après la capture de $B$ continuent d'être découverts et absorbés en tâche de fond. Ils n'empêchent pas la validation de l'oracle READY et la réouverture du service.

### 9.2 Calibrage du Watchdog de Progrès (Résolution du Seuil 30s)
Le seuil arbitraire de 30 secondes était inadapté aux opérations réelles (recalcul de solde $O(N)$ sur de volumineux Pots, durée normale des baux de partition de 240s ou 180s, retries de slots).
Le mécanisme de détection de blocage est donc reconfiguré :
- **Définition du progrès :** Un progrès est constaté si **au moins un checkpoint avance** OU si **au moins un slot $\le B$ est résorbé**.
- **Fenêtre de timeout de progrès :** Fixée à **180 secondes** ($1.5 	imes 	ext{lease duration local}$ ou temps supérieur à toute transaction individuelle légitime).
- **Vérification de vie avant bascule en `FAILED` :** Si aucun progrès n'est constaté pendant 180 s et que le backlog $\le B$ est non vide :
  1. Le script vérifie la présence du worker dans `pg_stat_activity`.
  2. S'il n'y a plus aucune activité worker, l'état bascule en `FAILED` avec consigne dans `error_detail`.
  3. Si le worker est actif et effectue une opération SQL longue, un avertissement opérationnel est consigné et une prolongation de 60 secondes est accordée avant déclaration de panne.

---

## 10. Chronologie Complète de Reconstruction en 19 Étapes

1. **Validation de la cible :** Le script vérifie la connectivité à `pocoma_read` et `pocoma_write` (en lecture seule), et contrôle l'intégrité des rôles.
2. **Confirmation destructive :** Vérification de l'argument `--confirm-destructive=RESET_READ_DATA`.
3. **Acquisition de l'exclusivité opérateur :** Transition atomique `state = 'MAINTENANCE'`, `phase = 'DRAINING'`, assignation de `operator_id` dans `public.read_maintenance_state`.
4. **Maintenance persistante :** L'état est persistant et survit à tout crash.
5. **Propagation et drainage :** Les filtres HTTP basculent en 503 en 500 ms ; attente de grâce $T_{drain} = 3$ s.
6. **Neutralisation des anciens workers :** Arrêt contrôlé des conteneurs/processus workers READ via `SIGTERM` / `requestDrain()` ; mise à l'échelle 0.
7. **Vérification de l'exclusion transactionnelle :** Requête `pg_stat_activity` confirmant 0 sessions `pocoma_read` actives ou en transaction. `phase = 'EXCLUDED'`.
8. **Préparation du reset :** Révocation administrative de `USAGE ON SCHEMA v3_read FROM pocoma_read;`.
9. **Destruction contrôlée :** `DROP SCHEMA v3_read CASCADE;` exécuté par `pocoma`. `phase = 'SCHEMA_DROPPED'`.
10. **Reconstruction Flyway :** `CREATE SCHEMA v3_read AUTHORIZATION pocoma;`, attribution `USAGE` à `pocoma_read`, application des migrations Flyway V1..V7 et V10. `phase = 'MIGRATED'`.
11. **Restauration SQL et grants :** Exécution de `bootstrap-l2-routing.sql`, attribution des droits de cycle de vie et découverte ; éviction `softEvictConnections()` sur HikariCP. `phase = 'ROUTED'`.
12. **Initialisation des consommations :** Contrôle des 7 routes dans `consumption_routing_contract` et des 28 checkpoints à 0.
13. **Capture du préfixe immuable $B$ :** Lecture atomique de `v3_event_counter` sur les 5 familles, écriture de $B$ dans `target_prefix`. Transition `state = 'REBUILDING'`, `phase = 'REPLAYING'`.
14. **Replay avec le worker autorisé :** Démarrage du worker de reconstruction dédié.
15. **Contrôle de progression :** Surveillance continue de l'avancement des 28 checkpoints et de la résorption des slots sous watchdog de 180s.
16. **Évaluation de l'oracle READY :** Constat de $\mathcal{P}_{	ext{READY}}(B)$ (28 checkpoints $\ge B$, 0 slots $\le B$). `phase = 'CONVERGED'`.
17. **Vérification structurelle finale :** Validation des 4 oracles d'intégrité relationnelle sur les projections.
18. **Réouverture HTTP :** Transition atomique `state = 'READY'`, `phase = 'IDLE'`, `operator_id = NULL` dans `public.read_maintenance_state` ; appel `softEvictConnections()` ; les instances HTTP reprennent le service en 200 OK sous 500 ms.
19. **Vérification opérationnelle :** Requête smoke test HTTP 200 OK sur un endpoint de lecture ; les événements postérieurs à $B$ continuent d'être absorbés au fil de l'eau.

---

## 11. Stratégie de Qualification Ciblée, Commandes Exactes et Budgets

### 11.1 Analyse de Compatibilité Maven (Traitement de la Réserve R4)
L'analyse statique de la structure multimodule Maven (`app/pom.xml`) confirme que :
- L'utilisation de `-am` compile les modules amont nécessaires.
- Pour éviter que Surefire ne fasse échouer la commande dans les modules amont où les classes de tests spécifiées sont absentes, l'argument `-DfailIfNoSpecifiedTests=false` est formellement requis par le standard Maven Surefire (conformément à [Reactor_Verification_Policy.md §35](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/Reactor_Verification_Policy.md#L1426)).

### 11.2 Commandes Exactes Post-Implémentation

#### Gate A — Contrats Ciblés & Fail-Closed HTTP (Plafond : 60 s)
Vérifie le filtre de servlet HTTP 503, la bascule atomique fail-closed de l'autorité, la gestion du cache 500 ms et l'évaluation unitaire du prédicat READY.
```bash
./mvnw test -pl composition-runtime-spring -am   -Dtest=ReadMaintenanceBarrierTest,ReadinessOracleTest   -DfailIfNoSpecifiedTests=false
```
- Modules amont compilés : 15 modules.
- Tests exécutés : 10 à 15 tests unitaires et de contrats Spring purs (sans conteneur lourd).
- Durée estimée : **35 s** (plafond contractuel : 60 s).

#### Gate B — Qualification Déterministe PostgreSQL RCV01 (Plafond : 240 s)
Exécute l'intégralité des 11 scénarios normatifs SPEC-10 RCV01-A à RCV01-N sur base PostgreSQL réelle (reset, exclusion, 503, rattrapage 5 flux, convergence, crash recovery R4, WRITE continu R5, oracles de concordance).
```bash
./mvnw test -pl runtime-task-consumption-worker -am   -Dtest=V3ReadReconstructionPostgresTest   -DfailIfNoSpecifiedTests=false
```
- Réutilise les fixtures PostgreSQL existantes (`SubobjectPostgresFixture`, `PostgresFoundationTestSupport`).
- Durée estimée : **140 s** (plafond contractuel : 240 s).

#### Gate C — Validation Script CLI et Résilience Locale (Plafond : 180 s)
Vérifie le script CLI Python `v3-rebuild-read.py` sur la stack Docker locale provisionnée (vérifications prévol, garde-fous `--confirm-destructive`, arrêt workers, reprise après crash `--resume`, bascule 503 $
ightarrow$ 200 OK, métriques Prometheus).
```bash
bash scripts/operations/verify-l6-i2-rebuild.sh
```
- Durée estimée : **110 s** (plafond contractuel : 180 s).

### 11.3 Récapitulatif des Budgets
- **Budget total estimé :** $35 + 140 + 110 = 285$ s ($pprox 4.8$ minutes).
- **Plafond cible global :** $60 + 240 + 180 = 480$ s (8 minutes).
- **Plafond contractuel maximal :** 540 s (9 minutes).
- **Full reactor :** Strictement interdit.
- **Benchmarks de charge :** Attribués à L6/I3.

---

## 12. Risques Résiduels Explicitement Acceptables

1. **Indisponibilité temporaire de lecture :** L'espace READ est inaccessible (HTTP 503) pendant le temps du reset et du rattrapage. Ce comportement est formellement accepté par [SPEC-10 §10](SPEC-10_Test_Certification.md).
2. **Latence de rafraîchissement du filtre (500 ms) :** Un client très rapide pourrait théoriquement initier une requête dans les 500 premières millisecondes suivant l'ordre de maintenance. Cette requête est absorbée de manière sûre par le délai de grâce $T_{drain} = 3$ s avant tout DROP DDL.
3. **Performance de rattrapage sous gros volume :** La vitesse de dépilement dépend du débit d'exécution des projections ($O(N)$ pour Balance). La qualification de ces performances et des SLO associés est explicitement dévolue à l'incrément **L6/I3**.

---

## 13. Verdict Formel Technique

```
================================================================================
POCOMA V3 — LOT L6 / INCRÉMENT I2 : VERDICT DE CONCEPTION FINALE
Statut officiel : L6_I2_DESIGN_FINAL_READY_FOR_GO
Dépôt : kartaguez/pocoma
Branche : feat/v3-architecture
SHA de référence : 07f71693b2a05560d7820da514d90586850a704e
Mode d'exécution : TARGETED DESIGN CORRECTION — DOCUMENTATION ONLY

Autorité de maintenance : public.read_maintenance_state (indestructible par drop v3_read)
Exclusion des workers : 5 niveaux étanches (inhibition, arrêt conteneurs, pg_stat_activity,
                        REVOKE USAGE, AccessExclusiveLock de DROP)
Machine à états & Crash : FSM 4 états / 9 phases avec reprise déterministe --resume
Oracle de remise en service : P_READY(B) sur 28 checkpoints et 0 slots <= B (non redondant)
Convergence & Watchdog : Cible B finie, watchdog de progrès re-calibré à 180s
Stratégie de vérification : Gates A, B, C (budget <= 8 min, -DfailIfNoSpecifiedTests=false)

Statut d'implémentation : IMPLEMENTATION NOT AUTHORIZED
Prochain acte attendu : Arbitrage humain final et décision GO_L6_I2_IMPLEMENTATION
================================================================================
```
