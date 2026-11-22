# Rapport L7.4-A4.1 — Agrégats `PotBalance` et équivalence exacte

- **Date** : 19 novembre 2026
- **Branche courante** : `feat/l7-4-a3-potversion-incremental-nm` (parent HEAD `814fcb0b`)
- **Document de conception** : [`PotBalance_Incremental_Materialization_Design_Plan.md`](PotBalance_Incremental_Materialization_Design_Plan.md)
- **Verdict final** : **`A4_1_QUALIFIED`**

---

## 1. Modèle implémenté et justification

Le modèle d'agrégats pur, immuable et indépendant des poids courants a été implémenté dans le module `domain-projection` (aucune dépendance à PostgreSQL, Spring, Jackson ou aux mécanismes de consommation) :

- [`PotBalanceAggregate.java`](../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/PotBalanceAggregate.java)
- [`PotBalanceAggregateCalculator.java`](../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/PotBalanceAggregateCalculator.java)

### Structure canonique retenue (décisions D1 et D3)

1. **Dictionnaire canonique des shareholders (`List<ShareholderEntry> shareholders`) — D1** :
   - Chaque entrée porte `(shareholderId, defaultWeight, deleted, fixedNet)`.
   - La liste est strictement triée par `SubobjectKey::compareUuid` (ordre canonique identique à `PotBalance` et `PotBalanceCalculator`).
   - La position `0 .. S-1` dans cette liste constitue l'**indice entier canonique** stable du shareholder dans cet agrégat (`indexOf(UUID)` en $O(\log S)$).
   - Le vecteur `fixedNet` ($F_i \in \mathbb{Q}$) regroupe toutes les contributions indépendantes des poids courants :
     - la totalité des crédits payeur ($+A_e$) ;
     - la totalité des débits des dépenses 100 % poids spécifiques ($B_e^{\text{def}} = \emptyset$), qui ne créent aucun bucket et restent insensibles aux changements de poids par défaut.

2. **Buckets de répartition (`Map<BucketKey, BucketValue> buckets`)** :
   - **Clé canonique** $\kappa = (B_{\text{def}}, W_{\text{spec}})$ :
     - `defaultParticipantIndices` ($B_{\text{def}}$) : liste non vide, strictement croissante d'indices entiers dans `[0, S-1]` représentant les bénéficiaires au poids par défaut courant ;
     - `specificWeightTotal` ($W_{\text{spec}} \in \mathbb{Q}_{\ge 0}$) : somme exacte des poids spécifiques de la dépense (`0/1` pour une dépense 100 % poids par défaut, $> 0$ pour une dépense mixte).
   - **Valeur additive** `(totalAmount, specWeightedAmounts)` :
     - `totalAmount` ($T_\kappa = \sum_{e \in \kappa} A_e > 0$) ;
     - `specWeightedAmounts` ($Q_\kappa(j) = \sum_{e \in \kappa,\, (j, \omega_{e,j}) \in B_e^{\text{spec}}} A_e \cdot \omega_{e,j} > 0$, vide si $W_{\text{spec}} = 0/1$).
   - **Propriété clé** : toutes les dépenses partageant $\kappa = (B_{\text{def}}, W_{\text{spec}})$ ont identiquement le même dénominateur $W_\kappa(w) = W_{\text{spec}} + \sum_{i \in B_{\text{def}}} w_i$ pour tout vecteur de poids $w \in \mathbb{Q}_{>0}^S$. Aucune hypothèse simplificatrice n'est faite sur l'usage exclusif des poids par défaut.

3. **Accesseurs préparant A4.2 (D5)** :
   - `bucketCount()` ($G$) et `bucketEntryCount()` ($\sum_\kappa (|B_{\text{def}}| + |Q_\kappa|)$) pour les futurs garde-fous de cardinalité en A4.2.
   - `defaultWeights()` pour l'évaluation directe avec les poids stockés dans l'agrégat.

---

## 2. Formalisation des opérations

Toutes les opérations de `PotBalanceAggregateCalculator` sont pures, sans état partagé, et reposent sur un accumulateur interne unique (`Accumulator.applyExpense(expense, ±1)`) afin d'éviter toute duplication de la logique métier :

