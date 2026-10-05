# POST-WP6 — Final Modularity Closure

Date : 2026-10-05

Baseline : `2fdfbf92baa35fbcad18f028c8de473e8f49069b`

Portée : correction strictement bornée des findings B-01, B-02 et M-01 à M-03 du [Final Independent Audit](Modularity_Final_Independent_Audit.md). L'audit historique et son verdict FAIL ne sont pas réécrits.

## 1. Baseline

Avant toute édition :

| Contrôle | Résultat |
| --- | --- |
| `git fetch origin` | PASS |
| branche | `v2-make-it-pull` |
| `git status --short` | vide |
| `git rev-parse HEAD` | `2fdfbf92baa35fbcad18f028c8de473e8f49069b` |
| `git rev-parse origin/v2-make-it-pull` | `2fdfbf92baa35fbcad18f028c8de473e8f49069b` |
| divergence | `0/0` |

La politique `docs/testing/Reactor_Verification_Policy.md`, les six sources d'architecture demandées et le registre de dette ont été lus avant analyse et modification.

## 2. Pré-audit ciblé

Cette matrice a été établie avant la première édition.

| Finding | Concrete code | Consumers | Current owner | Expected TARGET owner | Root cause | Proposed action |
| --- | --- | --- | --- | --- | --- | --- |
| B-01 | `ClaimToken`; `Claim.compatibilityKey` et surcharges/transitions dépréciées; constructeur/mapping historique de `ConsumptionKey`; transitions simplifiées de `ConsumptionSlot`; surcharge directe de discovery à usage test | uniquement tests legacy, auto-références de la couche de compatibilité et reconstruction JPA d'un champ toujours vide; aucun consommateur métier de production | `domain-consumption`, plus une surcharge d'adapter PRIMARY | primitives/protocole génériques dans `domain-consumption`; coordonnées de segmentation primitives sur le port Event | le nettoyage WP6 a supprimé les POM legacy mais a conservé une API transitoire désormais sans consommateur | migrer les tests vers l'API TARGET explicite, supprimer le dead/legacy et verrouiller la surface générique |
| B-02 | dépendance POM `runtime-event-consumption-worker → engine-consume-command`; import test `CommandTerminalEventTypes` | un seul test de policy; aucune source `main`, aucun bean, aucun wiring | runtime Event | aucun owner Event : Command reste dans son engine/runtime | une assertion négative redondante a promu une dépendance de test en dépendance de production | supprimer l'assertion/import et la dépendance; guard POM Event !→ Command |
| M-01 | document Consumption; commentaires B16 et deux commentaires Consumption | lecteurs/mainteneurs | documentation active et sources | owners TARGET existants | formulations de migration restées actives après rehome/suppression | currentiser uniquement ces formulations prouvées fausses |
| M-02 | agrégation naïve de tous les `target/surefire-reports`, y compris `domain-pot-policy/target` supprimé | rapport WP6 | processus de preuve | reactor CURRENT déclaré par `app/pom.xml` | les outputs ignorés d'anciens modules ne sont pas nettoyés par le reactor courant | `mvn clean`, isoler les `target/` orphelins, compter uniquement les modules enfants et rapports du run courant |
| M-03 | `LatestKnownVersionRuntimePostgresTest.consumesOutOfOrder...` | documentation de preuve | runtime LKV test | runtime LKV | le nom confond ordre d'insertion durable 10,12,11 et ordre d'application normalisé 10,11,12 | renommer sans changer l'assertion ni la sémantique |

## 3. Déclaration de vérification

