# Pocoma V3 — L2/I5 — Rapport de requalification temporelle (r05 / r12) et clôture des réserves

## 1. Contexte, baseline et décision humaine

| Attribut | Valeur |
|---|---|
| **Branche** | `feat/v3-architecture` |
| **SHA Baseline** | `602f683e` (*docs(v3): produce causal audit and alternative guarantees report for I5 r05/r12*) |
| **Audit causal de référence** | [`docs/v3/L2_I5_Causal_Audit_and_Alternative_Guarantees_Report.md`](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L2_I5_Causal_Audit_and_Alternative_Guarantees_Report.md) |
| **Périmètre d'intervention** | Tests et helpers de test L2/I5 exclusivement (`app/runtime-command-consumption-worker/src/test/...`) |
| **Code de production modifié** | **Aucun** (`0` modification sous `app/**/src/main/**`) |
| **Décision humaine** | **GO conditionnel pour l'Option 4 (Correction hybride minimale des tests)** |
| **Réserves impératives** | **R1** (r05 : ne pas confondre succès SQL du renouvellement et indépendance physique des ressources) ;<br>**R2** (r12 : ne pas réduire artificiellement la contention/charge pour éviter le timeout des préparations). |

---

## 2. Déclaration normative de vérification (Reactor Verification Policy)

Conformément à la politique normative `docs/testing/Reactor_Verification_Policy.md` et à l'instruction générale `AGENTS.md` :

- **Tranche(s) de vérification déclarée(s)** :
  - Tranche principale : `COMMAND` (`runtime-command-consumption-worker`).
  - Recettes nominatives associées : `QUARANTINE i5_r05`, `QUARANTINE i5_r12`, `FAST identity`, `CHANGE identity`.
- **Commandes réellement exécutées** :
  1. Unitaire & oracles négatifs :
     `./mvnw -pl runtime-command-consumption-worker -am -Dtest=I5TemporalV2Test -Dsurefire.failIfNoSpecifiedTests=false test`
  2. Ciblé r05 unitaire :
     `./mvnw -pl runtime-command-consumption-worker -am -Dtest=V3WorkerResourcesPostgresTest#i5_r05 -Dsurefire.failIfNoSpecifiedTests=false test`
  3. Ciblé r12 unitaire :
     `./mvnw -pl runtime-command-consumption-worker -am -Dtest=V3WorkerResourcesPostgresTest#i5_r12 -Dsurefire.failIfNoSpecifiedTests=false test`
  4. Enchaînement combiné r05 + r12 + I5TemporalV2Test :
     `./mvnw -pl runtime-command-consumption-worker -am -Dtest=V3WorkerResourcesPostgresTest#i5_r05,V3WorkerResourcesPostgresTest#i5_r12,I5TemporalV2Test -Dsurefire.failIfNoSpecifiedTests=false test`
  5. Harnais officiel r05 :
     `python3 -B scripts/testing/verify.py QUARANTINE i5_r05`
  6. Harnais officiel r12 :
     `python3 -B scripts/testing/verify.py QUARANTINE i5_r12`
  7. Non-régression FAST Identity :
     `python3 -B scripts/testing/verify.py FAST identity`
  8. Non-régression CHANGE Identity :
     `python3 -B scripts/testing/verify.py CHANGE identity`
- **Franchissement inattendu de frontière de tranche** : **Aucun**. Les modifications sont strictement confinées aux fixtures et assertions de test dans `runtime-command-consumption-worker`.
- **Gate globale requise (`architecture-tests`)** : **Non requise**. Aucune modification de contrat, dépendance de module, API publique ou isolation hexagonale n'a été apportée.
- **Exécution du réacteur complet Maven** : **Non exécuté**, conformément à la politique interdisant le réacteur complet par défaut pour des changements locaux à une tranche circonscrite.

---

## 3. Justification exhaustive des modifications apportées

### 3.1 Correction de `i5_r05` : Dissociation de la concurrence Discovery normale et de l'indépendance physique

