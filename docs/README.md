# Documentation Pocoma

## Programme V3 courant

- [Plan directeur L0–L8](v3/V3_Implementation_Master_Plan.md) : point d’entrée opérationnel unique ; L0/L1 ACCEPTED dans leur portée interne ; L2 ACCEPTED WITH EXPLICIT RESERVATIONS ; L3 en réalisation, I1 ACCEPTED WITH EXPLICIT RESERVATIONS et GO I2 limité à l'autorité PostgreSQL Identity ; L4–L8 DESIGN PENDING.
- [Arbitrage L3/I1 et GO I2](v3/L3_I1_Arbitration_and_I2_GO.md) : 35 nouveaux tests PASS, gate COMMAND + Architecture FAIL (`i5_r05`, `i5_r12`), aucun I1 LOCAL BOUNDED PASS ; réserves et gates ultérieures conservées.
- [Canon et SPEC](v3/V3_Canon_and_Roadmap.md) : décisions normatives V3.
- [Clôture officielle L2](v3/L2_Documentary_Closure.md) : décision humaine, statuts I1–I7, réserves R-L2-01 à R-L2-05, SHAs testés et transition L3. G1/G2 INCONCLUSIVE, G3 NOT PASSED / NOT COMPLETED ; aucun PASS technique global ni GO production. [Plan L2](v3/L2_Design_and_Verification_Plan.md), [dossier historique I6](v3/L2_I6_I7_Consolidation.md) et [récupération des preuves](testing/Evidence_Recovery.md). La progression L3 courante est portée par le plan directeur et l'arbitrage I1 ci-dessus.
- [Clôture L1](v3/L1_Documentary_Closure.md), [plan L1](v3/L1_Design_and_Verification_Plan.md), [rapport final](v3/L1_E_Execution_Report.md) et [manifeste historique](v3/L1_Proof_Manifest.json) : preuves et limites, sans certificat V3 complet.

## Architecture historique encore livrée

Les sources et plans ci-dessous décrivent l’architecture historique encore présente ; leurs anciens « courant/NEXT » ne pilotent pas V3. Les stratégies de migration A2/A2-1 sont supplantées pour V3 par le canon. Préserver leurs preuves, sans les assimiler à une certification V3.

Sources de l’état historique livré :

1. [Functional Model](product/Functional_Model.md) — comportement observable.
2. [Architecture](architecture/Architecture.md) — chaînes et modules exécutés.
3. [System Guarantees](guarantees/System_Guarantees.md) — propriétés, preuves et limites.

La [validation croisée](Documentation_Truth_Validation.md) retrace les preuves et leurs limites.

## Chantier historique : séquencement → Discovery → READ

- [Step Canon — current_canon](steps/current/DISCOVERY/Step_Canon.md) : décisions, état livré, cibles et limites.
- [Step Plan — current_plan](steps/current/DISCOVERY/Step_Plan.md) : **4A–4E implémentation fonctionnelle terminée ; Discovery efficiente NEXT**, réserves de certification et preuves de clôture. Activation production NON AUTORISÉE.
- [Next Plan — next_plan](steps/current/DISCOVERY/Next_Plan.md) : dépendances Discovery, modèle READ, matérialisation et évolutions fonctionnelles.
- [Inventaire et contrôle de consolidation](steps/current/DISCOVERY/Consolidation_Report_2026-10-20.md).

Conventions : `steps/current/<STEP>/Step_Canon.md`, `Step_Plan.md`, `Next_Plan.md`.
Canon : décisions prises/révisées ; plan courant : avancement ; plan suivant : dépendances/priorités.
À la relève, consolider l'acquis dans le canon et les synthèses CURRENT, archiver le Step achevé,
promouvoir le prochain plan. [Gouvernance](Documentation_Rationalization_Audit.md#15-gouvernance-documentaire).

## Architecture finale, dettes et historique

- **CURRENT = TARGET ACHIEVED** : l'[Architecture](architecture/Architecture.md) reste la description de référence historique ; la [topologie finale](steps/completed/ARCHITECTURE/Modularity_Target_Topology.md), la [traçabilité](steps/completed/ARCHITECTURE/Modularity_Current_Target_Traceability.md) et le [re-audit final PASS](steps/completed/ARCHITECTURE/Modularity_Final_Reaudit.md) en conservent la preuve historique.
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
- **Étude de conception historique, subordonnée au Step courant** : [protocole consolidé](architecture/Mutation_Authorities_Ordered_Publication_Consolidated_Study_2026-10-18.md). L’obsolescence par BusinessEvents est abandonnée : [cible temporelle courante et restrictions](architecture/Pot_Command_Versioning_Obsolescence_Design_2026-10-18.md#ag-décision-courante--obsolescence-par-versions-temporelles-18-octobre-2026), **READY_WITH_CONSERVATIVE_RESTRICTIONS** pour l’audit initial. L’[implémentation métier et ses preuves](architecture/Pot_Command_Obsolescence_Implementation_2026-10-18.md) sont désormais livrées ; la [reprise CAS par Consumption (étape 2B)](architecture/Pot_Command_Cas_Consumption_Step_2B_2026-10-18.md) est désormais vérifié dans COMMAND, avec le budget existant de quatre essais. Le séquencement E en production reste TARGET : contrats 4A clôturés, [runner/collecteur 4B dormants prouvés](architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md) et [schéma PREPARED/séquenceur 4C dormants prouvés](architecture/Event_Transactional_Sequencer_Step_4C_2026-10-20.md) ; raccordement 4D et capacités 4E désormais intégrés et clôturés fonctionnellement hors production selon le [plan courant](steps/current/DISCOVERY/Step_Plan.md). La certification de production reste NOT CERTIFIED, avec réserves R1–R6 et performance après raccordement E2E.
- [Registre de consolidation Git](testing/evidence/Git_Consumption_Consolidation_2026-10-18.md), pour la provenance et les limites historiques des rapports.
