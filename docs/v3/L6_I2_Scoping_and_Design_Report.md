# Pocoma V3 — L6/I2 — Rapport de Cadrage et de Conception Consolidé
## Exploitation & Reconstruction READ — Rapport de Correction Finale

**Date :** 6 novembre 2026
**Dépôt :** `kartaguez/pocoma`
**Branche :** `feat/v3-architecture`
**SHA de référence initial :** `07f71693b2a05560d7820da514d90586850a704e`
**Autorisation humaine :** Mandat de correction finale des blocages du protocole de reconstruction READ
**Mode de mission :** `TARGETED DESIGN CORRECTION — DOCUMENTATION ONLY — NO IMPLEMENTATION — NO TEST EXECUTION`
**Statut L6/I1 :** `CLOSED — LOCAL OPERATIONAL V3 PASS WITH EXPLICIT RESERVATIONS`
**Statut L6/I2 :** `DESIGN FINAL CONSOLIDATED — READY FOR IMPLEMENTATION GO — IMPLEMENTATION NOT AUTHORIZED`
**Verdict formel :** `L6_I2_DESIGN_FINAL_READY_FOR_GO`

---

## 1. Contexte, Mandat et Déclaration de Vérification (`AGENTS.md`)

Suite à la revue critique humaine complémentaire sur le cadrage de L6/I2, cette mission a résolu les trois blocages structurels (B1, B2, B3) et levé les quatre réserves techniques (R1, R2, R3, R4) identifiés sur le protocole de reconstruction READ.

### Déclaration de Vérification Normative
- **Declared verification slice(s) :** `NONE` (Mode purement documentaire et de conception statique).
- **Commands actually executed :** Commandes d'inspection git et fichiers statiques, script de suppression des espaces blancs, `git diff --cached --check`, `git commit`, `git push origin feat/v3-architecture`.
- **Unexpected slice crossing :** `NONE`.
- **Global gate required :** `NO` (aucune frontière inter-modules altérée).
- **Full reactor run and why :** `NO` (strictement interdit par [Reactor_Verification_Policy.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/Reactor_Verification_Policy.md)).

---

## 2. Matrice Synthétique des Résolutions B1–B3 et R1–R4

