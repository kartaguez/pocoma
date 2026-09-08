# Lot 7.3 — Generic projection foundation

## 1. Objectif

Introduire la fondation fonctionnelle et persistante commune aux futures projections Pot et Balance,
sur la frontière read store du Lot 7.2. Le lot établit l'identité, la couverture continue, le statut
dérivé, les artifacts immuables, les failures terminales, le head monotone et l'atomicité de
matérialisation, sans créer de projection métier concrète.

## 2. Sources canoniques

- `docs/architecture/read-side-target.md`
- `docs/architecture/read-side-current-state.md`
- `docs/plans/lot-7-read-side-implementation-plan.md`
- `docs/plans/lot-7.2-read-store-boundary-plan.md`
- `docs/architecture/module-dependency-matrix.md`

## 3. Périmètre

Le Lot 7.3 crée un domaine projection framework-free, un engine read projection et les adapters SQL
dans `infra-read-persistence`. Il ne câble aucun runtime et ne crée ni watermark, Task producer,
projector Pot, artifact Balance, query HTTP ou index métier.

## 4. Invariants

- L'identité complète est `projectionType + pipelineId + pipelineVersion + potId + potVersion`.
- La couverture d'une génération/Pot est une plage finie, inclusive, continue et sans exception.
- `READY`, `FAILED` et `NOT_READY` sont dérivés ; aucune table de state n'est créée.
- Hors couverture est `NOT_EXPECTED`, distinct du statut fonctionnel.
- Artifact et failure sont mutuellement exclusifs ; la première issue terminale commitée gagne.
- Un artifact est immuable. Un duplicate divergent ne l'écrase pas et ne produit pas `FAILED`.
- Le head progresse par maximum, accepte les trous et ne régresse jamais.
- L'atomicité permanente est limitée au read store.

## 5. État technique utilisé

Le Lot 7.2 fournit `infra-read-persistence`, le schéma `pocoma_read`, son cycle Flyway autonome, un
`JdbcOperations` qualifié et une intention transactionnelle qualifiée. Le Lot 7.3 les étend ; il ne
crée ni datasource, transaction manager ou module de persistence supplémentaire.

## 6. Décisions de conception

- Créer `domain-projection` pour les concepts fonctionnels.
- Créer `engine-read-projection` pour les ports et services de résolution/matérialisation.
- Utiliser une couverture finie explicite `[fromVersion..throughVersion]`. Une borne ouverte créerait
  un couplage inutile au futur watermark.
- Ne pas créer de blob générique. Chaque projection concrète possédera ses tables/fragments.
- Conserver un descriptor générique minimal pour l'identité, l'idempotence et le diagnostic.
- Sérialiser les issues terminales par verrou transactionnel PostgreSQL sur l'identité complète,
  sans table de state cachée servant de mutex.

## 7. Ownership

`domain-projection` possède identité, coverage, status, descriptors, failure, head et violation.
`engine-read-projection` possède les ports framework-free et les services transactionnels.
`infra-read-persistence` possède SQL, migrations, verrouillage, upserts et adaptation Spring.
Les Tasks futures possèdent le scheduling, jamais la couverture ni le statut fonctionnel.

## 8. Modèle fonctionnel

`ProjectionGenerationIdentity` omet `potVersion`; `ProjectionIdentity` l'ajoute.
`ProjectionCoverage` valide `fromVersion >= 1` et `throughVersion >= fromVersion`, et expose seulement
des extensions monotones vers le passé ou le futur. Pour une identité couverte : artifact donne
`READY`, failure donne `FAILED`, absence des deux donne `NOT_READY`.

## 9. Persistence read-side

- `projection_coverages`: PK génération/Pot, `from_version`, `through_version`, checks de validité.
- `projection_artifacts`: descriptor par identité complète, UUID, digest et date, identité unique.
- `projection_failures`: identité complète, date et code terminal.
- `projection_heads`: PK génération/Pot, plus haute version et date d'avancée.
- `projection_invariant_violations`: divergence duplicate durable et diagnostiquable.

Les artifacts concrets restent hors de ce registre et seront ajoutés par les projections métier.

## 10. Concurrence et idempotence

Les extensions de couverture utilisent `least`/`greatest`. Une matérialisation ou failure acquiert un
verrou transactionnel déterministe par identité. Après le verrou, le service relit artifact et failure.
Deux succès identiques donnent `Created` puis `AlreadySatisfied`; deux contenus divergents conservent
le premier et enregistrent une violation. Success/failure est first-commit-wins. Le head utilise un
upsert conditionnel qui ignore toute version inférieure.

