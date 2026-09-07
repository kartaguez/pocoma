# Pocoma — Lot 7.2 — Séparation logique du read store

## 1. Objectif

Le Lot 7.2 crée la frontière logique et technique de persistence du read side, sans introduire de
modèle fonctionnel de projection et sans modifier les GET.

À la fin du lot :

- le read store possède son module, son schéma et son cycle de migrations ;
- ses accès et transactions sont explicitement identifiables ;
- il utilise encore la même instance PostgreSQL, le même pool JDBC et le même transaction manager
  que la persistence actuelle ;
- les futurs readers et projectors pourront dépendre de cette frontière sans dépendre des
  repositories primaires ;
- aucun runtime de production n'est encore câblé au nouveau module ;
- les pipelines et GET existants conservent leur comportement.

Le lot établit la séparation suivante :

```text
write/operational persistence
        !=
read-side persistence
```

Il ne met pas encore en œuvre les concepts fonctionnels construits dans les Lots 7.3 et suivants.

## 2. Sources canoniques

Le plan dérive de :

1. `docs/architecture/read-side-target.md` ;
2. `docs/architecture/read-side-current-state.md` ;
3. `docs/plans/lot-7-read-side-implementation-plan.md` ;
4. `docs/README.md`.

`docs/architecture/module-dependency-matrix.md` a été consulté ponctuellement pour confirmer les
conventions Maven et les directions de dépendance. La cible normative prévaut sur l'implémentation
existante.

## 3. Invariants applicables

- Le write side ne doit jamais écrire directement dans le read store.
- Le read store initial reste dans la même instance PostgreSQL, mais dans un schéma dédié.
- Aucune FK, vue ou jointure runtime read vers primary n'est introduite.
- Aucun objet applicatif read-side ne dépend structurellement d'un objet métier du schéma primaire.
- Les migrations read-side ont une location et un historique Flyway distincts.
- Les migrations read-side sont autonomes et installables sans tables primaires.
- L'atomicité permanente du modèle cible couvre uniquement les futures données appartenant au read
  store.
- Une éventuelle transaction locale englobant Task et read store reste une commodité liée à la
  colocalisation PostgreSQL, jamais un invariant fonctionnel.
- Aucun second PostgreSQL, pool JDBC, `DataSource`, `EntityManagerFactory` ou transaction manager
  n'est créé par principe en 7.2.
- Aucun GET, pipeline métier ou contrat HTTP n'est modifié.

Les choix de module, schéma, qualifier et composition Flyway décrits ci-dessous sont les defaults
d'implémentation du Lot 7.2. Ils ne deviennent pas des invariants architecturaux.

## 4. État technique actuel strictement nécessaire

- `infra-persistence-jpa` rassemble aujourd'hui persistence primaire, consommation, queries et
  artifacts Balance.
- Les migrations V1 à V3 sont possédées par `runtime-monolith`; les migrations V4 et suivantes par
  `infra-persistence-jpa`. Les runtimes les assemblent sous `classpath:db/migration`.
- Spring Boot fournit un unique `DataSource`, un unique contexte JPA et un unique
  `PlatformTransactionManager`.
- `runtime-web-api`, `runtime-event-consumption-worker` et `runtime-task-consumption-worker` dépendent
  directement d'`infra-persistence-jpa`.
- Le Task worker utilise le transaction manager commun pour le lifecycle de consommation et
  l'écriture Balance.
- `balance_projection_artifacts` et `balance_projection_entries` vivent dans le schéma primaire ;
  l'artifact possède une FK vers `pot_global_versions`.
- La persistence Balance existante reste inchangée jusqu'au Lot 7.12.

## 5. Décisions de conception du Lot 7.2

### 5.1 Architecture verrouillée et implémentation de référence

Aucun arbitrage architectural n'est ouvert. Les choix techniques décrits dans ce plan constituent
l'implémentation de référence du Lot 7.2. Ils peuvent être ajustés pendant l'exécution uniquement si
une contrainte concrète et vérifiée du framework, du reactor ou du runtime l'exige, sans modifier les
invariants du lot.

