# A2-1 — Contrat applicatif Consumption et identité des slots

2026-10-22 — **PLAN PROPOSÉ, EN ATTENTE DE VALIDATION ARCHITECTURALE. Aucune implémentation commencée.**
Branche : `feat/consumption-transaction-protocol`. Référence CURRENT inspectée :
`ac1a895af02190886814318ae49d63105297af00` ; divergence initiale après fetch : **0/0**.

## 1. Autorité et CURRENT vérifié

Référence conceptuelle : [A2 minimal amendé](2026-10-22-a2-minimal-consumption-discovery.md),
importé **à l'identique** depuis `90a3e9bc1a1fdd325d0c36e543824239cd85a431`, sans merge.
Lire avec le [canon](../steps/current/DISCOVERY/Step_Canon.md), le [plan courant](../steps/current/DISCOVERY/Step_Plan.md),
[A1](../architecture/Discovery_A1_Contract_2026-10-21.md) et la [politique Reactor](../testing/Reactor_Verification_Policy.md).
[A2-P](../architecture/Discovery_A2_Implementation_Plan_2026-10-21.md) sert d'inventaire technique : ses propositions de registre/configuration ne gouvernent pas ce lot.

Travail dans le worktree propre d'une **copie Git indépendante**, `/private/tmp/pocoma-a2-1-plan`,
créée depuis le dépôt local puis synchronisée avec l'origin GitHub. Ce choix préserve les cinq
worktrees existants, dont le principal sur `feat/runtime-monolith` avec ses modifications et le
worktree Discovery avec son audit non suivi. Aucune modification dans ces worktrees, aucun stash/reset/nettoyage.

CURRENT signifie ici **lecture des sources à ce SHA**, sans nouvelle certification runtime/DB :

| Constat | Source contrôlée, sous `app/` |
|---|---|
| Pas de définition applicative `Consumption` ni de `ConsumptionId` | `domain-consumption/.../key/ConsumptionKey.java` = `ConsumableIdentity` + `ConsumerIdentity`, composants structurels ; `ConsumptionSlot` porte slot UUID, état, claim courant |
| Un slot Event par projection | `supra-consume-event/.../materialization/ProjectionMaterializationConsumptionKeys.java` : `EVENT[eventId] / PROJECTION_TASK_MATERIALIZER[projectionType]` |
| Discovery actuelle sans E/checkpoint | `infra-persistence-primary-jpa/.../adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java` : routes métier, curseur date/UUID/projection, anti-jointure JSONB DONE |
| Acquisition crée puis verrouille le slot | `JpaConsumptionLifecycleAdapter.acquire` : `insertInitial` idempotent, `findByKeyForUpdate`, échéance/Busy, takeover, nouveau claim et installation |
| Exécution/finalisation non liées à une définition | `ExecuteConsumptionInput` reçoit une fonction ; `FinalizeConsumptionInput` reçoit slotId/claimId/effet. `ExecuteConsumptionService` appelle l'exécution avant le finaliseur ; celui-ci applique effet/provenance avant CAS |
| Identité durable à quatre colonnes | V4 : unique `(consumable_type, consumable_components, consumer_type, consumer_components)` ; aucune colonne `consumption_id` ou `partition_id`. V6/V7 complètent raisons et erreurs |
| Fencing livré | `JpaConsumptionSlotRepository.terminalize/scheduleRetry` : PENDING + current_claim_id ; flush automatique avant CAS ; clôture exactement un claim dans `JpaConsumptionLifecycleAdapter` |
| Pilote possible sans changer les Events métier | `domain.pot.event.RecordedEvent` porte eventId/date ; `BusinessEvent` porte potId/version/type. `JpaEventPort` sait recharger, mais son port appartient à `supra-consume-lkv` |
| Sorties existantes | `ProduceProjectionTaskService.ensure` traite une conséquence ; `ProjectionTaskStorePort.ensure(ProjectionKey, Instant)` déduplique. Politique actuelle : AUTH, READ_POT, BALANCES sur les types connus ; aucun besoin de changer leur exécution |

