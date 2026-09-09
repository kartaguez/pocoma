# Lot 7.7 — Indexes Pot/Expense et décision de routage

## 1. Scope

Ce lot ferme les prérequis des futures listes de Pots et du routage d'une Expense, sans activer de nouveau GET. Il couvre :

- la metadata primaire immuable `PotVersionMetadata(potId, version, createdAt)` ;
- sa propagation exacte sous le nom read-side `updatedAt` ;
- un index historisé `userId -> PotProjection@V`, scopé par génération ;
- le registre durable `expenseId -> potId` et sa copie dans le read store ;
- le tri `updatedAt DESC, potId ASC` et la pagination keyset ;
- atomicité, idempotence, concurrence, reconstructibilité, observabilité, tests et CI.

**CANONICAL INVARIANT —** le lot complète la production shadow de `read-pot/v1`. Aucun GET actif ne bascule.

## 2. Non-scope

Sont hors périmètre : moteur administratif 7.8, Query Kernel/HTTP 7.9, autorisation 7.10, cutover et suppression legacy 7.11, activation d'une génération reader, timeline de routage Expense, état fonctionnel `current`, indexes Balance, et nouveau moteur de projection.

## 3. Documentation consultée

**CONFIRMED BY DOCUMENTATION —** ont été lus avant le code :

- `docs/README.md` ;
- `docs/architecture/read-side-target.md` ;
- `docs/architecture/read-side-current-state.md` ;
- `docs/architecture/pot-historical-reconstruction.md` ;
- `docs/architecture/write-side-closure.md` ;
- `docs/architecture/module-dependency-matrix.md` ;
- `docs/plans/lot-7-read-side-implementation-plan.md` ;
- `docs/plans/lot-7.6-canonical-pot-projection-plan.md` ;
- `docs/plans/lot-7.5-applicable-projection-task-scheduling-plan.md` ;
- `docs/development/ci.md`.

**CONFIRMED BY DOCUMENTATION —** le Lot 7.6 publie `read-pot/v1`, reconstruit un snapshot exact et autonome, et exclut `updatedAt` de son digest faute de source durable. Certaines formulations anciennes de `read-side-target.md` et du plan directeur parlent encore d'un index « current » ou laissent Option A/B ouverte : l'implémentation 7.7 devra les aligner sur les invariants désormais fermés.

## 4. Code inspecté

Inspection ciblée uniquement : DDL/migrations primaires de `runtime-monolith`, `JpaPotGlobalVersionEntity`/repository/adapter, `PotGlobalVersion`/port, les dix use cases allouant une version, wrapper transactionnel des commandes, `ExpenseId`, factory/commande/service de création Expense, entités/repositories Expense, migrations V1–V5 du read store, `JdbcPotProjectionArtifactWriter`, `ProjectionMaterializationService`, transaction runner, watermark JDBC/consumer, liste legacy des Pots, `ListUserPotsService` et `.github/workflows/ci.yml`.

**CONFIRMED BY TARGETED CODE INSPECTION —** aucun scan global du repository n'a été nécessaire.

## 5. État actuel vérifié

**CONFIRMED BY TARGETED CODE INSPECTION —** `pot_global_versions` contient une seule ligne mutable par Pot. Il ne conserve ni ligne par version ni timestamp historique.

**CONFIRMED BY TARGETED CODE INSPECTION —** toutes les mutations Pot/Shareholder/Expense observées allouent leur version via `PotGlobalVersionPort`. CAS de version, écritures primaires, Events, provenance et CAS terminal rejoignent déjà la transaction locale de commande.

**CONFIRMED BY TARGETED CODE INSPECTION —** le timestamp commun donné aux Events ne matérialise pas la fonction `(potId, potVersion) -> createdAt` : plusieurs Events peuvent correspondre à une version et aucune contrainte n'en fait la source.

**CONFIRMED BY TARGETED CODE INSPECTION —** `ExpenseId` est un UUID généré côté serveur ; le client ne le fournit pas. Les lignes temporelles de `expense_headers` réutilisent cet ID, mais aucune contrainte SQL globale ne garantit aujourd'hui un unique `pot_id` pour toutes ces lignes.