Tout ajustement doit être :

- motivé par une preuve reproductible ;
- consigné dans `Targeted code inspections` ;
- couvert par un test apportant les mêmes garanties ;
- limité au détail technique concerné.

### 5.2 Module de persistence read-side

Choix de référence :

```text
module    = infra-read-persistence
artifact  = pocoma-infra-read-persistence
package   = com.kartaguez.pocoma.infra.read.persistence
```

Le suffixe `jpa` n'est pas retenu en 7.2 : le module contient Flyway, JDBC, transactions et
auto-configuration, mais aucune entité ni aucun repository JPA. Ce nom n'interdit pas d'ajouter plus
tard des adapters JPA et évite de nommer la frontière d'après une technologie encore absente.

Le module ne dépend initialement d'aucun domaine ou engine, puisqu'il ne contient encore aucun port
fonctionnel, artifact ou repository métier. Ses dépendances sont limitées à Spring JDBC/transactions,
Spring Boot auto-configuration et Flyway, plus PostgreSQL/Testcontainers en test.

Dépendances interdites :

- `pocoma-infra-persistence-jpa` ;
- runtimes et packages `supra` ;
- repositories ou entités primaires ;
- modèle ou persistence Balance legacy.

### 5.3 Schéma read-side

Defaults de référence :

```text
schema             = pocoma_read
migration location = classpath:db/read-store/migration
history table      = pocoma_read.flyway_schema_history
```

La première migration, proposée sous le nom `V1__initialize_read_store.sql`, initialise et valide la
frontière sans créer de table métier. Elle ne crée ni watermark, ni artifact, ni state, ni head, ni
index fonctionnel.

Le schéma est possédé initialement par le compte de migration actuel. La séparation en rôles
PostgreSQL de production distincts est différée ; l'ownership architectural est déjà porté par le
module et la location de migrations dédiés.

### 5.4 Cycle Flyway

Les garanties obligatoires sont :

```text
même DataSource
+ cycle primaire conservé
+ cycle read-side séparé
+ locations séparées
+ historiques séparés
+ ordre de composition contrôlé
+ aucune perturbation du Flyway primaire
```

L'implémentation de référence conserve l'auto-configuration primaire et introduit un migrateur
read-side dédié utilisant le même `DataSource`. Il est préférable que ce migrateur ne soit pas exposé
comme bean `Flyway` si les tests de contexte confirment que cette forme évite de faire reculer
l'auto-configuration Spring Boot primaire.

La classe exacte, le nom de bean, l'interface lifecycle et la dépendance de démarrage ne sont pas des
invariants. Une autre composition Spring est acceptable si les tests prouvent qu'elle :

- conserve le Flyway primaire et son comportement ;
- exécute chaque historique une seule fois ;
- respecte l'ordre prévu dans une composition réunissant les deux stores ;
- utilise le même `DataSource` en 7.2 ;
- n'introduit aucune dépendance aux tables primaires dans le cycle read-side.

### 5.5 Accès et transactions qualifiés

Le choix de référence introduit un qualifier interne `@ReadStore` et des beans qualifiés de type :

- `JdbcOperations` pour les accès read-side ;
- `TransactionOperations` pour délimiter une intention transactionnelle read-side.

La forme exacte du qualifier et les noms de beans restent ajustables si une convention Spring déjà
présente l'exige.

Cette frontière est une frontière d'ownership, d'intention et de composition. Elle ne constitue pas
une isolation transactionnelle physique : le `JdbcOperations` qualifié utilise le même `DataSource`,
et le `TransactionOperations` qualifié utilise le même `PlatformTransactionManager` que la
persistence primaire.

Les futurs adapters read devront vivre dans le module read, utiliser ces accès explicites et qualifier
leurs objets SQL avec le schéma read. Les ports fonctionnels et l'unité de matérialisation atomique
seront introduits à partir du Lot 7.3.

## 6. Modules et responsabilités

