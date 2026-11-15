# Étape 3D — conformité des producteurs d’Events au contrat terminal E

Date : 2026-10-19. Baseline exclusive : `510521be0dda92c928696d9bd1ed888bc30b81c1`, branche `feat/consumption-transaction-protocol`.

**AUDIT DOCUMENTAIRE de CURRENT ; recommandations TARGET non implémentées. Verdict : READY WITH LIMITED CHANGES.** Ce verdict porte sur l’applicabilité de R1–R5 aux producteurs identifiés. Il ne constitue pas une autorisation d’intégrer E en production. Aucun runtime, test, schéma, migration ou POM n’est modifié ; aucune preuve d’exécution nouvelle n’est revendiquée.

## 1. Conclusion et limites

**A — Oui, le contrat est raisonnable.** Les parcours de production identifiés finissent leurs effets dans leurs propriétaires physiques existants. Les quatre familles convergent vers six adaptateurs et un repository JDBC de faits Binding commun. Aucun besoin identifié de réécrire les services métier, Consumption, P/B/S, les transactions propriétaires ou les retries. Aucun contre-exemple CURRENT de fractionnement d’une opération de production entre commits n’a été trouvé.

**B — Des changements locaux sont BLOCKING avant activation de E :** rendre obligatoire le contexte transactionnel contrôlé de `UserCreatedFact`, fermer l’accès non contrôlé aux points bas de persistance, puis assurer l’enregistrement des UUID exacts sur tous les writers et le refus de publication après fermeture. Le contrat explicite d’absence de savepoints et de travail métier tardif doit être adopté et vérifié. Les deux dernières obligations sont principalement des conditions de la future intégration, pas des bugs de Consumption constatés aujourd’hui. Détails et proportionnalité au §7.

**C — L’infrastructure doit garantir** la barrière complète de flush, la fermeture du lot, le suffixe exécuté une seule fois au commit physique après les callbacks pré-commit, l’ordre des familles, l’atomicité et le nettoyage du collecteur. La discipline des producteurs suffit à interdire du nouveau métier après cette barrière sur les chemins contrôlés ; un flush isolé ne garantit jamais, à lui seul, la fermeture d’Hibernate. Aucune protection universelle contre du SQL arbitraire n’est requise ou annoncée.

Conventions : **CURRENT comportemental** = déduction statique du chemin et du wiring présents ; **garantie architecturale** = prévention des usages futurs par restrictions explicites, gardes et contrôles ciblés ; **TARGET** = mécanisme à construire. `SATISFIED` est borné au chemin nommé, sans promesse universelle. `CONDITIONAL` identifie une dépendance réelle au propriétaire/à la discipline future. `VIOLATED` nécessiterait un contre-exemple courant identifié ; `INCONCLUSIVE` une preuve insuffisante. L’absence de collecteur ou de E aujourd’hui n’est pas classée comme violation.

Références historiques : [3A](Event_Terminal_Sequencing_Step_3A_Audit_2026-10-19.md), [3B](Event_Terminal_Sequencing_Step_3B_PostgreSQL_Proof_2026-10-19.md), [3C](Event_Terminal_Sequencing_Step_3C_Transactional_Integration_2026-10-19.md), [2A](Pot_Command_Transaction_Boundaries_Audit_Step_2A_2026-10-18.md), [2B](Pot_Command_Cas_Consumption_Step_2B_2026-10-18.md), [2C intégré](Pot_Command_Binding_Shared_Fence_Integration_Step_2C_2026-10-18.md), [2C PostgreSQL](Pot_Command_Binding_Shared_Fence_PostgreSQL_Proof_Step_2C_2026-10-18.md). Les corrections 2B et le fence partagé 2C sont CURRENT ; les anciens catchs et UPDATE no-op décrits en 2A ne le sont plus. Le prototype 3C reste une expérience : collecte JDBC incomplète et contre-exemple Hibernate conservés comme limites, pas comme défauts runtime livrés.

## 2. Baseline, sécurité et méthode

Le worktree cible préexistant `/private/tmp/pocoma-consumption-transaction-protocol` est propre au départ. HEAD, tracking `origin/feat/consumption-transaction-protocol` et référence distante vérifiée par `git ls-remote` valent tous le SHA attendu ; divergence initiale `0/0`. L’accès réseau initial sandbox a échoué en résolution DNS ; la même lecture distante autorisée hors sandbox a réussi. Aucun reset, stash, merge, rebase ou checkout.

| Autre worktree inventorié | HEAD initial | Protection |
|---|---|---|
| checkout principal `/Users/julien.guezennec/Dev/projects/pocoma` | `90926a5e`, `feat/runtime-monolith` | Keycloak, docs/README, Bruno, test Expense et documents/preuves non suivis préexistants laissés intacts |
| `/private/tmp/pocoma-2c-differential-baseline` | `8a79f06d`, détaché | propre, aucune écriture |
| `/private/tmp/pocoma-binding-lock-order-guard` | `2eef3ab9` | propre, aucune écriture |
| `/private/tmp/pocoma-expense-context` | `116f4e4a` | propre, aucune écriture |
| `/Users/julien.guezennec/.codex/worktrees/debt-wa6-integration/pocoma` | `2eef3ab9`, détaché | propre, aucune écriture |

