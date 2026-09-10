# Lot 7.7 — Métadonnées de version Pot, index utilisateur et pagination keyset

## 1. Scope

Le Lot 7.7 ferme les prérequis read-side des futures listes de Pots, sans activer de GET :

- `PotVersionMetadata.createdAt` sur le primaire autoritatif ;
- sa propagation exacte vers le read store sous le nom `updatedAt` ;
- l'index immuable et historisé `userId -> PotProjection@V` ;
- l'ordre `updatedAt DESC, potId ASC` ;
- la pagination keyset et son curseur opaque ;
- la résolution efficace d'une liste multi-Pots à partir des watermarks individuels ;
- atomicité, reconstructibilité, concurrence, idempotence et tests shadow.

**CANONICAL INVARIANT —** `read-pot/v1` reste shadow. Aucun reader HTTP ne bascule dans ce lot.

## 2. Non-scope

Sont hors périmètre :

- tout routing global `expenseId -> potId` ou `shareholderId -> potId` ;
- `expense_identities`, `expense_routes`, consumer de route et réplication de route ;
- Option A/Option B et toute preuve d'existence par absence d'un index secondaire ;
- moteur administratif de rebuild 7.8 ;
- Query Kernel et contrats HTTP complets 7.9 ;
- autorisation 7.10 et cutover 7.11 ;
- activation d'une génération reader ;
- état fonctionnel `current` persistant ;
- index Balance, sauf primitive réellement commune déjà nécessaire.

## 3. Documentation consultée et contradictions ciblées

**CONFIRMED BY DOCUMENTATION —** la réécriture s'appuie sur `docs/README.md`, `read-side-target.md`, `read-side-current-state.md`, `pot-historical-reconstruction.md`, `write-side-closure.md`, `module-dependency-matrix.md`, le plan directeur Lot 7, les plans 7.5/7.6 et `development/ci.md`.

La recherche ciblée de `/expenses/{id}`, `expenseId -> potId`, `routing Expense`, `Option A` et `Option B` a trouvé des hypothèses obsolètes dans :

- `read-side-target.md` ;
- `lot-7-read-side-implementation-plan.md` ;
- `lot-7.6-canonical-pot-projection-plan.md` ;
- l'ancien plan 7.7.

**IMPLEMENTATION CHOICE —** les passages normatifs et de planning sont alignés avec cette réécriture. `read-side-current-state.md` conserve le constat du endpoint global legacy tant qu'il existe réellement, mais devra signaler lors du cutover qu'il n'appartient pas au contrat cible.

## 4. État actuel vérifié

**CONFIRMED BY TARGETED CODE INSPECTION —** `pot_global_versions` contient une seule ligne mutable par Pot, sans historique de timestamps.

**CONFIRMED BY TARGETED CODE INSPECTION —** les mutations Pot/Shareholder/Expense observées allouent leur version via `PotGlobalVersionPort`, dans la transaction locale qui porte aussi les écritures métier et les BusinessEvents.

**CONFIRMED BY TARGETED CODE INSPECTION —** le timestamp Event ne matérialise pas la fonction `(potId, potVersion) -> createdAt` : plusieurs Events peuvent appartenir à une version.

**CONFIRMED BY TARGETED CODE INSPECTION —** `ProjectionMaterializationService` écrit déjà fragments, artifact et head dans une transaction read-store commune. La dernière migration read-store PotProjection est V5.

**CONFIRMED BY TARGETED CODE INSPECTION —** `source_version_watermarks` porte `latestVersionSeen` par Pot et son consumer express ne fait aujourd'hui que l'observation monotone des versions source.

## 5. Invariants canoniques

