# Audit architectural et documentaire contradictoire indépendant V3 / L2

## Déclaration de politique de vérification (`AGENTS.md`)

Conformément à `AGENTS.md` et à `docs/testing/Reactor_Verification_Policy.md` :

- **Tranche(s) de vérification déclarée(s)** : `NONE` (mission d'audit architectural et documentaire contradictoire indépendant en mode strictement lecture seule ; aucune modification de code sous `app/`).
- **Commandes réellement exécutées** :
  - `git status --short`, `git rev-parse HEAD`, `git log -n 5 --oneline`, `git fetch origin feat/v3-architecture`, `git ls-remote origin refs/heads/feat/v3-architecture` (vérification de l'état exact du dépôt et du SHA distant).
  - `python3 docs/v3/check_l2_manifest.py` (exécution en lecture seule du vérificateur documentaire L2).
  - Aucune commande Maven (`mvn test`, `mvn verify`) n'a été exécutée ni revendiquée comme preuve de certification.
- **Franchissement inattendu de tranche** : Aucun.
- **Porte globale (`architecture-tests`) requise** : Non.
- **Exécution du réacteur complet** : Non (interdite par défaut par la politique du réacteur et hors périmètre d'un audit documentaire).

---

## 9.1 Synthèse exécutive

### Tableau des verdicts par axe

| Périmètre évalué | Verdict | Synthèse |
|---|---|---|
| **Axe A — Validité technique et architecturale des choix L2** | **`PASS WITH RESERVATIONS`** | Les arbitrages structurants `L2-A01..A12` et `R1..R10` sont techniquement sains, cohérents avec PostgreSQL `READ COMMITTED` et alignés avec le prérequis `L1-F2` (validé indépendamment à `PASS`). Toutefois, le protocole d'enrôlement Discovery burnable décrit dans `docs/v3/L2_Design_and_Verification_Plan.md` (§7, lignes 214–215) expose un **risque de deadlock AB-BA (`SQLSTATE 40P01`)** entre Discovery burnable et Finalizer (`V3-GEM-AUD-001`, `MAJOR`), ainsi que trois imprécisions techniques mineures sur l'indexation/filtrage SQL de `v3_command` (`V3-GEM-AUD-002`), la migration du trigger différé `v3_command_unit` (`V3-GEM-AUD-003`) et la condition de progrès du polling adaptatif à `softCap` saturé (`V3-GEM-AUD-004`). Aucune de ces réserves ne remet en cause les arbitrages humains `R1..R10`. |
| **Axe B — Qualité, complétude et cohérence du dossier documentaire L2** | **`PASS WITH RESERVATIONS`** | Les six constats du précédent audit post-consolidation (`POSTAUD-001` à `POSTAUD-006`) sont effectivement corrigés au commit `39aecafd8eb2b43ce56a3c23cb4f10a0b4babfa4`. Le vérificateur `check_l2_manifest.py` valide `253` identifiants normatifs, `380` lignes et `33` oracles distincts sans erreur. Des angles morts subsistent néanmoins dans la paramétrisation générique par renvoi circulaire de la colonne `Oracle indépendant attendu` (`V3-GEM-AUD-005`, `MINOR`) et dans la fragmentation du contrat L2 sur 9 fichiers (`V3-GEM-AUD-006`, `OBSERVATION`). |
| **Axe C — Qualité, complétude et cohérence du corpus documentaire V3 global** | **`PASS WITH RESERVATIONS`** | Le corpus normatif `SPEC-01` à `SPEC-10`, `V3_Canon_and_Roadmap.md` et `V3_Implementation_Master_Plan.md` est cohérent de bout en bout sur les frontières `L0–L8`. Le prérequis `L1-F2` est formellement prouvé en code et en migration SQL. Subsistent quelques scories éditoriales mineures de numérotation interne dans `SPEC-10` et de bandeaux historiques (`V3-GEM-AUD-007`, `OBSERVATION`). |
| **Verdict global** | **`PASS WITH RESERVATIONS`** | L'architecture L2 et ses arbitrages `L2-A01..A12` / `R1..R10` sont validés sur le fond, et le prérequis `L1-F2` est levé (`PASS`). Quatre clarifications techniques ciblées dans `docs/v3/L2_Design_and_Verification_Plan.md` (`V3-GEM-AUD-001` à `V3-GEM-AUD-004`, sans nouvel arbitrage humain) doivent être intégrées avant l'ouverture formelle de l'implémentation à `G0`. |

### Référence Git auditée

| Élément | Valeur vérifiée |
|---|---|
| **Dépôt GitHub** | `kartaguez/pocoma` (`https://github.com/kartaguez/pocoma.git`) |
| **Branche auditée** | `feat/v3-architecture` (`refs/heads/feat/v3-architecture`) |
| **SHA attendu** | `39aecafd8eb2b43ce56a3c23cb4f10a0b4babfa4` |
| **SHA réellement audité (`HEAD` local et `origin/feat/v3-architecture`)** | `39aecafd8eb2b43ce56a3c23cb4f10a0b4babfa4` (`Close L2 post-consolidation audit findings`) |
| **Écart avec le SHA attendu** | Aucun (`0` commit d'écart entre `HEAD`, `origin/feat/v3-architecture` et le SHA de référence). |
| **Statut de l'arbre de travail** | Propre et intact (`git status --short` vide avant audit ; vérifications menées en lecture seule). |

### Validation indépendante du prérequis `L1-F2` (clôture de `POSTAUD-006`)

L'audit précédent (`docs/v3/L2_Post_Consolidation_Independent_Audit_Report.md`) avait laissé `L1-F2` en réserve ouverte faute de validation contradictoire indépendante de son code et de ses preuves. Nous avons audité directement le code et les migrations de `L1-F2` au SHA `39aecafd8eb2b43ce56a3c23cb4f10a0b4babfa4` :
1. **Élimination des lectures $O(V)$ sur le chemin critique WRITE** : Dans `JdbcPotRevisionAdapter.java` et `PotHistoryRows.java`, `loadCurrent()` et `save()` n'appellent plus `rows.versions()` ni `rows.checkedComponent()`. Ils utilisent `rows.latestVersion(potId)` (`ORDER BY version DESC LIMIT 1`) et `rows.openComponent(table, potId)` qui vérifie les invariants de chaîne locale (`missing_birth`, `missing_previous`, `ahead_of_open`, `overlapping_open`) par sous-requêtes ponctuelles indexées sur la seule ligne ouverte (`WHERE pot_id = ? AND active_to IS NULL`).
2. **Couverture d'indexation du trigger différé `v3_version_has_effect`** : La migration `V4__write_effect_indexes.sql` ajoute les 8 index composites `(pot_id, active_from)` sur les tables de composants, garantissant que le trigger `v3_require_effective_version()` s'exécute en $O(1)$ par sonde d'index.
3. **Preuves mesurées (`L1_F2_Closure_Report.md`)** : Aux profondeurs `100 / 1 000 / 10 000` versions, le nombre de lignes transférées vers Java sur une mutation WRITE est désormais **constant à `28` lignes** (contre `354,5 / 3 054,5 / 30 054,5` en `L1-F`), avec un ratio de latence $p95(10\,000)/p95(100) = 1{,}02$ en écriture et $1{,}00$ en lecture courante/historique.
4. **Conclusion sur `L1-F2`** : **Prérequis `L1-F2` validé (`PASS`)**. La réserve `POSTAUD-006` est définitivement levée.

---

## 9.2 Évaluation des décisions L2 (`L2-A01..A12` et `R1..R10`)

### Matrice d'évaluation des décisions `L2-A01` à `L2-A12`

| ID | Sujet (`docs/v3/L2_Decision_Register.md`) | Verdict | Risque résiduel | Recommandation |
|---|---|---|---|---|
| **`L2-A01`** | Périmètre fonctionnel de L2 (Command Pot réel + consommateurs synthétiques de qualification pour `POT_EVENT`, `COMMAND_COMPLETION_EVENT`, `IDENTITY_REQUEST`, `IDENTITY_EVENT`, `PROJECTION_REBUILD_TASK`) | **`VALIDATED`** | Faible : risque de confusion entre certification du moteur générique L2 et certification métier L3/L4/L5. | Conserver la frontière explicite documentée dans `docs/v3/L2_Design_and_Verification_Plan.md` §13.3. |
| **`L2-A02`** | Granularité des claims, leases et fencing (bail sur `(consumptionId, partitionId)` et claim sur `(consumptionId, consumableId)` avec `claimToken` UUID + `attemptFence` monotone) | **`VALIDATED`** | Faible : nécessite la revalidation systématique du couple `(claimToken, attemptFence)` sur tout renouvellement, release et finalisation. | Aucun changement d'architecture ; appliquer strictement l'oracle `OR-FENCE-01`. |
| **`L2-A03`** | Topologie de persistance WRITE vs READ (`COMMAND`, `IDENTITY_REQUEST` dans WRITE ; événements et `PROJECTION_REBUILD_TASK` dans READ ; zéro transaction distribuée) | **`VALIDATED`** | Faible : impose à Discovery non-burnable de lire WRITE en `READ COMMITTED` puis d'écrire ses slots et son checkpoint `K` dans READ. | Maintenir l'absence totale d'écriture WRITE depuis Discovery/Engine non-burnable (`OR-ISO-01`). |
| **`L2-A04`** | Cardinalité et cycle de vie de `DiscoveryCheckpoint` (`K` unique par `(consumptionId, partitionId)` pour non-burnables ; curseur UUID volatil en mémoire pour burnables) | **`VALIDATED WITH RESERVATIONS`** | Moyen sur les burnables si le scan SQL de `v3_command` ne pousse pas `NOT EXISTS (slot)` en SQL ou ne dispose pas d'index partiel `WHERE status = 'PENDING'` (`V3-GEM-AUD-002`). | Ajouter l'index partiel `WHERE status = 'PENDING'` et clarifier la règle de wrap-around `< B` dans `docs/v3/L2_Design_and_Verification_Plan.md` §6–§7. |
| **`L2-A05`** | Politique `softCap` et arrêt partiel de fenêtre (plafond par `(consumptionId, partitionId)`, arrêt de `K` à `position(E) - 1` sur le premier événement de `P` excédentaire) | **`VALIDATED WITH RESERVATIONS`** | Mineur : si `C_dispo = 0`, l'avancement de `K` sur des événements hors `P` ne doit pas réinitialiser le backoff de polling au minimum (`V3-GEM-AUD-004`). | Préciser dans `docs/v3/L2_Design_and_Verification_Plan.md` §8 que `K` avancé sans slot créé ne réinitialise le délai au minimum que si `C_dispo > 0`. |
| **`L2-A06`** | Politique de retry, backoff, quarantaine et reprise (6 classes d'échec, backoff borné à 5 paliers, `MAX_TECHNICAL_ATTEMPTS`, état `QUARANTINED`, reprise explicite) | **`VALIDATED`** | Faible : la distinction entre collision CAS Pot transitoire et invariant métier déterministe est déjà prouvée en `L1-C`. | Maintenir la classification stricte en 6 classes (`OR-FAIL-01`, `OR-QUAR-01`). |
| **`L2-A07`** | Coordination entre exécutions (sérialisation métier exclusivement au commit via CAS/triggers/index, aucun verrou métier pris au claim) | **`VALIDATED`** | Faible : dépend du maintien de transactions courtes en finalisation. | Aucun changement. |
| **`L2-A08`** | Ordonnancement, équité et déclenchement (Discovery adaptatif, Engine piloté par échéance, tourniquet borné par `(C, P)` avec quota `Q`) | **`VALIDATED`** | Faible : simple et déterministe. | Aucun changement. |
| **`L2-A09`** | Emplacement des types et ports dans l'arbre Maven (adaptation sur place de `domain-consumption`, `engine-consumption`, `orchestrator-consumption`, etc., sans nouveau module) | **`VALIDATED`** | Faible : évite l'inflation au-delà des 60 modules Maven actuels, mais nécessite de purger proprement la sémantique V2 de `ConsumptionSlot` (`DONE`/`ABANDONED` en place vs suppression à la complétion V3). | Vérifier en `I1` que `ConsumptionSlot.java` est aligné sur le cycle `AVAILABLE / CLAIMED / QUARANTINED` et la suppression terminale. |
| **`L2-A10`** | Observabilité minimale de L2 (compteurs, jauges d'âge, traces corrélées par `consumptionId`, `consumableId`, `partitionId`, `workerId`, `attemptCount`, `outcome`) | **`VALIDATED`** | Faible. | Aucun changement. |
| **`L2-A11`** | Stratégie de test et qualification de L2 (`P0 -> G0 -> I1..I5 -> G1 -> G2 -> I6 -> I7 -> G3`, campagnes `C01..C08`, `E01..E03`, `O01..O02`, `P01`) | **`VALIDATED WITH RESERVATIONS`** | Mineur : résoudre l'écart entre la classe synthétique unique `L2ManifestProof` du manifeste et les vrais modules Maven de test avant `G1/G2` (`V3-GEM-AUD-005`). | Documenter et outiller le mapping réel dans le collecteur de preuves en `I6`. |
| **`L2-A12`** | Partitionnement déterministe et ownership exclusif de partition pour Discovery (`partition_ownership` avec bail et `ownership_fence`) | **`VALIDATED`** | Faible : empêche structurellement deux Discovery concurrents d'avancer `K` sur la même partition `(C, P)`. | Aucun changement. |

### Matrice d'évaluation des consolidations normatives `R1` à `R10`

| ID | Sujet (`docs/v3/L2_R1_R10_Normative_Consolidation.md`) | Verdict | Risque résiduel | Recommandation |
|---|---|---|---|---|
| **`R1`** | Intégration de `ExecutePotCommand` : Option B — transaction finale unique (fencing du slot + effets métier WRITE + `SET CONSTRAINTS ... IMMEDIATE` des 22 contraintes d'historique + suffixe de séquence + suppression du slot) | **`VALIDATED WITH RESERVATIONS`** | Mineur : dans `V2__command_execution.sql` L79, le trigger différé `v3_require_command_unit()` vérifie `not exists(... from v3_command_slot)`. Lors du remplacement de `v3_command_slot` par `consumption_slot`, ce trigger doit être migré vers `consumption_slot` tout en restant `DEFERRABLE INITIALLY DEFERRED` (`V3-GEM-AUD-003`). | Expliciter la mise à jour de `v3_require_command_unit()` dans l'étape `I1`/`I2` du plan L2. |
| **`R2`** | Sûreté de `K` sous commits concurrents d'événements : couplage à la barrière de séquence monotone WRITE (`v3_event_counter` verrouillé `FOR UPDATE` dans le suffixe final de commit) | **`VALIDATED`** | Très faible : tant que `v3_event_counter` est incrémenté dans le suffixe final après validation des contraintes métier (`L1-C`), l'ordre des `position` coïncide avec l'ordre de visibilité des commits `READ COMMITTED`. | Garantir en `I3` (et en `L3` pour `IDENTITY_EVENT` / `INVITATION_EVENT`) que toute famille d'événements utilise exactement le même protocole de compteur sérialisé en suffixe de commit. |
| **`R3`** | Fonction de partitionnement `MurmurHash3_x86_32(UTF-8, seed=0)` et table `consumption_routing_contract` (4 tables techniques par base) | **`VALIDATED`** | Faible : la 4e table `consumption_routing_contract` est défensive (`V3-GEM-AUD-008`), mais inoffensive si elle est vérifiée uniquement au démarrage/enregistrement du consommateur et jamais jointe sur le chemin critique. | Interdire toute jointure ou verrouillage de `consumption_routing_contract` pendant les cycles Discovery et Engine. |
| **`R4`** | Scan Discovery burnable borné par `(status = 'PENDING', request_id > cursor)` ordonné par `request_id ASC LIMIT B` avec retour à `MIN_UUID` | **`VALIDATED WITH RESERVATIONS`** | **Majeur sur l'ordre des verrous à l'insertion (`V3-GEM-AUD-001`)** : verrouiller `v3_command` avant `INSERT INTO consumption_slot ON CONFLICT DO NOTHING` crée un deadlock AB-BA avec le Finalizer. **Mineur sur le scan (`V3-GEM-AUD-002`)** : absence d'index partiel `WHERE status = 'PENDING'` sur `v3_command` et nécessité de filtrer `NOT EXISTS (slot)` en SQL avant `LIMIT B`. | Enforcer l'ordre canonique `consumption_slot -> v3_command` à l'enrôlement burnable (`V3-GEM-AUD-001`) et ajouter l'index partiel `v3_command(request_id) WHERE status = 'PENDING'` (`V3-GEM-AUD-002`). |
| **`R5`** | Autorité temporelle unique de la base (`clock_timestamp()`) pour tous les baux, claims, expirations et `retry_after` | **`VALIDATED`** | Nul : élimine tout biais d'horloge JVM entre Workers. | Utiliser exclusivement `clock_timestamp()` dans les prédicats et affectations SQL. |
| **`R6`** | Tableaux de dimensionnement explicites de `P01` et `C01–C08` / `E01–E03` / `O01–O02` et profils CI PR vs qualification complète | **`VALIDATED`** | Faible. | Aucun changement. |
| **`R7`** | Gouvernance de `G0` exclusivement humaine (aucun auto-franchissement par un agent IA) | **`VALIDATED`** | Nul : respecté dans le dossier et dans le présent audit. | Maintenir le verrou humain. |
| **`R8`** | Non-purge en L2 et absence de nettoyage destructif implicite | **`VALIDATED`** | Faible : la table `consumption_slot` reste bornée car les slots sont supprimés à la complétion terminale, tandis que les tables sources (`v3_command`, événements) croissent de façon monotone (d'où l'importance de l'index partiel `V3-GEM-AUD-002`). | Aucun changement de politique. |
| **`R9`** | Sémantique de quarantaine et prévention de ré-enrôlement des burnables terminaux | **`VALIDATED WITH RESERVATIONS`** | Moyen si l'ordre des verrous entre revalidation `PENDING` et insertion dans `consumption_slot` n'est pas inversé (`V3-GEM-AUD-001`). | Appliquer la séquence `INSERT INTO consumption_slot ON CONFLICT DO NOTHING` puis vérification verrouillée `status = 'PENDING'` sur la source (`V3-GEM-AUD-001`). |
| **`R10`** | Renouvellement de lease et préemption : Option B — renouvellement proactif borné par `maxRenewals` et `maxTotalHoldDuration`, sans thread de heartbeat ni interruption de thread | **`VALIDATED`** | Faible : modèle coopératif très simple et testable déterministement via horloge SQL contrôlée. | Aucun changement. |

---

## 9.3 Registre des constats

### `V3-GEM-AUD-001` — Risque de deadlock AB-BA (`SQLSTATE 40P01`) entre Discovery burnable et Finalizer lors de la revalidation `PENDING` et de l'insertion dans `consumption_slot`

- **Sévérité** : **`MAJOR`**
- **Axe** : **Axe A** (A2 Correction transactionnelle et concurrence PostgreSQL / A3 Discovery burnable / A5 Recovery et non-ré-enrôlement `R9`)
- **Fichiers, sections et lignes exacts** :
  - `docs/v3/L2_Design_and_Verification_Plan.md` §7 « Burnables — enrôlement sans checkpoint persistant », étape 4, lignes 214–215
  - `docs/v3/L2_R1_R10_Normative_Consolidation.md` §1 (`R1-02`, lignes 17–24) et §9 (`R9-02`, ligne 141)
  - Code existant de référence : `ExecutePotCommand.java` lignes 154–176 et `JdbcPotCommandStore.java` lignes 216–309
- **Scénario / entrelacement précis** :
  1. Le plan `docs/v3/L2_Design_and_Verification_Plan.md` (lignes 214–215) prescrit pour chaque candidat burnable `R` :
     > *« chaque candidat retenu revalide `PENDING` sous une protection qui sérialise la terminalisation et insère idempotemment la clé canonique `(consumption_id, consumable_id)`. [...] Attention à l'ordre des verrous (`partition -> source -> slot` vs finalizer `slot -> source`). Recommandation : ne jamais attendre un slot existant après prise d'une source ; constater le doublon et abandonner l'enrôlement. »*
  2. Or, dans PostgreSQL, cette recommandation est **impossible à satisfaire si le verrou sur la ligne source (`v3_command`) est acquis avant l'insertion dans `consumption_slot`** :
     - Supposons qu'une commande `R` a été lue `PENDING` à l'étape 2 (hors verrou, ou avant que son slot ne soit créé/pendant une course avec un Worker).
     - **Transaction $T_{\text{fin}}$ (Finalizer Engine, protocole `R1-02`)** :
       - Étape 1 : verrouille `consumption_slot(C, R) FOR UPDATE` (ou le supprime par `DELETE FROM consumption_slot WHERE consumption_id = C AND consumable_id = R AND claim_token = ...`). $T_{\text{fin}}$ détient désormais un **verrou exclusif (X-lock) sur la clé d'index unique `(C, R)` de `consumption_slot`** jusqu'à son commit physique.
     - **Transaction $T_{\text{disc}}$ (Discovery burnable)** :
       - Suit l'ordre décrit (`source -> slot`) : verrouille `SELECT status FROM v3_command WHERE request_id = R FOR SHARE` (ou `FOR UPDATE`) pour « revalider `PENDING` sous une protection qui sérialise la terminalisation ». Comme $T_{\text{fin}}$ n'a pas encore exécuté son `UPDATE v3_command SET status = 'TERMINAL'`, $T_{\text{disc}}$ obtient le verrou sur `v3_command(R)` et voit `status = 'PENDING'`.
       - Puis $T_{\text{disc}}$ exécute `INSERT INTO consumption_slot(consumption_id, consumable_id, ...) VALUES (C, R, ...) ON CONFLICT (consumption_id, consumable_id) DO NOTHING`.
       - **Comportement interne PostgreSQL** : Contrairement à `SELECT ... FOR UPDATE SKIP LOCKED`, `INSERT ... ON CONFLICT DO NOTHING` **ne possède pas de mode `SKIP LOCKED`**. Lorsqu'une transaction concurrente non committée ($T_{\text{fin}}$) détient un verrou X sur le tuple `(C, R)` dans `consumption_slot` (suite à son `FOR UPDATE` ou `DELETE`), PostgreSQL **bloque obligatoirement l'`INSERT ... ON CONFLICT DO NOTHING` de $T_{\text{disc}}$** en attente de la fin de $T_{\text{fin}}$ !
     - Simultanément, **$T_{\text{fin}}$** poursuit son protocole `R1-02` et exécute `UPDATE v3_command SET status = 'TERMINAL' WHERE request_id = R`, ce qui **bloque en attente du verrou `FOR SHARE`/`FOR UPDATE` détenu par $T_{\text{disc}}$ sur `v3_command(R)`** !
     - **Résultat** : **Deadlock AB-BA déterministe (`SQLSTATE 40P01`)** entre $T_{\text{disc}}$ (`v3_command(R)` $\rightarrow$ `consumption_slot(C, R)`) et $T_{\text{fin}}$ (`consumption_slot(C, R)` $\rightarrow$ `v3_command(R)`).
- **Invariant menacé** :
  - Absence de deadlock entre Discovery burnable et Finalizer sous concurrence (`CON-01`, `CON-04`, `R1-02`, `R9-02`, sous-scénario `C04-F`).
- **Impact concret** :
  - Lorsqu'un cycle Discovery burnable (par exemple après un wrap-around à `MIN_UUID` ou un changement de propriétaire de partition) croise la finalisation d'une commande `R`, l'une des deux transactions (le Finalizer de la commande ou le lot d'enrôlement Discovery) échoue avec `40P01 Deadlock detected`.
- **Correction minimale recommandée** :
  - Imposer dans `docs/v3/L2_Design_and_Verification_Plan.md` §7 Burnables, étape 4 (lignes 214–215) le **même ordre canonique de verrouillage `consumption_slot(C, R) -> source (v3_command(R))`** dans Discovery burnable que dans le Finalizer :
    1. À l'étape 4 (après `partition_ownership FOR UPDATE`), pour chaque candidat `R` issu de l'étape 2, exécuter **d'abord** `INSERT INTO consumption_slot(consumption_id, consumable_id, partition_id, ...) VALUES (C, R, P, ...) ON CONFLICT (consumption_id, consumable_id) DO NOTHING`.
       - Si `0` ligne est insérée, le slot existe déjà : ignorer `R` immédiatement sans toucher à `v3_command`.
       - Si un Finalizer $T_{\text{fin}}$ était en train de supprimer `(C, R)`, l'`INSERT` attend la fin de $T_{\text{fin}}$ **avant** que $T_{\text{disc}}$ ne détienne le moindre verrou sur `v3_command(R)` : aucun cycle d'attente n'est possible.
    2. **Ensuite seulement** (si `1` ligne a été insérée dans `consumption_slot`, ce qui confère à $T_{\text{disc}}$ le verrou X sur ce nouveau slot), revalider `SELECT status FROM v3_command WHERE request_id = R FOR SHARE` (ou supprimer immédiatement le slot inséré via `DELETE FROM consumption_slot WHERE consumption_id = C AND consumable_id = R AND NOT EXISTS (SELECT 1 FROM v3_command WHERE request_id = R AND status = 'PENDING' FOR SHARE)`). Si `v3_command(R)` est devenue `TERMINAL` entre l'étape 2 et l'`INSERT`, le slot fraîchement inséré est supprimé dans la même transaction avant le `COMMIT` de l'étape 4 (aucun Worker ne peut donc jamais le voir ni le claimer).
- **Nécessité d'un nouvel arbitrage humain** : **Non** (conserve intégralement `R1`, `R4` et `R9` ; corrige uniquement l'ordre d'acquisition des verrous à l'étape 4 de §7).
- **Preuve attendue de correction** :
  - Mise à jour des lignes 214–215 de `docs/v3/L2_Design_and_Verification_Plan.md` prescrivant l'ordre `consumption_slot -> source (v3_command / v3_identity_request)` avec suppression intra-transactionnelle si la source est devenue `TERMINAL`.
  - En qualification (`C04-F` / `OR-TERM-01`), test d'entrelacement concurrent entre Finalizer et Discovery burnable sur la même `requestId` démontrant `0` deadlock `40P01` et `0` slot recréé post-commit.

---

### `V3-GEM-AUD-002` — Dégradation en $O(N_{\text{terminal}})$ du scan Discovery burnable (`R4`) faute d'index partiel `WHERE status = 'PENDING'` sur `v3_command` et risque de blocage en tête de file inter-partitions sur la règle `< B`

- **Sévérité** : **`MINOR`**
- **Axe** : **Axe A** (A3 Discovery burnable / A6 Coût, performance et scalabilité) & **Axe B** (B1 Cohérence entre `R4` et le plan L2)
- **Fichiers, sections et lignes exacts** :
  - `docs/v3/L2_R1_R10_Normative_Consolidation.md` §4 (`R4-01` à `R4-03`, lignes 63–72)
  - `docs/v3/L2_Design_and_Verification_Plan.md` §6 (lignes 187–189) et §7 Burnables étapes 2 et 5 (lignes 213 et 216)
  - `app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql` lignes 13–17
- **Scénario / entrelacement précis** :
  1. **Absence d'index partiel `WHERE status = 'PENDING'`** : Dans `V2__command_execution.sql` (lignes 13–17), `v3_command` ne possède que sa clé primaire `request_id uuid primary key`. Or, selon `R8` (`SPEC-03` §3), les commandes terminées ne sont jamais purgées et passent à `status = 'TERMINAL'`. Sans index partiel `CREATE INDEX ... ON v3_command(request_id) WHERE status = 'PENDING'`, chaque cycle Discovery burnable exécutant `WHERE status = 'PENDING' AND request_id > :cursor ORDER BY request_id ASC LIMIT B` parcourt dans l'index primaire `v3_command_pkey` **toutes les lignes `TERMINAL` historiques** ($10^5, 10^6 \dots$) pour filtrer `status = 'PENDING'` sur la table.
  2. **Poussée de `NOT EXISTS (slot)` en SQL et interprétation de la règle `< B` (`R4-03`)** :
     - Si le prédicat `NOT EXISTS (SELECT 1 FROM consumption_slot s WHERE s.consumption_id = :cid AND s.consumable_id = c.request_id)` n'est pas évalué en SQL avant `LIMIT B`, alors $B$ commandes en `QUARANTINED` (qui restent `status = 'PENDING'` dans `v3_command` selon `R9-01` tout en ayant un slot `QUARANTINED`) satureront la fenêtre `LIMIT B` après un retour à `MIN_UUID`.
     - En outre, comme `MurmurHash3_x86_32` (`R3-01`) est une fonction Java et que `v3_command` ne stocke pas `partition_id`, si la requête SQL lit `LIMIT B` lignes `PENDING` sans slot (toutes partitions confondues) puis filtre `partitionHash(requestId) mod P == P` en Java : si une partition $P_0$ est saturée (`softCap` atteint) et laisse $B$ commandes `PENDING` sans slot en tête de `v3_command`, et qu'une autre partition $P_1 \neq P_0$ évalue la condition de retour à `MIN_UUID` (`< B` lignes) sur le nombre de lignes *retenues pour $P_1$ après filtrage Java* (ici $0 < B$) au lieu du nombre de lignes *lues par la requête SQL avant filtrage de partition* (ici $B$), $P_1$ réinitialisera son curseur à `MIN_UUID` à chaque cycle et restera **bloquée indéfiniment derrière les $B$ commandes non enrôlées de $P_0$**.
- **Invariant menacé** :
  - Isolation de capacité entre partitions (`C01-E`, `C02-A`), non-famine des commandes admises pendant un scan (`C02-B`, `R4-02`), et coût borné de Discovery (`SPEC-09`, `P01`).
- **Impact concret** :
  - Dégradation linéaire du temps de scan Discovery burnable avec le volume historique de commandes `TERMINAL`, et risque de famine inter-partitions si `< B` est évalué après filtrage Java de la partition.
- **Correction minimale recommandée** :
  1. Dans `docs/v3/L2_Design_and_Verification_Plan.md` §6 (lignes 187–189), ajouter l'index partiel `v3_command (request_id) WHERE status = 'PENDING'` dans la migration WRITE de `I1`.
  2. Dans `docs/v3/L2_Design_and_Verification_Plan.md` §7 Burnables étapes 2 et 5 (lignes 213 et 216), préciser explicitement que :
     - le filtre `NOT EXISTS (SELECT 1 FROM consumption_slot s WHERE s.consumption_id = :c AND s.consumable_id = cmd.request_id)` est exécuté **en SQL avant `LIMIT B`** ;
     - si le filtrage `partitionHash(requestId) mod P = P` est effectué en Java sur le lot SQL de taille `B`, alors `cursor_volatil(C, P)` avance à la dernière `request_id` examinée dans le lot SQL et ne repart à `MIN_UUID` au cycle suivant que lorsque **la requête SQL brute a retourné `< B` lignes** (et que toutes les lignes du lot SQL ont été examinées sans être interrompues par `C_dispo == 0`).
- **Nécessité d'un nouvel arbitrage humain** : **Non** (simple précision d'implémentation SQL/Java de `R4`).
- **Preuve attendue de correction** :
  - Ajout de l'index partiel et de la règle de curseur SQL dans `docs/v3/L2_Design_and_Verification_Plan.md` §6 et §7.

---

### `V3-GEM-AUD-003` — Dépendance du trigger différé `v3_require_command_unit()` envers l'ancienne table `v3_command_slot` dans `V2__command_execution.sql`

- **Sévérité** : **`MINOR`**
- **Axe** : **Axe A** (A2 Correction transactionnelle PostgreSQL / `R1`) & **Axe B** (B3 Plan d'exécution `I1`/`I2`)
- **Fichiers, sections et lignes exacts** :
  - `app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql` lignes 70–97 (en particulier ligne 79)
  - `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/pot/v3/PotCommandJdbcTransactions.java` lignes 31–44
  - `docs/v3/L2_Design_and_Verification_Plan.md` §11 étapes `I1` et `I2`, lignes 309–327
- **Scénario / entrelacement précis** :
  - Dans `V2__command_execution.sql` (lignes 79–81), la fonction PL/pgSQL `v3_require_command_unit()` (appelée par les constraint triggers `DEFERRABLE INITIALLY DEFERRED` `v3_command_unit` et `v3_result_unit` au commit physique) vérifie :
    ```sql
    if r.request_id is null or exists(select 1 from v3_command_slot where request_id=c.request_id)
       or not exists(select 1 from v3_command_completion_event e join v3_event_position p ...) then
        raise exception 'Incomplete terminal Command unit' using errcode='23514';
    end if;
    ```
  - L'étape `I1` de `docs/v3/L2_Design_and_Verification_Plan.md` (lignes 309–318) prévoit de remplacer le prototype `v3_command_slot` par la table canonique `consumption_slot`.
  - Si `v3_require_command_unit()` n'est pas mise à jour lors de cette migration pour interroger `consumption_slot (WHERE consumption_id = 'ExecutePotCommand' AND consumable_id = c.request_id)` :
    - soit `DROP TABLE v3_command_slot` fait échouer tout commit de commande à l'exécution du trigger (`relation "v3_command_slot" does not exist`) ;
    - soit, si `v3_command_slot` est laissée vide sans mise à jour du trigger, la garantie base de données qu'aucune commande `TERMINAL` ne conserve son slot dans `consumption_slot` au commit physique est silencieusement perdue !
  - De plus, `v3_command_unit` et `v3_result_unit` doivent impérativement rester `DEFERRABLE INITIALLY DEFERRED` (et ne **pas** être ajoutés aux 22 contraintes de `IMMEDIATE_BUSINESS_CONSTRAINTS` dans `PotCommandJdbcTransactions.java`), car à l'étape 4 de `R1-02` (`SET CONSTRAINTS ... IMMEDIATE`), ni le suffixe de séquence `v3_event_position` (étape 5) ni le `DELETE FROM consumption_slot` (étape 6) n'ont encore été exécutés.
- **Invariant menacé** :
  - Atomicité de l'unité de commande terminale au commit physique (`R1-02`, `L1-C`).
- **Impact concret** :
  - Échec SQL au commit en `I1`/`I2` ou perte du garde-fou SQL vérifiant la suppression effective du slot à la terminalisation.
- **Correction minimale recommandée** :
  - Mentionner explicitement dans `docs/v3/L2_Design_and_Verification_Plan.md` §11 étapes `I1`/`I2` (lignes 309–327) la mise à jour de `v3_require_command_unit()` vers `consumption_slot` et le maintien de son statut `DEFERRABLE INITIALLY DEFERRED` évalué au commit physique après l'étape 6 de `R1-02`.
- **Nécessité d'un nouvel arbitrage humain** : **Non**.
- **Preuve attendue de correction** :
  - Précision ajoutée dans `I1`/`I2` de `docs/v3/L2_Design_and_Verification_Plan.md`.

---

### `V3-GEM-AUD-004` — Réinitialisation intempestive du backoff Discovery non-burnable lorsque `C_dispo = 0` (`softCap` saturé) et que `K` avance sur des événements hors partition `P`

- **Sévérité** : **`MINOR`**
- **Axe** : **Axe A** (A3 Discovery non-burnable / A4 Capacité et ordonnancement)
- **Fichiers, sections et lignes exacts** :
  - `docs/v3/L2_Design_and_Verification_Plan.md` §8 « Ordonnancement, équité, capacité et déclenchement », lignes 220–222
  - `docs/v3/L2_R1_R10_Normative_Consolidation.md` §2 (`R2-04`, ligne 36)
- **Scénario / entrelacement précis** :
  - Selon `R2-04`, lorsqu'une partition `(C, P)` a atteint son `softCap` (`activeCount >= softCap`, donc `C_dispo = 0`), Discovery peut encore faire avancer `K` sur les événements hors partition `P` situés avant le prochain événement de `P`.
  - Or, `docs/v3/L2_Design_and_Verification_Plan.md` §8 (lignes 221–222) définit :
    > *« Discovery : polling adaptatif borné ; retour au délai minimal dès qu'un cycle fait progresser le système, backoff progressif borné sur cycles vides. Progrès Discovery : au moins un slot créé ou `K` avancé. »*
  - Si la partition `(C, P)` est saturée (`C_dispo = 0`, par exemple parce que ses `softCap` slots sont en cours de traitement long ou en `QUARANTINED`), et que d'autres partitions $P' \neq P$ produisent un flux continu d'événements dans WRITE, chaque cycle Discovery de `(C, P)` lira une fenêtre `F` dans WRITE, n'enrôlera `0` slot (car `C_dispo = 0`), mais avancera `K` sur les événements de $P'$, ce qui **réinitialisera son délai de polling au minimum** ! La partition saturée `(C, P)` consultera donc WRITE et READ à fréquence maximale alors même qu'elle a `C_dispo = 0` et ne peut enrôler aucun travail.
- **Invariant menacé** :
  - Sobriété du polling sous saturation (`O01-E`, `C01-L`, `L2-A08`).
- **Impact concret** :
  - Consommation inutile de requêtes WRITE/READ par les partitions saturées à `softCap` tant que d'autres partitions publient des événements.
- **Correction minimale recommandée** :
  - Préciser dans `docs/v3/L2_Design_and_Verification_Plan.md` §8 (ligne 222) :
    > *« Progrès Discovery réinitialisant le délai au minimum : au moins un slot créé, ou (`K` avancé alors que `C_dispo > 0`). Lorsque `C_dispo = 0` (`softCap` atteint), un cycle qui n'enrôle aucun slot applique le backoff borné même si `K` a franchi des événements hors partition. »*
- **Nécessité d'un nouvel arbitrage humain** : **Non**.
- **Preuve attendue de correction** :
  - Mise à jour de la ligne 222 de `docs/v3/L2_Design_and_Verification_Plan.md`.

---

### `V3-GEM-AUD-005` — Angles morts mécaniques de `check_l2_manifest.py` et uniformité synthétique de `L2_Certification_Coverage_Manifest.md`

- **Sévérité** : **`MINOR`**
- **Axe** : **Axe B** (B4 Manifeste, oracles et vérificateur mécanique)
- **Fichiers, sections et lignes exacts** :
  - `docs/v3/L2_Certification_Coverage_Manifest.md` lignes 19–399
  - `docs/v3/check_l2_manifest.py` lignes 106–198
  - `docs/v3/L2_Independent_Test_Oracles.md` lignes 1–185
- **Scénario / entrelacement précis** :
  - L'inspection directe des 380 lignes de `docs/v3/L2_Certification_Coverage_Manifest.md` et du script `docs/v3/check_l2_manifest.py` révèle deux limites structurelles :
    1. **Paramétrisation générique par renvoi circulaire** : Sur les `324` lignes obligatoires L2, `323` lignes contiennent dans la colonne `Oracle indépendant attendu` la chaîne générique auto-générée :
       `"OR-xxx ; cas <ID> ; paramètres et injection : colonne Stimulus"`
       (seule la ligne `L2M-R7-02` à la ligne 397 contient un oracle rédigé en propre). Le script `check_l2_manifest.py` se contente de vérifier `len(row["Oracle indépendant attendu"]) >= 12` et la présence d'au moins un code `OR-*`. Or, pour plusieurs lignes de campagne (ex. `L2M-C01-01` où `Stimulus` vaut simplement `"Campagne C01 Discovery non-burnable."`), renvoyer à la colonne `Stimulus` pour « paramètres et injection » ne fournit aucun paramètre concret (`F`, `P`, `softCap`, point d'arrêt).
    2. **Classe de test fictive unique `L2ManifestProof`** : Les `324` lignes obligatoires pointent toutes vers une classe hypothétique unique `prévisionnelle : L2ManifestProof.l2m_<id>`, alors que l'architecture Maven sépare les tests dans `domain-consumption`, `engine-consumption`, `orchestrator-consumption`, `runtime-command-consumption-worker`, `runtime-event-consumption-worker`, `runtime-task-consumption-worker` et `architecture-tests`.
- **Invariant menacé** :
  - Traçabilité mécanique sans fausse certification aux portes `G1`, `G2` et `G3` (`SPEC-10` §14).
- **Impact concret** :
  - Acceptable à `G0` (où les méthodes sont explicitement marquées `prévisionnelle :`), mais risque de fausse certification à `G1`/`G2` si un test de façade `L2ManifestProof` délègue à des mocks ou si `check_l2_manifest.py` n'est pas enrichi en `I6` pour vérifier que chaque `L2M-*` pointe vers une méthode `@Test` réellement exécutée par Surefire/Failsafe avec des assertions SQL/métriques non vides.
- **Correction minimale recommandée** :
  - Avant `G1`/`G2` (étape `I6`), remplacer dans le manifeste les références `prévisionnelle : L2ManifestProof.l2m_*` par les classes/méthodes réelles de chaque module Maven, et étendre `check_l2_manifest.py` pour :
    1. interdire le préfixe `prévisionnelle :` aux portes `G1`, `G2` et `G3` ;
    2. vérifier l'existence du fichier `.java` et de la méthode `@Test` correspondante dans `app/` ;
    3. vérifier dans les rapports JUnit XML Surefire/Failsafe que chacune des `324` méthodes a effectivement été exécutée (`status="PASS"`, `0` skip).
- **Nécessité d'un nouvel arbitrage humain** : **Non**.
- **Preuve attendue de correction** :
  - En `I6` (avant `G1`/`G2`), mode `--gate G1|G2|G3` dans `check_l2_manifest.py` validant l'existence du code de test et des rapports JUnit XML.

---

### `V3-GEM-AUD-006` — Fragmentation cognitive du dossier documentaire L2 sur 9 fichiers

- **Sévérité** : **`OBSERVATION`**
- **Axe** : **Axe B** (B1/B2 Clarté, hiérarchie et autoportance du dossier L2) & **Axe C** (C4 Documentation vivante)
- **Fichiers, sections et lignes exacts** :
  - `docs/v3/L2_Design_and_Verification_Plan.md`
  - `docs/v3/L2_R1_R10_Normative_Consolidation.md`
  - `docs/v3/L2_Decision_Register.md`
  - `docs/v3/L2_Contradictory_Review.md`
- **Scénario / impact concret** :
  - Bien que les bandeaux d'autorité ajoutés en `POSTAUD-001/002` éliminent toute contradiction normative formelle, un développeur implémentant `I1..I5` doit croiser en permanence `docs/v3/L2_Design_and_Verification_Plan.md`, `docs/v3/L2_R1_R10_Normative_Consolidation.md`, `docs/v3/L2_Decision_Register.md` et `docs/v3/L2_Independent_Test_Oracles.md`.
- **Correction minimale recommandée** :
  - À la clôture `G3` de L2 (et dès `P0` pour les lots `L3..L8`), privilégier un document de contrat d'architecture par lot consolidé unique + un journal d'audit séparé, plutôt qu'un empilement de couches d'addenda.
- **Nécessité d'un nouvel arbitrage humain** : **Non**.

---

### `V3-GEM-AUD-007` — Scories éditoriales de numérotation interne et mentions `[SPEC-06 provisoire]` dans `SPEC-04`, `SPEC-06` et `SPEC-10`

- **Sévérité** : **`OBSERVATION`**
- **Axe** : **Axe C** (C1/C2 Cohérence et propreté éditoriale de `SPEC-01..10`)
- **Fichiers, sections et lignes exacts** :
  - `docs/v3/SPEC-10_Test_Certification.md` lignes 276 (`§§32–37`), 1104–1163 (`### 24.1` à `### 24.6` sous `## 12. Performance`) et 1181 (`revue §30`)
  - `docs/v3/SPEC-04_Read_Projections.md` ligne 99 (`[SPEC-06 provisoire]`)
  - `docs/v3/SPEC-06_Supra_Contracts.md` lignes 99 et 143 (`[SPEC-06 provisoire]`)
- **Scénario / impact concret** :
  - Vestiges purement cosmétiques de la renumérotation `AUD-V3-012` dans `SPEC-10` (`### 24.1..24.6` au lieu de `### 12.1..12.6`) et du tag `[SPEC-06 provisoire]` alors que `SPEC-06` est au statut `Ratifié V3-2`. Aucune ambiguïté fonctionnelle ni technique.
- **Correction minimale recommandée** :
  - Nettoyage éditorial non bloquant (en veillant à mettre à jour simultanément les ancres `#241-budget-par-opération` utilisées dans `docs/v3/L2_Certification_Coverage_Manifest.md` si `24.1..24.6` est renuméroté).
- **Nécessité d'un nouvel arbitrage humain** : **Non**.

---

### `V3-GEM-AUD-008` — Vigilance sur l'usage strictement au démarrage de la 4e table `consumption_routing_contract` (`R3`)

- **Sévérité** : **`OBSERVATION`**
- **Axe** : **Axe A** (A1 Simplicité et A6 Performance)
- **Fichiers, sections et lignes exacts** :
  - `docs/v3/L2_R1_R10_Normative_Consolidation.md` §3 (`R3-02` à `R3-04`, lignes 49–58)
  - `docs/v3/L2_Design_and_Verification_Plan.md` §6 lignes 180–185
- **Scénario / impact concret** :
  - L'arbitrage humain `R3` a retenu 4 tables techniques (`consumption_slot`, `partition_ownership`, `discovery_checkpoint`, `consumption_routing_contract`). Cette 4e table est un garde-fou de démarrage contre une reconfiguration accidentelle de `partition_count` ou `hash_version` entre deux redémarrages. Elle ne doit **jamais** être lue ni jointe dans la boucle chaude de Discovery ou d'Engine.
- **Correction minimale recommandée** :
  - Vérifier le contrat `consumption_routing_contract` une seule fois à l'initialisation/démarrage du `ConsumptionWorker` (et dans le test `C02-D`), jamais par cycle de polling ou par claim.
- **Nécessité d'un nouvel arbitrage humain** : **Non**.

---

## 9.4 Analyse de simplicité

### 1. Complexités nécessaires aux invariants V3 (à conserver intégralement)
- **Double fencing : `ownership_fence` sur `partition_ownership` + `(claim_token, attempt_fence)` sur `consumption_slot`** (`L2-A02`, `L2-A12`) : Indispensable en PostgreSQL sans coordinateur externe pour empêcher simultanément (a) deux Discovery de faire régresser ou diverger `K` après expiration de bail, et (b) deux Workers Engine d'appliquer un renouvellement, un release ou une finalisation obsolète après reprise de claim.
- **Transaction finale unique de `ExecutePotCommand` avec suffixe de séquence (`R1` Option B + `R2` Option A)** : C'est la décision la plus structurante et la plus juste du lot L2. Elle préserve intégralement les garanties `L1-C` (aucune commande `TERMINAL` sans résultat et événement séquencé, aucun trou de séquence visible avant la fin des validations métier) sans introduire d'états intermédiaires de slot ni de phase de réconciliation post-crash.
- **Autorité temporelle SQL unique `clock_timestamp()` (`R5`)** : Élimine toute une classe de bugs distribués liés aux dérives d'horloge JVM entre Workers.
- **Renouvellement coopératif aux frontières d'étapes (`R10` Option B)** : Beaucoup plus simple, prévisible et testable qu'un thread de heartbeat asynchrone ou qu'une interruption forcée de thread JDBC.

### 2. Complexités défensives acceptables mais coûteuses (à encadrer en implémentation)
- **Table `consumption_routing_contract` (`R3` Option B — 4e table technique)** : Un contrôle d'invariant de configuration au démarrage suffit ; le coût reste nul à l'exécution tant que cette table n'est interrogée qu'au bootstrap du Worker (`V3-GEM-AUD-008`).
- **6 classes d'échec dans `ProcessingFailureClassifier` (`L2-A06`)** : Bien que le comportement runtime se réduise essentiellement à deux décisions (retry avec backoff vs quarantaine immédiate), conserver les 6 codes explicites (`TRANSIENT_INFRA`, `SERIALIZATION_OR_CAS_CONFLICT`, `OWNERSHIP_OR_FENCE_LOST`, `MISSING_DEPENDENCY_WITHIN_GRACE`, `DETERMINISTIC_CONTRACT_VIOLATION`, `UNCLASSIFIED_BUG`) est justifié par l'observabilité (`SPEC-08`, `O01-F`) et le diagnostic de quarantaine.

### 3. Complexités injustifiées ou dangereuses qui doivent être simplifiées
- **Verrouillage de la source burnable avant l'insertion du slot (`V3-GEM-AUD-001`)** : Vouloir verrouiller `v3_command` *avant* `INSERT INTO consumption_slot ON CONFLICT DO NOTHING` complique la concurrence et crée un deadlock AB-BA avec le Finalizer. L'ordre simple et uniforme **`consumption_slot -> source`** (insérer le slot en premier avec `ON CONFLICT DO NOTHING`, puis vérifier `status = 'PENDING'` et supprimer le slot si la source est déjà `TERMINAL`) est à la fois plus simple et sans deadlock.
- **Classe de test monolithique hypothétique `L2ManifestProof` (`V3-GEM-AUD-005`)** : Centraliser 324 preuves dans une classe synthétique unique contredit le découpage par tranches du réacteur (`docs/testing/Reactor_Verification_Policy.md`). Mapper directement chaque ligne `L2M-*` vers les classes de test des modules `domain-consumption`, `engine-consumption`, `orchestrator-consumption` et `runtime-*-worker` évite une couche d'indirection artificielle.

---

## 9.5 Matrice des dépendances documentaires (`SPEC-01..10` et `L0..L8`)

| Source | Cible(s) | Nature de l'interface / dépendance | Statut de cohérence | Contradictions, cycles, trous ou responsabilités non attribuées |
|---|---|---|---|---|
| **`SPEC-01` (Pot Domain)** | `SPEC-03`, `SPEC-04`, `L1`, `L1-F`, `L1-F2`, `L2` | Modèle de révisions par composant `[active_from, active_to)`, CAS Pot global (`v3_pot_identity.current_version`), `ExecutePotCommand` | **Cohérent (`PASS`)** | Aucun cycle. Le trou de performance `L1-F` sur la vérification d'historique WRITE est formellement comblé par `L1-F2` (`28` lignes constantes). |
| **`SPEC-02` (Identity Domain)** | `SPEC-01`, `SPEC-03`, `SPEC-04`, `L3`, `L4` | EUI `(issuer, subject)`, `User`, `Invitation` (token haché + lien récupérable protégé), rattachement 1–1 `(potId, userId)` | **Cohérent (`PASS`)** | Responsabilités clairement réparties : domaine et écriture en `L3`, projections READ (`CurrentUser`, `CurrentInvitation`) en `L4`. |
| **`SPEC-03` (Events, Requests, Consumables)** | `SPEC-05`, `L1-C`, `L2`, `L3`, `L5` | 6 familles de consommables (`COMMAND`, `IDENTITY_REQUEST`, `POT_EVENT`, `IDENTITY_EVENT`, `INVITATION_EVENT`, `COMMAND_COMPLETION_EVENT`, `PROJECTION_REBUILD_TASK`), non-purge (`R8`), séquence monotone par famille (`v3_event_counter` / `v3_event_position`) | **Cohérent avec 1 point d'attention `L2/L3`** | En `L1-C`, `v3_event_counter` ne contient que `('POT', 'COMMAND_COMPLETION')` (`V2__command_execution.sql` L55–56). Pour la qualification synthétique L2 de `IDENTITY_EVENT` (`L2-A01`), la fixture de test L2 devra enregistrer la famille nécessaire sans polluer le schéma métier définitif de `L3`, puis `L3` ajoutera `IDENTITY` et `INVITATION` avec le même protocole de suffixe sérialisé (`R2`). |
| **`SPEC-04` (Read Projections)** | `SPEC-01`, `SPEC-02`, `SPEC-03`, `SPEC-05`, `L4`, `L5` | Projections `CurrentPot`, `CurrentUser`, `CurrentInvitation`, `CommandResult`, `IdentityRequestResult` ; idempotence monotone par `version` ; absence de dépendance READ $\rightarrow$ WRITE hors `RebuildPotProjection` (`L5`) | **Cohérent (`PASS`)** | Mention résiduelle `[SPEC-06 provisoire]` ligne 99 (`V3-GEM-AUD-007`). Responsabilités de matérialisation en `L4` et de rebuild en `L5` clairement attribuées. |
| **`SPEC-05` (Consumption & Discovery)** | `SPEC-03`, `SPEC-08`, `SPEC-09`, `SPEC-10`, `L2` | Modèle `ConsumptionSlot`, `DiscoveryCheckpoint`, `PartitionOwnership`, `ConsumptionRoutingContract`, burnables vs non-burnables, `softCap`, quarantaine | **Cohérent sous réserve de `V3-GEM-AUD-001..004`** | Les arbitrages `R1..R10` sont répercutés dans `SPEC-05` (§§3–8). Les 4 précisions d'implémentation SQL/ordonnancement identifiées en `V3-GEM-AUD-001..004` doivent être alignées dans `docs/v3/L2_Design_and_Verification_Plan.md`. |
| **`SPEC-06` (Supra Contracts)** | `SPEC-01..04`, `L6` | Contrats REST/HTTP supra, corrélation `requestId`, lecture de `CurrentInvitation` par `creationRequestId`, statut terminal dans `CommandResult` sans lien d'invitation | **Cohérent (`PASS`)** | Mentions résiduelles `[SPEC-06 provisoire]` lignes 99 et 143 (`V3-GEM-AUD-007`). Implémentation attribuée à `L6`. |
| **`SPEC-07` (Transverse Requirements)** | `SPEC-01..10`, `L0..L8` | Isolation WRITE/READ, transactions explicites, horloge injectable/base, erreurs déterministes | **Cohérent (`PASS`)** | Respecté par `R5` (autorité temporelle SQL) et `L2-A03` (zéro transaction distribuée WRITE/READ). |
| **`SPEC-08` (Observability)** | `SPEC-05`, `SPEC-10`, `L2`, `L7` | Métriques et traces structurées de Discovery, Engine, claims, retries, quarantaine et âge du backlog | **Cohérent (`PASS`)** | Périmètre L2 (`OPS-01`, `OPS-02`, `O01`, `O02`) clairement distingué du durcissement transverse `L7`. |
| **`SPEC-09` (Performance)** | `SPEC-01`, `SPEC-04`, `SPEC-05`, `SPEC-10`, `L1-F`, `L1-F2`, `L2`, `L7` | Budgets $p95/p99$, non-dégradation avec la profondeur d'historique, coût borné de Discovery et du claim Engine | **Cohérent (`PASS`)** | `L1-F2` valide le budget WRITE Pot ; `P01` (`R6`) cadre le budget L2 Consumption/Discovery. L'ajout de l'index partiel `v3_command(request_id) WHERE status = 'PENDING'` (`V3-GEM-AUD-002`) est requis pour préserver cette borne lorsque l'historique `TERMINAL` grandit. |
| **`SPEC-10` (Test & Certification)** | `SPEC-01..09`, `L0..L8`, `L2_Certification_Coverage_Manifest.md` | Invariants `POT-*`, `ID-*`, `EVT-*`, `PRJ-*`, `CON-*`, `SUP-*`, `OPS-*`, `PERF-*`, campagnes `C01–C08`, `E01–E03`, `O01–O02`, `P01` | **Cohérent (`PASS`)** | Les 253 identifiants normatifs applicables à L2 sont 100 % couverts par les 380 lignes du manifeste. Scorie de numérotation `### 24.1..24.6` sous `## 12` (`V3-GEM-AUD-007`). |
| **Programme `L0..L8` (`V3_Implementation_Master_Plan.md`)** | `V3_Canon_and_Roadmap.md`, `L1-F2`, `L2` | Séquence `L0 (PASS) -> L1 (PASS) -> L1-F (FAIL) -> L1-F2 (PASS) -> L2 (en revue G0) -> L3 -> L4 -> L5 -> L6 -> L7 -> L8` | **Cohérent (`PASS`)** | Aucune responsabilité orpheline entre `L2` (moteur Consumption/Discovery + Command Pot réel + consommateurs synthétiques) et `L3..L8` (Identity, Projections READ, Rebuild, Supra API, NFR finaux, décommissionnement V2). |

---

## 9.6 Évaluation des tests et de la certification

1. **Pertinence du manifeste (`docs/v3/L2_Certification_Coverage_Manifest.md`)** :
   - Le manifeste comporte **380 lignes de preuve** couvrant exhaustivement les **253 identifiants normatifs** extraits de `SPEC-05`, `SPEC-03`, `SPEC-07`, `SPEC-08`, `SPEC-09`, `SPEC-10`, `L2_Decision_Register.md`, `L2_R1_R10_Normative_Consolidation.md` et `L2_Design_and_Verification_Plan.md`.
   - Il décompose explicitement les **87 sous-scénarios** des campagnes `C01` à `C08`, `E01` à `E03`, `O01` à `O02` et `P01`, et distingue proprement les **324 obligations exigibles en L2** des **56 obligations reportées** vers `L3`, `L4`, `L5`, `L6` et `L7` (chacune dotée d'une justification et d'un lot cible).
2. **Pertinence des 33 oracles (`docs/v3/L2_Independent_Test_Oracles.md`)** :
   - Le catalogue de 33 oracles (`OR-BOOT-01` à `OR-SCOPE-01`) est d'excellente tenue architecturale : chaque oracle exige une **vérification indépendante hors retour mémoire du composant testé** (états SQL persistés dans `consumption_slot`, `discovery_checkpoint`, `partition_ownership`, `v3_command`, `v3_command_result`, `v3_pot_event`, `v3_event_position`, `EXPLAIN (ANALYZE, BUFFERS)`, compteurs et traces corrélées).
3. **Pertinence et limites de `docs/v3/check_l2_manifest.py`** :
   - L'exécution de `python3 docs/v3/check_l2_manifest.py` sur l'état `39aecafd8eb2b43ce56a3c23cb4f10a0b4babfa4` confirme la validité syntaxique et structurelle :
     `OK: 253 normative identifiers, 380 proof rows, 324 L2 obligations, 56 reports, 33 distinct oracles, 783 oracle references, 0 orphan catalog oracles, 0 rows without oracle, 87 subscenario rows, 584 relative links, 49 anchors`.
   - Toutefois, comme détaillé dans **`V3-GEM-AUD-005`**, `check_l2_manifest.py` est aujourd'hui un **vérificateur syntaxique et référentiel de `P0/G0`**, pas encore un **vérificateur d'exécution de `G1/G2/G3`** : il accepte la chaîne générique `"OR-... ; cas <ID> ; paramètres et injection : colonne Stimulus"` sur 323 des 324 lignes L2 et la cible fictive unique `prévisionnelle : L2ManifestProof.l2m_*`.
4. **Risques de fausse certification à prévenir en `I6` (`G1/G2/G3`)** :
   - **Risque 1 (Tests de façade ou mocks)** : Une classe `L2ManifestProof` qui appellerait des doublures en mémoire au lieu de PostgreSQL réel (`Testcontainers`) validerait le script actuel tout en violant `SPEC-10`. Les campagnes `C01–C08`, `E01–E03` et `P01` doivent obligatoirement tourner contre PostgreSQL réel avec les vraies migrations Flyway WRITE et READ.
   - **Risque 2 (Concurrence séquentielle déguisée)** : Les tests `C01-I`, `C03-A..I`, `C04-D..F`, `C07-A..D` doivent utiliser des barrières d'entrelacement déterministes (`CountDownLatch` / `CyclicBarrier` / proxy SQL ou verrous transactionnels explicites) prouvant que les deux transactions/Workers se sont réellement disputé la ressource, et non exécutés l'un après l'autre.
5. **Frontière L2 vs lots différés (`L3–L7`)** :
   - La séparation entre les 324 preuves L2 (incluant `ExecutePotCommand` réel bout-en-bout et les consommateurs synthétiques de qualification pour les 5 autres familles) et les 56 preuves métier différées (`L3` Identity WRITE, `L4` Projections READ réelles, `L5` Rebuild Pot réel, `L6` Supra REST, `L7` NFR système complet) est saine, sans trou de couverture.

---

## 9.7 Actions correctives par échéance

### 1. `Avant G0` (clarifications documentaires ciblées dans `docs/v3/L2_Design_and_Verification_Plan.md`, sans nouvel arbitrage humain)
1. **`V3-GEM-AUD-001` (`MAJOR`)** : Dans `docs/v3/L2_Design_and_Verification_Plan.md` §7 Burnables, étape 4 (lignes 214–215), imposer l'ordre canonique de verrouillage **`consumption_slot -> source (`v3_command` / `v3_identity_request`)`** : exécuter d'abord `INSERT INTO consumption_slot ... ON CONFLICT (consumption_id, consumable_id) DO NOTHING`, puis, uniquement si la ligne a été insérée, revalider `status = 'PENDING'` sur la table source (`FOR SHARE`) et supprimer immédiatement le slot dans la même transaction si la source est devenue `TERMINAL`.
2. **`V3-GEM-AUD-002` (`MINOR`)** : Dans `docs/v3/L2_Design_and_Verification_Plan.md` §6 (lignes 187–189) et §7 Burnables étapes 2 et 5 (lignes 213, 216), ajouter l'index partiel `v3_command (request_id) WHERE status = 'PENDING'`, exiger que `NOT EXISTS (SELECT 1 FROM consumption_slot ...)` soit évalué en SQL avant `LIMIT B`, et préciser que le retour du curseur volatil à `MIN_UUID` (`R4-03`) s'évalue sur la taille du lot SQL brut (`< B` lignes retournées par SQL) afin qu'une partition saturée ne bloque jamais le curseur des autres partitions.
3. **`V3-GEM-AUD-003` (`MINOR`)** : Dans `docs/v3/L2_Design_and_Verification_Plan.md` §11 étapes `I1`/`I2` (lignes 309–327), inscrire explicitement la migration de la fonction trigger `v3_require_command_unit()` (`V2__command_execution.sql` L79) de `v3_command_slot` vers `consumption_slot`, en conservant son caractère `DEFERRABLE INITIALLY DEFERRED` évalué au commit physique (hors `IMMEDIATE_BUSINESS_CONSTRAINTS`).
4. **`V3-GEM-AUD-004` (`MINOR`)** : Dans `docs/v3/L2_Design_and_Verification_Plan.md` §8 (ligne 222), préciser que l'avancement de `K` avec `0` slot créé ne réinitialise le délai de polling au minimum que lorsque `C_dispo > 0` (si `C_dispo = 0` à cause de `softCap`, la partition applique le backoff borné).

### 2. `Avant l'étape d'implémentation concernée` (`I1` à `I5`)
- **En `I1`** (`V3-GEM-AUD-008`, `L2-A09`) : Aligner `ConsumptionSlot.java` et `PartitionHash.java` sur `MurmurHash3_x86_32(UTF-8, seed=0)` et le cycle `AVAILABLE / CLAIMED / QUARANTINED` avec suppression à la complétion ; ne vérifier `consumption_routing_contract` qu'au démarrage/enregistrement du Worker.

### 3. `Avant les campagnes de qualification` (`I6` / avant `G1` et `G2`)
- **En `I6`** (`V3-GEM-AUD-005`) : Remplacer dans `docs/v3/L2_Certification_Coverage_Manifest.md` les identifiants `prévisionnelle : L2ManifestProof.l2m_*` par les classes et méthodes de test réelles réparties dans leurs modules Maven, et enrichir `check_l2_manifest.py` pour vérifier l'existence des méthodes `@Test` et leur statut `PASS` dans les rapports JUnit XML Surefire/Failsafe.

### 4. `Avant les lots futurs` (`L3` à `L8`)
- **En entrée de `L3`** : Appliquer aux tables `v3_identity_request` (index partiel `WHERE status = 'PENDING'`, ordre de verrouillage `consumption_slot -> v3_identity_request`) et aux familles `IDENTITY` / `INVITATION` dans `v3_event_counter` exactement les mêmes invariants `R1`, `R2` et `R4` qu'en `L2`.
- **En entrée de `L3..L8`** (`V3-GEM-AUD-006`) : Produire un document de conception et de vérification unique par lot plutôt que 9 documents superposés.

### 5. `Amélioration non bloquante`
- **`V3-GEM-AUD-007`** : Corriger les numéros de sous-sections `### 24.1` à `### 24.6` dans `docs/v3/SPEC-10_Test_Certification.md` (avec mise à jour synchronisée des ancres du manifeste) et retirer le qualificatif `provisoire` des mentions `[SPEC-06 provisoire]` dans `SPEC-04` et `SPEC-06`.

---

## 9.8 Verdict sur G0

**Verdict retenu** : **`2. G0 est bloqué par les corrections identifiées`**

### Justification précise du verdict sur `G0`

1. **Tous les prérequis amont et arbitrages humains sont validés** :
   - Le prérequis de performance WRITE **`L1-F2` est formellement validé (`PASS`)** par le présent audit indépendant (code, index `V4` et mesures à `28` lignes constantes jusqu'à `10 000` versions).
   - Les 12 décisions d'architecture `L2-A01..A12` et les 10 consolidations normatives `R1..R10` sont techniquement saines et ne nécessitent **aucun nouvel arbitrage humain**.
   - Les 6 constats de l'audit précédent (`POSTAUD-001` à `POSTAUD-006`) sont clos au SHA `39aecafd8eb2b43ce56a3c23cb4f10a0b4babfa4`.
2. **Pourquoi `G0` requiert néanmoins une micro-passe documentaire préalable sur `docs/v3/L2_Design_and_Verification_Plan.md`** :
   - Conformément à `R7` (`R7-01` à `R7-03`), la porte `G0` fige le contrat technique d'implémentation de `I1..I5`.
   - Or, le constat **`V3-GEM-AUD-001` (`MAJOR`)** démontre que la formulation actuelle de l'étape 4 du Discovery burnable (`docs/v3/L2_Design_and_Verification_Plan.md` L214–215) induirait en `I4` un deadlock AB-BA (`40P01`) sous PostgreSQL si le développeur verrouillait `v3_command` avant `INSERT INTO consumption_slot ON CONFLICT DO NOTHING`.
   - Dès lors que les 4 corrections documentaires ciblées **`V3-GEM-AUD-001` à `V3-GEM-AUD-004`** (environ 15 lignes dans `docs/v3/L2_Design_and_Verification_Plan.md` §§6, 7, 8, 11, suivies d'une ré-exécution de `python3 docs/v3/check_l2_manifest.py`) auront été appliquées, **le dossier L2 pourra être immédiatement soumis à la validation humaine de `G0`**.
