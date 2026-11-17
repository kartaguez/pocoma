# Audit de conformité L7 vs SPEC-09 (`L7_RESIDUAL_AUDIT_READY`)

- **Date d'audit** : 17 novembre 2026
- **Référence Git** : `main` @ `59f68956e984a932bc28da6d9588680ce49d8210`
- **Sources normatives** : [`SPEC-09_Performance.md`](SPEC-09_Performance.md), [`L7_I4_SPEC09_Contract_Matrix.csv`](L7_I4_SPEC09_Contract_Matrix.csv), [`L7_Local_Baseline_SPEC09_Feasibility_Matrix.csv`](L7_Local_Baseline_SPEC09_Feasibility_Matrix.csv)
- **Campagnes comparées** :
  - `L6/I3-R11` ([`L6_I3_R11_Final_SPEC09_E2E_SLA_Qualification_Report.md`](L6_I3_R11_Final_SPEC09_E2E_SLA_Qualification_Report.md) @ `c61df675`)
  - `L7-I4 E1` ([`L7_I4_SPEC09_Technical_Qualification_Report.md`](L7_I4_SPEC09_Technical_Qualification_Report.md) @ `ef90dd6f`..`230feb05`)
  - `L7-LB S5-C` ([`L7_LB_Distribution_Qualification_and_Guarantees.md`](L7_LB_Distribution_Qualification_and_Guarantees.md) @ `5009d03a`) & `L7-LB S6` ([`L7_LB_S6_Operational_Hardening_Report.md`](L7_LB_S6_Operational_Hardening_Report.md) @ `f9c0ef64`→S6)

---

## 1. Verdict synthétique SPEC-09

- **Toutes les 13 familles contractuelles `SPEC-09` (`F01-A`..`J`, `A11-L/M`, `ENDURANCE`, `SATURATION`) sont `NOT_RUN` dans leurs conditions contractuelles (Configuration A/B).**
- **Aucun SLO `SPEC-09` n'est certifié à ce stade.**
- Les mesures existantes (`L6/I3-R11`, `L7-I4 E1`, `L7-LB S5-C/S6`) apportent des caractérisations locales utiles mais **ne sont ni directement comparables entre elles ni substituables aux campagnes Configuration A/B**.

---

## 2. Tableau de correspondance explicite `SPEC-09` ↔ Preuves existantes

