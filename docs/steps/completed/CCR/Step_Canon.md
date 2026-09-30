# CCR — Command Completion Result

Ce document fixe le modèle canonique de résolution terminale d'une Command et son exposition par
la chaîne de projection existante. Il complète, sans les remplacer, les canons
[`EPT`](../EPT/Step_Canon.md) et [`PCL`](../PCL/Step_Canon.md).

## 1. Purpose

Une Command acceptée est traitée par le moteur générique de Consumption. CCR rend son résultat
fonctionnel terminal durable et observable côté READ sans recopier le lifecycle technique de la
Consumption sur `recorded_commands`.

```text
recorded Command
    ↓
Consumption(COMMAND × COMMAND_EXECUTOR)
    ↓
CommandOutcome + Command terminal Event
    ↓
Consumption(EVENT × PROJECTION_TASK_MATERIALIZER)
    ↓
ProjectionTask(COMMAND_RESULT, COMMAND, commandId, 1)
    ↓
Consumption(PROJECTION_TASK × PROJECTION_EXECUTOR)
    ↓
COMMAND_RESULT
```

## 2. Authority and cardinality

`command_outcomes` est la source autoritaire du résultat fonctionnel terminal. Pour tout
`commandId` :

```text
0 outcome  = non résolue, en cours ou retryable
1 outcome  = terminalement résolue
>1         = interdit par la clé primaire
```

Consumption reste l'autorité exclusive pour les Claims, leases, takeover, retry,
`next_claim_at`, diagnostics et états techniques. `consumption_results` reste une provenance des
effets et n'est pas un contrat de résultat Command.

Les issues terminales sont :

- `APPLIED` : `commandId`, `potId`, `resultingVersion`, `resolvedAt` ;
- `REJECTED` : `commandId`, code métier stable, `resolvedAt` ;
- `FAILED` : `commandId`, code public opaque `COMMAND_PROCESSING_FAILED`, `resolvedAt`.

Le résultat public cible volontairement le Pot. Il ne promet pas l'identité d'une sous-entité
éventuellement créée ou modifiée.

## 3. Resulting version

Chaque Command métier réussie retourne explicitement un `CommandAppliedResult`. La version ne se
déduit ni du nombre ni de l'ordre des Events. Les adapters Pot la construisent depuis le snapshot
versionné effectivement produit par le use case (`PotHeaderSnapshot`, `PotShareholdersSnapshot`,
`ExpenseHeaderSnapshot` ou `ExpenseSharesSnapshot`).

## 4. Atomicity and fencing

Pour `APPLIED` et `REJECTED`, l'outcome, le terminal Event et la terminalisation Consumption sont
écrits dans la transaction fenced de `ExecuteConsumptionService`. Les mutations métier et Events
métier du succès appartiennent à cette même transaction.

Pour `FAILED`, la transaction Execute est rollbackée. `HandleConsumptionFailureService` n'exécute
l'effet terminal Command que lorsque la policy choisit `Fail`; il réutilise alors
`FinalizeConsumptionService` pour verrouiller le slot, vérifier le Claim courant, publier outcome
et Event, puis terminaliser dans une transaction courte unique.

Une décision `RetryAfter` ne publie ni outcome ni terminal Event. Un Claim stale ne peut publier
aucun résultat terminal.

## 5. Command terminal Events

Trois types explicites existent :

```text
COMMAND_APPLIED
COMMAND_REJECTED
COMMAND_FAILED
```

Ils signalent uniquement qu'un résultat terminal durable est disponible. Leur envelope contient
l'identité `commandId`, l'identité technique de l'Event, l'instant, la partition et la trace ; les
données du résultat restent dans `command_outcomes`.

Ces Events ne sont pas des `BusinessEvent` Pot. Ils vivent dans `command_terminal_events`, table
dédiée liée à `command_outcomes`, parce que `business_event_outbox` porte un contrat Pot
(`potId`, aggregate, version et payload métier). La discovery EPT unifie les deux sources au niveau
de l'envelope metadata-only ; elle ne les force pas dans une table ou une famille métier commune.

Un unique terminal Event est permis par Command.

## 6. COMMAND_RESULT

Les trois terminal Events routent vers `COMMAND_RESULT`. La clé est exactement :

```text
ProjectionKey(
    projectionType   = COMMAND_RESULT,
    targetObjectType = COMMAND,
    targetObjectId   = commandId,
    targetVersion    = 1
)
```

La version `1` exprime une résolution terminale unique et immutable ; ce n'est pas une version
Pot. Le loader relit l'outcome autoritaire et l'ownership externe attesté capturé dans la source
durable de la Command. Il ne dépend pas d'un `auth_user_id` résolu à l'admission. Le projector publie
un artefact unique validé contenant `commandId`, une clé d'ownership READ non ambiguë, l'issue, les
données publiques de l'issue et `resolvedAt`. La structure exacte de cette clé reste un choix de
plan, mais elle provient de l'`ExternalIdentity` authentifiée capturée.

## 7. Minimal READ contract

Le seul contrat HTTP introduit est :

```text
GET /api/v1/command-results/{commandId}
```

- `200` + `APPLIED`, `REJECTED` ou `FAILED` uniquement si la projection exacte est `READY`, son
  payload est valide et son ownership externe correspond au caller authentifié ;
- `404` lorsqu'aucune ressource READ `COMMAND_RESULT` n'est visible pour le caller, quelle qu'en
  soit la cause : projection pas encore matérialisée, identité inconnue, ownership différent ou
  projection terminalement échouée sans résultat `READY`.

Le `404` ne signifie pas que la Command n'a jamais existé côté WRITE. Il signifie qu'aucune
ressource READ visible n'existe actuellement sous cette identité. Le READ ne cherche pas à
distinguer absence, non-readiness et invisibilité d'ownership tant que la projection finale n'est
pas `READY`. Il ne consulte pour cela ni `recorded_commands`, ni Consumption, ni un index
d'ownership intermédiaire.

Le GET ne résout jamais l'`ExternalIdentity` vers un User sur le primaire WRITE. Toute donnée
nécessaire à l'ownership est dans READ. Le contrat ne révèle ni `BindingId` courant, ni autre User,
ni historique de binding et conserve le même masquage non-oracle.

Ce contrat n'introduit ni façade Query générale, ni résolution latest/current, ni lecture directe
du write side. Les détails internes de Consumption ne sont jamais exposés.

## 8. Explicit non-goals

- aucun nouveau Command Engine ;
- aucun status lifecycle sur `recorded_commands` ;
- aucun Event générique de Consumption ;
- aucun résultat complet copié dans l'Event trigger ;
- aucune fusion des modes Execute et Finalize ;
- aucune Query façade générale ;
- aucun statut public `NOT_READY` ou `PROJECTION_FAILED` pour `COMMAND_RESULT` ;
- aucune suppression de chemin WRITE de consultation qui n'existe pas dans le repository courant.
