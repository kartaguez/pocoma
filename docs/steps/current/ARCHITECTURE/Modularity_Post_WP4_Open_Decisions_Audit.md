# Checkpoint post-WP4 — audit et décisions proposées Command / E→U

**Statut : PROPOSITION À AUDITER, non implémentée.** `TBD-COMMAND-CONTRACT` et `TBD-E2U` restent **OPEN** jusqu'à implémentation et vérification. Ce document confronte le code au HEAD aux audits [CURRENT](Modularity_Current_State_Audit.md), [TARGET](Modularity_Target_Topology.md), [plan](Modularity_Migration_Plan.md), [WP3](Modularity_WP3_Execution_Report.md) et [WP4](Modularity_WP4_Execution_Report.md). Les chemins historiques dans `docs/architecture/Architecture.md`, `type-ownership.md` et `read-side-target.md` ne prouvent pas l'emplacement actuel des classes : les sources sous `app/*/src/main` prévalent.

## 1. Baseline et méthode

`git fetch origin` a réussi après une première tentative refusée par le sandbox pour `.git/FETCH_HEAD`. Avant cet audit, `HEAD` local et `origin/v2-make-it-pull` étaient tous deux `b85ced4944ecb08a33744eaa00b6478f80cb3729`, divergence distante/locale `0/0`, working tree propre. Aucun changement pertinent non analysé. Recherche de tous les usages Java de production, signatures et adapters ; inspection des POM, des transactions, du SQL embarqué et des tests existants. Documents relus : politique normative de vérification, les cinq documents ci-dessus, `docs/architecture/{Architecture,recorded-command-intake,recorded-command-persistence,command-consumption-runtime,authorization-kernel-contracts,read-side-target,type-ownership}.md`, `docs/steps/completed/REGISTRATION/Step_Canon.md`, les documents Binding/Registration de cette étape et `docs/debts/README.md`. Les documents historiques sont utilisés comme intentions ou garanties à challenger.

**Vérification déclarée pour ce passage :** impact production interdit ; slices WEB/COMMAND/EVENT/PROJECTION/BINDING/LKV et Results : **aucune exécutée**, car seul un rapport est créé. Gate global d'architecture **non requis** pour une modification documentaire ; full reactor **non requis**. Preuve : inventaires `rg`, lecture des sources/POM, analyse statique d'un graphe hypothétique. Aucun SQL, schéma ou migration exécuté.

## 2. Command — inventaire CURRENT

La matrice couvre les six types dont les usages dépassent l'exécution. `P` signifie stockage ou adapter, `H` HTTP, `A` admission, `X` exécution, `R` Direct Result, `S` supra ; `—` signifie aucun usage de production trouvé. Les producteurs/consommateurs sont des rôles, pas seulement des imports Maven. Source des types : `app/engine-consume-command/src/main/java/.../model/` ; usages vérifiés dans `engine-admit-command`, les deux moteurs Result, `infra-persistence-jpa`, les supras et runtimes.

| Type | Owner CURRENT | Producteurs | Consommateurs | P | H | A | X | R | S |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `CommandId` | `engine-consume-command` | générateur Web/admission | record durable, discovery/execute/outcome, Result store/GET, HTTP GET | clé `recorded_commands`, outcome, `command_results` | GET path, réponse 202 via `SubmittedCommand` | oui | oui | oui | GET et locator Command |
| `CommandOutcome` | `engine-consume-command` | exécution/fence recovery | publication/reload PRIMARY, materializer, **read Result** | `command_outcomes`, Result row via adapter | indirect via traduction GET | — | oui | materialize **et read** | — |
| `RecordedCommand` | `engine-consume-command` | admission | insert/reload, consommation | `recorded_commands` + row JDBC distincte | indirect via POST | oui | oui | source ne relit que ID/E historique | — |
| `CommandType` | `engine-consume-command` | HTTP WRITE | admission, record, decoder | colonne type | POST adapter | oui | oui | — | HTTP WRITE |
| `CommandAuthenticationEvidence` | `engine-consume-command` | factory admission | envelope, contrôle expiration/exécution | colonnes evidence | indirect | oui | oui | — | — |
| `TargetCommandEnvelope` | `engine-consume-command` | admission | record, contrôle E+B | colonnes E/B/evidence | indirect | oui | oui | E historique relu séparément | — |

