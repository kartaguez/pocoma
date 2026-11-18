# Rapport L7.4-A2 — Optimisation des relectures de `MaterializePotVersion`

- **Date** : 18 novembre 2026
- **Branche de référence (L7.4-A)** : `feat/l7-4-a-baseline-qualification` @ `b344e44005f660c0d995365a065c54512f2d8ff1`
- **Branche d'optimisation (L7.4-A2)** : `feat/l7-4-a2-potversion-optimization`
- **Verdict L7.4-A2** : **`L7_4_A2_OPTIMIZATION_QUALIFIED`**

---

## 1. Analyse de la cause

### 1.1 Chemin complet d'une matérialisation `PotVersion` (avant optimisation)

L'inspection de [`MaterializePotVersion.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotVersion.java), [`JdbcPotVersionSource.java`](../../app/infra-postgres-write-source-read/src/main/java/com/kartaguez/pocoma/infra/postgres/source/JdbcPotVersionSource.java), [`JdbcSubobjectVersionStore.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcSubobjectVersionStore.java), [`JdbcPotVersionStore.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcPotVersionStore.java) et [`ConsumptionEngine.java`](../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/ConsumptionEngine.java) établit la séquence exacte suivante pour chaque événement `POT` sur un Grand Pot (`100` participants, `2 000` dépenses = `6 302` sous-objets) :

1. **Phase `PREPARE`** (`ConsumptionEngine.executeAttempt`) :
   - `reads.source(c -> source.composition(event, c))` : lit l'événement et les en-têtes/versions locales dans `v3_write` (`PotHistoryRows`), construisant `PotVersion value` (`6 302` `SubobjectKey`s).
   - `reads.effects(c -> subobjects.readAll(value.keys(), c))` : exécute `25` requêtes `SELECT pot_id,kind,object_id,local_version,schema_version,payload::text FROM read_subobject_version` (paquets de `256`) et **désérialise en JSON (`SubobjectJson.decode`) les `6 302` sous-objets** (dont `~200 000` participations).
   - `MaterializePotVersion.prepare()` n'utilise pourtant de `present` que `present.containsKey(k)` (pour calculer `missing`, soit `3` clés sur un `CreateExpense`) et `present.values()` (pour construire `all` à destination de `requireCompletionReady()`). Le contenu métier de `present` n'est jamais inspecté.
   - `reads.source(c -> source.contents(value, missing, c))` : charge uniquement les `3` sous-objets manquants depuis `v3_write`.

2. **Phase `FINALIZE`** (`ConsumptionEngine.finalizeAttemptOperation`, transaction `READ_COMMITTED` sur `v3_read`) :
   - `subobjects.insertOrVerifyAll(loaded)` : insère les `3` sous-objets manquants (`ON CONFLICT DO NOTHING`) puis les relit et compare leur contenu décodé (`compare`).
   - `versions.insertAndAdvance(value)` : encode `PotVersion` en JSON (`2 100` entrées), insère dans `read_pot_version`, relit et compare (`ReadJdbc.same`), puis avance `read_current_pot`.
   - `completion.complete()` → `requireCompletionReady()` :
     - `subobjects.requireStored(all)` : **relit une seconde fois les `6 302` lignes** de `read_subobject_version` (`25` `SELECT` avec `payload::text`), **redésérialise une seconde fois les `6 302` JSON (`~200 000` participations)** et compare `actual.equals(candidate)` contre les objets déjà lus depuis la même table en phase `PREPARE`.
     - `versions.requireStored(value)` : relit `read_pot_version` et `read_current_pot`.

### 1.2 Mesure ciblée du coût (Phase B)

Les métriques Micrometer existantes (`pocoma_v3_attempt_phase_duration_seconds`) sur `L7-4A/restore-large-1` (`n=52` événements `PotVersion` incluant le warmup) décomposent le coût sans instrumentation intrusive :

| Phase `ConsumptionEngine` | Total (`52` événements) | Moyenne / événement | Part | Opérations dominantes |
|---|---:|---:|---:|---|
| `phase="prepare"` | `40.482 s` | **`778.5 ms`** | `53.9%` | `source.composition` (`v3_write`) + `subobjects.readAll` (`25` SELECT `payload::text` + `200 000` décodages JSON) |
| `phase="finalize"` | `34.625 s` | **`665.9 ms`** | `46.1%` | `insertAndAdvance` (`PotVersionJson`) + `subobjects.requireStored(all)` (`25` SELECT `payload::text` + `200 000` décodages JSON) + `versions.requireStored` + commit |
| **Total `PotVersion`** | `75.107 s` | **`1 444.4 ms`** | `100%` | Cohérent avec le p50 mesuré (`1 463.92 ms`) |

