# REGISTRATION — Plan d'implémentation rebaseliné

```text
Step: REGISTRATION
Phase: IMPLEMENTATION PLANNED
Baseline: v2-make-it-pull @ 6ccb69b1103878ebe010a406b940d28fac2226ab
Authorities: Step_Canon.md (D1–D33), WRITE_ADMISSION/Step_Canon.md (WA1–WA11)
Sequence: REG.1 → REG.2 → REG.3 → REG.4 → REG.5
Verdict: READY FOR IMPLEMENTATION
Blocking questions: 0
```

## 1. Fondation livrée et cible

WRITE_ADMISSION est clos. Le code fournit `ExternalIdentity`, `PocomaUserId`, `BindingId`,
`User`, `UserAuthorityPort`, `ExternalIdentityBindingPort`, le stream de révision, les
occurrences et faits de binding, le worker Consumption Binding, `CURRENT_BINDING` en READ,
l'AuthN JWT et le principal externe attesté. Registration les réutilise : aucun deuxième
modèle User/Identity, binding, resolver ou store courant.

Le chemin `CURRENT_BINDING` **actuel** consomme directement `external_identity_binding_facts`
et applique la vue READ sous claim Consumption. Ce n'est pas encore un `ProjectionTask` standard.
La standardisation conditionnelle étudiée dans
[la révision ARCHITECTURE](../ARCHITECTURE/Three_Engine_Families_Revision.md) n'est pas un
prérequis à Registration et n'est pas déclenchée par ce plan.

```text
POST Registration: JWT → AuthenticatedExternalPrincipal → E → R(E) durable → 202
                   aucune résolution E→U/B, aucun User, aucun binding
worker Registration: R(E) → Registered(U,B) + User(U) + Binding(E,U,B)
                             + UserCreated(U) + ExternalIdentityAttached(E,U,B)
                         ou Rejected(EXTERNAL_IDENTITY_ALREADY_USED)
résultat projeté READ + CURRENT_BINDING READ → GET Registration
self identity READ → CreatePot(E,B) → Command worker → COMMAND_RESULT READ → Pot READ
```

La cohérence READ est éventuelle. Aucun GET ne lit le primaire WRITE ou les tables Consumption.
Absence, non-terminalité, retard de projection et non-ownership produisent le même `404` opaque.

## 2. Ownership physique retenu

Les noms ci-dessous désignent les **nouveaux** modules Registration. Les renommages du step
ARCHITECTURE ne sont pas supposés déjà réalisés. Si ce refactoring précède un lot, utiliser le
nom final sans couche transitoire.

| Module | Responsabilité et ports | Dépendances et raison d'exister |
|---|---|---|
| `engine-write-registration` | Request, outcome, ports request/outcome/UserCreated, admission et transition. | Domaine User/Identity et contrats transactionnels neutres ; use case WRITE distinct de Command et de Consumption. |
| `engine-consumption-registration` | Discovery, reload de R, callback et classification. | WRITE Registration et protocole Consumption existant ; aucune logique de claim, retry ou polling dupliquée. |
| `runtime-registration-consumption-worker` | Composition Spring, propriétés, polling, lifecycle et métriques. | Modules Registration, worker générique et infra ; processus déployable autonome. |
| `projection-registration-result` | Loader de l'outcome autoritatif et producteur `REGISTRATION_RESULT` pour ProjectionTask. | Contrats de projection et port d'outcome ; ni claim ni GET. |
| `engine-read-registration` | Port du résultat projeté, visibilité avec `CURRENT_BINDING`, use case GET. | Contrats READ neutres et User/Identity ; aucune dépendance WRITE/Consumption/infra primaire. |
| `supra-http-registration` | Contrôleurs POST/GET et DTO HTTP. | Use cases WRITE/READ et principal AuthN ; séparé de l'HTTP Command. |

