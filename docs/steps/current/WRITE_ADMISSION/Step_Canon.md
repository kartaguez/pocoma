# WRITE_ADMISSION — Canon architectural

```text
Step: WRITE_ADMISSION
Phase: FRAMING CLOSED
Authority: WA1–WA11
```

Ce document est l'autorité normative de WRITE_ADMISSION. Il fixe les frontières entre admission
HTTP, traitement WRITE autoritatif et READ. Il ne constitue ni un plan d'implémentation, ni un
schéma Java, SQL ou HTTP détaillé. L'[audit](step_audit.md) reste une preuve factuelle et historique.

## 1. Frontière générale WRITE / READ

```text
HTTP WRITE
  AuthN -> validation structurelle -> capture durable de l'intention -> 202
                                      |
                                      v
                               WRITE PRIMARY
                                      ^
                                      | workers métier autoritatifs
                                      v
                               faits / outcomes
                                      |
                                      v
                             projection workers
                                      |
                                      v
                                    READ
                                      |
                                      v
                                  HTTP READ
```

Le chemin HTTP WRITE n'effectue aucune lecture de l'état métier primaire Pocoma, aucune AuthZ
métier et aucune lecture de READ pour décider de l'admission. Les workers métier lisent l'autorité
primaire, résolvent l'identité métier, évaluent les capabilities et l'AuthZ, contrôlent les
invariants, mutent l'état et produisent outcomes et faits. Ils sont la frontière autoritative des
décisions métier WRITE.

Les projection workers construisent READ à partir des faits, outcomes et lectures autoritatives
explicitement prévues par leur contrat. Ils ne font pas partie du chemin utilisateur synchrone.

Un endpoint HTTP READ construit sa réponse exclusivement à partir de READ et des projections. Il ne
lit jamais le primaire WRITE pour compléter, autoriser ou construire sa réponse. L'AuthN externe
reste autorisée. Toute implémentation actuelle qui résout une identité ou un ownership sur le
primaire depuis un controller READ est une dette de migration, pas une exception au canon.

## 2. Admission d'une intention WRITE

### WA1 — AuthN et capture, sans décision métier

> Une admission HTTP WRITE synchrone authentifie l'appelant, valide la structure de l'intention et
> la capture durablement. Elle ne détermine ni l'identité métier Pocoma de l'appelant, ni ses droits
> métier, ni la validité métier de l'intention.

Elle peut valider l'AuthN, extraire l'`ExternalIdentity` attestée, vérifier la forme et les limites
techniques de la requête, écrire durablement l'intention et retourner son acceptation technique.
Elle ne résout pas `ExternalIdentity -> PocomaUserId`, ne lit aucun Pot, User ou Identity métier
pour accepter, n'effectue aucune AuthZ métier, ne vérifie aucun invariant dépendant de l'état
primaire et ne consulte pas READ pour décider.

L'admission peut capturer l'évidence d'authentification attestée nécessaire au traitement futur,
mais ne produit pas une décision d'AuthZ métier. Le canon ne fixe ni sa représentation technique
détaillée, ni le stockage du JWT brut, qui est interdit. La représentation durable minimale,
provider-neutral si approprié, relève du futur plan.

### WA2 — Zéro lecture primaire à l'admission

> Aucun chemin HTTP WRITE synchrone ne lit l'état métier primaire Pocoma pour décider de
> l'admission d'une intention.

L'écriture durable d'un `RecordedCommand`, d'une `RegistrationRequest` ou d'une future famille
d'intentions reste évidemment autorisée. La charge utilisateur synchrone ne provoque aucune lecture
de l'autorité métier WRITE.

### WA3 — Admission ouverte

> Toute `ExternalIdentity` valablement authentifiée par un provider accepté peut déposer une
> intention WRITE structurellement valide.

Être déjà un `PocomaUser` n'est pas une condition d'admission technique. Une Command issue d'une
identité inconnue peut recevoir `202`, puis être rejetée fonctionnellement au traitement. Taille,
rate limiting, quotas et protections anti-abus sont des limites techniques distinctes de l'AuthZ
métier.

