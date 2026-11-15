# L7-LB/S5 — Qualification finale de la distribution locale et garanties consolidées

- **Mandat** : `GO_L7_LB_S5_FINAL_DISTRIBUTION_QUALIFICATION` (complété par [`L7_LB_S6_Operational_Hardening_Report.md`](L7_LB_S6_Operational_Hardening_Report.md) sous `GO_L7_LB_S6_OPERATIONAL_HARDENING`)
- **Date de qualification** : 15 novembre 2026
- **Branche / SHA de référence S5** : `feat/l7-lb-local-playground` @ `5009d03a20ea781f1aa789ebd089a2174d06cecd` (code source, SQL baselines, `.properties` Docker et scripts CLI strictement inchangés en S5 ; durcissements ciblés S6 appliqués ensuite sur base `f9c0ef64` sans modification des invariants métier ni des baselines SQL)
- **Instance de qualification dédiée** : `l7lb-s5` (`owner=b8d3620d-8f07-4ed7-8371-4630411dffa7`, projet Compose `pocoma-lb-l7lb-s5-b8d3620d`, ports `55574..55583`), exécutée depuis un clone propre isolé puis arrêtée et détruite après collecte complète des preuves
- **Instance protégée `demo`** : intouchée (`pocoma-lb-demo-33dd8c1d`, `490` fichiers, `9` conteneurs arrêtés et volume `pocoma-lb-demo-33dd8c1d-data` vérifiés byte à byte entre `demo-integrity-before.json` et `demo-integrity-after.json`)

## Verdicts

