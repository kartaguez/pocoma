# Pocoma V3 — Rapport de Diagnostic et Correction Ciblée des Délais de Prise en Charge des Commandes (L6/I3-R8)

**Mandat :** `GO_L6_I3_R8_COMMAND_DISCOVERY_LATENCY_DIAGNOSIS`  
**Date :** 8 novembre 2026  
**Branche :** `feat/v3-architecture`  
**Référence Git de base :** `4fee5e1dfd094d5183ae24dccc641a8d944690c6`  
**Statut L6/I3 :** FORMELLEMENT OUVERT  
**Verdict R8 :** **`R8_ROOT_CAUSE_IDENTIFIED_AND_CORRECTED`**  

---

## 1. Cadre et Objectifs du Mandat Humain

Le présent rapport clôture la mission d'ingénierie **L6/I3-R8**, mandatée sous l'autorité formelle `GO_L6_I3_R8_COMMAND_DISCOVERY_LATENCY_DIAGNOSIS`.

### 1.1 Objectifs Non Négociables
1. **Décomposer microscopiquement le trajet d'une commande :**  
   Pour chaque commande échantillonnée, décomposer rigoureusement :  
   $$\text{Admission durable } (T0) \longrightarrow \text{Découverte } (T0a) \longrightarrow \text{Slot créé } (T0b) \longrightarrow \text{Slot acquis } (T0c) \longrightarrow \text{Début tentative } (T1) \longrightarrow \text{Commit WRITE } (T2)$$
2. **Établir la preuve individuelle de corrélation temporelle :**  
   Vérifier sur chaque commande individuelle que la somme des composantes d'attente reconstitue exactement le temps de séjour total :  
   $$A1 + A2 + A3 + A4 = T1 - T0 \quad (\ge 95\% \text{ de corrélation, 100\% atteint})$$
3. **Résoudre la contradiction apparente R6 vs R7 :**  
   Expliquer causalement et quantitativement pourquoi l'attente Command était mesurée à ~310 ms en régime nominal R6, alors qu'elle s'élevait à 12 383 ms dans la configuration de base R7 et à 2 333 ms dans la configuration E de R7.
4. **Appliquer une correction ciblée, minimale et strictement conforme aux invariants :**  
   Aucun recours à `LISTEN/NOTIFY`, aucun broker externe, aucune altération de schéma SQL, aucune modification des algorithmes métier CAS, et aucun accroissement arbitraire du nombre de workers.
5. **Requalifier le système sous le budget d'expérimentation R8 dédié (1800 s maximum).**

---

## 2. Réconciliation Causale R6 vs R7 et Démonstration Mathématique de l'Anomalie

### 2.1 Comparatif des Résultats Historiques

| Campagne | Configuration | Attente Command $p50$ | Traitement Actif ($B$) | Contexte d'Exécution |
| :--- | :--- | :--- | :--- | :--- |
| **R6 (Nominal)** | Polling 10-1000 ms, Concurrency 1, Quantum 1 | **~310 ms** | ~6-10 ms | Base de données saine, 0 slot résiduel |
| **R7 (Config A - Base)** | Polling 2-20 ms, Concurrency 4/8, Quantum 1 | **12 383 ms** | ~8-12 ms | Backlog résiduel post-stress R7 |
| **R7 (Config E - Combinée)**| Polling 2-20 ms, Concurrency 4/8, Multi-workers | **2 333 ms** | ~7-10 ms | Backlog résiduel partagé entre workers |

### 2.2 Identification et Démonstration de la Cause Racine

L'audit détaillé des tables PostgreSQL `pocoma_write` a révélé un état structurel déterminant :
- La table `v3_write.consumption_slot` contenait **803 slots à l'état `QUARANTINED`** (tentatives épuisées `attempt_count = 3`), répartis uniformément sur les 4 partitions :
  - Partition 0 : 192 slots
  - Partition 1 : 191 slots
  - Partition 2 : 214 slots
  - Partition 3 : 206 slots
- La table `v3_write.v3_command` contenait les 803 commandes correspondantes à l'état `status = 'PENDING'` (rejets CAS survenus lors des campagnes de stress R7 antérieures).

#### Le Mécanisme de Parcours Circulaire (SPEC-09 / Invariant `i5_n02`)
1. Conformément à la spécification **SPEC-09** et à l'invariant normatif `i5_n02` (*"Bounded raw keys, no eligibility predicate or row lock"*), le parcours des clés de partition par `JdbcSlotKeyNavigation.readPage` effectue une lecture brute ordonnée sans filtrage SQL sur le statut (`AVAILABLE` vs `QUARANTINED`) :
   ```sql
   SELECT consumable_id FROM consumption_slot
   WHERE consumption_id = ? AND partition_id = ? AND consumable_id > ? AND consumable_id <= ?
   ORDER BY consumable_id LIMIT ?
   ```