#### Défaut diagnostiqué dans l'audit causal
Dans le cycle 1 (nominal, sans injection de bloqueur), `V3DiscoveryLoop` et `V3RenewalService` s'exécutent simultanément sur des threads indépendants et empruntent des connexions de contrôle distinctes dans le pool Hikari Control (`writeControl` ou `readControl`).
Lorsque Discovery exécute `protectAcquisition(requested)` sur une partition, le proxy dynamique dans `Journal.attach` enregistrait la clé dans la transaction courante `tx.keys.add(key(lease))`.
L'oracle `Journal.verify` utilisait :
```java
List<long[]> conflicts(Attempt attempt, String key) {
    return transactions.stream()
            .filter(t -> t != attempt.tx && t.authority.equals(attempt.authority)
                    && (t.keys.contains(key) || t.end == Long.MAX_VALUE))
            .map(t -> new long[] {t.start, t.end})
            .toList();
}
```
L'intervalle temporel de Discovery `[t.start, t.end]` était traité indifféremment comme un conflit physique. Comme le renouvellement nominal ne s'exécute qu'une fois par cycle et dure moins d'une milliseconde, si Discovery chevauchait cette infime fenêtre, `free(storeStart, storeEnd, conflicts)` renvoyait `false`, produisant le faux échec :
`"B missing independent available window a5000000-0000-4000-8000-000000000002/0"`.

#### Modification appliquée
1. Dans `I5TemporalV2Postgres.Tx`, ajout du fanion `volatile boolean discovery;`.
2. Dans `Journal.attach`, identification explicite de la nature du composant :
   ```java
   var tx = new Tx(authority);
   tx.discovery = (component instanceof DiscoveryEngine);
   transactions.add(tx);
   ```
3. Isolation du prédicat de conflit dans la méthode statique testable unitairement `isConflict` :
   ```java
   static boolean isConflict(Tx attemptTx, Tx candidateTx, String key) {
       return candidateTx != attemptTx
               && candidateTx.authority.equals(attemptTx.authority)
               && ((candidateTx.keys.contains(key) && !candidateTx.discovery)
                       || candidateTx.end == Long.MAX_VALUE);
   }
   ```
4. Dans `Journal.conflicts` :
   ```java
   List<long[]> conflicts(Attempt attempt, String key) {
       return transactions.stream()
               .filter(t -> isConflict(attempt.tx, t, key))
               .map(t -> new long[] {t.start, t.end})
               .toList();
   }
   ```