Les autres types ont des rôles plus étroits : `Command` décodée, `CommandExecutionInput`, dispatch et outcome publication/query sont propres à l'exécution ; `SubmitRecordedCommandInput`, `SubmittedCommand`, `CommandIdGenerator`, evidence factory sont propres à l'admission ; `RecordedCommandRow` est le DTO SQL de PRIMARY ; `CommandResultSource` est la relecture contrôlée du materializer ; `ImmutableCommandResult`, `CommandResultStore`, `GetCommandResult` sont des contrats du Direct Result ; les réponses JSON sont des DTO HTTP. Les candidates/cursors de découverte appartiennent aux supras/engines spécialisés. Aucun de ces derniers ne doit entrer dans un module Command partagé du seul fait qu'il porte « Command » dans son nom.

### Sémantique et cycle de vie

`CommandId` est l'identité durable de **la Command admise puis enregistrée**, conservée pendant consommation, outcome et GET Result ; ce n'est pas un ID créé par l'exécution. `SubmitRecordedCommandService` l'obtient de `CommandIdGenerator` dans la transaction d'insertion ; le bean Web génère un UUID. Il existe donc avant toute consommation et reste nécessaire ensuite comme corrélation stable. Command Result doit le connaître comme clé, sans connaître le moteur consume. Owner naturel : contrat d'identité Command partagé.

`CommandOutcome` est l'issue terminale **de l'exécution** (`Applied/Rejected/Failed`, temps, version/code), persistée par `JdbcCommandOutcomeAdapter`. `MaterializeCommandResultService` compare outcome, Event terminal et ID enregistré avant insertion `0..1` ; sa dépendance à l'issue d'exécution est sémantique. En revanche, `ImmutableCommandResult` garde actuellement un `CommandOutcome` et `GetCommandResultService` le traduit au GET : l'arc READ Result → consume est accidentel. Le **Result publié** doit devenir un modèle immuable distinct, owner `engine-read-command-result`, avec ID, E propriétaire historique, issue publique et temps ; le materializer effectue l'unique conversion après contrôle des sources. Ne pas confondre l'issue d'exécution et l'objet publié, même si leurs variantes se ressemblent aujourd'hui.

`RecordedCommand` contient type sérialisé, payload, instant et envelope E+B/evidence : c'est un **contrat durable de transfert admission → stockage → consommation**, pas un `Command` métier décodée, ni `RecordedCommandRow` SQL. L'interface actuelle `RecordedCommandPort` mélange `insert` (admission) et `findById` (consommation), ce qui crée un arc admission → consume au delà des valeurs partagées. La représentation stable de l'envelope peut être commune ; les deux ports doivent appartenir chacun à leur besoin. L'auth evidence et `TargetCommandEnvelope` suivent cette envelope ; `CommandType` est l'identifiant versionné du payload, partagé avant/après l'insertion. Le code peut conserver un record unique si son contrat est réellement stable, tout en gardant la ligne SQL interne à l'adapter.

### Options réellement possibles

| Option | Sens, graphe et suppression/création | Cohésion, stabilité, coût |
| --- | --- | --- |
| A. `contracts-command` **minimal** | Admit/consume/HTTP/Result → contrat d'ID ; admission et consommation → envelope durable ; materialize → consume pour outcome ; read Result → contrat d'ID et son propre modèle. Retire `admit → consume` et `read Result → consume`. | Cohésif si limité aux identités/intake durables ; nouveau POM et migration d'imports/ports ; risque de god module contrôlé par une allowlist de types. |
| B. Domaine existant (`domain-user-identity` ou `domain-pot`) | Aucun nouveau POM ; tous importent un domaine existant. | Mauvais langage : CommandId et payload ne sont ni identité User/Binding ni Pot ; fan-in trompeur, coût initial faible mais couplage durable. |
| C. Owners séparés sans contrat neuf | ID dans admit, RecordedCommand dans consume, Outcome dans consume ou materialize ; impose `consume/read → admit` ou maintient `admit → consume` ; copies d'ID possibles. | Graphe possiblement acyclique mais direction ou duplication injustifiée. Distinction outcome/Result reste indispensable. |
| D. Garder les modèles consume, extraire seulement ports étroits | `admit → consume` persiste pour le type ; `read Result → consume` supprimable par modèle publié et ID local/UUID. | Plus petit diff, mais la provenance de l'identité partagée reste fausse ; une conversion répétée ou des UUID nus effacent le langage Command. |
| E. Contrats séparés par phase | `contracts-command-id` + `contracts-recorded-command` + Result propre ; liens additionnels entre contrats. | Physiquement possible, mais deux POM minuscules couplés par le même cycle de vie durable sans protection de frontière démontrée ; coût disproportionné. |