**CONFIRMED BY TARGETED CODE INSPECTION —** la dernière migration PotProjection read-store est V5. Le materializer écrit déjà fragments, artifact et head dans une transaction commune. `source_version_watermarks` fournit `latestVersionSeen` par Pot.

## 6. Canonical invariants

- **CANONICAL INVARIANT —** `(potId, potVersion)` détermine exactement un `createdAt`, fixé dans la transaction qui crée la version.
- **CANONICAL INVARIANT —** aucun timestamp de projection, Task, worker ou Event agrégé ne le remplace.
- **CANONICAL INVARIANT —** aucun backfill legacy approximatif.
- **CANONICAL INVARIANT —** aucune vérité fonctionnelle `current` dans le read store.
- **CANONICAL INVARIANT —** les indexes dérivés sont versionnés, immuables et scopés par `pipelineId/pipelineVersion`.
- **CANONICAL INVARIANT —** `PipelineSelectionStrategy` intervient uniquement à la lecture.
- **CANONICAL INVARIANT —** ordre total `updatedAt DESC, potId ASC`.
- **CANONICAL INVARIANT —** `expenseId -> exactly one potId` est durable, immuable, indépendant du statut et conservé après delete.
- **CANONICAL INVARIANT —** Option A : après établissement des préconditions, routing absent signifie `NOT_FOUND`.
- **CANONICAL INVARIANT —** une duplication divergente n'écrase jamais l'existant.

## 7. Design de `PotVersion.createdAt`

**IMPLEMENTATION CHOICE —** créer un registre primaire append-only dédié :

```text
PotVersionMetadata
  potId
  version
  createdAt
```

`pot_global_versions` reste le compteur CAS courant. Le nouveau registre conserve une ligne par version.

Faire évoluer le port d'allocation pour rendre indivisibles :

- création Pot : compteur V1 + metadata V1 ;
- mutation : CAS `expected -> next` + metadata `next`.

**IMPLEMENTATION CHOICE —** fixer `createdAt` avec `transaction_timestamp()` PostgreSQL dans l'adapter d'allocation. Il est produit au moment autoritatif, une fois, et jamais recalculé par un projector. Un rollback ne crée aucune version durable.

Ajouter un port intentionnel `load(potId, version)` pour la reconstruction. PK `(pot_id, version)`, absence de méthodes update/delete et protection SQL contre `UPDATE/DELETE` rendent le registre append-only. Collision identique = adoption dans un flux de reprise explicite ; collision divergente = invariant violation.

## 8. Stratégie de migration legacy

**IMPLEMENTATION CHOICE —** ajouter une migration primaire forward-only V11, après revérification du prochain numéro. Elle crée la table vide, détecte toute version Pot déjà présente sans metadata, puis échoue explicitement avec le runbook de reset.

Les bases de développement incompatibles doivent être reset explicitement. Une base durable ne peut avancer que si elle est vide pour cet historique ou si une source exacte est démontrée ultérieurement. Ni Events, ni heure de migration, ni min/max ne sont utilisés.

**BLOCKER —** aucun blocker architectural. La seule précondition opérationnelle est l'inventaire des bases legacy avant déploiement ; une base incompatible est reset ou son déploiement est refusé.

## 9. Propagation de `updatedAt`

`updatedAt` est le nom read-side de `PotVersionMetadata.createdAt`.

**IMPLEMENTATION CHOICE —** ne pas modifier rétroactivement le digest publié de `read-pot/v1`. Le reconstructeur retourne un résultat exact composé de `PotProjection` et de sa `PotVersionMetadata`. Cette metadata fonctionnelle adjacente est stockée/vérifiée avec l'artifact et alimente les indexes.

**CANONICAL INVARIANT —** reconstruction et rebuild relisent la metadata primaire exacte. L'heure de projection n'entre ni dans l'artifact v1 ni dans l'index.

## 10. Modèle logique de l'index `userId -> Pot`

```text
PotUserIndexEntry
  pipelineId
  pipelineVersion
  potId
  potVersion
  userId
  updatedAt
  potStatus ACTIVE|DELETED
  artifactId
```