| ID `SPEC-09` | Exigence & Seuil contractuel | Campagnes / Profils exécutés | Résultats mesurés vs Seuils | Comparabilité / Reproductibilité & Statut |
|---|---|---|---|---|
| `ENV-A` | PG dédié `4 vCPU / 8 Go` + Runtimes sur 2e machine `4 vCPU / 8 Go` (`SPEC-09 §3`). | Aucune (tous essais sur Docker Desktop mono-hôte `10 CPU / 7.75 GiB` VM). | N/A — Cible distante absente (`L74-ENV-01`). | **`NOT_RUN`** (relève de L8 / labo A/B). |
| `ENV-B` | Socle `A` + Keycloak isolé pour `F01-F`. | Keycloak local colocalisé dans `L7-LB` (`S5-A`, `clean-clone`, `main`). | Fonctionnel prouvé localement ; comparaison perf A/B absente. | **`NOT_RUN`** (relève de L8 / labo A/B). |
| `DATA-LOCAL` | Profils `Petit` (`5/20`), `Moyen` (`20/200`), `Grand` (`100/2000`, `≤200k` participations). | `L6/R11` : 6 Pots moyens (`20/200+`). `L7-I4 E1` : 2 Petits (`5/20`). `L7-LB S5/S6` : `CreatePot` neuf (`0` expense). | `Grand` (`100/2000`) jamais matérialisé en E2E. | **`NOT_RUN`** pour `Grand` ; `Moyen` uniquement en `L6/R11` (ancienne config). |
| `F01-A-C03` | Pot moyen `1 cmd/s`, Config A : `p95 ≤ 500 ms` jusqu'à 1re lecture READ complète `W≥V`. | `L6/R11 N1` (`1/s`, Pots moyens, 4 partitions, poll `20..100 ms`, `n=120`) ; `L7-I4 E1` (`1/s`, Petits Pots, `n=12`) ; `L7-LB S5-C P0` (`1/s`, `CreatePot`, poll `2..20 ms`, `n=60`). | `L6/R11 N1` : **`1 558.45 ms`** (**`LOCAL FAIL`** vs `500 ms`). `L7-I4 E1` : `202–338 ms` (Petits Pots). `S5-C P0` : `84.4 ms` (`CreatePot` neuf). | **`NOT_RUN`** (Config A + Pots moyens + `n≥300` manquants ; `S5-C`/`E1` non comparables car Petits Pots/`CreatePot` et `n<300`). |
| `F01-A-BAL` | Pot moyen `1 cmd/s`, Config A : `p95 ≤ 500 ms` jusqu'à `PotBalance(P,V)` exacte et durable (budget technique interne candidat `≤ 50 ms`). | `L7-I4 E1` sonde Balance après lecture Pot (`n=12`, Petits Pots). | `L7-I4 E1` : `207.4–343.4 ms` (borne supérieure retardée, non indépendante). | **`NOT_RUN`** (sonde indépendante sous charge + Pots moyens + Config A manquants). |
| `F01-B` | Pot moyen `10 cmd/s` soutenus, Config A : `p95 ≤ 1 000 ms`, backlog stable. | `L6/R11 N2` (`10/s`, Pots moyens, `n=1801`) ; `L7-LB S5-C P1` (`10/s`, `CreatePot`, `n=900`). | `L6/R11 N2` : **`101 098.7 ms`** (**`LOCAL FAIL`**). `S5-C P1` : `87.0 ms` (`CreatePot`, `0` backlog résiduel). | **`NOT_RUN`** (Config A + Pots moyens + 3 répétitions manquants). |
| `F01-C` | Pot moyen pointe `50 cmd/s` puis `1/s`, Config A : `p95 ≤ 3 000 ms` + résorption. | `L6/R11 N3` (`46.03/s`, Pots moyens) ; `L7-LB S5-C P3` (`capacity=1`) & `S6 P3` (`capacity=4`, `CreatePot`). | `L6/R11 N3` : **`121 975.0 ms`** (**`LOCAL FAIL`**). `S5-C P3` : saturé à `41.37/s` (`1036` HTTP `503`). `S6 P3` : `50.005/s` admis, **READ visible `p95 = 25 176.75 ms`** (`drain=6.284 s`). | **`NOT_RUN`** (même sur `CreatePot`, `50 cmd/s` sature `business-read-worker` / PG en mono-hôte sous `S6`). |
| `F01-D` | Grand Pot (`100/2000`) à `1 cmd/s`, Config A : `p95 ≤ 2 000 ms` (`C-03`). | Aucune campagne E2E (seul profilage calcul pur `L1-F` historique). | Non mesuré en E2E. | **`NOT_RUN`**. |
| `F01-E` | Registration EUI fraîche → `HTTP 200` `/api/v3/me/pots?limit=1` avec nouveau `bindingToken`, Config A : `p95 ≤ 500 ms`. | `L7-I4 E1` (`1` EUI par run, sans warmup) ; `L7-LB` (`Bruno J01`, `clean-clone`, `main`). | Fonctionnel prouvé ; aucune population `n≥300` mesurée vs `500 ms`. | **`NOT_RUN`**. |
| `F01-F` | OIDC / Registration / Unlink sur Config B vs Config A (pas de SLO numérique supplémentaire). | `L7-LB` (`S5-A`, `clean-clone`, `main`) exécute Code+PKCE S256 réel. | Fonctionnel prouvé localement ; comparaison appariée A/B absente. | **`NOT_RUN`** (Config A/B absente). |
| `F01-G` | Rattrapage `10 000` commandes `PENDING` sur Pots moyens, redémarrage workers sous `1 cmd/s`, Config A : convergence `≤ 300 s`. | Aucune (`L7-LB S5/S6` draine `3`–`35` slots en `< 6.3 s`). | Non mesuré à `10 000` commandes sur Pots moyens. | **`NOT_RUN`**. |
| `F01-H` | Contention même Pot, reprise nominale (pas de SLO numérique, critères sûreté/CAS/drainage). | `L6/I3-R4` / `R5` (caractérisation contention CAS mono-worker). | Zéro double effet / perte en `R5` sous sérialisation par Pot. | **`NOT_RUN`** sur code courant unifié / protocole `SPEC-09`. |
| `F01-I-GLOBAL` | Volumes globaux `100/10k`, `10k/1M`, `100k/10M` (Pots/événements), plans/WAL/CPU. | Aucune. | Non mesuré. | **`NOT_RUN`** (relève de L8 / capacité infra). |
| `F01-I-HISTORY` | Profondeur `100/1000/10000` versions : lignes JDBC et histoire chargée indépendantes de la profondeur (`SPEC-09 §8`). | `L1-F2` historique (avant réacteur unifié L7). | Non rejoué sur le code unifié courant. | **`NOT_RUN`** en L7 (rejouable localement). |
| `F01-J` | Repos puis première commande (CPU/SQL à vide, latence réveil). | `L6/I3-R7` (charge à vide à `20 ms` polling : `554 cycles/s`, PG `29%` CPU). | Non mesuré avec `min-poll=2 ms` de `L7-LB` ni repos `600 s` de `L7-I4`. | **`NOT_RUN`** (rejouable localement). |
| `DELETE` & `DELETE-HISTORY` | `DeletePot` : observation interne `CurrentPot → PotVersion(D) deleted=true` sous budgets `C-03` + exactitude `V<D` et absence `Balance(D)`. | Tests fonctionnels `L7.3` / Bruno. | Aucune mesure de latence interne `DeletePot` sous charge. | **`NOT_RUN`**. |
| `AUD-V3-013` | Coûts détaillés `PotVersion` / `SubobjectVersion` / `PotBalance` / manifeste JSON sur les 3 profils. | `L1-F` (calcul pur), `L6/I3-R2` (complexité `O(E × P)` confirmée). | Mesures E2E SQL/JDBC/WAL/JSON manquantes sur les 3 profils. | **`NOT_RUN`**. |
| Méthodologie (`REPEAT`, `MIN-SAMPLE`, `LOAD`, `MIX`, `METRICS`, `REPRO`) | `≥ 3` répétitions, `≥ 300` obs/population SLO, charge ouverte sans perte, mix complet, calibration sonde. | `L7-LB S5-C` (`P1=900`, `P2=2250`, `P3=4964/6000` sur `CreatePot` seul, 1 run S5 + 1 run S6). | `L7-I4 E1` (`n=12`) < `300` ; `S5-C`/`S6` = 1 seul mix (`CreatePot`), `1` run par config, mono-hôte. | **Non reproductible comme preuve `SPEC-09`**. |

