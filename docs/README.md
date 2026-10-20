# Documentation Pocoma

Sources **CURRENT** de l’état livré :

1. [Functional Model](product/Functional_Model.md) — comportement observable.
2. [Architecture](architecture/Architecture.md) — chaînes et modules exécutés.
3. [System Guarantees](guarantees/System_Guarantees.md) — propriétés, preuves et limites.

La [validation croisée](Documentation_Truth_Validation.md) retrace les preuves et leurs limites.

## Chantier courant : séquencement → Discovery → READ

- [Step Canon — current_canon](steps/current/DISCOVERY/Step_Canon.md) : décisions, état livré, cibles et limites.
- [Step Plan — current_plan](steps/current/DISCOVERY/Step_Plan.md) : **4A CLOSED, 4B CLOSED hors production, 4C NEXT**, critères 4C–4E et preuves 4A/4B.
- [Next Plan — next_plan](steps/current/DISCOVERY/Next_Plan.md) : dépendances Discovery, modèle READ, matérialisation et évolutions fonctionnelles.
- [Inventaire et contrôle de consolidation](steps/current/DISCOVERY/Consolidation_Report_2026-10-20.md).

Conventions : `steps/current/<STEP>/Step_Canon.md`, `Step_Plan.md`, `Next_Plan.md`.
Canon : décisions prises/révisées ; plan courant : avancement ; plan suivant : dépendances/priorités.
À la relève, consolider l'acquis dans le canon et les synthèses CURRENT, archiver le Step achevé,
promouvoir le prochain plan. [Gouvernance](Documentation_Rationalization_Audit.md#15-gouvernance-documentaire).

## Architecture finale, dettes et historique

- **CURRENT = TARGET ACHIEVED** : l'[Architecture](architecture/Architecture.md) reste la source normative ; la [topologie finale](steps/completed/ARCHITECTURE/Modularity_Target_Topology.md), la [traçabilité](steps/completed/ARCHITECTURE/Modularity_Current_Target_Traceability.md) et le [re-audit final PASS](steps/completed/ARCHITECTURE/Modularity_Final_Reaudit.md) en conservent la preuve historique.
- **HISTORICAL design** : [révision des trois familles de moteurs](steps/completed/ARCHITECTURE/Three_Engine_Families_Revision.md). Ses tableaux et anciens comptes sont des matériaux de migration superseded.
- **TARGET / références spécialisées** : [architecture READ cible](architecture/read-side-target.md) et [contrats du kernel d’autorisation](architecture/authorization-kernel-contracts.md) ; leur portée est indiquée dans chaque document.
- **DEBTS** : [registre des dettes techniques](debts/README.md), pour les écarts connus et acceptés sans en faire une source normative du système ni un Step automatiquement actif.
- **HISTORICAL-COMPLETED** : [Steps livrés](steps/completed/) et [plans exécutés](plans/completed/).
- **SUPERSEDED** : [archives](archive/). La [matrice de classement](Documentation_Rationalization_Audit.md#8-audit-exhaustif-de-docsplans) conserve la provenance des 43 plans.

## Ingénierie et références

- [Politique de vérification](testing/Reactor_Verification_Policy.md) et [CI](development/ci.md).
- [Exploitation](operations/cmd-start-runtimes.md) et SQL associé dans `operations/sql/`.
- Détails : [transactions Consumption](architecture/consumption-transactional-execution.md), [exécution ProjectionTask](architecture/projection-task-execution.md), [reconstruction historique Pot](architecture/pot-historical-reconstruction.md).

## Audits transactionnels et études du prochain protocole

- [Index des audits, études et preuves R03](architecture/transactional-protocol-studies.md) : autorités, ordre des verrous et fencing Binding / Consumption.
- **R03 corrigé et clôturé** : [intégration validée](testing/evidence/Expense_Context_Integration_Report_2026-10-18.md), avec conservation de la preuve rouge historique.
- **Étude de conception historique, subordonnée au Step courant** : [protocole consolidé](architecture/Mutation_Authorities_Ordered_Publication_Consolidated_Study_2026-10-18.md). L’obsolescence par BusinessEvents est abandonnée : [cible temporelle courante et restrictions](architecture/Pot_Command_Versioning_Obsolescence_Design_2026-10-18.md#ag-décision-courante--obsolescence-par-versions-temporelles-18-octobre-2026), **READY_WITH_CONSERVATIVE_RESTRICTIONS** pour l’audit initial. L’[implémentation métier et ses preuves](architecture/Pot_Command_Obsolescence_Implementation_2026-10-18.md) sont désormais livrées ; la [reprise CAS par Consumption (étape 2B)](architecture/Pot_Command_Cas_Consumption_Step_2B_2026-10-18.md) est désormais vérifié dans COMMAND, avec le budget existant de quatre essais. Le séquencement E reste TARGET : contrats 4A clôturés et [runner/collecteur 4B dormants prouvés](architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md) ; réalisation 4C–4E restante selon le [plan courant](steps/current/DISCOVERY/Step_Plan.md).
- [Registre de consolidation Git](testing/evidence/Git_Consumption_Consolidation_2026-10-18.md), pour la provenance et les limites historiques des rapports.