### WA4 — Identité durable capturée

Une intention WRITE capture l'identité attestée exacte :

```text
ExternalIdentity {
  issuer
  subject
}
```

Elle ne capture pas un `PocomaUserId` obtenu par lecture primaire synchrone. Une Command nécessitant
un User existant capture aussi le `BindingId` présenté par le client, jamais la
`BindingRevision`. Pour WRITE_ADMISSION actuel, l'acteur authentifié et le sujet effectif sont la
même personne : `actor == subject`. L'`ExternalIdentity E` capturée est celle de l'acteur qui agit
pour son propre binding Pocoma.

## 3. Command et occurrence de binding

### WA5 — Sémantique

> Une Command signifie : « Moi, ExternalIdentity E, agissant sous l'occurrence de binding B que je
> connais, je demande l'opération X. »

Une Command durable contient conceptuellement `ExternalIdentity E`, `BindingId B`, le payload et
l'évidence d'authentification nécessaire. L'admission ne vérifie ni l'existence ni l'actualité de B.
Au traitement, le worker consulte l'autorité User/Identity primaire. La Command n'est attribuée à un
`PocomaUserId` que si `(E,B)` correspond exactement au binding actuellement actif. B est le fence
logique de la Command ; `BindingRevision` n'est ni portée par la Command, ni fournie par le client
comme précondition métier de son exécution.

### WA6 — `BindingId`

> `BindingId` identifie une occurrence précise de rattachement
> `ExternalIdentity -> PocomaUserId`.

Il est opaque, unique, non ordinal, non réutilisable et sans sémantique temporelle ; seule l'égalité
compte. Le type canonique peut être un value object autour d'un UUID, conformément aux conventions
du repository. Chaque Attach produit un nouveau `BindingId`, y compris après detach/reattach vers le
même User.

`BindingRevision R` est distinct de B : c'est le numéro strictement monotone des changements de
binding pour une `ExternalIdentity E`. R appartient conceptuellement à E, pas à une occurrence B.
Chaque mutation autoritative de binding (attach, detach, reattach) incrémente R et prolonge sans
rupture l'ordre logique de l'histoire de E. R ordonne les faits et permet la convergence des
projections ; il n'identifie pas une occurrence et ne sert pas de fence à une Command. Le type SQL
du compteur et sa stratégie d'allocation ne sont pas fixés ici.

Si un contrat de transport emploie le terme « binding token », ce terme désigne une représentation
de l'occurrence B. Il n'introduit aucune identité métier distincte de `BindingId`.

`BindingId` ne représente jamais une délégation, une impersonation, un droit de supervision ou un
« agir au nom de ». Une future opération où `actor != effectiveSubject` devra porter un contexte
explicite d'exécution, par exemple `actor`, `effectiveSubject` et `delegationEvidence`, sans changer
le sens de `ExternalIdentity`, `BindingId` ou `BindingRevision`. Ce contexte n'appartient pas au
contrat WRITE_ADMISSION actuel.

### WA7 — Forme, jamais vérité métier

Une intention nécessitant B doit présenter un `BindingId` syntaxiquement valide : absent ou mal
formé, il provoque un rejet HTTP structurel sans intention durable. Bien formé mais faux, ancien ou
inexistant, il est durablement admis avec `202` et décidé autoritativement au worker.

> L'admission vérifie la forme d'une intention, jamais sa vérité métier.

### WA8 — Rejet fonctionnel non-oracle

Un mismatch `(ExternalIdentity, BindingId)` produit un unique rejet fonctionnel public :

```text
CALLER_IDENTITY_NOT_CURRENT
```

Ce code couvre indistinctement identité jamais enregistrée, absence de binding, ancien B, B
inexistant, detach et reattach vers le même ou un autre User. Aucun autre `BindingId`, aucun
`PocomaUserId` et aucun historique n'est révélé.

### WA11 — Continuité transactionnelle

