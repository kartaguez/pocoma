# Lot 7.10.1 — Analyse d'impact de la préallocation des identifiants

## 1. Objet et statut du document

Ce document analyse l'impact d'une préallocation des identifiants métier avant l'autorisation des
actions de création `CREATE_EXPENSE` et `ADD_SHAREHOLDER`. Il doit permettre de décider si cette
stratégie est proportionnée au write side actuel de Pocoma.

Ce document n'est ni une décision canonique, ni un plan d'implémentation. Il ne modifie pas les
contrats définis dans [authorization-kernel-contracts.md](authorization-kernel-contracts.md) et ne
préjuge pas de la solution qui sera retenue dans le plan du Lot 7.10.1.

L'analyse porte sur trois niveaux de garantie différents :

1. le même identifiant est utilisé par la cible d'autorisation, la mutation, la persistence et
   l'événement au sein de la tentative gagnante ;
2. le même identifiant est réutilisé par toutes les tentatives d'une même `RecordedCommand` ;
3. l'identifiant est connu dès l'admission asynchrone et peut être retourné au client avant
   l'exécution.

Une génération avancée à l'intérieur du use case garantit le premier niveau, mais pas les deux
suivants.

## 2. Synthèse

Les identifiants métier `PotId`, `ExpenseId` et `ShareholderId` sont déjà des UUID générés côté Java.
Ils ne sont générés ni par JPA, ni par une séquence ou une colonne identity de la base. Les déplacer
légèrement plus tôt est donc techniquement naturel et ne requiert aucune migration de persistence.

Pour `ExpenseId`, la génération est aujourd'hui concentrée dans `ExpenseFactory`. Pour
`ShareholderId`, elle est directement incluse dans `PotShareholders.addShareholder`. Les événements,
les modèles persistés et les snapshots acceptent déjà un identifiant fourni par le domaine. La
préallocation impose principalement de changer ces deux API métier et de transporter une association
explicite entre chaque entrée d'un batch Shareholder et son identifiant prospectif.

La difficulté réelle n'est pas la persistence mais le niveau de déterminisme recherché :

- un UUID aléatoire généré avant l'autorisation, pendant l'exécution, peut changer lors d'un retry ;
- l'architecture transactionnelle actuelle rend ce changement non observable si la première
  tentative n'a pas committé ;
- un simple service de génération aléatoire ne garantit donc pas `same RecordedCommand -> same
  resource ID` ;
- cette garantie plus forte exige de figer l'identifiant dans la commande durable ou de le dériver
  de façon déterministe d'une identité durable telle que `commandId`.

La préallocation pendant le traitement est ainsi **acceptable avec des adaptations d'intrusivité
modérée**. Son bénéfice concret pour les règles actuelles reste toutefois limité : ni
`CREATE_EXPENSE` ni `ADD_SHAREHOLDER` n'utilisent aujourd'hui l'identité du nouvel objet dans leur
règle métier. Elle apporte surtout l'uniformité contractuelle d'un `AuthorizationTarget` complet.

## 3. Ownership actuel de la génération

### 3.1 `PotId`

Le flux actuel est :

```text
CreatePotCommand(label, creatorId)
  -> CreatePotAuthorizationPolicy
  -> PotFactory.createPot(label, creatorId)
  -> PotId.of(UUID.randomUUID())
  -> PotCreated
  -> PotHeader / PotCreatedEvent / PotHeaderSnapshot
```

Sources principales :

- [`CreatePotCommand`](../../app/engine-pot-command/src/main/java/com/kartaguez/pocoma/engine/port/in/command/intent/CreatePotCommand.java)
  ne contient pas de `potId` ;
- [`CreatePotService`](../../app/engine-pot-command/src/main/java/com/kartaguez/pocoma/engine/service/command/CreatePotService.java)
  autorise avant de demander la création ;
- [`PotFactory`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/factory/PotFactory.java)
  possède actuellement la génération par `UUID.randomUUID()` ;
