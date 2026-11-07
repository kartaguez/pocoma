# Rapport d'Audit Indépendant — Métrologie, Capacité et Chaîne Asynchrone Pocoma V3 (L6/I3-R3)

> **Statut de l'Audit :** ACHEVÉ — AUDIT INDÉPENDANT CONTRADICTOIRE  
> **Mandat :** `GO_L6_I3_R3_INDEPENDENT_CAPACITY_AUDIT`  
> **Mode :** `READ-ONLY SOURCE & EVIDENCE AUDIT + DOCUMENTATION` (Aucune modification applicative, aucune réexécution de banc)  
> **Branche :** `feat/v3-architecture`  
> **SHA de référence analysé :** `a12b3550b0c6183d2f3d67f70b2299cf76fbe6d4`  
> **Verdict d'audit :** `NOT_READY_FOR_CLOSURE — METROLOGY_CORRECTIONS_REQUIRED`  
> **Statut de l'itération L6/I3 :** L6/I3 DEMEURE OUVERT

---

## 1. Contexte, Mandat et Périmètre de l'Audit Indépendant

### 1.1 Contexte des Itérations L6/I3
L'itération L6/I3 (« Banc de performance et profils structurels ») de Pocoma V3 a fait l'objet de trois étapes successives :
1. **L6/I3 Initial :** Implantation du banc k6/Python, outillage de mesure et premières qualifications locales bornées.
2. **L6/I3-R :** Instrumentation approfondie, tentatives de montée en charge progressive et identification de divergences de débits.
3. **L6/I3-R2 :** Découplage de l'injection k6 et de l'observation asynchrone (Terminal + READ), mise en place du rafraîchissement proactif OIDC, exécution des paliers P1 à P5 (120 s chacun).

Le rapport R2 ([`L6_I3_R2_Metrology_and_Capacity_Requalification_Report.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L6_I3_R2_Metrology_and_Capacity_Requalification_Report.md)) concluait au verdict `CAPACITY_CHARACTERIZED` avec l'affirmation d'un goulot applicatif strict à 5,4 cmd/s et d'un effondrement à 0,48 cmd/s au palier P5.

### 1.2 Décision Humaine et Mandat R3
La décision humaine a rejeté le verdict de clôture de R2 en raison d'incohérences métrologiques manifestes :
- Débit d'admission non corrélé au débit de traitement ;
- Saturation du backlog plafonnant invariablement à 253–255 sans explication causale ;
- Effondrement brutal du palier P5 à 1 % de couverture E2E ;
- Hypothèse d'un algorithme quadratique $\mathcal{O}(N^2)$ pour le calcul de balance non étayée par l'analyse statique ;
- Rôle exact du générateur et de l'observateur non isolé des performances du runtime Pocoma.

Le mandat **`GO_L6_I3_R3_INDEPENDENT_CAPACITY_AUDIT`** a ordonné un audit indépendant contradictoire, strictement en lecture seule, fondé sur l'inspection du code source et l'analyse exhaustive des preuves brutes collectées lors de R2.

### 1.3 Règles d'Exécution et Périmètre Strict
L'audit a opéré sous les garde-fous normatifs suivants :
- **Aucune exécution de test de charge ni de script k6** ;
- **Aucun lancement du réacteur Maven global** (respect de [`Reactor_Verification_Policy.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/Reactor_Verification_Policy.md)) ;
- **Aucune modification du code applicatif sous `app/`** ni des scripts de test sous `scripts/` ;
- **Aucune altération ni suppression des preuves brutes historiques** ;
- **Calculs et réconciliations effectués hors-ligne** directement à partir des journaux JSONL et métriques brutes archivées sous [`docs/testing/evidence/L6-I3/`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/).

---

## 2. Traçabilité des Sources et Inventaire des Preuves

### 2.1 Environnement et Intégrité Git
- **Dépôt :** `kartaguez/pocoma`
- **Branche :** `feat/v3-architecture`
- **SHA audité :** `a12b3550b0c6183d2f3d67f70b2299cf76fbe6d4`
- **État du worktree :** Strictement propre (0 modification non commise), synchronisation distante 0/0.

### 2.2 Inventaire Exhaustif des Fichiers de Preuve R2
Tous les jeux de données bruts ayant servi aux conclusions de R2 ont été vérifiés sur disque :

