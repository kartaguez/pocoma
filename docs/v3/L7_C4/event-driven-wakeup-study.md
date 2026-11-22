# Pocoma V3 — L7-C4 — Étude d'architecture : Réveil événementiel des workers (Axe 5)

> **Statut** : **Étude d'architecture uniquement — aucune implémentation engagée** dans L7-C4 Phase 2.  
> **Référence de comparaison** : Polling intrinsèquement optimisé (`B3 + A2`), qui ramène le fonctionnement à vide de `14 034 tx/s` / `1,37 CPU` (T0) à **`~1 336 tx/s` / `~0,55 CPU`** (`-90,5 %` transactions, `-59,8 %` CPU, `-87,1 %` WAL).

---

## 1. Finalité et principes invariants

L'objectif d'un mécanisme de réveil événementiel est de remplacer le polling fréquent à vide (actuellement `max-poll = 20 ms`, soit `50 Hz` sur les sources `v3_command`, `v3_identity_request` et `v3_event_counter`) par un **réveil à la demande** lorsqu'un travail durable est commité, tout en conservant un **polling de réconciliation lent** (ex. `1 000–5 000 ms`).

Principes non négociables :
1. **PostgreSQL reste l'unique autorité durable** (`v3_write` et `v3_read`). Le signal de réveil est exclusivement une *indication volatile* (« best-effort hint »), jamais une autorité pour les slots, checkpoints, leases ou effets.
2. **Dégradation gracieuse** : si le bus de réveil est indisponible, redémarre ou perd des paquets, les workers continuent de progresser via le polling de réconciliation sans perte ni double effet.
3. **Pas d'outbox ni de transaction distribuée (2PC)** : un outbox PostgreSQL ajouterait des écritures WAL dans la base déjà sollicitée et recréerait du polling sur la table outbox. Un signal *post-commit* coalescé suffit dès lors que le polling de réconciliation couvre la fenêtre de perte.

---

## 2. Analyse comparative des architectures candidates