---

## 3. Décomposition causale des latences observées

Les campagnes `L6/R11`, `L7-I4 E1` et `L7-LB S5-C/S6` permettent de distinguer quatre composantes de latence sans les confondre :

1. **Latence de démarrage / à froid** :
   - Bootstrap complet `l7lb-s5` en `23.937 s` (build caché), `63.067 s` sur `clean-clone` (`buildCached=false`). N'entre pas dans les budgets `C-03` (qui démarrent après stabilisation/warmup).
2. **Latence d'admission (`http-write`)** :
   - Sur `L7-LB`, `POST /api/v3/commands` est très rapide tant que le sémaphore n'est pas saturé : `p95 = 7.80 ms` (`P0`), `6.15 ms` (`P1`), `19.25 ms` (`P2` S5, amélioré à **`13.91 ms`** en S6 avec `capacity=4`), `43.78 ms` (`P3` S6 à `50 cmd/s`).
   - L'admission n'est **pas** le goulot de latence E2E dès lors que `capacity=4` est configuré.
3. **Latence de consommation (`command-worker` & `results-worker`)** :
   - Dépend fortement du polling Discovery et du nombre de partitions :
     - Avec `20..100 ms` et `4` partitions (`L6/R11`), l'attente amont représentait la majorité du délai (`stageA_commandWait` en R7, E2E `p95 = 1 558.45 ms` à `1 cmd/s`).
     - Avec `2..20 ms` et `8` partitions (`L7-LB S5/S6`), `E2E CMD (T0→T2)` tombe à `p95 = 34.05 ms` (`P0`), `35.0 ms` (`P1`), `50.0–57.0 ms` (`P2`), `196.0 ms` (`P3` S6).
     - En contrepartie, un polling agressif (`2 ms`) augmente la charge SQL à vide sur PostgreSQL (à quantifier avec `F01-J`).
