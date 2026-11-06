# Pocoma V3 — L6/I2 — Rapport de Cadrage et de Conception
## Exploitation & Reconstruction READ

**Date :** 6 novembre 2026
**Dépôt :** `kartaguez/pocoma`
**Branche :** `feat/v3-architecture`
**SHA de référence :** `38478787edfb7ee3fecf18739165f96faf2b8727`
**Autorisation humaine :** `GO_L6_I2_SCOPING`
**Statut L6/I1 :** `CLOSED — LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS`
**Statut L6/I2 :** `SCOPING COMPLETE — DESIGN READY FOR HUMAN REVIEW — IMPLEMENTATION NOT AUTHORIZED`
**Verdict formel :** `L6_I2_DESIGN_READY_FOR_HUMAN_REVIEW`

---

## 1. Mandat et Contexte de Cadrage

Conformément à l'instruction humaine `GO_L6_I2_SCOPING`, ce rapport consigne les résultats de l'étude statique approfondie, du cadrage architectural et de la conception technique de l'incrément **L6/I2 (« Exploitation & reconstruction READ »)** de Pocoma V3.

Cette mission a été exécutée selon la discipline stricte `ANALYSIS FIRST — NO IMPLEMENTATION` :
- Aucun code applicatif de production n'a été altéré.
- Aucun test Maven ni aucun composant n'a été exécuté.
- Aucun service n'a été démarré ou arrêté.
- Aucune base de données n'a été modifiée.
- L'ensemble des conclusions repose sur une analyse statique et contradictoire des sources Java, des schémas SQL, des scripts d'exploitation et des preuves historiques de L1 à L5 et de L6/I1.

Le livrable technique compagnon complet est disponible dans [docs/v3/L6_I2_Technical_Implementation_Plan.md](L6_I2_Technical_Implementation_Plan.md).

---

## 2. Synthèse Exécutive

1. **Objectif opérationnel clair et borné :**
   L'incrément L6/I2 fournit une procédure déterministe, sûre et entièrement automatisable de reconstruction intégrale de la base de lecture READ (`pocoma_read` / schéma `v3_read`) à partir des faits autoritatifs contenus dans les **cinq séquences événementielles immuables WRITE** (`v3_write`), sans aucune indisponibilité du service d'admission et d'écriture WRITE.
2. **Réutilisation maximale du socle V3 :**
   Aucun second moteur de reconstruction ou d'orchestration n'est inventé. La solution réutilise à 100% le moteur de découverte `DiscoveryEngine`, le moteur de consommation `ConsumptionEngine`, les sept participants de matérialisation existants (`V3ReadParticipants`), leurs baux et identifiants stables (`consumptionId`).
3. **Architecture de référence ratifiée :**
   La **reconstruction en place coordonnée avec barrière de maintenance exclusive** est retenue. Pendant la reconstruction, l'espace READ est placé sous indisponibilité totale temporaire (HTTP 503 explicite, prévenant tout faux 404), les anciens écrivains sont physiquement neutralisés, le schéma `v3_read` est atomiquement réinitialisé via les DDL Flyway canoniques, et le rattrapage est exécuté jusqu'à un **vecteur de préfixes sûrs $B$**.
4. **Oracle de remise en service READY formel :**
   La base reconstruite n'est déclarée `READY` que lorsque les 7 Consumptions READ ont dépassé les préfixes $B$ sur leurs 4 partitions respectives et que tous les slots enrôlés $\le B$ sont totalement résorbés.
5. **Observabilité sans redondance :**
   Enrichissement de la stack locale L6/I1 par 4 métriques Micrometer à cardinalité bornée (état du rebuild, progression des checkpoints, événements et slots restants, détection de blocage) et ajout d'une rangée dédiée sur le dashboard Grafana existant.
6. **Qualification strictement bornée :**
   Trois gates étanches (Gate A contextuelle 120s, Gate B PostgreSQL RCV01 240s, Gate C locale 180s) garantissant une qualification complète en moins de 9 minutes, sans full reactor.

---

## 3. Matrice du Périmètre Réel de L6/I2

Le périmètre de L6/I2 est strictement articulé autour des obligations normatives issues de [SPEC-10 §10](SPEC-10_Test_Certification.md), [SPEC-05 §9](SPEC-05_Consumption_Discovery.md) et [V3 Master Plan](V3_Implementation_Master_Plan.md) :