- [`PotCreated`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/created/PotCreated.java)
  reçoit un `PotId` déjà construit.

`CREATE_POT` restant hors du kernel Pot-scoped 7.10.1, aucune préallocation de `PotId` n'est
nécessaire pour résoudre la question immédiate.

### 3.2 `ExpenseId`

Le flux actuel est :

```text
CreateExpenseCommand(potId, payerId, values, shares, expectedVersion)
  -> chargement et validation de CreateExpenseContext
  -> CreateExpenseAuthorizationPolicy
  -> ExpenseFactory.createExpense(...)
  -> ExpenseId.of(UUID.randomUUID())
  -> ExpenseCreated
  -> ExpenseHeader + ExpenseShares
  -> ExpenseCreatedEvent + ExpenseSharesSnapshot
```

Sources principales :

- [`CreateExpenseCommand`](../../app/engine-pot-command/src/main/java/com/kartaguez/pocoma/engine/port/in/command/intent/CreateExpenseCommand.java)
  ne contient pas d'`expenseId` ;
- [`CreateExpenseService`](../../app/engine-pot-command/src/main/java/com/kartaguez/pocoma/engine/service/command/CreateExpenseService.java)
  appelle la factory après les préconditions et l'autorisation ;
- [`ExpenseFactory`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/factory/ExpenseFactory.java)
  génère l'UUID et construit `ExpenseCreated` ;
- [`ExpenseCreated`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/created/ExpenseCreated.java)
  porte ensuite l'identifiant vers les deux agrégats créés.

Ni un repository, ni JPA, ni la base ne choisit l'identifiant métier.

### 3.3 `ShareholderId`

Le flux actuel est :

```text
AddPotShareholdersCommand(potId, Set<ShareholderInput>, expectedVersion)
  -> chargement et validation de AddPotShareholdersContext
  -> AddPotShareholdersAuthorizationPolicy
  -> chargement de PotShareholders
  -> pour chaque input : PotShareholders.addShareholder(...)
  -> ShareholderId.of(UUID.randomUUID())
  -> PotShareholdersAddedEvent + PotShareholdersSnapshot
```

Sources principales :

- [`AddPotShareholdersCommand`](../../app/engine-pot-command/src/main/java/com/kartaguez/pocoma/engine/port/in/command/intent/AddPotShareholdersCommand.java)
  transporte un `Set<ShareholderInput>` dont les éléments ne possèdent pas d'identifiant ;
- [`AddPotShareholdersService`](../../app/engine-pot-command/src/main/java/com/kartaguez/pocoma/engine/service/command/AddPotShareholdersService.java)
  itère sur ces entrées après l'autorisation ;
- [`PotShareholders`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/aggregate/PotShareholders.java)
  génère directement chaque `ShareholderId` dans `addShareholder`.

Il n'existe pas de factory Shareholder séparée ni de générateur d'identifiants métier partagé.

## 4. Compatibilité du domaine avec une préallocation

Les value objects `PotId`, `ExpenseId` et `ShareholderId` encapsulent tous un `UUID` fourni à leur
constructeur ou à leur méthode `of`. Aucun d'eux n'impose l'origine de cet UUID.

Le domaine accepte déjà naturellement des identifiants fournis lors de la reconstitution :

- `ExpenseHeader.reconstitute(ExpenseId, ...)` ;
- `PotShareholders.reconstitute(PotId, Set<Shareholder>)` ;
- le constructeur de `Shareholder` et `Shareholder.reconstitute` reçoivent un `ShareholderId` ;
- `ExpenseCreated` reçoit un `ExpenseId` explicite.

Les signatures qui devraient évoluer si la préallocation est retenue sont limitées au chemin de
création :

```text
ExpenseFactory.createExpense(
    ExpenseId expenseId,
    PotId potId,
    ...)

PotShareholders.addShareholder(
    ShareholderId shareholderId,
    Name name,
    Weight weight,
    UserId userId)
```

Les méthodes devraient valider les IDs par `requireNonNull` et ne jamais en substituer un autre.
Cette évolution retire à la factory et à l'agrégat la responsabilité de choisir une identité, sans
affaiblir leurs invariants métier.