Règles lues : [AGENTS](../../AGENTS.md), [politique normative de vérification](../testing/Reactor_Verification_Policy.md), [contribution](../../CONTRIBUTING.md), [index documentaire](../README.md), [gouvernance documentaire §15](../Documentation_Rationalization_Audit.md#15-gouvernance-documentaire), [validation de vérité](../Documentation_Truth_Validation.md). Sources CURRENT : [Architecture](Architecture.md), [System Guarantees](../guarantees/System_Guarantees.md), [protocole Consumption](consumption-transactional-execution.md). Cette étude ne remplace ni ne modifie ces sources normatives.

Périmètre déclaré avant écriture : **audit statique/documentaire**, slices Maven primaire/secondaires **NONE**, base **NONE**, gate architecture global **NOT REQUIRED**, full reactor **NOT REQUIRED et non exécuté**. Surfaces analysées : COMMAND, Registration et autorités Binding, infrastructure partagée ; aucun comportement de ces capacités n’est modifié. Impact interdit : tout fichier sous `app/`, tests, migrations, POM et runtime. Aucun franchissement inattendu de slice.

Méthode : inventaire `rg --files`, recherches dans tous les fichiers `app/**/src/main` (exclusion explicite de `src/test` et `target`), remontée des appels des writers, lecture des propriétaires et finalisations, recherche séparée migrations/scripts/runbooks. Recherches positives par tables, entité outbox, ports de publication et repositories ; recherches négatives des propagations, savepoints, commits, connexions, callbacks et listeners. Les absences ci-dessous portent sur ces sources versionnées, pas sur des binaires ou extensions externes de déploiement. Les expériences 3B/3C et tests existants sont cités sans être relancés.

## 3. Inventaire exhaustif des producteurs identifiés

### 3.1 BUSINESS_EVENT : dix services, deux chemins de persistance

Les services de `engine-write-pot` publient dans `EventPublisherPort`. Dans le chemin Command, celui-ci est `PotCommandInvocation` : simple liste mémoire, pas un INSERT. Le dispatch/factory produit un snapshot et la liste ; `ExecuteRecordedCommandService.execute` appelle ensuite `EventAppendPort.appendAll`, puis le fence Binding. `CommandConsumptionExecution` publie enfin l’outcome et son TerminalEvent avant la finalisation S/Claim [C1–C5,C34].

| Service / méthode | Event publié | Cardinalité sur succès courant |
|---|---|---|
| `CreatePotService.createPot` | `PotCreatedEvent` | 1 |
| `DeletePotService.deletePot` | `PotDeletedEvent` | 1 |
| `UpdatePotDetailsService.updatePotDetails` | `PotDetailsUpdatedEvent` | 1 |
| `AddPotShareholdersService.addPotShareholders` | `PotShareholdersAddedEvent` | 1 |
| `UpdatePotShareholdersDetailsService.updatePotShareholdersDetails` | `PotShareholdersDetailsUpdatedEvent` | 1 |
| `UpdatePotShareholdersWeightsService.updatePotShareholdersWeights` | `PotShareholdersWeightsUpdatedEvent` | 1 |
| `CreateExpenseService.createExpense` | `ExpenseCreatedEvent` | 1 |
| `DeleteExpenseService.deleteExpense` | `ExpenseDeletedEvent` | 1 |
| `UpdateExpenseDetailsService.updateExpenseDetails` | `ExpenseDetailsUpdatedEvent` | 1 |
| `UpdateExpenseSharesService.updateExpenseShares` | `ExpenseSharesUpdatedEvent` | 1 |

Les dix services sont référencés en fin de rapport. Une publication agrégée ne devient pas plusieurs Events par shareholder/share. Le port `appendAll` accepte néanmoins une liste arbitraire : le contrat doit supporter N Events et une transaction éventuellement multi-familles, pas coder une limite à un. Le branchement générique de succès vide reste valide et produit seulement le TerminalEvent.

Writer Command : `JpaPotCommandEventAppendAdapter.appendAll`, `MANDATORY`, construit un UUID aléatoire par `RecordedEvent`, convertit en enveloppe puis `repository.saveAllAndFlush`. L’INSERT SQL `business_event_outbox` est effectué par Hibernate sur `JpaBusinessEventOutboxEntity`, pas par un SQL explicite de l’adaptateur. UUID disponible **avant** save/flush et retourné dans les artifacts. Le flush force aussi le travail JPA en attente dans le contexte, avant B. TE possède mutations temporelles, metadata, Events, outcome, provenance et S/Claim [C5,C17–C19,C35].

Writer auxiliaire : `BusinessEventAppendPort` → `JpaBusinessEventOutboxAdapter.append`, `@Transactional` REQUIRED, même entité/repository, UUID local avant `save`, sans flush explicite. L’INSERT peut être différé au flush ou au commit physique [C6]. Sans transaction ambiante, le proxy peut être **propriétaire autonome d’une transaction d’un Event** ; avec ambient, il participe. Aucun appelant de production trouvé pour ce port auxiliaire : cela ne dispense ni de sa couverture, ni du cas autonome. Appeler ce port après avoir committé séparément les mutations d’une même opération serait interdit ; aucun tel appelant courant n’a été trouvé.

Pour TARGET, un retour de `save` ne prouve pas à lui seul un INSERT effectif (notamment avec `merge` sur identité préassignée). Confirmer les INSERT JPA au flush, par exemple par le hook PostInsert ciblé sur l’entité outbox déjà étudié en 3C, ou par une confirmation équivalente du writer ; dédupliquer les identités et refuser toute collision/réécriture au lieu de collecter un UUID non nouvellement inséré. Les UUID JDBC sont enregistrés après le succès de leur INSERT strict. La collecte reste ouverte pendant le flush complet, puis devient immuable avant E. Cela couvre aussi le writer JPA auxiliaire différé sans modifier les payloads ni les ports métier.

Les services/factory métier eux-mêmes n’ouvrent pas une transaction physique. Les adaptateurs de mutation Pot/Expense sont REQUIRED et peuvent en ouvrir une lorsqu’appelés isolément. Un assemblage externe des services sans owner pourrait donc morceler une opération et perdre la liste mémoire. Ce n’est pas le wiring Command CURRENT ; sa prohibition doit être explicite, et l’accès aux factories/ports bruts limité aux compositions recensées. Ajouter une nouvelle API autonome nécessite un owner englobant, pas une transaction par port.

### 3.2 COMMAND_TERMINAL : APPLIED, REJECTED, FAILED et recovery

`CommandOutcomePublicationPort` → `JdbcCommandOutcomeAdapter.publish` impose `MANDATORY`. Deux INSERT JDBC stricts synchrones : `command_outcomes`, puis `command_terminal_events`. L’UUID du TerminalEvent est actuellement `UUID.randomUUID()` inline dans le second `jdbc.update`, disponible juste avant l’INSERT mais non retourné par le port `void` [C7]. Une variable locale suffit à l’enregistrer après succès SQL ; aucun changement des DTO/outcomes n’est nécessaire.

| Chemin | Production et transaction | Erreur / reprise |
|---|---|---|
| TE APPLIED | N BusinessEvents (1 par service courant) + 1 TerminalEvent ; même TE Command | échec append/fence/S/Claim/commit ⇒ rollback commun ; artifacts provisoires non réutilisés |
| TE REJECTED | 1 TerminalEvent, 0 BusinessEvent ; refus d’autorisation, obsolescence ou précondition | `AbstractPotCommandUseCaseAdapter` refuse un rejet après production d’Event ; erreurs techniques 2B ne deviennent plus des rejets |
| TH FAILED | 1 TerminalEvent par effet terminal de `CommandConsumptionExecution` ; TH possède effet FAILED + S/Claim | après rollback confirmé de TE et ownership courant ; TH retry non terminale produit 0 Event |
| TR recovery Binding | 1 TerminalEvent REJECTED/CALLER_IDENTITY_NOT_CURRENT dans TR | TE déjà rollbackée ; si occurrence exacte encore présente, TR lève conflit et rollbacke sans Event durable ; sinon participant exécution/finalisation dans TR |

TA ne produit pas ces Events. TE/TR/TH sont intentionnellement distinctes, **pas** des transactions autonomes fractionnant une opération atomique. COMMITTED/UNKNOWN ne doivent pas déclencher une nouvelle émission FAILED aveugle ; le runner et Sequential conservent ce traitement [C4,C8,C14,C20,C30]. Appeler directement `publish` sous un owner externe est possible, hors Consumption ; sans transaction, le proxy MANDATORY refuse. La sémantique d’un owner externe reste à contractualiser.

### 3.3 BINDING_FACT : ATTACHED, DETACHED et port auxiliaire

`ExternalIdentityBindingPort` → `JpaExternalIdentityBindingAdapter` est MANDATORY. `acquire` délègue à `acquireWithInitializer` ; l’entrée externe `acquire` porte elle-même l’annotation, donc la délégation interne ne retire pas cette protection. Le stream est créé/verrouillé, l’occupation vérifiée, puis l’initializer synchrone exécuté. Ensuite réserve occurrence, active binding, avance révision et insère ATTACHED. Le retry de collision de BindingId emploie `ON CONFLICT` de réservation ; **ce n’est pas un rollback partiel** [C9].

`detach` verrouille le stream, supprime l’actif exact, avance la révision, insère DETACHED. Pas de fact sur conflit/NOT_CURRENT ; un acquire refusé peut néanmoins avoir effectué du SQL de stream. Pas d’appelant métier courant trouvé pour detach/acquire simple hors Registration ; ils restent des ports de production autorisés sous un owner, non des chemins déjà éprouvés avec E.

Les deux lifecycle methods génèrent l’UUID de fact dans `ExternalIdentityAttached/Detached(UUID.randomUUID(), ...)`, avant mapping en row. Le SQL effectif est `ExternalIdentityBindingFactJdbcRepository.append`, INSERT strict dans `external_identity_binding_facts`, avec `row.eventId()` [C9–C11]. Chaque succès lifecycle émet 1 fact ; un owner externe peut composer plusieurs appels, donc N facts par transaction sont possibles.

Le port auxiliaire `ExternalIdentityBindingFactPort.append` → `JpaExternalIdentityBindingFactAdapter`, MANDATORY, reçoit un fact dont l’UUID existe déjà. Même repository SQL. Aucun appelant métier de ce port trouvé. Son MANDATORY prouve la participation, **pas** que le fact correspond à toutes les mutations d’autorité requises chez un futur appelant.

**Chemin indirect décisif :** l’autorité Binding construit `new ExternalIdentityBindingFactJdbcRepository(jdbc)` dans son constructeur ; le port auxiliaire reçoit le bean repository. Un aspect sur le seul port auxiliaire ou le seul bean repository manquerait les INSERT lifecycle. Le point partagé de collecte/contrôle doit être le corps de `append` (ou une dépendance commune réellement utilisée par les deux instances). Le repository est public sans garde : un accès direct peut faire du JDBC hors owner. Restreindre ses appelants et/ou ajouter une garde explicite au corps ; une annotation sur un objet construit par `new` ne suffit pas.

### 3.4 USER_CREATED et Registration

`ExecuteRegistrationService.execute` sous TE Registration : requête → outcome existant ? retour sans nouvelle publication ; sinon `bindings.acquireWithInitializer` avec initializer `users.create` puis `userFacts.append`. Gagnante : User + UserCreated + ATTACHED + outcome REGISTERED + provenance/S/Claim dans la même TE [C12–C14,C21]. **2 Events**, familles USER_CREATED et BINDING_FACT ; le fait USER_CREATED est publié avant ATTACHED, ce qui ne prescrit pas l’ordre des E.

`UserCreatedFactPort` → `JdbcUserCreatedFactAdapter.append` : INSERT strict JDBC `user_created_facts`, UUID aléatoire inline ; disponibilité avant SQL, non exposé. Pas d’annotation ni garde transactionnelle ; appelé directement hors TE, `JdbcTemplate` peut utiliser une connexion en autocommit et committer le fait seul [C12]. Ce chemin exposé ne peut pas être déclaré sûr parce que le seul appelant trouvé est Registration. La correction locale préférée est une garde transactionnelle explicite, combinée au contexte collecteur TARGET. `MANDATORY` est une alternative, à vérifier avec le mode proxy : la classe actuelle est `final`, une simple annotation sans adaptation n’est pas une solution certaine avec un proxy de classe.

Registration rejetée : Binding occupé ⇒ initializer non exécuté, 0 UserCreated, 0 ATTACHED ; outcome REJECTED puis finalisation. Déjà traitée : outcome existant ⇒ 0 Event neuf. Erreur à n’importe quelle étape ⇒ rollback TE ; failure policy Registration sans effet terminal producteur de fact, donc TH n’invente pas de USER_CREATED. Pas de TR BindingFenceRecovery pour Registration. Les stores Request/Outcome/Result n’émettent pas eux-mêmes un Event supplémentaire. `JdbcRegistrationOutcomeStore` est également sans annotation : participation sûre dans le chemin CURRENT, mais nouvel usage direct à interdire ou garder pour ne pas isoler l’outcome des faits [C21,C27].

### 3.5 SQL natif, migrations, imports et maintenance

Inventaire croisé des noms des quatre tables, des constructeurs d’entité et des APIs de publication : aucun autre INSERT Event runtime trouvé, aucun bulk/native INSERT JPA identifié vers ces tables. `JpaEventPort` lit l’entité ; les discovery/result/projection/LKV et le worker Binding **consomment** des Events ou matérialisent des résultats, sans en produire dans ces quatre familles. Admission Request/Command n’est pas un producteur d’Event de ces familles.

Hors runtime, [V22](../../app/infra-persistence-primary-jpa/src/main/resources/db/migration/V22__repair_binding_history_and_contract.sql) insère directement deux ensembles ATTACHED de baseline, UUID déterministes `md5(... )::uuid`, origine MIGRATION_BASELINE, avant réparation d’historique et occurrences. Ce writer Flyway ne traverse aucun port : 0..N facts, transaction de migration, sans finalisation Consumption. V20 initialise les streams, V21 prépare occurrences/provenance ; V15 réécrit types/payload outbox, pas de nouveaux Events ; V17/V28 créent les tables Terminal/UserCreated, sans producteur caché de ces Events. Les triggers PRIMARY inspectés protègent surtout l’immutabilité ; aucun trigger insérant tardivement un Event de ces familles trouvé. Pas de procédure autonome productrice identifiée.

Recherche séparée des scripts et SQL d’exploitation : aucun import/COPY/INSERT de ces Events identifié hors V22 ; des lectures et migrations dérivées existent. Cela n’exclut pas des interventions DBA externes. **Cutover indispensable si E est activé** : définir explicitement quels Events historiques sont couverts, geler les anciens writers/imports concurrents, charger les positions initiales selon une règle assumée (l’ordre réel des anciens commits n’est pas reconstructible), rapprocher UUID/positions et n’activer la nouvelle écriture qu’après contrôle. Ne pas réécrire V22 ni rejouer l’histoire pour cet audit. Après cutover, tout import nouveau doit passer par le protocole contrôlé ou une procédure de maintenance exclusive avec preuve équivalente. Les droits opérateur ne deviennent pas une garantie fournie par ArchUnit.

## 4. Matrice R1–R5

R1 : même transaction physique pour les effets de l’opération ; R2 : port/writer identifié et capable de fournir l’UUID inséré ; R3 : pas de rollback partiel ; R4 : fin du métier avant barrière terminale ; R5 : pas de commit autonome d’un sous-effet. R2 SATISFIED signifie **capacité locale démontrée**, pas collecte déjà implémentée. R3 SATISFIED décrit l’absence d’utilisation sur le chemin CURRENT, pas l’impossibilité technique de savepoints JDBC futurs. R4 reste CONDITIONAL faute de fermeture TARGET : aucun DML tardif métier courant n’est pour autant constaté.

| Producteur / chemin | R1 | R2 | R3 | R4 | R5 | Écart | Correction minimale |
|---|---|---|---|---|---|---|---|
| TE Command APPLIED | SATISFIED | SATISFIED | SATISFIED | CONDITIONAL | SATISFIED | fermeture physique/collecte TARGET à fournir | collecter aux writers, flush/fermeture après S/Claim, aucun retour métier |
| TE Command REJECTED | SATISFIED | SATISFIED | SATISFIED | CONDITIONAL | SATISFIED | idem ; UUID terminal inline | local UUID après INSERT, même suffixe |
| TH Command FAILED | SATISFIED | SATISFIED | SATISFIED | CONDITIONAL | SATISFIED | couverture TH distincte de TE | même suffixe par manager, collecteur neuf |
| TR Binding recovery | SATISFIED | SATISFIED | SATISFIED | CONDITIONAL | SATISFIED | couverture TR, rollback TE impératif | même suffixe TR ; aucune réutilisation du lot TE |
| TE Registration gagnante | SATISFIED | SATISFIED | SATISFIED | CONDITIONAL | SATISFIED | UserFact sans garde propre ; deux familles | garde locale + capture des deux UUID ; barrière commune |
| Registration rejetée ou déjà traitée | SATISFIED | SATISFIED | SATISFIED | CONDITIONAL | SATISFIED | 0 Event neuf ; aucune E nécessaire | même owner, ne pas recycler de collecte précédente |
| Binding ATTACH, appel de port externe | CONDITIONAL | SATISFIED | SATISFIED | CONDITIONAL | CONDITIONAL | MANDATORY prouve participation ; owner externe/initializer non borné | owner atomique déclaré, initializer synchrone contrôlé, corps SQL commun |
| Binding DETACH, appel de port externe | CONDITIONAL | SATISFIED | SATISFIED | CONDITIONAL | CONDITIONAL | owner externe doit inclure les autres effets associés | transaction englobante et contrôle du repository partagé |
| Publication BusinessEvent auxiliaire | CONDITIONAL | SATISFIED | SATISFIED | CONDITIONAL | CONDITIONAL | REQUIRED autonome possible ; dépend des effets associés de l’appelant | couvrir owner annoté ; interdire assemblage sans owner ou imposer MANDATORY si autonomie abandonnée |
| Publication BindingFact auxiliaire | CONDITIONAL | SATISFIED | SATISFIED | CONDITIONAL | CONDITIONAL | MANDATORY ne prouve pas la cohérence avec l’autorité chez l’appelant | appelants autorisés, même owner, collecte au SQL commun |
| Publication UserCreated directe | CONDITIONAL | SATISFIED | SATISFIED | CONDITIONAL | CONDITIONAL | possibilité concrète d’autocommit hors TE | garde explicite obligatoire, local UUID + collecte |
| Repository fact / repository outbox direct | CONDITIONAL | CONDITIONAL | SATISFIED | CONDITIONAL | CONDITIONAL | contournement de ports, JDBC sans garde / Spring Data propriétaire | accès restreint ; garde dans le corps JDBC ; confirmer INSERT JPA |
| Assemblage direct des services Pot hors owner | CONDITIONAL | CONDITIONAL | SATISFIED | CONDITIONAL | CONDITIONAL | plusieurs REQUIRED isolés possibles ; Events mémoire non durables | contrat/composition autorisée, owner englobant si nouvel usage |
| V22 / imports historiques | CONDITIONAL | CONDITIONAL | SATISFIED | CONDITIONAL | CONDITIONAL | hors writers runtime, protocole cutover distinct | procédure exclusive de baseline/cutover, pas une annotation Spring |

Les lignes génériques ne supposent pas un appelant sûr inexistant : leurs dépendances sont laissées CONDITIONAL. Aucun `VIOLATED` n’est retenu pour les opérations de production câblées. La capacité d’un port exposé à être appelé en autocommit est un défaut local de garantie architecturale, sans invention d’un appelant métier actuel fautif. La garantie d’absence de SQL futur arbitraire reste INCONCLUSIVE si elle est posée sans le périmètre des composants autorisés ; ce n’est pas l’objectif de ce contrat.

## 5. Frontières transactionnelles physiques

| Frontière | Propriétaire / participation CURRENT | Conséquence |
|---|---|---|
| Command TA, TE, TH et TR | `commandConsumptionTransactionRunner` : owner guard → `SpringTransactionRunner` → `TransactionTemplate(manager)` REQUIRED | refus ambient ; chaque invocation possède sa résolution physique ; TR après sortie TE rollbackée |
| Registration TA/TE/TH | runner propriétaire de `RegistrationRuntimeConfiguration` même forme | initializer User/facts/outcome et finalisation rejoignent TE |
| FinalizeConsumption / Execute / failure participant | services sans transaction propre ; lifecycle et provenance MANDATORY | pas un nouveau commit ; `durableEffect` avant provenance/S/Claim |
| Pot/Expense persistence | adaptateurs JPA REQUIRED/readOnly participants | rejoignent TE ; isolément, peuvent posséder une transaction limitée à un appel |
| Append Command, Outcome, lifecycle Binding, fact auxiliaire | MANDATORY via proxy Spring | pas d’owner autonome ; ne garantit ni le bon manager ni la discipline d’un appelant externe arbitraire |
| BusinessEvent auxiliaire | annotation REQUIRED | propriétaire autonome légitime d’un Event seul, ou participant ; à couvrir aussi sans runner |
| UserCreated / repositories JDBC directs | aucune annotation propre | JDBC participe à la connexion transactionnelle Spring sous TE ; hors contexte, autocommit possible |
| Runners Event/Binding/Results/Projection/LKV | owners analogues ou owner spécialisé LKV | pas de nouveau producteur identifié ; ne pas confondre worker Binding et autorité lifecycle |

`PlatformTransactionManager` est partagé par injection des configurations concernées ; aucun manager alternatif construit dans leurs sources. L’autoconfiguration fournit `JpaTransactionManager`. Le test existant `CurrentTransactionManagerPostgresTest` de 3C prouve **pour le contexte Command exécuté alors** manager unique, nested refusé et même PID/xid EntityManager/JdbcTemplate [C22]. Pour Registration/owners externes, identité déduite statiquement du wiring et de la datasource commune, pas nouvelle preuve dynamique. Aucun `getConnection`, `DriverManager`, `setAutoCommit`, appel direct `commit`, suspension `REQUIRES_NEW`/`NOT_SUPPORTED`, configuration NESTED ou rollback partiel trouvé dans les sources de production examinées. Les tests expérimentaux qui utilisent ces mécanismes ne sont pas du runtime.

`JdbcTemplate`/`JdbcOperations` participent à la transaction JPA grâce à la datasource partagée et la connexion liée par Spring ; ils ne rendent pas transactionnel un appel isolé par magie. Un futur deuxième datasource/manager imposerait une nouvelle analyse. Les autoconfigurations READ/projection ordonnent aussi leur chargement par rapport à `DataSourceTransactionManagerAutoConfiguration` sans construire de second manager ; leur template REQUIRED n’est pas un producteur supplémentaire de ces Events. Une décoration des seuls runners manquerait les owners annotés autonomes, tandis que le gestionnaire physique commun peut les couvrir.

R3 : aucune utilisation de `PROPAGATION_NESTED`, `TransactionStatus.createSavepoint`, `Connection.setSavepoint`, SQL SAVEPOINT/ROLLBACK TO, ni équivalent détectée en production. Le refus NESTED du manager n’empêche pas un savepoint via la connexion brute : démontré en 3C. Contrat raisonnable : interdire rollback partiel et accès aux connexions brutes dans les composants producteurs, contrôler les dépendances et usages connus ; conserver le rollback intégral en cas d’échec. Pas besoin d’un framework de réconciliation de savepoints.

## 6. Fermeture des écritures : réponses précises à R4

### 6.1 Entités encore modifiables après finalisation ?

**En général oui, le contexte demeure utilisable ; sur la fin CURRENT contrôlée, pas d’entité métier résiduelle modifiée observée.** `FinalizeConsumptionService` applique l’effet durable puis écrit la provenance puis appelle `terminalize`. `JpaConsumptionSlotRepository.terminalize` a `flushAutomatically=true, clearAutomatically=true`. `claims.end/fail` font également flush/clear. Les entités déjà présentes sont donc flushées puis détachées ; `ExecuteConsumptionService` retourne un résultat de domaine/provenance, pas une entité JPA [C14–C19]. Une référence Java détachée reste modifiable, mais son seul dirty checking ne produira plus de SQL sans réattachement.

Ce clear ne ferme pas l’EntityManager ni la transaction. Un nouveau `find`, `persist`, `merge`, repository `save`, une requête ou un callback peut repeupler le contexte après S/Claim. Un owner/participant futur pourrait encore le faire avant commit. Le chemin de production inspecté n’effectue pas ce retour au métier après finalisation. L’auxiliaire BusinessEvent autonome ne passe même pas par cette finalisation, donc ses entités restent gérées jusqu’au commit. E ne peut pas dépendre du seul clear de Consumption.

### 6.2 Travail métier dans les synchronisations de commit ?

**Aucun observé en production.** Les owners Consumption/LKV enregistrent `afterCompletion` pour qualifier COMMITTED/ROLLED_BACK/UNKNOWN, sans DML. `MeteredAdvanceLatestKnownVersionUseCase.afterCommit` incrémente des compteurs et journalise, sans mutation persistée ni Event. `SpringTransactionRunner.runAfterCommit` accepte cependant un Runnable arbitraire (exécution immédiate sans synchronisation) ; aucun appelant métier producteur trouvé [C20,C23,C24]. Il faut contractualiser observation seulement, jamais mutation durable de l’opération, et contrôler les futurs usages. Ne pas supprimer les métriques existantes.

Aucun `beforeCommit`, `beforeCompletion`, `TransactionExecutionListener`, `@TransactionalEventListener` de production producteur trouvé. Les synchronisations Spring/provider internes subsistent. Le placement TARGET doit donc se faire après les callbacks pré-commit du framework, puis ne plus rappeler l’application, conformément à 3C ; une priorité `beforeCommit` seule ne clôt pas le cycle.

### 6.3 Listeners JPA/Hibernate producteurs ?

**Aucun listener applicatif de production identifié** : pas de callback `@PrePersist/@PostPersist/@PreUpdate/@PostUpdate/@PreRemove/@PostRemove/@PostLoad`, `@EntityListeners`, enregistrement Hibernate `EventListenerRegistry`/Integrator ni mapping XML de listener trouvé. Le listener PostInsert de 3C appartient aux tests. Les opérations de flush/dirty checking Hibernate natives existent malgré cette absence ; elle n’est pas une interdiction technique d’en ajouter un demain.

### 6.4 SQL encore possible après le flush terminal ?

Le scénario `gHibernateCommitCanStillFlushAfterE` de 3C recharge l’entité outbox après E, modifie `attemptCount` par réflexion et observe un UPDATE au commit [C25]. **Injection de test réelle, pas chemin métier CURRENT**. Il prouve que `flush()` n’est pas une fermeture. Dans ce scénario, le SQL `find` après E est déjà un accès non terminal à éviter, avant même l’UPDATE.

| Opération possible | Observation / restriction |
|---|---|
| Dirty checking et nouveau flush lors de `EntityTransaction.commit` | possible ; sans nouvel état sale/actions, il peut ne produire aucun DML ; flush initial seul insuffisant |
| Nouvel accès JPA, réattachement, persist/save ou requête à autoflush après barrière | pas observé dans le suffixe CURRENT des producteurs ; interdire après fermeture, y compris depuis callbacks/listeners |
| JDBC brut / `JdbcTemplate.update` / SQL natif après barrière | possible techniquement ; aucun chemin courant trouvé ; garder les writers contrôlés et leur phase transactionnelle |
| Cascades/listeners provoqués par le flush terminal | autorisés avant fermeture si synchrones et entièrement drainés, UUID ajoutés au lot avant sa fixation ; aucun listener métier actuel trouvé |
| INSERT EventPosition, autres E en ordre commun, commit et contraintes | travail TARGET autorisé ; pas un nouveau travail métier ; contrôler FK/index/contraintes et pas seulement l’ORM |
| Contraintes différées, triggers, WAL/I/O, locks de commit | coût/échec possible après E ; pas une publication métier Java autorisée ; pas d’absence universelle de toute attente SQL revendiquée |

Les migrations PRIMARY auditées ne déclarent pas de contrainte DEFERRABLE ; une FK READ différée existe, sans transposition automatique aux producteurs PRIMARY. Les contraintes futures d’EventPosition restent à prouver. Aucun SQL E n’existe CURRENT : on évalue le point d’insertion du suffixe, sans inventer une violation après une E déjà livrée.

### 6.5 Restriction simple et protection minimale

Contrat proposé : le propriétaire finit toute décision/mutation et la finalisation, puis rend la main au gestionnaire ; callbacks métier, accès aux entités/repositories, nouveaux appels de ports, retours vers le métier et I/O externes sont interdits après la barrière. Les callbacks d’observation ne peuvent pas charger/salir d’entité. Les entités restent internes à la persistance et ne sont pas retournées par les producteurs. Les listeners futurs doivent être revus comme nouveaux writers et travailler avant fermeture.

Infrastructure TARGET minimale : flush **complet du contexte transactionnel** avant fermeture ; recueillir aussi les INSERT provoqués pendant ce flush, fixer le lot, passer en phase fermée et refuser les appenders tardifs. Pour une fermeture JPA explicite, un `clear()` après flush est une option locale raisonnable contre les références déjà gérées ; il ne remplace pas l’interdiction de rechargement/réattachement. Le suffixe et ses associations doivent utiliser du JDBC contrôlé sur la même transaction et ne pas relancer le métier. Un contrôle Hibernate ciblé du DML tardif, incluant UPDATE/DELETE et collections s’ils deviennent pertinents, peut renforcer ce contrat ; le seul PostInsert Event de 3C ne couvre pas l’UPDATE démontré. La nécessité de cette détection supplémentaire dépend du niveau de prévention choisi et de la preuve de fermeture obtenue.

Pour les chemins identifiés, owner sans retour métier + ports gardés + absence contractuelle de callbacks/listeners écrivains + flush/clear suffisent raisonnablement, sous tests. Si des callbacks Hibernate écrivains non maîtrisables devaient rester autorisés au commit, cette discipline ne suffirait plus : il faudrait un contrôle provider ciblé qui échoue et rollbacke la transaction **avant commit**, ou retirer ces callbacks. Aucun cas CURRENT n’impose cette complication. Un flush-mode réglé seul, un clear seul ou une garde des seuls INSERT Event ne prouvent pas R4.

## 7. Écarts, corrections minimales et proportionnalité

Ces actions sont proposées, **aucune appliquée**. « Avant E » signifie avant son activation avec une garantie de couverture ; cela n’exige pas d’implémenter à l’avance un collecteur sans séquenceur.

| ID / priorité | Correction / responsabilité | Complexité et surface | Impact abstractions / risque | Vérification nécessaire |
|---|---|---|---|---|
| P1 **BLOCKING** | exiger transaction active sur UserCreated et contexte contrôlé TARGET ; outcome Registration interdit hors owner | LOW ; un writer, éventuellement garde de store outcome | aucun DTO/Consumption changé ; risque faible, vérifier proxy final si annotation choisie | appel direct hors tx refusé, TE gagnante/rejetée/rollback |
| P2 **BLOCKING** | capturer UUID exact sur les six adaptateurs (cinq points de persistance), via le repository commun pour les deux chemins Binding ; phase fermée refusée | LOW–MEDIUM ; 2 writers JPA + Terminal + UserCreated + corps JDBC Binding | ports void conservés ; dépendance technique collecteur locale ; risque oubli/doublon | rapprochement exhaustif SQL des quatre familles, lifecycle construit par new, append auxiliaire, N Events |
| P3 **BLOCKING** | limiter les writers bas niveau aux appelants autorisés ; pour JDBC Binding garde dans le corps commun, pas seulement aspect sur bean | LOW ; repository JDBC et règle d’accès outbox/entité | pas de refonte JPA ; risque faible ; ne pas casser les lectures autorisées | appels directs hors contexte et revue des dépendances ; bypass JPA refusé ou observé par hook ciblé |
| P4 **BLOCKING** | adopter contrat sans savepoints/suspension de sous-effets, owner englobant, callbacks sans métier ; rendre le suffixe sans retour application | LOW ; contrat ports/owners et garde de fermeture | aucune réécriture métier ; risque faible sur usages trouvés | scans/règles ciblées + cas négatifs savepoint/nouvelle publication/mutation tardive |
| I1 **BLOCKING**, infrastructure | flush complet, fermeture/éventuel clear, hook au commit physique après callbacks, même connexion, une collecte par transaction, rollback/nettoyage | MEDIUM ; manager/collecteur et intégration persistance localisés | extension technique existante étudiée 3C, aucune nouvelle abstraction Consumption ; risque moyen au cycle commit | vraie TE/TH/TR/Registration, owners annotés, rollback de suffixe, identité PID/xid, UPDATE Hibernate tardif |
| I2 **BLOCKING**, intégration | E/associations atomiques, ordre commun familles, couverture du lot et décision de cutover | MEDIUM ; futur séquenceur et procédure opérationnelle | pas de changement P/B/S/retry ; risque moyen sur intégrité | scénarios 3B adaptés au schéma définitif, rapprochement Event↔Position, zéro Event, overflow/contraintes |
| V1 **RECOMMENDED** | ArchUnit ciblé : writers/constructeurs/repositories/connexions et callbacks autorisés ; changements de propagation revus | LOW ; tests structurels localisés | aucune dépendance runtime ; risque faible, maintenir allowlist lisible | test négatif de règle et audit de l’allowlist |
| V2 **RECOMMENDED** | assertions d’identité transactionnelle et couverture UUID pérennes sur les vrais chemins | MEDIUM ; tests PostgreSQL existants/locaux à étendre | pas de mécanisme runtime nouveau ; risque faible | matrice §8, diagnostics SQL bornés |
| O1 **OPTIONAL** | rendre BusinessEvent auxiliaire MANDATORY si son autonomie est abandonnée explicitement | LOW ; une annotation/contrat | réduit la surface autorisée, peut casser usage externe futur ; pas nécessaire si owner annoté couvert | autonome refusé ou accepté/couvert selon décision |
| O2 **OPTIONAL** | détection Hibernate ciblée de tout DML métier tardif en défense supplémentaire | MEDIUM ; hook provider/version et tests | couplage Hibernate, risque moyen ; à réserver si discipline/fermeture insuffisante | UPDATE/DELETE/collections tardifs, rollback réel ; pas uniquement late INSERT Event |

P2 est une adaptation technique requise de TARGET, **pas** un classement VIOLATED du R2 CURRENT. P1/P3 ferment des trous d’usage exposés même sans appelant courant. P4 demande un contrat applicable et vérifiable ; les tests structurels V1 renforcent sa durabilité sans prétendre l’imposer à n’importe quel SQL externe. I1/I2 relèvent du séquenceur/manager, jamais d’un ajout de E dans `FinalizeConsumptionService` ou dans chaque service métier.

Aucune correction HIGH ou structurante identifiée. Non nécessaires : framework d’interception universelle JDBC, nouvelle Consumption, refactoring transactionnel général, refonte des adaptateurs, modification P/B/S, nouvelle stratégie retry. Une option de restriction de ports peut remplacer une modification du service ; ne pas durcir indistinctement tous les adaptateurs REQUIRED si la composition propriétaire actuelle suffit.

## 8. Vérifiabilité durable : dispositif minimal proposé

| Règle | Statique ciblée | Intégration PostgreSQL | Garantie structurelle raisonnable / convention résiduelle |
|---|---|---|---|
| R1 | allowlist owners, manager injecté, participation MANDATORY et mutations accessibles seulement aux compositions autorisées | même PID/xid JPA/JDBC, mutations/faits/outcome/S/Claim ; échec à chaque frontière ⇒ rollback commun | owner englobant + datasource commune ; nouvelle configuration externe exige nouvelle preuve |
| R2 | dépendances aux repositories/entité et SQL Event limitées aux writers listés ; constructions directes inventoriées | UUID des INSERT réellement durables = UUID collectés = UUID associés ; toutes familles/auxiliaires/lots ; échec INSERT/doublon sans fausse collecte | corps SQL Binding commun et hook JPA ciblé ou confirmation d’INSERT équivalente ; convention pour SQL externe non inspecté |
| R3 | interdire NESTED/REQUIRES_NEW sur producteurs, usages APIs savepoint/connexions et statements connus ; revue migrations | refus NESTED et savepoint Spring ; cas brut négatif documentant limite ou refus au port ; rollback complet après publication | aucun port producteur n’expose de connexion/statut transactionnel ; absence de tout SQL brut futur non prouvable statiquement |
| R4 | absence de callbacks écrivains/listeners hors allowlist ; pas de JPA dans callback d’observation ni couche métier | chronologie SQL jusqu’au commit, late append JDBC/JPA refusé, dirty checking UPDATE après rechargement interdit/détecté, 0 DML métier après première E | propriétaire retourne définitivement au manager, phase fermée, contexte drainé ; interdiction conventionnelle d’extensions provider non revues |
| R5 | pas de commit/connexions/autocommit manuels, propagation autonome de sous-effets interdite | TE rollbackée sans effet ; TR/TH nouveaux xid seulement après rollback ; owner autonome Event seul correctement couvert | aucune suspension utilisée, garde ambient des owners ; contrat de toute nouvelle API autonome |

Minimum de scénarios réels à conserver lors de la future intégration :

1. Command APPLIED Pot et Expense (avec liste multi-Event au niveau port), REJECTED sans BusinessEvent ; comparer toutes les identités, pas seulement ConsumptionResult.
2. FAILED dans TH, retry TH sans Event ; recovery TR rejetée et conflit ; perte de claim et clôture refusée : rollback de tous effets et de E, aucune fuite du collecteur TE vers TR/TH.
3. Registration gagnante = exactement USER_CREATED + ATTACHED ; rejetée/déjà traitée = zéro neuf ; échec après UserCreated avant fact/outcome/finalisation ⇒ aucun effet durable.
4. Binding attach/detach/NOT_CURRENT, port fact auxiliaire et repository construit directement par l’autorité ; BusinessEvent auxiliaire REQUIRED sous owner et propriétaire autonome ; UserCreated direct refusé hors transaction.
5. Participants REQUIRED/MANDATORY : même collecteur sans suffixe prématuré ; deux transactions successives sur même thread, rollback puis succès : nettoyage/UUID disjoints.
6. Barrière : callback pré-commit drainé avant E ou interdit par contrat ; append tardif après fermeture refusé ; rejouer le contre-exemple UPDATE Hibernate 3C avec la protection retenue, pas un test qui vérifie seulement INSERT.
7. Atomicité de E/associations : erreur d’association/commit ⇒ rollback entier ; transaction zéro Event sans acquisition E ; multi-familles ordres de publication opposés sans inversion des E. Pas de nouvelle politique retry.

Les preuves existantes 2B/2C restent utiles sur rollback/fence et 3B/3C sur suffixe/cycle Spring, mais ne démontrent pas une collecte runtime exhaustive. Ce rapport ne réutilise pas leur PASS comme résultat nouveau. Un test statique n’interdit pas tout SQL construit dynamiquement, réflexion ou modification future ; les tests d’intégration restent eux aussi bornés à leurs scénarios. Le niveau recherché est la couverture vérifiée des composants recensés et une revue obligatoire de tout nouveau writer.

Pour une future implémentation, slices à déclarer selon les fichiers effectivement modifiés : COMMAND et BINDING/WEB selon l’impact réel Registration/autorité partagé ; ne pas élargir automatiquement à tous les consumers. Commandes canoniques de la politique, à choisir alors, non exécutées ici. Un changement volontaire de frontière/dépendance devra réévaluer le gate global ; l’audit actuel n’en réclame aucun. Les dettes du gate global documentées en 2C ne sont ni corrigées ni rendues vertes par 3D.

## 9. Verdict et conditions de décision

**READY WITH LIMITED CHANGES.** Le contrat R1–R5 est applicable aux producteurs CURRENT sans changement architectural fondamental. Les dépendances aux owners externes et ports auxiliaires sont explicites dans la matrice ; aucun chemin sans appelant n’est silencieusement déclaré couvert.

Avant activation de E : fermer les usages hors transaction/ports autorisés (P1/P3), intégrer la capture exhaustive des UUID et le refus tardif (P2), adopter la discipline P4 et prouver la fermeture I1 ainsi que l’intégrité/cutover I2. Les seuls prérequis propres aux producteurs sont locaux ; la clôture physique, le suffixe et l’atomicité ne sont pas à déléguer aux Consumptions. V1/V2 sont RECOMMENDED pour pérenniser ces garanties ; O1/O2 sont OPTIONAL selon les restrictions retenues, tant que R1–R5 restent prouvées.

La décision d’intégration revient à la revue de 3D puis au chantier explicitement autorisé. Les choix de schéma EventPosition, famille/ordre définitif, cutover, preuve de contention et contrat Discovery indépendant ne sont pas tranchés ici. Aucune implémentation de E ne suit cet audit.

## 10. Vérification documentaire et livraison

Commandes effectivement utilisées : `pwd`, `git status --short` / `--porcelain=v1`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git worktree list --porcelain`, `git rev-list --left-right --count HEAD...origin/feat/consumption-transaction-protocol`, `git ls-remote origin refs/heads/feat/consumption-transaction-protocol` ; inventaires/recherches `rg --files`, `rg -n` et `rg -n -i`, lectures `cat`/`sed`, réduction d’affichage `head`/`tail`. Vérification de livraison : script Python de validation des liens locaux, références et périmètre ; `git diff --check`, diff exact du seul rapport puis staging explicite, commit et push non forcé sur la branche demandée. Le SHA final et l’état distant sont communiqués dans le compte rendu de livraison, sans auto-référence circulaire dans ce fichier.

Fichier livré : ce seul rapport sous `docs/architecture/`. Impact production attendu/observé **NONE** ; slices déclarées/exécutées **NONE** ; commandes Maven exécutées **NONE** ; DB/migrations exécutées **NONE** ; franchissement inattendu **NONE** ; gate architecture global **NOT REQUIRED / non exécuté** ; full reactor **non exécuté**, audit documentaire ne le nécessitant pas et mission l’excluant. Aucun fichier préexistant inclus ; les autres worktrees sont préservés. Aucune nouvelle preuve PostgreSQL ou performance n’est annoncée.

## 11. Sources de code CURRENT à la baseline

Les références C ci-dessous désignent les méthodes et objets examinés au SHA d’en-tête. Les liens sont relatifs au dépôt ; le numéro C permet de retrouver les preuves sans dépendre d’un numéro de ligne susceptible d’évoluer. Les sources de tests sont clairement distinguées ; leur présence n’est pas un résultat d’exécution 3D.

- **C1** — [PotCommandInvocation.java](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/dispatch/PotCommandInvocation.java) — publish des dix types : collecte mémoire ; adaptation des mutations/contextes.
- **C2** — [PotBusinessUseCaseFactory.java](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/PotBusinessUseCaseFactory.java) — construction des dix services, aucune transaction propriétaire.
- **C3** — [AbstractPotCommandUseCaseAdapter.java](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/dispatch/AbstractPotCommandUseCaseAdapter.java) — executeAdapted/rejected : résultat et refus du rejet après Event.
- **C4** — [CommandConsumptionExecution.java](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/consumption/CommandConsumptionExecution.java) — execute et terminalFailureEffect : APPLIED/REJECTED/FAILED.
- **C5** — [JpaPotCommandEventAppendAdapter.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaPotCommandEventAppendAdapter.java) — appendAll : UUID, MANDATORY, saveAllAndFlush.
- **C6** — [JpaBusinessEventOutboxAdapter.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaBusinessEventOutboxAdapter.java) — append : UUID local, REQUIRED, save différé.
- **C7** — [JdbcCommandOutcomeAdapter.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandOutcomeAdapter.java) — publish : INSERT strict outcome puis TerminalEvent, UUID inline.
- **C8** — [BindingFenceRecoveryExecuteUseCase.java](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/consumption/BindingFenceRecoveryExecuteUseCase.java) — execute : propriétaire rollback TE avant TR.
- **C9** — [JpaExternalIdentityBindingAdapter.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingAdapter.java) — acquire/acquireWithInitializer/detach ; construction directe des repositories.
- **C10** — [JpaExternalIdentityBindingFactAdapter.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingFactAdapter.java) — append : participant MANDATORY.
- **C11** — [ExternalIdentityBindingFactJdbcRepository.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingFactJdbcRepository.java) — APPEND/append : SQL commun et row.eventId.
- **C12** — [JdbcUserCreatedFactAdapter.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcUserCreatedFactAdapter.java) — append : UUID inline et absence de garde transactionnelle.
- **C13** — [ExecuteRegistrationService.java](../../app/engine-consume-registration/src/main/java/com/kartaguez/pocoma/engine/consume/registration/ExecuteRegistrationService.java) — execute : existing outcome, initializer User/fact, Binding, outcome.
- **C14** — [FinalizeConsumptionService.java](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/FinalizeConsumptionService.java) — finalizeConsumption : effet puis provenance puis terminalize.
- **C15** — [ExecuteConsumptionService.java](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java) — execute : callback puis finalizer puis retour domaine.
- **C16** — [JpaConsumptionLifecycleAdapter.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java) — terminalize/scheduleRetry/closeClaim : S puis clôture C.
- **C17** — [JpaConsumptionSlotRepository.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionSlotRepository.java) — terminalize/scheduleRetry : flushAutomatically et clearAutomatically.
- **C18** — [JpaConsumptionClaimRepository.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/consumption/JpaConsumptionClaimRepository.java) — end/fail : flushAutomatically et clearAutomatically.
- **C19** — [JpaConsumptionProvenanceAdapter.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionProvenanceAdapter.java) — appendInputs/appendResults MANDATORY ; saveAll.
- **C20** — [ConsumptionOwnerTransactionRunner.java](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerTransactionRunner.java) — requireNoAmbientTransaction/runInTransaction : résolution physique, afterCompletion.
- **C21** — [RegistrationRuntimeConfiguration.java](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/registration/RegistrationRuntimeConfiguration.java) — registrationTransactionRunner/registrationExecute/registrationFailure : propriétaires.
- **C22** — [JpaTerminalSequencingPostgresTest.java](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/sequencing/JpaTerminalSequencingPostgresTest.java) — TEST EXISTANT 3C : classe secondaire CurrentTransactionManagerPostgresTest, effectiveManagerAndSharedJdbcConnection, non relancé.
- **C23** — [SpringTransactionRunner.java](../../app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunner.java) — runInTransaction/runAfterCommit : template et Runnable.
- **C24** — [MeteredAdvanceLatestKnownVersionUseCase.java](../../app/runtime-latest-known-version-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/latestknownversion/MeteredAdvanceLatestKnownVersionUseCase.java) — afterCommit : métriques/logs uniquement.
- **C25** — [JpaTerminalSequencingPostgresTest.java](../../app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/sequencing/JpaTerminalSequencingPostgresTest.java) — TEST EXISTANT 3C : gHibernateCommitCanStillFlushAfterE, eNestedRejectedButProgrammaticSavepointStillPossible ; non relancé.
- **C26** — [CommandConsumptionRuntimeConfiguration.java](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/consumption/CommandConsumptionRuntimeConfiguration.java) — commandConsumptionTransactionRunner/Execute/HandleFailure : propriétaires et recovery.
- **C27** — [JdbcRegistrationOutcomeStore.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcRegistrationOutcomeStore.java) — insert/find : JDBC sans annotation propre, sous TE courante.
- **C28** — [JpaBusinessEventOutboxEntity.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/entity/outbox/JpaBusinessEventOutboxEntity.java) — mapping Hibernate, UUID et champs persistés dont attemptCount.
- **C29** — [JpaBusinessEventOutboxRepository.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/outbox/JpaBusinessEventOutboxRepository.java) — Spring Data JpaRepository : accès bas niveau save/saveAll.
- **C30** — [SequentialConsumptionOrchestrator.java](../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java) — handleExecution : rollback confirmé, TH, ownership, COMMITTED/UNKNOWN.
- **C31** — [HandleConsumptionFailureService.java](../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/HandleConsumptionFailureService.java) — handle : effet terminal uniquement sur Fail, retry sinon.
- **C32** — [ExternalIdentityBindingPort.java](../../app/port-binding-authority/src/main/java/com/kartaguez/pocoma/port/binding/authority/ExternalIdentityBindingPort.java) — contrat de participation et initializer synchrone.
- **C33** — [RegistrationConsumptionLocator.java](../../app/supra-consume-registration/src/main/java/com/kartaguez/pocoma/supra/consume/registration/RegistrationConsumptionLocator.java) — exécution, provenance et failure policy sans fact terminal.
- **C34** — [ExecuteRecordedCommandService.java](../../app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/execution/ExecuteRecordedCommandService.java) — execute/fence : appendAll avant fence Binding.
- **C35** — [JpaPotGlobalVersionAdapter.java](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/JpaPotGlobalVersionAdapter.java) — save/updateIfActive : mutations version et CAS Pot.

Services métier (publication unique dans la méthode du tableau §3.1) :

- [CreatePotService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/CreatePotService.java).
- [DeletePotService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/DeletePotService.java).
- [UpdatePotDetailsService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotDetailsService.java).
- [AddPotShareholdersService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/AddPotShareholdersService.java).
- [UpdatePotShareholdersDetailsService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotShareholdersDetailsService.java).
- [UpdatePotShareholdersWeightsService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotShareholdersWeightsService.java).
- [CreateExpenseService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/CreateExpenseService.java).
- [DeleteExpenseService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/DeleteExpenseService.java).
- [UpdateExpenseDetailsService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdateExpenseDetailsService.java).
- [UpdateExpenseSharesService](../../app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdateExpenseSharesService.java).