> Lorsqu'une Command est exécutée sous `(ExternalIdentity E, BindingId B)`, l'occurrence B doit
> rester autoritativement courante pendant toute la transition métier de la Command, jusqu'au
> commit métier.

La résolution `(E,B) -> PocomaUserId` et la mutation métier appartiennent donc à une frontière
transactionnelle empêchant Attach ou Detach concurrent de rendre B obsolète entre validation et
commit. Le fence logique reste l'occurrence `(E,B)`, jamais la `BindingRevision R`. Le présent canon
impose cet invariant métier et transactionnel, sans imposer `SELECT ... FOR UPDATE`, row lock, CAS,
advisory lock ou autre primitive d'implémentation.

La chaîne Command canonique est :

```text
HTTP -> AuthN -> E -> B présenté -> validation structurelle
     -> RecordedCommand(E,B,payload,authenticationEvidence) -> 202

worker fenced -> reload -> résolution autoritative (E,B) -> U
              -> capabilities/AuthZ -> état métier courant -> mutation ou rejet
              -> maintien de B courant jusqu'au commit métier
```

## 4. Modèle User/Identity et lifecycle

### WA9 — READ self-service du binding courant

`CURRENT_BINDING` est la projection READ self-service mutable, indexée par `ExternalIdentity E`,
qui permet à l'identité authentifiée de connaître son état courant projeté :

```text
CURRENT_BINDING(E)
  externalIdentity = E
  bindingRevision = R
  state = ATTACHED | DETACHED

  si ATTACHED : userId = U, bindingId = B
  si DETACHED : userId = null, bindingId = null
```

Elle possède au plus une valeur courante par E, est reconstruisible depuis les faits canoniques et
conserve la dernière R lorsque E est détachée. Une projection E avec `state=DETACHED` signifie que E
a une histoire de binding mais aucun binding courant ; l'absence de projection pour E signifie
qu'aucune connaissance projetée n'est disponible pour E. Un detach ne supprime donc pas
conceptuellement E de `CURRENT_BINDING`.

La vue est strictement self-service : E vient de l'AuthN et aucun caller ne recherche une
`ExternalIdentity` arbitraire. L'endpoint READ ne lit jamais le primaire et accepte la cohérence
éventuelle de READ. `AUTH(Pot,V)` ne porte pas cette donnée. Le statut HTTP exact exposé au client
en cas de detach (par exemple `200`, `204` ou `404`) reste un choix d'implémentation ultérieur.

Le worker WRITE ne consomme jamais `CURRENT_BINDING` pour décider d'exécuter une Command : son
contrôle `(E,B) -> U` utilise l'autorité primaire WRITE.

### WA10 — Lifecycle de l'occurrence

L'autorité WRITE possède conceptuellement un état courant par `ExternalIdentity E`, y compris
lorsqu'elle est détachée :

```text
BINDING_AUTHORITY(E)
  bindingRevision = R
  state = ATTACHED | DETACHED

  si ATTACHED : userId = U, bindingId = B
  si DETACHED : aucun binding courant
```

Cette forme est conceptuelle et n'impose pas une table unique. Après detach, l'autorité conserve
l'état `DETACHED` et suffisamment d'information sur la dernière R pour que la prochaine mutation
produise la révision suivante. L'absence d'une row de binding actif ne décrit donc pas, à elle
seule, toute la vérité métier de E.

Le lifecycle conceptuel d'une même E peut être :

```text
R1 ATTACHED(E,U1,B1)
R2 DETACHED(E,U1,B1)
R3 ATTACHED(E,U1,B2)
R4 DETACHED(E,U1,B2)
R5 ATTACHED(E,U2,B3)
```

Une Registration réussie crée User U puis attache E à U sous un nouveau B et produit
`Registered(U,B)` ; un Attach ou Reattach réussi crée également un nouveau B, y compris vers le
même U, et retourne ce B. Un Detach invalide le B courant. Chaque mutation autoritative fait
avancer la révision propre à E. Un B n'est jamais réutilisé.

