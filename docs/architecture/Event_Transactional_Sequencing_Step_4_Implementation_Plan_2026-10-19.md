# Étape 4 — Plan d’implémentation du séquencement transactionnel E

Date : 2026-10-19. Statut : **TARGET — PLAN UNIQUEMENT, NON IMPLÉMENTÉ**.
Baseline de révision : `015ce9180e0743cede83c9a7a3cfef1222173357`, branche `feat/consumption-transaction-protocol`.

## 1. Synthèse exécutive

**Recommandation : READY WITH CONDITIONS.** Conserver **4A contrat → 4B orchestration prouvée hors production → 4C schéma/séquenceur inactifs → 4D raccordement complet PREPARED → 4E cutover/ACTIVE**. La révision remplace la préférence pour `JpaTransactionManager.doCommit` et la fermeture défensive universelle par un propriétaire explicite, des adaptateurs contractuellement conformes et des preuves sur les orchestrations autorisées.

Choix : un runner PRIMARY spécialisé utilise le `TransactionTemplate` Spring standard et place le suffixe **dans son callback transactionnel**, après le retour de l’action englobant exécution et finalisation, avant son propre retour vers `TransactionTemplate`. Le gestionnaire JPA standard reste responsable du commit physique. Le contrat de `TransactionRunner` et l’algorithme Consumption restent inchangés. Le writer BusinessEvent auxiliaire actuellement autonome par `@Transactional` rejoint ce propriétaire explicite : une simple décoration des beans runners existants ne le couvrirait pas.

Le protocole garantit l’ordre pour les compositions autorisées, pas l’impossibilité de toute fraude d’un adaptateur. Les violations constatées sur ces compositions bloquent le passage de lot ; l’impossibilité d’intercepter toute requête JDBC, tout savepoint natif ou toute mutation Hibernate arbitraire ne le bloque plus. Les études 3A–3D restent historiques et inchangées ; leurs expériences n’acquièrent aucune portée supplémentaire.

## 2. Baseline, sécurité et autorité documentaire

Le checkout principal `/Users/julien.guezennec/Dev/projects/pocoma` est sur `feat/runtime-monolith`, HEAD `90926a5e`, avec travaux préexistants Keycloak/docs/Bruno, test Expense et études/preuves non suivis. Aucune écriture n’y est effectuée. Le worktree préexistant `/private/tmp/pocoma-consumption-transaction-protocol` est propre avant modification, sur la branche cible. HEAD et tracking valent le SHA attendu, divergence **0/0** ; `git ls-remote origin refs/heads/feat/consumption-transaction-protocol` confirme le même SHA sur le serveur. Origin fetch/push : `https://github.com/kartaguez/pocoma.git`. La lecture distante a nécessité l’accès réseau hors sandbox après échec DNS initial ; aucun fetch n’a été nécessaire.

Autres worktrees inventoriés et préservés : `/private/tmp/pocoma-2c-differential-baseline` (`8a79f06d`, détaché), `/private/tmp/pocoma-binding-lock-order-guard` (`2eef3ab9`), `/private/tmp/pocoma-expense-context` (`116f4e4a`), `/Users/julien.guezennec/.codex/worktrees/debt-wa6-integration/pocoma` (`2eef3ab9`, détaché). Aucun reset, stash, rebase, merge, checkout ni suppression de worktree.