`runtime-web-api` câble HTTP, use cases et adapters. `infra-persistence-jpa` implémente les
ports WRITE et migrations ; `infra-read-persistence` implémente le port de résultat READ.
Les orchestrateurs Consumption existants restent génériques : **pas de
`orchestrator-registration`**. Les DTO du résultat utilisés par READ sont des contrats
neutres de projection, jamais importés depuis l'engine WRITE.

## 3. Décisions techniques fermées

### 3.1 Admission

`RegistrationRequestId(UUID)` et `capturedAt` sont générés côté serveur. La request
immutable contient `requestId`, `creatorExternalIdentity`, `capturedAt` ; ni JWT,
UserId, BindingId, état, claim, lease ou retry. Le POST dérive E exclusivement de
`AuthenticatedExternalPrincipal.identity()`. Il ne consulte ni
`ExternalIdentityResolverPort`, ni `ExternalIdentityBindingPort`, ni
`UserAuthorityPort`, ni la disponibilité de E.

Modifier `WebApiSecurityConfiguration` pour installer la chaîne JWT quand Registration est
activée et rendre `/api/v1/registrations` et `/api/v1/registrations/*` authentifiés.
Sans JWT valide : `401`, aucune request. E connue ou inconnue : `202`, une request seule.
Aucun scope ou capability métier Registration supplémentaire.

### 3.2 Acquisition atomique, outcome et faits

Étendre **l'autorité existante** `ExternalIdentityBindingPort` avec une opération
`acquireForNewUser(E, User)`, spécifique à la création initiale d'un User, sans créer une
deuxième autorité. L'adapter réutilise lock du stream `(issuer,subject)`, vérification du
binding actif, réservation de B, mise à jour de l'autorité, avance de révision et append
de `ExternalIdentityAttached`. Après le lock et le test de conflit, il insère U
**uniquement sur le chemin gagnant**, avant les écritures référencées par FK. Sur conflit,
il retourne `CONFLICT` sans insérer U ni fait. `acquire(E,U)` reste pour l'attach d'un
User préexistant ; factoriser la séquence commune dans l'adapter.

L'executor génère U pendant la transition, jamais au POST. Sous
`TransactionalExecuteConsumptionUseCase`, dans la **même transaction** que le CAS final
du claim, il appelle `acquireForNewUser`, écrit l'outcome terminal propre à R et, sur
succès, append `UserCreated(U)`. `ExternalIdentityAttached` est déjà produit par
l'autorité Binding : ne jamais l'append une seconde fois. Toute exception ou perte de
claim rollbacke User, binding, outcome et faits. Aucun savepoint, `REQUIRES_NEW`,
delete User compensatoire ni catch d'une transaction PostgreSQL abortée.

`UserCreated` appartient à la **famille métier User/Identity**. Son journal durable
append-only `user_identity_user_facts` porte `event_id`, `request_id`, `user_id`,
`recorded_at` et le type `USER_CREATED` ; `request_id` est unique pour ce fait.
Il est écrit dans la même transaction que le journal de binding, User, binding et outcome.
La table `external_identity_binding_facts` reste spécialisée : son stream E/B et son
worker ne sont pas élargis artificiellement à un fait sans E/B. L'outcome autoritatif,
unique par `request_id`, reste distinct des slots/results Consumption et des faits.

Si E est courante au point d'arbitrage, écrire seulement
`Rejected(EXTERNAL_IDENTITY_ALREADY_USED)` et finaliser le claim dans la transaction.
Un Detach ultérieur ne change pas ce résultat ; si Detach a gagné avant l'arbitrage,
Registration peut réussir. Deux requests distinctes concurrentes pour E, sans Detach
intercalé, donnent exactement un succès et un rejet. Le retry de **la même** R recharge
d'abord son outcome et ne réapplique aucun effet.

### 3.3 Résultat READ

