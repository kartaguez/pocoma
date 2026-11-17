# Pocoma V3 — L2/I5 — Audit final des corrections r05/r12 et requalification canonique

**Date d'audit :** 1er novembre 2026  
**Branche :** `feat/v3-architecture`  
**Statut :** QUALIFICATION CANONIQUE VALIDÉE — AUDIT FINAL CONCLUANT — SORTIE DE QUARANTAINE JUSTIFIÉE  

---

## 1. Références exactes, SHA et contrôle de provenance

| Attribut | Valeur |
|---|---|
| **Branche** | `feat/v3-architecture` |
| **HEAD baseline antérieure** | `602f683e` (*docs(v3): deliver independent causal audit report for L2/I5 tests*) |
| **HEAD initial / testé** | `486ebdd0` (*test(v3): resolve L2/I5 r05/r12 temporal flaws and requalify guarantees*) |
| **HEAD final après audit & requalification** | Commit de qualification finale sur `feat/v3-architecture` |
| **Audit causal de référence** | [`docs/v3/L2_I5_Causal_Audit_and_Alternative_Guarantees_Report.md`](L2_I5_Causal_Audit_and_Alternative_Guarantees_Report.md) |
| **Rapport de correction intermédiaire** | [`docs/v3/L2_I5_Temporal_Requalification_Report.md`](L2_I5_Temporal_Requalification_Report.md) |
| **Politique normative** | [`docs/testing/Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md) |
| **Registre de campagnes** | [`scripts/testing/verification-campaigns.json`](../../scripts/testing/verification-campaigns.json) |
| **Code productif modifié** | **Aucun** (`0` fichier sous `app/**/src/main/**`) |

---

## 2. Déclaration normative de vérification (Reactor Verification Policy & AGENTS.md)

- **Tranche(s) de vérification déclarée(s) :**
  - Tranche principale : `COMMAND` (`runtime-command-consumption-worker`).
  - Recettes nominatives associées : `QUARANTINE i5_r05`, `QUARANTINE i5_r12`, `FAST identity`, `CHANGE identity`.
- **Commandes réellement exécutées :**
  1. `python3 -B scripts/testing/verify.py FAST identity` (65 tests, PASS, 2.32s)
  2. `python3 -B scripts/testing/verify.py QUARANTINE i5_r05` (1 test, PASS, 49.12s)
  3. `python3 -B scripts/testing/verify.py QUARANTINE i5_r12` (1 test, PASS, 54.97s)
  4. `./mvnw -pl runtime-command-consumption-worker -am -Dtest=V3WorkerResourcesPostgresTest#i5_r05,V3WorkerResourcesPostgresTest#i5_r12,I5TemporalV2Test -Dsurefire.failIfNoSpecifiedTests=false test` (5 tests, PASS, 108s / 01:48 min)
  5. `python3 -B scripts/testing/verify.py CHANGE identity` (149 tests, PASS, 476.02s)
  6. `./mvnw --batch-mode --no-transfer-progress -pl runtime-command-consumption-worker -am test` (Gate `COMMAND` canonique complète et non filtrée : 319 tests, 0 failure, 0 error, 1 skip historique, PASS, 51:26 min)
- **Franchissement inattendu de frontière de tranche :** **Aucun**. Les modifications et vérifications sont strictement confinées aux fixtures et tests sous `runtime-command-consumption-worker`.
- **Gate globale requise (`architecture-tests`) :** **Non requise**. Conformément aux §§17.2 et 34 de `Reactor_Verification_Policy.md`, aucune dépendance Maven, frontière hexagonale, règle de dépendance interdite ou isolation n'a été modifiée.
- **Exécution du réacteur complet Maven :** **Non exécuté**, conformément à la politique normative interdisant le full reactor par défaut pour les vérifications circonscrites (§§18, 19, 34).

---

## 3. Diffs exacts et modifications de code

Les modifications sont strictement limitées à deux fichiers de test :
- `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalV2Postgres.java`
- `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalV2Test.java`

### 3.1 Détaillé des modifications dans `I5TemporalV2Postgres.java`
1. **Constructeur explicite pour `Attempt` :** Permet la création directe d'instances `Attempt` dans les tests unitaires sans dépendre d'objets `V3RenewalService.Call` instanciés par réflexion.
2. **Constructeur isolé pour `Journal` :** Permet d'instancier un `Journal(transactions, index, workers)` sans conteneur PostgreSQL actif (`fixture = null`).
3. **Protection des appels de trace :** `if (fixture != null) fixture.trace.add(...)` aux lignes 491 et 572, rendant `Journal.verify(...)` exécutable en isolation complète sans Testcontainers.

### 3.2 Détaillé des modifications dans `I5TemporalV2Test.java`
1. **Suppression des tautologies JUnit :** Élimination des assertions purement démonstratives `assertEquals("COMMITTED", "ROLLED_BACK")` et `assertEquals("CONFIRMED", "OMITTED")`.
2. **Injection dans le véritable chemin d'évaluation de l'oracle (`Journal.verify`) :**
   - Échec sur transaction non committée (`tx.resolution = "ROLLED_BACK"`).
   - Échec sur résultat omis (`outcome = "OMITTED"`).
   - Échec sur absence de renouvellement lors d'une fenêtre disponible (`returned = List.of()`).
   - Échec sur dépassement du budget d'opération physique (`a.end - a.start > ns("operation")`).
   - Échec sur transaction non résolue (`tx.end == Long.MAX_VALUE`).
3. **Suppression des duplications :** Référencement explicite des vérifications `extension(...)` et `below(...)` existant déjà dans `v2_negative_checkers()`, avec assertions ciblées sur les messages d'erreur de conformité.

---

## 4. Audit final du prédicat `isConflict` et analyse physique SQL

### 4.1 Forme du prédicat audité
```java
static boolean isConflict(Tx attemptTx, Tx candidateTx, String key) {
    return candidateTx != attemptTx
            && candidateTx.authority.equals(attemptTx.authority)
            && ((candidateTx.keys.contains(key) && !candidateTx.discovery)
                    || candidateTx.end == Long.MAX_VALUE);
}
```

### 4.2 Analyse SQL physique des interactions Discovery et Renewal
1. **Opérations SQL dans `JdbcPartitionOwnershipStore` :**
   - `protectAcquisition(expected, requested)` : Exécute `SELECT ... FROM partition_ownership WHERE consumption_id=? AND partition_id=? FOR UPDATE SKIP LOCKED`.
   - `renew(expected, leases, duration)` : Pour chaque bail, exécute `SELECT ... FOR UPDATE SKIP LOCKED` puis `UPDATE partition_ownership SET owned_until=greatest(...) WHERE ... AND owner_token=? AND owned_until>? RETURNING owner_token, owned_until`.
2. **Nature des verrous PostgreSQL et comportement `SKIP LOCKED` :**
   - Tant `protectAcquisition` que `renew` utilisent `FOR UPDATE SKIP LOCKED`.
   - Aucun des deux threads n'attend un verrou de ligne : si la ligne est déjà verrouillée par une transaction concurrente, elle est omise immédiatement (`SKIP LOCKED`).
3. **Pools physiques étanches :**
   - `writeControl` et `readControl` disposent de $M_W + 6 = 10$ connexions physiques.
   - Les threads de contrôle actifs sont au nombre de 4 au maximum (1 thread Discovery, 3 threads Renewal).
   - Aucune famine de connexion physique n'est possible entre Discovery et Renewal.
4. **Distinction causale quadripartite :**
   - **(1) Chevauchement temporel inoffensif :** Les enveloppes de transaction Java $[t_{\text{start}}, t_{\text{end}}]$ de Discovery et Renewal se chevauchent, mais Renewal obtient la ligne, exécute le CAS PostgreSQL et committe avec succès (`CONFIRMED`, `COMMITTED`, $token$ inchangé, $\text{durée} < 1\,\text{ms}$). Ce cas représentait le faux échec de `r05`.
   - **(2) Concurrence sur une même clé sans blocage réel :** Discovery interroge la partition avant ou après l'opération de Renewal sans interférer avec le CAS.
   - **(3) Contention physique effective :** Si Discovery retenait la ligne pendant l'appel `renew`, le verrou `FOR UPDATE` ferait omettre la ligne par Renewal (`returned` vide pour ce bail). Dans ce cas, l'oracle `Journal.verify` teste :
     ```java
     if (available) assertTrue(returned, "B available physical window without same-token CAS");
     ```
     L'omission non attendue sous fenêtre disponible déclenche l'échec immédiat de la vérification ! Aucun masquage n'est possible.
   - **(4) Attente ou indisponibilité susceptible de compromettre un renewal :** Si une transaction Discovery se bloque indéfiniment, `candidateTx.end == Long.MAX_VALUE` est vrai, ce qui force `isConflict == true` et invalide la disponibilité.
5. **Verdict d'audit :**
   > **VALIDATED : Le prédicat `isConflict` conserve rigoureusement toutes les garanties normatives.**  
   > L'exclusion des transactions Discovery *terminées* (`!candidateTx.discovery`) élimine le faux conflit d'enveloppe temporelle sans masquer aucune contention physique réelle, car toute omission ou blocage réel reste immédiatement sanctionné par les oracles de conformité SQL et de complétude d'Attempt.

---

## 5. Inventaire et qualification des contre-exemples

| # | Propriété vérifiée | Catégorie | Mécanisme d'évaluation | Statut |
|---|---|---|---|:---:|
| **CE1** | Bloqueur injecté sur même clé (`Holder`) | Vrai prédicat & oracle | `isConflict == true` et `free(...) == false` | **VALIDÉ** |
| **CE2** | Transaction non résolue (`end == Long.MAX_VALUE`) | Vrai prédicat & oracle réel | `isConflict == true` et rejet par `Journal.verify` | **VALIDÉ** |
| **CE3** | Mutation concurrente sur même clé | Vrai prédicat | `isConflict == true` | **VALIDÉ** |
| **CE4** | Concurrence Discovery normale non bloquante | Vrai prédicat | `isConflict == false` (évite faux négatif) | **VALIDÉ** |
| **CE5** | Isolation d'autorité (WRITE vs READ) | Vrai prédicat | `isConflict == false` | **VALIDÉ** |
| **CE6** | Transaction de renouvellement non committée (`ROLLED_BACK`) | **Véritable oracle `Journal.verify`** | Injection dans `Attempt` $\rightarrow$ échec `assertEquals("COMMITTED", resolution)` | **VALIDÉ** *(Tautologie supprimée)* |
| **CE7** | Résultat de renouvellement `OMITTED` | **Véritable oracle `Journal.verify`** | Injection dans `Attempt` $\rightarrow$ échec `assertEquals("CONFIRMED", outcome)` | **VALIDÉ** *(Tautologie supprimée)* |
| **CE8** | Renewal absent lors d'une fenêtre disponible | **Véritable oracle `Journal.verify`** | `returned = List.of()` sous `available == true` $\rightarrow$ échec `"B available physical window without same-token CAS"` | **VALIDÉ** *(Nouveau)* |
| **CE9** | Dépassement du budget physique d'opération sous charge | **Véritable oracle `Journal.verify`** | `a.end - a.start > ns("operation")` $\rightarrow$ échec `"B physical complete operation"` | **VALIDÉ** *(Véritable chemin)* |
| **CE10** | Violation du contrat d'extension PostgreSQL ($token$, deadline, marge) | **Oracle de certification `extension`** | Rejet par `extension(...)` : token remplacé, deadline non avancée, marge temporelle violée | **VALIDÉ** |

---

## 6. Audit final de `i5_r12` : Charge physique, fenêtres temporelles et drainage

### 6.1 Charge physique
Les observations physiques indépendantes confirment :
- **2 Workers simultanés :** Deux instances de runtime actives en parallèle.
- **8 pools Hikari actifs :** 4 pools par Worker (`writeBusiness`, `writeControl`, `readBusiness`, `readControl`), soit 64 connexions réservées.
- **8 préparations Business maintenues sous charge continue :** 4 par Worker via `holdFourWithWrite`, immobilisant physiquement les pools Business durant toute la durée des cycles 1 et 2, de la phase excessive et des vérifications de claims.
- **4 contentions excessives simultanées :** Parallélisation stricte des 4 détenteurs externes (`Holder` posant `SELECT ... FOR UPDATE` sur Worker 0 WRITE, Worker 0 READ, Worker 1 WRITE, Worker 1 READ).
- **12 couples Consumption/partition couverts collectivement :** 6 couples par Worker sur les 3 Consumptions.
- **Absence de trou de contention :** Saturation Business continue durant toutes les fenêtres de qualification.

### 6.2 Qualification temporelle et respect des timeouts
- **Durée continue de rétention des préparations :** Mesurée à **~42 secondes** (libération via `release.countDown()` dès le scellement de la fenêtre de qualification à la ligne 956).
- **Timeout PostgreSQL productive :** `transaction_timeout = 65000` (65 secondes).
- **Marge temporelle effective :** **~23 secondes** de sécurité absolue avant l'échéance d'interruption unilatérale par PostgreSQL.
- **Marges SQL avant expiration :** Vérification continue de la marge de sécurité ($\ge 2\,\text{s}$) et de l'extension de 10 secondes avec le même token.

### 6.3 Drainage et sûreté
- **Résolution physique propre :** Les 8 préparations sont libérées sur des connexions saines et s'achèvent par un commit/rollback normal (`unresolvedPreparation == false`).
- **Capacité ramenée à zéro :** `WorkerCapacity.release()` est appelé sur tous les tickets, `used` passe à 0.
- **Passage en `STOPPED` immédiat :** `Runtime.close()` observe la transition vers `STOPPED` en moins de 100 ms, sans aucun dépassement de délai.
- **Préservation de la politique UNKNOWN $\rightarrow$ DRAINING :** La politique de sûreté conservative (refus de transitionner vers `STOPPED` si une connexion est tuée unilatéralement) demeure intégralement protégée et vérifiée par les tests dédiés `i5_w20` et `i5_w21` dans `V3WorkerPostgresTest`.

---

## 7. Matrice finale de conservation des garanties

| Garantie | Oracle avant correction | Oracle après correction | Contre-exemple réel | Preuve indépendante | Verdict |
|---|---|---|---|---|:---:|
| **G1 : Fencing & non-usurpation de token** | CAS SQL `owner_token=?` | CAS SQL `owner_token=?` inchangé | `extension("tokenA", "tokenB", ...)` $\rightarrow$ FAIL | Rejet immédiat en base si token corrompu | **EQUIVALENT** |
| **G2 : Avancement durable des échéances ($\ge 10\,\text{s}$, marge $\ge 2\,\text{s}$)** | Oracle SQL `cVector` | Oracle SQL `cVector` inchangé | `extension(..., until, until, ...)` $\rightarrow$ FAIL | Vérification directe dans PostgreSQL | **EQUIVALENT** |
| **G3 : Absence de famine des renewals sous saturation Business** | Enveloppe d'isolation globale `free(...)` (faux échec si Discovery) | Prédicat `isConflict` discriminé : bloqueurs réels et stuck Tx détectés | `journal.verify` avec `returned = List.of()` $\rightarrow$ FAIL | Exécution nominale sous 4/8 préparations Business bloquées | **STRONGER** *(Élimine faux négatif tout en renforçant l'oracle réel)* |
| **G4 : Suspension locale et retraite sous contention excessive** | Séquentiel (32 s d'attente cumulée) | Parallélisé (7 s, 4 cibles simultanées) | `target.journal.assertSuspended` | 4 verrous `FOR UPDATE` simultanés dans PostgreSQL | **STRONGER** *(Contention plus sévère et temps maîtrisé)* |
| **G5 : Arrêt propre qualifié (`STOPPED`) sans timeout de connexion** | Rétention 71 s > timeout 65 s $\rightarrow$ crash unilatéral PG | Rétention bornée à ~42 s (< 65 s) $\rightarrow$ connexions saines | `i5_w20` / `i5_w21` prouvent le refus sous `UNKNOWN` | Fermeture ordonnée en < 100 ms avec `used == 0` | **STRONGER** *(Rend la preuve réalisable et stable)* |
| **G6 : Maintien des claims sous baux retirés** | Oracle claims après retraite | Oracle claims après retraite inchangé | Contrôle des vecteurs claims dans PostgreSQL | Vérification directe dans PostgreSQL | **EQUIVALENT** |

---

## 8. Bilan des vérifications exécutées

| Type de vérification | Commande exécutée | Tests | Résultat | Durée |
|---|---|---|---|---|
| **Contre-exemples & unitaires** | `(cd app && ./mvnw -pl runtime-command-consumption-worker -am -Dtest=I5TemporalV2Test ... test)` | 3 / 3 | **PASS** | 1.52 s |
| **Recette QUARANTINE r05** | `python3 -B scripts/testing/verify.py QUARANTINE i5_r05` | 1 / 1 | **PASS** | 49.12 s |
| **Recette QUARANTINE r12** | `python3 -B scripts/testing/verify.py QUARANTINE i5_r12` | 1 / 1 | **PASS** | 54.97 s |
| **Campagne combinée r05 + r12 + I5TemporalV2Test** | `(cd app && ./mvnw -pl runtime-command-consumption-worker -am -Dtest=V3WorkerResourcesPostgresTest#i5_r05,V3WorkerResourcesPostgresTest#i5_r12,I5TemporalV2Test ... test)` | 5 / 5 | **PASS** | 108 s (01:48 min) |
| **Non-régression FAST Identity** | `python3 -B scripts/testing/verify.py FAST identity` | 65 / 65 | **PASS** | 2.32 s |
| **Non-régression CHANGE Identity** | `python3 -B scripts/testing/verify.py CHANGE identity` | 149 / 149 | **PASS** | 476.02 s |
| **Gate canonique COMMAND complète (unfiltered)** | `(cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-command-consumption-worker -am test)` | 319 / 319 (1 skip historique) | **BUILD SUCCESS** | 51:26 min |

---

## 9. Statut nominatif des réserves R1 et R2

- **Réserve R1 (`i5_r05` — Indépendance physique vs simple succès CAS) :**  
  **LEVÉE**. L'oracle continue d'exiger une fenêtre disponible exempte de conflit physique. Les bloqueurs injectés (`Holder`), les transactions non résolues (`MAX_VALUE`) et les omissions intempestives sont rigoureusement détectés et sanctionnés par `Journal.verify`.
- **Réserve R2 (`i5_r12` — Maintien du niveau de charge physique et contention) :**  
  **LEVÉE**. La charge physique est intégralement maintenue (2 Workers, 8 pools, 8 préparations saturant les pools Business). La phase excessive a été parallélisée (4 verrous concurrents simultanés) et non allégée, ramenant la rétention à ~42s dans le respect strict du `transaction_timeout` de 65s de PostgreSQL.

---

## 10. Décision motivée sur la quarantaine

1. **Requalification des méthodes :**  
   Les méthodes `V3WorkerResourcesPostgresTest#i5_r05` et `V3WorkerResourcesPostgresTest#i5_r12` ont démontré leur répétabilité, leur robustesse et leur validité causale intégrale, tant dans leurs recettes dédiées que dans la gate `COMMAND` complète non filtrée.
2. **Sortie de quarantaine :**  
   Les deux tests sont déclarés **qualifiés et sortis de quarantaine**.
3. **Maintien du registre opérationnel :**  
   Les entrées nominatives dans `scripts/testing/verification-campaigns.json` sont conservées comme outils de diagnostic ciblés disponibles, conformément au principe évitant toute modification inutile du registre opérationnel.

---

## 11. Réserves résiduelles et limites de certification

1. **Configuration cible 30 secondes (R-L2-01) :**  
   La qualification I5 certifie le fonctionnement sous l'enveloppe expérimentale de 90 secondes (`I5-CB-90-v2`). La configuration cible de production à 30 secondes demeure explicitement **NON CERTIFIÉE** (`production30s=NOT_CERTIFIED`).
2. **Drainage conservateur (R-L2-02) :**  
   Le comportement de sûreté UNKNOWN $\rightarrow$ DRAINING demeure la règle inviolable de l'architecture Pocoma V3.
3. **Périmètre de certification :**  
   La réussite de cette requalification ne vaut pas certification globale aveugle de SPEC-10 ou de l'étape L3 dans son ensemble : elle lève nominativement les réserves R1 et R2 sur le runtime de consommation L2/I5.
