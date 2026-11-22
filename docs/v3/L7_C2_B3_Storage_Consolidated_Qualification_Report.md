# Pocoma V3 — L7-C2-B3 — Rapport de qualification consolidée du stockage READ (C2)

- **Date** : 20 novembre 2026
- **Branche qualifiée** : `feat/v3-l7` (aucun merge vers `main`, C3 et C4 non engagés)
- **Candidat final C2** : `d25309256aae47f3608629901184de8c316828c6` *(+ ajout exclusif du test transversal `jointLeaseRevocationAndInterruptionPreserveAtomicityAndIndependence` dans `PotVersionAndBalanceJointIncrementalPostgresTest.java`, outillage B3 et preuves `L7-C2-B3/` ; `0` modification de code de production)*
- **Baselines comparées** :
  - **A3+A4 avant C2** : `b8924f3d` (`L7.4-A3+A4` consolidé : incrémental `N−m`, manifestes JSONB V1+V2)
  - **Après C2-B1** : `e25cd84b` (`L7_C2_B1_CLOSED` : `V3` Radix-16 `PotVersion` + `PotBalance` JSONB)
  - **Après C2-B2 (candidat final)** : `d2530925` (`L7_C2_B2_CLOSED` : `V4` Radix-16 + `PotBalance` binaire mutualisé + `read_pot_shareholder_set`)