- Slices primaires : EVENT et LKV.
- Slices secondaires : generic Consumption, COMMAND, PROJECTION, BINDING, Registration et Direct Results, car l'API générique est dans leur closure.
- Impacts interdits : SQL/schema/migration, nouveau runtime/famille/pattern transverse, modèle métier, refonte Event/Command/Consumption, nouvelle projection ou journey.
- Tests ciblés : les huit runtimes Consumption et leur closure; après suppression du dernier helper mort, slice Event et PRIMARY directement affectée.
- PostgreSQL : PRIMARY Consumption/discovery, Event→ProjectionTask, Event→LKV, runtimes affectés et journeys A–E/C2.
- Architecture gate : REQUIRED, car un POM et deux invariants de frontière changent.
- Full reactor : REQUIRED, car ce lot est la gate finale de clôture.
- STOP : owner TARGET absent, dépendance Event→Command sémantique durable, abstraction/SQL/migration nécessaires, journey structurellement cassé, bridge restant ou CURRENT/TARGET encore divergent.

Aucun franchissement inattendu de slice n'a eu lieu. La suppression de la surcharge de discovery test-only est le résultat direct de l'inventaire exhaustif B-01 et reste dans les slices PRIMARY/EVENT déclarées.

## 4. B-01 — inventaire et classification

| Symbol / surface | Historical origin | Current consumers at baseline | TARGET equivalent | Classification | Action |
| --- | --- | --- | --- | --- | --- |
| `ClaimToken` | identité de claim antérieure, dupliquant `ClaimId` | `Claim` legacy, son propre test, guard négatif | `ClaimId` | LEGACY COMPATIBILITY | supprimé |
| `Claim.compatibilityKey` | contexte de clé embarqué avant navigation durable par slot | transitions internes; JPA lui passait toujours `Optional.empty()` | `slotId` puis chargement du `ConsumptionSlot` | LEGACY COMPATIBILITY | composant supprimé; reconstruction JPA alignée |
| `Claim.active(...ConsumptionKey...)`, variante `ClaimToken`, `token`, `consumptionKey`, `withCompatibilityKey`, `isOwnedBy(ClaimToken, ...)` | façades de migration de l'ancien moteur | tests legacy ou auto-références uniquement | factory explicite `ClaimId + slotId + WorkerId + attempt + lease`; fencing par `ClaimId` | LEGACY COMPATIBILITY | supprimés |
| `Claim.endAt(Instant)` et `invalidateAt(Instant)` | transitions simplifiées de l'ancien lifecycle | aucun consommateur de production | `succeedAt`, `failAt`, `invalidateForTakeoverAt`, `abandonAt` | LEGACY COMPATIBILITY | supprimés |
| `ConsumptionSlot.initial(ConsumptionKey)` et `legacySlotIdFor` | slot synthétique sans identité durable explicite | tests legacy et factory Claim legacy | `initial(UUID, ConsumptionKey, Instant)` | LEGACY COMPATIBILITY | supprimés |
| `ConsumptionSlot.acquired/completed/failed/released` | états simplifiés de l'ancien moteur | aucun consommateur de production | `withCurrentClaim`, `terminalize`, `reschedule`, `abandon` | LEGACY COMPATIBILITY | supprimés |
| `ConsumptionKey(String,List)`, `namespace`, `components`, helpers `legacy*` | traduction implicite Command/Event de l'ancien namespace | tests legacy uniquement | `ConsumableIdentity + ConsumerIdentity` | LEGACY COMPATIBILITY | supprimés |
| surcharge adapter `findCandidates(...WorkerSegment...)` | helper « Compatibility API for CURRENT callers » | tests directs uniquement | port `findCandidates(...segmentIndex, segmentCount...)` | DEAD CODE | tests migrés; surcharge supprimée |
| `ConsumableIdentity`, `ConsumerIdentity`, `ConsumptionKey` explicite | modèle TARGET | engines, supras et infra | identique | TARGET GENERIC PROTOCOL | conservés |
| `ClaimId`, `ClaimLease`, `WorkerId`, `Claim`, `ConsumptionSlot`, statuts/outcomes/reasons, `WorkerSegment` | modèle durable générique | engine/orchestrateurs/supras/infra | identique | TARGET CORE PRIMITIVE | conservés |

La responsabilité recherchée par les rares usages était soit une commodité de test, soit l'identité/transition TARGET déjà disponible. Il n'existait ni capability métier cachée ni owner TARGET manquant. La migration rejoint donc les abstractions explicites existantes; aucune API legacy renommée n'est créée.

