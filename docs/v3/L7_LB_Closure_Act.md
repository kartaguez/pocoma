# Acte de clôture formel — L7-LB (`L7_LB_CLOSED`)

- **Statut prononcé** : **`L7_LB_CLOSED`**
- **Audit résiduel associé** : **`L7_RESIDUAL_AUDIT_READY`**
- **Date de clôture** : 17 novembre 2026
- **Dépôt** : `kartaguez/pocoma`
- **Branche de référence** : `main`
- **SHA qualifié** : `59f68956e984a932bc28da6d9588680ce49d8210` (PR #6 fusionnée en `297b2da3`, CI verte)
- **Verdict de recette indépendante** : `L7_LB_CERTIFIABLE` (obtenu depuis un clone vierge de `main`)

---

## 1. Portée et limite explicite de la clôture

La clôture **`L7_LB_CLOSED`** atteste que la **distribution locale cinq runtimes** (`./pocoma-local` et ses 9 conteneurs Docker : `postgres`, `keycloak`, `http-write`, `http-read`, `command-worker`, `results-worker`, `business-read-worker`, `prometheus`, `grafana`) s'installe, démarre, s'authentifie, exécute les parcours fonctionnels V3, isole les accès, redémarre avec persistance et se qualifie de manière reproductible depuis un clone vierge de `main`.

> [!IMPORTANT]
> **Borne stricte de validité** : `L7_LB_CLOSED` **ne constitue ni une certification globale du lot L7** (`L7` reste **OPEN**, `L7.3` reste **NOT_CERTIFIED**, `L7.4` reste **NOT_CERTIFIED**, `L7.5` reste **BLOCKED**), **ni une certification de production Internet**, **ni une homologation SPEC-09 / SPEC-10 globale**.

---

## 2. Preuves fondant la clôture `L7_LB_CLOSED`

### 2.1 Recette indépendante sur clone vierge (`main` @ `59f68956e984a932bc28da6d9588680ce49d8210`)

La recette indépendante réalisée depuis un clone vierge de `main` a obtenu le verdict **`L7_LB_CERTIFIABLE`** sur la base des vérifications suivantes :

| # | Critère vérifié | Résultat constaté | Statut |
|---|---|---|---|
| 1 | Installation vierge depuis `main` | Clone vierge sans réutilisation de volumes, secrets ni état antérieur | `PASS` |
| 2 | Diagnostic poste (`./pocoma-local doctor`) | Verdict `READY` | `PASS` |
| 3 | Démarrage (`./pocoma-local start`) | `READY`, `OPERATIONAL`, `QUALIFIED`, `20/20` contrôles `PASS` | `PASS` |
| 4 | Topologie conteneurs | `9/9` conteneurs opérationnels | `PASS` |
| 5 | Bruno CLI (`J01`–`J03`, 1re exécution) | `236` tests, `731` assertions, `220` requêtes HTTP, `PASS` | `PASS` |
| 6 | Configuration GUI & AuthN PKCE | Configuration GUI et authentification PKCE `Owner` / `Guest` réussies | `PASS` |
| 7 | Parcours métier E2E | Inscription (`Registration`), création de pot (`CreatePot`), résultat de commande et lecture de projection validés | `PASS` |
| 8 | Isolation des accès | Lecture non autorisée retournant `HTTP 404` (isolation opaque conforme SPEC-06) | `PASS` |
| 9 | Cycle d'arrêt / redémarrage | Arrêt propre, redémarrage et persistance des données/secrets validés | `PASS` |
| 10 | Bruno CLI (`J01`–`J03`, 2e exécution) | Deuxième exécution Bruno CLI réussie après redémarrage | `PASS` |

*Note de traçabilité* : la recette indépendante sur `59f68956e984a932bc28da6d9588680ce49d8210` a été exécutée hors dépôt sur un clone vierge ; ses identifiants de runs et journaux locaux sont consignés dans le rapport de recette indépendant qui l'accompagne. Aucune preuve supplémentaire n'a été réinventée dans le dépôt.

### 2.2 Corpus de preuves L7-LB archivées dans le dépôt

Les qualifications antérieures de la branche `feat/l7-lb-local-playground` (fusionnée dans `main` en `59f68956`) restent conservées et vérifiables dans le dépôt :

| Campagne / Jalon | SHA / Contexte | Identifiants & Chemins des preuves | Verdict conservé |
|---|---|---|---|
| **S5 — Qualification distribution** (`S5-A`, `S5-B`, `S5-C`) | `5009d03a20ea781f1aa789ebd089a2174d06cecd` (instance `l7lb-s5`, `demo` intacte) | [`L7_LB_Distribution_Qualification_and_Guarantees.md`](L7_LB_Distribution_Qualification_and_Guarantees.md) · [`docs/testing/evidence/L7-LB-S5/`](../testing/evidence/L7-LB-S5/SHA256SUMS) (`00-configuration-manifest.json`, `s5a-installation-and-functioning.json`, `s5b-resilience-and-fault-injection.json`, `s5c-performance-and-limits.json`, `demo-integrity-before.json` / `after.json`) | `L7_LB_S5_QUALIFIED_WITH_RESERVATIONS` |
| **S6 — Durcissement opérationnel** | `f9c0ef64eb2249955e7acc7b74b9e157bf2620ba` → S6 (instance `l7lb-s6`) | [`L7_LB_S6_Operational_Hardening_Report.md`](L7_LB_S6_Operational_Hardening_Report.md) · [`docs/testing/evidence/L7-LB-S6/`](../testing/evidence/L7-LB-S6/) (`s6-b6-recovery.json`, `s6-p2-p3-performance.json`, `s6-status-stale.json`, `s6-final-canary-start.json`, `demo-integrity-before.json` / `after.json`) · `129/129` tests Python CLI + `4/4` tests Maven ciblés | `L7_LB_S6_QUALIFIED_WITH_RESERVATIONS` |
| **S7 — Productisation documentaire** | `4d889ec6` | [`L7_LB_S7_Documentation_Productization_Report.md`](../internal/L7_LB_S7_Documentation_Productization_Report.md) | `L7_LB_S7_QUALIFIED` |
| **Clean Clone (Zero Hidden Steps)** | `0a3246b9bc58bb813163caf22c6a2108c6f88db9` (instance `clean-clone`, `runId=1794740465-d1773951`) | [`L7_Local_Playground_Clean_Clone_Qualification_Report.md`](L7_Local_Playground_Clean_Clone_Qualification_Report.md) · [`docs/v3/evidence/l7-lb-clean-clone/2026-11-15/README.md`](evidence/l7-lb-clean-clone/2026-11-15/README.md) | `L7_LB_CLEAN_CLONE_QUALIFIED` |
| **Prérequis & diagnostics** | `da7912e3707a72c801c39c08858f65e801a508cb` | [`L7_LB_Prerequisites_Hardening_and_Requalification_Report.md`](L7_LB_Prerequisites_Hardening_and_Requalification_Report.md) · [`docs/v3/evidence/l7-lb-prerequisites/2026-11-15/README.md`](evidence/l7-lb-prerequisites/2026-11-15/README.md) | `L7_LB_PREREQUISITES_HARDENED_AND_QUALIFIED` |
| **S1 / S3 / S4** | Stabilisation Java, baselines SQL V1, doc produit | [`L7_LB_S1_Java_Naming_Stabilization_Report.md`](L7_LB_S1_Java_Naming_Stabilization_Report.md) · [`L7_LB_S3_SQL_Baseline_Consolidation_Report.md`](L7_LB_S3_SQL_Baseline_Consolidation_Report.md) · [`L7_LB_S4_Product_Documentation_Report.md`](L7_LB_S4_Product_Documentation_Report.md) | Clos dans L7-LB |

---

## 3. Dette non bloquante enregistrée à la clôture

- **`DEBT-L7LB-01` — Contrôle de l'espace disque hôte dans `pocoma-local doctor`** :
  - **Constat factuel** : [`scripts/local/runtime.py`](../../scripts/local/runtime.py#L1314-L1340) implémente `doctor()` en vérifiant les outils, les images Docker cachées et la mémoire Docker (`dockerMemoryMiB` / `recommendedMemoryMiB`), mais ne contrôle pas l'espace disque libre de l'hôte (`PRE-DISK` n'est vérifié que dans `resources()` au moment de `start` aux lignes `678-688`).
  - **Impact** : un poste disposant d'un espace disque insuffisant obtient `doctor -> READY` puis échoue tardivement au `start` sur `PRE-DISK` (constat déjà observé lors de S5 avec `255.3 MiB` libres).
  - **Exigence différée (non bloquante pour L7-LB)** : ajouter le contrôle de l'espace disque hôte dans `pocoma-local doctor` avec diagnostic préventif explicite (espace disponible, seuil requis froid/chaud, action recommandée).
  - **Fiche de dette** : [`docs/internal/debts/DOCTOR_DISK_CHECK/Debt.md`](../internal/debts/DOCTOR_DISK_CHECK/Debt.md) (indexée dans [`docs/internal/debts/README.md`](../internal/debts/README.md)).

---

## 4. Documents faisant autorité associés à cette clôture

- **Canon et plan directeur** : [`V3_Canon_and_Roadmap.md`](V3_Canon_and_Roadmap.md), [`V3_Implementation_Master_Plan.md`](V3_Implementation_Master_Plan.md)
- **Audit résiduel L7** :
  - [`L7_Residual_Certification_Matrix.md`](L7_Residual_Certification_Matrix.md) — État résiduel exact de L7 et des 15 obligations précédemment partielles
  - [`L7_SPEC09_Compliance_Audit.md`](L7_SPEC09_Compliance_Audit.md) — Confrontation L7 vs SPEC-09
  - [`L7_Reservations_Register_Updated.md`](L7_Reservations_Register_Updated.md) — Registre des réserves actualisé
  - [`L7_Termination_Plan.md`](L7_Termination_Plan.md) — Plan de terminaison L7 ordonné par dépendances et effort croissant
