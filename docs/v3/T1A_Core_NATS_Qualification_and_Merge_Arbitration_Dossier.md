# Pocoma V3 — Dossier Final de Qualification T1A + Core NATS et d'Arbitrage de Fusion vers `main`

- **Date** : 23 novembre 2026 (clôture définitive T1A + Core NATS post-`39923db0`)
- **Branche de travail** : `feat/v3-identity-governance` (basée sur `v3.0.0-l7-local` / `main` @ `afec69f8`, préservant intégralement la gouvernance des identités assignées par le serveur `8b9227e3`)
- **Révision Git qualifiée (`app/`)** : **`6f08b8f66b2cb4e37fd84eafe570394d2c0174da`** (`CERT-FULL` monolithique `1 220/1 220 PASS`, 0 modification dans `app/` après ce commit)
- **Branche `main`** : **strictement intacte** (`afec69f8`, tag `v3.0.0-l7-local` non modifié, aucune fusion effectuée)
- **Références historiques archivées (intactes)** :
  - `study/event-driven-wakeup` (`93b22d35`) → [Worker_Event_Driven_Wakeup_Experimental_Report.md](Worker_Event_Driven_Wakeup_Experimental_Report.md) et `docs/testing/evidence/event-driven-wakeup/` (SHA-256 vérifié)
  - `study/worker-topology-optimization` (`73c2b58d`) → [Worker_Topology_Optimization_Study.md](Worker_Topology_Optimization_Study.md) et `docs/testing/evidence/worker-topology-study/` (SHA-256 vérifié)
- **Preuves archivées (`docs/testing/evidence/t1a-nats-qualification/`, manifeste SHA-256 vérifié 8/8 OK)** :
  - `t1a-nats-results.json` & `t1a-nats-summary.json` (campagne différentielle initiale `29/29 PASS`, préservées intactes)
  - `t1a-nats-remediation-bench.json` & `t1a-nats-remediation-summary.json` (campagne répétée `10/10 PASS` : `c3-int-pot-vs-id-deep` 160 sondes Identity/config + `c4-backlog-10k` `R=3` entrelacé)
  - `cert-full-monolithic-summary.json` (archive historique `CERT-FULL` monolithique sur `943c2a07` : `1 216/1 216 PASS`, préservée intacte)
  - `cert-full-closure-6f08b8f6-summary.json` (**nouvelle preuve finale** : `CERT-FULL` monolithique non filtré sur le code final `6f08b8f6` : **`1 220/1 220 PASS`**, 179 suites, 29 modules avec tests, 44/44 projets du réacteur, `0` échec, `0` erreur, `0` ignoré, `01:22 h`, `BUILD SUCCESS`)
  - `pocoma-local-live-receipt.json` (recette Docker nominale complète `./pocoma-local`)
  - `audit-final-recovery-and-subs-receipt.json` (invariance des **88 abonnements** via `/connz?subs=detail` & `/subsz?subs=1` sur 4 cycles + récupération live après transition `NATS ↔ NOOP` interrompue dans les deux sens)

> [!IMPORTANT]
> **Verdict final : `T1A_NATS_READY_FOR_MERGE`**
>
> L'unique obstacle résiduel (échec intermittent de `ReadReconstructionPostgresTest.readReplayPreservesExactValuesAndCatchesUpWithNewWritePublication`) a été diagnostiqué à la cause racine, corrigé sans affaiblissement de l'oracle et sans aucune modification du code de production, démontré de manière déterministe sur PostgreSQL 17, puis qualifié par une gate monolithique **`CERT-FULL` 100 % verte (`1 220/1 220 PASS`)** sur le commit final `6f08b8f6`.
> Seules deux réserves explicites et **non bloquantes** déjà arbitrées subsistent :
> 1. **`RES-T1A-BACKLOG-01` (Faible — non bloquant)** : sur le drainage de backlog `10 000` (`R=3`), la médiane reste à `134,92 s` en NATS vs `127,68 s` en polling (`+5,7 %`) ; compromis observé (CPU `-6,1 %`, latence Identity concurrente `p50 -12,0 %`, mais gain de débit de drainage non démontré sur cette campagne).
> 2. **`RES-L8-01` (Hors périmètre local — non bloquant)** : clustering NATS 3 nœuds, TLS/mTLS, `prometheus-nats-exporter`, HA multi-nœud actif/passif, `SPEC-09` Config A/B, `50 cmd/s` soutenus et volumes `10k/1M`–`100k/10M`, différés à la qualification multi-VM L8.

---

## 1. Inventaire des corrections (remédiations `1d9be71c` → HEAD)

