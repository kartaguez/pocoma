# Pocoma V3 — Plan de conception révisé : Calcul incrémental `N−m` de `PotVersion`

- **Date** : 18 novembre 2026
- **Branche de référence analysée** : `feat/l7-4-a2-potversion-optimization` @ `f196b2fb` (post-L7.4-A2)
- **Statut** : Document de conception révisé (aucune implémentation réalisée)
- **Modèle cible** : $P_N = P_K \oplus \Delta_{K \rightarrow N}$ avec $K = \max\{k < N \mid P_k \text{ est disponible et exploitable}\}$
- **Verdict de séquentialité (constat documentaire)** : **`SEQUENTIALITY_NOT_FAVORED`**
- **Verdict du mandat** : **`READY_WITH_DECISIONS`**

---

## 1. Contexte, abandon de la dépendance à `N−1` et constat de séquentialité

### 1.1 Abandon de la dépendance à `N−1`

La révision `N−m` supprime toute hypothèse selon laquelle `PotVersion(N−1)` doit être disponible pour activer le chemin incrémental :
- Le calcul s'appuie sur **la plus récente version antérieure $K < N$ déjà matérialisée et exploitable** dans `read_pot_version`, quel que soit l'écart $m = N - K \ge 1$.
- **Aucun changement de routage ni d'ordonnancement n'est requis** : `RoutingV1` (routage par `event_id`), `JdbcSlotKeyNavigation` (`ORDER BY consumable_id`) et le parallélisme de `ConsumptionWorker` restent strictement inchangés.
- Le calcul fonctionne **indépendamment de l'ordre de matérialisation des versions** (exécutions concurrentes, rattrapage de backlog, reconstructions partielles ou publications inversées).

### 1.2 Rappel documentaire de l'audit de séquentialité (`SEQUENTIALITY_NOT_FAVORED`)

L'analyse du code établit que l'ordre séquentiel des versions d'un même pot n'est pas garanti :
1. `JdbcPotCommandStore.publish()` (`L253`) génère `event_id = UUID.randomUUID()`.
2. `DiscoveryEngine.discoverEvents()` (`L211`) et `RoutingV1` distribuent les événements `POT_EVENT` sur 4 partitions par `SHA-256(event_id) mod 4` (`75 %` de séparation inter-partitions entre deux versions consécutives).
3. `JdbcSlotKeyNavigation.fetchKeys()` (`L87, L97`) navigue `consumption_slot` par `ORDER BY consumable_id`, sans lien avec `v3_event_position.position` ni `subject_version`.
4. `business-read-worker.properties` exécute `MaterializePotVersion` avec `consumption-limits=2` et `quantum=4`, et `JdbcPotVersionStore.insertAndAdvance()` (`L97-102`) gère explicitement les inversions de publication (`WHERE read_current_pot.pot_version < excluded.pot_version`).

**Conséquence pour le modèle `N−m`** : même lorsque `N−1` est absente (en cours de calcul sur un autre thread/partition, en retry ou en quarantaine), une version antérieure $K = N - 2$, $N - 3$, etc. reste exploitable pour calculer $P_N$ sans retomber systématiquement sur une reconstruction intégrale.

---

## 2. Identification de la meilleure version de référence $K$

### 2.1 Table, index et requête SQL de recherche

Dans `v3_read` (`app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql`, `L194-200`) :
```sql
create table read_pot_version (
  pot_id uuid not null,
  pot_version bigint not null check(pot_version>0),
  schema_version integer not null check(schema_version=1),
  payload jsonb not null check(jsonb_typeof(payload)='object'),
  primary key(pot_id,pot_version)
);
```
La clé primaire `(pot_id, pot_version)` fournit un index B-tree ordonné. La recherche et la lecture de $K$ dans la borne $[N - M_{\max}, N)$ s'effectuent en **un seul aller-retour SQL** :

