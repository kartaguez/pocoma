# Pocoma V3 — L7-C4 — Rapport de Diagnostic (Phase 1) et Dossier de Décision Gate `G-C4-1`

> **Statut** : Phase 1 exécutée — **En attente de décision humaine sur la Gate `G-C4-1`**.  
> **Non-certification** : Toutes les mesures sont attribuées à l'environnement local de diagnostic (Docker Desktop sur Apple M4, 10 vCPU / ~7,75 GiB RAM Docker). Elles ne constituent pas une qualification sur infrastructure cible (`C4-R12`).

---

## 1. Provenance, environnement et validité expérimentale

### 1.1 Identité du code, des binaires et des images

| Élément | Valeur observée |
| :--- | :--- |
| **Branche Git** | `feat/v3-l7` |
| **Commit de référence** | `4a355cdccd7bc78f5069ed8623ce476d09a66a04` (`docs(l7-c4): consolidate C4 diagnostic plan, protocol, traceability and G-C4-1 template`) |
| **Binaires Java** | `runtime-web-write`, `runtime-web-read`, `runtime-worker-process`, `runtime-worker-materialize` (`0.0.1-SNAPSHOT-exec.jar`), compilés sans modification du code métier/SQL/runtime |
| **Image PostgreSQL** | `postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7` (`postgres:17-alpine`) |
| **Image JVM** | `eclipse-temurin@sha256:66bb900643426ad01996d25bada7d56751913f9cec3b827fcb715d2ec9a0fbfc` (`eclipse-temurin:21.0.8_9-jre`) |
| **Preuves agrégées** | [phase1-summary.json](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-C4/phase1/phase1-summary.json), [manifest.sha256](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-C4/phase1/manifest.sha256) |
| **Harnais utilisé** | [run-l7-c4-diagnostic.py](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/run-l7-c4-diagnostic.py), [c4_metrics.py](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/c4_metrics.py) |

### 1.2 Laboratoire local, budget matériel et contrôle de la charge parasite

* **Hôte** : macOS Apple M4, 10 CPUs, Docker Desktop `29.9.2` (`aarch64`), `MemTotal = 8 319 504 384` octets (~7,75 GiB alloués à la VM Docker).
* **Budget matériel de référence (topologie 5 runtimes + PostgreSQL)** :
  * `postgres` : `2.0` CPUs, `2048m` RAM, `tmpfs 1536m`
  * `http-write` : `0.5` CPU, `512m` RAM
  * `http-read` : `0.5` CPU, `768m` RAM
  * `command-worker` : `0.5` CPU, `512m` RAM
  * `results-worker` : `0.5` CPU, `512m` RAM
  * `business-read-worker` : `2.0` CPUs, `1024m` RAM
  * **Total** : `6.0` CPUs limités, `5376m` RAM limités (options JVM : `-Xms64m -Xmx512m -XX:+UseSerialGC`).
* **Contrôle des interférences** :
  * Chaque exécution E2E utilise des conteneurs jetables `pocoma-c4-*` isolés (`tmpfs` pour PostgreSQL) ; aucun conteneur tiers n'a été arrêté ou modifié.
  * `pg_stat_statements` a été installé uniquement dans la base de maintenance `pocoma` (statistiques cluster-wide filtrées par `datname`), afin de ne pas altérer les bases `pocoma_write` / `pocoma_read` ni déclencher les gardes d'autorité SQL au démarrage.

---

## 2. Synthèse des mesures de bout en bout (Niveau 1 — `C4-R2`)

Tous les scénarios E2E (`14` scénarios, `37` runs `PASS` au total) ont validé `read_availability_valid() = true` sans violation d'invariant.