**IMPLEMENTATION CHOICE —** indexer à V le creator et chaque Shareholder actif possédant un `userId`, avec déduplication. Un Shareholder `deleted=true` n'est plus candidat à cette version mais reste dans PotProjection et dans les indexes de versions antérieures. Un Pot `DELETED` conserve ses entrées à la delete version avec ce statut.

**CANONICAL INVARIANT —** cet index produit des candidats de navigation, pas une ACL. L'autorisation relira PotProjection au Lot 7.10.

## 11. Schéma physique

### Primaire

**IMPLEMENTATION CHOICE —** migration pressentie `V11__pot_version_metadata_and_expense_identity.sql` :

```sql
pot_version_metadata (
  pot_id uuid not null references pot_global_versions(pot_id),
  version bigint not null check (version > 0),
  created_at timestamptz not null,
  primary key (pot_id, version)
)

expense_identities (
  expense_id uuid primary key,
  pot_id uuid not null references pot_global_versions(pot_id)
)
```

### Read store

**IMPLEMENTATION CHOICE —** migration pressentie `V6__pot_indexes_and_expense_routing.sql` :

```sql
pot_version_metadata (
  pot_id uuid not null,
  pot_version bigint not null check (pot_version > 0),
  created_at timestamptz not null,
  primary key (pot_id, pot_version)
)

pot_projection_user_index (
  artifact_id uuid not null references pot_projection_snapshots(artifact_id),
  pipeline_id varchar not null,
  pipeline_version integer not null check (pipeline_version > 0),
  pot_id uuid not null,
  pot_version bigint not null check (pot_version > 0),
  user_id uuid not null,
  updated_at timestamptz not null,
  pot_status varchar not null check (pot_status in ('ACTIVE', 'DELETED')),
  primary key (pipeline_id, pipeline_version, pot_id, pot_version, user_id),
  unique (artifact_id, user_id)
)

expense_routes (
  expense_id uuid primary key,
  pot_id uuid not null
)
```

Indexes utiles : `(user_id, updated_at DESC, pot_id ASC)` incluant identité/version/statut, et `(pipeline_id, pipeline_version, pot_id, pot_version)`. Le FK artifact empêche l'index user d'exister sans sa projection ; le writer vérifie la concordance des champs dénormalisés.

## 12. Scoping par génération

**CANONICAL INVARIANT —** l'index user/Pot est scopé par génération et version. `read-pot/v1@V` et `read-pot/v2@V` coexistent.

**IMPLEMENTATION CHOICE —** metadata Pot et route Expense ne sont pas dupliquées par génération : elles décrivent des identités métier autoritatives communes. Chaque génération les adopte et les vérifie.

## 13. Sémantique immutable/versionnée

Pour `(pipelineId, pipelineVersion, potId, potVersion, userId)` : contenu identique = no-op ; contenu divergent = invariant violation et ligne conservée ; nouvelle version = nouvelles lignes sans suppression.

**CANONICAL INVARIANT —** V5 puis V3 écrit `@V5` puis `@V3`. Aucun mécanisme n'avance ou ne fait régresser un index current inexistant.

## 14. Résolution des listes current

Le futur reader :

1. part des candidats de `userId` ;
2. joint `source_version_watermarks` par `potId` ;
3. retient `index.potVersion = latestVersionSeen(potId)` ;
4. applique la génération sélectionnée pour cette version ;
5. applique actif/archive ;
6. ordonne et pagine.

**IMPLEMENTATION CHOICE —** l'adapter shadow reçoit du futur Query Kernel les plages applicables `(fromVersion, toVersion, pipelineVersion)` et les traduit en prédicats SQL. La stratégie n'entre jamais dans la production.

**CANONICAL INVARIANT —** chaque Pot a son watermark ; aucune version globale multi-Pots n'existe.

## 15. Structure d'accélération éventuelle

**IMPLEMENTATION CHOICE —** aucune table current ou autre accélération n'est créée en 7.7. Index versionné + watermark sont d'abord validés par `EXPLAIN` et tests.

Une accélération future devra être marquée `TECHNICAL ACCELERATION — NOT FUNCTIONAL SOURCE OF TRUTH`, être reconstructible et ne jamais masquer l'absence d'un artifact exact.