Une task `REGISTRATION_RESULT` de clé `(requestId, version=1)` est assurée dans la
transaction qui écrit l'outcome, **à partir de REG.4**, quand le producteur est disponible.
Le producteur recharge cet outcome depuis WRITE dans le worker ProjectionTask et publie
une projection immutable contenant `requestId`, `creator E` et
`Registered(U,B)` ou seulement `Rejected(EXTERNAL_IDENTITY_ALREADY_USED)`.
Cette lecture primaire du worker n'est jamais celle du GET. Les outcomes REG.3 antérieurs
sont repris par un backfill idempotent de tasks fondé sur les outcomes terminaux.

Le GET lit le résultat projeté et `CURRENT_BINDING` depuis READ :

```text
caller = creator E, Registered(U,B), CURRENT_BINDING(E) = ATTACHED(U,B) → 200
caller = creator E, Rejected(EXTERNAL_IDENTITY_ALREADY_USED)          → 200
toute autre combinaison                                                → 404 opaque
```

Sont masqués : binding absent/détaché, B2 avec le même U, U2/B2, autre E du même U,
projection absente et request inconnue. La révision monotone de `CURRENT_BINDING`
empêche un fait ancien de réactiver B1 après B2. Aucun GET ne consulte les tables
request/outcome/User/binding/faits WRITE ni les tables Consumption. Aucun état
`PENDING`, `PROCESSING` ou `FAILED` public.

## 4. Lots démontrables et committables

### REG.1 — Request durable et admission authentifiée

Créer modèles, port/repository/table request, discovery ordonnée
`(capturedAt, requestId)` et POST `202`. Ajouter WRITE Registration, HTTP Registration
et wiring WEB. La discovery peut filtrer DONE, occupé et non éligible, mais ne crée
aucun slot avant acquire. Aucun worker Registration actif.

**Preuves :** JWT connu/inconnu → request seule et `202` ; absent/invalide → `401`
sans ligne ; round-trip immutable ; ordering/pagination ; zéro SELECT primaire d'identité
au POST ; aucun User, binding, outcome ou fait.

**Vérification pré-déclarée :** impact WEB et persistence request/discovery ; slice **WEB**
`./mvnw -pl runtime-web-api -am test`. Gate global
`./mvnw -pl architecture-tests -am test` requis pour modules/frontières nouveaux.
Migration nouvelle : test PostgreSQL ciblé du schéma courant. Full reactor : non.

### REG.2 — Transition WRITE et concurrence

Ajouter `acquireForNewUser` à l'autorité Binding, outcome unique, journal
`UserCreated`, use case et adapters. L'executor reste inactif jusqu'à REG.3.
Tester avec deux connexions PostgreSQL réellement concurrentes et une barrière au
lock du stream.

**Preuves :** R1(E) || R2(E) → un Registered, un Rejected, un seul
User/binding/Attached/UserCreated ; conflit sans User candidat ; aucun User orphelin ;
retry de R → même outcome ; concurrence Registration/Attach/Detach ; échec après chaque
écriture et perte de claim → rollback complet ; aucun delete User ; outcome unique
par contrainte `request_id`.

**Vérification pré-déclarée :** impact WRITE Registration, Binding et persistence ;
slices **BINDING + COMMAND** car le port Binding est partagé avec le fence Command :
`./mvnw -pl runtime-binding-consumption-worker,runtime-command-consumption-worker -am test`.
Gate architecture requis pour port/module ; tests PostgreSQL ciblés ; full reactor non.

### REG.3 — Exécution asynchrone réelle

Créer `engine-consumption-registration` et
`runtime-registration-consumption-worker`. Adapter R à la clé
`REGISTRATION_REQUEST/requestId` + `REGISTRATION_PROCESSOR`, recharger R après
claim, appeler REG.2 sous transaction fenced et utiliser polling/lease/retry/takeover
génériques. L'état public ne vient jamais de Consumption. Aucun `UserRegistered`.