L'intrusion est plus sensible pour `PotShareholders` que pour `ExpenseFactory`, car l'agrégat possède
actuellement à la fois la création de l'entité et la génération de son identité. Elle reste néanmoins
locale : l'entité, les events et les adapters acceptent déjà le `ShareholderId` obtenu.

## 5. Alternatives au niveau des Commands

### 5.1 A — Ajouter l'identifiant à la Command

Cette option inscrit l'identité prospective dans le payload durable :

```text
CreateExpenseCommand(expenseId, ...)
AddPotShareholdersCommand(..., Set<ShareholderInput(shareholderId, ...)>, ...)
```

Avantages :

- toutes les tentatives de la même `RecordedCommand` réutilisent les mêmes IDs ;
- le mapping d'un batch Shareholder est explicite et indépendant de l'ordre ;
- l'identité peut être connue et éventuellement retournée dès l'admission.

Coûts et risques :

- modification des contrats JSON, records, décodeurs et tests ;
- besoin probable de nouveaux types de commande versionnés plutôt qu'une modification silencieuse
  des payloads `*_V1` ;
- si l'ID vient du client, ajout d'une responsabilité de validation et d'une surface de collision ou
  d'abus ;
- si le serveur l'ajoute, il faut enrichir le payload avant sa persistence durable.

Cette solution est robuste pour l'idempotence forte mais plus intrusive que le besoin strict du
kernel.

### 5.2 B — Générer pendant le traitement, avant l'autorisation

Cette option conserve les Commands actuelles :

```text
decode durable command
  -> validate/load preconditions
  -> generate prospective ID
  -> authorize AuthorizationTarget
  -> create with the same ID
```

Avantages :

- pas de changement HTTP, de payload ou de type de commande ;
- déplacement court de la génération déjà effectuée en Java ;
- identité exacte partagée par la décision, la mutation, la persistence et l'event gagnants.

Limite : après un rollback, une nouvelle tentative génère normalement un autre ID. Le service de
génération améliore le placement de la responsabilité et la testabilité, mais ne rend pas la
génération déterministe.

### 5.3 C — Générer à l'admission HTTP ou BFF

L'admission actuelle est volontairement générique :
[`AsyncCommandController`](../../app/supra-http-rest-spring/src/main/java/com/kartaguez/pocoma/supra/http/rest/spring/controller/AsyncCommandController.java)
sérialise un payload opaque et
[`SubmitRecordedCommandService`](../../app/orchestrator-command-admission/src/main/java/com/kartaguez/pocoma/orchestrator/command/admission/SubmitRecordedCommandService.java)
enregistre ce payload avec un `commandId` et un snapshot d'autorisation.

Générer les IDs à ce niveau imposerait soit :

- que le client ou BFF fournisse les IDs ;
- que le controller connaisse les schémas Pot ;
- qu'un registre d'enrichisseurs typés transforme le payload avant insertion de la
  `RecordedCommand`.

Toutes les voies d'admission devraient alors appliquer le même enrichissement. Cette option couple
une frontière aujourd'hui générique au domaine Pot et est disproportionnée si son seul objectif est
de compléter `AuthorizationTarget`.

## 6. Retry, idempotence et déterminisme

### 6.1 Garanties transactionnelles actuelles

[`ExecuteConsumptionService`](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java)
est exécuté sous `TransactionalExecuteConsumptionUseCase`. Dans cette transaction gagnante, il :

1. exécute la `RecordedCommand` ;
2. laisse les ports write-side écrire les états versionnés ;
3. append les événements via `JpaPotCommandEventAppendAdapter`, qui exige une transaction existante ;
4. enregistre inputs et résultats de provenance ;
5. terminalise le consumption slot avec un CAS clôturant le claim.

Une exception technique avant le commit annule donc la mutation, l'outbox, la provenance et la
terminalisation. La policy de failure ne retente que les erreurs classifiées transitoires. Une
tentative ayant committé rend le slot `DONE`; elle n'est pas suivie d'une seconde création réussie.