| Module | Responsabilité après 7.2 |
|---|---|
| `infra-read-persistence` | Schéma, migrations, accès JDBC et frontière transactionnelle read-side |
| `infra-persistence-jpa` | Persistence primaire, opérationnelle et Balance existante, inchangée |
| runtimes de production | Inchangés ; aucune dépendance vers le nouveau module en 7.2 |
| `architecture-tests` | Vérification de la direction des dépendances et de l'absence de couplage primaire |

Le module Command, le monolithe transitionnel, les engines et les domaines ne dépendent jamais de la
persistence read-side.

## 7. Stratégie schéma et migrations

Deux propriétés indépendantes doivent être vérifiées.

### 7.1 Autonomie intrinsèque du read store

```text
PostgreSQL vide
  -> cycle read-side seul
  -> création du schéma read-side
  -> création de son historique Flyway
```

Cette installation n'exige aucune table, migration ou ressource primaire. Elle prouve que le module
read-side ne possède aucune dépendance structurelle vers le primaire.

### 7.2 Composition des runtimes Pocoma

Lorsqu'un runtime chargera ultérieurement les deux stores, l'ordre de composition retenu est :

```text
migrations primaires
  -> migrations read-side
```

Cet ordre est une propriété du composition root, pas une précondition du module read. Le Lot 7.2 le
valide dans une composition de test isolée sans modifier les runtimes de production.

### 7.3 Règles de migration

- Conserver `public.flyway_schema_history` et toutes les migrations primaires existantes.
- Installer un historique distinct dans le schéma read.
- Ne déplacer ni modifier aucune migration existante.
- Ne créer aucune FK ou vue vers un objet métier primaire.
- Qualifier explicitement les futurs objets applicatifs read-side.
- Vérifier la reprise idempotente des deux cycles.

## 8. Stratégie datasource, JPA et transactions

- Conserver `spring.datasource.*` comme unique configuration de connexion.
- Ne pas ajouter de propriétés de second datasource en 7.2.
- Ne créer ni second pool, ni second `DataSource`, ni second `EntityManagerFactory`.
- Ne créer aucun second `PlatformTransactionManager`.
- Ne créer aucune entité JPA read-side dans ce lot.
- Exposer une intention d'accès read-side par qualifier ou convention équivalente.
- Tester le rollback via le `TransactionOperations` read-side sur une structure créée uniquement par
  le test.

La transaction qualifiée read-side est une frontière d'intention et d'ownership. Elle ne constitue
pas une transaction physiquement indépendante du primaire tant que les deux stores partagent le même
transaction manager.

La colocalisation conserve la possibilité d'une coordination Task/read store locale dans les lots
suivants. Cette possibilité ne doit pas être exposée dans un port fonctionnel du read store et ne
préjuge pas du protocole nécessaire après séparation physique.

## 9. Wiring des runtimes

Le Lot 7.2 retient un wiring différé au premier consommateur fonctionnel.

Le lot se limite à :

- module Maven ;
- schéma et cycle de migration ;
- auto-configuration ;
- accès et transactions qualifiés ;
- tests isolés et tests d'architecture.

Aucun POM de runtime n'est modifié en 7.2. La dépendance sera ajoutée uniquement lorsque le runtime
utilisera réellement le read store :

- Event worker au Lot 7.4 pour le watermark ;
- Task worker au Lot 7.5, lorsque la création/adoption de `NOT_READY` utilisera le read store ;
- web au premier lot composant réellement un reader read-side, sans anticiper son cutover.

Un test de composition Spring isolé dans le nouveau module prouve la chargeabilité de
l'auto-configuration, la coordination des migrations et l'absence de régression du Flyway primaire.
Cette stratégie réduit le blast radius du Lot 7.2 sans différer la preuve technique de composition.

## 10. Séquence détaillée d'implémentation

### Step 1 — Introduire l'ownership Maven

**Objectif**

Créer la frontière structurelle du read store.

**Responsabilité architecturale**

Ownership indépendant de la persistence read-side.