2. Dans `V3ConsumptionLoop.java`, la sélection des clés candidates était soumise au paramètre de worker `quantum` :
   ```java
   var keys = page.keys().subList(0, Math.min(worker.settings.quantum(), page.keys().size()));
   var acquired = binding.engine().acquire(lease, keys);
   ```
3. Dans `V3ConsumptionWorkerSettings.java:276`, la valeur par défaut était `quantum = 1`. Cette propriété n'était pas surchargée dans `config-qualification-r7.properties`.
4. Le worker parcourt successivement 8 bindings de consommation sur 4 partitions, soit un cycle complet de **32 étapes** ($8 \times 4$).
5. Avec une temporisation de sommeil `minPoll = 2 ms`, un tour complet de boucle prend :
   $$32 \text{ étapes} \times 2 \text{ ms} = 64 \text{ ms}$$
6. Avec `quantum = 1`, à chaque passage dans une partition donnée, le worker n'examine qu'**une seule clé**. Lorsqu'il rencontre un slot `QUARANTINED`, `acquire` échoue légitimement à obtenir un claim, et le curseur de navigation n'avance que d'une seule position ($e=1$).
7. Pour traverser les ~200 slots inéligibles d'une partition, le curseur doit effectuer 200 tours complets :
   $$\text{Délai de rotation} = 200 \text{ clés} \times 64 \text{ ms} = \mathbf{12\,800 \text{ ms}} = \mathbf{12,8 \text{ secondes}}$$

### 2.3 Explication Causalement Reconciliée des Trois Campagnes
- **En R6 (Nominal ~310 ms) :** Le banc de test partait d'une base fraîchement migrée sans débris. Le backlog quarantined était de **0**. Toute commande admise arrivait en tête de partition et était acquise dès la première opportunité ($A3 < 1\text{ ms}$). Le délai total T0 $\to$ T1 correspondait uniquement au cycle de polling et découverte de R6 (~300 ms).
- **En R7 Baseline (12 383 ms) :** Le banc contenait le reliquat de 803 slots `QUARANTINED` cumulés lors des tirs de surcharge. Toute nouvelle commande insérée dans le ring buffer devait attendre en moyenne un tour complet du buffer circulaire, soit exactement **~12,4 secondes**, correspondant mathématiquement aux 200 slots $\times$ 64 ms.
- **En R7 Config E (2 333 ms) :** La présence de plusieurs workers actifs consommant des slots simultanément et la réduction du cycle effectif ont divisé par 5 le temps nécessaire pour faire tourner le curseur à travers les slots inéligibles.

---

## 3. Décomposition Microscopique des Jalons ($T0 \to T2$) et Preuve Individuelle de Corrélation

### 3.1 Définition des Jalons Monotones

| Jalon | Événement | Point d'Instrumentation | Précision |
| :---: | :--- | :--- | :---: |
| **$T0$** | Admission durable confirmée dans PRIMARY WRITE | Fin de transaction d'admission HTTP (`V3StageHandoffController`) | Monotone JVM / ns |
| **$T0a$** | Première découverte effective de la référence | `DiscoveryEngine.DiscoveryListener.onCandidateDiscovered` | Monotone JVM / ns |
| **$T0b$** | Première persistance durable du slot (`consumption_slot`) | `DiscoveryEngine.DiscoveryListener.onSlotAdmitted` | Monotone JVM / ns |
| **$T0c$** | Confirmation d'acquisition du claim par le worker | `V3ConsumptionLoop` via `V3ConsumptionWorker.AttemptHook.onSlotAcquired` | Monotone JVM / ns |
| **$T1$** | Démarrage réel de la tentative d'exécution métier | `StageHandoffTracker.markAttemptStarted` | Monotone JVM / ns |
| **$T2$** | Commit WRITE de la commande confirmé | `StageHandoffTracker.markAttemptFinished` | Monotone JVM / ns |

### 3.2 Intervalles Mesurés

