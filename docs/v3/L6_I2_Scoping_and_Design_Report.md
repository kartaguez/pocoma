# Pocoma V3 — L6/I2 — Rapport de Cadrage et de Conception Consolidé
## Exploitation & Reconstruction READ — Version Finale Consolidée

**Date :** 6 novembre 2026
**Dépôt :** `kartaguez/pocoma`
**Branche :** `feat/v3-architecture`
**SHA de référence publié :** `6a1c04fa3f5b419b800754893e56790702a13a5b`
**Autorisation humaine :** `GO_L6_I2_SCOPING` suivi de l'arbitrage `DESIGN_ACCEPTED_SUBJECT_TO_CORRECTIONS`
**Mode de mission :** `DESIGN CORRECTION ONLY — NO IMPLEMENTATION — NO TEST EXECUTION`
**Statut L6/I1 :** `CLOSED — LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS`
**Statut L6/I2 :** `CONSOLIDATED — READY FOR IMPLEMENTATION APPROVAL — IMPLEMENTATION NOT AUTHORIZED`
**Verdict formel :** `L6_I2_DESIGN_READY_FOR_IMPLEMENTATION_APPROVAL`

---

## 1. Mandat, Contexte et Objectifs de la Consolidation

Le cadrage initial de l'incrément **L6/I2 (« Exploitation & reconstruction READ »)** a fait l'objet d'une revue critique d'architecture humaine dont le verdict formel a été rendu :
`DESIGN_ACCEPTED_SUBJECT_TO_CORRECTIONS`.

L'architecture générale proposée (reconstruction en place coordonnée, réutilisation intégrale des briques V3, isolation stricte de WRITE) a été validée. Néanmoins, l'arbitrage humain a exigé la levée d'ambiguïtés et la consolidation de plusieurs garanties critiques avant toute autorisation d'implémentation :
1. **Correction A — Barrière de maintenance distribuée :** Garantie multi-instances HTTP, autorité partagée dans PostgreSQL, drainage des requêtes en cours et comportement fail-closed.
2. **Correction B — Exclusion réelle des workers et fencing :** Analyse fine de `protectFinal(claim)`, neutralisation des commits résiduels par révocation DML temporaire, contrôle de `pg_stat_activity` et clarification du rôle de fallback exceptionnel de `pg_terminate_backend`.
3. **Correction C — Protocole DDL et Flyway exact :** Séquençage strict des migrations sous rôle administratif `pocoma`, purge douce du pool HikariCP (`softEvictConnections()`), étanchéité absolue de la base `pocoma_write`.
4. **Correction D — Oracle de convergence et prédicat formel READY :** Formalisation mathématique $\mathcal{P}_{\text{READY}}(B)$ reposant sur 4 conditions simultanées (28 checkpoints $\ge B$, 0 slots résiduels, 0 quarantaines, cohérence relationnelle interne).
5. **Correction E — Machine à états de reprise sur incident :** FSM à 4 états persistants (`READY`, `MAINTENANCE`, `REBUILDING`, `FAILED`) mémorisant le vecteur $B$ pour permettre la reprise sans double reset.
6. **Correction F — Budgets de qualification ciblés :** 3 gates étanches totalisant $\le 8$ minutes (Gate A: 60s, Gate B: 240s, Gate C: 180s), interdiction formelle du full reactor.
7. **Vérifications complémentaires & Nuance architecturale :** Dérivation dynamique du catalogue (28 checkpoints = 7 consumptions $\times$ 4 partitions), convergence mathématique sous WRITE continu, et justification du rejet de Blue/Green par complexité opérationnelle disproportionnée au regard de SPEC-10.

Cette mission a été réalisée sous la discipline stricte `DESIGN CORRECTION ONLY — NO IMPLEMENTATION — NO TEST EXECUTION` :
- **0** modification du code Java de production.
- **0** modification des tests unitaires ou d'intégration.
- **0** exécution de Maven, de Docker Compose ou de tests PostgreSQL.
- Seuls les livrables de cadrage et directeurs ont été consolidés.

Le document technique de référence consolidé est accessible dans [docs/v3/L6_I2_Technical_Implementation_Plan.md](L6_I2_Technical_Implementation_Plan.md).