### 6.2 Génération aléatoire par tentative

Le déroulé suivant ne produit pas de doublon observable :

```text
attempt 1 -> ExpenseId E1 -> ALLOW -> failure -> rollback complet
attempt 2 -> ExpenseId E2 -> ALLOW -> commit complet
```

`E1` n'a été ni persisté, ni publié dans l'outbox, ni exposé comme résultat durable. Seul `E2`
existe. Ce comportement est équivalent au comportement actuel, puisque `ExpenseFactory` et
`PotShareholders` génèrent déjà leurs UUID pendant chaque tentative.

Il ne satisfait cependant pas l'invariant plus fort :

```text
same logical RecordedCommand -> same resource ID on every attempt
```

Ce dernier invariant n'est pas garanti aujourd'hui. Il deviendrait nécessaire si l'identifiant était
exposé avant le commit, utilisé par un effet externe non transactionnel, enregistré dans un audit de
décision avant l'exécution, ou promis contractuellement au client dès l'admission.

### 6.3 Moyens de figer l'identifiant

Deux familles de solutions peuvent fournir l'idempotence forte :

- stocker l'ID dans le payload de la `RecordedCommand` avant son insertion ;
- le dériver de façon déterministe du `commandId` et d'une clé stable de l'objet créé.

La seconde solution demanderait de propager le `commandId` jusqu'aux use cases, ce que les signatures
actuelles ne font pas. Pour un batch Shareholder, elle exigerait en plus une clé stable par entrée :
le `Set<ShareholderInput>` actuel n'a ni identité propre ni ordre contractuel. Une dérivation fondée
sur l'ordre d'itération serait incorrecte.

## 7. Events et identité définitive

Les événements portent déjà les identifiants métier nécessaires :

- [`ExpenseCreatedEvent`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/event/ExpenseCreatedEvent.java)
  contient `expenseId`, `potId` et `version` ;
- [`PotShareholdersAddedEvent`](../../app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/event/PotShareholdersAddedEvent.java)
  contient `potId`, l'ensemble des `shareholderIds` et `version` ;
- `BusinessEventRecordMapper` utilise l'`ExpenseId` comme `aggregateId` pour un événement Expense et
  sérialise les IDs Shareholder dans le payload de l'événement Pot.

La préallocation ne simplifie ni ne complexifie leur schéma. Elle avance uniquement le moment auquel
l'identité prospective est choisie. Dans la tentative gagnante, l'invariant à tester devient :

```text
authorization target ID
= domain creation ID
= persisted business ID
= event business ID
= returned snapshot ID
```

Pocoma reconstruit actuellement ses états métier depuis les lignes versionnées et utilise les
événements typés/outbox comme faits de consommation et de projection. La préallocation ne change ni
l'ordre des événements, ni leur version métier, ni les mécanismes de reconstruction historique.

## 8. Persistence, JPA et base de données

Les entités JPA `JpaPotHeaderEntity`, `JpaExpenseHeaderEntity` et `JpaShareholderEntity` ont deux
niveaux d'identité :

- un `id` technique de ligne versionnée, généré en Java avec `UUID.randomUUID()` ;
- un `potId`, `expenseId` ou `shareholderId` métier fourni par le domaine.

Aucune de ces entités n'utilise `@GeneratedValue`. Le schéma PostgreSQL déclare des colonnes `uuid`
ordinaires, sans séquence ni identity. Les adapters `JpaExpenseHeaderAdapter` et
`JpaPotShareholdersAdapter` transcrivent déjà les IDs du domaine vers les entités.

La préallocation des IDs métier ne nécessite donc :

- aucune migration SQL ;
- aucun changement de type ;
- aucun accès à la base pendant la génération ;
- aucune transaction dédiée ;
- aucun mécanisme de réservation ou de restitution des IDs refusés.