| Scénario | Runs (`PASS`) | Débit observé | Admission `p50` | `resultE2e` `p50` | `C-03` (`c03E2e` / `bind`) `p50` | `C-03` `p95` | `Postgres` CPU moy. / thr. | `Workers` CPU moy. (cmd / res / biz) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`c1-idle`** (`0.33 cmd/s`) | 3 | `0.36 /s` | `4.78 ms` | `73.28 ms` | `101.57 ms` | `150.86 ms` | `0.64` / `0.00 s` | `0.20` / `0.19` / `0.33` |
| **`c1-med-1`** (`1 cmd/s`, 200 exp.) | 3 | `1.01 /s` | `6.60 ms` | `75.94 ms` | `109.96 ms` | `146.19 ms` | `0.73` / `0.00 s` | `0.26` / `0.25` / `0.42` |
| **`c1-large-1`** (`1 cmd/s`, 2000 exp.) | 3 | `1.03 /s` | `7.96 ms` | `101.00 ms` | `145.63 ms` | `331.55 ms` | `0.78` / `0.00 s` | `0.32` / `0.30` / `0.48` |
| **`c1-id-1`** (`1 reg/s`) | 3 | `1.0 /s` | `6.99 ms` | `75.92 ms` | `87.52 ms` (bind) | `194.92 ms` | `0.72` / `0.00 s` | `0.28` / `0.28` / `0.40` |
| **`c1-delete-1`** (`10` suppressions) | 3 | — | — | `124.61 ms` | `173.04 ms` (del) | `334.49 ms` | `0.97` / `0.00 s` | `0.49` / `0.47` / `0.86` |
| **`c2-med-10`** (`10 cmd/s`, 50 pots) | 3 | `10.00 /s` | `6.34 ms` | `81.25 ms` | `113.42 ms` | `251.58 ms` | `1.35` / `0.04 s` | `0.50` / `0.47` / `0.76` |
| **`c2-ramp`** (`1..50 cmd/s`, 50 pots) | 2 | **Plateau `27–28 /s`** | `5.37–8.86 ms` | `74.5–150.4 ms` | `105.3–446.4 ms` | `127–2179 ms` | `1.53` / `27.35 s` | `0.43` / `0.41` / `0.66` |
| **`c2-med-50`** (`50 cmd/s`, 50 pots)* | 3 | `13.37 /s` | `8.50 ms` | `251.69 ms` | `344.59 ms` | `3165.68 ms` | `1.24` / `0.02 s` | `0.44` / `0.43` / `0.78` |
| **`c2-contention-1pot`** (`6×20` cmds) | 3 | — | — | — | — | — | `1.01` / `0.03 s` | `0.34` / `0.30` / `0.49` |
| **`c3-int-pot-vs-id`** (`20 cmd/s` + `1 id/s`) | 3 | — | — | `198.0 ms` (id) | `298.66 ms` (id bind) | `1900.54 ms` (id) | `1.50` / `0.10 s` | `0.48` / `0.48` / `0.81` |
| **`c3-int-inv-vs-pot`** (`10 inv/s` + `5 cmd/s`) | 3 | `150/150` inv | — | — | `142.66 ms` (pot) | `349.00 ms` (pot) | `1.06` / `0.00 s` | `0.38` / `0.36` / `0.56` |
| **`c3-int-d5-vs-med`** (`3/s` large + `1/s` med + `0.5/s` id) | 2 | — | — | — | `151.17 ms` (med) / `104.62 ms` (id) | `160.25 ms` (med) / `300.42 ms` (id) | `0.99` / `0.01 s` | `0.30` / `0.28` / `0.46` |
| **`c4-backlog-read-only`** (`300` cmds + `1/s`) | 3 | `30/30` conc. | — | — | `108.57 ms` (`33.35 s` rec.) | `151.69 ms` | `0.57` / `0.00 s` | `0.25` / `0.25` / `0.05` |
| **`c4-backlog-10k`** (`10k CreatePot` + `0.2 id/s`) | 1 | `~55.6 /s` drain | — | — | `170.17 ms` (id bind) | `1062.99 ms` (id) | `1.17` / `164.64 s` | `0.23` / `0.21` / `0.38` |

