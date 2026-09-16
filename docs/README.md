# Pocoma architecture documentation

Commencer ici, puis lire uniquement les documents canoniques correspondant au chantier en cours. Ne
refaire un audit global du repository que si la documentation est insuffisante, ambiguë ou doit être
vérifiée avant une modification.

## Start here

### Write side

- [Clôture du write side](architecture/write-side-closure.md) — voie canonique de mutation et legacy Command retiré.
- [Runtime de consommation Command](architecture/command-consumption-runtime.md) — composition, polling et exploitation du Command worker.
- [Exécution transactionnelle](architecture/consumption-transactional-execution.md) — frontière transactionnelle, fencing et failure handling génériques.

### Read side

- [État actuel du read side](architecture/read-side-current-state.md) — description factuelle de l'existant : GET, sources SQL, temporalité et Balance.
- [Architecture canonique du Read side et des projections](architecture/read-side-target.md) — cible normative root/artifacts/result, contrats partagés, ports universels et plan de refonte challengeable.
- [Reconstruction historique d'un Pot](architecture/pot-historical-reconstruction.md) — temporalité primaire exacte, fragments reconstructibles et limite `updatedAt`.
- [Plan directeur du Lot 7](plans/lot-7-read-side-implementation-plan.md) — séquencement de réalisation, subordonné à la cible normative.
- [Plan détaillé du Lot 7.9.1](plans/lot-7.9.1-versioned-query-contracts-plan.md) — contrats framework-free monoprojection, génération serving, état terminal et enveloppe versionnée.
- [Plan de révision monoprojection du Lot 7.9.1](plans/lot-7.9.1-monoprojection-contract-revision-plan.md) — justification, migration et critères de la révision livrée.
- [Design du Lot 7.9.2](plans/lot-7.9.2-query-version-resolution-design.md) — resolver monoprojection CURRENT/EXACT, résultats typés et absence de fallback.
- [Design du Lot 7.14.1](plans/lot-7.14.1-pipeline-version-lifecycle-design.md) — lifecycle minimal declared/active/serving, frontière Claim et control store autoritatif.
- [Plan d'implémentation du Lot 7.14.1](plans/lot-7.14.1-pipeline-version-lifecycle-implementation-plan.md) — réalisation livrée, tests de concurrence et intégration Query Kernel.
- [LatestKnownVersion](plans/lot-7.4-source-version-watermark-plan.md) — dossier de réalisation du consumer direct ; les noms watermark qui y subsistent sont de compatibilité.
- [Plan détaillé du Lot 7.7](plans/lot-7.7-pot-version-user-indexes-and-keyset-pagination-plan.md) — dossier de réalisation de la metadata et de l'index shadow. Sa sélection current par égalité à latest-known est superseded : l'index cible ne fournit que des candidats, ensuite résolus et autorisés à une businessVersion commune.

### Consumption pipelines

- [Event pull runtime](architecture/consumption-event-pull-runtime.md) — Event durable vers matérialisation de Tasks.
- [Task Balance runtime](architecture/consumption-task-balance-runtime.md) — Task durable vers projection Balance immuable.
- [Cutover LatestKnownVersion](operations/latest-known-version-cutover.md) — préflight read-only et bascule sans slots terminaux artificiels.

### Structural references

- [Matrice des dépendances](architecture/module-dependency-matrix.md) — responsabilités et directions de dépendance des modules.
- [Ownership des types](architecture/type-ownership.md) — propriétaire canonique des principaux contrats et types.
- [Familles de use cases](use-case-families.md) — inventaire transversal des familles fonctionnelles.

### Development

- [Continuous integration](development/ci.md) — workflow GitHub permanent, validation Maven complète et protection de branche.

### Command persistence and intake

- [Admission des Recorded Commands](architecture/recorded-command-intake.md) — frontière HTTP/authentification et sémantique `202 Accepted`.
- [Persistence des Recorded Commands](architecture/recorded-command-persistence.md) — schéma durable, immutabilité et discovery.

## Historical material

Les documents datés, [projection-workers.md](projection-workers.md), les plans détaillés clôturés et
les runbooks de cutover décrivent des décisions de réalisation antérieures ou des chemins
transitionnels. Lorsqu'un dossier de réalisation conserve un vocabulaire ou un comportement shadow
superseded, la cible normative et le plan directeur courant prévalent.