| Phase / Objet | Verdict | Synthèse factuelle |
| :--- | :--- | :--- |
| **S5-A — Installation & fonctionnement** | **`PASS`** | Clone vierge reproductible, `40/40` contrôles prérequis `PASS`, bootstrap `l7lb-s5` sans SQL manuel en `23.937 s`, `20/20` checks `READY`, `36/36` partitions actives, `236/236` tests Bruno `PASS`, arrêt/redémarrage sans perte ni mutation de secrets (`0o600`), canari `v4 -> v5` + commande E2E `0.139 s`. |
| **S5-B — Résilience & gestion des pannes** | **`PASS_WITH_RESERVATIONS`** | Porte B franchie (`B2..B6`) : zéro perte de commande admise, zéro double effet métier, fencing `PASS` (`100 %` de rotation `owner_token` après expiration des baux). Réserves documentées : topologie mono-worker (reprise après redémarrage, non failover multi-nœuds), écart `/actuator/health/readiness` vs `/actuator/health` sur workers en état `draining`, et verrouillage fail-closed d'admission `http-write` (`capacity=1`) après admission `UNRESOLVED` sur coupure PG. |
| **S5-C — Performances & limites opérationnelles** | **`PASS`** | Enveloppe reproductible mesurée sur `P0` (`1 cmd/s`), `P1` (`10 cmd/s`), `P2` (`25 cmd/s` soutenus, `0` erreur) et `P3` (`50 cmd/s` demandés -> saturation d'admission à `41.37 cmd/s`, `1036` rejets fail-fast `503`, drainage `0.884 s`), `P4` ignoré par garde `C2`, plus balayage READ `Q=8` jusqu'à `1390.13 req/s` (`0` HTTP `503`). Cumul `8417` commandes sans perte ni quarantaine. |
| **Verdict global S5** | **`L7_LB_S5_QUALIFIED_WITH_RESERVATIONS`** | La distribution locale démontre des garanties fortes de sûreté CQRS, d'idempotence, de fencing et de reproductibilité d'installation, avec des limites d'exploitation et d'auto-guérison clairement bornées et documentées. |
| **Durcissement S6** | **`L7_LB_S6_QUALIFIED_WITH_RESERVATIONS`** | Voir [`L7_LB_S6_Operational_Hardening_Report.md`](L7_LB_S6_Operational_Hardening_Report.md) : récupération PG par `./pocoma-local start` (`23,031 s`, `0` intervention Docker), `admission.capacity=4` effectif (`0` `503` à `50 cmd/s`, enveloppe WRITE maintenue à `25 cmd/s`), séparation `operationalState` / `qualificationState`, rejet `--dry-run` hors `reset`/`destroy`. |
| **Clôture `L7-LB`** | **Clôture recommandée (`L7_LB_CLOSURE_RECOMMENDED`)** | Critères de sortie S5 + S6 satisfaits sous les bornes documentées. |

---

## 1. Configuration de référence et enveloppe machine (Préparation)

Manifeste complet : [`docs/testing/evidence/L7-LB-S5/00-configuration-manifest.json`](../testing/evidence/L7-LB-S5/00-configuration-manifest.json).

### 1.1 Hôte, Docker et chaîne d'outils

| Composant | Valeur observée |
| :--- | :--- |
| **OS hôte** | macOS `27.0` (`BuildVersion 26A428`, `Darwin ARM64_T8132`) |
| **CPU / RAM hôte** | `10` cœurs logiques/physiques, `16 GiB` RAM (`17179869184` octets) |
| **Docker Engine / Desktop** | `29.9.2` (`aarch64`, `NCPU=10`, `MemTotal=8319504384` octets ≈ `7.75 GiB` alloués à la VM) |
| **Java / Maven** | `Temurin-21+35` (`openjdk version "21"`), `Apache Maven 3.9.14` |
| **Python / Node / npm / k6** | `Python 3.14.7`, `Node v24.15.0`, `npm 12.0.2`, `k6 v1.7.1`, `Git 2.54.0` |

### 1.2 Images de conteneurs et limites de ressources

Total des limites déclarées dans Compose : **`5760 MiB` RAM** (`5.625 GiB`) et **`9.0` CPUs** (compatibles avec la VM Docker de `7.75 GiB` / `10` vCPUs, sous contrainte de disque décrite en §1.4).

| Service | Image & Digest SHA-256 | Limite RAM | Limite CPU | Paramètres applicatifs notables |
| :--- | :--- | :---: | :---: | :--- |
| `postgres` | `postgres:17.6-alpine`<br>`sha256:ef257d85...eee94` | `1024m` | `1.5` | Bases partagées `pocoma_write` (`v3_write`) & `pocoma_read` (`v3_read`) |
| `keycloak` | `quay.io/keycloak/keycloak:26.7.0`<br>`sha256:0f198be2...76b13` | `896m` | `1.5` | `-Xms128m -Xmx512m`, royaume `pocoma`, client `pocoma-functional-tests` (PKCE `S256`, `directAccessGrantsEnabled=false`) |
| `http-write` | `eclipse-temurin:21.0.8_9-jre`<br>`sha256:66bb9006...0fbfc` | `640m` | `1.0` | `http-write.properties` définit `pocoma.v3.http.admission.maximum=4` / `minimum-idle=1` (clés non lues par `AdmissionHttpConfiguration`), sans surcharger `pocoma.v3.http.admission.capacity` → défaut Java **`1`** (sémaphore d'admission et pool Hikari `v3-http-admission` à `maximumPoolSize=1`) |
| `http-read` | `eclipse-temurin:21.0.8_9-jre`<br>`sha256:66bb9006...0fbfc` | `640m` | `1.0` | **`Q=8`** (`http-permits=8`, `sql-permits=8`, `maximum-pool-size=8`, `minimum-idle=8`), utilisateur `pocoma_read_http`, maintenance `pocoma_read_status` |
| `command-worker` | `eclipse-temurin:21.0.8_9-jre`<br>`sha256:66bb9006...0fbfc` | `640m` | `1.0` | `8` partitions (`Command=0..3`, `IdentityRequest=0..3`), `batch-size=32`, `lease=30000 ms`, `renewal=10000 ms`, `claim=60000 ms`, `min-poll=2 ms`, `max-poll=20 ms`, `error-poll=50 ms` |
| `results-worker` | `eclipse-temurin:21.0.8_9-jre`<br>`sha256:66bb9006...0fbfc` | `640m` | `1.0` | `8` partitions (`MaterializeCommandResult=0..3`, `MaterializeIdentityRequestResult=0..3`), `lease=30000 ms`, `renewal=10000 ms` |
| `business-read-worker` | `eclipse-temurin:21.0.8_9-jre`<br>`sha256:66bb9006...0fbfc` | `640m` | `1.0` | `20` partitions (`MaterializePotVersion`, `PotBalance`, `PotVisibility`, `CurrentBinding`, `Invitation` × `0..3`), `lease=30000 ms`, `renewal=10000 ms` |
| `prometheus` | `prom/prometheus:v3.5.0`<br>`sha256:63805ebb...4996` | `256m` | `0.5` | `scrape_interval=2s` |
| `grafana` | `grafana/grafana:12.3.2`<br>`sha256:ba93c9d1...2bfb` | `384m` | `0.5` | Dashboard `Pocoma runtime` provisionné |

### 1.3 Évaluation ressource et gestion de l'espace disque hôte

- **CPU / RAM** : adéquats pour `S5-C` (`7.75 GiB` VM vs `5.625 GiB` somme des plafonds conteneurs ; pic observé en `P3` ≤ `497.3 MiB` sur Keycloak et `≤ 245.1 MiB` par JVM applicative, sans aucun `OOMKilled`).
- **Disque hôte (`/dev/disk3s5`, `228 GiB`)** : contrainte critique observée. L'espace libre non purgeable initial était de `~4.5 GiB` avant création du clone isolé (`2882192` blocs de `1 KiB` après clone dur), puis est descendu à **`255.3 MiB` (`253 MiB` `df`) après S5-A et S5-B**, soit sous le seuil bloquant `PRE-DISK` (`256 MiB`) de `./pocoma-local start`. Avant d'engager `S5-C`, une déduplication APFS copy-on-write (`cp -cR`) des sous-arborescences `docs`, `scripts`, `app` dans le clone temporaire `/private/tmp/pocoma-l7-lb-s5/source` a restitué **`637.4 MiB`** libres (clone `git status --porcelain=v1` strictement propre), permettant d'exécuter `P0..P3` et `Q=8` (`427.8 MiB` libres en fin de `S5-C`, puis `2939 MiB` après `destroy` de `l7lb-s5` et suppression du répertoire temporaire). **Aucune option système ni paramètre applicatif n'a été altéré pour influencer les débits ou latences.**

---

## 2. S5-A — Installation et fonctionnement (`Gate A = PASS`)

Preuve complète : [`docs/testing/evidence/L7-LB-S5/s5a-installation-and-functioning.json`](../testing/evidence/L7-LB-S5/s5a-installation-and-functioning.json).

| Étape | Vérifications exécutées | Résultats factuels | Statut |
| :--- | :--- | :--- | :---: |
| **A1. Clone propre** | Clone isolé dans `/private/tmp/pocoma-l7-lb-s5/source` sur `feat/l7-lb-local-playground` @ `5009d03a...` | Arbre propre (`dirty=false`), `./pocoma-local --help` (`exit 0`). *Observation CLI* : `./pocoma-local start --dry-run` ignore `--dry-run` et exécute un démarrage réel (`--dry-run` n'est actif que sur `reset`/`destroy` selon [`commands.md`](../reference/commands.md)). | `PASS` |
| **A2. Prérequis** | `python3 -B -m unittest discover -s scripts/local -p 'test_prerequisites.py' -v` + contrôles `./pocoma-local start` | `40/40` tests unitaires de prérequis passés en `0.234 s`. | `PASS` |
| **A3. Bootstrap `l7lb-s5`** | `./pocoma-local start --instance l7lb-s5` (sans aucune commande SQL manuelle) | `state=READY`, `exit=0`, durée `23.937 s`, `secrets.json` en mode `0o600`. Provisionnement automatique `pocoma_write` (`37` tables, `38` fonctions, `62` triggers) et `pocoma_read` (`14` tables, `5` fonctions, `16` triggers), empreinte `public.pocoma_local_bootstrap` = `a5514fdf8415...0fec8f`, zéro `flyway_schema_history`. | `PASS` |
| **A4. READY & partitions** | `./pocoma-local status`, Actuator readiness des 5 JVM, Prometheus `pocoma_v3_lease_local`, SQL `partition_ownership` | `9/9` conteneurs UP (`restartCount=0`), `20/20` checks `PASS`, `businessEvidence=PROVEN`, `proofFreshness=FRESH`. Couverture complète des **`36/36` partitions** (`8` WRITE + `8` RESULTS + `20` BUSINESS READ). | `PASS` |
| **A5. Fonctionnement & Bruno** | `./pocoma-local login`, endpoints HTTP anonymes vs JWT PKCE, `./pocoma-local bruno --instance l7lb-s5` | `GET /api/v3/me/binding` sans jeton → `401` ; `login` → `GUI_CONFIGURED` ; suite Bruno → `BRUNO_PASS` (`exit=0`, `118` suites, `236` tests, `731` assertions, `221` requêtes HTTP, parcours `J01`/`J02`/`J03`, `0` failure, `0` error, `0` skipped). État SQL fin A5 : `34` commandes terminales, `0` pending, `0` quarantaine. | `PASS` |
| **A6. Arrêt & redémarrage** | `./pocoma-local stop` → inspection volume/fichiers → `./pocoma-local start` → commande E2E complète | `stop`: `4.023 s` (`exit=0`, `143` SIGTERM gracieux, `forcedKill=[]`, volume conservé). Empreintes SHA-256 des `11` fichiers immuables (`secrets.json`, `pg-password`, `compose.json`, `realm.json`, `*.properties`, etc.) identiques avant/après (`resources.json` régénéré avec `freeDiskBytes` courant). `restart`: `28.473 s` → `READY`, mêmes `34` commandes conservées, canari incrémenté `v4 -> v5` sur le même `potId`, nouvelle commande `CreatePot` E2E (`0.139 s`) confirmée côté WRITE et READ (`200`). | `PASS` |

---

## 3. S5-B — Résilience et gestion des pannes (`Gate B = PASS`)

Preuve complète : [`docs/testing/evidence/L7-LB-S5/s5b-resilience-and-fault-injection.json`](../testing/evidence/L7-LB-S5/s5b-resilience-and-fault-injection.json).

### 3.1 Cadre d'observation et portée topologique

- Chaque scénario est précédé d'un contrôle `READY` complet (`0` commande pending, `0` slot en quarantaine, `36/36` baux actifs).
- La topologie locale déploie **une instance unique de chaque runtime**. Par conséquent, `B2`, `B3` et `B4` mesurent la **reprise d'un même worker redémarré après expiration complète de son bail SQL (`30 s` TTL)**, et **non un basculement (failover) actif vers un second worker pair** (hors topologie locale).
- La durée du bail SQL (`lease-duration-ms=30000`) borne la rétention de propriété par un worker mort, mais ne constitue pas un SLA de reprise : pendant nos tests, le renouvellement groupé `renewBatch` (`10 s`) laissait ~`20–24 s` de TTL résiduel au moment du `SIGKILL`.

### 3.2 Tableau de synthèse `B7`

| Scénario | Instant panne (UTC) | Détection | Indispo avant action | Reprise composant (`/actuator/health`) | Acquisition nouveau bail (depuis restart) | Reprise traitement utile (depuis restart/unpause) | Retour `READY` CLI | Backlog max | Temps de drainage | Perte | Double effet | Fencing |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **B2. Crash `command-worker`** (`SIGKILL`) | `20:16:10.274` | `0.155 s` | `3.632 s` | `6.469 s` | `20.087 s` (`23.719 s` depuis panne) | `20.087 s` | `38.074 s` | `8` cmds `PENDING` | `20.568 s` | **Non** | **Non** | **`PASS`** (`8/8` tokens renouvelés post-TTL) |
| **B3. Crash `results-worker`** (`SIGKILL`) | `20:16:56.651` | `0.167 s` | `1.081 s` | `5.244 s` | `20.042 s` (`21.124 s` depuis panne) | `19.561 s` | `37.961 s` | `2` slots (`8` events en retard) | `20.042 s` | **Non** | **Non** | **`PASS`** (`8/8` tokens renouvelés post-TTL) |
| **B4. Crash `business-read-worker`** (`SIGKILL`) | `20:17:39.947` | `0.158 s` | `1.187 s` | `5.091 s` | `22.375 s` (`23.562 s` depuis panne) | `22.375 s` | `47.129 s` | `3` slots (`8` events en retard) | `22.873 s` | **Non** | **Non** | **`PASS`** (`20/20` tokens renouvelés post-TTL) |
| **B5a. Crash `http-write`** (`SIGKILL`) | `20:18:33.664` | `0.190 s` | — | `5.092 s` | N/A | `5.850 s` | `28.523 s` | `0` | `0.000 s` | **Non** | **Non** | Non applicable (stateless HTTP) |
| **B5b. Crash `http-read`** (`SIGKILL`) | `20:19:04.836` | `0.177 s` | — | `4.926 s` | N/A | `5.361 s` | `27.587 s` | `0` | `0.000 s` | **Non** | **Non** | Non applicable (stateless HTTP) |
| **B6. Pause réversible `postgres`** (`14.0 s`) | `20:19:37.394` | `7.606 s` (sondes `503` WRITE/READ) | `14.0 s` pause | `21.103 s` (après restart des 4 runtimes fail-closed) | Réacquisition complète (`36/36`) après restart | `26.460 s` (depuis `unpause`) | `46.925 s` (depuis `unpause`) | `0` (`commands_pending=0`) | `0.000 s` | **Non** | **Non** | **`PASS`** (drain fail-closed + expiration baux + verrou capacité admission) |

### 3.3 Observations comportementales critiques en `S5-B`

1. **Preuve fine du fencing (`B2`, `B3`, `B4`)** : avant expiration des anciens baux (`11.67–13.51 s` restants échantillonnés après redémarrage dans `B2`), la jauge locale `pocoma_v3_lease_local` reste à `0` et `0` nouveau `owner_token` n'est écrit en base. La réattribution n'intervient qu'après `min_owned_until` (`B2`: `1794773793.993 > 1794773793.965` ; `B3`: `1794773837.774 > 1794773837.370` ; `B4`: `1794773883.509 > 1794773883.201`). En `B2`, le rejeu idempotent de `8` commandes avant reprise produit `0` duplication d'événement.
2. **Découplage asynchrone (`B3` et `B4`)** : pendant l'arrêt de `results-worker` (`B3`), `command-worker` termine les `8` commandes (`commands_terminal=182`) et `business-read-worker` matérialise les projections métier (`read_pot_version=177`, pot `v4` visible en `http-read`), tandis que `read_command_result` reste à `174` jusqu'au redémarrage de `results-worker`. Inversement, pendant l'arrêt de `business-read-worker` (`B4`), `results-worker` met à disposition les résultats (`read_command_result=191`) tandis que les 4 tables de projection READ restent cohérentes sur la version antérieure `v4` sans état partiel.
3. **Statut CLI `NOT_READY` après redémarrage partiel** : après chaque restauration unitaire (`B2`–`B6`), les 20 contrôles techniques passent (`technical=AVAILABLE`), mais `./pocoma-local status` renvoie `state=NOT_READY` (`exit=1`, `generationQualified=false`) jusqu'à requalification explicite de la nouvelle génération de conteneur via `./pocoma-local start`.
4. **Pause réversible du `postgres` partagé (`B6`) — Trois mécanismes vérifiés** :
   - **Auto-guérison de `http-read`** : rejette avec `503` en `1.018 s` pendant la pause, puis évince sa connexion Hikari fermée et répond `200 OK` sans redémarrage `5 s` après `unpause`.
   - **Drain fail-closed des 3 workers vs divergence d'Actuator** : face aux timeouts SQL (`SocketTimeoutException` / `CannotCreateTransactionException`, `outcome="unknown"`), les 3 workers basculent en état `pocoma_v3_worker_state{state="draining"} = 1.0`, cessent de consommer et laissent expirer leurs baux sans exécution zombie. Leur endpoint `/actuator/health` passe en **`HTTP 503 DOWN`**, mais **`/actuator/health/readiness` reste en `HTTP 200 UP`**. Or `./pocoma-local status` interroge `/actuator/health/readiness` (`scripts/local/runtime.py:533`) et `./pocoma-local start` utilise `docker compose up -d` (qui ne redémarre pas un conteneur déjà `Up`), rendant un `docker restart` explicite nécessaire sur les workers passés en `draining`.
   - **Verrouillage de budget d'admission `http-write` (`capacity=1`)** : la sonde `POST /api/v3/commands` exécutée durant la pause échoue après timeout socket JDBC (`5.046 s`, `socketTimeout=5` s dans `AdmissionHttpConfiguration`) avec `AdmissionFailure(503)` sans terminaison physique observée (`physicalTerminationObserved()=false`). Conformément au mécanisme fail-closed de [`AdmissionController.java`](../../app/supra-web-write/src/main/java/com/kartaguez/pocoma/supra/web/write/AdmissionController.java#L61-L64) et [`AdmissionTransport.java`](../../app/supra-web-write/src/main/java/com/kartaguez/pocoma/supra/web/write/AdmissionTransport.java#L142), le jeton unique (`pocoma.v3.http.admission.capacity` par défaut à `1`) n'est pas libéré (`pocoma_v3_admission_capacity_available = 0.0`). Même après `unpause` de PostgreSQL (sans ligne partielle insérée dans `v3_command`), `http-write` reste `200 UP` sur `/actuator/health` tout en refusant toute nouvelle commande en `503 UNAVAILABLE` jusqu'à un `docker restart` explicite.

---

## 4. S5-C — Performances et limites opérationnelles

Preuve complète : [`docs/testing/evidence/L7-LB-S5/s5c-performance-and-limits.json`](../testing/evidence/L7-LB-S5/s5c-performance-and-limits.json).

### 4.1 Paliers de charge WRITE → RESULT → READ (`C2..C5`, `C7`)

Charge constituée de commandes `CreatePot` (pot neuf + binding valide + `Idempotency-Key` unique), avec corrélation des horodatages `/tmp/pocoma-stage-handoffs.jsonl` (`T0` http-write → `T1..T2` command-worker → `T4` results-worker → `max(T4)` business-read-worker sur `PotVersion`, `PotBalance`, `PotVisibility`), complétée par des sondes `GET /api/v3/pots/{id}` en arrière-plan (`0` erreur sur tous les paliers).

| Profil | Demandé / Durée | Admises (`202`) | Débit admis | Erreurs `503` | Latence HTTP WRITE p50 / p95 / p99 | Exécution utile CMD (`T1→T2`) p50 / p95 | E2E CMD (`T0→T2`) p50 / p95 / p99 | Délai RESULT (`T0→T4`) p50 / p95 / p99 | Délai visible READ (`T0→max T4`) p50 / p95 / p99 | Backlog max (`pending` / `res` / `read` slots) | Drainage post-charge | Perte / Dup / Quarantaine |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **P0** | `1 cmd/s` / `59.01 s` | `60/60` | `1.017 cmd/s` | `0` | `4.97 / 7.80 / 10.69 ms` | `5.14 / 8.71 ms` | `22.5 / 34.05 / 38.23 ms` | `49.0 / 64.2 / 74.51 ms` | `59.5 / 84.4 / 94.23 ms` | `0 / 1 / 2` | `0.135 s` | `0 / 0 / 0` |
| **P1** | `10 cmd/s` / `89.91 s` | `900/900` | `10.01 cmd/s` | `0` | `3.96 / 6.15 / 9.45 ms` | `4.99 / 7.66 ms` | `19.0 / 35.0 / 44.01 ms` | `37.0 / 59.0 / 70.0 ms` | `49.0 / 87.0 / 109.0 ms` | `1 / 1 / 3` | `0.150 s` | `0 / 0 / 0` |
| **P2** | `25 cmd/s` / `89.98 s` | `2250/2250` | `25.005 cmd/s` | `0` | `3.49 / 19.25 / 23.89 ms` | `5.35 / 19.45 ms` | `21.0 / 50.0 / 66.0 ms` | `40.0 / 80.0 / 103.51 ms` | `85.0 / 196.55 / 266.02 ms` | `2 / 2 / 6` | `0.762 s` | `0 / 0 / 0` |
| **P3** | `50 cmd/s` / `119.99 s` | `4964/6000` | `41.37 cmd/s` | `1036` | `3.47 / 28.25 / 45.42 ms` | `6.10 / 40.31 ms` | `37.0 / 96.0 / 131.74 ms` | `76.0 / 156.0 / 202.0 ms` | `259.0 / 674.7 / 1018.7 ms` | `3 / 3 / 35` | `0.884 s` | `0 / 0 / 0` |
| **P4** | `100 cmd/s` | *Non exécuté* | — | — | — | — | — | — | — | — | — | Garde conditionnelle `C2` (`P3` déjà saturé à `1036` rejets `503`) |

Sondes concurrentes `http-read` pendant la charge WRITE : `54` requêtes en `P0` (`p95=6.38 ms`, `0` err), `82` en `P1` (`p95=8.09 ms`, `0` err), `80` en `P2` (`p95=7.65 ms`, `0` err), `106` en `P3` (`p95=10.42 ms`, `0` err).

### 4.2 Évaluation dédiée HTTP READ `Q=8` (`C=4, 8, 16, 24`)

Test en rafales parallèles soutenues (`15 s` par niveau sur `32` pots matérialisés), avec échantillonnage de `pg_stat_activity` sur l'utilisateur SQL `pocoma_read_http` (`http-read.properties`: `http-permits=8`, `sql-permits=8`, `maximum-pool-size=8`) :

| Concurrence client | Requêtes totales / Durée | Débit `200 OK` | `503` / Autres erreurs | Latence `200 OK` p50 / p95 / p99 / max | Pic sessions SQL actives (`pocoma_read_http`) | Récupération post-rafale (`10` req `C=1`) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **C = 4** | `6891` / `15.005 s` | `459.25 req/s` | `0` / `0` | `2.77 / 68.34 / 74.22 / 91.29 ms` | `1` / `8` | `10/10 OK` (`1.72 ms`) |
| **C = 8** | `14912` / `15.006 s` | `993.74 req/s` | `0` / `0` | `3.03 / 57.67 / 73.29 / 152.94 ms` | `1` / `8` | `10/10 OK` (`1.19 ms`) |
| **C = 16** | `20902` / `15.036 s` | `1390.13 req/s` | `0` / `0` | `5.62 / 55.97 / 60.67 / 88.31 ms` | `2` / `8` | `10/10 OK` (`1.75 ms`) |
| **C = 24** | `21997` / `16.001 s` | `1374.54 req/s` | `0` / `3` `TimeoutError` client | `5.41 / 55.94 / 60.38 / 5501.08 ms` | `2` / `8` | `10/10 OK` (`1.02 ms`) |

### 4.3 Consommation de ressources (`C6`) et identification du premier goulot

Pics mesurés en `P3` (`docker stats --no-stream` + `/actuator/prometheus`) :

| Conteneur | CPU max (plafond) | CPU moyen en `P3` | RAM max (plafond) | Heap JVM avant → après `P3` | Pauses GC (nb avant→après / total ms) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `postgres` | `157.01 %` (`150 %` nom.) | `151.06 %` | `227.7 MiB` (`1024 MiB`) | N/A | N/A |
| `business-read-worker` | `61.51 %` (`100 %`) | `51.10 %` | `234.6 MiB` (`640 MiB`) | `45.17 → 41.35 MiB` | `1023 → 1258` (`944 → 1253 ms`) |
| `command-worker` | `52.83 %` (`100 %`) | `44.39 %` | `237.8 MiB` (`640 MiB`) | `40.81 → 46.39 MiB` | `622 → 799` (`679 → 949 ms`) |
| `results-worker` | `44.75 %` (`100 %`) | `37.97 %` | `211.9 MiB` (`640 MiB`) | `34.81 → 39.79 MiB` | `592 → 748` (`520 → 700 ms`) |
| `http-write` | `11.90 %` (`100 %`) | `9.09 %` | `245.1 MiB` (`640 MiB`) | `34.82 → 39.11 MiB` | `49 → 106` (`141 → 247 ms`) |
| `http-read` | `2.94 %` (`100 %`) | `0.79 %` | `222.2 MiB` (`640 MiB`) | `33.22 → 38.15 MiB` | `33 → 39` (`164 → 182 ms`) |
| `keycloak` | `1.09 %` (`150 %`) | `0.13 %` | `497.3 MiB` (`896 MiB`) | — | — |
| `prometheus` / `grafana` | `1.95 %` / `2.69 %` | `0.50 %` / `0.31 %` | `94.78` / `115.8 MiB` | — | — |

- **Enveloppe opérationnelle reproductible** : **`25 cmd/s` soutenus côté WRITE (`0` rejet, E2E READ `p95=196.55 ms`, drainage `< 0.8 s`)** et jusqu'à **`~1390 req/s` côté READ point lookup (`p95 < 60 ms`)**.
- **Premier goulot d'étranglement identifié** : le **sémaphore mono-permis de [`AdmissionTransport.java`](../../app/supra-web-write/src/main/java/com/kartaguez/pocoma/supra/web/write/AdmissionTransport.java#L115-L143)** (`pocoma.v3.http.admission.capacity=1` par défaut, également appliqué à `HikariConfig.setMaximumPoolSize(l.capacity())` dans `AdmissionHttpConfiguration`, alors que `http-write.properties` définit des clés non consommées `admission.maximum=4` / `minimum-idle=1`). À `50 cmd/s` (période `20 ms`), dès que deux requêtes d'admission se chevauchent en vol, la seconde est rejetée en fail-fast avec `503` (sans fuite de jeton : `admission_capacity_after = 1`), plafonnant le débit effectif à **`41.37 cmd/s`** pendant que les 3 workers et PostgreSQL draineront en `0.884 s` avec seulement `3` commandes `PENDING` au maximum.
- **Bilan cumulé post-S5-C** : `8417` commandes totales traitées, `0` pending, `0` quarantaine, `36/36` baux actifs, `8417` `read_command_result`, `8412` `read_pot_version`, `8408` `read_pot_balance`, `8245` `read_current_pot` == `8245` `read_pot_visibility_state`. Le contrôle final `./pocoma-local status` retourne logiquement `NOT_READY` (`exit=1`, `20/20` checks `PASS`) en raison exclusive de `proofFreshness=STALE_OR_MISSING` (`proofAgeSeconds=630.45 s > MAX_AGE=300 s`).

---

## 5. Garanties consolidées

| Garantie | Formulation exacte | Portée | Configuration associée | Preuve | Limite connue |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **G1. Bootstrap vierge automatisé sans SQL manuel** | Sur clone propre et prérequis satisfaits, `./pocoma-local start --instance <nom>` provisionne secrets, bases `pocoma_write`/`pocoma_read`, schémas V3, Keycloak PKCE et 9 conteneurs jusqu'à `READY`. | Locale (`pocoma-local`) | `scripts/local/bootstrap.py`, `setup-v3-database.py`, `.properties` par défaut | `S5-A` (`A1..A4`), `23.937 s`, `20/20 PASS`, `37` + `14` tables, `0` SQL manuel | Nécessite `>= 256 MiB` libres (`PRE-DISK`), Docker fonctionnel et absence de variables `JAVA_TOOL_OPTIONS`/`MAVEN_OPTS` interdites. |
| **G2. Persistance des données et configuration au cycle stop/start** | Un arrêt gracieux (`SIGTERM` `143`, `0` slot en cours) préserve le volume PG, les secrets (`0o600`), la configuration `.properties` et les commandes déjà matérialisées après redémarrage. | Instance locale arrêtée proprement | Volume nommé Compose, `drain-timeout=90000 ms` | `S5-A` (`A6`), `11` fichiers SHA-256 inchangés, `34/34` commandes intactes, canari `v4->v5`, E2E `0.139 s` | `resources.json` est régénéré à chaque `start` (champ `freeDiskBytes`). |
| **G3. Sûreté d'exécution WRITE/RESULTS/BUSINESS READ sous crash worker** | L'arrêt brutal (`SIGKILL`) d'un worker ne provoque aucune perte de commande admise ni double effet métier ; la réattribution des partitions respecte strictement l'expiration du bail SQL (`fencing`). | Mono-worker par rôle (`command`, `results`, `business-read`) | `lease-duration-ms=30000`, `renewal-interval-ms=10000`, `claim-duration-ms=60000` | `S5-B` (`B2`, `B3`, `B4`), `perte=Non`, `double_effet=Non`, `fencing=PASS` (`8/8`, `8/8`, `20/20` tokens post-TTL) | Reprise par **redémarrage du même worker** (`~20–24 s` selon TTL résiduel), et **non basculement multi-nœuds** (1 conteneur/rôle). |
| **G4. Découplage asynchrone des étapes et cohérence des projections READ** | L'indisponibilité de `results-worker` ne bloque ni `command-worker` ni `business-read-worker` ; l'indisponibilité de `business-read-worker` n'expose aucune projection READ partielle/incohérente. | Pipeline WRITE → RESULTS / BUSINESS READ | Tables SQL découplées (`v3_command_completion_event`, `v3_pot_event`, `v3_read.*`) | `S5-B` (`B3`: `pot0` `v4` matérialisé pendant arrêt RESULTS ; `B4`: `read_command_result=191` et READ figé cohérent à `v4`) | Pendant une panne totale `results-worker` ou `business-read-worker`, l'alimentation des `consumption_slot` côté READ attend la reprise du worker concerné. |
| **G5. Sûreté fail-closed lors d'une coupure temporaire PostgreSQL** | Face à une indisponibilité PG > timeouts (`14 s`), les workers stoppent fail-closed (`draining`, expiration des baux, `/actuator/health=DOWN`) et `http-write` retient son jeton après admission `UNRESOLVED`, empêchant toute dérive silencieuse. | Infrastructure locale (`postgres` partagé) | Hikari `connectionTimeout=1000 ms` / `socketTimeout=5 s`, `AdmissionTransport capacity=1`, `processHealth` / `materializeHealth` | `S5-B` (`B6`), `0` perte, `0` double effet, `0` ligne partielle en base, `http-read` auto-rétabli (`200`) | **Pas d'auto-guérison complète sans intervention** : nécessite `docker restart` des 3 workers (`draining`) et de `http-write` (si admission `UNRESOLVED` reçue pendant la panne) puis `./pocoma-local start`. |
| **G6. Enveloppe soutenable sans perte ni quarantaine sous charge nominale** | Jusqu'à `25 cmd/s` soutenus (`90 s`) en écriture et jusqu'à `~1390 req/s` (`C=16`) en lecture `Q=8`, la chaîne complète opère avec `0` erreur HTTP, `0` quarantaine et drainage `< 1 s`. | Mono-instance Docker locale sur matériel équivalent | `http-write capacity=1` (`maximum=4` inutilisé) ; `http-read Q=8` (`http/sql-permits=8`, pool `8`) | `S5-C` (`P0..P3` = `8417` cmds, `0` perte, `0` quarantaine ; `Q=8` = `459..1390 req/s`, `0` HTTP `503`) | Saturation HTTP WRITE dès chevauchement concurrent au-delà de `~41.4 cmd/s` (rejets `503` fail-fast). Quelques `TimeoutError` client à `C=24` READ (`3/21997`). |
| **G7. Isolation strictement vérifiable des instances** | Plusieurs instances (`demo` et `l7lb-s5`) coexistent sans collision de ports, volumes, secrets, bases ni conteneurs via identifiants et projets Compose dédiés. | Orchestrateur `./pocoma-local` | Préfixe projet `pocoma-lb-<instance>-<owner8>` et plage de ports allouée | `demo-integrity-before.json` == `demo-integrity-after.json` (`490` fichiers, `9` conteneurs, volume inchangé) | Les fichiers de trace `/tmp/pocoma-stage-handoffs.jsonl` dans les conteneurs appartiennent à l'instance dédiée et sont éphémères. |

---

## 6. Réserves identifiées et recommandations

1. **Divergence sonde CLI `/actuator/health/readiness` vs `/actuator/health` sur workers `draining` (Technique / Exploitation)** : `ProcessComposeConfiguration` (`processHealth`) et `MaterializeComposeConfiguration` (`materializeHealth`) passent `/actuator/health` à `HTTP 503 DOWN` lorsqu'un worker passe en `draining`, mais Spring `/actuator/health/readiness` (interrogé par [`runtime.py:533`](../../scripts/local/runtime.py#L533)) reste `200 UP`. De plus, `./pocoma-local start` utilise `docker compose up -d`, qui ne redémarre pas un conteneur déjà `Up` en état `draining` ou avec capacité `http-write=0`.
2. **Sérialisation et verrouillage fail-closed d'admission `http-write` (`capacity=1`) (Architecture / Config)** : [`http-write.properties`](../../docker/v3-five-runtimes/http-write.properties#L29-L30) définit `pocoma.v3.http.admission.maximum=4` et `minimum-idle=1` sans définir `pocoma.v3.http.admission.capacity` (laissé au défaut `1` dans `AdmissionHttpConfiguration`, qui applique cette valeur à la fois au sémaphore d'admission et à `maximumPoolSize`/`minimumIdle` du pool Hikari `v3-http-admission`). Conséquence double : (a) plafond mono-permis à `~41.4 cmd/s` avec rejets `503` à `50 cmd/s`, et (b) une seule requête `UNRESOLVED` pendant une panne PG épuise durablement le jeton unique (`0.0`) tout en laissant `/actuator/health` à `200 UP`.
3. **Politique de fraîcheur CLI `READY` (`MAX_AGE = 300 s` et `generationQualified`) (Exploitation)** : tout redémarrage unitaire d'un des 5 conteneurs (`B2..B6`) ou toute exécution prolongée dépassant `300 s` (`S5-C`: `630.45 s`) fait basculer `./pocoma-local status` en `NOT_READY` (`exit=1`, `technical=AVAILABLE`) jusqu'à réexécution explicite de `./pocoma-local start`.
4. **Topologie mono-worker et base PostgreSQL partagée (Architecture locale)** : `pocoma_write` et `pocoma_read` partagent le même conteneur `postgres` (`B6` touche simultanément WRITE et READ), et chaque rôle worker n'a qu'une réplique (aucune preuve de failover multi-nœuds actif en `L7-LB`).
5. **Incohérences mineures de documentation constatées (Documentation)** :
   - [`docs/reference/configuration.md`](../reference/configuration.md#L30) mentionne les paramètres `min-poll=2` / `max-poll=20` / `error-poll=50` « en secondes », alors que `ProcessSettings` et `MaterializeSettings` utilisent `Duration.ofMillis(...)` (**millisecondes**). Le même tableau omet `capacity=1` (défaut actif sur `http-write`).
   - Option `--dry-run` acceptée sans effet sur `./pocoma-local start` (active uniquement sur `reset`/`destroy`).
6. **Sensibilité à l'espace disque hôte (Environnement)** : le seuil `PRE-DISK` (`256 MiB`) et la croissance rapide sous charge/journaux nécessitent au moins plusieurs gigaoctets libres avant campagne longue.

> [!NOTE]
> **Levées de réserves en S6** (`f9c0ef64` → S6) : les points **1** (récupération CLI après coupure PG), **2** (`pocoma.v3.http.admission.capacity=4` effectif), **3** (distinction additive `operationalState` vs `qualificationState` en cas de preuve `STALE`) et **5** (unités `min-poll`/`max-poll`/`error-poll` en ms et rejet explicite `--dry-run` hors `reset`/`destroy`) ont été corrigés et requalifiés dans [`L7_LB_S6_Operational_Hardening_Report.md`](L7_LB_S6_Operational_Hardening_Report.md) tout en conservant les mesures historiques S5 intactes ci-dessus.

---

## 7. Conformité `Reactor_Verification_Policy.md`

Conformément à [`AGENTS.md`](../../AGENTS.md) et [`docs/testing/Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md) :

- **Slices de vérification déclarées** : campagne de qualification système/distribution (`L7-LB/S5`) sans modification sous `app/`, `docker/` ou `scripts/` ; aucun réacteur Maven complet ni slice Java historique relancé.
- **Commandes exécutées** :
  - `python3 -B -m unittest discover -s scripts/local -p 'test_prerequisites.py' -v` (`40/40 OK`, clone isolé)
  - `./pocoma-local start --instance l7lb-s5` / `status` / `login` / `bruno` / `stop` / `destroy` (sur `/private/tmp/pocoma-l7-lb-s5/source`)
  - Harnais d'exécution S5-A, S5-B (`B1..B6`), S5-C (`P0..P3` + `Q=8`) produisant les preuves JSON sous [`docs/testing/evidence/L7-LB-S5/`](../testing/evidence/L7-LB-S5/) et vérifiant `demo-integrity-before.json` == `demo-integrity-after.json`
- **Franchissement de slice inattendu** : aucun (zéro modification de code Java/SQL/CLI ; comportements `B6` et `capacity=1` observés et documentés à révision constante).
- **Porte globale requise** : Non (aucun changement de code source Java, aucun déclenchement `architecture-tests`).
- **Réacteur Maven complet exécuté** : **Non** — conformément à la politique de vérification et aux contraintes du mandat (`5009d03a20ea781f1aa789ebd089a2174d06cecd` réutilise les preuves Java/SQL antérieures validées).

---

## 8. Inventaire des preuves archivées

| Artefact | Rôle |
| :--- | :--- |
| [`00-configuration-manifest.json`](../testing/evidence/L7-LB-S5/00-configuration-manifest.json) | État Git, hôte, Docker, digests d'images, versions outils, propriétés des 5 runtimes, limites CPU/RAM |
| [`demo-integrity-before.json`](../testing/evidence/L7-LB-S5/demo-integrity-before.json) | Empreinte intégrale de l'instance protégée `demo` avant S5 (`490` fichiers, `9` conteneurs, volume) |
| [`demo-integrity-after.json`](../testing/evidence/L7-LB-S5/demo-integrity-after.json) | Empreinte intégrale de `demo` après S5 (strictement identique hors horodatage) |
| [`s5a-installation-and-functioning.json`](../testing/evidence/L7-LB-S5/s5a-installation-and-functioning.json) | Preuves S5-A (`A1..A6`), Bruno JUnit/summary, hashes de persistance, canari + E2E post-redémarrage |
| [`s5b-resilience-and-fault-injection.json`](../testing/evidence/L7-LB-S5/s5b-resilience-and-fault-injection.json) | Preuves S5-B (`B2..B6`), tableaux `B7`, horodatages, journaux fencing et comportement `B6` |
| [`s5c-performance-and-limits.json`](../testing/evidence/L7-LB-S5/s5c-performance-and-limits.json) | Preuves S5-C (`P0..P3`, décision `P4`, `Q=8` sweep `C=4..24`, stats conteneurs/GC/SQL) |
| [`SHA256SUMS`](../testing/evidence/L7-LB-S5/SHA256SUMS) | Empreintes SHA-256 des artefacts de preuve S5 |