*\*Note méthodologique sur `c2-med-50` et `c2-ramp`* : à `35–50 cmd/s`, le générateur client ouvert (`32` threads, `max_lag_ms=150..200`, polling HTTP toutes les `25 ms`) sature le conteneur `http-read` (`0.5` CPU, `33.66–44.28 s` de throttling cgroup), provoquant des `DROPPED_OFFER`. Sans polling HTTP client (`c4-backlog-10k`), le pipeline de workers draine `10 000 CreatePot` (`50 000` consommations) en `179.98 s` (~**`55.6 cmd/s`**).

---

## 3. Réexamen de la réserve de performance C3 (§5.4 — Campagne `C4-P1-C5`)

### 3.1 Débit mixte sous concurrence (`C5-SQL-C3`, code C3 final `ReadParticipants.select` vs `ReadParticipants.create`)

Les campagnes historiques C3 utilisaient un compteur expérimental (`c3_bench_counter`) et `summarize-cost.py` attendait `3` statements JDBC supplémentaires par effet. La mesure C4 a comparé directement **`ReadParticipants.create(..., false)` (V4/OFF)** contre **`ReadParticipants.select(..., true)` (V5/ON, suffixe CTE livré)** sur `R = 3` exécutions appariées (`100` échantillons/famille à `1` worker, `300` échantillons `MIXED` à `4` workers) et `1` exécution `pg_walinspect` à `1` worker :

| Profil (`n`) | Workers | V4/OFF Mur (`ms`) | V5/ON Mur (`ms`) | Δ Mur | V4/OFF WAL (`B`) | V5/ON WAL (`B`) | Δ WAL | V4/OFF `pg_cpu` (`ms`) | V5/ON `pg_cpu` (`ms`) | Δ `pg_cpu` |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`COMMAND_RESULT`** (`100`) | 1 | `1870.93` | `2213.52` | `+18.3 %` | `105 984` | `161 040` | `+51.9 %` (`+551 B/op`) | `1391.76` | `1754.98` | `+26.1 %` |
| **`IDENTITY_REQUEST_RESULT`** (`100`) | 1 | `1866.67` | `2285.28` | `+22.4 %` | `104 728` | `163 984` | `+56.6 %` (`+593 B/op`) | `1440.62` | `1716.61` | `+19.2 %` |
| **`POT_VERSION`** (`100`) | 1 | `4110.74` | `4393.42` | `+6.9 %` | `211 912` | `318 656` | `+50.4 %` (`+1067 B/op`) | `2943.47` | `3180.35` | `+8.0 %` |
| **`POT_BALANCE`** (`100`) | 1 | `2940.16` | `3287.63` | `+11.8 %` | `145 056` | `214 328` | `+47.8 %` (`+693 B/op`) | `2088.62` | `2407.90` | `+15.3 %` |
| **`MIXED`** (`300`) | **4** | `4180.17` | `5006.61` | **`+19.8 %`** | `468 512` | `714 128` | **`+52.4 %`** | `10 639.45` | `13 131.18` | **`+23.4 %`** |

* **Statements JDBC ajoutés** : exactement **`+2` statements/effet** (`1000 -> 1200` sur `COMMAND_RESULT`, `1400 -> 1600` sur `POT_BALANCE`, `1700 -> 2100` sur `POT_VERSION` qui publie 2 familles, `4100 -> 4900` sur `MIXED`), et non `+3`.
* **Contention & Verrous (`waits.tsv`)** : `0` deadlock, `0` rollback. Les attentes PostgreSQL observées au `COMMIT` sont dominées par `IO:WalSync` / `IO:WalWrite`, sans sérialisation bloquante sur `read_availability_counter`.
* **Coût unitaire SQL réel** : `+0.48` à `+0.62 ms` de temps SQL READ par tentative. **La réserve historique C3 est donc requalifiée** : sur le code réel V5 CTE, le surcoût reste réel en WAL (`+52 %`) et CPU PostgreSQL (`+15 %` à `+26 %`), mais reste contenu (~`+20 %` en `MIXED` à 4 workers) et ne constitue pas le goulot principal observé en E2E.