## 16. Invariants d'identité Expense

**CONFIRMED BY TARGETED CODE INSPECTION —** les nouveaux IDs sont générés serveur et non fournis par le client.

**IMPLEMENTATION CHOICE —** `expense_identities(expense_id PK, pot_id)` protège l'invariant. La création Expense insère l'identité dans la transaction du header initial et de la Pot version. Aucune API ne réaffecte ou supprime l'identité.

La migration reconstruit exactement les couples distincts depuis `expense_headers`, après un preflight refusant tout `expense_id` associé à plusieurs Pots. Ce backfill est exact, car le Pot est déjà une donnée primaire, contrairement au timestamp legacy.

## 17. Modèle de routage Expense

```text
ExpenseRoute
  expenseId
  potId
```

**CANONICAL INVARIANT —** ni génération, ni timeline, ni statut dans le routage.

La future lecture suit `expenseId -> potId -> latestVersionSeen(potId) -> génération sélectionnée -> PotProjection@V -> Expense status@V`.

## 18. Ownership du routage

**IMPLEMENTATION CHOICE —** la source autoritative `expense_identities` appartient au primaire/write-side. Le read store possède une copie durable `expense_routes`, donc aucun futur GET ne lit le primaire.

Elle converge par deux chemins idempotents :

1. l'Event de création assure la route avant l'avancement du watermark ;
2. toute PotProjection assure les routes des Expenses qu'elle contient, couvrant projection directe/hors ordre/rebuild.

Les deux utilisent `ensure(expenseId, potId)` : même Pot = adopt/no-op ; autre Pot = invariant violation. Le backfill administratif en masse reste 7.8 ; 7.7 fournit le port et prouve la reconstructibilité.

## 19. Sémantique de delete

**CANONICAL INVARIANT —** supprimer une Expense ne supprime ni identité ni route. Une PotProjection `DELETED` et ses enfants historiques restent valides. Le futur reader choisit leur exposition.

Le terminal-delete Pot fermé en 7.6 reste inchangé.

## 20. Sémantique `NOT_FOUND`

Après baseline complète et consumer à niveau : route absente = `NOT_FOUND`; route présente mais projection cible non READY = `NOT_READY`; route et projection présentes avec Expense deleted = résultat archive/deleted selon la future query.

Le port shadow distingue `RouteAbsent` de `RouteFound(potId)`. La traduction HTTP reste 7.9. Option A n'est activable qu'après migration/preflight, watermark attendu et audit de concordance primaire/read store.

## 21. Frontières transactionnelles

Transaction commande Pot : compteur CAS/create + metadata + mutations + Events + provenance/lifecycle/CAS terminal.

Transaction création Expense : nouvelle version/metadata + identité Expense + header/shares + Events + provenance/lifecycle/CAS terminal.

Transaction PotProjection existante étendue : metadata adopt/verify + snapshot/fragments + descriptor + index user exact + routes contenues + head max + provenance Task + terminal CAS.

Transaction Event création Expense : route assurée avant watermark/provenance/CAS terminal.

**IMPLEMENTATION CHOICE —** étendre writers/runners existants, sans moteur parallèle. Des injections après metadata, première ligne user, descriptor, head et avant CAS prouvent le rollback intégral.

## 22. Concurrence et idempotence

Chaque store fait insert-first puis reload/compare sur conflit unique : metadata même instant, index même contenu ou route même Pot = adopt ; valeur divergente = invariant violation.

**CANONICAL INVARIANT —** aucun `ON CONFLICT DO UPDATE` ne modifie du contenu fonctionnel. `DO NOTHING` exige une comparaison complète. Les courses mêmes/différentes sont testées avec transactions PostgreSQL concurrentes.

## 23. Contraintes SQL

Tester réellement : PK metadata et Expense, timestamp non null, versions positives, append-only, FK primaire vers Pot, FK user-index vers artifact, unicités exactes, statut Pot, conflit de route, rollback et ordre keyset.

**IMPLEMENTATION CHOICE —** aucune FK cross-database read store/primary. Adoption immuable, audit et rebuild assurent la concordance.

## 24. Pagination keyset

Ordre canonique :