Les UUID aléatoires rendent les collisions extrêmement improbables. Cette stratégie ne crée pas un
nouveau risque de collision par rapport à l'existant. Les tables versionnées n'imposent d'ailleurs
pas une unicité simple du business ID, puisqu'un même objet possède légitimement plusieurs lignes de
versions différentes.

Une migration vers UUID time-based, ULID ou séquence DB n'apporterait rien au besoin étudié. Le format
UUID actuel permet déjà une génération hors persistence, sans coordination.

## 9. Commands et résultat de Command

Les méthodes métier synchrones renvoient déjà des snapshots contenant les identifiants créés :

- `CreateExpenseService` retourne `ExpenseSharesSnapshot.expenseId` ;
- `AddPotShareholdersService` retourne un `PotShareholdersSnapshot` contenant l'ensemble reconstitué.

Cependant, `AbstractPotCommandUseCaseAdapter` utilise ces services à l'intérieur d'une exécution qui
capture les événements et ignore leur valeur de retour. Le résultat durable est ensuite construit par
`ExecuteRecordedCommandService` à partir des événements appendés.

`CommandExecutionArtifact.id` est actuellement l'identifiant technique de l'événement. Pour les
events Pot et Expense, son `subject` est le Pot et sa version. `ConsumptionResult` ne fournit donc pas
directement `createdExpenseId` ou `createdShareholderIds` comme résultats métier typés, même si ces
valeurs existent dans l'enveloppe ou le payload de l'événement.

La préallocation ne nécessite pas de modifier ce modèle. En revanche, si Pocoma veut exposer
explicitement les IDs créés dans le résultat public de la Command, il faudra concevoir séparément :

- une catégorie d'artifact représentant une ressource créée plutôt qu'un événement ; ou
- des métadonnées de résultat métier distinctes des artifacts d'outbox.

Cette évolution toucherait `CommandUseCaseResult`, `RecordedCommandExecutionResult`,
`CommandExecutionArtifact`, la provenance et l'API de consultation. Elle serait disproportionnée si
elle était introduite uniquement pour la préallocation.

## 10. Batch `ADD_SHAREHOLDER`

Une commande peut créer plusieurs Shareholders. Si les IDs sont générés pendant le traitement, le
service doit d'abord matérialiser une collection interne explicite, par exemple conceptuellement :

```text
PlannedShareholderCreation
  shareholderId
  source ShareholderInput
  converted Name / Weight / UserId
```

Le flux sûr est :

```text
Set<ShareholderInput>
  -> un PlannedShareholderCreation par entrée
  -> une AuthorizationTarget(SHAREHOLDER, shareholderId) par plan
  -> toutes les décisions doivent être ALLOW
  -> seulement ensuite, toutes les mutations
  -> event avec l'ensemble exact des IDs créés
```

L'association ne doit jamais dépendre de la position d'une entrée dans le `Set` ni de son ordre
d'itération. Le record planifié conserve directement la paire entrée/ID durant toute la tentative.
Les entrées strictement égales sont déjà dédupliquées par le `Set` de la Command avant cette étape.

Si une autorisation est refusée, aucune mutation ne doit avoir commencé. La commande reste atomique :
un seul refus interdit le batch entier. Les IDs déjà générés sont abandonnés.

Cette association en mémoire suffit pour la garantie de tentative gagnante. Pour garantir les mêmes
IDs au retry, chaque entrée devrait porter durablement son `ShareholderId` dans la Command ou disposer
d'une autre clé stable ; une simple position n'est pas acceptable.

## 11. Scénarios détaillés

### 11.1 `CREATE_EXPENSE` réussi

```text
RecordedCommand durable
  -> décodage de CreateExpenseCommand
  -> chargement de CreateExpenseContext à la version attendue
  -> validation Pot/payer/shares/version
  -> génération de ExpenseId E1
  -> AuthorizationTarget(EXPENSE, E1)
  -> authorization ALLOW
  -> ExpenseFactory construit ExpenseCreated avec E1
  -> ExpenseHeader et ExpenseShares utilisent E1
  -> persistence versionnée avec E1
  -> ExpenseCreatedEvent contient E1
  -> outbox, provenance et slot DONE
  -> commit
```