| Priorité | Commit | Objet | Fichiers clés |
| :--- | :---: | :--- | :--- |
| **P0** | `b8926fae` | Résolution `WorkerResourcesPostgresTest.i5_r11:1087` + test unitaire déterministe `RenewalServiceLeaseRetryTest` | `WorkerResourcesPostgresTest.java`, `RenewalServiceLeaseRetryTest.java` |
| **P1** | `7d1da2f1` | Confirmation `flush()` avant `CONNECTED` dans `NatsWakeupTransport` + renforcement preuves post-COMMIT, blocage transport, concurrence `DiscoveryLoop` et isolation HTTP | `NatsWakeupTransport.java`, `NatsWakeupTransportStateTest.java`, `NatsWakeupTransportTest.java`, `WakeupContractTest.java`, `SpringAttemptTransactionsAfterCommitTest.java`, `WakeupWorkerBehaviorTest.java`, `AdmissionWakeupIsolationTest.java` |
| **P1** | `862630ea` | Bascule non destructive `NATS ↔ NOOP` dans `pocoma-local` et alignement docs | `runtime.py`, `runtime_lifecycle.py`, `test_t1a_nats.py`, `cli.md`, `configuration.md`, `recovery.md` |
| **P2** | `6ddc8371` | Exposition interne `/varz` Core NATS dans `./pocoma-local status` | `runtime_lifecycle.py`, `test_t1a_nats.py` |
| **Qualif** | `943c2a07` | Attente explicite `read_command_result >= 1` dans `ReadReconstructionPostgresTest.rcv01H` | `ReadReconstructionPostgresTest.java` |
| **Qualif** | `f0ee1b79` | Archivage benchmarks répétés, reçu Docker nominal et `CERT-FULL` monolithique (`1 216/1 216`) | `docs/testing/evidence/t1a-nats-qualification/*`, dossier |
| **P0 (audit final)** | `923aac2f` | **Récupération automatique après fermeture terminale involontaire `CLOSED`** dans `NatsWakeupTransport` (`scheduleTerminalRecovery` hors thread callback `jnats`, libération connexion/dispatcher obsolètes, reconstruction + `flush()`, garde `close()` volontaire) | `NatsWakeupTransport.java`, `NatsWakeupTransportStateTest.java` (`13/13 PASS`) |
| **P0 (audit final)** | `abc7f2bd`, `a70e14be` | **Protocole de transition `NATS ↔ NOOP` en 3 phases (`PREPARING → APPLYING → COMMITTED`) récupérable après interruption** + 12 scénarios d'injection de fautes | `prerequisites.py`, `runtime_lifecycle.py`, `test_t1a_nats.py` (`143/143 PASS`) |
| **P1/Docs (audit final)** | `39923db0` | Preuve `/connz?subs=detail` & `/subsz?subs=1` des **88 abonnements** + recette live de récupération après interruption + nuance des conclusions de performance + manifeste SHA-256 7/7 OK | `audit-final-recovery-and-subs-receipt.json`, `manifest.sha256`, `cli.md`, `recovery.md`, dossier |
| **P0 (clôture READ)** | `6f08b8f6` | Résolution définitive de l'échec `ReadReconstructionPostgresTest.readReplayPreservesExactValuesAndCatchesUpWithNewWritePublication` : empreinte logique `read_user_pot WHERE visible = true` + assertions explicites `assertVisibilityInvariants` + test déterministe de convergence hors-ordre `PotVisibilityReadStorePostgresTest.outOfOrderArrivalConvergesToIdenticalLogicalVisibility` (0 fichier de production modifié) | `ReadReconstructionPostgresTest.java`, `PotVisibilityReadStorePostgresTest.java` |
| **Clôture `CERT-FULL`** | *(HEAD final)* | Archivage du `CERT-FULL` monolithique final sur `6f08b8f6` (`1 220/1 220 PASS`), manifeste SHA-256 8/8 OK, dossier de clôture et verdict `T1A_NATS_READY_FOR_MERGE` | `cert-full-closure-6f08b8f6-summary.json`, `manifest.sha256`, dossier |

---

## 2. P0 — Récupération après fermeture terminale `CLOSED` dans `NatsWakeupTransport`

### 2.1 Analyse causale
En `f0ee1b79`, `NatsWakeupTransport.handleConnectionEvent()` traitait `ConnectionListener.Events.CLOSED` comme un simple `DISCONNECTED` : `state` passait à `DEGRADED`, mais la référence `AtomicReference<Connection> connection` restait non nulle (pointant sur la connexion `jnats` définitivement fermée). Or `ensureConnecting()` vérifie `connection.get() != null` et n'était jamais relancé. En cas de fermeture terminale involontaire du client `jnats`, le transport restait donc bloqué en polling de secours `DEGRADED` même après le retour du broker.

