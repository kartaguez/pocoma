# Protocole de qualification SPEC-09 locale (`SPEC09_LOCAL_READY`)

- **Date** : 17 novembre 2026
- **Référence Git** : `main` @ `0227dd0f` (post-fusion PR #7, post-`L7_LB_CLOSED`)
- **Sources normatives** : [`SPEC-09_Performance.md`](SPEC-09_Performance.md), [`L7_SPEC09_Compliance_Audit.md`](L7_SPEC09_Compliance_Audit.md), [`L7_I4_SPEC09_Contract_Matrix.csv`](L7_I4_SPEC09_Contract_Matrix.csv), [`scripts/performance/l7-spec09-campaigns.json`](../../scripts/performance/l7-spec09-campaigns.json)
- **Statut** : **Protocole reproductible prêt (`SPEC09_LOCAL_READY`)** — aucune campagne lourde exécutée ni aucune modification du moteur de projection engagée à ce stade.

---

## 1. Distinction stricte : caractérisation locale L7 vs conformité contractuelle SPEC-09

| Dimension | Caractérisation locale L7 (ce protocole) | Conformité contractuelle SPEC-09 (Configuration A/B) |
|---|---|---|
| **Topologie** | Mono-hôte Docker (`pocoma-local` / `docker/v3-five-runtimes`), 9 conteneurs colocalisés sur la même VM (`10` vCPU / `7.75 GiB` RAM, PostgreSQL `1.5` CPU / `1024 MiB` RAM). | Deux machines/VM dédiées **`4 vCPU / 8 Go RAM`** (PostgreSQL isolé sur hôte 1, API/workers sur hôte 2), générateur `k6` isolé, Keycloak isolé en Config B. |
| **Objectif** | Isoler et quantifier les 4 composantes de latence (admission, consommation, calcul de projection, disponibilité READ) sur **Pots moyens (`20/200`)** et **Grands pots (`100/2000`)** avec la configuration courante (`admission.capacity=4`, `min-poll=2 ms`, `20` partitions READ). | Certifier les SLO contractuels `F01-A`..`J`, `A11-L/M`, `ENDURANCE`, `SATURATION` avec `≥ 3` répétitions et `≥ 300` observations valides par population. |
| **Décision permise** | Diagnostic chiffré, comparaison relative avant/après, et arbitrage factuel sur l'utilité d'une éventuelle projection incrémentale de `PotBalance`. | **Ne certifie jamais un `PASS` ou `FAIL` contractuel Config A/B** ; un dépassement local signale un risque à investiguer, pas une non-conformité Config A définitive. |

---

## 2. Profils de données reproductibles

Les deux profils de référence sont construits par mutations réelles via l'API d'admission WRITE (sans injection SQL directe des projections READ), puis vérifiés convergés (`0` slot résiduel, `CurrentPot` et `PotBalance` exacts) avant le démarrage de la fenêtre de mesure :

| Profil | Participants (`Shareholders`) | Dépenses (`Expenses`) | Participations max | Version initiale attendue | Générateur de référence |
|---|---:|---:|---:|---:|---|
| **Pot moyen (`medium`)** | `20` (`1` SELF + `19` FREE) | `200` | `≤ 4 000` | `V ≥ 202` | [`scripts/performance/seed_spec09_medium_pots.py`](../../scripts/performance/seed_spec09_medium_pots.py) (à étendre avec paramètre `--profile medium\|large`) |
| **Grand pot (`large`)** | `100` (`1` SELF + `99` FREE) | `2 000` | `≤ 200 000` | `V ≥ 2 002` | Extension paramétrée du seeder (`--shareholders 100 --expenses 2000`, `10` Pots indépendants pour `F01-D` local) |

**Préconditions de reproductibilité du seed** :
1. Base vierge migrée (`V1__initial_schema.sql` WRITE + READ) et `pocoma-local` en état `READY` (`OPERATIONAL` + `QUALIFIED`).
2. Graine déterministe (`--seed`) pour les montants, dates et répartitions de participations.
3. Sauvegarde d'un manifeste de fixture JSON (`potIds`, `initialVersions`, `sha256` du jeu, horodatage) et drainage complet (`consumption_slot = 0` sur WRITE et READ) avant warmup.

---

## 3. Séparation des 4 étages de mesure

Chaque requête `CreateExpense` / `UpdateExpenseDetails` (à `1 cmd/s`, `10 cmd/s`, etc.) est horodatée par un identifiant `requestId` unique afin de dissocier les 4 étages sans additionner leurs `p95` :

| Étage | Frontière mesurée | Source d'observation (sans perturber la cible) | Métriques obligatoires |
|---|---|---|---|
| **1. Admission (`http-write`)** | `T0` (envoi HTTP `POST /api/v3/commands`) → `T1` (réponse `HTTP 202` ou `503`) | Horloge client monotone (`offerLagMs`, `admissionMs`), métriques Actuator/Prometheus `http-write` (`admission_capacity_available`) | `p50`, `p95`, `p99`, taux `202`/`503`, `offerLagMs` |
| **2. Consommation WRITE (`command-worker`)** | `T0` → `T2` (`v3_command_result` committé + `v3_pot_event` publié) | Sondage léger `GET /api/v3/commands/{requestId}/result` ou horodatage `recorded_at` en base WRITE post-run (hors boucle critique) | `p50`, `p95`, `p99`, durée `ExecutePotCommand`, taille file `COMMAND` |
| **3. Calcul des projections (`business-read-worker`)** | Durée interne d'exécution de `MaterializePotVersion`, `MaterializePotBalance` (`O(E × P)`), `MaterializePotVisibility` et `MaterializeCommandResult` | Métriques Micrometer/Prometheus (`pocoma_consumption_attempt_duration_seconds`, `pocoma_projection_*`), statistiques PostgreSQL `pg_stat_statements` / `pg_stat_wal` / `EXPLAIN ANALYZE BUFFERS` isolé | `p50`, `p95`, `p99` par projection, nb requêtes JDBC, lignes lues, octets WAL |
| **4. Disponibilité READ (`http-read`)** | `T0` → `T4` (1re lecture `GET /api/v3/pots/{potId}?minVersion=V` retournant `HTTP 200` complet avec `W ≥ V`, oracle `C-03`) + jalon `PotBalance(P,V)` exacte | Sonde HTTP `http-read` (intervalle `25 ms` documenté, biais `≤ 25 ms` conservé séparément) et vérification post-run `read_pot_balance` | `p50`, `p95`, `p99` (`C-03` et `Balance`), backlog résiduel READ |

---

## 4. Paramètres expérimentaux et seuils contractuels de référence

| Campagne locale | Profil | Débit offert | Warmup | Échantillon cible | Seuil `SPEC-09` (référence Config A) | Rôle local L7 |
|---|---|---:|---:|---:|---|---|
| **`LOC-MED-1`** (ref `F01-A`) | Moyen (`20/200`) | `1 cmd/s` (`10` Pots) | `60 s` | `n ≥ 300` (`300 s`), `3` runs | `p95 ≤ 500 ms` (`C-03` & `Balance`), calcul Balance candidat `≤ 50 ms` | Mesurer l'impact de `min-poll=2 ms` + `20` partitions READ vs l'ancien `1 558 ms` (`L6/R11`) |
| **`LOC-MED-10`** (ref `F01-B`) | Moyen (`20/200`) | `10 cmd/s` (`20`–`50` Pots) | `60 s` | `n ≥ 300` (`60 s`–`120 s`), `3` runs | `p95 ≤ 1 000 ms`, backlog stationnaire | Vérifier la stationnarité du backlog `business-read-worker` sur Pots peuplés |
| **`LOC-GRAND-1`** (ref `F01-D`) | Grand (`100/2000`) | `1 cmd/s` (`5`–`10` Pots) | `60 s` | `n ≥ 300` (`300 s`), `3` runs | `p95 ≤ 2 000 ms` (`C-03`) | Isoler le coût réel `O(E × P)` de `PotBalanceCalculator` et des lectures `SubobjectVersion` |
| **`LOC-IDLE`** (ref `F01-J`) | Moyen (`20/200`) | `0 cmd/s` puis `1` cmd | `30 s` | `120 s` repos + `10` réveils | Aucun seuil numérique ; CPU/SQL à vide documentés | Quantifier le coût CPU/SQL de `min-poll=2 ms` au repos |
| **`LOC-DEPTH`** (ref `F01-I-HISTORY`) | Profondeur `100/1000/10000` | Séquentiel isolé | `10` ops | `30` ops / profondeur | Requêtes & lignes JDBC indépendantes de la profondeur | Rejouer le contrôle `L1-F2` sur le code unifié courant |

---

## 5. Critères de comparabilité et de validité d'un run

Un run local n'est retenu dans les comparaisons que si **toutes** les conditions suivantes sont réunies :
1. **Calibration de la sonde** : mesurer l'écart avec et sans observateur HTTP/SQL (`L74-MET-02`), et vérifier `offerLagMs p95 ≤ 50 ms` (charge ouverte réelle, sans dérive d'échéancier).
2. **Stabilité environnementale** : aucune autre charge Docker/Maven active sur l'hôte, espace disque libre `≥ 1 GiB`, même SHA Git, mêmes `.properties` (`docker/v3-five-runtimes/*`), même limites CPU/RAM conteneurs.
3. **Intégrité fonctionnelle** : `0` erreur `5xx` inattendue, `0` slot en quarantaine, `100 %` de réconciliation WRITE ↔ READ en fin de drainage (`read_pot_version`, `read_pot_balance`, `read_command_result`).
4. **Répétabilité** : minimum `3` répétitions par profil (`LOC-MED-1`, `LOC-GRAND-1`), rapportant médiane et étendue des `p95`.

---

## 6. Règle d'arbitrage post-mesure (Projections incrémentales)

- **Aucune modification du moteur de projection n'est engagée avant exécution de ce protocole.**
- Si les mesures `LOC-MED-1` et `LOC-GRAND-1` (puis Config A) montrent que le recalcul intégral `O(E × P)` reste sous `500 ms` (`Moyen`) et `2 000 ms` (`Grand`) avec marge, **aucune projection incrémentale n'est nécessaire**.
- Si un goulot mesuré démontre que `MaterializePotBalance` ou le chargement `SubobjectVersion` viole les seuils malgré le polling `2 ms` et `20` partitions, un **mandat d'arbitrage dédié** sera soumis avec les relevés bruts avant toute évolution architecturale.
