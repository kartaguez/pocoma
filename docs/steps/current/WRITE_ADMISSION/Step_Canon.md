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
un User existant capture aussi le `BindingId` présenté par le client.

## 3. Command et occurrence de binding

### WA5 — Sémantique

> Une Command signifie : « Moi, ExternalIdentity E, agissant sous l'occurrence de binding B que je
> connais, je demande l'opération X. »

Une Command durable contient conceptuellement `ExternalIdentity E`, `BindingId B`, le payload et
l'évidence d'authentification nécessaire. L'admission ne vérifie ni l'existence ni l'actualité de B.
Au traitement, le worker consulte l'autorité User/Identity primaire. La Command n'est attribuée à un
`PocomaUserId` que si `(E,B)` correspond exactement au binding actuellement actif.

### WA6 — `BindingId`

> `BindingId` identifie une occurrence précise de rattachement
> `ExternalIdentity -> PocomaUserId`.

Il est opaque, unique, non ordinal, non réutilisable et sans sémantique temporelle ; seule l'égalité
compte. Le type canonique peut être un value object autour d'un UUID, conformément aux conventions
du repository. Chaque Attach produit un nouveau `BindingId`, y compris après detach/reattach vers le
même User.

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
> rester autoritativement courante pendant toute la transition métier de la Command.

La résolution `(E,B) -> PocomaUserId` et la mutation métier appartiennent donc à une frontière
transactionnelle empêchant Attach ou Detach concurrent de rendre B obsolète entre validation et
commit. Row lock, conditional write ou autre primitive PostgreSQL relèvent du plan ; le présent
canon impose l'invariant, pas la primitive.

La chaîne Command canonique est :

```text
HTTP -> AuthN -> E -> B présenté -> validation structurelle
     -> RecordedCommand(E,B,payload,authenticationEvidence) -> 202

worker fenced -> reload -> résolution autoritative (E,B) -> U
              -> capabilities/AuthZ -> état métier courant -> mutation ou rejet
```

## 4. Modèle User/Identity et lifecycle

### WA9 — READ self-service du binding courant

Une vue READ permet à l'ExternalIdentity authentifiée de retrouver son binding courant :

```text
authenticated ExternalIdentity E
  -> READ projection
  -> PocomaUserId U + BindingId B
```

Elle est strictement self-service : E vient de l'AuthN et aucun caller ne recherche une
`ExternalIdentity` arbitraire. Elle ne lit jamais le primaire et accepte la cohérence éventuelle de
READ. `AUTH(Pot,V)` ne porte pas cette donnée. En l'absence de projection adaptée, une projection
User/Identity minimale est requise ; son nom et son layout restent des choix de plan.

Le worker Command ne consomme jamais cette projection : son contrôle `(E,B) -> U` reste
autoritatif sur le primaire.

### WA10 — Lifecycle de l'occurrence

```text
Registration réussie -> User U -> Binding(E,U,B) -> Registered(U,B)
Attach réussi         -> Binding(E,U,Bn) -> résultat contenant Bn
Detach                 -> invalide ou supprime l'occurrence courante
```

Tout attach ultérieur produit un nouveau `BindingId`. Un B n'est jamais réutilisé. Aucun historique
des bindings n'est requis uniquement pour satisfaire cet invariant.

`BindingId` appartient au domaine User/Identity. Le fait de création d'occurrence est :

```text
ExternalIdentityAttached(E,U,B)
```

Un futur fait Detach identifie lui aussi l'occurrence B invalidée, afin qu'un fait stale ne puisse
pas supprimer une occurrence plus récente. Ces faits permettent de construire la projection
self-service sans relire le primaire pour découvrir B.

## 5. Enveloppe durable et résultat Command

Le contrat conceptuel est :

```text
RecordedCommand
  commandId
  commandType
  payload
  submittedAt
  ExternalIdentity
  BindingId
  authenticationEvidence
```

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
| WA4 | §2 capture durable de E et B, jamais U résolu |
| WA5 | §3 sémantique de la Command sous occurrence B |
| WA6 | §3 identité et propriétés de `BindingId` |
| WA7 | §3 validation structurelle seulement |
| WA8 | §3 rejet unique non-oracle |
| WA9 | §4 projection READ self-service |
| WA10 | §4 lifecycle Registration/Attach/Detach |
| WA11 | §3 continuité transactionnelle jusqu'au commit métier |

## 7. Verdict

**FRAMING CLOSED**

Les choix de schéma, ports, locks, migration des Commands historiques, représentation durable des
capabilities et layout des projections appartiennent au futur plan d'implémentation. Aucun de ces
choix ne peut rouvrir ou affaiblir WA1–WA11.