---

## 2. Synthèse Exécutive Consolidée

1. **Barrière distribuée fail-closed :**
   La table technique d'autorité `v3_read.read_maintenance_state` centralise l'état du système de lecture (`READY`, `MAINTENANCE`, `REBUILDING`, `FAILED`). Tout nœud HTTP vérifie cet état (via cache TTL court de 500 ms rafraîchi de manière asynchrone ou synchrone) et bascule immédiatement en HTTP 503 Service Unavailable (`Retry-After: 30`) dès la transition vers `MAINTENANCE`. En cas d'inaccessibilité de la base ou de timeout, la barrière est **fail-closed** (HTTP 503 systématique).
2. **Double barrière de fencing contre les writers tardifs :**
   L'analyse approfondie de `JdbcConsumptionSlotStore#protectFinal` a révélé que la finalisation d'un slot ne vérifie que le claim et son expiration, et non l'appartenance de la partition au worker à l'instant exact du commit. Pour éliminer tout risque d'écriture fantôme sans dépendre exclusivement de signaux système, la procédure applique deux barrières :
   - *Barrière 1 (Coopérative) :* Arrêt gracieux des workers et attente de l'expiration naturelle des baux (`lease_duration = 3s` en profil local).
   - *Barrière 2 (Physique SQL) :* Révocation administrative des privilèges DML : `REVOKE INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA v3_read FROM pocoma_read;`. Tout commit tardif éventuel subit une exception SQL bloquante `permission denied for table ...`.
3. **Réinitialisation DDL Flyway propre et étanche :**
   Exécutée exclusivement sous la connexion d'administration `pocoma`, la réinitialisation applique `DROP SCHEMA v3_read CASCADE; CREATE SCHEMA v3_read AUTHORIZATION pocoma_read;`, puis rejoue les migrations Flyway canoniques V1..V7 et V10, suivies de `bootstrap-l2-routing.sql` et des attributions de droits `grant-*.sql`. Le pool applicatif HikariCP exécute `softEvictConnections()` pour renouveler les sessions sans interrompre le pool.
4. **Oracle de convergence mathématique $\mathcal{P}_{\text{READY}}(B)$ :**
   La fin du rattrapage et la réouverture du service ne reposent sur aucune temporisation arbitraire. Le prédicat mathématique $\mathcal{P}_{\text{READY}}(B)$ exige la satisfaction simultanée de :
   - $\forall c \in \mathcal{C}, \forall p \in \{0,1,2,3\}, \text{checkpoint}(c, p) \ge B_{F(c)}$ (soit 28 checkpoints vérifiés).
   - $\forall c \in \mathcal{C}, \forall p \in \{0,1,2,3\}, |\{s \in \text{slots}(c, p) \mid s.\text{pos} \le B_{F(c)}\}| = 0$.
   - Zéro slot en quarantaine $\le B$.
   - Cohérence relationnelle interne vérifiée sur 4 oracles (`CurrentPot`, `PotBalance`, `PotVisibility`, `CurrentBinding`).
5. **Résilience et reprise après crash (FSM 4 états) :**
   Si le processus de reconstruction est interrompu lors du rattrapage (état `REBUILDING`), le vecteur $B$ stocké de manière persistante permet au script de relancer immédiatement les moteurs de consommation et de reprendre la surveillance de l'oracle de convergence sans détruire à nouveau le schéma.
6. **Qualification compacte $\le 8$ minutes :**
   Trois gates locales ciblées (Gate A: 60s, Gate B: 240s, Gate C: 180s) garantissant la conformité intégrale aux 11 scénarios normatifs SPEC-10 RCV01-A à RCV01-N, sans aucun full reactor ni altération du périmètre de vérification.

---

## 3. Matrice du Périmètre Normatif de L6/I2