**Modules/fichiers concernés**

- `app/pom.xml` ;
- nouveau `app/infra-read-persistence/pom.xml` ;
- matrice des dépendances ;
- POM d'`architecture-tests`.

**Modification prévue**

- Ajouter le module au reactor près des autres modules `infra-*`.
- Déclarer uniquement les dépendances techniques nécessaires.
- Ajouter le module comme dépendance de test d'`architecture-tests`.
- Documenter sa responsabilité dans la matrice des dépendances.

**Pourquoi cette étape appartient au Lot 7.2**

La frontière ne doit pas reposer sur de simples packages placés dans l'infrastructure primaire.

**Dépendances**

Lot 7.1 uniquement.

**Tests**

- Build isolé du module.
- Build du reactor.
- Vérification de l'absence de dépendance vers `infra-persistence-jpa`.

**Critère de sortie**

Le module compile seul et ne crée aucun cycle Maven.

**Rollback éventuel**

Retirer le module du reactor et des tests d'architecture.

### Step 2 — Installer le schéma et l'historique autonomes

**Objectif**

Créer la frontière SQL read-side indépendante.

**Responsabilité architecturale**

Ownership et cycle de migration séparés.

**Modules/fichiers concernés**

Resources Flyway et configuration de migration du nouveau module.

**Modification prévue**

- Ajouter la location read-side dédiée.
- Ajouter la migration d'initialisation sans structure métier.
- Configurer un schéma et un historique read-side distincts.
- Permettre l'exécution du cycle read seul sur PostgreSQL vide.

**Pourquoi cette étape appartient au Lot 7.2**

Les lots suivants doivent ajouter leurs structures sans reprendre la frontière de migration.

**Dépendances**

Step 1.

**Tests**

- Installation read seule sur PostgreSQL vide.
- Présence du schéma et de son historique.
- Redémarrage sans rejeu.
- Absence de table métier.

**Critère de sortie**

Le cycle read s'installe sans ressource primaire.

**Rollback éventuel**

Désactiver le cycle read. Le schéma vide peut rester sans impact.

### Step 3 — Ajouter l'auto-configuration et les accès qualifiés

**Objectif**

Rendre la frontière utilisable par les futurs consommateurs sans doubler l'infrastructure JDBC.

**Responsabilité architecturale**

Composition Flyway, ownership des accès et intention transactionnelle.

**Modules/fichiers concernés**

Sources Spring et déclaration d'auto-configuration du nouveau module.

**Modification prévue**

- Conserver le Flyway primaire auto-configuré.
- Introduire le cycle read-side séparé avec le même `DataSource`.
- Exposer les accès et transactions read-side qualifiés.
- Ne créer aucun repository ou port métier.

**Pourquoi cette étape appartient au Lot 7.2**

La frontière doit être testable avant les premières structures fonctionnelles.

**Dépendances**

Step 2.

**Tests**

- Contexte Spring avec Flyway primaire toujours actif.
- Deux historiques distincts.
- Un seul `DataSource` et un seul transaction manager.
- Résolution non ambiguë des accès qualifiés.
- Rollback complet sur table de test.

**Critère de sortie**

La composition technique fonctionne sans perturber la persistence primaire.

**Rollback éventuel**

Retirer l'auto-configuration et les beans qualifiés.

### Step 4 — Prouver la composition sans câbler les runtimes

**Objectif**

Valider la faisabilité de la future composition runtime avec un blast radius minimal.

**Responsabilité architecturale**

Ordre des migrations et chargeabilité Spring.

**Modules/fichiers concernés**

Tests de contexte et d'intégration du nouveau module.

**Modification prévue**

- Créer une composition Spring de test réunissant cycle primaire et cycle read.
- Prouver l'ordre primaire puis read dans cette composition.
- Prouver séparément l'autonomie du cycle read.
- Ne modifier aucun runtime de production.

**Pourquoi cette étape appartient au Lot 7.2**

Elle valide la frontière sans ajouter une dépendance inutilisée dans trois composition roots.