- **CANONICAL INVARIANT —** une sous-ressource est toujours adressée avec sa ressource parente.
- **CANONICAL INVARIANT —** `(potId, potVersion)` détermine exactement un `createdAt` durable et immuable.
- **CANONICAL INVARIANT —** aucun timestamp Event, Task, worker ou projector ne remplace ce `createdAt`.
- **CANONICAL INVARIANT —** aucun backfill legacy approximatif.
- **CANONICAL INVARIANT —** aucune vérité fonctionnelle `current` n'est persistée.
- **CANONICAL INVARIANT —** l'index user/Pot est versionné, immuable et scopé par génération.
- **CANONICAL INVARIANT —** `PipelineSelectionStrategy` intervient uniquement à la lecture.
- **CANONICAL INVARIANT —** l'ordre total est `updatedAt DESC, potId ASC`.
- **CANONICAL INVARIANT —** un index secondaire absent ne prouve jamais l'inexistence d'une sous-ressource.
- **CANONICAL INVARIANT —** le SourceVersionWatermark reste un consumer indépendant et pur.

## 6. Adressage des sous-ressources

Le contrat cible accepte :

```text
GET /pots/{potId}/expenses/{expenseId}
GET /pots/{potId}/shareholders/{shareholderId}
```

Il ne prépare pas :

```text
GET /expenses/{expenseId}
GET /shareholders/{shareholderId}
```

Le `potId` fourni par le chemin suffit pour résoudre version cible, génération et PotProjection exacte. Aucun mapping transverse enfant vers parent n'est créé par 7.7.

## 7. Design de `PotVersionMetadata.createdAt`

`PotVersionMetadata` est une vérité temporelle canonique du Pot, possédée par `domain-pot` dans
`domain.pot.version`. Elle naît sur le write-side primaire et reste indépendante de toute projection ;
`domain-projection` ne fait que consommer cette vérité via la reconstruction.

**IMPLEMENTATION CHOICE —** créer sur le primaire un registre append-only distinct du compteur courant :

```text
PotVersionMetadata
  potId
  version
  createdAt
```

Faire évoluer le port d'allocation pour rendre indivisibles :

- création Pot : compteur V1 + metadata V1 ;
- mutation : CAS `expectedVersion -> nextVersion` + metadata `nextVersion`.

**IMPLEMENTATION CHOICE —** fixer `createdAt` avec `transaction_timestamp()` PostgreSQL dans l'adapter primaire. Un rollback ne laisse ni version ni metadata ; un retry réussi fixe une seule valeur durable.

Ajouter une lecture intentionnelle exacte `load(potId, version)`. PK `(pot_id, version)`, absence de méthode update/delete et protection SQL append-only empêchent toute réécriture.

## 8. Stratégie legacy

**IMPLEMENTATION CHOICE —** la migration primaire forward-only crée la structure sans inventer l'historique. Si une base contient des versions sans metadata exacte, la migration ou son preflight échoue et le runbook impose un reset explicite des données de développement concernées.

Ni l'heure de migration, ni `recordedAt`, ni min/max/first/last Event ne sont admissibles.

**BLOCKER —** aucun choix architectural. Avant déploiement, inventorier les bases legacy ; une base incompatible est reset explicitement ou son déploiement est refusé.

## 9. Propagation exacte de `updatedAt`

`updatedAt` est le nom read-side de `PotVersionMetadata.createdAt`.

**IMPLEMENTATION CHOICE —** conserver le digest fonctionnel publié de `read-pot/v1` inchangé. Le reconstructeur renvoie `PotProjection` avec sa metadata exacte adjacente. Le read store adopte/vérifie cette metadata dans la transaction de matérialisation et l'utilise dans l'index user/Pot.

Une future génération pourra intégrer la valeur à son snapshot si son format le prévoit. Pour v1, aucune horloge de projection n'entre dans le digest ou dans l'ordre.

## 10. Modèle logique de l'index user/Pot

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

Chaque PotProjection produit ses propres lignes. V12 et V13 coexistent ; V13 ne remplace jamais V12.

## 11. Utilisateurs indexés

**IMPLEMENTATION CHOICE —** indexer à V :

- le creator ;
- chaque Shareholder actif à V possédant un `userId` ;
- une seule ligne si plusieurs relations désignent le même user.

Un Shareholder `deleted=true` reste dans PotProjection mais n'est plus candidat de navigation à cette version. Ses lignes historiques restent intactes. Un Pot `DELETED` conserve les lignes de sa delete version avec `potStatus=DELETED`.