Les [rapports 4A](../architecture/Event_Publication_Contracts_Step_4A_2026-10-19.md),
[4B](../architecture/Event_Transaction_Protocol_Step_4B_2026-10-20.md),
[4C](../architecture/Event_Transactional_Sequencer_Step_4C_2026-10-20.md),
[4D](../architecture/Event_Producer_Wiring_Step_4D_2026-10-20.md) et
[4E](../architecture/Event_Sequencing_Step_4E_2026-10-20.md), leurs preuves liées et la
[clôture 4E](../testing/evidence/Event_Sequencing_Step_4E_Functional_Closure_2026-10-20.json)
établissent historiquement acceptations explicites, owner PRIMARY commun JPA/JDBC, flush/fermeture puis suffixe E,
poisoning et COMMITTED/ROLLED_BACK/UNKNOWN. Les gates de clôture 4E publiés sont 1230/1388/1388 tests,
0 failure/error, un skip 3B historique ; **ils ne sont pas réexécutés ici**.
4A–4E sont CLOSED/PASS fonctionnel ; activation production NON AUTORISÉE, réserves R1–R6 conservées.

## 2. Livraison proposée et contrats

**Livrer un chemin explicite dormant, exercé par tests**, utilisant de vrais slots/claims, et le contrat
Discovery sans son implémentation E. Le Locator A2-1 acquiert un **slot précis déjà enrôlé** ; la sélection
paginée des slots dus par partition reste au lot de reprise. Aucun worker n'est raccordé au nouveau chemin.

| Type / contrat proposé | Contenu et responsabilité |
|---|---|
| `ConsumptionId` | record UUID non nul ; pas de génération automatique dans la définition. Constante Java partagée entre réplicas ; nouvelle génération de règles retraitant l'historique = nouvel UUID |
| `Consumption<I,O>` | Définition applicative immuable : id, `NonBurnableFamily<I>`, `Function<I,O>` fixée au constructeur. Pas de sérialisation de fonction, de configuration ou de nom dans l'identité |
| `NonBurnableFamily<I>` | Petit descripteur typé, code canonique de famille ; ici BUSINESS_EVENT. Aucun catalogue dynamique ni enum des familles cibles futures ; burnable non introduit sans usage |
| `ConsumableRef<I>` | `(consumptionId, ConsumableIdentity)` ; identifiant source UUID encodé canoniquement pour le pilote. La forme composite existante reste possible pour une future ProjectionKey |
| `SlotRef<I>` | `(consumptionId, slotId UUID, ConsumableRef<I>)` ; constructeur refuse les deux consumptionId différents |
| `ClaimedSlot<I>` | `SlotRef<I>` + `ClaimId` ; expose C/slot/source/claim. Une référence n'est jamais une preuve d'autorité durable |
| `NonBurnableDiscovery<I>` | Interface liée à C et à la partition : `consumptionId()`, `partitionId()`, `enrol(request)` ; request/résultat portent C/partition/limite et références de slots. Position atteinte éventuelle dans le résultat ; aucun checkpoint ni algorithme livré A2-1 |
| `LocateConsumptionUseCase<I>` | `consumptionId()` et `acquire(consumptionId, SlotRef<I>, WorkerId, ClaimLease)` ; résultat contextualisé : acquis, DONE, Busy, NotReady ou absent. Implémentation `NonBurnableLocator<I>` construite avec C ; aucune création implicite sur ce chemin |
| `RunConsumptionUseCase<I>` | `consumptionId()` et `execute(ClaimedSlot<I>)` ; résultat contextualisé terminal/retry/perte de claim après résolution. `ConsumptionEngine<I,O>` reçoit C, loader autoritatif, writer des sorties, policy et owner transactionnel au constructeur ; aucune fonction dans la requête |

Placer les identités/références pures dans les packages existants `domain.consumption.key` et `.claim`
(JDK seul). Placer définition/descripteur et interfaces applicatives dans `engine-consumption`.
Le moteur reste ignorant de Pot, Event et ProjectionTask. Le pilote et son assemblage Java restent
respectivement dans `engine-produce-projection-task` et `supra-consume-event`, déjà dépendants des types nécessaires.
Aucun module/POM supplémentaire prévu.

