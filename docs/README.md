# Documentation Pocoma

Sources **CURRENT** de l’état livré :

1. [Functional Model](product/Functional_Model.md) — comportement observable.
2. [Architecture](architecture/Architecture.md) — chaînes et modules exécutés.
3. [System Guarantees](guarantees/System_Guarantees.md) — propriétés, preuves et limites.

La [validation croisée](Documentation_Truth_Validation.md) retrace les preuves et leurs limites.

## Cibles et historique

- **TARGET / travail actif** : [révision des trois familles de moteurs](steps/current/ARCHITECTURE/Three_Engine_Families_Revision.md). Ses tableaux historiques 47→47 ne sont pas exécutables sur les 51 modules et neuf runtimes actuels.
- **TARGET / références spécialisées** : [architecture READ cible](architecture/read-side-target.md) et [contrats du kernel d’autorisation](architecture/authorization-kernel-contracts.md) ; leur portée est indiquée dans chaque document.
- **HISTORICAL-COMPLETED** : [Steps livrés](steps/completed/) et [plans exécutés](plans/completed/).
- **SUPERSEDED** : [archives](archive/). La [matrice de classement](Documentation_Rationalization_Audit.md#8-audit-exhaustif-de-docsplans) conserve la provenance des 43 plans.

## Ingénierie et références

- [Politique de vérification](testing/Reactor_Verification_Policy.md) et [CI](development/ci.md).
- [Exploitation](operations/cmd-start-runtimes.md) et SQL associé dans `operations/sql/`.
- Détails : [transactions Consumption](architecture/consumption-transactional-execution.md), [exécution ProjectionTask](architecture/projection-task-execution.md), [reconstruction historique Pot](architecture/pot-historical-reconstruction.md).