```sql
SELECT pot_version, schema_version, payload::text
FROM read_pot_version
WHERE pot_id = ?
  AND pot_version < ?
  AND pot_version >= ?
ORDER BY pot_version DESC
LIMIT 1
```
Avec `minAcceptableK = Math.max(1L, N - maxDeltaDistance)` :
- **Coût SQL** : `Index Scan Backward` sur `read_pot_version_pkey` avec `LIMIT 1` ($O(\log V)$). Si aucune version n'existe dans la fenêtre $[N - M_{\max}, N)$, `0` ligne est retournée sans lire ni transférer de `payload`.

### 2.2 Distinction version existante / complète / publiée (`read_current_pot`)

| Notion | Table | Rôle exact et garantie |
|---|---|---|
| **Version existante & complète** | `read_pot_version(pot_id, K)` | Toute ligne commise dans `read_pot_version` est **complète et immuable** : elle est insérée dans la transaction `READ_COMMITTED` de `FINALIZE` après `subobjects.insertOrVerifyAll(loaded)` et protégée par triggers `ENABLE ALWAYS` (`read_pot_version_immutable`, `read_subobject_immutable`). |
| **Version publiée courante** | `read_current_pot(pot_id, pot_version)` | Pointeur monotone (`max(pot_version)` publiée). **Ne jamais confondre avec $K$** : si `N+1` a été publiée avant `N`, `read_current_pot` vaut `N+1 > N` et ne désigne pas la meilleure version antérieure à `N`. |

### 2.3 Visibilité transactionnelle, concurrence et disparition de $K$

- **Visibilité transactionnelle** : `MaterializePotVersion.prepare()` lit $K$ via `reads.effects(...)` sous snapshot `READ ONLY` + `REPEATABLE READ` (`SpringAttemptTransactions.observed`).
  - Une écriture concurrente de $K$ non encore commise est invisible (aucun dirty read, aucun verrou bloquant).
  - Si $K$ est commise avant le début du snapshot `REPEATABLE READ`, elle est visible, complète et immuable pendant toute la durée de `prepare()`.
  - Si $K$ commet après le début du snapshot, elle reste invisible pour cette tentative, qui utilisera une version antérieure $K' < K$ ou le chemin intégral.
- **Versions concurrentes** : si deux workers matérialisent simultanément `N` et `N−1`, `N` peut démarrer avec $K = N - 2$ pendant que `N−1` est en vol, sans aucune contention ni attente.
- **Disparition ou corruption de $K$** : en régime normal, `read_pot_version` est strictement append-only (aucun `UPDATE`/`DELETE`/`TRUNCATE` accordé à `pocoma_read`). Si `PotVersionJson.decode` lève `IllegalStateException("Corrupt PotVersion")` sur $K$, il s'agit d'une corruption réelle à propager (aucun fallback silencieux).

---

## 3. Composition des changements d'état sur l'intervalle $(K, N]$

### 3.1 Principe : deltas d'état et résolution directe de l'état final à $N$

Dans `v3_write`, les 8 tables de composants (`v3_pot_header`, `v3_pot_details`, `v3_shareholder_header`, `v3_shareholder_details`, `v3_shareholder_weight`, `v3_expense_header`, `v3_expense_details`, `v3_expense_financials`) stockent des révisions intervalaires `[active_from, active_to)` et disposent chacune de l'index `v3_<composant>_effect(pot_id, active_from)` (`V1__initial_schema.sql`).

Pour obtenir **directement la valeur finale à $N$ de tout sous-objet modifié au moins une fois sur $(K, N]$**, sans reconstruire les versions intermédiaires $K+1, \dots, N-1$, il suffit d'exécuter sur chaque table :

```sql
SELECT <colonnes_composant>
FROM v3_<composant>
WHERE pot_id = :potId
  AND active_from > :k
  AND active_from <= :n
  AND (active_to IS NULL OR active_to > :n)
```

### 3.2 Preuve d'exactitude sur tous les cas de figure

1. **Remplacements successifs du même sous-objet sur $(K, N]$** :
   - Supposons qu'un sous-objet ait connu des révisions $r_1$ (`active_from = K+1, active_to = K+3`), $r_2$ (`active_from = K+3, active_to = N+2`) et $r_3$ (`active_from = N+2, active_to = NULL`).
   - $r_1$ a `active_to = K+3 <= N` → exclue.
   - $r_2$ a `active_from = K+3 ∈ (K, N]` et `active_to = N+2 > N` → **seule ligne retenue** (état exact à $N$).
   - $r_3$ a `active_from = N+2 > N` → exclue.