### 2.2 Correction implémentée (`923aac2f`)
- Dans `handleConnectionEvent()` :
  - `DISCONNECTED` → `subscriptionsConfirmed = false`, incrément `confirmGeneration`, `DEGRADED` (reconnexion gérée par `jnats`).
  - `CLOSED` (lorsque `!closed.get()`) → `subscriptionsConfirmed = false`, incrément `confirmGeneration`, `DEGRADED`, puis **`scheduleTerminalRecovery(conn)`** sur un thread daemon dédié (`v3-wakeup-nats-recover-<gen>`), **hors du thread callback `jnats`** (zéro risque d'interblocage avec `close()` ou `start()`).
  - `confirmReconnect()` et `installAndPublishConnection()` vérifient également `Connection.Status.CLOSED` et déclenchent `resetConnectionAndRetry(conn)` en cas de course `RECONNECTED ↔ CLOSED`.
- `resetConnectionAndRetry(conn)` (sous `synchronized (this)`, idempotent par garde `connection.get() != conn`) : nettoie `activeDispatcher`, ferme `conn`, libère `connection` (`compareAndSet(conn, null)`), maintient `DEGRADED` et relance `ensureConnecting()` (`connectLoop()` borné par `connectRetryDelay`, qui réinstalle un unique `Dispatcher` et exige `conn.flush(...)` avant `CONNECTED`).
- `close()` volontaire positionne `closed.compareAndSet(false, true)` **avant** `conn.close()` et interrompt `connectorThread`, `confirmThread` et `recoveryThread` : un `CLOSED` issu d'un arrêt volontaire ne déclenche jamais de reconnexion.

### 2.3 Preuves déterministes (`infra-wakeup-nats` : `13/13 PASS`)
- `involuntaryClosedWhileBrokerUnavailableRetriesAndRecoversDeliveryWhenBrokerReturns` : `CLOSED` involontaire broker indisponible → maintien `DEGRADED` + nettoyage dispatcher/connexion obsolètes + retries bornés → retour broker → reconstruction unique (1 connexion, 1 dispatcher, `flush()` confirmé) → retour `CONNECTED` et livraison effective d'un nouveau réveil.
- `voluntaryCloseNeverTriggersReconnectEvenWhenClosedEventFires` : `close()` volontaire suivi d'un callback `CLOSED` → zéro reconnexion.
- `concurrentClosedReconnectedAndCloseNeverDeadlockOrLeakConnections` : concurrence simultanée `RECONNECTED` (bloqué dans `flush()`), `CLOSED` et `close()` → terminaison immédiate sans interblocage ni fuite de connexion.
- Conservés au vert : les 6 tests `flush()` / reconnexion `NatsWakeupTransportStateTest` + les 4 tests Testcontainers `NatsWakeupTransportTest` (incluant remplacement complet du broker sur même port).

---

## 3. P0 — Protocole de reconfiguration `pocoma-local` récupérable après interruption

### 3.1 Protocole en 3 phases (`abc7f2bd`, `a70e14be`)
Pour éliminer toute fenêtre d'incohérence entre `manifest.json`, `compose.json`, `nats.conf` et les conteneurs Docker :
1. **`PREPARING`** : `m['wakeupMode']` reste inchangé sur le dernier mode confirmé ; `m['wakeupTransition'] = {from, to, phase: 'PREPARING', resumed, startedAt}` est enregistré dans `manifest.json`. `package()` s'exécute avant toute modification du déploiement.
2. **`APPLYING`** : `render()` et `properties()` utilisent une vue `effective_manifest` (sans écraser `m['wakeupMode']` sur disque). `postgres` et `keycloak` ne sont jamais recréés (`up -d` idempotent). En cible `nats`, `nats` est démarré ; en cible `noop`, le conteneur `nats` possédé par le projet est supprimé de manière idempotente par label Docker (`_remove_owned_nats_container`), tout en conservant `nats.conf` jusqu'au commit final. Seuls les 4 `WAKEUP_RUNTIMES` sont recréés (`--force-recreate`).
3. **`COMMITTED`** : uniquement après réussite de `current()`, `recover_workers()` et `_verify_canary_and_observability()`, `_commit_wakeup_transition()` met à jour `m['wakeupMode'] = target_mode`, supprime `m['wakeupTransition']` et nettoie `nats.conf` si `noop`.

### 3.2 Reprise déterministe après interruption
- En cas d'interruption à n'importe quelle étape :
  - `manifest.json` conserve `wakeupMode = from` et `wakeupTransition` (jamais d'état faussement validé).
  - `./pocoma-local status` expose `wakeup.transitionInProgress`.
  - `prerequisites.instance()` autorise la fenêtre de transition `nats`/`nats.conf` tout en continuant de vérifier intégralement `secrets.json`, ports, propriété Docker, PostgreSQL, Keycloak et empreintes JAR.
  - `./pocoma-local start --instance <name>` reprend et finalise déterministiquement la transition vers `to` (`transitionRecovered=true`) ; `./pocoma-local start --instance <name> --wakeup-mode <from>` revient proprement au mode précédent, sans `reset` ni régénération de secrets.

### 3.3 Preuves d'injection de fautes et recette live
- **Tests unitaires CLI (`143/143 PASS`)** : `test_fault_injection_recovery_across_all_six_interruption_points_both_directions` couvre les **6 points d'interruption** (`before_prepare_compose`, `after_prepare_before_apply`, `after_nats_resource_change`, `during_runtime_recreation`, `before_manifest_commit`, `during_revert_to_previous_mode`) dans les **deux directions** (`NATS → NOOP` et `NOOP → NATS`, soit 12 scénarios complets vérifiant `prerequisites.instance()` avant/après reprise, identité, secrets, ports et convergence).
- **Recette Docker live (`audit-final-recovery-and-subs-receipt.json`)** :
  - Interruption injectée en plein `NATS → NOOP` (`manifest.json` encore en `wakeupMode=nats` avec `wakeupTransition`, conteneur `nats` et `nats.conf` supprimés) → `status` détecte `transitionInProgress=true` → `./pocoma-local start` reprend en `READY` (`transitionRecovered=true`, `configuredMode=noop`, `effectiveMode=NOOP`, `postgresUnchanged=true`, `keycloakUnchanged=true`, `potsPreserved=true`).
  - Interruption injectée en `NOOP → NATS` → `./pocoma-local start` reprend en `READY` (`transitionRecovered=true`, `configuredMode=nats`, `effectiveMode=CONNECTED`, `postgresUnchanged=true`, `keycloakUnchanged=true`, `potsPreserved=true`, `88` abonnements exacts).

---

## 4. P1 — Explication vérifiée des 88 abonnements NATS (`audit-final-recovery-and-subs-receipt.json`)

L'inspection directe des endpoints internes `/connz?subs=detail` et `/subsz?subs=1` sur le broker `nats:2.15.3-alpine` live démontre qu'il n'existe **aucune fuite ni duplication** :

**`88 abonnements totaux = 28 abonnements applicatifs Pocoma + 60 abonnements internes $SYS.* de nats-server 2.15.3`**

| Connexion / Composant | Dispatchers actifs | Partitions logiques T1A | Sujets NATS souscrits | Comptage vérifié |
| :--- | :---: | :---: | :--- | :---: |
| `http-write` | `0` *(publisher seul)* | — | *(aucun)* | **`0`** |
| `command-worker` | `1` | `8` (`Command` × 4, `IdentityRequest` × 4) | `pocoma.v3.wakeup.COMMAND.{0..3}`, `IDENTITY_REQUEST.{0..3}` | **`8`** |
| `critical-read-worker` | `1` | `12` (`CommandResult` × 4, `IdResult` × 4, `CurrentBinding` × 4) | `COMMAND_COMPLETION_EVENT.{0..3}`, `IDENTITY_COMPLETION_EVENT.{0..3}`, `IDENTITY_EVENT.{0..3}` | **`12`** |
| `pot-read-worker` | `1` | `16` (`PotVersion`, `PotBalance`, `PotVisibility`, `Invitation` × 4) | `POT_EVENT.{0..3}`, `INVITATION_EVENT.{0..3}` *(les 3 projections `POT_EVENT` sont dédupliquées par `Set<WakeupKey>` dans `ConsumptionWorker.startWakeupSubscriber()`)* | **`8`** |
| **Sous-total applicatif Pocoma** | **3** | **36 couples logiques** | **7 familles × 4 partitions uniques** | **`28`** |
| Broker `nats-server 2.15.3` (`$SYS`) | *(interne)* | — | `$SYS.REQ.SERVER.*`, `$SYS.REQ.ACCOUNT.*`, `$SYS.ACCOUNT.*`, etc. | **`60`** |
| **Total `/varz` `subscriptions`** | — | — | `28 + 60` | **`88`** |

**Invariance vérifiée en live** (`audit-final-recovery-and-subs-receipt.json`) :
- Démarrage nominal : `connections=4`, `pocoma=28 (0+8+12+8)`, `$SYS=60`, `varz=88`, `slowConsumers=0`.
- Après cycle 1 (`docker stop nats` + `docker start nats`) : `connections=4`, `pocoma=28`, `$SYS=60`, `varz=88`, `slowConsumers=0`.
- Après cycle 2 (`docker restart nats` complet) : `connections=4`, `pocoma=28`, `$SYS=60`, `varz=88`, `slowConsumers=0`.
- Après récupération `NATS → NOOP` puis `NOOP → NATS` : `connections=4`, `pocoma=28`, `$SYS=60`, `varz=88`, `slowConsumers=0`.

---

## 5. P1 — Conclusions de performance corrigées et nuancées

Les campagnes archivées (`t1a-nats-results.json` et `t1a-nats-remediation-bench.json`) restent valides et réutilisées sans relance : l'unique modification Java depuis `f0ee1b79` concerne exclusivement le chemin de récupération après `CLOSED` terminal dans `NatsWakeupTransport` et n'affecte ni les chemins nominaux mesurés ni les paramètres du banc.

### 5.1 Drainage du backlog `10 000` (`c4-backlog-10k`, `R=3` entrelacé)

| Configuration | Répétitions (`r1 / r2 / r3`) | Médiane | CPU total médian | Identity concurrente `p50` / `p95` | Wakeups utiles / erreurs / timeouts HikariCP |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`T1A + A-20`** *(polling)* | `121,73 s` / `127,68 s` / `177,33 s` | **`127,68 s`** | `2,089` | `219,2 / 580,7 ms` | `0 / 0 / 0` |
| **`T1A + D-nats`** *(Core NATS)* | `134,92 s` / `136,47 s` / `127,97 s` | **`134,92 s`** (`+5,7 %`) | **`1,962`** (`-6,1 %`) | **`192,9 ms`** (`-12,0 %`) / `602,4 ms` | `98,3 %` / `0` / `0` |

- **Conclusion corrigée** : la médiane reste moins favorable à NATS (`134,92 s` vs `127,68 s`), et la moyenne apparemment favorable (`133,12 s` vs `142,25 s`) dépend fortement du 3e essai polling (`177,33 s`). **NATS n'améliore pas de façon démontrée le temps de drainage du backlog `10 000`**. Le résultat constitue un **compromis observé** (CPU total `-6,1 %` et latence Identity concurrente `p50 -12,0 %`, mais gain de débit de drainage non démontré) et demeure tracé comme réserve non bloquante **`RES-T1A-BACKLOG-01`**.

### 5.2 Interférence Pot / Identity (`c3-int-pot-vs-id-deep`, 160 sondes Identity/config sous `20/s` Pot)

| Configuration | Sondes | `usableBindingE2eMs` `p50 / p75 / p90 / p95 / p99 / max` | Outliers `≥ 300 ms` | CPU total médian |
| :--- | :---: | :---: | :---: | :---: |
| **`T1A + A-20`** *(polling)* | `160/160` | `78,0 / 104,9 / 123,7 / 209,7 / 1 124,0 / 1 131,4 ms` | `7/160` (`4,4 %`) | `2,972` |
| **`T1A + D-nats`** *(Core NATS)* | `160/160` | **`46,2 / 73,3 / 80,5 / 96,7 / 1 033,6 / 1 168,4 ms`** (`p50 -40,8 %`, **`p95 -53,9 %`**) | **`5/160`** (`3,1 %`) | **`2,856`** (`-3,9 %`) |

- **Conclusion corrigée** : sur 160 sondes par configuration, Core NATS apporte un gain net sur les latences centrales (`p50 -40,8 %`, `p95 -53,9 %`) tout en abaissant le CPU total (`-3,9 %`). Des pics isolés en queue de distribution (`p99 ~1,03–1,17 s`) sont observés dans **les deux modes** (y compris en polling pur `A-20`) : ils ne sont donc **pas spécifiques à NATS**, sans qu'une cause racine unique (JIT, contention ou ordonnancement) puisse être affirmée exclusivement à partir de ces mesures.

### 5.3 Bilan CPU selon la charge et tuning
- À vide (`c1-idle`) : `-56,0 %` CPU total ; faible charge (`1 cmd/s`) : `-27,9 %` CPU ; charge soutenue (`10 cmd/s`) : `+2,5 %` CPU compensé par `-31,2 %` sur `c03 p95` ; forte charge mixte (`20/s` Pot + `2/s` ID) : `-3,9 %` CPU ; drain 10k : `-6,1 %` CPU.
- **Tuning** : aucun changement de `reconcile-poll-ms=2000`, `fallback-max-poll-ms=20` ou `IN_FLIGHT_DEDUP` n'est appliqué.

---

## 5bis. P0 — Résolution définitive de l'échec de reconstruction READ (`read_user_pot`)

### 5bis.1 Reconstitution de l'échec et cause racine démontrée
- **Test concerné** : `ReadReconstructionPostgresTest.readReplayPreservesExactValuesAndCatchesUpWithNewWritePublication` (`runtime-worker-materialize`), assertion ligne 341 :
  `assertEquals(expected, replayed, "logical table fingerprints differ after replay")` sur la table `read_user_pot`.
- **Chronologie et mécanisme** :
  - Le test crée deux pots avant démarrage des workers READ : `pot` (`v1 CreatePot` + `v2 UpdatePotDetails`, `owner` visible dans les deux versions) et `removed` (`v1 CreatePot` avec `users={owner}`, puis `v2 DeletePot` avec `users={}`).
  - Les événements `POT_EVENT` sont routés sur 4 partitions par `consumption_hash_v1('POT_EVENT', event_id) % 4` (`V1__initial_schema.sql:108-109`) et parcourus par `consumable_id` (`event_id`). Les versions `v1` et `v2` d'un même pot peuvent donc arriver dans n'importe quel ordre au materializer `MaterializePotVisibility` → `JdbcPotVisibilityStore.apply(PotVisibility)`.
  - Le rôle PostgreSQL runtime READ ne dispose volontairement d'**aucun privilège `DELETE`/`TRUNCATE`** sur `read_user_pot` (`V1__initial_schema.sql:334-337`, vérifié par `PotVisibilityReadStorePostgresTest.migrationAndPrivileges`). La suppression de visibilité est donc représentée physiquement par une **tombstone** (`visible = false`).
  - Dans `JdbcPotVisibilityStore.apply()` (`JdbcPotVisibilityStore.java:35-48`) :
    - **Ordre `v1 → v2` sur `removed`** : `v1` insère `(owner, removed, pot_version=1, visible=true)` ; `v2` constate `disabled = {owner}` et met à jour la ligne en **tombstone physique** `(owner, removed, pot_version=2, visible=false)`.
    - **Ordre `v2 → v1` sur `removed`** : `v2` constate `g=0`, `before={}`, `users={}`, `disabled={}` → avance `read_pot_visibility_state.materialized_version = 2` sans insérer aucune ligne dans `read_user_pot` ; `v1` arrive ensuite, constate `1 < g (2)` et retourne en no-op. **Aucune ligne tombstone n'est créée** dans `read_user_pot`.
  - Le commit historique `7641b80b` avait identifié la première dépendance à l'ordre d'arrivée (`read_user_pot.pot_version`, qui vaut `1` en `v1 → v2` vs `2` en `v2 → v1` sur `pot`) et exclu `pot_version` dans `readContent()`, **mais avait omis la seconde dépendance** : la présence ou l'absence des lignes tombstones `visible = false` dans `read_user_pot`. Dès que la passe initiale (`original`) et le rejeu (`replay`) traitaient `(removed, v1)` et `(removed, v2)` dans des ordres opposés entre partitions, l'empreinte SHA-256 de `read_user_pot` divergeait (`[(owner, pot, true), (owner, removed, false)]` vs `[(owner, pot, true)]`).

### 5bis.2 Invariant métier clarifié (`PotVisibility` / `read_user_pot`)
- **Données strictement indépendantes de l'ordre d'arrivée (convergence garantie)** :
  1. `read_pot_visibility_state(pot_id, materialized_version)` converge exactement vers `V_max` pour chaque pot (empreinte complète `to_jsonb(row)` conservée).
  2. L'ensemble logique visible `{(user_id, pot_id) ∈ read_user_pot | visible = true}` converge exactement vers `users(pot_id, V_max)` — toutes les requêtes métier (`JdbcPotVisibilityStore.visible`, `visiblePots`, `ReadNavigation`) et les deux index partiels (`read_visible_by_pot`, `read_visible_by_user`) portent exclusivement sur `WHERE visible = true`.
  3. Toute ligne présente dans `read_user_pot` (visible ou tombstone) respecte `1 ≤ pot_version ≤ read_pot_visibility_state.materialized_version` et possède un garde `read_pot_visibility_state`.
- **Métadonnées physiques légitimement dépendantes de l'ordre d'arrivée** :
  1. `read_user_pot.pot_version` (version de matérialisation ayant opéré la dernière transition de `visible`).
  2. La présence physique d'une ligne tombstone `visible = false` (créée uniquement si l'utilisateur a été matérialisé visible avant d'être retiré/supprimé).
- **Conclusion sur les 15 autres tables de `readContent()`** : toutes les autres tables READ (`read_current_pot`, `read_pot_version*`, `read_subobject_version`, `read_pot_balance*`, `read_pot_visibility_state`, `read_current_binding`, `read_command_result`, `read_identity_request_result`, `read_current_invitation`) sont soit immuables par version / adressées par contenu, soit monotones par clé unique, donc strictement indépendantes de l'ordre d'arrivée.

### 5bis.3 Correction réalisée et justification (test uniquement — 0 fichier de production modifié)
La projection `JdbcPotVisibilityStore` est conforme au contrat métier (convergence logique exacte en ordre `v1 → v2` comme `v2 → v1`, sans accès à l'historique WRITE@V−1 et sans privilège `DELETE`). La correction porte exclusivement sur les tests (`6f08b8f6`) :
1. **`ReadReconstructionPostgresTest.java`** :
   - `readContent()` calcule l'empreinte logique de `read_user_pot` sur `SELECT jsonb_build_object('user_id', user_id, 'pot_id', pot_id) FROM read_user_pot WHERE visible = true`, tout en conservant l'empreinte complète `to_jsonb(row)` sur les 15 autres tables (dont `read_pot_visibility_state`).
   - Ajout de `assertVisibilityInvariants(...)` (appelé après `original` et après `replay`), qui vérifie explicitement : `materialized_version = 2` pour `pot` et `removed`, exactement `1` ligne `visible = true` pour `pot`, exactement `0` ligne `visible = true` pour `removed`, et `0` ligne incohérente dans `read_user_pot` (`s.pot_id IS NULL OR u.pot_version < 1 OR u.pot_version > s.materialized_version`). L'oracle n'est donc pas affaibli : il est au contraire plus strict et plus explicite.
2. **`PotVisibilityReadStorePostgresTest.java`** :
   - Ajout du test déterministe `outOfOrderArrivalConvergesToIdenticalLogicalVisibility()`, qui reproduit directement sur PostgreSQL 17 les deux ordres d'arrivée (`v1 → v2` vs `v2 → v1`) pour un pot actif et un pot supprimé, démontre simultanément la divergence physique attendue (`pot_version` `1` vs `2` ; tombstone `1` vs `0` ligne) et l'identité logique stricte (`read()`, `visiblePots()`, `read_pot_visibility_state`).

### 5bis.4 Justification de réutilisation des preuves NATS / CLI / Docker / Benchmarks
Le correctif `6f08b8f6` ne modifie **aucun fichier de code de production** (`src/main/**`), aucune migration SQL, aucun module NATS et aucun script `scripts/local/**`. Conformément à `Reactor_Verification_Policy.md` (§34) et au mandat de clôture (§6), les preuves déjà acquises sur les chemins NATS, CLI (`143/143`), Docker live (`88` abonnements invariants et transitions `NATS ↔ NOOP` récupérables), Bruno et benchmarks différentiels/répétés restent strictement valides et réutilisées avec leur SHA d'origine.

---

## 6. Résultats des gates et rapport `Reactor_Verification_Policy.md` (§30)

```text
Start HEAD (clôture READ): 39923db0 (feat/v3-identity-governance; main intact @ afec69f8)
Commit qualifié (app/): 6f08b8f66b2cb4e37fd84eafe570394d2c0174da
Expected production impact:
- NONE (correction strictement limitée aux tests READ : ReadReconstructionPostgresTest.java, PotVisibilityReadStorePostgresTest.java)
Observed production impact:
- NONE (0 fichier modifié sous src/main/**, migrations SQL ou scripts/local/**).
- Unexpected slice crossings: NONE.
- Forbidden production impact verified untouched: tous les modules production (domain-*, engine-*,
  orchestrator-consumption, infra-*, compose-*, runtime-*, migrations SQL, scripts/local, Valkey).
Declared slices:
- Primary slice: PROJECTION (runtime-worker-materialize -am)
- Global gates: architecture-tests + CERT-FULL monolithique (Integration / Main gate §§19, 21.3)
Verification commands executed on 6f08b8f6:
1. ./mvnw --batch-mode --no-transfer-progress -pl infra-postgres-read -Dtest="PotVisibilityReadStorePostgresTest" test
   -> 5/5 PASS (inclut reproduction déterministe outOfOrderArrivalConvergesToIdenticalLogicalVisibility)
2. ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize -Dtest="ReadReconstructionPostgresTest#readReplayPreservesExactValuesAndCatchesUpWithNewWritePublication" test
   -> 1/1 PASS (250.6 s)
3. Tranche canonique PROJECTION :
   ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize -am test
   -> BUILD SUCCESS (36/36 modules, runtime-worker-materialize 135/135 dont ReadReconstructionPostgresTest 13/13,
      infra-postgres-read 40/40, infra-wakeup-nats 13/13, 0 failure/error/skip, 27:55 min)
4. Gate monolithique finale CERT-FULL (non filtrée, ininterrompue, sur code final 6f08b8f6) :
   ./mvnw --batch-mode --no-transfer-progress test
   -> BUILD SUCCESS : 1 220/1 220 PASS, 179 suites, 29 modules avec tests, 44/44 projets du réacteur,
      0 failure, 0 error, 0 skipped, 01:22 h (archivé dans cert-full-closure-6f08b8f6-summary.json).
      Inclut notamment : architecture-tests (52/52), infra-wakeup-nats (13/13), infra-postgres-read (40/40),
      infra-postgres-write (479/479), runtime-web-read (42/42), runtime-worker-process (98/98),
      runtime-worker-materialize (135/135), runtime-monolith (40/40).
5. Preuves réutilisées (0 delta production NATS/CLI/Docker depuis 39923db0) :
   - python3 -B -m unittest discover -s scripts/local -p "test_*.py" -> 143/143 PASS (39923db0)
   - Recette Docker live & 88 subs (audit-final-recovery-and-subs-receipt.json, 39923db0) -> ALL PASS
   - Recette Docker nominale (pocoma-local-live-receipt.json, f0ee1b79) -> ALL PASS
   - Benchmarks différentiels (29/29) & répétés (10/10) -> archivés, intact
6. shasum -a 256 -c docs/testing/evidence/t1a-nats-qualification/manifest.sha256 -> 8/8 OK
Global architecture gate: RUN (52/52 PASS dans CERT-FULL monolithique sur 6f08b8f6)
Full reactor: RUN — CERT-FULL monolithique BUILD SUCCESS sur 6f08b8f6 (1 220/1 220 PASS, 01:22 h)
Database verification: CURRENT (PostgreSQL 17.11 Testcontainers sur l'intégralité du réacteur)
Merge to main: NOT PERFORMED (interdit sans autorisation explicite humaine)
```

---

## 7. Matrice différentielle actualisée (`L7` / `SPEC-09`) et statut des réserves

| Domaine / Invariant | Référence (`v3.0.0-l7-local` / `T0`) | Statut final | Preuve sur `feat/v3-identity-governance` (`6f08b8f6`) | Réserve éventuelle |
| :--- | :--- | :---: | :--- | :--- |
| **Domaine & Admission HTTP (`L7.2`, identités serveur)** | `44 CURRENT_FULL` + `8b9227e3` | **Clôturé** | `CERT-FULL` (`6f08b8f6`) : `compose-web-write-postgres` (`23/23`), `runtime-web-read` (`42/42`), `infra-postgres-write` (`479/479`) + Bruno `J01–J03` | Aucune |
| **Exécution Consumption L2, Fencing, Reprise & Reconstruction READ (`L7.3`)** | `425 CURRENT_FULL`, `L7-C3` | **Clôturé** | `CERT-FULL` monolithique (`6f08b8f6`, **`1 220/1 220`**), `runtime-worker-process` (`98/98`), `runtime-worker-materialize` (`135/135` dont `ReadReconstructionPostgresTest` `13/13`), `infra-postgres-read` (`40/40` dont `PotVisibilityReadStorePostgresTest` `5/5`) | Aucune *(cause racine `read_user_pot` résolue et démontrée)* |
| **Architecture modulaire & déploiement (`L7.5`)** | `50/50` `architecture-tests` | **Clôturé** | `architecture-tests` **`52/52 PASS`** dans `CERT-FULL` (`6f08b8f6`) | Aucune |
| **Distribution locale & DX (`L7-LB`, `./pocoma-local`)** | `L7_LB_CLOSED` (9 conteneurs) | **Clôturé** | `143/143` tests unitaires CLI (dont 12 injections de fautes `PREPARING → APPLYING → COMMITTED`), `pocoma-local-live-receipt.json`, `audit-final-recovery-and-subs-receipt.json` | Aucune |
| **Résilience, récupération & abonnements Core NATS** | Nouvelle capacité | **Clôturé** | `infra-wakeup-nats` (`13/13` dans `CERT-FULL` `6f08b8f6`), `/connz?subs=detail` & `/subsz?subs=1` (`88 = 60 $SYS + 28 pocoma` invariants sur 4 cycles), `WakeupContractTest` (`5/5`), `WakeupWorkerBehaviorTest` (`8/8`), `SpringAttemptTransactionsAfterCommitTest` (`2/2`) | Preuve équivalente post-COMMIT vs `SIGKILL` noyau ; HA multi-nœud différé L8 |
| **Performances locales (`L7.4` / `SPEC-09` local)** | `L7-C4` (`T0 + A-20`) | **Clôturé avec réserve non bloquante** | Campagne initiale `29/29 PASS` + campagne répétée `10/10 PASS` (`c3-deep` 160 sondes `p50 -40,8 %`, `p95 -53,9 %` ; `c4-backlog-10k` `R=3`) | **`RES-T1A-BACKLOG-01`** *(médiane drainage `134,92 s` vs `127,68 s`, gain débit non démontré ; compromis CPU `-6,1 %` / Identity `p50 -12,0 %`)* |
| **Infrastructure cible multi-VM (`L8`)** | Ouverte (`L74-ENV-01`) | **Différée L8** | Hors périmètre local | **`RES-L8-01`** *(non bloquant)* |

---

## 8. Manifeste d'intégrité des preuves (`docs/testing/evidence/t1a-nats-qualification/manifest.sha256`)

```text
e54b996d1eb5e5e47f99265ad3c28e7b0ca3f3a09fe684169439af853735b3a6  audit-final-recovery-and-subs-receipt.json
76b5da45501beeeebc57bf528631812d6486ce133de782d2cc8bb16b1951d18b  cert-full-closure-6f08b8f6-summary.json
ed305cf21dff51d9f3e48aeb96ffe238f3cbd6b34148e2f35fe79bf0fdbd3272  cert-full-monolithic-summary.json
e956b8782335dd0df8010dd5b288289b87d8733d3b10d0bfdeda6691b7c0e517  pocoma-local-live-receipt.json
2642a251c2c730e74cba4aabadfd2126d0d1eaa1f6bf1913de589b00a8ffa0b4  t1a-nats-remediation-bench.json
5c7c16b90c3cdc9f9d413b38668bb87b97297d3cc930d8dbc8578c803c61e46d  t1a-nats-remediation-summary.json
a427ebc57e6f6cf0ba259f4449dd7a6a65362ecec3e99b2bbf7beb4ba54aef06  t1a-nats-results.json
802740cddd8d505becd6d2c17221bf435d87ce1bacfe0985da716b1fe36565c4  t1a-nats-summary.json
```

---

## 9. Verdict final et proposition de fusion

- **Verdict final : `T1A_NATS_READY_FOR_MERGE`**
- **Révision qualifiée (`app/`)** : `6f08b8f66b2cb4e37fd84eafe570394d2c0174da` (`CERT-FULL` monolithique **`1 220/1 220 PASS`**, `0` échec, `0` erreur, `0` ignoré, `44/44` projets Maven, `01:22 h`).
- **Réserves non bloquantes conservées** : `RES-T1A-BACKLOG-01` et `RES-L8-01`.
- **Proposition explicite** : **fusionner `feat/v3-identity-governance` dans `main`** après autorisation humaine explicite. Aucune fusion n'a été effectuée (`main` et `origin/main` restent strictement à `afec69f8`).