Séparation finale inchangée : `domain-consumption` porte les primitives/protocoles, `engine-consumption` les mécaniques d'exécution, `orchestrator-consumption` un traitement et `orchestrator-poll-consumption` la répétition. Les capabilities restent dans leurs supras/engines.

### Guard B-01

`Wp5TargetTopologyTest.domainConsumptionExportsOnlyTheExplicitTargetProtocolSurface` vérifie : absence d'API `@Deprecated` dans la surface production du domaine, absence physique de `ClaimToken`, et composants exacts des records `Claim`, `ConsumptionKey` et `ConsumptionSlot`. Le guard protège la cause réelle — réintroduction d'un contexte/identifiant/lifecycle parallèle dans la surface générique — plutôt qu'un simple nom de module.

## 5. B-02 — trace, classification et correction

Trace exacte à la baseline :

1. source POM : `runtime-event-consumption-worker/pom.xml`;
2. target POM : `engine-consume-command/pom.xml`;
3. déclaration : dépendance Maven de scope production `pocoma-engine-consume-command`;
4. sources `main`/imports : aucune;
5. beans et wiring Spring : aucun;
6. seul usage : `PocomaProjectionMaterializationPolicyTest` importait `CommandTerminalEventTypes` dans une boucle négative;
7. sémantique réelle : le test prouve la policy Event→ProjectionTask; son assertion d'égalité de routes excluait déjà les types terminaux Command.

Classification : **A — stale dependency only**. Le runtime Event n'invoque aucune capability Command. Aucune responsabilité ne doit être rehomée et aucune décision architecturale nouvelle n'est requise.

Correction : suppression de l'import et de l'assertion redondante, puis de la dépendance POM. La chaîne Event reste `runtime-event-consumption-worker → supra-consume-event → engine-produce-projection-task`; Command reste dans sa capability et son runtime.

Guard : `Wp5TargetTopologyTest.graphAndRoleBoundariesHaveNoCyclesOrForbiddenArcs` interdit explicitement `runtime-event-consumption-worker → engine-consume-command`, au niveau POM qui avait permis le défaut.

## 6. Findings mineurs

| Finding | Root cause | Change | Proof | Status |
| --- | --- | --- | --- | --- |
| B-01 | compatibilité transitoire laissée dans un POM TARGET après disparition des consommateurs | API legacy/dead supprimée; tests et JPA alignés; surface TARGET conservée | tests Consumption/PRIMARY/runtimes, guard exact, scans | RESOLVED |
| B-02 | dépendance de production ajoutée pour une constante de test | import/assertion et dépendance supprimés | slice Event, journeys E, guard POM, graphe | RESOLVED |
| M-01 | texte de migration non currentisé | documentation Consumption et commentaires B16/Consumption corrigés | scan actif classifié | RESOLVED |
| M-02 | rapports ignorés de modules supprimés agrégés avec CURRENT | clean reactor + isolement de 40 `target/` orphelins + compteur limité aux enfants Maven | 0 rapport orphelin; 1061/1061 CURRENT | RESOLVED |
| M-03 | nom fondé sur l'ordre d'insertion plutôt que l'ordre d'application | renommé en `convergesToTwelveFromDurableInsertionOrderTenTwelveElevenWithoutCreatingProjectionWork` | résultat 12 inchangé; test JDBC high→low conservé | RESOLVED |

## 7. M-02 — méthode reproductible

La cause observée est précise : `./mvnw clean` ne visite que les enfants CURRENT et laisse les `target/` d'anciens modules supprimés. Après le clean CURRENT, 40 répertoires `target/` orphelins subsistaient; `domain-pot-policy/target` contenait les 22 tests qui avaient transformé le total WP6 1065 en 1087.

Procédure finale :