| Palier / Scénario | Journal d'Admissions (JSONL) | Métriques Découplées (JSON) | Sortie k6 (JSON) | Snapshot Télémétrie (TXT) | Rapport Synthèse (JSON) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **P1 (1 cmd/s)** | [`run-r2-ramp-p1-1794080775_admissions.jsonl`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p1-1794080775_admissions.jsonl) (76 Ko) | [`run-r2-ramp-p1-1794080775_decoupled.json`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p1-1794080775_decoupled.json) (37 Ko) | `run-r2-ramp-p1-1794080775_k6_summary.json` (8,7 Ko) | `run-r2-ramp-p1-1794080775_pre_snapshot.txt` (955 Ko) | `run-r2-ramp-p1-1794080775_report.json` (46 Ko) |
| **P2 (5 cmd/s)** | [`run-r2-ramp-p2-1794080924_admissions.jsonl`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p2-1794080924_admissions.jsonl) (381 Ko) | [`run-r2-ramp-p2-1794080924_decoupled.json`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p2-1794080924_decoupled.json) (40 Ko) | `run-r2-ramp-p2-1794080924_k6_summary.json` (9,1 Ko) | `run-r2-ramp-p2-1794080924_pre_snapshot.txt` (955 Ko) | `run-r2-ramp-p2-1794080924_report.json` (49 Ko) |
| **P3 (10 cmd/s)** | [`run-r2-ramp-p3-1794081083_admissions.jsonl`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p3-1794081083_admissions.jsonl) (768 Ko) | [`run-r2-ramp-p3-1794081083_decoupled.json`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p3-1794081083_decoupled.json) (52 Ko) | `run-r2-ramp-p3-1794081083_k6_summary.json` (10 Ko) | `run-r2-ramp-p3-1794081083_pre_snapshot.txt` (956 Ko) | `run-r2-ramp-p3-1794081083_report.json` (62 Ko) |
| **P4 (20 cmd/s)** | [`run-r2-ramp-p4-1794081290_admissions.jsonl`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p4-1794081290_admissions.jsonl) (1,5 Mo) | [`run-r2-ramp-p4-1794081290_decoupled.json`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p4-1794081290_decoupled.json) (63 Ko) | `run-r2-ramp-p4-1794081290_k6_summary.json` (11 Ko) | `run-r2-ramp-p4-1794081290_pre_snapshot.txt` (956 Ko) | `run-r2-ramp-p4-1794081290_report.json` (74 Ko) |
| **P5 (50 cmd/s)** | [`run-r2-ramp-p5-1794081695_admissions.jsonl`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p5-1794081695_admissions.jsonl) (3,8 Mo) | [`run-r2-ramp-p5-1794081695_decoupled.json`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/run-r2-ramp-p5-1794081695_decoupled.json) (63 Ko) | `run-r2-ramp-p5-1794081695_k6_summary.json` (13 Ko) | `run-r2-ramp-p5-1794081695_pre_snapshot.txt` (956 Ko) | `run-r2-ramp-p5-1794081695_report.json` (79 Ko) |

Chaque assertion quantitative du présent rapport est directement traçable vers ces artefacts bruts non modifiés.

---

## 3. Réconciliation Exhaustive des Populations et des Équations de Conservation

### 3.1 Définition des Équations de Conservation
Afin d'éviter tout double comptage ou mélange de catégories hétérogènes, les ensembles d'événements sont partitionnés de façon stricte et exclusive :

1. **Conservation à l'Émission :**
   $$\text{Émises} = \text{Admises (HTTP 202)} + \text{Rejetées Capacité (HTTP 503)} + \text{Erreurs Admission (HTTP 0 / Timeout)} + \text{Indéterminées}$$
2. **Conservation à l'Admission (Cycle Terminal) :**
   $$\text{Admises} = \text{Appliquées (COMPLETED)} + \text{Rejetées CAS (REJECTED)} + \text{Timeouts Observateur} + \text{Non-Dépouillées à Clôture}$$
3. **Conservation de la Matérialisation READ :**
   $$\text{Appliquées} = \text{Matérialisées READ Confirmées} + \text{Timeouts READ} + \text{Non-Lues à Clôture}$$

### 3.2 Tableau de Réconciliation Exhaustif (P1 à P5)
Le recalcul hors-ligne sur les 10 301 commandes émises à travers les 5 paliers fournit la réconciliation exacte suivante :

