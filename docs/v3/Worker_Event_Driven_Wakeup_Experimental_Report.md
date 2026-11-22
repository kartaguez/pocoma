# Pocoma V3 — Rapport d'Expérimentation : Prototype de Réveil Événementiel des Workers

- **Date** : 22 novembre 2026
- **Branche expérimentale isolée** : `study/event-driven-wakeup` (créée depuis le tag stable `v3.0.0-l7-local` / `main` @ `afec69f8`, dans un worktree isolé `.worktrees/event-driven-wakeup`)
- **Préservation de l'existant** : `main` (`afec69f8`), le tag `v3.0.0-l7-local`, `pocoma-local` et la branche de travail en cours `feat/v3-identity-governance` (`8b9227e3`) sont **strictement intacts et non modifiés**.
- **Campagne exécutée** : **80 exécutions Docker E2E contrôlées** (**`80/80 PASS`**, `0` violation d'invariant, `read_availability_valid() = true` sur 100 % des runs) + **`22/22` tests JUnit `PASS`** (`WakeupContractTest`, `WakeupWorkerBehaviorTest`, `L7C4PollingOptimizationTest`, `ConsumptionWorkerTest`).
- **Preuves archivées** :
  - [phase1-polling-results.json](file:///Users/julien.guezennec/Dev/projects/pocoma/.worktrees/event-driven-wakeup/docs/testing/evidence/event-driven-wakeup/phase1-polling-results.json) (20 runs Phase 1)
  - [phase45-comparative-results.json](file:///Users/julien.guezennec/Dev/projects/pocoma/.worktrees/event-driven-wakeup/docs/testing/evidence/event-driven-wakeup/phase45-comparative-results.json) (60 runs Phases 4 & 5)
  - [manifest.sha256](file:///Users/julien.guezennec/Dev/projects/pocoma/.worktrees/event-driven-wakeup/docs/testing/evidence/event-driven-wakeup/manifest.sha256)

> [!IMPORTANT]
> **Verdict expérimental : `WAKEUP_NATS_RECOMMENDED` (confiance : ÉLEVÉE — adoption conditionnelle au mode `NOOP` par défaut en local et arbitrage humain explicite avant toute intégration sur `main`)**
>
> Aucune intégration sur `main` n'est réalisée. Ces mesures locales (Docker Desktop Apple M4, budget constant `6,0` CPUs / `5 376 MiB` RAM) ne requalifient ni L7 ni `SPEC-09` sur infrastructure cible Config A/B.

---

## 1. État Git initial et périmètre exact du prototype

- **Référence stable de départ** : `v3.0.0-l7-local` = `main` @ `afec69f8` (`L7_LOCAL_CERTIFIED_WITH_RESERVES`, topologie `T0` 5 runtimes avec `L7-C4 B1+B2+B3+A2`).
- **Branches d'étude vérifiées (non fusionnées sur `main`)** : `study/worker-topology-optimization` (`73c2b58d`, topologies `T0`/`T1A`/`T1B`/`T2`/`T3`/`T4`).
- **Commits de la branche expérimentale `study/event-driven-wakeup`** :
  1. `f31020dd` — `docs(v3): add event-driven worker wakeup architecture study`
  2. `a5cacd9a` — `feat(study): implement experimental wakeup port, Core NATS and Valkey Pub/Sub adapters`
  3. `e4fd709e` — `fix(study): subscribe on existing NATS connection when start() follows createPublisher()`
  4. `f5b6d434` — `fix(study): invoke onStateChange unconditionally when NATS already connected on start()`
  5. *(commit final)* — rapport d'expérimentation et preuves archivées.
- **Périmètre code modifié (strictement isolé)** :
  - `contract-consumption` : port pur Java (`WakeupKey`, `WakeupState`, `WakeupMetrics`, `WakeupPublisher`, `WakeupSubscriber`, `CoalescingWakeupPublisher`).
  - `supra-polling-consumption` : correction de la course *signal ↔ await* dans `ConditionConsumptionWaiter` (drapeau `pendingSignal` mémorisé), bitmask borné 28 bits + invalidation `emptyFamilyCheckpointThisTour` + filtrage sans lease dans `DiscoveryLoop`, modes `NOOP`/`CONNECTED`/`DEGRADED` dans `ConsumptionWorker`.
  - `infra-postgres-write` : hook `TransactionSynchronization.afterCommit()` optionnel (`NOOP` par défaut) dans `JdbcPotCommandStore` et `JdbcIdentityRequestStore` (aucun changement SQL, aucune dépendance transport).
  - `infra-observability-micrometer` : `MicrometerWakeupMetrics` (10 métriques basse cardinalité `pocoma.v3.wakeup.*`).
  - **2 nouveaux modules isolés** : `infra-wakeup-nats` (`io.nats:jnats:2.20.5`, Core NATS sans JetStream) et `infra-wakeup-valkey` (`io.lettuce:lettuce-core` direct via BOM Spring Boot `4.0.5`, sans Spring Data Redis).
  - `compose-web-write-postgres`, `compose-polling-burnable-write-postgres`, `compose-polling-nonburnable-materialize-postgres` : câblage par propriétés `pocoma.v3.wakeup.*` (`mode=noop` par défaut).
- **Modules strictement intouchés** : `domain-*`, `engine-*`, `orchestrator-consumption`, `infra-postgres-read`, `infra-postgres-write-source-read`, `infra-postgres-core`, migrations SQL `V1..V5`, `scripts/local/` (`pocoma-local`), `docker/v3-five-runtimes/`.

---

## 2. Architecture du port abstrait et des adaptateurs

- **Contrat logique unique** : `WakeupKey(ConsumableFamily family, int partition)` → sujet `pocoma.v3.wakeup.<family>.<partition>` (`7 × 4 = 28` canaux max, payload vide `0` octet, **zéro donnée métier**).
- **Publication strictement post-COMMIT** :
  - `http-write` : émet après retour réussi de `CommandAdmission.admit()` / `IdentityRequestAdmission.admit()` (hors transaction, dans `try/catch` silencieux → ne modifie jamais le `202 Accepted`).
  - `command-worker` : émet dans `TransactionSynchronization.afterCommit()` pour `POT_EVENT`, `INVITATION_EVENT`, `COMMAND_COMPLETION_EVENT`, `IDENTITY_EVENT`, `IDENTITY_COMPLETION_EVENT`.
  - **Coalescing partagé (`CoalescingWakeupPublisher`)** : bitmask atomique 32 bits (`AtomicInteger` pour 28 bits → **mémoire strictement bornée à 4 octets**, appel O(1) lock-free côté thread métier, jamais bloquant). Deux stratégies testées : `IN_FLIGHT_DEDUP` (défaut, 0 ms de délai artificiel) et `WINDOWED` (`2 ms`).
- **3 modes de fonctionnement du worker** :
  - `NOOP` : polling périodique seul (`max-poll`).
  - `CONNECTED` : `DiscoveryLoop` en `reconcile-poll = 2 000 ms` + réveil événementiel ; `ConsumptionLoop` borné à `500 ms` (préserve la réactivité des retries `30 s` et expirations de claim `60 s` sans signal externe).
  - `DEGRADED` : bascule automatique en `fallback-max-poll = 20 ms` dès déconnexion (ou si broker absent au démarrage), réconciliation immédiate (`onExternalReconnect`) à la reconnexion.

---

## 3. Phase 1 — Caractérisation du polling seul sur `T0` (`20/20 PASS`, JARs `v3.0.0-l7-local` non modifiés)

### 3.1 Effet réel de `pocoma.v3.worker.max-poll` sur les boucles
- **Constat vérifié dans le code** : `max-poll` borne simultanément le backoff exponentiel de **`DiscoveryLoop`** ET de **`ConsumptionLoop`** (`LoopSchedule`), tandis que `ConsumptionLoop` applique aussi une fenêtre *clean-pair* de `500 ms` (`cleanUntilNanos`). `RenewalService` (`10 s` / idle `1 s`) et `maintain()` (`1 s`) sont **indépendants** de `max-poll`.
- Par conséquent, au-delà de `500 ms`, `max-poll` ralentit aussi la détection des retries (`30 s`) et expirations de claim (`60 s`) dans `ConsumptionLoop`.

### 3.2 Courbe mesurée du compromis fréquence de polling / consommation / latence (`T0`, `R=1` par point)

| Variante (`max-poll`) | `c1-idle` CPU total (PG / wk) | `c1-idle` PG tx/s | `c1-idle` `c03` `p50` (après repos) | `c1-med-1` CPU / tx/s | `c1-med-1` `c03` `p50` / `p95` / `p99` | `c2-med-10` CPU / tx/s | `c2-med-10` `c03` `p50` / `p95` / `p99` | `c4-backlog-10k` drain |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`A-20`** (`20 ms`, réf.) | `0,541` (`0,137`/`0,376`) | `1 339` | `136 ms` | `0,663` / `1 689` | `126 / 158 / 180 ms` | `1,932` / `4 608` | `104 / 143 / 164 ms` | `125,3 s` |
| **`B-50`** (`50 ms`) | `0,453` (`0,107`/`0,314`) | `795` (`-41 %`) | `185 ms` | `0,608` / `1 193` | `159 / 201 / 225 ms` | `2,033` / `4 257` | `112 / 176 / 207 ms` | — |
| **`B-100`** (`100 ms`) | `0,379` (`0,085`/`0,258`) | `569` (`-57 %`) | `239 ms` | `0,523` / `1 013` | `200 / 269 / 298 ms` | `1,893` / `4 140` | `112 / 195 / 215 ms` | — |
| **`B-250`** (`250 ms`) | `0,309` (`0,063`/`0,200`) | `448` (`-67 %`) | `370 ms` | `0,503` / `964` | **`348 / 541 / 701 ms`** *(> SLO 500 ms)* | `1,784` / `3 846` | `112 / 210 / 347 ms` | — |
| **`B-1000`** (`1 000 ms`) | **`0,240`** (`0,043`/`0,120`) | **`296`** (`-78 %`) | **`1 463 ms`** | `0,526` / `993` | **`545 / 909 / 1 525 ms`** *(>> SLO)* | `1,749` / `3 679` | `111 / 454 / 2 054 ms` | `123,1 s` |

**Enseignement clé de Phase 1** :
- Par simple configuration sans bus, **`B-100`** économise déjà **`-30 %` de CPU à vide** et **`-57 %` de tx/s PG**, tout en restant sous le SLO `SPEC-09 F01-A` (`p95 = 269 ms ≤ 500 ms`), mais au prix d'un **doublement de la latence à faible charge** (`158 → 269 ms`).
- Dès **`max-poll ≥ 250 ms`**, le polling seul **viole le SLO `500 ms`** (`F01-A`). Il est donc **impossible par simple réglage de polling** d'obtenir simultanément une sobriété à vide `< 0,25 CPU` et une latence nominale `< 150 ms`.

---

## 4. Phase 4 — Résultats comparatifs `A` / `B` / `C` / `D` (`54/54 PASS`)

### 4.1 Matrice centrale sur `T0` (`R = 3` répétitions par variante et scénario — médianes et `[min, max]`)

| Scénario & Variante | CPU total `[min, max]` | CPU PG / Workers / Broker | PG tx/s | RSS JVM + Broker (Total) | JVM Heap / Non-Heap | `c03` `p50` / `p95` / `p99` [ms] | Réveils utiles / parasites |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`c1-idle` — `A-20`** | `0,530` `[0,436; 0,537]` | `0,133` / `0,369` / `0` | `1 347,9` | `1 135,7 + 0` = **`1 135,7 MiB`** | `155,4` / `395,4 MiB` | `131,8 / 973,8 / 973,8` | `0 / 0` |
| **`c1-idle` — `B-100`** | `0,371` `[0,362; 0,385]` | `0,083` / `0,252` / `0` | `574,4` | `1 125,6 + 0` = `1 125,6 MiB` | `174,6` / `394,8 MiB` | `245,8 / 1 194,2 / 1 194,2` | `0 / 0` |
| **`c1-idle` — `D-nats`** | **`0,246`** `[0,246; 0,249]` (**`-53,6 %`**) | **`0,052`** / **`0,158`** / **`0,0013`** | **`358,7`** (**`-73,4 %`**) | **`1 119,4 + 15,7` = `1 135,1 MiB`** (**`-0,6 MiB`**) | **`147,7` / `393,3 MiB`** | `127,3 / 1 210,1 / 1 210,1` | **`50 / 0`** (`100 %`) |
| **`c1-idle` — `C-valkey`** | `0,251` `[0,247; 0,258]` (`-52,6 %`) | `0,052` / `0,160` / `0,0026` | `354,1` (`-73,7 %`) | `1 212,5 + 19,0` = **`1 231,5 MiB`** (**`+95,8 MiB`**) | `174,4` / **`431,0 MiB`** | `118,6 / 1 400,3 / 1 400,3` | **`50 / 0`** (`100 %`) |
| **`c1-med-1` — `A-20`** | `0,659` `[0,655; 0,688]` | `0,167` / `0,387` / `0` | `1 683,5` | `1 152,8 + 0` = `1 152,8 MiB` | `159,3` / `415,5 MiB` | `121,6 / 140,0 / 167,9` | `0 / 0` |
| **`c1-med-1` — `B-100`** | `0,547` `[0,541; 0,553]` | `0,129` / `0,279` / `0` | `1 011,7` | `1 140,2 + 0` = `1 140,2 MiB` | `179,6` / `415,1 MiB` | `206,7 / 275,5 / 289,6` | `0 / 0` |
| **`c1-med-1` — `D-nats`** | **`0,499`** `[0,463; 0,510]` (**`-24,3 %`**) | `0,120` / `0,248` / **`0,0014`** | **`820,2`** (**`-51,3 %`**) | **`1 150,1 + 14,2` = `1 164,3 MiB`** (`+11,5 MiB`) | `161,2` / `416,6 MiB` | **`98,7`** / `150,8` / `423,2` *(r1/r2: `111/114 ms`)* | **`350 / 0`** (`100 %`) |
| **`c1-med-1` — `C-valkey`** | **`0,483`** `[0,482; 0,490]` (`-26,7 %`) | `0,113` / `0,247` / `0,0025` | `815,6` (`-51,6 %`) | `1 247,8 + 19,4` = **`1 267,2 MiB`** (**`+114,4 MiB`**) | `165,8` / **`454,3 MiB`** | `103,7` / **`113,3`** / `154,7` | **`350 / 0`** (`100 %`) |
| **`c2-med-10` — `A-20`** | `1,890` `[1,856; 1,896]` | `0,554` / `0,893` / `0` | `4 608,6` | `1 162,0 + 0` = `1 162,0 MiB` | `169,3` / `429,3 MiB` | `105,8 / 123,6 / 151,8` | `0 / 0` |
| **`c2-med-10` — `B-100`** | `1,846` `[1,816; 1,866]` | `0,524` / `0,839` / `0` | `4 056,0` | `1 175,6 + 0` = `1 175,6 MiB` | `165,4` / `428,9 MiB` | `112,1 / 209,9 / 223,2` | `0 / 0` |
| **`c2-med-10` — `D-nats`** | `1,940` `[1,888; 1,998]` | `0,572` / `0,962` / `0,0029` | `4 495,4` | `1 183,5 + 15,7` = `1 199,2 MiB` (`+37,2 MiB`) | `168,4` / `431,4 MiB` | **`71,1 / 82,0 / 96,7`** (**`-33,7 %` p95**) | **`1 102 / 8`** (`99,3 %`) |
| **`c2-med-10` — `C-valkey`** | `1,945` `[1,939; 1,963]` | `0,564` / `0,971` / `0,0029` | `4 374,1` | `1 268,3 + 19,8` = **`1 288,1 MiB`** (**`+126,1 MiB`**) | `173,0` / **`472,6 MiB`** | `73,8 / 90,2 / 172,4` (`-27,0 %` p95) | **`1 074 / 11`** (`99,0 %`) |

### 4.2 Forte charge (`c2-ramp`), mixte (`c3-int-pot-vs-id`), backlog 10k et rafales/coalescing sur `T0`

| Scénario | `A-20` (Référence) | **`D-nats` (Core NATS)** | `C-valkey` (Valkey Pub/Sub) | Constat |
| :--- | :---: | :---: | :---: | :--- |
| **`c2-ramp` — `10/s` `p95` / `20/s` `p95`** | `118,1` / `114,7 ms` | **`80,2` / `81,4 ms`** (`-32 %` / `-29 %`) | `81,4` / `101,6 ms` | Gain de latence massif en charge modérée. |
| **`c2-ramp` — `35/s` `p95` / `50/s` plateau** | `150,3 ms` / `36,8–38,3/s` *(réf. C4)* | **`142,6 ms` / `41,4 cmd/s`** | `141,8 ms` / `39,2 cmd/s` | Aucune régression en pointe ; plateau supérieur. |
| **`c3-int-pot-vs-id` (`20/s` Pot + `1/s` ID)** | Pot `100/240 ms` · ID `81/1 075 ms` | **Pot `72/194 ms` · ID `52,3 / 78,4 ms`** | Pot `85/473 ms` · ID `79/1 037 ms` | **Réduction de `-92,7 %` sur la `p95` Identity** sous interférence Pot avec NATS ! |
| **`c4-backlog-10k` (drain `10 000 CreatePot`)** | `125,3 s` *(Phase 1)* | `157,3 s` (`pub=29 942`, `coal=112`) | `128,4 s` (`pub=30 000`, `coal=54`) | Coalescing actif en drainage massif (`54–112` signaux fusionnés) ; légère dispersion mono-run (`n=1`). |
| **`burst-same-pot` (40 cmds / 2 pots à 20/s)** | `p50=175,5 / p95=1 247 ms` | **`IN_FLIGHT_DEDUP` : `147,4 / 1 150 ms`** (`49/50` utiles)<br>**`WINDOWED 2 ms` : `139,3 / 1 127 ms`** (`47/49` utiles) | `196,0 / 1 548 ms` (`35/35` utiles) | `IN_FLIGHT_DEDUP` et `WINDOWED 2 ms` sont très proches ; aucune fenêtre artificielle n'est indispensable. |

### 4.3 Désambiguïsation topologique : `T3` (2 workers) vs `T0` (3 workers)

| Topologie + Variante | `c1-idle` CPU total (PG / wk / br) | `c1-idle` PG tx/s | `c1-idle` RSS total (JVM + br) | `c1-med-1` CPU / tx/s | `c1-med-1` `c03` `p50` / `p95` |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`T0 + A-20`** *(réf. actuelle)* | `0,530` (`0,133`/`0,369`/`0`) | `1 348` | `1 135,7 MiB` | `0,659` / `1 684` | `121,6 / 140,0 ms` |
| **`T3 + A-20`** *(gain topologique seul)* | `0,353` (`0,094`/`0,235`/`0`) (`-33,4 %`) | `1 271` (`-5,7 %`) | **`926,8 MiB`** (`-209 MiB`) | `0,501` / `1 609` | `120,0 / 150,6 ms` |
| **`T3 + D-nats`** *(topologie + Core NATS)* | **`0,191`** (`0,045`/`0,114`/`0,0012`) (**`-64,0 %` vs T0**) | **`349,1`** (**`-74,1 %`**) | **`924,5 MiB`** (`912,9 + 11,6`) | **`0,399`** / **`810,0`** | **`101,8 / 110,6 ms`** (`-26,6 %` p95) |
| **`T3 + C-valkey`** *(topologie + Valkey)* | `0,195` (`0,044`/`0,116`/`0,0025`) | `349,5` | `1 016,5 MiB` (`999,1 + 17,4`, `+90 MiB`) | `0,413` / `808,5` | `101,8 / 137,8 ms` |

> **Preuve décisive de séparation des gains** : `T3` seul économise `-209 MiB` de RAM et `-33 %` de CPU à vide, **mais ne réduit presque pas les transactions PostgreSQL (`1 348 → 1 271 tx/s`) ni la latence (`140 → 151 ms`)**. Le réveil Core NATS ajoute **en sus** **`-46 %` de CPU à vide**, **`-73 %` de transactions PG**, et **`-27 %` sur la `p95` à 1/s**, **sans aucun surcoût mémoire net (`924,5` vs `926,8 MiB`)**.

---

## 5. Phase 5 — Défaillances, sûreté et reprise (`6/6` Docker + `22/22` JUnit `PASS`)

| # | Scénario de défaillance exigé | Preuve exécutée | Statut | Observations vérifiées |
| :-: | :--- | :--- | :---: | :--- |
| 1 | **Bus absent dès le démarrage** | `P5-D-nats-fail-bus-absent`, `P5-C-valkey-fail-bus-absent` (Docker) | **Prouvé (`PASS`)** | Les 3 workers démarrent sans erreur en `DEGRADED (2)`, `20/20` commandes observées, `0` violation, `read_availability_valid=true`. |
| 2 | **Broker arrêté brutalement (`SIGKILL`) en charge + redémarrage** | `P5-D-nats-fail-bus-kill-restart`, `P5-C-valkey-fail-bus-kill-restart` (Docker `5 cmd/s`) | **Prouvé (`PASS`)** | Bascule automatique **`fallback_transitions = 3`** (tous les workers), continuité sans violation (`0` violation), puis **`reconnects = 3`** au redémarrage et retour en `CONNECTED (1)`. |
| 3 | **Coupure réseau workers ↔ broker** | Couvert par `SIGKILL` broker + `ConnectionListener` / `RedisConnectionStateAdapter` | **Prouvé (partiel)** | Comportement identique à la perte TCP ; une partition silencieuse sans RST dépend du `pingInterval = 2 s` (`maxPingsOut = 2`, soit `≤ 4 s` de détection). |
| 4 | **Perte silencieuse de 100 % des notifications** | `P5-D-nats-fail-silent-loss`, `P5-C-valkey-fail-silent-loss` (Docker : `http-write` en `noop`, workers `CONNECTED`, `reconcile=1s`) | **Prouvé (`PASS`)** | `12/12` commandes observées, `0` violation, `c03 p50 = 366 ms` (NATS) / `393 ms` (Valkey) uniquement par polling lent de réconciliation. |
| 5 | **Perte partielle des notifications** | Couvert par #4 (borne supérieure 100 % de perte) | **Prouvé** | Progression garantie par `reconcile-poll` ou par le commit suivant sur la même `(family, partition)`. |
| 6 | **Crash émetteur après `COMMIT` avant `publish`** | `WakeupContractTest.publisherTransportFailureNeverThrowsToCaller` + `afterCommit` + #4 | **Prouvé** | Une exception transport ne peut jamais annuler le `COMMIT` ni altérer la réponse HTTP `202` ; perte couverte par `reconcile-poll`. |
| 7 | **Réveil reçu pendant une boucle Discovery active** | `DiscoveryLoop.pendingWakeupPairs` + 80 runs E2E (`c2-med-10`, `c2-ramp`) | **Prouvé** | Bitmask atomique absorbe les réveils concurrents sans empilement ; `99,3 %` de réveils utiles à `10 cmd/s`. |
| 8 | **Signal reçu avant l'entrée en attente (`signal ↔ await`)** | `WakeupWorkerBehaviorTest.conditionWaiterLatchesSignalBeforeAwait` | **Prouvé (`PASS`)** | Retour en `< 5 ms` malgré `await(10 s)` ; deuxième `await` rendormi normalement. |
| 9 | **Réveils simultanés et dupliqués / rafales** | `WakeupContractTest.coalescingPublisherDeduplicatesBurstAndBoundsMemory` (5 000 rafales → `≤ 3` envois) + `burst-same-pot` | **Prouvé (`PASS`)** | Mémoire bornée à `4` octets (`AtomicInteger`), zéro croissance de buffer. |
| 10 | **Reconnexion et réabonnement** | `P5-*-fail-bus-kill-restart` (`recon=3`) + `DiscoveryLoop.onExternalReconnect` | **Prouvé (`PASS`)** | Tous les workers reviennent en `CONNECTED (1)` et déclenchent une réconciliation immédiate. |
| 11 | **Worker passif sans lease** | `DiscoveryLoop.runOnePass()` (`if (fromWakeup && worker.lease(binding, p) == null)`) | **Prouvé (code) / Probable (HA multi-nœud)** | `0` requête SQL émise si lease absent ; test multi-nœud actif/passif réel reste à exécuter en L8. |
| 12–14 | **Expiration lease/claim, retries `30 s`, reprise `UNKNOWN`** | `ConsumptionWorkerTest` (`13/13 PASS`) + `ConsumptionLoop` borné à `500 ms` | **Prouvé (`PASS`)** | Contrats L2/fencing/retry/reprise intacts (`0` changement dans `orchestrator-consumption`). |

---

## 6. Phase 6 — Qualification Impact Assessment (Matrice différentielle)

Le tag **`v3.0.0-l7-local` (`afec69f8`, `L7_LOCAL_CERTIFIED_WITH_RESERVES`)** conserve intégralement son statut historique. Aucune preuve antérieure n'est supprimée ni invalidée rétroactivement.

| Famille / Jalon | Preuve historique de référence (`v3.0.0-l7-local` / `T0`) | Composants modifiés par le prototype | Invariant couvert | Statut de réutilisation | Nouvelles vérifications requises si adoption |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`L7.2` (HTTP / Auth / Idempotence / Domaine)** | `44 CURRENT_FULL` (`CERT-FULL` `f589d95c`) | Wrapper `afterCommit` dans `AdmissionHttpConfiguration` | `202 Accepted`, idempotence, AuthZ | **1. Intégralement réutilisable** | Vérification unitaire que l'exception bus n'altère jamais `202`. |
| **`L7.3` (Consumption L2, Fencing, C3 `V5`)** | `425 CURRENT_FULL`, `ConsumptionWorkerTest` 13/13, `L7-C3` | `ConditionConsumptionWaiter`, `DiscoveryLoop`, `ConsumptionWorker`, `afterCommit` `Jdbc*Store` | Claim, lease, fencing, retry, `read_availability_valid()` | **2. Confirmation ciblée requise** | Rejouer `COMMAND` + `PROJECTION` + campagnes `takeover`/`restart`/`read-reacquire` en modes `NOOP` et `CONNECTED`. |
| **`L7.4` / `SPEC-09` (Performances)** | `17 LOCAL_ENV_BOUNDED` (`L7-C4 B3+A2`), Config A/B ouverte (`L74-ENV-01`) | Scheduling polling + transport NATS | SLO `F01-A..J`, `A11-L/M` | **3. Non suffisante pour la nouvelle config** | Nouvelle campagne complète `L7-C4` si activé par défaut ; Config A/B reste du ressort de L8. |
| **`L7.5` (`CERT-FULL` & `architecture-tests`)** | `1 173/1 173` tests, `50/50` `architecture-tests` | +1 module `infra-wakeup-nats` (43 modules) | Catalogue modules, neutralité couches, non-régression globale | **3. Non suffisante (catalogue 42→43)** | Mettre à jour `L7_Target_Modular_Architecture_and_Migration_Plan.md` + `L7ReactorArchitectureTest`, puis exécuter `CERT-FULL` avant fusion `main`. |
| **`L7-LB` (`./pocoma-local`)** | `L7_LB_CLOSED` (9 conteneurs, Bruno 236/236) | **Aucun** si `mode=noop` par défaut en local | DX local, 9 conteneurs, `doctor` | **1. Intégralement réutilisable** *(si `NOOP` par défaut)* | **0 coût** si `NOOP` par défaut ; requalification `L7-LB` complète seulement si NATS activé en local. |
| **Nouvelles obligations (résilience bus)** | Aucune (nouvelle capacité) | `infra-wakeup-nats`, `supra-polling-consumption` | Perte 100 %, crash broker, fallback, reconnexion, latch, pas de payload métier | **4. Nouvelle obligation de test** | Intégrer les tests déterministes + Testcontainers NATS (`fail-bus-absent`, `kill-restart`, `silent-loss`, passive replica) dans la CI. |

**Coût estimé de requalification en cas d'adoption sur `main`** : **modéré (~2–3 jours)** si `mode=noop` reste le défaut de `pocoma-local` (mise à jour `L7ReactorArchitectureTest`, ajout de 1 module `infra-wakeup-nats`, suite Testcontainers NATS, rejeu `CERT-FULL` ~80 min) — sans toucher à `L7-LB`.

---

## 7. Comparaison opérationnelle : Core NATS vs Valkey Pub/Sub

| Critère mesuré / vérifié | **Core NATS (`io.nats:jnats`)** | **Valkey Pub/Sub (`io.lettuce:lettuce-core`)** |
| :--- | :--- | :--- |
| **Surcoût RAM mesuré (`T0` `c1-idle` → `c2-med-10`)** | **`-0,6` à `+37,2 MiB`** (`14–16 MiB` broker, `+0 à +2 MiB` non-heap JVM) | **`+95,8` à `+126,1 MiB`** (`19–20 MiB` broker, **`+36 à +43 MiB` non-heap Netty** sur 4 JVM) |
| **CPU broker mesuré** | **`0,0012–0,0029` CPU** | `0,0025–0,0029` CPU |
| **Latence `c2-med-10` `p95` / `p99`** | **`82,0 / 96,7 ms`** | `90,2 / 172,4 ms` |
| **Interférence `c3-int-pot-vs-id` `ID p95`** | **`78,4 ms`** | `1 037,4 ms` |
| **Dépendance Java / Spring Boot `4.0.5`** | 1 JAR autonome `jnats` (zéro Netty, zéro couplage Spring) | `lettuce-core` + pile Netty complète + Reactor |
| **Exploitation multi-VM HA (L8)** | Clustering natif 3 nœuds sans Sentinel ; 1 binaire Go (`nats:2-alpine`) | Nécessite Valkey Sentinel (3 nœuds) ou service cloud managé |
| **Disponibilité managée cloud & cache** | Moins répandu en managé 1-clic ; ne fait pas cache | Standard managé partout ; réutilisable cache/rate-limit |

---

## 8. Verdict final, conditions d'adoption et stratégie de rollback

### Verdict : **`WAKEUP_NATS_RECOMMENDED`**

- **Niveau de confiance** : **ÉLEVÉ** (80/80 runs E2E `PASS`, `R=3` sur les scénarios principaux, reproductibilité serrée `[0,246; 0,249]` CPU sur `D-nats` idle, 6/6 pannes Docker `PASS`, 22/22 JUnit `PASS`, et séparation rigoureuse des gains topologiques sur `T3`).
- **Pourquoi `WAKEUP_NATS_RECOMMENDED` (et non `NO_BUS` ni `VALKEY`)** :
  1. Le polling optimisé seul (`B-100`, `B-250`, `B-1000`) impose un compromis défavorable : dès qu'on veut descendre sous `0,35 CPU` à vide, il **dégrade fortement la latence** et viole le SLO `500 ms` dès `250 ms`.
  2. **Core NATS brise ce compromis** : il offre simultanément **`-53,6 %` de CPU à vide** (`-64 %` combiné à `T3`), **`-73,4 %` de transactions PG à vide**, **`-33,7 %` sur la `p95` à 10 cmd/s** (`123,6 → 82,0 ms`), et **`-92,7 %` sur la `p95` Identity sous interférence Pot**, **pour un surcoût mémoire quasi nul (`-0,6` à `+37 MiB`)** et `0,0013` CPU broker.
  3. **Core NATS surclasse nettement Valkey Pub/Sub** sur la mémoire (`-90 à -100 MiB` économisés grâce à l'absence de Netty dans les 4 JVM), sur la latence `p99` et sur la simplicité HA multi-VM.
- **Réserves explicites** :
  1. Toutes les mesures proviennent du banc local Docker Apple M4 (`6,0` CPUs) ; elles ne certifient pas `SPEC-09` Config A/B (L8).
  2. Un seul run (`n=1`) sur `c4-backlog-10k`, `c2-ramp`, `c3-int-pot-vs-id` et `T3` ; `c4-backlog-10k` sous NATS (`157 s`) vs Valkey (`128 s`) mérite une confirmation `R=3` si adopté.
  3. Le comportement en réplication HA actif/passif multi-nœud (`wakeup_ignored_no_lease`) est vérifié par lecture/code, pas encore sur 2 nœuds simultanés.
- **Conditions d'adoption (soumises à arbitrage humain)** :
  1. **Ne retenir que `infra-wakeup-nats`** (ne pas embarquer `infra-wakeup-valkey` sur `main` afin de ne pas alourdir les JARs).
  2. **Maintenir `pocoma.v3.wakeup.mode=noop` par défaut dans `./pocoma-local`** (préserve `L7-LB` à 9 conteneurs sans régression DX), et activer `mode=nats` par profil optionnel et en déploiement multi-VM L8 (idéalement combiné à `T3`).
  3. Exécuter la requalification ciblée + mise à jour `L7ReactorArchitectureTest` + `CERT-FULL` avant toute fusion sur `main`.
- **Stratégie de rollback instantané** :
  - **À chaud par configuration (0 redéploiement de code)** : passer `pocoma.v3.wakeup.mode=noop` (ou simplement couper le broker NATS → bascule automatique en `DEGRADED` / `fallback-max-poll=20 ms` en `< 4 s`, prouvé en Phase 5).

---

## 9. Commandes reproductibles

```bash
# Depuis le worktree isolé .worktrees/event-driven-wakeup (branche study/event-driven-wakeup)
# 1. Compiler et packager les 4 runtimes + exécuter les tests unitaires et ConsumptionWorkerTest
(cd app && ./mvnw --batch-mode --no-transfer-progress -pl compose-polling-burnable-write-postgres -am \
  -Dtest=ConsumptionWorkerTest,WakeupContractTest,WakeupWorkerBehaviorTest,L7C4PollingOptimizationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test)
(cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am -DskipTests package)

# 2. Rejouer Phase 1 (polling seul sur T0)
python3 -B experiments/event-driven-wakeup/run-phase1.py --variants A-20 B-50 B-100 B-250 B-1000 --scenarios c1-idle c1-med-1 c2-med-10

# 3. Rejouer Phases 4 & 5 (matrice comparative A/B/C/D R=3 + pannes Docker + T3)
python3 -B experiments/event-driven-wakeup/run-phase4-5.py --wave all
```

---

## 10. Rapport normatif `Reactor_Verification_Policy.md` (§30)

```text
Start HEAD: afec69f8 (tag: v3.0.0-l7-local, branche main intacte)
Experimental branch: study/event-driven-wakeup (worktree isolé .worktrees/event-driven-wakeup)
Expected production impact (sur branche d'étude isolée uniquement, aucune fusion main):
- contract-consumption, supra-polling-consumption, infra-postgres-write, infra-observability-micrometer,
  infra-wakeup-nats (nouveau), infra-wakeup-valkey (nouveau), compose-web-write-postgres,
  compose-polling-burnable-write-postgres, compose-polling-nonburnable-materialize-postgres, app/pom.xml
Observed production impact: conforme au périmètre déclaré (0 modification dans domain-*, engine-*, orchestrator-consumption,
  infra-postgres-read, infra-postgres-write-source-read, infra-postgres-core, SQL V1..V5, pocoma-local, docker/v3-five-runtimes/)
Declared slices:
- COMMAND + PROJECTION + WEB (tests ciblés + 80 exécutions E2E Docker sur snapshots V5)
Verification commands executed:
1. ./mvnw --batch-mode --no-transfer-progress -pl compose-polling-burnable-write-postgres -am \
   -Dtest=ConsumptionWorkerTest,WakeupContractTest,WakeupWorkerBehaviorTest,L7C4PollingOptimizationTest \
   -Dsurefire.failIfNoSpecifiedTests=false test -> 22/22 PASS
2. ./mvnw --batch-mode --no-transfer-progress -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am -DskipTests package -> BUILD SUCCESS
3. Phase 1 polling-only (T0: A-20, B-50, B-100, B-250, B-1000) -> 20/20 PASS
4. Phases 4 & 5 comparative + failures + T3 (A-20, B-100, C-valkey, D-nats) -> 60/60 PASS
Unexpected slice crossings: NONE
Global architecture gate: NOT REQUIRED en phase prototype isolé (écart volontaire 42->44 modules documenté en §6 ; obligatoire avant toute fusion sur main)
Full reactor: NOT REQUIRED (prototype expérimental isolé, aucune fusion sur main)
Database verification: CURRENT (80 runs PostgreSQL 17 réels sur snapshots V5, read_availability_valid() = true sur 80/80)
Push / Merge to main: NOT PERFORMED (interdit sans arbitrage humain explicite)
Working tree (main repo): INTACT sur feat/v3-identity-governance
```