Le loader est un port fonctionnel `ConsumableLoader<I>.load(ConsumableRef<I>)`, exécuté dans TE :
il exige source existante, famille et identité exactes. Le writer, fixé au constructeur, reçoit entrée
rechargée et sorties (`BiConsumer<I,O>` suffit) et participe à TE. Le résultat technique conserve les
issues/raisons existantes ; SUCCESS à zéro sortie est valide, inconnu/invalide reste une erreur explicite.
Les contrôles de cohérence des trois composants se font localement à leur construction ; pas de Worker nouveau dans A2-1.

### Identité dans les slots existants

Proposition **sans DDL** pour les nouveaux slots :

```text
consumable_type       = "BUSINESS_EVENT"
consumable_components = [eventId.toString()]       // UUID minuscule canonique
consumer_type         = "CONSUMPTION"              // namespace réservé du nouveau chemin
consumer_components   = [consumptionId.toString()]
```

`Consumption.key(ref)` contrôle C et la famille puis produit la `ConsumptionKey` canonique ; il est partagé
par les futurs enrôlement, acquisition et relecture. La partition ne figure jamais dans cette clé.
Pour C fixé et mono-famille, l'unique existant représente exactement `(C, sourceId)` ; C1≠C2 donne
deux slots distincts même sur le même Event. Famille, arité, ordre et casse UUID sont vérifiés à la
frontière Java et à la relecture ; une représentation durable non canonique est refusée, jamais réparée implicitement.

Cette bijection suppose l'association immuable **C → famille/règles**, portée par la constante Java.
Sans registre/manifeste, aucune détection globale de deux binaires incompatibles réutilisant le même UUID
n'est revendiquée. La discipline de génération et sa revue sont nécessaires. Aucun index de reprise ni
persistance de partition ajouté maintenant ; le choix relationnel/indexé sera évalué au lot concerné.

### Garde durable et conservation du protocole

1. **TA** : vérifier C et les références avant accès ; charger/verrouiller le slot existant avec **slotId et clé complète**.
   Absent ou identité différente : refus, aucun INSERT de remplacement. Réutiliser échéance, Busy, takeover,
   numéro de tentative et installation du claim sous slotId+clé complète (méthode contextualisée). Retourner le `ClaimedSlot` construit depuis la ligne durable, après commit TA.
2. **TE** : refuser C incohérent ; relire sans verrou terminal anticipé le slot par C/slotId/clé complète,
   PENDING et claim courant ; vérifier aussi claim.slotId, état ouvert/non invalidé et source demandée.
   Seulement ensuite recharger la source et appeler **la transformation de C** puis le writer fixé.
3. Flush des effets, puis **le suffixe commun** : CAS PENDING/current_claim_id enrichi de la clé complète attendue,
   clôture exactement un claim. Retry sous le même périmètre C/slot/source/claim. Zéro ligne ou clôture refusée
   lève et annule toute TE ; aucune action métier après CAS/clôture. Factoriser le suffixe existant, sans second moteur de fencing.
4. Une erreur d'identité est un refus de protocole, jamais un FAILED/retry de l'autre Consumption.
   Une erreur technique ne conduit à TH qu'après rollback confirmé ; TH revérifie C/slot/claim et applique la policy existante.
   La lease expirée autorise takeover, elle ne retire pas seule l'autorité ; revision n'est pas un token.
5. Réutiliser les owners certifiés : garde d'ambient `ConsumptionOwnerTransactionRunner`, manager PRIMARY et participants
   MANDATORY/REQUIRED communs. Pour la composition pilote de test, entourer `PrimaryEventTransactionRunner` par cet owner
   afin de conserver le marqueur COMMIT UNKNOWN 4E ; lot E vide, sans allocation. Ne pas modifier les runners.
   APPLIED exige COMMITTED confirmé ; COMMITTED avec exception/UNKNOWN n'autorise ni replay ni FAILED automatique.
   Préserver P → B → S et S/claim → flush/fermeture → E chez les consommateurs existants.

**Fermer le contournement legacy est nécessaire.** Les anciennes acquisitions à clé structurelle refusent
le namespace CONSUMPTION. Les anciennes entrées Execute/Finalize/Failure rechargent et refusent ce namespace
**avant tout callback** ; les mutations SQL legacy l'excluent aussi. Ajouter explicitement le query port
aux constructeurs Execute/Finalize qui en ont besoin, sans constructeur permissif/default no-op.
Le nouveau moteur utilise les méthodes contextualisées du lifecycle ; le callback interne éventuel du
suffixe est construit par le service, jamais fourni par un message. Les autres clés historiques restent acceptées.

