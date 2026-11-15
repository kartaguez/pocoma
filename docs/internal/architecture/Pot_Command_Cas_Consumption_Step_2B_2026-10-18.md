# Étape 2B — CAS Pot : exceptions, rollback et retry Consumption

Date : 2026-10-18. **Implémenté et vérifié**, périmètre COMMAND.
Départ : `6757f7cbbb65290a2cc877129fec88fd994b4e7b`, branche `feat/consumption-transaction-protocol`.
L'[audit 2A](Pot_Command_Transaction_Boundaries_Audit_Step_2A_2026-10-18.md) reste l'audit historique de CURRENT à `29a5b953` ; il n'est pas réécrit. Les règles d'[obsolescence livrées à l'étape 1](Pot_Command_Obsolescence_Implementation_2026-10-18.md) restent inchangées.

## Périmètre de vérification déclaré avant implémentation

Impact production attendu et observé : taxonomie des erreurs Pot, résolution des références métier absentes, propagation des causes et classification technique COMMAND.

- Slice primaire : **COMMAND** ; secondaires : **NONE**.
- Commande canonique depuis `app/` : `./mvnw -pl runtime-command-consumption-worker -am test`.
- Preuve locale : tests unitaires des adaptateurs, classifier et owner ; PostgreSQL réel avec le wiring COMMAND et les proxies Spring.
- Impact interdit : autres moteurs Consumption, comportement BINDING, règles d'obsolescence, ordre P → B → S, leases, schéma, dépendances Maven, séquencement des Events.
- Gate architecture global : **NOT REQUIRED / non exécuté**. Aucune dépendance, frontière de module ou règle d'isolation modifiée.
- Reactor complet : **NOT REQUIRED / non exécuté** ; étape bornée, sans intégration main ni clôture de Wave.
- Base : **CURRENT**, fixtures existantes Testcontainers PostgreSQL 17. La politique indique qu'aucune baseline n'est certifiée ; le bootstrap Flyway existant est conservé. La passe canonique inclut aussi les tests historiques déjà rattachés à sa fermeture Maven ; aucun gate historique additionnel n'est demandé.
- Escalade : tout changement de comportement BINDING ou d'un autre moteur imposerait une nouvelle déclaration. Aucun franchissement inattendu observé.

Le runner physique est partagé. La conservation additionnelle de l'erreur d'action remplacée est **activée uniquement dans `commandConsumptionTransactionRunner`**, par un paramètre explicite. Les configurations des autres moteurs gardent le constructeur et le comportement existants. Le partage de ce fichier n'étend donc pas la classification aux autres moteurs.

## Taxonomie et remplacement de VersionConflictException

| Exception | Responsabilité | Sortie de TE | Classification COMMAND |
|---|---|---|---|
| `PotVersionCasConflictException` | UPDATE conditionnel de la version globale Pot ne modifiant pas la ligne attendue | Exception technique, rollback intégral | `POT_VERSION_CAS_CONFLICT`, `TRANSIENT`, après rollback confirmé et contrôle d'ownership |
| `AggregateTransitionConsistencyException` | Cardinalité de fermeture temporelle différente de l'attendu | Exception technique, rollback intégral | `AGGREGATE_TRANSITION_INCONSISTENT`, `COMMAND_TECHNICAL_INVARIANT` ; terminal technique |
| `BusinessRuleViolationException` | Préconditions, autorisations et obsolescence métier prévues | `Rejected` avant mutations ; finalisation atomique | Terminal métier ; aucun retry technique |
| `BusinessEntityNotFoundException` | Donnée persistée attendue absente après résolution de référence | Exception technique, rollback intégral | `COMMAND_EXECUTION_FAILURE`, terminal technique par défaut |

Les seuls anciens points d'émission de `VersionConflictException` étaient :

| Émetteur / méthode | Remplacement |
|---|---|
| `JpaPotGlobalVersionAdapter.updateIfActive` | `PotVersionCasConflictException` |
| `JpaPotHeaderAdapter.save` | `AggregateTransitionConsistencyException` |
| `JpaExpenseHeaderAdapter.save` | `AggregateTransitionConsistencyException` |
| `JpaPotShareholdersAdapter.saveAll` | `AggregateTransitionConsistencyException` |
| `JpaExpenseSharesAdapter.save` | `AggregateTransitionConsistencyException` |

Le catch de `VersionConflictException` dans `AbstractPotCommandUseCaseAdapter` est supprimé ; ses tests de rejet sont remplacés par des assertions de propagation technique. Le type et son ancien test sont supprimés. Aucun usage Java du type ne subsiste. Les tests des quatre writers temporels et du CAS global attendent les types spécialisés. `POT_VERSION_CONFLICT` n'est plus produit comme rejet par le chemin Pot de production. Des fixtures historiques hors de ce chemin emploient encore cette chaîne comme exemple de raison arbitraire. `OBSOLETE_COMMAND` reste exclusivement métier.

## Inventaire exhaustif de BusinessEntityNotFoundException

Inventaire réalisé avant changement par recherche de tous les usages Java, puis lecture des méthodes et de leurs consommateurs. Il existait **dix points d'émission**, dont neuf sur le chemin de mutation COMMAND et un sur le chemin de projection. Aucun catch spécialisé autre que celui d'Abstract n'a été trouvé en production.

| Émission initiale | Nature de l'absence | Traitement livré |
|---|---|---|
| `JpaPotContextAdapter.loadPotContextData` : version globale initiale | Référence Pot inconnue, absence métier attendue | `Optional.empty` ; les six services utilisant PotContext décident `BusinessRuleViolationException("POT_GLOBAL_VERSION", ...)` |
| Même méthode : header à V après lecture du compteur | Donnée persistée attendue absente | `BusinessEntityNotFoundException` technique conservée |
| `JpaExpenseContextAdapter.loadExpenseContextData` : premier header Expense courant, lookup propriétaire | Référence Expense inconnue, absence métier attendue | `Optional.empty` ; les trois services Expense décident `BusinessRuleViolationException("EXPENSE_HEADER", ...)` |
| Même méthode : version Pot après identification du propriétaire | Donnée persistée attendue absente | Exception technique conservée |
| Même méthode : header Expense à V | Donnée persistée attendue absente | Exception technique conservée |
| Même méthode : header Pot à V | Donnée persistée attendue absente | Exception technique conservée |
| `JpaPotHeaderAdapter.loadActiveAtVersion` | Header attendu après validation du contexte, utilisé par UpdatePotDetails et DeletePot | Exception technique conservée |
| `JpaExpenseHeaderAdapter.loadActiveAtVersion` | Header attendu après validation du contexte, utilisé par UpdateExpenseDetails et DeleteExpense | Exception technique conservée |
| `JpaExpenseSharesAdapter.loadPotIdFromHeader` | Header propriétaire attendu lors du fallback sur jeu vide dans UpdateExpenseShares | Exception technique conservée ; le jeu vide lui-même reste valide selon les règles existantes |
| `JpaProjectedExpenseAdapter.loadProjectedExpense` | Projection, hors chemin d'exécution Pot COMMAND | Émission inchangée, sans extension du chantier PROJECTION |

Les six consommateurs PotContext sont AddPotShareholders, CreateExpense, DeletePot, UpdatePotDetails, UpdatePotShareholdersDetails et UpdatePotShareholdersWeights. Les trois consommateurs ExpenseContext sont DeleteExpense, UpdateExpenseDetails et UpdateExpenseShares. Les ports retournent désormais `Optional<Context>` : aucune requête additionnelle ni exception métier ne traverse le proxy de lecture pour signaler une référence inconnue. Le service choisit le rejet après le retour normal du proxy. `PotCommandInvocation` capture V uniquement pour les contextes présents.

Les autres absences traversent toujours les services, la factory, l'adaptateur de commande, ExecuteRecordedCommand et le propriétaire TE comme erreurs techniques. Elles ne sont plus converties en `Rejected` dans Abstract. Elles peuvent révéler une cohérence de persistance incorrecte, mais l'absence seule n'est pas renommée aveuglément en invariant de transition : le type historique est conservé et documenté comme technique. Les violations de cardinalité pendant une écriture, elles, ont le nouveau type spécialisé.

Les tombstones demeurent présents dans les lectures temporelles ; les contextes conservent leurs codes de suppression, d'autorisation, de membre/référence invalide et `OBSOLETE_COMMAND`. Les tests existants d'obsolescence ne changent que pour déballer les contextes optionnels et contrôler l'absence initiale explicite.

## Rollback, ownership et reprise

TA acquiert et committe le claim avant TE. TE reste physiquement possédée par `ConsumptionOwnerTransactionRunner` via `SpringTransactionRunner`. Une perte CAS sort du proxy JPA, traverse l'adaptateur de commande sans conversion, puis provoque le rollback physique de TE. Le runner ne relance directement l'erreur d'action que si `afterCompletion` confirme ROLLED_BACK et que le delegate a renvoyé cette même erreur.

Si la résolution remplace l'erreur, le runner COMMAND conserve l'erreur d'action comme exception supprimée de l'erreur de frontière, elle-même cause de `ConsumptionTransactionResolutionException`. Le classifier COMMAND explore causes et exceptions supprimées avec détection de cycles pour les deux nouveaux types. Une anomalie de transition n'est pas assimilée au CAS. Le traitement SQLState existant reste inchangé : deadlock, sérialisation, indisponibilité et timeout conservent leur classification.

`SequentialConsumptionOrchestrator` reste inchangé :

- ROLLED_BACK confirmé : relecture d'ownership avant classification d'une erreur de résolution ; perte d'ownership → abandon.
- COMMITTED ou UNKNOWN : aucun TH, aucun retry ni publication FAILED fondée sur cette seule erreur.
- Claim courant après rollback : classifier puis TH via `HandleConsumptionFailureService` et `CommandConsumptionFailurePolicy` / `DefaultConsumptionFailurePolicy` existants.
- TH non terminale : clôture technique du claim et `scheduleRetry` ; nouvelle éligibilité à **1 s, 5 s, 30 s** ; acquisition d'un **nouveau ClaimId**. La quatrième perte épuise le budget et produit la terminalisation technique existante.

La prochaine Execute recharge RecordedCommand, redécode son payload durable et garde **O strictement identique**. Elle relit V, reconstruit les contextes et agrégats, réévalue préconditions/autorisation/obsolescence, réobserve Binding et refait le fence exact. Invocation, objets et Events provisoires de TE rollbackée ne sont pas réutilisés. Une modification indépendante permet le succès à V>O ; une cible modifiée donne un unique rejet `OBSOLETE_COMMAND` au second essai.

TR de recovery Binding reste distincte et inchangée. Les contrats de BindingFenceLost/Conflict, LostClaim, ClaimClosure et TransactionResolution sont conservés. Aucune transaction imbriquée, boucle de retry locale, attente dans TE, modification des délais ou nouvel orchestrateur de production.

## Publication et ordre SQL

Ordre inchangé : CAS Pot P → mutations temporelles et append BusinessEvents → fence Binding B → outcome/terminal Event/provenance → CAS slot S → clôture claim. Aucun changement de repository, requête SQL, flush, verrou, propagation transactionnelle, migration ou contrainte d'unicité.

Le succès conserve son incrément global, son BusinessEvent Pot, ses mutations et metadata, son outcome et son CommandTerminalEvent distinct, la provenance et la finalisation du claim courant dans TE. La tentative CAS perdante ne laisse ni mutation, metadata supplémentaire, BusinessEvent, outcome/terminal Event, provenance de succès ni slot DONE. TH peut enregistrer les transitions techniques prévues, sans résultat métier terminal tant que le retry est éligible.

## Preuves PostgreSQL et limites

La classe `PotCasConsumptionPostgresTest` utilise l'application COMMAND, les owners publics, le locator réel, les proxies JPA et le protocole Sequential/Failure existant. Un décorateur de test observe la sortie du vrai owner Execute sans en remplacer le comportement. Un port de test attend par latches avant le vrai CAS : les deux commandes ont déjà lu le même V ; la gagnante committe avant libération de la perdante. Aucun sleep destiné à fabriquer une race. L'horloge de test avance à l'échéance réellement persistée, sans changer la politique ni écrire artificiellement nextClaimAt.

| Scénario | Preuve exécutée |
|---|---|
| T1 | Deux commandes réelles au même V, véritable CAS JPA perdant via proxies ; exception CAS observée hors transaction, `afterCompletion=ROLLED_BACK`, absence d'effets durables et catégorie TRANSIENT persistée sur le claim |
| T2 | Nouvelle acquisition et TE : ClaimId et XID PostgreSQL distincts ; O durable = 1 aux deux essais ; V = 1 puis 2 ; succès à 3, un seul Event de cette commande relié à sa provenance ; pas de retry avant échéance |
| T3 | Gagnante modifiant le même header : second contexte à V=2 détecte l'obsolescence ; un outcome/terminal Event REJECTED et aucun troisième claim |
| T4 | Fermeture temporelle perturbée dans TE après CAS pour provoquer cardinalité zéro du vrai writer ; AggregateTransitionConsistencyException, rollback compteur/metadata/header, FAILED technique sans retry |
| T5 | Pot inconnu et Expense inconnue : rejets métier durables avec codes existants ; suppression provisoire du header après contexte : absence technique traversant le proxy, rollback confirmé, header restauré, aucune conversion Rejected |
| T6 | Takeover pendant TE arrêtée : ancien claim ne finalise pas, ses effets et Event flushés rollbackent ; nouveau claim réussit. Changement de révision Binding : recovery distincte, BINDING_FENCE_CONFLICT, aucun effet durable de TE, puis nouveau claim réussi |
| T7 | Quatre CAS réellement perdus : échéances 1/5/30, absence de FAILED avant épuisement ; cause CAS conservée après vrai rollback PostgreSQL avec remplacement injecté de l'erreur de frontière ; COMMITTED/UNKNOWN n'engagent pas TH |

Les erreurs d'observation après COMMITTED et l'issue UNKNOWN sont injectées à la frontière Execute : COMMITTED suit un vrai commit de TE ; UNKNOWN n'est **pas** une panne réseau réellement provoquée. Le test prouve l'absence de retry/publication FAILED sous ce résultat, pas une résolution opérationnelle de l'incertitude. Le remplacement d'erreur après rollback est également une injection de frontière ; le CAS et le rollback sont réellement PostgreSQL. Les tests unitaires du runner et classifier complètent cette preuve.

La preuve de takeover interrompt une TE avant son CAS et valide le fencing jusqu'à rollback des écritures flushées ; elle ne prétend pas énumérer tous les timings possibles entre TA, TH et takeover. Les tests PostgreSQL existants Consumption/Command couvrent aussi les pertes d'ownership et autres anomalies de résolution. Un test de factory directe n'est pas utilisé comme substitut à la preuve du chemin propriétaire complet.

Restrictions conservées : budget de quatre essais, aucune garantie de fairness sous contention ; aucune certification universelle de cohérence temporelle ou unicité métier face à des writers externes ; restrictions d'obsolescence Shareholder et jeu vide inchangées. L'ordre des autorités (2C), la séquence durable des Events et Discovery (étape 3) restent hors périmètre.

## Commandes, résultats et livraison

Depuis `app/`, commandes Maven réellement exécutées (les répétitions de développement utilisent les mêmes commandes) :

```bash
./mvnw -pl runtime-command-consumption-worker -am test-compile -DskipTests
./mvnw -pl runtime-command-consumption-worker -am test -Dtest=PotCasConsumptionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl runtime-command-consumption-worker -am test -Dtest=PotCasConsumptionPostgresTest,ConsumptionOwnerTransactionRunnerTest,CommandConsumptionTechnicalFailureClassifierTest -Dsurefire.failIfNoSpecifiedTests=false
```

La passe canonique termine le 18 octobre à 21:54:08 +02:00 : **935 tests, 190 classes, 0 échec, 0 erreur, 0 skip**, fermeture Maven de **51 modules**. Après renforcement des assertions et ajout du scénario de cause remplacée, la dernière passe ciblée termine à 21:55:43 +02:00 : **17 tests dans 3 classes, 0 échec, 0 erreur, 0 skip**, dont **9 PostgreSQL**. Le cumul des classes vérifiées, en remplaçant leur dernier rapport, représente 936 tests distincts ; ce nombre n'est pas présenté comme une seconde passe canonique complète.

Les échecs intermédiaires étaient : adaptation incomplète des signatures Optional et de fixtures à la compilation ; heure de clôture du nouveau claim antérieure à son acquisition dans la fixture takeover ; nom erroné de colonne `event_id` au lieu de `id` dans l'assertion de jointure de provenance. Corrigés sans affaiblir les assertions ni changer les garanties de production. Les dernières exécutions réussies font foi. La preuve machine contient les modules, résultats par classe et noms des tests : [evidence 2B](../testing/evidence/Pot_Command_Cas_Consumption_Step_2B_2026-10-18.json).

Impact production effectif : engine-write-pot, engine-consume-command, infra-persistence-primary-jpa, supra-consume-command et composition-runtime-spring ; tests runtime-command-consumption-worker. Les modules de support inclus par `-am` ne constituent pas de nouveaux comportements modifiés. Aucun franchissement inattendu, aucun gate global ni reactor complet.

Préparation Git : fetch réussi après refus sandbox initial sur FETCH_HEAD. HEAD et origin égaux à la baseline fournie, divergence 0/0, worktree cible propre ; aucun commit postérieur. Le worktree principal `feat/runtime-monolith` à `90926a5e` contenait des changements Keycloak, docs, Bruno et fichiers non suivis ; les trois autres worktrees inspectés étaient propres. Tous sont préservés. Travail effectué exclusivement dans `/private/tmp/pocoma-consumption-transaction-protocol`.

Revue finale : diff et `git diff --check`, absence de changement de migration/POM/ordre SQL, suppression des usages Java de VersionConflictException, isolation de l'opt-in de conservation de cause à COMMAND. Inventaire exact des fichiers livré dans la preuve machine. Commit et push sans force sur la branche cible ; SHA final, statut distant et divergence sont rapportés après livraison, sans prétendre inscrire le hash propre du présent commit dans son contenu.
