# Rapport L7.4-A4.3 — Qualification de performance du calcul incrémental `N−m` de `PotBalance`

- **Date** : 19 novembre 2026
- **Branche courante** : `feat/l7-4-a3-potversion-incremental-nm` (commit A4.2 `317854c8`)
- **Références comparées** :
  - **L7.4-A2 (`f196b2fb`)** : `PotVersion` intégral optimisé, `PotBalance` intégral
  - **L7.4-A3 (`814fcb0b`)** : `PotVersion` incrémental `N−m`, `PotBalance` intégral ([rapport A3](L7_4_A3_PotVersion_Incremental_N_Minus_M_Report.md))
  - **L7.4-A4 (`317854c8`)** : `PotVersion` **et** `PotBalance` incrémentaux `N−m` ([rapport A4.1](L7_4_A4_1_PotBalance_Aggregate_Equivalence_Report.md), [rapport A4.2](L7_4_A4_2_PotBalance_Incremental_N_Minus_M_Report.md))
- **Preuves archivées** : `docs/testing/evidence/L7-4A4/{restore-large-1,med-1,med-5,weight-update-large,out-of-order-nm-large}`
- **Verdict final A4.3** : **`A4_3_QUALIFIED`**

---

## 1. Protocole et reproductibilité

Les campagnes ont été exécutées dans des conditions strictement comparables à L7.4-A2 et L7.4-A3 :

- **Topologie** : 5 runtimes Docker mono-hôte (`http-write`, `http-read`, `command-worker`, `results-worker`, `business-read-worker`) + `postgres:17-alpine` en `tmpfs`, mêmes limites CPU/RAM (`2.0` CPUs pour `postgres` et `business-read-worker`).
- **Images épinglées** :
  - Java : `eclipse-temurin@sha256:66bb900643426ad01996d25bada7d56751913f9cec3b827fcb715d2ec9a0fbfc`
  - PostgreSQL : `postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7`
- **Snapshots préservés à l'identique** : `/private/tmp/pocoma-l74a-snapshots/large` (`SHA-256 = 2eb4567b568073c7d4e0c5bbd8db40aadfaeb222ed1c7e504bd6ed91fdab637d`, `2` pots de `100` shareholders / `2 000` dépenses). Le snapshot n'a pas été modifié ; après restauration, la migration additive `V2__pot_balance_aggregate.sql` est appliquée à chaud sur `v3_read` (reproduisant exactement une montée de version V1 → V2 sur données existantes).
- **Séparation des gains A3 vs A4** : la colonne **Gain A4 vs A3** isole exclusivement l'apport du calcul incrémental `PotBalance` (A4), indépendamment des gains déjà obtenus sur `PotVersion` en A3.

---

## 2. Campagnes obligatoires — Comparaison A2 / A3 / A4

### 2.1 Grand Pot — Comparaison principale (`restore-large-1`, `100/2000`, `2` pots, `1.0 cmd/s`, `n = 60` + `5` warmup)