| Opération | Signature | Rôle exact |
|---|---|---|
| **Construction complète** | `Optional<PotBalanceAggregate> fromState(PotFinancialState state)` | Retourne `Optional.empty()` si `state.header().value().deleted()` (terminal `D`), sinon construit $\mathcal{A}(N)$ en une passe sur les dépenses actives (`!deleted`). |
| **Ajout de dépense** | `PotBalanceAggregate addExpense(PotBalanceAggregate agg, ExpenseFinancials exp)` | Ajoute la contribution de `exp` (`sign = +1`). No-op (après validation des références) si `amount == 0/1`. |
| **Retrait de dépense** | `PotBalanceAggregate removeExpense(PotBalanceAggregate agg, ExpenseFinancials exp)` | Soustrait la contribution de `exp` (`sign = -1`). Purge les entrées nulles de $Q_\kappa$ et supprime le bucket lorsque $T_\kappa = 0/1$. Rejette tout retrait incohérent ($T_\kappa < 0$, $Q_\kappa < 0$, bucket absent). |
| **Composition multi-étapes** | `PotBalanceAggregate applyDelta(PotBalanceAggregate base, long targetVersion, Map<UUID, Weight> updatedOrAddedWeights, Collection<ExpenseFinancials> removed, Collection<ExpenseFinancials> added)` | Met à jour les poids, insère les nouveaux shareholders en **ré-indexant automatiquement** les indices entiers D1 des buckets existants, puis retire `removed` et ajoute `added`. |
| **Évaluation des balances (D3)** | `PotBalance calculate(PotBalanceAggregate agg, Map<UUID, Weight> currentWeights)` (et surcharge `calculate(agg)`) | Évalue l'ensemble des buckets contre `currentWeights` : $B_i = F_i - \sum_{i \in B_{\text{def}}} \frac{T_\kappa}{W_\kappa} w_i - \sum_{Q_\kappa(i) > 0} \frac{Q_\kappa(i)}{W_\kappa}$ et retourne un `PotBalance` immuable (vérifiant somme nulle). |

---

## 3. Invariants et canonicalisation

Les constructeurs compacts de `PotBalanceAggregate`, `ShareholderEntry`, `BucketKey` et `BucketValue` imposent systématiquement :

1. **Ordre canonique strict** :
   - `shareholders` strictement ordonnée par `SubobjectKey::compareUuid` (aucun doublon) ;
   - `defaultParticipantIndices` non vide, indices dans `[0, S-1]` strictement croissants ;
   - `buckets` triée canoniquement par `(defaultParticipantIndices lexicographique, specificWeightTotal)` ;
   - `specWeightedAmounts` triée par indice croissant dans `[0, S-1]`, valeurs strictement positives.
2. **Disjonction intra-bucket** : aucun indice ne peut apparaître simultanément dans $B_{\text{def}}$ et dans $\text{keys}(Q_\kappa)$.
3. **Cohérence pur vs mixte** : $W_{\text{spec}} = 0/1 \iff Q_\kappa = \emptyset$.
4. **Conservation par bucket** : $\sum_{j \in \text{keys}(Q_\kappa)} Q_\kappa(j) = T_\kappa \cdot W_{\text{spec}}$ dans $\mathbb{Q}$.
5. **Conservation globale** : $\sum_{i=0}^{S-1} F_i = \sum_{\kappa \in \mathcal{B}} T_\kappa$ dans $\mathbb{Q}$ (garantissant mathématiquement que $\sum_i B_i = 0/1$ pour tout vecteur de poids $w > 0$).
6. **Précision numérique** : exclusivement `Fraction(BigInteger, BigInteger)` canonique normalisé (zéro = `0/1`, dénominateur $> 0$, GCD réduit) ; aucun flottant ni arrondi.

---

## 4. Matrice de couverture des tests