**Preuve conceptuelle C1/C2.** Le Locator C2 refuse une SlotRef C1. Changer seulement son C en C2 ne change
pas la clé durable du slot : lecture/verrouillage par slotId+clé C2 ne trouve rien, aucun claim installé.
Engine C2 refuse un ClaimedSlot C1 ; un message entièrement réétiqueté C2 portant slotId/claimId de C1 échoue
à la relecture avant transformation. Un mélange source/slot/claim échoue également. Si takeover ou incohérence
survient après lecture, le CAS complet et la clôture protègent encore le commit et annulent tous les effets perdants.
Cette preuve vaut entre deux définitions de **la même famille**, pas seulement entre types Java différents.

## 3. Pilote Java concret — BUSINESS_EVENT → ProjectionTasks

Extrait **proposé, non compilé/non livré** ; imports omis. `BusinessEvent` ci-dessous est le contrat
`domain.pot.event.BusinessEvent` et l'entrée conserve l'enveloppe durable existante, sans ajouter un UUID au métier.
La transformation peut être portée par `BusinessEventToProjectionTasks.java` :

```java
static final NonBurnableFamily<RecordedEvent<? extends BusinessEvent>> BUSINESS_EVENT =
    new NonBurnableFamily<>("BUSINESS_EVENT");

static List<ProjectionTask> toTasks(RecordedEvent<? extends BusinessEvent> recorded) {
    var event = recorded.event();
    var types = PocomaProjectionMaterializationPolicy.policy()
        .materializations().get(event.eventType());
    if (types == null) throw new IllegalArgumentException("Unknown BusinessEvent type");
    return types.stream().sorted(Comparator.comparing(ProjectionType::value))
        .map(type -> new ProjectionTask(new ProjectionKey(type,
            new TargetObjectType("POT"),
            new TargetObjectId(event.potId().value().toString()), event.version())))
        .toList();
}

static final Consumption<RecordedEvent<? extends BusinessEvent>, List<ProjectionTask>>
    PROJECTION_TASKS = new Consumption<>(
        new ConsumptionId(UUID.fromString("bd7593f5-17f9-49b4-bfc7-daf221696642")),
        BUSINESS_EVENT, BusinessEventToProjectionTasks::toTasks);

// Composition manuelle de TEST A2-1 : slots déjà insérés par la fixture.
// FixtureDiscovery reçoit C au constructeur et implémente le contrat d'enrôlement.
NonBurnableDiscovery<RecordedEvent<? extends BusinessEvent>> discovery =
    new FixtureDiscovery<>(PROJECTION_TASKS, partitionId, enrolledSlots);
var locator = new NonBurnableLocator<>(PROJECTION_TASKS, lifecycle, ownerTx, clock);
var engine = new ConsumptionEngine<>(PROJECTION_TASKS, lifecycle, sourceLoader,
    (recorded, outputs) -> outputs.forEach(task ->
        tasks.ensure(task.projectionKey(), recorded.recordedAt())),
    failurePolicy, ownerTx, clock);

var page = discovery.enrol(new NonBurnableDiscovery.Request(
    PROJECTION_TASKS.id(), partitionId, 10));
if (!PROJECTION_TASKS.id().equals(discovery.consumptionId())
        || !PROJECTION_TASKS.id().equals(locator.consumptionId())
        || !PROJECTION_TASKS.id().equals(engine.consumptionId())
        || !PROJECTION_TASKS.id().equals(page.consumptionId())
        || partitionId != page.partitionId()) {
    throw new IllegalArgumentException("Inconsistent Consumption composition");
}
for (var slot : page.slots()) {
    var result = locator.acquire(PROJECTION_TASKS.id(), slot, workerId, lease);
    switch (result) {
        case LocateConsumptionUseCase.Acquired(var claimed) -> engine.execute(claimed);
        default -> { } // Busy/DONE/NotReady/absent : pas d'exécution.
    }
}
```