| Exigence / Scénario | Source Normative | État dans le Socle | Prise en Compte Consolidée L6/I2 |
|---|---|---|---|
| **REC-01** (Reconstruction READ) | SPEC-10 §10, SPEC-05 §9 | 7 Consumptions READ créées en L4/L5 | **OUI** : Procédure orchestrée réutilisant 100% des briques existantes. |
| **RCV01-A** (Reset complet) | SPEC-10 §10 | Migrations DDL READ V1..V7, V10 | **OUI** : Drop/Create schema `v3_read` + rejeu Flyway + bootstrap routing. |
| **RCV01-B** (Barrière writer tardif) | SPEC-10 §10 | `partition_ownership`, `consumption_slot` | **OUI** : Fencing à deux barrières (éviction + révocation SQL DML). |
| **RCV01-C** (Indisponibilité 503) | SPEC-10 §10 | Façade READ Web | **OUI** : Barrière `read_maintenance_state` fail-closed (503 automatique). |
| **RCV01-D** (Rattrapage 5 séquences) | SPEC-10 §10 | `v3_event_counter`, `v3_event_position` | **OUI** : Capture atomique du vecteur $B$, rattrapage checkpoints 0 à $B$. |
| **RCV01-E** (3 Consumptions Pot) | SPEC-10 §10 | `MaterializePotVersion/Balance/Vis` | **OUI** : Convergence synchronisée des 3 projections sur chaque Pot. |
| **RCV01-F** (Convergence multi-flux) | SPEC-10 §10 | Consumptions Invitation & Binding | **OUI** : Ordonnancement respecté sous interleavings concurrents réels. |
| **RCV01-G** (Reprise sur panne R4) | SPEC-10 §10 | Slots et checkpoints durables | **OUI** : FSM avec persistance de $B$ ; reprise de rattrapage sans reset. |
| **RCV01-H** (Refus restauration partielle)| SPEC-10 §10 | Gardes relationnelles SQL | **OUI** : Rejet de toute réinitialisation partielle ; reset global atomique. |
| **RCV01-I** (Purge slots orphelins) | SPEC-10 §10 | `consumption_slot` | **OUI** : Recréation de table vierge éliminant tout slot résiduel. |
| **RCV01-L** (WRITE continu R5) | SPEC-10 §10 | Isolation physique des bases | **OUI** : WRITE continue sans interruption ; rattrapage des événements $> B$. |
| **RCV01-M** (Consultation après READY) | SPEC-10 §10 | Façades READ et tokens EUI | **OUI** : Réouverture nominale en 200 OK dès validation de $\mathcal{P}_{\text{READY}}(B)$. |
| **RCV01-N** (Concordance des oracles) | SPEC-10 §10 | Oracles de validation L1–L5 | **OUI** : Validation d'équivalence stricte état reconstruit vs WRITE. |
| **Option 2 (Blue/Green)** | Spécification d'étude | Schémas PostgreSQL | **ÉCARTÉE** : Complexité opérationnelle injustifiée sous SPEC-10. |

---

## 4. Matrice Exhaustive des Constats et Décisions de Consolidation

La revue critique d'architecture a donné lieu à 14 constats techniques majeurs et décisions associées :

