# Rapport de consolidation L7.4 — Qualification conjointe A3 (`PotVersion`) + A4 (`PotBalance`)

- **Date** : 19 novembre 2026
- **Branche qualifiée** : `feat/l7-4-a3-potversion-incremental-nm`
- **Base `main`** : `1c5140fe` (`tag: v3-code-health-baseline-2026-11-18`)
- **Documents de référence** :
  - [`PotVersion_Incremental_Materialization_Design_Plan.md`](PotVersion_Incremental_Materialization_Design_Plan.md)
  - [`L7_4_A3_PotVersion_Incremental_N_Minus_M_Report.md`](L7_4_A3_PotVersion_Incremental_N_Minus_M_Report.md)
  - [`PotBalance_Incremental_Materialization_Design_Plan.md`](PotBalance_Incremental_Materialization_Design_Plan.md)
  - [`L7_4_A4_1_PotBalance_Aggregate_Equivalence_Report.md`](L7_4_A4_1_PotBalance_Aggregate_Equivalence_Report.md)
  - [`L7_4_A4_2_PotBalance_Incremental_N_Minus_M_Report.md`](L7_4_A4_2_PotBalance_Incremental_N_Minus_M_Report.md)
  - [`L7_4_A4_3_PotBalance_Performance_Qualification_Report.md`](L7_4_A4_3_PotBalance_Performance_Qualification_Report.md)
  - [`Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)
- **Verdict final** : **`A3_A4_READY_TO_PUBLISH`**

---

## 1. Commits qualifiés

Séquence complète sur `feat/l7-4-a3-potversion-incremental-nm` depuis `main` (`1c5140fe`) :

| Commit | Lot | Objet |
|---|---|---|
| `b344e440` | L7.4-A | Banc de référence L7.4-A, rapport et preuves baseline (`G = 1`) |
| `f196b2fb` | L7.4-A2 | Élimination des relectures redondantes de sous-objets dans `MaterializePotVersion` |
| `814fcb0b` | **L7.4-A3** | Calcul incrémental opportuniste `N−m` de `PotVersion` (`MaterializePotVersion`, `JdbcPotVersionSource`, `JdbcPotVersionStore`, Micrometer) |
| `e8125bc9` | **L7.4-A4.1** | Modèle d'agrégats pur `PotBalanceAggregate` et calculateur `PotBalanceAggregateCalculator` (`domain-projection`) |
| `317854c8` | **L7.4-A4.2** | Calcul incrémental opportuniste `N−m` de `PotBalance`, migration additive `V2__pot_balance_aggregate.sql`, codec JSONB D1, seuils D5, Micrometer et `v3-rebuild-read.py` |
| `cc85406b` | **L7.4-A4.3** | Qualification de performance `PotBalance` `N−m` (`restore-large-1`, `med-1`, `med-5`, `weight-update-large`, `out-of-order-nm-large`) |
| *(commit local de consolidation)* | **L7.4-A3+A4 Gate** | Test d'intégration conjoint PostgreSQL 17 (`PotVersionAndBalanceJointIncrementalPostgresTest`), campagne multi-buckets & seuils D5 (`run-l7-4-a3-a4-multibucket.py`), mise à jour `L7ReactorArchitectureTest` (`V2__pot_balance_aggregate.sql` + types imbriqués `*Source$*`), preuves archivées et présent rapport |

---

## 2. Matrice des vérifications (conjointe A3 + A4)

| Dimension | Exigence | Preuve / Test | Statut |
|---|---|---|---|
| **Coexistence A3 + A4 hors ordre & `N − K > 1`** | Progression asymétrique concurrente (`K_ver != K_bal`, `N − K >= 4..5`) | [`PotVersionAndBalanceJointIncrementalPostgresTest`](../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/PotVersionAndBalanceJointIncrementalPostgresTest.java) (`jointOutOfOrder...`) + `out-of-order-nm-large` | **PASS** |
| **Mutations multiples & typologie des poids** | Poids par défaut, spécifiques, mixtes, créations, modifications financières, suppressions logiques, `AddShareholders` (ré-indexation D1), `DeleteShareholder` (D4), `DeletePot` (terminal `D`) | `PotVersionAndBalanceJointIncrementalPostgresTest` + `PotBalanceAggregateEquivalenceTest` (`9/9`) + `multibucket-realistic` (`60/60`) + `multibucket-d5-boundary` (`59/59`) | **PASS** |
| **Indépendance des fallbacks A3 vs A4** | `global_shareholder_effect` (A3 full, A4 incrémental `0` dépense relue) ; `shareholder_deleted` (A4 full, A3 incrémental) ; `bucket_cap` (A4 full sans agrégat, A3 `100%` incrémental) ; `maxDeltaDistance` asymétrique | `PotVersionAndBalanceJointIncrementalPostgresTest` (`2/2`) + `multibucket-d5-boundary` Phase B (`A3 = 29/29` incrémental pendant `13` fallbacks `bucket_cap` de A4) | **PASS** |
| **Reconstruction READ complète** | Reset complet des tables READ incluant `read_pot_balance_aggregate`, rejeu intégral et vérification bit-à-bit contre les deux oracles | `PotVersionAndBalanceJointIncrementalPostgresTest` + `ReadReconstructionPostgresTest` | **PASS** |
| **Reprise, idempotence & atomicité** | Rejeu idempotent de versions déjà matérialisées, `prepareRecovery()` sur oracle intégral (`SATISFIED` sur toutes les versions), atomicité balance+agrégat+slot, triggers `ENABLE ALWAYS` | `PotVersionAndBalanceJointIncrementalPostgresTest` + `PotBalanceReadStorePostgresTest` (`4/4`) + `PotVersionReadStorePostgresTest` (`4/4`) + `SubobjectAtomicityPostgresTest` | **PASS** |
| **Absence de divergence de valeurs** | Équivalence exacte rationnelle (`num/den`) et `PotVersion` + sous-objets contre les oracles intégraux | `0` divergence sur tous les tests Java/PG et sur `119/119` commandes multi-buckets vérifiées par l'oracle Python `Fraction` indépendant | **PASS** |
| **Multi-buckets réaliste (`G = 60..62`)** | `100` shareholders, `2 000` dépenses, `45` groupes défaut + `15` mixtes + pur spécifique, `60` commandes mixtes | `docs/testing/evidence/L7-4A3-A4-CONSOLIDATED/multibucket-realistic/manifest.json` | **PASS** |
| **Proche des seuils D5 (`G = 240`) & dépassement (`G > 256`)** | `G = 240`, `6 401` entrées puis franchissement `G = 257..260` | `docs/testing/evidence/L7-4A3-A4-CONSOLIDATED/multibucket-d5-boundary/manifest.json` | **PASS** |
| **Full Reactor Maven + Architecture + PostgreSQL 17** | `42` modules enfants V3, `0` échec, `0` erreur, `0` exclusion | `./mvnw --batch-mode --no-transfer-progress test` + reprise propre `runtime-monolith` & `architecture-tests` (`1 119 / 1 119` PASS) | **PASS** |

---

## 3. Gate Maven, tests d'architecture et intégration PostgreSQL

### 3.1 Portée déclarée (`Reactor_Verification_Policy.md` §30)

- **Start HEAD** : `cc85406b`
- **Expected production impact** : `NONE` (lot de consolidation/qualification ; aucune modification de code de production requise)
- **Observed production impact** : `NONE` (uniquement ajouts/mises à jour de tests, script de campagne, preuves et documentation)
- **Declared slices** : `PROJECTION` + **Full Reactor** + **Global Architecture Gate** (gate d'intégration requis par le mandat et `Reactor_Verification_Policy.md` §§19/21.3)
- **Unexpected slice crossings** : `NONE`
- **Global architecture gate** : **RUN (`52/52` PASS)**
- **Full reactor** : **RUN (`42/42` modules enfants V3, `1 119 / 1 119` tests PASS, `0` échec, `0` erreur, `0` skip)**
- **Database verification** : `CURRENT` (PostgreSQL 17.11 via Testcontainers sur l'ensemble des suites `infra-*` et `runtime-*`, incluant `V2__pot_balance_aggregate.sql`, triggers d'immuabilité, privilèges minimaux et migration additive `V1 → V2`)

### 3.2 Diagnostic des incidents rencontrés durant le gate et résolution

Conformément à la procédure du mandat (§4) :

1. **Incident d'environnement (non imputable au lot) — `pocoma-runtime-monolith`** :
   - Lors de l'exécution initiale de `./mvnw --batch-mode --no-transfer-progress test` (qui a passé **`41/43` modules** incluant `infra-postgres-write` `473/473`, `runtime-worker-process` `98/98` dont `i5_r05`/`i5_r12`, et `runtime-worker-materialize` `115/115`), `runtime-monolith` a échoué sur des `.class` périmés produits par le serveur de langage IDE (Eclipse JDT LS) dans `runtime-monolith/target` (`FileNotFoundException: AdmissionHttpConfiguration.class`, `Container PG needs to be initialized`).
   - **Résolution** : installation propre des modules amont (`install -DskipTests`) puis exécution `clean test` de `runtime-monolith` → **`40/40` tests PASS** en `21m41s`, `0` échec, `0` erreur.
2. **Régressions réelles détectées par le gate global — `L7ReactorArchitectureTest` (`2` échecs)** :
   - `migrationBytesAndResourceOwnershipAreConserved:191` : la liste statique des migrations actives de `infra-postgres-read` n'attendait encore que `V1__initial_schema.sql` et ne déclarait pas la nouvelle migration additive `V2__pot_balance_aggregate.sql` introduite en A4.2.
   - `sourceAdaptersCannotInvokeMutatorsAndRuntimesOnlyHostCompose:138` : la règle `type.endsWith("Source")` rejetait les records de contrat imbriqués dans les ports `*Source` introduits en A3/A4 (`PotVersionSource$EventRef`, `PotBalanceSource$EventRef`, `PotVersionSource$StateDelta`, `PotBalanceSource$FinancialDelta`).
   - **Correction** : mise à jour de [`L7ReactorArchitectureTest.java`](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/l7/L7ReactorArchitectureTest.java) pour inclure `V2__pot_balance_aggregate.sql` dans l'inventaire actif de `infra-postgres-read` (sans modifier les 64 empreintes historiques SHA-256) et accepter `type.endsWith("Source") || type.contains("Source$")` (tout en continuant d'interdire tout accès aux classes d'implémentation `Materialize*`).
   - **Re-vérification** : `./mvnw --batch-mode --no-transfer-progress -pl architecture-tests clean test` → **`52/52` tests PASS**, `0` échec, `0` erreur.

### 3.3 Synthèse des résultats Maven par module (42 modules enfants V3)

| Module | Tests | Échecs | Erreurs | Skippés | Statut |
|---|---:|---:|---:|---:|---|
| `infra-postgres-write` | `473` | `0` | `0` | `0` | **PASS** |
| `runtime-worker-materialize` *(incl. joint A3+A4)* | `115` | `0` | `0` | `0` | **PASS** |
| `runtime-worker-process` *(incl. `i5_r05`, `i5_r12`)* | `98` | `0` | `0` | `0` | **PASS** |
| `architecture-tests` *(incl. `L7ReactorArchitectureTest`)* | `52` | `0` | `0` | `0` | **PASS** |
| `domain-pot` | `46` | `0` | `0` | `0` | **PASS** |
| `runtime-web-read` | `42` | `0` | `0` | `0` | **PASS** |
| `runtime-monolith` | `40` | `0` | `0` | `0` | **PASS** |
| `orchestrator-consumption` | `38` | `0` | `0` | `0` | **PASS** |
| `infra-postgres-read` | `30` | `0` | `0` | `0` | **PASS** |
| `infra-tx-spring` | `28` | `0` | `0` | `0` | **PASS** |
| `domain-identity` | `20` | `0` | `0` | `0` | **PASS** |
| `compose-web-write-postgres` | `18` | `0` | `0` | `0` | **PASS** |
| `supra-web-read` | `17` | `0` | `0` | `0` | **PASS** |
| `domain-projection` *(incl. A4.1 équivalence)* | `14` | `0` | `0` | `0` | **PASS** |
| `engine-write-pot` | `14` | `0` | `0` | `0` | **PASS** |
| `compose-polling-burnable-write-postgres` | `13` | `0` | `0` | `0` | **PASS** |
| `compose-polling-nonburnable-materialize-postgres` | `13` | `0` | `0` | `0` | **PASS** |
| `engine-write-identity` | `13` | `0` | `0` | `0` | **PASS** |
| `infra-postgres-core` | `12` | `0` | `0` | `0` | **PASS** |
| `engine-materialize-pot` *(incl. A3 + A4 unitaires)* | `7` | `0` | `0` | `0` | **PASS** |
| `infra-observability-micrometer` | `6` | `0` | `0` | `0` | **PASS** |
| `engine-admit-identity` | `4` | `0` | `0` | `0` | **PASS** |
| `engine-materialize-results`, `engine-read-execution` (`2` chacun) | `4` | `0` | `0` | `0` | **PASS** |
| `domain-consumption`, `engine-read-identity` (`1` chacun) | `2` | `0` | `0` | `0` | **PASS** |
| *(16 autres modules contrats/ports/façades sans tests dédiés)* | `0` | `0` | `0` | `0` | **PASS** |
| **TOTAL RÉACTEUR V3 (`42` modules enfants)** | **`1 119`** | **`0`** | **`0`** | **`0`** | **PASS** |

---

## 4. Campagne de performance multi-buckets et seuils D5

> [!IMPORTANT]
> **Précaution méthodologique** : Les campagnes multi-buckets ci-dessous exercent une charge métier nettement plus riche que les anciennes campagnes `G = 1` (`restore-large-1`) : `60` à `260` buckets distincts, poids mixtes et spécifiques, modifications financières, suppressions logiques et changements de poids, avec vérification d'équivalence rationnelle exacte par un oracle Python `Fraction` sur chaque version. **Elles ne sont pas présentées comme directement comparables 1:1 aux campagnes historiques `G = 1`** ; la comparaison A2 / A3 / A4 à iso-charge `G = 1` figure séparément en §5.

- **Script reproductible** : [`scripts/performance/run-l7-4-a3-a4-multibucket.py`](../../scripts/performance/run-l7-4-a3-a4-multibucket.py)
- **Topologie & images épinglées** : identiques à L7.4-A2/A3/A4 (5 runtimes Docker mono-hôte, `postgres@sha256:a77aa70e...`, `eclipse-temurin@sha256:66bb9006...`).
- **Preuves archivées** :
  - `docs/testing/evidence/L7-4A3-A4-CONSOLIDATED/multibucket-realistic/`
  - `docs/testing/evidence/L7-4A3-A4-CONSOLIDATED/multibucket-d5-boundary/`

### 4.1 Synthèse des trois régimes multi-buckets (`100` shareholders, `2 000` dépenses)

| Indicateur | **1. Multi-buckets réaliste** (`realistic-60`) | **2. Proche seuils D5** (`near-d5-30`) | **3a. D5 traversée `G ≤ 256`** (`exceed-d5`, 16 cmds) | **3b. D5 dépassé `G > 256`** (`exceed-d5`, 13 cmds) |
|---|---:|---:|---:|---:|
| **Charge métier** | `60` cmds (`20` Create, `15` UpdateFin, `15` Weights, `10` Delete) | `30` cmds (`10` Create, `8` UpdateFin, `8` Weights, `4` Delete) | `16` Create (nouveaux buckets `241→256`) | `4` Create (`257→260`), `4` Weights, `3` UpdateFin, `2` Delete |
| **Cardinalité réelle des buckets ($G$)** | **`60` → `62`** (`45` défaut + `15` mixtes + pur spéc.) | **`240`** (`200` défaut + `40` mixtes) | **`241` → `256`** (plafond D5 exact) | **`257` → `260` → `258`** (`> 256`) |
| **Entrées d'agrégats ($\sum (\|B_{\text{def}}\| + \|Q_\kappa\|)$)** | `1 144` avg (`max 1 162`) | **`6 401`** (`max 6 401`, cap `10 000`) | `6 622` avg (`max 6 817`) | `6 869` (oracle, **non persisté**) |
| **Taille agrégat JSONB texte (`octet_length`)** | `21 514 B` (`21.0 KB`, max `21.1 KB`) | `55 301 B` (`54.0 KB`, max `54.0 KB`) | `56 739 B` (`55.4 KB`, max `56.6 KB`) | **`0` ligne écrite** (`0 B`) |
| **Taille agrégat disque (`pg_column_size`)** | **`10 085 B` (`9.8 KB`)** | **`21 319 B` (`20.8 KB`)** | **`21 914 B` (`21.4 KB`)** | **`0` ligne écrite** (`0 B`) |
| **Équivalence exacte oracle intégral (`Fraction`)** | **`60/60` (`0` divergence)** | **`30/30` (`0` divergence)** | **`16/16` (`0` divergence)** | **`13/13` (`0` divergence)** |
| **Taux incrémental `PotBalance` (A4)** | **`100%` (`60/60`)** | **`100%` (`30/30`)** | **`100%` (`16/16`)** | **`0%` (`13/13` `full:bucket_cap`)** |
| **Taux incrémental `PotVersion` (A3)** | **`100%` (`60/60`)** | **`100%` (`30/30`)** | **`100%` (`16/16`)** | **`100%` (`13/13` — indépendant !)** |
| **`PotBalance` p50 / p95 / p99\*** | **`11.13` / `14.97` / `17.83 ms`** (`max 18.86`) | **`33.89` / `42.05` / `46.47 ms`** (`max 59.09`) | **`37.33` / `41.21` / `41.21 ms`** (`max 47.70`) | `351.82` / `377.28` / `377.28 ms` (`max 469.18`) |
| **`PotVersion` p50 / p95 / p99\*** | **`29.42` / `39.52` / `47.25 ms`** (`max 59.27`) | **`33.20` / `44.18` / `65.12 ms`** (`max 67.74`) | **`34.47` / `41.95` / `41.95 ms`** (`max 46.94`) | **`35.71` / `102.89` / `102.89 ms`** (`max 104.10`) |
| **E2E READ `C-03` p50 / p95 / p99\*** | **`158.69` / `223.61` / `367.07 ms`** | **`198.37` / `307.21` / `405.01 ms`** | `235.02` / `492.81` / `634.38 ms` *(fenêtre 3a+3b combinée, `n=29`)* | *(cf. 3a)* |

*\*Limite statistique : pour `n <= 60`, `p99` correspond au dernier ou avant-dernier point observé de l'échantillon.*

### 4.2 Décomposition interne des coûts d'agrégats (Micrometer)

| Poste interne (`PotBalance`) | `realistic-60` (`G ≈ 61`, `1 144` entrées) | `near-d5-30` (`G = 240`, `6 401` entrées) | `exceed-d5` (`16` incrémentaux + `13` `bucket_cap`) |
|---|---:|---:|---:|
| **Lecture + désérialisation JSONB $\mathcal{A}(K)$** | **`1.142 ms` avg** | **`2.204 ms` avg** | `2.207 ms` avg |
| **Résolution delta `v3_write`** | **`1.471 ms` avg** (`45` dép. / `60` cmds = **`0`** sur `15` poids) | **`1.590 ms` avg** (`22` dép. / `30` cmds = **`0`** sur `8` poids) | `1.757 ms` avg (`74` dép. / `29` cmds) |
| **Composition RAM $\mathcal{A}(K) \to \mathcal{A}(N)$** | **`0.330 ms` avg** | **`0.724 ms` avg** | `2.724 ms` avg *(inclut reconstruction intégrale sur fallback)* |
| **Calcul / Évaluation `Eval(A(N), w(N))` (incrémental)** | **`1.558 ms` avg** | **`15.826 ms` avg** | `16.612 ms` avg (`n = 16`) |
| **Calcul intégral (fallback `bucket_cap`)** | N/A (`0`) | N/A (`0`) | **`199.780 ms` avg** (`n = 13`) |
| **Persistance `read_pot_balance`** | **`1.642 ms` avg** | **`3.366 ms` avg** | `3.560 ms` avg |
| **Persistance `read_pot_balance_aggregate`** | **`2.210 ms` avg** | **`5.744 ms` avg** | `6.304 ms` avg (`n = 16`, **`0`** au-dessus de `256`) |

**Enseignements clés de la campagne multi-buckets** :
1. **Tenue en charge multi-buckets réaliste (`G = 60..62`)** : `PotBalance` reste à **`11.13 ms` p50** (`14.97 ms` p95), avec une empreinte disque de **`9.8 KB`/version** et **`0` dépense relue** lors des `15` changements de poids.
2. **Rentabilité prouvée jusqu'au plafond D5 (`G = 240..256`)** : même à `94 %`–`100 %` du plafond D5 (`240`–`256` buckets, `6 401`–`6 817` entrées, `~21 KB` sur disque), le chemin incrémental (`33.89`–`37.33 ms` p50) reste **`~10×` plus rapide** que le chemin intégral (`351.82 ms` p50).
3. **Bascule propre au-delà de D5 (`G > 256`)** : le plafond `maxAggregateBuckets = 256` déclenche immédiatement `full:bucket_cap`, bloque toute persistance d'agrégat (`0` ligne écrite, `0` gonflement JSONB), préserve l'exactitude rationnelle (`13/13`, `0` divergence) et **n'affecte aucunement `PotVersion` (A3)** qui reste à **`100%` incrémental** (`35.71 ms` p50).

---

## 5. Comparaison consolidée A2 / A3 / A4 (iso-charge historique `G = 1`)

Synthèse consolidée des campagnes comparables à iso-charge (`restore-large-1` `100/2000`, `med-1` `20/200 @ 1 cmd/s`, `med-5` `20/200 @ 5 cmd/s`), issue des preuves archivées `L7-4A2`, `L7-4A3` et `L7-4A4` (aucune preuve historique écrasée) :

| Campagne & Métrique | L7.4-A2 (`PotVersion` opt., `PotBalance` full) | L7.4-A3 (`PotVersion` `N−m`, `PotBalance` full) | L7.4-A4 (`PotVersion` + `PotBalance` `N−m`) | Gain A3 vs A2 | Gain propre A4 vs A3 | **Gain cumulé A3+A4 vs A2** |
|---|---:|---:|---:|---:|---:|---:|
| **Grand Pot `restore-large-1` — `PotVersion` p50 / p95** | `1 031.52` / `1 376.17 ms` | `34.96` / `50.63 ms` | **`29.94` / `40.80 ms`** | `-96.6%` / `-96.3%` | `-14.4%` / `-19.4%` | **`-97.1%` (`34.5×`) / `-97.0%`** |
| **Grand Pot `restore-large-1` — `PotBalance` p50 / p95** | `455.51` / `573.08 ms` | `381.61` / `441.65 ms` | **`8.39` / `12.58 ms`** | `-16.2%` / `-22.9%` | **`-97.8%` (`45.5×`) / `-97.2%`** | **`-98.2%` (`54.3×`) / `-97.8%`** |
| **Grand Pot `restore-large-1` — E2E `C-03` READ p50 / p95** | `1 234.93` / `1 549.65 ms` | `511.03` / `613.79 ms` | **`157.76` / `292.99 ms`** | `-58.6%` / `-60.4%` | **`-69.1%` / `-52.3%`** | **`-87.2%` (`7.8×`) / `-81.1%`** |
| **Grand Pot `restore-large-1` — CPU `postgres` avg** | `152.9%` | `86.6%` | **`76.2%`** | `-66.3 pts` | `-10.4 pts` | **`-76.7 pts`** |
| **Grand Pot `restore-large-1` — CPU `read-worker` avg** | `85.7%` | `77.2%` | **`45.4%`** | `-8.5 pts` | **`-31.8 pts`** | **`-40.3 pts`** |
| **Pot Moyen `med-1` — `PotVersion` p50 / `PotBalance` p50** | `25.31 ms` / — | `9.95` / `8.46 ms` | **`8.69` / `4.39 ms`** | `-60.7%` (`PotVersion`) | `-12.7%` / **`-48.1%`** | **`-65.7%`** (`PotVersion`) |
| **Pot Moyen `med-5` — `PotVersion` p50 / `PotBalance` p50** | — | `9.72` / `15.40 ms` | **`8.74` / `4.27 ms`** | — | `-10.1%` / **`-72.3%`** | — |
| **Grand Pot `weight-update-large` (`30` poids)** | — | `381.61 ms` *(ref full)* | **`8.00 ms` p50** (`0` dép. relue) | — | **`-97.9%` (`47.7×`)** | — |
| **Grand Pot `out-of-order-nm-large` (`N−K = 2..5`)** | — | — | **`100%` incrémental** (`22.0 ms` p50 sous rafale) | — | — | — |

---

## 6. Réserves, limites et distinction bloquant vs non bloquant

### Réserves bloquantes
- **AUCUNE (`0`)**. L'ensemble des exigences fonctionnelles, architecturales, PostgreSQL 17, multi-buckets, seuils D5 et full reactor Maven (`1 119 / 1 119` PASS, `0` échec, `0` erreur) est satisfait.

### Limites environnementales et statistiques non bloquantes
1. **Topologie locale mono-hôte Docker** : les campagnes de performance (`L7-4A3`, `L7-4A4`, `L7-4A3-A4-CONSOLIDATED`) fournissent des preuves locales reproductibles mais ne se substituent pas à une certification multi-nœuds Config A/B.
2. **Taille d'échantillon finie (`n = 13` à `60`)** : sur `n <= 60`, `p99` coïncide avec le maximum ou l'avant-dernier point de l'échantillon ; aucune extrapolation vers des queues p99.9 n'est revendiquée.
3. **Coût résiduel connu et assumé** :
   - Sur `PotVersion` (A3), les `3×` décodages `PotVersionJson` en `finalize` (`~30 ms` sur Grand Pot) ont été volontairement conservés par l'arbitrage D3.
   - Sur `PotBalance` (A4), les pots dépassant les seuils D5 (`G > 256` ou `entries > 10 000`) basculent proprement sur le calcul intégral (`~352 ms` p50 sur `100/2000`) sans stockage d'agrégats.
4. **Exécution du gate en deux temps pour `runtime-monolith` + `architecture-tests`** : `41/43` modules ont passé lors du premier `./mvnw --batch-mode --no-transfer-progress test` ; `runtime-monolith` (`40/40`) et `architecture-tests` (`52/52`) ont été exécutés proprement juste après (sans aucune modification des `41` modules amont).

---

## 7. État Git final

- **Branche** : `feat/l7-4-a3-potversion-incremental-nm`
- **Action distante** : **AUCUN push, AUCUN merge, AUCUN tag** (conformément au mandat).
- **Preuves historiques** : strictement intactes (`docs/testing/evidence/L7-4A*` non modifiés).
- **Fichiers ajoutés / modifiés dans ce lot de consolidation** :
  - [`app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/PotVersionAndBalanceJointIncrementalPostgresTest.java`](../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/PotVersionAndBalanceJointIncrementalPostgresTest.java) (créé)
  - [`app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/l7/L7ReactorArchitectureTest.java`](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/l7/L7ReactorArchitectureTest.java) (mis à jour : inventaire `V2__pot_balance_aggregate.sql` + types imbriqués `*Source$*`)
  - [`scripts/performance/run-l7-4-a3-a4-multibucket.py`](../../scripts/performance/run-l7-4-a3-a4-multibucket.py) (créé)
  - `docs/testing/evidence/L7-4A3-A4-CONSOLIDATED/{multibucket-realistic,multibucket-d5-boundary}/` (créés)
  - [`docs/v3/L7_4_A3_A4_Consolidated_Qualification_Report.md`](L7_4_A3_A4_Consolidated_Qualification_Report.md) (créé)

---

## 8. Verdict final

**`A3_A4_READY_TO_PUBLISH`**

Les optimisations incrémentales `N−m` de `PotVersion` (A3) et `PotBalance` (A4) sont qualifiées conjointement sans aucune réserve bloquante :
- **Exactitude & sûreté** : `0` divergence de valeurs sur tous les oracles Java/PostgreSQL 17 et sur `119/119` commandes multi-buckets vérifiées par oracle Python `Fraction` ; indépendance stricte des fallbacks A3/A4 prouvée ; reconstruction READ, reprise, idempotence et atomicité vérifiées.
- **Performance multi-buckets & seuils D5** : `PotBalance` atteint **`11.13 ms` p50** sur Grand Pot réaliste à `60..62` buckets (`9.8 KB` disque), reste **`~10×` plus rapide** jusqu'au plafond D5 (`33.89 ms` p50 à `G = 240`, `20.8 KB` disque), et bascule proprement en `bucket_cap` au-delà de `256` sans gonflement JSONB ni impact sur `PotVersion`.
- **Gate Maven complet** : **`1 119 / 1 119` tests PASS** sur les `42` modules enfants V3 (`0` échec, `0` erreur, `0` exclusion), incluant `architecture-tests` (`52/52`) et l'ensemble des suites PostgreSQL 17 (`runtime-worker-materialize`, `runtime-worker-process`, `runtime-monolith`, `runtime-web-read`, `infra-postgres-*`).

La branche `feat/l7-4-a3-potversion-incremental-nm` est prête à être publiée et intégrée sur nouveau mandat.