```sql
ORDER BY updated_at DESC, pot_id ASC
```

Après `(updatedAt, potId)` :

```sql
updated_at < :updatedAt
OR (updated_at = :updatedAt AND pot_id > :potId)
```

**IMPLEMENTATION CHOICE —** curseur opaque Base64URL avec `schemaVersion=1`, instant exact `epochSecond+nano` et UUID `potId`. Refuser encodage/taille/champs/version/timestamp/UUID invalides.

**IMPLEMENTATION CHOICE —** défaut 50, maximum 200, lecture `limit+1`; aucun OFFSET. Sur dataset stable, ni doublon ni omission. Entre deux pages mutées, documenter les limites normales d'une keyset sans snapshot global.

## 25. Sémantique de rebuild

Sources exactes : metadata read-side depuis metadata primaire ; index user depuis PotProjection + metadata ; route depuis `expense_identities`, vérifiable depuis l'historique PotProjection ; éventuelle accélération depuis indexes versionnés + watermarks.

**CANONICAL INVARIANT —** aucun rebuild ne consulte l'horloge, les timestamps Task/Event ou V-1. L'orchestration en masse reste 7.8.

## 26. Observabilité

Prévoir compteurs/logs structurés pour metadata absente/conflit, conflit index/route, lignes créées/adoptées, curseur invalide, retard de route et audits de concordance. IDs dans logs corrélés, jamais dans labels métriques. Une violation n'altère aucun artifact READY.

## 27. Matrice de tests

### `createdAt`

- V1 et toute mutation : metadata présente ;
- timestamp stable/immuable ;
- plusieurs Events sans effet ;
- rollback/retry sans orphelin ;
- projection/rebuild identiques ;
- deux Pots au même instant permis ;
- heure de projection sans effet ;
- metadata absente/divergente : échec explicite.

### Index user/Pot

- V1 A/B, V2 A/C, avec conservation et lecture explicite des deux ;
- creator, shareholder actif/deleted, déduplication ;
- Pot DELETED conservé ;
- V5 puis V3 ;
- générations v1/v2 isolées ;
- duplicate exact/no-op, divergent/invariant ;
- rollback à chaque injection ;
- aucune décision d'autorisation par l'index.

### Expense routing

- création exactement une fois ; mutation inchangée ; delete conservé ; historique routable ;
- même ID/même Pot concurrent idempotent ; autre Pot invariant ;
- conflit legacy refusé ; rebuild identique ;
- absence prouvant `NOT_FOUND`, présence sans projection prouvant `NOT_READY`.

### Pagination/list-current

- ordre descendant/ascendant et timestamps égaux ;
- pages sans doublon/omission sur dataset stable ;
- curseur invalide/inconnu/trop grand ; limites et fin ;
- watermark individuel et générations sélectionnées différentes.

### PostgreSQL/intégration

- contraintes, transactions, concurrence et rollback réels via Testcontainers ;
- `EXPLAIN`/ordre keyset ; projection directe V5 ; générations multiples ; hors ordre ; watermark independence ;
- non-régression 7.6 : exactitude, autonomie, idempotence, divergence, atomicité, delete terminal, shadow mode.

## 28. Séquence d'implémentation

1. Revalider numéros Flyway et bases legacy.
2. Ajouter metadata/version primaire append-only.
3. Rendre compteur + metadata indivisibles.
4. Tester timestamp/rollback/retry sur tous les chemins.
5. Étendre reconstruction et matérialisation metadata sans changer le digest v1.
6. Ajouter schéma/writer user-Pot dans la transaction PotProjection.
7. Ajouter `expense_identities`, preflight et création transactionnelle.
8. Ajouter `expense_routes` et `ensure` partagé Event/Projection.
9. Ajouter adapters shadow route et liste.
10. Ajouter codec/requête keyset PostgreSQL, sans endpoint.
11. Tester concurrence/rebuild/non-régression.
12. Mettre à jour la documentation canonique.
13. Valider localement, pousser et attendre la CI verte.

Les vérités primaires précèdent leurs dérivés read-side ; aucun index n'est publié avant fermeture du timestamp et de l'identité.

## 29. Fichiers/modules probablement touchés

