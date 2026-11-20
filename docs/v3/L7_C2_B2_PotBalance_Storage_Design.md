# Pocoma V3 — L7-C2-B2 — Audit et conception du stockage mutualisé de `PotBalance`

- **Date** : 19 novembre 2026
- **Branche** : `feat/v3-l7`
- **Commit de référence C2-B1** : `e25cd84b` (`L7_C2_B1_CLOSED` — stockage persistant Radix-16 de `PotVersion` qualifié)
- **Prérequis qualifiés** :
  - **L7.4-A4** : calcul incrémental `N−m` de `PotBalance` ([`L7_4_A4_3_PotBalance_Performance_Qualification_Report.md`](L7_4_A4_3_PotBalance_Performance_Qualification_Report.md), [`L7_4_A3_A4_Consolidated_Qualification_Report.md`](L7_4_A3_A4_Consolidated_Qualification_Report.md))
  - **L7-C2-B1** : stockage persistant Radix-16 de `PotVersion` ([`L7_C2_B1_PotVersion_Radix16_Implementation_Plan.md`](L7_C2_B1_PotVersion_Radix16_Implementation_Plan.md), [`L7_C2_B1_4_PotVersion_Radix16_Qualification_Report.md`](L7_C2_B1_4_PotVersion_Radix16_Qualification_Report.md))