Autorités : [AGENTS](../../AGENTS.md), [politique normative de vérification](../testing/Reactor_Verification_Policy.md), [contribution](../../CONTRIBUTING.md), [index](../README.md), [gouvernance documentaire](../Documentation_Rationalization_Audit.md#15-gouvernance-documentaire), [validation documentaire](../Documentation_Truth_Validation.md), [Architecture CURRENT](Architecture.md), [garanties CURRENT](../guarantees/System_Guarantees.md), [protocole Consumption CURRENT](consumption-transactional-execution.md), [index transactionnel](transactional-protocol-studies.md). Références historiques conservées : [3A](Event_Terminal_Sequencing_Step_3A_Audit_2026-10-19.md), [3B](Event_Terminal_Sequencing_Step_3B_PostgreSQL_Proof_2026-10-19.md), [3C](Event_Terminal_Sequencing_Step_3C_Transactional_Integration_2026-10-19.md), [3D](Event_Terminal_Sequencing_Step_3D_Producer_Contract_Audit_2026-10-19.md). La décision de cette révision prévaut pour la cible Étape 4 ; elle ne réécrit pas les conclusions expérimentales ni les garanties CURRENT.

Déclaration préalable pour **cette livraison** : impact production/test/DB **NONE**, slices primaire/secondaires **NONE**, vérification documentaire seule ; Maven **interdit**, gate architecture global **NOT REQUIRED**, reactor complet **NOT REQUIRED, interdit et non exécuté**. Les commandes futures du §8 sont un plan. Ce fichier seul est modifié ; les index, études, code, tests, migrations, configurations et POM restent inchangés.

## 3. Décision architecturale — Grammaire d’orchestration et confiance contractuelle dans les adaptateurs

### 3.1 Principes retenus et grammaire d’exécution

Le propriétaire transactionnel impose une trajectoire sans retour métier. Une opération atomique possède une transaction physique ; ses participants ne terminent ni le lot ni la transaction. La phase 3 appartient au propriétaire, indépendamment de Consumption.

```text
TRANSACTION PHYSIQUE — JpaTransactionManager standard
│
├── PHASE 1 — EXÉCUTION
│   ├── décisions et mutations métier
│   └── publications synchrones par les ports autorisés
│
├── PHASE 2 — FINALISATION (ordre CURRENT conservé)
│   ├── effet terminal/finalisation applicative déjà prévu
│   ├── provenance
│   ├── fences/CAS et clôture Consumption éventuels
│   └── flush complet des écritures JPA différées, collecte encore ouverte
│
├── PHASE 3 — SÉQUENCEMENT TERMINAL, code technique fermé
│   ├── fermeture immuable du lot (même si vide)
│   ├── si ACTIVE et lot non vide : E non vides en ordre déterministe
│   ├── réservation d’un bloc par famille
│   └── INSERT des EventPosition typées
│
└── résolution Spring/JPA puis COMMIT PHYSIQUE
    └── observation du résultat et nettoyage après résolution
```

Ce diagramme n’impose pas de déplacer un fence à la frontière graphique des phases. Command conserve exactement P → mutations/append et **flush existant avant B** → B partagé/revalidation → outcome/terminal → provenance → flush/CAS S → clôture Claim. Le flush terminal ajouté vient **après S et la clôture**, sans remplacer ni avancer ces barrières. Registration conserve B exclusif, initializer User/UserCreated, ATTACHED, outcome puis sa finalisation. Les callbacks d’effet terminal existants sont exécutés à leur emplacement CURRENT, avant provenance/S, jamais déplacés dans le suffixe.

L’action englobante existante doit retourner seulement après cette finalisation. Il n’est pas nécessaire de scinder les services métier en nouveaux callbacks génériques. Le propriétaire matérialise alors les écritures différées avant de fermer le lot ; la fermeture est la première instruction de phase 3, avant la première E. Le flush ne publie rien de nouveau par callback métier : les UUID JPA ont été explicitement enregistrés par les writers. Aucun callback applicatif n’est invoqué entre fermeture et retour au gestionnaire.

Spring peut encore invoquer ses synchronisations et Hibernate refaire un dirty checking au commit. Dans les compositions autorisées, ces hooks n’effectuent ni mutation d’entité, ni publication, ni appel métier ; après le flush terminal, le séquenceur ne reçoit ni entité métier ni callback utilisateur. Les contraintes PostgreSQL, le flush résiduel sans nouveau DML métier et WAL/I/O restent possibles sous E. Cette preuve structurelle et les tests remplacent une police permanente du SQL.

### 3.2 Responsabilités par couche

| Couche | Engagement |
|---|---|
| Orchestrateur / propriétaire transactionnel | Ouvrir ou identifier l’unique transaction physique ; exécuter action/finalisation, flush, fermeture puis suffixe une fois ; ne jamais rappeler le métier après fermeture ; laisser toute erreur provoquer le rollback physique ; ne publier le succès qu’après résolution confirmée |
| Adaptateurs de persistance | Opérations synchrones ; participation à PRIMARY courant ; aucun commit autonome d’un sous-effet, savepoint ou rollback partiel ; Events par ports recensés ; UUID exact des INSERT ; aucune publication différée après retour ni callback de commit métier. Le DML JPA différé reste permis et doit être matérialisé avant E |
| Infrastructure de séquencement | Collecteur physique indépendant de Consumption ; familles/UUID exacts ; lot fermé ; ordre des E, blocs consécutifs et associations atomiques ; zéro accès E pour lot vide ; échecs propagés et nettoyage commit/rollback |
| PostgreSQL | Atomicité, intégrité relationnelle des associations et sérialisation des autorités E ; aucune responsabilité de contrôle de la grammaire applicative |

### 3.3 Choix du propriétaire transactionnel

**Option A retenue** : propriétaire PRIMARY explicite, nom provisoire `PrimaryEventTransactionRunner`, dans `infra-persistence-primary-jpa`. Il utilise directement la primitive publique `TransactionTemplate` déjà employée par `SpringTransactionRunner` ; il n’étend pas le manager et ne crée pas un framework transactionnel. Ce choix donne accès au `TransactionStatus` du callback pour les gardes rollback-only, sans modifier le runner générique ni ajouter une dépendance de production vers sa classe concrète. JPA et le séquenceur restent dans PRIMARY, sans dépendance JPA ajoutée à `infra-tx-spring` et sans nouveau port métier.

Composition propriétaire Consumption cible :

```text
ConsumptionOwnerTransactionRunner (guard ambient + classification CURRENT)
  → PrimaryEventTransactionRunner
    → TransactionTemplate.execute(status →
        callback : installer contexte physique lié à status
                   résultat = action.get()  // exécution ET finalisation CURRENT
                   flush complet PRIMARY
                   confirmer les intentions JPA
                   fermer le lot
                   si ACTIVE : séquencer le lot non vide
                   return résultat
      )  // TransactionTemplate résout/commit ici, avant de retourner
```

Le propriétaire logique choisit le protocole ; le gestionnaire JPA standard détient le commit physique. **Interdit** : `result = delegate.runInTransaction(action); sequence();` puisque le commit a déjà eu lieu. Un décorateur acceptable transforme le callback fourni au délégué et exécute E dedans. La sortie du callback reste suivie uniquement de la résolution technique Spring/JPA et de callbacks d’observation contractuellement sans effets métier.

L’entrée propriétaire exige aucune transaction/synchronisation ambiante ; l’état de nouvelle transaction et le manager PRIMARY doivent être prouvés. Un appel participant sous contexte PRIMARY OPEN exécute seulement son action et contribue au lot. Il ne flushe pas à sa sortie, ne ferme rien et ne séquence pas. Les annotations participantes rejoignent le même manager PRIMARY, et le chemin participant du runner ne crée pas un second statut propriétaire. Un ambient sans contexte de propriétaire autorisé est refusé avant toute publication ; la seule présence d’une transaction Spring ne vaut plus couverture E. Pas de suffixe en `finally` ni sur un chemin rollback-only.

### 3.4 Protections bas niveau abandonnées et limites assumées

Abandonnés : surveillance universelle SQL, proxy général de `Connection.setSavepoint`, interception native/unwrap, listeners Hibernate interdisant tous les DML tardifs, hooks généraux de suspension/manager et collecte automatique par listener Hibernate. Aucun de ces mécanismes n’est un prérequis de 4A/4B.

Conserver : contrats d’adaptateurs, gardes locales transaction/contexte/phase des writers, restrictions ciblées des accès repositories et des propagations, audit des callbacks, ArchUnit proportionné et tests PostgreSQL des compositions autorisées. Garder `nestedTransactionAllowed=false` du manager standard ; interdire NESTED/REQUIRES_NEW/NOT_SUPPORTED, savepoints et rollback partiel dans le graphe producteur. TA puis TE puis TR/TH après rollback ne constituent pas une suspension.

Un rogue adapter peut ignorer le collecteur, obtenir une connexion native, créer un savepoint ou modifier une entité après fermeture. Cette violation de contrat est assumée hors garantie structurelle. Une garde de port ne prétend pas intercepter ces accès arbitraires. Les principales violations via les ports autorisés doivent être refusées et testées ; les appels connus de savepoint/callback écrivain doivent échouer aux règles structurelles. Toute violation **démontrée sur un chemin de production autorisé** bloque l’intégration jusqu’à correction ou retrait de ce chemin. L’impossibilité de détecter tous les rogues n’est pas un blocage.

### 3.5 Garanties maintenues

P → B → S et ses flush/fences, TA/TE/TR/TH, observedVersion, retries Consumption, recovery Binding, classifications COMMITTED/ROLLED_BACK/UNKNOWN, contrats et payloads métier, Discovery et ses consommateurs restent CURRENT. La seule modification ciblée de propriété est la composition du runner des producteurs et le transfert de la propriété autonome du writer BusinessEvent vers ce runner. Aucun E ni code métier E dans Consumption ; aucun APPLIED public sans COMMITTED confirmé.

## 4. Comparaison technique, collecte et intégrité

### 4.1 Options A, B et C

Les lettres ci-dessous désignent les options de cette mission, indépendamment des lettres historiques de 3C.

| Critère | A — runner explicite retenu | B — extension `JpaTransactionManager.doCommit` | C — composition minimale par propriétaire |
|---|---|---|---|
| Ordre des phases | Callback complet → flush → fermeture → E, sans rappel métier ; hooks de commit sans métier par contrat | Suffixe après hooks Spring, mais fermeture métier toujours contractuelle ; g de 3C reste possible sans discipline | Chaque owner appelle explicitement une fonction terminale dans son template, avant retour ; risque d’oubli/duplication |
| Commit physique | Manager standard, après retour callback, avant retour runner | `super.doCommit` après suffixe ; provider peut encore flusher | Manager standard après callback propre à chaque owner |
| Participants | Contexte existant partagé ; aucun suffixe à leur retour ; ambient inconnu refusé | Automatiquement couverts si même manager ; suffixe à la résolution physique | Nécessite convention commune et contrôle de chaque owner |
| Producteurs autonomes annotés | Migration explicite du seul writer autonome Event recensé ; annotation extérieure propriétaire non conservée | Couverts sans migration si même manager | Migration ou adaptation de chaque propriétaire annoté, même limite que A |
| TA/TE/TR/TH | Injection du runner sous guard existant ; lots physiques neufs ; TA/retry vides sans E | Couverture implicite par manager de chaque frontière | Suffixe à ajouter à chaque wrapper, risque de couplage Consumption |
| Registration / Binding | Runner Registration et owners Binding autorisés ; collecte au repository commun, y compris `new` | Même manager requis ; collecte explicite toujours nécessaire | Même inventaire mais composition répétée |
| Collecteur | Ressource transactionnelle installée dans callback propriétaire, cleanup après résolution | Hooks begin/completion/suspension à maintenir | Même ressource à gérer dans chaque composition |
| Exceptions / UNKNOWN | Échec du callback terminal → rollback par template standard ; résolution existante observée, UNKNOWN conservé | Échec avant `super.doCommit` exige preuve du chemin rollback Spring et options manager | Template standard mais risque de traductions hétérogènes |
| Intégration | Quelques owners/writers migrés, inventaire exhaustif exigé ; aucune couverture automatique d’un owner futur | Remplacement bean Boot, customizers, parité de configuration et lifecycle manager | Peu d’abstraction mais duplication de la grammaire et des preuves |
| Internes Spring/Hibernate | APIs publiques runner/template/synchronisation et `EntityManager.flush/persist` ; pas de hooks provider | Dépendance aux hooks protégés du manager et ordre Spring/Boot/provider | APIs publiques ; coût de maintenance par owner |

B reste faisable expérimentalement, mais sa portée automatique n’est plus une raison suffisante pour le préférer. C n’apporte pas de garantie supérieure à A et disperse le suffixe ; non retenue. Une synchronisation `beforeCommit` ne remplace aucune option : sa priorité ne garantit pas qu’aucun callback écrivain ne suivra. Si A ne couvre pas un owner réel, le migrer explicitement ou revoir le plan avant 4D, sans basculer silencieusement sur B.

### 4.2 Collecteur associé à la transaction physique

Ressource technique attachée via `TransactionSynchronizationManager` au couple manager/EMF PRIMARY, installée **après ouverture physique** et avant l’action, jamais ThreadLocal libre ni objet Consumption. Le propriétaire enregistre immédiatement le cleanup `afterCompletion` et conserve une commande technique `status.setRollbackOnly` pour les gardes locales. Vérifier POISONED/rollback-only avant flush et avant E, même si l’appelant a intercepté une exception. États : OPEN → CLOSED → CLEANED ; échec de contrat local : POISONED et rollback-only irréversible avant exception. Une exception interceptée par le métier ne doit pas rendre le lot committable.

Les participants REQUIRED/MANDATORY utilisent exactement cette ressource. Le runner racine est le seul à fermer ; les appels imbriqués sous contexte OPEN sont participants. Pas de création de contexte de secours pour une transaction inconnue, pas de réutilisation entre TE et TR/TH. Au commit/rollback, détacher et vider la ressource ; en cas d’échec d’installation/enregistrement du cleanup, échouer le callback et nettoyer ce qui a été installé. Tester aussi les échecs de begin, sans contexte créé, et de rollback/commit incertain ; ne pas effacer la preuve de résolution du wrapper.

Chaque writer vérifie transaction PRIMARY et contexte OPEN avant `persist` ou INSERT JDBC, y compris les corps appelés directement. Collecter `(famille, UUID)` avec origine unique. Un échec de capture après écriture empoisonne le contexte et le statut avant de lever ; aucune publication non collectée ne devient committable si l’exception est interceptée. Chaque point d’INSERT collecte une seule fois ; un double enregistrement du même couple est une erreur locale, pas un doublon silencieusement absorbé. Un UUID identique entre familles reste distinct. Les UUID JDBC sont confirmés après INSERT strict affectant exactement une ligne. Les UUID JPA sont des intentions explicites jusqu’au succès du flush terminal. Aucune position n’est réservée pour une intention non matérialisée.

**Choix JPA** : les deux writers outbox utilisent une primitive locale insert-only `EntityManager.persist` pour des entités neuves à UUID préassigné, puis enregistrent leur intention ; aucun `merge`/upsert et aucune confirmation par simple retour de `save`. Conserver un flush au point CURRENT de `appendAll` avant B ; l’append auxiliaire peut rester différé. Le flush complet final réussi matérialise toutes les intentions encore ouvertes, sans listener PostInsert. PK/contraintes font échouer les collisions ; les chemins autorisés ne peuvent supprimer les entités nouvellement publiées ni remplacer les UUID. Les tests compareront exactement les UUID collectés aux INSERT durables. La participation du repository de lecture reste autorisée, sans accès d’écriture hors writers.

Après finalisation et flush, confirmer les intentions puis fermer un snapshot immuable ; aucune référence métier ne passe au séquenceur. La garde des ports refuse publication après CLOSED et empoisonne la transaction. Structurellement, le callback métier est déjà revenu et le suffixe n’a aucun callback d’extension : il n’existe aucun retour métier autorisé après fermeture. Les hooks pré-/post-commit existants sont inventoriés comme observations sans écriture ; les futurs hooks écrivains sont interdits dans ce graphe. Une transaction vide ferme/nettoie son contexte et commit normalement, sans lecture/verrou E, ni réservation/association. Le flush terminal peut toutefois être exécuté même dans cette transaction : absence d’E ne signifie pas absence de changement transactionnel.

### 4.3 Familles, réservation et intégrité relationnelle

| Rang E stable | Famille | Table CURRENT / UUID | Association cible |
|---|---|---|---|
| 1 | BusinessEvents (Pot et Expense, autorité commune) | `business_event_outbox.id` | `business_event_positions` |
| 2 | CommandTerminalEvents | `command_terminal_events.event_id` | `command_terminal_event_positions` |
| 3 | BindingFacts | `external_identity_binding_facts.event_id` | `binding_fact_positions` |
| 4 | UserCreatedFacts | `user_created_facts.event_id` | `user_created_fact_positions` |

Quatre lignes initialisées à zéro, une par famille, dans `event_sequence_authorities(family PK, last_position bigint NOT NULL CHECK >= 0)`. Pas de `nextval`, identity ou cache hors transaction. Pour N UUID d’une famille : UPDATE de l’autorité avec protection overflow et `RETURNING`, bloc `[r-N+1,r]`, INSERT strict des N associations. E acquises uniquement pour les familles non vides, dans l’ordre fixe 1 → 4 et conservées jusqu’à résolution physique. Trier les UUID par texte canonique Java indépendant de la collation ; ce tri ne signifie ni causalité ni ordre des appels. Pas d’ordre global promis entre familles disjointes.

Quatre tables typées `(event_id UUID NOT NULL UNIQUE, position bigint PRIMARY KEY CHECK > 0)` avec FK immédiate restrictive vers la source ; vue `event_position(family,event_id,position)` par UNION ALL. Pas de FK association → autorité E ajoutant un verrou E avant le suffixe, ni colonne position dans les Events/payloads. Droits applicatifs sans UPDATE/DELETE des positions et sans DDL/mutation libre des autorités ; maintenance exclusive distincte. Les réservations exigent exactement une ligne d’autorité modifiée ; ni upsert ni `ON CONFLICT DO NOTHING` pour les associations. Aucun RegistrationTerminalEvent n’est créé ; rétention/purge nouvelle hors scope. Le séquenceur JDBC utilise la connexion de la même transaction JPA ; aucun mapping JPA des positions, aucun appel métier, aucun retry local.

Conserver une protection de **complétude relationnelle** ACTIVE par quatre constraint triggers différés vérifiant, par UUID indexé inséré, l’existence de sa position. Ils ne numérotent rien, n’analysent pas le SQL et n’imposent aucune phase applicative. La FK protège Position → Event ; ces contrôles protègent Event → Position pour les nouveaux INSERT. Leur coût et leurs attentes sont à prouver en 4C/4D, sans DML métier ni acquisition P/B/S ; pas de scan global au commit. Ils ne prouvent pas l’ordre métier avant E et ne remplacent pas le propriétaire. Une ancienne instance oubliant E échoue à cette intégrité, mais le cutover exclut de toute façon les anciens writers. Le rôle applicatif ne peut désarmer ces contraintes. Aucun système général d’interception ni API SQL métier nouvelle n’est requis.

Un seul mode durable `event_sequencing_protocol` PREPARED/ACTIVE avec version de protocole, jamais par famille. Compatibilité contrôlée au démarrage, bascule seulement sous gel total ; pas de verrou de mode ajouté au suffixe vide. Les associations et compteurs rollbackent avec les effets ; aucune réparation locale après échec. Les Events historiques nécessitent les anti-jointures exhaustives du §7, que les triggers ne certifient pas rétroactivement.

### 4.4 Résolution, exceptions et propriétaires autonomes

[ConsumptionOwnerTransactionRunner](../../app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/ConsumptionOwnerTransactionRunner.java), [SpringTransactionRunner](../../app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/SpringTransactionRunner.java) et [SequentialConsumptionOrchestrator](../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/SequentialConsumptionOrchestrator.java) conservent leur algorithme. Le suffixe s’exécute après le retour de l’action passée par le wrapper : son échec peut donc être rapporté phase COMMIT, outcome ROLLED_BACK si le rollback est confirmé, bien que l’erreur survienne avant l’appel au commit physique. Tester exactement cette composition, sans déplacer le suffixe dans l’action métier du wrapper et changer sa classification.

Échec flush/fermeture/réservation/association : laisser remonter dans le callback du template ; rollback standard de toute l’opération. Le runner spécialisé conserve les mêmes sémantiques publiques de `runInTransaction` et `runAfterCommit` que le runner Spring, avec des callbacks après commit limités à l’observation ; aucun résultat autonome n’est retourné avant résolution du template. Ne jamais avaler pour retourner un résultat. Échec de résolution : afterCompletion COMMITTED/ROLLED_BACK/UNKNOWN reste l’autorité ; aucune absence d’accusé transformée en rollback. COMMITTED/UNKNOWN n’entraînent pas une mutation FAILED automatique, pas de retry du séquenceur. Les causes de contention suivent les policies CURRENT après résolution confirmée.

Le writer auxiliaire `JpaBusinessEventOutboxAdapter.append`, aujourd’hui REQUIRED autonome ou participant, garde son port et ses deux usages mais transfère sa propriété : retirer son annotation propriétaire extérieure et exécuter le corps via le runner PRIMARY. Sans ambient, il ouvre l’unique transaction ; sous contexte OPEN, il participe sans suffixe. Une annotation REQUIRED conservée à l’extérieur créerait une transaction inconnue avant le runner et est donc exclue. La fermeture autonome ne doit intervenir qu’après son corps ; plusieurs appels sous un owner englobant partagent le lot. Les autres ports restent MANDATORY ou gardés dans leur corps. Tout futur use case autonome producteur déclare le même owner ; aucun producteur annoté autonome laissé hors inventaire n’est réputé couvert.

## 5. Carte des producteurs et portée des changements futurs

Les liens pointent vers CURRENT ; les noms nouveaux sont provisoires et n’existent pas encore.

| Source | Raccordement explicite prévu | Preuve attendue |
|---|---|---|
| [JpaPotCommandEventAppendAdapter](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaPotCommandEventAppendAdapter.java) | Intentions UUID exactes via primitive JPA insert-only ; conserver flush avant B et artifacts | Pot/Expense, N et liste vide, collision rollback, UUID collectés = insérés |
| [JpaBusinessEventOutboxAdapter](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/outbox/JpaBusinessEventOutboxAdapter.java) | Même primitive ; propriété autonome transférée au runner explicite ; pas de flush participant supplémentaire | Autonome, participant, multiples appels, flush différé et rollback |
| [JdbcCommandOutcomeAdapter](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/command/JdbcCommandOutcomeAdapter.java) | Extraire UUID terminal inline, collecter après second INSERT réussi, pas après seul outcome | TE APPLIED/REJECTED, TH FAILED, TR recovery ; port void inchangé |
| [ExternalIdentityBindingFactJdbcRepository](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/identity/ExternalIdentityBindingFactJdbcRepository.java) | Garde/capture dans le corps commun `append`, après INSERT unitaire ; aucun second enregistrement au port auxiliaire | ATTACHED/DETACHED, port fact auxiliaire, plusieurs facts, instances bean et `new` |
| [JpaExternalIdentityBindingAdapter](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/identity/JpaExternalIdentityBindingAdapter.java) | Transmettre la dépendance de garde/collecte au repository actuellement construit par `new`, comme au bean ; annotation seule insuffisante | Constructeur réel et initializer Registration ; aucun INSERT lifecycle oublié |
| [JdbcUserCreatedFactAdapter](../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/registration/JdbcUserCreatedFactAdapter.java) | Garde de corps compatible classe final ; UUID local exact puis capture après INSERT | Refus hors owner ; gagnante Registration, rejet/existing sans Event, rollback |

Six adaptateurs producteurs, cinq points d’INSERT : deux outbox JPA, Terminal, Binding partagé, UserCreated. Le port auxiliaire Binding est couvert par son repository et ne collecte pas une seconde fois. V22 reste un writer historique SQL hors runtime : backfill, aucune instrumentation/réécriture.

| Propriétaire / chemin | Cible, sans déplacement de frontière |
|---|---|
| Command TA/TE/TR/TH | Injecter le runner PRIMARY sous le guard propriétaire existant ; TE complète avant E ; TR/TH seulement après rollback TE confirmé, lot neuf ; TA et TH RETRY sans E |
| Registration TA/TE/TH | Même substitution de composition ; TE gagnante produit UserCreated + ATTACHED puis finalisation/S/Claim avant E ; rejet/déjà traitée et TH sans faits ne prennent pas E |
| Binding acquire/detach/fact auxiliaire hors Registration | Ports participants, owner explicite englobant obligatoire pour tout appel autonome autorisé ; initializer synchrone avant phase terminale ; pas de transaction par sous-port |
| BusinessEvent autonome | Migration explicite du writer décrite §4.4 ; autonomie d’un Event seul conservée, pas de commit séparé des mutations d’une même opération |
| Owners annotés/templates directs non producteurs | Inchangés ; ne gagnent pas le droit de publier. Un ajout de publication impose migration explicite du propriétaire avant raccordement |
| Admission, Event/Binding consumers, Task, LKV, Results, READ | Pas producteurs de ces quatre familles à la baseline ; aucune modification de Discovery ni substitution globale du manager ; compositions vérifiées selon impact réel |

Composants futurs : `infra-persistence-primary-jpa` contient gardes, primitive insert-only, runner PRIMARY, collecteur, flush et séquenceur/DDL ; `composition-runtime-spring` câble les runners Command/Registration et l’usage auxiliaire sans copier le protocole par worker. `infra-tx-spring` et `port-transaction` restent inchangés ; aucune dépendance JPA n’y est ajoutée. Les engines/supras/domaines conservent leur logique, leurs ports et signatures ; seuls les contrats documentés des producteurs peuvent être précisés en 4A. `architecture-tests` contrôle accès et propriétaires lorsque le gate global est requis. Les tests des workers Command, Registration et du monolith prouvent les compositions réelles ; WEB/BINDING couvrent les usages partagés. Une dépendance POM non prévue ou un nouvel owner exige déclaration d’impact avant changement, pas une refonte opportuniste.

## 6. Lots ordonnés et gates

Dépendances conservées : **4A → 4B → 4C → 4D → 4E**. Aucun lot n’est autorisé par cette livraison. Chaque lot relit AGENTS/politique au HEAD approuvé, déclare son scope du §8 et rapporte les preuves réellement exécutées. Un DDL jetable peut servir aux tests 4B, sans devenir une migration livrée.

### 4A — Contrat et sécurisation des producteurs

- **Objectif / garanties** : R1 transaction atomique, R2 ports/UUID identifiés, R3 aucun rollback partiel, R4 callbacks de commit sans métier, R5 pas de commit autonome de sous-effet ; garantie contractuelle sur chemins autorisés, sans E.
- **Composants / modifications** : gardes de corps transaction PRIMARY pour UserCreated, Binding repository direct et outcome Registration ; contrats des ports et allowlist writers/repositories/factories. Interdire propagations fractionnantes, savepoints, commits manuels et callbacks écrivains dans ce graphe. Conserver l’autonomie auxiliaire CURRENT jusqu’au raccordement atomique 4D ; ne pas imposer un collecteur encore inexistant.
- **Tests** : PostgreSQL appel direct/new hors transaction refusé sans ligne durable, Command APPLIED/REJECTED et Registration gagnante/rejetée/rollback inchangées ; ArchUnit ciblé avec fixtures négatives pour accès repository interdit, propriétaire non déclaré, propagation/callback interdit. Distinguer lecteurs autorisés et writers.
- **Acceptation / passage** : tous INSERT runtime recensés et contrats affectés listés ; gardes efficaces sans proxy ; aucune violation connue sur chemin autorisé ; aucun changement métier/P/B/S/retry. Passer à 4B après preuve V-A et gate structurel. Ne pas exiger la détection de SQL/savepoints arbitraires pour clore 4A.
- **Risques / arrêt** : faux positifs lecteurs et usages auxiliaires, transaction par sous-port découverte, callback écrivain réel ; corriger la composition dans le périmètre ou revoir le plan. Pas de durcissement global des ports Pot.
- **Runtime livré** : CURRENT avec gardes/contrats locaux, ni collecte ni E ni schéma. Retour au runtime précédent par revert du lot sans opération DB.

### 4B — Orchestration transactionnelle commune, non raccordée en production

- **Objectif / garanties** : construire et prouver la grammaire, le runner propriétaire/participant, le collecteur et la fermeture ; une résolution physique et un suffixe terminal par transaction productrice. **Aucun séquencement de production activé.**
- **Composants / modifications** : classes techniques PRIMARY et configuration importable seulement dans les tests, non découverte par scan/autoconfiguration ; composition de test avec guard Consumption CURRENT, template et manager Spring standards. États OPEN/CLOSED/POISONED/CLEANED, flush complet, snapshot immuable, cleanup. Primitive JPA insert-only testée et writer synthétique ; migration des vrais producteurs différée en 4D. Aucune extension `doCommit`, aucun listener Hibernate ni proxy de connexion livré.
- **Tests** : PostgreSQL même PID/xid JPA/JDBC ; propriétaire, REQUIRED/MANDATORY et runner participant sans suffixe prématuré ; ambient inconnu refusé ; un seul suffixe malgré plusieurs participants ; zéro Event sans E même si E verrouillées ailleurs ; rollback action/flush/suffixe, rollback-only, begin échoué, nettoyage après rollback/commit et deux transactions successives sur même thread. Échec de garde de publication avalé par un participant reste rollback-only ; les échecs de fermeture/association, exécutés hors action métier, remontent directement au template. Un callback écrivain `beforeCompletion` est exclu structurellement : Spring pouvant ignorer son exception, une garde qui lève ne constitue pas sa sécurisation. Le test négatif doit prouver son rejet par les règles d’architecture, sans revendiquer une interception universelle de hooks arbitraires. Tests de classification COMMITTED/ROLLED_BACK/UNKNOWN avec composition exacte du §4.4.
- **Tests de grammaire** : marqueurs action → finalisation/S/Claim → flush → fermeture → E, aucun retour métier ; refus publication via port fermé, même exception interceptée ; absence de point d’extension métier dans le séquenceur ; callbacks autorisés d’observation sans mutation. Le contre-exemple g de 3C devient une injection métier **non autorisée** après fermeture : tester son exclusion structurelle et le refus par les ports, sans prétendre détecter toute mutation directe de référence Hibernate. Tout hook écrivain effectivement présent en composition doit être retiré/replacé avant E.
- **Acceptation / passage** : V-B vert, rollback physique observé et aucune fuite ; preuve que E est dans callback avant commit et après S ; inventaire des owners autonomes à migrer terminé. Une anomalie dans la composition réelle bloque 4C/4D ; la possibilité théorique d’un rogue ne bloque pas. Global gate selon §8, pas de full reactor ordinaire.
- **Risques** : décorateur extérieur trop tardif, fermeture au retour participant, annotation autonome oubliée, callback technique écrivain, classification d’échec modifiée. Chacun reçoit le test ciblé ci-dessus.
- **Runtime livré** : code technique dormant, owners et writers de production CURRENT inchangés ; aucune E/migration de production. Retrait de ce code sans opération DB.

### 4C — Schéma et séquenceur inactifs

- **Objectif / garanties** : autorités par famille, associations typées, ordre et allocation par blocs atomiques sous vraie transaction ; PREPARED durable, aucune numérotation runtime.
- **Composants / modifications** : migration additive PRIMARY après version courante (V29 à cette baseline), quatre E/tables, vue et contraintes de complétude conditionnées ACTIVE, droits séparés, séquenceur JDBC fermé. Pas de raccordement des producteurs réels ni des owners de production.
- **Tests** : PostgreSQL vrai DDL et adaptation des scénarios 3B : N Events/famille, ordre E opposé entre transactions, contention observée serveur, rollback après chaque réservation/INSERT, collisions/FK/overflow et interdictions UPDATE/DELETE ; absence de verrou E si lot vide. Vérifier PREPARED sans associations, ACTIVE refus Event sans position, pas de nouveau verrou métier P/B/S dans les contraintes de fin ; coût indexé mesuré. Tester upgrade depuis schéma courant et rollback applicatif compatible PREPARED.
- **Acceptation / passage** : V-C et contraintes/compteurs corrects ; aucun séquenceur appelé en production ; droits et coût des contraintes prouvés. Si l’intégrité ou le suffixe introduit une arête métier après E, arrêter pour correction locale/décision, sans demander à la DB de contrôler la grammaire.
- **Risques** : FK mal orientée, overflow, DDL ou trigger coûteux, bootstrap historique inutile. Suivre §8 pour DB CURRENT ; ne pas inventer une baseline trusted.
- **Runtime livré** : schéma PREPARED additif, E à zéro et associations vides, code séquenceur non appelé. Retour au binaire précédent possible en gardant le schéma PREPARED ; aucune migration inverse automatique.

### 4D — Raccordement complet, production PREPARED

- **Objectif / garanties** : raccordement atomique des quatre familles et de tous leurs owners autorisés, sans activation E en production. Même grammaire finale dans PREPARED et ACTIVE.
- **Composants / modifications** : toutes captures §5, y compris repository Binding `new` et bean, primitive JPA insert-only sans déplacer flush avant B, garde contexte OPEN, injection du runner sous owners Command/Registration, migration du writer autonome §4.4. Aucun double collecteur ni capture au simple publish mémoire. Vérifier manager/EMF/datasource réels dans les compositions distribuées et monolith sans remplacement du manager.
- **Tests** : V-D, PostgreSQL chemins réels : Command APPLIED (Business + Terminal), REJECTED (Terminal), TA et TH retry vides, TH FAILED (Terminal), TR après rollback TE (Terminal), claim perdu/CAS/fence refusés (aucun effet durable) ; Registration gagnante (UserCreated + ATTACHED), rejet/existing sans Event, initializer/fin de traitement en échec ; Binding acquire/detach/fact auxiliaire et N facts sous owner ; append Business auxiliaire autonome/participant. Comparer ensembles UUID exacts ; suffixe après S/Claim ; aucun DML métier après E sur les chemins autorisés ; lots propres après recovery. Tester ACTIVE seulement sur bases isolées avec les contraintes finales.
- **Acceptation / passage** : toutes familles et owners certifiés ensemble, aucun INSERT oublié, tests négatifs ports hors owner/fermés, doublon et callback non autorisé ; mêmes résultats, fences/retries/observedVersion et classifications. PREPARED répété en composition réelle, reprise runtime précédent répétée avant activation ; pas de canary par famille. Violation réelle ⇒ STOP avant 4E.
- **Risques** : changement de propriété autonome ou flush terminal sous-estimé, garde oubliée dans `new`, manager divergent, callback non inventorié. Un simple flag désactivant E ne valide pas ces changements.
- **Runtime livré** : **PREPARED complet décrit ci-dessous** ; E/positions production toujours inactifs. Pas de nouvelle Discovery. Le passage à ACTIVE doit seulement activer le même suffixe E, sans nouvelle refonte des owners.

### 6.1 Contrat de livraison PREPARED

| Aspect | État exact après 4D |
|---|---|
| Actif | Owner explicite, distinction participants, collecteur quatre familles, insert-only JPA, gardes contexte/phase/poisoning, flush terminal, fermeture et cleanup ; schéma/version PREPARED et vérification au démarrage |
| Changement observable | Writer autonome passe par runner ; ambient non autorisé refusé ; double capture/publication tardive refusée ; flush complet après finalisation avant résolution ; exceptions possibles plus tôt. Ce sont des changements transactionnels même sans E |
| Déjà garanti | Même transaction des effets autorisés, finalisation avant fermeture, aucun retour métier, collecte exacte et lot fermé, rollback des erreurs, absence de savepoint/callback métier par contrat et règles ciblées |
| Inactif | Acquisition/réservation E, INSERT positions, complétude Event → Position ACTIVE et toute consommation des positions. Aucune prétention de couverture historique ni d’ordre E en production |
| Branche terminale | Même fonction terminale dans les deux modes : flush/confirmer/fermer ; PREPARED termine sans SQL E, ACTIVE utilise ce snapshot pour E. Pas de fallback automatique si ACTIVE échoue |
| Retour au runtime précédent | Avant tout ACTIVE, arrêter/drainer les writers, confirmer PREPARED/version et absence de positions officielles, redéployer le binaire précédent certifié en laissant le DDL additif ; pas de downgrade à chaud avec transactions pendantes. Répéter cette procédure en 4D ; si elle échoue, bloquer le déploiement PREPARED |
| Activation ultérieure | Un seul basculement durable après backfill validé, tous nouveaux owners déjà éprouvés ACTIVE en base isolée ; redémarrage homogène et contrôle version. Aucun changement de logique métier/transaction à introduire au flip |

Une transaction PREPARED produit encore des Events sans position par définition. Les nouveaux writers ne peuvent se mêler à ACTIVE tant que le cutover n’est pas confirmé. Après activation officielle, aucun retour simple à l’ancien binaire : procédure 4E.

### 4E — Cutover et activation

- **Objectif / garanties** : traiter tous Events historiques conservés, prouver complétude et continuité, puis activer atomiquement les quatre familles. Pas de reconstruction fictive de causalité historique.
- **Composants / modifications** : outil/runbook de maintenance exclusive, contrôle version/mode/droits, backfill UUID et compteurs, anti-jointures et validation finale ; documentation CURRENT actualisée à la livraison effective, sans changer Discovery.
- **Tests** : répétition sur copie représentative, reprise après rollback ou commit UNKNOWN sous gel, ancien writer incompatible refusé par intégrité, premiers blocs après frontière N, charge/attentes E/temps sous E, restauration et reprise opérationnelle. V-E et gates majeurs du §8 avant activation ; preuve d’upgrade depuis version déployée réellement supportée.
- **Acceptation / passage** : §7 intégralement satisfait, sauvegarde restaurable, seuils débit/p95/backlog/arrêt définis par responsable et tenus, pour chaque famille `count(Event)=count(Position)=E`, anti-jointures bidirectionnelles à zéro, positions distinctes positives avec min=1/max=E si non vide et E=0 sinon ; mode unique et aucune ancienne instance/import concurrent ; **autorisation explicite de l’environnement avant toute activation déployée**. Une autorisation du plan n’autorise pas le flip.
- **Risques / arrêt** : cardinalité ou couples UUID divergents, ordre historique réel exigé, gel incomplet, coût ou fenêtre dépassés, commit UNKNOWN non réconcilié : conserver le gel et arrêter l’activation.
- **Runtime livré** : ACTIVE quatre familles, historique associé avec frontière publiée ; commit commun Event/position. Avant flip, retour PREPARED après résolution confirmée ; après flip/premier commit ACTIVE, arrêter les producteurs et corriger en avant. Désactivation exceptionnelle exige décision explicite, gel, conservation positions et nouveau cutover avant réactivation ; restauration perdant des commits jamais automatique.

## 7. Stratégie historique et cutover opératoire

**Périmètre initial : toutes les lignes conservées des quatre tables au gel**, y compris faits `MIGRATION_BASELINE` de V22 et Events déjà consommés. Ne pas filtrer par status outbox, type de consumer, Claim, timestamp métier ou résultat. La situation d’une base déployée n’est pas déduite des migrations du dépôt : inventorier avant action.

L’ordre réel des commits historiques est perdu. Choix privilégié : ordinal historique arbitraire mais reproductible par famille, tri **UUID canonique texte avec collation C explicitement fixée**, positions 1..N ; le compteur E démarre ensuite à N. Le rapport de cutover enregistre pour chaque famille N et sa frontière historique. Ne pas présenter ce tri comme une causalité ou une reconstitution des anciens commits. Alternatives rejetées : timestamp métier/recorded_at comme ordre de commit, xmin/xid comme ordre de commit conservé, positions seulement pour nouveaux Events avec trous historiques silencieux. Si un besoin métier exige un ordre historique réel, cette étape ne peut pas le fournir et doit s’arrêter pour décision.

Procédure privilégiée, à automatiser et répéter en 4E :

1. Vérifier DB PREPARED/version, binaire complet certifié, sauvegarde restaurable, droits app/maintenance et volume. Mesurer durée estimée ; établir budget d’arrêt avant exécution.
2. Arrêter producteurs Command/Registration, monolith, owners auxiliaires, imports et maintenance concurrente ; empêcher de nouveaux démarrages. Arrêter admissions si nécessaire pour maîtriser la file et la durée. Les readers peuvent rester ouverts si leurs verrous/transactions n’empêchent pas l’opération ; cette permission est vérifiée, pas supposée.
3. Drainer/terminer selon procédure approuvée toutes transactions productrices et vérifier `pg_stat_activity`/verrous ; obtenir verrou de maintenance exclusif et verrous de tables empêchant tout INSERT concurrent sur les quatre sources et associations, dans un ordre constant. Le gel opérationnel seul ne remplace pas la preuve DB d’exclusion. Aucun producteur normal n’acquiert E en amont d’une demande de verrou métier.
4. Prendre inventaire stable des UUID et cardinalités ; associations préparatoires attendues vides sur première exécution. Backfill dans **une transaction de maintenance** si le volume répété tient le budget : quatre séries typées, FK intactes, compteurs mis à N. Vérifier tous les invariants ci-dessous avant mode ACTIVE.
5. Si volume trop élevé pour une transaction : **STOP au gate de répétition**, faire approuver une variante bornée de chargement par lots sous gel maintenu, avec manifeste stable, reprise contrôlée, égalité des couples déjà présents et validation finale exclusive. Ne pas activer des familles ou segments au fil de l’eau. La variante n’est pas une permission implicite de `ON CONFLICT DO NOTHING` masquant une divergence.
6. Contrôler chaque UUID exactement une fois, aucune association orpheline et continuité ; pas seulement count/max. Valider droits et triggers puis passer l’unique état DB/version à ACTIVE dans le même commit de maintenance. Si commit inconnu, conserver le gel et relire sur nouvelle connexion ; valider les données avant toute reprise.
7. Redémarrer seulement versions complètes ACTIVE, vérification de mode/propriétaire/ports avant workers. Les premières transactions produisent leurs blocs après N. Vérifier à nouveau les anti-jointures et positions ; un INSERT de test sans association doit être rejeté par la contrainte de complétude.
8. Lever le gel selon ordre opératoire approuvé ; observer latence, attente E, temps sous E, attente P/B/S, erreurs et file, avec seuils fixés. En dépassement : arrêter les producteurs et diagnostiquer, garder protocole et positions ; pas de désactivation automatique des contraintes ou des retries.

Reprise avant activation : après rollback de maintenance confirmé, EventPosition et E restent à leur état précédent ; rejouer avec le même inventaire sous gel. Si des données préparatoires ont été conservées par une variante approuvée, comparer chaque couple exact et refuser toute divergence. Après ACTIVE, les positions sont immuables, pas de renumérotation. Aucun replay V22, aucune altération de payload, aucun reset des slots, Claims ou Outcomes.

## 8. Stratégie de vérification normative

Toutes les commandes ci-dessous sont **futures**, lancées depuis `app/` uniquement après autorisation du lot. Elles ne doivent pas être exécutées pour ce plan. Lire la politique au HEAD de chaque lot et déclarer le scope avant modification.

### 8.1 Commandes et frontières

| Scope | Primaire ; secondaires | Commande canonique de clôture du lot | Complément explicitement borné |
|---|---|---|---|
| V-A | COMMAND ; BINDING, WEB (Registration/ports partagés) | `./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker,runtime-web-api -am test` | Test réel Registration : `./mvnw -pl runtime-registration-consumption-worker -am test` ; gate architecture ci-dessous |
| V-B | COMMAND ; NONE | `./mvnw -pl runtime-command-consumption-worker -am test` | Preuves locales PRIMARY incluses via `-am`, orchestration expérimentale explicitement importée |
| V-C | COMMAND ; BINDING | `./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker -am test` | Bootstrap/DDL et triggers PostgreSQL réels, aucune simulation H2 pour les propriétés E |
| V-D | COMMAND ; BINDING, WEB | `./mvnw -pl runtime-command-consumption-worker,runtime-binding-consumption-worker,runtime-web-api -am test` | `./mvnw -pl runtime-registration-consumption-worker,runtime-monolith -am test` pour compositions/Registration non couvertes par ancre canonique ; gate architecture |
| V-E | COMMAND ; WEB, BINDING, EVENT, PROJECTION, LKV | `./mvnw -pl runtime-command-consumption-worker,runtime-web-api,runtime-binding-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker,runtime-latest-known-version-consumption-worker -am test` | Registration/Results/monolith au gate d’intégration ; global architecture et full reactor de clôture |

La politique ne définit **pas** de slice canonique Registration, Results ou monolith. Le complément les nomme comme ancres de preuve de composition réelle, sans inventer une slice ni prétendre que BINDING inclut le worker Registration. Les tests Registration de PRIMARY peuvent être dans `-am`, mais ne suffisent pas pour certifier la configuration de son worker.

Gate architecture global : `./mvnw -pl architecture-tests -am test`. **REQUIRED en 4A et 4D** car les restrictions de dépendances/accès sont l’objet du changement ; **REQUIRED en 4E** pour la clôture architecturale. 4B/4C : **NOT REQUIRED par défaut**, sauf dépendance ajoutée, frontière modifiée ou autre condition §17 de la politique ; les règles locales déjà dans les slices restent exécutées. Toute modification de POM ajoutant une dépendance entraîne une révision préalable du gate, jamais un ajout automatique opportuniste.

Full reactor : `./mvnw test`. **NOT REQUIRED en clôture ordinaire 4A–D**, impact borné et preuves composées ; **REQUIRED au gate majeur 4E**, qui clôt une vague d’infrastructure transversale, puis au gate d’intégration main selon politique. Si l’impact de 4B/D devient non bornable, arrêter pour l’expliquer et re-déclarer la vérification ; ne pas lancer FULL pour compenser une hypothèse non examinée. L’exécution ciblée n’est pas remplacée par un full reactor tardif.

### 8.2 Déclaration commune obligatoire, spécialisée par lot

Chaque prompt/rapport de lot doit reprendre explicitement :

- **Expected production impact** : uniquement modules et comportements listés dans son lot ; 4B propriétaire/collecteur non câblés, 4C DDL inactif, 4D PREPARED, 4E ACTIVE.
- **Primary/secondary slices** : V-A/B/C/D/E ci-dessus, ancres supplémentaires distinguées de slices canoniques.
- **Forbidden production impact** : algorithmes Consumption, P/B/S, TA/TE/TR/TH, classification/retry, Discovery et contrats/payloads métier ; aucun runtime-to-runtime dependency.
- **Required local proof** : tests du lot, cas négatifs et PostgreSQL avec vraies contraintes et connexions distinctes ; aucun PASS déduit d’un audit.
- **Global architecture gate / full reactor** : statut explicite selon §8.1 ; gate prochain : 4A structure, puis 4D structure, puis clôture majeure 4E/intégration.
- **Database verification** : 4A CURRENT pour gardes ; 4B CURRENT/prototypes isolés ; 4C CURRENT + upgrade nouveau DDL ; 4D CURRENT ACTIVE isolé ; 4E CURRENT + historique/cutover représentatif autorisé.
- **Escalation conditions** : nouvel Event store, owner hors cartographie §5, nouveau manager/datasource, dépendance/POM, autre slice de comportement, besoin de SQL métier après E ou support de savepoint ; re-déclarer **avant** changement.

La politique mentionne une candidate V23 et **Trusted baseline NONE**, tandis que CURRENT possède V1–V29. Ne pas la transformer silencieusement en baseline V29 certifiée. Si une baseline trusted est certifiée entre-temps, l’utiliser avec les migrations postérieures pour les tests CURRENT ; sinon conserver les bootstraps existants nécessaires et déclarer leur exécution. Ne pas ajouter une campagne complète d’histoire par défaut ; la preuve upgrade 4E vise la version déployée effectivement supportée. Les schémas `experiment_*` de 3B/3C ne sont pas des baselines PRIMARY.

### 8.3 Preuves et résultats exigés

Les scénarios PostgreSQL concurrents utilisent barrières déterministes et timeouts bornés, pas de sleep pour fabriquer l’ordre ; observer `pg_blocking_pids`, PID/xid et statements. Les assertions comparent les **ensembles UUID** et les effets métier, pas seulement les counts ou appels mock. Garder un scénario où une autre connexion tient toutes les E et où une transaction sans Event commit sans attente E.

Distinguer tests unitaires de classification UNKNOWN et perte réelle d’accusé réseau. Ne pas introduire une plateforme de chaos ; si une vraie panne de transport est nécessaire pour lever une ambiguïté de résolution, prévoir une injection ciblée dédiée avant 4E et conserver le statut de preuve exact. Aucun changement de retry pour faire réussir la preuve.

Dettes préexistantes : les limites de gates 2C et les diagnostics Surefire de 3C gardent leur statut. Un échec existant n’est pas réétiqueté PASS ni corrigé hors scope. Les critères bloquants sur lesquels repose le protocole doivent être verts ; tout autre échec de gate doit être attribué et soumis à décision explicite avant activation, sans désactiver un test pertinent.

Rapport obligatoire : Start HEAD, commit final, impact attendu/observé, slices déclarées, commandes **réellement** exécutées et résultats, franchissements inattendus, gate global, reactor complet et raison, DB/bootstrap/histoire, push/divergence et worktree. Aucun lot ne se clôt par « tout passe » sans preuve de ces frontières.

## 9. Risques, preuves et véritables conditions bloquantes

| Condition observée | Preuve / décision | Gate |
|---|---|---|
| Suffixe hors transaction ou avant S/Claim | Composition réelle, marqueurs de phases et même PID/xid ; corriger owner | 4B/4D bloquant |
| Owner autonome annoté/template producteur non migré | Cartographie exhaustive et test ambient inconnu ; pas de couverture automatique revendiquée | 4D bloquant |
| Callback ou mutation métier après E sur chemin autorisé | Audit des hooks, restriction structurelle et trace PostgreSQL des scénarios ; retirer/replacer l’appel avant fermeture | 4B puis 4D bloquant |
| Savepoint/rollback partiel ou suspension réellement utilisé dans graphe producteur | Contrat, règles d’architecture ciblées et test du chemin ; supprimer cet usage | 4A/4D bloquant |
| Possibilité théorique de bypass natif, dirty checking rogue ou savepoint caché | Limite contractuelle assumée ; ne justifie aucune infrastructure universelle supplémentaire | Non bloquant en soi |
| UUID oublié/double ou mauvais dans repository Binding `new` | Collecte unique au corps partagé, ensembles UUID exacts, rollback collision et intégrité | 4D bloquant |
| Rollback non confirmé ou classement UNKNOWN altéré | Résolution physique réelle + tests wrapper ; garder UNKNOWN et pas de replay aveugle | 4B/4D bloquant |
| PREPARED impossible à retirer sans perte d’effets | Répéter drainage et redéploiement avant ACTIVE, compatibilité DDL | Avant déploiement 4D bloquant |
| Schéma/contraintes ou charge E ajoutant attente métier non maîtrisée | Tests vraies FK/triggers, ordre P/B/S/E et mesures représentatives | 4C/4D, capacité 4E bloquants |
| Historique incomplet, anciennes instances, absence d’autorisation ou restauration non prouvée | Gel, anti-jointures, manifeste/frontières N et runbook §7 | Activation 4E bloquante |

Invariants ACTIVE à vérifier par famille : chaque Event conservé a exactement une association et inversement ; positions exactement `1..last_position` à la validation ; rollback sans effet/position/avance E ; transaction zéro Event sans acquisition E. Après première E, seulement E suivantes, associations, validations relationnelles et résolution physique, sans nouveau travail métier. Les contraintes peuvent attendre sous E : aucune promesse universelle de deadlock-free ni SLO tirée des mesures 3B.

Non-régressions : observedVersion et temporalité inchangés ; provenance puis CAS S et Claim avant E ; Command rejetée sans BusinessEvent ; TA/TH retry sans E ; TR/TH seulement après rollback TE ; recovery occurrence Binding exacte sans rejouer Pot ; initializer Registration inchangé ; aucun APPLIED sans COMMITTED ; Discovery/cursors/consommateurs inchangés. Un défaut préexistant reste attribué comme tel, jamais renommé PASS ni corrigé hors scope pour masquer un gate pertinent.

## 10. Découpage final, décisions et verdict

**Découpage final recommandé : 4A–4E conservé**, avec 4B dormant, 4C PREPARED inactif et 4D PREPARED complet. La portée des composants change : le runner propriétaire remplace le manager spécialisé et les hooks défensifs, sans lot supplémentaire. La migration du writer autonome et de toutes compositions productrices est groupée avec la collecte en 4D pour éviter un runtime partiellement couvert. Le câblage injecte des runners PRIMARY nommés aux seuls producteurs ; le bean générique des admissions et les runners des consommateurs non producteurs ne sont pas remplacés globalement.

Décisions retenues : Option A explicite dans le callback transactionnel ; manager JPA standard et runner Spring générique inchangés ; collecteur physique indépendant de Consumption ; publications explicites, primitive JPA insert-only et flush avant fermeture ; phase 3 sans callback métier ; quatre E ordonnées et blocs, quatre associations typées avec intégrité relationnelle ; un seul mode PREPARED/ACTIVE ; aucun séquencement partiel par famille ; aucun contrôle universel SQL/JDBC/Hibernate. Le rogue adapter est une limite contractuelle assumée, sans abandon des violations réelles des chemins autorisés.

Incertitudes résiduelles **à lever par les lots, sans rouvrir les principes** : preuve de la composition exacte des runners et des classifications de résolution (4B), exhaustivité des hooks/owners au HEAD d’implémentation, adaptation insert-only sans déplacement de flush/fences (4D), coût et verrous du vrai DDL (4C/4D), volumes/fenêtre/seuils et version d’upgrade effectivement déployée (4E). Un double de manager prouve une branche UNKNOWN, pas une perte réelle d’accusé réseau ; distinguer les niveaux de preuve sans créer une plateforme de chaos.

Les conditions bloquantes sont celles du §9 et les gates de vérification déclarés. La préférence historique pour `doCommit`, la fermeture universelle de Hibernate et l’interception de tous les savepoints ne sont plus des décisions en attente ni des conditions de passage.

**Verdict : READY WITH CONDITIONS.** Le plan est prêt pour une implémentation bornée après nouvelle autorisation ; chaque lot doit satisfaire ses preuves avant passage et l’activation déployée nécessite son autorisation explicite. Aucun lot n’est commencé ici.

## 11. Contrôle documentaire de cette livraison

Commandes utilisées : `pwd`, `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git remote -v`, `git worktree list`, `git rev-parse origin/feat/consumption-transaction-protocol`, `git rev-list --left-right --count HEAD...origin/feat/consumption-transaction-protocol`, `git ls-remote origin refs/heads/feat/consumption-transaction-protocol`, inventaires/lectures `rg --files`, `rg -n`, `cat`, `sed`, script Python de révision et contrôle des liens locaux/ancrages et du périmètre ; `git diff --check`, `git diff` du seul document, staging explicite, commit documentaire et push non forcé, puis contrôle SHA distant/divergence/propreté. Les tentatives de lecture sans réseau ont précédé la lecture distante autorisée. Aucun `javap`, fetch ou test technique nouveau pour cette révision ; les observations bytecode et PostgreSQL citées appartiennent aux études historiques.

Impact attendu/observé : **un document existant révisé sous docs/architecture uniquement**. Slices déclarées/exécutées **NONE** ; Maven **NONE** ; DB/migration/tests **NONE** ; franchissement inattendu **NONE** ; gate global **NOT REQUIRED, non exécuté** ; full reactor **NOT REQUIRED pour ce plan, interdit et non exécuté**. Études 3A–3D inchangées, aucun fichier production/test/migration/configuration/POM/index modifié. SHA final et résultat du push fournis dans le rapport de livraison, sans référence circulaire dans la baseline.