### 3.2 Coût WAL `PotBalance` XLARGE (`C5-XLARGE-C3`, `500` shareholders, `100` SitD + `50` SitA)

* **Situation D (`100` versions financières)** :
  * `C2` (V4/OFF) : `4 443 B/ver` WAL, écriture `p50 = 2.86–4.33 ms`.
  * `C3` (V5/ON) : **`4 984 B/ver`** WAL (**`+541 B/ver`**, `+12.2 %`), écriture `p50 = 3.84–4.05 ms` (`+172 032 B` au total pour `read_availability_*` sur 150 événements).
* **Situation A (`50` versions identiques)** : `690 B/ver` (V4) → `1 240 B/ver` (V5, `+550 B/ver`).
* **Profondeur `L1-F2` (`with recursive manifest`)** : mesurée en runtime via `pg_stat_statements` : `0.155 ms` en moyenne sur pots MEDIUM (`202` versions, `c1-med-1`) contre `1.123–1.724 ms` sur pots LARGE (`2 002` versions, `c1-large-1`). Sensibilité réelle à la profondeur (~×8–11), mais marginale devant les autres coûts (< 2 ms).

---

## 4. Analyse des facteurs limitants internes (Niveau 2 — `C4-R2`)

### 4.1 Décomposition et constat majeur sur le bruit de fond PostgreSQL

L'observation conjointe des métriques cgroup, Micrometer et `pg_stat_statements` met en évidence **trois facteurs limitants dominants** :

1. **Bruit de fond continu du polling sur 36 partitions (même à idle !)** :
   * En `c1-idle` (`0.36 cmd/s`, `88 s`), PostgreSQL traite **`560 887` transactions `pocoma_write`** et **`676 155` transactions `pocoma_read`** (~`14 000 tx/s` cumulées !), consommant **`0.64` CPU sur PostgreSQL** et **`0.72` CPU cumulés sur les 3 workers** (`~1.37` CPU au total à vide).
   * Top requêtes `pg_stat_statements` en `c1-med-1` (`70 s`, 60 commandes) :
     * `select set_config('lock_timeout', $1, true), set_config('statement_timeout', $2, ...)` : `116 071` appels (`pocoma_read`) + `39 248` (`pocoma_write`).
     * `select position from discovery_checkpoint ...` : `81 395` appels (`pocoma_read`) + `30 162` (`pocoma_write`).
     * `select family,routing_version,partition_count from consumption_routing_contract ...` : `65 578` appels (`pocoma_read`) + `21 670` (`pocoma_write`).
     * `select owner_token,owned_until from partition_ownership ...` : `65 579` appels (`pocoma_read`) + `21 670` (`pocoma_write`).
     * `select consumable_id from consumption_slot ...` : `52 288` appels (`pocoma_read`).
   * *Causalité démontrée* : chaque boucle Discovery/Consumption sur `9` consommations × `4` partitions = `36` partitions exécute des milliers de courtes transactions par seconde (et des `set_config` par transaction), saturant la capacité de `command-worker` et `results-worker` dès `10–20 cmd/s`.

2. **Sous-dimensionnement CPU asymétrique de `command-worker`, `results-worker` et `http-read` (`0.5` CPU)** :
   * En `c2-ramp` (`111 s`), `command-worker` (`0.5` CPU) subit **`28.19 s` de throttling cgroup** (`643` fois) tout en affichant `sat_worker=0, sat_consumption=0, sat_executor=0` dans Micrometer : ce ne sont pas les sémaphores internes qui saturent, mais **le quota CPU de `0.5` cœur**, dont `~0.20` CPU est déjà consommé par le polling à vide.
   * Symétriquement, `business-read-worker` dispose de `2.0` CPUs mais n'en utilise que `~0.66–0.81` (`0.09 s` de throttling) — il déclenche en revanche des saturations internes (`PotVisibility`: `sat_worker=330, sat_executor=202`; `PotVersion`: `sat_worker=14, sat_consumption=43`) dans `c2-ramp`.
   * Sous polling HTTP client intensif (`25 ms`), `http-read` (`0.5` CPU) subit **`44.28 s` de throttling** en `c2-ramp` et **`63.21 s`** en `c3-int-pot-vs-id`.