### Décision TARGET proposée — TBD-COMMAND-CONTRACT

**RECOMMENDATION :** option A limitée au passage durable, combinée à des ports séparés et un Result publié autonome. **RATIONALE :** le même ID et la même envelope traversent légitimement l'admission, la persistance et l'exécution ; l'outcome reste issu de l'exécution ; la lecture du Result n'a aucune raison d'importer un moteur WRITE. Le nombre d'arcs n'est pas le critère.

**TARGET OWNERS :** `CommandId`, `CommandType`, `RecordedCommand`, `TargetCommandEnvelope`, `CommandAuthenticationEvidence` → **nouveau `contracts-command`**, contrat d'intake durable uniquement ; `CommandOutcome` → `engine-consume-command` ; `Command` décodée, dispatch, fence et query/publication d'outcome → `engine-consume-command` ; `SubmitRecordedCommandInput/SubmittedCommand`, générateur et **port d'insertion** → `engine-admit-command` ; **port de relecture** → `engine-consume-command` ; modèle publié, store et GET → `engine-read-command-result` ; conversion/validation des sources → `engine-materialize-command-result` ; rows/SQL → PRIMARY ; DTO JSON → supras HTTP. Le modèle publié ne stocke pas `CommandOutcome`. Toute extension de `contracts-command` exige de démontrer un consommateur pré-consommation **et** post-consommation ou une identité transverse ; aucune policy, persistence technique, HTTP ou Result n'y entre.

## 3. E→U — inventaire CURRENT et cohérence

`ExternalIdentityResolverPort.findUserId(E)` a exactement un appelant de production, `ReadPotForExternalIdentityService` (`engine-read-pot`) et un bean Web de composition ; `JpaExternalIdentityResolverAdapter` le réalise par `SELECT user_id FROM external_identities WHERE issuer,subject` dans une transaction `MANDATORY`/read-only. Les recherches équivalentes sont distinctes :

| Chemin | E→U ou information liée | Besoin | Source/port CURRENT |
| --- | --- | --- | --- |
| HTTP GET Pot | E authentifiée → U pour AUTH@V | **user-facing READ avec contrôle d'identité courante autoritatif** | engine-read-pot → `ExternalIdentityResolverPort` → PRIMARY ; Web compose |
| HTTP POST Command | E attestée + B fourni, sans résoudre U | admission | `SubmitRecordedCommandService`, record durable |
| Command consumption | (E,B) → U, observation/fence avant effet | **execution fencing** | `ExternalIdentityBindingPort`, PRIMARY, transaction d'effet |
| Registration | E → nouvel U+B, conflit arbitré | **authoritative current identity / mutation** | `ExternalIdentityBindingPort.acquireWithInitializer`, PRIMARY |
| Binding attach/detach | E, U, B, R → facts | **autorité du cycle de vie** | PRIMARY authority/stream/facts |
| GET `/me/binding` | E → état ATTACHED U+B+R | **projected current identity** | `CurrentBindingReadPort`, READ JDBC ; absence/DETACHED → 404 |
| Command/Registration Result | E historique propriétaire, comparaison avec E authentifiée | **historical identity** | Result immuable PRIMARY ; aucune résolution E→U |
| Tests | scénarios attach/detach/rebind, monotonie, échecs et HTTP | preuves ciblées | `BindingRuntimePostgresTest`, `CurrentBindingPersistencePostgresTest`, Web Pot/Result tests |