## 11. Atomicité

Le service de matérialisation exécute dans une transaction read-store : écriture de l'artifact concret,
descriptor, indexes indispensables futurs et avance du head. Toute exception annule l'ensemble. La
coverage n'est pas modifiée à chaque matérialisation. La coordination Task reste hors contrat.

## 12. Séquence d'implémentation

### Étape 1 — Modèle fonctionnel

Créer les deux identités, coverage, status, artifact descriptor, failure, head et violation dans
`domain-projection`. Tester bornes, égalité et extensions. Rollback : retirer le module neuf.

### Étape 2 — Ports et services

Créer le port de métadonnées, le runner transactionnel, le writer d'artifact concret, le resolver et
les services success/failure dans `engine-read-projection`. Critère : aucune dépendance framework ou
Task. Rollback : retirer le module neuf.

### Étape 3 — Schéma générique

Ajouter une migration au cycle read store avec les cinq tables minimales et leurs contraintes.
Critère : installation autonome sur PostgreSQL vide. Rollback avant livraison : retirer la migration.

### Étape 4 — Adapters

Implémenter les ports via JDBC et l'intention transactionnelle du Lot 7.2. Critère : concurrence,
rollback, idempotence et head hors ordre passent sur PostgreSQL réel.

### Étape 5 — Architecture et documentation

Verrouiller les directions de dépendance et aligner la cible sur coverage + statut dérivé. Critère :
aucun runtime, Balance ou GET modifié.

## 13. Fichiers et modules concernés

- `app/pom.xml`
- `app/domain-projection/**`
- `app/engine-read-projection/**`
- `app/infra-read-persistence/**`
- `app/architecture-tests/**`
- documentation d'architecture et matrice de dépendances

## 14. Tests

- Modèle : bornes, continuité et extensions monotones.
- Résolution : hors coverage, `NOT_READY`, `READY`, `FAILED`.
- PostgreSQL : création/extension concurrente, unique constraints, advisory lock, rollback.
- Matérialisation : duplicate identique/divergent et artifact inchangé.
- Exclusivité : success/failure dans les deux ordres, jamais artifact + failure.
- Head : `44 -> 46 -> 45` donne 46.
- Architecture : domaine/engine sans Spring/JDBC, infra read sans persistence primaire.

## 15. Critères de sortie

Le modèle à cinq dimensions est imposé, coverage n'a aucune ligne par version, le statut est dérivé,
les issues terminales sont exclusives, le head ne régresse pas et l'atomicité est testée. Aucun runtime
n'est câblé, Balance reste inchangée et aucun accès primaire n'est introduit.

## 16. Rollback

Avant déploiement, les nouveaux modules et la migration peuvent être retirés ensemble car aucun
runtime ne les consomme. Après application de la migration, le rollback opérationnel consiste à ne
pas activer de consommateur et à appliquer la politique de migration habituelle ; aucune table legacy
n'est touchée.

## 17. Risques

- Une coverage trop large déclare attendues des versions non voulues.
- Coverage et scheduling pourraient être confondus dans les lots suivants.
- Une mauvaise clé de verrou pourrait rompre l'exclusivité terminale.
- Le digest ne doit pas remplacer la comparaison sémantique autoritative.
- Un artifact concret incomplet rendrait `READY` dérivable prématurément.

## 18. Hors scope

Watermark, Tasks, retries, réparation des failures, backfill, observabilité avancée, PotProjection,
Balance, indexes métier, readers, HTTP, activation de pipeline et GC.

## 19. Questions différées

La politique de réparation d'une failure, le contrat HTTP hors coverage, l'orchestration du backfill,
les métriques avancées et le calcul de digest propre à chaque artifact restent à définir dans leurs lots.

## 20. Targeted code inspections

| Question | Fichiers consultés | Conclusion utile |
|---|---|---|
| Quelle identité de pipeline réutiliser ? | `domain-pipeline/PipelineDefinition`, `PipelineId` | Réutiliser type + version sans identité implicite. |
| Quelle frontière transactionnelle consommer ? | auto-configuration et qualifiers de `infra-read-persistence` | Adapter `TransactionOperations` derrière un port framework-free. |
| Où ajouter les migrations ? | ressources Flyway de `infra-read-persistence` | Étendre le cycle autonome existant avec V2. |
| Comment protéger success/failure sans state ? | capacités PostgreSQL et conventions JDBC du module | Verrou advisory transactionnel par identité, puis relecture et insert exclusif. |
| Balance doit-elle être modifiée ? | plan directeur et matrice des modules | Non ; son alignement appartient à un lot ultérieur. |