| Métrique | L7.4-A2 (`PotVersion` opt., `PotBalance` full) | L7.4-A3 (`PotVersion` `N−m`, `PotBalance` full) | L7.4-A4 (`PotVersion` + `PotBalance` `N−m`) | **Gain propre A4 vs A3** | Gain cumulé A4 vs A2 |
|---|---:|---:|---:|---:|---:|
| **Verdict / Offres observées** | `PASS` (`60/60`) | `PASS` (`60/60`, `0` drop) | **`PASS` (`60/60`, `0` drop)** | Stable | Stable |
| **Débit effectif terminé** | `0.998 cmd/s` | `1.008 cmd/s` | **`1.015 cmd/s`** | `+0.7%` | `+1.7%` |
| **`MaterializePotBalance` p50** | `455.51 ms` | `381.61 ms` | **`8.39 ms`** | **`-97.8%` (`45.5×`)** | **`-98.2%` (`54.3×`)** |
| **`MaterializePotBalance` p95** | `573.08 ms` | `441.65 ms` | **`12.58 ms`** | **`-97.2%` (`35.1×`)** | **`-97.8%` (`45.6×`)** |
| **`MaterializePotBalance` p99*** | — | `511.20 ms` | **`24.01 ms`** | **`-95.3%` (`21.3×`)** | — |
| **`MaterializePotVersion` p50 / p95** | `1 031.52` / `1 376.17 ms` | `34.96` / `50.63 ms` | **`29.94` / `40.80 ms`** | `-14.4%` / `-19.4%` *(baisse contention)* | `-97.1%` / `-97.0%` |
| **E2E `C-03` READ p50** | `1 234.93 ms` | `511.03 ms` | **`157.76 ms`** | **`-69.1%`** | **`-87.2%`** |
| **E2E `C-03` READ p95** | `1 549.65 ms` | `613.79 ms` | **`292.99 ms`** | **`-52.3%`** | **`-81.1%`** |
| **E2E `Balance` READ p50 / p95** | `1 179.53` / `1 536.51 ms` | `488.17` / `588.20 ms` | **`137.86` / `227.36 ms`** | **`-71.8%` / `-61.3%`** | **`-88.3%` / `-85.2%`** |
| **CPU `postgres` (avg / max)** | `152.9%` / `174.2%` | `86.6%` / `94.6%` | **`76.2%` / `86.2%`** | **`-10.4` / `-8.4` pts** | **`-76.7` / `-88.0` pts** |
| **CPU `business-read-worker` (avg / max)** | `85.7%` / `147.9%` | `77.2%` / `142.0%` | **`45.4%` / `93.2%`** | **`-31.8` / `-48.8` pts** | **`-40.3` / `-54.7` pts** |
| **Convergence & Violations** | `True` (`0`) | `True` (`0`) | **`True` (`0` violation)** | — | — |

*\*Limite statistique : pour `n = 60`, `p99` correspond au maximum observé de l'échantillon.*

**Décomposition interne Micrometer (`L7-4A4/restore-large-1`, `64` matérialisations totales = `4` warmup + `60` sample)** :
- **Chemin `INCREMENTAL` (`incremental_hit`)** : **`62 / 64`** (`100%` des `60` commandes mesurées + `2` commandes de warmup ; `N − K = 1`).
- **Chemin `FULL` (`no_reference`)** : **`2 / 64`** — exclusivement la 1re commande de warmup sur chacun des 2 pots restaurés pré-V2 (qui a construit et persisté $\mathcal{A}(2003)$ pour toute la suite). `0` autre fallback, `0` `corrupt`.
- **Coûts moyens mesurés** :
  - Lookup + décodage JSONB $\mathcal{A}(K)$ (`n=64`) : **`1.11 ms`** avg (`max = 5.89 ms`)
  - Résolution delta `v3_write` (`n=62`, `1.0` dépense modifiée/événement) : **`2.18 ms`** avg (`max = 6.82 ms`)
  - Évaluation RAM `Eval(A(N), w(N))` (`n=62` incrémental) : **`0.155 ms`** avg (`max = 0.59 ms`)
  - Persistance `read_pot_balance` (`n=64`) : **`1.33 ms`** avg (`max = 6.09 ms`)
  - Persistance `read_pot_balance_aggregate` (`n=64`, `1` bucket, `100` entrées) : **`1.46 ms`** avg (`max = 4.71 ms`)

### 2.2 Pot Moyen — Faible charge et concurrence (`med-1`, `med-5`)

