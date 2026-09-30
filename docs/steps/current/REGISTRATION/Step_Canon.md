# REGISTRATION — Canon métier et architectural

```text
Step: REGISTRATION
Phase: FRAMING CLOSED
Next phase: implementation planning
```

Ce document est l'autorité normative du cadrage REGISTRATION. Il fixe le métier et les frontières
architecturales que le futur plan d'implémentation devra respecter ; il ne constitue ni ce plan, ni
un design Java, SQL ou HTTP détaillé.

Le canon transversal [`WRITE_ADMISSION`](../WRITE_ADMISSION/Step_Canon.md) complète et prévaut sur
ce document pour les frontières HTTP WRITE/READ, l'identité de binding et `BindingId`.

Les audits [`step_audit.md`](step_audit.md) et [`domain_audit.md`](domain_audit.md) sont des preuves
historiques et factuelles, pas la source canonique du comportement cible. D1–D30 ont clos le métier.
`domain_audit.md` avait conclu `NOT CLOSABLE` pour le cadrage combiné uniquement parce que
l'ownership User/Identity, la frontière de cohérence User/binding et le vocabulaire des faits
métier restaient ouverts. D31–D33 ont depuis fermé exactement ces trois questions. Le cadrage
métier et architectural est donc clos et REGISTRATION est prête pour un plan d'implémentation.

## 1. Purpose

Registration transforme une intention durable portée par une identité externe déjà attestée en
une tentative unique de créer un nouveau User et de lui rattacher cette identité :

```text
ExternalIdentity attestée E
    ↓ admission
RegistrationRequest R(E), durable et immutable
    ↓ exécution de la Registration
Registered(U, B)
    + User(U)
    + binding courant Binding(E, U, B)
    + UserCreated(U)
    + ExternalIdentityAttached(E, U, B)

ou

Rejected(EXTERNAL_IDENTITY_ALREADY_USED)
    + aucune mutation User/Identity
    + aucun fait User/Identity
```

Registration n'est ni une Command, ni un get-or-create de User, ni l'autorité du lifecycle
technique de son traitement.

## 2. Modèle métier canonique

### 2.1 User

`User` est une entité autonome et l'aggregate root de sa propre existence. Son état métier minimal
est exactement :

```text
User
----
PocomaUserId
```

Un User existe indépendamment de ses bindings courants. Il peut donc exister avec zéro
ExternalIdentity, notamment après le détachement de sa dernière identité. Le détachement ne
supprime, ne désactive et ne compense pas le User.

Ce cadrage n'introduit ni profil, ni statut `ACTIVE`/`DISABLED`/`DELETED`, ni autre lifecycle User.
Le `PocomaUserId` est généré pendant la transition métier effective qui crée le User, jamais à
l'admission de la request.

### 2.2 ExternalIdentity

Une `ExternalIdentity` est la valeur provider-neutral et opaque :

```text
ExternalIdentity(issuer, subject)
```

Elle provient exclusivement d'un principal déjà authentifié par un fournisseur accepté. Le domaine
ne reçoit ni ne persiste le token brut. `issuer` et `subject` sont comparés exactement, sans
normalisation métier implicite. Le client ne choisit dans le payload ni l'identité, ni le
`PocomaUserId`.

Une ExternalIdentity n'est ni « principale », ni « première », ni « registration identity ». Une
même identité garde la même signification avant, pendant et après Registration.

### 2.3 Binding courant

Le binding courant est une autorité durable distincte du User :

```text
Binding(ExternalIdentity, PocomaUserId, BindingId)
```

Sa clé métier naturelle est l'ExternalIdentity. Il protège l'invariant global :

> Une ExternalIdentity courante est rattachée à au plus un User.

Un User peut avoir plusieurs ExternalIdentities courantes. Il ne possède toutefois pas
conceptuellement une collection `externalIdentities[]` : charger un User ne définit ni ne protège
l'ensemble global des bindings.

Une identité détachée redevient disponible et peut être rattachée ou utilisée par une Registration
ultérieure. Aucun registre séparé des identités « libres » n'existe. AttachExternalIdentity et
DetachExternalIdentity sont hors du use case Registration, mais doivent employer la même autorité
de binding et le même invariant global.