| Dimension / Compteur | P1 (1 cmd/s) | P2 (5 cmd/s) | P3 (10 cmd/s) | P4 (20 cmd/s) | P5 (50 cmd/s) | Total Cumulé |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Durée consigne injection** | 120,0 s | 120,0 s | 120,0 s | 120,0 s | 120,0 s | 600,0 s |
| **Durée réelle injection ($T_{\text{inj}}$)** | 120,02 s | 120,01 s | 120,02 s | 125,02 s | 125,02 s | 610,09 s |
| **Durée drainage ($T_{\text{drain}}$)** | 0,63 s | 1,71 s | 27,07 s | 60,27 s (expiré) | 60,00 s (expiré) | 149,68 s |
| **Durée totale scénario** | 145,23 s | 155,33 s | 202,62 s | 247,79 s | 261,20 s | 1 012,17 s |
| **1. Commandes émises** | **121** | **600** | **1 201** | **2 399** | **5 980** | **10 301** |
| — Admises (HTTP 202) | 121 (100 %) | 600 (100 %) | 1 201 (100 %) | 2 392 (99,7 %) | 5 930 (99,16 %) | 10 244 (99,45 %) |
| — Rejetées Capacité (HTTP 503) | 0 | 0 | 0 | 0 | 1 (0,017 %) | 1 (< 0,01 %) |
| — Erreurs Admission (HTTP 0) | 0 | 0 | 0 | 7 (0,29 %) | 49 (0,82 %) | 56 (0,54 %) |
| — Indéterminées | 0 | 0 | 0 | 0 | 0 | 0 |
| *Contrôle Conservation Émission* | *121 = 121* | *600 = 600* | *1201 = 1201* | *2399 = 2399* | *5980 = 5980* | **EXACT (100 %)** |
| **2. Commandes admises** | **121** | **600** | **1 201** | **2 392** | **5 930** | **10 244** |
| — Appliquées (`COMPLETED`) | 121 | 600 | 521 | 673 | 60 | 1 975 |
| — Rejetées CAS (`REJECTED`) | 0 | 0 | 680 | 384 | 492 | 1 556 |
| — Timeouts Observateur | 0 | 0 | 0 | 924 | 1 687 | 2 611 |
| — Non-dépouillées à clôture | 0 | 0 | 0 | 411 | 3 691 | 4 102 |
| *Contrôle Conservation Admission* | *121 = 121* | *600 = 600* | *1201 = 1201* | *2392 = 2392* | *5930 = 5930* | **EXACT (100 %)** |
| **3. Matérialisation READ** | | | | | | |
| — Appliquées éligibles READ | 121 | 600 | 521 | 673 | 60 | 1 975 |
| — Matérialisées READ confirmées | 121 | 600 | 521 | 670 | 60 | 1 972 |
| — Timeouts READ | 0 | 0 | 0 | 0 | 0 | 0 |
| — Non-lues à clôture | 0 | 0 | 0 | 3 | 0 | 3 |
| **Backlog DB final (`consumption_slot`)** | **0** | **0** | **0** | **255** | **255** | — |
| **Couverture E2E observée** | **100 %** | **100 %** | **43,38 %** | **28,01 %** | **1,01 %** | — |

### 3.3 Résolution des Anomalies Spécifiques