- `engine-core` et modules command Pot/Expense : metadata, allocator, identité ;
- `infra-persistence-jpa` et migrations `runtime-monolith` : registres/adapters ;
- `engine-read-projection`, `pipeline-pot`, reconstruction historique : propagation et dérivation ;
- `infra-read-persistence` : migration V6, writers/readers/keyset ;
- runtime source-version-watermark : route avant watermark ;
- `runtime-task-consumption-worker` seulement si nécessaire au wiring transactionnel ;
- `architecture-tests` et tests PostgreSQL/Testcontainers concernés.

**IMPLEMENTATION CHOICE —** réutiliser moteurs Event/Task et transaction runners existants. Aucun module parallèle.

## 30. Documentation à mettre à jour

Après implémentation :

- `read-side-target.md` : supprimer le current fonctionnel, fermer Option A et décrire watermark/version/génération ;
- `read-side-current-state.md` : tables/ports shadow réels ;
- `pot-historical-reconstruction.md` : source exacte de `createdAt` ;
- `write-side-closure.md` : allocation atomique et identité Expense ;
- `module-dependency-matrix.md` : nouveaux ports/adapters ;
- plan directeur 7 et plan 7.6 : gate résolu, Option A, digest v1 inchangé ;
- `development/ci.md` seulement si la CI change.

Le présent plan est ajouté à `docs/README.md`.

## 31. Risques

- Legacy sans timestamp exact : preflight + reset, jamais approximation.
- Bypass de l'allocator : port unique et architecture tests.
- Route read-side en retard : assurance avant watermark, baseline et audit.
- Overwrite concurrent : insert/reload/compare et rollback.
- Join coûteux : indexes, `EXPLAIN`, mesures avant accélération.
- Confusion index/ACL : autorisation toujours sur projection exacte.
- Rupture digest v1 : metadata adjacente, serializer publié inchangé.

## 32. Blockers

**BLOCKER —** aucun choix architectural ne reste ouvert. Seuls restent des détails mineurs : numéro Flyway libre, noms Java/SQL, forme du DTO de reconstruction et variante d'index équivalente validée par `EXPLAIN`.

Une base legacy sans metadata est reset explicitement ou son déploiement est refusé ; cela ne rouvre pas la source d'`updatedAt`.

## 33. Critères de sortie

- Une metadata primaire exacte et immuable par Pot version ; aucun fallback.
- Stratégie legacy explicite ; rebuild au même timestamp.
- Index user/Pot exact, immutable, versionné et génération-scopé.
- Données deleted historisées.
- Route Expense durable, unique, reconstructible et conservée après delete.
- Option A démontrée avant activation.
- Aucun état current ; résolution watermark individuel + sélection reader testée.
- Ordre/keyset prouvés sur PostgreSQL.
- Projection, metadata dérivée, index, artifact et head atomiques.
- Concurrence/divergence sans overwrite.
- Aucun GET actif basculé ; docs à jour ; tests locaux et CI verts.

## 34. Commandes de validation

Depuis `app` :

```bash
./mvnw -pl domain-projection,engine-read-projection,pipeline-pot -am test
./mvnw -pl engine-core,infra-persistence-jpa -am test
./mvnw -pl infra-read-persistence -am test
./mvnw -pl runtime-source-version-watermark-consumption-worker,runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
./mvnw test
```

Depuis la racine :

```bash
git diff --check
```

Les noms Maven sont revérifiés à l'implémentation. Les tests PostgreSQL/Testcontainers ne sont ni exclus ni remplacés par H2/mocks.

## 35. Validation CI

**CONFIRMED BY DOCUMENTATION —** le garde-fou permanent est `Pocoma CI / build-and-test`, déclenché sur `push` et `pull_request`, avec Java 21 et Maven Wrapper.

Tout commit destiné à devenir HEAD de `v2-make-it-pull` doit suivre :

```text
push -> Pocoma CI / build-and-test -> ./mvnw test -> green
```

La validation locale seule ne clôt pas l'implémentation : le SHA final doit avoir le check GitHub Actions vert et satisfaire la règle de protection. Toute suppression du workflow est une modification d'infrastructure explicite.
