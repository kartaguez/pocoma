# Registre des réserves actualisé — Post-clôture des 15 obligations L7.3

- **Date d'actualisation** : 17 novembre 2026
- **Référence Git** : `feat/l7-i3-closure-and-consolidation` (issue de `main` @ `0227dd0f`)
- **Sources** : [`L7_I3_Closure_Report.md`](L7_I3_Closure_Report.md), [`L7_Residual_Certification_Matrix.md`](L7_Residual_Certification_Matrix.md), [`L7_SPEC09_Local_Qualification_Protocol.md`](L7_SPEC09_Local_Qualification_Protocol.md)

**Classes de réserves** :
- **R1 (Bloquante L7)** : garantie ou preuve nécessaire avant clôture de L7.
- **R2 (Limite caractérisée / non bloquante L7-LB)** : limite d'exploitation ou de capacité connue et documentée.
- **R3 (Report L8 / Production)** : exigence relevant de l'infrastructure cible (Configuration A/B), de la haute disponibilité multi-nœuds ou de la production Internet.
- **R4 (Soldée / Remplacée)** : réserve close par correction ou preuve complète dans sa portée explicite.

---

## 1. Réserves L7-LB (Distribution locale)

| ID | Description factuelle | Classe | Statut | Impact L7 |
|---|---|---|---|---|
| `DEBT-L7LB-01` | `pocoma-local doctor` ne contrôle pas l'espace disque hôte (`PRE-DISK` vérifié au `start`). | **R2** | **OPEN** (non bloquant) | Non bloquant L7 ; fiche [`DOCTOR_DISK_CHECK/Debt.md`](../internal/debts/DOCTOR_DISK_CHECK/Debt.md). |
| `RES-L7LB-S6-01` | Enveloppe locale nominale bornée à `25 cmd/s` (`50 cmd/s` sature `business-read-worker` / PG en mono-hôte). | **R2** | **OPEN** (caractérisée) | À confronter sur Config A en L8 (`SPEC-09`). |
| `RES-L7LB-S6-02` | Topologie mono-worker et PostgreSQL partagé (reprise post-TTL, non failover actif multi-nœuds). | **R3** | **OPEN** (borne locale) | Relève de L8 (multi-nœuds / HA). |
| `RES-L7LB-S5-01..03` / `LB-J1-COLD` | Écarts S5 et installation clone vierge local. | **R4** | **CLOSED** | Soldée par S6, PR #6 et PR #7. |
| `LB-J1-GUI` / `LB-J1-CLIENT` | Ergonomie Bruno GUI par humain ; CORS/SPA BFF. | **R2 / R3** | **OPEN** | Non bloquant L7-LB ; CORS/SPA relève de L8. |

---

## 2. Réserves L7.3 (Sûreté, atomicité, non-purge et couverture matricielle)

| ID / Groupe | Description factuelle | Classe | Statut | Impact L7 |
|---|---|---|---|---|
| `L2M-E02-J-REPORT` | Crash après admission HTTP durable avant finalisation (`e1-e3-06` + `S5-B`/`S6`, diff vérifié). | **R4** | **CLOSED** | Soldée par réemploi validé ([`L7_I3_Closure_Report.md`](L7_I3_Closure_Report.md) §1). |
| `14` obligations `L7.3` ciblées | `L2M-E01-E`, `CON-05-NOPURGE`, `C01-A-REPORT`, `C01-D-REPORT`, `E01-G-INV-COMP`, `E01-G-ID-IDCOMP`, `CON-05-CRASH`, `C03-J-CRASH`, `C04-K`, `E01-H-REPORT`, `C04-L-REPORT`, `C06-M-REPORT`, `R8-03`, `R8-05`. | **R4** | **CLOSED** | Soldées par tests PostgreSQL ciblés (11/11 PASS) et audit sémantique de `writer-inventory-final` (`2 704` candidats). |
| `338` `CURRENT_PARTIAL` + `30` `HISTORICAL_PARTIAL` `L7.3` | Contributions partielles hors du noyau des 15 obligations bloquantes closes. | **R1** | **PARTIAL** | **Bloquant L7** — revue d'allocation/suffisance requise avant L7.5. |
| `41` `CURRENT_PARTIAL` `L7.2` | Couverture fonctionnelle/sécurité `L7.2` restante (après passage à `CURRENT_FULL` de `5` lignes qualifiées par `L7-LB`). | **R1** | **PARTIAL** | Requis pour la clôture L7.5. |

---

## 3. Réserves L7.4 (`SPEC-09`) & L7.5 / L8

| ID | Description factuelle | Classe | Statut |
|---|---|---|---|
| `L74-ENV-01` | Environnement A/B (`2 × 4 vCPU / 8 GiB` + Keycloak isolé) indisponible ; `13` familles `SPEC-09` `NOT_RUN` en conditions contractuelles. | **R3** | **OPEN** (relève de L8 / labo A/B dédié) |
| `L74-MET-02` / `L74-BENCH-01` | Calibration sonde et caractérisation locale sur profils `Moyen` (`20/200`) et `Grand` (`100/2000`). | **R1 / R2** | **PROTOCOL_READY** ([`L7_SPEC09_Local_Qualification_Protocol.md`](L7_SPEC09_Local_Qualification_Protocol.md)) — campagne locale non lancée. |
| `RES-L75-GATES` | Gates globales non filtrées (`./mvnw test` complet + `architecture-tests`) sur le SHA candidat gelé de L7, manifeste et audit final (`CERT-FULL`, `CERT-ARCH`, `CERT-SPEC10`, `CERT-AUDIT`, `CERT-TRACE`). | **R1** | **BLOCKED** (attend solde `338+30` L7.3 / `41` L7.2 et caractérisation locale SPEC-09). |
| `RES-L8-PROD` | HA multi-nœuds, TLS/mTLS, rotation secrets, volumes globaux `10k/1M` et `100k/10M`, campagnes `SPEC-09` Config A/B. | **R3** | **DEFERRED TO L8** |