Les deux ports Binding authority (`findUserId(E,B)` et `observeCurrentBinding(E,B)`) ont un B exact : ils ne sont pas des substituts neutres du resolver de GET. `PocomaUserId` et `domain-pot.UserId` se convertissent aujourd'hui dans `ReadPotForExternalIdentityService` ; cette conversion reste à la frontière du moteur Pot.

### CURRENT_BINDING : données suffisantes, cohérence insuffisante

Clé `(issuer,subject)` ; payload `binding_revision`, `ATTACHED/DETACHED`, U+B seulement pour ATTACHED, `source_event_id`, `projected_at`. Le fait immutable `ExternalIdentityAttached/Detached` fournit E,B,R ; `MaterializeCurrentBindingService` construit la valeur. `JdbcCurrentBindingAdapter` applique seulement une révision supérieure, reconnaît duplicate exact au même R, rejette une divergence au même R, et garde DETACHED comme tombstone. Le discovery est trié `(issuer,subject,R)` ; les scans sont courts et les cursors locaux. **Écart docs/code à relever :** l'adapter accepte tout R supérieur, sans vérifier `R courant + 1` ; le tri de découverte et les tests de reconstruction ordonnée ne prouvent donc pas la continuité de chaque application en cas d'échec/retry d'un fact antérieur. Cela ne change pas la convergence vers le maximum une fois les facts traités, mais doit être vérifié séparément si la continuité est exigée. Le store **n'expose pas un certificat de fraîcheur vis à vis de la révision PRIMARY**. Les facts sont committés avec l'autorité PRIMARY ; le worker les découvre, claim, applique dans READ puis finalise son slot dans une autre voie de consommation. Aucun commit atomique PRIMARY/READ n'est établi. Le bootstrap/migration permet une révision 0 et des facts de lifecycle ultérieurs ; sans consumer à jour, une ligne peut manquer ou rester à R ancien. `GET /me/binding` accepte déjà cette convergence.

**Information : OUI**, un ATTACHED courant contient exactement U (plus B et R). **Contrat de cohérence pour GET Pot : NON**, tant qu'aucune borne/fence ne prouve qu'il reflète la dernière révision PRIMARY.

Scénario admission/Binding committé puis worker en retard : PRIMARY peut trouver U immédiatement, CURRENT_BINDING peut répondre absent/DETACHED/ancien U. Une absence temporaire serait un *stale denial* (`404`) et pourrait être acceptable pour une vue self-service convergente. Après `E→U1`, detach puis `E→U2`, la même fenêtre est plus grave : PRIMARY retourne absence puis U2 selon le commit ; CURRENT_BINDING peut rester ATTACHED U1 jusqu'au fact detach, puis DETACHED jusqu'au fact attach U2. `GET Pot` avec U1 stale et un token E valide peut passer `AUTH@V` si U1 est créateur/actionnaire à V, puis lire `READ_POT@V`. C'est un *stale authorization*, donc une divulgation après révocation ; le risque est plus fort qu'un 404 temporaire. Le fait que les projections Pot soient asynchrones ne rend pas acceptable une identité courante stale : leurs versions portent l'état **historique du Pot**, pas l'état courant du Binding E.

`AUTH@V` contrôle les relations U↔Pot à V et les capacités traduites du token. Il ne contient ni E, ni BindingId, ni révision de Binding ; ses causalités/versionnements sont ceux du Pot, indépendants des facts Binding. Deux projections convergentes ne se compensent donc pas. Le resolver PRIMARY actuel lit E→U dans une courte transaction, puis AUTH/READ dans une autre séquence : si detach gagne **avant** le SELECT à isolation READ COMMITTED, le lookup refuse ; si detach commit **après** le SELECT, une lecture déjà engagée peut finir après la révocation. Le contrat constaté est un contrôle ponctuel au moment de la résolution, pas une garantie linéarisable jusqu'à l'envoi HTTP. Une exigence plus forte demanderait un fence/lock maintenu jusqu'au contrôle, à décider explicitement avec son coût ; elle n'est pas prouvée par le modèle actuel.