1. exécuter `./mvnw clean` depuis `app/`;
2. détecter les `app/*/target` dont le parent n'est plus un enfant de `app/pom.xml`;
3. les isoler hors du repository — ici dans `/private/tmp/pocoma-post-wp6-stale-targets.8pYexr` — sans supprimer de fichier suivi;
4. vérifier qu'il reste zéro rapport;
5. exécuter `./mvnw test`;
6. parser uniquement `target/surefire-reports/TEST-*.xml` des 58 enfants déclarés par `app/pom.xml` et vérifier leur appartenance au run courant.

Résultat du run final : 230 fichiers de rapport, **1061 tests, 0 failure, 0 error, 0 skipped**, zéro rapport orphelin. La baisse nette depuis les 1065 tests de l'audit est expliquée : cinq tests consacrés exclusivement aux APIs legacy supprimées disparaissent et un guard d'architecture est ajouté.

## 8. Vérifications exécutées

| Commande | Portée | Résultat |
| --- | --- | --- |
| `./mvnw -pl runtime-event-consumption-worker,runtime-latest-known-version-consumption-worker,runtime-task-consumption-worker,runtime-command-consumption-worker,runtime-binding-consumption-worker,runtime-registration-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker -am -DskipTests compile` | toutes les slices Consumption affectées | PASS |
| même sélection avec `-am test -q` | tests ciblés générique, one-treatment, polling et supras/runtimes spécifiques | PASS |
| `./mvnw -pl runtime-event-consumption-worker -am test -q` | suppression finale du helper dead PRIMARY/Event | PASS |
| `./mvnw -pl architecture-tests -am test -q` | gate global et journeys | PASS, 120/120 |
| `./mvnw clean` | élimination des outputs CURRENT avant preuve finale | PASS |
| `./mvnw test` | full reactor propre | PASS, 1061/1061 |
| `git diff --check` | intégrité patch | PASS |

Les avertissements initiaux sur `/var/run/docker.sock` sont non bloquants : Testcontainers sélectionne ensuite le socket utilisateur Docker et les suites PostgreSQL passent.

## 9. Final Architectural Journeys

Les journeys sont réexécutés par `architecture-tests`, notamment `CommandCompletionE2EPostgresTest`, `CommandResultConsumptionChainPostgresTest` et les preuves Event→Projection, ainsi que par le test runtime LKV.

| Journey | Résultat |
| --- | --- |
| A Registration → Binding → CURRENT_BINDING | PASS |
| B Command → Event → Projection → GET Pot | PASS |
| C Command → Command Result | PASS |
| D Registration → Registration Result | PASS |
| E Event → LKV / Projection indépendants | PASS |
| C2 arrivées durables V10,V12,V11 → LKV final 12 | PASS; application runtime normalisée 10,11,12 et robustesse high→low prouvée au store |

## 10. Graphe et réconciliation CURRENT/TARGET

Le calcul XML parcourt les 58 enfants déclarés, exclut `test` et `provided` pour le graphe de production, vérifie chaque artifact interne et détecte les cycles.

| Mesure | Résultat final |
| --- | ---: |
| POM enfants | 58 |
| POM production | 57 |
| POM verification | 1 |
| arcs internes directs de production | 226 |
| dépendances internes tous scopes | 234, dont 8 de test |
| cycles | 0 |
| dépendances internes manquantes | 0 |
| TARGET→legacy | 0 |
| POM legacy/provisoire | 0 |
| embedded legacy compatibility clusters | 0 |
| unexplained production arcs | 0 |

Le nombre 234 des rapports WP6/audit mélangeait les 8 dépendances internes de test avec les arcs de production. La définition écrite par l'audit exclut pourtant `test` et `provided`; le nombre reproductible correspondant est 227 à la baseline puis **226** après retrait de B-02. Le total tous scopes final reste 234.

La topologie et la traçabilité CURRENT/TARGET sont mises à jour avec cette distinction. L'arc Event→Command est **REMOVED**. Aucun owner, flux ou invariant TARGET ne change.

## 11. Scan legacy/TBD et SQL

Recherche active : `TBD|TODO|FIXME|provisional|temporary|compatibility|legacy|bridge|unresolved` sur les sources `main`, la documentation d'architecture active et les documents CURRENT/TARGET.