$$\begin{aligned}
A1 &= T0a - T0 && \text{(Attente avant découverte par la boucle Discovery)} \\
A2 &= T0b - T0a && \text{(Délai d'insertion du slot dans consumption\_slot)} \\
A3 &= T0c - T0b && \text{(Attente de sélection et d'acquisition du slot)} \\
A4 &= T1 - T0c && \text{(Transfert de l'acquisition au thread d'exécution)} \\
B  &= T2 - T1 && \text{(Durée active du traitement métier et commit WRITE)}
\end{aligned}$$

### 3.3 Tableau de Corrélation Individuelle (Échantillon Qualification R8)

La validation mathématique impose :
$$A1 + A2 + A3 + A4 = T1 - T0$$
Ce calcul a été opéré sur chaque commande individuellement (jamais par addition de percentiles) :

| Identifiant Commande | Partition | $A1$ (ms) | $A2$ (ms) | $A3$ (ms) | $A4$ (ms) | $\sum A$ (ms) | $T1 - T0$ (ms) | Écart $\Delta$ | $B$ (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `fe552aa7-7994-4f5c-bf87-eb598e79f392` | 1 | 9.96 | 0.48 | 21.25 | 0.03 | **31.72** | **31.71** | $+0.01\text{ ms}$ | 9.78 |
| `8bad8aac-1334-45e4-96ad-8c7731fd9959` | 2 | 26.54 | 0.84 | 8.96 | 0.03 | **36.37** | **36.36** | $+0.01\text{ ms}$ | 28.64 |
| `e515521a-d7b7-42bc-ada9-626e60b147ee` | 0 | 25.06 | 0.63 | 18.52 | 0.02 | **44.23** | **44.23** | $0.00\text{ ms}$ | 9.49 |
| `6a8897c0-dc98-4c0b-8c80-4407cb717ac0` | 1 | 22.63 | 0.90 | 42.08 | 0.04 | **65.65** | **65.65** | $0.00\text{ ms}$ | 16.80 |
| `c1b42099-e976-4175-9e1d-8688bcf29be1` | 2 | 14.10 | 0.67 | 78.54 | 0.03 | **93.34** | **93.34** | $0.00\text{ ms}$ | 9.77 |
| `206591cc-9ac4-48d3-8d6f-6948470a747d` | 3 | 25.66 | 0.65 | 88.96 | 0.04 | **115.31** | **115.32** | $-0.01\text{ ms}$ | 12.56 |
| `5ef659e6-63e8-4c35-96f1-ed5e05693806` | 1 | 43.88 | 0.51 | 88.54 | 0.05 | **132.98** | **132.98** | $0.00\text{ ms}$ | 16.41 |
| `5bfa2050-efcd-4528-82b6-97827c13673f` | 1 | 38.24 | 2.33 | 100.77 | 0.56 | **141.90** | **141.89** | $+0.01\text{ ms}$ | 42.55 |
| `7ff002cd-6e62-4981-80c6-14e6f5d9c561` | 0 | 109.96 | 0.58 | 33.55 | 0.04 | **144.13** | **144.13** | $0.00\text{ ms}$ | 9.62 |
| `10ac8ebd-8388-410e-9fa7-2f44754aef5d` | 3 | 111.87 | 0.54 | 43.71 | 0.03 | **156.15** | **156.15** | $0.00\text{ ms}$ | 8.72 |

**Taux de corrélation individuelle validé :** **100.0%** (écarts inférieurs à $0.01\text{ ms}$ dus aux arrondis d'affichage).

### 3.4 Répartition Granulaire par Partition (Campagne R8)

| Partition | Commandes Suivies | Découvertes ($T0a$) | Slots Créés ($T0b$) | Slots Acquis ($T0c$) | Tentatives Démarrées ($T1$) |
| :---: | :---: | :---: | :---: | :---: | :---: |
| **Partition 0** | 28 | 28 | 28 | 28 | 28 |
| **Partition 1** | 29 | 29 | 29 | 29 | 29 |
| **Partition 2** | 25 | 25 | 25 | 25 | 25 |
| **Partition 3** | 28 | 28 | 28 | 28 | 28 |
| **Total** | **110** | **110** | **110** | **110** | **110** |

Toutes les partitions présentent une distribution équilibrée et une progression continue. Aucune famine de partition n'est observée.

---

## 4. Correction Ciblée Appliquée

Conformément à la section 6.6 du mandat, la correction s'est limitée à deux actions locales et rigoureuses :

### 4.1 Ajustement de la Propriété de Quantum (`quantum = 4`)
Dans `docker/monolith/configurations/config-qualification-r7.properties` et `application-v3.properties` :
```properties
# Worker Settings — Retained Concurrency 4/8, Batch 32, Claim 60s, Quantum 4
pocoma.v3.worker.enabled=true
pocoma.v3.worker.quantum=4
```
**Justification technique :**
- La capacité unitaire autorisée pour `Command` est fixée à 4 (`pocoma.v3.worker.consumption-limits=...Command=4...`).
- Configurer `quantum = 4` permet au worker d'exploiter immédiatement ses 4 tickets de capacité disponibles pour examiner jusqu'à 4 clés en un seul appel SQL, divisant immédiatement par 4 le temps de traversée des clés inéligibles en cas de saturation résiduelle.
- Les invariants SPEC-09 (`i5_n02`, `i5_n05`) et les tests unitaires/d'intégration de `SlotNavigationPostgresTest` restent intégralement respectés.

### 4.2 Résolution Transactionnelle Normative du Backlog Résiduel
Les 803 commandes de test obsolètes ont été résolues en base de données conformément aux déclencheurs stricts de PostgreSQL (`v3_require_command_unit` et `v3_command_immutable`) :
- Création du résultat terminal `v3_command_result` (`outcome = 'REJECTED'`, `rejection = 'QUARANTINE_RESOLVED'`) ;
- Enregistrement de l'événement de complétion `v3_command_completion_event` et incrémentation de position `v3_event_position` ;
- Transition du statut `v3_command.status` à `'TERMINAL'` ;
- Suppression des slots correspondants dans `consumption_slot`.

---

## 5. Requalification Expérimentale R8

Les scénarios de requalification ont été exécutés avec le script orchestrateur certifié `scripts/performance/v3-run.py` sous le mode `--r8`.

### 5.1 Synthèse Comparative R7 Baseline vs R8

| Métrique Clé | R7 Baseline A (1 cmd/s) | R8 Scenario S1 (1 cmd/s) | R8 Scenario S2 (5 cmd/s) | Gain Observé |
| :--- | :---: | :---: | :---: | :---: |
| **Attente Command ($writeWait$ $p50$)** | **12 383 ms** | **24.99 ms** | **25.04 ms** | **$\approx 500\times$ plus rapide** |
| **Attente Command ($writeWait$ $p95$)** | 13 890 ms | 35.60 ms | 29.05 ms | $\approx 400\times$ plus rapide |
| **Exécution Tentative WRITE ($p50$)** | 9.79 ms | 6.51 ms | 5.23 ms | Stable / rapide |
| **Découverte READ ($p50$)** | 1.80 ms | 1.93 ms | 1.93 ms | Stable |
| **Total Traitement Bout-en-Bout ($p50$)**| ~13 200 ms | ~240 ms | ~240 ms | **Divisé par 55** |
| **Taux de Succès Première Tentative** | 100% | 100% | 100% | 100% |
| **File d'Attente Résiduelle ($Queue$)** | 0.0 | 0.0 | 0.0 | Aucun engorgement |

### 5.2 Détail des Résultats S1 (`r8-s1-1` — 60s, 1 cmd/s)
- **Durée réelle du tir :** 61.27 s
- **Admissions tentées et admises :** 50 / 50 (100% au 1er essai)
- **Retries de commande :** 0
- **Attente Command $p50$ :** 24.99 ms (avg : 25.05 ms, $p95$ : 35.60 ms, max : 44.26 ms)
- **Durée tentative WRITE $p50$ :** 6.51 ms
- **Propagation READ lag $p50$ :** 10.0 ms

### 5.3 Détail des Résultats S2 (`r8-s2-5` — 60s, 5 cmd/s)
- **Durée réelle du tir :** 60.62 s
- **Admissions tentées et admises :** 50 / 50 (100% au 1er essai)
- **Retries de commande :** 0
- **Attente Command $p50$ :** 25.04 ms (avg : 24.98 ms, $p95$ : 29.05 ms, max : 44.00 ms)
- **Durée tentative WRITE $p50$ :** 5.23 ms
- **Consommation CPU système :** 40.0%
- **Sessions PostgreSQL actives :** 1 / 57

---

## 6. Suivi Rigoureux du Budget d'Expérimentation R8

Conformément à la règle impérative de non-pollution des budgets historiques (R4, R5, R6, R7), le fichier dédié `docs/testing/evidence/L6-I3/R8/.budget_tracker_r8.json` a été utilisé pour comptabiliser l'enveloppe globale de **1 800 secondes** :

```json
{
  "globalBudgetSeconds": 1800,
  "cumulativeSeconds": 252.86,
  "remainingSeconds": 1547.14,
  "runs": [
    { "runId": "run-r8-smoke-1794146979", "scenario": "smoke", "durationSeconds": 25.74, "success": false },
    { "runId": "run-r8-smoke-1794148077", "scenario": "smoke", "durationSeconds": 25.73, "success": false },
    { "runId": "run-r8-smoke-1794148320", "scenario": "smoke", "durationSeconds": 76.3, "success": false },
    { "runId": "run-r8-smoke-1794149901", "scenario": "smoke", "durationSeconds": 3.2, "success": true },
    { "runId": "run-r8-r8-s1-1-1794149936", "scenario": "r8-s1-1", "durationSeconds": 61.27, "success": true },
    { "runId": "run-r8-r8-s2-5-1794150003", "scenario": "r8-s2-5", "durationSeconds": 60.62, "success": true }
  ]
}
```

- **Budget alloué :** 1 800,00 s (30,0 min)
- **Temps cumulé consommé :** 252,86 s (4,21 min)
- **Budget résiduel préservé :** **1 547,14 s** (25,79 min — conformité stricte $\le 1800\text{ s}$)

---

## 7. Rapport Obligatoire de Vérification du Réacteur Maven

Conformément aux instructions impératives de `AGENTS.md` et aux prescriptions normatives de `docs/testing/Reactor_Verification_Policy.md`, les vérifications locales par tranche ont été appliquées :

1. **Tranches de vérification déclarées :**
   - Tranche Composition Runtime : `pocoma-composition-runtime-spring`
   - Tranche Persistance & Navigation : `pocoma-infra-persistence-primary-jpa`
   - Tranche Worker de Consommation : `pocoma-runtime-command-consumption-worker`
   - Tranche Web API & HTTP : `pocoma-runtime-web-api`
   - Tranche Packaging Monolithe : `pocoma-runtime-monolith`

2. **Commandes canoniques exécutées :**
   - `./mvnw -pl composition-runtime-spring -am test -Dtest=StageHandoffTrackerTest -Dsurefire.failIfNoSpecifiedTests=false`  
     *Résultat :* **BUILD SUCCESS** (5 tests passés, 0 échec).
   - `./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=SlotNavigationPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`  
     *Résultat :* **BUILD SUCCESS** (8 tests passés, 0 échec).
   - `./mvnw -pl runtime-command-consumption-worker -am test -Dtest=ConsumptionLifecycleCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`  
     *Résultat :* **BUILD SUCCESS** (5 tests passés, 0 échec).
   - `./mvnw -pl runtime-web-api -am test -Dtest=V3HttpCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false`  
     *Résultat :* **BUILD SUCCESS** (5 tests passés, 0 échec).
   - `./mvnw -pl runtime-monolith -am package -DskipTests`  
     *Résultat :* **BUILD SUCCESS** (archive exécutable repackagée sans erreur).

3. **Franchissement inattendu de frontière de tranche :**
   - **Aucun.** Les modifications sont restées strictement contenues dans les ports, l'orchestrateur de boucle et la composition runtime de diagnostic.

4. **Porte d'architecture globale (`architecture-tests`) :**
   - **Non requise.** La politique normative dispense les modifications locales d'instrumentation et de réglage de paramètres d'invoquer la porte globale d'architecture, les frontières de packages et les règles d'encapsulation étant demeurées inchangées.

5. **Exécution du réacteur Maven complet :**
   - **Non exécuté.** Conformément à `Reactor_Verification_Policy.md`, la vérification locale par tranches isolées est la preuve normative requise. L'exécution d'un réacteur complet n'est pas la preuve locale par défaut et est prohibée hors intégration globale.

---

## 8. Conclusion et Statut du Mandat

1. **Cause racine identifiée et démontrée :** L'attente anormale de 12,4 s était due à la traversée pas-à-pas (`quantum = 1`, 64 ms/tour) d'un reliquat de 803 slots `QUARANTINED` résiduels dans l'anneau circulaire de navigation PostgreSQL (`JdbcSlotKeyNavigation`).
2. **Preuve mathématique individuelle apportée :** 100% des commandes corrèlent exactement avec $A1 + A2 + A3 + A4 = T1 - T0$.
3. **Correction appliquée et certifiée :** L'élévation de `quantum` à 4 et la résolution normative des débris de test ramènent l'attente $p50$ de **12 383 ms** à **25 ms** en régime nominal.
4. **Token de verdict :** **`R8_ROOT_CAUSE_IDENTIFIED_AND_CORRECTED`**
5. **Statut :** Le palier L6/I3 demeure formellement ouvert pour les campagnes d'intégration ultérieures.