*Limite documentée* : les métriques standard séparent `prepare` et `finalize`, sans isoler la sous-part exacte de `source.composition` vs `readAll` à l'intérieur de `prepare`. La présence symétrique de `~666 ms` en `finalize` (où `read_subobject_version` est relu en `requireStored(all)`) étaye suffisamment l'hypothèse avant modification.

---

## 2. Garanties préservées

La table `read_subobject_version` est immuable par `(pot_id, kind, object_id, local_version)` et le rôle runtime PostgreSQL ne possède aucun privilège `UPDATE`, `DELETE` ou `TRUNCATE`. Toutefois, afin de ne **supprimer aucune vérification d'existence en transaction finale**, `requireCompletionReady()` continue de vérifier la présence de toutes les clés dans la transaction `READ_COMMITTED` finale avant suppression du slot :

- **Sous-objets nouvellement chargés (`loaded`)** : insérés par `insertOrVerifyAll(loaded)` et vérifiés intégralement (`payload::text` + `SubobjectJson.decode` + égalité structurelle) par `subobjects.requireStored(loaded)`.
- **Sous-objets préexistants (`presentKeys`)** : détectés en `prepare` par `subobjects.existingKeys(value.keys(), c)` (requête PK uniquement, sans `payload::text` ni `SubobjectJson.decode`), puis vérifiés en `requireCompletionReady()` par `subobjects.requireKeysPresent(presentKeys)` dans la transaction `READ_COMMITTED` finale (`CorruptSubobjectException` si une clé manque).
- **Concurrence et conflits** : `insertOrVerifyAll(loaded)` conserve `INSERT ... ON CONFLICT DO NOTHING` suivi d'une relecture `READ_COMMITTED` et `compare`, levant `SubobjectConflictException` en cas de divergence concurrente.
- **Rejeu et idempotence** : si `missing` est vide, `loaded` est vide (`insertOrVerifyAll(List.of())` et `requireStored(List.of())` sont sans effet), et `requireKeysPresent(presentKeys)` + `versions.requireStored(value)` vérifient l'intégralité des clés et de la version courante avant suppression du slot.
- **Reprise après crash (`prepareRecovery`)** : inchangée (`source.contents(expected, expected.keys(), c)` + `subobjects.evidence(contents, c)`).
- **Lectures HTTP (`ReadPot`, `ReadInvitation`)** : inchangées (`readAll` conservé intact).

---

## 3. Modification exacte