`BindingId` identifie l'occurrence précise du rattachement. Il est opaque, unique, non ordinal,
non réutilisable et sans sémantique temporelle ; seule l'égalité compte. Chaque acquisition ou
Attach réussi génère un B neuf, y compris après detach/reattach vers le même User. Detach invalide
l'occurrence courante. L'absence d'historique durable des anciennes occurrences reste compatible
avec cet invariant.

## 3. RegistrationRequest et admission

Une `RegistrationRequest` est une intention durable, spécifique à Registration et distincte du
User, du binding, de la Registration exécutée et de toute Consumption technique. Elle capture
immuablement l'ExternalIdentity attestée qui l'a créée et possède sa propre identité durable.

L'admission est ouverte à toute ExternalIdentity valablement authentifiée par un fournisseur
accepté. Elle ne requiert ni User Pocoma préalable, ni capability ou scope métier Registration. Elle
ne résout pas l'identité vers un User et ne vérifie pas sa disponibilité.

L'acceptation signifie uniquement que l'intention est enregistrée durablement pour traitement. Elle
ne préjuge pas de son résultat. Elle ne crée aucun User, aucun binding et aucun fait métier
User/Identity.

Après admission, la request est irrévocable : le caller ne peut ni la modifier ni l'annuler. Les
retries techniques de son exécution restent des tentatives de réaliser la même intention ; ils ne
créent pas une nouvelle RegistrationRequest.

## 4. Résultat fonctionnel

Une request possède au plus un résultat terminal, autoritatif et immutable :

```text
Registered(PocomaUserId, BindingId)
Rejected(EXTERNAL_IDENTITY_ALREADY_USED)
```

`EXTERNAL_IDENTITY_ALREADY_USED` est le seul rejet fonctionnel actuellement défini. Une failure
d'infrastructure n'est ni un troisième résultat métier, ni un résultat public `FAILED`. Elle reste
technique et laisse le traitement converger selon la stratégie d'exécution retenue.

Une absence de résultat terminal signifie seulement qu'aucun résultat fonctionnel n'est encore
visible. Le contrat fonctionnel n'expose aucun état `PENDING`, `PROCESSING`, claim, lease, retry,
takeover, failure de projection ou autre état intermédiaire. Le lifecycle technique appartient à
Consumption ou aux mécanismes d'exploitation, jamais à la RegistrationRequest ni à son résultat.

### 4.1 Visibilité

La visibilité d'un résultat exige l'égalité exacte entre l'ExternalIdentity du caller et celle
capturée immuablement par la request.

Pour `Registered(U,B)`, l'occurrence courante exacte `Binding(E,U,B)` doit en plus exister au moment
de la lecture.
Une autre identité du même User ne peut pas lire ce résultat. Si E est détachée ou rattachée à un
autre User, le résultat demeure terminal et durable mais n'est plus fonctionnellement visible via E.
Un detach puis reattach, même vers U, produit un nouveau B et ne réactive jamais la visibilité de
l'ancienne occurrence.

Pour `Rejected(EXTERNAL_IDENTITY_ALREADY_USED)`, seule l'identité créatrice exacte peut voir le
résultat ; aucun owner existant ni `PocomaUserId` ne lui est révélé.

La traduction HTTP, la représentation READ et la manière de masquer absence, non-disponibilité et
non-ownership restent des décisions d'implémentation. Le endpoint construit exclusivement depuis
READ/projections, sans lecture du primaire WRITE, et ne consulte ni n'expose le lifecycle
Consumption. La cohérence éventuelle de READ est acceptée.

## 5. Atomicité et concurrence

### 5.1 Frontière transactionnelle du succès

Une Registration réussie est une transition métier atomique qui rend durables ensemble :

```text
create User(U)
generate and acquire current Binding(E, U, B)
write terminal Registered(U, B) for the request
write UserCreated(U)
write ExternalIdentityAttached(E, U, B)
```

Ou aucun de ces effets ne subsiste. En particulier, aucun état où le User nouvellement créé existe
sans son binding initial ne peut être le résultat d'une Registration. La RegistrationRequest admise
auparavant reste durable quel que soit le résultat de la transition.

Le succès n'est jamais compensé par suppression du User. Une propagation READ éventuellement
différée ne rouvre pas la transition et ne rend pas la request rejouable comme nouvelle intention.

