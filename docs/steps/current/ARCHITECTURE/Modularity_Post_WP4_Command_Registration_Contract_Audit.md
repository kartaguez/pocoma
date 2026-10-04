# Complément post-WP4 — ownership Command / Registration

**Statut : audit documentaire, proposition non implémentée.** Complète [l'audit des décisions ouvertes](Modularity_Post_WP4_Open_Decisions_Audit.md). `TBD-COMMAND-CONTRACT` reste **OPEN**. Ce texte ne lance pas POST-WP4.A/B/C et ne change ni `TBD-E2U`, ni `TBD-LKV`, ni CURRENT_BINDING. Les sources `app/*/src/main` et les POM au HEAD priment sur les anciens plans.

## 1. Baseline et méthode

Branche `v2-make-it-pull`. `git fetch origin` exécuté avec succès (après refus initial du sandbox d'écrire `.git/FETCH_HEAD`). Avant le document : `HEAD = origin/v2-make-it-pull = 0c1b8bf758ababa9f0a92d7d0700e9af560ed705`, `git rev-list --left-right --count origin/v2-make-it-pull...HEAD = 0 0`, `git status --short` vide. Aucun écart.

**Vérification déclarée avant modification :** diff documentaire seulement ; slices Maven **aucune**, gate global **non requis**, full reactor **non requis**, base de données **non exécutée**. Méthode : inventaire exhaustif des deux sources de `contracts-registration`, recherche `rg` des usages de production, lecture des moteurs, supras, adapters PRIMARY, runtimes, migrations existantes (lecture seule), POM et canon Registration ; analyse statique des arcs Maven. `git diff --cached --check` constitue le contrôle du livrable. Aucun comportement `app/` n'est modifié.

## 2. Inventaire exhaustif de `contracts-registration`

Le module contient **deux fichiers de production**, sans port, DTO HTTP ou type `RegistrationRequestId` dédié. `Registered` et `Rejected` sont les deux records imbriqués dans le type sealed `RegistrationOutcome`. L'ID est un `UUID` présent dans les deux contrats, et non une troisième classe. `ExternalIdentity` vient de `domain-user-identity` ; `AuthenticatedExternalPrincipal` vient de `contracts-authentication`, hors de ce module.

Dans la matrice, A = admission, P = persistance, X = exécution, M = matérialisation, R = Result READ, H = HTTP ; « indirect » désigne une traduction, et non l'import d'un type par l'étape.

| Type | Sémantique | Producteur | Consommateurs | A | P | X | M | R | H |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `RegistrationRequest` | Intention authentifiée immuable : `UUID requestId`, E `(issuer,subject)`, `payload="{}"`, `createdAt` | `AdmitRegistrationService` ; reconstitution par `JdbcRegistrationRequestStore` | recorder admission, reader consume, exécution, `RegistrationResultSource`/materializer, PRIMARY | oui | `registration_requests`, insert et reload | rechargée après découverte | rechargée pour E historique et corrélation | non directement | POST crée l'intention via admission ; aucun DTO partagé |
| `RegistrationOutcome` | Décision métier terminale sealed par request : `Registered(U,B)` ou `Rejected(EXTERNAL_IDENTITY_ALREADY_USED)` | `ExecuteRegistrationService` ; reconstitution par les stores JDBC | repository consume, locator, source/materializer, `ImmutableRegistrationResult`, store/read GET, controller | non | `registration_outcomes` puis représentation dans `registration_results` | produit ou relu lors du retry | oui | **oui, directement aujourd'hui** | GET le traduit en réponse JSON |
| `RegistrationOutcome.Registered` | Variante de succès, ID + UserId + BindingId | exécution après acquisition Binding | outcome store, Result store, GET HTTP | non | oui | oui | oui | oui | traduit en `REGISTERED`/U/B |
| `RegistrationOutcome.Rejected` | Variante de rejet et code constant | exécution après conflit Binding | locator (BusinessConsumptionOutcome), stores, GET HTTP | non | oui | oui | oui | oui | code JSON `EXTERNAL_IDENTITY_ALREADY_USED` |

Il n'existe dans `contracts-registration` ni preuve d'authentification distincte, ni statut Consumption, ni port. E attestée est extraite du principal à l'admission, puis inscrite dans `RegistrationRequest`. Le token, ses scopes et les détails HTTP ne sont pas durables dans cette request. Le `payload="{}"` est une valeur validée par le contrat d'intake actuel ; la colonne JSONB est une représentation technique PRIMARY, pas un type à déplacer dans le contrat. La découverte (`Candidate`, `Cursor`) et les états/échecs Consumption restent propres aux moteurs/supras.

## 3. Pipeline Registration réel et frontières

```text
POST /api/v1/registrations (supra-http-write.RegistrationController)
  → AdmitRegistrationService : E du principal, nouveau UUID, "{}", instant
  → RegistrationRequestRecorder.insert → JdbcRegistrationRequestStore → registration_requests (PRIMARY)
  → HTTP 202 {requestId}

JdbcRegistrationDiscovery : request_id/created_at seulement
  → supra-consume-registration.RegistrationConsumptionLocator / claim + execute transaction
  → RegistrationRequestReader.find → RegistrationRequest durable rechargée
  → ExecuteRegistrationService : existing RegistrationOutcome ? sinon acquireWithInitializer(E,U,...)
  → RegistrationOutcomeRepository.insert → registration_outcomes (PRIMARY, 0..1 terminal)
  → BusinessConsumptionOutcome pour la mécanique Consumption

JdbcRegistrationResultDiscovery : request_id/decided_at depuis registration_outcomes
  → supra-consume-registration-result.RegistrationResultConsumptionLocator
  → RegistrationResultSourcePort.reload : Request + Outcome autoritatifs
  → MaterializeRegistrationResultService : vérifie les trois requestId, prend E historique
  → RegistrationResultStore.ensureResult → registration_results (PRIMARY, 0..1)
  → GetRegistrationResultService / owner E exact
  → supra-http-read.RegistrationResultController → HTTP 200 ou 404 opaque
```

Ce Result est **Direct** et stocké en PRIMARY, sans chemin `ProjectionTask` ni lecture de CURRENT_BINDING. La consommation Registration et celle du Result ont chacune leur slot, locator et runtime ; `registration_outcomes` est la source de découverte du second worker. L'adapter PRIMARY `JdbcRegistrationRequestStore` sert les deux ports TARGET distincts. `JdbcRegistrationOutcomeStore`, `JdbcRegistrationResultSource` et `JdbcRegistrationResultStore` assurent les autres frontières. Le shell historique `engine-registration` conserve une façade d'exécution et les interfaces `RegistrationRequestStore`/`RegistrationOutcomeStore` pour compatibilité ; son arc est LEGACY → TARGET. Il ne possède plus le GET ni la matérialisation. Son `RegistrationRequestStore` mélange encore insert/find **dans le bridge**, tandis que les vrais besoins TARGET sont déjà séparés.

## 4. Command comparé à Registration au HEAD

| Concept | Command | Owner CURRENT | Registration | Owner CURRENT |
| --- | --- | --- | --- | --- |
| ID durable | `CommandId` typé, créé avant insert | `engine-consume-command` (provisoire) | `UUID requestId` dans Request/Outcome/Result, créé avant insert ; aucun value type dédié | `contracts-registration` via `RegistrationRequest`, sinon signatures `UUID` |
| Request/envelope durable | `RecordedCommand` avec `CommandType`, payload, E+B et evidence | `engine-consume-command` (provisoire) | `RegistrationRequest` avec E, `"{}"`, instant ; aucun B préexistant | `contracts-registration` |
| Evidence d'authentification | `CommandAuthenticationEvidence` et `TargetCommandEnvelope` capturés | `engine-consume-command` (provisoire) | E attestée dans request ; principal hors request et hors contrat Registration | `domain-user-identity` pour E ; `contracts-authentication` pour le principal |
| Outcome d'exécution | `CommandOutcome` Applied/Rejected/Failed | `engine-consume-command` | `RegistrationOutcome` Registered/Rejected | **`contracts-registration`** |
| Published Direct Result | `ImmutableCommandResult` contient actuellement `CommandOutcome` | `engine-read-command-result`, avec dépendance à consume | `ImmutableRegistrationResult(owner, RegistrationOutcome)` | `engine-read-registration-result`, avec dépendance à contracts |
| Port d'insert admission | `RecordedCommandPort.insert` | `engine-consume-command` (port mixte) | `RegistrationRequestRecorder.insert` | `engine-admit-registration` |
| Port de reload consumption | `RecordedCommandPort.findById` | `engine-consume-command` (même port) | `RegistrationRequestReader.find` | `engine-consume-registration` |

Les asymétries sont justifiées par le cycle de vie : Command vérifie E+B et une evidence de fraîcheur à l'exécution ; Registration admet E pour acquérir un nouveau Binding, sans B capturé à l'admission. L'ID Command a un value type ; l'ID Registration est actuellement `UUID`. Il n'est pas nécessaire d'introduire un type Registration pour copier Command. Les variantes terminales diffèrent (`Failed` existe côté Command, pas dans le canon Registration). L'asymétrie **non justifiée** est l'owner de l'outcome : les deux décisions naissent après exécution. Registration a déjà séparé les ports TARGET ; Command ne l'a pas encore fait.

## 5. Sémantique de chaque type partagé et de `RegistrationOutcome`

`RegistrationRequest` existe **avant** l'exécution, est insérée par l'admission, relue après claim, et fournit à l'exécution E autoritative pour cette occurrence. Le materializer la relit pour corréler request/outcome et conserver E historique. L'ID, E, le payload vide validé et l'instant appartiennent donc au même contrat durable d'intake. Son owner `contracts-registration` est cohérent. `ExternalIdentity` reste au domaine User/Identity et le principal HTTP reste au contrat d'authentification : la request ne les absorbe pas. Le `UUID` de request pourrait devenir un value type si un invariant nouveau le justifie ; ce n'est pas nécessaire pour cette séparation.

Réponses explicites pour `RegistrationOutcome` :

| Question | Fait au HEAD |
| --- | --- |
| Qui le produit ? | `ExecuteRegistrationService` après `acquireWithInitializer`, ou le repository le recharge si la même request a déjà été exécutée. |
| Existe-t-il avant exécution ? | Non ; pas à l'admission ni dans `registration_requests`. |
| Nécessaire à l'admission / insertion initiale ? | Non ; `AdmitRegistrationService` n'importe que `RegistrationRequest`. |
| Produit uniquement par consume ? | Décision initiale oui ; PRIMARY le reconstruit lors des reloads et du GET. Le code de rejet sert aussi au locator/HTTP. |
| Persisté comme résultat terminal ? | Oui, une ligne `registration_outcomes` par request, immuable ; le retry relit la décision. |
| Le materializer le consomme-t-il ? | Oui, via `RegistrationResultSource`, avec comparaison des ID. |
| Le moteur READ le connaît-il ? | Oui : champ de `ImmutableRegistrationResult` et retour de `GetRegistrationResultService`. |
| Confondu avec Result publié ? | Oui **dans l'objet Java**, malgré deux tables et deux étapes de publication distinctes. |

Son owner `contracts-registration` est donc à challenger : ce type est une décision **execution-owned**, pas une valeur nécessaire au contrat d'intake. Le fait qu'un materializer et le GET l'utilisent ne le transforme pas en contrat d'admission. Le code de rejet public a une sémantique partagée ; la conversion au materializer doit préserver exactement la valeur `EXTERNAL_IDENTITY_ALREADY_USED` et le controller doit utiliser le modèle publié, sans imposer que l'ensemble du type d'exécution reste partagé.

## 6. Published Registration Result

`engine-read-registration-result` possède déjà `ImmutableRegistrationResult`, `RegistrationResultStore` et le contrôle E exact de `GetRegistrationResultService`. La table `registration_results` est séparée de `registration_outcomes`, avec clé `request_id`, owner `(issuer,subject)`, version de schéma, issue publique et trigger d'immutabilité. `ensureResult` utilise `ON CONFLICT DO NOTHING`, relit puis rejette une divergence : replay identique admis, publication divergente refusée. Le Result est donc `0..1` et immuable au niveau du store. Le GET compare l'E attestée à l'E **historique** enregistrée ; absence ou autre owner donnent le même 404. Il ne consulte ni Binding courant, ni projection.

**Limite de frontière :** `ImmutableRegistrationResult` n'est pas autonome au niveau des types : il garde `RegistrationOutcome`, le store JDBC le reconstruit, le GET le retourne et le controller le pattern-match. La dépendance READ → outcome d'exécution passe aujourd'hui par `contracts-registration`. Le modèle publié devrait contenir son propre ID/owner/statut public/U/B/code immuables (forme précise à choisir au lot d'implémentation), sans champ `RegistrationOutcome`. La conversion unique appartient à `engine-materialize-registration-result`, qui a légitimement besoin de Request, Outcome et du store READ. La persistance doit relire le modèle publié, pas reconstruire un outcome. Ce changement d'ownership n'implique aucun changement SQL, de table ou de règle de visibilité.

## 7. Ports et règle commune

Registration TARGET possède déjà `RegistrationRequestRecorder.insert` dans admit et `RegistrationRequestReader.find` dans consume. PRIMARY implémente les deux. Le seul port mixte est `engine-registration.RegistrationRequestStore`, bridge historique pour tests/compatibilité WP5 ; le déplacer vers `contracts-registration` ou le réutiliser comme port TARGET recréerait le problème Command. L'enlever relève de D.19/WP5 après migration de ses appelants, pas du présent audit. Côté Command, `RecordedCommandPort` possède encore insert et reload dans consume : la séparation proposée en POST-WP4.A répond à un besoin réel de deux phases et retire `admit → consume`.

**Règle proposée, affinée :** `contracts-<capability>` peut posséder les identités et valeurs **stables, définies avant ou pendant l'admission**, dont la même signification durable traverse admission → stockage → consommation (éventuellement relues ensuite comme provenance). Un besoin de corrélation tardif ne suffit pas à y placer un outcome. La décision terminale et sa policy appartiennent à l'exécution ; le Direct Result publié et sa visibilité au moteur READ ; la conversion contrôlée au materializer ; lignes SQL aux adapters PRIMARY ; JSON HTTP aux supras. Un type partagé exclusivement après exécution ne devient pas « intake » parce que plusieurs modules l'importent. Une différence de contenu d'envelope ou de représentation d'ID reste légitime si les invariants métier diffèrent.

## 8. Classification et delta proposé à POST-WP4.A

**R2 — même défaut d'ownership pour l'outcome et le modèle publié, mais pas pour la request ni les ports TARGET.** Déplacer `RegistrationOutcome` de `contracts-registration` vers `engine-consume-registration`, rendre `ImmutableRegistrationResult` autonome dans `engine-read-registration-result`, convertir dans `engine-materialize-registration-result`, puis adapter PRIMARY, locator et controller au nouveau type de chaque frontière. Garder `RegistrationRequest` dans `contracts-registration`, les deux ports TARGET et le même adapter PRIMARY. Ne pas déplacer mécaniquement le `UUID` ni l'E de domaine. L'opération ne doit modifier ni la décision métier, ni l'ordre transactionnel User/Binding/outcome.

**Delta au plan, sans exécution :** ajouter à POST-WP4.A.2 un lot Registration Result/outcome coordonné avec Command. A.1 conserve l'intake Command et ses ports ; Registration n'a aucun refactor d'intake à faire. A.2 prouve pour les **deux** familles que le READ ne transporte plus l'outcome d'exécution. Déclarer avant ce futur changement les slices réellement touchées : `runtime-registration-consumption-worker`, `runtime-registration-result-consumption-worker`, `runtime-web-api` et les anchors Command déjà prévus ; employer les commandes canoniques de la politique, éventuellement une commande `-pl ... -am test` composée. Nouveau POM/arc Command et déplacement de frontière imposeront le gate global d'architecture selon la politique ; décider le full reactor selon l'ampleur réelle et le jalon, pas pour ce document. Garder `TBD-COMMAND-CONTRACT` OPEN jusqu'à l'implémentation et ses preuves.

## 9. Graphe Maven et vérifications statiques

Arcs **directs de production** pertinents au HEAD (`infra-persistence-jpa` est le PRIMARY actuel ; `infra-persistence-primary-jpa` n'existe pas encore) :

```text
contracts-registration → domain-user-identity
engine-admit-registration → contracts-registration
engine-consume-registration → contracts-registration
engine-materialize-registration-result → contracts-registration + engine-read-registration-result
engine-read-registration-result → contracts-registration + domain-user-identity
supra-consume-registration → engine-consume-registration
supra-consume-registration-result → engine-materialize-registration-result
infra-persistence-jpa → contracts-registration + admit/consume/materialize/read Registration + engine-registration (legacy bridge)
runtime-web-api → contracts-registration + admit/read Registration + infra-persistence-jpa + supra-http-write/read
```

Après correction Registration proposée :

```text
contracts-registration → domain-user-identity                  [Request seulement]
engine-admit-registration → contracts-registration             [identique]
engine-consume-registration → contracts-registration           [Request ; Outcome local]
engine-materialize-registration-result → contracts-registration
                                       + engine-consume-registration [Outcome, ajouté]
                                       + engine-read-registration-result [Result]
engine-read-registration-result → domain-user-identity         [arc contracts-registration retiré]
supra-consume-registration → engine-consume-registration        [identique]
supra-consume-registration-result → engine-materialize-registration-result [identique]
infra-persistence-jpa → mêmes modules ; adapter Request/Outcome/Result, puis futur renommage WP5
runtime-web-api → mêmes modules de composition ; retirer les dépendances directes devenues inutiles lors de l'implémentation
```

Command conserve le graphe proposé dans [l'audit initial](Modularity_Post_WP4_Open_Decisions_Audit.md#4-graphe-résultant-et-vérification-statique) : `contracts-command` limité à l'intake ; admit/consume y accèdent ; materialize → consume pour l'outcome et → read pour le Result ; read → contracts pour l'ID, sans arc read → consume ; port insert dans admit et reload dans consume. La correction Registration n'ajoute aucun POM et échange un arc direct (`read → contracts`) contre un autre (`materialize → consume`).

Analyse XML des POM de production au HEAD : **72 modules, 306 arcs, aucun cycle** ; simulation de ces deux changements d'arcs Registration : **72/306, aucun cycle**. Simulation supplémentaire du sous-graphe d'ownership Command (nouveau `contracts-command`, retrait `admit/read → consume`, ajout de ses consommateurs de contrat) avec Registration : **aucun cycle** ; le graphe complet de POST-WP4.A devra être recalculé sur son vrai diff. Aucun module TARGET Registration listé ci-dessus ne dépend de `engine-registration`; le bridge est LEGACY → TARGET. Aucun `engine-* → runtime-*`. Aucun arc des deux moteurs `engine-read-*-result` vers CURRENT_BINDING ou ProjectionTask dans les POM, et les stores/GET Registration examinés ne les lisent pas. `infra-persistence-jpa` porte plusieurs adapters, dont ProjectionTask, mais le chemin **Direct Result Registration** n'emprunte pas ces tables : ne pas confondre coexistence dans PRIMARY et dépendance de ce Result.

## 10. Invariants à protéger

- Conserver `bindings.acquireWithInitializer(E,U, initializer)` : le lock/arbitrage Binding précède `initializer.run()`, qui crée User et `UserCreatedFact` seulement si l'E est libre ; succès, fait Binding et outcome restent dans l'exécution transactionnelle fenced. Un conflit ne laisse aucun User orphelin et produit un rejet terminal.
- Retry de la même request : outcome existant relu, aucun second User/Binding. Deux requests concurrentes pour E : une seule acquisition. Aucun changement de policy Consumption.
- Request et outcome immuables ; materializer vérifie les IDs avant publication ; Result `0..1` et replay non divergent ; owner E historique, GET 404 opaque, indépendance de CURRENT_BINDING et ProjectionTask.
- Codes et formes HTTP (`202`, `200`, `404`, `REGISTERED`, `REJECTED`, `EXTERNAL_IDENTITY_ALREADY_USED`) préservés. Aucun SQL, schéma ou migration requis pour le déplacement de types proposé.

## Réponse finale

**YES.** La frontière envisagée pour Command révèle la même incohérence de cycle de vie dans Registration : un outcome né uniquement de l'exécution est possédé par `contracts-registration` et sert directement de contenu au Result READ. La request Registration, elle, est déjà un vrai contrat durable d'intake, et ses ports TARGET sont déjà séparés. La correction doit donc porter sur l'outcome et le modèle publié, sans forcer une identité de modules ni toucher à la transaction métier Registration. **Même cycle sémantique → même principe d'ownership ; cycles différents → asymétrie justifiée.**