| Réf. Constat | Thématique | Analyse Technique & Risque Identifié | Décision & Solution Consolidée |
|---|---|---|---|
| **CST-I2-01** | Barrière multi-instances | Risque de faux 404 si une instance HTTP n'est pas informée du début du rebuild. | Autorité partagée dans PostgreSQL (`v3_read.read_maintenance_state`), cache local court (500 ms) et bascule fail-closed (503 si DB indisponible). |
| **CST-I2-02** | Drainage HTTP | Une requête READ engagée avant la bascule pourrait échouer en cours d'exécution lors du DROP TABLE. | Temporisation de drainage $T_{drain} = 3$ s (supérieure au timeout de transaction Spring de 2 s) avant toute opération DDL. |
| **CST-I2-03** | Fencing applicatif incomplet | `protectFinal` valide le `claim_token` mais ne vérifie pas la possession de partition au commit. | Complément par neutralisation SQL physique : `REVOKE DML FROM pocoma_read` rendant tout commit tardif impossible. |
| **CST-I2-04** | Fallback `pg_terminate_backend` | L'usage systématique de `pg_terminate_backend` peut masquer des défauts d'arrêt gracieux. | Relégué au rang de fallback d'urgence actionné uniquement si des sessions actives persistent après le délai de grâce de 3 s. |
| **CST-I2-05** | Permissions DDL | L'utilisateur `pocoma_read` ne dispose pas (et ne doit pas disposer) des droits de `DROP/CREATE SCHEMA`. | Exécution des étapes DDL sous le rôle d'administration `pocoma`, avec réattribution immédiate des grants nominaux. |
| **CST-I2-06** | Invalidation HikariCP | La recréation des tables peut invalider les prepared statements en cache (`cached plan must not change result type`). | Appel de `softEvictConnections()` sur le pool HikariCP lors de la transition vers `MAINTENANCE` et vers `READY`. |
| **CST-I2-07** | Répétabilité Flyway | Les migrations historiques doivent s'appliquer sur un schéma totalement vierge. | Nettoyage complet : `DROP SCHEMA v3_read CASCADE; CREATE SCHEMA v3_read;` avant invocation Flyway standard. |
| **CST-I2-08** | Dérivation du catalogue | Un codage en dur du nombre de checkpoints (28) introduit un risque de régression silencieuse. | Dérivation dynamique : $N_{cp} = |\mathcal{C}| \times |\mathcal{P}| = 7 \times 4 = 28$, vérifiée par assertion stricte. |
| **CST-I2-09** | Quarantaine et READY | Un slot en erreur résiduelle ou quarantaine pourrait fausser la remise en service. | Le prédicat $\mathcal{P}_{\text{READY}}(B)$ exige inconditionnellement zéro slot en quarantaine pour les événements $\le B$. |
| **CST-I2-10** | Reprise après crash (FSM) | Si le script est tué pendant le rattrapage, un re-drop schema ferait perdre le travail déjà accompli. | Persistance de l'état `REBUILDING` et du vecteur $B$ : la commande `--resume` reprend le suivi sans nouveau drop. |
| **CST-I2-11** | Étancheité absolue WRITE | Risque de pollution accidentelle de la base WRITE lors d'un script d'exploitation. | L'accès à `pocoma_write` est strictement borné à une connexion en lecture seule (`ReadOnlyDataSource` / privilèges `SELECT`). |
| **CST-I2-12** | Budget de qualification | Des tests de charge ou des full reactors lors du pipeline I2 dépasseraient les budgets d'exécution. | 3 gates unitaires/intégration strictement ciblées pour un total $\le 8$ min (inférieur au plafond contractuel de 9 min). |
| **CST-I2-13** | Détection de famine / blocage | Si un worker plante pendant le rebuild, le script pourrait attendre indéfiniment. | Watchdog avec timeout de progrès : si aucun checkpoint n'avance pendant 30 s en état `REBUILDING`, transition vers `FAILED`. |
| **CST-I2-14** | Nuance Blue/Green | Le rejet de Blue/Green ne doit pas être justifié par une impossibilité PostgreSQL mais par la simplicité. | Justification formelle : complexité opérationnelle (double schéma, routage, sync) disproportionnée au regard de SPEC-10. |

---

## 5. Synthèse des Six Corrections Majeures

### Correction A — Barrière de Maintenance Distribuée
- **Autorité et Schéma :** Table unique `v3_read.read_maintenance_state` possédant une seule ligne (`id = 1`). Propriétaire : `pocoma`, lecture accordée à `pocoma_read`.
- **Transitions Atomiques :** Effectuées par `UPDATE v3_read.read_maintenance_state SET state = :newState, target_prefix = :prefix, updated_at = clock_timestamp() WHERE id = 1;`.
- **Garantie Multi-Instances :** Les instances HTTP interrogent l'état via un cache en mémoire d'une durée de vie maximale de 500 ms.
- **Fail-Closed :** Si la base de données est injoignable, si une erreur SQL survient ou si la ligne d'état est verrouillée/absente, le filtre HTTP intercepte la requête et renvoie inconditionnellement HTTP 503 (`Retry-After: 30`).
- **Drainage :** Après le passage de l'état à `MAINTENANCE`, le script applique une temporisation de grâce $T_{drain} = 3$ secondes. Comme le timeout des transactions HTTP Spring est fixé à 2 secondes (`@Transactional(timeout = 2)`), toute requête cliente engagée avant la bascule s'achève naturellement avant l'amorce des opérations DDL destructives.

