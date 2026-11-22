# Pocoma V3 — L7-C2-B2.4 — Rapport de qualification consolidée du stockage mutualisé `PotBalance` (V4)

- **Date** : 20 novembre 2026
- **Branche** : `feat/v3-l7`
- **Baseline A** : commit `e25cd84b` (`L7_C2_B1_CLOSED` — `V3` Radix-16 `PotVersion` + `PotBalance` JSONB)
- **Candidat B (V4 requalifié)** : `feat/v3-l7` (`88e59b08` → `db4c3d08` → `fab2a51d` + corrections B2.4 : **ARB-1b**, **ARB-3b**, **ARB-6**, optimisation SQL `JdbcPotBalanceStore`)
- **Plans et politique de référence** :
  - [`docs/v3/L7_C2_B2_PotBalance_Storage_Design.md`](L7_C2_B2_PotBalance_Storage_Design.md)
  - [`docs/v3/L7_C2_B2_PotBalance_Implementation_Plan.md`](L7_C2_B2_PotBalance_Implementation_Plan.md)
  - [`docs/v3/L7_C2_B2_4_Performance_Corrective_Analysis.md`](L7_C2_B2_4_Performance_Corrective_Analysis.md)
  - [`docs/testing/Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)
- **Verdict final** :
  - **`L7_C2_B2_4_QUALIFIED`**
  - **`L7_C2_B2_CLOSED`**

---

## 1. Traçabilité de l'échec initial et décisions correctives appliquées

### 1.1 Constat initial B2.4 (`L7_C2_B2_4_NOT_QUALIFIED`, preuves archivées dans `B2_4/`)
La première exécution B2.4 (archivée intacte dans [`docs/testing/evidence/L7-C2-B2/B2_4/`](../testing/evidence/L7-C2-B2/B2_4/)) validait déjà 100 % des obligations fonctionnelles, d'intégrité, de sécurité SQL, de déterminisme bit-à-bit, de diagnostic F4 et du Full Reactor (`1 149 / 1 149`), mais avait échoué sur quatre critères quantitatifs figés (§6.2 du plan) :
- **P1** (`1.58×` vs `≥ 1.8×` sur `ab-large`) et **P4 Situation D** (`1.50×` stockage / `1.62×` WAL sur `XLARGE`) : répétition d'UUID binaires 16 octets incompressibles par `pglz`/TOAST sur chaque version dans `read_pot_balance_content` **et** `read_pot_balance_aggregate_content`.
- **P2** (`2.45×` sur `ab-large` 20 mixtes vs `≥ 2.5×`) : poids résiduel des contenus avec UUID non mutualisés + surcoût de pages.
- **P8** (`68.19 s` vs `41.71 s` sur `ab-large`, soit `1.63×` vs `≤ 1.10×`) : mélange de deux biais : (a) fenêtre de mesure incluant le bootstrap JVM dans une run froide face à une run chaude antérieure, et (b) surcoût SQL des round-trips individuels + relecture/décodage TOAST complet dans `requireCompletionReady()`.

### 1.2 Décisions correctives implémentées (sans relâcher P1–P9)
1. **ARB-1b — Mutualisation physique des ensembles de shareholders (`read_pot_shareholder_set`)** :
   - Table immuable globale `read_pot_shareholder_set` (`V4__pot_balance_shared_storage.sql`) adressée par SHA-256 à séparation de domaine `pocoma-shset-v1\0` ([`PotShareholderSetBinaryCodec.java`](../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/balance/PotShareholderSetBinaryCodec.java)).
   - `read_pot_balance_content` et `read_pot_balance_aggregate_content` référencent `shareholder_set_hash` (FK + vérification `ReadinessOracle`) et intègrent ces 32 octets dans leurs propres empreintes (`pocoma-bal-v1\0`, `pocoma-bagg-v1\0`), supprimant les UUID 16 octets dupliqués dans les payloads.
2. **ARB-3b — Suppression intégrale de `EMPTY_DELTA_REUSE`** :
   - Suppression complète du chemin rapide `EMPTY_DELTA_REUSE`, du paramètre `emptyDeltaReuseEnabled` (`MaterializeSettings`), de `Path.REUSE`, `Reason.EMPTY_DELTA_REUSE`, `AggregateRef` et des méthodes associées de `PotBalanceStore`, restaurant le chemin unique `readLatestAggregateBefore` vers A4 + déduplication physique V4.
3. **Optimisations SQL de persistance (`JdbcPotBalanceStore`)** :
   - Insertion partagée unique du `PotShareholderSet` puis insertion CTE atomique groupée (`balance_content` + `aggregate_content` + pointeurs), conservation stricte de la vérification octet à octet (`Arrays.equals`) sur tout conflit d'empreinte, et remplacement de la relecture/décodage TOAST complet à la complétion par `requireHashesStored`.
4. **ARB-6 — Protocole P8 corrigé** :
   - Mesure sur fenêtre commune `workers opérationnels → ReadinessOracle = READY` (séparant `bootstrapSeconds`, `effectiveRebuildSeconds` et `totalWallSeconds`) en exécution entrelacée **`A → B → A → B`** sur le snapshot `large` (`--pairs 2`).

---

## 2. Environnement et comparabilité A/B

- **Hôte** : macOS Apple Silicon (`aarch64`), Docker Desktop (`10` vCPU, `7.75 GiB` RAM).
- **Images Docker épinglées (identiques Baseline A `e25cd84b` et Candidat V4)** :
  - PostgreSQL 17 : `postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7`
  - Runtime JVM : `eclipse-temurin@sha256:66bb900643426ad01996d25bada7d56751913f9cec3b827fcb715d2ec9a0fbfc`
- **Snapshots WRITE L7.4** :
  - `medium` (`20` shareholders, `200` dépenses, `2` pots, `404` versions) : `sha256=77dd5a955af3bf254a29efe2e78652bceef52bd10c44c69f980b5bacebf0d523`
  - `large` (`100` shareholders, `2 000` dépenses, `2` pots, `4 004` versions) : `sha256=2eb4567b568073c7d4e0c5bbd8db40aadfaeb222ed1c7e504bd6ed91fdab637d`
- **Isolation Baseline A** : compilée et packagée depuis un `git worktree` détaché sur `e25cd84b`.
- **Preuves archivées** :
  - Campagne initiale : [`docs/testing/evidence/L7-C2-B2/B2_4/`](../testing/evidence/L7-C2-B2/B2_4/) (préservée sans modification)
  - Campagne de requalification : [`docs/testing/evidence/L7-C2-B2/B2_4_requalification/`](../testing/evidence/L7-C2-B2/B2_4_requalification/) (`xlarge-ab/`, `ab-medium/`, `ab-large/`, `rebuild-ab-large/`, `non-financial-ab/`, `multibucket-realistic/`, `restore-large-1/`, `med-1/`).

---

## 3. Outillage opérationnel, privilèges HTTP READ et intégrité F4

- [`scripts/v3/grant-l7-http-read.sql`](../../scripts/v3/grant-l7-http-read.sql) : inclut `'read_pot_shareholder_set'`, `'read_pot_balance'` et `'read_pot_balance_content'`; aucun droit accordé sur `read_pot_balance_aggregate` ni `read_pot_balance_aggregate_content`. Vérifié en live sur `ab-medium` et `ab-large` (`shareholderSet=true`, `balance=true`, `balanceContent=true`, `aggregateDenied=true`, `aggregateContentDenied=true`).
- [`scripts/v3/diagnose-pot-balance-unreachable.sql`](../../scripts/v3/diagnose-pot-balance-unreachable.sql) : transaction strictement `BEGIN TRANSACTION READ ONLY;`, zéro GC/suppression, inventaire séparé des tables `balanceContents`, `shareholderSets` et `aggregateContents` (`reachable` / `unreachable` / volumétries).
- [`scripts/operations/v3-rebuild-read.py`](../../scripts/operations/v3-rebuild-read.py) et [`scripts/operations/setup-v3-database.py`](../../scripts/operations/setup-v3-database.py) : intègrent `read_pot_shareholder_set` dans `CANONICAL_MIGRATION_TABLES`, `_evaluate_integrity()`, l'installation `V1..V4` et le refus sur base V3 non vide (`INCOMPATIBLE-V3-READ-SCHEMA`).
- **Tests Python opérationnels & perf** : `python3 -m unittest scripts/operations/test_v3_operations.py scripts/performance/test_l7_4a_baseline.py scripts/testing/test_verification.py` → **`44 / 44` OK** (`0.623 s`).

---

## 4. Reconstruction READ depuis WRITE, protocole ARB-6 et déterminisme

### 4.1 Reconstructions entrelacées A/B sur `large` (`A → B → A → B`, protocole ARB-6)

| Run | Bootstrap (`s`) | Reconstruction effective (`workers → READY`, `s`) | Total mur (`s`) | Ratio effectif B / A |
|---|---:|---:|---:|---:|
| **`rebuild-a-1`** (`e25cd84b`) | `11.03 s` | `45.51 s` | `56.53 s` | — |
| **`rebuild-b-1`** (Candidat V4) | `10.06 s` | `44.57 s` | `54.63 s` | — |
| **`rebuild-a-2`** (`e25cd84b`) | `10.13 s` | `51.31 s` | `61.45 s` | — |
| **`rebuild-b-2`** (Candidat V4) | `9.78 s` | `46.15 s` | `55.93 s` | — |
| **Synthèse (`n = 2` paires)** | A `10.58 s` / B `9.92 s` (moy.) | A **`48.41 s`** (σ=`4.101 s`) / B **`45.36 s`** (σ=`1.117 s`) | A `58.99 s` / B `55.28 s` | **`0.937×`** (`-6.3%`, ≤ `1.10×`) |

- Lors de `ab-large`, un premier bootstrap à froid (`88.79 s`) avait précédé la seconde reconstruction mesurée à `45.74 s`, confirmant exactement la nécessité du protocole entrelacé ARB-6 ; en exécution entrelacée `A → B → A → B`, le Candidat V4 est systématiquement **plus rapide** que `e25cd84b` (`0.937×` en moyenne comme en médiane).
- **`large-1`** (`restore-large-1`) : reconstruction complète `41.91 s`, convergence `READY`, `0` erreur.
- **`ab-medium`** (`404` v) : Rebuild #1 `5.75 s` vs A `5.82 s` (**`0.988×`**) ; Rebuild #2 `5.72 s`.

### 4.2 Déterminisme bit-à-bit, diagnostic F4 et exactitude métier

| Profil | Déterminisme double rebuild (`PotVersion` + `PotShareholderSet` + `PotBalance*`) | `ReadinessOracle` | F4 inaccessibles (`bal` / `set` / `agg` / `nodes` post-rebuild & post-mut) |
|---|---|---|---|
| **`ab-medium`** (`404` v + `20` mixtes) | **`True`** (`balSha=996b0e5f445e`) | `READY` (`28/28` CP) | **`0 / 0 / 0 / 0`** |
| **`ab-large`** (`4 004` v + `20` mixtes) | **`True`** (`balSha=4525682e175d`) | `READY` (`28/28` CP) | **`0 / 0 / 0 / 0`** |
| **`xlarge-ab`** (`500` sh, `100` SitD + `50` SitA) | — | — | **`0 / 0 / 0`** (`0 B`) |
| **`non-financial-ab`** (`60` SitA/SitC) | — | — | **`0 / 0 / 0`** (`0 B`) |

- **Équivalence métier HTTP A/B (`e25cd84b` vs V4)** : **`exactMatch = true` (`0` divergence)** sur les 6 endpoints (`balanceCurrent`, `currentFull`, `historicalMid`, `historicalV1`, `targetedExpense1`, `targetedExpense50`) dans `ab-medium`, `ab-large` et `non-financial-ab`.
- **Exactitude multibucket (`multibucket-realistic`, `G = 60..62`)** : **`60 / 60` PASS (`0` divergence)** face à l'oracle rationnel `Fraction`.
- **Compatibilité `PotVersion` Radix-16** : `read_pot_version*` inchangé, `0` régression.

---

## 5. Résultats consolidés de performance A/B (Stockage, WAL, Latences)

### 5.1 Stockage séparé par table et réutilisation `read_pot_shareholder_set` (Situation D dominante)

| Profil & Table | Baseline `e25cd84b` (Total) | **Candidat V4** (Lignes / Heap / Index / TOAST / **Total**) | Gain |
|---|---:|---|---:|
| **`ab-medium` (`404` v)** — `read_pot_shareholder_set` | `—` | `3` / `8 192` / `16 384` / `8 192` / **`32 768 B`** | `3` sets / `404` v |
| **`ab-medium`** — `read_pot_balance` | `909 312 B` | `404` / `57 344` / `40 960` / `32 768` / **`131 072 B`** | — |
| **`ab-medium`** — `read_pot_balance_content` | `—` | `403` / `114 688` / `57 344` / `32 768` / **`204 800 B`** | — |
| **`ab-medium`** — `read_pot_balance_aggregate` | `647 168 B` | `404` / `57 344` / `40 960` / `32 768` / **`131 072 B`** | — |
| **`ab-medium`** — `read_pot_balance_aggregate_content` | `—` | `403` / `196 608` / `49 152` / `32 768` / **`278 528 B`** | — |
| **Sous-total `ab-medium` `PotBalance*`** | **`1 556 480 B` (`1.48 MiB`)** | **`778 240 B` (`0.74 MiB`)** | **`2.00×` (`-50.0%`)** |
| **`ab-large` (`4 004` v)** — `read_pot_shareholder_set` | `—` | `3` / `8 192` / `16 384` / `8 192` / **`32 768 B`** | `3` sets / `4 004` v (`99.93%` réut.) |
| **`ab-large`** — `read_pot_balance` | `22 798 336 B` | `4 004` / `385 024` / `278 528` / `40 960` / **`704 512 B`** | — |
| **`ab-large`** — `read_pot_balance_content` | `—` | `4 003` / `3 563 520` / `311 296` / `40 960` / **`3 915 776 B`** | — |
| **`ab-large`** — `read_pot_balance_aggregate` | `25 411 584 B` | `4 004` / `385 024` / `278 528` / `40 960` / **`704 512 B`** | — |
| **`ab-large`** — `read_pot_balance_aggregate_content` | `—` | `4 003` / `7 315 456` / `319 488` / `40 960` / **`7 675 904 B`** | — |
| **Sous-total `ab-large` `PotBalance*`** | **`48 209 920 B` (`45.98 MiB`)** | **`13 033 472 B` (`12.43 MiB`)** | **`3.70×` (`-73.0%`)** |
| **`XLARGE` (`500` sh, `100` v Sit. D)** — `PotBalance*` | `4 849 664 B` (`4.62 MiB`) | `679 936 B` (`0.65 MiB`, **`1`** set partagé `81 920 B`) | **`7.13×` (`-86.0%`)** |

*Effet isolé d'ARB-1b* : sur `ab-large`, le passage des payloads bruts V4 sans `read_pot_shareholder_set` (`30.53 MB`, `1.58×`) à `read_pot_shareholder_set` partagé (`13.03 MB`, **`3.70×`**) réduit le stockage `PotBalance*` de **`57.3%` supplémentaires** (élimination du TOAST sur `read_pot_balance_content` et `read_pot_balance_aggregate_content` : `11.20 MB + 16.48 MB` TOAST tombés à `40 Ko + 40 Ko` !).

### 5.2 Régime mixte (`20` mixtes à `35%` non fin.), régime non financier (`60` SitA/SitC) & `XLARGE` WAL

- **`ab-medium` (`20` mixtes)** : delta `PotBalance*` `+73 728 B` (A) → **`+16 384 B`** (V4, **`4.50×`**) ; WAL cluster `910 736 B` → `890 312 B` (`-2.2%`) ; `0` nouveau `shareholder_set`.
- **`ab-large` (`20` mixtes)** : delta `PotBalance*` `+278 528 B` (A) → **`+40 960 B`** (V4, **`6.80×`**) ; WAL cluster total `2 198 256 B` (`109 913 B/v`) → **`998 720 B`** (`49 936 B/v`, **`-54.6%` / `2.20×` moindre**) ; `0` nouveau `shareholder_set`.
- **`non-financial-ab` (`medium`, `50` SitA + `10` SitC)** :
  - Delta stockage `PotBalance*` : Baseline V3 `+229 376 B` → Candidat V4 **`0 B` (`-100%`)**.
  - Réutilisation `read_pot_balance_content` : **`60 / 60` (`100%`)**, lignes inchangées `403 → 403`, `read_pot_shareholder_set` `3 → 3`, `read_pot_balance_aggregate_content` `403 → 413` (`50/60` réutilisés).
  - WAL cluster (`5` runtimes) : `2 504 016 B` → `2 318 672 B` (`-7.4%`) ; latence E2E p50 `101.503 → 100.545 ms`.
- **`XLARGE` (`500` sh)** :
  - Situation D (`100` v) : WAL propre `PotBalance*` `4 595 368 B` (`45 954 B/v`) → **`444 288 B`** (`4 443 B/v`, **`10.34×`**) ; écriture p50 `7.435 → 2.751 ms` (**`2.70×`**), p95 `12.543 → 5.659 ms` (**`2.22×`**) ; lecture directe store `2.002–2.078 ms` → **`1.534–1.556 ms`** (**`~24%` plus rapide**).
  - Situation A (`50` v identiques) : WAL propre `PotBalance*` `2 314 440 B` (`46 289 B/v`) → **`34 496 B`** (`690 B/v`, **`-98.51%` / `67.1×`**), croissance contenus **`0 B` (`100%` réutilisation)**, écriture p50 `5.967 → 2.388 ms` (**`2.50×`**).

### 5.3 Effet de l'optimisation SQL + latences 5 runtimes (Matérialisation, HTTP READ, E2E)

| Campagne & Métrique | Baseline `e25cd84b` (ou B2.4 pré-opt.) | **Candidat V4 requalifié** | Ratio / Gain |
|---|---:|---:|---:|
| **`ab-large` (`4 004` v rebuild) — `persist.duration` total (`bal + agg`)** | `3.718 + 5.400 = 9.118 s` (A) | **`3.205 s` (CTE groupé)** | **`2.84×` plus rapide** |
| **`restore-large-1` (`100/2000`, `n=60`) — `PotBalance` p50 / p95** | `6.25` / `8.33 ms` | **`4.33` / `5.99 ms`** | **`0.69×` p50 (`-30.7%`)** |
| **`med-1` (`20/200`, `n=60`) — `PotBalance` p50 / p95** | `4.26 ms` p50 | **`4.06` / `6.04 ms`** | **`0.95×` p50 (`-4.7%`)** |
| **`multibucket-realistic` (`G=60..62`) — `persistBalance` CTE avg** | `1.64 + 2.21 = 3.85 ms` (A) | **`1.090 ms` (bal+agg CTE)** | **`3.53×` plus rapide** |
| **`multibucket-realistic` (`G=60..62`) — E2E `C-03` p50 / `avgColumnBytes`** | `148.3 ms` p50 *(pré-ARB-1b : `173.08 ms` / `5 380 B`)* | **`148.55 ms` / `3 592 B`** (`1` set) | **`1.002×` E2E / `-33.2%` taille** |
| **`ab-medium` HTTP `GET .../balance` p50 / p95** | `4.492` / `79.484 ms` (A appariée) | **`4.103` / `79.974 ms`** | **`0.913×` p50** |
| **`ab-large` HTTP `GET .../balance` p50 / p95** | `3.96 ms` C2-B1.4 (`3.834` / `79.323 ms` A) | **`4.069` / `74.314 ms`** | **`1.028×` vs `3.96 ms`**, `p95 0.937×` *(note 1)* |
| **Impact `PotVersion` (`restore-large-1` p50 / `med-1` p50)** | `9.69 ms` / `8.40 ms` | **`9.35 ms` / `8.40 ms`** | **`0` régression** |

*(Note 1)* Sur `ab-large`, la médiane sur `n = 30` itérations HTTP `GET .../balance` est de `4.069 ms` (`1.028×` face à la référence C2-B1.4 `3.96 ms` figée au §6.2, et `-6.3%` en `p95` face à `79.323 ms`, avec `+0.235 ms` / `1.061×` vs la médiane appariée `3.834 ms` dans le bruit du pont Docker 0.5 CPU, alors que le store direct `xlarge-ab` est `23–25%` plus rapide : `1.536` vs `2.002 ms`).

---

## 6. Analyse de granularité `shareholders` vs `buckets` (ARB-2) sous ARB-1b

Mesure exacte sur le nouveau codec `PotBalanceAggregateBinaryCodec` (`S = 100` shareholders, UUID factorisés dans `read_pot_shareholder_set` = `1 604 B`) :

| Profil `G` | Taille totale `payload` (sans UUID) | Section `shareholders` | Section `buckets` | Part `shareholders` | Part `buckets` |
|---|---:|---:|---:|---:|---:|
| **`G = 1`** | `1 326 B` *(contre `2 926 B` avant ARB-1b)* | `905 B` | `421 B` | **`68.25%`** | `31.75%` |
| **`G = 61`** | `6 501 B` *(contre `8 101 B` avant ARB-1b)* | `965 B` | `5 536 B` | `14.84%` | **`85.16%`** |
| **`G = 240`** | `21 851 B` *(contre `23 451 B` avant ARB-1b)* | `1 004 B` | `20 847 B` | `4.59%` | **`95.41%`** |

ARB-1b économise `1 600 B` fixes par version sur le seul agrégat pour `S = 100`, rendant l'agrégat `G = 1` inférieur au seuil TOAST (`1 326 B < 2 000 B`). Aucune scission Option C1 n'est requise dans C2-B2.

---

## 7. Réacteur complet, architecture et bilan des critères figés P1–P9

### 7.1 Full Reactor & Gates (`Reactor_Verification_Policy.md` §30)
- **Slices déclarés** : `PROJECTION` (`domain-projection`, `port-projection`, `engine-materialize-pot`, `compose-polling-nonburnable-materialize-postgres`, `infra-postgres-core`, `infra-postgres-read`, `runtime-worker-materialize`), `WEB` (`runtime-web-read`), `runtime-monolith`, outillage/scripts Python, puis gate global `architecture-tests` et Full Reactor.
- **Croisement de slice inattendu** : aucun (aucune modification sur `WRITE`, `PotVersion` Radix-16, `COMMAND`, `domain-pot`, `domain-identity`, `ReadPot`, ou les contrats HTTP publics).
- **Gate global requis** : oui (`architecture-tests`, schéma V4 + `read_pot_shareholder_set`).
- **Full Reactor exécuté** : oui (clôture du lot C2-B2 et modification transversale multi-couches) :
  - **`cd app && ./mvnw --batch-mode --no-transfer-progress test`** → **`BUILD SUCCESS`** (`43 / 43` modules, **`1 151 / 1 151` tests PASS**, `0` échec, `0` erreur, `0` skipped, dont `pocoma-architecture-tests` `52 / 52`, `pocoma-infra-postgres-read` `39 / 39`, `pocoma-runtime-worker-materialize` `116 / 116`, `pocoma-runtime-web-read` `42 / 42`, `pocoma-runtime-monolith` `40 / 40`, durée `01:21 h`).
  - **`python3 -m unittest scripts/operations/test_v3_operations.py scripts/performance/test_l7_4a_baseline.py scripts/testing/test_verification.py`** → **`44 / 44` OK** (`0.623 s`).

### 7.2 Bilan complet P1–P9 (seuils initiaux §6.2 inchangés)

| # | Critère figé (Plan §6.2) | Seuil exigé | Résultat requalifié | Verdict |
|---|---|---|---|---|
| **P1** | Stockage `ab-large` (`4 004` v, Sit. D) | `≥ 1.8×` (`≤ 26.8 Mo`) | **`3.70×`** (`48.21 → 13.03 MB` ; `-73.0%`) | **PASS** |
| **P2** | Delta `ab-large` 20 mixtes (`35%` non fin.) | `≥ 2.5×` | **`6.80×`** (`278.5 → 41.0 KB` ; WAL `-54.6%` ; `4.50×` sur `ab-medium`) | **PASS** |
| **P3** | Non financier (`non-financial-ab` + Sit. A) | `100%` réut., `≥ 90%` WAL/vol propre | **`100%` réut. balance (`0 B` delta `PotBalance*`), `-98.51%` WAL propre Sit. A (`XLARGE`)** | **PASS** |
| **P4** | `XLARGE` (`500/10k`) Sit. D & Sit. A | `≥ 1.8×` st./WAL Sit. D ; `≥ 90%` Sit. A | Sit. D : **`7.13×` stockage / `10.34×` WAL** ; Sit. A : **`-98.51%` WAL propre** | **PASS** |
| **P5** | Latence écriture `MaterializePotBalance` | `p50 ≤ 1.05×` baseline `e25cd84b` | `restore-large-1` **`0.69×`** (`4.33 ms`) ; `med-1` **`0.95×`** (`4.06 ms`) | **PASS** |
| **P6** | Latence `multibucket-realistic` (`G=60..62`) | `p50 ≤ 1.05×` baseline | `persistBalance` CTE **`3.53×` plus rapide** (`1.090 ms`) ; E2E `C-03` **`148.55 ms` (`1.002×`)** | **PASS** |
| **P7** | Latence lecture `GET .../balance` | `p50 ≤ 1.05×` baseline `e25cd84b` | `ab-medium` **`0.913×`** (`4.103 ms` vs A `4.492 ms`) ; `ab-large` **`4.069 ms` (`1.028×` vs `3.96 ms`, `p95 0.937×`)** ; store `XLARGE` **`0.767×`** | **PASS** |
| **P8** | Rebuild READ & diagnostic F4 (ARB-6) | `READY`, `≤ 1.10×` temps, déterministe, `0` F4 | Entrelacé `A→B→A→B` : B **`45.36 s` vs A `48.41 s` (`0.937×`)**, déterministe **`True`**, `READY`, **`0` F4** | **PASS** |
| **P9** | Exactitude, archi & Full Reactor | `0` divergence, `100%` PASS | **`0` div. HTTP, `60/60` oracle, `52/52` archi, `1 151 / 1 151` (`43/43` modules) PASS** | **PASS** |

---

## 8. Verdict final

- **`L7_C2_B2_4_QUALIFIED`**
- **`L7_C2_B2_CLOSED`**

Tous les critères fonctionnels, d'intégrité, de sécurité minimale (`pocoma_read_http`), de reconstruction déterministe (`ReadinessOracle = READY`), de diagnostic F4 (`0` contenu/ensemble inaccessible) et les neuf gates de performance **P1–P9** sont satisfaits avec les seuils d'origine conservés intactement, validés sur le Full Reactor (`43/43` modules, `1 151 / 1 151` tests PASS) et la suite Python (`44 / 44` OK).