### 5.2 Nouvelle request et retry de la même request

Deux RegistrationRequests distinctes concurrentes pour la même ExternalIdentity ne sont pas
idempotentes entre elles :

```text
R1(E) → Registered(U1,B1)
R2(E) → Rejected(EXTERNAL_IDENTITY_ALREADY_USED)
```

L'ordre des deux résultats est indifférent, mais il doit y avoir exactement un succès. Le perdant ne
crée aucun User et ne converge pas vers le User du gagnant. Toute forme de `get-or-create` qui relit
et retourne l'owner existant est contraire au canon.

À l'inverse, plusieurs exécutions ou retries techniques de la même RegistrationRequest convergent
vers son unique résultat terminal. Une tentative qui reprend après un commit ne réapplique pas la
mutation et ne transforme pas son propre binding en rejet.

### 5.3 Registration, Attach et Detach

Registration et AttachExternalIdentity visant E arbitrent par la même autorité de binding ; aucune
priorité ne découle de l'ordre des workers, de discovery ou des claims. La transition qui acquiert E
gagne, l'autre applique son issue métier. Chaque Attach gagnant crée un `BindingId` neuf et le
retourne dans son résultat.

Registration et DetachExternalIdentity sont ordonnées par leurs transitions autoritatives : si le
détachement de E est effectif avant l'acquisition, Registration peut réussir ; si le binding existe
encore au point d'arbitrage, Registration est rejetée. Une lecture préalable hors de cette frontière
ne décide jamais du résultat.

Detach invalide l'occurrence exacte courante. Un attach ultérieur crée toujours une nouvelle
occurrence avec un nouveau B ; aucun `BindingId` n'est réutilisé.

## 6. Faits métier User/Identity

Une Registration réussie produit deux faits métier distincts :

```text
UserCreated(U)
ExternalIdentityAttached(E, U, B)
```

Ils sont enregistrés atomiquement avec les mutations et le résultat terminal correspondants.
`ExternalIdentityAttached` décrit la création d'une occurrence exacte et a la même signification
lorsqu'il provient de Registration ou
d'un futur use case AttachExternalIdentity. Aucun fait unique `UserRegistered` ne les remplace :
Registration nomme le use case, tandis que les faits décrivent les changements du domaine
User/Identity.

Un futur fait Detach identifie lui aussi le `BindingId` de l'occurrence invalidée. Un consumer peut
ainsi ignorer un fait stale au lieu de supprimer une occurrence plus récente.

Une Registration rejetée ne produit ni `UserCreated` ni `ExternalIdentityAttached`. La famille
d'outbox, l'envelope et le schéma de persistence exacts de ces faits ne sont pas fixés ici.

## 7. Frontières architecturales canoniques

Le domaine User/Identity est l'autorité conceptuelle sur :

- `User` ;
- `PocomaUserId` ;
- `ExternalIdentity` ;
- le rattachement courant `ExternalIdentity → User`.

`PocomaUserId` n'appartient donc pas conceptuellement à `engine-command` et `ExternalIdentity`
n'appartient pas conceptuellement à `orchestrator-command-admission`. Command, Pot, AUTH,
Registration et les autres consommateurs utilisent ou adaptent les concepts du domaine
User/Identity. Registration est un use case agissant sur ce domaine, pas le propriétaire de ces
concepts. User/Identity ne dépend conceptuellement ni de Command, ni de ses outcomes, ni de son
admission.

`BindingId` appartient lui aussi au domaine User/Identity. L'autorité du binding porte l'identité
d'occurrence `Binding(E,U,B)`, pas seulement la relation naturelle E → U.

Les responsabilités restent séparées :

| Responsabilité | Autorité canonique |
|---|---|
| intention admise et identité créatrice immutable | RegistrationRequest |
| orchestration du use case | Registration |
| existence de U | User aggregate root |
| relation courante `Binding(E,U,B)` et unicité globale de E | autorité durable de binding |
| résultat métier terminal de R | résultat Registration |
| claims, leases, retries, takeover et état d'exécution | Consumption |

Cette séparation n'impose ni aggregate singleton des identités, ni collection d'identités dans
User. Elle ne décide pas davantage si le binding devient une classe dédiée ou reste une relation
protégée par un service et un port atomique.

## 8. Choix explicitement laissés au plan d'implémentation