**Preuves :** discovery bornée, acquire paresseux, restart, plusieurs workers,
takeover, perte de claim, unicité outcome/faits, rejet sans faits User/Identity,
failure technique réessayée sans troisième résultat public.

**Vérification pré-déclarée :** nouveau slice Registration, anchor
`runtime-registration-consumption-worker` :
`./mvnw -pl runtime-registration-consumption-worker -am test`.
BINDING secondaire seulement si son comportement change à nouveau.
Gate architecture requis ; full reactor non.

### REG.4 — Projection du résultat et GET exclusivement READ

Créer producteur/task `REGISTRATION_RESULT`, projection/store READ, use case de
visibilité et GET. Activer l'émission de tasks et le producteur dans le même lot ;
backfill idempotent les outcomes antérieurs sans insérer manuellement projection
ou faits. Réutiliser le `CURRENT_BINDING` actuel, sans attendre sa standardisation.

**Preuves :** résultat avant/après binding ; auteur exact voit son rejet, autre E
non ; succès seulement sous E/U/B exact ; detach masque B1 ; reattach B2 vers U
ou U2 ne réactive pas B1 ; faits Binding hors ordre/rejoués ; absence de projection
→ même `404` ; traces SQL et guards prouvent zéro lecture WRITE/Consumption du GET ;
backfill rejouable.

**Vérification pré-déclarée :** slices **PROJECTION + BINDING + WEB**
`./mvnw -pl runtime-task-consumption-worker,runtime-binding-consumption-worker,runtime-web-api -am test`.
Gate architecture requis pour modules READ/producteur ; migration READ ciblée ;
full reactor non.

### REG.5 — Premier Bruno E2E autonome et clôture

Le fixture prépare uniquement l'AuthN technique de E. Il ne crée manuellement
aucun User, binding, outcome, fait, task ou projection.

```text
unknown authenticated E → POST Registration → Registered(U,B) visible
→ GET self binding = U/B → POST CreatePot(E,B) → Command worker
→ COMMAND_RESULT READ → Pot READ
```

Compléter D1–D33 et les négatifs REG.1–REG.4 : concurrence, claim perdu,
known/unknown E, rejet sans owner divulgué, B1 périmé/B2 accepté,
restart et multi-worker. Vérifier les migrations nouvelles sur installation
courante et upgrade représentatif. La politique indique encore
`Trusted baseline: NONE` : ne pas présumer d'une baseline certifiée.

**Vérification pré-déclarée :** WEB + Registration + COMMAND + BINDING + PROJECTION :
`./mvnw -pl runtime-web-api,runtime-registration-consumption-worker,runtime-command-consumption-worker,runtime-binding-consumption-worker,runtime-task-consumption-worker -am test`.
Gate `./mvnw -pl architecture-tests -am test` requis. À la clôture de ce
milestone multi-module, `./mvnw test` est le gate d'intégration global,
**une fois**, après les slices ; il n'est pas la boucle de chaque lot.
Bruno E2E et tests PostgreSQL sont obligatoires.

## 5. Instructions aux sessions d'implémentation

Avant toute modification sous `app/`, lire
`docs/testing/Reactor_Verification_Policy.md` et déclarer pour le lot
impacts permis/interdits, slices, base, gate et conditions d'escalade.
Les commandes du §4 sont les ancres initiales ; une frontière franchie
inopinément doit être expliquée et le scope révisé **avant** de poursuivre.
Les nouveaux modules exigent un gate d'architecture ; un changement
documentaire seul ne lance aucun slice.

Les canons et audits historiques restent inchangés sauf contradiction
factuelle prouvée. Ne pas rouvrir D1–D33. Le Bruno E2E entièrement réel
est la condition de clôture, jamais un fixture qui préinsère ses effets.

**Décisions d'implémentation ouvertes : aucune.** Les détails locaux de
nommage SQL/Java et de wiring suivent les conventions du repository sans
changer les frontières, résultats ou preuves fixés ici.