3. **Interférence sévère entre familles partageant `command-worker` et `results-worker` (`c3-int-pot-vs-id`, `c4-backlog-10k`)** :
   * En `c1-id-1` (nominal), `IdentityRequest` atteint `usableBindingE2eMs` `p50 = 87.52 ms`, `p95 = 194.92 ms`.
   * Sous `20 cmd/s` de trafic pot (`c3-int-pot-vs-id`), `usableBindingE2eMs` grimpe à **`p50 = 298.66 ms` (`×3.4`)**, **`p95 = 1 900.54 ms` (`×9.7`)**, tandis que `command-worker` est throttlé `30.37 s` et `results-worker` `13.83 s`.
   * Sous backlog de `10 000 CreatePot` (`c4-backlog-10k`), `IdentityRequest` reste isolé au niveau des partitions (`ConsumableFamily` distincte) et aboutit en `170.17 ms` (`p50`) sans attendre les 180 s du backlog, mais sa queue `p95` monte à **`1 062.99 ms`** à cause de la contention CPU sur `command-worker`/`results-worker`/`postgres`.

### 4.2 Attribution causale entre les 4 axes

| Axe | Part causale démontrée | Preuves |
| :--- | :--- | :--- |
| **Ordonnanceur / Polling** | **Dominante à faible/moyenne charge** | `~14 000 tx/s` et `~1.37` CPU à idle (`c1-idle`), `81k+` requêtes `discovery_checkpoint` en `70 s` (`c1-med-1`) |
| **Topologie / Quotas CPU** | **Dominante en saturation (`≥ 15–20 cmd/s`) et interférences** | Throttling massif sur `command-worker` (`28–30 s`), `http-read` (`44–63 s`), `results-worker` (`9–14 s`) à `0.5` CPU alors que `business-read-worker` (`2.0` CPU) est à `< 40 %` d'utilisation |
| **PostgreSQL** | **Secondaire à `1–20 cmd/s`, co-limitante à `≥ 25 cmd/s` / backlog** | `0` deadlock, `WalSync` sur `COMMIT`, throttling `postgres` (`2.0` CPU) de `27.35 s` en `c2-ramp` et `164.64 s` en `c4-backlog-10k` largement amplifié par les `14k tx/s` de polling |
| **Partitions (`4/famille`)** | **Non limitante en multi-pots, isolante pour `IdentityRequest`** | `c4-backlog-10k` draine à `~55.6 cmd/s` sans HTTP polling ; `IdentityRequest` traverse un backlog de `10k` `CreatePot` en `170 ms` p50 |

### 4.3 Verdict sur les hypothèses initiales (`H-C4-01` à `H-C4-07`)

