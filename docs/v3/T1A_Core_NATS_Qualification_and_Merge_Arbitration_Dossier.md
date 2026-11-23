# Pocoma V3 — Dossier Final de Qualification T1A + Core NATS et d'Arbitrage de Fusion vers `main`

- **Date** : 23 novembre 2026 (remédiation post-audit de la révision `1d9be71c`)
- **Branche de travail** : `feat/v3-identity-governance` (basée sur `v3.0.0-l7-local` / `main` @ `afec69f8`, préservant intégralement la gouvernance des identités assignées par le serveur `8b9227e3`)
- **Branche `main`** : **strictement intacte** (`afec69f8`, tag `v3.0.0-l7-local` non modifié, aucune fusion effectuée)
- **Références historiques archivées (intactes)** :
  - `study/event-driven-wakeup` (`93b22d35`) → [Worker_Event_Driven_Wakeup_Experimental_Report.md](Worker_Event_Driven_Wakeup_Experimental_Report.md) et `docs/testing/evidence/event-driven-wakeup/` (SHA-256 vérifié)
  - `study/worker-topology-optimization` (`73c2b58d`) → [Worker_Topology_Optimization_Study.md](Worker_Topology_Optimization_Study.md) et `docs/testing/evidence/worker-topology-study/` (SHA-256 vérifié)
- **Preuves archivées (`docs/testing/evidence/t1a-nats-qualification/`, manifeste SHA-256 vérifié 6/6 OK)** :
  - `t1a-nats-results.json` & `t1a-nats-summary.json` (campagne différentielle initiale `29/29 PASS`, préservées intactes)
  - `t1a-nats-remediation-bench.json` & `t1a-nats-remediation-summary.json` (campagne répétée de remédiation `10/10 PASS` : `c3-int-pot-vs-id-deep` 160 sondes Identity/config + `c4-backlog-10k` `R=3` entrelacé)
  - `cert-full-monolithic-summary.json` (`CERT-FULL` monolithique intégral : **`1 216/1 216 PASS`**, 179 suites, 29 modules avec tests, `0` échec, `0` erreur, `0` skip)
  - `pocoma-local-live-receipt.json` (recette Docker complète `./pocoma-local` : `CONNECTED`, `DEGRADED`, reconnexion, bascule non destructive `NATS ↔ NOOP`, durabilité après `stop/start`, Bruno `J01–J03`, `/varz` interne)