- zéro `@Deprecated`, `ClaimToken`, `compatibilityKey`, factory/transition legacy ou constructeur historique dans `domain-consumption`;
- zéro import/dépendance/wiring Event→Command;
- `temporary` dans Claim/Projection décrit une durée de lease ou une classe d'échec opérationnelle;
- les mentions `legacy` restantes dans migrations, adapters JSON/SQL et documents de démolition décrivent des formats physiques/historiques explicitement conservés ou supprimés, pas une API de migration active;
- le TODO d'autorisation Create Pot est une extension produit antérieure, pas une frontière Modularity;
- les `TBD-*` des matrices historiques de la topologie sont explicitement superseded puis RESOLVED par les deltas normatifs;
- les mentions ajoutées dans ce rapport nomment les findings clôturés.

Conclusion : **architectural TBDs = 0; implicit closure items = 0**.

`git ls-files '*.sql'` retourne 47 fichiers. Le diff contient zéro fichier SQL, schema ou migration; aucune modification sémantique, aucun ajout/suppression/rehome.

## 12. Fichiers et dette

| Catégorie | Fichiers |
| --- | --- |
| production | `domain-consumption/.../Claim.java`, suppression de `ClaimToken.java`, `ConsumptionSlot.java`, `ConsumptionKey.java`; `JpaConsumptionLifecycleAdapter.java`; `JdbcProjectionMaterializationDiscoveryAdapter.java`; commentaires ciblés dans `PotCommandInputMapper.java`, `PotCommandInvocation.java`, `AcquireResult.java`, `ConsumptionLifecyclePersistencePort.java` |
| POM | `runtime-event-consumption-worker/pom.xml` uniquement |
| tests | `Wp5TargetTopologyTest.java`; `ClaimTest.java`; suppression de `ClaimTokenTest.java`; `ConsumptionSlotTest.java`; `ConsumptionKeyTest.java`; `JdbcProjectionMaterializationDiscoveryAdapterPostgresTest.java`; `PocomaProjectionMaterializationPolicyTest.java`; `ProjectionMaterializationConsumptionPostgresTest.java`; `LatestKnownVersionRuntimePostgresTest.java` |
| documentation | `docs/architecture/consumption-transactional-execution.md`; `Modularity_Migration_Plan.md`; `Modularity_Target_Topology.md`; `Modularity_Current_Target_Traceability.md`; présent rapport |
| SQL/schema/migrations | aucun fichier |

Le registre de dette n'est pas muté pendant ce passage. Recommandation après preuves : **DEBT-MOD-01 READY TO CLOSE**, sous réserve du re-audit final indépendant. `DEBT-WA6-01` et `DEBT-WA6-02` ne sont pas modifiées.

## 13. Verdict

POST-WP6 FINAL MODULARITY CLOSURE: DONE

B-01 EMBEDDED CONSUMPTION LEGACY: RESOLVED
B-02 EVENT→COMMAND ARC: RESOLVED
M-01 DOCUMENTATION: RESOLVED
M-02 TEST ACCOUNTING: RESOLVED
M-03 LKV TEST NAMING: RESOLVED

POMs: 58 children / 57 production / 1 verification
INTERNAL ARCS: 226 production / 234 all scopes
CYCLES: 0
MISSING INTERNAL DEPS: 0
TARGET→LEGACY: 0
LEGACY/PROVISIONAL POMs: 0
EMBEDDED LEGACY COMPATIBILITY: 0
UNEXPLAINED PRODUCTION ARCS: 0
ARCHITECTURAL TBDs: 0

ARCHITECTURE GATE: PASS — 120/120
FINAL JOURNEYS: PASS — A, B, C, D, E, C2
CLEAN FULL REACTOR: PASS — 1061/1061
SQL/SCHEMA/MIGRATIONS: UNCHANGED — 47 tracked SQL files

DEBT-MOD-01: READY TO CLOSE
MODULARITY MIGRATION: READY FOR FINAL RE-AUDIT