**Dépendances**

Steps 1 à 3.

**Tests**

- Primary puis read sur base vide.
- Chaque migration exécutée une seule fois.
- Démarrage répété idempotent.
- Auto-configuration désactivable sans affecter le Flyway primaire.

**Critère de sortie**

La future composition runtime est prouvée hors production.

**Rollback éventuel**

Retirer uniquement la composition de test avec le module.

### Step 5 — Verrouiller l'isolation architecturale

**Objectif**

Empêcher les lots suivants de recréer un couplage au primaire.

**Responsabilité architecturale**

Directions de dépendance Java/Maven et autonomie SQL.

**Modules/fichiers concernés**

`architecture-tests` et tests PostgreSQL du nouveau module.

**Modification prévue**

- Interdire à `infra.read.persistence..` de dépendre d'`infra.persistence.jpa..`, des runtimes ou des
  supra.
- Vérifier qu'aucune entité ou structure métier n'est introduite en 7.2.
- Contrôler les dépendances applicatives inter-schémas pertinentes dans les catalogues PostgreSQL.

**Pourquoi cette étape appartient au Lot 7.2**

L'isolation doit être une propriété testée de la fondation.

**Dépendances**

Steps 1 à 4.

**Tests**

- ArchUnit et vérification du graphe Maven.
- Absence de FK read vers primary.
- Absence de vue read reposant sur le primaire.
- Absence de dépendance structurelle applicative vers tables, vues ou séquences primaires.
- Exclusion explicite des objets système, extensions et fonctions partagées des assertions.

**Critère de sortie**

Une dépendance applicative read vers primary interdite fait échouer le build ou le test PostgreSQL.

**Rollback éventuel**

Retirer les règles avec le module ; aucun runtime n'est affecté.

### Step 6 — Valider le caractère strictement structurel du lot

**Objectif**

Prouver qu'aucun comportement existant n'est modifié.

**Responsabilité architecturale**

Préservation des GET, pipelines et persistences Balance.

**Modules/fichiers concernés**

Reactor, POM des runtimes vérifiés en lecture et suites existantes utiles.

**Modification prévue**

- Vérifier qu'aucun runtime ne dépend du nouveau module.
- Ne créer aucun feature flag de query.
- Confirmer qu'aucun controller, handler, locator ou pipeline n'a été modifié.
- Confirmer que Balance continue d'utiliser ses tables actuelles.

**Pourquoi cette étape appartient au Lot 7.2**

Le lot doit rester une fondation isolée et rollbackable.

**Dépendances**

Toutes les étapes précédentes.

**Tests**

- Build du reactor.
- Tests d'architecture.
- Vérification du graphe Maven des runtimes.
- Diff limité au module, aux tests d'architecture et à la documentation structurelle nécessaire.

**Critère de sortie**

Seuls le module, le schéma vide, son historique et leurs tests sont nouveaux.

**Rollback éventuel**

Revenir au commit précédent ; le schéma read vide restant en base est inerte.

## 11. Fichiers et modules probablement concernés

- `app/pom.xml` ;
- nouveau `app/infra-read-persistence/pom.xml` ;
- sources de configuration et resources Flyway du nouveau module ;
- tests PostgreSQL et tests de contexte du nouveau module ;
- `app/architecture-tests/pom.xml` ;
- `HexagonalArchitectureTest` ;
- `docs/architecture/module-dependency-matrix.md`.

Ne doivent pas être modifiés :

- POM et configurations des runtimes ;
- `engine-query` ;
- domaines et pipelines ;
- controllers HTTP ;
- migrations et adapters Balance existants.

## 12. Plan de tests

### 12.1 Installation autonome

- PostgreSQL vide vers installation read seule réussie.
- Schéma et historique read présents.
- Aucune table primaire requise.
- Aucun objet fonctionnel read créé.
- Redémarrage sans rejeu de migration.

### 12.2 Composition primaire et read