`Acquired` est le record résultat imbriqué proposé dans `LocateConsumptionUseCase`.
`sourceLoader` est l'adaptateur du nouveau port : il vérifie l'UUID demandé contre `RecordedEvent.eventId()`.
A2-1 exerce ainsi les contrats sans inventer une Discovery E fictivement fonctionnelle. Pour PotCreated,
la politique réelle retourne AUTH, READ_POT et BALANCES ; toutes les `ensure` sont dans TE. Un type connu
sans conséquence retourne une liste vide. Les tests peuvent injecter un writer marqueur pour les fautes.
La production atomique raccordée au nouveau worker et sa couverture historique restent au lot 3 d'A2 minimal.

## 4. Modifications envisagées, fichier par fichier et ordre court

Tous les chemins ci-dessous sont **prospectifs**. Préfixes Java :
`D = app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/` ;
`E = app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/` ;
`J = app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/` ;
`S = app/supra-consume-event/src/main/java/com/kartaguez/pocoma/supra/consume/event/materialization/` ;
`P = app/engine-produce-projection-task/src/main/java/com/kartaguez/pocoma/engine/produce/projectiontask/materialization/`.
Une ligne regroupant plusieurs noms désigne une modification de **chacun** des fichiers nommés.

| Ordre | Fichier(s) | Modification bornée |
|---|---|---|
| 1 | `D/key/ConsumptionId.java`, `D/key/ConsumableRef.java`, `D/claim/SlotRef.java`, `D/claim/ClaimedSlot.java` (nouveaux) | Valeurs immuables et invariants de cohérence ; packages existants |
| 1 | `D/key/ConsumptionKey.java` | Factory canonique et reconnaissance du namespace réservé, sans changer les clés historiques |
| 1 | `E/port/in/consumption/contract/Consumption.java`, `NonBurnableFamily.java` (nouveaux, même répertoire) | Définition applicative et contrôle local des références/clé |
| 1 | `E/port/in/consumption/usecase/NonBurnableDiscovery.java`, `LocateConsumptionUseCase.java`, `RunConsumptionUseCase.java` (nouveaux) | Interfaces liées à C, commandes/résultats imbriqués ; Discovery interface seulement |
| 2 | `E/port/out/consumption/ConsumableLoader.java` (nouveau) | Chargement autoritatif typé ; aucun détail PostgreSQL |
| 2 | `E/port/out/consumption/ConsumptionLifecyclePersistencePort.java`, `ConsumptionQueryPort.java` | Méthodes contextualisées d'acquisition existante, lecture, terminalisation et retry ; anciens chemins gardés |
| 2 | `J/adapter/consumption/JpaConsumptionLifecycleAdapter.java` | Encodage/décodage strict, acquisition sans INSERT du nouveau chemin, relecture/guards et suffixe commun ; pas d'autre lifecycle |
| 2 | `J/repository/consumption/JpaConsumptionSlotRepository.java` | Lookup slotId+clé, acquisition verrouillée et installation contextualisées, CAS/retry contextualisés ; exclusion CONSUMPTION des mutations legacy |
| 2 | `E/service/consumption/NonBurnableLocator.java`, `ConsumptionEngine.java` (nouveaux) | C au constructeur ; transactions TA/TE/TH résolues, source/claim vérifiés, fonction et writer fixes |
| 2 | `E/service/consumption/AcquireConsumptionService.java`, `ExecuteConsumptionService.java`, `FinalizeConsumptionService.java`, `HandleConsumptionFailureService.java` | Refus legacy du namespace réservé avant effet ; query injecté explicitement pour Execute/Finalize ; aucune modification des policies |
| 3 | `P/BusinessEventToProjectionTasks.java` (nouveau) | Transformation Java bornée du pilote, réutilisant la politique actuelle ; ne pas modifier `ProduceProjectionTaskService.ensure` |
| 3 | `S/ProjectionTasksConsumption.java`, `S/BusinessEventSourcePort.java` (nouveaux) | Constante C et port reload de l'enveloppe ; ne pas dépendre du port LKV |
| 3 | `J/adapter/processing/event/JpaBusinessEventSourceAdapter.java` (nouveau) | Lecture par PK et mapping existant, participant TE ; ni scan Discovery ni lifecycle source |
| 3 | Configurations et holder ci-dessous | Passer le query port obligatoire aux anciens services ; aucun nouveau bean pilote ni changement de polling |
| 4 | Tests ci-dessous, puis documents de preuve/clôture | Prouver le contrat avant toute proposition d'activation ; distinguer résultats réellement exécutés |