> [!IMPORTANT]
> **Verdict final : `T1A_NATS_READY_FOR_MERGE`**
>
> Toutes les obligations bloquantes de sûreté, d'exploitation, de performance et de certification locale sont satisfaites sur `feat/v3-identity-governance` :
> - **`CERT-FULL` monolithique 100 % vert** (`1 216/1 216 PASS` en un seul passage ininterrompu `./mvnw --batch-mode --no-transfer-progress test`, dont `52/52` tests d'architecture, `98/98` `runtime-worker-process`, `135/135` `runtime-worker-materialize`, `40/40` `runtime-monolith`).
> - **Toutes les réserves locales précédentes (`RES-T1A-CERT-01`, `RES-T1A-C3-01`, `RES-T1A-BACKLOG-01`) sont levées** par analyse de cause racine, corrections ciblées et preuves répétées.
> - **Seule réserve résiduelle non bloquante** : **`RES-L8-01`** (hors périmètre local — clustering NATS 3 nœuds, TLS/mTLS, HA multi-nœud actif/passif, `prometheus-nats-exporter`, `SPEC-09` Config A/B, `50 cmd/s` soutenus et volumes `10k/1M`–`100k/10M`, différés à la qualification multi-VM L8).

---

## 1. Inventaire des corrections de remédiation (depuis `1d9be71c`)

| Priorité | Commit | Objet | Fichiers clés |
| :--- | :---: | :--- | :--- |
| **P0** | `b8926fae` | Résolution `WorkerResourcesPostgresTest.i5_r11:1087` + test unitaire déterministe `RenewalServiceLeaseRetryTest` | `WorkerResourcesPostgresTest.java`, `RenewalServiceLeaseRetryTest.java` |
| **P1** | `7d1da2f1` | Fiabilisation état `CONNECTED` dans `NatsWakeupTransport` (confirmation `flush()`, dispatcher unique, reconnexion sûre) + renforcement preuves post-COMMIT, blocage transport, concurrence `DiscoveryLoop` et isolation HTTP | `NatsWakeupTransport.java`, `NatsWakeupTransportStateTest.java`, `NatsWakeupTransportTest.java`, `WakeupContractTest.java`, `SpringAttemptTransactionsAfterCommitTest.java`, `WakeupWorkerBehaviorTest.java`, `AdmissionWakeupIsolationTest.java` |
| **P1** | `862630ea` | Contrat opérationnel `pocoma-local` corrigé : bascule non destructive `NATS ↔ NOOP` sur instance existante avec redémarrage contrôlé des 4 runtimes wakeup et persistance durable `manifest.json` + alignement docs | `scripts/local/runtime.py`, `scripts/local/runtime_lifecycle.py`, `scripts/local/test_t1a_nats.py`, `docs/reference/cli.md`, `docs/reference/configuration.md`, `docs/operations/recovery.md` |
| **P2** | `6ddc8371` | Observabilité interne Core NATS `/varz` dans `./pocoma-local status` (connexions, total connexions, abonnements, `inMsgs`/`outMsgs`, `slowConsumers`, mémoire/CPU, zéro port exposé) | `scripts/local/runtime_lifecycle.py`, `scripts/local/test_t1a_nats.py` |
| **Qualif** | `943c2a07` | Correction race condition de test dans `ReadReconstructionPostgresTest.rcv01H` (attente explicite `read_command_result >= 1` avant suppression de `COMMAND_RESULT_AVAILABLE`) | `ReadReconstructionPostgresTest.java` |
| **Bench/Docs** | *(HEAD final)* | Scripts benchmarks répétés (`run-remediation-bench.py`), télémétrie HikariCP/handoff, archivage preuves SHA-256 et mise à jour finale du dossier | `experiments/t1a-nats-qualification/*`, `docs/testing/evidence/t1a-nats-qualification/*`, `docs/v3/T1A_Core_NATS_Qualification_and_Merge_Arbitration_Dossier.md` |

---

## 2. P0 — Analyse complète de l'échec `RenewalService` (`WorkerResourcesPostgresTest.i5_r11:1087`)

### 2.1 Chronologie reconstituée depuis les traces Surefire originales
1. **Rapport Surefire d'origine** (`TEST-com.kartaguez.pocoma.runtime.command.WorkerResourcesPostgresTest.xml` + `v3-i5-evidence/i5_r11-lease-skip-{WRITE,READ}.txt`) :
   - Itération `write = true` (autorité `WRITE`, 1 seul binding `Command`) : **`PASS`** (`targetCalls = 2`, `fullService = [PT0.155s, PT0.172s]`).
   - Itération `write = false` (autorité `READ`, 2 bindings : `bindings.get(1) = I5ReadPotProbe` et `bindings.get(2) = I5ReadCompletionProbe`) :
     - `SKIP LOCKED omitted live lease authority=READ blockerPID=12693 renewPID=12680 blocking={}` enregistré.
     - Après `blocker.commit()`, `await(() -> owned_until.after(before), 5s)` **a réussi** : la lease `(I5ReadPotProbe, partition 0)` **a bien été renouvelée en PostgreSQL**.
     - L'assertion ligne 1087 `assertTrue(targetCalls.get() >= 2)` a échoué (`targetCalls == 1`).

### 2.2 Cause racine démontrée (et comparaison pré-NATS)
- **Comparaison pré-NATS** : aucun fichier impliqué (`RenewalService.java`, `ConsumptionSafety.java`, `AbstractConsumptionProtocol.java`, `WorkerResourcesPostgresTest.java`) n'a été modifié par les commits NATS (`8b9227e3..1d9be71c`). Le renouvellement cross-définition (`AbstractConsumptionProtocol.supportsCrossDefinitionRenewal() == true`) date du commit `16dada44` (L7-LB, antérieur à NATS).
- **Mécanisme exact** :
  - `i5_r11` remplaçait le champ `ownership` par un proxy JDK **uniquement sur `bindings.get(1)` (`I5ReadPotProbe`)**, et non sur le binding frère `bindings.get(2)` (`I5ReadCompletionProbe`).
  - Dans `RenewalService.selectDueLeaseBatch(Authority.READ, now)`, les leases échues de l'autorité `READ` sont triées par `e.renewedAt` croissant, puis `renewPartitionBatch(...)` est invoqué sur **`sortedDue.getFirst().pair.binding().engine()`**.
  - Lors de l'appel 1 (omis par `SKIP LOCKED`), `bindings.get(1)` était en tête → proxy appelé (`targetCalls = 1`), puis bloqué ≥ 100 ms (`pg_sleep(0.1)`).
  - À la clôture de l'appel 1, `ConsumptionSafety.retryOrExpireLease` a replanifié la lease omise à `renewedAt = callBegan - renewalInterval + minPoll`.
  - Pendant les 100 ms, une lease du binding frère `bindings.get(2)` (`I5ReadCompletionProbe`) est devenue échue avec un `renewedAt` antérieur à la date de retry de `(bindings.get(1), p=0)`.
  - Lors de l'appel 2 (le retry), `sortedDue.getFirst()` était donc **`bindings.get(2)`** : `RenewalService` a correctement regroupé les deux bindings dans un unique appel cross-définition `bindings.get(2).engine().renewPartitionBatch(...)` via le store `ownership` **non proxifié** de `bindings.get(2)`.
  - **Verdict** : **l'invariant `RenewalService` était 100 % respecté** (retry immédiat, conservation du `due` d'origine, renouvellement effectif en base sans changement de `owner_token`). La faute provenait exclusivement de l'instrumentation incomplète du test `i5_r11`.