- Composition de test primaire puis read réussie.
- Historiques Flyway distincts.
- Chaque cycle exécuté une seule fois.
- Auto-configuration primaire toujours active.
- Ordre runtime prouvé sans être requis par le module read seul.

### 12.3 Isolation

- Aucune FK read vers une table primaire.
- Aucune vue ou vue matérialisée read reposant sur une table/vue primaire.
- Aucune dépendance structurelle applicative vers table, vue, séquence ou autre objet métier primaire.
- Aucune référence SQL empêchant l'installation autonome du read store.
- Aucune dépendance Java ou Maven du module read vers `infra-persistence-jpa`.

Les requêtes de catalogue ciblent les objets applicatifs connus. Elles excluent `pg_catalog`,
`information_schema`, les objets système, les dépendances d'extensions et les fonctions partagées qui
ne constituent pas un couplage métier. Le test ne bannit pas globalement toute ligne `pg_depend`
pointant vers `public`.

### 12.4 Transactions

- Commit nominal dans une table créée uniquement par le test.
- Exception après une première écriture : rollback complet.
- Exception entre deux écritures : aucune écriture partielle.
- Accès read explicitement qualifiés.
- Un seul datasource, pool et transaction manager.

### 12.5 Reactor et non-régression

- Compilation du nouveau module seul.
- Compilation du reactor.
- Tests ArchUnit verts.
- Absence de cycle Maven.
- Aucun runtime de production ne dépend du nouveau module.
- Aucun GET, consumer, projector ou pipeline ajouté ou modifié.

## 13. Critères de sortie

- Une frontière read-side explicite existe dans le reactor.
- Le cycle read seul installe son schéma sur PostgreSQL vide.
- Le schéma et l'historique Flyway read sont séparés du primaire.
- L'ordre primaire puis read est prouvé dans une composition de test, sans devenir une dépendance du
  module read.
- Aucune dépendance structurelle applicative read vers primary n'existe.
- Les accès read-side sont explicitement identifiables.
- La transaction read-side possède une frontière d'intention claire sans prétendre fournir une
  isolation physique.
- Aucun runtime de production n'est câblé en 7.2 ; le wiring est différé au premier consommateur.
- Aucun GET ni pipeline métier n'a changé.
- Aucun watermark, `ProjectionState`, `ProjectionHead`, PotProjection ou index métier n'a été créé.
- Les artifacts et pipelines Balance actuels restent inchangés.
- Aucune infrastructure de séparation physique future n'est sur-implémentée.
- Les choix techniques peuvent évoluer sous contrainte vérifiée sans rouvrir les invariants.
- Les Lots 7.3 et suivants peuvent construire leurs structures sur cette frontière.

## 14. Rollback

Le rollback applicatif consiste à retirer :

- le module du reactor ;
- l'auto-configuration et les accès qualifiés ;
- les tests et règles d'architecture associés.

Le schéma read-side ne contenant que son historique peut rester sans effet. Sa suppression est une
opération administrative destructive et optionnelle, jamais une condition du rollback applicatif.

Aucune table existante n'est déplacée ou modifiée ; aucune restauration de données n'est requise.

## 15. Risques

- Le second cycle Flyway pourrait perturber l'auto-configuration primaire.
- Un test de contexte incomplet pourrait valider une hypothèse erronée sur Spring Boot.
- L'ordre primaire puis read d'une composition runtime pourrait être confondu avec une dépendance
  intrinsèque du module read.
- Le qualifier transactionnel pourrait être interprété à tort comme une isolation physique.
- Un test `pg_depend` trop large pourrait produire des faux positifs sur des objets système ou
  partagés.
- Un wiring runtime prématuré augmenterait inutilement le blast radius.
- Un nom de module trop technologique pourrait figer JPA avant son introduction effective.
- Une future migration non qualifiée pourrait créer un objet dans le schéma primaire.
- Une migration accidentelle des artifacts Balance étendrait le lot au Lot 7.12.

## 16. Hors scope