### Options E2U

| Option | Autorité, lag/sécurité, graphe, coût et philosophie READ |
| --- | --- |
| A. PRIMARY via port actuel | Autorité immédiate au SELECT, pas de lag worker ; `engine-read-pot → domain-user-identity` et `infra-persistence-primary-jpa → domain-user-identity`. Aucun cycle ; port trop générique et intention de sécurité implicite. Exception explicite à la cible READ pure. Coût nul hors documentation. |
| B. CURRENT_BINDING via READ | `engine-read-pot → engine-read-current-binding` ou un port local, adapter `infra-persistence-read-jdbc` ; découple PRIMARY et respecte la cible READ physique ; lag non borné et stale authorization U1, donc **rejetée** pour GET Pot actuel. Admissible pour `/me/binding`, avec sa sémantique convergente. |
| C. Port sémantique dans `engine-read-pot`, adapter PRIMARY | Exprime `resolveCurrentUserForAuthorization(E)` avec promesse de lecture autoritative au point de résolution ; PRIMARY implémente, READ adapter seulement après preuve de fraîcheur/fence équivalente. Ajoute `infra-persistence-primary-jpa → engine-read-pot` (déjà direction adapter → engine), retire la dépendance de ce moteur au port générique si plus utilisé ailleurs. Petit coût ; exception ciblée et visible à la règle READ. |
| D. CURRENT_BINDING + fence PRIMARY/révision | Deux lectures ou transaction/fence, source physique mixte ; peut rendre la projection sûre si la révision READ est comparée à la dernière révision autoritative et si le résultat est revalidé selon le contrat temporel. Ajoute ports/transactions et risque de race ; pas de gain démontré pour le besoin actuel. À ouvrir seulement si un objectif mesuré impose l'usage READ ou une révocation plus forte. |
| E. Snapshot historique E→U à V | Proche d'une lecture historique, mais GET Pot autorise **l'actuel porteur E** ; garder l'ancien U après detach aurait le même défaut de révocation. Inadapté. |

### Décision TARGET proposée — TBD-E2U

**RECOMMENDATION :** option C, avec PRIMARY comme adapter physique. **SEMANTIC PORT :** port étroit possédé par `engine-read-pot`, nommé par exemple `CurrentPotReaderIdentityPort`, `resolveCurrentUser(E)`, contrat explicite « binding courant autoritatif au point de résolution ou absence » ; résultat minimal U (éventuellement B/R uniquement si un besoin de fence est décidé), aucune API de mutation. **AUTHORITATIVE/PROJECTED SOURCE :** `external_identities` PRIMARY pour GET Pot ; CURRENT_BINDING reste la vue convergente de `/me/binding`. **CONSISTENCY MODEL :** contrôle ponctuel PRIMARY, absence → 404 opaque, pas de promesse de transaction couvrant toute la réponse ; la décision de produit sur les lectures déjà engagées lors d'un detach reste une question ouverte explicite. **SECURITY CONSEQUENCES :** évite le faux positif non borné dû au lag de CURRENT_BINDING ; AUTH@V reste nécessaire mais insuffisant seul. Le test doit couvrir detach/rebind avant résolution et les fenêtres concurrentes documentées.

Ce besoin consulte *une donnée détenue par l'autorité Binding*, mais n'est pas un appel du protocole d'**écriture/fencing** `port-binding-authority`. Le placer dans ce port importerait au moteur READ des méthodes `acquire/detach/fence`, confondrait le contrôle d'accès GET avec la consommation Command et contredirait silencieusement `READ !→ Binding authority`. La dépendance sémantique est un **port de lecture de sécurité local** ; l'adapter lit physiquement PRIMARY. Cela constitue une exception ciblée à `read-side-target.md` (« HTTP READ exclusivement READ »), déjà présente dans CURRENT derrière le port générique : l'implémentation devra l'inscrire explicitement dans TARGET/guards, et non prétendre que `AUTH@V` ou CURRENT_BINDING la supprime. Le controller et supra HTTP restent indépendants de PRIMARY concret.

## 4. Graphe résultant et vérification statique