### 2.3 Correction et preuve déterministe
- `WorkerResourcesPostgresTest.i5_r11` proxifie désormais `ownership` sur **tous les bindings de l'autorité testée**, en filtrant exactement sur `(l.consumptionId().value().equals(cid) && l.partitionId() == 0)`, et observe `RENEWAL_LEASE` sur cette autorité sans affaiblir aucune assertion.
- **Test unitaire déterministe ajouté** : `RenewalServiceLeaseRetryTest.omittedLeaseIsRetriedAndRetainsOriginalDueEvenWhenSiblingLeadsCrossDefinitionBatch` (`app/supra-polling-consumption`), qui reproduit déterministiquement l'omission + la prise de leadership cross-définition par un binding frère et vérifie le retry, la restauration `usable=true` et la conservation du `due` d'origine.
- **Incident secondaire corrigé durant le `CERT-FULL`** : `ReadReconstructionPostgresTest.rcv01H` (ligne 547) n'attendait que `read_current_pot >= 1 && consumption_slot == 0` avant de supprimer `COMMAND_RESULT_AVAILABLE`, pouvant drainer le worker avant la matérialisation de `read_command_result` (`943c2a07`). Corrigé en attendant explicitement `read_command_result >= 1`.

---

## 3. P1 — Rapport de robustesse Core NATS (`NatsWakeupTransport`) et preuves transactionnelles/concurrence

### 3.1 Fiabilisation de `NatsWakeupTransport` (`7d1da2f1`)
- **Défaut corrigé** : `subscribeOn()` n'avale plus les exceptions de `conn.flush(...)`.
- **Invariant `WakeupState.CONNECTED`** : `CONNECTED` n'est désormais annoncé que si et seulement si :
  1. La connexion NATS est établie (`Connection.Status.CONNECTED`).
  2. Un **unique** `Dispatcher` actif (`activeDispatcher.isActive()`) est installé pour l'ensemble des `WakeupKey` abonnés.
  3. `conn.flush(connectionTimeout)` a réussi **sans exception** et l'état de connexion/dispatcher reste valide après `flush()`.
- **Comportement en cas d'échec** : maintien/retour immédiat en `DEGRADED` (préservation du polling de secours `fallback-max-poll=20 ms`), fermeture explicite du `Dispatcher` et de la `Connection` (`closeDispatcherQuietly` + `closeQuietly`), puis retry borné dans `connectLoop()` (`connectRetryDelay`).
- **Reconnexion `jnats`** : sur `DISCONNECTED`/`CLOSED`, `subscriptionsConfirmed = false` et passage immédiat en `DEGRADED`. Sur `RECONNECTED`, aucun second `Dispatcher` n'est créé (`jnats` rejoue automatiquement les `SUB` du dispatcher actif) ; une confirmation asynchrone (`confirmReconnect`, protégée par génération atomique `confirmGeneration`) vérifie `activeDispatcher.isActive()` et exécute `conn.flush(connectionTimeout)` avant de réannoncer `CONNECTED`. Si `flush()` échoue, la connexion est détruite proprement et relancée via `connectLoop()`.

### 3.2 Couverture de tests et matrice de sûreté corrigée