| Réf. | Risque Initial | Correction Retenue | Justification Technique & Références | État |
|---|---|---|---|:---:|
| **B1** | **Autorité détruite par le reset** : `v3_read.read_maintenance_state` était supprimée par `DROP SCHEMA v3_read CASCADE;`. | Relocalisation de l'autorité dans `public.read_maintenance_state` au sein de la base `pocoma_read`. | Le schéma `public` survit au drop de `v3_read`. Le `search_path` de `pocoma_read` inclut déjà `public`. Propriétaire : `pocoma`, `SELECT` seul accordé à `pocoma_read`. [Plan §2](L6_I2_Technical_Implementation_Plan.md#2-autorite-indestructible-de-maintenance-publicread_maintenance_state). | **RÉSOLU** |
| **B2** | **Fencing SQL insuffisant** : `REVOKE DML` inefficace sur transactions ouvertes ou si `pocoma_read` est propriétaire. | Protocole d'exclusion à 5 niveaux : inhibition, arrêt conteneurs (`scale=0`), vérification `pg_stat_activity`, révocation `USAGE ON SCHEMA v3_read`, et verrou `AccessExclusiveLock` du DROP. | `protectFinal` ne vérifie pas le bail de partition au commit. Le DROP détruit physiquement les tables et leurs OIDs. Tout accès résiduel échoue avec `permission denied` ou `relation does not exist`. [Plan §4](L6_I2_Technical_Implementation_Plan.md#4-exclusion-reelle-des-workers-et-fencing-multi-couches). | **RÉSOLU** |
| **B3** | **Reprise après crash incomplète** : `--resume` supposait un schéma déjà prêt ; crash possible pendant DDL/Flyway. | FSM à 4 états (`READY`, `MAINTENANCE`, `REBUILDING`, `FAILED`) enrichie de 9 marqueurs de phase avec actions déterministes par phase. | Si crash avant replay : re-drop idempotent sans perte. Si crash pendant replay : reprise directe sans double reset via persistance de $B$. [Plan §5](L6_I2_Technical_Implementation_Plan.md#5-machine-a-etats-persistante-et-resilience-au-crash-fsm). | **RÉSOLU** |
| **R1** | **Drainage HTTP approximatif** : Simple attente de 3 s sans preuve de fin des requêtes engagées. | Temporisation de confort 3 s combinée à un contrôle physique dans `pg_stat_activity` de 0 requête active sur `pocoma_read`. | `V3ReadController` n'a pas de tâches asynchrones. Le timeout Spring est de 2 s. `pg_stat_activity` apporte la preuve matérielle d'absence de session active avant DDL. [Plan §3](L6_I2_Technical_Implementation_Plan.md#3-barriere-dindisponibilite-distribuee-et-drainage-http). | **LEVÉE** |
| **R2** | **Oracle non démontré & redondant** : Redondance entre C2 et C3 ; sémantique de position par partition à clarifier. | Prédicat épuré en 3 conditions indépendantes : $\mathcal{C}_{\text{checkpoints}}$ (28 CP $\ge B_F$), $\mathcal{C}_{\text{slots}}$ (0 slots $\le B$, incluant quarantaines), $\mathcal{C}_{\text{integrity}}$ (4 oracles de projection). | Dans `DiscoveryEngine`, chaque partition parcourt la séquence globale et enregistre sa position couverte. Dans `consumption_slot`, un slot achevé est supprimé (`deleteProtected`). [Plan §8](L6_I2_Technical_Implementation_Plan.md#8-oracle-de-convergence-mathematique-et-predicat-formel-ready). | **LEVÉE** |
| **R3** | **Convergence supposée & watchdog inadapté** : Seuil 30 s trop agressif face aux recalculs $O(N)$ et baux 240 s. | Distinction entre préfixe fini (backlog borné) et progression réelle ; watchdog recalibré à 180 s avec vérification de vie worker. | Évite les faux-positifs `FAILED` lors des recalculs légitimes de gros Pots tout en détectant les réels blocages. [Plan §9](L6_I2_Technical_Implementation_Plan.md#9-analyse-de-convergence-et-watchdog-de-progres-sous-write-continu). | **LEVÉE** |
| **R4** | **Gates Maven incertaines** : Risque d'échec Surefire sur modules amont (`-am`) sans tests correspondants. | Utilisation du flag standard `-DfailIfNoSpecifiedTests=false` conformément à la politique normative §35. | Évite les échecs factices de sélection Surefire dans les 15 modules amont de `composition-runtime-spring`. Budget $\le 8$ min préservé. [Plan §11](L6_I2_Technical_Implementation_Plan.md#11-strategie-de-qualification-ciblee-commandes-exactes-et-budgets). | **LEVÉE** |

---

## 3. Détail des Résolutions Apportées

### 3.1 Blocage B1 — Autorité Indestructible de Maintenance
- **Localisation :** Base `pocoma_read`, table `public.read_maintenance_state`.
- **Pourquoi `public` ?**
  1. Lors de l'exécution de `DROP SCHEMA v3_read CASCADE;`, seul le schéma applicatif `v3_read` est supprimé. Le schéma système `public` demeure intact.
  2. Le rôle applicatif `pocoma_read` a son `search_path` initialisé à `v3_read, public`. Il peut donc interroger `read_maintenance_state` ou `public.read_maintenance_state` en continu, y compris lorsque `v3_read` n'existe pas.
  3. L'administrateur `pocoma` est propriétaire de la table ; `pocoma_read` ne détient que le privilège `SELECT`. L'application ne peut pas falsifier son état.
  4. Flyway n'administre que le schéma `v3_read` ; il n'interfère jamais avec la table de maintenance.

### 3.2 Blocage B2 — Fencing et Exclusion Réelle des Workers
- **Constat :** `protectFinal` dans `JdbcConsumptionSlotStore` vérifie uniquement la validité du claim token, sans valider l'appartenance de la partition au worker au moment exact du commit SQL.
- **Protocole d'Exclusion en 5 Niveaux :**
  1. *Inhibition :* Les workers lisent l'état `MAINTENANCE` et cessent le polling.
  2. *Arrêt physique :* `docker compose stop worker-read` et mise à l'échelle 0 (`--scale worker-read=0`) pour empêcher tout redémarrage intempestif.
  3. *Observation SQL :* Contrôle dans `pg_stat_activity` de 0 connexion active portant `usename = 'pocoma_read'`.
  4. *Révocation USAGE :* `REVOKE USAGE ON SCHEMA v3_read FROM pocoma_read;` par l'administrateur `pocoma`.
  5. *Destruction des OIDs :* `DROP SCHEMA v3_read CASCADE;` acquiert un verrou `AccessExclusiveLock`, purgeant physiquement toutes les tables et invalidant tout accès ultérieur.

### 3.3 Blocage B3 — Résilience Complète au Crash (FSM)
- **États et Phases :** 4 états (`READY`, `MAINTENANCE`, `REBUILDING`, `FAILED`) et 9 phases fines (`IDLE`, `DRAINING`, `EXCLUDED`, `SCHEMA_DROPPED`, `SCHEMA_CREATED`, `MIGRATED`, `ROUTED`, `REPLAYING`, `CONVERGED`).
- **Comportement de `v3-rebuild-read.py --resume` :**
  - Si crash avant le début du replay (`DRAINING` .. `ROUTED`) : réinitialisation propre et idempotente du schéma vide via Flyway sans perte de travail.
  - Si crash pendant le replay (`REPLAYING`) : le vecteur cible $B$ et l'état des tables sont préservés dans `public.read_maintenance_state`. Le replay worker reprend directement son travail à partir des checkpoints existants **sans exécuter de nouveau DROP SCHEMA**.
  - Si état `FAILED` : blocage de sécurité nécessitant une intervention de l'exploitant (`--resume` ou `--restart`).

### 3.4 Réserve R1 — Drainage HTTP et Preuve d'Inactivité
- Les contrôleurs `V3ReadController` sont purement synchrones (aucune tâche de fond asynchrone).
- Le délai de grâce $T_{drain} = 3$ s absorbe les requêtes engagées avant la bascule (timeout transactionnel Spring de 2 s).
- La confirmation matérielle est apportée par `SELECT count(*) FROM pg_stat_activity WHERE datname='pocoma_read' AND state IN ('active', 'idle in transaction')`. Le DROP DDL n'est autorisé que lorsque ce compteur atteint 0.

### 3.5 Réserve R2 — Exactitude de l'Oracle READY
- **Sémantique :** Dans `DiscoveryEngine`, chaque partition examine la séquence globale de la famille et met à jour `discovery_checkpoint` avec la position couverte.
- **Suppression de redondance :** Dans `consumption_slot`, un slot achevé avec succès est supprimé de la table. La condition $\mathcal{C}_{\text{slots}}$ vérifie que `COUNT(*) == 0` pour les événements $\le B_F$, ce qui englobe inconditionnellement les slots disponibles ET les slots en quarantaine.
- **Prédicat $\mathcal{P}_{\text{READY}}(B)$ :**
  1. 28 checkpoints $\ge B_F$ dans `discovery_checkpoint`.
  2. 0 slots $\le B_F$ dans `consumption_slot`.
  3. Cohérence relationnelle interne vérifiée sur les 4 projections métiers.

### 3.6 Réserve R3 — Convergence et Watchdog sous WRITE Continu
- Un vecteur $B$ fini garantit un backlog cible borné, éliminant le problème de la cible mouvante. Les événements postérieurs à $B$ sont absorbés au fil de l'eau.
- Le watchdog de détection de blocage est recalibré à **180 secondes** (au lieu de 30 s), assurant la compatibilité avec les recalculs lourds de soldes de Pots ($O(N)$) et la durée des baux sans déclencher de faux positifs de panne.

### 3.7 Réserve R4 — Compatibilité Maven et Couverture Réelle
- Les commandes des trois gates intègrent `-DfailIfNoSpecifiedTests=false`, évitant les erreurs de sélection Surefire sur les 15 modules amont de `composition-runtime-spring`.
- Budget total respecté : Gate A (60s) + Gate B (240s) + Gate C (180s) = $\le 480$ s ($\le 8$ minutes, sous le plafond contractuel de 9 minutes).

---

## 4. Réponses Consolidées aux Huit Questions Critères (Section 15)

1. **Qu'est-ce qui est reconstruit ?**
   L'intégralité du schéma `v3_read` : les 9 projections réelles, les tables techniques de consommation (`discovery_checkpoint`, `consumption_slot`, `partition_ownership`), ainsi que les routes de consommation. La table `public.read_maintenance_state` est préservée hors du schéma détruit.
2. **À partir de quoi ?**
   Exclusivement à partir des 5 flux d'événements immuables conservés dans `pocoma_write` (`v3_event_counter` / `v3_event_position`) et de leurs données associées, accédées en lecture seule stricte.
3. **Comment ?**
   Par composition sans modification des moteurs existants `DiscoveryEngine` et `ConsumptionEngine`, et des sept participants de `V3ReadParticipants`.
4. **Avec quelles garanties ?**
   Étanchéité totale de WRITE ; indisponibilité 503 fail-closed ; double barrière d'exclusion des anciens workers ; résilience au crash via FSM persistante et marqueurs de phase ; oracle mathématique de convergence READY.
5. **Comment l'exploiter ?**
   Via le script CLI Python `scripts/operations/v3-rebuild-read.py` avec confirmation obligatoire `--confirm-destructive=RESET_READ_DATA` et commande de reprise après incident `--resume`.
6. **Comment savoir que cela fonctionne ?**
   Par la satisfaction prouvée du prédicat $\mathcal{P}_{\text{READY}}(B)$ (28 checkpoints $\ge B$, 0 slots $\le B$), les oracles d'intégrité relationnelle, et les métriques Prometheus exposées sur Grafana.
7. **Quel coût ?**
   Zéro régression, réutilisation intégrale du socle V3, et un budget de qualification compact $\le 8$ minutes sans full reactor.
8. **Qu'est-ce qui reste hors périmètre ?**
   Les benchmarks de charge et SLO (SPEC-09) attribués à **L6/I3** ; la convergence de modèle V2/V3 attribuée à **L7** ; le packaging multi-nœuds attribué à **L8**.

---

## 5. Risques Résiduels Explicitement Acceptables

1. **Indisponibilité temporaire de lecture :** L'espace READ est inaccessible (HTTP 503) pendant le temps du reset et du rattrapage. Ce comportement est formellement accepté par [SPEC-10 §10](SPEC-10_Test_Certification.md).
2. **Latence de rafraîchissement du filtre (500 ms) :** Absorbée en toute sécurité par la temporisation de grâce $T_{drain} = 3$ s avant toute opération DDL.
3. **Débit de rattrapage sous fort volume :** Dépend du coût de recalcul $O(N)$ sur la balance des grands pots. Sa caractérisation relève de l'incrément de qualification de performance **L6/I3**.

---

## 6. Verdict Formel de Cadrage et Recommandation

```
================================================================================
POCOMA V3 — LOT L6 / INCRÉMENT I2 : VERDICT DE CONCEPTION FINALE
Statut officiel : L6_I2_DESIGN_FINAL_READY_FOR_GO
Dépôt : kartaguez/pocoma
Branche : feat/v3-architecture
SHA de référence initial : 07f71693b2a05560d7820da514d90586850a704e
Mode d'exécution : TARGETED DESIGN CORRECTION — DOCUMENTATION ONLY

Autorité de maintenance : public.read_maintenance_state (indestructible par drop v3_read)
Exclusion des workers : 5 niveaux étanches (inhibition, scale=0, pg_stat_activity,
                        REVOKE USAGE, AccessExclusiveLock de DROP)
Machine à états & Crash : FSM 4 états / 9 phases avec reprise déterministe --resume
Oracle de remise en service : P_READY(B) sur 28 checkpoints et 0 slots <= B (non redondant)
Convergence & Watchdog : Cible B finie, watchdog de progrès re-calibré à 180s
Stratégie de vérification : Gates A, B, C (budget <= 8 min, -DfailIfNoSpecifiedTests=false)

Statut d'implémentation : IMPLEMENTATION NOT AUTHORIZED
Prochain acte attendu : Arbitrage humain final et décision GO_L6_I2_IMPLEMENTATION
================================================================================
```