### 11.2 `CREATE_EXPENSE` refusé

```text
préconditions disponibles
  -> génération de E1
  -> AuthorizationTarget(EXPENSE, E1)
  -> authorization DENY
  -> aucune mutation
  -> aucun business event
  -> résultat Command REJECTED
```

`E1` n'est jamais utilisé. Pour un UUID aléatoire, cet abandon est normal et n'appelle aucun
nettoyage.

### 11.3 `CREATE_EXPENSE` avec retry technique

```text
attempt 1
  -> E1 -> ALLOW -> mutation/écriture
  -> défaillance technique avant commit
  -> rollback complet

attempt 2
  -> E2 -> ALLOW -> mutation/écriture/event
  -> commit et terminalisation
```

Seul `E2` est visible. Si le même ID doit absolument survivre au retry, la variante B n'est pas
suffisante.

### 11.4 `ADD_SHAREHOLDER` multiple

```text
command inputs I1, I2, I3
  -> plans (I1,S1), (I2,S2), (I3,S3)
  -> autorisations sur S1, S2, S3
  -> si une décision DENY : aucune mutation, S1/S2/S3 abandonnés
  -> sinon : addShareholder(S1,...), addShareholder(S2,...), addShareholder(S3,...)
  -> PotShareholdersAddedEvent({S1,S2,S3})
  -> persistence et commit atomiques
```

### 11.5 Échec technique après autorisation

```text
ID généré
  -> ALLOW
  -> échec avant commit
  -> aucune identité publiée durablement
  -> retry selon la failure policy
  -> nouvel ID possible
```

Un effet externe non transactionnel inséré entre `ALLOW` et le commit invaliderait ce raisonnement,
mais aucun effet de cette nature n'existe dans le flux inspecté.

## 12. Architecture conceptuelle d'un générateur

Si la génération pendant le traitement est retenue, deux collaborateurs spécifiques sont suffisants :

```text
ExpenseIdGenerator.generate() -> ExpenseId
ShareholderIdGenerator.generate() -> ShareholderId
```

Le timing de génération appartient à l'orchestration du use case, pas à la persistence. Ces contrats
peuvent donc être possédés par `engine-pot-command` et injectés dans `CreateExpenseService` et
`AddPotShareholdersService`. Une implémentation par défaut peut être fournie par le binding sous forme
de fonctions pures utilisant `UUID.randomUUID()`.

Cette génération :

- n'a pas d'état ;
- ne fait pas d'I/O ;
- n'a pas besoin d'une transaction ;
- ne réserve rien en base ;
- peut être remplacée par un fake déterministe dans les tests.

Un port infrastructure est inutile tant que le générateur reste local et aléatoire. Un service
universel `IdGenerator<T>` ajouterait une abstraction sans sémantique métier et n'est pas justifié.

Il n'est pas non plus nécessaire de migrer `PotFactory` vers un `PotIdGenerator` pour satisfaire le
Lot 7.10.1. Une homogénéisation des trois créations pourrait être étudiée séparément si Pocoma décide
plus tard de fournir des résultats de création stables dès l'admission.

## 13. Changements nécessaires si la solution est retenue

Sans constituer un plan d'implémentation, le périmètre minimal serait :

- introduire les deux générateurs spécifiques et leur wiring ;
- faire accepter un `ExpenseId` à `ExpenseFactory.createExpense` ;
- faire accepter un `ShareholderId` à `PotShareholders.addShareholder` ;
- préallouer les IDs après validation des préconditions mais avant l'autorisation ;
- construire les `AuthorizationTarget` avec ces IDs ;
- conserver les paires entrée/ID des batches jusqu'à la mutation ;
- vérifier par des tests que le même ID traverse cible, domaine, persistence, événement et snapshot ;
- vérifier que tous les IDs sont abandonnés sans mutation sur un refus de batch.