- Modèle générique de projection.
- `ProjectionState`, `ProjectionHead` et `SourceVersionWatermark`.
- PotProjection et ses fragments.
- Indexes utilisateurs, Expense ou Balance.
- Nouveau consumer Event ou Task worker.
- Migration ou cutover Balance.
- Wiring des runtimes de production.
- Bascule des GET, contrats HTTP et autorisation.
- Deuxième PostgreSQL, pool JDBC ou transaction distribuée.
- Outbox ou transport inter-store.
- Comptes PostgreSQL de production séparés.
- Backfill, rebuild fonctionnel, pipeline versions et GC.

## 17. Décisions et ajustements permis

Aucun arbitrage architectural n'est ouvert.

Les choix techniques décrits dans ce plan constituent l'implémentation de référence du Lot 7.2.
Ils peuvent être ajustés pendant l'exécution uniquement si une contrainte concrète et vérifiée du
framework, du reactor ou du runtime l'exige, sans modifier les invariants du lot.

Defaults de référence :

- module : `infra-read-persistence` ;
- artifact : `pocoma-infra-read-persistence` ;
- schéma : `pocoma_read` ;
- location : `db/read-store/migration` ;
- qualifier : `@ReadStore` ;
- même datasource et même transaction manager ;
- migrateur read dédié préservant l'auto-configuration primaire ;
- wiring runtime différé.

Tout ajustement est justifié dans `Targeted code inspections` et couvert par un test équivalent.

## 18. Targeted code inspections

| Question | Fichiers consultés | Conclusion |
|---|---|---|
| Le plan existe-t-il dans le checkout ? | `docs/plans`, arbre Git de `HEAD` | Le fichier 7.2 était absent ; le chemin canonique demandé doit être créé sans second plan. |
| Le suffixe JPA est-il justifié en 7.2 ? | `app/pom.xml`, POM des modules `infra-*` | Non à ce stade : `infra-read-persistence` décrit Flyway/JDBC/transactions sans empêcher JPA ultérieurement. |
| Un wiring runtime immédiat apporte-t-il une preuve indispensable ? | POM de web, Event worker et Task worker | Non : aucun runtime n'a de consommateur read en 7.2 ; un test isolé offre la preuve avec moins de blast radius. |
| La composition Flyway peut-elle perturber le primaire ? | Métadonnées Spring Boot Flyway 4.0.5 et propriétés runtime | Oui si un bean `Flyway` fait reculer l'auto-configuration ; la mécanique exacte doit être validée par tests de contexte. |
| Le qualifier crée-t-il une isolation physique ? | Configurations transactionnelles et JDBC des runtimes | Non : les runtimes utilisent actuellement un seul datasource et un seul transaction manager. |
| Où vivent les migrations actuelles ? | migrations V1–V3 de `runtime-monolith`, V4+ d'`infra-persistence-jpa`, POM runtime | Les migrations primaires sont assemblées sous `db/migration`; le read store nécessite une location et un historique séparés. |
| Quel couplage Balance doit rester ? | migration Balance actuelle et adapters immuables | Les artifacts actuels restent dans le schéma primaire et ne sont ni déplacés ni modifiés en 7.2. |
| Quels garde-fous sont conventionnels ? | `module-dependency-matrix.md`, `HexagonalArchitectureTest` | ArchUnit et les tests PostgreSQL ciblés sont les mécanismes adaptés pour verrouiller la frontière. |

## 19. Vérifications documentaires finales

Avant validation du plan et avant clôture de son implémentation :

- aucun détail technique n'est présenté comme invariant irrévocable ;
- architecture verrouillée et implémentation de référence ajustable sont distinguées ;
- installation read autonome et ordre runtime primaire vers read sont distingués ;
- le wiring runtime est explicitement différé au premier consommateur ;
- `@ReadStore` est décrit comme frontière d'intention, pas comme isolation physique ;
- les tests PostgreSQL visent les dépendances métier interdites sans bannir aveuglément tout lien vers
  `public` ;
- aucun modèle fonctionnel, artifact Balance, GET ou pipeline n'entre dans le Lot 7.2.