1. [`SubobjectVersionStore.java`](../../app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/SubobjectVersionStore.java) : ajout de `Set<SubobjectKey> existingKeys(Collection<SubobjectKey> keys, RecoveryReadContext context)` et `void requireKeysPresent(Collection<SubobjectKey> expectedKeys)`.
2. [`JdbcSubobjectVersionStore.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcSubobjectVersionStore.java) : implémentation de `existingKeys` (sous `SpringAttemptTransactions.observed`) et `requireKeysPresent` (sous `SpringAttemptTransactions.guarded` + `requireReadCommitted()`), factorisant le découpage par paquets de `256` via `queryChunks(...)` et requêtant uniquement les colonnes de clé primaire `(pot_id, kind, object_id, local_version)` sans `payload::text` ni `SubobjectJson.decode`.
3. [`MaterializePotVersion.java`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotVersion.java) : remplacement de `readAll` par `existingKeys` dans `prepare()`, et remplacement de `requireStored(all)` par `requireStored(loaded)` + `requireKeysPresent(presentKeys)` dans `requireCompletionReady()`.

---

## 4. Tests exécutés et résultats

### Déclaration de périmètre (`Reactor_Verification_Policy.md`)
- **Slice primaire** : `PROJECTION` (`./mvnw -pl runtime-worker-materialize -am clean test`)
- **Slices secondaires** : `NONE` (aucun contrat HTTP, domaine ou WRITE modifié)
- **Global architecture gate / Full reactor** : `NOT REQUIRED`

### Tests ajoutés et exécutés
- [`MaterializePotVersionTest.java`](../../app/engine-materialize-pot/src/test/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotVersionTest.java) : vérifie que `prepare()` appelle `existingKeys` (jamais `readAll`), ne charge que `missing` via `source.contents`, et que `requireCompletionReady()` vérifie `requireStored(loaded)` + `requireKeysPresent(presentKeys)` (`1/1 PASS`).
- [`SubobjectStorePostgresTest.java`](../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/SubobjectStorePostgresTest.java) (`existingKeysAndRequireKeysPresent`) : vérifie sur PostgreSQL 17 réel (Testcontainers) le comportement sur > 256 clés, l'absence de `payload` dans les requêtes SQL, la levée de `CorruptSubobjectException` sur clé absente et le garde `READ_COMMITTED` (`12/12 PASS`).
- **Slice canonique `PROJECTION`** (`./mvnw -pl runtime-worker-materialize -am clean test`) : **`PASS`** (`35` modules construits et testés, `108` tests dans `runtime-worker-materialize`, `0` échec, `0` erreur).
- **Tests unitaires du banc** (`python3 -m unittest scripts/performance/test_l7_4a_baseline.py`) : **`4/4 PASS`**.

---

## 5. Comparaison avant/après (Phase E)

Conditions strictement identiques : même topologie Docker 5 runtimes (`postgres` `2.0` CPU / `2048m`, `business-read-worker` `2.0` CPU / `1024m`), mêmes images (`eclipse-temurin:21.0.8_9-jre`, `postgres:17-alpine`), même snapshot L7.4-A pour `restore-large-1` et `restore-smoke-medium`.

### 5.1 Grand Pot (`restore-large-1`, `100/2000`, `2` pots, `1.0 cmd/s` demandé, `n=60`)

| Métrique | L7.4-A (référence) | L7.4-A2 (optimisé) | Gain absolu | Gain relatif |
|---|---:|---:|---:|---:|
| **Verdict / Offres observées** | `MEASUREMENT_INVALID` (`49/60`) | **`PASS` (`60/60`)** | `+11` obs (`0` drop) | **`-100%` drops** |
| **Débit effectif terminé** | `0.829 cmd/s` | **`0.998 cmd/s`** | `+0.169 cmd/s` | **`+20.4%`** |
| **PotVersion p50** | `1 463.92 ms` | **`1 031.52 ms`** | `-432.40 ms` | **`-29.5%`** |
| **PotVersion p95** | `2 094.30 ms` | **`1 376.17 ms`** | `-718.13 ms` | **`-34.3%`** |
| **PotVersion p99** | `2 187.26 ms` | **`1 544.06 ms`** | `-643.20 ms` | **`-29.4%`** |
| **PotBalance p50 / p95** | `488.82` / `671.50 ms` | `455.51` / `573.08 ms` | `-33.3` / `-98.4 ms` | `-6.8%` / `-14.7%` |
| **Admission p50 / p95** | `8.83` / `18.17 ms` | `8.32` / `11.93 ms` | `-0.51` / `-6.24 ms` | `-5.8%` / `-34.3%` |
| **WRITE service p50 / p95** | `29.48` / `95.73 ms` | `26.37` / `40.80 ms` | `-3.11` / `-54.93 ms` | `-10.5%` / `-57.4%` |
| **E2E `/result` p50 / p95** | `101.60` / `193.03 ms` | `98.47` / `199.34 ms` | `-3.13` / `+6.31 ms` | `-3.1%` / `+3.3%` |
| **E2E `C-03` READ p50** | `1 651.56 ms` | **`1 234.93 ms`** | `-416.63 ms` | **`-25.2%`** |
| **E2E `C-03` READ p95** | `2 262.15 ms` | **`1 549.65 ms`** | `-712.50 ms` | **`-31.5%`** |
| **E2E `Balance` READ p50 / p95** | `1 613.75` / `2 230.99 ms` | `1 179.53` / `1 536.51 ms` | `-434.2` / `-694.5 ms` | `-26.9%` / `-31.1%` |
| **CPU `business-read-worker` (avg / max)** | `97.2%` / `205.5%` | **`85.7%` / `147.9%`** | `-11.5` / `-57.6` pts | `-11.8%` CPU malgré `+20.4%` débit |
| **CPU `postgres` (avg / max)** | `132.5%` / `195.0%` | `152.9%` / `174.2%` | `+20.4` / `-20.8` pts | À `+20.4%` débit |
| **Convergence READ (`reconciliationComplete`)** | `True` (`0` violation) | **`True` (`0` violation)** | — | — |

Décomposition Micrometer post-optimisation (`L7-4A2/restore-large-1`, `n=64` événements `PotVersion`) :
- `phase="prepare"` : `37.699 s / 64 = 589.0 ms` (vs `778.5 ms` avant, **`-189.5 ms`**)
- `phase="finalize"` : `27.734 s / 64 = 433.3 ms` (vs `665.9 ms` avant, **`-232.6 ms`**)

### 5.2 Pot Moyen (`med-1`, `20/200`, `5` pots, `1.0 cmd/s`, `n=60`)

| Métrique | L7.4-A (`med-1`) | L7.4-A2 (`med-1`) | Gain absolu | Gain relatif |
|---|---:|---:|---:|---:|
| **Verdict / Offres** | `PASS` (`60/60`, `0` drop) | **`PASS` (`60/60`, `0` drop)** | — | — |
| **PotVersion p50 / p95 / p99** | `39.73` / `73.65` / `131.81 ms` | **`25.31` / `33.69` / `41.31 ms`** | `-14.42` / `-39.96` / `-90.50 ms` | **`-36.3%`** / **`-54.3%`** / **`-68.7%`** |
| **PotBalance p50 / p95** | `8.10` / `17.29 ms` | `8.53` / `14.86 ms` | `+0.43` / `-2.43 ms` | `+5.3%` / `-14.1%` |
| **E2E `C-03` READ p50 / p95** | `125.11` / `204.01 ms` | **`109.84` / `153.35 ms`** | `-15.27` / `-50.66 ms` | **`-12.2%`** / **`-24.8%`** |
| **Mem max `business-read-worker`** | `46.3%` | **`24.6%`** | `-21.7` pts | **`-46.9%`** |

*(Confirmation complémentaire sur `restore-smoke-medium`, `n=10` : `PotVersion` p50 `49.05 ms` → `32.92 ms` (`-32.9%`), `C-03` p50 `199.32 ms` → `135.85 ms` (`-31.8%`).)*

---

## 6. Gains mesurés (distinction des trois dimensions)

1. **Gain sur le coût de service `PotVersion`** : **`-29.5%` p50** (`-432 ms`) et **`-34.3%` p95** (`-718 ms`) sur Grand Pot ; **`-36.3%` p50** et **`-54.3%` p95** sur Pot Moyen.
2. **Gain sur la latence E2E READ (`C-03`)** : **`-25.2%` p50** (`-417 ms`) et **`-31.5%` p95** (`-713 ms`) sur Grand Pot (`1 549.65 ms` p95).
3. **Gain sur le débit soutenable** : sur Grand Pot `2` pots à `1.0 cmd/s`, la version optimisée soutient intégralement **`0.998 cmd/s` avec `0` offre abandonnée (`PASS`)**, là où la baseline saturait à `0.829 cmd/s` avec `11` offres abandonnées (`MEASUREMENT_INVALID`).

---

## 7. Limites et réserves

1. **Le coût résiduel de `MaterializePotVersion` reste à `1 031.5 ms` p50 sur Grand Pot** (`589 ms` en `prepare` + `433 ms` en `finalize`). L'analyse du code montre que ce coût résiduel provient désormais de :
   - `JdbcPotVersionSource.composition()` (relecture de `2 000` dépenses + `100` participants dans `v3_write`) + `25` allers-retours `existingKeys` en `prepare` ;
   - `JdbcPotVersionStore.insertAndAdvance()` et `requireStored()` (encodage + `3×` décodage JSON de `PotVersion` à `2 100` éléments) + `25` allers-retours `requireKeysPresent` en `finalize`.
2. **Conditions mono-hôte Docker** : les mesures restent locales (non contractuelles pour la certification Config A/B multi-nœuds).

---

## 8. Impact sur les exigences SPEC-09

Sur Grand Pot à `1.0 cmd/s` en topologie locale, le p95 E2E `C-03` passe de `2 262.15 ms` (au-dessus de `2 000 ms`) à **`1 549.65 ms`** (sous le seuil indicatif `2 000 ms` Config A) avec **`0` offre abandonnée**. Sur Pot Moyen, le p95 E2E `C-03` passe de `204.01 ms` à **`153.35 ms`**.

---

## 9. Recommandation pour la suite

- **Verdict** : **`L7_4_A2_OPTIMIZATION_QUALIFIED`** — l'optimisation est fonctionnellement équivalente, tous les tests de la slice `PROJECTION` passent, et les gains sur `PotVersion`, l'E2E READ et le débit soutenable sont reproductibles.
- **Prochaine étape recommandée (après validation humaine et fusion)** : si un gain supplémentaire est recherché sur Grand Pot (`1 031 ms` → `< 300 ms`), investiguer les deux postes résiduels identifiés (§7.1) : suppression des relectures/décodages redondants de `PotVersionJson` dans `JdbcPotVersionStore` (`insertAndAdvance` / `requireStored`) et optimisation de `JdbcPotVersionSource.composition`, avant d'engager `MaterializePotBalance` ou L7.4-B.
