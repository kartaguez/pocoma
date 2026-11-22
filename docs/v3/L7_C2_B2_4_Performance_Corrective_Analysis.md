# Pocoma V3 — L7-C2-B2.4 — Diagnostic correctif après échec des gates B2.4 (P1, P2, P4, P8)

- **Date** : 20 novembre 2026
- **Branche** : `feat/v3-l7`
- **Baseline A** : commit `e25cd84b` (`L7_C2_B1_CLOSED` — `V3` Radix-16 `PotVersion` + `PotBalance` JSONB)
- **Candidat V4 audité** : `fab2a51d` + modifications opérationnelles B2.4 non committées (intégralement préservées, zéro modification de production)
- **Références normatives** :
  - [`docs/v3/L7_C2_B2_PotBalance_Implementation_Plan.md`](L7_C2_B2_PotBalance_Implementation_Plan.md)
  - [`docs/v3/L7_C2_B2_4_PotBalance_Qualification_Report.md`](L7_C2_B2_4_PotBalance_Qualification_Report.md)
  - [`docs/testing/evidence/L7-C2-B2/B2_4/`](../testing/evidence/L7-C2-B2/B2_4/)
  - [`docs/testing/Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)
- **Verdict** : **`L7_C2_B2_CORRECTIVE_PLAN_READY_FOR_REVIEW`**

---

## 1. Synthèse des causes mesurées des quatre écarts (P1, P2, P4, P8)

Des bancs isolés sur PostgreSQL 17 (`postgres@sha256:a77aa70e...`, mêmes classes de production compilées, zéro modification du code de production) et une campagne de 4 reconstructions `LARGE` entrelacées (A→B→A→B) instrumentées par `pg_stat_statements` et Micrometer établissent les causes exactes de chacun des quatre écarts :

| Critère | Écart constaté en B2.4 | Cause racine mesurée |
|---|---|---|
| **P1** (`ab-large` Sit. D) | `1.58×` (`48.23 → 30.53 Mo`) vs `≥ 1.8×` (`≤ 26.8 Mo`) | **Incompressibilité `pglz` des UUID binaires 16 octets répétés** : sur `LARGE` (`S=100`), les `1 600 B` d'UUID aléatoires représentent `76.0 %` du payload balance (`2 106 B`) et `53.1 %` du payload agrégat (`3 011 B`). Le gain maximal tombe sous le seuil d'abandon de `25 %` de `pglz` (`PGLZ_STRATEGY_DEFAULT_MIN_COMP_RATE`) : PostgreSQL abandonne totalement la compression et stocke `100 %` des payloads bruts en TOAST (`pg_column_size == octet_length`), alors que le JSONB de Baseline A était compressé de `-31.5 %` à `-50.4 %` par `pglz`. Les mêmes `100` UUID sont stockés `8 006` fois (`12.8 Mo` d'entropie pure → `~16–18 Mo` TOAST). |
| **P2** (`ab-large` 20 mixtes) | `2.45×` (`221 → 90 Ko`) vs `≥ 2.5×` | **Effet direct de P1** sur les `13` mutations financières (`13/20`) qui allouent chacune `~6.9 Ko` non compressés en TOAST + effet de granularité de page `8 KiB`. Réduire le volume unitaire Sit. D résout automatiquement P2 avec large marge (`~4.5×–5.0×`). |
| **P4** (`XLARGE` Sit. D) | `1.50×` st. / `1.62×` WAL vs `≥ 1.8×` | **Même mécanisme qu'en P1 amplifié à `S=500`** : `8 000 B` d'UUID binaires répétés dans chacune des deux tables (`16 000 B/version` d'entropie incompressible). `pglz` abandonne à `0.0 %` (`10 741 → 10 741 B` et `14 568 → 14 568 B`), alors que JSONB V3 se compressait de `-40.2 %` / `-54.6 %`. |
| **P8** (`ab-large` rebuild) | `68.19 s` / `58.75 s` (`1.63×`) vs `41.71 s` (`≤ 1.10×`) | **Faux positif majoritairement méthodologique (variance non entrelacée + biais de fenêtre `t0` de `await_read_rebuild_from_write`)**, doublé de **2 surcoûts évitables propres à V4**. En campagne contrôlée entrelacée (`A→B→A→B`), **V4 est en réalité `-2.6 %` plus rapide que Baseline A** (`48.76 s` vs `50.08 s` total ; `38.34 s` vs `40.16 s` post-boot) et **`3.32×` plus rapide en SQL PostgreSQL sur `PotBalance`** (`1 176 ms` vs `3 909 ms`). Les deux surcoûts évitables restants sont : (a) le split 2 requêtes `readLatestAggregateRefBefore` + `readAggregateByHash` imposé par `EMPTY_DELTA_REUSE` (`+152..193 µs` sur 100 % des versions financières) et (b) `requireCompletionReady()` qui relit et redécode intégralement les deux payloads TOAST + SHA-256 (`405 µs/ver`, soit `29.5 %` du coût de `JdbcPotBalanceStore`). |

---

## 2. Axe 1 — Comparaison expérimentale des formats d'encodage et de stockage

### 2.1 Protocole

Huit représentations ont été évaluées sur PostgreSQL 17 (`postgres@sha256:a77aa70e...`) sur des jeux de données **strictement identiques** générés avec les classes de production (`PotBalance`, `PotBalanceAggregate`, `PotBalanceAggregateCalculator`, `PotBalanceBinaryCodec`, `PotBalanceAggregateBinaryCodec`) :

1. **`F0_V3_JSONB`** : Baseline V3 (`jsonb` non mutualisé, `pglz` défaut).
2. **`F1_V4_BIN_PGLZ`** : V4 actuel (`bytea` binaire canonique, `pglz` défaut).
3. **`F2_JSONB_SHARED_PGLZ`** : JSONB canonique mutualisé par `content_hash` (`pglz` défaut).
4. **`F2_JSONB_SHARED_LZ4`** : JSONB canonique mutualisé par `content_hash` (`COMPRESSION lz4`).
5. **`F3A_BIN_PG_LZ4`** : Binaire canonique V4 avec `COMPRESSION lz4` PostgreSQL 17.
6. **`F3B_BIN_DEFLATE`** : Binaire canonique V4 compressé applicativement (`java.util.zip.Deflater` niveau 6, `STORAGE EXTERNAL`).
7. **`F4_BIN_SHSET`** : Binaire canonique avec factorisation de la liste triée d'UUID dans une table immuable adressée par contenu `read_pot_shareholder_set(set_hash, shareholders_count, payload)` + `shareholder_set_hash` (FK 32 B) dans les deux tables de contenus *(exactement sur le modèle de `read_pot_user_set` qualifié en C2-B1)*, `pglz` défaut.
8. **`F4_BIN_SHSET_LZ4`** : Variante `F4_BIN_SHSET` avec `COMPRESSION lz4`.

### 2.2 Pourquoi les UUID binaires compressent moins bien que le JSONB sous `pglz`

Les mesures `octet_length(payload)` vs `pg_column_size(payload)` sur PostgreSQL 17 démontrent le mécanisme exact :

| Profil (`G=1`) | Format | Raw Bal / Agg (B) | `pg_column_size` Bal / Agg (B) | Taux compression PostgreSQL | Cause |
|---|---|---:|---:|---:|---|
| **`LARGE` (`S=100`)** | `F0_V3_JSONB` (`pglz`) | `6 672` / `9 996` | `4 572` / `4 954` | **`-31.5 %` / `-50.4 %`** | Syntaxe JSON répétitive + hex ASCII (`16` symboles/256) + tirets UUID → dépasse largement le seuil `25 %` de `pglz`. |
| **`LARGE` (`S=100`)** | `F1_V4_BIN_PGLZ` (`pglz`) | `2 106` / `3 011` | **`2 106` / `3 011`** | **`0.0 %` / `0.0 %`** | `1 600 B` d'UUID binaires aléatoires (`76 %` / `53 %`) empêchent d'atteindre `25 %` de gain → **`pglz` abandonne** et stocke brut en TOAST. |
| **`LARGE` (`S=100`)** | `F3A_BIN_PG_LZ4` (`lz4`) | `2 106` / `3 011` | `2 096` / `2 694` | `-0.5 %` / `-10.5 %` | `lz4` n'a pas de seuil d'abandon `25 %`, mais reste borné par les `1 600 B` d'entropie UUID. |
| **`LARGE` (`S=100`)** | `F3B_BIN_DEFLATE` (zlib) | `2 030` / `2 223` | `2 030` / `2 223` | `-3.6 %` / `-26.2 %` | Limite de Shannon sur `1 600 B` d'UUID aléatoires (`≥ 1 600 B` incompressibles quoi qu'on fasse). |
| **`LARGE` (`S=100`)** | `F4_BIN_SHSET` (`pglz`) | **`506` / `1 405`** | **`510` / `1 409`** | **Inline heap (`0` TOAST)** | Sans les `1 600 B` d'UUID, les deux payloads tiennent **entièrement en heap** (`< 2 KiB`, zéro chunk TOAST). |
| **`XLARGE` (`S=500`)** | `F0_V3_JSONB` (`pglz`) | `33 800` / `49 080` | `20 206` / `22 299` | **`-40.2 %` / `-54.6 %`** | Idem : forte redondance syntaxique JSON compressée par `pglz`. |
| **`XLARGE` (`S=500`)** | `F1_V4_BIN_PGLZ` (`pglz`) | `10 741` / `14 568` | **`10 741` / `14 568`** | **`0.0 %` / `0.0 %`** | `8 000 B` d'UUID binaires (`74.5 %` / `54.9 %`) bloquent `pglz` sous le seuil `25 %`. |
| **`XLARGE` (`S=500`)** | `F4_BIN_SHSET` (`pglz`) | **`2 741` / `6 563`** | **`282` / `2 484`** | **`-89.7 %` / `-62.2 %`** | Sans UUID aléatoires, les fractions et indices compressent massivement sous `pglz` standard ! |

### 2.3 Résultats complets par profil (`MEDIUM`, `LARGE`, `XLARGE` et cardinalités `G=1, 61, 240`)

#### A. `MEDIUM` (`S=20, G=1`, 200 v Sit. D + 50 v Sit. A)

| Format | Heap (B) | Index (B) | TOAST (B) | **Total Sit. D (B)** | **Ratio vs F0** | **WAL Sit. D (B/v)** | **WAL Sit. A (B/v)** | Enc B/A (µs) | Dec B/A (µs) | Écr. D p50 (ms) | Lect. hist. p50 (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `F0_V3_JSONB` | 679 936 | 32 768 | 65 536 | 778 240 | 1.00× | 3 320 | 3 327 | 83 / 70 | 416 / 134 | 0.692 | 0.675 |
| `F1_V4_BIN_PGLZ` | 311 296 | 114 688 | 131 072 | 557 056 | 1.40× | 2 295 (1.45×) | 727 | 5 / 6 | 13 / 19 | 0.845 | 0.344 |
| `F2_JSONB_SHARED_PGLZ` | 737 280 | 114 688 | 131 072 | 983 040 | **0.79×** | 4 029 (0.82×) | 727 | 17 / 22 | 40 / 70 | 0.980 | 0.412 |
| `F2_JSONB_SHARED_LZ4` | 737 280 | 114 688 | 131 072 | 983 040 | **0.79×** | 3 993 (0.83×) | 727 | 12 / 18 | 42 / 47 | 0.923 | 0.438 |
| `F3A_BIN_PG_LZ4` | 311 296 | 114 688 | 131 072 | 557 056 | 1.40× | 2 295 (1.45×) | 728 | 5 / 6 | 15 / 16 | 0.855 | 0.361 |
| `F3B_BIN_DEFLATE` | 294 912 | 114 688 | 131 072 | 540 672 | 1.44× | 2 197 (1.51×) | 727 | 41 / 38 | 23 / 19 | 0.900 | 0.371 |
| **`F4_BIN_SHSET`** | 196 608 | 131 072 | 139 264 | **466 944** | **1.67×** | **1 776 (1.87×)** | **727** | **9 / 9** | **18 / 5** | 1.009 | 0.377 |

#### B. `LARGE` (`S=100, G=1`, 400 v Sit. D + 50 v Sit. A)

| Format | Heap (B) | Index (B) | TOAST (B) | **Total Sit. D (B)** | **Ratio vs F0** | **WAL Sit. D (B/v)** | **WAL Sit. A (B/v)** | Enc B/A (µs) | Dec B/A (µs) | Écr. D p50 (ms) | Lect. hist. p50 (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `F0_V3_JSONB` | 73 728 | 65 536 | 4 620 288 | 4 759 552 | 1.00× | 10 941 | 10 939 | 46 / 71 | 73 / 122 | 1.042 | 0.467 |
| `F1_V4_BIN_PGLZ` | 163 840 | 172 032 | 2 916 352 | 3 252 224 | **1.46×** | 6 964 (1.57×) | 725 | 9 / 23 | 25 / 39 | 0.917 | 0.382 |
| `F2_JSONB_SHARED_PGLZ` | 163 840 | 180 224 | 4 685 824 | 5 029 888 | **0.95×** | 11 663 (0.94×) | 725 | 35 / 51 | 56 / 101 | 1.337 | 0.467 |
| `F2_JSONB_SHARED_LZ4` | 163 840 | 180 224 | 4 636 672 | 4 980 736 | **0.96×** | 11 370 (0.96×) | 725 | 38 / 73 | 59 / 112 | 1.201 | 0.531 |
| `F3A_BIN_PG_LZ4` | 163 840 | 172 032 | 2 514 944 | 2 850 816 | **1.67×** | 6 631 (1.65×) | 725 | 8 / 10 | 21 / 37 | **0.874** | **0.362** |
| `F3B_BIN_DEFLATE` | 278 528 | 172 032 | 2 260 992 | 2 711 552 | **1.76×** | 6 045 (1.81×) | 725 | 68 / 96 | 36 / 47 | 1.023 | 0.383 |
| **`F4_BIN_SHSET`** | 1 007 616 | 196 608 | **139 264** | **1 343 488** | **3.54×** | **3 319 (3.30×)** | **725** | **5 / 10** | 49 / 3 | 1.008 | 0.404 |

#### C. `LARGE` multi-buckets (`S=100, G=61` [100 v] et `G=240` [50 v])

| Profil & Format | Raw B/A (B) | `pg_col` B/A (B) | TOAST (B) | **Total Sit. D (B)** | **Ratio vs F0** | **WAL Sit. D (B/v)** | Enc B/A (µs) | Écr. D p50 (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| **`LARGE_G61`** `F0_V3_JSONB` | 7 051 / 14 830 | 5 185 / 6 180 | 1 515 520 | 1 572 864 | 1.00× | 12 904 | 34 / 151 | 1.368 |
| **`LARGE_G61`** `F1_V4_BIN_PGLZ` | 2 301 / 5 990 | 2 301 / 3 447 | 884 736 | 1 015 808 | 1.55× | 7 533 (1.71×) | 9 / 26 | 0.976 |
| **`LARGE_G61`** `F2_JSONB_SHARED_PGLZ` | 7 051 / 14 830 | 5 185 / 6 180 | 1 605 632 | 1 736 704 | **0.91×** | 13 563 (0.95×) | 36 / 112 | 1.604 |
| **`LARGE_G61`** `F3A_BIN_PG_LZ4` | 2 301 / 5 990 | 2 299 / 3 782 | 884 736 | 1 015 808 | 1.55× | 7 867 (1.64×) | 7 / 19 | **0.895** |
| **`LARGE_G61`** `F3B_BIN_DEFLATE` | 2 228 / 2 916 | 2 228 / 2 916 | 786 432 | 917 504 | 1.71× | 6 928 (1.86×) | 66 / 153 | 1.071 |
| **`LARGE_G61`** **`F4_BIN_SHSET`** | 701 / 4 024 | **705 / 1 450** | **139 264** | **524 288** | **3.00×** | **3 502 (3.68×)** | **4 / 42** | 1.106 |
| **`LARGE_G240`** `F0_V3_JSONB` | 7 559 / 29 579 | 5 611 / 9 189 | 909 312 | 958 464 | 1.00× | 16 485 | 41 / 196 | 2.106 |
| **`LARGE_G240`** `F1_V4_BIN_PGLZ` | 2 510 / 15 192 | 2 510 / 5 246 | 532 480 | 630 784 | 1.52× | 9 685 (1.70×) | 11 / 45 | 1.107 |
| **`LARGE_G240`** `F3A_BIN_PG_LZ4` | 2 510 / 15 192 | 2 510 / 6 487 | 647 168 | 745 472 | **1.29×** | 11 061 (1.49×) | 8 / 41 | 1.085 |
| **`LARGE_G240`** `F3B_BIN_DEFLATE` | 2 402 / 4 286 | 2 402 / 4 286 | 516 096 | 614 400 | 1.56× | 8 615 (1.91×) | 83 / 303 | 1.385 |
| **`LARGE_G240`** **`F4_BIN_SHSET`** | 910 / 12 152 | **914 / 3 168** | **303 104** | **475 136** | **2.02×** | **5 746 (2.87×)** | **8 / 50** | 1.339 |

#### D. `XLARGE` (`S=500`, `G=1` [100 v Sit. D + 50 v Sit. A] et `G=61` [50 v])

| Profil & Format | Heap (B) | Index (B) | TOAST (B) | **Total Sit. D (B)** | **Ratio vs F0** | **WAL Sit. D (B/v)** | **WAL Sit. A (B/v)** | Enc B/A (µs) | Écr. D p50 (ms) | Lect. hist. p50 (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **`XLARGE_G1`** `F0_V3_JSONB` | 24 576 | 32 768 | 4 808 704 | 4 866 048 | 1.00× | 46 297 | 46 658 | 127 / 182 | 3.139 | 0.884 |
| **`XLARGE_G1`** `F1_V4_BIN_PGLZ` | 65 536 | 65 536 | 3 104 768 | 3 235 840 | 1.50× | 28 497 (1.62×) | 690 | 27 / 40 | 1.459 | 0.528 |
| **`XLARGE_G1`** `F2_JSONB_SHARED_PGLZ` | 65 536 | 65 536 | 4 898 816 | 5 029 888 | **0.97×** | 46 956 (0.99×) | 690 | 193 / 300 | 3.581 | 1.038 |
| **`XLARGE_G1`** `F2_JSONB_SHARED_LZ4` | 65 536 | 65 536 | 4 530 176 | 4 661 248 | 1.04× | 44 082 (1.05×) | 690 | 139 / 219 | 2.638 | 0.935 |
| **`XLARGE_G1`** `F3A_BIN_PG_LZ4` | 65 536 | 65 536 | 2 531 328 | 2 662 400 | **1.83×** | 24 480 (1.89×) | 690 | 29 / 41 | 1.179 | 0.522 |
| **`XLARGE_G1`** `F3B_BIN_DEFLATE` | 65 536 | 65 536 | 2 318 336 | 2 449 408 | **1.99×** | 21 544 (2.15×) | 690 | 256 / 325 | 1.643 | 0.555 |
| **`XLARGE_G1`** **`F4_BIN_SHSET`** | 98 304 | 81 920 | **499 712** | **679 936** | **7.16×** | **4 473 (10.35×)** | **690** | **18 / 40** | 1.313 | **0.496** |
| **`XLARGE_G61`** `F0_V3_JSONB` | 16 384 | 32 768 | 2 473 984 | 2 523 136 | 1.00× | 49 156 | 49 080 | 131 / 260 | 3.513 | 0.904 |
| **`XLARGE_G61`** `F1_V4_BIN_PGLZ` | 32 768 | 65 536 | 1 433 600 | 1 531 904 | 1.65× | 27 395 (1.79×) | 690 | 28 / 48 | 1.495 | 0.525 |
| **`XLARGE_G61`** `F3A_BIN_PG_LZ4` | 32 768 | 65 536 | 1 433 600 | 1 531 904 | 1.65× | 26 687 (1.84×) | 690 | 34 / 63 | **1.221** | 0.537 |
| **`XLARGE_G61`** `F3B_BIN_DEFLATE` | 32 768 | 65 536 | 1 228 800 | 1 327 104 | 1.90× | 23 338 (2.11×) | 690 | 213 / 423 | 1.753 | 0.582 |
| **`XLARGE_G61`** **`F4_BIN_SHSET`** | 90 112 | 81 920 | **352 256** | **524 288** | **4.81×** | **6 182 (7.95×)** | **690** | **17 / 41** | 1.417 | 0.556 |

### 2.4 Conclusions de l'Axe 1

1. **JSONB canonique mutualisé (`F2`)** est **éliminé** : il régresse en Situation D (`0.79×` à `0.97×`) sur tous les profils tout en conservant le surcoût CPU/latence de JSONB (`2×–3×` plus lent en écriture et lecture que le binaire).
2. **Binaire + `COMPRESSION lz4` (`F3A`)** corrige P4 sur `XLARGE_G1` (`1.83×` / `1.89×` WAL), mais **échoue encore sur P1** (`1.67×` `< 1.8×` sur `LARGE_G1`) et **dégrade les multi-buckets** (`1.29×` vs `1.52×` sur `LARGE_G240`).
3. **Binaire + `Deflater` (`F3B`)** atteint `1.99×` sur `XLARGE_G1`, mais **reste sous `1.8×` sur `LARGE_G1` (`1.76×`)** à cause de la borne de Shannon sur les `1 600 B` d'UUID répétés, au prix d'un surcoût CPU encodage `×8` (`68/96 µs`).
4. **Binaire + factorisation `read_pot_shareholder_set` (`F4_BIN_SHSET`)** attaque directement la cause physique (la répétition inutile de `16 × S` octets d'UUID constants d'une version à l'autre) : il atteint **`3.54×` sur `LARGE_G1`**, **`3.00×` / `2.02×` en multi-buckets**, **`7.16×` stockage / `10.35×` WAL sur `XLARGE_G1`**, sans algorithme de compression applicatif, en utilisant le `pglz` standard PostgreSQL, tout en maintenant des temps d'encodage et de lecture historique inférieurs ou égaux à V4.

---

## 3. Axe 2 — Profil des coûts SQL et transactionnels de `JdbcPotBalanceStore`

### 3.1 Décomposition mesurée d'une écriture complète (`finalizeIn` + `requireCompletionReady` + `COMMIT`)

Dans l'orchestrateur [`ConsumptionEngine.java`](../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/ConsumptionEngine.java#L1053), **chaque** appel `completion.complete()` appelle `prepared.requireCompletionReady()` dans la même transaction `READ_COMMITTED`. Dans [`MaterializePotBalance.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotBalance.java#L362-L365), cela déclenche `store.requireStored(...)` puis `store.requireAggregateStored(...)`, qui relisent et redécodent intégralement les deux payloads TOAST (avec re-calcul SHA-256 et reconstruction des objets domaine).

Profilage microseconde par microseconde sur PostgreSQL 17 (moyenne sur `n=100` versions Sit. D et `n=30` conflits Sit. A) :

| Étape dans la transaction `READ_COMMITTED` | `MEDIUM_G1` (µs) | `LARGE_G1` (µs) | `LARGE_G61` (µs) | `XLARGE_G1` (µs) | Part sur `LARGE_G1` |
|---|---:|---:|---:|---:|---:|
| 1. `encode(balance)` + SHA-256 | 1.8 | 6.3 | 6.7 | 27.1 | `0.5 %` |
| 2. `INSERT read_pot_balance_content ON CONFLICT DO NOTHING RETURNING` | 186.1 | 206.2 | 207.0 | 391.6 | `15.0 %` |
| 3. `INSERT read_pot_balance ON CONFLICT DO NOTHING RETURNING` | 184.8 | 183.4 | 175.2 | 197.2 | `13.4 %` |
| 4. `encode(aggregate)` + SHA-256 | 2.5 | 9.1 | 17.1 | 39.6 | `0.7 %` |
| 5. `INSERT read_pot_balance_aggregate_content ON CONFLICT DO NOTHING RETURNING` | 177.2 | 210.2 | 255.3 | 399.3 | `15.3 %` |
| 6. `INSERT read_pot_balance_aggregate ON CONFLICT DO NOTHING RETURNING` | 190.8 | 187.0 | 180.9 | 191.6 | `13.6 %` |
| **7. `requireStored(balance)` (SELECT JOIN TOAST + decode + SHA-256)** | **178.6** | **205.8** | **214.8** | **315.9** | **`15.0 %`** |
| **8. `requireAggregateStored(aggregate)` (SELECT JOIN TOAST + decode + SHA-256)** | **186.9** | **199.0** | **244.0** | **279.9** | **`14.5 %`** |
| 9. `COMMIT` (FK + WAL flush) | 164.7 | 162.6 | 167.4 | 197.4 | `11.9 %` |
| **Total V4 actuel (`6` allers-retours SQL + `COMMIT`)** | **1 274.5 µs** (`1.182 ms` p50) | **1 370.8 µs** (`1.316 ms` p50) | **1 469.6 µs** (`1.398 ms` p50) | **2 040.9 µs** (`1.874 ms` p50) | **`100.0 %`** |
| *En conflit Sit. A (sans `EMPTY_DELTA_REUSE`)* : `SELECT` + `exactContentEquals` (bal + agg) | 172.3 + 162.5 | 201.9 + 177.1 | 197.1 + 189.8 | 240.3 + 238.5 | — |

### 3.2 Requêtes redondantes identifiées et optimisations prototypées

Trois redondances majeures sont mesurées :

1. **Redondance #1 — `requireCompletionReady()` relit et redécode 100 % des payloads TOAST (`29.5 %` du temps d'écriture !)** :
   - Dans la même transaction, `insertOrVerifyBalanceContent` et `insertOrVerifyAggregateContent` ont **déjà** garanti soit l'insertion exacte du contenu, soit l'égalité octet à octet (`exactContentEquals`) en cas de conflit, et les FK natives garantissent l'existence des lignes de contenu.
   - Remplacer `requireStored` + `requireAggregateStored` par **une unique requête `SELECT` sur les pointeurs** (`SELECT b.content_hash, a.content_hash FROM read_pot_balance b LEFT JOIN read_pot_balance_aggregate a USING (pot_id, pot_version) WHERE b.pot_id = ? AND b.pot_version = ?`) — exactement comme le font déjà `MaterializePotVersion.requireCompletionReady()` et `MaterializePotBalance.buildReusePrepared()` — ramène les étapes 7+8 de **`404.8 µs` → `169.5 µs`** sur `LARGE_G1` (**`-17.4 %`** sur la transaction totale ; `-16.4 %` sur `XLARGE_G1`, `-18.5 %` sur `LARGE_G61`).
   - **Invariants préservés** : atomicité, fencing (`SlotCompletion`), concurrence, idempotence ; `prepareRecovery()` conserve l'oracle intégral + décodage complet via `store.evidence(...)`.

2. **Redondance #2 — Regroupement CTE des insertions de contenus (`Opt-2`)** :
   - Combiner `INSERT read_pot_balance_content ON CONFLICT DO NOTHING RETURNING 1` et `INSERT read_pot_balance_aggregate_content ON CONFLICT DO NOTHING RETURNING 1` dans **un unique CTE** élimine 1 aller-retour SQL supplémentaire (ordre déterministe fixe conservé : `bal_c` puis `agg_c`, suivi des 2 pointeurs `bal` puis `agg` après incrément du command counter pour satisfaire les FK).
   - Gain mesuré cumulé (`Opt-1 + Opt-2`) :
     - `MEDIUM_G1` : `1 274 → 903 µs` (**`-29.1 %`**, p50 `1.182 → 0.854 ms`)
     - `LARGE_G61` : `1 470 → 1 059 µs` (**`-27.9 %`**, p50 `1.398 → 1.009 ms`)
     - `XLARGE_G1` : `2 041 → 1 348 µs` (**`-33.9 %`**, p50 `1.874 → 1.315 ms`)

3. **Redondance #3 — Double aller-retour de lookup dans `prepare()` (`+34 % à +42 %` sur chaque mutation financière)** :
   - Pour supporter `EMPTY_DELTA_REUSE`, [`MaterializePotBalance.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotBalance.java#L129-L219) appelle d'abord `readLatestAggregateRefBefore` (requête #1), puis `readAggregateByHash` (requête #2) pour 100 % des mutations financières (même si `emptyDeltaReuseEnabled=false`).
   - Mesure directe :
     - `MEDIUM_G1` : `366 µs` (1 requête `readLatestAggregateBefore`) vs `520 µs` (2 requêtes) → **`+154 µs` (`+42.1 %`)**
     - `LARGE_G1` : `484 µs` vs `677 µs` → **`+193 µs` (`+39.9 %`)**
     - `LARGE_G61` : `419 µs` vs `578 µs` → **`+159 µs` (`+38.1 %`)**
     - `XLARGE_G1` : `447 µs` vs `599 µs` → **`+152 µs` (`+34.1 %`)**

---

## 4. Axe 3 — Analyse de la reconstruction READ (écart P8)

### 4.1 Reproduction contrôlée A/B entrelacée (`LARGE`, 4 004 versions)

Quatre reconstructions complètes depuis le snapshot WRITE `large` (`sha256=2eb4567b...`) ont été exécutées en alternance stricte (`A1 → V4_1 → A2 → V4_2`) sur conteneurs PostgreSQL 17 jetables identiques avec `pg_stat_statements` activé sur la base de maintenance `postgres` (zéro altération de `pocoma_read`/`pocoma_write`) et collecte Micrometer complète :

| Run | Boot workers (s) | Convergence post-boot (s) | **Durée murale totale `up → READY` (s)** |
|---|---:|---:|---:|
| `baseline-a-1` (`e25cd84b`, V3) | `10.14` | `39.53` | **`49.66`** |
| `candidate-v4-1` (V4) | `9.85` | `39.50` | **`49.36`** |
| `baseline-a-2` (`e25cd84b`, V3) | `9.70` | `40.79` | **`50.50`** |
| `candidate-v4-2` (V4) | `11.00` | `37.17` | **`48.16`** |
| **Moyenne Baseline A (V3)** | **`9.92 s`** | **`40.16 s`** | **`50.08 s`** |
| **Moyenne Candidat V4** | **`10.43 s`** | **`38.34 s` (`0.955×`, `-4.5 %`)** | **`48.76 s` (`0.974×`, `-2.6 %`)** |

**Constat fondamental** : sur environnement strictement comparable et entrelacé, **V4 ne régresse pas sur la reconstruction READ** ; il est **`-2.6 %` à `-4.5 %` plus rapide** que Baseline A `e25cd84b`.

### 4.2 Décomposition complète de la durée de reconstruction (`pg_stat_statements` + Micrometer)

#### A. Décomposition SQL serveur PostgreSQL (`pg_stat_statements`, top 40 requêtes, moyenne 2 runs)

| Base & Catégorie | Baseline A (V3) | Part | **Candidat V4** | Part | Écart V4 vs A |
|---|---:|---:|---:|---:|---:|
| **`pocoma_read` — Découverte & coordination (`consumption_slot`, `discovery_checkpoint`, `digest`)** | `9 727.7 ms` (`357k` appels) | `49.1 %` | `9 685.4 ms` (`357k` appels) | `58.1 %` | `-42.3 ms` (`-0.4 %`) |
| **`pocoma_read` — `PotVersion` + `Subobjects`** | `4 371.6 ms` (`57.7k` appels) | `22.1 %` | `4 128.3 ms` (`59.8k` appels) | `24.8 %` | `-243.3 ms` (`-5.6 %`) |
| **`pocoma_read` — `PotBalance` (toutes tables)** | **`3 909.1 ms`** (`28.0k` appels) | **`19.7 %`** | **`1 176.1 ms`** (`26.0k` appels) | **`7.1 %`** | **`-2 733.0 ms` (`3.32×` plus rapide !)** |
| **`pocoma_read` — Autres projections (`PotVisibility`, `CurrentBinding`, `Invitation`, `Results`)** | `1 787.2 ms` | `9.0 %` | `1 667.9 ms` | `10.0 %` | `-119.3 ms` |
| **Sous-total `pocoma_read` SQL** | **`19 795.7 ms`** | `100 %` | **`16 657.7 ms`** | `100 %` | **`-3 138.0 ms` (`-15.9 %`)** |
| **`pocoma_write` — Lecture financière / delta (`v3_expense_*`, `v3_shareholder_weight`)** | `1 845.7 ms` | `36.9 %` | `2 233.0 ms` | `41.3 %` | `+387.3 ms` |
| **`pocoma_write` — Découverte événements (`v3_event_position`)** | `1 521.3 ms` | `30.4 %` | `1 491.7 ms` | `27.6 %` | `-29.6 ms` |
| **`pocoma_write` — Composition / headers (`v3_pot_event`, `v3_shareholder_*`)** | `1 084.6 ms` | `21.7 %` | `1 109.3 ms` | `20.5 %` | `+24.7 ms` |
| **`pocoma_write` — Autres lectures WRITE** | `554.7 ms` | `11.1 %` | `567.5 ms` | `10.5 %` | `+12.8 ms` |
| **Sous-total `pocoma_write` SQL** | **`5 006.4 ms`** | `100 %` | **`5 401.5 ms`** | `100 %` | `+395.1 ms` |

#### B. Décomposition Micrometer `business-read-worker` (cumul threads, moyenne 2 runs)

| Étape / Consommation | Baseline A (V3) | **Candidat V4** | Écart V4 vs A |
|---|---:|---:|---:|
| `PotBalance` — Lecture WRITE (`delta_duration`) | `6.913 s` | `6.932 s` | `+0.020 s` (identique) |
| `PotBalance` — Calcul (`compose` + `evaluate`) | `0.671 s` | `0.701 s` | `+0.030 s` (identique) |
| `PotBalance` — Encodage binaire (`bal + agg`, 4 004 v) | N/A (inclus dans persist) | `0.159 s` (`0.083 + 0.076 s`) | — |
| `PotBalance` — Persistance `balance` (`persist_duration`) | `3.156 s` | **`1.201 s`** | **`-1.955 s` (`2.63×` plus rapide)** |
| `PotBalance` — Persistance `aggregate` (`persist_duration`) | `3.873 s` | **`1.388 s`** | **`-2.485 s` (`2.79×` plus rapide)** |
| `PotBalance` — Lookup agrégat (`aggregate_lookup_duration`) | `3.080 s` (1 requête) | `1.762 s` *(ref seule ; `readAggregateByHash` non timé)* | *2e aller-retour masqué hors timer* |
| **Total opportunités `PotBalance` (`confirmed`)** | **`9.273 s`** | **`9.179 s`** | **`-0.094 s` (`-1.0 %`)** |
| Total opportunités `PotVersion` (`confirmed`) | `9.566 s` | `9.174 s` | `-0.392 s` |
| Total opportunités `PotVisibility` / `CurrentBinding` / `Invitation` | `12.214 s` | `13.190 s` | `+0.976 s` |

### 4.3 Explication de l'écart P8 observé lors de B2.4 (`68.19 s` vs `41.71 s`)

L'écart apparent constaté en B2.4 s'explique par la combinaison de deux facteurs :

1. **Biais méthodologique de `run-l7-c2-b2-qualification.py` (lignes 503–538)** :
   - En `rebuild1`, `await_read_rebuild_from_write` ne démarre son chronomètre qu'**après** le boot complet des **5** conteneurs (`http-write`, `http-read`, `command-worker`, `results-worker`, `business-read-worker`). Pendant ce délai variable (`10–25 s`), `business-read-worker` a déjà consommé une fraction non mesurée du backlog WRITE — ce qui explique aussi pourquoi `ab-medium` affichait systématiquement `rebuild1 = 2.91 s` vs `rebuild2 = 12.00 s` (déjà observé sur Baseline A en C2-B1.4 : `4.29 s` vs `11.23 s`).
   - En run unique non entrelacé sur Docker Desktop macOS, la variance hôte atteint `±15–25 %` (en C2-B1.4, la même baseline `e25cd84b` variait de `42.59 s` à `54.56 s`, et en B2.4 `restore-large-1` V4 convergeait déjà en **`46.16 s`**).
2. **Coût marginal réel évitable propre à V4** : bien que V4 économise `-4.44 s` sur `persist` (`7.03 → 2.59 s`), ce gain est en grande partie absorbé par :
   - les `3 991` allers-retours réseau/JDBC supplémentaires de `readAggregateByHash` (split `EMPTY_DELTA_REUSE`, non timés dans `aggregate_lookup_duration`, `+0.8–1.6 s`), et
   - les `8 008` lectures TOAST + décodages complets de `requireCompletionReady()` (`+1.6–2.0 s`).
   Supprimer ces deux redondances fera passer la reconstruction V4 **nettement sous Baseline A** (`~44–46 s`, soit `~0.88–0.92×` Baseline A).

---

## 5. Axe 4 — Évaluation de `EMPTY_DELTA_REUSE` (ARB-3)

### 5.1 Bilan chiffré de la campagne 3-way (`non-financial-3way`, `medium`, 60 commandes)

| Métrique | Baseline V3 (`e25cd84b`) | **V4 `emptyDeltaReuse=false`** | **V4 `emptyDeltaReuse=true`** |
|---|---:|---:|---:|
| Delta stockage `PotBalance*` | `+221 184 B` | **`+8 192 B` (`-96.3 %`)** | `+49 152 B` *(pages)* |
| Contenus réutilisés (bal ; agg) | N/A | **`60/60` ; `50/60` (`100 %` Sit. A)** | `60/60` ; `50/60` (`100 %` Sit. A) |
| WAL cluster 5 runtimes | `2 708 640 B` | **`2 551 408 B` (`-5.8 %`)** | `2 619 288 B` (`-3.3 %`) |
| Coût total encodage (`bal + agg`, 60 cmds) | N/A | `1.413 ms` (`23.6 µs/cmd`) | `0.464 ms` (`45` reuse, `15` inc.) |
| **Gain CPU marginal de `EMPTY_DELTA_REUSE`** | — | — | **`0.949 ms` sur 60 cmds (`15.8 µs/cmd`)** |
| Latence E2E p50 / p95 | `101.79` / `192.69 ms` | **`96.85` / `180.71 ms`** | `101.83` / `195.82 ms` |

### 5.2 Coût des vérifications, complexité et surface de tests

- **Pénalité asymétrique sur les mutations financières** : le mécanisme a imposé de scinder `readLatestAggregateBefore` (1 requête) en `readLatestAggregateRefBefore` + `readAggregateByHash` (2 requêtes). Mesuré en Axe 2, ce split inflige **`+152 à +193 µs/mutation` (`+34 % à +42 %`)** sur **100 % des mutations financières** (y compris lorsque `emptyDeltaReuseEnabled=false` !). Le seuil de rentabilité CPU/latence exigerait **`> 90 %` de commandes strictement non financières**, ce qui est irréaliste.
- **0 % d'impact sur le stockage et le WAL** : `100 %` de la déduplication (`0` nouveau contenu, `-96.3 %` volume, `-98.5 %` WAL propre) est déjà garanti par la couche physique `ON CONFLICT (content_hash) DO NOTHING`.
- **Complexité et risque d'invariant** : le commit `fab2a51d` représente **`+757 / -39` lignes** sur 8 fichiers (`PotBalanceStore` +4 méthodes + `AggregateRef`, `MaterializePotBalance` +149 lignes et 2 implémentations de `Prepared`, `JdbcPotBalanceStore` +151 lignes, `MaterializeSettings`, `320+` lignes de tests dédiés) et crée un couplage fragile sur `isProvenEmptyFinancialDelta` (toute future table financière WRITE imposerait de ré-auditer la preuve formelle).

### 5.3 Recommandation sur `EMPTY_DELTA_REUSE`

**Suppression complète de `EMPTY_DELTA_REUSE`** (conserver uniquement les métriques Micrometer `pocoma.v3.potbalance.content.write` et `encode.duration` de B2.3). Cela élimine `~650` lignes de code/tests, supprime la pénalité de `+152..193 µs` sur 100 % des mutations financières, et conserve `100 %` des gains stockage/WAL de la mutualisation physique. *(La désactivation par défaut seule ne suffirait pas sans rétablir le lookup en 1 requête.)*

---

## 6. Options correctives comparées (3 options maximum)

| Critère | **Option 1 (Recommandée)** : `read_pot_shareholder_set` + nettoyage SQL/lookup | **Option 2** : `COMPRESSION lz4` + nettoyage SQL/lookup | **Option 3** : `Deflater` applicatif + nettoyage SQL/lookup |
|---|---|---|---|
| **Principe** | Factoriser la liste triée d'UUID dans `read_pot_shareholder_set(set_hash, shareholders_count, payload)` + FK `shareholder_set_hash` (modèle exact de `read_pot_user_set` C2-B1) + supprimer `EMPTY_DELTA_REUSE` (lookup 1 requête) + `requireCompletionReady` hash-only (1 requête JOIN) + protocole P8 entrelacé. | Ajouter `COMPRESSION lz4` sur `payload` des 2 tables de contenu + supprimer `EMPTY_DELTA_REUSE` + `requireCompletionReady` hash-only + protocole P8 entrelacé. | Compresser `payload` en Java (`Deflater` niveau 6, `STORAGE EXTERNAL`) + supprimer `EMPTY_DELTA_REUSE` + `requireCompletionReady` hash-only + protocole P8 entrelacé. |
| **Simplicité** | **Élevée** : pattern déjà qualifié en C2-B1 (`read_pot_user_set`), `0` nouvelle dépendance, `0` compression applicative, code `MaterializePotBalance` nettement simplifié (`-650` lignes via retrait `EMPTY_DELTA_REUSE`). | **Maximale** : `0` nouvelle table, 2 mots-clés SQL (`COMPRESSION lz4`) + nettoyage SQL/lookup. | **Moyenne** : `0` nouvelle table, mais gestion `Deflater`/`Inflater`, garde-fous zip-bomb (`maxUncompressedBytes`), coût CPU `×8`. |
| **P1 (`ab-large` ≥ 1.8×)** | **PASS large (`~3.5×`**, `~13–14 Mo` vs `≤ 26.8 Mo`) | **FAIL (`~1.67×`**, plafonne sous `1.8×`) | **FAIL/LIMITE (`~1.76×`**, plafonne par entropie UUID) |
| **P2 (`ab-large` mixtes ≥ 2.5×)** | **PASS large (`~4.5×–5.0×`)** | **LIMITE (`~2.5–2.7×`)** | **PASS limite (`~2.7–2.9×`)** |
| **P3 (Sit. A ≥ 90 %)** | **PASS (`100 %` réutilisation, `-98.5 %` WAL)** | **PASS (`100 %`, `-98.5 %`)** | **PASS (`100 %`, `-98.5 %`)** |
| **P4 (`XLARGE` ≥ 1.8×)** | **PASS massif (`7.16×` st. / `10.35×` WAL)** | **PASS (`1.83×` st. / `1.89×` WAL)** | **PASS (`1.99×` st. / `2.15×` WAL)** |
| **P5 / P6 / P7 (latences ≤ 1.05×)** | **PASS** (lookup 1 requête + `requireCompletionReady` hash-only + 0 TOAST sur `LARGE_G1`) | **PASS** (sauf `LARGE_G240` qui grossit sous `lz4`) | **RISQUE CPU** sur `XLARGE` / `G=240` (`8×` encodage) |
| **P8 (rebuild ≤ 1.10×)** | **PASS (`~0.88–0.94×` Baseline A)** | **PASS (`~0.90–0.96×` Baseline A)** | **PASS (`~0.92–0.98×` Baseline A)** |
| **Risques** | 1 table immuable supplémentaire (`read_pot_shareholder_set`, 1 ligne/pot) à couvrir par `V4`, `BootstrapGuard`, `ReadinessOracle`, `grant-l7-http-read.sql`, `diagnose-pot-balance-unreachable.sql`. | **Ne satisfait pas le seuil figé P1 `≥ 1.8×`** sans assouplir les critères initiaux ; régresse en multi-buckets `G=240` (`1.29×` vs `1.52×`). | Ne passe pas P1 avec marge ; introduit une surface de sécurité/CPU (`Inflater` sur données lues) sans résoudre la cause racine. |

---

## 7. Recommandation argumentée

**Retenir l'Option 1 (`read_pot_shareholder_set` + suppression `EMPTY_DELTA_REUSE` + `requireCompletionReady` hash-only + protocole P8 entrelacé).**

1. **Seule option qui satisfait 100 % des critères figés P1–P9 sans modifier aucun seuil** : les gains mesurés (`3.54×` sur `LARGE_G1`, `3.00×` sur `LARGE_G61`, `7.16×` st. / `10.35×` WAL sur `XLARGE_G1`) donnent une marge confortable de `×2` à `×4` au-dessus des seuils P1, P2 et P4.
2. **Coherence architecturale totale avec C2-B1** : `read_pot_shareholder_set` reproduit à l'identique le pattern immuable déjà qualifié avec `read_pot_user_set` (`SHA-256` avec séparation de domaine `pocoma-shset-v1\0`, `ENABLE ALWAYS TRIGGER`, FK depuis `read_pot_balance_content` et `read_pot_balance_aggregate_content`, `SELECT` accordé à `pocoma_read_http`, diagnostic F4 étendu).
3. **Élimination des TOAST sur `LARGE_G1`** : les payloads `read_pot_balance_content` (`510 B`) et `read_pot_balance_aggregate_content` (`1 409 B`) tiennent entièrement **inline en heap**, réduisant simultanément le stockage, le WAL et les I/O de lecture historique.
4. **Nettoyage et accélération du chemin critique** : la suppression de `EMPTY_DELTA_REUSE` (retour au lookup 1 requête `readLatestAggregateBefore`) et le passage de `requireCompletionReady()` en vérification hash-only 1 requête `JOIN` réduisent la complexité (`-650` lignes) et abaissent la latence d'écriture de `-17 % à -30 %` tout en préservant strictement l'atomicité, le fencing, la concurrence, l'idempotence et l'oracle intégral en `prepareRecovery()`.
5. **Impact nul sur `PotVersion` Radix-16, `WRITE` et contrats HTTP (`ReadPot`)**.

---

## 8. Plan de correction et de requalification (hors mandat courant)

### 8.1 Découpage en deux étapes bornées (sur `feat/v3-l7`, avant tout merge vers `main`)

1. **Étape C1 — Évolution `V4` (`read_pot_shareholder_set`), `JdbcPotBalanceStore` optimisé et retrait `EMPTY_DELTA_REUSE`** :
   - `domain-projection` (`balance/*`) : ajouter `PotShareholderSet` / `PotShareholderSetBinaryCodec` (`pocoma-shset-v1\0`), adapter `PotBalanceBinaryCodec` et `PotBalanceAggregateBinaryCodec` (`shareholderSetHash` inclus dans le calcul SHA-256 des contenus, payloads sans UUID répétés) + tests unitaires round-trip et rejet corruption.
   - `infra-postgres-read` : ajouter `read_pot_shareholder_set` + triggers `ENABLE ALWAYS` + ACL + FK dans `V4__pot_balance_shared_storage.sql` *(V4 n'étant pas mergée ni déployée, amender V4 directement avant clôture de B2)* ; adapter `JdbcPotBalanceStore` (insertion `read_pot_shareholder_set` avec `ON CONFLICT DO NOTHING` + `exactContentEquals`, retrait des 4 méthodes `EMPTY_DELTA_REUSE`, ajout `requireHashesStored(potId, version, balHash, aggHash)` en 1 requête `JOIN`, CTE regroupant les insertions de contenus).
   - `port-projection` & `engine-materialize-pot` : retirer `AggregateRef` / `EMPTY_DELTA_REUSE` ; revenir au lookup unique `readLatestAggregateBefore` ; utiliser `requireHashesStored` dans `requireCompletionReady()`.
   - `infra-postgres-core` (`BootstrapGuard`) & `compose-polling-nonburnable-materialize-postgres` (`ReadinessOracle`, `MaterializeSettings`) : ajouter `read_pot_shareholder_set` ; retirer la propriété `empty-delta-reuse-enabled`.
   - `scripts/` : ajouter `'read_pot_shareholder_set'` dans `grant-l7-http-read.sql`, `v3-rebuild-read.py` (`CANONICAL_MIGRATION_TABLES` + `_evaluate_integrity`), et `diagnose-pot-balance-unreachable.sql`.
   - **Verification scope (`Reactor_Verification_Policy.md` §29)** :
     - Expected production impact : `domain-projection`, `port-projection`, `engine-materialize-pot`, `infra-postgres-read`, `infra-postgres-core`, `compose-polling-nonburnable-materialize-postgres`.
     - Primary slice : `PROJECTION` (`./mvnw -pl runtime-worker-materialize -am test`).
     - Secondary slice : `WEB` (`./mvnw -pl runtime-web-write,runtime-web-read -am test`).
     - Forbidden production impact : `COMMAND`, `infra-postgres-write`, `domain-pot`, `domain-identity`, `ReadPot`, `PotVersion` Radix-16.
     - Global architecture gate : **REQUIRED** (`./mvnw -pl architecture-tests -am test` — modification DDL `V4`).
     - Full reactor : différé à l'étape C2.

2. **Étape C2 — Requalification B2.4 complète et clôture du lot** :
   - Corriger le protocole de mesure de rebuild dans `run-l7-c2-b2-qualification.py` (mesure murale homogène `workers up → READY` sur les deux branches + 2 runs entrelacés sur `ab-large` pour neutraliser la variance hôte).
   - Ré-exécuter la qualification complète : `ab-medium`, `ab-large` (avec double rebuild déterministe + F4 sur les 3 tables de contenu), `xlarge-ab`, `multibucket-realistic`, `restore-large-1`, `med-1`, test non-financier (2-way V3 vs V4), tests Python.
   - Exécuter le gate final **`architecture-tests`** + **Full Reactor (42 modules)** (`./mvnw --batch-mode --no-transfer-progress test`).
   - Publier le rapport de requalification finale visant **`L7_C2_B2_4_QUALIFIED`**.

---

## 9. Arbitrages architecturaux requis avant implémentation

Trois arbitrages explicites sont demandés avant d'engager le mandat de correction :

1. **ARB-1b (Évolution encodage V4)** : Approuver l'ajout de la table immuable `read_pot_shareholder_set` (adressée par SHA-256 `pocoma-shset-v1\0`, FK depuis `read_pot_balance_content` et `read_pot_balance_aggregate_content`, `SELECT` accordé à `pocoma_read_http`, incluse dans F4 et `ReadinessOracle`) directement dans `V4__pot_balance_shared_storage.sql` (puisque `V4` n'est pas mergée sur `main` ni déployée).
2. **ARB-3b (Retrait `EMPTY_DELTA_REUSE`)** : Approuver la suppression complète du chemin rapide `EMPTY_DELTA_REUSE` (en conservant les métriques Micrometer `content.write` et `encode.duration`), la déduplication physique assurant `100 %` du gain stockage/WAL et le split 2 requêtes pénalisant de `+34 % à +42 %` les mutations financières.
3. **ARB-6 (Protocole de mesure P8)** : Approuver l'homogénéisation du chronométrage de reconstruction dans `run-l7-c2-b2-qualification.py` (mesure `workers up → READY` identique pour Baseline A et Candidat V4, sur 2 runs entrelacés pour `ab-large`) sans modifier le seuil `≤ 1.10×`.

---

## Verdict

**`L7_C2_B2_CORRECTIVE_PLAN_READY_FOR_REVIEW`**