Le lifecycle produit une histoire durable de faits canoniques append-only, conceptuellement :

```text
ExternalIdentityAttached(E,U,B,R)
ExternalIdentityDetached(E,U,B,R)
```

Chaque fait porte E et R ; le detach identifie l'occurrence B invalidée. Pour un même E, R définit
l'ordre de l'histoire et un fait stale ne peut ni supprimer ni remplacer une occurrence plus
récente. Les faits sont immuables ; `CURRENT_BINDING` est leur projection READ mutable et
reconstruisible. B et R ne sont jamais interchangeables. Les noms Java et le schéma SQL des faits
ne sont pas fixés ici.

Les responsabilités sont distinctes :

| Élément | Rôle |
|---|---|
| Binding authority WRITE | Vérité courante autoritative, utilisée pour résoudre et fencer les Commands. |
| Binding facts | Histoire durable append-only de E, ordonnée par R. |
| `CURRENT_BINDING` | Vue courante READ mutable, reconstruisible depuis les faits. |

## 5. Enveloppe durable et résultat Command

Le contrat conceptuel est :

```text
RecordedCommand
  commandId
  commandType
  payload
  submittedAt
  ExternalIdentity E
  BindingId B
  authenticationEvidence
```

La Command ne porte pas `BindingRevision R`. B identifie déjà l'occurrence exacte revendiquée ;
tout detach/reattach crée un nouveau B. Une ancienne Command sous B1 reste invalide après création
de B2, même si E est rattachée au même U. R ordonne l'histoire du binding et la convergence de
`CURRENT_BINDING` ; elle ne détermine pas directement la validité d'une Command.

`PocomaUserId` n'est pas une identité résolue à l'admission. L'admission ne traduit pas les
`scope/scp` en décision d'AuthZ ; elle capture seulement l'évidence attestée requise. Le worker
interprète les capabilities et les relations métier courantes au moment autoritatif.

La visibilité de `COMMAND_RESULT` est déterminée côté READ sans lookup primaire. La source durable
de l'ownership est l'`ExternalIdentity` authentifiée capturée à l'admission, ou une clé READ dérivée
sans ambiguïté. Un `GET` ne résout jamais E sur le primaire. Le contrat reste non-oracle et n'expose
ni binding courant, ni autre User, ni historique. La structure exacte de la projection relève du
plan.

## 6. Traçabilité WA1–WA11

| Décision | Couverture |
|---|---|
| WA1 | §2 AuthN, capture et absence de décision métier |
| WA2 | §1 et §2 zéro lecture primaire HTTP WRITE |
| WA3 | §2 admission ouverte à toute E authentifiée |
| WA4 | §2 capture durable de E et B, jamais U résolu ni R ; actor == subject |
| WA5 | §3 sémantique de la Command sous occurrence B, validée sur l'autorité WRITE |
| WA6 | §3 identité et propriétés de B, ordre propre à E porté par R, sans troisième identité « token » |
| WA7 | §3 validation structurelle seulement |
| WA8 | §3 rejet unique non-oracle |
| WA9 | §4 `CURRENT_BINDING(E)` mutable, avec état `DETACHED` et dernière R conservée |
| WA10 | §4 autorité WRITE, faits append-only et lifecycle Registration/Attach/Detach ordonné par R |
| WA11 | §3 fence logique `(E,B)` maintenu jusqu'au commit, sans primitive imposée |

## 7. Verdict

**FRAMING CLOSED**

Le cadrage Binding / Command / fencing est fermé : B identifie l'occurrence et fence la Command ;
R ordonne les mutations de E et les faits ; l'autorité WRITE conserve l'état détaché et la dernière
révision ; `CURRENT_BINDING` est la projection READ mutable. Aucune question architecturale ne
reste ouverte sur ces responsabilités.

Les choix de schéma, ports, locks, migration des Commands historiques, représentation durable des
capabilities et layout des projections appartiennent au futur plan d'implémentation. Aucun de ces
choix ne peut rouvrir ou affaiblir WA1–WA11.