2. **Création d'un shareholder ou d'une dépense sur $(K, N]$** :
   - Les 3 composants (`header`, `details`, `weight`/`financials`) ont été créés à une version $v \in (K, N]$ et chacun possède exactement une révision couvrant $N$ avec `active_from ∈ (K, N]`.
   - Même si l'entité a été créée à $K+1$ puis soft-deleted à $K+2 \le N$, sa révision `header` retenue aura `deleted = true`, produisant l'état exact à $N$.
3. **Modification partielle d'un objet existant dans $P_K$** :
   - Si seule `EXPENSE_DETAILS` a été modifiée sur $(K, N]$, seule la table `v3_expense_details` retourne une ligne ; `headerVersion` et `financialsVersion` sont conservés depuis $P_K$.
4. **Sous-objets inchangés sur $(K, N]$** :
   - Aucune ligne retournée par la requête ; les références immuables (`PotVersion.Shareholder`, `PotVersion.Expense`, `headerVersion`, `detailsVersion`) sont conservées telles quelles depuis $P_K$.
5. **Changements de shareholder et effets globaux sur `authorizedUserIds` / `attachedActiveUserIds`** :
   - Création (`AddShareholders`) ou attachement d'un shareholder qui était `isFree == true` dans $P_K$ : si son état final à $N$ a `user_id != null && !deleted`, on ajoute `user_id` à `attachedActiveUserIds` et `authorizedUserIds`.
   - Si un shareholder qui était `isFree == false && !deleted` dans $P_K$ subit sur $(K, N]$ un `SHAREHOLDER_HEADER` (`deleted = true`) ou un `SHAREHOLDER_DETAILS` (détachement ou ré-attachement à un autre utilisateur) : $P_K$ ne stockant pas le mapping `shareholder_id → user_id`, cet effet est classé **effet global potentiel** (traité selon **D2** : fallback intégral ou recomputation ciblée des $S$ shareholders).

---

## 4. Algorithme cible `N−m` à deux chemins

### 4.1 Chemin incrémental (`MaterializePotVersion.prepare`)

1. **Résolution de l'événement (`reads.source`)** :
   Lire `(potId, N)` depuis `v3_pot_event` + `v3_event_position` + `v3_event_counter` ($O(1)$).
2. **Court-circuit `N == 1`** :
   Si `N == 1`, exécuter le **Chemin intégral**.
3. **Recherche et lecture de $K$ (`reads.effects`)** :
   `Optional<PotVersion> refOpt = versions.readLatestBefore(potId, N, minAcceptableK, c);`
   - Si `refOpt.isEmpty()`, exécuter le **Chemin intégral** sans attente ni retry.
4. **Lecture du delta d'état sur $(K, N]$ (`reads.source`)** :
   - Vérifier que `(potId, N)` existe dans `v3_pot_version` (`rows.version(pot, N)`).
   - Exécuter les 8 requêtes `active_from > K AND active_from <= N AND (active_to IS NULL OR active_to > N)`.
   - Si le delta est vide (anomalie défensive) ou si son volume dépasse le seuil de rentabilité (ex. `changedObjects > maxChangedRatio * (S + E)`), basculer vers le **Chemin intégral**.
   - Si une mutation sur $(K, N]$ touche `SHAREHOLDER_HEADER(deleted=true)` ou `SHAREHOLDER_DETAILS` d'un shareholder déjà présent dans $P_K$ avec `!wasFree && !wasDeleted`, appliquer **D2** (fallback intégral ou recomputation ciblée des $S$ shareholders).
5. **Composition $P_N = P_K \oplus \Delta_{K \rightarrow N}$** :
   - Mettre à jour `headerVersion`, `detailsVersion`, `deleted` si `POT_HEADER` / `POT_DETAILS` présents dans le delta.
   - Construire les nouvelles listes immuables `shareholders` et `expenses` en fusionnant les éléments modifiés/créés sur $(K, N]$ avec les éléments inchangés de $P_K$, triées par `SubobjectKey::compareUuid`.
   - Mettre à jour `attachedActiveUserIds` et `authorizedUserIds` pour les nouveaux attachements.
   - Instancier `PotVersion value = new PotVersion(...)` (valide tous les invariants du constructeur compact).