Fichiers de composition concernés par l'injection de garde, tous existants :

- `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionTerminalParticipant.java` ;
- sous ce même `.../runtime/` : `command/consumption/CommandConsumptionRuntimeConfiguration.java`, `registration/RegistrationRuntimeConfiguration.java`, `registrationresult/RegistrationResultRuntimeConfiguration.java`, `result/CommandResultRuntimeConfiguration.java`, `binding/BindingRuntimeConfiguration.java`, `event/consumption/EventConsumptionRuntimeConfiguration.java`, `task/consumption/CanonicalProjectionTaskRuntimeConfiguration.java` ;
- `app/runtime-latest-known-version-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/latestknownversion/LatestKnownVersionRuntimeConfiguration.java`.

Les wrappers `Transactional*ConsumptionUseCase`, `ConsumptionOwnership`, claims/repository/entités,
`ConsumptionSlot`, provenance, POM et migrations sont **à conserver**. Si les nouvelles signatures exigent
leur modification, réviser l'inventaire avant code ; ne pas élargir tacitement le lot.

## 5. Transition, alternatives et arbitrages

| Choix réel | Proposition et conséquence |
|---|---|
| JSONB existant ou colonne consommation dédiée | Garder l'unique existant et le namespace réservé pour A2-1 : pas de migration/backfill ; coût de sélection/reprise à mesurer au lot suivant. Colonnes/index dédiés différés, pas écartés définitivement |
| Modifier toutes les identités ou chemin explicite additif | Nouveau chemin seulement pour C ; anciens formats et workers conservés, avec gardes empêchant leur accès aux nouveaux slots. Aucun adaptateur universel legacy↔C, aucune identité C déduite d'un nom de consumer |
| Fermer immédiatement toutes les API à callback ou isoler leur portée | Isoler maintenant les slots C par gardes avant callback et CAS SQL. Leur retrait complet suit la migration des consumers ; pas de callback arbitraire sur la nouvelle API |
| Réutiliser le port Event de LKV ou petit port du pilote | Petit port reload dans supra Event et adapter JPA utilisant le mapping existant : évite une dépendance Event→LKV et ne déplace pas un contrat partagé maintenant |

L'additif n'est pas une conversion des anciens DONE : un slot Event×projection ne prouve pas la couverture
Event×Consumption. Les nouveaux slots restent dormants hors tests. Lot suivant : enrôlement BUSINESS_EVENT et checkpoint CAS ;
puis reprise/bascule coordonnée avec rattrapage `ensure` des tâches manquantes, sans fusion des anciens DONE.
Les Consumers Command, Task, Binding, LKV, Registration et Results gardent leurs clés, effets, outcomes,
policies et provenance. La suppression de provenance générique reste un lot séparé après inventaire/rétention.