- **Documents de référence** :
  - [`L7_C2_B1_4_PotVersion_Radix16_Qualification_Report.md`](L7_C2_B1_4_PotVersion_Radix16_Qualification_Report.md)
  - [`L7_C2_B2_4_PotBalance_Qualification_Report.md`](L7_C2_B2_4_PotBalance_Qualification_Report.md)
  - [`L7_4_A3_A4_Consolidated_Qualification_Report.md`](L7_4_A3_A4_Consolidated_Qualification_Report.md)
  - [`Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)
- **Verdict final** :
  - **`L7_C2_B3_QUALIFIED`**
  - **`L7_C2_CLOSED`**

---

## 1. Matrice de réconciliation des preuves (`PotVersion`, `PotBalance`, `PotBalanceAggregate`)

| # | Obligation / Invariant | Preuve existante & complémentaire B3 | SHA | Environnement | Oracle | Couverture | Nouvel essai B3 |
|---|---|---|---|---|---|---|---|
| 1 | **Exactitude métier** | `PotVersionAndBalanceJointIncrementalPostgresTest`, `ab-medium`, `ab-large`, `multibucket-realistic` (`60/60`), `multibucket-d5-boundary` (`59/59`) | `d2530925` | PG 17 TC + 5 runtimes Docker | Oracle rationnel `Fraction` + `PotBalanceCalculator` + A/B HTTP | Complète | `multibucket-d5-boundary` (`59/59`) exécuté sur `d2530925` |
| 2 | **Immutabilité historique** | `PotVersionRadixSchemaPostgresTest`, `PotBalanceSharedSchemaPostgresTest`, triggers `ENABLE ALWAYS` (`23514`) | `c3f52dd4`..`d2530925` | PG 17 Testcontainers | Triggers PostgreSQL `ENABLE ALWAYS` | Complète | Couvert par slice `PROJECTION` sur `d2530925` |
| 3 | **Lecture de versions arbitraires** | `PotVersionReadStorePostgresTest`, `PotVersionTargetedReadPostgresTest`, `PotBalanceReadStorePostgresTest`, `ab-medium`/`ab-large` (`v=1`, `v=mid`, `v=cur`) | `e25cd84b`, `d2530925` | PG 17 TC + 5 runtimes | Comparaison HTTP champ par champ A/B | Complète | Requalifié sur `large-consolidated` (`d2530925`) |
| 4 | **Reconstruction déterministe** | Double rebuild `ab-medium` (`deterministic=True`), `ab-large` (`balSha=4525682e...`, `pvSha=838fdeeb...`), `large-consolidated` | `d2530925` | 5 runtimes Docker + WRITE L7.4 | Empreintes SHA-256 triées sur toutes tables READ | Complète | `large-consolidated` (`d2530925`) confirme SHA identiques |
| 5 | **Matérialisation N−m** | `PotVersionAndBalanceJointIncrementalPostgresTest`, `restore-large-1`, `med-1`, `med-5`, `multibucket-realistic` | `d2530925` | PG 17 TC + 5 runtimes | Micrometer `materialize.path` + oracles | Complète | `med-5` (`150` cmds) exécuté sur `d2530925` |
| 6 | **Fallback intégral** | `independentFallbacksForBucketCapAndAsymmetricMaxDeltaDistance`, `multibucket-d5-boundary` (`13/13` `bucket_cap` à `G>256`) | `d2530925` | PG 17 TC + 5 runtimes | Oracle `Fraction` + 0 ligne agrégat `>256` | Complète | `multibucket-d5-boundary` exécuté sur `d2530925` |
| 7 | **Replay et hors ordre** | `jointOutOfOrderConcurrencyRecoveryIdempotenceAndReconstructionMatchBothOracles` (`v6,3,8,2,5,4,7` vs `v5,8,2,7,3,6,4`), `ReadReconstructionPostgresTest` | `d2530925` | PG 17 Testcontainers | Oracles `JdbcPotVersionSource` + `PotBalanceCalculator` | Complète | Revalidé dans slice `PROJECTION` |
| 8 | **Concurrence** | Exécution parallèle virtual threads `PotVersion` + `PotBalance`, `ReadReconstructionPostgresTest` (`rcv01I`) | `d2530925` | PG 17 Testcontainers | Oracles + absence d'erreur SQL / lock | Complète | Revalidé dans slice `PROJECTION` |
| 9 | **Fencing** | `InterAuthorityRecoveryPostgresTest`, `ReadReconstructionPostgresTest` (`rcv01B`), nouveau test `jointLeaseRevocation...` | `d2530925` | PG 17 Testcontainers | `Status != COMMITTED` + 0 ligne partielle | Complète | Nouveau test transversal ajouté & passé |
| 10 | **Atomicité des publications** | `PotVersionReadStorePostgresTest`, `PotBalanceReadStorePostgresTest`, `SubobjectAtomicityPostgresTest`, `jointLeaseRevocation...` | `d2530925` | PG 17 Testcontainers | Rollback intégral + vérification multi-tables | Complète | Vérifié sous révocation de lease |
| 11 | **Intégrité des références physiques** | `diagnose-pot-version-unreachable.sql`, `diagnose-pot-balance-unreachable.sql`, `ReadinessOracle` (`FK` + hashes) | `d2530925` | 5 runtimes Docker (`READ ONLY`) | `0` nœud/set/contenu inaccessible (`0 B`) | Complète | Revalidé post-rebuild & post-mutations (`large-consolidated`) |
| 12 | **Indépendance des projections** | `PotVersionAndBalanceJointIncrementalPostgresTest`, `non-financial-ab`, `multibucket-d5-boundary` (`PotVersion` 100% inc. pendant `bucket_cap`) | `d2530925` | PG 17 TC + 5 runtimes | Checkpoints & compteurs indépendants | Complète | Prouvé sur `d5-boundary` + `jointLeaseRevocation...` |
| 13 | **Privilèges PostgreSQL** | `grant-l7-http-read.sql`, `verify_http_read_privileges` (`ab-medium`, `ab-large`, `large-consolidated`), `ReadConsumptionLifecyclePrivilegesPostgresTest` | `d2530925` | PG 17 TC + 5 runtimes | `pocoma_read_http`: SELECT autorisé sur balance/sets/nodes, refusé sur agrégats | Complète | Revalidé sur `large-consolidated` (`d2530925`) |
| 14 | **Compatibilité HTTP** | `ab-medium`, `ab-large`, `non-financial-ab` (`exactMatch=true`, 6 endpoints), `ReadHttpContractTest`, `HttpCompositionPostgresTest` | `d2530925` | 5 runtimes + MockMvc/PG | Comparaison champ par champ A/B (`0` divergence) | Complète | Latences HTTP revalidées sur `large-consolidated` |
| 15 | **Contrôles de readiness** | `ReadinessOracle` (`28/28` CP, `0` slot, intégrité V3+V4), `ReadRuntimeAssemblyPostgresTest`, `ReadReconstructionPostgresTest` | `d2530925` | PG 17 TC + 5 runtimes | `ReadinessOracle = READY` | Complète | Revalidé sur `large-consolidated` |
| 16 | **Reprise après interruption** | `InterAuthorityRecoveryPostgresTest`, `ReadReconstructionPostgresTest` (`rcv01G`, `rcv01K`), `jointLeaseRevocation...` | `d2530925` | PG 17 Testcontainers | `EffectEvidence.SATISFIED` + convergence | Complète | Nouveau test `jointLeaseRevocation...` |

---

## 2. État exact de l'architecture (clôture C2)

Le schéma `v3_read` (`V1__initial_schema.sql` → `V2__pot_balance_aggregate.sql` → `V3__pot_version_radix16.sql` → `V4__pot_balance_shared_storage.sql`) est consolidé sans aucune modification supplémentaire dans C2-B3 :

1. **`PotVersion` (C2-B1 — `V3`)** :
   - `read_pot_version` (`1` ligne/version, FK vers `shareholders_root`, `expenses_root`, `authorized_users_hash`, `attached_active_users_hash`, cardinalités `O(1)`).
   - `read_pot_version_node` (arbre Radix-16 compressé adressé par SHA-256 `pocoma-r16-v1\0`, feuilles `L=64` en binaire fixe `41 B/entrée`, garde `BEFORE INSERT` d'acyclicité/unicité).
   - `read_pot_user_set` (ensembles d'autorisations SHA-256 `pocoma-uset-v1\0`).
2. **`PotBalance` et `PotBalanceAggregate` (C2-B2 — `V4`)** :
   - `read_pot_shareholder_set` (ensembles ordonnés de shareholders adressés par SHA-256 `pocoma-shset-v1\0`, mutualisés entre `PotBalance` et `PotBalanceAggregate`).
   - `read_pot_balance` + `read_pot_balance_content` (pointeur par version vers contenu binaire compact sans UUID `pocoma-bal-v1\0`).
   - `read_pot_balance_aggregate` + `read_pot_balance_aggregate_content` (pointeur par version vers agrégat binaire sans UUID `pocoma-bagg-v1\0`, insertion CTE atomique groupée).
3. **Isolation & immuabilité** : triggers `ENABLE ALWAYS` sur toutes les tables de contenu, nœuds, ensembles et pointeurs ; privilèges `pocoma_read_http` strictement limités aux tables de lecture publique (zéro accès aux tables d'agrégats).

---

## 3. Qualification transversale complémentaire (`d2530925`)

### 3.1 Matérialisation conjointe, interruption et révocation de lease (§5.1 & §5.2)

Ajout de `jointLeaseRevocationAndInterruptionPreserveAtomicityAndIndependence` dans [`PotVersionAndBalanceJointIncrementalPostgresTest.java`](../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/PotVersionAndBalanceJointIncrementalPostgresTest.java) :
- `Worker 1` acquiert le lease partition et le claim `PotBalance` sur `v2`, puis son lease partition et son claim expirent et `Worker 2` prend le relais (`ownerToken` distinct, nouveau claim sur `v2`).
- Pendant l'interruption de `PotBalance`, `PotVersion` avance indépendamment sur `v2`, `v3`, `v4` (`COMMITTED`).
- L'exécution tardive du claim périmé par `Worker 1` échoue au fencing (`Status != COMMITTED`) et laisse **`0` ligne partielle** dans `read_pot_balance` et les tables associées.
- `Worker 2` reprend et converge sur `v2..v4`, avec équivalence exacte contre les deux oracles et `EffectEvidence.SATISFIED`.

### 3.2 Reconstruction READ consolidée sur `d2530925` (`large-consolidated`)

Exécutée via [`scripts/performance/run-l7-c2-b3-consolidated.py`](../../scripts/performance/run-l7-c2-b3-consolidated.py) sur le snapshot WRITE L7.4 `large` (`sha256=2eb4567b568073c7d4e0c5bbd8db40aadfaeb222ed1c7e504bd6ed91fdab637d`, `4 004` versions) :
- **Reconstruction effective** (`workers → ReadinessOracle = READY`) : **`45.27 s`** (`bootstrap = 10.22 s`, `total = 55.49 s`).
- **Empreintes déterministes exactes** :
  - `PotVersion` : `4 004` versions, `sha256 = 838fdeebb675687baae185346bb3c344168fe18e46ecf66777dadc7f8e09dc86` (identique à B1.4 & B2.4).
  - `PotShareholderSet` : `3` ensembles, `sha256 = 252303c4c1539f71cbffe3de7e2ab2a3c3cabcb00fc7bc78e47cc6f0b121c268`.
  - `PotBalanceContent` : `4 003` contenus, `sha256 = 4525682e175d608bb165ed39292ef37c9000c2d0d7bdff1b1d3f64f0d8804d65` (identique à B2.4).
  - `PotBalanceAggregateContent` : `4 003` contenus, `sha256 = f1b798a761ea62a643491fc995c6ae12a195a9793fce8723f0f3d7532e0d259e`.
- **Diagnostics F4 (`BEGIN TRANSACTION READ ONLY`)** :
  - Post-rebuild (`4 004` versions) : `0` nœud inaccessible (`10 354/10 354`), `0` user set inaccessible (`1/1`), `0` balance content inaccessible (`4 003/4 003`), `0` shareholder set inaccessible (`3/3`), `0` aggregate content inaccessible (`4 003/4 003`).
  - Post-mutations (`+20` mixtes, `4 024` versions) : **`0 / 0 / 0 / 0 / 0` (`0 B`)** inaccessibles (`4 016` contenus distincts, `3` shareholder sets).

### 3.3 Campagne `multibucket-d5-boundary` sur `d2530925`

Exécutée via [`run-l7-4-a3-a4-multibucket.py`](../../scripts/performance/run-l7-4-a3-a4-multibucket.py) (`docs/testing/evidence/L7-C2-B3/multibucket-d5-boundary/manifest.json`) :
- **Phase A (`near-d5-30`, `G = 240`, `6 401` entrées)** : `30/30` incrémental `PotBalance` & `PotVersion`, `0` divergence oracle `Fraction`, taille disque moyenne agrégat **`10 533 B`** (vs `21 319 B` en A3+A4, **`-50.6%` / `2.02×`**), `persistAggregate` **`1.568 ms`** (vs `5.744 ms`, **`3.66×`**), E2E `C-03` p50 **`181.01 ms`** (vs `198.37 ms`).
- **Phase B (`exceed-d5`, `G = 241..260 -> 258`)** : `16/16` incrémental jusqu'à `G = 256`, puis bascule propre `13/13` `full:bucket_cap` au-dessus de `256` (`0` ligne agrégat écrite au-delà de `256`), `PotVersion` reste **`29/29` (`100%`) incrémental**, `0` divergence oracle `Fraction`, E2E `C-03` p50 **`217.09 ms`** (vs `235.02 ms`).

---

## 4. Comparaison consolidée A3+A4 / C2-B1 / C2-B2+B3 (`LARGE` `4 004` versions)

| Indicateur (`LARGE` 100/2000, `4 004` versions) | A3+A4 (`b8924f3d`) | Après C2-B1 (`e25cd84b`) | **Après C2-B2 / B3 (`d2530925`)** | **Gain cumulé C2 vs A3+A4** |
|---|---:|---:|---:|---:|
| **`pocoma_read` total post-restore** | `242.60 MB` *(sans 4004 agg.)* / `~268 MB` *(est. avec 4004 agg.)* | `95.64 MB` (`91.21 MiB`) | **`60.52 MB` (`57.72 MiB`)** | **`~4.4×` (`-77%` vs `~268 MB`)** |
| **Sous-total `PotVersion*`** | `185.13 MB` | `9.86 MB` | **`9.89 MB`** | **`18.72×` (`-94.7%`)** |
| **Sous-total `PotBalance*` (avec `aggregate`)** | `48.31 MB` *(22.90 + 25.41 MB)* | `48.21 MB` | **`13.06 MB`** | **`3.70×` (`-73.0%`)** |
| **Sous-total optimisé C2 (`PotVersion* + PotBalance*`)** | **`233.44 MB`** | `58.07 MB` | **`22.95 MB`** | **`10.17×` (`-90.2%`)** |
| **Autres tables READ (`read_subobject_version` + `read_current_pot`)** | `24.89 MB` | `25.21 MB` | **`25.15 MB`** | Inchangé (hors périmètre C2) |
| **Écriture `PotVersion` p50 / p95 (`restore-large-1`)** | `29.94` / `40.80 ms` | `9.63` / `14.02 ms` | **`9.35 ms` p50** | **`3.20×` (`-68.8%`)** |
| **Écriture `PotBalance` p50 / p95 (`restore-large-1`)** | `8.39` / `12.58 ms` | `6.25` / `8.33 ms` | **`4.33` / `5.99 ms`** | **`1.94×` (`-48.4%`)** |
| **E2E `C-03` READ p50 (`restore-large-1`)** | `157.76 ms` | `132.14 ms` | **`133.82 ms`** | **`-15.2%`** |
| **HTTP `GET .../balance` p50 (`ab-large`)** | `8.52 ms` | `3.96 ms` | **`3.86 ms`** *(B3)* / `4.07 ms` *(B2.4)* | **`2.21×` (`-54.7%`)** |
| **HTTP `POST .../expenses/query` 50 dép. p50 (`ab-large`)** | `45.94 ms` | `18.72 ms` | **`17.91 ms`** *(B3)* | **`2.56×` (`-61.0%`)** |

---

## 5. Gains de stockage cumulés et décomposition exacte (`d2530925`)

Mesure exacte par table sur `large-consolidated` (`d2530925`, `4 004` versions reconstruites depuis WRITE) :

| Table `v3_read` | Lignes | Contenus distincts | Heap | Index | TOAST | **Total** | Rôle & Gain |
|---|---:|---:|---:|---:|---:|---:|---|
| `read_pot_version` | `4 004` | — | `901 120 B` | `278 528 B` | `32 768 B` | **`1 212 416 B`** | Manifestes `PotVersion` (vs `185.13 MB` A3+A4) |
| `read_pot_version_node` | `10 354` | `10 354` | `7 815 168 B` | `794 624 B` | `32 768 B` | **`8 642 560 B`** | Nœuds Radix-16 mutualisés |
| `read_pot_user_set` | `1` | `1` | `8 192 B` | `16 384 B` | `8 192 B` | **`32 768 B`** | Ensembles d'autorisations mutualisés (`1` pour `4 004` v) |
| `read_pot_shareholder_set` | `3` | `3` | `8 192 B` | `16 384 B` | `8 192 B` | **`32 768 B`** | Ensembles shareholders mutualisés (`3` pour `4 004` v, `99.93%` réut.) |
| `read_pot_balance` | `4 004` | — | `409 600 B` | `278 528 B` | `32 768 B` | **`720 896 B`** | Pointeurs logiques balance |
| `read_pot_balance_content` | `4 003` | `4 003` | `3 571 712 B` | `303 104 B` | `32 768 B` | **`3 907 584 B`** | Balances binaires sans UUID (TOAST éliminé) |
| `read_pot_balance_aggregate` | `4 004` | — | `409 600 B` | `278 528 B` | `32 768 B` | **`720 896 B`** | Pointeurs logiques agrégat |
| `read_pot_balance_aggregate_content` | `4 003` | `4 003` | `7 323 648 B` | `319 488 B` | `32 768 B` | **`7 675 904 B`** | Agrégats binaires sans UUID (TOAST éliminé) |
| **Sous-total optimisé C2** | — | — | `20 447 232 B` | `2 285 568 B` | `221 184 B` | **`22 953 984 B` (`21.89 MiB`)** | **`10.17×` de réduction vs `233.44 MB` A3+A4** |
| `read_subobject_version` | `12 604` | — | `1 572 864 B` | `1 359 872 B` | `22 192 128 B` | **`25 124 864 B`** | Hors C2 (sous-objets JSONB, `88%` TOAST) |
| `read_current_pot` | `2` | — | `8 192 B` | `16 384 B` | `0 B` | **`24 576 B`** | Hors C2 |
| **Reste base `pocoma_read` (système, index, slots, Flyway)** | — | — | — | — | — | **`~12.42 MB`** | *(cf. `60.52 MB` total `restore-large-1`)* |
| **Total `pocoma_read` (`restore-large-1`)** | — | — | — | — | — | **`60 520 115 B` (`57.72 MiB`)** | *(contre `242.60 MB` pré-C2)* |

**Constat clé** :
- Sur le périmètre optimisé C2, les tables `PotVersion* + PotBalance*` passent de **`233.44 MB` → `22.95 MB` (`10.17×`, `-90.2%`)**.
- Le premier poste restant de `pocoma_read` est désormais `read_subobject_version` (`25.12 MB`, dont `22.19 MB` de TOAST), qui n'était pas dans le périmètre C2.
- **Croissance par version (`XLARGE` 500/10 000)** : `PotVersion` passe de `~434.9 KB/ver` à **`~3.97 KB/ver`** (`109.5×`), et `PotBalance*` (Sit. D) de `~48.5 KB/ver` à **`~6.8 KB/ver`** (`7.13×`), et **`0 B/ver`** en non-financier (Sit. A).

---

## 6. Gains de WAL

| Régime / Mesure | A3+A4 (`b8924f3d`) | C2-B1 (`e25cd84b`) | **C2-B2 / B3 (`d2530925`)** | **Gain** |
|---|---:|---:|---:|---:|
| **`XLARGE` (500/10k) — WAL propre `PotVersion` (99 ver)** | `44.30 MB` (`437.0 KB/v`) | **`449.8 KB` (`4.44 KB/v`)** | **`449.8 KB` (`4.44 KB/v`)** *(inchangé)* | **`98.49×` (`-99.0%`)** |
| **`XLARGE` (500 sh) — WAL propre `PotBalance*` Sit. D (100 ver)** | — | `4.60 MB` (`45.95 KB/v`) | **`444.3 KB` (`4.44 KB/v`)** | **`10.34×` (`-90.3%`)** |
| **`XLARGE` (500 sh) — WAL propre `PotBalance*` Sit. A (50 ver)** | — | `2.31 MB` (`46.29 KB/v`) | **`34.5 KB` (`690 B/v`)** | **`67.1×` (`-98.51%`)** |
| **`ab-large` (20 mixtes) — WAL cluster total** | `4 257 608 B` (`212.9 KB/v`) | `3 730 888 B` (`186.5 KB/v`) | `998 720 B` *(B2.4)* / `1 904 920 B` *(B3)* | **`-55.3%` à `-76.5%` vs A3+A4** |
| **`restore-large-1` (60 cmds `100/2000`) — WAL `pocoma_read` total** | `18.66 MB` | `12.18 MB` | **`11.67 MB`** | **`-37.5%`** *(dominé par contrôle/polling)* |

---

## 7. Latences READ, Matérialisation et Bout-en-Bout (`d2530925`)

### 7.1 Matérialisation et E2E (`MEDIUM`, `LARGE`, `Multibucket`)

| Campagne | `PotVersion` p50 / p95 | `PotBalance` p50 / p95 | `PotVisibility` p50 / p95 | `CommandResult` p50 / p95 | E2E `C-03` p50 / p95 | E2E `Balance` p50 / p95 |
|---|---:|---:|---:|---:|---:|---:|
| **`med-1`** (`20/200 @ 1 cmd/s`, B2.4) | `8.40` / `12.66 ms` | `4.06` / `6.04 ms` | `2.46` / `3.64 ms` | `1.37` / `2.14 ms` | `101.29 ms` p50 | — |
| **`med-5`** (`20/200 @ 5 cmd/s`, **B3 `d2530925`**) | **`7.99` / `11.80 ms`** | **`4.02` / `6.18 ms`** | **`2.30` / `3.37 ms`** | **`1.35` / `2.16 ms`** | **`81.27` / `159.22 ms`** | **`83.17` / `138.06 ms`** |
| **`restore-large-1`** (`100/2000`, B2.4) | `9.35 ms` p50 | `4.33` / `5.99 ms` | `2.70` / `3.52 ms` | `1.44` / `2.05 ms` | `133.82 ms` p50 | — |
| **`multibucket-realistic`** (`G=60..62`, B2.4) | — | `1.090 ms` *(CTE persist)* | — | — | `148.55 ms` p50 | — |
| **`multibucket-d5-boundary`** (`G=240`, **B3 `d2530925`**) | — | `1.568 ms` *(CTE persist)* | — | — | **`181.01` / `237.33 ms`** | — |

### 7.2 Latences HTTP READ (warm, `n=30`) et Store `XLARGE`

| Endpoint / Opération | `ab-medium` (`d2530925`) | `large-consolidated` (**B3 `d2530925`**) | `XLARGE` Store (500/10k, B1.4/B2.4) |
|---|---:|---:|---:|
| `GET /pots/{id}` (courant complet R1) | `8.05 ms` *(B1.4)* | **`79.43 ms` p50 / `90.42 ms` p95** | **`3.68` / `6.19 ms`** (`5.22×` vs A) |
| `GET /pots/{id}/versions/{mid}` (historique R1) | `5.80 ms` *(B1.4)* | **`13.21 ms` p50 / `84.00 ms` p95** | **`3.54` / `11.01 ms`** (`6.09×` vs A) |
| `GET /pots/{id}/versions/{v}/balance` (R3a) | `4.10 ms` p50 *(B2.4)* | **`3.86 ms` p50 / `79.53 ms` p95** | **`1.54 ms`** (`24%` mieux que B1.4) |
| `POST .../expenses/query` (1 dép., R3b) | `4.43 ms` *(B1.4)* | **`4.39 ms` p50 / `81.50 ms` p95** | **`1.96` / `3.72 ms`** (`9.79×` vs A) |
| `POST .../expenses/query` (50 dép., R3c) | `9.85 ms` *(B1.4)* | **`17.91 ms` p50 / `82.95 ms` p95** | **`2.16` / `4.30 ms`** (`8.88×` vs A) |

**Distinction ordonnancement vs temps utile (`med-5` / `restore-large-1`)** :
- Sur `med-5`, la somme des temps utiles de matérialisation (`CommandWorker 8.19 ms + PotVersion 7.99 ms + PotBalance 4.02 ms + PotVisibility 2.30 ms + CommandResult 1.35 ms ≈ 23.85 ms`) représente ~`29%` de la latence E2E `C-03` (`81.27 ms`), le reste (`~57 ms`) provenant des délais de polling/découverte et d'ordonnancement inter-workers.

---

## 8. Profil de ressources des workers (préparation C4 — sans modification de topologie)

Données extraites de [`docs/testing/evidence/L7-C2-B3/worker_resources_and_db_comparison.json`](../testing/evidence/L7-C2-B3/worker_resources_and_db_comparison.json) sur `restore-large-1` (`60` commandes, `~60 s`) :

| Conteneur | CPU avg / max (A3+A4 → **B2.4**) | RAM avg (B2.4) | Heap utilisé / max | GC pauses / temps | Saturation Δ |
|---|---:|---:|---:|---:|---:|
| **`postgres`** | `76.18% / 86.22%` → **`72.36% / 76.24%`** | `943.6 MiB` | — | — | — |
| **`business-read-worker`** | `45.38% / 93.22%` → **`37.27% / 42.16%`** | `548.3 MiB` | `221.7 / 494.9 MiB` | `22` / **`46 ms`** *(vs `878 ms` A3+A4)* | **`44`** *(vs `108` A3+A4)* |
| **`command-worker`** | `26.05% / 36.73%` → **`25.43% / 49.69%`** | `206.8 MiB` | — | — | — |
| **`results-worker`** | `25.85% / 39.47%` → **`22.36% / 24.32%`** | `202.7 MiB` | — | — | — |
| **`http-read`** | `11.30% / 25.42%` → **`7.72% / 20.78%`** | `203.3 MiB` | — | — | — |
| **`http-write`** | `1.65% / 3.93%` → **`1.53% / 2.89%`** | `189.6 MiB` | — | — | — |

### Analyse détaillée de `business-read-worker` et des pools PostgreSQL (`restore-large-1` B2.4)

- **Répartition du temps utile par projection (`65` tentatives chacun)** :
  - `PotVersion` : **`620.16 ms`** (`9.541 ms/attempt`)
  - `PotBalance` : **`288.20 ms`** (`4.434 ms/attempt`)
  - `PotVisibility` : **`172.85 ms`** (`2.659 ms/attempt`)
  - `CurrentBinding` / `Invitation` : **`0.00 ms`** (`0` tentative)
  - **Total temps utile de matérialisation** = **`1.081 s`** sur **`25.67 s` de CPU process** (**`4.2%`** seulement !).
- **Overhead dominant (découverte & contrôle)** :
  - `5` projections × `~5 780` cycles de découverte = **`34.80 s`** de durée cumulée de découverte (`~7.0 s` par projection, dont **`13.84 s` / `40%`** consommés par `Invitation` + `CurrentBinding` alors qu'ils n'ont **aucun** événement à traiter).
  - Pools HikariCP de `business-read-worker` :
    - `read-business` : `715` acquisitions (`0.0207 ms` attente moy., **`245 ms`** d'usage total).
    - `read-control` : **`96 549` acquisitions** (`0.0002 ms` attente moy., **`2 246 ms`** d'usage total).
    - `write-source-read` : **`29 293` acquisitions** (`0.0007 ms` attente moy., **`655 ms`** d'usage total).
  - Base `pocoma_read` : **`519 224` commits** (`~8 650 TPS`), `blks_read = 28` vs `blks_hit = 861 170` (**`99.997%` hit ratio**, contre `21 802` lectures disque en A3+A4).
- **Métriques manquantes / non instrumentées** : IOPS disque physique de l'hôte macOS (stockage `tmpfs` Docker), détail CPU par thread interne JVM non exposé en `.prom`.

### Hypothèse argumentée pour le chantier C4 (rééquilibrage des workers)

1. **Constat** : Après C2, le coût métier de matérialisation (`16.6 ms` cumulés pour `PotVersion + PotBalance + PotVisibility`) n'est plus le goulot. Les ressources dominantes sont le **CPU de `postgres` (`72.4%`)** et le **CPU de `business-read-worker` (`37.3%`)**, tirés à **>95%** par les boucles de découverte/polling et les transactions de contrôle (`96.5k` acquires `read-control`, `519k` commits PG en `60 s`).
2. **Déséquilibre de regroupement** : `business-read-worker` regroupe **5 projections** (`PotVersion`, `PotBalance`, `PotVisibility`, `Invitation`, `CurrentBinding`) dont deux (`Invitation`, `CurrentBinding`) consomment `~40%` du budget de découverte à vide lors des charges pot, tout en partageant la capacité de contention avec les 3 projections pot critiques sur le parcours READ.
3. **Piste C4** : Découpler ou alléger les boucles de polling des projections à faible fréquence (`Invitation`, `CurrentBinding`) et optimiser la cadence/batching de découverte contrôle plutôt que d'optimiser davantage les codecs métier. *(Aucune modification de topologie effectuée dans C2-B3.)*

---

## 9. Limites de mesure

1. **Topologie mono-hôte Docker** (`10` vCPU, `7.75 GiB` RAM, `tmpfs`) : excellente reproductibilité A/B, mais non représentative d'un réseau multi-nœuds ou d'I/O disque NVMe réelles.
2. **Échantillons finis** (`n = 20..150`) : `p99` correspond aux valeurs extrêmes observées.
3. **Endpoint `POST /api/v3/invitations/available-shareholders/query`** : couvert fonctionnellement par les tests HTTP/Java (`InvitationStandaloneWebPostgresTest`), non inclus dans la sonde HTTP latence du banc pot-only.

---

## 10. Réserves restantes

- **Réserves bloquantes** : **AUCUNE (`0`)**.
- **Réserves non bloquantes** :
  1. Absence volontaire de GC des nœuds/contenus inaccessibles (surveillée par les diagnostics `READ ONLY` F4, `0 B` inaccessible constaté).
  2. `read_subobject_version` (`25.12 MB`, `88%` TOAST) devient le premier poste de stockage READ après C2 (hors périmètre C2).
  3. Overhead de polling contrôle (`~8 650 TPS` sur `pocoma_read`), documenté pour C4.

---

## 11. Nouvelle baseline de référence C2 (`d2530925`)

- **Référence Git** : `feat/v3-l7` (`d2530925` + test transversal B3 + preuves `L7-C2-B3/`).
- **Preuves archivées** : [`docs/testing/evidence/L7-C2-B3/`](../testing/evidence/L7-C2-B3/) (`med-5/`, `multibucket-d5-boundary/`, `large-consolidated/`, `worker_resources_and_db_comparison.json`, `consolidated_ab_and_latency_summary.json`).

---

## 12. Vérification Maven / Python et Verdict de clôture

Conformément à [`Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md) :
- **Slices déclarés** : `PROJECTION` (`./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize -am test` sur `35` modules, dont `runtime-worker-materialize` `117/117`, `infra-postgres-read` `39/39`, `infra-postgres-write` `473/473`, `domain-projection` `31/31` — **PASS**, `0` échec, `0` erreur, `0` skip, durée `27:17 min`).
- **Croisement de slice inattendu** : `NONE` (`0` fichier de production modifié).
- **Gate global & Full Reactor** : `d2530925` déjà qualifié sur `43/43` modules (`1 151 / 1 151` PASS, `architecture-tests` `52/52` PASS) ; `0` code de production modifié depuis `d2530925`.
- **Tests Python** : `python3 -m unittest scripts/operations/test_v3_operations.py scripts/performance/test_l7_4a_baseline.py scripts/testing/test_verification.py` → **`44 / 44` OK** (`0.638 s`).

### Verdict final

- **`L7_C2_B3_QUALIFIED`**
- **`L7_C2_CLOSED`**