### Correction B — Exclusion Réelle des Workers et Fencing Multi-Couches
- **Vérification de `protectFinal` :** L'analyse de `JdbcConsumptionSlotStore.java` confirme que la requête SQL de protection :
  ```sql
  UPDATE v3_read.consumption_slot
  SET state = 'PROCESSED', claimed_until = NULL, finalized_at = clock_timestamp()
  WHERE slot_id = ? AND claim_token = ? AND state = 'AVAILABLE' AND claimed_until > clock_timestamp();
  ```
  n'effectue aucune jointure sur `partition_ownership`. Un worker dont le bail de partition a expiré mais dont le claim sur le slot court encore pourrait théoriquement finaliser son écriture s'il se réveille avant l'échéance du claim.
- **Double Barrière de Neutralisation :**
  1. *Étape 1 (Applicative) :* Envoi du signal d'arrêt gracieux aux workers READ et expiration des baux de partition.
  2. *Étape 2 (Physique SQL) :* Exécution administrative :
     ```sql
     REVOKE INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA v3_read FROM pocoma_read;
     ```
     Cette barrière garantit qu'aucun worker attardé ou zombie ne peut committer de données dans les tables de lecture.
- **Rôle de `pg_terminate_backend` :** Après $T_{drain} = 3$ s, le script interroge `pg_stat_activity`. Si et seulement si des sessions applicatives `pocoma_read` restent ouvertes et bloquées, `pg_terminate_backend(pid)` est invoqué comme mesure exceptionnelle de sécurité.

### Correction C — Protocole DDL et Flyway de Réinitialisation
- **Connexion Administrative :** Utilisation exclusive du rôle administrateur `pocoma`.
- **Séquence Déterministe :**
  1. `DROP SCHEMA IF EXISTS v3_read CASCADE;`
  2. `CREATE SCHEMA v3_read AUTHORIZATION pocoma_read;`
  3. Rejeu des migrations Flyway Java canoniques V1..V7 et V10 sur le schéma `v3_read`.
  4. Exécution du script de routage : `bootstrap-l2-routing.sql`.
  5. Réattribution des privilèges opérationnels : exécution des scripts `grant-*.sql`.
  6. Éviction douce des connexions : `softEvictConnections()` sur les pools HikariCP des applications connectées.
- **Isolation Absolue de WRITE :** Le script n'utilise pour WRITE qu'un pool distinct configuré en lecture seule (`readOnly=true`), interdisant tout ordre DDL ou DML sur `v3_write`.

### Correction D — Oracle de Convergence et Prédicat Formel READY
Le retour en disponibilité nominale (état `READY`, code HTTP 200) est conditionné par la validation stricte du prédicat mathématique $\mathcal{P}_{\text{READY}}(B)$ :
$$\mathcal{P}_{\text{READY}}(B) = \mathcal{C}_1 \wedge \mathcal{C}_2 \wedge \mathcal{C}_3 \wedge \mathcal{C}_4$$

1. **$\mathcal{C}_1$ (Progression des Checkpoints) :**
   $$\forall c \in \mathcal{C}, \forall p \in \{0,1,2,3\}, \quad \text{checkpoint}(c, p) \ge B_{F(c)}$$
   (28 checkpoints au total pour les 7 consommations sur les 4 partitions).
2. **$\mathcal{C}_2$ (Résorption Intégrale des Slots $\le B$) :**
   $$\forall c \in \mathcal{C}, \forall p \in \{0,1,2,3\}, \quad |\{s \in \text{slots}(c, p) \mid s.\text{pos} \le B_{F(c)}\}| = 0$$
3. **$\mathcal{C}_3$ (Absence de Quarantaine $\le B$) :**
   $$|\{s \in \text{slots} \mid s.\text{state} = \text{'QUARANTINED'} \wedge s.\text{pos} \le B_{F(c)}\}| = 0$$
