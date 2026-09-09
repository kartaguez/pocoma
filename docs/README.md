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
- [Architecture cible du read side](architecture/read-side-target.md) — cible normative du Lot 7 : modèle temporel, projections, queries et invariants.
- [Reconstruction historique d'un Pot](architecture/pot-historical-reconstruction.md) — temporalité primaire exacte, fragments reconstructibles et limite `updatedAt`.
- [Plan directeur du Lot 7](plans/lot-7-read-side-implementation-plan.md) — séquencement de réalisation, subordonné à la cible normative.

### Consumption pipelines

- [Event pull runtime](architecture/consumption-event-pull-runtime.md) — Event durable vers matérialisation de Tasks.
- [Task Balance runtime](architecture/consumption-task-balance-runtime.md) — Task durable vers projection Balance immuable.

### Structural references

- [Matrice des dépendances](architecture/module-dependency-matrix.md) — responsabilités et directions de dépendance des modules.
- [Ownership des types](architecture/type-ownership.md) — propriétaire canonique des principaux contrats et types.
- [Familles de use cases](use-case-families.md) — inventaire transversal des familles fonctionnelles.

### Command persistence and intake

- [Admission des Recorded Commands](architecture/recorded-command-intake.md) — frontière HTTP/authentification et sémantique `202 Accepted`.
- [Persistence des Recorded Commands](architecture/recorded-command-persistence.md) — schéma durable, immutabilité et discovery.

## Historical material

Les documents datés, [projection-workers.md](projection-workers.md), les plans clôturés ou non
référencés ci-dessus et les runbooks de cutover décrivent des étapes antérieures ou des chemins
transitionnels. Les consulter pour l'historique ou l'exploitation correspondante, jamais comme
remplacement des documents canoniques ci-dessus.