Suite créée : [`PotBalanceAggregateEquivalenceTest.java`](../../app/domain-projection/src/test/java/com/kartaguez/pocoma/domain/projection/PotBalanceAggregateEquivalenceTest.java) (`9` méthodes de test, toutes vérifiées contre l'oracle `PotBalanceCalculator` sur numérateurs, dénominateurs, ordre canonique des clés et représentation JSON canonique `[{"id":"...","n":"...","d":"..."}]`).

| Exigence métier / technique | Test(s) couvrant le cas |
|---|---|
| Dépenses 100 % poids par défaut, sous-ensembles, plusieurs dépenses partageant un bucket, plusieurs buckets distincts | `pureDefaultWeightsSubsetsAndSharedBuckets` |
| Dépenses 100 % poids spécifiques (0 bucket, tout dans `fixedNet`, immunité aux changements de poids par défaut) | `pureSpecificWeightsCollapseIntoFixedNetAndIgnoreDefaultWeightChanges` |
| Dépenses mixtes (partage de bucket par `(B_def, W_spec)` même avec participants spécifiques distincts, buckets distincts, changement rétroactif de poids) | `mixedExpensesShareBucketWhenDefaultSetAndSpecificTotalMatch` |
| Payeur bénéficiaire et payeur non bénéficiaire | Tous les scénarios ci-dessus + suite différentielle |
| Ajout suivi de retrait (`add` + `remove` = identité exacte), annulation des contributions, purge des buckets vides, montant `0/1` | `addThenRemoveIsExactIdentityAndPrunesEmptyBuckets` |
| Modification montant, payeur, bénéficiaires, poids spécifiques, ajout de shareholders avec ré-indexation D1, changements successifs (`applyDelta` vs `fromState`) | `modificationsOfAmountPayerBeneficiariesSpecificWeightsAndMultiStepDeltas` |
| Ordres d'ajout différents (20 permutations aléatoires → agrégat identique) et fractions non triviales (`> 64 bits`, dénominateurs premiers, `Fraction.decimal`) | `permutationInvarianceAndLargeNonTrivialRationals` |
| Suppression terminale du pot (`D` → `Optional.empty()`), shareholders supprimés conservés à `0/1`, dépenses supprimées exclues | `deletedPotDeletedShareholdersAndDeletedExpenses` |
| Suite différentielle déterministe (`150` scénarios seeded : `fromState` vs `PotBalanceCalculator` + chaîne de deltas `applyDelta` avec changements de poids, ajouts de shareholders, suppressions et créations) | `deterministicDifferentialSuiteAcrossManyValidStatesAndDeltaChains` |
| Garde-fous d'invariants et canonicalisation (ordre UUID, conservation globale/bucket, chevauchement `B_def ∩ Q_κ`, retrait absent, poids manquant) | `invariantsAndCanonicalizationGuardsRejectInvalidAggregatesAndOperations` |

---

## 5. Résultats de vérification (`Reactor_Verification_Policy.md`)

- **Portée déclarée avant implémentation** :
  - Impact production attendu : `domain-projection` uniquement (2 nouvelles classes, 0 classe existante modifiée).
  - Slice primaire : `PROJECTION` (`./mvnw -pl runtime-worker-materialize -am test`).
  - Slices secondaires : `NONE`.
  - Traversée de slice inattendue : `NONE`.
  - Global architecture gate : `RUN` (`HexagonalArchitectureTest` + `ConsumptionEngineArchitectureTest`, requis par §6 du mandat).
  - Full reactor : `NOT REQUIRED` (non exécuté).
  - Vérification base de données : `NONE` (aucune migration SQL).

| Commande exécutée (depuis `app/`) | Résultat | Détail |
|---|---|---|
| `./mvnw -pl domain-pot,domain-projection -am test` | **`PASS`** | `domain-pot`: `46/46` (dont `PotBalanceCalculatorTest`: `6/6`) ; `domain-identity`: `20/20` ; `domain-projection`: `14/14` (dont `PotBalanceAggregateEquivalenceTest`: `9/9`). |
| `./mvnw -pl engine-materialize-pot -am clean test` | **`PASS`** | `9/9` modules compilés et testés (`engine-materialize-pot`: `5/5`). |
| `./mvnw -pl architecture-tests -am -Dtest=HexagonalArchitectureTest,ConsumptionEngineArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false clean test` | **`PASS`** | `43/43` modules compilés proprement ; `HexagonalArchitectureTest`: `15/15`, `ConsumptionEngineArchitectureTest`: `11/11`, `0` violation. |
| `./mvnw -pl runtime-worker-materialize -am test` | **`PASS`** | Slice canonique `PROJECTION` complète : `35/35` modules, `runtime-worker-materialize`: `111/111`, `0` échec, `0` erreur. |

---

## 6. Limites et réserves

1. **Périmètre volontairement pur (A4.1)** : aucune persistance PostgreSQL, aucun codec JSONB (`PotBalanceAggregateJson`), aucune intégration dans `MaterializePotBalance` ni aucun benchmark de latence ne sont inclus dans ce lot (réservés à A4.2 et suivants).
2. **Suppression logique d'un shareholder (`DeleteShareholder`)** : conformément à la décision **D4**, `applyDelta` ne gère pas la transition `deleted: false -> true` d'un shareholder existant — en A4.2, ce cas déclenchera un fallback intégral vers `fromState(PotFinancialState)`.
3. **Vérification de la sérialisation** : la comparaison canonique JSON en A4.1 reproduit le format contractuel `[{"id":"...","n":"...","d":"..."}]` de `JdbcPotBalanceStore.encode(PotBalance)` ; l'intégration directe avec `JdbcPotBalanceStore` et `PotBalanceAggregateJson` sera qualifiée sur PostgreSQL 17 en A4.2.

---

## 7. Préparation de A4.2

A4.1 fournit directement les briques consommées par A4.2 sans retouche de modèle :
- `PotBalanceAggregate` (incluant `indexOf`, `bucketCount()`, `bucketEntryCount()`, `defaultWeights()` et tous les invariants de conservation) est directement prête pour la sérialisation JSONB D1 (`PotBalanceAggregateJson`) et le stockage dans `read_pot_balance_aggregate` (D2 / D6).
- `PotBalanceAggregateCalculator.fromState`, `applyDelta` et `calculate` fournissent les chemins intégral et incrémental `N−m` prêts à être câblés dans `MaterializePotBalance` avec les seuils D5 (`64 / 64 / 256 / 10 000`) et le fallback D4.

---

## 8. Liste des fichiers ajoutés / modifiés

| Module / Dossier | Fichier | Action |
|---|---|---|
| `domain-projection` | [`PotBalanceAggregate.java`](../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/PotBalanceAggregate.java) | Créé |
| `domain-projection` | [`PotBalanceAggregateCalculator.java`](../../app/domain-projection/src/main/java/com/kartaguez/pocoma/domain/projection/PotBalanceAggregateCalculator.java) | Créé |
| `domain-projection` (test) | [`PotBalanceAggregateEquivalenceTest.java`](../../app/domain-projection/src/test/java/com/kartaguez/pocoma/domain/projection/PotBalanceAggregateEquivalenceTest.java) | Créé |
| `docs/v3` | [`L7_4_A4_1_PotBalance_Aggregate_Equivalence_Report.md`](L7_4_A4_1_PotBalance_Aggregate_Equivalence_Report.md) | Créé |

Aucune classe existante (`MaterializePotBalance`, `PotBalanceCalculator`, `PotBalance`, sources/stores JDBC, migrations SQL, routage, consommation) n'a été modifiée.

---

## 9. État Git final

- **Branche** : `feat/l7-4-a3-potversion-incremental-nm` (inchangée, aucun push, aucun merge, aucun tag).
- **Commit parent** : `814fcb0b` (`perf(materialize): implement opportunistic N-m incremental PotVersion materialization (L7.4-A3)`).
- **Commit local A4.1** : créé immédiatement après rédaction du présent rapport pour isoler les 4 fichiers du lot A4.1 (`PotBalanceAggregate.java`, `PotBalanceAggregateCalculator.java`, `PotBalanceAggregateEquivalenceTest.java`, `L7_4_A4_1_PotBalance_Aggregate_Equivalence_Report.md`).
- **Fichier hors commit A4.1 conservé intact dans l'arbre de travail** : `docs/v3/PotBalance_Incremental_Materialization_Design_Plan.md` (document de conception issu du mandat précédent).

---

## Verdict final

**`A4_1_QUALIFIED`** — Le modèle d'agrégats `PotBalanceAggregate` (dictionnaire canonique D1, vecteur `fixedNet`, buckets $\kappa = (B_{\text{def}}, W_{\text{spec}})$ avec $T_\kappa$ et $Q_\kappa$) et le calculateur pur `PotBalanceAggregateCalculator` démontrent une équivalence rationnelle stricte avec `PotBalanceCalculator` sur l'ensemble des cas métier et différentiels, respectent toutes les règles d'architecture hexagonale (`26/26`) et passent intégralement la slice canonique `PROJECTION` (`35/35` modules, `111/111` tests).