4. **$\mathcal{C}_4$ (Cohérence Relationnelle Interne) :**
   - $\forall P, \text{CurrentPot}(P) \implies \text{PotVersion}(P, \text{current\_version})$ existe.
   - $\forall P, \text{Balance}(P, \text{current\_version})$ calculée et cohérente avec les opérations.
   - $\forall I, \text{CurrentBinding}(I)$ résolu vers son identité autoritative.
   - Absence d'orphelins ou d'incohérences structurelles.

### Correction E — Machine à États de Reprise sur Incident (FSM)
La machine d'exploitation persistance comprend 4 états distincts :
```
[READY] ──(start rebuild)──> [MAINTENANCE] ──(drop/flyway + store B)──> [REBUILDING]
   ▲                                                                         │
   │                                                                    (crash / kill)
   │                                                                         │
   │                                                                         ▼
   └───────────(oracles OK)──────────── [REBUILDING] <──(--resume)─── (process dead)
                                             │
                                      (stall timeout)
                                             │
                                             ▼
                                          [FAILED]
```
- Si le script ou l'infrastructure crash pendant `REBUILDING`, la commande `python3 scripts/operations/v3-rebuild-read.py --resume` lit le vecteur $B$ conservé dans `read_maintenance_state`, réactive la surveillance et attend l'oracle READY **sans relancer de DROP SCHEMA**.

### Correction F — Stratégie de Qualification Ciblée et Budgets
Conformément à la directive stricte interdisant tout full reactor :
1. **Gate A — Contrats Ciblés & Fail-Closed (Budget : 60 s) :**
   - Commande : `./mvnw -pl composition-runtime-spring -am -Dtest=ReadMaintenanceBarrierTest,ReadinessOracleTest test`
   - Vérifie le filtre 503, la bascule atomique d'état et le calcul du prédicat.
2. **Gate B — Qualification Déterministe PostgreSQL RCV01 (Budget : 240 s) :**
   - Commande : `./mvnw -pl runtime-task-consumption-worker -am -Dtest=V3ReadReconstructionPostgresTest test`
   - Exécute les 11 scénarios normatifs SPEC-10 RCV01-A à RCV01-N sur base réelle.
3. **Gate C — Validation Script CLI Locale (Budget : 180 s) :**
   - Commande : `bash scripts/operations/verify-l6-i2-rebuild.sh`
   - Valide le comportement de bout en bout du script CLI Python, la résilience aux pannes (`--resume`), et les métriques Prometheus.
- **Budget total cumulé :** $\le 480$ secondes (8 minutes), parfaitement contenu dans l'objectif de 9 minutes.

---

## 6. Vérifications Complémentaires et Nuance Architecturale

### Vérification Complémentaire 1 — Dérivation Dynamique du Catalogue
Le catalogue des 7 consommations READ est dynamiquement dérivé de `V3ReadParticipants` :
1. `POT_VERSION` $\rightarrow$ Famille `POT`
2. `POT_BALANCE` $\rightarrow$ Famille `POT`
3. `POT_VISIBILITY` $\rightarrow$ Famille `POT`
4. `CURRENT_BINDING` $\rightarrow$ Famille `IDENTITY`
5. `COMMAND_RESULT` $\rightarrow$ Famille `COMMAND_COMPLETION`
6. `IDENTITY_REQUEST_RESULT` $\rightarrow$ Famille `IDENTITY_COMPLETION`
7. `INVITATION` $\rightarrow$ Famille `INVITATION`

Pour les 4 partitions configurées (0 à 3), l'oracle valide exactement $7 \times 4 = 28$ checkpoints distincts.

### Vérification Complémentaire 2 — Performance et Convergence sous WRITE Continu
Puisque le préfixe $B$ est figé au début du rattrapage, le nombre d'événements à traiter pour atteindre l'oracle de convergence est **strictement fini**. Même sous une cadence d'écriture WRITE soutenue (ex. 100 cmd/s), le rattrapage converge mathématiquement. Les événements postérieurs à $B$ continuent d'être absorbés au fil de l'eau une fois la base remise en service nominal (`READY`).