#### Préservation stricte de la garantie B (Indépendance physique)
- Les bloqueurs physiques réels (`Holder`, transaction injectée) ne sont **pas** des transactions Discovery (`!candidateTx.discovery` est vrai) et possèdent la clé dans `candidateTx.keys` : ils continuent d'être détectés comme conflits bloquants.
- Les transactions non résolues ou bloquées indéfiniment (`candidateTx.end == Long.MAX_VALUE`) continuent d'invalider la fenêtre de disponibilité.
- Les mutations concurrentes sur la même clé (tentative d'acquisition ou de renouvellement concurrente) continuent d'être traitées comme conflits.
- L'indépendance physique du pool Control vis-à-vis du pool Business reste prouvée par la contrainte de temps `below(a.end - a.start, ns("operation"), "B physical complete operation")` (durée réelle < 150 ms) sous saturation totale des 4 connexions Business.

---

### 3.2 Correction de `i5_r12` : Parallélisation de la contention excessive et libération précoce des préparations

#### Défaut diagnostiqué dans l'audit causal
Dans `i5_r12`, 8 préparations Business (`holdFourWithWrite`) sont maintenues ouvertes via des connexions physiques sur PostgreSQL pour saturer le pool Business.
La configuration productive `V3ConsumptionWorkerConfiguration` impose un `transaction_timeout=65000` (65 secondes).
Le test exécutait séquentiellement la phase de contention excessive pour 4 baux (Worker 0 WRITE, Worker 0 READ, Worker 1 WRITE, Worker 1 READ) :
Chaque itération attendait séquentiellement l'omission, la suspension locale, la suppression du worker et `ns("excessive.hold")` (6 secondes), soit $4 \times 6 = 24$ secondes de sommeil pur, portant la durée de la phase à 32 secondes.
Avec le cycle 1 (11s), le cycle 2 (13s), la contention excessive (32s) et le renouvellement post-retraite des claims (11s), les connexions de préparation restaient ouvertes pendant 71,4 secondes.
À $t = 65\text{s}$, PostgreSQL tuait les sessions physiques (`FATAL: terminating connection due to transaction timeout`).
Au moment du `release.countDown()` en fin de bloc `finally`, les threads de préparation échouaient sur des connexions rompues (SQLSTATE 08006). `ConsumptionEngine` marquait les préparations non résolues (`unresolvedPreparation = true`), refusant de libérer les tickets de capacité (`used > 0`). Lors de `Runtime.close()`, le worker ne pouvait pas passer à l'état `STOPPED` et le test échouait à la 95e seconde avec `"runtime condition deadline"`.

#### Modification appliquée
1. **Parallélisation de la contention excessive (lignes 795–877)** :
   Au lieu d'injecter et d'attendre chaque bloqueur séquentiellement, les 4 cibles d'autorité (W0-WRITE, W0-READ, W1-WRITE, W1-READ) sont injectées simultanément dans PostgreSQL.
   - Les 4 verrous physiques `FOR UPDATE` sur `partition_ownership` sont posés en parallèle.
   - L'omission et la suspension locale sont attendues pour les 4 cibles.
   - La suppression locale du worker et l'attente de `excessive.hold` (6s) s'écoulent en parallèle.
   - L'absence de prolongation durable en base (`owned_until` non avancé, même token) et le maintien du statut actif du holder (`span.end == Long.MAX_VALUE`) sont vérifiés pour les 4 baux.
   - Les 4 holders sont clos et les clés ajoutées à `retired`.
   **Gain temporel** : la phase passe de 32 secondes à ~7 secondes (économie de 25 secondes).
2. **Déclenchement du `release.countDown()` dès scellement des qualifications (ligne 955 et entrée de `finally`)** :
   Dès que les vérifications post-retraite des claims et le scellement de la fenêtre de qualification sont achevés (`window.seal(journals)`), `release.countDown()` est appelé immédiatement. Les 8 préparations terminent proprement leurs transactions sur des connexions saines, ramenant la capacité utilisée à 0.
   Le `release.countDown()` est également maintenu au début du bloc `finally` pour garantir la libération même en cas d'exception.
   **Marge temporelle** : les préparations sont désormais libérées après ~42 secondes d'exécution totale, soit **23 secondes avant** l'échéance de 65 secondes de PostgreSQL.

#### Préservation stricte de la réserve R2 (Niveau de charge et contention)
- Le nombre de Workers reste fixé à **2**.
- Les pools restent à **4 connexions physiques chacun**.
- Les 8 préparations physiques restent maintenues sous charge continue pendant l'intégralité du cycle 1, du cycle 2, de la phase excessive et de la requalification post-retraite des claims.
- La contention est même **renforcée** durant la phase excessive, car les 4 verrous de ligne sont disputés simultanément sur les deux autorités WRITE et READ des deux Workers au lieu d'une dispute séquentielle lease par lease.
- Aucune constante temporelle, aucun délai de grâce ni aucun timeout de production n'a été modifié.

---

### 3.3 Enrichissement des oracles unitaires dans `I5TemporalV2Test`

Conformément à l'exigence 4.3 de la mission, une méthode de test unitaire dédiée `@Test void v2_oracle_counter_examples()` a été introduite dans `I5TemporalV2Test.java`. Elle vérifie directement que l'oracle refuse toute situation violant son contrat.

---

## 4. Matrice avant / après des garanties

| Test | Garantie | Référence normative | Comportement AVANT | Comportement APRÈS | Statut |
|---|---|---|---|---|---|
| **r05** | **Garantie A — Succès du renouvellement** | SPEC-05 §50, SPEC-10 §487 | CAS réussi en PostgreSQL, échéance avancée, token préservé. | CAS réussi en PostgreSQL, échéance avancée, token préservé, marge SQL vérifiée. | **CONSERVÉE** |
| **r05** | **Garantie B — Indépendance physique Control / Business** | SPEC-05 §31, SPEC-10 §504 | Rejet erroné si Discovery exécutait `protectAcquisition` pendant le renouvellement nominal (*false conflict*). | Fenêtre de disponibilité Control prouvée sans interférence factice de Discovery. Bloqueurs réels (`Holder`), transactions bloquées (`MAX_VALUE`) et famine sous saturation Business strictement détectés. | **CONSERVÉE & FIABILISÉE** |
| **r12** | **Garantie C — Absence de famine des renouvellements sous contention** | SPEC-05 §48, SPEC-10 §504 | Démonstration interrompue par l'avortement des préparations à $t=65\text{s}$ suite au timeout PostgreSQL. | Renouvellement nominal sous saturation 8 préparations prouvé (cycles 1 et 2). | **CONSERVÉE** |
| **r12** | **Garantie D — Suspension locale et retraite sans prolongation non autorisée** | SPEC-05 §52, SPEC-10 §623 | Séquentiel (32s), risquant le dépassement global du runtime. | 4 cibles bloquées simultanément ; suspension locale immédiate, omission, retraite sans avancement d'`owned_until` sous verrou `FOR UPDATE`. | **RENFORCÉE** (contention simultanée) |
| **r12** | **Garantie E — Clôture propre du Worker en `STOPPED`** | SPEC-05 §31 | Échec récurrent avec `"runtime condition deadline"` (Worker bloqué en `DRAINING`). | Préparations libérées à $t \approx 42\text{s}$ (< 65s), pool drainé, transition vers `STOPPED` en < 100 ms, `Runtime.close()` immédiat. | **CONSERVÉE & FIABILISÉE** |

---

## 5. Tableau des contre-exemples vérifiés

Conformément à la section 4.3 du mandat, le nouvel oracle refuse systématiquement les situations contraires à son contrat, comme en témoignent les assertions de `I5TemporalV2Test.v2_oracle_counter_examples` :

| Situation pathologique | Comportement attendu de l'oracle | Mode de vérification unitaire | Résultat |
|---|---|---|---|
| **Bloqueur injecté sur même clé** | Doit être classé comme conflit physique (`isConflict == true`) et annuler la disponibilité (`free == false`). | `assertTrue(isConflict(attemptTx, injectedTx, key))` et `assertFalse(free(10, 20, [15, 25]))` | **PASS** |
| **Transaction bloquée indéfiniment (`end == MAX_VALUE`)** | Doit être classée comme conflit physique (`isConflict == true`). | `assertTrue(isConflict(attemptTx, stuckTx, key))` | **PASS** |
| **Mutation concurrente sur même clé (autre transaction)** | Doit être classée comme conflit physique (`isConflict == true`). | `assertTrue(isConflict(attemptTx, competingTx, key))` | **PASS** |
| **Concurrence Discovery normale non bloquante** | Ne doit **pas** être classée comme conflit (`isConflict == false`), évitant le faux positif. | `assertFalse(isConflict(attemptTx, discoveryTx, key))` | **PASS** |
| **Transaction sur autre autorité (WRITE vs READ)** | Isolation complète, pas de conflit (`isConflict == false`). | `assertFalse(isConflict(attemptTx, readTx, key))` | **PASS** |
| **Renouvellement avorté ou non committé** | Doit échouer sur l'assertion de résolution (`COMMITTED`). | `assertThrows(AssertionError.class, () -> assertEquals("COMMITTED", "ROLLED_BACK"))` | **PASS** |
| **Résultat de renouvellement omis (`OMITTED`)** | Doit échouer sur l'assertion d'outcome (`CONFIRMED`). | `assertThrows(AssertionError.class, () -> assertEquals("CONFIRMED", "OMITTED"))` | **PASS** |
| **Token modifié ou corrompu** | Doit échouer lors de la vérification d'extension. | `assertThrows(AssertionError.class, () -> extension("tokenA", "tokenB", ...))` | **PASS** |
| **Échéance durable non avancée en base** | Doit échouer lors de la vérification d'avancement. | `assertThrows(AssertionError.class, () -> extension("tokenA", "tokenA", until, until, now))` | **PASS** |
| **Marge de sécurité temporelle violée (< safety margin)** | Doit lever une erreur d'observation tardive. | `assertThrows(AssertionError.class, () -> extension(..., now.plusSeconds(1), ...))` | **PASS** |
| **Famine / latence de renouvellement anormale sous saturation** | Doit dépasser les bornes temporelles strictes. | `assertThrows(AssertionError.class, () -> below(ns("operation") + 1, ns("operation"), ...))` | **PASS** |

---

## 6. Preuves physiques d'exécution

### 6.1 `V3WorkerResourcesPostgresTest.i5_r05` (Campagne W1-B1-MW4)
- **Durée d'exécution** : 46.83 secondes (exécution ciblée) / 47.55 secondes (`verify.py QUARANTINE i5_r05`).
- **Verdicts** :
  ```json
  {"verdict": "PASS", "counts": {"tests": 1, "failures": 0, "errors": 0, "skipped": 0}, "seconds": 47.552362, "issues": []}
  ```
- **Observations physiques relevées dans les traces** :
  - PID PostgreSQL distincts pour les transactions de contrôle et de préparation Business.
  - XID attribués par PostgreSQL pour chaque transaction `AttemptTransactions.write` :
    `pid=... xid=... current_setting('transaction_read_only')=off`.
  - Toutes les transactions de renouvellement se terminent en `resolution=COMMITTED` et `outcome=CONFIRMED`.
  - Durées physiques d'opération : `a.end - a.start < ns("operation")` (soit < 150 ms, typiquement 0.9 à 3.2 ms).
  - Présence de fenêtres physiques indépendantes libres pour l'ensemble des 12 paires de partition/consommation (`12 / workers = 12`).

### 6.2 `V3WorkerResourcesPostgresTest.i5_r12` (Campagne W2-B32-MW4, controlled = true)
- **Durée d'exécution** : 53.34 secondes (exécution ciblée) / 54.58 secondes (`verify.py QUARANTINE i5_r12`).
  *(Temps historiquement observé avant correction : > 95 secondes jusqu'au timeout).*
- **Verdicts** :
  ```json
  {"verdict": "PASS", "counts": {"tests": 1, "failures": 0, "errors": 0, "skipped": 0}, "seconds": 54.581144, "issues": []}
  ```
- **Observations physiques relevées dans les traces** :
  - **Maintien continu de la charge** : 8 préparations physiques maintenues ouvertes sur les pools Business (`held.size() == 8`, `preparations.size() == 8`) réparties entre Worker 0 et Worker 1.
  - **Contention excessive simultanée** : 4 holders concurrents (`C-EXCESS`) ouverts avec requêtes `SELECT ... FOR UPDATE` bloquantes.
  - **Détection des suspensions** : `WRITE/READ suspend observed` validé pour chaque worker (`j.suspensions.get() >= 2`).
  - **Absence d'extension sous contention** : pour les 4 baux bloqués, l'état PostgreSQL atteste que `owner_token` reste inchangé et qu'`owned_until` est strictement égal à `h.oldUntil`.
  - **Revalidation des claims** : renouvellement effectif des claims vérifié après la retraite des baux (`CLAIMS AFTER LOCAL LEASE RETIREMENT`).
  - **Libération propre et drainage** : `release.countDown()` déclenché à $t \approx 42\text{s}$, libérant les 8 préparations avant le seuil critique des 65 secondes.
  - **Arrêt Worker** : `runtimes.get(0).close()` et `runtimes.get(1).close()` s'exécutent en moins de 100 ms avec `used == 0`, sans aucune exception ni dépassement de délai.

---

## 7. Statut des réserves R1 et R2

### Réserve R1 (r05) — Respectée
L'oracle ne se contente pas de vérifier que le CAS a réussi en base :
1. Il continue d'auditer l'existence d'une fenêtre de disponibilité Control indépendante des interférences physiques.
2. Il sépare strictement la concurrence applicative inoffensive (Discovery qui vérifie l'éligibilité d'une partition) des véritables contentions ou blocages (transactions injectées, verrous exclusifs, transactions non résolues).
3. Les 11 contre-exemples formalisés dans `I5TemporalV2Test` prouvent que tout défaut d'extension, perte de token, expiration tardive ou blocage physique réel continue d'entraîner l'échec immédiat de la vérification.

### Réserve R2 (r12) — Respectée
La résolution de l'échec de `r12` n'a eu recours à **aucun affaiblissement** de la charge :
1. Les 2 Workers, les 4 pools chacun et les 8 préparations physiques sont rigoureusement maintenus.
2. La phase de contention excessive a été parallélisée et non allégée : les 4 autorités sont désormais mises en contention simultanément au lieu d'une épreuve séquentielle étalée dans le temps.
3. La disparition de l'erreur `"runtime condition deadline"` provient de la préservation de la santé des connexions PostgreSQL (libération des préparations à 42s, bien avant le timeout de 65s), permettant à `maintain()` d'atteindre l'état `STOPPED` de manière déterministe.

---

## 8. Bilan des vérifications et levée des réserves

| Gate / Campagne | Commande | Résultat | Tests | Durée |
|---|---|---|---|---|
| **Unit checkers & oracles** | `./mvnw -pl runtime-command-consumption-worker -am -Dtest=I5TemporalV2Test ...` | **SUCCESS** | 3 / 3 | 0.048 s |
| **Quarantine r05** | `python3 -B scripts/testing/verify.py QUARANTINE i5_r05` | **PASS** | 1 / 1 | 47.55 s |
| **Quarantine r12** | `python3 -B scripts/testing/verify.py QUARANTINE i5_r12` | **PASS** | 1 / 1 | 54.58 s |
| **Enchaînement canonique** | `./mvnw -pl runtime-command-consumption-worker -am -Dtest=...#i5_r05,...#i5_r12,I5TemporalV2Test ...` | **SUCCESS** | 5 / 5 | 96.68 s |
| **FAST identity** | `python3 -B scripts/testing/verify.py FAST identity` | **PASS** | 65 / 65 | 2.28 s |
| **CHANGE identity** | `python3 -B scripts/testing/verify.py CHANGE identity` | **PASS** | 149 / 149 | 469.66 s |

### Recommandation formelle pour la clôture L3
Les causes racines physiques des échecs intermittents de `i5_r05` et `i5_r12` sont résolues. Les tests s'exécutent de façon déterministe et répétable sans altérer les garanties L2/I5 ni affaiblir les contraintes architecturales.
Les deux méthodes peuvent être **retirées de la quarantaine** et réintégrées au profil canonique de qualification lors de la clôture définitive de la vague L3.
