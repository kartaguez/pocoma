# Documentation Pocoma

Sources transversales de l'état **CURRENT**, vérifiées contre le HEAD `c14751f` :

1. [Functional Model](product/Functional_Model.md) — comportement et parcours observables.
2. [Architecture](architecture/Architecture.md) — chaînes et modules exécutés.
3. [System Guarantees](guarantees/System_Guarantees.md) — propriétés, preuves et limites.

La [matrice DOC.5](Documentation_Truth_Validation.md) trace leur validation croisée. Une proposition **TARGET** dans un Step actif ne décrit pas le système livré.

## Travaux et historique

- **TARGET actif** : [refonte des familles de moteurs](steps/current/ARCHITECTURE/Three_Engine_Families_Revision.md).
- **Steps livrés, historiques** : [REGISTRATION](steps/completed/REGISTRATION/Step_Plan.md), [POT_E2E](steps/completed/POT_E2E/Step_Plan.md), [ARCHITECTURE R1/R2](steps/completed/ARCHITECTURE/Read_Materialization_Gap_and_Migration_Plan.md), [WRITE_ADMISSION](steps/completed/WRITE_ADMISSION/Step_Plan.md), [CCR](steps/completed/CCR/Step_Plan.md), [EPT](steps/completed/EPT/Step_Plan.md), [PCL](steps/completed/PCL/Step_Plan.md).
- **Plans historiques exécutés** : [classification figée](Documentation_Rationalization_Audit.md#8-audit-exhaustif-de-docsplans) ; leur déplacement vers `plans/completed/` est une vague ultérieure.
- **Archives et hypothèses supplantées** : les anciens audits portent un bandeau historique ; voir [l'audit de rationalisation](Documentation_Rationalization_Audit.md). Le reclassement massif des plans attend la vague suivante.

## Références spécialisées et exploitation

- [Transaction Consumption](architecture/consumption-transactional-execution.md), [exécution ProjectionTask](architecture/projection-task-execution.md), [reconstruction historique Pot](architecture/pot-historical-reconstruction.md).
- [Politique de vérification](testing/Reactor_Verification_Policy.md), [CI](development/ci.md).
- [Runbooks](operations/cmd-start-runtimes.md) et SQL associé sous `operations/sql/`.
