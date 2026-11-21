# Registre des réserves actualisé — Qualification globale finale L7 (Post C1–C4 & `CERT-FULL`)

- **Date d'actualisation** : 22 novembre 2026
- **Référence Git** : `feat/v3-l7` (`CERT-FULL` vérifiée sur `f589d95cc120331250a9e566f548481b6da7f836`)
- **Sources** : [`L7_Final_Global_Qualification_Dossier.md`](L7_Final_Global_Qualification_Dossier.md), [`L7_Residual_Certification_Matrix.md`](L7_Residual_Certification_Matrix.md), [`L7_C4/closure-and-l7-qualification.md`](L7_C4/closure-and-l7-qualification.md)

**Classes de réserves** :
- **R1 (Bloquante L7)** : garantie ou preuve nécessaire avant clôture locale de L7 — **toutes soldées (`0` ouverte)**.
- **R2 (Limite caractérisée / non bloquante)** : limite d'exploitation ou sous-variante connue et documentée.
- **R3 (Report L8 / Production)** : exigence relevant de l'infrastructure cible (Configuration A/B), de la haute disponibilité multi-nœuds ou de la production Internet.
- **R4 (Soldée / Remplacée)** : réserve close par correction ou preuve complète dans sa portée explicite.

---

## 1. Réserves L7-LB (Distribution locale)

| ID | Description factuelle | Classe | Statut | Impact L7 |
|---|---|---|---|---|
| `DEBT-L7LB-01` | Contrôle d'espace disque hôte (`PRE-DISK`) ajouté à `pocoma-local doctor` (`f589d95c`, `36/36` tests Python `PASS`). | **R4** | **CLOSED** | Soldée ([`DOCTOR_DISK_CHECK/Debt.md`](../internal/debts/DOCTOR_DISK_CHECK/Debt.md)). |
| `RES-L7LB-S6-01` | Enveloppe locale mono-hôte (`6 CPUs` Docker) : soutenable jusqu'à `35 cmd/s` (`p95 = 134 ms`), plateau à **`36,83 cmd/s`** (`+34 %` vs `T0`), en deçà de la pointe `50 cmd/s` Config A. | **R2** | **OPEN** (caractérisée) | À confronter sur Config A en L8 (`SPEC-09`). |
| `RES-L7LB-S6-02` | Topologie mono-worker et PostgreSQL partagé (reprise post-TTL, non failover actif multi-nœuds). | **R3** | **OPEN** (borne locale) | Relève de L8 (multi-nœuds / HA). |
| `RES-L7LB-S5-01..03` / `LB-J1-COLD` | Écarts S5 et installation clone vierge local. | **R4** | **CLOSED** | Soldée par S6, PR #6 et PR #7. |
| `LB-J1-GUI` / `LB-J1-CLIENT` | Ergonomie Bruno GUI par humain ; CORS/SPA BFF. | **R2 / R3** | **OPEN** | Non bloquant L7 ; CORS/SPA relève de L8. |

---

## 2. Réserves L7.2 / L7.3 / C4

| ID / Groupe | Description factuelle | Classe | Statut | Impact L7 |
|---|---|---|---|---|
| `L2M-E02-J-REPORT` + `14` obligations `L7.3` ciblées | Clôturées en `L7-I3` et reconfirmées `PASS` dans `CERT-FULL` (`f589d95c`). | **R4** | **CLOSED** | Soldée. |
| Revue `R1` (`41` L7.2 & `338+30` L7.3) | Réconciliation nominative des `522` lignes contre `CERT-FULL` (`1 173/1 173 PASS`) : `486 CURRENT_FULL`, `3 HISTORICAL_REUSABLE`, `10` sous-variantes partielles non bloquantes (`7` L7.2 + `3` L7.3) documentées. | **R4** *(réserve bloquante levée)* / **R2** *(10 sous-variantes)* | **CLOSED (R1)** / **OPEN (R2)** | Aucune obligation bloquante restante en L7 local. |
| Observation `a2-idle` (latence post-inactivité) | Pic `p95=max = 978–1 121 ms` (`n=10`, `warmup=0`) démontré comme effet de démarrage à froid des conteneurs sur `--snapshot-dir` (`41–54 %` dès `http-write`, hors `B1–B3`) ; médiane chaude `142–149 ms`. | **R4** | **CLOSED** | Expliquée et qualifiée sans modification de code ni des SLO. |

---

## 3. Réserves L7.4 (`SPEC-09`) & L7.5 / L8

| ID | Description factuelle | Classe | Statut |
|---|---|---|---|
| `L74-MET-02` / `L74-BENCH-01` | Métrologie complète (`PERF-METRO` `CURRENT_FULL`) et caractérisation locale `Moyen`, `Grand`, `Identity`, `DeletePot`, `Idle`, `Ramp`, `Backlog 10k` exécutées (`C2–C4`). | **R4** | **CLOSED** (périmètre local) |
| `RES-L75-GATES` | Gates globales `CERT-FULL` (`1 173/1 173 PASS`), `CERT-ARCH` (`50/50 PASS`) et `CERT-TRACE` exécutées sur SHA gelé propre `f589d95c`. | **R4** | **CLOSED** |
| `RES-L75-AUDIT` | `CERT-AUDIT` : audit interne consolidé livré ; un éventuel audit tiers indépendant relève d'une décision de gouvernance humaine. | **R2** | **OPEN** (gouvernance) |
| `RES-L74-CONTENTION` | `F01-H` (`c2-contention-1pot`) : `0` violation d'invariant mais `2–6/120` commandes résiduelles au `drainageTimeout` sous `T0` ; revalidation sous `A2` + Config A reportée L8. | **R2 / R3** | **OPEN** |
| `L74-ENV-01` | Environnement cible `SPEC-09` Configuration A/B (`2 × 4 vCPU / 8 GiB` + Keycloak isolé) indisponible localement (`17` obligations `LOCAL_ENV_BOUNDED`, `1` `DEFERRED_TO_L8_INFRA`). | **R3** | **DEFERRED TO L8** |
| `RES-L8-PROD` | HA multi-nœuds, TLS/mTLS, rotation secrets, volumes globaux `10k/1M` et `100k/10M`, campagnes `SPEC-09` Config A/B. | **R3** | **DEFERRED TO L8** |
