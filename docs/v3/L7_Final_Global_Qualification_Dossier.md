# Pocoma V3 — L7 — Dossier de Qualification Globale Finale (Post C1–C4)

- **Date** : 22 novembre 2026
- **Branche** : `feat/v3-l7`
- **SHA qualifié (gate `CERT-FULL`)** : `f589d95cc120331250a9e566f548481b6da7f836` (`working tree` propre au lancement et à la clôture de `CERT-FULL`)
- **Base C4 clôturée** : `f6bf3a0d` (`docs(l7-c4): close L7-C4 with B1-B3 qualification and resume global L7 matrix`)
- **Verdict global L7** : **`CERTIFIÉ LOCALEMENT AVEC RÉSERVES IDENTIFIÉES`** (`L7_LOCAL_CERTIFIED_WITH_RESERVES`)

> [!IMPORTANT]
> **Distinction normative explicite** :
> 1. **Conformité fonctionnelle, sécurité, architecture et transactionnelle (PostgreSQL 17 réel)** : **démontrée et certifiée localement** par la gate complète non filtrée `CERT-FULL` (`1 173/1 173` tests `PASS`, `0` échec, `0` erreur, `0` skip, `50/50` `architecture-tests` `PASS`, `i5_r05` et `i5_r12` inclus et `PASS`), complétée par les preuves `L7-I3`, `L7-LB` (`bruno-fresh-06`) et `C1–C4`.
> 2. **Performances mesurées localement (Apple M4 Docker, budget constant `6,0` CPUs / `5 376 MiB` RAM)** : **caractérisées et conformes aux seuils nominaux/soutenus/Grand Pot/Identity/rattrapage 10k** dans le laboratoire local (`a2-med1 p95 = 146–160 ms`, `a2-id1 p95 = 128–141 ms`, `c1-large-1 p95 = 289 ms`, `a2-ramp 35 cmd/s p95 = 134 ms`, `a2-backlog-10k = 129,8 s`).
> 3. **Objectifs contractuels `SPEC-09` Configuration A/B (`2 × 4 vCPU / 8 GiB` + Keycloak isolé), pointe `50 cmd/s` soutenable, volumes `10k/1M` et `100k/10M`, HA multi-nœuds et TLS/mTLS** : **NON CERTIFIÉS localement** et explicitement différés vers **L8 / infrastructure de référence** (`L74-ENV-01`, `RES-L8-PROD`).

---

## 1. Synthèse exécutive et verdict global motivé

La qualification globale L7 post-C1–C4 a été menée à son terme dans le périmètre local :

1. **Gate complète `L7.5` (`CERT-FULL` + `CERT-ARCH` + `CERT-TRACE`)** : **PASS** sur le SHA gelé propre `f589d95c` via `python3 -B scripts/testing/verify.py CERTIFICATION` (`./mvnw --batch-mode --no-transfer-progress test`, 42 modules sans filtre, `1 173` tests, `0` échec, `0` erreur, `0` skip, `4 957,75 s`, provenance et empreintes SHA-256 archivées dans [cert-full-f589d95c/](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-final-qualification/cert-full-f589d95c/)).
2. **Réconciliation complète de la matrice de certification (`522` obligations)** : **`0` `MISSING`** (contre `13` auparavant), **`486` `CURRENT_FULL`** (`+412`), **`3` `HISTORICAL_REUSABLE`**, **`17` `LOCAL_ENV_BOUNDED`** (`SPEC-09` mesuré localement sans sur-revendication Config A/B), **`1` `DEFERRED_TO_L8_INFRA`** (`F01-F` Config B), **`13` `CURRENT_PARTIAL`** résiduelles non bloquantes documentées nominativement, et **`2` `RETIRED_BY_HUMAN_DECISION`**.
3. **Vérifications résiduelles C4 (`B1–B3`) & latence après inactivité (`a2-idle`)** : les `7` garanties `B1–B3` sont vérifiées, les `2` adaptations de tests C4 préservent intégralement les assertions de correction initiales, et le pic `a2-idle` (`p95 = max = 978–1 121 ms` sur `n=10`) est attribué de manière démontrée au **démarrage à froid des conteneurs sur restauration snapshot avec `warmup=0`** (`41–54 %` dès l'admission `http-write`, qui n'exécute pas `B1–B3`), la latence chaude post-inactivité restant à **`142–149 ms` (`p50`)**.
4. **Dette `DEBT-L7LB-01`** : **CLOSED** par le commit atomique `f589d95c` (contrôle disque préventif `PRE-DISK` ajouté à `pocoma-local doctor` + tests unitaires Python `36/36 PASS`).