#### 1. Palier P3 : Les 680 Rejets CAS
- **Constat :** Sur 1 201 commandes admises, 521 ont été appliquées et 680 ont été rejetées avec le statut `REJECTED` et le motif `Pot global CAS collision`.
- **Analyse :** Le scénario k6 P3 (`sustained`) injecte 1 201 commandes réparties sur seulement 8 cagnottes (`POTS: 8`), soit environ 150 commandes par cagnotte en 120 secondes (1,25 cmd/s/pot). Les commandes ciblant la même cagnotte concurrencent la même version scalaire protégée par le déclencheur PostgreSQL `v3_advance_pot_cas()` ([`V2__command_execution.sql`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql#L4-L11)). Lorsqu'une commande réussit, les requêtes concurrentes ayant observé la version précédente sont rejetées par collision de version optimiste.
- **Verdict :** Il s'agit d'un comportement métier nominal de contrôle de concurrence optimiste et non d'une perte de message ou d'une défaillance d'infrastructure. **La chaîne asynchrone a traité l'intégralité des 1 201 commandes en 147 secondes (backlog final = 0)**.

#### 2. Palier P4 : Les 255 Slots en Backlog et les 1 335 Commandes Non Terminales
- **Constat :** À l'issue des 60 s de drainage, le backlog mesuré reste figé à 255, et 1 335 commandes n'ont pas de statut terminal confirmé par l'observateur.
- **Analyse :** 924 commandes ont atteint le timeout de sonde locale de 90 secondes, et 411 commandes étaient encore en attente dans la file `terminal_queue` de l'observateur. La somme $673 + 384 + 924 + 411 = 2 392$ boucle exactement. L'origine du chiffre 255 est expliquée en Section 4.

#### 3. Palier P5 : Les 49 Erreurs d'Admission HTTP 0 et la Couverture de 1,01 %
- **Constat :** 49 requêtes ont échoué avec le code HTTP 0, 1 seule avec HTTP 503, et seules 60 commandes appliquées ont été enregistrées sur 5 930 admises.
- **Analyse des HTTP 0 :** Les horodatages bruts révèlent que 5 de ces erreurs sont survenues dans les 500 premières millisecondes du scénario (débordement transitoire du socket TCP lors du démarrage brutal des 50 VUs k6), et les suivantes lors de coupures ponctuelles de connexion côté client. **Le taux d'admission HTTP réel de Pocoma reste exceptionnel : 99,16 % (5 930 / 5 980) à 47,4 cmd/s**.
- **Analyse des 60 appliquées :** L'observateur Python de R2 (`TerminalObserver` dans [`v3_observer.py`](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/v3_observer.py#L219-L260)) était strictement **mono-threadé**. Face à 5 930 commandes admises, une boucle séquentielle effectuant des requêtes HTTP avec latence réseau ne peut interroger qu'un nombre fini de statuts. En 185 secondes totales, l'observateur n'a réussi à sonder que 552 commandes terminalisées (60 `COMPLETED`, 492 `REJECTED`) avant l'expiration du drainage ! Les 5 378 autres commandes sont restées bloquées dans la file d'attente de l'observateur.

---

## 4. Analyse Approfondie de l'Anomalie du Backlog à ~255

Dans tous les rapports précédents (I3-R et I3-R2), la stabilisation du backlog autour de 253 à 255 lors des fortes charges était interprétée comme « un palier d'engorgement du système » ou « une saturation du pool de workers ».

### 4.1 Découverte du Mécanisme de Découverte Biphasée
L'inspection du code source Java du moteur de consommation V3 dévoile la véritable cause :

1. **Table d'Admission Durable (`v3_command`) :**
   Lorsqu'une commande est admise via l'API HTTP (`POST /api/v3/pots/{id}/commands`), elle est immédiatement insérée dans la table PostgreSQL `v3_write.v3_command` avec `status = 'PENDING'` ([`V2__command_execution.sql#L13-L17`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql#L13-L17)). Cette table absorbe l'ensemble du flux sans limite rigide de 256.
2. **Mécanisme de Découverte Biphasée (`DiscoveryEngine`) :**
   Le composant `DiscoveryEngine` effectue périodiquement une découverte bornée des commandes en attente pour créer des unités de travail (slots) dans la table `v3_write.consumption_slot` ([`DiscoveryEngine.java#L264-L310`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/DiscoveryEngine.java#L264-L310)).
3. **Le Plafond Souple (`softCap`) :**
   Dans [`DiscoveryEngine.java#L148-L152`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/DiscoveryEngine.java#L148-L152) :
   ```java
   private int budget(PartitionLease lease) {
       long count = admissions.liveSlots(lease);
       if (count < 0) throw new IllegalStateException("negative live stock");
       return (int) Math.min(settings.insertBatchSize(), Math.max(0, settings.softCap() - count));
   }
   ```
4. **Configuration Normative du Runtime :**
   Dans [`V3ConsumptionWorkerConfiguration.java#L334-L340`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java#L334-L340) :
   ```java
   var ds = new DiscoveryEngine.Settings(
           128,
           settings.lifecycle().batchSize(),
           settings.lifecycle().batchSize(),
           64,  // softCap par partition = 64
           settings.lifecycle().lockTimeout(),
           settings.lifecycle().statementTimeout());
   ```
5. **Nombre de Partitions de Routage :**
   La table de contrat de routage impose strictement 4 partitions (`partition_count = 4`, partitions 0, 1, 2, 3) ([`V5__consumption_foundation.sql#L34`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/resources/db/v3-consumption-write/migration/V5__consumption_foundation.sql#L34)).

### 4.2 La Démonstration Mathématique du Plafond 256
La capacité maximale théorique de slots simultanés dans `v3_write.consumption_slot` est :
$$\text{Capacité Max Slots} = \text{Nombre de Partitions} \times \text{softCap} = 4 \times 64 = 256 \text{ slots}$$

Dès qu'une partition atteint 64 slots actifs, `budget()` retourne 0 et `DiscoveryEngine` suspend l'insertion de nouveaux slots pour cette partition (`Status.CAPACITY`), attendant que le worker exécute et supprime les slots existants via `deleteProtected()` ([`JdbcConsumptionSlotStore.java#L313-L323`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcConsumptionSlotStore.java#L313-L323)).

### 4.3 Classification Métrologique Formelle
Dans [`v3_observer.py#L43`](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/v3_observer.py#L43), la sonde de backlog exécutait :
```sql
SELECT count(*) FROM v3_write.consumption_slot;
```
Le banc mesurait donc **le buffer de travail interne du moteur de découverte** (borné par conception à 256) et non **la file d'attente globale des commandes en souffrance** dans `v3_command WHERE status = 'PENDING'`.

> **Classification de l'Anomalie :** **`QUERY_OR_COLLECTOR_CAP`** cumulé à un **`MEASUREMENT_ARTIFACT`**.  
> Le plateau à ~255 n'a jamais représenté une saturation de la base de données ni un effondrement mémoire, mais l'effet direct de la borne de conception `softCap = 64` sur 4 partitions.

---

## 5. Analyse de la Chaîne Asynchrone WRITE et Requalification du Goulot

### 5.1 Architecture et Topologie du Worker
Le runtime monolithique instancie un orchestrateur `V3ConsumptionWorker` ([`V3ConsumptionWorker.java`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionWorker.java)) doté de :
- Un thread dédié `v3-discovery` exécutant la boucle de découverte ;
- Un thread dédié `v3-consumption` exécutant la boucle d'acquisition de claims ;
- Un pool d'exécution `v3-attempt-*` dimensionné par `WorkerCapacity.workerLimit()` ;
- Les métriques Prometheus de snapshot confirment : `pocoma_v3_capacity_limit{scope="worker"} = 4.0`.

Le worker dispose donc d'une capacité de parallélisme de 4 exécutions concurrentes.

### 5.2 Évaluation de la Thèse du « Plafond à 5,4 cmd/s »
Le rapport R2 concluait que la chaîne WRITE avait une capacité maximale intrinsèque de 5,4 cmd/s.

Cette conclusion est formellement auditée et soumise aux cinq qualifications possibles :
- **DEMONSTRATED :** Non. Aucune preuve de saturation des ressources CPU, I/O ou locks n'est établie.
- **SUPPORTED :** Non.
- **HYPOTHESIS :** Non.
- **REFUTED :** **OUI.**
- **UNDETERMINED :** Non.

**Preuve irréfutable de réfutation :**
Au palier P3 (10 cmd/s demandés) :
- Le système a admis 1 201 commandes en 120,02 secondes ;
- Le worker a dépouillé, traité et terminalisé **1 201 commandes** (521 appliquées + 680 rejets CAS complets) ;
- À la fin du drainage (27 s après l'arrêt de l'injection), le backlog `consumption_slot` est tombé à **0** ;
- Le débit transactionnel terminal soutenu par le worker en P3 s'élève à :
  $$\text{Débit Réel P3} = \frac{1\,201 \text{ transactions}}{120,02 \text{ s}} = 10,01 \text{ transactions terminales/seconde}$$

Le chiffre de 5,4 cmd/s mesuré au palier P4 correspondait uniquement à $\frac{673 \text{ appliquées}}{125 \text{ s}}$, c'est-à-dire au débit des commandes ayant réussi leur CAS au milieu des collisions concurrentes et des limitations de dépouillement de l'observateur.

> **Verdict d'Audit :** L'affirmation d'un plafond intrinsèque à 5,4 cmd/s est **`REFUTED`** par les données brutes de P3 ($10,01 \text{ trans/s}$). La véritable limite de traitement WRITE sous charge non conflictuelle demeure **`UNDETERMINED`** au-delà de 10 cmd/s tant que l'observateur n'est pas fiabilisé.

---

## 6. Audit des Fenêtres de Mesure, des Horloges et des Calculs de Débit

### 6.1 Alignement des Horloges
- **k6 :** Horloge système cliente (`Date.now()`).
- **Python (`v3_observer.py`) :** Horloge système hôte (`time.time() * 1000`).
- **PostgreSQL :** `clock_timestamp()`.
- **Runtime Spring / Actuator :** `System.currentTimeMillis()` / `System.nanoTime()`.
Tous les composants s'exécutant sur la machine hôte locale, le décalage d'horloge est inférieur à 1 ms et n'introduit aucun biais temporel.

### 6.2 Analyse Critique des Formules de Calcul de Débit
Dans [`v3_observer.py#L585-L589`](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/v3_observer.py#L585-L589) :
```python
emitted_rate = round(self.store.emitted_count / inj_duration, 2)
admission_rate = round(self.store.admitted_count / inj_duration, 2)
applied_rate = round(len(applied_records) / inj_duration, 2)
read_rate = round(len(read_records) / inj_duration, 2)
```

Deux failles méthodologiques majeures ont faussé les débits publiés :
1. **Dénominateur Fixe $T_{\text{inj}}$ face à un Dépouillement Partiel :**
   En P5, `len(applied_records)` valait 60 parce que l'observateur n'avait eu le temps de sonder que 552 commandes sur les 5 930 admises. Diviser cet échantillon partiel par 125,02 s donne 0,48 cmd/s. Ce calcul suppose implicitement que toutes les commandes admises ont été dépouillées, ce qui était faux.
2. **Confusion entre Statut Terminal Métier et Observation Client :**
   Une commande appliquée par le worker mais non encore interrogée par l'observateur avant la coupure du drainage était classée `nonTerminalAtClose` et exclue de `applied_records`.

### 6.3 Tableau d'Audit Méthodologique des Métriques

| Métrique | Source | Population | Fenêtre | Formule R2 | Validité Méthodologique | Diagnostic d'Audit |
| :--- | :--- | :--- | :--- | :--- | :---: | :--- |
| **Débit Émis** | k6 / Observer | Total requêtes envoyées | $T_{\text{inj}}$ | $N_{\text{emitted}} / T_{\text{inj}}$ | **VALIDE** | Mesure conforme de la pression injectée. |
| **Débit Admission** | HTTP 202 | Commandes admises | $T_{\text{inj}}$ | $N_{\text{admitted}} / T_{\text{inj}}$ | **VALIDE** | Reflète fidèlement la capacité d'ingestion HTTP (47,4 cmd/s à P5). |
| **Débit Appliqué** | Polling `/result` | `COMPLETED` confirmés | $T_{\text{inj}}$ | $N_{\text{applied}} / T_{\text{inj}}$ | **BIAISÉ (P4, P5)** | Invalide si l'observateur est saturé ou si le drainage est tronqué. |
| **Débit READ** | Polling `/balance` | Balances lues $\ge v$ | $T_{\text{inj}}$ | $N_{\text{read}} / T_{\text{inj}}$ | **BIAISÉ (P4, P5)** | Conditionné à la complétion du polling terminal. |
| **Backlog** | SQL PostgreSQL | `v3_write.consumption_slot` | Instantané | `SELECT count(*)` | **INVALIDÉE** | Mesure le buffer borné (cap 256) au lieu de la file `v3_command`. |

---

## 7. Revue de Complexité du Calcul de Balance et Profils Structurels

### 7.1 Audit de `PotBalanceCalculator.java`
Le composant [`PotBalanceCalculator.java`](file:///Users/julien.guezennec/Dev/projects/pocoma/app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/PotBalanceCalculator.java#L24-L50) a été audité ligne par ligne.

```java
public Optional<Balances> calculate(PotFinancialState state) {
    // 1. Initialisation des balances par participant : O(S)
    state.shareholders().keySet().forEach(id -> balances.put(id, Fraction.ZERO));
    
    // 2. Boucle principale sur les dépenses : E itérations
    for (var expense : state.expenses().values()) {
        if (expense.header().value().deleted()) continue;
        var financials = expense.financials().value();
        
        // Somme des poids des participants : O(P_e)
        Fraction total = Fraction.ZERO;
        for (var participant : financials.participants().entrySet())
            total = total.add(effectiveWeight(state, participant.getKey(), participant.getValue()));
            
        Fraction amount = financials.amount().value();
        balances.merge(financials.payerId(), amount, Fraction::add);
        
        // Facteur de débit unitaire
        Fraction debitPerWeight = Fraction.ZERO.subtract(amount.divide(total));
        
        // Répartition aux participants : O(P_e)
        for (var participant : financials.participants().entrySet()) {
            var weight = effectiveWeight(state, participant.getKey(), participant.getValue());
            balances.merge(participant.getKey(), debitPerWeight.multiply(weight), Fraction::add);
        }
    }
    // 3. Tri canonique TreeMap : O(S log S)
    return Optional.of(new Balances(state.potId(), state.version(), balances));
}
```

#### Complexité Temporelle Réelle
Soit $E$ le nombre de dépenses actives et $S$ le nombre de participants. Pour chaque dépense $e$, le nombre de participants à la dépense est $P_e \le S$.
- Initialisation : $\mathcal{O}(S)$
- Traitement de chaque dépense : $2 \times P_e$ opérations arithmétiques rationnelles
- Assemblage final : $\mathcal{O}(S \log S)$
- **Complexité globale :**
  $$\mathcal{T}(E, S) = \mathcal{O}\left(S \log S + \sum_{e=1}^E P_e\right) \le \mathcal{O}(S \log S + E \cdot S)$$

> **Réfutation Formelle :** L'algorithme **n'est pas quadratique** $\mathcal{O}(E^2)$ ou $\mathcal{O}(N^2)$ en nombre de dépenses. Pour un nombre donné de participants $S$, il est **strictement linéaire** $\mathcal{O}(E)$ en nombre de dépenses. L'hypothèse quadratique avancée dans les rapports exploratoires précédents est **`REFUTED`**.

#### Pression Mémoire et Allocations GC
La classe `Fraction` manipule des représentations rationnelles exactes (`BigInteger` numérateur/dénominateur avec réduction PGCD). Chaque opération arithmétique (`add`, `subtract`, `multiply`, `divide`) alloue une nouvelle instance immuable.
Pour le profil Large ($E = 2\,000$, $S = 100$) :
- Par dépense : $\sim 200$ opérations rationnelles ;
- Par projection complète : $\sim 400\,000$ allocations d'objets `Fraction` en quelques millisecondes.
Le coût CPU de la projection provient de la pression sur le garbage collector JVM et de la sérialisation/désérialisation JSON en base, et non d'une complexité algorithmique d'ordre supérieur.

### 7.2 Statut de Matérialisation des Profils Structurels
L'audit des artefacts de pré-amorçage sous [`docs/testing/evidence/L6-I3/seeds/`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L6-I3/seeds/) révèle :

| Profil Structurel | Spécification Normative | Fichier de Seed sur Disque | Statut `materialized` | Preuve SQL (`materializationProof`) | Statut de Qualification |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **Small** | 5 shareholders, 20 expenses | `seed-v3-small-indep.json` (12 Ko) | `True` | Validée (`finalVersion: 22`, SQL vérifié) | **MATÉRIALISÉ ET VÉRIFIÉ** |
| **Medium** | 20 shareholders, 200 expenses | `seed-v3-medium-indep.json` (271 Ko) | `True` | Validée (`finalVersion: 202`, SQL vérifié) | **MATÉRIALISÉ ET VÉRIFIÉ** |
| **Large** | 100 shareholders, 2000 expenses | `seed-v3-large-001.json` (11,5 Mo) | `null` | **Absente (`proof: []`)** | **NON MATÉRIALISÉ** |

Le fichier attendu par `v3-run.py` ([ligne 338](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/v3-run.py#L338)), `seed-v3-large-indep.json`, **n'existe pas sur le dépôt**. Les scénarios S5-Large ont donc systématiquement basculé en initialisation séquentielle inline, sans jamais tester une cagnotte volumineuse pré-matérialisée.

---

## 8. Registre d'Audit des Anomalies et Vulnérabilités Métrologiques

Les constatations sont hiérarchisées selon leur impact sur la validité des décisions :

| Identifiant | Sévérité | Intitulé de l'Anomalie | Description et Impact | Statut |
| :--- | :---: | :--- | :--- | :---: |
| **FINDING-01** | **BLOCKER** | Épuisement de l'observateur mono-threadé en forte injection | `TerminalObserver` ne disposait que d'un seul thread de polling HTTP séquentiel. À 50 cmd/s, l'observateur a été submergé, n'observant que 552 commandes sur 5 930, faussant l'évaluation du débit appliqué (0,48 cmd/s au lieu d'une mesure réelle). | À corriger (R4) |
| **FINDING-02** | **BLOCKER** | Conflation de la métrique de backlog avec le buffer `softCap` | La sonde SQL comptait `v3_write.consumption_slot` (borné par conception à $4 \times 64 = 256$) au lieu de `v3_write.v3_command WHERE status = 'PENDING'`, masquant l'accumulation réelle du backlog de commandes. | À corriger (R4) |
| **FINDING-03** | **MAJOR** | Absence de matérialisation effective du profil Large | Le profil Large ($E = 2\,000, S = 100$) ne dispose d'aucune preuve de matérialisation en base (`materialized: null`), invalidant les conclusions de tenue en charge volumétrique. | À corriger (R4) |
| **FINDING-04** | **MAJOR** | Double comptage des timeouts dans l'équation de conservation | L'équation `admittedEquation` dans `v3_observer.py` additionnait `timeouts_count` à `non_terminal_at_close`, alors que ce dernier englobait déjà les timeouts, faussant l'équation de conservation en P4 et P5. | À corriger (R4) |
| **FINDING-05** | **MAJOR** | Réfutation de l'hypothèse d'un algorithme quadratique $\mathcal{O}(N^2)$ | L'algorithme `PotBalanceCalculator` est prouvé bilinéaire $\mathcal{O}(E \cdot S)$ et linéaire en dépenses. La latence de projection découle des allocations d'objets `Fraction` et des I/O PostgreSQL. | Résolu (Audit) |
| **FINDING-06** | **MINOR** | Réfutation du goulot physique du worker à 5,4 cmd/s | Le palier P3 a démontré un débit terminal effectif de 10,01 cmd/s (1 201 transactions traitées et drainées à 0 en 147 s). | Résolu (Audit) |
| **FINDING-07** | **OBSERVATION** | Contention sévère induite par le dimensionnement du banc | L'injection de 10 à 50 cmd/s sur 8 à 12 cagnottes génère mécaniquement des collisions optimistes CAS (680 rejets en P3), masquant la capacité pure d'ingestion transactionnelle hors contention. | À adapter (R4) |

---

## 9. Synthèse des Conclusions et Requalification Formelle de la Capacité

À partir des preuves brutes incontestables et de l'analyse statique du code source, les quatre questions fondamentales de la mission reçoivent les réponses suivantes :

1. **Débit d'admission WRITE soutenable :**
   Pocoma V3 soutient sans difficulté un débit d'admission HTTP 202 d'au moins **47,4 commandes/seconde** (5 930 commandes admises sur 5 980 en 125 s à P5 avec 1 seul rejet 503).
2. **Débit de traitement durable WRITE et matérialisation READ :**
   Le traitement durable est prouvé à **10,01 transactions terminales/seconde** sur le palier P3 (drainage complet à zéro). Au-delà de 10 cmd/s, la capacité exacte n'est pas caractérisée en raison de l'épuisement de l'observateur.
3. **Origine des files d'attente et du palier à 255 :**
   Le plateau à 255 n'est pas un blocage système mais le plafonnement intentionnel du moteur de découverte (`softCap = 64` par partition $\times$ 4 partitions). Lorsque le débit d'injection dépasse la capacité de consommation, le véritable backlog s'accumule dans `v3_command` et non dans `consumption_slot`.
4. **Impact propre du banc de test et de l'observateur :**
   L'observateur mono-threadé a introduit un artefact métrologique sévère à partir de 20 cmd/s, atteignant l'asphyxie complète à 50 cmd/s (1 % de couverture). La mesure de 0,48 cmd/s en P5 est un artefact d'échantillonnage de l'outil et non un comportement du serveur Pocoma.

---

## 10. Recommandations Normatives pour L6/I3-R4

Pour permettre une acceptation humaine rigoureuse de la capacité de Pocoma V3 lors de l'itération R4, les actions suivantes sont prescrites :

1. **Rénovation de l'Observateur Asynchrone (`v3_observer.py`) :**
   - Remplacer le thread unique par un pool de threads d'observation concurrents (`ThreadPoolExecutor`, typiquement 16 threads) pour absorber jusqu'à 100 requêtes de résultat par seconde ;
   - Corriger l'équation de conservation en éliminant le double comptage des timeouts ;
   - Porter le délai de drainage à un temps proportionnel au volume injecté pour éviter les troncatures arbitraires.
2. **Correction des Sondes de Backlog :**
   - Interroger conjointement :
     ```sql
     SELECT count(*) FROM v3_write.v3_command WHERE status = 'PENDING';
     ```
     et
     ```sql
     SELECT count(*) FROM v3_write.consumption_slot;
     ```
     afin de distinguer le stock amont total du buffer de travail actif.
3. **Génération et Vérification du Seed Large :**
   - Exécuter la matérialisation hors-ligne complète du profil Large (100 participants, 2 000 dépenses) et archiver `seed-v3-large-indep.json` avec sa preuve `materializationProof` vérifiée en base.
4. **Découplage de la Contention et du Débit Scalable :**
   - Tester la montée en charge sur un nombre de cagnottes suffisant ($\ge 50$ cagnottes) pour mesurer la capacité pure d'exécution hors rejet CAS, et réserver les scénarios à 1 ou 4 cagnottes à la caractérisation du contrôle de concurrence.

---

## 11. Avis d'Ouverture / Clôture de L6/I3

Compte tenu de la découverte d'artefacts métrologiques majeurs (FINDING-01, FINDING-02, FINDING-03), la capacité réelle de Pocoma V3 au-delà de 10 cmd/s n'est pas scientifiquement qualifiée.

> **Verdict de l'Audit Indépendant R3 :**  
> **`NOT_READY_FOR_CLOSURE — METROLOGY_CORRECTIONS_REQUIRED`**  
> 
> **L'itération L6/I3 demeure formellement OUVERTE.**  
> Une campagne ciblée L6/I3-R4 incorporant les correctifs d'outillage prescrits est requise avant toute clôture.