Arcs proposés (flèche = dépendance Maven de production) :

```text
engine-admit-command ──→ contracts-command ←── engine-consume-command
        │                         ↑                    │
        └→ port-transaction       │                    └→ port-binding-authority (exécution seulement)
engine-read-command-result ──────┘
engine-materialize-command-result ──→ engine-consume-command (outcome)
engine-materialize-command-result ──→ engine-read-command-result (publication)
infra-persistence-primary-jpa [CURRENT infra-persistence-jpa] ──→ engines/ports qu'il adapte
engine-read-pot ──→ domain-user-identity + engine-read-projection
infra-persistence-primary-jpa ──→ engine-read-pot (port E→U)
runtime-web-api ──→ engine-read-pot + supra-http-read + PRIMARY (composition)
```

**Retraits :** `engine-admit-command → engine-consume-command` après séparation insert/reload et valeurs ; `engine-read-command-result → engine-consume-command` après modèle publié distinct. **Conservation justifiée :** `engine-materialize-command-result → engine-consume-command` pour interpréter l'issue réellement exécutée. **Créations :** dépendances des consommateurs de l'ID/envelope vers `contracts-command`, adapter PRIMARY → port dans `engine-read-pot`. Aucun engine → runtime, runtime → runtime, TARGET → legacy, ni moteur READ → `port-binding-authority`. Le seul READ → information d'autorité est le port E→U local et explicite. Une analyse XML des POM de production au HEAD donne 72 modules, 306 arcs, aucun cycle ; une simulation ajoutant le contrat et les arcs décrits donne 73 modules, 315 arcs, aucun cycle. Ces nombres sont ceux du **reactor CURRENT avec legacy**, pas une révision automatique des 54 POM TARGET / 147 arcs normatifs : TARGET devrait être révisé et revérifié au checkpoint d'implémentation avant de devenir canonique.

## 5. TBD, dette et décisions encore ouvertes

Recherche `TBD`, `TODO architecture`, `BLOCKED_BY_TBD`, `UNRESOLVED`, `provisional`, `temporary bridge` dans `docs/steps/current/ARCHITECTURE` et `app` : trois **décisions architecturales canoniques** ressortent, sans nouveau TBD justifié par un TODO technique.

| Décision | Registre de dette ? | Suivi/point de résolution | Risque d'oubli |
| --- | --- | --- | --- |
| `TBD-COMMAND-CONTRACT` | Mentionnée séparément comme décision ouverte dans `docs/debts/README.md`, sans criticité | plan POST-WP4 ; ce rapport propose A ; vérification POST-WP4.A avant WP5 | faible si la recommandation reste OPEN jusqu'aux gates ; élevé si WP5 supprime les shells sans contrôle |
| `TBD-E2U` | Pas une dette cotée ; bridge B25 suivi dans plan/WP4 | D.TBD-E2U ; ce rapport propose l'option C ; POST-WP4.B avant WP5 | élevé sans lien depuis le registre des décisions : exception READ/PRIMARY et risque sécurité pourraient être oubliés |
| `TBD-LKV` | Hébergement provisoire B15 documenté, non coté comme dette indépendante | D.TBD-LKV, après décision de l'owner fonctionnel et du consommateur aval ; piste WP6 distincte | moyen : trois responsabilités `TBD_PHYSICAL` hors des 54 POM fermes |

La taxonomie « dette technique » et « décision architecturale ouverte » est utile : ne pas inventer de criticité pour les trois TBD. Le plan et ce rapport forment aujourd'hui un suivi explicite ; lors de l'implémentation, ajouter une petite table de décisions ouvertes au registre sans requalifier artificiellement ces choix en dettes. Les mentions `provisional`/`temporary bridge` renvoient principalement aux bridges B15/B25/BC-04, aux shells WP5 et aux anciens snapshots WP1–WP3 ; elles ne constituent pas de décisions supplémentaires. `TBD-LKV` garde son moteur/owner fonctionnel, supra, runtime et store READ provisoires ; Command et E2U ne déterminent ni sa destination ni sa frontière Maven.

## 6. Plan minimal de mise en œuvre après audit de ce rapport