Les Commands, décodeurs, événements, repositories, entités JPA, migrations SQL et modèles de
consumption result n'ont pas besoin de changer pour la variante minimale.

## 14. Risques

1. **Fausse promesse de déterminisme.** Injecter un générateur ne rend pas un UUID aléatoire stable
   entre retries.
2. **Mutation partielle du batch.** Autoriser et muter entrée par entrée pourrait laisser l'agrégat
   modifié avant un refus ultérieur, même si la transaction finit par rollback. Toutes les décisions
   doivent précéder les mutations.
3. **Association par ordre.** Associer des IDs aux éléments d'un `Set` par index rendrait le flux
   fragile et non reproductible.
4. **Duplication de génération.** La factory ou l'agrégat ne doit jamais régénérer un ID déjà fourni.
5. **Extension prématurée du résultat de Command.** Confondre identité de la cible et artifact de
   consommation élargirait fortement le changement.
6. **Généralisation artificielle.** Migrer aussi `CREATE_POT` ou créer `IdGenerator<T>` ne sert pas le
   besoin immédiat.
7. **Bénéfice métier limité.** Les rules de création actuelles ne consultent pas l'identité créée ;
   la modification est essentiellement motivée par la forme uniforme du contrat d'autorisation.

## 15. Évaluation de l'intrusivité

| Couche | Préallocation pendant l'exécution | Motif |
|---|---|---|
| Value objects UUID | Faible | Ils acceptent déjà un UUID externe. |
| Domaine Expense | Faible | Une signature de factory change. |
| Domaine Shareholder | Modérée | L'agrégat cède l'ownership de la génération et le batch doit être planifié. |
| Commands et décodeurs | Nulle | Aucun changement dans la variante B. |
| Events et outbox | Nulle à faible | Structures inchangées, tests de cohérence à renforcer. |
| Persistence/JPA/DB | Nulle | IDs métier déjà fournis par Java, aucun `@GeneratedValue`. |
| Retry transactionnel actuel | Faible | Même sémantique aléatoire par tentative qu'aujourd'hui. |
| Déterminisme inter-retry | Non fourni | Exige une solution durable supplémentaire. |
| Command result public | Nulle | Aucune extension nécessaire, mais l'ID créé n'est pas directement exposé. |

L'intrusivité globale de la variante B est **modérée**. La variante A est modérée à forte et la
variante C forte dans l'architecture d'admission actuelle.

## 16. Recommandation argumentée

Préallouer les IDs immédiatement avant l'autorisation est compatible avec les invariants actuels de
Pocoma et ne force pas la persistence à adopter un nouveau modèle. Le write side choisit déjà ses
identifiants en Java ; la proposition déplace cette décision de quelques lignes et rend possible un
`AuthorizationTarget` complet sans dépendre de l'état futur muté.

La solution est donc acceptable si l'invariant recherché est :

> au sein de la tentative qui commit, l'ID autorisé est exactement l'ID créé, persisté et publié.

Elle ne doit pas être présentée comme garantissant :

> une même `RecordedCommand` utilise le même resource ID dans toutes ses tentatives.

Si cette seconde garantie, un audit durable de la cible refusée ou la restitution de l'ID dès
l'admission devient nécessaire, il faudra figer l'identité dans la commande durable. Ce besoin serait
une décision write-side plus large, avec versionnement des payloads et traitement spécifique des
batches ; il ne devrait pas être introduit implicitement par le Lot 7.10.1.

Enfin, l'identité de la cible n'influence actuellement aucune des deux règles de création :
`CREATE_EXPENSE` repose sur les relations au Pot et `ADD_SHAREHOLDER` reste réservé au créateur. La
préallocation améliore donc surtout la cohérence du contrat, pas le résultat métier de la policy. Si
son coût était jugé excessif, ce constat justifierait de réexaminer séparément la nécessité d'un
`targetId` pour une cible de création prospective plutôt que de déplacer l'identité jusqu'au BFF ou
de modifier le modèle durable des Commands uniquement pour satisfaire la forme du kernel.

