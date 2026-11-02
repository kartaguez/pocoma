# Pocoma V3 — L4/I2 — Plan d’implémentation de la préparation interautorités et du recovery

**2 novembre 2026 — `feat/v3-architecture` — PLAN DOCUMENTAIRE, sans GO d’implémentation.**

Ce document remplace intégralement l’esquisse `6d41ee439800c079ccd05a02ccc285b5daa4221f`.
Les signatures marquées **cibles** sont à implémenter ; elles ne sont pas attribuées au code actuel.
L’analyse statique porte sur ce SHA. Le checkout initial était `5f0846bd585aee52424cbe66e90eafad9ee0b379`,
propre, puis avancé en fast-forward au SHA de l’esquisse ; aucun autre worktree n’a été utilisé.

## 1. Mandat, préconditions et frontière de vérification

L4/I1 est **CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS**, décision humaine du
2 novembre 2026, commit de clôture `fd02a1978012b979137db7d9043aea5c69144cfd`.
L’[acte I1](L4_I1_Acceptance_Decision.md), le [rapport](L4_I1_Execution_Report.md) et le
[manifeste](L4_I1_Proof_Manifest.json) restent inchangés. L’acte identifie le dossier accepté
`3bba9dbaa4fedfe040ed9f7e5d7ca2239b1ddab7`, le stockage `533c14746b61395691845edf19f40e5444f9edd9`
et le correctif owner `9e8c18a9830fa151808f6aef8a96ae978a199c87` : ne pas confondre ces attributions.
L’acceptation I1 satisfait le prérequis de cadrage I2, sans constituer son GO Java.

Préconditions du futur développement : décision humaine I2 distincte, code I1 et correctif tardif
présents, cibles V3 PRIMARY/READ administrativement installées, accès aux preuves historiques,
JDK/Maven/PostgreSQL conformes aux campagnes, un seul propriétaire de campagne à la fois.
Aucun prérequis ne demande une nouvelle clôture I1 ou un arbitrage D1–D4.

Déclaration **de cette mission documentaire** : slices **NONE**, impact `docs/v3/L4_I2_Implementation_Plan.md`
seul ; aucune campagne Maven/PostgreSQL ; gate globale et full reactor **NOT REQUIRED / NOT RUN**.
Aucun changement sous `app/`, aucun collecteur ni preuve modifié. Les commandes ci-dessous sont
les obligations **futures**, pas des commandes exécutées ni des PASS I2.

Déclaration **avant implémentation I2** selon la [politique Reactor](../testing/Reactor_Verification_Policy.md) :

| Champ | Déclaration |
|---|---|
| Impact production attendu | L2 préparation/recovery, contrat transactionnel pur et owner Spring, gardes de lecture V3, adaptation Command/Identity/probes et assemblages existants |
| Slice principale | PROJECTION : `runtime-task-consumption-worker` |
| Slice secondaire | COMMAND : `runtime-command-consumption-worker` ; IdentityRequest V3 est assemblé dans cette racine |
| Racine complémentaire | `runtime-monolith`, preuve d’assemblage obligatoire, pas nouvelle slice canonique |
| BINDING | Pas de changement production BINDING identifié ; critères précis §12 |
| Impact interdit | Participants I3–I7, activation I8, métier WRITE, SQL durable, POM/modules/runtimes, AuthN/HTTP, protocole Worker/capacité parallèle |
| Gate globale Architecture | REQUIRED à l’acceptation : extension intentionnelle de la frontière de transaction/observation L2, §17 |
| Full reactor | NOT REQUIRED pour I2 borné ; ni clôture majeure L4, ni intégration main, ni nouvelle baseline globale |
| Base de données | CURRENT V3 ; aucune nouvelle migration. Baseline trusted actuellement NONE selon la politique ; ne pas annoncer un bootstrap depuis une baseline certifiée inexistante |
| Escalade | Toute modification hors inventaire, du protocole Discovery, du routage, des pools, des grants ou d’une autre autorité doit être expliquée avant poursuite et sa gate ajoutée ; FULL seulement aux conditions §19 |