**CANONICAL INVARIANT —** cet index est un index de navigation, jamais une ACL. L'autorisation exacte appartient à 7.10.

## 12. Schéma physique prévu

Migration primaire, après revérification du numéro :

```sql
pot_version_metadata (
  pot_id uuid not null references pot_global_versions(pot_id),
  version bigint not null check (version > 0),
  created_at timestamptz not null,
  primary key (pot_id, version)
)
```

Migration read-store, après revérification du numéro V6 :

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
```

Indexes SQL : `(user_id, updated_at DESC, pot_id ASC)` incluant les colonnes de résolution, et `(pipeline_id, pipeline_version, pot_id, pot_version)` pour les lectures exactes.

**CANONICAL INVARIANT —** aucune table `expense_routes`, `expense_identities` ou équivalent n'est créée par ce lot.

## 13. Scoping par génération

L'index dérivé est scopé par `(pipelineId, pipelineVersion, potId, potVersion)`. `read-pot/v1@V` et `read-pot/v2@V` coexistent sans pollution croisée.

La metadata Pot n'est pas dupliquée par génération : elle décrit la version métier autoritative. Chaque génération adopte et vérifie la même valeur.

## 14. Immutabilité et projections hors ordre

Pour `(pipelineId, pipelineVersion, potId, potVersion, userId)` :

- même contenu : no-op/AlreadySatisfied ;
- contenu divergent : invariant violation, ancienne ligne conservée ;
- nouvelle Pot version : nouvelles lignes, anciennes intactes.

V5 puis V3 écrit `@V5` puis `@V3`. Aucun mécanisme « current-index advance » n'existe.

## 15. Frontière transactionnelle PotProjection

Dans la transaction read-store existante :

```text
PotProjection snapshot/fragments
+ ProjectionArtifact
+ PotVersionMetadata exacte read-side
+ user index exact @V
+ ProjectionHead max
+ provenance Task
+ terminal CAS
```

**IMPLEMENTATION CHOICE —** étendre le writer/materializer existant, sans moteur parallèle. Une défaillance après metadata, première ligne user, artifact ou avant head/CAS doit tout rollbacker. Aucun index indispensable ne devient visible après un artifact READY.

## 16. SourceVersionWatermark pur

```text
BusinessEvent -> latestVersionSeen = max(existing, event.potVersion)
```

et rien d'autre.

**CANONICAL INVARIANT —** ce consumer ne crée ni route, ni index, ni projection ; il ne dépend d'aucun autre consumer et ne garantit aucun ordre causal avec eux. Les chemins Event restent indépendants.

## 17. Résolution d'une liste current multi-Pots

Le futur reader :

1. cherche les entrées candidates du user ;
2. joint `source_version_watermarks` par `potId` ;
3. retient `index.potVersion = latestVersionSeen(potId)` ;
4. applique la génération sélectionnée pour cette version ;
5. applique actif/archive ;
6. ordonne et pagine.

Chaque Pot a son watermark ; aucune version globale n'existe.

**IMPLEMENTATION CHOICE —** l'adapter shadow reçoit du futur Query Kernel les plages applicables `(fromVersion, toVersion, pipelineVersion)` et les traduit en prédicats SQL. `PipelineSelectionStrategy` n'intervient jamais lors de la production des lignes.

## 18. Appartenance nouvelle dans une projection NOT_READY

Cas explicite :

```text
watermark Pot X = 13
V12 : user A absent
V13 : user A devient shareholder
READ_POT@13 = NOT_READY
```

L'index dérivé ne peut pas contenir `A -> X@13` avant la matérialisation atomique de `READ_POT@13`. Une query partant seulement de cet index ne peut donc ni découvrir X ni produire un `NOT_READY` pour A.

**IMPLEMENTATION CHOICE —** le contrat shadow 7.7 retourne uniquement les Pots dont l'entrée exacte sélectionnée est matérialisée et exposable. Il ne crée pas une structure source-membership séparée.

**BLOCKER —** avant cutover de `GET /pots`, le Lot 7.9 doit fermer la décision produit : liste des seuls Pots exposables, ou signalement des appartenances source connues mais NOT_READY. La seconde sémantique exigerait une connaissance supplémentaire explicitement conçue ; elle ne doit pas être ajoutée implicitement à 7.7.

## 19. Accélération éventuelle

**IMPLEMENTATION CHOICE —** aucune table current fonctionnelle ou technique n'est créée initialement. Index versionné + watermark sont d'abord validés avec PostgreSQL et `EXPLAIN`.

Toute accélération ultérieure porte obligatoirement :

```text
TECHNICAL ACCELERATION
NOT FUNCTIONAL SOURCE OF TRUTH
```

Elle est reconstructible depuis metadata, index versionné et watermarks, et ne change ni READY/NOT_READY ni sélection de génération.

## 20. Pagination keyset

```sql
ORDER BY updated_at DESC, pot_id ASC
```

Après `(updatedAt, potId)` :

```sql
updated_at < :updatedAt
OR (updated_at = :updatedAt AND pot_id > :potId)
```

**IMPLEMENTATION CHOICE —** curseur opaque Base64URL contenant `schemaVersion=1`, l'instant exact (`epochSecond+nano`) et `potId`. Le decoder refuse taille, encodage, champs, version, instant ou UUID invalides.

Défaut 50, maximum 200, lecture `limit+1`, aucun OFFSET. Sur dataset stable : aucun doublon/omission. Des mutations entre pages gardent les limites normales d'une keyset sans snapshot global.

## 21. `NOT_FOUND`/`NOT_READY` des sous-ressources

Pour `/pots/{potId}/expenses/{expenseId}?version=V` ou le Shareholder équivalent :

1. résoudre V et lire `latestVersionSeen(potId)` ;
2. si `V > latestVersionSeen`, `NOT_FOUND` ;
3. sélectionner `READ_POT` pour V ;
4. source connue + projection exacte absente sans failure terminale : `NOT_READY` ;
5. projection READY + enfant absent : `NOT_FOUND` ;
6. projection READY + enfant présent : appliquer statut ACTIVE/DELETED puis autorisation future.

**CANONICAL INVARIANT —** l'absence d'un index secondaire n'entre jamais dans cette décision. La traduction HTTP finale reste 7.9.

## 22. Readiness exacte entre pipelines

Cas contractuel à préserver :

```text
watermark=13
READ_POT@12 READY
READ_POT@13 READY
BALANCES@13 READY
BALANCES@12 absent
```

Une query Balance @12 sélectionne BALANCES applicable à 12 puis conclut `NOT_READY`. Elle ne tombe ni sur BALANCES@13, ni sur une autre version/génération READY.

**CANONICAL INVARIANT —** source connue + artifact requis absent = NOT_READY ; aucun fallback temporel ou inter-génération.

## 23. Concurrence et idempotence

Chaque store applique insert-first puis reload/compare sur conflit :

- metadata même instant : adopt ; autre instant : invariant violation ;
- index même contenu : no-op ; contenu différent : invariant violation.

Aucun `ON CONFLICT DO UPDATE` ne modifie un contenu fonctionnel. `DO NOTHING` exige une comparaison complète.

## 24. Contraintes SQL

Les tests PostgreSQL prouvent : PK metadata, timestamps non null, versions positives, protection append-only, FK index vers artifact, unicité exacte user/projection, statut Pot, rollback et ordre keyset.

Il n'existe aucune FK ou contrainte de routing enfant-parent ajoutée pour les GET.

## 25. Rebuild

Sources exactes :

- metadata read-side depuis `pot_version_metadata` primaire ;
- index user/Pot depuis PotProjection exacte + metadata exacte ;
- pagination depuis les mêmes lignes versionnées ;
- accélération future depuis metadata, indexes versionnés et watermarks.

Un rebuild ne consulte ni horloge courante, ni timestamps Task/Event, ni projection V-1. L'orchestration en masse reste 7.8.

## 26. Observabilité

Prévoir métriques/logs structurés pour metadata absente/conflit, conflit d'index, lignes créées/adoptées, curseur invalide, lag watermark/artifact et résultat des audits. IDs uniquement dans les logs corrélés, pas dans les labels métriques.

## 27. Tests `createdAt`

- création V1 et chaque mutation : metadata présente ;
- même version : timestamp stable et non modifiable ;
- plusieurs Events : aucun effet ;
- rollback/retry : aucun orphelin ;
- projection/rebuild : même valeur ;
- deux Pots peuvent partager un timestamp ;
- heure de projection différente : `updatedAt` inchangé ;
- metadata absente/divergente : échec explicite.

## 28. Tests index user/Pot

- V1 A/B, V2 A/C ; V1 reste intacte et chaque version se lit explicitement ;
- creator, Shareholder actif/deleted et déduplication ;
- Pot DELETED historisé ;
- V5 puis V3 ;
- `read-pot/v1` et v2 isolés ;
- duplicate exact/no-op, divergent/invariant ;
- rollback après chaque point d'injection ;
- aucune utilisation de l'index comme ACL.

## 29. Tests liste et pagination

- join sur watermark individuel de chaque Pot ;
- génération sélectionnée selon chaque version ;
- ordre `updatedAt DESC, potId ASC`, y compris timestamps égaux ;
- pages sans doublon/omission sur dataset stable ;
- curseur invalide, trop grand ou version inconnue ;
- limites et fin de liste ;
- relation A apparue à V13 NOT_READY : absence explicite du résultat shadow, sans fausse entrée ni structure auxiliaire ;
- `EXPLAIN` sur PostgreSQL/Testcontainers.

## 30. Contract tests readiness/sous-ressources

À préparer pour 7.9, avec fixtures/adapters 7.7 :

- V connue + READ_POT@V absente : `NOT_READY` ;
- READ_POT@V READY + Expense absente : `NOT_FOUND` ;
- READ_POT@V READY + Shareholder absent : `NOT_FOUND` ;
- V au-delà du watermark : `NOT_FOUND` ;
- failure terminale : sémantique FAILED dédiée, pas NOT_READY ;
- Balance @12 absente malgré Balance @13 READY : NOT_READY sans fallback.

## 31. Séquence d'implémentation

1. Revalider numéros Flyway et bases legacy.
2. Ajouter metadata/version primaire append-only.
3. Rendre compteur + metadata transactionnellement indivisibles.
4. Tester timestamp, rollback et retry sur tous les chemins d'allocation.
5. Étendre reconstruction et matérialisation de metadata sans changer le digest v1.
6. Ajouter schéma/writer user-Pot dans la transaction PotProjection.
7. Ajouter adapter shadow de liste par watermark et génération explicites.
8. Ajouter codec et requête keyset PostgreSQL, sans endpoint public.
9. Tester concurrence, hors-ordre, rebuild et non-régression 7.6.
10. Préparer les contract tests de sous-ressources sans routing.
11. Mettre à jour la documentation canonique factuelle.
12. Valider localement, pousser via PR protégée et attendre la CI verte.

## 32. Modules probablement touchés

- domaine/engine et command Pot : metadata et allocation ;
- `infra-persistence-jpa` et migrations primaires ;
- `engine-read-projection`, `pipeline-pot`, reconstruction historique ;
- `infra-read-persistence` : migration, writer et query keyset ;
- `architecture-tests` et tests PostgreSQL/Testcontainers.

**IMPLEMENTATION CHOICE —** le runtime SourceVersionWatermark n'est pas modifié. Les moteurs Event/Task et transaction runners existants sont réutilisés.

## 33. Documentation à aligner après implémentation

- `read-side-target.md` : état physique final et liste multi-Pots ;
- `read-side-current-state.md` : tables/ports shadow réels et endpoint Expense global marqué legacy ;
- `pot-historical-reconstruction.md` : source exacte de `createdAt` ;
- `write-side-closure.md` : allocation atomique des versions ;
- `module-dependency-matrix.md` : nouveaux ports/adapters ;
- plan directeur 7 et plan 7.6 : état de clôture du gate `updatedAt` ;
- docs HTTP/query 7.9 : sous-ressources toujours sous leur Pot parent.

## 34. Risques, dépendances et points ouverts

- Legacy sans timestamp exact : preflight + reset, jamais approximation.
- Bypass de l'allocator : port unique et architecture tests.
- Overwrite concurrent : insert/reload/compare et rollback.
- Join coûteux : index PostgreSQL et mesures avant accélération.
- Index confondu avec ACL : autorisation toujours sur projection exacte.
- Relation nouvelle dans une projection NOT_READY : décision produit 7.9 à fermer avant cutover de la liste.

**BLOCKER —** aucun blocker pour implémenter 7.7. Le seul point produit ouvert concerne le comportement futur de `GET /pots` face à une appartenance nouvelle encore NOT_READY ; il bloque le cutover 7.11, pas les artefacts shadow 7.7.

## 35. Critères de sortie, validation et CI

Le lot est clos si :

- une metadata exacte/immuable existe pour chaque Pot version, sans fallback legacy ;
- l'index user/Pot est atomique, versionné et génération-scopé ;
- aucune route enfant-parent ou dépendance route/watermark n'existe ;
- le SourceVersionWatermark reste pur ;
- aucun current fonctionnel n'est persisté ;
- ordre/keyset sont déterministes sur PostgreSQL ;
- source connue + projection requise absente donne NOT_READY ;
- enfant absent n'est déclaré NOT_FOUND qu'après PotProjection exacte READY ;
- reconstruction, hors-ordre, concurrence et rollback sont prouvés ;
- aucun GET actif ne bascule.

Validation locale depuis `app` : tests ciblés des modules touchés, `./mvnw -pl architecture-tests -am test`, puis `./mvnw test`. Depuis la racine : `git diff --check`.

Tout commit passe par la PR protégée et doit obtenir `Pocoma CI / build-and-test` green. Les tests PostgreSQL/Testcontainers ne sont ni désactivés ni remplacés par H2/mocks.

## 36. État réel après implémentation

- La migration primaire V11 crée `pot_version_metadata` append-only et refuse les compteurs legacy
  sans timestamp exact. `JpaPotGlobalVersionAdapter` rend allocation du compteur et metadata
  transactionnellement indivisibles avec le timestamp transactionnel PostgreSQL.
- `JpaHistoricalPotSnapshotSourceAdapter` relit la metadata exacte ; son absence est une erreur de
  reconstruction. `ReconstructedPotProjection` la transporte sans modifier le contenu fonctionnel ni
  le digest publié de `read-pot/v1`.
- La migration read-store V6 crée la copie exacte des metadata et
  `pot_projection_user_index`. Le writer indexe creator et Shareholders actifs liés à un user,
  déduplique les identités et conserve les lignes des anciennes versions et générations. Chaque ligne
  d'index applique localement `insert / conflict / reload / complete-content comparison` : contenu
  identique adopté, contenu divergent rejeté sans overwrite.
- Snapshot/fragments, metadata, index user, descriptor, head, provenance Task et terminal CAS restent
  dans la transaction locale existante. Les tests PostgreSQL injectent un échec au cours de l'écriture
  de l'index et prouvent l'absence de matérialisation partielle.
- `JdbcPotUserIndexReader` joint l'index exact au watermark individuel, reçoit les plages de pipeline
  sélectionnées par le futur reader et applique l'ordre/keyset `updatedAt DESC, potId ASC`, limite 50
  par défaut et maximum 200. Une sélection de plages vide retourne immédiatement une page vide, sans
  fallback de génération ni SQL. Aucun état current n'est matérialisé.
- Aucun routage Expense/Shareholder, aucun endpoint HTTP et aucune modification du consumer watermark
  n'ont été introduits. Le comportement produit d'une appartenance nouvelle encore NOT_READY reste un
  sujet du Query Kernel/cutover, pas un écart de matérialisation 7.7.
