# Documentation interne et mémoire de construction

> [!NOTE]
> Cet espace regroupe les politiques d'ingénierie internes, spécifications historiques, décisions d'architecture, rapports d'implémentation, qualifications, benchmarks et archives conservés pour traçabilité.
>
> **Pour installer, comprendre ou utiliser Pocoma**, consultez la [documentation produit](../README.md).

## 1. Politiques d'ingénierie actives (normatives)

- [Politique de vérification Maven par slices](../testing/Reactor_Verification_Policy.md) — **normative** (référencée par `AGENTS.md`).
- [Intégration continue (CI)](../development/ci.md) — règles de gate Maven et architecture.
- [Architecture modulaire cible et matrice de dépendances](../v3/L7_Target_Modular_Architecture_and_Migration_Plan.md) — référence normative des 42 modules (chemin contractuel vérifié par `L7ReactorArchitectureTest`).

## 2. Spécifications et canon V3

Les spécifications fonctionnelles et techniques du programme V3 sont conservées sous `docs/v3/` :

- [Canon et feuille de route V3](../v3/V3_Canon_and_Roadmap.md)
- [Plan directeur d'implémentation L0–L8](../v3/V3_Implementation_Master_Plan.md)
- [SPEC-01 — Pot Domain](../v3/SPEC-01_Pot_Domain.md) · [SPEC-02 — Identity Domain](../v3/SPEC-02_Identity_Domain.md) · [SPEC-03 — Events, Requests, Consumables](../v3/SPEC-03_Events_Requests_Consumables.md)
- [SPEC-04 — Read Projections](../v3/SPEC-04_Read_Projections.md) · [SPEC-05 — Consumption Discovery](../v3/SPEC-05_Consumption_Discovery.md) · [SPEC-06 — Supra Contracts](../v3/SPEC-06_Supra_Contracts.md)
- [SPEC-07 — Transverse Requirements](../v3/SPEC-07_Transverse_Requirements.md) · [SPEC-08 — Observability](../v3/SPEC-08_Observability.md) · [SPEC-09 — Performance](../v3/SPEC-09_Performance.md) · [SPEC-10 — Test Certification](../v3/SPEC-10_Test_Certification.md)

## 3. Rapports d'implémentation, lots et décisions

- **Distribution locale (`L7-LB`)** :
  - **[Acte de clôture formel `L7_LB_CLOSED`](../v3/L7_LB_Closure_Act.md)** (`main` @ `59f68956`)
  - [L7-LB S1 — Java Naming Stabilization](../v3/L7_LB_S1_Java_Naming_Stabilization_Report.md)
  - [L7-LB S3 — SQL Baseline Consolidation](../v3/L7_LB_S3_SQL_Baseline_Consolidation_Report.md)
  - [L7-LB S4 — Product Documentation](../v3/L7_LB_S4_Product_Documentation_Report.md)
  - [L7-LB S6 — Operational Hardening](../v3/L7_LB_S6_Operational_Hardening_Report.md)
  - [L7-LB S7 — Documentation Productization](L7_LB_S7_Documentation_Productization_Report.md)
  - [L7-LB — Distribution Qualification and Guarantees](../v3/L7_LB_Distribution_Qualification_and_Guarantees.md)
  - [L7 Local Playground — Réserves historiques](../v3/L7_Local_Playground_Reservations.md) · [API BFF](../v3/L7_Local_Playground_Public_API.md) · [Bruno](../v3/L7_Local_Playground_Bruno.md) · [Observabilité](../v3/L7_Local_Playground_Observability.md) · [Keycloak Diagnostics](../v3/L7_Local_Playground_Keycloak_Diagnostics.md) · [Recovery and Evidence](../v3/L7_Local_Playground_Recovery_and_Evidence.md)
- **Audit résiduel L7 (`L7_RESIDUAL_AUDIT_READY`)** :
  - [Matrice résiduelle de certification L7](../v3/L7_Residual_Certification_Matrix.md)
  - [Audit de conformité SPEC-09](../v3/L7_SPEC09_Compliance_Audit.md)
  - [Registre des réserves actualisé](../v3/L7_Reservations_Register_Updated.md)
  - [Plan de terminaison L7](../v3/L7_Termination_Plan.md)
- **Lots V3 (L0–L7)** : rapports de clôture, plans et manifestes conservés sous [`docs/v3/`](../v3/).
- **Code Health V3 (Phase 1)** : [Rapport de clôture et registre des travaux différés](../v3/Code_Health_Phase_1_Closure_Report.md) (`refactor/codescene-code-health-10`, chantier en pause).
- **Steps et plans historiques** : [`steps/`](steps/) (`completed/` et anciens `current/`) et [`plans/`](plans/).

## 4. Qualifications, benchmarks et preuves

- **Qualifications de performance (`L6/I3`)** : [R5 Realistic Business Workload](../v3/L6_I3_R5_Realistic_Business_Workload_Qualification_Report.md), [R4 Final Metrology](../v3/L6_I3_R4_Final_Metrology_and_Sustainable_Capacity_Qualification_Report.md), [L7.4 SPEC-09 Technical Qualification](../v3/L7_I4_SPEC09_Technical_Qualification_Report.md).
- **Preuves d'exécution et jeux de données** :
  - [`docs/testing/evidence/`](../testing/evidence/) (référencé par les tests Java et scripts de vérification).
  - [`docs/v3/evidence/`](../v3/evidence/) (preuves `l7-lb-clean-clone`, `l7-lb-prerequisites`).

## 5. Dettes techniques, études historiques et archives

- **Dettes techniques** : [Registre des dettes techniques](debts/README.md).
- **Études d'architecture et audits historiques** : [`architecture/`](architecture/), [Audit de rationalisation documentaire](Documentation_Rationalization_Audit.md), [Validation croisée DOC.5](Documentation_Truth_Validation.md), [`projection-workers.md`](projection-workers.md), [`use-case-families.md`](use-case-families.md).
- **Runbooks dev-bench et cutovers historiques** : [`operations/`](operations/) (scripts SQL associés conservés sous [`../operations/sql/`](../operations/sql/)).
- **Architecture historique V2** : [Functional Model (V2)](v2/product/Functional_Model.md), [Architecture (V2)](architecture/Architecture.md), [System Guarantees (V2)](v2/guarantees/System_Guarantees.md).
- **Archives et migrations SQL isolées** : [Index des archives](../archive/README.md).