Le [plan global L4](L4_Read_Inventory_and_Implementation_Plan.md#6-participants-transactions-et-recovery)
reste compatible : I2 réalise A01–A03. Sa « FOUNDATION REQUALIFICATION » signifie requalification
ciblée des propriétés L2 affectées, **pas création d’une slice FOUNDATION pour L2**. FOUNDATION
reste L0 au §34 de la politique. Ni SPEC, canon, Master Plan ni plan global ne nécessitent de modification.

## 2. Analyse du code et réemploi

Les chemins exacts sont inventoriés au §10. **EXISTING** décrit un constat de code, **REUSE** un
mécanisme conservé sans changement sémantique, **MODIFY** une évolution nécessaire et **NEW** un
contrat absent. La présence d’un test n’atteste pas son succès à ce SHA.

| Mécanisme | État réel et décision |
|---|---|
| `ConsumptionEngine.executeAttempt` | EXISTING : `transactions.read(context -> participant.prepare(id))` sur les effets. MODIFY : appel extérieur hors transaction, capacité de scopes L2 explicite |
| `ConsumptionParticipant` | EXISTING : `Optional<Prepared> prepare(UUID)`, `RecoveryObservation observe(UUID, RecoveryReadContext)` ; `Prepared.finalizeIn(SlotCompletion)` et `requireCompletionReady()`. MODIFY uniquement préparation et observation |
| `AttemptTransactions` | EXISTING : `read(Function<RecoveryReadContext,T>)`, `write(Supplier<T>)`, `poison(Throwable)`, `requirePublicationClosed()`, `Failure` et `Resolution`. REUSE résolutions ; MODIFY avec garde partagée de tentative (§4), sans owner composite |
| `SpringAttemptTransactions` | EXISTING : un manager/datasource identiques, sans transaction ambiante, `read` RR RO avec SQL `set transaction read only`, `write` RC, synchronisation de résolution, fermeture de publication, flush/clear JPA et `PoisonedCommitGuard`. REUSE intégral de ces garanties ; MODIFY rattachement de la garde L2 et gardes de lecture |
| `RecoveryReadContext` | EXISTING : `void requireActiveRead()`, capability du `Unit`, thread/callback/owner. REUSE signature ; MODIFY implémentation des refus pour propager le poison |
| Slots | EXISTING : `protectFinal`, `deleteProtected`, `observe`, `inspectResolved`, acquisition/renew/cession. REUSE SQL, token et horloge après attente ; MODIFY enveloppe gardée de `observe`, aucun nouveau champ/table |
| Checkpoints | EXISTING : `DiscoveryAdmissionStore.checkpoint(ConsumptionId,int,RecoveryReadContext)` retourne `long`, absence = 0. `JdbcDiscoveryAdmissionStore` est **SQL partagé aux deux autorités**, malgré son module PRIMARY. REUSE port et lecture ; MODIFY enveloppe gardée |
| Ownership/navigation | EXISTING : `PartitionOwnershipStore` protège acquisition et timeouts techniques ; `SlotKeyNavigation.readPage` est navigation MVCC. REUSE ; aucune preuve métier ou permission de restitution n’est ajoutée à ces ports |
| Source événements | EXISTING : `NonBurnableReferences.window(long,int,context)` et `Reference(long position,UUID eventId,String subjectType)`. NEW lookup exact `find` sur le même port, sans deuxième mapping famille/table |
| Recovery actuel | EXISTING : snapshot effets, slot + verdict participant, sans checkpoint ; `COMPLETED` métier + slot présent devient `INCONSISTENT`. MODIFY pour distinguer preuve READ réutilisable et terminal WRITE |
| Command | EXISTING : load demande, observe acteur, `history.current` et éventuellement `activeUsage` dans une seule préparation ; `Proposal` détachée, finalisation avec protections acteur/Pot. REUSE métier et snapshot unique |
| IdentityRequest | EXISTING : préparation de l’intention uniquement ; `lockInCanonicalOrder`, lecture des EUI courantes, décision, effets, résultat et publication dans finalisation. REUSE sans déplacement des décisions |
| Capacité | EXISTING : un `WorkerCapacity`, tickets privés, `pending`, `localDone`, `businessStarted`, `unresolvedPreparation`, `unresolvedRecovery`. REUSE ; seuls les nouveaux points de lecture alimentent ces responsabilités (§8) |
| Runtime | EXISTING : quatre pools Business/Control WRITE/READ, un Worker, Command/IdentityRequest et deux probes READ seulement. REUSE ressources et limites ; MODIFY injection explicite source et facts techniques |
| Stockage I1 | EXISTING : `read_subobject_version`, huit catégories, codec strict, `SubobjectVersionStore.readAll/insertOrVerifyAll/requireStored`. REUSE clés/égalité/writers ; MODIFY garde de `readAll`, pas sa signature ni son SQL |

### 2.1 Autorités physiques réellement disponibles

« PRIMARY » désigne ici l’autorité logique **WRITE** ; chaque pool garde sa datasource physique.
Business et Control sont deux pools différents vers **la même** cible physique de leur autorité.
Le snapshot de recovery utilise Business ; il ne lit pas ses faits techniques via une seconde
connexion Control. Le préflight existant valide base, schéma, rôle, routing contract et privilèges.

| Donnée | Résidence livrée | Observation/utilisation |
|---|---|---|
| Contrat, lease, checkpoint, slot WRITE | PRIMARY : `consumption_routing_contract`, `partition_ownership`, `discovery_checkpoint`, `consumption_slot` | Migration `db/v3-consumption-write/migration/V5__consumption_foundation.sql` ; stores JDBC L2 |
| Mêmes quatre tables READ | READ : mêmes noms, cible séparée | `db/v3-consumption-read/migration/V1__consumption_foundation.sql` ; mêmes adaptateurs SQL injectés sur READ |
| Position et enveloppe Pot/CommandCompletion | PRIMARY : `v3_event_position`, `v3_event_counter`, `v3_pot_event`, `v3_pot_event_component`, `v3_command_completion_event` | `db/v3-pot-execution/migration/V2__command_execution.sql` ; source RR WRITE |
| Position/enveloppe Identity/IdentityCompletion | PRIMARY : `v3_event_position`, `v3_identity_event`, `v3_identity_completion_event` | `db/v3-identity/migration/V6__identity_authority_and_requests.sql` ; source RR WRITE |
| Faits Pot historiques | PRIMARY : `v3_pot_version`, identités et huit tables de composants | `db/v3-pot/migration/V1__pot_components.sql` ; `JdbcPotHistoryAdapter` et `PotHistoryRows` |
| Demande/résultat Command | PRIMARY : `v3_command`, `v3_command_result` et publications ci-dessus | `JdbcPotCommandStore` / `JdbcPotCommandObservation` ; snapshot effets WRITE en recovery |
| Demande/résultat/autorité Identity | PRIMARY : `v3_identity_request`, `v3_identity_request_result`, `v3_eui_authority`, `v3_eui_version`, `v3_pocoma_user` | Stores et observations Identity ; snapshot effets WRITE |
| Sous-versions I1 | READ : `read_subobject_version` | `db/v3-read-business/migration/V2__subobject_version.sql` ; `JdbcSubobjectVersionStore.readAll` |
| Effet des probes Worker | READ : `i5_read_probe_effect` | Fixture/développement technique existant, **aucune projection métier** |
| PotVersion, PotBalance, Visibility, CurrentBinding et résultats V3 READ futurs | Pas livrés par I2 | Les anciens stores V2 ne sont pas substituables à ces contrats futurs |

**Contradiction concrète de l’esquisse corrigée :** il n’existe pas de checkpoint pour les familles
`COMMAND` et `IDENTITY_REQUEST` : les triggers PRIMARY/READ interdisent les checkpoints burnable.
Le recovery WRITE conserve donc demande terminale + préfixe métier/publications + slot, dans
WRITE. Le recovery événement READ utilise checkpoint **C/p**, slot et preuve métier dans READ.
Le checkpoint contient une position globale couverte pour **une Consumption et une partition**,
pas un accusé de commit par événement. Il avance aussi sur les exclusions stables.

Aucune contradiction physique pour READ : Discovery lit sa fenêtre PRIMARY séparément, puis
insère les slots admissibles et avance le checkpoint atomiquement sur l’autorité des effets
(`DiscoveryEngine.discoverEvents`). Cela autorise l’implication « référence éligible + K≥position +
slot absent », sous les invariants L2 d’absence de purge/suppression étrangère. L’implication ne
vient pas d’une jointure PRIMARY/READ. Ni ownership, ni navigation, ni expiration du claim ne
prouvent la complétion. Une suppression administrative illégitime ne devient pas détectable par
magie sans reçu : c’est une hypothèse de confiance explicitée et protégée par les privilèges existants.

## 3. Architecture retenue et cycle complet

Un seul Engine possède les owners. Il reçoit explicitement `sourceTransactions` et les owners
existants effets/Control. Pour WRITE : source et effets sont **la même instance Business WRITE**.
Pour READ : source = Business WRITE, effets = Business READ. Control reste réservé aux unités
techniques existantes. Aucun pool, manager, thread, router ou orchestrateur générique nouveau.

Chemin READ cible, hors transaction ambiante au début :

1. L2 possède déjà l’`AcquiredClaim` et son ticket ; `executeOperation` l’inscrit dans `pending`.
2. L2 crée une session privée et appelle `participant.prepare(id, reads)` hors transaction.
3. Le participant peut faire `source` → `effects` → `source` ; chaque retour de scope signifie
   retour du callback **et résolution confirmée** de l’owner. Chaque scope est RR RO réel.
4. Les lectures source ultérieures ciblent les identités/versions immuables initialement choisies,
   jamais le courant relu comme substitut historique ; les bornes `active_to` variables ne sont
   pas transportées comme contenu immuable. Calcul détaché hors transaction.
5. Sortie saine seulement : L2 expire la capacité de préparation, vérifie sa garde, puis ouvre
   l’unique unité finale RC READ avec cette même garde encore vivante.
6. `protectFinal` → `finalizeIn` → `complete` qui exige `requireCompletionReady` puis DELETE →
   fermeture de publication → résolution physique. Les writers existants et leur ordre restent requis.
7. Sur succès, rollback ou UNKNOWN, L2 applique son cycle de capacité existant (§8).

Le finaliseur READ ne lit aucune donnée PRIMARY, même via un supplier capturé. La nouvelle API ne
rend pas automatiquement sûr un futur participant : architecture, types des ports et tests
physiques contrôlent cette interdiction. I2 livre une fixture interautorités discriminante, pas
les participants métier I3–I7.

## 4. Contrats Java cibles complets

Les imports ci-dessous sont ceux de `java.util`, `java.util.function` et des types existants cités.
Les types publics nouveaux sont limités à la capacité et aux petits contrats imbriqués nécessaires.
Pas de `RecoveryPreparation` marqueur, de cast de DTO générique, de `RecoveryFacts` public ni de
second service de recovery : l’Engine combine lui-même les faits techniques.

### 4.1 Capacité participante et comparaison métier

Nouveau fichier `PreparationReads.java`, package et chemin complet au §10 :

```java
package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.function.Function;

public interface PreparationReads {
    <T> T source(Function<RecoveryReadContext, T> action);
    <T> T effects(Function<RecoveryReadContext, T> action);

    final class Invalid extends IllegalStateException {
        public Invalid(String message, Throwable cause) { super(message, cause); }
    }
}
```

Remplacement de l’interface publique `ConsumptionParticipant` :

```java
package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.Optional;
import java.util.UUID;

public interface ConsumptionParticipant {
    Optional<Prepared> prepare(UUID consumableId, PreparationReads reads);
    Recovery prepareRecovery(UUID consumableId, PreparationReads reads);

    enum EffectEvidence { SATISFIED, MISSING, DIVERGENT, UNAVAILABLE }

    @FunctionalInterface
    interface Recovery {
        EffectEvidence observe(RecoveryReadContext context);
    }

    interface Prepared {
        void finalizeIn(SlotCompletion completion);
        void requireCompletionReady();
    }
}
```

`Prepared` conserve son rôle actuel, notamment les commandes détachées et fonctions d’application
L1 ; il n’est pas une entité JPA. `Recovery` est une comparaison différée, liée à une invocation,
qui capture seulement les valeurs source détachées et les ports d’effets injectés. Elle ne capture
ni contexte actif, ni transaction source, ni résultat de commit. `prepareRecovery` peut faire zéro
scope (WRITE/probe), plusieurs scopes source/effets séquentiels si nécessaire (futurs READ), mais
sa comparaison finale **relit tous les effets faisant autorité** dans le contexte donné par L2.
Une présence lue pendant préparation ne peut remplacer cette observation finale.

Sens des preuves : `SATISFIED` = toutes les postconditions durables requises sont vraies, même
préexistantes ; `MISSING` = état admissible incomplet ; `DIVERGENT` = contradiction/corruption
positive ; `UNAVAILABLE` = comparaison impossible. Un ensemble partiel conforme de sous-versions
est MISSING, pas DIVERGENT. Aucun de ces termes n’autorise un DELETE, un retry ou une restitution.
L’enum publique existante `RecoveryObservation` reste inchangée et reste le verdict de l’Engine.

### 4.2 Garde commune jusqu’à la résolution physique

Extension **cible** d’`AttemptTransactions` dans le package existant ; `Failure` et `Resolution`
restent exactement les types existants, sans reclassification optimiste :

```java
interface Guard {
    void requireValid();
    void invalidate(Throwable cause);
}

<T> T read(Function<RecoveryReadContext, T> action, Guard guard);
<T> T write(Supplier<T> action, Guard guard);

default <T> T read(Function<RecoveryReadContext, T> action) {
    return read(action, unguarded());
}
default <T> T write(Supplier<T> action) {
    return write(action, unguarded());
}
private static Guard unguarded() {
    return new Guard() {
        public void requireValid() {}
        public void invalidate(Throwable cause) {}
    };
}
```

`Guard` est imbriquée dans `AttemptTransactions`. Les méthodes existantes `read(action)` et
`write(action)` deviennent des méthodes `default` déléguant aux surcharges avec une garde neutre
privée au port. Celle-ci n’ajoute aucun état de session, accepte `requireValid` et ne neutralise
**jamais** le poison local du Unit. Les contrats `poison`, `requirePublicationClosed`, `Failure` et `Resolution` sont conservés.
Les nouvelles surcharges sont abstraites : un décorateur/fake doit transmettre effectivement
la garde, sans fallback par défaut qui l’ignorerait. Tous les implémenteurs sont listés au §10.
Le listener I1 continue de fonctionner aussi pour les appels historiques sans garde de session.

Pourquoi cette extension est indispensable : la liste Spring des Units actuellement liés ne
contient plus le contexte source expiré lorsque le scope effects commence. Un simple booléen
sur `PreparationReads` ou `poisonUnits` seul ne peut relier l’erreur de cet ancien contexte à
l’unité suivante, ni à une erreur interceptée dans un callback tardif après la barrière Java.
La garde est ce lien monotone, pur, sans datasource et détenu exclusivement par L2.

Implémentation privée dans `ConsumptionEngine` : une session par invocation `prepare` ou
`prepareRecovery`, implémentant `PreparationReads` et `AttemptTransactions.Guard`. Champs privés :
thread propriétaire final ; capacité ouverte/expirée ; scope actif ; première cause atomique ;
incertitude physique monotone ; compteur des scopes réellement entrés. La garde n’est jamais
exposée au participant. Méthodes internes exactes à prévoir :

```java
private final class Reads implements PreparationReads, AttemptTransactions.Guard {
    public <T> T source(Function<RecoveryReadContext, T> action);
    public <T> T effects(Function<RecoveryReadContext, T> action);
    public void requireValid();
    public void invalidate(Throwable cause);
    private <T> T scope(AttemptTransactions owner,
                        Function<RecoveryReadContext, T> action);
    private void expire();
    private boolean physicallyUnresolved();
}
```

`expire()` ferme les **ouvertures de scopes**, sans invalider une session saine : sa garde reste
consultable par le finaliseur ou le snapshot final de recovery. `requireValid` vérifie l’absence
de poison/interruption ; elle relance la première cause RuntimeException/Error sans l’envelopper
comme faute de contrat : une erreur SQL/JPA d’origine ne doit pas devenir NON_COUNTABLE parce que
sa garde la signale ensuite. `Invalid` est réservé aux usages structurels interdits et à l’arrêt
par interruption/annulation, pas aux causes métier/SQL. La vérification de thread pour ouvrir un scope est distincte afin que
`invalidate` soit sûre depuis un thread étranger. Toute cause enregistrée reste présente.
L’Engine n’obtient le résultat de préparation que lorsque `prepare` a rendu, qu’aucun scope n’est
actif et que cette garde est valide. Les champs nécessaires aux refus interthreads sont atomiques
ou protégés par la même section critique ; aucune mutation du manager Spring depuis un autre thread.

### 4.3 Intégration concrète à l’owner Spring

`Unit` conserve une référence finale à `Guard`, et une cause de poison visible entre threads.
`requireCurrent`, `requireActiveRead`, `open`, les writers gardés, `poison`, le refus d’ambient et
la vérification d’autorité propagent **d’abord** `guard.invalidate(cause)`, puis marquent les
ressources actives rollback-only sur leur thread propriétaire. Un accès refusé depuis un thread
étranger ne fait aucun SQL et n’appelle pas le manager sur ce thread ; il invalide la garde et
la cause volatile du Unit. Le thread owner voit ces causes à ses barrières et au commit.
Le Unit expiré garde son lien avec la garde pour notifier un refus tardif.

Points de contrôle obligatoires : avant begin, en tête de `requireCurrent` avant les autres refus
locaux, avant chaque callback, à son retour, puis dans
`PoisonedCommitGuard.beforeCommit` **après les synchronisations et avant `doCommit`**. Le listener
existant examine cause locale, rollback-only **et** garde partagée, même si `unit.active=false`.
Il ne faut ni avancer ce listener avant les callbacks, ni unbind le Unit avant terminaison.
Une faute interceptée dans `beforeCommit` ou `beforeCompletion` ne peut ainsi committer le préfixe.
Le résultat COMMITTED réellement attesté après `afterCompletion` ne peut être annulé rétroactivement
par un usage ultérieur illégal d’un contexte : cet usage est refusé avant tout SQL. La garde ne
prétend pas modifier un commit physique déjà décidé.

Nouveaux helpers statiques **cibles** dans `SpringAttemptTransactions` :

```java
public static <T> T observed(DataSource source, RecoveryReadContext context,
                             Supplier<T> action);
public static <T> T participating(DataSource source, Supplier<T> action);
```

`observed` enveloppe **tout** `requireRead` + SQL + décodage/comparaison ; sur RuntimeException/Error,
poisonne l’unité/garde et rethrow la cause originale. `requireRead` conserve sa signature mais
son refus entier devient empoisonnant, y compris après `context.requireActiveRead()`.
`participating` enveloppe les lecteurs V3 également appelés en finalisation ou par L1 autonome :
si un Unit existe, il exige le Unit actuel sur cette datasource et une garde valide ; si un Unit
étranger existe, il refuse avant SQL ; sans Unit, il conserve les préconditions transactionnelles
L1 du lecteur appelé et ne crée aucun owner. Sur erreur il empoisonne les Units/ressources du
caller selon le mécanisme existant. Il n’exige pas RO pour un `load` utilisé aussi en finalisation RC.
Les règles RR des lecteurs historiques restent vérifiées par `PotHistoryRows.requireTransaction(true)`.

`observed` est appliqué aux observations slot/checkpoint/source/Command/Identity et `readAll` I1.
`participating` est appliqué aux lectures de demande Command/Identity, `currentVersion`, à tous les
points publics de lecture de `JdbcPotHistoryAdapter`, à `JdbcIdentityPotActorAuthority.observe` et
à la lecture de l’autorité fixture. Ces enveloppes couvrent aussi les erreurs de décodage Java
que PostgreSQL ne marquerait pas comme transaction abortée. Les signatures métier/SQL restent intactes.

Ce contrat détecte les erreurs qui passent par ces gardes. Il ne prétend pas détecter une exception
Java arbitraire levée et absorbée sans interaction avec un port gardé ; une telle exception pure
n’établit pas à elle seule une invalidation physique. Les participants ne peuvent contourner les
ports par SQL/transaction/ressource autonome ; les tests d’architecture doivent le contrôler.

### 4.4 Source exacte et construction de l’Engine

Ajout sur `NonBurnableReferences` **sans modifier `Reference`, `Window` ou `window`** :

```java
Optional<Reference> find(UUID eventId, RecoveryReadContext read);

final class InvalidReference extends IllegalStateException {
    public InvalidReference(String message) { super(message); }
}
```

Dans `JdbcEventDiscoveryReferences`, réutiliser exactement le mapping existant :
POT_EVENT → `POT` / `v3_pot_event` / POT ; COMMAND_COMPLETION_EVENT → `COMMAND_COMPLETION` /
`v3_command_completion_event` / COMMAND ; IDENTITY_EVENT → `IDENTITY` / `v3_identity_event` /
POCOMA_USER ou EXTERNAL_USER_IDENTITY ; IDENTITY_COMPLETION_EVENT → `IDENTITY_COMPLETION` /
`v3_identity_completion_event` / REGISTRATION, LINK ou UNLINK. Aucune source Invitation livrée :
la composition continue de refuser cette famille, sans inventer table ni fallback V2.

`find` utilise l’owner source RR : lookup par eventId avec position/famille, enveloppe par LEFT JOIN
et contrôle du compteur de la famille ; une seule ligne, position positive ≤ compteur, eventId
identique et subjectType autorisé. Enveloppe sans position, position sans enveloppe, mauvaise
famille, compteur régressé, sujet invalide = `NonBurnableReferences.InvalidReference` ; absence réelle =
`Optional.empty`. La comparaison est bornée à l’identité recherchée, pas scan historique du préfixe.
Les contraintes de publication existantes fournissent le lien durable, la fenêtre Discovery
reste la preuve du préfixe dense. Le lookup de position ne filtre pas silencieusement une mauvaise
famille : la ligne trouvée par eventId est validée contre la famille attendue. Sans position,
contrôler aussi l’existence de l’enveloppe dans la table mappée : si elle existe, lever
InvalidReference ; si position et enveloppe sont absentes, retourner empty. Un eventId dont la
position appartient à une autre famille est une InvalidReference, pas une source indisponible.
Les timeouts locaux de `window` sont aussi appliqués à `find`.

Nouveau constructeur complet **cible** de `ConsumptionEngine` :

```java
public ConsumptionEngine(
    ConsumptionDefinition definition,
    AttemptTransactions effectsTransactions,
    ConsumptionSlotStore effectsSlots,
    ConsumptionParticipant participant,
    AttemptTransactions sourceTransactions,
    Optional<NonBurnableReferences> recoverySource,
    java.util.function.Predicate<NonBurnableReferences.Reference> relevant,
    DiscoveryAdmissionStore admissions,
    AttemptTransactions controlTransactions,
    ConsumptionSlotStore controlSlots,
    PartitionOwnershipStore ownership,
    LifecycleSettings settings,
    WorkerCapacity capacity,
    AttemptFailureClassifier classifier,
    SlotKeyNavigation navigation,
    ConsumptionObservation observation);
```

`admissions` est l’adaptateur existant construit sur **effects Business**, avec les settings
Discovery déjà assemblés ; il n’est pas celui injecté sur Control dans Discovery. Les deux lisent
la même cible physique, à des moments séparés. `relevant` est le **même prédicat stable** que
Discovery : aujourd’hui, seuls les IdentityEvent EXTERNAL_USER_IDENTITY sont retenus pour le
participant READ Identity ; autres familles livrées = true. Extraire ce prédicat commun dans
`V3ConsumptionDiscoveryComposition.relevant(ConsumptionDefinition, Reference)` (méthode statique
pure renvoyant boolean), et l’utiliser des deux côtés. Pas de mapping métier dans l’Engine.

Pour READ, `recoverySource` est obligatoire et sa `family()` doit égaler `definition.family()` ;
pour WRITE burnable, il est vide et `sourceTransactions == effectsTransactions` est exigé.
Le constructeur ne peut vérifier une identité de base à partir d’interfaces pures : la composition
valide les datasources via `requireSource`/préflight et construit chaque store sur la bonne instance.
Les deux constructeurs actuels restent pour les callers WRITE uniquement : délégation
avec source=effects, source optionnelle vide, `admissions=null` (autorisé uniquement pour WRITE,
jamais consulté), prédicat true. Tout caller READ passe au constructeur complet. Aucun
mode legacy READ pouvant oublier checkpoint/éligibilité. `navigation` garde sa nullabilité actuelle
pour les bancs sans navigation ; `admissions` est exigé non nul pour READ ; tous les autres paramètres exigés sont non nuls.

Les factories existantes `V3ConsumptionLifecycleComposition.assemble` conservent leurs chemins
WRITE. Ajouter un overload explicite pour READ reprenant les paramètres de l’overload quatre pools
actuel et ajoutant `DataSource writeSource, SpringAttemptTransactions sourceReads,
DiscoveryEngine.Settings discoverySettings` avant `LifecycleSettings settings`. Il vérifie source
WRITE, owner identique, effets/control READ, construit `JdbcEventDiscoveryReferences` source et
`JdbcDiscoveryAdmissionStore` Business READ, puis le constructeur complet. Les factories mono-pool
READ techniques des tests sont migrées à une construction explicite équivalente. L’assemblage Worker
passe `resources.writeBusiness`, `resources.writeEffects` et ses settings `ds` déjà existants.
Les factories Command/Identity passent explicitement source=effects ; aucun nouveau bean/pool/grant.

Signatures exactes des deux points de composition ajoutés (types importés des packages existants) :

```java
public static boolean relevant(ConsumptionDefinition definition,
                                NonBurnableReferences.Reference reference);

public static ConsumptionEngine assemble(
    V3BootstrapGuard guard,
    ConsumptionDefinition definition,
    com.zaxxer.hikari.HikariDataSource business,
    SpringAttemptTransactions effects,
    ConsumptionParticipant participant,
    com.zaxxer.hikari.HikariDataSource control,
    SpringAttemptTransactions technical,
    javax.sql.DataSource writeSource,
    SpringAttemptTransactions sourceReads,
    DiscoveryEngine.Settings discoverySettings,
    LifecycleSettings settings,
    WorkerCapacity capacity,
    AttemptFailureClassifier classifier,
    ConsumptionObservation observation);
```

La première appartient à `V3ConsumptionDiscoveryComposition`, la seconde à
`V3ConsumptionLifecycleComposition`. L’Engine fait aussi évoluer ses deux méthodes privées
`finalizeAttempt(ClaimedSlot, Prepared, AttemptTransactions.Guard)` et
`finalizeAttemptOperation(ClaimedSlot, Prepared, AttemptTransactions.Guard)`, toutes deux de retour
`Attempt`, pour transmettre explicitement la garde au `transactions.write(action, guard)`.
Aucun `ThreadLocal` de session ni dépendance de Spring vers `engine-consumption` n’est nécessaire.

## 5. Durée de vie, erreurs et détachement

### 5.1 Automate de la capacité

La création n’ouvre aucune transaction. L’ouverture d’un scope vérifie : thread d’origine, callback
extérieur non terminé, session non invalidée, aucun scope actif, thread non interrompu. Le scope
actif est réservé atomiquement **avant** l’appel d’owner, et reste actif jusqu’au retour/résolution
physique de `owner.read`. `source/effects` utilisent `read(action, this)` ; source=effects ne fusionne
pas implicitement deux scopes : ce seraient deux snapshots différents. Toute tentative imbriquée,
concurrente, étrangère, après expiration ou pendant finalisation invalide avant d’échouer.
Un essai refusé n’ouvre jamais une connexion supplémentaire.

| Événement | État/conséquence de session |
|---|---|
| Scope confirmé sain | Fermé ; scope suivant autorisé ; aucun commit métier prouvé |
| Callback en erreur et Failure ROLLED_BACK | Invalidée ; aucune suite ; toutes les unités entrées doivent être connues résolues pour restituer |
| Failure COMMITTED mais résultat perdu | Invalidée ; ne pas récupérer un `Prepared` perdu ; aucun finaliseur ; ce read committé n’est pas un commit métier |
| Failure UNKNOWN ou exception brute sans qualification | Invalidée + physiquement incertaine irréversiblement ; aucune suite ni finalisation |
| Erreur gardée interceptée | Le Unit/garde reste empoisonné ; le retour Java du participant ne réarme rien |
| Fin du callback extérieur | `expire()` dans finally ; le résultat n’est utilisable qu’après barrière saine ; la garde reste liée aux Units expirés et au finaliseur |
| Contexte conservé puis réutilisé | Refus avant SQL ; invalide la même garde, donc aussi une unité effects ou finale ultérieure déjà en cours |
| Interruption / CancellationException | Invalidation ; conserver le flag d’interruption ; attendre le retour physique de l’owner synchrone ; un Future annulé n’est pas une preuve de terminaison |

L’incertitude ne peut être effacée par un succès ultérieur. Pas de délai local transformé en rollback.
Si le callback est bloqué, l’Engine ne fabrique pas de retour de scope ; la réserve reste tenue,
les renewals indépendants continuent selon L2 et le drainage reste incomplet au-delà de son budget.
La fermeture des JDBC statements/resultsets reste celle de `JdbcTemplate` ; Spring termine et
unbind ses ressources, le pool restitue/évince la connexion. Le participant ne ferme jamais la
connexion de l’owner. Aucune interruption ne ferme prématurément les pools du Worker.
Utiliser `isInterrupted()` sans effacer le flag. Un `cancel(false)` externe qui ne déclenche ni
interruption ni exception n’est pas un signal observable par cette API synchrone : il n’autorise
aucune restitution ni fermeture. Les refus d’admission/annulation avant exécution restent ceux
de l’enveloppe Worker existante ; aucun token/protocole de cancellation parallèle n’est ajouté.

### 5.2 Contrat opérationnel de détachement

Le générique `<T>` ne prouve pas l’immuabilité. I2 n’ajoute ni sérialiseur universel, ni réflexion
récursive prétendant vérifier tout graphe Java. Les garanties vérifiables sont les suivantes :

| Sortie de port | Obligation et vérification |
|---|---|
| `NonBurnableReferences.Reference`, fenêtres, clés | UUID/long/String, records existants, listes copiées ; aucun contexte capturé |
| Demande Command/Identity | `Stored`, intention et résultat décodés en valeurs ; collections défensives ; aucun Map JDBC conservé dans Prepared |
| Historique Pot | `Snapshot`, `SelectedPot`, composants/versioned/fractions et collections détachées ; pas de référence JPA ni curseur ; contrôle des collections des DTO existants |
| `SubobjectVersionStore.readAll` | Map copiée et valeurs typées décodées ; égalité complète I1 ; aucune vue sur un buffer du codec |
| `Prepared` / `Recovery` | Capturent ces valeurs et des ports injectés ; ne capturent jamais `RecoveryReadContext`, Connection/EntityManager, transaction, stream/supplier de lecture source à exécuter en finalisation |

Les méthodes JDBC matérialisent et ferment intégralement leurs résultats avant retour.
Les callbacks de test négatifs essaient de conserver contexte, ResultSet/Stream et proxy ; ils
prouvent le refus/fermeture de ces ressources, pas la détection magique de tout objet malveillant.
Un nouveau port retournant une ressource attachée est refusé en revue/architecture et doit devenir
un DTO détaché **avant** intégration. Un contrôle structurel inspecte les types de retour/champs des
DTO de production concernés ; les lambdas Prepared sont contrôlées par leurs chemins d’accès et
les compteurs physiques de finalisation. Il n’existe pas de valeur attachée légitimement admise
par l’API que l’Engine devrait « détacher plus tard ».

## 6. Compatibilité WRITE démontrée par chemins avant/après

| Participant/phase | Avant I2 | Après I2 et invariant conservé |
|---|---|---|
| Command préparation | `executeAttempt` ouvre Business WRITE RR RO ; `prepare` fait `store.load`, `authority.observe`, sélection `history.current`, éventuellement `history.activeUsage`, décision et Proposal | `prepare(id,reads)` appelle **un seul** `reads.source(ctx -> prepareInSnapshot(id))`. Le corps actuel entier, y compris tous les chemins rejetés/unchanged, reste dans ce callback privé. Zéro `effects` additionnel. Source et effets = même owner Business WRITE |
| Command finalisation | RC : slot protégé, `store.load` et intention inchangée, `authority.protect`, `protectPot` CAS quand requis, `apply`, `finishTerminal`, complete/readiness/DELETE, `publish` | Même ordre et méthodes ; aucune relecture de l’historique courant pour recalculer dans RC ; collision → rollback et prochain essai L2, pas décision mixant deux snapshots |
| Command recovery | Une observation WRITE de demande/résultat/version historique/composants/publications, croisée avec slot | `prepareRecovery` ne lit pas la source et retourne une comparaison différée de `JdbcPotCommandObservation.observe` ; conversion au §7. Aucun checkpoint burnable |
| Identity préparation | Business WRITE RR RO : charge intention ; TERMINAL retourne empty ; Prepared capture Stored | Un seul `reads.source(ctx -> prepareInSnapshot(id))` enveloppe ce corps ; ne lit ni ne décide de courant EUI |
| Identity finalisation | RC : slot protégé, recharge intention, verrous EUI ordonnés, lectures courant après attente, décision Registration/Link/Unlink, apply si Changed, terminal, complete puis publish | Corps inchangé. Génération token/user et `IdentityTransitions` restent sous verrous. Pas de décision d’autorité dans source RR |
| Identity recovery | Préfixe durable WRITE avec provenance, EUI historique, utilisateur, événements et positions | Comparaison différée de `JdbcIdentityRequestObservation.observe` dans WRITE, slot même snapshot ; aucun checkpoint |
| Probes READ | Effet `i5_read_probe_effect`, aucune matérialisation métier | Adaptation interface et récupération technique complète ; récupération source par Engine, preuve exacte de l’effet ; zéro activation métier |

`prepareInSnapshot(UUID)` est une méthode privée cible de chaque participant, de retour
`Optional<Prepared>`, obtenue en extrayant son corps `prepare(UUID)` actuel. Les classes
`JdbcPotCommandObservation` et `JdbcIdentityRequestObservation` gardent leurs signatures de port :
conversion `COMPLETED→SATISFIED`, `NOT_COMPLETED→MISSING`, `INCONSISTENT→DIVERGENT`,
`UNAVAILABLE→UNAVAILABLE` dans la lambda Recovery. Aucun changement de leurs règles métier.

Le snapshot Command doit être prouvé par PID + `pg_current_snapshot()` et par un interleaving où
une autre session change l’autorité/le Pot entre deux lectures. Un même PID seul ne prouve pas
un snapshot unique. Identity doit prouver que le résultat utilise l’état committé **après** attente
sur EUI, dans RC, avec token périmé, auto-Link/Unlink et deux EUI en ordre canonique.
Les tests L1/L3 existants restent requis (§11), sans réinterpréter leurs anciennes preuves.

## 7. Protocole de recovery complet

### 7.1 Acquisition des faits, sans jointure interautorités

`observe(ClaimedSlot)` garde sa signature publique et son absence d’effet durable. Il n’exécute
jamais de réparation, cession, recalcul métier ou DELETE. Séquence cible :

1. Valider Consumption du claim et `unresolvedRecovery`. Créer une nouvelle session Reads.
2. Pour READ événement : `reads.source(ctx -> recoverySource.find(id,ctx))` ; conserver seulement
   la référence publiée immuable. Vérifier famille du port, eventId, sujet et `relevant`. Calculer
   `p = RoutingV1.partition(RoutingV1.hash(definition.family(), id), definition.partitionCount())`.
   Source absente/incompatible/inéligible positivement constatée pour le claim prétendu enrôlé =
   INCONSISTENT ; `InvalidReference` remontée par une Failure physiquement résolue donne aussi
   INCONSISTENT. UNKNOWN domine toujours ce diagnostic et donne UNAVAILABLE. Une impossibilité
   technique de lire donne UNAVAILABLE. Aucun lookup de slot dans PRIMARY.
3. Appeler `participant.prepareRecovery(id, reads)` hors transaction ambiante. Pour WRITE,
   démarrer directement ici, sans référence source ni checkpoint. Expirer les scopes en finally.
4. Si garde saine : `effectsTransactions.read(ctx -> ..., guard)` ; dans **ce seul snapshot** lire
   `slots.observe(C,id,ctx)`, puis pour READ `admissions.checkpoint(C,p,ctx)`, puis
   `Recovery.observe(ctx)` pour les effets métier. Les trois appels utilisent la même datasource
   Business et le même Unit. WRITE n’appelle jamais `checkpoint`.
5. Combiner les faits suivant les tables ci-dessous. Retourner le verdict seulement après résolution
   confirmée du snapshot. Failure ou exception ne laisse pas échapper le verdict calculé dans le callback.
6. Hors `InvalidReference` qualifiée à l’étape 2, `recoveryFailed` conserve sa distinction : Failure
   physiquement résolue → UNAVAILABLE pour cet
   appel sans nouvelle incertitude physique ; UNKNOWN/brute → `unresolvedRecovery=true` irréversible,
   et `unresolvedPreparation=true` pour le handle concerné s’il existe. Le Worker draine selon L2.

Les faits techniques sont des variables locales de l’Engine : `SlotObservation`, `long K`,
`Reference`, partition `int` et `EffectEvidence`. Ils ne sont ni préparés hors READ, ni fournis par
un mock de participant faisant autorité. Les ports d’ownership/navigation ne sont pas sollicités
pour inventer une preuve de complétion. Les snapshots ne consultent pas plusieurs autorités.

### 7.2 Table READ événement — priorité et exhaustivité

Les observations sont complètes et décodables dans le snapshot effets, sauf mention contraire.
« K insuffisant » inclut checkpoint absent (valeur contractuelle 0). Un claim fourni à ce recovery
prétend un événement enrôlé ; ce n’est pas une API de statut pour événement arbitraire jamais découvert.

| Priorité / faits | Slot PRESENT | Slot ABSENT |
|---|---|---|
| Source ou faits techniques impossibles à lire/qualifier ; comparaison UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| Source positivement absente/corrompue, mauvaise famille/sujet/éligibilité, routing incompatible | INCONSISTENT | INCONSISTENT |
| K < position source, lecture réussie | INCONSISTENT : slot non-burnable au-dessus du préfixe contraire à Discovery | INCONSISTENT : absence sans enrôlement couvert ne prouve pas complétion |
| K ≥ position ; preuve DIVERGENT | INCONSISTENT | INCONSISTENT |
| K ≥ position ; preuve MISSING, y compris ensemble partiel conforme | NOT_COMPLETED | INCONSISTENT |
| K ≥ position ; preuve SATISFIED, effet créé par cet essai **ou identique préexistant** | NOT_COMPLETED | COMPLETED |

Une divergence positivement connue reste diagnostiquée ; si une autre lecture indispensable a
échoué, le verdict conservateur est UNAVAILABLE avec diagnostic, jamais COMPLETED. La première
ligne ne convertit pas une corruption lisible en simple absence. Slot PRESENT inclut QUARANTINED,
claim expiré ou token d’un autre owner : `observe` ne les interprète pas comme un droit d’exécuter.
Les contraintes de routing du schéma et le préflight sont les garanties de sa partition ; I2 ne
change pas `ConsumptionSlotStore.observe` pour y dupliquer le calcul.

### 7.3 Table WRITE burnable

| Preuve du préfixe demande/métier/publications dans WRITE | Slot PRESENT | Slot ABSENT |
|---|---|---|
| SATISFIED (terminal complet) | INCONSISTENT | COMPLETED |
| MISSING (PENDING sans suffixe illégitime) | NOT_COMPLETED | INCONSISTENT |
| DIVERGENT | INCONSISTENT | INCONSISTENT |
| UNAVAILABLE / échec de lecture | UNAVAILABLE | UNAVAILABLE |

Les observateurs WRITE distinguent déjà PENDING sain, résultat/publication partiel et terminal
complet, y compris rejected/unchanged. Une sous-version historique préexistante n’est pas un
terminal de demande Command ; la nouvelle règle READ ne doit pas assouplir cette table WRITE.

### 7.4 Postconditions métier attendues des futurs participants

Ces lignes fixent le sens de la preuve, sans livrer leurs tables/adaptateurs I3–I7 :

| Objet / cas | SATISFIED | MISSING / DIVERGENT |
|---|---|---|
| Sous-versions/PotVersion(V) | Toutes les clés et tous les contenus exacts à V, composition canonique conforme | Absent/partiel conforme = MISSING ; contenu ou composition divergent = DIVERGENT ; pointeur >V insuffisant |
| PotBalance(V), Pot actif | Balance exacte à V et contenu complet attendu | Balance absente = MISSING, autre contenu = DIVERGENT ; balance à W>V ne remplace pas V |
| PotBalance(D), Pot supprimé | Source prouve D et aucune balance à D, conformément au contrat L4 | Balance à D présente = DIVERGENT ; l’absence conforme est SATISFIED, pas MISSING |
| CurrentPot | Pointeur monotone cohérent, sans dispenser des preuves PotVersion(V) exactes requises | Pointeur illégal ou régressé = DIVERGENT ; pointeur seul ne prouve pas le slot |
| Visibility à V=G | Ensemble complet attendu, garde et état à G cohérents | Ensemble divergent = DIVERGENT ; publication requise absente = MISSING |
| Visibility V<G | État et garde G cohérents ; ne pas exiger l’ancien ensemble V encore courant | G seul sans cohérence = DIVERGENT ou MISSING selon donnée constatée ; jamais preuve technique de complétion |
| CurrentBinding V=W / V<W | À égalité comparer contenu complet ; dépassé vérifier cohérence de W sans reconstituer V comme courant | Divergence à égalité ou état W corrompu = DIVERGENT |
| CommandResult / IdentityRequestResult | Résultat immuable complet : issue, identité/provenance et contenu du résultat source | Absent = MISSING ; différence même partielle = DIVERGENT ; pas égalité par seule version/ID |

Dans tous ces cas, **SATISFIED + slot présent = NOT_COMPLETED READ** ; seul le triplet couverture
éligible + slot absent + preuve satisfaite permet COMPLETED. Les pointeurs monotones dépassés et
la déduplication interslots ne sont pas de faux INCONSISTENT. Les tests I2 utilisent des représentations
techniques de ces cas, sans créer les participants ou tables métier futurs.

### 7.5 ACK perdus et trois notions séparées

| Situation | Résolution physique historique | Observation possible | Permission de restitution |
|---|---|---|---|
| ACK final perdu, commit réellement durable | UNKNOWN conservé | READ : K couvert, slot absent, effets conformes → COMPLETED | Seulement `settlePending`, inspection résolue + observation cohérente + sortie locale et aucune préparation incertaine |
| ACK final perdu, rollback réel | UNKNOWN conservé | Slot présent, effets absents/identiques préexistants → NOT_COMPLETED | Inspection PRESENT_UNLOCKED peut résoudre la responsabilité finale selon L2 ; jamais sur le seul NOT_COMPLETED |
| Transaction finale encore active | UNKNOWN | Ancien snapshot peut montrer slot présent et effets absents | LOCKED empêche restitution ; observation seule n’établit pas rollback |
| ACK d’un read préparatoire perdu | UNKNOWN monotone de cette préparation | Même un COMPLETED ultérieur ne résout pas cette lecture qui n’avait pas le verrou de slot | Aucune restitution par inspection du slot ; responsabilité gardée jusqu’à terminaison du Worker |
| ACK de recovery perdu | UNKNOWN ; `unresolvedRecovery` | Verdict calculé dans callback écarté → UNAVAILABLE | Aucune restitution ; drainage incomplet |
| ACK de cession perdu | Résolution de cession distincte de celle de l’essai | Ne pas déduire son nombre d’échecs d’un état ultérieur | Pas de seconde cession ; inspection technique existante seulement |

**Résolution physique ≠ observation durable ≠ autorisation de restitution.** I2 ne change ni les
énums de résolution, ni cette séparation, ni le propriétaire du budget.

## 8. Transitions exactes dans le cycle L2 existant

`executeAttempt` ne possède pas le ticket. Il renvoie un Attempt ou laisse remonter une erreur ;
`executeOperation` est toujours l’unique chemin de libération immédiate et de cession classifiée.
Aucun ticket/sémaphore nouveau n’est créé par Reads. L’incertitude de session est recopiée de
façon monotone vers `acquired.unresolvedPreparation` **avant** de quitter `executeAttempt`, y
compris si le participant a absorbé l’erreur.

| Point / issue | Transition imposée |
|---|---|
| Préparation saine, Prepared présent | Finalisation existante ; garder la garde de session jusqu’à résolution finale |
| Préparation saine, Optional.empty | `observed(claim, UNKNOWN, null)` comme aujourd’hui ; nouvelle observation ; pas de finalisation répétée |
| Scope ROLLED_BACK confirmé | Attempt ROLLED_BACK/ROLLED_BACK, cause originale ; classification existante puis éventuelle cession unique ; libération seulement si dernière opération résolue |
| Scope COMMITTED mais callback/result perdu | Aucun Prepared utilisable ; observation seulement ; Attempt historique UNKNOWN côté résultat métier ; **pas** `unresolvedPreparation` si toutes les lectures sont physiquement résolues ; règlement par chemin L2 existant |
| Refus local hors unité après scopes connus résolus | Tentative empêchée, aucune transaction métier ouverte ; chemin de rejet ROLLED_BACK avec cause, sans prétendre qu’un read COMMITTED a rollbacké ; faute de contrat non comptable |
| Scope UNKNOWN ou brut non qualifié | `unresolvedPreparation=true`, Attempt RECOVERY_PENDING/UNKNOWN et diagnostic ; aucun scope suivant, aucun finaliseur, **aucun recovery supplémentaire déclenché depuis cette préparation invalidée** |
| Annulation pendant callback non sorti | `localDone=false` ; pas de restitution ; attendre sortie synchrone, ne pas assimiler Future.done à localDone |
| Finalisation COMMITTED certaine | Attempt COMMITTED/COMMITTED ; sortie locale puis ticket libéré si aucune lecture incertaine |
| Finalisation ROLLED_BACK certaine | CLAIM_LOST ou ROLLED_BACK ; classifier et cession comme aujourd’hui ; claim perdu non comptable |
| Finalisation UNKNOWN | Observation fraîche autorisée car préparation résolue ; résolution Attempt demeure UNKNOWN même si status COMMITTED ; ticket pending jusqu’au règlement sûr |
| Recovery UNKNOWN/brut | `recoveryFailed` marque exclusion Engine ; pas de retries masquant l’incertitude ; Worker demande drainage |

Une exception brute du participant **hors scope**, dont l’absence de transaction est contrôlée et
qui survient après des scopes tous résolus, n’est pas une preuve d’un read UNKNOWN ; la nouvelle
session distingue ce cas d’une erreur brute sortant de l’owner. Les erreurs `Error` sans résolution
qualifiée provenant de l’owner restent conservatrices. Le classifier n’absorbe pas les violations
structurelles comme des échecs métier : la cause de garde doit être reconnue NON_COUNTABLE ;
les causes SQL/JPA/techniques réellement qualifiées gardent le classement L2. Ne pas transformer toute `IllegalStateException` métier en exclusion. La faute
de contrat utilise le type public imbriqué
`PreparationReads.Invalid extends IllegalStateException` avec constructeur `(String, Throwable)` ;
les compositions le reconnaissent explicitement comme NON_COUNTABLE.

`settlePendingOperation` conserve son protocole :

1. si `unresolvedRecovery`, retourner 0 ;
2. candidats `localDone && !unresolvedPreparation`, bornés à batchSize ;
3. `technical(controlSlots.inspectResolved(...))` dans RC technique borné ;
4. LOCKED → conserver ; PRESENT_UNLOCKED → peut libérer la responsabilité de finalisation/cession
   terminée, indépendamment d’une preuve de commit métier ; ABSENT → si businessStarted,
   appeler recovery et exiger COMPLETED ;
5. recontrôler sortie locale/exclusions, retirer `pending`, `ticket.release` idempotent.

Une nouvelle observation UNKNOWN pendant cette boucle coupe les restitutions suivantes.
Aucune observation NOT_COMPLETED ne décrète un rollback, une restitution, un retry ou une cession.
Le règlement technique ne réécrit jamais la résolution UNKNOWN stockée dans l’Attempt historique.

Fencing/renewal/saturation/drainage restent ceux de L2 : tokens neufs, validation après attente
sur `clock_timestamp`, protection du slot jusqu’à terminaison sans recheck d’échéance après
protection, pas d’exécution depuis un handle étranger/réutilisé, limites M_W/M_C, renewals Control
indépendants pendant scopes Business, arrêt des admissions au drainage, fermeture des pools
seulement après sorties locales/renouvellements/ressources et capacité nulle. Le timeout de
drainage signale l’incomplétude ; il ne fabrique pas STOPPED. Aucun changement du Worker n’est prévu.

## 9. Séquence courte d’implémentation après GO distinct

| Étape | Production concrète | Preuve de passage |
|---|---|---|
| 1 — Contrats et owner | Ajouter PreparationReads/Invalid, Guard + surcharges, helpers Spring et session Reads ; adapter tous les fakes/décorateurs et signatures participantes dans le même changement compilatoire | Compilation/test-compile du périmètre ; unités de session, tests owner de poisoning actuel + interscopes ; aucun fallback ignorant Guard |
| 2 — Préparation WRITE/READ | Brancher source explicite, préserver `prepareInSnapshot` Command/Identity, enveloppes JDBC de lecture, probe et fixture interautorités | P01–P08, W01–W04 ; mêmes snapshots Command et décisions EUI après attente ; aucune lecture WRITE pendant finalisation READ |
| 3 — Recovery complet | `find`, prédicat Discovery commun, admissions Business, comparaison différée, tables READ/WRITE, propagation dans execute/recovery/pending | R01–R09, C01–C05 PostgreSQL ; ACK perdus et contre-oracles ; aucune modification du protocole capacité/Discovery |
| 4 — Acceptation bornée | Requalification L2 ciblée puis gates canoniques et racines actives, inventaire de preuves actuel, rapport attribué | Critères §13 ; verdict séparant implémentation, qualification, audit et acceptation humaine |

Les adaptations de tests compilatoires peuvent précéder la preuve PostgreSQL mais ne doivent
pas remplacer les oracles par des mocks qui retournent arbitrairement la résolution attendue.
Avant chaque étape, confirmer le diff contre la frontière déclarée. Une anomalie de précondition
I1 concrète est documentée comme telle ; ne pas rouvrir I1 pour une hypothèse générale.

## 10. Inventaire exact CREATE / MODIFY / UNCHANGED

Ces statuts décrivent **l’implémentation prévue**, pas les modifications réalisées par la mission.
Les chemins ci-dessous ont été confrontés au checkout. Les chemins CREATE sont explicitement
nouveaux. Les tests/décorateurs inventoriés ne sont pas des mécanismes runtime supplémentaires.
Aucun « fichier à découvrir après GO » n’est laissé pour les contrats changés.

### 10.1 Production

| Action | Chemin exact | Changement prévu |
|---|---|---|
| CREATE | `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/PreparationReads.java` | Capacité et exception Invalid imbriquée |
| MODIFY | `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionParticipant.java` | Signatures et Recovery/EffectEvidence imbriqués |
| MODIFY | `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionEngine.java` | Source explicite, Reads privée, barrière finale, lookup/couverture et composition des preuves ; propagation vers les flags L2 |
| MODIFY | `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/NonBurnableReferences.java` | find et InvalidReference, sans changement window |
| MODIFY | `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/v3/AttemptTransactions.java` | Guard imbriquée et surcharges obligatoires, compatibilité des appels anciens |
| MODIFY | `app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/v3/SpringAttemptTransactions.java` | Garde des Units jusqu’au commit, observed/participating, refus empoisonnants et préservation I1 |
| MODIFY | `app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/v3/ExecutePotCommand.java` | Un seul source autour du corps existant, comparaison recovery différée |
| MODIFY | `app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/v3/ExecuteIdentityRequest.java` | Intention en source, décision finale inchangée, comparaison recovery différée |
| MODIFY | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcEventDiscoveryReferences.java` | Lookup exact et enveloppe observed pour find/window |
| MODIFY | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcDiscoveryAdmissionStore.java` | Enveloppe observed sur checkpoint, SQL inchangé |
| MODIFY | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcConsumptionSlotStore.java` | Enveloppe observed sur observe ; acquisition/renew/cession/protection/DELETE/inspection inchangés |
| MODIFY | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotHistoryAdapter.java` | Envelopper verifyHistory, atVersion, financialAtVersion, current, componentsAtVersion, activeUsage ; aucun nouvel algorithme historique |
| MODIFY | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotCommandStore.java` | participating sur load/currentVersion ; writes/publication inchangés |
| MODIFY | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotCommandObservation.java` | observed autour de l’observation existante |
| MODIFY | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityRequestStore.java` | participating sur load ; finalisation/publication inchangées |
| MODIFY | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityRequestObservation.java` | observed autour de l’observation, sans changer la redaction ni les verdicts métier |
| MODIFY | `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityPotActorAuthority.java` | participating autour de observe, y compris validation/redaction ; protect inchangé |
| MODIFY | `app/infra-persistence-read-jdbc/src/main/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/JdbcSubobjectVersionStore.java` | observed sur readAll incluant codec ; writers I1 inchangés |
| MODIFY | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionLifecycleComposition.java` | Construction explicite et overload READ §4.4 ; Invalid non comptable |
| MODIFY | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionDiscoveryComposition.java` | Méthode pure relevant commune, pas changement de Discovery |
| MODIFY | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java` | Injecter les owners/source/admissions existants, aucune nouvelle ressource |
| MODIFY | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandComposition.java` | Passer explicitement source=effects et dépendances de recovery WRITE |
| MODIFY | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3IdentityRequestComposition.java` | Même explicitation WRITE |
| MODIFY | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/dev/V3TechnicalReadParticipant.java` | API et preuve différée ; différencier effet absent et valeur divergente |
| MODIFY | `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/dev/V3FixturePotActorAuthority.java` | participating autour de la lecture fixture utilisée par le pilote Command |

UNCHANGED, réemployés et requalifiés selon leur impact :

| Chemin exact | Garantie conservée |
|---|---|
| `app/port-transaction/src/main/java/com/kartaguez/pocoma/port/transaction/v3/RecoveryReadContext.java` | Signature inchangée ; seule l’implémentation Unit évolue |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/RecoveryObservation.java` | Quatre résultats existants |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/SlotCompletion.java` | Complétion unique existante |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/WorkerCapacity.java` | Ticket unique, limites et release idempotent |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/DiscoveryEngine.java` | Admission et checkpoint atomiques, préfixe, burnable/non-burnable |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/DiscoveryAdmissionStore.java` | Port checkpoint réutilisé sans extension |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/ConsumptionSlotStore.java` | Port slot/inspection réutilisé sans extension |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/PartitionOwnershipStore.java` | Ownership technique existant |
| `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/SlotKeyNavigation.java` | Navigation MVCC bornée |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcPartitionOwnershipStore.java` | Fencing/horloges/timeouts techniques |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcSlotKeyNavigation.java` | Navigation existante |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotHistoryRows.java` | SQL et sélection L1 ; gardes externes ajoutées à l’adaptateur |
| `app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityAuthority.java` | Verrous/décision/écriture EUI existants |
| `app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/v3/SubobjectVersionStore.java` | readAll/insertOrVerifyAll/requireStored inchangés |
| `app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionWorker.java` | Exécution/renouvellement/drainage ; aucun protocole parallèle |
| `app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3RenewalService.java` | Renouvellement et règlement existants |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3BootstrapGuard.java` | Cibles/permissions/routes ; aucun élargissement des grants |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerSettings.java` | Pools/quotas/activation fermée |
| `app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionCatalogConfiguration.java` | Catalogue inchangé |

Toutes les migrations du §2.1, POM, scripts, configurations et preuves sont UNCHANGED. Les ports
L1 et Identity (`PotHistoryPort`, `PotCommandStore`, `PotCommandObservation`, `IdentityRequestStore`,
`IdentityRequestObservation`, `IdentityAuthority`, `PotActorAuthority`) gardent toutes leurs signatures.
Aucune évolution métier/SQL n’est cachée dans le mot « adaptation ».

### 10.2 Tests et adaptateurs de test

Les tables suivantes sont l’inventaire compilatoire et probatoire. Chaque fichier MODIFY existant
est réellement présent ; aucun changement n’est réalisé pendant la présente mission.

| Action | Chemin exact | Objet |
|---|---|---|
| MODIFY | `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3ConsumptionEngineArchitectureTest.java` | Frontière pure, garde/owner exclusivement L2, contre-exemples et absence de ressource attachée |
| UNCHANGED | `app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3ConsumptionFoundationArchitectureTest.java` | Référence/types conservés ; requalification sans adaptation de signature |
| MODIFY | `app/engine-consume-registration/src/test/java/com/kartaguez/pocoma/engine/consume/registration/v3/ExecuteIdentityRequestTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/engine-consumption/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionFailureClassificationTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/engine-consumption/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/DiscoveryEngineTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/v3/ExecutePotCommandTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/I2ReservedClaimFixture.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/ConsumptionLifecyclePostgresFixture.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/ConsumptionLifecyclePostgresTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/DiscoveryPostgresFixture.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/DiscoveryPostgresTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/ForwardingTransactions.java` | Déléguer les nouvelles surcharges en conservant la garde |
| MODIFY | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/L2ManifestProof.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/SlotNavigationPostgresTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/IdentityRequestExecutionPostgresTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/infra-tx-spring/src/test/java/com/kartaguez/pocoma/infra/tx/spring/v3/AttemptTransactionsPostgresTest.java` | Régression I1 et propagation entre Units/threads/callbacks tardifs |
| MODIFY | `app/orchestrator-poll-consumption/src/test/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionWorkerTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| UNCHANGED | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/CommandReservedClaimFixture.java` | Référence/types conservés ; requalification sans adaptation de signature |
| MODIFY | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/ConsumptionLifecycleCompositionPostgresTest.java` | Preuve de la racine active avec nouveaux contrats et owners explicites |
| MODIFY | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/DiscoveryCompositionPostgresTest.java` | Preuve de la racine active avec nouveaux contrats et owners explicites |
| MODIFY | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3IdentityRequestCompositionPostgresTest.java` | Preuve de la racine active avec nouveaux contrats et owners explicites |
| MODIFY | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandCompositionPostgresTest.java` | Preuve de la racine active avec nouveaux contrats et owners explicites |
| MODIFY | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerPostgresTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerResourcesPostgresTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3IdentityRequestMonolithPostgresTest.java` | Preuve de la racine active avec nouveaux contrats et owners explicites |
| MODIFY | `app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3WorkerMonolithPostgresTest.java` | Preuve de la racine active avec nouveaux contrats et owners explicites |
| MODIFY | `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/SubobjectAtomicityPostgresTest.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| MODIFY | `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/SubobjectPostgresFixture.java` | Adapter prepare/recovery, constructeurs, doubles/forwarders et ajouter les oracles concernés sans réduire les assertions |
| CREATE | `app/engine-consumption/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/PreparationReadsTest.java` | Automate, barrières, erreurs et capacité ; pas preuve physique |
| CREATE | `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/InterAuthorityPreparationPostgresTest.java` | P01–P08 et W01 ; vrai PRIMARY/READ distincts |
| CREATE | `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/InterAuthorityRecoveryPostgresTest.java` | R01–R09, C01–C05 et contre-oracles |
| CREATE | `app/runtime-task-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/task/v3/InterAuthorityPostgresFixture.java` | Support commun des deux classes, owners et données sources réelles, témoins indépendants |

Les fixtures READ historiques qui créent directement des slots doivent désormais fournir aussi
une référence source et un checkpoint cohérents lorsque leur scénario appelle le recovery.
Leurs sources analogues restent des fixtures explicitement identifiées ; elles ne prouvent pas
les producteurs réels. Le nouveau banc interautorités utilise les vraies publications PRIMARY
V3 existantes, générées par Command/Identity, et leurs positions, dans une autre base que READ.
Il réemploie I1 pour les effets typés. Ne pas simplement programmer un mock à répondre COVERED.
Les scénarios négatifs modifient administrativement les fixtures pour créer une incohérence,
jamais les contrats de production. Toute table témoin supplémentaire est exclusivement de test,
pas migration ni nouvel état durable du protocole applicatif.

Les décorateurs qui interceptent actuellement `prepare`/`observe` doivent transmettre les scopes
et la comparaison différée sans déplacement transactionnel. Les fakes `NonBurnableReferences`
des tests Discovery/Worker implémentent `find` explicitement (ou échouent si un scénario sans
recovery l’appelle). Les décorateurs `AttemptTransactions` transmettent la garde aux vrais owners
et conservent leurs points d’injection de panne ; une surcharge héritée qui contournerait le
proxy d’ACK invaliderait le test. Le `CommandReservedClaimFixture` ne change pas : il ne fait
que réserver un handle. Les tests et reçus I1 historiques restent des preuves attribuées à I1 ;
leur adaptation I2 produit de nouvelles preuves, sans réécrire les anciennes.

## 11. Matrice des tests et oracles discriminants

### 11.1 Tests rapides et PostgreSQL nouveaux

| ID | Scénario / résultat exigé | Oracle indépendant qui distingue une fausse implémentation |
|---|---|---|
| P01 | PRIMARY source → READ effects → PRIMARY source → READ final ; snapshots RR RO puis RC final | Journaux emprunt/retour par datasource, `pg_stat_activity`, database/schema/role, PID et snapshot ; aucun chevauchement Business source/effets pour cette tentative ; les connexions renewal Control sont distinctes et autorisées |
| P02 | source dans source/effects dans source, même owner ou deux owners ; concurrence depuis second thread | Refus avant second begin/SQL, poison monotone ; callback peut catch mais finaliseur jamais invoqué ; compteur source/READ indépendant |
| P03 | Contexte source expiré utilisé pendant effects, puis pendant finalisation, erreur interceptée | Même garde invalidée ; préfixe effects + DELETE rollbackés ; snapshot extérieur montre slot conservé et zéro effet nouveau |
| P04 | Faute gardée avec `Failure` UNKNOWN au premier scope, participant absorbe puis tente effects/return Prepared | Aucun scope suivant, aucun recovery dans cette session, aucun finaliseur ; ticket retenu même après slot déverrouillé/absent ; classification non optimiste |
| P05 | Corruption de codec/DTO levée après SQL puis catch dans le callback ; mauvais context/datasource ; guarded writer sur mauvaise autorité | Erreur Java ne se perd pas faute de transaction SQL abortée : observed/participating invalident ; aucun commit métier ; inspecter aussi l’absence d’écriture sur PRIMARY |
| P06 | Interruption et annulation avant ouverture, pendant requête bloquée et après retour du callback avant commit | Barrière PG attestant blocage puis résolution ; flag d’interruption conservé ; `localDone` seulement à sortie réelle ; pas de faux STOPPED/restitution |
| P07 | Read ROLLED_BACK certain / COMMITTED avec cleanup perdu / UNKNOWN / Error brut | Compteurs des scopes, résolution physique instrumentée et sortie locale ; quatre chemins distincts §8, aucun COMMITTED métier depuis un read |
| P08 | Ressources échappées : ancien contexte, curseur/Stream, graphe DTO et lambda retenant source | Ressource fermée/refusée après scope ; assertions de types/détachement et compteur SQL PRIMARY nul pendant finalisation READ ; ne pas se limiter à un `instanceof` sur l’objet racine |
| W01 | Command : mutation concurrente du Pot/acteur entre load/observe/history | Toutes les lectures préparatoires gardent le même snapshot ; protection finale provoque collision si état changé, aucune décision hybride ; un seul scope source |
| W02 | Identity : Link/Unlink/Registration concurrents, token périmé, deux EUI et identité identique | `pg_locks`/attente matérialisée, lecture de la version après déblocage RC, décision durable attendue et ordre canonique ; pas simple vérification des méthodes appelées |
| W03 | Faute interceptée en beforeCommit et beforeCompletion JDBC/JPA, après DELETE/closePublication | Listener I1 toujours après callbacks, rollback extérieur des effets/slot ; observation des mêmes PID/XID, pas seulement exception Java |
| W04 | Demande terminale au prepare, rejected/unchanged, ancienne version historique et collision | Aucune double finalisation ; préfixe exact/publications conservés ; observe WRITE sans checkpoint, contrôlé par droits/compteur d’appels |
| R01 | READ slot présent : effets absents, partiels conformes, identiques préexistants, projection dépassée cohérente | NOT_COMPLETED pour chaque cas couvert ; créer les effets **avant** l’essai par autre transaction et autre slot ; détecte l’ancien `COMPLETED + PRESENT → INCONSISTENT` |
| R02 | READ slot absent, K insuffisant/absent ou mauvais C/p | INCONSISTENT, jamais succès ; checkpoints de l’autre Consumption/partition plus hauts ne compensent pas |
| R03 | READ slot absent, couverture exacte/plus haute ; effets exacts, absents ou divergents | Respectivement COMPLETED / INCONSISTENT / INCONSISTENT ; données relues directement par connexion d’oracle |
| R04 | Source absente, mauvais subject/famille, exclusion Identity POCOMA_USER, mauvaise position, source indisponible | Anomalie lisible → INCONSISTENT ; réseau/lecture impossible → UNAVAILABLE ; aucune source cachée dans le snapshot READ |
| R05 | Publication attendue entre deux statements de recovery READ | Un seul snapshot `pg_current_snapshot()` : ne pas combiner ancien slot et nouvel effet/checkpoint ; contre-implémentation à deux transactions doit échouer |
| R06 | ACK perdu avant envoi COMMIT / après COMMIT serveur, rollback explicite et backend encore actif | Proxy existant d’ACK + backend/locks + snapshots hors owner ; UNKNOWN historique inchangé ; preuve durable correcte et capacité séparée |
| R07 | Callback recovery calcule COMPLETED puis read final UNKNOWN ; source prep UNKNOWN ; subsequent observe | Verdict du callback non publié ; UNAVAILABLE, exclusion Engine monotone, ticket retenu/drainage ; pas de retry réparateur |
| R08 | Balance supprimée, pointeur monotone dépassé, égalité de version et contenu divergent, résultat partiellement égal | Simulateurs techniques avec valeurs indépendantes appliquant §7.4 ; détecter « version seule suffit », absence conforme confondue avec MISSING et état récent seul utilisé comme reçu |
| R09 | Tables WRITE complètes : PENDING/terminal avec slot PRESENT/ABSENT et corruption historique/publication | Tableau §7.3 exact ; aucune consultation de checkpoint (SQL trigger l’interdit) ; préservation observateurs L1/L3 |
| C01 | UNKNOWN final avec callback encore actif vs revenu ; LOCKED/PRESENT_UNLOCKED/ABSENT | Barrières SQL + handle/capacité ; pas de release avant `localDone`, pas de release sur simple NOT_COMPLETED |
| C02 | UNKNOWN préparatoire sans verrou slot, puis suppression/expiration/déverrouillage du slot | Ticket jamais restitué par `inspectResolved`, même si les effets sont conformes ; propriété `unresolvedPreparation` |
| C03 | Préparation longue source puis effects, toutes les réserves M_W/M_C occupées | Renewals Control continuent réellement ; acquisition supplémentaire refuse proprement ; pas de cinquième pool ni nouveau quota |
| C04 | Drain pendant scopes/UNKNOWN/recovery ; deadline dépassée | Admissions arrêtées, opérations comptées, réserves retenues, pools non fermés prématurément, pas de STOPPED nominal ; sortie réelle observable |
| C05 | Rollback qualifié, collision, faute de contrat, cession UNKNOWN et classifier qui échoue | Budget incrémenté une seule fois lorsque requis, pas pour contrat/collision ; pas de deuxième cession ni fuite/libération spéculative |

P02/P04/P07 et les tables de combinaison ont aussi des tests unitaires dans `PreparationReadsTest` ;
ils vérifient l’automate sans se substituer à PostgreSQL. Les fakes retournant des Failure arbitraires
ne prouvent pas que l’owner réel a physiquement terminé.

### 11.2 Oracles physiques et contre-oracles

Le banc initialise des cibles V3 propres via les migrations CURRENT pertinentes et des rôles
runtime réels ; pas de H2 pour cette preuve, pas de schéma `public` improvisé, pas de migration
V2 historique ajoutée au banc. Les deux autorités doivent être des bases distinctes pour qu’un
join accidentel, un search_path chanceux ou une seule connexion ne puissent faire passer P01/R05.
Les contrôles suivants sont obligatoires :

- Témoins SQL indépendants sur effets et DELETE : database/schema/user, `pg_backend_pid()`,
  XID quand une écriture l’alloue, `pg_current_snapshot()`, isolation et read-only. Un read RR peut
  ne pas avoir de XID ; ne pas l’allouer artificiellement pour assimiler PID et transaction.
- Snapshots d’observation extérieurs avant, pendant et après commit/rollback ; logs SQLSTATE,
  locks et interleavings attestés par barrières/`pg_locks`, pas par `sleep` supposé suffisant.
- Comptage borrow/return et ressources actives pour la tentative ; un backend idle du pool n’est
  pas une connexion simultanément possédée par le participant. Une panne réseau UNKNOWN peut
  laisser un backend en activité : précisément le cas où aucune phase suivante ne doit commencer.
- Valeurs attendues calculées depuis données source ou assertions SQL indépendantes, pas par le
  codec/comparateur de production seul. Changer un champ autre que clé/version doit faire échouer
  l’égalité ; valeurs identiques préparées par une autre transaction doivent rester acceptables.
- Contre-oracle finalisation divisée en deux commits ; contre-oracle recovery à deux snapshots ;
  source PRIMARY consultée depuis finalisation READ ; suppression isolée du slot ; ancien contexte
  réutilisé pendant effects ; garde perdue par un forwarder. Chaque contre-implémentation doit
  être détectée par la propriété pertinente, pas seulement par la compilation.

Les contre-implémentations sont confinées aux tests ; aucune option runtime permettant de
désactiver les garanties n’est livrée. Timeout sans interleaving attesté, trace manquante, skip,
absence du serveur ou résultat non attribuable = NOT RUN/INCONCLUSIVE/FAIL selon la politique,
jamais PASS. Les diagnostics contradictoires restent archivés.

### 11.3 Requalification nominative des acquis affectés

| Acquis / classes existantes | Obligation I2 / transfert autorisé |
|---|---|
| `AttemptTransactionsPostgresTest` | Rejouer la classe entière : unité JPA/JDBC, ambient/REQUIRES_NEW/nesting, contexte owner/lifetime, commit rejeté/masqué, cause originale et poisoning tardif |
| `SubobjectAtomicityPostgresTest` | Rejouer en entier : même unité, rollback à toutes frontières, readiness, publication fermée, concurrence identique/divergente, contre-oracle transactions indépendantes, late callback et lost ACK |
| `SubobjectStorePostgresTest`, `SubobjectMigrationPostgresTest` | Conservation stockage/codec/schéma I1 ; inclus dans PROJECTION, ne pas utiliser les anciens totaux comme nouveaux résultats |
| `ConsumptionLifecyclePostgresTest` | Rejouer les familles préparation/finalisation/cession, `i3_c05_*`, audits UNKNOWN actif/local exit, absentRequiresCoherentR9, classifier et `i3_capacity_uncertainty` ; classe entière dans gate canonique |
| `DiscoveryPostgresTest`, `SlotNavigationPostgresTest` | Algorithme inchangé mais nouveau find/guard ; couvrir admission/checkpoint atomiques et navigation/settlement, puis gates non filtrées ; preuves historiques restent un argument après comparaison |
| `CommandExecutionPostgresTest`, `L1IntegrationPostgresTest`, `PotHistoryPostgresTest`, `WriteRevisionPostgresTest` | Snapshot/CAS/historique/publication L1 inchangés en métier, lecteurs désormais gardés ; non-régression réelle nécessaire |
| `IdentityRequestExecutionPostgresTest`, `IdentityConcurrencyPostgresTest`, `IdentityPublicationPostgresTest`, `IdentityPotAuthorityPostgresTest` | Locks EUI, décision RC, corruption/source historique, UNKNOWN/rollback, redaction et acteur Pot ; réutilisation seule insuffisante |
| `V3PotCommandCompositionPostgresTest`, `V3IdentityRequestCompositionPostgresTest` | Assemblages réels et préflights des participants adaptés |
| `V3WorkerPostgresTest`, `V3WorkerResourcesPostgresTest`, `I5PhysicalWindowTest`, `I5TemporalV2Test`, `I5TemporalBudgetTest` | Scopes/capacité/renewal/drainage modifiés dans leurs hypothèses ; campagnes pertinentes puis gate non filtrée avec r05/r12 et interleaving réel |
| `V3IdentityRequestMonolithPostgresTest`, `V3WorkerMonolithPostgresTest` | Deuxième racine active, pools et drainage ; tests de racine obligatoires, pas seulement worker autonome |
| `V3ConsumptionEngineArchitectureTest`, autres règles V3 | Réviser les règles concernées et leurs contre-exemples ; accepter seulement engine/ports purs, aucun owner dans participants ou Worker |

Routage SHA-256, contraintes SQL, transitions métier et algorithmes d’admission non changés peuvent
réutiliser les preuves accessibles **après** comparaison ancienne→nouvelle des hypothèses, SQL,
configurations, tests et environnement. Les frontières préparation/recovery, leurs guards et leur
impact capacité ont changé : leurs preuves anciennes ne sont pas transférables comme PASS I2.
La collecte relie SHA testé, état dirty, empreintes exactes, commande, configuration, image PG,
identités JUnit, compteurs et traces physiques ; aucun rapport target antérieur n’est réattribué.
Les campagnes I1 37/29/1833/2096 demeurent I1 uniquement.

**r05/r12 :** conserver tous les FAIL, succès, diagnostics, réserves historiques et leur attribution.
Le registre actuel porte `status=QUALIFIED, active=false` et renvoie au
[contre-audit de résolution](L2_I5_Counter_Audit_and_Definitive_Closure_Report.md) ; il ne faut ni
réinventer une quarantaine active, ni effacer les raisons historiques qui y sont conservées.
La [clôture humaine L2](L2_Documentary_Closure.md) reste ACCEPTED WITH EXPLICIT RESERVATIONS ;
ce plan ne lève aucune réserve globale temporelle/disponibilité. Les passages I2 doivent réexécuter
les scénarios dont les hypothèses changent, conserver `i5_r05_discovery_skip_locked_interleaving`
et ne modifier ni délai, assertion d’availabilityRequired, ni quarantaine pour obtenir un PASS.
Une divergence entre documents historiques ne se règle pas par une certification nouvelle implicite.

## 12. Commandes futures, gates et conditions d’élargissement

Toutes les commandes Maven de cette section partent de `app/`. **Aucune n’a été exécutée par
la mission documentaire.** La politique impose de déclarer le niveau opérationnel et les
contributions filtrées ; un feedback vert ne remplace pas l’acceptation canonique non filtrée.

### 12.1 FAST — feedback après chaque groupe cohérent

```sh
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am test-compile
./mvnw -pl engine-consumption,engine-write-pot,engine-consume-registration -am -Dtest=PreparationReadsTest,ConsumptionFailureClassificationTest,ExecutePotCommandTest,ExecuteIdentityRequestTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Le premier appel contrôle les signatures à travers leurs consommateurs et les sources de tests ;
il n’exécute aucun test. Le second est un feedback unit borné, pas la slice COMMAND/PROJECTION.
Le flag `failIfNoSpecifiedTests=false` autorise uniquement les modules upstream sans ces tests ;
la contribution attendue doit être explicitement non vide et entièrement présente dans les modules
sélectionnés. Aucun `skipTests`, `maven.test.skip` ou exclusion cachée ne produit un PASS.

### 12.2 CHANGE / INTEGRATION — protocoles PostgreSQL ciblés

```sh
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am -Dtest=InterAuthorityPreparationPostgresTest,InterAuthorityRecoveryPostgresTest,SubobjectAtomicityPostgresTest,AttemptTransactionsPostgresTest,CommandExecutionPostgresTest,IdentityRequestExecutionPostgresTest,IdentityConcurrencyPostgresTest,IdentityPotAuthorityPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Cette sélection prouve les nouveaux contrats et les régressions directes ; elle ne couvre pas
à elle seule toute la matrice L2. Ajouter ensuite les contributions L2 affectées listées §11.3
pour les interleavings capacité/renewal/drainage avant acceptation. La sélection de méthodes peut
être réduite pendant le diagnostic, avec obligation manquante explicitement NOT RUN. Les gates
ci-dessous réexécutent les classes entières sans filtre. Les nouvelles classes/identités doivent
être intégrées à l’inventaire de collecte de la livraison ; aucune recette préexistante `identity`
ou `foundation` ne certifie automatiquement les nouveaux tests I2.

### 12.3 Acceptation canonique bornée I2

Base obligatoire demandée et conservée :

```sh
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```

La gate Architecture est **globale** ; elle n’est pas dans la boucle FAST. Aucun POM ne change,
mais la frontière préparatoire et l’observation L2 changent intentionnellement, ce qui satisfait
§17.2. Le POM Architecture dépend directement de `runtime-monolith` et de
`runtime-binding-consumption-worker` en plus des deux ancres de base. Avec `-am`, leurs modules
et tests entrent dans le reactor de cette gate. La preuve de racine MONOLITH est donc obligatoire
et peut être satisfaite **dans cette exécution** si le modèle effectif et l’inventaire frais montrent
les deux classes monolith et toutes leurs invocations attendues, sans skip. Ne pas relancer la
même preuve sans modification/failure/ambiguïté. Si elle n’est pas effectivement couverte :

```sh
./mvnw -pl runtime-monolith -am test
```

**BINDING : gate autonome NOT REQUIRED dans le périmètre retenu.** La racine BINDING importe
`BindingRuntimeConfiguration`, n’assemble pas `ConsumptionEngine` V3 et ne consomme pas ces
préparations ; les données d’autorité Identity utilisées par Command ne sont pas le runtime
BINDING historique. L’ancien chemin de l’owner sans garde de session conserve son comportement,
et le listener reste sans effet sur les transactions sans Unit. Le module BINDING est néanmoins
inclus par `-am` dans la gate Architecture ; ses résultats doivent être attribués comme tels.
Si le diff change le contrat binding, son matérialisateur, son runtime, la configuration commune
non V3 ou le comportement de ses transactions, déclarer BINDING secondaire **avant** de poursuivre :

```sh
./mvnw -pl runtime-binding-consumption-worker -am test
```

Ne pas ajouter EVENT/LKV/WEB uniquement parce qu’ils sont dépendants d’un module partagé. Les
ajouter si une modification de leurs comportements de production est démontrée ; le périmètre
actuel ne modifie que la classe V3 d’owner, les lecteurs V3 nommés et leurs assemblages. Si cet
impact ne peut plus être borné, analyser la cause puis appliquer §§15–20, sans élargissement muet.

### 12.4 Provenance, environnement et limites

Pas de campagnes concurrentes partageant targets/fixtures. Pas de `-amd` automatique, clean/install
systématique, ni exécution full reactor par précaution. Après suppression/déplacement de sources
ou suspicion de bytecode périmé, nettoyer **la clôture affectée** et refaire la preuve nécessaire ;
les traces du FAIL Architecture I1 restent attribuées à I1. Aucun autre worktree n’est autorisé
par la présente mission. Un besoin futur de checkout isolé nécessite un mandat adapté.

La qualification future utilise les contrôles de provenance existants et conserve commande exacte,
modèle effectif, SHA/dirty, empreintes, settings, JDK/Maven/PG, identité d’invocation, XML frais et
traces physiques. Inventaire attendu non vide, chaque identité exactement une fois, zéro skip,
compteurs cohérents ; refus/absence d’inventaire = INCONCLUSIVE, jamais PASS par total égal.
La simple présence d’anciens XML ou le succès Maven ne remplace pas ces contrôles.

Les fixtures nouvelles sont CURRENT V3 avec migrations pertinentes. Le passage transitif des
gates dans les tests historiques existants est déclaré comme tel, sans prétendre l’avoir évité.
Aucun rejeu V1–V23 n’est ajouté pour démontrer I2 et aucun trusted baseline inexistant n’est utilisé.
Le full reactor reste NOT REQUIRED / NOT RUN à la clôture bornée I2 ; il devient requis lors du
gate majeur L4/intégration main, d’un remaniement large, ou d’un changement partagé réellement
impossible à borner selon la politique. I2 ne délivre ni SPEC-10 global, ni disponibilité/SLO globale.

## 13. Critères d’acceptation mesurables et risques résiduels

Le développement pourra être déclaré terminé dans son périmètre seulement si :

1. Les signatures cibles sont présentes ; tous les callers/fakes/forwarders inventoriés compilent ;
   aucune ancienne signature participante ne contourne les scopes. Zéro nouveau POM/table/colonne/
   état durable/pool/moteur/orchestrateur et aucun participant métier I3–I7.
2. P01 prouve physiquement la succession source/effects/source/final et RR RO/RC ; les tentatives
   invalides P02–P07 ouvrent zéro scope supplémentaire et zéro finaliseur après invalidation.
3. Les anciens contextes et erreurs gardées interceptées empoisonnent la même session jusqu’au
   contrôle pré-commit, y compris callbacks tardifs JDBC/JPA ; les snapshots extérieurs attestent
   effets/slot intacts après rollback. Aucune régression du correctif I1.
4. W01 prouve un unique snapshot historique Command pour demande/acteur/Pot ; W02 prouve les
   décisions Identity sous verrous et leur lecture RC après attente, pas une simulation d’ordre.
5. R01–R09 couvrent toutes les cellules des deux tables, les deux statuts de slot, K absent/insuffisant/
   égal/supérieur, source pertinente/inéligible/absente/indisponible, effets absents/partiels/identiques/
   divergents/dépassés et pertes d’ACK. Les contre-oracles échouent comme attendu.
6. C01–C05 prouvent conservation des tickets des lectures inconnues et règlement distinct des
   finalisations/cessions ; renewals continuent sous saturation ; drainage ne ferme pas les pools
   avec responsabilité restante ; aucun compteur de budget altéré par simple observation.
7. Contributions PostgreSQL, propriétés L2 affectées, gates PROJECTION+COMMAND, Architecture et
   racine MONOLITH sont qualifiées et attribuées au code livré. Aucun skip masqué ni seuil affaibli.
8. Rapport de livraison : slices déclarées, commandes réellement exécutées, entrées/diff, franchissement
   inattendu ou NONE, gate globale et full reactor avec raison, résultats par contribution et réserves.
   Un futur FAIL requis bloque le PASS technique ; une décision humaine ne réécrit pas ce résultat.

Risques **réellement résiduels après ce cadrage**, à traiter dans la qualification/livraisons suivantes :

| Risque / limite | Traitement et propriétaire |
|---|---|
| Frontière entre garde pure et callbacks Spring | Tests PG tardifs JDBC/JPA et contre-oracles P03/W03 ; aucun PASS statique revendiqué ; développement I2 |
| `<T>` et captures Java ne constituent pas un système de types linéaires | DTO/ports fermés, revue structurale et tests de ressources ; chaque nouveau participant I3–I7 doit respecter §5.2 |
| Hypothèse de confiance checkpoint/admission et absence de suppression administrative étrangère | Privilèges/préflight existants, tests adverses d’incohérence et traces ; aucune preuve de causalité par effet préexistant seul |
| Une lecture UNKNOWN sans témoin de résolution retient durablement la capacité locale | Conservatisme L2 assumé, drainage incomplet et opération d’arrêt selon runbook ; aucun protocole de restitution inventé par I2 |
| Volume/mémoire des DTO et codecs | Réserve I1 conservée ; mesurer les volumes réels lors des matérialisations concernées ; aucune certification de capacité métier I2 |
| Invitation et participants métier encore absents | Famille refusée par composition ; livraison ultérieure du producteur/participant nécessaire ; pas dépendance bloquante de la fixture I2 |
| Réserves disponibilité/temps et portée de preuves anciennes | Attributions conservées, requalification ciblée ; ni clôture globale L2/L4 ni levée implicite r05/r12 |

Aucun risque restant ne laisse à inventer l’ownership d’un fait, une signature centrale, une
combinaison de verdicts ou une gate de base. Les résultats physiques restent **UNVERIFIED pour I2**
tant que son implémentation et ses campagnes n’ont pas eu lieu.

## 14. Résolution des findings et contrôle documentaire

| Finding obligatoire | Résolution dans ce plan | Preuve future exigée |
|---|---|---|
| AUD-I2-01 — ownership/cohérence recovery | §2.1 identifie chaque donnée/autorité ; §7 impose source immuable séparée et snapshot unique effets ; checkpoint interdit aux burnables, correction minimale sans migration | R02–R06/R09 et mêmes datasource/Unit/snapshot pour checkpoint/slot/effets |
| AUD-I2-02 — poison interautorités | §4.2–4.3 lie Units successifs et finalisation à la même Guard monotone ; contexte expiré conserve ce lien ; enveloppes couvrent SQL/décodage | P02–P05/W03 et rollback physique après faute interceptée |
| AUD-I2-03 — contrats Java | §4 donne signatures, types imbriqués, exceptions, owners, constructeur et adaptation des callers ; pas de type RecoveryPreparation indéfini | Compilation entière et tests API/architecture |
| AUD-I2-04 — capacité/résolution | §8 suit executeAttempt→executeOperation→recovery→settlePending avec flags existants ; §7.5 sépare trois notions | P07/C01–C05, UNKNOWN préparatoire sans verrou et ACK cession séparé |
| AUD-I2-05 — matrice recovery | §7.2–7.4 distingue READ/WRITE, présence/absence, couverture, préexistence, monotonie, suppression, divergence/absence et indisponibilité | R01–R09 et contre-oracle à deux snapshots |
| AUD-I2-06 — détachement opérationnel | §5.2 inventorie valeurs/captures/fermeture et limite explicite du générique ; ports gardés, absence de source en finalisation | P08/W01/P01, pas inspection superficielle du résultat seul |
| AUD-I2-07 — fichiers/gates | §10 fournit chemins/action exacts, y compris fakes/décorateurs/racines ; §11–12 fixent tests et gates, BINDING conditionnel et MONOLITH obligatoire couvert par Architecture si inventaire le prouve | Inventaire effectif, résultats attribués, rapport de franchissement |

Contrôles documentaires de cette mission : lecture intégrale finale du document, comparaison des
signatures existantes/cibles et des chemins contre les sources, inventaire des implémenteurs et
constructeurs, préconditions/ancestry I1, dépendances POM et gates Reactor, liens locaux/ancres,
absence de modification hors document, `git diff --check`, revue complète du diff et contrôle
Git avant/après publication. Aucun Maven, PostgreSQL, collecteur ou script de qualification lancé.
Les commandes documentaires sont lectures `cat`/`sed`/`rg`, Python `-B` pour chemins/liens/inventaires,
`git status`, `branch`, `rev-parse`, `log`, `show`, `merge-base`, `diff`, `worktree list`, `remote -v`,
`fetch`, `merge --ff-only`, puis commit de ce seul document, push sans force, `ls-remote` et
comparaison local/distant. Le rapport final donne les SHA et les résultats **effectifs**, sans SHA
autoréférent inventé dans le document.

**Verdict de préparation : READY FOR I2 IMPLEMENTATION.** Les contrats et décisions techniques
ordinaires sont arrêtés, l’inventaire et les gates sont explicites, les preuves à produire sont
falsifiables et les réserves restantes ne masquent pas une architecture indéfinie. Ce verdict
qualifie le caractère directement implémentable du plan ; il ne constitue **ni GO humain Java,
ni PASS d’implémentation/qualification I2**, qui relèvent d’une décision et d’une mission distinctes.