4. **Latence de disponibilité des projections READ (`business-read-worker` / `T0→max T4`)** :
   - C'est ici que se situent les deux vrais phénomènes limitants observés :
     - **Saturation sous débit soutenu (`50 cmd/s`)** : en `S6 P3` (`capacity=4`), les 6 000 commandes `CreatePot` sont admises sans `503` et `command-worker` suit (`p95 = 196.0 ms`), mais `business-read-worker` accumule une file d'attente qui porte la visibilité READ `p95` à **`25 176.75 ms`** (`25.18 s`, drainage `6.284 s`, PostgreSQL à `~151–157 %` CPU sur plafond `150 %` en S5).
     - **Coût de recalcul sur Pots peuplés (`O(E × P)`)** : en `L6/R11` sur Pots moyens (`20/200`), même à `1 cmd/s`, la matérialisation complète dépassait `500 ms` (`p95 = 1 558.45 ms`). En `L7-LB S5/S6` (`CreatePot`, `0` expense), la projection reste `< 231 ms` jusqu'à `25 cmd/s`.

---

## 4. Évaluation de l'intérêt des projections incrémentales

- **Décision dans cette mission** : **aucun moteur incrémental n'est implémenté ni prescrit comme obligation contractuelle.**
- **Justification factuelle** :
  1. Sur `CreatePot` (`0` expense), le goulot à `50 cmd/s` en `S6` vient de la **saturation CPU/I-O PostgreSQL + `business-read-worker` mono-conteneur** sous débit continu (`6000` créations), pas du recalcul `O(E × P)` des soldes.
  2. Sur Pots moyens (`20/200`) et Grands Pots (`100/2000`), la dernière mesure disponible (`L6/R11`, `1 558.45 ms` à `1 cmd/s`) date d'avant `L7-LB` (4 partitions, polling `20..100 ms`, mono-hôte Docker) et **n'a pas été réexécutée avec la configuration actuelle (`2..20 ms`, `20` partitions READ) ni sur Configuration A**.
  3. Par conséquent, **l'intérêt d'une projection incrémentale de `PotBalance` ne pourra être arbitré qu'après mesure ciblée sur Pots moyens (`20/200`) et Grands Pots (`100/2000`)** (d'abord localement pour isoler le coût CPU/SQL de `PotBalanceCalculator`, puis sur Configuration A) :
     - Si le recalcul intégral `O(E × P)` tient `≤ 500 ms` (`Moyen`) et `≤ 2 000 ms` (`Grand`) sur Configuration A, **aucun moteur incrémental n'est requis**.
     - Ce n'est **que si un écart mesuré sur Configuration A** démontre que le recalcul intégral viole ces seuils qu'une optimisation incrémentale deviendra pertinente (à instruire alors par mandat dédié, avec maintien de l'oracle exact `L1-F`).