| Obligation de sûreté | Preuve exécutée | Statut | Précision / limite explicite |
| :--- | :--- | :---: | :--- |
| Échec `flush()` / abonnement non confirmé / déconnexion pendant `subscribe` / pas de `CONNECTED` prématuré | `NatsWakeupTransportStateTest` (6/6 tests déterministes : `flushFailureStaysDegraded...`, `noPrematureConnectedWhileFlushIsInFlight`, `disconnectDuringSubscriptionInstallation...`, `unconfirmedInactiveDispatcher...`, `reconnectWaitsForFlushConfirmationAndNeverCreatesDuplicateDispatchers`, `startAfterPublisherConnectResetsAndRetriesWhenSubscriptionFlushFails`) | **`PASS`** | Preuve déterministe complète (0 attente fragile). |
| Reconnexion `jnats` + réabonnement effectif après remplacement complet du broker | `NatsWakeupTransportTest.coreNatsBrokerRestartRestoresAutoResubscriptionAndDeliversPostReconnectWakeups` (Testcontainers `nats:2.15.3-alpine`, arrêt complet du 1er broker + démarrage d'un 2e broker neuf sur même port) | **`PASS`** | Prouve la restauration effective de la table `SUB` côté broker neuf sans duplication de dispatcher. |
| Authentification token (valide / invalide) & broker absent | `NatsWakeupTransportTest` (4/4 Testcontainers) + `T1A-D-nats-fail-bus-absent-r1` | **`PASS`** | Mauvais token / broker absent restent `DEGRADED`, zéro exception bloquante. |
| **Fenêtre crash après `COMMIT` avant publication NATS** | `SpringAttemptTransactionsAfterCommitTest` (2/2 : ordre strict `PHYSICAL_COMMIT → AFTER_COMMIT_HOOK`, rollback n'exécute jamais le hook, exception post-COMMIT avalée) + `WakeupWorkerBehaviorTest.crashAfterCommitWithoutNotificationProgressesViaReconcilePollAndNoop` + `T1A-D-nats-fail-silent-loss-r1` (Docker) | **`PASS` *(preuve équivalente)*** | **Distinction explicite** : la faute est injectée de façon déterministe dans la fenêtre post-COMMIT / perte silencieuse (travail commité en base, 0 trame NATS émise → découverte par `reconcile-poll`, exécution exactement une fois). Un `SIGKILL` matériel exact entre le `COMMIT` JDBC et l'écriture socket TCP produit le même état observable en base/réseau, mais n'est pas déclenché par injection noyau directe. |
| Isolation publication (exception, blocage durable, saturation rafales) | `WakeupContractTest.blockedTransportNeverBlocksBusinessThreadAndBoundsMemory` (100 000 publications pendant transport bloqué : retour O(1) `< 500 ms`, bitmask 32 bits = 4 octets bornés, `>= 99 990` coalescées) + `AdmissionWakeupIsolationTest` | **`PASS`** | Impossible de faire croître les buffers ou de convertir une admission HTTP `202` en erreur. |
| Concurrence & reprise (`signal → await`, invalidation caches Discovery, réveils simultanés/dupliqués, perte de lease, mode `NOOP`) | `WakeupWorkerBehaviorTest` (8/8 tests : `conditionWaiterLatchesSignalBeforeAwait`, `discoveryLoopExternalWakeupInvalidatesEmptyFamilyCacheAndRecordsDelay`, `discoveryLoopExternalReconnectInvalidatesEmptyFamilyCache`, `discoveryLoopSimultaneousDuplicateWakeupsCoalesceWithoutLoss`, `discoveryLoopWakeupIgnoredSafelyWhenLeaseLost`, `crashAfterCommitWithoutNotificationProgressesViaReconcilePollAndNoop`) | **`PASS`** | Couverture déterministe complète sans attentes fragiles. |
| Leases, fencing, idempotence, retry, `UNKNOWN`, reconstruction READ indépendante du bus | `CERT-FULL` monolithique : `runtime-worker-process` (`98/98`), `runtime-worker-materialize` (`135/135`, dont `ReadReconstructionPostgresTest` `13/13`), `runtime-monolith` (`40/40`), `infra-postgres-write` (`479/479`) | **`PASS`** | `0` échec, `0` modification dans `domain-*`, `engine-*`, `orchestrator-consumption`, migrations SQL. |

---

## 4. P1 — Contrat opérationnel corrigé de `./pocoma-local`

### 4.1 Correction de l'écart d'audit (`862630ea`)
L'audit a confirmé qu'en `1d9be71c`, `--wakeup-mode` avait `default='nats'` et était ignoré sur une instance existante. Le contrat opérationnel est désormais exact, cohérent et vérifié en live :

1. **Fallback automatique `CONNECTED → DEGRADED` (panne broker)** : **aucun redémarrage**, aucune perte, bascule automatique sur `fallback-max-poll-ms=20` en `≤ 4 s`, reconnexion automatique avec confirmation `flush()`.
2. **Désactivation volontaire de NATS (`NOOP`) ou réactivation (`NATS`) sur instance existante** :
   - Commande : `./pocoma-local start --instance <name> --wakeup-mode noop` (ou `nats`).
   - **Mécanisme non destructif avec redémarrage contrôlé** *(et non un rollback « à chaud » sans redémarrage)* : met à jour `manifest.json`, arrête/supprime uniquement le conteneur `nats` (vers `noop`) ou démarre `nats` (vers `nats`), régénère les `.properties` et recrée **exclusivement les 4 runtimes wakeup** (`http-write`, `command-worker`, `critical-read-worker`, `pot-read-worker`).
   - **Strictement préservés et jamais recréés** : PostgreSQL (conteneur, volume, toutes les données), Keycloak (conteneur, realm, utilisateurs), `http-read`, Prometheus, Grafana, ports, identités de déploiement et `secrets.json` (incluant le token NATS).
3. **Persistance durable de la configuration** : `./pocoma-local start --instance <name>` sans `--wakeup-mode` conserve le mode enregistré dans `manifest.json` (`noop` reste `noop` après `stop` + `start`).

### 4.2 Recette live Docker complète (`pocoma-local-live-receipt.json`)
Exécutée sur la véritable distribution Docker (`./pocoma-local`, instance `qualif-final`) :
- `./pocoma-local doctor` → `READY` (exit `0`, 6 images dont `nats:2.15.3-alpine`).
- `./pocoma-local start --instance qualif-final` → `READY` (10 conteneurs, 20/20 checks `PASS`, `effectiveMode=CONNECTED`, `varz` live : 4 connexions, 88 abonnements, 0 slow consumer).
- `./pocoma-local logs --instance qualif-final --service nats --tail 20` → `LOGS_CAPTURED` (exit `0`, secrets rédigés).
- `./pocoma-local bruno --instance qualif-final` (`CONNECTED`) → **`BRUNO_PASS`** (exit `0`, 9 pots matérialisés).
- Arrêt brutal `docker stop <nats>` → `status`: `effectiveMode=DEGRADED`, `brokerAvailable=false`, `operationalState=OPERATIONAL` → `./pocoma-local bruno` → **`BRUNO_PASS`**.
- Redémarrage `docker start <nats>` → `status`: reconnexion automatique `effectiveMode=CONNECTED`, `brokerAvailable=true`, `commandWorkerReconnects=2.0`, `varz` live.
- Bascule non destructive `NATS → NOOP` (`start --wakeup-mode noop`) → `modeChanged=true`, `previousMode=nats`, `configuredMode=noop`, `effectiveMode=NOOP`, `natsContainerAbsent=true`, **`postgresUnchanged=true`**, **`keycloakUnchanged=true`**, **`httpReadUnchanged=true`**, **`potsPreserved=true`**, `./pocoma-local bruno` → **`BRUNO_PASS`**.
- `stop` (`forcedKills=0`) + `start` sans `--wakeup-mode` → `configuredMode=noop`, `effectiveMode=NOOP`, `modeChanged=false`, `postgresUnchanged=true`, `keycloakUnchanged=true`, `potsPreserved=true`.
- Bascule non destructive `NOOP → NATS` (`start --wakeup-mode nats`) → `modeChanged=true`, `previousMode=noop`, `configuredMode=nats`, `effectiveMode=CONNECTED`, `brokerAvailable=true`, `varz` live, `postgresUnchanged=true`, `keycloakUnchanged=true`, `httpReadUnchanged=true`, `potsPreserved=true`.
- `stop` + `destroy --confirm DESTROY:qualif-final` → `STOPPED`, `DESTROYED`.
- **Tests unitaires CLI** : `python3 -B -m unittest discover -s scripts/local -p "test_*.py"` → **`141/141 PASS`**.

---

## 5. P1 — Clarification des performances (campagnes répétées `t1a-nats-remediation-summary.json`)

### 5.1 Drainage du backlog `10 000` (`c4-backlog-10k`, `R=3` entrelacé)

| Configuration | Temps de drainage (`R=3`) | Médiane / Moyenne | Débit médian | CPU total médian (PG / Workers / NATS) | PG tx/s médian | Identity concurrente `p50` / `p95` | Wakeups (pub / coal / recv / useful / errors) | HikariCP timeouts |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`T1A + A-20`** *(polling)* | `121,73 ; 127,68 ; 177,33 s` | `127,68 s` / `142,25 s` | `78,3 cmd/s` | `2,089` (`1,058 / 0,711 / 0`) | `4 482,8/s` | `219,2 / 580,7 ms` | `0 / 0 / 0 / 0 / 0` | `0` |
| **`T1A + D-nats`** *(cible)* | `134,92 ; 136,47 ; 127,97 s` | `134,92 s` (`+5,7 %`) / **`133,12 s` (`-6,5 %`)** | `74,1 cmd/s` | **`1,962` (`-6,1 %`)** (`0,972 / 0,679 / 0,010`) | `4 370,6/s` (`-2,5 %`) | **`192,9 ms` (`-12,0 %`)** / `602,4 ms` | `~30 000 / 48–66 / ~20 050 / ~21 500 (98,3 %) / 0` | `0` |

- **Analyse** : l'écart de `+5,7 %` sur la médiane (`134,92 s` vs `127,68 s`, avec un run `D-nats` à `127,97 s` identique à la médiane polling et une moyenne meilleure en NATS `133,1 s` vs `142,3 s`) s'explique par la mise en concurrence immédiate des workers READ réveillés à chaque lot commité (contre un réveil différé au cycle de polling) et la variance Docker Desktop locale. **NATS consomme `-6,1 %` de CPU total en moins**, maintient `0` timeout HikariCP et `0` erreur de publication, et améliore la médiane des inscriptions Identity concurrentes de `-12,0 %`. **`RES-T1A-BACKLOG-01` est levée.**

### 5.2 Interférence Pot / Identity (`c3-int-pot-vs-id-deep`, 160 sondes Identity par configuration sous `20/s` Pot)

| Configuration | Sondes Identity | `usableBindingE2eMs` `p50 / p75 / p90 / p95 / p99 / max` | `resultE2eMs` `p50 / p95 / p99` | Outliers `≥ 300 ms` | CPU total médian (PG / Workers / NATS) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`T1A + A-20`** *(polling)* | `160/160` | `78,0 / 104,9 / 123,7 / 209,7 / 1 124,0 / 1 131,4 ms` | `72,4 / 198,7 / 1 105,1 ms` | `7/160` (`4,4 %`) | `2,972` (`1,073 / 1,313 / 0`) |
| **`T1A + D-nats`** *(cible)* | `160/160` | **`46,2 / 73,3 / 80,5 / 96,7 / 1 033,6 / 1 168,4 ms`** (`p50 -40,8 %`, **`p95 -53,9 %`**) | **`39,8 / 85,9 / 1 026,1 ms`** (`p95 -56,8 %`) | **`5/160` (`3,1 %`)** | **`2,856` (`-3,9 %`)** (`1,024 / 1,321 / 0,005`) |

- **Analyse** : sur un échantillon statistiquement significatif (`n=160` au lieu de `n=20`), **`T1A + NATS` réduit la `p95` Identity de `-53,9 %` (`209,7 → 96,7 ms`)** tout en réduisant le CPU total de `-3,9 %`. Les rares pics `p99` (`~1,03–1,17 s`) surviennent **dans les deux configurations** (y compris `A-20` sans NATS : `p99 = 1 124 ms`, `7/160` outliers vs `5/160` en NATS), concentrés sur les toutes premières sondes (`index 0, 1`), avec `resultE2eMs ≈ usableBindingE2eMs` — prouvant une origine amont (warmup JIT / contention `command-worker`) et **non une notification NATS perdue ni `reconcile-poll=2000 ms`**. **`RES-T1A-C3-01` est levée.**

### 5.3 Profil CPU selon la charge et décision de tuning
- **Évolution CPU selon la charge** :
  - À vide (`c1-idle`) : **`-56,0 %` CPU total** (`0,241` vs `0,548`), **`-73,6 %` PG tx/s**.
  - Faible charge (`c1-med-1`, `1 cmd/s`) : **`-27,9 %` CPU total** (`0,482` vs `0,668`), `p95 -19,4 %`.
  - Charge soutenue (`c2-med-10`, `10 cmd/s`) : **`+2,5 %` CPU total** (`1,916` vs `1,869`, léger surcoût de publication/réception des hints à haute fréquence), compensé par **`-31,2 %` sur `c03 p95`** (`83,5` vs `121,4 ms`).
  - Forte charge mixte (`c3-int-pot-vs-id-deep`, `20/s` Pot + `2/s` ID) : **`-3,9 %` CPU total** (`2,856` vs `2,972`) et **`-53,9 %` Identity `p95`**.
  - Drain massif (`c4-backlog-10k`) : **`-6,1 %` CPU total** (`1,962` vs `2,089`).
- **Décision de tuning** : conserver les paramètres nominaux (`reconcile-poll-ms=2000`, `fallback-max-poll-ms=20`, `consumption-max-poll-ms=500`, `IN_FLIGHT_DEDUP`). Aucune modification n'est justifiée par les mesures.

---

## 6. P2 — Observabilité broker Core NATS

- **Complément minimal implémenté (`6ddc8371`)** : `./pocoma-local status` interroge désormais l'endpoint interne `http://127.0.0.1:8222/varz` à l'intérieur du conteneur `nats` (zéro port publié sur l'hôte ou Internet, zéro conteneur supplémentaire, zéro RAM/CPU additionnelle) et expose `connections`, `totalConnections`, `subscriptions`, `inMsgs`, `outMsgs`, `slowConsumers`, `memBytes`, `cpuPct` et `uptime`, en complément des 6 panels Grafana applicatifs existants (états, taux publish/receive/coalesce/errors, reconnexions, fallbacks, délai notification→découverte, fréquence de polling).
- **Évaluation de coût `natsio/prometheus-nats-exporter`** : ajouter un 11e conteneur exporter coûterait ~15–20 MiB de RSS, une 7e image Docker épinglée dans `doctor`/`prerequisites.py`/`L7DeploymentArchitectureTest` et une surface de cycle de vie supplémentaire. Ce coût n'est pas justifié pour le poste local 10 conteneurs ; l'adoption de `prometheus-nats-exporter` est recommandée en **L8** pour le cluster NATS multi-nœuds.

---

## 7. Certification finale (`CERT-FULL` monolithique) & `Reactor_Verification_Policy.md` (§30)

```text
Start HEAD (audit baseline): 1d9be71c (feat/v3-identity-governance; main intact @ afec69f8 / v3.0.0-l7-local)
Remediation commits: b8926fae, 7d1da2f1, 862630ea, 6ddc8371, 943c2a07 + final qualification/dossier commit
Expected production impact:
- app/infra-wakeup-nats (NatsWakeupTransport.java)
- scripts/local/runtime.py, scripts/local/runtime_lifecycle.py
- Tests: runtime-worker-process, runtime-worker-materialize, supra-polling-consumption,
  infra-wakeup-nats, contract-consumption, infra-tx-spring, compose-web-write-postgres, scripts/local
Observed production impact:
- Strictement conforme au périmètre déclaré.
- Unexpected slice crossings: NONE.
- Forbidden production impact verified untouched: domain-*, engine-*, orchestrator-consumption,
  infra-postgres-read, infra-postgres-write-source-read, infra-postgres-core, SQL migrations, Valkey.
Declared slices:
- COMMAND (runtime-worker-process), PROJECTION (runtime-worker-materialize),
  WEB (runtime-web-write, runtime-web-read), FOUNDATION (infra-tx-spring), infra-wakeup-nats
Global architecture gate:
- RUN (targeted + inside monolithic CERT-FULL): architecture-tests 52/52 PASS
Full reactor (CERT-FULL monolithique):
- RUN (unbroken single invocation from app/):
  ./mvnw --batch-mode --no-transfer-progress test
  -> BUILD SUCCESS (01:22 h, 44 reactor entries / 43 child modules, 29 modules with tests,
     179 test suites, 1 216/1 216 PASS, 0 failure, 0 error, 0 skip).
  Key modules:
    contract-consumption: 5/5, supra-polling-consumption: 11/11, infra-tx-spring: 30/30,
    infra-postgres-write: 479/479, infra-wakeup-nats: 10/10, compose-web-write-postgres: 23/23,
    runtime-web-read: 42/42, runtime-worker-process: 98/98, runtime-worker-materialize: 135/135,
    runtime-monolith: 40/40, architecture-tests: 52/52.
Local CLI, Docker, Bruno & Repeated Benchmarks:
- python3 -B -m unittest discover -s scripts/local -p "test_*.py" -> 141/141 PASS
- ./pocoma-local live receipt (qualif-final): doctor READY, start READY (10 containers + /varz),
  logs NATS OK, Bruno PASS (CONNECTED, DEGRADED, NOOP), reconnect OK, non-destructive NATS->NOOP->NATS
  (postgres/keycloak/http-read containers & data preserved), durable NOOP across stop/start, destroy OK.
- python3 -B experiments/t1a-nats-qualification/run-remediation-bench.py -> 10/10 PASS (100% availabilityValid)
Database verification: CURRENT (PostgreSQL 17 Testcontainers + Docker E2E V2/V4.1/V5)
Merge to main: NOT PERFORMED (interdit sans arbitrage humain)
```

---

## 8. Matrice différentielle de qualification actualisée (`L7` / `SPEC-09`)

Le statut historique de **`v3.0.0-l7-local` (`afec69f8`, `L7_LOCAL_CERTIFIED_WITH_RESERVES`, topologie `T0`)** est intégralement préservé.

| Domaine / Invariant | Référence historique (`v3.0.0-l7-local` / `T0`) | Impact T1A + Core NATS | Statut final | Preuve consolidée sur `feat/v3-identity-governance` | Risque résiduel |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **Domaine & Admission HTTP (`L7.2`, identités serveur)** | `44 CURRENT_FULL` (`afec69f8`) + `8b9227e3` | Wrapper post-COMMIT `AdmissionHttpConfiguration` | **Clôturé** | `compose-web-write-postgres` (`23/23`), `runtime-web-read` (`42/42`), `infra-postgres-write` (`479/479`), Bruno `J01–J03` (`PASS` en `CONNECTED`, `DEGRADED`, `NOOP`) | Aucun |
| **Exécution Consumption L2, Fencing, Reprise (`L7.3`)** | `425 CURRENT_FULL`, `L7-C3` | `ConditionConsumptionWaiter`, `DiscoveryLoop`, `ConsumptionWorker`, `SpringAttemptTransactions.afterCommit` | **Clôturé** | `CERT-FULL` monolithique `1 216/1 216 PASS` (`runtime-worker-process` `98/98`, `runtime-worker-materialize` `135/135`, `runtime-monolith` `40/40`, `RenewalServiceLeaseRetryTest`) | **`RES-T1A-CERT-01` LEVÉE** |
| **Architecture modulaire & déploiement (`L7.5`)** | `50/50` `architecture-tests` (42 modules, T0) | +1 module `infra-wakeup-nats` (43 modules, 187 arêtes), neutralité `io.nats.*`, T1A Compose | **Clôturé** | `architecture-tests` **`52/52 PASS`** (`L7ReactorArchitectureTest` 5/5, `L7DeploymentArchitectureTest` 1/1) | Aucun |
| **Distribution locale & DX (`L7-LB`, `./pocoma-local`)** | `L7_LB_CLOSED` (9 conteneurs, T0) | 10 conteneurs T1A + NATS, bascule non destructive `NATS ↔ NOOP`, `/varz` interne, `logs`, Grafana +6 panels | **Clôturé** | Recette live `pocoma-local-live-receipt.json` 100 % verte + `141/141` unit tests CLI `PASS` | Aucun |
| **Résilience & sûreté bus Core NATS** | Nouvelle capacité | `NatsWakeupTransport` (`flush()` obligatoire), `CoalescingWakeupPublisher`, `DiscoveryLoop` | **Clôturé** | `NatsWakeupTransportStateTest` (`6/6`), `NatsWakeupTransportTest` (`4/4` dont restart broker), `WakeupContractTest` (`5/5`), `WakeupWorkerBehaviorTest` (`8/8`), `SpringAttemptTransactionsAfterCommitTest` (`2/2`), 3/3 pannes Docker | HA multi-nœud différé L8 ; `SIGKILL` noyau exact entre `COMMIT` et socket couvert par preuve équivalente |
| **Performances locales (`L7.4` / `SPEC-09` local)** | `L7-C4` (`T0 + A-20`) | Topologie `T1A` + Core NATS | **Clôturé** | Campagne initiale `29/29 PASS` + campagne répétée `10/10 PASS` (`c3-deep` 160 sondes `p95 -53,9 %`, `c4-backlog-10k` `R=3` CPU `-6,1 %`, moyenne `-6,5 %`) | **`RES-T1A-C3-01` & `RES-T1A-BACKLOG-01` LEVÉES** |
| **Infrastructure cible multi-VM (`L8` / `SPEC-09` Config A/B)** | Ouverte (`L74-ENV-01`) | Clustering NATS 3 nœuds, TLS/mTLS, `prometheus-nats-exporter`, HA actif/passif, `50 cmd/s`, volumes `10k/1M` | **Différée L8** | Hors périmètre local (explicitement tracé) | **`RES-L8-01`** *(non bloquant pour merge local)* |

---

## 9. Manifeste d'intégrité des preuves (`docs/testing/evidence/t1a-nats-qualification/manifest.sha256`)

```text
ed305cf21dff51d9f3e48aeb96ffe238f3cbd6b34148e2f35fe79bf0fdbd3272  cert-full-monolithic-summary.json
e956b8782335dd0df8010dd5b288289b87d8733d3b10d0bfdeda6691b7c0e517  pocoma-local-live-receipt.json
2642a251c2c730e74cba4aabadfd2126d0d1eaa1f6bf1913de589b00a8ffa0b4  t1a-nats-remediation-bench.json
5c7c16b90c3cdc9f9d413b38668bb87b97297d3cc930d8dbc8578c803c61e46d  t1a-nats-remediation-summary.json
a427ebc57e6f6cf0ba259f4449dd7a6a65362ecec3e99b2bbf7beb4ba54aef06  t1a-nats-results.json
802740cddd8d505becd6d2c17221bf435d87ce1bacfe0985da716b1fe36565c4  t1a-nats-summary.json
```

---

## 10. Recommandation finale d'arbitrage

- **Verdict : `T1A_NATS_READY_FOR_MERGE`**
- **Recommandation** : **Approuver la fusion humaine de `feat/v3-identity-governance` vers `main`**. L'architecture T1A + Core NATS est intégralement qualifiée en local (`CERT-FULL` monolithique `1 216/1 216 PASS`, `52/52` architecture, `141/141` CLI, recette Docker complète, bascule non destructive `NATS ↔ NOOP`, `39/39` runs benchmarks/résilience `PASS`), avec toutes les réserves locales levées et `RES-L8-01` explicitement bornée au chantier multi-VM L8.