| Campagne | Métrique | L7.4-A3 (`PotBalance` full) | L7.4-A4 (`PotBalance` `N−m`) | **Gain propre A4 vs A3** |
|---|---|---:|---:|---:|
| **`med-1`** (`20/200`, `5` pots, `1.0 cmd/s`, `n=60`, `PASS`) | **`PotBalance` p50 / p95 / p99*** | `8.46` / `12.45` / `15.66 ms` | **`4.39` / `6.10` / `8.09 ms`** | **`-48.1%` / `-51.0%` / `-48.3%`** |
| **`med-1`** | **`PotVersion` p50 / p95** | `9.95` / `13.70 ms` | **`8.69` / `13.11 ms`** | `-12.7%` / `-4.3%` |
| **`med-1`** | **E2E `C-03` p50 / p95** | `102.29` / `138.81 ms` | `105.86` / `136.85 ms` | `+3.5%` / `-1.4%` *(bruit polling `25 ms`)* |
| **`med-1`** | **E2E `Balance` p50 / p95 / p99*** | `97.70` / `139.10` / `190.45 ms` | `100.65` / `133.67` / **`147.57 ms`** | `+3.0%` / `-3.9%` / **`-22.5%`** |
| **`med-1`** | **CPU `postgres` / `read-worker` avg** | `71.5%` / `37.6%` | `74.3%` / `38.2%` | Stable (`+2.8` / `+0.6` pts) |
| **`med-5`** (`20/200`, `5` pots, `5.0 cmd/s`, `n=150`, `PASS`) | **`PotBalance` p50 / p95 / p99** | `15.40` / `22.14` / `36.15 ms` | **`4.27` / `6.91` / `14.67 ms`** | **`-72.3%` (`3.6×`) / `-68.8%` / `-59.4%`** |
| **`med-5`** | **`PotVersion` p50 / p95 / p99** | `9.72` / `15.43` / `32.45 ms` | **`8.74` / `12.32` / `19.19 ms`** | `-10.1%` / `-20.2%` / `-40.9%` |
| **`med-5`** | **E2E `C-03` p50 / p95 / p99** | `100.58` / `169.81` / `198.64 ms` | **`94.58` / `148.00` / `181.31 ms`** | **`-6.0%` / `-12.8%` / `-8.7%`** |
| **`med-5`** | **E2E `Balance` p50 / p95 / p99** | `96.85` / `160.89` / `173.59 ms` | **`93.48` / `134.98` / `147.35 ms`** | **`-3.5%` / `-16.1%` / `-15.1%`** |
| **`med-5`** | **CPU `postgres` / `read-worker` avg** | `111.4%` / `57.5%` | `114.0%` / `55.7%` | Stable (`+2.6` / `-1.8` pts) |

**Recours incrémental & distances `N − K` sur `med-1` et `med-5` (seeding parallèle + charge)** :
- **`med-1`** (`1 080` matérialisations) : **`1 075 / 1 075` (`100%` hors `N = 1`)** en incrémental (`5` `initial_version`, `0` autre fallback), dont `1 063` à `N − K = 1` et **`12` hors ordre à `N − K = 2`**.
- **`med-5`** (`1 175` matérialisations) : **`1 170 / 1 170` (`100%` hors `N = 1`)** en incrémental (`5` `initial_version`, `0` autre fallback), dont `1 164` à `N − K = 1` et **`6` hors ordre à `N − K = 2`**.

---

## 3. Campagnes ciblées obligatoires

### 3.1 Changements de poids sur Grand Pot (`weight-update-large`)

- **Protocole** : restauration du snapshot Grand Pot (`100` shareholders, `2 000` dépenses, `2` pots) + migration `V2`, `2` commandes de warmup (établissant $\mathcal{A}(2003)$), puis **`30` commandes `UpdateShareholderWeights`** (modifiant chacune `3` poids par défaut sur les pots de `2 000` dépenses), avec vérification de la somme nulle exacte en rationnels sur chaque version.
- **Résultats** :
  - **Verdict / Sûreté** : **`PASS`** / **`PASS`** (`0` erreur, `0` divergence de somme nulle).
  - **Chemin incrémental** : **`30 / 30` (`100%`)** pendant la fenêtre de changement de poids (`2` `no_reference` durant le warmup initial pré-V2).
  - **Dépenses relues sur le chemin incrémental (`pocoma.v3.potbalance.delta.expenses` delta)** : **`0`** sur les `30` changements de poids — preuve directe qu'**aucune des `2 000` dépenses historiques n'est relue individuellement lors d'un changement de poids**.
  - **Latence `MaterializePotBalance` (`n = 30`, hors warmup)** : **`p50 = 8.00 ms`**, **`p95 = 14.00 ms`**, **`max = 14.00 ms`** (vs `381.61 ms` p50 en A3, **`~47.7×` plus rapide**).
  - **Latence E2E `Balance` READ (`n = 30`)** : **`p50 = 121.80 ms`**, **`p95 = 176.65 ms`**, **`max = 183.54 ms`**.
  - **Décomposition interne Micrometer (`30` changements de poids)** : lookup $\mathcal{A}(K)$ `1.09 ms` avg, `financialDelta` `1.49 ms` avg (`0` dépense), évaluation `Eval(A(N), w(N))` **`0.19 ms` avg** (`max = 0.40 ms`), persistance balance `1.42 ms` avg, persistance agrégat `1.90 ms` avg.
  - **Volumétrie `read_pot_balance_aggregate` (`100` shareholders, `1` bucket)** : `11 674 B` texte JSON brut (`11.4 KB`), **`5 538 B` (`5.4 KB`) sur disque** (`pg_column_size`, max `5 588 B`).

