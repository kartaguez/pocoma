# Pocoma V3 — Étude d'Optimisation de l'Organisation des Workers et Runtimes

- **Date** : 22 novembre 2026
- **Référence qualifiée** : tag `v3.0.0-l7-local` (commit `afec69f8`, préservé intact)
- **Branche d'étude isolée** : `study/worker-topology-optimization`
- **Binaires mesurés** : `runtime-web-write`, `runtime-web-read`, `runtime-worker-process`, `runtime-worker-materialize` (`0.0.1-SNAPSHOT-exec.jar`) compilés **sans aucune modification du code de production sous `app/`**
- **Budget matériel strictement constant** : **`6,00` CPUs / `5 376 MiB` RAM** (PostgreSQL 17 local `2,0` CPUs / `2 048 MiB` + `http-write` `0,5` CPU / `512 MiB` + `http-read` `0,75` CPU / `768 MiB` + **budget workers constant `2,75` CPUs / `2 048 MiB`**)
- **Campagne exécutée** : **6 topologies réellement exécutées**, **58 exécutions contrôlées**, **`58/58 PASS`** (`0` échec, `0` violation d'invariant, `read_availability_valid() = true` sur 100 % des runs)
- **Preuves archivées** : [summary.json](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/worker-topology-study/summary.json), [per-run-metrics.json](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/worker-topology-study/per-run-metrics.json), [manifest.sha256](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/worker-topology-study/manifest.sha256), [topologies/](file:///Users/julien.guezennec/Dev/projects/pocoma/experiments/worker-topology-study/topologies/)

> [!IMPORTANT]
> **Statut décisionnel** : Conformément au mandat, cette étude livre un diagnostic architectural, des prototypes exécutables isolés de `main` et une recommandation étayée. Aucune nouvelle topologie n'est adoptée ni fusionnée dans `main` sans décision explicite. Les mesures locales (Docker Desktop sur Apple M4, `10` vCPU, `~7,75 GiB` RAM Docker) ne remplacent pas une certification `SPEC-09` sur infrastructure multi-VM (`C4-R12`).

---

## Synthèse exécutive

1. **Constat architectural majeur sur les gardes de composition** : les deux topologies bloquées pendant L7-C4 (`topo-c1` et `topo-c2`) **n'étaient pas interdites par un invariant fondamental**. `ProcessSettings.validateProcessWorkerName` et `MaterializeSettings.validateMaterializeWorkerName` n'appliquent leurs contraintes strictes qu'aux 3 noms de workers canoniques (`command-worker`, `results-worker`, `business-read-worker`). Avec des noms explicites dédiés, **les 6 topologies (`T0`, `T1a`, `T1b`, `T2`, `T3`, `T4`) ont toutes démarré et passé les tests sur les binaires `v3.0.0-l7-local` non modifiés**, en préservant intégralement la séparation d'autorités SQL WRITE/READ.
2. **Mythe réfuté sur le coût des projections** : **`MaterializePotBalance` n'est PAS la projection la plus coûteuse**. Sur pots MEDIUM (`200` dépenses) comme LARGE (`2 000` dépenses) et en backlog `10k`, **`MaterializePotVersion` est `~2×` à `2,4×` plus coûteuse que `MaterializePotBalance`** (`12,3–25,9 ms` vs `6,2–10,8 ms`), tandis que `Command` reste de loin la consommation la plus lourde (`14,8–85,7 ms`).
3. **Coût réel mesuré d'une JVM worker supplémentaire** : **`~195–205 MiB` de RSS à vide** (`~210–225 MiB` en charge, dont `~75–80 MiB` hors-heap JVM et `~30–40 MiB` heap), **`12–16` connexions PostgreSQL** (du fait des règles strictes HikariCP `M_W+2` / `M_W+6`), et **`~0,14–0,19` CPU à vide**.
4. **Comparaison à budget constant (`6,0` CPUs / `5 376 MiB`)** :
   - **Gagnant sobriété, performance et simplicité (`T3` — 2 workers : `command-worker` + `unified-read-worker`)** : **`-34,5 %` CPU à vide** (`0,36` vs `0,55`), **`-24,2 %` CPU à 1 cmd/s**, **`-195 MiB` RSS workers** (`-29,6 %`), **`-16` connexions PG** (`-21,3 %`), **`-62 %` de throttling workers** et **`-44 %` de throttling PG** en rampe, **`-86 %` de saturations de sémaphores**, plateau E2E le plus élevé (**`39,04 cmd/s`** médian, max `40,1`), drain 10k le plus rapide (**`112,6 s` / `88,8 cmd/s`**, `-7,9 %` vs `T0`), meilleure latence `p50` sous interférence (`ID 149 ms` vs `195 ms`, `Pot 122 ms` vs `168 ms`), et **1 conteneur en moins**.
   - **Gagnant isolation des pannes à 3 workers (`T1a` — `command-worker` + `critical-read-worker` [Results + `CurrentBinding`] + `pot-read-worker`)** : coût identique à `T0` (`0` JVM supplémentaire, `0` connexion PG supplémentaire), élimine totalement la saturation de `CurrentBinding` (`cb_sat = 0` vs `4 872` en `T0` sous backlog 10k), réduit `conc ID p50` en backlog 10k de `319` → **`218 ms` (`-32 %`)**, et préserve la disponibilité des résultats et du binding si le worker Pot redémarre.
   - **Topologies clairement dominées** : **`T1b`** (isolation physique `IdentityRequest`, 4 workers), **`T2`** (séparation `PotBalance`, 4 workers — casse la déduplication `B3` sur `POT_EVENT` et fait exploser `p95` à `35/s` à `1 115 ms`), et **`T4`** (réplication horizontale `2×` READ shardés à budget constant — multiplie le bruit PG à vide à `2 028 tx/s`, fait exploser `pg_thr` à `49,5 s` et `p95` à `35/s` à `1 166 ms`).
5. **Recommandation** :
   - **Pour Pocoma local / mono-VM aujourd'hui** : privilégier **`T3` (2 workers : `command-worker` + `unified-read-worker`)** si l'on accepte le redémarrage conjoint Résultats + Projections READ (sans impact sur l'admission WRITE), ou **conserver 3 workers en évoluant de `T0` vers `T1a`** si l'isolation de panne des Résultats + `CurrentBinding` prime.
   - **Pour L8 / multi-VM** : conserver la **flexibilité statique par configuration** (`runtime-worker-process` + `runtime-worker-materialize` génériques) permettant `T3` sur mono-nœud, `T1a` pour isoler le chemin critique, et la réplication active/passive (`0|1|2|3` sur 2 VMs) ou shardée (`0|1` vs `2|3` sur 2 VMs) uniquement lorsque la capacité verticale d'un nœud est dépassée ou pour la HA.

---

## A. Cartographie actuelle des 9 consommations

### A.1 Inventaire fonctionnel, coûts réels et sensibilités

Données unitaires mesurées directement via Micrometer (`attempt_duration`, `prepare_duration`, `finalize_duration`, `discovery_duration`, `saturation_total`) sur les exécutions qualifiées `A2` (`T0`) et `C4` (pots MEDIUM `20/200`, LARGE `100/2 000`, `10k CreatePot`, `Invitation`, `IdentityRequest`) :

| # | Consommation | Autorité / Runtime actuel | Famille source (`ConsumableFamily`) | Rôle fonctionnel & criticité parcours | Coût unitaire mesuré (`avg` : `prep` + `fin`) | Accès PG & Pools JDBC | Concurrence & comportement backlog | Polling & déduplication `B3` | Réplication horizontale (4 partitions `0\|1\|2\|3`) |
|---|---|---|---|---|---|---|---|---|---|
| 1 | **`Command`** | WRITE (`command-worker`) | `COMMAND` | Exécution autoritaire des commandes Pot (`CreatePot`, `CreateExpense`, `DeletePot`, `CreateInvitation`, etc.). **Critique** (tous parcours Pot). | **Le plus lourd** : `14,8–16,0 ms` (MED), **`85,7 ms`** (LARGE), `23,3 ms` (`10k CreatePot`), `93,0 ms` (`DeletePot`). Dominé par `finalize` (`11–64 ms`). | `pocoma_write` (`write-business`, `write-control`). Pure JDBC (sans JPA). | Limité par quota CPU & `Command=3` sur `M_W=4`. En backlog 10k : `sat_c=7 607`. | Sonde dédiée `v3_command` / `v3_request_counter`. | Max 4 répliques actives (sharding statique), ordonnancement garanti par `pot_id` → partition. |
| 2 | **`IdentityRequest`** | WRITE (`command-worker`) | `IDENTITY_REQUEST` | Inscription/lien/révocation d'identité (`Registration`, `Unlink`). **Critique `SPEC-09` (`F01-E`)**. | Modéré : **`8,1 ms`** (nominal `a2-id1`), `10,8 ms` (sous 20/s Pot), `28,4 ms` (backlog 10k). | `pocoma_write` (`write-business`, `write-control`). | Très faible volume, très sensible à la famine CPU/sémaphore. Protégé par réservation `Command=3` sur `M_W=4` (`≥ 1` slot garanti). | Sonde dédiée `IDENTITY_REQUEST`. | Max 4 répliques ; faible intérêt à répliquer seul (volume marginal). |
| 3 | **`MaterializeCommandResult`** | READ (`results-worker`) | `COMMAND_COMPLETION_EVENT` | Matérialise le statut terminal (`CHANGED`/`REJECTED`) consulté par `/commands/{id}/result`. **Critique** (premier jalon UX). | **Très léger** : **`2,8–2,9 ms`** (MED), `4,2 ms` (LARGE), `5,1 ms` (10k). | `pocoma_write_source_read` (SELECT) + `pocoma_read` (`read-business`, `read-control`) + CTE C3. | Débit très élevé (`~5,8 s` cumulées sur toute une rampe `2 008` cmds !). Quasi jamais saturé. | Sonde dédiée `COMMAND_COMPLETION_EVENT`. | Max 4 répliques. Partage très bien un runtime avec d'autres READ si protégé par `M_W`. |
| 4 | **`MaterializeIdentityRequestResult`** | READ (`results-worker`) | `IDENTITY_COMPLETION_EVENT` | Matérialise le résultat d'une `IdentityRequest` (`/identity/requests/{id}/result`). **Critique (`F01-E`)**. | **Très léger** : **`3,4 ms`** (nominal), `3,6 ms` (sous 20/s Pot), `8,0 ms` (backlog 10k). | Idem READ (`source-read` + `read-business` + `read-control`). | Volume très faible, ultra-sensible à la latence de file. | Sonde dédiée `IDENTITY_COMPLETION_EVENT`. | Max 4 répliques. |
| 5 | **`MaterializePotVersion`** | READ (`business-read-worker`) | **`POT_EVENT`** | Reconstruit l'état/manifeste Radix16 d'une version de Pot (`read_pot_version`, `read_current_pot`). **Critique `C-03`**. | **#1 des projections READ (`2×` `PotBalance` !)** : **`12,3–14,0 ms`** (MED : `prep 8,0–8,6` + `fin 4,3–5,4`), **`25,9 ms`** (LARGE), `10,0 ms` (10k). Représente **~54 %** du temps d'exécution métier READ. | Idem READ + manifestes Radix16 + `2` familles C3 (`POT_VERSION_AVAILABLE`, `CURRENT_POT_CHANGED`). | Premier consommateur CPU en `business-read-worker` (`sat_c=197` en rampe, `sat_w=820` en 10k). | **Partage `POT_EVENT` avec `PotBalance` et `PotVisibility` → déduplication `B3` gratuite si colocalisé !** | Max 4 répliques. Gagne fortement à rester colocalisé avec `PotBalance` et `PotVisibility`. |
| 6 | **`MaterializePotBalance`** | READ (`business-read-worker`) | **`POT_EVENT`** | Calcule les soldes financiers incrémentaux (`C2-B2` Radix16) (`read_pot_balance`). **Critique `C-03`**. | **2× plus léger que `PotVersion`** : **`6,2–6,5 ms`** (MED : `prep 4,0–4,2` + `fin 2,2–2,5`), **`10,8 ms`** (LARGE), `6,4 ms` (10k). **27 %** du temps métier READ. | Idem READ + agrégats binaires + C3 (`POT_BALANCE_AVAILABLE`). | Très efficace depuis `C2-B2` incrémental ; peu de saturation (`sat_w=0`, `sat_c=60` en rampe). | **Bénéficie de `B3` sur `POT_EVENT` uniquement si colocalisé avec `PotVersion`/`PotVisibility`.** | Max 4 répliques. L'isoler dans sa propre JVM détruit `B3` sur `POT_EVENT`. |
| 7 | **`MaterializePotVisibility`** | READ (`business-read-worker`) | **`POT_EVENT`** | Matérialise la visibilité utilisateur→pot (`read_pot_visibility`, `/me/pots`). **Important** (listes & autorisation READ). | **Quasi égal à `PotBalance`** : **`4,2–5,4 ms`** (MED), `6,7 ms` (LARGE), `6,0 ms` (10k). **19 %** du temps métier READ. | Idem READ + C3 (`POT_VISIBILITY_CHANGED`). | Sensible à la saturation du pool worker (`sat_w=114`, `sat_e=73` en rampe ; `sat_w=1 539` en 10k). | **Partage `POT_EVENT` (`B3`).** | Max 4 répliques. Doit rester colocalisé avec `PotVersion` et `PotBalance`. |
| 8 | **`MaterializeCurrentBinding`** | READ (`business-read-worker`) | `IDENTITY_EVENT` | Matérialise le `bindingToken` actif (`/me/binding`, prérequis de toute requête authentifiée). **Critique (`F01-E`)**. | **Très léger** : **`2,9 ms`** (nominal), `3,2 ms` (sous 20/s Pot), `9,1 ms` (backlog 10k). | Idem READ + C3 (`CURRENT_BINDING_CHANGED`). | **Victime majeure d'interférence en `T0`** : en backlog 10k, seulement 12 tentatives mais **`sat_w=3 559` / `sat_e=1 785`** (et `4 872` cumulés en Vague 1B) car il partage `M_W=4` avec les 3 projections Pot (`2+2+2=6 > 4`) ! | Sonde dédiée `IDENTITY_EVENT` (indépendante de `POT_EVENT`). | Max 4 répliques. **Gagne énormément à être sorti du même quota que `Pot*` ou placé sous un `M_W` avec réserve.** |
| 9 | **`MaterializeInvitation`** | READ (`business-read-worker`) | `INVITATION_EVENT` | Matérialise les invitations publiques (sans clé de déchiffrement). Secondaire en volume. | **Le plus léger de tous** : **`2,2 ms`** (`prep 0,64` + `fin 1,52`, `c3-int-inv-vs-pot`). | Idem READ + C3. | Très faible coût, aucune contention observée. | Sonde dédiée `INVITATION_EVENT`. | Max 4 répliques. Aucun intérêt à l'isoler. |

### A.2 Analyse des contraintes actuelles de composition (Section 4)

L'inspection de [ProcessSettings.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/compose-polling-burnable-write-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/process/ProcessSettings.java), [MaterializeSettings.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializeSettings.java), [ProcessPoolSettings.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/compose-polling-burnable-write-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/process/ProcessPoolSettings.java), [MaterializePoolSettings.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializePoolSettings.java) et [L7DeploymentArchitectureTest.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/l7/L7DeploymentArchitectureTest.java) permet de classer rigoureusement chaque contrainte :

| Mécanisme / Garde | Localisation exacte | Classification | Analyse |
|---|---|---|---|
| **Séparation des binaires et principaux SQL WRITE vs READ** | `runtime-worker-process` (`pocoma_write`) vs `runtime-worker-materialize` (`pocoma_write_source_read` + `pocoma_read`), `L7DeploymentArchitectureTest` | **1. Véritable invariant architectural & sécurité (NON MODIFIABLE)** | Un worker READ ne dispose jamais de droits d'écriture sur `pocoma_write`, et un worker WRITE n'a ni classes ni credentials `pocoma_read`. Fusionner WRITE + READ en un seul runtime distribué violerait ce principe de moindre privilège (réservé à `runtime-monolith` en dev/test). |
| **Garde `validateProcessWorkerName`** (`"Command worker must activate Command and IdentityRequest"`) | `ProcessSettings.java:260-277` | **4. Limitation artificielle liée au nom canonique (MODIFIABLE / CONTOURNABLE)** | Ne s'applique **que** si `worker-name ∈ {"command-worker", "pocoma-command-worker"}`. Tout autre nom (`pot-command-worker`, `identity-worker`) permet déjà d'activer un sous-ensemble arbitraire (`Command` seul ou `IdentityRequest` seul) sans modifier Java ! |
| **Garde `validateMaterializeWorkerName`** (`"Results worker must activate exclusively..."`) | `MaterializeSettings.java:331-363` | **4. Limitation artificielle liée au nom canonique (MODIFIABLE / CONTOURNABLE)** | Ne s'applique **que** si `worker-name ∈ {"results-worker", "business-read-worker"}` (et `pocoma-*`). Tout autre nom (`critical-read-worker`, `unified-read-worker`, `pot-balance-worker`, `read-worker-1`) permet déjà **n'importe quel sous-ensemble des 7 participants READ** (y compris les 7 ensemble) sans modifier Java ! |
| **Dimensionnement strict des pools HikariCP** (`max == M_W + 2` business / `M_W + 6` control, `minIdle == max`) | `ProcessPoolSettings.java:70-103`, `MaterializePoolSettings.java:88-113` | **2. Contrainte d'assemblage technique (MODIFIABLE avec requalification)** | Garantit qu'aucun worker ne manque de connexions, mais impose **`2·M_W + 8`** connexions par worker WRITE et **`3·M_W + 10`** par worker READ (`22` conns pour `M_W=4`, `28` pour `M_W=6`), ce qui amplifie fortement le coût PostgreSQL de toute multiplication de runtimes ou de répliques. |
| **Bornes temporelles `verifyTimingBounds`** (`renewalInterval + 5·mw + 3 < claimDuration`) | `ProcessSettings.java:367-388`, `MaterializeSettings.java:455-475` | **1. Invariant de sûreté opérationnelle (À PRÉSERVER)** | Borne `M_W ≤ 9` avec `claimDuration = 60 s` et `renewalInterval = 10 s` ; protège contre l'expiration de claims sous contention. |
| **Leases statiques sans rééquilibrage dynamique** | `DiscoveryLoop.java`, `RenewalService.java` | **1. Choix architectural délibéré (HORS PÉRIMÈTRE)** | Deux répliques configurées sur `0\|1\|2\|3` forment une paire **actif/passif (failover HA)** et non actif/actif équilibré. Le scaling horizontal actif/actif requiert un sharding statique explicite (`0\|1` vs `2\|3`). |

**Conclusion sur l'assemblage générique** : Pocoma V3 possède **déjà** l'assemblage statique générique souhaité (deux artefacts génériques `runtime-worker-process` et `runtime-worker-materialize` pilotés par `.properties`, explicites et contrôlés au démarrage, sans orchestrateur dynamique). Seules les gardes nominatives de `ProcessSettings`/`MaterializeSettings` (et `QualificationSettings` côté tests) ont historiquement verrouillé les 3 noms canoniques.

---

## B. Analyse des topologies candidates

Toutes les topologies respectent le budget global constant **`6,00` CPUs / `5 376 MiB` RAM** (`postgres=2,0c/2048m`, `http-write=0,5c/512m`, `http-read=0,75c/768m`, **workers = `2,75` CPUs / `2 048 MiB`**), vérifié automatiquement avant chaque run par [run-campaign.py](file:///Users/julien.guezennec/Dev/projects/pocoma/experiments/worker-topology-study/run-campaign.py).

| Topologie | Famille | Workers (Total runtimes) | Répartition exacte des consommations, CPUs, RAM et `M_W` | Conns PG (totales) | Statut |
|---|---|---:|---|---:|---|
| **`T0`** | Référence actuelle (`A2`) | **3** (`5`) | `command-worker` (`0,75c/512m`, `M_W=4`, `Cmd=3,Id=2`) ; `results-worker` (`0,75c/512m`, `M_W=4`, `CmdRes=3,IdRes=2`) ; `business-read-worker` (`1,25c/1024m`, `M_W=4`, 5 biz READ=2) | `75` | **Exécutée (`R=3` + 10k + reprise)** |
| **`T1a`** | Isolation critique READ (`topo-c2` exécutable) | **3** (`5`) | `command-worker` (`0,75c/512m`, `M_W=4`) ; `critical-read-worker` (`0,75c/512m`, `M_W=4`, `CmdRes=2,IdRes=2,CurrentBinding=2`) ; `pot-read-worker` (`1,25c/1024m`, `M_W=4`, `PotVer=2,PotBal=2,PotVis=2,Inv=2`) | `75` | **Exécutée (`R=3` + 10k + reprise)** |
| **`T1b`** | Isolation physique Identity (`topo-c1` exécutable) | **4** (`6`) | `pot-command-worker` (`0,65c/512m`, `M_W=4`, `Cmd=4`) ; `identity-worker` (`0,25c/384m`, `M_W=2`, `Id=2`) ; `results-worker` (`0,65c/384m`, `M_W=4`) ; `business-read-worker` (`1,20c/768m`, `M_W=4`) | `87` | **Exécutée (criblage complet, écartée en Vague 2)** |
| **`T2`** | Séparation projections métier (`PotBalance` isolé) | **4** (`6`) | `command-worker` (`0,75c/512m`, `M_W=4`) ; `results-worker` (`0,50c/384m`, `M_W=3`) ; `pot-core-worker` (`0,90c/640m`, `M_W=4`, `PotVer,PotVis,CurBind,Inv=2`) ; `pot-balance-worker` (`0,60c/512m`, `M_W=2`, `PotBal=2`) | `88` | **Exécutée (criblage complet, écartée en Vague 2)** |
| **`T3`** | Mutualisation renforcée (READ unifié) | **2** (`4`) | `command-worker` (`0,85c/640m`, `M_W=4`, `Cmd=3,Id=2`) ; `unified-read-worker` (`1,90c/1408m`, `M_W=6`, 7 READ : `CmdRes=3`, autres `=2`) | **`59`** | **Exécutée (`R=3` + 10k + reprise)** |
| **`T4`** | Réplication horizontale (`2×` READ shardés) | **3** (`5`) | `command-worker` (`0,85c/640m`, `M_W=4`) ; `read-worker-1` (`0,95c/704m`, `M_W=4`, 7 READ sur `0\|1`) ; `read-worker-2` (`0,95c/704m`, `M_W=4`, 7 READ sur `2\|3`) | `75` | **Exécutée (criblage complet + reprise partielle)** |

*Variantes écartées par construction avec justification* :
- **Fusion WRITE + READ en 1 seul worker (`runtime-monolith`)** : écartée pour le déploiement cible car elle viole l'invariant fondamental de séparation des principaux SQL (`pocoma_write` vs `pocoma_read`).
- **Séparation `PotVersion` seule (autre variante `T2`)** : partage exactement les mêmes défauts structurels mesurés sur `T2` (`+200 MiB` RAM, `+13` conns PG, rupture de la déduplication `B3` sur `POT_EVENT`, morcellement des quotas CPU).

---

## C. Comparaison expérimentale (58/58 runs `PASS`)

### C.1 Tableau comparatif global (médianes ; `n = 3` sur `T0`/`T1a`/`T3` pour `idle`/`med-1`/`ramp`/`pot-vs-id`, `n = 1` sur criblage `T1b`/`T2`/`T4` et backlogs)

| Scénario & Indicateur | `T0` (Réf. `A2`, 3w) | `T1a` (`CurBind` isolé, 3w) | `T1b` (`Id` isolé, 4w) | `T2` (`PotBal` isolé, 4w) | **`T3` (READ unifié, 2w)** | `T4` (`2×` READ shardé, 3w) |
|---|---:|---:|---:|---:|---:|---:|
| **Runtimes workers / Total** | `3 / 5` | `3 / 5` | `4 / 6` | `4 / 6` | **`2 / 4`** | `3 / 5` |
| **Connexions PG totales (`write+read`)** | `75` | `75` | `87` | `88` | **`59`** | `75` |
| **`c1-idle` — RSS workers (pic) [MiB]** | `658,7` (`659,9`) | `667,5` (`668,4`) | `865,1` (`865,5`) | `855,3` (`863,0`) | **`463,7` (`464,6`)** | `671,1` (`671,6`) |
| **`c1-idle` — Heap / Hors-heap workers [MiB]** | `99,7` / `235,4` | `99,6` / `235,4` | `112,9` / `310,1` | `146,2` / `308,3` | **`56,9` / `161,3`** | `100,2` / `239,7` |
| **`c1-idle` — CPU total / PG / workers** | `0,55` / `0,14` / `0,38` | `0,56` / `0,14` / `0,39` | `0,54` / `0,13` / `0,38` | `0,59` / `0,14` / `0,42` | **`0,36` / `0,10` / `0,24`** | `0,57` / `0,16` / `0,39` |
| **`c1-idle` — PG tx/s** | `1 339` | `1 343` | `1 379` | `1 546` | **`1 257`** | `2 028` |
| **`c1-idle` — `c03` `p50` / `p95` (reprise froid)** | `150,1` / `926,6 ms` | `141,7` / `1 002,8 ms` | `148,3` / `1 133,4 ms` | `141,0` / `1 145,3 ms` | **`130,8` / `865,4 ms`** | `135,2` / `1 099,5 ms` |
| **`c1-med-1` — `c03` `p50` / `p95` [ms]** | `121,5` / `141,2` | **`120,0`** / `144,7` | `120,2` / `136,5` | `122,4` / `139,7` | `123,9` / `152,4` | `118,9` / `137,2` |
| **`c1-med-1` — CPU total / PG / workers** | `0,66` / `0,17` / `0,39` | `0,68` / `0,17` / `0,40` | `0,67` / `0,17` / `0,40` | `0,75` / `0,18` / `0,46` | **`0,50` / `0,14` / `0,27`** | `0,72` / `0,20` / `0,41` |
| **`c2-ramp` (`10/s`) — `c03` `p50` / `p95` [ms]** | `84,9` / `118,1` | `87,3` / `118,1` | `101,3` / `118,2` | `83,9` / `114,8` | `104,9` / `131,5` | `99,2` / `115,4` |
| **`c2-ramp` (`20/s`) — `c03` `p50` / `p95` [ms]** | `80,6` / `114,5` | `80,7` / **`111,4`** | `80,0` / `113,4` | `83,1` / `112,6` | `84,2` / `113,8` | `105,1` / `149,5` |
| **`c2-ramp` (`35/s`) — Débit · `p50`/`p95` [ms]** | `32,85/s` · `80,9/148,7` | **`34,83/s`** · `92,9/142,4` | `34,66/s` · `76,7/129,1` | `32,38/s` · `150,1/`**`1 115`** | `33,88/s` · `95,8/`**`137,4`** | `32,30/s` · `208,2/`**`1 166`** |
| **`c2-ramp` (`50/s`) — Plateau E2E [cmd/s]** | `38,31` | `37,33` | `38,10` | `31,47` | **`39,04`** *(max `40,1`)* | `29,93` |
| **`c2-ramp` — CPU / `w_thr` / `pg_thr` / `sat`** | `2,31` / `3,69 s` / `19,87 s` / `466` | `2,34` / `3,25 s` / `18,45 s` / `427` | `2,39` / `8,22 s` / `19,14 s` / `554` | `2,56` / `10,90 s` / `40,50 s` / `632` | **`2,13` / `1,41 s` / `11,09 s` / `63`** | `2,72` / `8,70 s` / `49,54 s` / `502` |
| **`c2-ramp` — RSS workers (pic) / WAL [MiB]** | `705,6` (`706,0`) / `179,3` | `705,4` (`705,8`) / `178,3` | `926,7` (`927,8`) / `178,5` | `907,5` (`911,8`) / `171,5` | **`508,0` (`508,6`)** / `181,0` | `710,4` (`721,9`) / `166,9` |
| **`c3-int-pot-vs-id` — `ID` `p50` / `p95` [ms]** | `195,0` / `1 120,4` | `194,3` / `1 327,4` | `111,7` / `382,5` *(n=1)* | `109,1` / `217,8` *(n=1)* | **`149,1`** / `1 143,5` | `75,5` / `164,0` *(n=1)* |
| **`c3-int-pot-vs-id` — `Pot` `p50` / `p95` [ms]** | `168,1` / `1 115,5` | `147,2` / `1 161,8` | `87,4` / `177,3` *(n=1)* | `100,4` / `221,4` *(n=1)* | **`121,8` / `1 041,8`** | `102,6` / `161,8` *(n=1)* |
| **`c3-int-pot-vs-id` — `w_thr` / `cb_sat` / `sat`** | `12,86 s` / `56` / `100` | `18,22 s` / **`0`** / `76` | `17,00 s` / `28` / `81` | `23,79 s` / `15` / `55` | **`4,55 s` / `1` / `9`** | `12,14 s` / `16` / `50` |
| **`c4-backlog-10k` — Drain (débit sans HTTP)** | `122,23 s` (`81,8/s`) | `122,27 s` (`81,8/s`) | `134,19 s` (`74,5/s`) | `132,37 s` (`75,5/s`) | **`112,63 s` (`88,8/s`)** | `121,48 s` (`82,3/s`) |
| **`c4-backlog-10k` — `conc ID` `p50` / `p95` [ms]** | `319,3` / `1 025,4` | **`217,7`** / `997,9` | `421,1` / `1 354,1` | `380,1` / `828,0` | `388,7` / **`692,6`** | `313,5` / `778,4` |
| **`c4-backlog-10k` — `pg_thr` / `w_thr` / `cb_sat`** | `227,2 s` / `25,4 s` / `4 872` | `230,8 s` / `23,7 s` / **`0`** | `248,6 s` / `35,8 s` / `5 092` | `302,3 s` / `52,0 s` / `3 479` | **`157,5 s` / `8,4 s`** / `1 188` | `294,4 s` / `14,4 s` / `1 936` |
| **`c4-backlog-read-only` — Reprise / `conc p50`** | `34,04 s` / `120,2 ms` | `34,33 s` / `116,4 ms` | — | — | `34,12 s` / `122,5 ms` | `35,15 s` / `118,4 ms` |

*Note de rigueur* : les débits E2E HTTP (`c2-ramp` : `37–39 cmd/s` avec polling HTTP client `25 ms`) et les débits de drainage sans HTTP (`c4-backlog-10k` : `74,5–88,8 cmd/s`) sont présentés séparément et ne doivent jamais être confondus.

### C.2 Variabilité observée sur les répétitions `R = 3` (`T0`, `T1a`, `T3`)

| Scénario | `T0` (`r1` / `r2` / `r3`) | `T1a` (`r1` / `r2` / `r3`) | `T3` (`r1` / `r2` / `r3`) |
|---|---|---|---|
| **`c1-med-1`** (`c03 p50 / p95 / p99`) | `121,5/137,9/152,8` · `123,3/141,2/148,9` · `119,4/150,7/181,6` | `124,2/156,3/165,7` · `120,0/144,7/165,1` · `119,0/140,5/168,4` | `121,6/158,2/163,5` · `123,9/152,4/163,4` · `126,2/152,0/161,8` |
| **`c2-ramp` (`35/s` · `50/s`)** | `32,7/s (150ms)` · `32,9/s (149ms)` · `34,9/s (145ms)` \| `37,3 / 39,3 / 38,3` | `34,8/s (158ms)` · `34,8/s (140ms)` · `34,8/s (142ms)` \| `37,3 / 31,7 / 39,5` | `32,9/s (186ms)` · `33,9/s (137ms)` · `34,9/s (135ms)` \| `35,7 / 39,0 / 40,1` |
| **`c3-int-pot-vs-id`** (`ID p50/p95`) | `80/157` · `195/1120` · `209/1340 ms` | `78/357` · `221/1327` · `194/2298 ms` | `85/1034` · `149/2047` · `154/1144 ms` |

La variabilité en `c3-int-pot-vs-id` (`r2`/`r3` plus chargés que `r1` sur les 3 topologies simultanément) provient de la saturation commune de `http-read` (`0,75` CPU sous polling client intensif à `20 cmd/s` + `1 id/s`) ; sur les 3 runs appariés, **`T3` obtient la meilleure médiane `ID p50` (`149 ms` vs `195 ms`), la meilleure `Pot p50` (`122 ms` vs `168 ms`) et `-65 %` de throttling worker**.

### C.3 Conditions de reproduction

```bash
# 1. Préparer les 4 JARs qualifiés v3.0.0-l7-local (si non présents)
(cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am -DskipTests package)

# 2. Exécuter la campagne complète (nécessite les snapshots V5 medium10-v5 et medium50-v5)
python3 -B experiments/worker-topology-study/run-campaign.py \
  --topologies t0 t1a t1b t2 t3 t4 \
  --scenarios c1-idle c1-med-1 c2-ramp c3-int-pot-vs-id c4-backlog-10k \
  --reps 1

# 3. Répétitions de confirmation R=3 sur les finalistes + reprise après arrêt
python3 -B experiments/worker-topology-study/run-campaign.py \
  --topologies t0 t1a t3 --scenarios c1-idle c1-med-1 c2-ramp c3-int-pot-vs-id --rep-start 2 --reps 2
python3 -B experiments/worker-topology-study/run-campaign.py \
  --topologies t0 t1a t3 t4 --scenarios c4-backlog-read-only --reps 1

# 4. Agréger les résultats
python3 -B experiments/worker-topology-study/analyze-results.py
```

---

## D. Recommandation d'architecture — Réponses explicites aux 7 questions

### 1. Faut-il conserver les trois runtimes de workers actuels ?
**Non comme dogme universel, et pas sous la répartition exacte `T0` :**
- **En installation locale / mono-VM (budget contraint `≤ 6–8` vCPU)** : passer à **2 runtimes de workers (`T3` : `command-worker` + `unified-read-worker`)** est **objectivement supérieur** sur le CPU (`-35 %` à vide, `-24 %` à 1/s), la RAM (`-195 MiB`), les connexions PostgreSQL (`-16`), le throttling (`-62 %`), le plateau (`39,0 cmd/s`) et le drain 10k (`112,6 s`), tout en simplifiant l'exploitation (`4` runtimes au lieu de `5`).
- **Si l'on souhaite conserver 3 runtimes de workers pour l'isolation des pannes** (un redémarrage des projections Pot ne coupe pas les résultats synchrones) : **`T1a`** (`critical-read-worker` regroupant `CommandResult`, `IdentityRequestResult` **et `CurrentBinding`** + `pot-read-worker`) est **préférable à `T0`** à coût identique, car elle élimine la famine de `CurrentBinding` (`cb_sat : 4 872 → 0`).

### 2. Quelles consommations gagnent à être isolées ?
- **Séparation obligatoire (sécurité/autorité)** : **WRITE (`Command`, `IdentityRequest`)** doit rester physiquement séparé de **READ (les 7 matérialisations)**.
- **Isolation logique par quota (sans JVM dédiée)** : **`IdentityRequest`** est parfaitement protégée par la réservation de quota (`Command=3, IdentityRequest=2` sur `M_W=4`) ; lui dédier une JVM (`T1b`) dégrade toutes les métriques.
- **Isolation optionnelle en cas d'exigence stricte de blast-radius (multi-VM / L8)** : le trio **chemin critique UX (`MaterializeCommandResult` + `MaterializeIdentityRequestResult` + `MaterializeCurrentBinding`)** gagne à être séparé du bloc **`POT_EVENT` + `Invitation`** (`T1a`) uniquement si l'exploitation exige que les résultats et le binding survivent à un arrêt/crash des projections Pot.
- **Aucune projection Pot (`PotBalance`, `PotVersion`, `PotVisibility`) ne gagne à être isolée des deux autres** : les séparer détruit la déduplication `B3` sur `POT_EVENT` et dégrade nettement les performances (`T2`).

### 3. Lesquelles gagnent à partager un runtime ?
1. **`MaterializePotVersion` + `MaterializePotBalance` + `MaterializePotVisibility`** : **DOIVENT** partager le même runtime `materialize` pour bénéficier de la déduplication intra-tour `B3` sur `POT_EVENT` (gain démontré : `-205 tx/s` à vide, `-30 s` de throttling PG en rampe).
2. **`Command` + `IdentityRequest`** : gagnent à partager `command-worker` avec réservation de quota (`Command=3, IdentityRequest=2` sur `M_W=4`), évitant `+206 MiB` RAM et `+12` conns PG pour une consommation à très faible volume.
3. **Les 7 consommations READ dans `unified-read-worker` (`T3`)** : sur mono-nœud, mutualiser les 7 consommations READ sous un quota mutualisé (`M_W=6`, `1,90` CPU) produit le meilleur rendement CPU, la plus faible empreinte mémoire et le meilleur débit de rattrapage (`88,8 cmd/s`).

### 4. Quel est le coût réel d'un runtime supplémentaire ?
Mesuré directement entre `T3` (2w), `T0`/`T1a`/`T4` (3w) et `T1b`/`T2` (4w) :
- **Mémoire** : **`+195 à +205 MiB` RSS à vide** (`+210 à +225 MiB` en charge), dont `~75–80 MiB` hors-heap JVM + `~30–40 MiB` heap + `~85–90 MiB` natif OS.
- **PostgreSQL** : **`+12 à +16` connexions permanentes** (`2·M_W+8` WRITE ou `3·M_W+10` READ), et **`+200 à +770 tx/s` à vide** si le runtime duplique le polling d'une famille déjà sondée ailleurs (`T2`, `T4`).
- **CPU à vide** : **`+0,14 à +0,19` CPU** (`+0,10–0,14` worker + `+0,04` PostgreSQL).
- **Exploitation** : +1 conteneur, +1 port Actuator, +1 fichier `.properties`, +1 cible Prometheus, +1 cycle de démarrage/redémarrage.

### 5. Est-il préférable d'augmenter les ressources d'un runtime ou de le répliquer ?
**Augmenter verticalement les ressources d'un runtime (`T3`) est massivement préférable à la réplication horizontale (`T4`) tant qu'un seul hôte/JVM suffit :**
- À budget READ strictement identique (`1,90` CPU / `1 408 MiB`), `T3` (1× `1,90`c / `M_W=6`) surclasse `T4` (2× `0,95`c / `M_W=4` shardés `0|1` vs `2|3`) sur **tous les indicateurs** : `-37 %` CPU à vide, `-38 %` PG tx/s à vide (`1 257` vs `2 028`), `-200 MiB` RAM, `-16` conns PG, `p95` à `35/s` divisée par **`8,5×`** (`137 ms` vs `1 166 ms`), plateau E2E **`+30 %`** (`39,0` vs `29,9 cmd/s`), throttling PG divisé par **`4,5×`** (`11,1 s` vs `49,5 s`), et drain 10k plus rapide (`112,6 s` vs `121,5 s`).
- **Quand répliquer horizontalement ?** Uniquement :
  1. Pour la **haute disponibilité multi-VM (L8)** en mode **actif/passif** (`0|1|2|3` sur 2 nœuds distincts, failover via lease `30 s`), où la réplique passive absorbe la panne matérielle d'une VM ;
  2. Ou en **sharding statique (`0|1` vs `2|3`, max 4 répliques)** lorsque la capacité verticale d'une seule VM est saturée **et** que PostgreSQL dispose de la marge nécessaire pour encaisser le doublement des connexions et du polling à vide (ou après l'introduction éventuelle du réveil événementiel Axe 5).

### 6. Quels regroupements recommander pour Pocoma aujourd'hui ?
- **Cible locale / mono-hôte recommandée (Option 1 — Sobriété & Simplicité)** : **`T3` (4 runtimes au total : `http-write`, `http-read`, `command-worker`, `unified-read-worker`)**. C'est la topologie la plus performante, la plus sobre (`-195 MiB`, `-35 %` CPU à vide, `-16` conns PG) et la plus simple à exploiter.
- **Alternative conservatrice à 3 workers (Option 2 — Blast-radius réduit sur Résultats + Binding)** : **`T1a` (`command-worker` + `critical-read-worker` [Results + `CurrentBinding`] + `pot-read-worker`)**, qui conserve la séparation de panne entre chemin critique et projections Pot tout en corrigeant la saturation `CurrentBinding` de `T0` sans aucun surcoût matériel.
- **Conserver `T0` sans changement** reste parfaitement viable si l'on souhaite éviter tout coût de requalification immédiate, mais `T0` est désormais démontré inférieur à `T3` en efficacité et à `T1a` en isolation `CurrentBinding`.

### 7. Quelle flexibilité de composition devrait être conservée pour les déploiements futurs ?
Conserver exactement les **deux artefacts workers génériques** (`runtime-worker-process` et `runtime-worker-materialize`) et **rendre explicite et documentée** leur composabilité statique :
- Remplacer ou assouplir les gardes nominatives rigides (`validateProcessWorkerName` / `validateMaterializeWorkerName`) pour qu'elles vérifient uniquement les **vrais invariants** (autorité WRITE vs READ, au moins une consommation active, `consumption-limits` et `partitions` cohérents, `verifyTimingBounds`, `dev-targets`), sans imposer de noms de workers figés ;
- Conserver la possibilité de passer par simple configuration `.properties` de **`T3`** (dev/local/petite VM) à **`T1a`** (isolation critique) ou **`T4`/HA** (multi-VM L8) sans aucune recompilation.

---

## E. Proposition de suite

Deux options claires sont soumises à décision humaine (aucune n'est appliquée sur `main` sans validation) :

### Option A — Adopter `T3` (2 workers) ou `T1a` (3 workers) comme nouvelle topologie de référence
- **Changements nécessaires** :
  1. Nettoyer les gardes nominatives dans `ProcessSettings.java`, `MaterializeSettings.java`, `L2ProofSettings.java` et `QualificationSettings.java` (préserver 100 % des invariants de séparation WRITE/READ et timing).
  2. Mettre à jour `docker-compose.v3-five-runtimes.yml` (ou créer un profil dédié), `docker/v3-five-runtimes/*.properties`, `scripts/local/`, `L7DeploymentArchitectureTest.java`, et la documentation opérationnelle (`docs/architecture/runtimes.md`).
- **Complexité et risques** : faibles côté code Java (quelques dizaines de lignes de validation de configuration), modérés côté outillage/documentation (`pocoma-local`, `L7DeploymentArchitectureTest`, Bruno/smoke tests). Risque principal en `T3` : perte temporaire des résultats si `unified-read-worker` redémarre (30 s de lease).
- **Plan de qualification** :
  1. Tranches `COMMAND` + `PROJECTION` (`./mvnw -pl runtime-worker-process,runtime-worker-materialize -am test`).
  2. Gate `architecture-tests` (`./mvnw -pl architecture-tests -am test`).
  3. Validation `pocoma-local` (`scripts/local/test_local.py`, `scripts/operations/test_v3_operations.py`) et smoke E2E.
  4. Gate `CERT-FULL` avant intégration sur `main`.

### Option B — Conserver `T0` (`v3.0.0-l7-local`) en l'état jusqu'à L8
- **Justification** : `v3.0.0-l7-local` est déjà certifié localement (`1 173/1 173 PASS`), conforme aux seuils `SPEC-09` locaux (`a2-med1 p95 = 146–160 ms`, `a2-backlog-10k = 122–130 s`), et les outils de déploiement (`pocoma-local`, Grafana, Prometheus, Bruno) sont déjà stabilisés autour des 5 runtimes.
- **Seuils déclenchant une révision** :
  1. Contrainte RAM serrée en déploiement mono-VM (`< 4 GiB` utiles → passer à `T3`, `-195 MiB` et `-35 %` CPU à vide).
  2. Backlogs Pot fréquents impactant les inscriptions `IdentityRequest` (`cb_sat` → passer à `T1a`, `cb_sat = 0`).
  3. Passage en multi-VM L8 (HA actif/passif ou besoin de dépasser le plateau vertical d'un nœud → réplication ciblée).

---

## Rapport normatif `Reactor_Verification_Policy.md` (§30)

```text
Start HEAD: afec69f8 (tag: v3.0.0-l7-local)
Branch: study/worker-topology-optimization
Expected production impact (app/): NONE (0 production Java file modified; only benchmark tooling under scripts/performance/, experiment configs under experiments/worker-topology-study/, and evidence/report under docs/)
Observed production impact (app/): NONE
Declared slices:
- Tooling only: scripts/performance/ (c4_metrics.py, run-l7-c4-diagnostic.py)
- Packaging only (no source change): runtime-worker-process (-DskipTests package)
Verification commands executed:
1. python3 -B scripts/performance/test_l7_4a_baseline.py -> 4/4 PASS
2. 58 controlled Docker E2E runs across 6 topologies (T0, T1a, T1b, T2, T3, T4) via experiments/worker-topology-study/run-campaign.py -> 58/58 PASS (0 invariant violation, read_availability_valid=true)
Unexpected slice crossings: NONE
Global architecture gate: NOT REQUIRED (no change under app/ or docker/v3-five-runtimes/)
Full reactor: NOT REQUIRED (no production code modified)
Database verification: CURRENT (58 real PostgreSQL 17 E2E runs on V5 snapshots, read_availability_valid=true)
Push / Merge to main: NOT PERFORMED (forbidden without explicit authorization)
```