Les éléments suivants ne sont pas des décisions canoniques de ce cadrage :

- modules Maven exacts à créer ou modifier ;
- déplacements précis des classes existantes et stratégie détaillée de migration de
  `PocomaUserId` / `UserId` ;
- noms et interfaces exacts des ports ;
- repositories, adapters et composition roots exacts ;
- classes éventuelles `ExternalIdentityBinding` ou équivalentes ;
- tables, contraintes, migrations et SQL finaux, notamment l'évolution éventuelle de
  `external_identities` ;
- endpoints, DTO, routes et codes HTTP précis ;
- structure exacte des projections de résultat et de binding self-service ;
- structure de polling et comportement HTTP avant visibilité terminale ;
- worker Registration, discovery, ordering, pagination et segmentation ;
- clés Consumption, leases, fencing et stratégie de retry ;
- schéma exact des outcomes, terminal Events et outboxes ;
- envelope, partitionnement et consumers des faits User/Identity ;
- paramètres opérationnels, métriques et topologie de déploiement.

Le futur plan peut choisir ces représentations, mais uniquement sous contrainte des invariants du
présent canon. Une décision technique encore ouverte ne rouvre pas le cadrage métier.

## 9. Traçabilité D1–D33

| Décision | Couverture canonique |
|---|---|
| D1 — User autonome | §2.1 |
| D2 — Registration crée un nouveau User | §1, §5.2 |
| D3 — unicité globale ExternalIdentity | §2.3, §5 |
| D4 — plusieurs identités par User | §2.3 |
| D5 — identité déjà utilisée = rejet | §1, §4, §5.2 |
| D6 — identité déjà attestée | §2.2, §3 |
| D7 — pas d'identité principale | §2.2 |
| D8 — User avec zéro identité | §2.1, §2.3 |
| D9 — création User + binding atomique | §5.1 |
| D10 — identité détachée réutilisable | §2.3, §5.3 |
| D11 — User minimal | §2.1 |
| D12 — toute identité acceptée peut s'inscrire | §3 |
| D13 — `Registered(U,B)` ou rejet unique | §4 |
| D14 — request, Registration, User/binding et Consumption distincts | §3, §7 |
| D15 — frontière transactionnelle User + binding | §5.1 |
| D16 — Registration irrévocable | §3 |
| D17 — seul rejet fonctionnel actuel | §4 |
| D18 — acceptation = intention durable seulement | §3 |
| D19 — visibilité liée à l'occurrence exacte `E,U,B` | §4.1 |
| D20 — résultat terminal unique et immutable | §4, §5.2 |
| D21 — aucun état technique dans le READ | §4, §7 |
| D22 — fait métier durable du succès | §5.1, §6 |
| D23 — génération du PocomaUserId pendant la transition | §2.1 |
| D24 — concurrence Registration/Attach/Detach et nouvel ID par occurrence | §2.3, §5.3 |
| D25 — aucune compensation User après succès | §5.1 |
| D26 — pas de lifecycle User anticipé | §2.1 |
| D27 — pas de registration identity dans User | §2.2, §6 |
| D28 — pas de registre des identités libres | §2.3 |
| D29 — identité opaque et provider-neutral | §2.2 |
| D30 — égalité exacte `(issuer, subject)` | §2.2, §4.1 |
| D31 — ownership canonique User/Identity, dont `BindingId` | §7 |
| D32 — User root et autorité distincte de l'occurrence de binding | §2.1, §2.3, §5, §7 |
| D33 — `UserCreated(U)` et `ExternalIdentityAttached(E,U,B)` | §6 |

## 10. Verdict de cadrage

**FRAMING CLOSED**

- D1–D30 ferment le modèle métier ;
- D31 ferme l'ownership canonique User/Identity ;
- D32 ferme la frontière de cohérence User/binding et la transaction Registration ;
- D33 ferme le vocabulaire des faits métier du succès ;
- aucune contradiction bloquante avec `domain_audit.md` ne subsiste ;
- les choix restants sont des décisions d'implémentation à traiter dans le futur plan.

REGISTRATION reste un step `current` parce que son implémentation n'a pas commencé. Son canon est
néanmoins suffisamment fermé pour demander ce plan sans laisser l'implémentation définir
implicitement le modèle métier.