- **Politique de vérification normative** : [`docs/testing/Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)
- **Nature du mandat** : **Audit technique et conception détaillée exclusivement** (aucune modification du code de production, aucune migration active, aucun changement de contrat public ou métier, aucun engagement sur C2-B3/C3/C4).
- **Verdict** : **`L7_C2_B2_PLAN_APPROVED`** *(arbitrages ARB-1 à ARB-5 et découpage B2.1 à B2.4 approuvés — voir le plan exécutable [`docs/v3/L7_C2_B2_PotBalance_Implementation_Plan.md`](L7_C2_B2_PotBalance_Implementation_Plan.md))*

---

## A. Audit de l'existant

### A.1 Structure physique actuelle des balances (`read_pot_balance`)

Définie dans [`V1__initial_schema.sql`](../../app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql#L213-L225) :

```sql
create table read_pot_balance (
  pot_id uuid not null,
  pot_version bigint not null check(pot_version>0),
  schema_version integer not null check(schema_version=1),
  payload jsonb not null check(jsonb_typeof(payload)='array'),
  primary key(pot_id,pot_version)
);
```

- **Contenu physique** : un tableau JSONB `[{"id":"<uuid>","n":"<BigInteger>","d":"<BigInteger>"}, ...]` contenant **l'intégralité** des shareholders du pot à `pot_version` (y compris les shareholders logiquement supprimés, porteurs d'une balance canonique `0/1`), triés par UUID 128 bits non signé (`SubobjectKey.compareUuid`).
- **Immutabilité** : triggers `BEFORE UPDATE OR DELETE` (`read_pot_balance_immutable`) et `BEFORE TRUNCATE` (`read_pot_balance_no_truncate`) exécutant `read_subobject_immutable()` (`ERRCODE = '23514'`), tous deux en **`ENABLE ALWAYS TRIGGER`**.
- **Clés étrangères** : **aucune FK** vers `read_pot_version` (isolation stricte entre participants de projection READ).
- **Index** : uniquement la PK B-tree `(pot_id, pot_version)`.
- **Privilèges SQL** :
  - Rôle worker `pocoma_read` : `SELECT, INSERT` uniquement (`REVOKE ALL` sur `PUBLIC` et `pocoma_read`).
  - Rôle HTTP READ `pocoma_read_http` ([`grant-l7-http-read.sql`](../../scripts/v3/grant-l7-http-read.sql#L16-L20)) : **`SELECT` uniquement**.

### A.2 Structure physique actuelle des agrégats (`read_pot_balance_aggregate`)

Définie dans [`V2__pot_balance_aggregate.sql`](../../app/infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql#L22-L54) :

```sql
create table read_pot_balance_aggregate (
  pot_id uuid not null,
  pot_version bigint not null check (pot_version > 0),
  schema_version integer not null check (schema_version = 1),
  bucket_count integer not null check (bucket_count >= 0),
  payload jsonb not null check (jsonb_typeof(payload) = 'object'),
  primary key (pot_id, pot_version),
  foreign key (pot_id, pot_version) references read_pot_balance(pot_id, pot_version)
);
```

- **Contenu physique** : un objet JSONB `{"s":[...],"b":[...]}` encodé par [`PotBalanceAggregateJson.java`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/PotBalanceAggregateJson.java#L33-L68) :
  - `s` : dictionnaire ordonné des shareholders (`id`, `wn`/`wd` poids par défaut, `del` booléen de suppression logique, `fn`/`fd` contribution nette fixe `fixedNet`).
  - `b` : buckets de dépenses à participation par défaut ordonnés par `BucketKey` (`p` indices entiers D1 dans `s`, `swn`/`swd` somme des poids spécifiques, `an`/`ad` montant cumulé, `sq` contributions pondérées spécifiques `[{i, n, d}]`).
- **Immutabilité** : triggers `read_pot_balance_aggregate_immutable` et `read_pot_balance_aggregate_no_truncate` en **`ENABLE ALWAYS TRIGGER`**.
- **Clés étrangères** : `FOREIGN KEY (pot_id, pot_version) REFERENCES read_pot_balance(pot_id, pot_version)` — un agrégat ne peut jamais exister sans sa balance associée, mais l'inverse est autorisé (versions pré-V2, ou dépassement des plafonds D5 `maxAggregateBuckets = 256` / `maxAggregateBucketEntries = 10 000`).
- **Index** : uniquement la PK B-tree `(pot_id, pot_version)` (utilisée en scan backward pour `readLatestAggregateBefore`).
- **Privilèges SQL** :
  - Rôle worker `pocoma_read` : `SELECT, INSERT` uniquement (vérifié par bloc d'assertion ACL `aclexplode` + `BootstrapGuard`).
  - Rôle HTTP READ `pocoma_read_http` : **AUCUN privilège** (`read_pot_balance_aggregate` est une structure interne de `MaterializePotBalance`, invisible de `runtime-web-read`).

### A.3 Relations entre versions logiques et contenus persistés

- **Cardinalité actuelle** : strictement `1:1` par `(pot_id, pot_version)` dans `read_pot_balance` (toute version active) et `0..1` par `(pot_id, pot_version)` dans `read_pot_balance_aggregate`.
- **Version terminale (`DeletePot`)** : aucune ligne dans `read_pot_balance` ni `read_pot_balance_aggregate` (`Optional.empty()`).
- **Données recopiées intégralement à chaque version** :
  - Sur `read_pot_balance` : l'intégralité du vecteur des `S` shareholders (UUID 36 caractères + numérateur/dénominateur base 10 + clés JSON `"id"`, `"n"`, `"d"`), même si **0** shareholder n'a changé.
  - Sur `read_pot_balance_aggregate` : l'intégralité du dictionnaire `s` (`S` shareholders avec poids et `fixedNet`) et de tous les buckets `b` (`G` buckets), même sur une mutation non financière ou un changement de poids sans impact sur `b`.
- **Conséquence post-C2-B1** : sur le profil `LARGE` (`100` shareholders, `2 000` dépenses, `4 004` versions), `read_pot_balance` (`22,82 Mo`) + `read_pot_balance_aggregate` (`25,41 Mo`) représentent **`48,23 Mo`**, soit **`5,1×` le volume complet de `read_pot_version*` Radix-16 (`9,41 Mo`)** et **`~58 %`** du stockage READ métier total (`~82,8 Mo`) — dont **`97,3 %`–`97,5 %` en TOAST**.

### A.4 Accès SQL en lecture et en écriture

1. **Écriture (`JdbcPotBalanceStore`, transaction `READ_COMMITTED` via `ReadJdbc.write`)** :
   - `insertOrVerify(PotBalance)` : `INSERT INTO read_pot_balance(...) VALUES (?,?,1,?::jsonb) ON CONFLICT DO NOTHING`, suivi systématiquement d'un `SELECT payload::text` (`load`) + décodage JSON complet + `ReadJdbc.same(b, actual)` (**lecture + désérialisation systématique même sur une insertion nouvelle sans conflit**).
   - `insertOrVerifyAggregate(PotBalanceAggregate)` : `INSERT INTO read_pot_balance_aggregate(...) VALUES (?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING`, suivi systématiquement d'un `SELECT` (`loadAggregate`) + décodage JSON complet + `ReadJdbc.same(...)`.
   - `requireStored` / `requireAggregateStored` : appelés par `requireCompletionReady()` avant complétion de slot (fencing/reprise).
2. **Lecture de matérialisation (`MaterializePotBalance`, transaction `REPEATABLE_READ` via `PreparationReads.effects`)** :
   - `readLatestAggregateBefore(pot, beforeVersionExclusive, minVersionInclusive, ctx)` : `SELECT pot_version, schema_version, bucket_count, payload::text FROM read_pot_balance_aggregate WHERE pot_id=? AND pot_version<? AND pot_version>=? ORDER BY pot_version DESC LIMIT 1`.
   - `prepareRecovery` → `store.evidence(pot, version, expected, ctx)` : `SELECT payload::text FROM read_pot_balance WHERE pot_id=? AND pot_version=?` et comparaison exacte avec l'oracle intégral.
3. **Lecture HTTP READ (`ReadPot`, transaction `REPEATABLE_READ`, rôle `pocoma_read_http`)** :
   - `ReadPot.main` (`GET /pots/{id}`, `GET /pots/{id}/versions/{v}`) et `ReadPot.balance` (`GET /pots/{id}/versions/{v}/balance`) appellent exclusivement `balances.read(pot, version, c)` qui exécute `SELECT payload::text FROM read_pot_balance WHERE pot_id=? AND pot_version=?`.
   - Aucune lecture partielle d'un seul shareholder n'existe dans le contrat public : `ReadPot` consomme et vérifie systématiquement le vecteur complet (`verifyBalanceKeys`).

### A.5 Dépendances du runtime et de l'outillage

| Composant | Dépendance exacte aux tables `read_pot_balance*` |
|---|---|
| [`PotBalanceStore`](../../app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/PotBalanceStore.java) | Port métier neutre vis-à-vis du schéma physique (8 méthodes). |
| [`JdbcPotBalanceStore`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcPotBalanceStore.java) | SQL direct sur `read_pot_balance` et `read_pot_balance_aggregate` ; codec inline pour `PotBalance` et [`PotBalanceAggregateJson`](../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/PotBalanceAggregateJson.java) pour l'agrégat. |
| [`MaterializePotBalance`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotBalance.java) | Orchestration `N−m` A4 ; écriture atomique balance + agrégat + `SlotCompletion` dans `finalizeIn`. |
| [`ReadPot`](../../app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/ReadPot.java) | Lit uniquement via `PotBalanceStore.read(pot, version, c)` (zéro SQL). |
| [`ReadinessOracle`](../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/maintenance/ReadinessOracle.java#L362-L371) | Vérifie l'existence exacte de `read_pot_balance(pot_id, pot_version)` pour toute version non supprimée de `read_pot_version` (et l'absence pour `deleted = true`). Ne vérifie pas encore de table de contenus. |
| [`BootstrapGuard`](../../app/infra-postgres-core/src/main/java/com/kartaguez/pocoma/infra/postgres/core/bootstrap/BootstrapGuard.java#L150-L165) | `READ_UPDATES` valide les privilèges `SELECT, INSERT` (zéro `UPDATE`/`DELETE`/`PUBLIC`) sur `read_pot_balance` et `read_pot_balance_aggregate`. |
| [`L7ReactorArchitectureTest`](../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/l7/L7ReactorArchitectureTest.java#L191-L196) | Inventaire statique des migrations actives de `infra-postgres-read` (`V1`, `V2`, `V3`). |
| [`grant-l7-http-read.sql`](../../scripts/v3/grant-l7-http-read.sql) | Accorde `SELECT` à `pocoma_read_http` sur `read_pot_balance` (pas sur `read_pot_balance_aggregate`). |
| [`v3-rebuild-read.py`](../../scripts/operations/v3-rebuild-read.py) & [`setup-v3-database.py`](../../scripts/operations/setup-v3-database.py) | Liste `CANONICAL_MIGRATION_TABLES`, séquence DDL `V1+V2+V3`, contrôle `_evaluate_integrity()`. |
| Scripts de performance | `run-l7-4-a3-a4-multibucket.py`, `run-l7-4a4-targeted.py`, `run-l7-c2-b1-qualification.py`, `run-l7-4a-baseline.py`, `observe-l7-spec09-backlog.py`, `v3-oracle-probe.py`, `v3-seed.py`, `spec09_bench.py`. |

---

## B. Analyse des duplications, des quatre situations métier et de leurs causes

Rappel des contenus exacts (hors clé logique `(pot_id, version)`) :
- **Contenu `PotBalance`** : `Map<UUID, Fraction>` ordonnée par `SubobjectKey.compareUuid`, couvrant tous les shareholders (actifs et supprimés, ces derniers à `0/1`), de somme exacte `0/1`.
- **Contenu `PotBalanceAggregate`** : `(shareholders: List<ShareholderEntry(id, defaultWeight, deleted, fixedNet)>, buckets: Map<BucketKey, BucketValue>)`.

### B.1 Vérification des quatre situations dans le modèle métier réel

#### Situation A — Balance inchangée, agrégat inchangé (**RÉELLE ET FRÉQUENTE**)

Les deux contenus sont strictement identiques à une version antérieure dans les cas suivants (vérifiés dans [`ExecutePotCommand.java`](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/ExecutePotCommand.java), [`PotBalanceAggregateCalculator.java`](../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/PotBalanceAggregateCalculator.java#L245-L248) et [`Amount.java`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Amount.java)) :

1. **Modifications du pot sans effet financier** : `UpdatePotDetails` (changement de libellé).
2. **Modifications des autorisations et détails de shareholders** :
   - `UpdateShareholderDetails` (changement de nom, rattachement/détachement `userId` `SELF`/`DETACH`/`KEEP`).
   - `AttachShareholderViaInvitation` (rattachement d'un utilisateur via invitation, modifiant `authorizedUsers`/`attachedActiveUsers` sans impact financier).
3. **Modifications non financières d'une dépense** : `UpdateExpenseDetails` (libellé ou date métier).
4. **Dépenses de montant nul (`amount = 0`)** : `Amount` autorise `0/1` (`>= 0`) et `PotBalanceAggregateCalculator.applyExpense` effectue un retour anticipé si `amount == 0` ; créer, supprimer ou modifier financièrement une dépense en conservant `amount = 0` laisse les deux contenus strictement inchangés.
5. **États historiques récurrents (non consécutifs)** : par exemple suppression d'une dépense qui annule la création précédente (`v_k` et `v_{k+2}` identiques), ou modification annulée ultérieurement.
6. **Replays, versions hors ordre (`N−m`) et reconstruction intégrale** : un rejeu cible le même `(pot_id, version)` ; lors d'une arrivée hors ordre (`N` matérialisé avant `N−1`) ou d'une reconstruction intégrale, le contenu canonique des versions sans delta financier est identique indépendamment de l'ordre d'exécution.

#### Situation B — Balance modifiée, agrégat inchangé (**IMPOSSIBLE sous l'agrégat monolithique actuel ; POSSIBLE uniquement si l'agrégat est scindé**)

- **Preuve mathématique sur le modèle actuel** : dans [`PotBalanceAggregateCalculator.java`](../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/PotBalanceAggregateCalculator.java#L95-L126), `PotBalance = calculate(PotBalanceAggregate)` est une **fonction pure et déterministe** du seul `PotBalanceAggregate`, car `ShareholderEntry` embarque déjà `defaultWeight`. Par conséquent, pour deux versions disposant toutes deux d'un `PotBalanceAggregate` :
  $$\mathcal{A}(v_1) = \mathcal{A}(v_2) \implies \text{calculate}(\mathcal{A}(v_1)) = \text{calculate}(\mathcal{A}(v_2)) \implies \mathcal{B}(v_1) = \mathcal{B}(v_2)$$
  La situation B est donc **mathématiquement impossible** avec un contenu d'agrégat monolithique.
- **Deux nuances structurantes** :
  1. **Cas dégénéré `bucket_cap`** (`G > 256` ou `entries > 10 000`) : aucun agrégat n'est persisté, seule la balance est renouvelée.
  2. **Décomposition interne de l'agrégat (Option C1)** : dans `PotBalanceAggregate`, les `buckets` sont **strictement indépendants des poids par défaut**. Lors d'un `UpdateShareholderWeights` (avec effet sur la balance), `PotBalance` et le vecteur `defaultWeights` changent, mais **`buckets` (qui représente 50 % à 75 % de la taille de l'agrégat en multi-buckets) reste 100 % inchangé**.

#### Situation C — Balance inchangée, agrégat modifié (**RÉELLE — JUSTIFIE LA MUTUALISATION INDÉPENDANTE**)

Vérifiée dans le domaine métier :
1. **Retrait logique d'un shareholder (`DeleteShareholder`)** : un shareholder ne peut être supprimé que s'il n'est référencé par aucune dépense active (sa balance vaut déjà `0/1`). Or [`PotBalanceCalculator.java`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/PotBalanceCalculator.java#L31-L32) et [`ReadPot.java`](../../app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/ReadPot.java#L154-L160) conservent les shareholders supprimés avec `0/1` dans `PotBalance`. Donc **`PotBalance` est 100 % inchangée**, tandis que `PotBalanceAggregate.shareholders[i].deleted` passe de `false` à `true`.
2. **Modification d'un poids (`UpdateShareholderWeights`) sans effet sur la balance** :
   - Poids modifié pour un shareholder qui ne participe à aucune dépense active en poids par défaut (nouveau shareholder, pot sans dépense, ou uniquement poids spécifiques).
   - **Homothétie proportionnelle des poids** (ex. `(1, 1) → (2, 2)` sur un pot purement en poids par défaut) : les ratios `w_i / sum(w)` sont invariants → `PotBalance` inchangée, `defaultWeight` dans l'agrégat modifié.
3. **Opérations de dépenses à effet net nul sur la balance mais modifiant les buckets ou `fixedNet`** :
   - Dépense de montant `> 0` payée par `A` avec pour seul participant `A` en poids par défaut : balance nette inchangée (`+X - X = 0`), mais `fixedNet[A] += X` et bucket `{A}` incrémenté de `X`.
   - Passage d'une participation par défaut à un poids spécifique numérique égal (`UpdateExpenseFinancials`) : balance inchangée, transfert entre `buckets` et `fixedNet`.
   - **Dépenses qui s'équilibrent (compensation / règlement)** : `A` paie `10` pour `{A, B}`, puis `B` paie `10` pour `{A, B}` → la balance redevient `{A: 0, B: 0}` (identique à `v1` !), alors que l'agrégat accumule `fixedNet[A]=10, fixedNet[B]=10, bucket({A,B})=20`.

#### Situation D — Balance modifiée, agrégat modifié (**RÉELLE ET DOMINANTE SUR CHARGES FINANCIÈRES**)

1. `CreateExpense` avec `amount > 0` et effet net non nul (cas nominal majoritaire).
2. `UpdateExpenseFinancials` avec effet net non nul.
3. `DeleteExpense` d'une dépense active de montant `> 0` à effet net non nul.
4. `UpdateShareholderWeights` non proportionnel affectant au moins un bucket actif.
5. `AddShareholders` : ajoute une nouvelle entrée `(newId -> 0/1)` dans `PotBalance` (vecteur agrandi, clé requise par `ReadPot.verifyBalanceKeys`) **et** ajoute une `ShareholderEntry` (avec ré-indexation potentielle des buckets D1) dans `PotBalanceAggregate`.
6. *(Cas terminal `DeletePot` : aucune balance ni agrégat persisté.)*

### B.2 Synthèse de granularité et fréquences estimées par profil de charge

| Situation | Balance | Agrégat monolithique | Partie `buckets` seule | Fréquence — Charge pure dépenses (ex. `ab-large` seed) | Fréquence — Charge mixte réaliste (ex. `ab-large` 20 mixtes) | Fréquence — Phase d'onboarding / édition métadonnées |
|---|---|---|---|---:|---:|---:|
| **A** | Inchangée | Inchangé | Inchangée | `~0 %` | **`~20–35 %`** *(mesuré : `7/20 = 35 %` en C2-B1.4)* | **`~60–90 %`** |
| **B** *(monolithique)* | Modifiée | Inchangé | — | `0 %` *(impossible)* | `0 %` *(impossible)* | `0 %` *(impossible)* |
| **B'** *(poids seuls, si C1)* | Modifiée | Modifié (`s` seul) | **Inchangée** | `~0 %` | `~5–25 %` *(mesuré : `15/60 = 25 %` en `multibucket-realistic`)* | `~0–5 %` |
| **C** | **Inchangée** | Modifié | Modifiée ou inchangée | `~0 %` | `~1–5 %` | `~2–10 %` |
| **D** | Modifiée | Modifié | Modifiée | **`~99,9 %`** | **`~55–75 %`** | `~5–30 %` |

> [!IMPORTANT]
> **Constat central de l'audit** : Contrairement à `PotVersion` (où 1 dépense modifie `1 / 2 000` entrées, soit `0,05 %` de la structure), une dépense financière dans `PotBalance` modifie le solde de tous ses participants (souvent `100 %` des `S` shareholders en `G = 1`). La mutualisation de contenus complets élimine **100 %** des duplications en Situation A et **100 %** de la duplication balance en Situation C, mais **ne réduit pas** le nombre de contenus distincts en Situation D. En revanche, l'encodage binaire canonique (vs JSONB texte/TOAST) réduit le volume physique et WAL **même en Situation D** (`~2×` à `3×`).

---

## C. Comparaison des architectures (Options A, B et C)

### C.1 Option A — Références vers des contenus immuables (Content-Addressed Storage)

Séparation de l'identité logique `(pot_id, pot_version)` et du contenu physique immuable adressé par SHA-256 (`bytea`, 32 octets), indépendamment pour les balances et les agrégats :

- **Tables de contenus immuables** :
  - `read_pot_balance_content(content_hash PK, schema_version, shareholders_count, payload)`
  - `read_pot_balance_aggregate_content(content_hash PK, schema_version, bucket_count, payload)`
- **Tables de versions logiques (légères, ~80 B/ligne, 0 TOAST)** :
  - `read_pot_balance(pot_id, pot_version, content_hash FK -> read_pot_balance_content)` — PK `(pot_id, pot_version)`
  - `read_pot_balance_aggregate(pot_id, pot_version, content_hash FK -> read_pot_balance_aggregate_content)` — PK `(pot_id, pot_version)`, FK `(pot_id, pot_version) -> read_pot_balance(pot_id, pot_version)`
- **Deux variantes d'encodage du `payload`** :
  - **A-JSONB** : conserve `jsonb` identique à V1/V2 (hachage calculé côté Java sur sérialisation canonique).
  - **A-BIN** : `bytea` binaire canonique (comme `read_pot_version_node.payload` qualifié en C2-B1 : UUID 16 octets big-endian, `BigInteger.toByteArray()` minimal avec longueur préfixée).
- **Mécanisme d'écriture idempotent et sans collision silencieuse** :
  1. `INSERT INTO read_pot_balance_content(...) VALUES (...) ON CONFLICT (content_hash) DO NOTHING RETURNING content_hash`.
  2. Si `RETURNING` est vide (conflit / contenu déjà présent) : `SELECT` sur `content_hash` et vérification d'égalité exacte (rejet `IllegalStateException` sur collision/corruption). Si `RETURNING` renvoie le hash (insertion neuve) : aucune relecture inutile de vérification !
  3. `INSERT INTO read_pot_balance(pot_id, pot_version, content_hash) VALUES (?,?,?) ON CONFLICT (pot_id, pot_version) DO NOTHING RETURNING 1`. Si vide, `SELECT content_hash` et vérification `actualHash.equals(expectedHash)`.
- **Lecture historique directe** : 1 seule jointure PK (`read_pot_balance JOIN read_pot_balance_content USING (content_hash) WHERE pot_id=? AND pot_version=?`), **0 chaîne de deltas**, **0 index secondaire**.

### C.2 Option B — Réutilisation par référence à une version antérieure (`source_version`)

Chaque ligne `(pot_id, pot_version)` possède `source_version bigint not null` (`FK (pot_id, source_version) -> read_pot_balance(pot_id, pot_version)`) et `payload` nullable (`CHECK ((source_version = pot_version) = (payload IS NOT NULL))`), avec un trigger `BEFORE INSERT` interdisant les chaînes (`source_version` cible doit avoir `source_version = pot_version`).

**Limites rédhibitoires dans Pocoma V3** :
1. **Non-déterminisme sous matérialisation hors ordre** : `POT_EVENT` est partitionné par `event_id` (4 partitions), non par `pot_id` (`55,6 %` de versions hors ordre observées dans `out-of-order-nm-large`). Si `v=10` arrive avant `v=9` (contenu identique), soit `source_version <= pot_version` empêche `v=9` de réutiliser `v=10` (perte de déduplication), soit `source_version != pot_version` rend le graphe physique dépendant de l'ordonnancement concurrent → **reconstruction physiquement non déterministe**.
2. **Impossible de dédupliquer deux versions non adjacentes** (ex. retour à balance nulle) sans ajouter un index sur `content_hash` — ce qui ramène au coût d'Option A sans sa simplicité.
3. **Concurrence et FK fragile** : deux workers matérialisant simultanément `v=9` et `v=10` identiques ne voient pas la ligne non committée de l'autre (duplication ou violation FK).
4. **Complexité SQL supérieure** : auto-FK, trigger anti-chaîne, auto-jointure `LEFT JOIN` + `COALESCE` à chaque lecture.

### C.3 Option C — Stockage structurel partagé

Analysée **séparément** pour les balances et pour les agrégats (sans analogie forcée avec Radix-16) :

1. **Option C pour `PotBalance` (arbre persistant / Radix-16 sur `Map<UUID, Fraction>`) — REJETÉE** :
   - Contrairement à `PotVersion` (`E = 2 000..10 000` dépenses, 1 entrée modifiée par événement, lectures ciblées `R3a/R3b/R3c`), `PotBalance` a une cardinalité faible `O(S)` (`S = 20..500`), **aucune lecture ciblée d'un seul shareholder** (`ReadPot` exige le vecteur complet), et une mutation typique modifie `P` participants souvent proche de `S` (en `G = 1`, `100 %` des feuilles seraient réécrites à chaque dépense → surcoût net de stockage, WAL et latence).
2. **Option C1 pour `PotBalanceAggregate` (scission en 2 contenus : `shareholders_header` + `buckets_content`)** :
   - `read_pot_balance_aggregate` référence deux empreintes indépendantes : `shareholders_hash` (`s`) et `buckets_hash` (`b`, nullable si `bucket_count = 0`).
   - **Avantage** : sur `UpdateShareholderWeights` et dépenses purement spécifiques, `buckets_hash` (50 % à 75 % de l'agrégat en multi-buckets) est **100 % réutilisé**.
   - **Coût** : 1 table de contenus supplémentaire (`read_pot_balance_aggregate_buckets`), 1 jointure supplémentaire dans `readLatestAggregateBefore`, et **0 % de gain** sur `CreateExpense`/`UpdateExpenseFinancials`/`DeleteExpense` en bucket par défaut (qui modifient `b`).
3. **Option C2 pour `PotBalanceAggregate` (adressage individuel par bucket `bucket_hash`) — REJETÉE à ce stade** :
   - Ne partage les buckets que si `G >> 1` (0 % de gain sur `G = 1` comme `restore-large-1`/`ab-large`), alors que `G` est strictement plafonné par D5 (`G <= 256`, `<= 21 Ko` JSONB ou `~8–10 Ko` en `bytea`), et imposerait un trigger `BEFORE INSERT` sur `bytea[]` (type `read_pot_version_node_guard`) + `unnest` à chaque `readLatestAggregateBefore`.

### C.4 Matrice comparative

| Critère | **Option A-BIN** *(Contenus immuables `bytea`)* | **Option A-JSONB** *(Contenus immuables `jsonb`)* | **Option B** *(Référence version antérieure)* | **Option C1** *(A-BIN + split agrégat `s`/`b`)* | **Option C2 / Radix-16** *(Arbre / par bucket)* |
|---|---|---|---|---|---|
| **Simplicité schéma & code** | **Très élevée** (2 tables contenu + 2 tables version, FK natives) | **Très élevée** | Moyenne (auto-FK, trigger anti-chaîne) | Élevée (3 tables contenu) | Faible (triggers `bytea[]`, `WITH RECURSIVE` / `unnest`) |
| **Déduplication Situation A** | **100 %** (bal + agg) | **100 %** (bal + agg) | Partielle (cassée hors ordre) | **100 %** (bal + agg) | **100 %** |
| **Déduplication Situation C** | **100 %** (bal) | **100 %** (bal) | Partielle (non-adjacent perdu) | **100 %** (bal) | **100 %** (bal) |
| **Réduction volume Situation D (`G=1`)** | **`~2,0×–3,0×`** *(codec binaire compact)* | `~1,0×` *(+1–2 % pointeurs)* | `~1,0×` | `~2,0×–3,0×` | `~0,9×–1,5×` *(surcoût nœuds)* |
| **Réduction volume `UpdateWeights` (`G=60..240`)** | `~2,0×–3,0×` *(binaire)* | `~1,0×` | `~1,0×` | **`~3,5×–6,0×`** *(binaire + reuse `b`)* | `~4×–8×` |
| **Réduction WAL (Situation A / mixte 35 %)** | **`-97 %` (Sit. A) / `~-65 à -75 %` (mixte)** | `-95 %` (Sit. A) / `~-33 %` (mixte) | Partielle | `-97 %` (Sit. A) / `~-70 %` (mixte) | Variable (surcoût `INSERT` multiples) |
| **Latence écriture** | **Meilleure** *(pas de relecture hors conflit, pas de JSON)* | Légère amélioration *(pas de relecture hors conflit)* | Dégradée *(trigger + lookup)* | Proche de A-BIN (+1 `INSERT` conditionnel) | Dégradée *(multi-INSERT + triggers)* |
| **Latence lecture historique** | **Meilleure ou égale** *(1 PK join, décodage binaire `5–10×` plus rapide que Jackson)* | Quasi égale *(+1 PK join ~0,05 ms)* | Égale (`LEFT JOIN`) | +1 `LEFT JOIN` sur `readLatestAggregateBefore` | Dégradée sur `PotBalance` |
| **Déterminisme reconstruction hors ordre** | **Strict (100 %)** | **Strict (100 %)** | **Cassé** | **Strict (100 %)** | **Strict (100 %)** |
| **Coût opérationnel** | **Faible** (identique C2-B1) | **Très faible** | Moyen | Faible | Élevé |

---

## D. Architecture recommandée et justification

### D.1 Choix recommandé : **Option A-BIN (Références vers des contenus immuables en binaire canonique)**

L'architecture retenue est **Option A** avec encodage binaire canonique `bytea` (**A-BIN**), en conservant une mutualisation **strictement indépendante** des balances et des agrégats, et en conservant la séparation physique entre `read_pot_balance` (projection publique READ) et `read_pot_balance_aggregate` (cache interne incrémental A4).

**Justification architecturale** :
1. **Architecture la plus simple satisfaisant tous les besoins démontrés** : 0 trigger sur `bytea[]`, 0 récursion SQL, uniquement des FK natives PostgreSQL sur `bytea` 32 octets, et des triggers d'immuabilité `ENABLE ALWAYS` identiques à V1/V2/V3.
2. **Déterminisme total face au hors-ordre `N−m` et aux reconstructions** : les empreintes SHA-256 dépendent exclusivement de la valeur métier canonique, jamais de l'ordre d'arrivée des partitions ou des versions.
3. **Efficacité réelle sur les 4 situations** :
   - **Situation A** : `0` nouvelle ligne de contenu, `0` TOAST, seules 2 lignes pointeurs de `~80 B` sont écrites (~`-97 %` de volume et de WAL).
   - **Situation C** : réutilisation complète du contenu `PotBalance`, renouvellement isolé du contenu agrégat.
   - **Situation D** : l'encodage binaire canonique divise déjà par `~2×–3×` la taille des contenus (suppression des UUID ASCII 36 octets, des noms de champs JSON et des chaînes décimales base 10), évitant le basculement TOAST sur les pots petits/moyens (`S <= 60`) et réduisant drastiquement le WAL même quand chaque dépense crée une nouvelle balance.
4. **Indépendance et moindre privilège préservés** : `pocoma_read_http` ne reçoit `SELECT` que sur `read_pot_balance` et `read_pot_balance_content` ; les tables d'agrégats restent invisibles du runtime HTTP READ.
5. **Évolutivité ouverte vers C1** : si des mesures futures en production démontraient des pots multi-buckets (`G > 50`) soumis à une forte proportion d'`UpdateShareholderWeights`, scinder `read_pot_balance_aggregate_content` en `(shareholders_hash, buckets_hash)` resterait une évolution locale et maîtrisée.

### D.2 Schéma SQL cible (`V4__pot_balance_shared_storage.sql`)

```sql
create table read_pot_balance_content (
  content_hash       bytea   primary key check (octet_length(content_hash) = 32),
  schema_version     integer not null    check (schema_version = 1),
  shareholders_count integer not null    check (shareholders_count >= 0),
  payload            bytea   not null
);

create table read_pot_balance_aggregate_content (
  content_hash       bytea   primary key check (octet_length(content_hash) = 32),
  schema_version     integer not null    check (schema_version = 1),
  shareholders_count integer not null    check (shareholders_count >= 0),
  bucket_count       integer not null    check (bucket_count >= 0),
  payload            bytea   not null
);

drop table read_pot_balance_aggregate;
drop table read_pot_balance;

create table read_pot_balance (
  pot_id       uuid   not null,
  pot_version  bigint not null check (pot_version > 0),
  content_hash bytea  not null references read_pot_balance_content(content_hash),
  primary key (pot_id, pot_version)
);

create table read_pot_balance_aggregate (
  pot_id       uuid   not null,
  pot_version  bigint not null check (pot_version > 0),
  content_hash bytea  not null references read_pot_balance_aggregate_content(content_hash),
  primary key (pot_id, pot_version),
  foreign key (pot_id, pot_version) references read_pot_balance(pot_id, pot_version)
);
```

- **Garde-fou de migration (identique à `V3__pot_version_radix16.sql`)** : vérifie `${expectedDatabase}`, `${expectedSchema}`, `${expectedAdmin}`, `${expectedRuntime}`, l'existence de `read_subobject_immutable()`, et impose que `read_pot_balance` et `read_pot_balance_aggregate` soient **vides** avant `DROP`/`CREATE` (toute base peuplée passe par `v3-rebuild-read.py`).
- **Immutabilité effective** : triggers `BEFORE UPDATE OR DELETE` et `BEFORE TRUNCATE` (`read_subobject_immutable()`) avec **`ALTER TABLE ... ENABLE ALWAYS TRIGGER`** sur les **4 tables**.
- **Index** : uniquement les 4 PK B-tree — **0 index secondaire**.

### D.3 Identité canonique, exactitude financière et vérification des collisions

#### Types numériques et normalisation exacte

Dans Pocoma, toutes les valeurs financières sont représentées en Java par [`Fraction`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/value/Fraction.java#L8-L26) (`record Fraction(BigInteger numerator, BigInteger denominator)`), jamais en virgule flottante ni en `BigDecimal` à échelle libre :
- **Dénominateur strictement positif** (`denominator > 0`, tout signe négatif est porté par `numerator`).
- **Zéro unique** : si `numerator == 0`, alors `denominator = BigInteger.ONE` (`0/1` unique ; impossible d'avoir `0/2` ou `-0/1`).
- **Forme irréductible** : si `numerator != 0`, `gcd(|numerator|, denominator) = 1`.
- **Aucune limite de précision** : `BigInteger` de taille arbitraire.

**Théorème d'équivalence représentation ↔ valeur métier** :
Deux instances `Fraction` représentent le même nombre rationnel exact **si et seulement si** leurs couples `(numerator, denominator)` sont égaux. De plus, `BigInteger.toByteArray()` produit la représentation binaire big-endian en complément à deux **minimale et unique** (aucun octet de signe redondant).

#### Sérialisation binaire canonique (bijection stricte)

1. **Encodage d'un `Fraction`** :
   - `varint(numBytes.length)` (entier non signé LEB128 ou `int32_be`, `>= 1`) || `numBytes` (`numerator.toByteArray()`)
   - `varint(denBytes.length)` (`>= 1`) || `denBytes` (`denominator.toByteArray()`)
   - Au décodage : on vérifie que `numBytes` et `denBytes` sont **minimaux** (pas de bourrage `0x00`/`0xFF` redondant), que `den > 0`, que `num == 0 => den == 1`, et que `gcd(|num|, den) == 1` (en reconstruisant `Fraction` et en vérifiant l'égalité exacte de `numerator` et `denominator`).
2. **Encodage canonique `PotBalance`** (`schema_version = 1`) :
   - Entrées triées par ordre strict `SubobjectKey.compareUuid` (UUID 128 bits non signé, déjà imposé par le constructeur `PotBalance`).
   - `int32_be(shareholdersCount)` puis pour chaque entrée : `int64_be(msb)` || `int64_be(lsb)` || `encodeFraction(balance)`.
   - Vérification au décodage : ordre strictement croissant des UUID et somme exacte égale à `Fraction.ZERO`.
   - **Empreinte SHA-256** :
     $$\text{content\_hash} = \text{SHA-256}\big(\texttt{"pocoma-bal-v1\textbackslash 0"} \;\|\; \text{uint8}(1) \;\|\; \text{payload\_bytes}\big)$$
3. **Encodage canonique `PotBalanceAggregate`** (`schema_version = 1`) :
   - `int32_be(shareholdersCount)` puis pour chaque `ShareholderEntry` (ordre strict `SubobjectKey.compareUuid`) : `uuid(16B)` || `uint8(deleted ? 1 : 0)` || `encodeFraction(defaultWeight)` || `encodeFraction(fixedNet)`.
   - `int32_be(bucketCount)` puis pour chaque `(BucketKey, BucketValue)` (ordre strict `BucketKey.compareTo`) :
     - `int32_be(defaultIndices.size())` || `int32_be(idx_0) ...` (strictement croissants)
     - `encodeFraction(specificWeightTotal)` || `encodeFraction(totalAmount)`
     - `int32_be(specWeightedAmounts.size())` || pour chaque `(idx, frac)` (indices strictement croissants) : `int32_be(idx)` || `encodeFraction(frac)`.
   - **Empreinte SHA-256** :
     $$\text{content\_hash} = \text{SHA-256}\big(\texttt{"pocoma-bagg-v1\textbackslash 0"} \;\|\; \text{uint8}(1) \;\|\; \text{payload\_bytes}\big)$$

#### Comparaison exacte et protection contre les collisions d'empreintes

- Grâce à la **bijection prouvée** entre la valeur métier exacte et `payload_bytes`, la comparaison octet à octet (`Arrays.equals(expectedPayload, storedPayload)` + égalité de `schema_version` et des compteurs) est **mathématiquement équivalente** à l'égalité métier exacte — aucune égalité approximative n'est possible.
- **Protocole sur conflit `ON CONFLICT (content_hash) DO NOTHING RETURNING content_hash`** :
  - Si `RETURNING` renvoie le hash : la ligne vient d'être insérée dans la transaction courante, aucune collision possible.
  - Si `RETURNING` est vide (ligne existante) : l'adaptateur lit `(schema_version, ..., payload)` pour ce `content_hash` et vérifie `Arrays.equals(expectedPayload, actualPayload)` (et métadonnées). Toute divergence lève immédiatement `IllegalStateException("Divergent read_pot_balance[_aggregate]_content for hash ...")`, provoquant le rollback atomique de la transaction (zéro corruption silencieuse, même en cas de collision SHA-256 théorique ou de corruption disque).

### D.4 Optimisation complémentaire facultative : chemin rapide « delta financier vide »

Dans [`MaterializePotBalance.prepareIncrementalOrFallback`](../../app/engine-materialize-pot/src/main/java/com/kartaguez/pocoma/engine/materialize/pot/MaterializePotBalance.java#L156-L203), lorsqu'un agrégat de référence `previous` à version `K` existe et que `source.financialDelta(potId, K, N, ...)` renvoie :
- `shareholderDeleted == false`
- `updatedOrAddedWeights.isEmpty()`
- `removedExpenses.isEmpty() && addedExpenses.isEmpty()` (i.e. `changedExpenseCount == 0`)

alors **par construction mathématique**, le contenu de `PotBalance(N)` est strictement identique à `PotBalance(K)` et le contenu de `PotBalanceAggregate(N)` est strictement identique à `PotBalanceAggregate(K)`.
- **Sans ce chemin rapide** : `MaterializePotBalance` ré-évalue `Eval` et ré-encode les bytes, et `JdbcPotBalanceStore` déduplique automatiquement via `ON CONFLICT (content_hash) DO NOTHING` (déjà 100 % des gains stockage et WAL).
- **Avec ce chemin rapide** (soumis à arbitrage ARB-3) : on évite en plus `applyDelta`, `Eval(A, w)` et le ré-encodage sur les versions purement métadonnées (gain CPU sur `UpdatePotDetails`, `UpdateShareholderDetails`, `AttachShareholderViaInvitation`, `UpdateExpenseDetails`).

---

## E. Mesures exploratoires, gains attendus et incertitudes

### E.1 Mesures effectives (issues des preuves archivées L7.4-A4 et L7-C2-B1.4)

Toutes les valeurs ci-dessous sont des **mesures réelles PostgreSQL 17** extraites de `docs/testing/evidence/L7-C2-B1-4/` et `docs/testing/evidence/L7-4A3-A4-CONSOLIDATED/` :

#### 1. Volumétrie actuelle (`heap` / `index` / `TOAST` / `total`)

| Profil & Table | Lignes | Heap | Index | TOAST | **Total** | Moyenne / version |
|---|---:|---:|---:|---:|---:|---:|
| **`ab-medium`** (`20` sh, `200` exp, 2 pots) — `read_pot_balance` | `404` | `827 392 B` | `40 960 B` | `32 768 B` | **`901 120 B`** (`880 Ko`) | `2 230 B/ver` *(inline)* |
| **`ab-medium`** — `read_pot_balance_aggregate` | `404` | `573 440 B` | `40 960 B` | `32 768 B` | **`647 168 B`** (`632 Ko`) | `1 602 B/ver` *(inline)* |
| **`ab-large`** (`100` sh, `2 000` exp, 2 pots) — `read_pot_balance` | `4 004` | `327 680 B` | `278 528 B` | **`22 216 704 B`** (`97,3 %`) | **`22 822 912 B`** (`21,77 Mo`) | **`5 700 B/ver`** |
| **`ab-large`** — `read_pot_balance_aggregate` | `4 004` | `352 256 B` | `278 528 B` | **`24 780 800 B`** (`97,5 %`) | **`25 411 584 B`** (`24,23 Mo`) | **`6 347 B/ver`** |
| **Delta 20 mutations mixtes `ab-large`** *(7/20 = 35 % non financières)* — `read_pot_balance` | `+20` | `+0 B` | `+0 B` | `+106 496 B` | **`+106 496 B`** | `+5 325 B/ver` |
| **Delta 20 mutations mixtes `ab-large`** — `read_pot_balance_aggregate` | `+20` | `+8 192 B` | `+0 B` | `+131 072 B` | **`+139 264 B`** | `+6 963 B/ver` |

#### 2. Croissance de `read_pot_balance_aggregate` avec le nombre de buckets `G` (`S = 100`, `E = 2 000`, mesuré)

| Régime (source) | Buckets `G` | Entrées | JSONB texte (`octet_length`) | Disque (`pg_column_size`) |
|---|---:|---:|---:|---:|
| `weight-update-large` / `out-of-order-nm-large` | `1` | `100` | `11 674 B` (`11,4 Ko`) | **`5 538 B`** (`5,4 Ko`) |
| `multibucket-realistic` (`L7-C2-B1-4`) | `60..62` | `1 144` | `21 496 B` (`21,0 Ko`) | **`10 146 B`** (`9,9 Ko`) |
| `near-d5-30` (`L7-4A3-A4-CONSOLIDATED`) | `240` | `6 401` | `55 301 B` (`54,0 Ko`) | **`21 319 B`** (`20,8 Ko`) |
| `exceed-d5` (`G ≤ 256`) | `256` | `6 817` | `56 739 B` (`55,4 Ko`) | **`21 914 B`** (`21,4 Ko`) |

#### 3. Coûts mesurés d'écriture, de lecture historique et de WAL (L7.4-A4 & L7-C2-B1.4)

- **Persistance `read_pot_balance`** (Micrometer `restore-large-1`, `n=64`) : **`1,33 ms` avg** (`max 6,09 ms`) ; `multibucket-realistic` (`G=61`) : **`1,64 ms` avg** ; `near-d5-30` (`G=240`) : **`3,37 ms` avg**.
- **Persistance `read_pot_balance_aggregate`** : **`1,46 ms` avg** (`G=1`), **`2,21 ms` avg** (`G=61`), **`5,74 ms` avg** (`G=240`).
- **Lecture HTTP `GET /pots/{id}/versions/{v}/balance`** (`ab-medium` / `ab-large`, warm `n=30`) : **`3,66 ms` p50** (`MEDIUM`), **`3,96 ms` p50** (`LARGE`, dont `0,215 ms` pour `PotVersion` R3a).
- **WAL cluster 5 runtimes (`ab-large`, 20 mutations mixtes)** : `3 730 888 B` total (`186,5 Ko/ver` incluant toutes les tables WRITE/READ/slots). Sur ce total, `read_pot_balance` + `read_pot_balance_aggregate` pèsent `+245,8 Ko` de pages TOAST/heap allouées (`~12,3 Ko/ver` de données brutes, soit `~5×` le volume propre de `PotVersion` Radix-16 `+49,2 Ko`).

### E.2 Estimations analytiques (explicitement distinguées des mesures)

*Les chiffres suivants sont des estimations analytiques à vérifier lors de la qualification A/B B2.3 :*

1. **Taille d'un contenu binaire canonique `bytea` (`A-BIN`)** :
   - **`PotBalance`** : `4 B + S × (16 B UUID + ~2 B longueurs + ~6–12 B BigInteger num/den)` ≈ **`24–30 B / shareholder`** brut (vs `~90–115 B / shareholder` JSONB texte) :
     - `S = 20` (`MEDIUM`) : **`~520–600 B`** (inline, ~`3,5×` plus petit que `2 230 B` JSONB).
     - `S = 100` (`LARGE`) : **`~2,5–3,0 Ko`** brut / **`~1,8–2,2 Ko` compressé** (proche du seuil TOAST `2 Ko`, **`~2,6×–3,0×` plus petit** que `5,7 Ko` JSONB TOAST).
     - `S = 500` (`XLARGE`, estimé) : **`~12–15 Ko`** brut / **`~9–11 Ko` compressé** (vs **`~28–30 Ko`** estimés en JSONB TOAST).
   - **`PotBalanceAggregate`** :
     - `S = 100, G = 1` : `100 × (16B UUID + 1B del + ~4B weight + ~8B fixedNet)` + `1 bucket (~420 B)` ≈ **`~3,3 Ko`** brut / **`~2,1–2,5 Ko` compressé** (vs `5,5 Ko` JSONB → **`~2,2×–2,6×` plus petit**).
     - `S = 100, G = 61` (`1 144` entrées) : `~3,0 Ko` (`s`) + `~6,0 Ko` (`b`) ≈ **`~9,0 Ko`** brut / **`~4,5–5,5 Ko` compressé** (vs `10,1 Ko` JSONB → **`~1,9×–2,2×` plus petit**).
     - `S = 100, G = 240` (`6 401` entrées) : `~3,0 Ko` (`s`) + `~27 Ko` (`b`) ≈ **`~30 Ko`** brut / **`~11–13 Ko` compressé** (vs `21,3 Ko` JSONB → **`~1,7×–1,9×` plus petit**).
2. **Gains cumulés estimés (Stockage & WAL propres `PotBalance*`)** :
   - **Charge pure dépenses (Situation D à 100 %, ex. `ab-large` 4 004 v)** :
     - **A-JSONB** : `~0 %` (`+1–2 %` de pointeurs).
     - **A-BIN** : **`~2,2×–2,8×` de réduction** (`48,2 Mo` → **`~18–22 Mo`**) et **`~-55 % à -65 %` de WAL propre**.
   - **Charge mixte réaliste (35 % Situation A + 5 % Situation C + 60 % Situation D, ex. `ab-large` 20 mixtes)** :
     - **A-JSONB** : **`~-35 %`** (`1,5×`).
     - **A-BIN** : **`~-70 % à -75 %`** (**`~3,3×–4,0×` de réduction**).
   - **Rafale non financière (Situation A à 100 % : invitations, renommages, éditions de libellés)** :
     - **A-BIN** et **A-JSONB** : **`~-97 %`** (`~160 B/ver` pour les 2 lignes pointeurs vs `12,3 Ko/ver` aujourd'hui → **`> 70×` de réduction**).
3. **Latence d'écriture & lecture** :
   - **Écriture (sans conflit)** : suppression de la relecture systématique `load()`/`loadAggregate()` + suppression de la sérialisation/désérialisation Jackson → gain estimé **`-0,4 à -1,0 ms`** sur `persist(BALANCE)` + `persist(AGGREGATE)`.
   - **Écriture (avec contenu identique)** : `ON CONFLICT DO NOTHING` évite toute écriture TOAST et ne fait qu'un `SELECT` PK sur `content_hash` + `Arrays.equals`.
   - **Lecture historique** : `+1` jointure PK en mémoire (`~0,05 ms`) largement compensée par la suppression du parsing Jackson + `new BigInteger(String)` (`~0,3–0,8 ms` gagnés sur `S = 100`).
4. **Incertitudes à lever en qualification B2.3** :
   - Ratio de compression réel `pglz`/`lz4` de PostgreSQL 17 sur le flux binaire `bytea` (UUID aléatoires peu compressibles vs entiers/dénominateurs répétitifs très compressibles).
   - Comportement exact au voisinage du seuil TOAST (`2 000 B`) pour `S = 50..100`.
   - Impact exact sur le WAL cluster global 5 runtimes (en séparant le WAL propre `PotBalance*` du WAL L2/slots).

---

## F. Invariants de sûreté et matrice de qualification

### F.1 Garanties des 12 invariants sous Option A-BIN

| # | Invariant | Garantie sous Option A-BIN |
|---|---|---|
| **1** | **Identité logique `(pot_id, version)`** | Préservée intégralement comme PK de `read_pot_balance` et `read_pot_balance_aggregate`. Aucun changement de contrat `PotBalanceStore` ni HTTP. |
| **2** | **Contenus physiques immuables** | Triggers `BEFORE UPDATE OR DELETE` + `BEFORE TRUNCATE` (`read_subobject_immutable()`) en **`ENABLE ALWAYS TRIGGER`** sur les 4 tables. |
| **3** | **Références historiques stables** | FK `read_pot_balance.content_hash -> read_pot_balance_content(content_hash)` et `read_pot_balance_aggregate.content_hash -> read_pot_balance_aggregate_content(content_hash)`. Zéro GC, zéro suppression. |
| **4** | **Lecture historique directe** | 1 seule requête SQL avec `JOIN` PK (`read_pot_balance JOIN read_pot_balance_content USING (content_hash)`). **0 chaîne de deltas**. |
| **5** | **Reconstruction déterministe** | Contenu et `content_hash` sont des fonctions pures des valeurs métier canoniques : deux reconstructions complètes depuis WRITE produisent **bit-à-bit** les mêmes tables et empreintes, quel que soit l'ordre concurrent des partitions. |
| **6** | **Exactitude financière** | `Fraction` irréductible (`BigInteger`), bijection prouvée `Fraction ↔ bytes`, vérification exacte `Arrays.equals` en cas de conflit de hash, zéro approximation. |
| **7** | **Idempotence** | `INSERT ... ON CONFLICT DO NOTHING RETURNING ...` sur le contenu puis sur la version logique, avec vérification exacte en cas de conflit. Rejouer la même version réussit ; tenter de publier une valeur divergente échoue (`IllegalStateException`). |
| **8** | **Concurrence** | Transactions `READ_COMMITTED` ; insertion dans un ordre déterministe fixe (`balance_content` → `balance` → `aggregate_content` → `aggregate`). Les verrous spéculatifs `ON CONFLICT` sur PK unique ne créent aucun cycle de deadlock. |
| **9** | **Fencing** | `requireCompletionReady()` appelle `requireStored` et `requireAggregateStored` dans la même transaction `READ_COMMITTED` avant `SlotCompletion.complete()` ; tout échec de contenu ou de pointeur annule la publication. |
| **10** | **Publication atomique** | Dans `MaterializePotBalance.finalizeIn`, les insertions `balance_content`, `balance`, `aggregate_content`, `aggregate` et `completion.complete()` s'exécutent dans une unique transaction `READ_COMMITTED`. |
| **11** | **Isolation entre projections** | Aucune FK vers `read_pot_version` ; `read_pot_balance_aggregate` référence uniquement `read_pot_balance(pot_id, pot_version)` ; `ReadPot` continue d'évaluer la disponibilité indépendante (`NotReady` si balance absente). |
| **12** | **Intégrité PostgreSQL & privilèges minimaux** | `REVOKE ALL` sur `PUBLIC` et `pocoma_read` ; `GRANT SELECT, INSERT` uniquement à `pocoma_read` ; `GRANT SELECT` à `pocoma_read_http` uniquement sur `read_pot_balance` + `read_pot_balance_content` (zéro accès aux agrégats) ; assertions `aclexplode` en migration + `BootstrapGuard.READ_UPDATES`. |

### F.2 Matrice de qualification

| Axe de preuve | Tests / Outils cibles |
|---|---|
| Bijection binaire, `Fraction` géants (`10^40`), zéros, ordre UUID, corruption/troncature, collisions | Nouveaux tests unitaires `PotBalanceBinaryCodecTest`, `PotBalanceAggregateBinaryCodecTest` (`infra-postgres-read` ou `domain-projection`) |
| Mutualisation indépendante Situations A, C, D, réutilisation multi-versions et hors ordre `N−m` | Extension de `PotBalanceReadStorePostgresTest`, `PotBalanceIncrementalPostgresTest`, `PotVersionAndBalanceJointIncrementalPostgresTest` |
| Collision SHA-256 simulée / contenu divergent sur même `content_hash` | Test PostgreSQL 17 dédié dans `PotBalanceReadStorePostgresTest` (rejet `IllegalStateException`, rollback atomique) |
| Immuabilité `ENABLE ALWAYS`, FK, privilèges minimaux `pocoma_read` / `pocoma_read_http`, garde-fou migration non vide | Nouveau `PotBalanceSharedSchemaPostgresTest` + `BootstrapGuard` + `ReadWorkerResourcesPostgresTest` |
| Reconstruction complète READ depuis WRITE & déterminisme double rebuild | `ReadReconstructionPostgresTest` + `v3-rebuild-read.py` (double rebuild sur snapshots `medium` et `large`) |
| Contenus inaccessibles (F4 lecture seule) | Nouveau script `scripts/v3/diagnose-pot-balance-unreachable.sql` (`BEGIN TRANSACTION READ ONLY`, attendu : `0` contenu inaccessible) |
| Qualification A/B vs baseline C2-B1 (`e25cd84b`) | Campagnes `ab-medium`, `ab-large`, `restore-large-1`, `multibucket-realistic`, `weight-update-large`, `out-of-order-nm-large` |

---

## G. Analyse d'impact et plan d'implémentation proposé

### G.1 Inventaire d'impact précis

> [!NOTE]
> **Périmètre strictement intouché** : `PotVersion`, les composants Radix-16 (`domain-projection/radix/*`, `JdbcPotVersionStore`, `MaterializePotVersion`, `V3__pot_version_radix16.sql`), `read_subobject_version`, `infra-postgres-write`, `domain-pot`, `supra-web-read` et tous les contrats HTTP publics ne subissent **aucune modification**.

| Couche | Fichiers / Modules impactés | Nature du changement |
|---|---|---|
| **Migration READ** | `app/infra-postgres-read/src/main/resources/db/read/migration/V4__pot_balance_shared_storage.sql` *(nouveau)* | Création `read_pot_balance_content`, `read_pot_balance_aggregate_content`, recréation `read_pot_balance` et `read_pot_balance_aggregate` (garde-fou tables vides), triggers `ENABLE ALWAYS`, `REVOKE`/`GRANT` + assertions ACL. |
| **Store READ** | `app/infra-postgres-read/.../JdbcPotBalanceStore.java`, nouveaux codecs `PotBalanceBinaryCodec.java` & `PotBalanceAggregateBinaryCodec.java` (retrait de `PotBalanceAggregateJson.java` en B2.2 ou B2.1) | Sérialisation binaire canonique + SHA-256, `INSERT ... ON CONFLICT DO NOTHING RETURNING`, vérification exacte uniquement sur conflit, requêtes `JOIN` PK. |
| **Port & Engine** | `PotBalanceStore.java` *(inchangé sauf si ARB-3 retenu)* ; `MaterializePotBalance.java` *(inchangé sauf si ARB-3 retenu)* ; `ReadPot.java` *(strictement inchangé)* | Si ARB-3 retenu : ajout optionnel d'un `contentHash` ou d'une méthode de réutilisation directe sur delta financier vide (sinon 0 changement). |
| **Bootstrap & Readiness** | `BootstrapGuard.java` (`READ_UPDATES`), `ReadinessOracle.java` (`checkRelationalIntegrity`) | Ajout de `read_pot_balance_content` et `read_pot_balance_aggregate_content` dans `READ_UPDATES` ; contrôle d'intégrité des FK `content_hash` dans `ReadinessOracle`. |
| **Architecture tests** | `L7ReactorArchitectureTest.java` | Ajout de `V4__pot_balance_shared_storage.sql` dans la liste statique des migrations actives `infra-postgres-read`. |
| **Tests à adapter** | `PotBalanceReadStorePostgresTest.java`, `ReadParticipantsFixture.java` (l. 51 & l. 148 : corruption ciblant `read_pot_balance_content`), `ReadParticipantsIntegrationPostgresTest.java` (l. 289), `ReadReconstructionPostgresTest.java` (l. 445), `PotVersionAndBalanceJointIncrementalPostgresTest.java` (l. 684), `ReadRuntimeI8Fixture.java` (l. 614), `ReadMonolithI8Fixture.java` (l. 613) | Mise à jour des listes de tables READ pour nettoyage/corruption et ajout des tests de mutualisation A/C/D et collision. |
| **Scripts opérationnels** | `scripts/v3/grant-l7-http-read.sql` (+ `'read_pot_balance_content'`), `scripts/operations/v3-rebuild-read.py` (`CANONICAL_MIGRATION_TABLES`, `V4`, `_evaluate_integrity`), `scripts/operations/setup-v3-database.py` (`V4` + garde-fou base V3 non vide), `scripts/v3/diagnose-pot-balance-unreachable.sql` *(nouveau, F4 lecture seule)* | Alignement schéma V4, privilèges HTTP READ et diagnostic lecture seule. |
| **Scripts de performance** | `scripts/performance/run-l7-4-a3-a4-multibucket.py` (l. 300), `scripts/performance/run-l7-4a4-targeted.py` (l. 73), `scripts/performance/run-l7-4a-baseline.py`, `scripts/performance/run-l7-c2-b1-qualification.py` (ou nouveau `run-l7-c2-b2-qualification.py`) | Adaptation des requêtes de mesure de taille (`JOIN read_pot_balance_aggregate_content`) et ajout de `read_pot_balance_content` / `read_pot_balance_aggregate_content` dans les relevés de volumétrie. |
| **Topologie & workers** | Aucun changement de topologie ni de partitionnement | Réduction attendue de la charge I/O PostgreSQL (TOAST/WAL) et CPU `business-read-worker` (suppression Jackson sur `PotBalance*`). |

### G.2 Plan d'implémentation en 3 étapes qualifiables

#### Étape B2.1 — Codecs binaires canoniques, schéma `V4`, `JdbcPotBalanceStore`, `BootstrapGuard` et `ReadinessOracle`

- **Objectif** : Mettre en œuvre le stockage mutualisé Option A-BIN dans la couche READ avec toutes les garanties SQL, d'immuabilité, de collision et de privilèges.
- **Fichiers et modules concernés** : `infra-postgres-read`, `infra-postgres-core` (`BootstrapGuard`), `compose-polling-nonburnable-materialize-postgres` (`ReadinessOracle`), tests associés dans `infra-postgres-read`, `runtime-worker-materialize`, `runtime-monolith`, et `architecture-tests` (`L7ReactorArchitectureTest`).
- **Modifications attendues** :
  - Ajout de `PotBalanceBinaryCodec`, `PotBalanceAggregateBinaryCodec` (et retrait de `PotBalanceAggregateJson`).
  - Ajout de `V4__pot_balance_shared_storage.sql`.
  - Refonte interne de `JdbcPotBalanceStore` (insertion contenu `ON CONFLICT DO NOTHING RETURNING` + vérification exacte sur conflit, insertion pointeur version, lecture par `JOIN` PK).
  - Mise à jour de `BootstrapGuard.READ_UPDATES`, `ReadinessOracle.checkRelationalIntegrity`, `L7ReactorArchitectureTest` et fixtures de tests (`ReadParticipantsFixture`, etc.).
- **Invariants vérifiés** : Invariants 1 à 12 (§F.1), bijection binaire exacte, détection de collision SHA-256, mutualisation indépendante Situations A/C/D, zéro régression `PotVersion` Radix-16.
- **Tests** : `PotBalanceBinaryCodecTest`, `PotBalanceAggregateBinaryCodecTest`, `PotBalanceSharedSchemaPostgresTest`, `PotBalanceReadStorePostgresTest`, `PotBalanceIncrementalPostgresTest`, `PotVersionAndBalanceJointIncrementalPostgresTest`, `ReadParticipantsIntegrationPostgresTest`, `ReadReconstructionPostgresTest`, `ReadinessOracleTest`.
- **Verification scope (`Reactor_Verification_Policy.md` §29)** :
  - Expected production impact : `infra-postgres-read`, `infra-postgres-core`, `compose-polling-nonburnable-materialize-postgres` (et `engine-materialize-pot` + `port-projection` uniquement si ARB-3 retenu).
  - Primary slice : `PROJECTION` (`./mvnw -pl runtime-worker-materialize -am test`)
  - Secondary slices : `WEB` (`./mvnw -pl runtime-web-read -am test` — vérification `ReadPot` + privilèges `pocoma_read_http`)
  - Forbidden production impact : `infra-postgres-write`, `domain-pot`, `domain-identity`, `runtime-worker-process`, `supra-web-read`, `domain-projection/radix/*`, `JdbcPotVersionStore`, `MaterializePotVersion`
  - Required local proof : `./mvnw -pl runtime-worker-materialize,runtime-web-read -am test`
  - Global architecture gate : **REQUIRED** (`./mvnw -pl architecture-tests -am test` — ajout de `V4__pot_balance_shared_storage.sql` dans l'inventaire statique)
  - Full reactor : **NOT REQUIRED** à cette étape (différé à la clôture B2.3)
  - Database verification : `CURRENT` (`V1 → V2 → V3 → V4` PostgreSQL 17 Testcontainers)
  - Escalation conditions : toute défaillance sur `runtime-monolith` ou croisement inattendu hors `PROJECTION`/`WEB`.
- **Critères de clôture** : 100 % PASS sur `PROJECTION` + `WEB` + `architecture-tests`, 0 modification des composants C2-B1.

#### Étape B2.2 — Outillage opérationnel, reconstruction READ (`v3-rebuild-read.py`), privilèges HTTP et diagnostic F4

- **Objectif** : Aligner l'ensemble des scripts d'administration, de reconstruction READ, de privilèges HTTP READ, de diagnostic lecture seule et de benchmark sur le schéma `V4`.
- **Fichiers et modules concernés** : `scripts/v3/grant-l7-http-read.sql`, `scripts/v3/diagnose-pot-balance-unreachable.sql` *(nouveau)*, `scripts/operations/v3-rebuild-read.py`, `scripts/operations/setup-v3-database.py`, scripts `scripts/performance/*` concernés.
- **Modifications attendues** :
  - Ajout de `read_pot_balance_content` dans `grant-l7-http-read.sql` (strictement sans `read_pot_balance_aggregate*`).
  - Mise à jour de `v3-rebuild-read.py` et `setup-v3-database.py` (DDL `V1+V2+V3+V4`, `CANONICAL_MIGRATION_TABLES`, `_evaluate_integrity`, refus explicite d'appliquer `V4` sur une base V3 non vide avec renvoi vers `v3-rebuild-read.py`).
  - Création de `diagnose-pot-balance-unreachable.sql` (`BEGIN TRANSACTION READ ONLY`).
  - Adaptation des requêtes de métriques dans les scripts de performance.
- **Invariants vérifiés** : Reconstruction READ complète depuis WRITE, déterminisme bit-à-bit sur double rebuild, isolation WRITE lecture seule, `0` contenu inaccessible F4, privilèges minimaux `pocoma_read_http`.
- **Tests** : Tests unitaires Python (`scripts/performance/test_*.py`, `scripts/testing/test_verification.py`) + exécution réelle `setup-v3-database.py` et double `v3-rebuild-read.py` sur snapshot WRITE `medium` + exécution de `diagnose-pot-balance-unreachable.sql`.
- **Verification scope (`Reactor_Verification_Policy.md` §29)** :
  - Expected production impact : `NONE` (scripts opérationnels et de qualification uniquement)
  - Primary slice : `NONE` (vérification Python + Docker 5 runtimes)
  - Global architecture gate : `NOT REQUIRED`
  - Full reactor : `NOT REQUIRED`
  - Database verification : `CURRENT` (bootstrap + reconstruction réelle Docker PostgreSQL 17)
- **Critères de clôture** : Double rebuild déterministe `READY`, `0` contenu inaccessible F4, tests Python 100 % PASS.

#### Étape B2.3 — Qualification A/B consolidée (vs baseline C2-B1 `e25cd84b`) et Gate Réacteur Complet

- **Objectif** : Prouver par mesure A/B iso-machine et iso-snapshots les gains de stockage, WAL et latence, la stricte équivalence métier bit-à-bit, et clore le lot L7-C2-B2 sur le réacteur complet.
- **Protocole A/B** :
  - **Baseline A** : commit `e25cd84b` (`L7-C2-B1.4` qualifié, compilé depuis un worktree détaché, images Docker épinglées identiques).
  - **Candidat B** : `feat/v3-l7` après B2.1 + B2.2.
  - **Campagnes** :
    1. `ab-medium` (`20/200`, `404` v + `20` mutations mixtes dont `35 %` non financières) : équivalence HTTP champ à champ, volumétrie, WAL, latences lecture/écriture, F4.
    2. `ab-large` (`100/2 000`, `4 004` v + `20` mutations mixtes) : idem + preuve réduction volume `read_pot_balance*` et WAL propre.
    3. `ab-non-financial-burst` *(ciblée Situation A/C)* : `50` mutations non financières (`UpdatePotDetails`, `UpdateShareholderDetails`, `UpdateExpenseDetails`, `amount=0`) + `10` Situation C (`DeleteShareholder`, poids sans effet) → preuve **`0` nouveau contenu balance**, **`~-97 %` volume/WAL** sur Situation A.
    4. `restore-large-1`, `multibucket-realistic` (`G=60..62`), `weight-update-large`, `out-of-order-nm-large` : non-régression complète A3 + A4 + C2-B1.
- **Verification scope (`Reactor_Verification_Policy.md` §29 — Clôture de lot majeur §19)** :
  - Primary + Secondary slices : `PROJECTION` + `WEB` + `runtime-monolith`
  - Global architecture gate : **REQUIRED** (`./mvnw -pl architecture-tests -am test`)
  - Full reactor : **REQUIRED** (`./mvnw --batch-mode --no-transfer-progress test` — clôture de lot majeur C2-B2)
  - Database verification : `CURRENT` + double reconstruction READ depuis WRITE (`medium` et `large`)
- **Critères de clôture** :
  - `0` divergence HTTP A/B et `0` divergence oracle `Fraction`.
  - Réduction stockage `read_pot_balance*` ≥ `2×` sur `ab-large` (Situation D pure) et ≥ `3×` sur charge mixte 35 % non financière ; ≥ `95 %` de réduction sur rafale Situation A.
  - Zéro régression sur les latences d'écriture `MaterializePotBalance` / `MaterializePotVersion` et de lecture `GET .../balance` / `GET /pots/{id}`.
  - `0` contenu inaccessible F4 ; `100 %` PASS sur le Full Reactor (42 modules enfants + `architecture-tests`).
  - Rapport final `docs/v3/L7_C2_B2_PotBalance_Qualification_Report.md` + preuves archivées dans `docs/testing/evidence/L7-C2-B2/`.

---

## H. Arbitrages résiduels (validés et figés dans le plan d'implémentation)

Les **5 arbitrages** ci-dessous ont été **validés** et intégrés comme décisions définitives dans [`L7_C2_B2_PotBalance_Implementation_Plan.md`](L7_C2_B2_PotBalance_Implementation_Plan.md) (§1) :

1. **ARB-1 — Encodage du `payload` dans les tables de contenus : `bytea` binaire canonique (Recommandé) vs `jsonb`**
   - *Pour `bytea` (recommandé)* : divise par `2×–3×` le stockage et le WAL **même en Situation D** (`48,2 Mo` → `~18–22 Mo` sur `ab-large`), élimine le parsing Jackson + `BigInteger(String)`, et rend la vérification anti-collision O(N) par `Arrays.equals` sur une bijection prouvée (cohérent avec `read_pot_version_node.payload` en C2-B1).
   - *Pour `jsonb`* : lisible directement dans `psql`, mais `0 %` de gain en Situation D (`ab-large` reste à `~48,5 Mo`).
2. **ARB-2 — Granularité de l'agrégat : contenu monolithique Option A (Recommandé pour B2) vs scission `shareholders + buckets` Option C1**
   - *Pour Option A (recommandé)* : 2 tables de contenus, 1 jointure PK, zéro complexité additionnelle, et déjà `~2×` plus compact grâce à `bytea`.
   - *Pour Option C1* : ajoute une 3e table de contenus (`read_pot_balance_aggregate_buckets`) pour réutiliser `buckets` lors d'`UpdateShareholderWeights` en multi-buckets (`G >> 1`). À n'envisager que si les changements de poids fréquents sur pots multi-buckets sont identifiés comme profil cible prioritaire.
3. **ARB-3 — Chemin rapide « delta financier vide » dans `MaterializePotBalance` : Activé (Recommandé) vs Déduplication strictement dans le store**
   - *Activé (recommandé)* : évite `applyDelta`, `Eval` et ré-encodage sur les versions purement métadonnées (Situation A) en réutilisant directement les contenus de `previous` (nécessite une méthode légère ou un retour de `contentHash` sur `PotBalanceStore`, sans changer le contrat public).
   - *Store uniquement* : `PotBalanceStore` et `MaterializePotBalance` restent strictement inchangés (zéro impact hors `infra-postgres-read`, `BootstrapGuard`, `ReadinessOracle`), au prix d'une évaluation RAM (`~0,15–1,5 ms`) et d'un hachage sur les versions non financières.
4. **ARB-4 — Stratégie de migration `V4__pot_balance_shared_storage.sql` : Garde-fou tables vides + rebuild READ (Recommandé, aligné sur `V3`) vs Migration en place**
   - *Garde-fou tables vides (recommandé)* : cohérent avec C2-B1 (`V3__pot_version_radix16.sql`), zéro coexistence de schémas, zéro code legacy dual, reconstruction garantie propre via `v3-rebuild-read.py`.
5. **ARB-5 — Politique de GC des contenus immuables : Aucun GC + diagnostic F4 lecture seule (Recommandé, aligné sur C2-B1)**
   - Préserve `ENABLE ALWAYS TRIGGER` d'immuabilité totale ; un script `diagnose-pot-balance-unreachable.sql` surveille l'absence de contenus orphelins (`0` attendu par atomicité transactionnelle).

---

## I. Risques et limites

1. **Dominance de la Situation D sur les pots purement transactionnels** : dans un pot où 95 %+ des versions sont des `CreateExpense` touchant plusieurs shareholders, la mutualisation entre versions seule apporte peu ; l'essentiel du gain en Situation D provient de l'encodage binaire `bytea` (ARB-1).
2. **Absence de réduction structurelle sur `S` très élevé (`S = 500+`)** : chaque version distincte en Situation D stocke les `S` rational balances (`~10–15 Ko` compressés à `S = 500`). Si des pots à plusieurs milliers de shareholders devenaient un cas d'usage cible, une réflexion dédiée (hors périmètre B2) serait nécessaire.
3. **Croissance des dénominateurs rationnels** : `Fraction` conserve une exactitude arbitraire ; des combinaisons répétées de poids premiers distincts peuvent allonger la représentation binaire des `BigInteger` (identique à l'existant, mais toujours plus compacte en `bytea` qu'en base 10).
4. **Seuil TOAST PostgreSQL (`~2 000 B`)** : pour `S ≈ 80..100`, le payload binaire peut osciller autour du seuil TOAST ; l'effet exact sur `heap` vs `TOAST` et sur le WAL devra être mesuré en B2.3.
5. **Absence volontaire de GC (ARB-5)** : en cas de rollback applicatif d'une transaction `READ_COMMITTED`, PostgreSQL annule automatiquement l'insertion du contenu (`0` orphelin) ; seul un défaut grave hors transaction pourrait créer un contenu non référencé, détectable par le diagnostic F4.
6. **Compatibilité des scripts existants** : les scripts de performance et fixtures de tests accédant directement à `read_pot_balance.payload` ou `read_pot_balance_aggregate.payload` (recensés en §G.1) devront tous être mis à jour lors de B2.1/B2.2.

---

## J. Évolutions correctives approuvées après l'échec B2.4 initial

À la suite de l'échec quantitatif initial de B2.4 sur P1, P2, P4 (Situation D) et P8 ([`L7_C2_B2_4_Performance_Corrective_Analysis.md`](L7_C2_B2_4_Performance_Corrective_Analysis.md)), la conception physique et applicative V4 intègre les décisions correctives suivantes tout en conservant le schéma cible `V4__pot_balance_shared_storage.sql` (sans double format permanent) :

1. **ARB-1b — Mutualisation physique des ensembles de shareholders (`read_pot_shareholder_set`)** :
   - **Table immuable globale** : `read_pot_shareholder_set (content_hash bytea PK, schema_version int = 1, shareholders_count int >= 0, payload bytea)`, protégée par `ENABLE ALWAYS TRIGGER` (`read_subobject_immutable`, `no_truncate`), avec droits `SELECT` pour `pocoma_read_http` (`grant-l7-http-read.sql`).
   - **Identité et séparation de domaine** : `PotShareholderSetBinaryCodec` encode `[schema_version=1][shareholders_count][uuid_msb_0, uuid_lsb_0, ...]` dans l'ordre canonique `SubobjectKey.compareUuid` et hache en SHA-256 avec préfixe **`pocoma-shset-v1\0`**.
   - **Référencement et suppression des UUID dupliqués** :
     - `read_pot_balance_content` et `read_pot_balance_aggregate_content` portent chacune `shareholder_set_hash bytea NOT NULL REFERENCES read_pot_shareholder_set(content_hash)` (cohérence `shareholders_count` vérifiée par `ReadinessOracle`).
     - Les payloads binaires de `PotBalanceBinaryCodec` (`pocoma-bal-v1\0`) et `PotBalanceAggregateBinaryCodec` (`pocoma-bagg-v1\0`) **intègrent les 32 octets `shareholderSetHash` dans leur calcul SHA-256** et **omettent les UUID 16 octets par shareholder** (puisque ceux-ci sont référencés à l'indice positionnel via `read_pot_shareholder_set`). L'ensemble des 5 tables vérifie les FKs, l'immutabilité, l'idempotence et la bijection exacte.
     - Élimine ~`32 octets/version` d'UUID bruts incompressibles par paire (balance + agrégat) sur chaque version financière (`100–500` shareholders), levant le plafond TOAST/pglz identifié en B2.4 initial.

2. **ARB-3b — Suppression intégrale du chemin rapide `EMPTY_DELTA_REUSE`** :
   - La comparaison 3-way initiale ayant établi que **100 % des gains stockage/WAL** proviennent de la déduplication physique SHA-256 côté store (tandis que `EMPTY_DELTA_REUSE` n'apportait qu'~`16 µs/cmd` au prix d'une seconde requête SQL `lookupReference` en cas de miss et d'une complexité de garde dans `MaterializePotBalance`), le chemin rapide `EMPTY_DELTA_REUSE`, le paramètre `emptyDeltaReuseEnabled`, `Path.REUSE`, `Reason.EMPTY_DELTA_REUSE`, `AggregateRef` et les méthodes associées de `PotBalanceStore` / `JdbcPotBalanceStore` sont **intégralement supprimés**.
   - `MaterializePotBalance` retrouve un chemin unique `readLatestAggregateBefore` vers le calcul incrémental A4 (avec fallback intégral conservé) suivi de la déduplication physique V4.

3. **Optimisations SQL de persistance PostgreSQL (`JdbcPotBalanceStore`)** :
   - **`insertOrVerifyVersionWithResult`** : résolution/insertion partagée unique du `PotShareholderSet`, puis **insertion CTE atomique groupée** de `read_pot_balance_content`, `read_pot_balance_aggregate_content`, `read_pot_balance` et `read_pot_balance_aggregate` en un seul aller-retour JDBC lorsqu'un agrégat est présent.
   - **Vérification exacte préservée sur conflit** : chaque `ON CONFLICT` conserve une comparaison octet à octet (`Arrays.equals`) entre payload entrant et payload stocké (aucune confiance *hash-only* autorisée), avec rollback complet en cas de divergence.
   - **`requireHashesStored`** : remplace la relecture et le décodage complets TOAST de `requireCompletionReady()` lors de la complétion par une requête légère vérifiant les pointeurs `(pot_id, pot_version)` et l'existence des empreintes SHA-256 stockées.

---

## Verdict final

**`L7_C2_B2_PLAN_APPROVED`** *(audit initial ARB-1..ARB-5 puis décisions correctives ARB-1b, ARB-3b et optimisation SQL intégrées)*

L'audit complet du code, du schéma SQL, des privilèges, des dépendances runtime et des preuves L7.4-A4 / L7-C2-B1 a conduit à l'approbation d'**Option A-BIN** (tables de contenus immuables adressés par SHA-256 en binaire canonique `bytea`, mutualisation indépendante des balances et des agrégats, sans modification de `PotVersion` ni des composants Radix-16) et du plan exécutable [`docs/v3/L7_C2_B2_PotBalance_Implementation_Plan.md`](L7_C2_B2_PotBalance_Implementation_Plan.md) (B2.1 à B2.4).