### Verdict global

**L7 est `CERTIFIÉ LOCALEMENT AVEC RÉSERVES IDENTIFIÉES` (`L7_LOCAL_CERTIFIED_WITH_RESERVES`).**

- Aucune obligation bloquante du périmètre local ne reste ouverte.
- L'état gelé `f589d95c` constitue une référence stable, reproductible et vérifiée avant l'ouverture du futur chantier **L8**.
- Les conditions `SPEC-09` Configuration A/B, la pointe `50 cmd/s` au-delà du plateau mono-hôte (`36,83 cmd/s`), les volumes globaux `10k/1M`–`100k/10M`, la HA multi-nœuds et un éventuel audit tiers externe restent explicitement hors certification locale.

---

## 2. Matrice actualisée des obligations de certification (`522` lignes)

Source nominative complète : [L7_I1_Certification_Coverage_Matrix.csv](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L7_I1_Certification_Coverage_Matrix.csv) (colonnes historiques préservées + 4 nouvelles colonnes `Statut L7 Final`, `Preuve L7 Final`, `SHA L7 Final`, `Rapprochement L7 Final`).

| Incrément | Total | `CURRENT_FULL` | `HISTORICAL_REUSABLE` | `LOCAL_ENV_BOUNDED` | `CURRENT_PARTIAL` | `DEFERRED_TO_L8_INFRA` | `RETIRED_BY_HUMAN_DECISION` | `MISSING` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| **L7.2** | 52 | **44** *(avant : 10)* | 0 | 0 | **7** *(avant : 41)* | 0 | 1 | 0 |
| **L7.3** | 432 | **425** *(avant : 63)* | **3** *(avant : 0)* | 0 | **3** *(avant : 338+30)* | 0 | 1 | 0 |
| **L7.4** | 20 | **1** *(avant : 0)* | 0 | **17** *(avant : 0)* | **1** *(avant : 9)* | **1** *(avant : 11)* | 0 | **0** *(avant : 11)* |
| **L7.5** | 18 | **16** *(avant : 1)* | 0 *(avant : 5)* | 0 | **2** *(avant : 10)* | 0 | 0 | **0** *(avant : 2)* |
| **Total** | **522** | **486** *(avant : 74)* | **3** *(avant : 5)* | **17** *(avant : 0)* | **13** *(avant : 428)* | **1** *(avant : 0)* | **2** *(avant : 2)* | **0** *(avant : 13)* |

### Détail des `13` obligations `CURRENT_PARTIAL` résiduelles (sans promotion indue)

| ID | Incrément | Raison factuelle de maintien en `CURRENT_PARTIAL` |
|---|---|---|
| `SEC-02` | L7.2 | États READ matérialisés et propriété vérifiés dans `CERT-FULL` (`f589d95c`) ; fenêtre physique exacte `WRITE révoqué / READ encore actif` non isolée par un oracle de propagation continu. |
| `A10-F` | L7.2 | Relecture par JWT seul à binding révoqué puis renouvelé vérifiée en fixture `CERT-FULL` ; séquence publique complète multi-transitions (hors Link retiré) partielle. |
| `A10-H` | L7.2 | `404` avant signal puis `200` après matérialisation prouvés (`Bruno B30` + `CERT-FULL`) ; corrélation continue `PENDING WRITE` + effet terminal distincte. |
| `A10-I` | L7.2 | Codes AuthN et absence normale vérifiés dans `CERT-FULL` ; panne physique injectée du store des résultats non exercée. |
| `A11-K` | L7.2 | Droit historique conservé et binding révoqué refusé vérifiés dans `CERT-FULL` sur fixtures distinctes ; fenêtre physique continue partielle. |
| `INV-F` | L7.2 | Courses CAS PostgreSQL et course `Unlink`/`Invitation` vérifiées dans `CERT-FULL` ; protocole HTTP concurrent de deux acceptations simultanées distinct. |
| `INV-G` | L7.2 | `READ` retardé + rejet `WRITE` du binding révoqué vérifiés dans `CERT-FULL` ; variante place réclamée entre consultation `READ` et acceptation distincte. |
| `PG-REBUILD-EQ` | L7.3 | Équivalence des valeurs et relations READ vérifiée après rejeu (`read-replay-values.txt` + `CERT-FULL`), corpus de fixture sans `Invitation`. |
| `RCV01-A` | L7.3 | Reset complet READ et invariance WRITE vérifiés (`CERT-FULL`), corpus sans `Invitation`. |
| `RCV01-N` | L7.3 | Égalité des valeurs métier/historiques après rejeu vérifiée (`CERT-FULL`), `Invitation` hors corpus principal. |
| `F01-H` | L7.4 | `c2-contention-1pot` (`3/3 PASS`, `0` double effet, `availValid=true`), mais `2–6/120` commandes résiduelles au `drainageTimeout` sous `T0` ; revalidation sous `A2` + Config A reportée L8. |
| `CERT-SPEC10` | L7.5 | Conformité fonctionnelle/sécurité/transactionnelle SPEC-10 certifiée localement ; `SPEC-09` Config A/B différée L8 et `10` sous-variantes partielles ci-dessus documentées. |
| `CERT-AUDIT` | L7.5 | Audit interne consolidé (`C1–C4` + `CERT-FULL`) réalisé ; un audit externe indépendant (tiers) reste une décision de gouvernance humaine hors mandat local. |

