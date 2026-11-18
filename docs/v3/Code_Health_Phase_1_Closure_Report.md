# POCOMA V3 — Clôture et mise en pause du chantier Code Health (Phase 1)

**Statut** : `CLÔTURÉ — CHANTIER EN PAUSE`  
**Branche source** : `refactor/codescene-code-health-10`  
**HEAD technique de référence (avant clôture documentaire)** : `cb3c8e1f`  
**Branche cible** : `main` (base `8d63ccfb`)

---

## 1. Décision de clôture

La première campagne d'amélioration de la lisibilité et de la structure du code Pocoma V3 est clôturée et intégrée dans `main`. Les lots restants (**B2**, **B3**, **C1**) sont explicitement **mis en pause** et consignés dans le registre ci-dessous afin de préserver la traçabilité sans engager de nouveau refactoring fonctionnel ou structurel à ce stade.

Les principes de lisibilité, d'organisation et de conception retenus à l'issue de cette phase sont pérennisés dans [`AGENTS.md`](../../AGENTS.md#code-quality-and-discoverability) pour s'appliquer à toute implémentation future.

---

## 2. Travaux réalisés

| Lot | Objet | Références Git | Résumé des changements |
| :--- | :--- | :--- | :--- |
| **Stabilisation CodeScene** | Assainissement transversal de lisibilité | `f179e7f4` (`refactor(codescene): stabilize code health improvements`) | Réduction de la complexité accidentelle et amélioration de la lisibilité sur les modules Java et scripts Python/JS, sans modification des contrats métier ni des frontières architecturales. |
| **A1** | Restructuration de `infra-postgres-core` | `3c8ced76` (`refactor(structure): organize postgres core packages`) | Organisation de `com.kartaguez.pocoma.infra.postgres.core` en sous-packages par responsabilité (`bootstrap/`, `consumption/`, `history/`) et mise à jour des tests d'architecture (`PotExecutionArchitectureTest`) et consommateurs. |
| **A2** | Restructuration de `infra-postgres-write` | `a7d35d93` (`refactor(structure): organize postgres write packages`) | Organisation de `com.kartaguez.pocoma.infra.postgres.write` en sous-packages (`admission/`, `pot/`, en complément de `identity/`) et mise à jour des appelants et tests. |
| **A3** | Restructuration de `domain-pot` | `e3dad7e0` (`refactor(structure): organize pot domain packages`) | Organisation de `com.kartaguez.pocoma.domain.pot` en sous-packages métier (`expense/`, `invitation/`, `revision/`, `shareholder/`, `value/`) et ajout de `HexagonalArchitectureTest` avec ses fixtures. |
| **B1** | Clarification des compositions Spring et des paramètres de pools | `42b2d640` (`refactor(compose): extract polling resources`) · `cb3c8e1f` (`refactor(compose): separate polling pool settings`) | Extraction de `ProcessResources` / `MaterializeResources` hors des classes `*ComposeConfiguration` et séparation de `ProcessPoolSettings` / `MaterializePoolSettings` hors de `ProcessSettings` / `MaterializeSettings`. |

---

## 3. Qualifications effectuées et limites

- **Périmètre des changements techniques (`f179e7f4`..`cb3c8e1f`)** : refactoring structurel et de lisibilité uniquement ; aucun changement de contrat fonctionnel, d'API HTTP ou de schéma SQL.
- **Qualification CI** : le workflow [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) (`Pocoma CI`) ne se déclenchant pas sur les branches de travail sans Pull Request, aucune exécution CI n'était encore rattachée à `refactor/codescene-code-health-10` avant l'ouverture de la PR d'intégration vers `main`. La qualification complète (`core-web-and-architecture` incluant `architecture-tests` et `git diff --check`, ainsi que la matrice `heavy-modules`) est donc portée par la PR vers `main`.
- **Clôture documentaire** : les ajouts dans `AGENTS.md`, `docs/v3/Code_Health_Phase_1_Closure_Report.md` et `docs/internal/README.md` ne modifient ni code de production ni tests sous `app/`. La vérification locale porte sur l'intégrité des liens/chemins et `git diff --check`.
- **Limite** : les métriques CodeScene ayant guidé l'identification initiale des lots ne sont pas archivées comme seuils normatifs dans le dépôt ; elles servent d'indicateurs exploratoires et non d'autorité architecturale.

---

## 4. Registre des travaux différés (en pause)

> [!NOTE]
> Les sujets ci-dessous sont **différés**, ni abandonnés ni automatiquement approuvés pour implémentation. Ils ne constituent pas des obstacles à la mise en production ou à l'exploitation actuelle de Pocoma V3.

### Vérification de nomenclature (C2)

Le cadrage précédent mentionne les lots **B2**, **B3** et **C1**. Une recherche dans l'ensemble de la documentation et de l'historique Git du dépôt confirme qu'**aucun lot `C2` n'existe** dans les documents de référence du chantier Code Health (les seules occurrences de `C2` dans `docs/` proviennent de l'historique de modularisation L7, sans lien avec cette campagne). Aucune définition n'est donc inventée.

---

### B2 — Extraction potentielle dans `ConsumptionEngine`

- **Cible** : `app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/ConsumptionEngine.java` (1 079 lignes).
- **1. Problème de lisibilité ou de responsabilité identifié** : la classe concentre l'orchestration du cycle de vie d'exécution (`ConsumptionEngine`), le handle de capacité locale (`AcquiredClaim`, ~500 lignes) et plusieurs adaptateurs internes (`ObservePhaseState`, `Reads`, `Completion`).
- **2. Solution envisagée** : extraire `AcquiredClaim` et/ou les adaptateurs internes cohérents en classes dédiées dans le même package `com.kartaguez.pocoma.orchestrator.consumption` en préservant la visibilité minimale.
- **3. Bénéfice attendu** : lecture plus directe de l'orchestrateur principal et séparation nette entre la mécanique de réservation locale (`WorkerCapacity` / `AcquiredClaim`) et le protocole d'exécution d'une tentative.
- **4. Risques et invariants à protéger** :
  - `ConsumptionEngine` et `WorkerCapacity` partagent des invariants stricts sur la conservation des tickets et l'exclusion locale (`unresolvedRecovery`, `unresolvedPreparation`).
  - `ConsumptionEngineArchitectureTest` (dans `app/architecture-tests`) référence explicitement `ConsumptionEngine` et `ConsumptionEngine.AcquiredClaim`.
- **5. Raison du report** : périmètre critique de concurrence et de fencing ; le coût de vérification (tests d'architecture et campagnes Postgres workers) est disproportionné par rapport au gain immédiat, le code actuel restant fonctionnellement sain et testé.
- **6. Conditions concrètes de réouverture** : évolution fonctionnelle majeure du moteur de consommation ou besoin avéré d'isoler les tests unitaires de `AcquiredClaim` indépendamment de `ConsumptionEngine`.

---

### B3 — Extraction potentielle dans `ConsumptionWorker`

- **Cible** : `app/supra-polling-consumption/src/main/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionWorker.java` (881 lignes).
- **1. Problème de lisibilité ou de responsabilité identifié** : le fichier regroupe la façade `WorkerView` et les records de configuration (`Binding`, `Settings`), le suivi des baux (`LeaseEntry`, `Phase`), l'enveloppe d'exécution (`Envelope`, ~588 lignes) et la planification de boucle (`LoopSchedule`).
- **2. Solution envisagée** : extraire `Envelope` et/ou `LoopSchedule` / `LeaseEntry` en composants package-private distincts sous `com.kartaguez.pocoma.supra.polling.consumption`.
- **3. Bénéfice attendu** : allègement de la classe d'entrée `ConsumptionWorker` et meilleure lisibilité de la boucle d'ordonnancement et de renouvellement des baux.
- **4. Risques et invariants à protéger** :
  - Coordination fine entre les threads d'exécution (`Envelope`), la gestion du drain (`drainDeadline`), le renouvellement de baux (`renewalInterval` / `renewalBudget`) et l'état partagé des `LeaseEntry`.
  - Références directes à `ConsumptionWorker` et `ConsumptionWorker.Settings` dans `QualificationSettings` et `ConsumptionWorkerTest`.
- **5. Raison du report** : risque de régression sur la synchronisation et le cycle de vie d'arrêt (shutdown/drain) sans bénéfice fonctionnel immédiat.
- **6. Conditions concrètes de réouverture** : modification substantielle de la politique d'ordonnancement, du mécanisme de drain ou de la gestion multi-partitions dans `supra-polling-consumption`.

---

### C1 — Déplacement potentiel de `ReadinessOracle`

- **Cible** : `app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/maintenance/ReadinessOracle.java` (399 lignes).
- **1. Problème de lisibilité ou de responsabilité identifié** : `ReadinessOracle` est placé dans `src/main/java` du module `compose-polling-nonburnable-materialize-postgres`, alors que ses seuls appelants dans `app/` sont des classes de test (`ReadinessOracleTest` dans ce même module et `ReadReconstructionPostgresTest` dans `runtime-worker-materialize`).
- **2. Solution envisagée** : déplacer `ReadinessOracle` vers un emplacement de test ou un module de support de test/qualification partagé, ou clarifier son statut de composant de maintenance/test.
- **3. Bénéfice attendu** : éviter d'exposer dans le code de production d'un module `compose-*` un oracle utilisé uniquement par les tests de reconstruction/readiness.
- **4. Risques et invariants à protéger** :
  - `runtime-worker-materialize` (`ReadReconstructionPostgresTest`) dépend aujourd'hui de la visibilité de `ReadinessOracle` via `compose-polling-nonburnable-materialize-postgres` ; un déplacement naïf vers `src/test/java` casserait la compilation des tests de `runtime-worker-materialize` sans artefact `test-jar` ou module dédié.
  - Les règles de graphe de modules vérifiées par `L7ReactorArchitectureTest` doivent rester intactes.
- **5. Raison du report** : un déplacement propre implique soit une évolution des dépendances Maven (`test-jar` ou module de support), soit un ajustement de frontières entre modules hors du périmètre de simple lisibilité de la phase 1.
- **6. Conditions concrètes de réouverture** : évolution de l'outillage de reconstruction/readiness ou révision du découpage des fixtures de test entre `compose-polling-nonburnable-materialize-postgres` et `runtime-worker-materialize`.