### 3.2 Versions hors ordre et distances `N − K > 1` sur Grand Pot (`out-of-order-nm-large`)

- **Protocole** : restauration du snapshot Grand Pot (`100/2000`, `2` pots) + warmup $\mathcal{A}(2003)$, mise en pause de `business-read-worker`, empilement de **`20` commandes mixtes** (`CreateExpense` + `UpdateShareholderWeights`, `10`/pot), puis redémarrage de `business-read-worker` pour drainer le backlog en concurrence sur 4 partitions.
- **Résultats** :
  - **Verdict / Sûreté** : **`PASS`** / **`PASS`** (toutes les `20` versions vérifiées avec somme nulle exacte, `20` lignes agrégats persistées).
  - **Taux incrémental post-redémarrage** : **`18 / 18` (`100%`)**, `0` fallback, `0` `corrupt`. *(Note : `2` événements avaient déjà été traités lors du drain gracieux avant l'arrêt complet du conteneur.)*
  - **Distribution observée des distances `N − K` (`n = 18`)** :
    - `N − K = 1` : `8` événements (`44.4%`)
    - `N − K = 2` : `6` événements (`33.3%`)
    - `N − K ∈ {3, 4}` : `3` événements (`16.7%`)
    - `N − K = 5` : `1` événement (`5.6%`) — **`max = 5`**, moyenne `1.89`, **`55.6%` traités hors ordre avec `N − K > 1`**.
  - **Dépenses modifiées lues par événement** : moyenne **`1.44`**, max **`3`** (vs `2 000` en intégral).
  - **Latence `MaterializePotBalance` (`n = 18` sous rafale concurrente à froid)** : **`p50 = 22.00 ms`**, **`p95 = 94.00 ms`**, **`max = 94.00 ms`**.
  - **Volumétrie `read_pot_balance_aggregate`** : `11 676 B` texte brut, **`5 512 B` (`5.4 KB`)** sur disque (`pg_column_size`).

---

## 4. Bilan des gains, régressions et limites

### Gains démontrés (propres à A4)
1. **Élimination du goulot `PotBalance` sur Grand Pot** : `MaterializePotBalance` p50 passe de `381.61 ms` (A3) à **`8.39 ms`** (**`-97.8%`**, `45.5×`), faisant chuter l'E2E `C-03` p50 de `511.03 ms` à **`157.76 ms`** (**`-69.1%`**) et le CPU `business-read-worker` de `77.2%` à **`45.4%`** (`-31.8` pts).
2. **Gains nets sur pots moyens et sous concurrence** : `-48.1%` en p50 sur `med-1` (`8.46` → `4.39 ms`) et **`-72.3%`** en p50 sur `med-5` (`15.40` → `4.27 ms`).
3. **Changements rétroactifs de poids en $O(S + G)$** : `0` dépense relue sur `30` `UpdateShareholderWeights` sur pots de `2 000` dépenses (`8.00 ms` p50, `0.19 ms` d'évaluation RAM).
4. **Résilience hors ordre sans séquentialité** : absorption à `100%` sur le chemin incrémental des distances `N − K = 2..5` sans attente de `N − 1` ni changement de routage.
5. **Empreinte disque maîtrisée** : `~5.4 KB` compressés par version sur Grand Pot (`100` shareholders, `1` bucket) grâce à l'encodage D1 par indices entiers.

### Régressions constatées
- **Aucune régression fonctionnelle ou de latence** sur les 5 campagnes. Sur `med-1`, la variation de `+3.5 ms` sur l'E2E `C-03` p50 (`102.3` → `105.9 ms`) reste largement inférieure au pas de polling client (`25 ms`), tandis que `PotBalance` baisse de `-48.1%` et l'E2E `Balance` p99 baisse de `-22.5%`.

### Limites documentées
1. **Taille des échantillons** : `n = 60` (`restore-large-1`, `med-1`), `n = 150` (`med-5`), `n = 30` (`weight-update-large`), `n = 18` (`out-of-order-nm-large`). Sur `n <= 60`, `p99` coïncide avec le maximum observé ; aucune extrapolation vers des queues p99.9 n'est revendiquée.
2. **Cardinalité des buckets sur les campagnes de référence** : les snapshots `medium` et `large` de L7.4-A ont tous les shareholders comme participants par défaut (`G = 1` bucket). Les cas multi-buckets, dépenses mixtes et déclenchement du plafond `BUCKET_CAP` (`256 / 10 000`) sont qualifiés fonctionnellement en A4.1/A4.2 ; la performance sur des pots à très haute cardinalité de buckets (`G` proche de `256`) n'a pas fait l'objet d'un benchmark Docker séparé.
3. **Topologie locale mono-hôte** : les mesures Docker locales sont reproductibles pour la comparaison A2/A3/A4 mais ne constituent pas une certification de déploiement multi-nœuds.

---

## 5. Vérification des critères de qualification A4.3 (§12)

| # | Critère | Statut | Preuve |
|---|---|---|---|
| 1 | A4.2 fonctionnellement qualifié | **OK** | Verdict `A4_2_QUALIFIED`, `PROJECTION` `35/35` (`113/113`), architecture `26/26` |
| 2 | Aucune divergence de balances | **OK** | `0` `invariantViolations` sur les 5 campagnes, sommes nulles exactes |
| 3 | Aucune perte ou violation de sûreté | **OK** | `0` drop, `0` restart, `reconciliationComplete = True`, `safety = PASS` |
| 4 | Utilisation démontrée du chemin incrémental | **OK** | `62/64` (`restore-large-1`), `1075/1075` (`med-1`), `1170/1170` (`med-5`), `30/30` (`weight-update-large`), `18/18` (`out-of-order-nm-large`) |
| 5 | Absence de relecture intégrale des dépenses lors des changements de poids | **OK** | `expensesReadDuringWeightUpdates = 0` sur `30` `UpdateShareholderWeights` (`100/2000`) |
| 6 | Mesures reproductibles et comparables à A3 | **OK** | Mêmes images épinglées, même topologie, même snapshot SHA-256 |
| 7 | Aucun ralentissement majeur non expliqué | **OK** | Gains de `-48%` à `-97.8%` sur `MaterializePotBalance` |
| 8 | Bilan explicite des gains, régressions et limites | **OK** | Section 4 ci-dessus |

---

## Verdict final A4.3

**`A4_3_QUALIFIED`** — Le calcul incrémental `N−m` de `PotBalance` atteint **`8.39 ms` p50** sur Grand Pot (`-97.8%`, `45.5×` vs A3), réduit l'E2E `C-03` p50 de **`-69.1%`** (`511.0 ms` → `157.8 ms`) et le CPU `business-read-worker` de **`-31.8` pts**, démontre **`0` dépense relue** sur `30` changements de poids (`8.00 ms` p50), et absorbe les versions hors ordre jusqu'à `N − K = 5` avec `0` divergence et `0` violation de sûreté.
