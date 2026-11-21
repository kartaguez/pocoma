# Matrice résiduelle de certification L7 (`L7_LOCAL_CERTIFIED_WITH_RESERVES`)

- **Date d'actualisation** : 22 novembre 2026
- **Référence Git** : `feat/v3-l7` (gate `CERT-FULL` vérifiée sur `f589d95cc120331250a9e566f548481b6da7f836`)
- **Sources normatives et matricielles** :
  - [`L7_Final_Global_Qualification_Dossier.md`](L7_Final_Global_Qualification_Dossier.md) (dossier consolidé final et verdict)
  - [`L7_I1_Certification_Coverage_Matrix.csv`](L7_I1_Certification_Coverage_Matrix.csv) (`522` IDs réconciliés, colonnes `Statut L7 Final` / `Preuve L7 Final` / `SHA L7 Final` / `Rapprochement L7 Final`)
  - [`docs/testing/evidence/L7-final-qualification/cert-full-f589d95c/result.json`](../testing/evidence/L7-final-qualification/cert-full-f589d95c/result.json) (`1 173/1 173` tests `PASS`, `0` échec, `0` skip, `50/50` `architecture-tests`)
  - [`L7_C4/closure-and-l7-qualification.md`](L7_C4/closure-and-l7-qualification.md), [`L7_I3_Closure_Report.md`](L7_I3_Closure_Report.md), [`L7_LB_Closure_Act.md`](L7_LB_Closure_Act.md)

---

## 1. Bilan chiffré final après `CERT-FULL` et réconciliation (`522` lignes)

| Incrément | Total | `CURRENT_FULL` | `HISTORICAL_REUSABLE` | `LOCAL_ENV_BOUNDED` | `CURRENT_PARTIAL` | `DEFERRED_TO_L8_INFRA` | `RETIRED_BY_HUMAN_DECISION` | `MISSING` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| **L7.2** | 52 | **44** *(avant : 10)* | 0 | 0 | **7** *(avant : 41)* | 0 | 1 | 0 |
| **L7.3** | 432 | **425** *(avant : 63)* | **3** *(avant : 0)* | 0 | **3** *(avant : 368)* | 0 | 1 | 0 |
| **L7.4** | 20 | **1** *(avant : 0)* | 0 | **17** *(avant : 0)* | **1** *(avant : 9)* | **1** *(avant : 11)* | 0 | **0** *(avant : 11)* |
| **L7.5** | 18 | **16** *(avant : 1)* | 0 *(avant : 5)* | 0 | **2** *(avant : 10)* | 0 | 0 | **0** *(avant : 2)* |
| **Total** | **522** | **486** *(avant : 74)* | **3** *(avant : 5)* | **17** *(avant : 0)* | **13** *(avant : 428)* | **1** *(avant : 0)* | **2** *(avant : 2)* | **0** *(avant : 13)* |

---

## 2. Synthèse de clôture

1. **Aucune obligation `MISSING` (`0/522`)** et aucune obligation bloquante ouverte dans le périmètre local.
2. **Gate complète `L7.5` (`CERT-FULL`, `CERT-ARCH`, `CERT-TRACE`)** : **`PASS`** sur `f589d95c` (`1 173/1 173` tests, `4 957,75 s`, `0` anomalie de provenance).
3. **`17` obligations `L7.4` (`LOCAL_ENV_BOUNDED`) + `1` (`DEFERRED_TO_L8_INFRA`)** : caractérisées localement en C2–C4 sans jamais assimiler le laboratoire Mac Docker (`6 CPUs`) à la certification contractuelle `SPEC-09` Configuration A/B (`L74-ENV-01`, report L8).
4. **`13` obligations `CURRENT_PARTIAL`** : sous-variantes résiduelles non bloquantes (`7` L7.2, `3` L7.3 Invitation en rebuild, `1` L7.4 `F01-H`, `2` L7.5 `CERT-SPEC10` / `CERT-AUDIT`), toutes documentées nominativement dans [`L7_Final_Global_Qualification_Dossier.md`](L7_Final_Global_Qualification_Dossier.md) §2.