| Hypothèse | Statut Phase 1 | Élément clé |
| :--- | :--- | :--- |
| `H-C4-01` (Coût C3 dans `command-worker`/`results-worker`/`READ`) | **Nuancée / Requalifiée** | Le surcoût réel V5 CTE est de `+15–26 %` CPU PG et `+52 %` WAL, mais le goulot E2E principal vient du polling et des quotas `0.5` CPU |
| `H-C4-02` (Interférences entre familles) | **Confirmée** | `IdentityRequest` `p95` multiplié par `~9.7×` sous charge pot (`194.9 -> 1900.5 ms`) |
| `H-C4-03` (Regroupement/isolation par criticité SPEC-09) | **Plausible (à tester en Phase 2)** | Isoler les ressources CPU/runtime des parcours critiques (`IdentityRequest`, `CommandResult`) ou rééquilibrer les quotas `0.5` vs `2.0` CPU |
| `H-C4-04` (Contention `Command` / `PotBalance`) | **Partiellement confirmée** | `PotVisibility` / `PotVersion` saturent les sémaphores de `business-read-worker` en rampe, `Command` sature d'abord par quota CPU (`0.5`) |
| `H-C4-05` (Scaling vertical vs horizontal) | **À trancher en Phase 2** | Le rééquilibrage CPU à budget constant (`6.0` CPUs) n'a pas encore été testé |
| `H-C4-06` (Backlog vs arrivées temps réel) | **Confirmée** | Le backlog `CreatePot` ne bloque pas `IdentityRequest` logiquement mais dégrade sa `p95` (`1063 ms`) par contention CPU |
| `H-C4-07` (Bruit de fond polling / `discovery_checkpoint`) | **Fortement confirmée (facteur n°1 à vide)** | `~1.37` CPU et `~14 000 tx/s` à idle sur 36 partitions |

---

## 5. Optimisations candidates pour la Phase 2, alternatives écartées et recommandation

### 5.1 Optimisations candidates soumises à décision

1. **`OPT-1` — Rééquilibrage statique du budget CPU / topologie à ressources constantes (`6.0` CPUs)** (`C4-R4`, `C4-R6`) :
   * Réallouer les quotas CPU (ex. réduire `business-read-worker` de `2.0` à `1.0` CPU pour doter `command-worker`, `results-worker` et `http-read`, ou tester un regroupement `critical-worker` dédié à `IdentityRequest` + `IdentityRequestResult` + `CommandResult` conformément à `C4-R3`/`C4-R5`).
2. **`OPT-2` — Réduction du coût du polling à vide (`discovery_checkpoint` / `routing_contract` / `ownership`)** :
   * Mettre en cache les métadonnées quasi-statiques (`consumption_routing_contract`) ou ajuster l'intervalle/backoff de polling à vide et supprimer les `set_config` redondants par transaction de polling vide, afin de libérer jusqu'à `~1` CPU sur PostgreSQL + workers sans changer le modèle d'ordonnancement (`C4-R9`).
3. **`OPT-3` — Ajustement des paramètres de concurrence interne sur `business-read-worker`** :
   * Ajuster les limites `worker`/`executor` sur `PotVisibility` et `PotVersion` (seules consommations ayant montré `sat_worker > 0` et `sat_executor > 0` en `c2-ramp`).

### 5.2 Alternatives explicitement écartées à l'issue de la Phase 1

* **Suppression ou refonte du suffixe CTE C3** : non justifiée au vu de son coût réel modéré (`+0.5 ms` SQL/tentative, `0` deadlock) comparé au gain potentiel d'`OPT-1` et `OPT-2`.
* **Pilotage dynamique des ressources ou repartitionnement dynamique** : hors cadre ratifié (`C4-R4`, `C4-R9`).

### 5.3 Ordre d'exécution priorisé recommandé pour la Phase 2

1. **`OPT-1`** (test A/B de topologie et rééquilibrage CPU à budget constant `6.0` CPUs — sans modification applicative, uniquement configuration/topologie).
2. **`OPT-2`** (réduction du bruit de fond de polling `~14 000 tx/s` à vide).
3. **`OPT-3`** (ajustement ciblé des capacités de `PotVisibility` / `PotVersion` si encore saturées après `OPT-1` + `OPT-2`).

---

## 6. Formulaire d'arbitrage humain — Gate `G-C4-1`

- [ ] **Option A** : Autoriser la Phase 2 sur le périmètre `OPT-1` uniquement (rééquilibrage topologique / quotas CPU à budget constant).
- [ ] **Option B** : Autoriser la Phase 2 sur le périmètre `OPT-1` + `OPT-2` (+ `OPT-3` si requis par les mesures).
- [ ] **Option C** : Clore L7-C4 sans Phase 2 d'optimisation (l'état C3 actuel est jugé suffisant).

*Décision et commentaires humains :* `____________________`