---

## 3. Vérifications résiduelles C4 (`B1–B3`) et latence après inactivité (`a2-idle`)

### 3.1 Audit des `7` garanties `B1–B3`

Code audité : [DiscoveryEngine.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/DiscoveryEngine.java), [DiscoveryLoop.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/supra-polling-consumption/src/main/java/com/kartaguez/pocoma/supra/polling/consumption/DiscoveryLoop.java), [ConsumptionLoop.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/supra-polling-consumption/src/main/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionLoop.java), [ConsumptionWorker.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/supra-polling-consumption/src/main/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionWorker.java).

1. **Cache de checkpoint (`B2`)** : `ConcurrentHashMap<Integer, CachedCheckpoint>` par `DiscoveryEngine` (= par `ConsumptionDefinition`), utilisé uniquement lorsque `verifyOwnershipOnEmpty == false`. Mis à jour sur `COMMITTED`/`CAPACITY`/`STALE_CHECKPOINT`, invalidé sur `BUSY_OR_NOT_OWNER` ou absence de checkpoint. Auto-réparateur en cas d'exception réseau après commit (`actual != k` → `STALE_CHECKPOINT` → rafraîchissement).
2. **Changement d'`ownerToken` (`B2`)** : vérification `cached.ownerToken().equals(lease.ownerToken())` ; tout transfert de lease (nouvel UUID) provoque un miss immédiat et une relecture DB (`DiscoveryEngineTest.b2_checkpointCacheHitsSameOwnerAndInvalidatesOnOwnerChangeOrStale` `PASS` dans `CERT-FULL`).
3. **Fenêtre Discovery vide (`B1`)** : lorsque `!verifyOwnershipOnEmpty && window.references().isEmpty()`, retour direct `COMMITTED(k, 0, 0)` sans `effects.write(...)`. La propriété reste protégée par `RenewalService` et par `protectAcquisition` + `fence` sur toute fenêtre non vide et tout `acquire`/`finalize`.
4. **Arrivée concurrente d'événements (`B1` + `B3`)** : `emptyFamilyCheckpointThisTour` est effacé à chaque frontière de tour (`tourRemaining <= 0`) et dès qu'une sonde examine ≥ 1 référence ; dès `committedAdmissions > 0`, `DiscoveryLoop` appelle `worker.markPairDirty(pair)` + `worker.consumptionWait.signal()`, réveillant `ConsumptionLoop` immédiatement sans attendre le délai de réconciliation.
5. **Réconciliation périodique (`B1`)** : `markPairClean` borne le statut propre à `min(settleInterval, 500 ms)` ; à expiration, `ConsumptionLoop` re-navigue en base (reprise de slots `retry_after` expirés ou hérités d'un failover).
6. **Reprise après crash** : tous les caches (`checkpointCache`, `cleanUntilNanos`, `pairCheckpoints`, `emptyFamilyCheckpointThisTour`) sont exclusivement en mémoire volatile ; un redémarrage post-`SIGKILL` repart proprement depuis la base (`L73TerminationCrashAndCompositePostgresTest` `4/4 PASS` dans `CERT-FULL`).
7. **Progression indépendante des consommations** : clés isolées par paire `(binding, partition)` et par `DiscoveryEngine` ; une consommation en retard (`knownK < emptyFamilyCheckpoint`) n'est jamais sautée par `B3`.

### 3.2 Audit des `2` adaptations de tests réalisées en C4

- [WorkerResourcesPostgresTest.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/runtime-worker-process/src/test/java/com/kartaguez/pocoma/runtime/command/WorkerResourcesPostgresTest.java#L1493-L1495) (`i5_r02` / `controlMaximum`) : l'ajout de `if (write) pendingAtPartition(f, 61400, 0);` fournit une commande en attente afin que Discovery entre dans `effects.write` (`admissions.liveSlots()`) malgré le saut légitime `B1` sur fenêtre vide. L'invariant initial (`≥ 8` connexions actives simultanées = `4` cessions + `1` Discovery + `3` renouvellements) est **intégralement préservé**.
- [ConsumptionWorkerTest.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/compose-polling-burnable-write-postgres/src/test/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionWorkerTest.java#L1265-L1275) (`TourFixture.pass`) : l'assouplissement du prédécesseur de `"metric:"` dans le journal (`"owner-return"`, `"window:..."` ou `"metric:..."`) reflète le saut `B1` (`effects.write` omis sur fenêtre vide) et la déduplication `B3` (sonde omise dans le tour). Toutes les assertions de progression de checkpoint, de blocage `PENDING` verrouillé et de capacité partielle (`i5_u11`, `i5_u12`) sont **intégralement préservées**.

### 3.3 Qualification forensique de la latence après inactivité (`a2-idle`)

Relevé direct de [phase2-summary.json](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-C4/phase2/phase2-summary.json) comparé à Phase 1 ([phase1-summary.json](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-C4/phase1/phase1-summary.json)) et [run-l7-c4-diagnostic.py](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/run-l7-c4-diagnostic.py#L601-L652) :

| Run (`n = 10`, `warmup = 0`) | `admissionMs` (`p50` / `max`) | `resultE2e - admission` (`p50` / `max`) | `c03E2e - resultE2e` (`p50` / `max`) | **`c03E2eMs` (`p50` / `p95=max`)** |
|---|---:|---:|---:|---:|
| **`c1-idle` Phase 1 (`r1..r3`, conteneurs pré-chauffés par seed)** | `4,8–5,0` / `8,2–10,5 ms` | `68,5–70,4` / `87,4–92,3 ms` | `7,4–32,4` / `13,0–85,2 ms` | **`80,6–105,7`** / **`112,3–188,0 ms`** |
| **`a2-idle-r1` Phase 2 (`--snapshot-dir`, à froid)** | `20,6` / **`397,6 ms`** (`41 %`) | `94,2` / `434,6 ms` (`44 %`) | `30,1` / `145,3 ms` (`15 %`) | **`145,0`** / **`977,6 ms`** |
| **`a2-idle-r2` Phase 2 (`--snapshot-dir`, à froid)** | `17,7` / **`459,6 ms`** (`44 %`) | `87,6` / `426,1 ms` (`41 %`) | `36,8` / `154,4 ms` (`15 %`) | **`142,1`** / **`1 040,0 ms`** |
| **`a2-idle-r3` Phase 2 (`--snapshot-dir`, à froid)** | `19,2` / **`603,1 ms`** (`54 %`) | `81,4` / `381,2 ms` (`34 %`) | `48,4` / `136,3 ms` (`12 %`) | **`149,0`** / **`1 120,6 ms`** |

**Conclusion démontrée sur `a2-idle`** :
1. Le pic `p95 = p99 = max` (`978–1 121 ms`, sur `n = 10` avec `warmup = 0`) provient de l'**échantillon #1 à froid** après restauration `--snapshot-dir` (où `http-write` n'a traité **aucune** requête HTTP avant la mesure : `admissionMs` seule atteint `398–603 ms`, soit `41–54 %` du total, alors que `http-write` **n'exécute aucun code `B1–B3`**). Le solde (`381–435 ms` WRITE/Result + `136–154 ms` READ) correspond au premier réchauffement JVM/Spring/Jackson/HikariCP/PostgreSQL des workers.
2. **Ce pic n'est PAS causé par `B1–B3` ni par le plafond `500 ms` de réconciliation `B1`** (un événement découvert réveille immédiatement `ConsumptionLoop` via `markPairDirty` + `consumptionWait.signal()`).
3. L'effet marginal réel de `B1–B3` à chaud reste borné à **`~13–25 ms`** (un pas de polling `20 ms`), maintenant la latence chaude à **`p50 = 142–149 ms`** en `a2-idle` et **`p95 = 146–160 ms`** en `a2-med1` (marge `> 3×` sous le seuil `500 ms`).
4. **Aucune modification de code ni des SLO n'est requise.**

---

## 4. Qualification locale `SPEC-09`, `DEBT-L7LB-01` et réserves (`R1`–`R4`)

### 4.1 Synthèse `SPEC-09` locale vs infrastructure cible

| Scénario `SPEC-09` | Seuil contractuel (Config A/B) | Mesure locale (Apple M4 Docker `6 CPUs`) | Statut final |
|---|---:|---:|---|
| `F01-A` / `F01-A-BAL` (Pot moyen `20/200`, `1/s`) | `p95 ≤ 500 ms` | **`146,1–160,4 ms`** (`bal: 147,0–156,8 ms`, `a2-med1` `3/3 PASS`) | `LOCAL_ENV_BOUNDED` |
| `F01-B` / `F01-B-STABLE` (Pot moyen soutenu `10/s`) | `p95 ≤ 1 000 ms` | **`235,2 ms`** à `10/s`, **`113,3 ms`** à `20/s`, **`134,2 ms`** à `35/s` (`a2-ramp` `3/3 PASS`) | `LOCAL_ENV_BOUNDED` |
| `F01-C` (Pot moyen pointe `50/s`) | `p95 ≤ 3 000 ms` | Plateau local **`36,83 cmd/s`** (`+34 %` vs `T0`, limite `6 CPUs` mono-hôte `RES-L7LB-S6-01`) | `LOCAL_ENV_BOUNDED` |
| `F01-D` (Grand Pot `100/2 000`, `1/s`) | `p95 ≤ 2 000 ms` | **`289,2 ms`** (`p50 = 145,6 ms`, `c1-large-1` `3/3 PASS` + `C2-B3`) | `LOCAL_ENV_BOUNDED` |
| `F01-E` (Identity Registration `1/s`) | `p95 ≤ 500 ms` | **`128,5–140,6 ms`** (`a2-id1` `3/3`) · **`119,3 ms`** sous charge Pot `20/s` (`a2-pot-vs-id`) | `LOCAL_ENV_BOUNDED` |
| `F01-F` (Keycloak isolé Config B) | Config B dédiée | Fonctionnel prouvé (`bruno-fresh-06`), charge isolée impossible localement | `DEFERRED_TO_L8_INFRA` |
| `F01-G` (Rattrapage `10 000` commandes) | `≤ 300 s` (`5 min`) | **`129,78 s`** (`77,1 cmd/s`, `a2-backlog-10k` `1/1 PASS`) | `LOCAL_ENV_BOUNDED` |
| `F01-H` (Contention même Pot) | `0` double/perte, résorption | `c2-contention-1pot` (`3/3`, `0` violation, `2–6/120` résiduels au timeout sous `T0`) | `CURRENT_PARTIAL` |
| `F01-I` / `F01-I-COST` (AUD-V3-013 / SQL) | 3 tailles + `10k/1M`–`100k/10M` | 3 tailles (`5/20`, `20/200`, `100/2000`, 200k participations) qualifiées (`C2-B3`, `C4`) ; `10k/1M`–`100k/10M` L8 | `LOCAL_ENV_BOUNDED` |
| `F01-J` (Polling à vide + réactivité) | Caractérisation | `-90,5 %` tx/s (`1 340 tx/s`), `-59,9 %` CPU, `-87,0 %` WAL (`a2-idle` `3/3 PASS`) | `LOCAL_ENV_BOUNDED` |
| `A11-L` / `A11-M` (`DeletePot` C-03) | `p95 ≤ 500 ms`, sans `Balance(D)` | **`195,7–371,1 ms`** (`c1-delete-1` `3/3 PASS`, `10/10` HTTP `404`) | `LOCAL_ENV_BOUNDED` |

### 4.2 Clôture de `DEBT-L7LB-01`

- **Correction** : commit atomique `f589d95c` dans [scripts/local/runtime.py](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/local/runtime.py) (helper partagé `_required_disk_bytes` entre `resources()` et `doctor()`, champs `freeDiskBytes`, `requiredDiskBytes`, `diskSufficient`, `diskDiagnostic`, retour `NOT_READY` / `exit=2` en cas d'espace insuffisant en mode strictement `readOnly=True`).
- **Preuve** : `python3 -B scripts/local/test_s6_hardening.py && python3 -B scripts/local/test_local.py` → **`36/36 PASS`** (`15/15` + `21/21`).
- **Statut** : **`CLOSED` (`R4`)** ([Debt.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/internal/debts/DOCTOR_DISK_CHECK/Debt.md)).

### 4.3 État final des réserves

- **Soldées (`R4 — CLOSED`)** : `DEBT-L7LB-01`, `RES-L75-GATES` (`CERT-FULL`, `CERT-ARCH`, `CERT-TRACE` `PASS` sur `f589d95c`), revue `R1` des `41` L7.2 et `338+30` L7.3 (matrice réconciliée sans `MISSING`), `L74-MET-02` / `L74-BENCH-01` (campagne locale exécutée en C2–C4), observation `a2-idle` expliquée et close.
- **Restantes (`R2 — Limites locales caractérisées, non bloquantes`)** : `RES-L7LB-S6-01` (plateau local `36,83 cmd/s` sur `6 CPUs`), `LB-J1-GUI` (ergonomie Bruno GUI), `13` sous-variantes `CURRENT_PARTIAL` documentées (§2).
- **Restantes (`R3 — Différées L8 / Production`)** : `L74-ENV-01` (`SPEC-09` Config A/B `2 × 4 vCPU / 8 GiB` + Keycloak isolé), `RES-L7LB-S6-02` (HA multi-nœuds), `RES-L8-PROD` (volumes `10k/1M` & `100k/10M`, TLS/mTLS, rotation secrets, CORS/BFF).

---

## 5. Rapport normatif `Reactor_Verification_Policy.md` (§30 & §35)

```text
Start HEAD: f6bf3a0d74dff03f2951cab5bcfc772cddfc5d36
Code/Tooling Commit (DEBT-L7LB-01): f589d95cc120331250a9e566f548481b6da7f836
Expected production impact (app/): NONE (tooling scripts/local/runtime.py + documentation/evidence only)
Observed production impact (app/): NONE
Declared slices:
- Tooling: scripts/local (DEBT-L7LB-01)
- Global L7.5 certification gate: FULL REACTOR + GLOBAL ARCHITECTURE TESTS (42 modules, unfiltered)
Required operational level and mandatory unfiltered gates:
- CERTIFICATION (L7.5 CERT-FULL, CERT-ARCH, CERT-TRACE) on frozen clean SHA f589d95c
Verification commands executed:
1. python3 -B scripts/local/test_s6_hardening.py && python3 -B scripts/local/test_local.py
   -> 36/36 PASS (15/15 + 21/21)
2. python3 -B scripts/testing/verify.py CERTIFICATION --output /tmp/pocoma-l7-cert-full-f589d95c
   -> ./mvnw --batch-mode --no-transfer-progress -Dpocoma.verification.id=a80a48245b5c4bedb737b50338d930dc -Dpocoma.verification.reports=/private/tmp/pocoma-l7-cert-full-f589d95c/reports test
   -> Tested SHA: f589d95cc120331250a9e566f548481b6da7f836 (clean)
   -> Verdict: PASS | tests=1173, failures=0, errors=0, skipped=0 | seconds=4957.75s | issues=[]
   -> Evidence archived: docs/testing/evidence/L7-final-qualification/cert-full-f589d95c/ (manifest.sha256)
Unexpected slice crossings: NONE
Global architecture gate: RUN (included unfiltered in CERT-FULL: 50/50 PASS)
Full reactor: RUN (mandatory L7.5 CERT-FULL gate per Reactor_Verification_Policy.md §§19, 21.3, 35)
Database verification: CURRENT + transitive PostgreSQL 17 suites in unfiltered 42-module reactor (PASS)
Deferred campaigns and contract-based reason:
- SPEC-09 Configuration A/B (2x4 vCPU / 8 GiB + isolated Keycloak), 50 cmd/s peak, 10k/1M & 100k/10M global volumes, multi-node HA, TLS/mTLS -> L8 / target reference infrastructure (L74-ENV-01, RES-L8-PROD)
Push / Merge to main: NOT PERFORMED (forbidden without explicit authorization)
Certification: QUALIFIED PASS — L7_LOCAL_CERTIFIED_WITH_RESERVES
```