6. **Chargement des sous-objets modifiés** :
   - Collecter `changedKeys` (uniquement les `SubobjectKey` créés ou mis à jour sur $(K, N]$).
   - Vérifier `presentChangedKeys = reads.effects(c -> subobjects.existingKeys(changedKeys, c))`.
   - Charger `loaded = missing.isEmpty() ? List.of() : reads.source(c -> source.contents(value, missing, c))` (ce qui exécute également `verifyShareholderRefs` contre `value`).
7. **Persistance et publication (`finalizeIn`)** :
   - `subobjects.insertOrVerifyAll(loaded)`
   - `versions.insertAndAdvance(value)`
   - `completion.complete()` → `requireCompletionReady()` :
     - `subobjects.requireStored(loaded)`
     - `subobjects.requireKeysPresent(presentChangedKeys)` (si **D1 = A**) ou `allUnchangedKeys` (si **D1 = B**)
     - `versions.requireStored(value)`
   - `versions.closePublication()`

### 4.2 Chemin intégral

Exécuté immédiatement, sans attente de $K$, sans retry supplémentaire et sans quarantaine lorsque :
- `N == 1` ;
- aucune version $K \in [\max(1, N - M_{\max}), N)$ n'est disponible dans `read_pot_version` ;
- le coût estimé du delta sur $(K, N]$ dépasse le seuil configuré (trop de sous-objets modifiés / $K$ trop éloigné) ;
- une mutation sur $(K, N]$ nécessite une recomputation globale non optimisée (détachement/suppression d'un shareholder non libre si **D2 = A**) ;
- le delta retourné est vide (anomalie défensive laissée aux vérifications strictes de `PotHistoryRows`).

**Garde-fou d'erreur** : toute `IllegalStateException("Corrupt PotVersion")`, `InconsistentPotFactsException`, ou erreur transactionnelle/SQL (`Failure`, `DataAccessException`) **remonte telle quelle** à `ConsumptionEngine` sans jamais être convertie en fallback.

---

## 5. Ports, adaptateurs et invariants transactionnels

### 5.1 Modifications de ports et adaptateurs

| Module | Interface / Classe | Évolution prévue |
|---|---|---|
| `port-projection` | `PotVersionStore` | Ajouter `Optional<PotVersion> readLatestBefore(UUID potId, long beforeVersion, long minVersion, RecoveryReadContext context)`. |
| `infra-postgres-read` | `JdbcPotVersionStore` | Implémenter `readLatestBefore` via `SELECT pot_version, schema_version, payload::text FROM read_pot_version WHERE pot_id=? AND pot_version<? AND pot_version>=? ORDER BY pot_version DESC LIMIT 1` sous `db.read(context, ...)`. *(Optionnel selon D3 : alléger les triples décodages `PotVersionJson` dans `insertAndAdvance`/`requireStored`.)* |
| `engine-materialize-pot` | `PotVersionSource` | Ajouter `PotEventRef eventRef(UUID eventId, RecoveryReadContext)` et `PotVersionDelta delta(UUID potId, long afterVersionExclusive, long targetVersionInclusive, RecoveryReadContext)`. |
| `infra-postgres-write-source-read` | `JdbcPotVersionSource` | Implémenter `eventRef` et `delta` (8 requêtes indexées `v3_*_effect` sur `(K, N]` + vérification `v3_pot_version`). |
| `engine-materialize-pot` | `MaterializePotVersion` | Implémenter l'algorithme `N−m` à deux chemins dans `prepare()` et l'instrumentation associée ; conserver `prepareRecovery()` inchangée (oracle intégral). |

### 5.2 Préservation des garanties existantes

- **Exactitude & Immuabilité** : `PotVersion(N)` est validée par son constructeur compact et `verifyShareholderRefs` ; `read_pot_version` et `read_subobject_version` restent strictement immuables.
- **Idempotence & Rejeu** : `insertOrVerifyAll` (`ON CONFLICT DO NOTHING` + `compare`), `insertAndAdvance` (`ON CONFLICT DO NOTHING` + `WHERE read_current_pot.pot_version < excluded.pot_version`) et `prepareRecovery()` sont inchangés.
- **Concurrence & Fencing** : `prepare()` reste purement `READ ONLY` (`REPEATABLE READ`) ; toute mutation READ reste confinée à `FINALIZE` (`READ_COMMITTED`) sous `slots.protectFinal(claim)` et `completion.complete()`.
- **Publication READ & API** : `read_current_pot`, `ReadPot`, `ReadInvitation` et `ReadController` ne sont pas modifiés. Aucune modification de schéma PostgreSQL ni de routage.

---

## 6. Analyse des coûts et cas défavorables

| Poste de coût | Chemin intégral (L7.4-A2) | Chemin incrémental `N−m` (avec D1=A) | Complexité |
|---|---|---|---|
| **1. Recherche de $K$** | N/A | 1 `Index Scan Backward` sur `read_pot_version_pkey` (`LIMIT 1`). | $O(\log V)$ |
| **2. Lecture & décodage de $K$** | N/A | Lecture `~200 KB` JSONB + 1 `PotVersionJson.decode` (si $K$ trouvée). | $O(S + E)$ |
| **3. Lecture des effets** | `source.composition()` : scan complet `v3_write` (`2 000` dépenses + `100` shareholders). | 8 index range scans `active_from ∈ (K, N]`. | $O(\Delta_{K \rightarrow N})$ |
| **4. Composition** | Construction des listes `Shareholder` et `Expense`. | Copie + fusion en mémoire Java (`Shareholder`, `Expense`). | $O(S + E)$ (RAM) |
| **5. Vérification sous-objets** | `25` `SELECT` `existingKeys` + `25` `SELECT` `requireKeysPresent`. | `existingKeys` / `requireKeysPresent` uniquement sur `changedKeys`. | $O(\Delta_{K \rightarrow N})$ |
| **6. Persistance & Validation `N`** | `insertOrVerifyAll` + `insertAndAdvance` + `requireStored` (`1` encode + `3` decodes `PotVersionJson`). | Identique (sauf si **D3** retenue). | $O(S + E)$ |

### Quand le chemin incrémental `N−m` n'est-il pas avantageux ?

- **Lorsque $K$ est très éloigné de $N$** (ex. $N - K$ grand sur un grand pot, ou grand nombre de créations/modifications sur $(K, N]$) : la lecture des effets sur $(K, N]$ finit par balayer une fraction importante de `v3_write`, à laquelle s'ajoute le coût de lecture/décodage JSONB de $P_K$.
- **Seuil de bascule (`maxDeltaDistance`)** : un paramètre bornant $N - K \le M_{\max}$ (ex. $M_{\max} = 64$ ou $128$, à calibrer sur banc L7.4-A) garantit que le chemin incrémental n'est tenté que lorsque $\Delta_{K \rightarrow N}$ reste petit devant $S + E$.

---

## 7. Protocole de qualification, tests et instrumentation

### 7.1 Tests déterministes (oracle = calcul intégral)

1. **Tests unitaires (`engine-materialize-pot`)** :
   - Équivalence stricte intégral vs `N−m` pour : `K = N−1`, `K = N−m` (`m = 2, 5, 20`), remplacements successifs du même sous-objet sur `(K, N]` (création + multiples updates + soft-delete), modifications multi-objets (`AddShareholders`, `UpdateShareholderWeights`), aucune référence $K$ disponible, $K$ hors borne `minVersion` (`N−K > M_max`), corruption de $K$ (propagation sans fallback), changements de shareholder (nom, poids, attachement, détachement, suppression), idempotence et immutabilité de $P_K$.
2. **Tests d'intégration PostgreSQL (`runtime-worker-materialize`)** :
   - Matérialisations hors ordre (`V1`, `V5`, `V3`, `V2`, `V4`), écritures concurrentes et publications inversées (`read_current_pot` avance puis `V` antérieure s'insère).
   - Retries, reprises (`prepareRecovery`) et reconstruction historique complète (`v3-rebuild-read`).

### 7.2 Instrumentation et benchmarks L7.4-A

- **Métriques Micrometer** :
  - `pocoma.v3.potversion.reference_lookup` (Counter, tag `outcome=hit|miss|corrupt`).
  - `pocoma.v3.potversion.reference_distance` (DistributionSummary / Histogramme de `N - K` quand `hit`).
  - `pocoma.v3.potversion.materialize.path` (Counter, tags `path=incremental|full`, `reason=initial_version|no_reference_in_window|distance_or_delta_too_large|global_effect|empty_delta|incremental_hit`).
  - `pocoma.v3.potversion.materialize.duration` (Timer, tags `path=incremental|full`, `phase=prepare|finalize`).
- **Benchmarks (`scripts/performance/run-l7-4a-baseline.py`)** :
  - **Régime séquentiel** : `restore-large-1` (`100/2000`, `1.0 cmd/s`, `n=60`) et `med-1` (`20/200`, `n=60`) — comparer à `f196b2fb`.
  - **Régime backlog / hors-ordre** : rafale sans attente `C-03` sur snapshot `large` et `v3-rebuild-read` sur `prepare-large` — mesurer le taux d'utilisation incrémental, la distribution de $N - K$, les latences par chemin et le coût résiduel de `PotVersionJson`.

---

## 8. Découpage en incréments, risques et arbitrages ouverts

### Verification scope (`docs/testing/Reactor_Verification_Policy.md` §29)

- **Impact production attendu** : `port-projection`, `engine-materialize-pot`, `infra-postgres-write-source-read`, `infra-postgres-read`, `infra-observability-micrometer`.
- **Slice primaire** : `PROJECTION` (`./mvnw -pl runtime-worker-materialize -am test`).
- **Slices secondaires / Global architecture gate / Full reactor / Database** : `NONE` / `NOT REQUIRED` par incrément (aucune migration SQL, aucun changement de dépendances Maven ni des contrats WEB/COMMAND).

### Incréments implémentables

1. **Incrément 1 — Recherche de $K$ et lecture de delta $(K, N]$** : `PotVersionStore.readLatestBefore` (`JdbcPotVersionStore`) + `PotVersionSource.eventRef` / `delta` (`JdbcPotVersionSource`) + tests d'intégration Postgres sur intervalles $(K, N]$ et remplacements multiples.
2. **Incrément 2 — Algorithme `N−m` dans `MaterializePotVersion` + tests oracle** : composition $P_K \oplus \Delta_{K \rightarrow N}$, seuils de fallback, garde-fous de corruption, tests unitaires et concurrence hors-ordre.
3. **Incrément 3 — Instrumentation Micrometer + *(si D3)* allègement `JdbcPotVersionStore`** : métriques de distance $N - K$, taux d'utilisation, latences par chemin.
4. **Incrément 4 — Qualification benchmark L7.4-A** : campagnes séquentielles (`restore-large-1`, `med-1`) et backlog/rebuild.

### Risques et arbitrages ouverts

| ID | Décision à arbitrer | Options |
|---|---|---|
| **D1** | Vérification `requireKeysPresent` en `finalize` | **A (recommandée)** : vérifier uniquement `changedKeys` (s'appuyant sur l'immuabilité de `read_pot_version(K)`). **B** : re-vérifier toutes les `6 302` clés. |
| **D2** | Effet global sur détachement/suppression d'un shareholder `!isFree` | **A (recommandée)** : fallback intégral. **B** : recomputation ciblée des $S$ shareholders. |
| **D3** | Allègement des `3×` décodages `PotVersionJson` dans `JdbcPotVersionStore` | **A (recommandée)** : inclure dans l'Incrément 3. **B** : différer à un lot séparé. |
| **D4** | Valeur initiale de $M_{\max}$ (`maxDeltaDistance`) | **A (recommandée)** : `64` (configurable par propriété, calibrée en Incrément 4). **B** : sans borne fixe (borne uniquement sur le nombre de lignes delta lues). |