| Exigence / Sujet | Source Normative | Déjà Disponible | Inclus dans I2 | Hors Périmètre I2 |
|---|---|---|:---:|:---:|
| **REC-01 (Reconstruction READ)** | SPEC-10 §10, SPEC-05 §9 | 7 Consumptions READ, stores JDBC | **OUI** (Procédure complète) | Rejeu sur `consumptionId` neuf |
| **RCV01-A (Reset complet)** | SPEC-10 §10 | Migrations DDL READ V1..V7, V10 | **OUI** (Recréation vierge) | — |
| **RCV01-B (Barrière writer tardif)** | SPEC-10 §10 | Table `partition_ownership` | **OUI** (Neutralisation physique)| — |
| **RCV01-C (Indisponibilité 503)** | SPEC-10 §10 | Statut `Unavailable` dans Façade | **OUI** (Barrière d'API) | — |
| **RCV01-D (Rattrapage 5 séquences)** | SPEC-10 §10 | Tables d'événements WRITE | **OUI** (Checkpoints 0 à $B$) | — |
| **RCV01-E (3 Consumptions Pot)** | SPEC-10 §10 | `MaterializePotVersion/Balance/Vis`| **OUI** (Vérification collective)| — |
| **RCV01-F (Convergence multi-flux)** | SPEC-10 §10 | Participants Invitation/Binding | **OUI** (Interleavings réels) | — |
| **RCV01-G (Reprise sur panne R4)** | SPEC-10 §10 | Slots et checkpoints durables | **OUI** (Reprise après crash) | — |
| **RCV01-H (Refus restauration partielle)**| SPEC-10 §10 | Gardes de slots/checkpoints | **OUI** (Oracle d'intégrité) | — |
| **RCV01-I (Purge slots orphelins)** | SPEC-10 §10 | Table `consumption_slot` | **OUI** (Élimination garantie) | — |
| **RCV01-L (WRITE continu R5)** | SPEC-10 §10 | Isolation physique des bases | **OUI** (Absorbtion post-$B$) | — |
| **RCV01-M (Consultation après READY)** | SPEC-10 §10 | Façade READ et auth EUI | **OUI** (Vérification 200 OK) | — |
| **RCV01-N (Concordance des oracles)** | SPEC-10 §10 | Oracles métier L1–L5 | **OUI** (Équivalence exacte) | — |
| **Reconstruction Ciblée (R3)** | — | Stores par table | **NON** (Rejet architectural) | Différé / Rejeté |
| **Benchmarks & SLO SPEC-09** | SPEC-09, SPEC-10 §12| Scripts k6 historiques | **NON** | Attribué à **L6/I3** |
| **Convergence V2/V3** | Canon §15 | Code V2 existant | **NON** | Attribué à **L7** |
| **Distribution & Sécurité Prod** | Canon §15 | Docker Compose actuel | **NON** | Attribué à **L8** |

---

## 4. Bilan de l'Audit Statique de l'Existant

### 4.1 Projections READ et Contraintes SQL
L'inspection détaillée de `infra-persistence-read-jdbc` a mis en lumière un élément capital d'architecture :
1. **Les 9 projections logiques sont pleinement matérialisées** : `PotVersion`, `CurrentPot`, `SubobjectVersion`, `PotBalance`, `PotVisibilityState`, `CurrentUserPotList`, `CurrentBinding`, `CommandResult`, `IdentityRequestResult` et `CurrentInvitation`.
2. **Protection d'immutabilité par triggers `ENABLE ALWAYS`** :
   Les tables de projection historiques (`read_subobject_version`, `read_pot_version`, `read_pot_balance`, `read_command_result`, `read_identity_request_result`) disposent de triggers PostgreSQL interdisant inconditionnellement toute instruction `UPDATE`, `DELETE` ou `TRUNCATE`.
   *Conséquence directe :* Une réinitialisation ne peut pas reposer sur un `DELETE FROM ...` naïf. Elle doit soit s'effectuer par drop/recréation du schéma via Flyway, soit nécessiter une session administrative d'effacement avec désactivation contrôlée des triggers.

### 4.2 Les Sept Consumptions READ Existantes
La classe `com.kartaguez.pocoma.runtime.consumption.v3.V3ReadParticipants` fournit déjà la composition canonique des sept participants nécessaires, directement raccordés à leurs sources WRITE en lecture seule et à leurs stores READ :
1. `POT_VERSION` (`3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a`) : `MaterializePotVersion`
2. `POT_BALANCE` (`190a93c6-b8af-5ac7-8001-72e2ec53c2df`) : `MaterializePotBalance`
3. `POT_VISIBILITY` (`b4d04cce-b5da-5d1b-9698-5ff9af9a46fa`) : `MaterializePotVisibility`
4. `CURRENT_BINDING` (`7b8d2838-bf7a-5117-868a-e5c14b4c0221`) : `MaterializeCurrentBinding`
5. `COMMAND_RESULT` (`63664303-d33e-5d42-8cce-c4dae8023311`) : `MaterializeCommandResult`
6. `IDENTITY_REQUEST_RESULT` (`1d8ff246-358f-5ddd-a7f5-416efcfd7445`) : `MaterializeIdentityRequestResult`
7. `INVITATION` (`924cd9e3-435d-5804-9d1e-c6aecadb9ab4`) : `MaterializeInvitation`

Chaque participant est unitaire, idempotent et dépourvu d'état mémoire partagé. Aucun de ces participants ne lit une projection READ pour matérialiser sa propre projection.

### 4.3 Les Cinq Séquences Événementielles WRITE
L'audit de la base `pocoma_write` (`v3_write`) confirme la présence des compteurs et séquences denses :
- `v3_event_counter` : table contenant la position courante maximale pour les cinq familles : `'POT'`, `'COMMAND_COMPLETION'`, `'IDENTITY'`, `'IDENTITY_COMPLETION'`, `'INVITATION'`.
- `v3_event_position` : table associant `(family, position)` à l'identifiant exact `event_id`.
La capture du préfixe $B$ est donc une opération SQL instantanée et atomique : `SELECT family, position FROM v3_write.v3_event_counter`.

---

## 5. Analyse Comparative des Architectures de Reconstruction

L'étude comparative a porté sur les quatre architectures prescrites :

1. **Option 1 — Reconstruction en place coordonnée (RETENUE) :**
   Bascule de READ en 503, arrêt des workers READ, réinitialisation administrative du schéma `v3_read`, capture de $B$, rattrapage via les moteurs existants, validation de l'oracle READY, réouverture en 200 OK.
   *Avantages :* Simplicité maximale, conformité textuelle à SPEC-05 et SPEC-10, aucun impact sur les pools HikariCP, reprise sur crash triviale.
2. **Option 2 — Schéma séparé (Blue/Green) puis renommage (ÉCARTÉE) :**
   Construction en tâche de fond sur `v3_read_stage`, puis bascule atomique par renommage DDL (`ALTER SCHEMA ... RENAME TO ...`).
   *Motif de rejet :* Le renommage de tables/schémas sous PostgreSQL invalide les prepared statements en cache dans HikariCP (`cached plan must not change result type`) et provoque des erreurs de connexion. De plus, SPEC-10 accepte explicitement l'indisponibilité totale temporaire de READ, rendant le surcoût de Blue/Green injustifié.
3. **Option 3 — Reconstruction ciblée par projection (ÉCARTÉE) :**
   Reconstruction isolée d'une seule famille (ex. `PotBalance`).
   *Motif de rejet :* Entraîne des incohérences relationnelles temporaires (un Pot à la version 10 sans son solde) et contredit l'exigence de SPEC-10 §10 interdisant les remises à zéro partielles.
4. **Option 4 — Rejeu sans reset (ÉCARTÉE) :**
   *Motif de rejet :* Violations systématiques de contraintes d'unicité et de triggers d'immutabilité dès la première réinsertion.

---

## 6. Architecture de Référence Retenue

L'architecture retenue est formalisée en 8 étapes rigoureusement séquencées :
1. **Verrou de maintenance :** `read_readiness_state = 'MAINTENANCE'` $\rightarrow$ façade HTTP READ répond 503.
2. **Neutralisation physique :** Arrêt du worker READ, confirmation de la libération des baux `partition_ownership` et fermeture forcée des connexions SQL résiduelles via `pg_terminate_backend`.
3. **Reset DDL :** Recréation du schéma `v3_read` via les migrations Flyway canoniques et scripts de routing (28 checkpoints réinitialisés à 0, 0 slots résiduels).
4. **Capture du préfixe :** Capture du vecteur $B = (B_{pot}, B_{inv}, B_{id}, B_{cmd}, B_{id\_comp})$ depuis `v3_write.v3_event_counter`.
5. **Rattrapage autonome :** Démarrage des 7 Consumptions READ réutilisant `DiscoveryEngine` et `ConsumptionEngine`.
6. **Surveillance :** Monitoring Prometheus des jauges de retard et détection de blocage.
7. **Oracle READY :** Constat que $\forall f, \min_{p}(checkpoint(f,p)) \ge B_f$ et $\text{COUNT}(slots \le B_f) = 0$, validé par les oracles de cohérence interne.
8. **Bascule nominale :** `read_readiness_state = 'READY'` $\rightarrow$ reprise du trafic client en 200 OK. Les événements $> B$ continuent d'être absorbés au fil de l'eau.

---

## 7. Modèle Opérationnel et Outillage

En stricte conformité avec le principe de simplicité (Section 2.2), l'outillage d'exploitation est concentré dans un unique script CLI Python :
`scripts/operations/v3-rebuild-read.py`

### Caractéristiques de Sécurité :
- **Prévol strict :** Vérifie que la base WRITE est saine et accessible en lecture seule, et que la base cible est formellement `pocoma_read`.
- **Protection contre les destructions accidentelles :** Refus absolu d'exécuter une opération destructive sans l'argument explicite `--confirm-destructive=RESET_READ_DATA`.
- **Modes opératoires :** Supporte l'exécution automatique complète (`--rebuild`) et l'exécution pas-à-pas pour les besoins de diagnostic ou de qualification fine.

---

## 8. Observabilité de la Reconstruction

Pour répondre aux 9 questions critiques de l'exploitant sans surcharger Prometheus :
- `pocoma_v3_rebuild_status{state="idle|in_progress|ready|failed"}` : état du cycle.
- `pocoma_v3_rebuild_checkpoint{family="...", partition="0..3"}` : watermark courant.
- `pocoma_v3_rebuild_remaining_events{family="..."}` : distance par rapport à $B_f$.
- `pocoma_v3_rebuild_remaining_slots{family="..."}` : slots restant à traiter $\le B_f$.
- `pocoma_v3_rebuild_stalled{family="..."}` : jauge d'alerte de blocage.

Tableau de bord Grafana : enrichissement du dashboard officiel `pocoma-v3-observability.json` par 4 panneaux dédiés (Statut, Backlog, Checkpoints vs Borne B, Débit & Erreurs).

---

## 9. Plan de Qualification et Budgets

La qualification est découpée en **trois gates étanches** avec des plafonds de temps stricts :

| Gate | Intitulé / Commande | Périmètre & Scénarios | Plafond Budget |
|---|---|---|:---:|
| **Gate A** | Contrats Ciblés<br>`./mvnw -pl composition-runtime-spring -am test` | Indicateur maintenance 503, capture préfixe B, métriques | **120 s** |
| **Gate B** | Reconstruction PostgreSQL<br>`./mvnw -pl runtime-task-consumption-worker -am -Dtest=V3ReadReconstructionPostgresTest test` | 11 scénarios déterministes RCV01-A à RCV01-N sur PostgreSQL réel | **240 s** |
| **Gate C** | Exploitation Locale<br>`python3 scripts/operations/verify-l6-i2-rebuild.sh` | Validation CLI `v3-rebuild-read.py`, garde-fous `--confirm-destructive`, bascule 503 $\rightarrow$ 200, Prometheus | **180 s** |
| **TOTAL** | — | — | **$\le$ 9 min** |

**Règles de discipline :** Aucun rejeu automatique ; 1 diagnostic avant rejeu unique ; aucun full reactor ; aucune gate globale d'architecture (non requise car aucune frontière inter-modules n'est modifiée).

---

## 10. Réponses aux Huit Questions Critères de Qualité (Section 15)

- **Q1 — Qu'est-ce qui est reconstruit ?**
  Les neuf projections READ réelles : `PotVersion`, `CurrentPot`, `SubobjectVersion`, `PotBalance`, `PotVisibilityState`, `CurrentUserPotList`, `CurrentBinding`, `CommandResult`, `IdentityRequestResult` et `CurrentInvitation`, ainsi que les tables techniques de consommation (`discovery_checkpoint`, `consumption_slot`, `partition_ownership`).
- **Q2 — À partir de quoi ?**
  Les cinq séquences d'événements immuables denses conservées dans WRITE (`v3_event_counter` / `v3_event_position`) et l'état autoritatif exact à chaque version.
- **Q3 — Comment ?**
  Par composition des moteurs existants (`DiscoveryEngine`, `ConsumptionEngine`) et des sept participants READ de `V3ReadParticipants`, avec les mêmes identifiants stables (`consumptionId`).
- **Q4 — Avec quelles garanties ?**
  Immutabilité absolue de WRITE (accès `SELECT` exclusif), atomicité locale des commits par slot, monotonie des checkpoints, neutralisation des écrivains tardifs (RCV01-B), reprise déterministe sur crash (RCV01-G).
- **Q5 — Comment l'exploiter ?**
  Via le script CLI unifié `scripts/operations/v3-rebuild-read.py` avec confirmation de sécurité obligatoire `--confirm-destructive=RESET_READ_DATA`.
- **Q6 — Comment savoir que cela fonctionne ?**
  Par l'oracle formel de READY (checkpoints $\ge B$ et 0 slots $\le B$), les oracles de cohérence interne (équivalence avec WRITE), et les métriques Prometheus.
- **Q7 — Quel coût ?**
  Zéro verrou et charge négligeable sur WRITE ; transactions courtes par lot de 32..128 sur READ ; budget de qualification total inférieur à 9 minutes.
- **Q8 — Qu'est-ce qui reste hors périmètre ?**
  Les benchmarks de charge et SLO (SPEC-09) relèvent de L6/I3 ; la convergence V2/V3 relève de L7 ; la sécurisation de production relève de L8.

---

## 11. Décisions Humaines Soumises à Arbitrage

L'autorisation d'implémentation requiert la ratification formelle des quatre points suivants :
1. **Ratification de l'Architecture de Référence :** Approbation de la reconstruction en place coordonnée sous barrière exclusive et confirmation de l'abandon de la reconstruction ciblée (R3).
2. **Protocole de Réinitialisation Administrative :** Approbation de la recréation du schéma `v3_read` via Flyway sous rôle `pocoma` pour contourner proprement les triggers `ENABLE ALWAYS` sans affaiblir la sécurité.
3. **Barrière d'Indisponibilité 503 :** Approbation de l'indicateur de maintenance `read_readiness_state` interdisant les faux 404 pendant la maintenance.
4. **Plafonds de Qualification :** Ratification des budgets des trois gates (Gate A: 120s, Gate B: 240s, Gate C: 180s) sans full reactor.

---

## 12. Verdict Formel de Cadrage

```
================================================================================
POCOMA V3 — LOT L6 / INCRÉMENT I2 : VERDICT FORMEL DE CADRAGE
Statut officiel : L6/I2_DESIGN_READY_FOR_HUMAN_REVIEW
Dépôt : kartaguez/pocoma
Branche : feat/v3-architecture
SHA de référence : 38478787edfb7ee3fecf18739165f96faf2b8727
Autorisation de cadrage : GO_L6_I2_SCOPING

Plan technique : docs/v3/L6_I2_Technical_Implementation_Plan.md (18 sections complètes)
Rapport de cadrage : docs/v3/L6_I2_Scoping_and_Design_Report.md
Architecture retenue : Reconstruction en place coordonnée avec barrière exclusive
Moteurs réutilisés : DiscoveryEngine, ConsumptionEngine, V3ReadParticipants (100%)
Impacts WRITE : 0 modification, 0 verrou, SELECT exclusif
Budget de qualification proposé : <= 9 minutes (Gates A, B, C)

Statut d'implémentation : IMPLEMENTATION NOT AUTHORIZED
Prochain acte attendu : Revue humaine et décision GO_L6_I2_IMPLEMENTATION
================================================================================
```