| Checkpoint | Changements et preuve ciblée | Sortie |
| --- | --- | --- |
| **POST-WP4.A.1** Command intake/identité | Créer `contracts-command` restreint, déplacer ID/type/envelope/evidence/record ; séparer port insert dans admit et port reload dans consume ; adapter PRIMARY implémente les deux ; retirer `admit → consume`. Mettre à jour POM/imports, gardes d'allowlist et docs. Déclarer slices **WEB + COMMAND**, commande canonique composée `./mvnw -pl runtime-web-api,runtime-command-consumption-worker -am test`. | Admission 202/record identique, consommation et E+B inchangés, aucun cycle. |
| **POST-WP4.A.2** Result publié | Créer le modèle Result immuable dans read Result, convertir `CommandOutcome` au materializer, adapter store/GET/HTTP ; retirer `read Result → consume`. Slices **COMMAND + WEB + Command Result worker** : `./mvnw -pl runtime-command-consumption-worker,runtime-command-result-consumption-worker,runtime-web-api -am test`. Tester 0..1/replay, owner E historique, 404 opaque, mismatch source/Event/outcome. Gate `./mvnw -pl architecture-tests -am test` requis pour nouveau POM/arcs ; full reactor à décider **avant** implémentation selon ampleur réelle, pas automatique pour un échec local. | Aucune référence `CommandOutcome` dans read Result ; contrat partagé borné ; TBD clos seulement après preuves. |
| **POST-WP4.B.1** E→U sémantique | Ajouter port local à `engine-read-pot`, adapter PRIMARY et wiring Web ; retirer le port générique devenu sans appelant après recherche, ou garder une compatibilité datée si un client existe. POM adapter → engine ; aucun READ JDBC E→U pour GET Pot. Slice **WEB**, `./mvnw -pl runtime-web-api -am test` ; tests PRIMARY detach/rebind, absence, token valide, AUTH@V de U1, et course de résolution ; garde `engine-read-pot !→ port-binding-authority` et contrat de source. `./mvnw -pl architecture-tests -am test` requis car frontière intentionnelle modifiée. | GET Pot n'utilise pas CURRENT_BINDING ; exception TARGET READ/PRIMARY documentée ; aucune autorisation stale après detach committé **avant** la résolution. |

Pour A et B, déclarer à nouveau impact, slices secondaires, interdits, gate et besoin de full reactor **avant** toute modification `app/`, conformément à `Reactor_Verification_Policy.md`. Une traversée de slice inattendue impose une nouvelle déclaration. Aucun test historique de migrations n'est requis par défaut avec un baseline DB trusted ; aucun changement SQL/schema n'est prévu. Le full reactor devient requis seulement si la refonte effective devient une restructuration large ou un gate d'intégration WP5/WP6 l'exige.

## 7. Effet attendu sur WP5 et questions ouvertes

WP5 pourra retirer les derniers `engine-command`/`engine-registration` legacy et locators/facades sans transporter BC-04 ; rehome PRIMARY (`infra-persistence-jpa` → `infra-persistence-primary-jpa`) avec l'adapter E→U explicitement possédé ; simplifier le wiring runtime Web et Command, et retirer B25 **comme bridge non décidé** après remplacement par un contrat stable. Les bridges B15/LKV restent hors de ce travail. Le modèle Result publié et les ports d'intake empêchent de cacher une dépendance legacy sous le renommage infra.

**Questions réellement ouvertes pour revue avant implémentation :** (1) le produit exige-t-il une révocation linéarisable jusqu'à la fin de la réponse GET, au delà du contrôle ponctuel PRIMARY actuel ? Si oui, POST-WP4.B doit inclure un fence/transaction et re-déclarer ses slices ; (2) `contracts-command` doit-il exposer le record durable complet ou deux représentations de phase ? Le code actuel et le port unique favorisent un record partagé, à confirmer contre les futurs changements de sérialisation ; (3) quel gate full reactor s'appliquera à POST-WP4.A une fois son impact Maven concret connu ? Ces questions ne changent pas la conclusion de sécurité : **CURRENT_BINDING seul n'est pas une source d'autorisation sûre pour GET Pot**.