| Critère | **Valkey Pub/Sub** (Spring Data Redis) | **NATS Core** (`jnats`) | **PostgreSQL `LISTEN/NOTIFY`** (comparaison) | **Spring Integration / AMQP (RabbitMQ)** |
| :--- | :--- | :--- | :--- | :--- |
| **Licence & maturité** | BSD-3 (Linux Foundation), standard Redis OSS | Apache-2.0 (CNCF), très mature | Intégré PG | Apache-2.0 / MPL-2.0 |
| **Intégration Spring Boot** | Native (`spring-boot-starter-data-redis`, `RedisMessageListenerContainer`, `StringRedisTemplate`) | Bonne via `io.nats:jnats` (client léger sans courtier lourd, ou `nats-spring`) | Native JDBC (`PGConnection.getNotifications`), mais nécessite connexion dédiée hors pool transactionnel | Plus lourde (AMQP broker, queues, exchanges) |
| **Empreinte locale / multi-VM** | Très faible (~`20–40 MiB` RAM, ~`0.05` CPU) | Très faible (~`15–30 MiB` RAM, ~`0.03` CPU, binaire Go unique ~20 MB) | `0` conteneur supplémentaire, mais **couple la messagerie aux connexions PG** | Moyenne/élevée (Erlang VM ~150+ MiB) |
| **Impact sur PostgreSQL** | **Nul** (décharge PG de la diffusion) | **Nul** (décharge PG de la diffusion) | **Négatif** : `NOTIFY` émis dans le WAL/commit PG, verrou global `AsyncQueue`, et maintien d'une connexion dédiée par worker | Nul |
| **Sémantique** | Pub/Sub volatile (at-most-once), wildcard `psubscribe`, fan-out natif | Pub/Sub volatile (at-most-once), hiérarchie de sujets `pocoma.wakeup.<family>.<p>`, queue groups optionnels | Pub/Sub dans la DB (payload 8 Ko, perte si déconnexion) | Orienté file durable (surdimensionné pour un simple hint) |
| **Exploitation** | Très simple, déjà familière dans les stacks Spring | Très simple (1 binaire, cluster natif multi-VM trivial) | Aucun service tiers mais anti-pattern messagerie sur la DB d'autorité | Plus complexe |
| **Verdict** | **Recommandé (Ex aequo #1 — meilleur fit Spring Boot)** | **Recommandé (Ex aequo #1 — empreinte minimale & sujets hiérarchiques)** | **Écarté comme cible** (mandat : ne pas transformer PG en bus de messagerie) | **Écarté** (surdimensionné) |

### Choix recommandé : **Valkey Pub/Sub (Spring Data Redis)** ou **NATS Core**

* **Valkey Pub/Sub** s'intègre nativement dans Spring Boot (`spring-boot-starter-data-redis` + Lettuce) sans introduire de SDK exotique et pourra être réutilisé ultérieurement si un cache applicatif ou rate-limiter léger est souhaité.
* **NATS Core** offre l'empreinte la plus faible (~15 MiB) et un routage par sujets hiérarchiques (`pocoma.v3.wakeup.<family>.<partition>`) extrêmement propre pour des runtimes JVM distincts en multi-VM.
* L'interface interne Pocoma (`WakeupPublisher` / `WakeupSubscriber` sur un canal `(ConsumableFamily, partition)`) rend le choix Valkey vs NATS **interchangeable** derrière un adaptateur de ~100 lignes.

---

## 3. Résolution des 10 questions d'architecture

### Q1. Quels événements durables doivent provoquer un réveil ?
Uniquement les commits durables qui créent du travail consommable :
1. **Sur `WRITE` (`http-write`)** :
   * Admission de commande (`v3_command` → `COMMAND`, partition `p`).
   * Admission d'identité (`v3_identity_request` → `IDENTITY_REQUEST`, partition `p`).
2. **Sur `WRITE` (`command-worker`)** :
   * Publication d'un `POT_EVENT` (`v3_pot_event` → réveille `PotVersion`, `PotBalance`, `PotVisibility`).
   * Publication d'un `COMMAND_COMPLETION_EVENT` (`v3_command_completion_event` → réveille `CommandResult`).
   * Publication d'un `IDENTITY_EVENT` (`v3_identity_event` → réveille `CurrentBinding`).
   * Publication d'un `IDENTITY_COMPLETION_EVENT` (`v3_identity_completion_event` → réveille `IdentityRequestResult`).
   * Publication d'un `INVITATION_EVENT` (`v3_invitation_event` → réveille `Invitation`).

*(Note : les workers `READ` ne publient pas de travail pour d'autres workers en aval dans V3, ils terminent le pipeline.)*

### Q2. À quel moment le signal est-il émis par rapport au `COMMIT` ?
**Strictement APRÈS le `COMMIT` PostgreSQL**, via `TransactionSynchronization.afterCommit()` (ou immédiatement après le retour de `AttemptTransactions.write(...)`).
* *Pourquoi* : un signal émis *avant* le `COMMIT` réveillerait le worker alors que les lignes/compteurs ne sont pas encore visibles (isolation `READ COMMITTED` / `REPEATABLE READ`), causant un tour à vide puis une attente jusqu'au prochain cycle.
* *Risque résiduel (fenêtre COMMIT → publish)* : si la JVM émettrice crashe entre le `COMMIT` PostgreSQL et l'envoi réseau Valkey/NATS, le signal est perdu. Ce risque est couvert sans outbox par **Q8** (polling de réconciliation lent).

### Q3. Comment réveiller les bonnes consommations et les bonnes instances ?
* **Granularité de canal/sujet** : `pocoma.v3.wakeup.<ConsumableFamily>.<partition>` (ex. `pocoma.v3.wakeup.COMMAND.2`, `pocoma.v3.wakeup.POT_EVENT.1`).
* **Abonnement sélectif** : au démarrage (et lors de l'acquisition/renouvellement de lease), chaque runtime worker ne s'abonne qu'aux couples `(family, partition)` correspondant aux `Binding` qu'il héberge (et dont il détient ou tente de détenir la partition).
* **Réveil ciblé** : côté `ConsumptionWorker`, la réception du signal sur `(family, partition)` appelle :
  1. `discoveryLoop.wakeFamily(family)` (ou `discoveryWait.signal()`) ;
  2. `markPairDirty(...)` si applicable, sans attendre `max-poll`.

### Q4. Comment éviter une notification par opération si son coût devient excessif ?
Par **coalescing (regroupement) en mémoire côté émetteur** par clé `(ConsumableFamily, partition)` :
* Lorsqu'un commit réussit pour `(family, p)`, l'émetteur marque un bit/flag `pendingNotify[family][p] = true` (ou utilise un micro-batcher asynchrone avec fenêtre de `1–2 ms` / in-flight deduplication).
* Si 50 commandes sont commitées en 2 ms sur la même `(family, p)`, **1 seule notification** (ou 1 par fenêtre) est envoyée sur Valkey/NATS.
* Sous forte charge (`> 20 cmd/s`), le coût réseau du bus de réveil devient quasi nul car les workers sont déjà actifs (`progressed == true`).

### Q5. Comment gérer les deux autorités `WRITE` et `READ` ?
* L'émetteur est toujours le runtime qui vient de commiter dans **`WRITE`** (`http-write` pour `COMMAND` / `IDENTITY_REQUEST`, `command-worker` pour les `*_EVENT` dans `v3_write`).
* Les récepteurs sont `command-worker` (consommations `WRITE`) et `results-worker` / `business-read-worker` (consommations `READ` lisant les sources `WRITE`).
* Le signal ne transporte aucune donnée métier ni état `READ` — uniquement `(family, partition)` (ou `empty` payload). Les deux autorités restent strictement découplées au niveau transactionnel.

### Q6. Comment gérer les signaux perdus ?
Par construction, un signal perdu ne corrompt jamais l'état :
1. `DiscoveryLoop` conserve un **polling de réconciliation lent** (ex. `reconcilePoll = 1 000–2 000 ms`) qui balaye périodiquement les sources.
2. En cas de perte de paquet ou de crash entre `COMMIT` et `publish`, le pire cas est une latence égale à `reconcilePoll` sur cet événement isolé (et immédiatement résorbé dès le prochain événement ou au passage de réconciliation).

### Q7. Comment assurer le réveil après reconnexion ou redémarrage ?
* **Au démarrage d'un worker** ou **à la reconnexion du client Valkey/NATS** (callback `onConnected` / `onSubscribed`), le worker déclenche immédiatement un `discoveryWait.signal()` + `consumptionWait.signal()` (passage de rattrapage immédiat).
* Ainsi, tout événement commité pendant la coupure ou avant la souscription est découvert dès la reconnexion, sans attendre le cycle de réconciliation.

### Q8. Quel polling de réconciliation conserver ?
* Conserver le mécanisme actuel `DiscoveryLoop` + `ConsumptionLoop`, mais avec :
  * **`max-poll` porté de `20 ms` à `1 000–2 000 ms`** lorsque le réveil événementiel est actif et connecté ;
  * **Fallback automatique à `20–50 ms`** si le client Valkey/NATS est déconnecté (`healthy == false`), garantissant les SLO de latence même en cas de panne du bus de notification.
* L'optimisation intrinsèque `B1 + B2 + B3` reste active dans les deux modes, rendant même le fallback très sobre.

### Q9. Comment traiter les retries et échéances sans notification ?
* Les retries (`RETRYABLE` avec `retryUntil` sur `LeaseEntry`) et les expirations de `claim_duration` / `lease_duration` sont **locaux ou temporels** dans PostgreSQL, sans nouvel événement source.
* `ConsumptionLoop` calcule le prochain `retryUntil` minimal parmi ses paires actives/en retry et passe `min(backoff, nextRetryDelay)` à `consumptionWait.await(...)` (et `cleanUntilNanos` de `B1` expire à `min(500 ms, nextRetryDelay)`). Aucun signal externe n'est requis pour les retries.

### Q10. Comment éviter les réveils redondants et les tempêtes de notifications ?
1. **Coalescing côté émetteur** (Q4) : au plus 1 signal en vol / par fenêtre de `1–2 ms` par `(family, p)`.
2. **Suppression des courses *signal ↔ mise en attente*** (`ConditionConsumptionWaiter`) : vérifier que `ConditionConsumptionWaiter` utilise un drapeau `signaled = true` sous verrou (wakeup mémorisé : si le signal arrive juste avant `waiter.await()`, `await()` consomme le drapeau et retourne immédiatement sans dormir).
3. **Filtrage côté récepteur** : si `DiscoveryLoop` est déjà en cours d'exécution active (`progressed == true`), les signaux supplémentaires sur la même `(family, p)` sont simplement absorbés par le drapeau `signaled` sans empiler de tâches.

---

## 4. Modifications nécessaires à Pocoma & Impacts L2

* **Modifications code** (périmètre très réduit, ~250–350 lignes) :
  * `contract-consumption` / `supra-polling-consumption` : interface `WakeupSignal` (avec implémentation `NOOP` par défaut) branchée sur `ConditionConsumptionWaiter` (drapeau `signaled` mémorisé + mode `reconcilePoll` / fallback déconnecté).
  * `infra-tx-spring` ou `compose-*` : hook `afterCommit` dans `AdmitCommand`, `AdmitIdentity`, `ExecutePotCommand`, `ExecuteIdentityRequest` appelant un `WakeupPublisher` coalescé.
  * Nouvel adaptateur optionnel `infra-wakeup-valkey` (ou `infra-wakeup-nats`).
* **Impacts L2 et spécifications** : **Aucun changement aux contrats L2, C1, C2 ou C3** (les partitions, leases, fencing, slots, checkpoints et disponibilité READ restent inchangés).

---

## 5. Bénéfice potentiel par rapport au polling intrinsèquement optimisé (`B3 + A2`)

| Métrique à vide (`c1-idle`) | T0 (Phase 1) | `B3 + A2` (Polling optimisé) | Projection avec Réveil (`reconcile = 2 s`) |
| :--- | :---: | :---: | :---: |
| **Transactions PG / s** | `14 034 tx/s` | `1 336 tx/s` (`-90,5 %`) | **`~15–30 tx/s`** (`-99,8 %` vs T0) |
| **CPU `postgres` à vide** | `0,64 CPU` | `0,137 CPU` (`-78,6 %`) | **`< 0,03 CPU`** |
| **CPU total cluster à vide** | `1,37 CPU` | `0,551 CPU` (`-59,8 %`) | **`~0,08–0,15 CPU`** |
| **Latence de détection à froid** | `0–20 ms` | `0–20 ms` | **`~1–3 ms`** (via Pub/Sub) |
| **Complexité opérationnelle** | `0` composant tiers | `0` composant tiers | `+1` composant (Valkey ou NATS) |

**Recommandation** :
* Pour le **court terme (L7 actuel)**, `B3 + A2` apporte déjà `-90,5 %` de transactions à vide et `-60 %` de CPU à vide **avec zéro dépendance supplémentaire** — ce qui est suffisant pour le déploiement mono-hôte et les objectifs actuels.
* Pour un **chantier ultérieur dédié (multi-VM / haute sobriété à vide)**, l'ajout d'un réveil événementiel **Valkey Pub/Sub** (ou **NATS Core**) sur le modèle ci-dessus permettra de descendre sous `30 tx/s` et `< 0,15 CPU` à vide tout en réduisant la latence à froid de `~10–20 ms` à `~1–3 ms`.