**Arbitrages à valider pour A2-1 :** encodage/namespace exact ; fermeture legacy avant callback et coût
associé en relectures ; frontière dormant A2-1 (acquisition d'un slot explicite) versus sélection des reprises
au lot 3. Les propositions ci-dessus sont le choix recommandé, pas des décisions déjà acquises.
UUID du pilote repris de l'exemple approuvé ; confirmer sa réservation avant première livraison exécutable.
L'association UUID/famille/règles incompatible entre binaires est un risque de déploiement à traiter par revue,
sans digest ni registre. Fusion des familles identité, journal de complétion Registration, partition/index,
bascule historique et rétention de provenance restent arbitrages des lots ultérieurs, non bloquants ici.

## 6. Matrice des preuves à produire après validation

**Aucun des tests de cette matrice n'a été écrit ou exécuté pour ce plan.** Racines de test : mêmes
modules/packages que les sources, avec `src/test/java` au lieu de `src/main/java`.

| Fichier de test prévu | Cas et oracle nécessaire |
|---|---|
| `domain-consumption/.../key/ConsumptionIdTest.java` (nouveau) | UUID fixé identique après reconstruction/deux instances ; null refusé ; UUID différent = autre C |
| `domain-consumption/.../claim/ContextualConsumptionRefsTest.java` (nouveau) | C ref/slot incohérent refusé, égalité structurelle, clé identique avec partition différente, encode/decode canonique ; UUID/shape durable invalides refusés |
| `engine-consumption/.../service/consumption/ConsumptionContractTest.java` (nouveau) | C fixé au constructeur, composant/résultat Discovery d'un autre C refusé ; C1/C2 même famille ; aucun appel loader/transformation/writer pour entrée étrangère, source et claim mélangés |
| `runtime-event-consumption-worker/.../event/consumption/ConsumptionIdentityPostgresTest.java` (nouveau) | Vrais slots C1/C2 même eventId, unicité/idempotence sans remise à zéro ; acquisition C2 d'un slot C1 refusée sans nouveau slot/claim ; message C falsifié, slot/source/claim falsifiés ; relecture autoritative avant transformation |
| Même fichier PostgreSQL | Identité durable divergente : effet marqueur antérieur dans TE annulé, aucun appel transformation, slot/claim inchangés. Faute après effets avant CAS : CAS contextualisé refusé et rollback intégral ; une injection hostile test-only n'est pas une voie de mutation supportée |
| Même fichier PostgreSQL | Reload JPA réel et `ProjectionTaskStorePort.ensure` : faute après deux tâches, zéro tâche partielle durable. Claim correct clôturé exactement une fois ; Busy/NotReady/DONE ; takeover pendant effets provisoires, lease expirée sans takeover ; perte CAS, clôture refusée et retry refusé annulent effets/schedule ; vérification par connexion indépendante et barrières bornées |
| `supra-consume-event/.../materialization/ProjectionTasksConsumptionTest.java` (nouveau) | Déclaration Java lisible, PotCreated→3 clés AUTH/READ_POT/BALANCES ; zéro sortie connu et type inconnu refusé. Atomicité et loader PK réel prouvés dans le test runtime PostgreSQL |
| `engine-consumption/.../service/consumption/AcquireConsumptionPreconditionTest.java`, `ExecuteConsumptionServiceTest.java`, `FinalizeConsumptionServiceTest.java` (existants) | Gardes legacy avant callback ; absence de fallback permissif ; doubles adaptés aux nouveaux ports/constructeurs |
| `engine-consumption/.../service/transaction/consumption/TransactionalConsumptionUseCaseTest.java` (existant) | Guards participantes, succès après commit confirmé ; rollback avant TH ; COMMITTED/UNKNOWN sans FAILED/replay |
| `infra-persistence-primary-jpa/.../adapter/consumption/JpaConsumptionLifecycleAdapterPostgresTest.java`, `TransactionalConsumptionExecutionPostgresTest.java` (existants) | API legacy refusant les slots C, aucune mutation SQL possible par ancien terminalize/retry ; chemins historiques et provenance toujours verts |
| `infra-persistence-primary-jpa/.../repository/consumption/JpaConsumptionSlotRepositoryFencingQueryTest.java` (existant) | PENDING + claim + identité complète dans nouvelles transitions ; protections legacy conservées |

Adapter également les constructions directes des services dans les fichiers existants suivants,
sans changer leurs scénarios/attentes : `infra-persistence-primary-jpa/.../adapter/command/CommandConsumptionPostgresTest.java`,
`runtime-command-consumption-worker/.../command/consumption/CommandConsumptionRuntimePostgresTest.java`,
`runtime-command-consumption-worker/.../command/consumption/eventprotocol/PrimaryEventTransactionPostgresTest.java`,
`EventSequencingPostgresTest.java` dans ce dernier répertoire, `runtime-event-consumption-worker/.../event/consumption/ProjectionMaterializationConsumptionPostgresTest.java`
et `runtime-web-api/src/test/java/com/kartaguez/pocoma/WriteSideClosurePostgresTest.java`.
Les tests runtime existants des autres Consumers sont rejoués par leurs anchors ; ne pas migrer leur logique.

## 7. Verification scope prospectif A2-1

- **Expected production impact :** contrats/références génériques, accès contextualisés aux slots et gardes legacy,
  pilote Java dormant et injection de ses gardes dans les compositions existantes ; aucun nouveau parcours activé.
- **Primary slice : EVENT. Secondary slices : COMMAND, PROJECTION, BINDING, LKV**, car elles exécutent les
  services communs dont les nouvelles relectures/gardes changent le chemin. WEB est un complément de non-régression
  du test WriteSideClosure ; Registration, CommandResult, RegistrationResult et monolithe ont des anchors explicites ci-dessous.
- **Forbidden production impact :** scan E, checkpoints, Discovery JDBC, hash/partitionnement, quatre familles E,
  métier/READ, orchestration/bascule workers, retrait provenance, schéma/migrations/POM, optimisation PostgreSQL.
- **Required local proof :** matrice ci-dessus, contrats Java, vrais slots/claims et rollback PostgreSQL CURRENT ;
  pas de H2 pour fencing/atomicité. Fixtures enrôlent les slots directement, pas de faux checkpoint.
- **Global architecture gate : REQUIRED à la clôture d'implémentation**, car fermeture intentionnelle de la
  frontière d'exécution publique (§17.2). Règles locales dans les slices pendant le développement ; aucun changement
  de règle hexagonale prévu (domain JDK seul, packages existants, moteur ignorant des familles).
- **Full reactor : NOT REQUIRED pour A2-1** : impact partagé borné aux consumers inventoriés, aucun graphe Maven restructuré.
  Prochain full obligatoire : intégration significative/main ou clôture majeure Discovery déclarée dans son futur plan.
- **Database verification : CURRENT**, sans DDL. Trusted baseline **NONE** dans la politique actuelle ; les bootstraps
  existants restent autoritatifs, sans campagne historique supplémentaire. Utiliser une baseline trusted si certifiée
  avant implémentation ; ne pas inventer une baseline V31 et ne pas retirer des tests de migration pour passer.
- **Escalation conditions :** autre runtime/port propriétaire, nouvelle dépendance/module, changement de transaction,
  accès indexé impossible sans DDL ou besoin de déplacer le port LKV : expliquer puis redéclarer avant de poursuivre.
  Une sélection de reprise demandée dans A2-1 exige d'abord de réviser la borne du lot, pas de la glisser dans le Locator.

Commandes **prévues, non exécutées**, depuis `app/`, après validation et implémentation :

```bash
./mvnw -pl runtime-event-consumption-worker -am test
./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker,runtime-binding-consumption-worker,runtime-latest-known-version-consumption-worker -am test
./mvnw -pl runtime-web-api,runtime-registration-consumption-worker,runtime-command-result-consumption-worker,runtime-registration-result-consumption-worker,runtime-monolith -am test
./mvnw -pl architecture-tests -am test
```

## 8. Clôture A2-1 et contrôle de cette livraison documentaire

A2-1 pourra être clos seulement après validation architecturale, déclaration reconfirmée au HEAD de départ,
contrat Java utilisable sans lire PostgreSQL, preuve C1/C2 falsifiée comprise, acquisition/finalisation/retry
réels sous bon claim, rollback intégral et compatibilité historique verts. Chaque nouveau slot aura exactement
la clé canonique retenue, sans partition dans l'unique ; aucune API legacy ne pourra contourner le périmètre C.
Rapport de tests avec commandes/sorties/limites requis ; aucun gate envisagé renommé PASS.
Clôture du contrat ne vaut ni Discovery E livrée, ni worker basculé, ni activation production.

Pour **cette livraison documentaire** : slices NONE ; DB NONE ; franchissement inattendu NONE ;
gate global NOT REQUIRED ; full reactor NOT REQUIRED/non exécuté, car aucun comportement applicatif modifié.
Contrôles documentaires : lectures statiques Git/code/POM/rapports et preuves JSON ; identité byte à byte
avec le blob A2 source ; liens locaux, `git diff --check` et liste exclusive des trois documents autorisés ;
script documentaire `/private/tmp/pocoma-a2-1-doc-check.py` (49 liens locaux, 33 noms de fichiers Java existants, cinq worktrees/index/fichiers préexistants préservés) ; état Git et divergence contrôlés. La synchronisation finale est vérifiée après push. Aucun Maven, PostgreSQL, test ou migration lancé.
Le commit/push normal vise explicitement `origin/feat/consumption-transaction-protocol` ; le SHA final et
la divergence finale sont fournis dans le compte rendu de livraison, sans autoréférence du commit.

**Arrêt après le push documentaire. Attendre la revue architecturale ; aucune implémentation commencée.**