### Nuance Architecturale — Justification Formelle du Rejet de Blue/Green
L'Option 2 (Blue/Green via schéma temporaire puis bascule) a été analysée avec rigueur. Elle n'est pas techniquement impossible dans PostgreSQL. Toutefois, elle impose :
- Une gestion complexe de renommage atomique DDL sous connexions actives.
- Une réinitialisation brutale du cache de plans préparés dans HikariCP.
- Le maintien temporaire d'un doublement des schémas et des baux de consommation.
Puisque la norme **SPEC-10 §10 accepte formellement une indisponibilité totale temporaire de READ** pour les besoins d'exploitation exceptionnelle, l'Option 1 (Reconstruction en place coordonnée avec barrière 503) offre les mêmes garanties de consistance pour un coût opérationnel, un risque d'implémentation et une complexité de maintenance considérablement plus faibles.

---

## 7. Réponses Consolidées aux Huit Questions Critères (Section 15)

1. **Qu'est-ce qui est reconstruit ?**
   L'intégralité du schéma `v3_read` : les 9 tables de projections réelles, ainsi que les tables techniques de consommation (`discovery_checkpoint`, `consumption_slot`, `partition_ownership`, `read_maintenance_state`).
2. **À partir de quoi ?**
   Exclusivement à partir des 5 flux d'événements immuables conservés dans `pocoma_write` (`v3_event_counter`, `v3_event_position`) et des tables d'état d'origine.
3. **Comment ?**
   En réutilisant sans modification les moteurs `DiscoveryEngine` et `ConsumptionEngine`, pilotés par les 7 participants de matérialisation de `V3ReadParticipants`.
4. **Avec quelles garanties ?**
   Zéro impact sur WRITE (étanchéité absolue et connexions en lecture seule) ; barrière d'indisponibilité 503 fail-closed ; double barrière de neutralisation des anciens écrivains (éviction + révocation SQL DML) ; résilience au crash via FSM persistante.
5. **Comment l'exploiter ?**
   Via un script CLI Python standardisé `scripts/operations/v3-rebuild-read.py` avec confirmation obligatoire `--confirm-destructive=RESET_READ_DATA` et support du mode reprise `--resume`.
6. **Comment savoir que cela fonctionne ?**
   Par la satisfaction prouvée du prédicat formel $\mathcal{P}_{\text{READY}}(B)$ sur les 28 checkpoints et 0 slots, les contrôles d'intégrité relationnelle interne, et les métriques Prometheus exposées sur le dashboard Grafana.
7. **Quel coût ?**
   Zéro régression architecturale, complexité d'implémentation minimale, et un budget de qualification compact $\le 8$ minutes sans full reactor.
8. **Qu'est-ce qui reste hors périmètre ?**
   Les benchmarks de charge et SLO (SPEC-09) attribués à **L6/I3** ; la convergence de modèle V2/V3 attribuée à **L7** ; le packaging multi-nœuds de production attribué à **L8**.

---

## 8. Verdict Formel de Cadrage Consolidé

```
================================================================================
POCOMA V3 — LOT L6 / INCRÉMENT I2 : VERDICT FORMEL DE CONSOLIDATION
Statut officiel : L6_I2_DESIGN_READY_FOR_IMPLEMENTATION_APPROVAL
Dépôt : kartaguez/pocoma
Branche : feat/v3-architecture
SHA de référence : 6a1c04fa3f5b419b800754893e56790702a13a5b
Mandat d'exécution : DESIGN CORRECTION ONLY — NO IMPLEMENTATION — NO TEST EXECUTION

Plan d'implémentation : docs/v3/L6_I2_Technical_Implementation_Plan.md (Consolidé)
Rapport de cadrage : docs/v3/L6_I2_Scoping_and_Design_Report.md (Consolidé)
Architecture validée : Reconstruction en place coordonnée sous barrière exclusive 503
Fencing writer tardif : Double barrière (éviction applicative + révocation DML PostgreSQL)
Prédicat de remise en service : P_READY(B) sur 28 checkpoints et 0 slots résiduels
Résilience aux pannes : FSM à 4 états persistants avec reprise sans double reset
Budget de qualification : <= 8 minutes (Gates A, B, C sans full reactor)

Statut d'implémentation : IMPLEMENTATION NOT AUTHORIZED
Prochain acte attendu : Arbitrage humain et décision GO_L6_I2_IMPLEMENTATION
================================================================================
```
