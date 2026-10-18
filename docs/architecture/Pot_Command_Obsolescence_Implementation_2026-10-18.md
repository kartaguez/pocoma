# Obsolescence des commandes Pot — implémentation du 18 octobre 2026

## Périmètre et décisions

Base : `6fabf85fdca5b729175fecb03da4378c58996597`, branche `feat/consumption-transaction-protocol`, worktree `/private/tmp/pocoma-consumption-transaction-protocol` initialement propre. Après `git fetch --no-tags origin`, divergence initiale 0/0. Le checkout principal sur `feat/runtime-monolith` contient du travail préexistant : il n’a pas été utilisé pour les modifications. Aucun merge, rebase, changement de main ou push forcé.

La mission remplace explicitement les recommandations de compatibilité V1 des sections AG–AO du document de conception : **un seul contrat `observedVersion`, aucune compatibilité `expectedVersion`**. Les identifiants de types `*_V1` existants sont conservés pour éviter un second catalogue sans valeur fonctionnelle ; ils ne sélectionnent aucun ancien comportement strict. Les décodeurs rejettent l’ancien champ. L’enveloppe d’admission et le stockage du payload restent génériques et opaques.

Déclaration de vérification faite avant modification :

- Impact attendu : contextes et exécution des mutations Pot, faits temporels JPA, contrat des commandes et exemples HTTP.
- Slice primaire : COMMAND ; secondaire : WEB.
- Commande canonique depuis `app/` : `./mvnw -pl runtime-command-consumption-worker,runtime-web-api -am test`.
- Impact interdit : ordre des autorités/fences, owner transactionnel, classification/retry après CAS perdu, séquence/Discovery des Events, projections READ, schéma temporel et graphe Maven.
- Gate global architecture : NOT REQUIRED. Reactor complet : NOT REQUIRED, non exécuté.
- Base : CURRENT, avec PostgreSQL 17 Testcontainers réel. La politique normative ne déclare aucune baseline TRUSTED ; les bootstraps et tests Flyway existants de la fermeture Maven ont donc exécuté leurs migrations. Aucune migration créée, modifiée ou supprimée.
- Escalade : changement de comportement propre à EVENT, PROJECTION, BINDING ou LKV, dépendance/module ou règle d’isolation nécessiterait une nouvelle déclaration avant modification. Aucun franchissement inattendu constaté.
- Prochain gate global : intégration significative vers main ou clôture d’une Wave majeure, pas cette livraison bornée.

## Comportement livré

O est lu dans la commande durable puis transmis sans remplacement jusqu’à la validation métier. V est la version globale choisie par le loader pour la tentative. Le CAS arbitre toujours V vers V+1 ; il n’utilise jamais O à la place de V.

Les neuf contextes de mutation valident `1 <= O <= V`. Une observation invalide produit `INVALID_OBSERVED_VERSION`. Une cible pertinente plus récente produit `OBSOLETE_COMMAND`, exception métier convertie en **Rejected définitif** par le chemin existant. Aucun retry n’est ajouté pour cette obsolescence.

| Commande | Décision dans le contexte |
|---|---|
| CreatePot | Création initiale, sans obsolescence |
| UpdatePotDetails | Début du header Pot à V ≤ O |
| DeletePot | O = V, même si seul un enfant a changé |
| AddPotShareholders | Observation valide et préconditions courantes, aucune version de collection |
| UpdatePotShareholdersDetails | Début de chaque Shareholder ciblé ≤ O |
| UpdatePotShareholdersWeights | Même témoin physique, conflit conservateur avec Details |
| CreateExpense | Observation valide et références courantes, aucune cible remplacée |
| UpdateExpenseDetails | Début du header Expense à V ≤ O |
| UpdateExpenseShares | Tous les débuts du jeu courant complet ≤ O ; jeu vide : O = V |
| DeleteExpense | Header et jeu courant complet ≤ O ; jeu vide : O = V |

Les suppressions et absences gardent leurs diagnostics courants : `POT_ALREADY_DELETED`, `EXPENSE_ALREADY_DELETED`, `SHAREHOLDER_NOT_PRESENT`, absence de header/version. Aucune absence n’est interprétée comme une preuve de compatibilité. Aucune lecture à O n’est ajoutée pour affirmer une disparition historique : on décrit l’état courant à V.

Les contextes exigent des témoins explicites, immuables et dans [1,V]. Pour les Shareholders, les clés des témoins correspondent exactement aux identifiants disponibles ; seuls les identifiants ciblés sont comparés à O. Un jeu de shares courant avec des débuts mélangés est une incohérence technique, puisque le writer doit remplacer intégralement le jeu. Les doublons de bénéficiaire et les incohérences de propriété détectées par le loader échouent techniquement, sans verdict compatible.

## Répartition des responsabilités

**Contextes :** décisions d’obsolescence dans les méthodes de validation existantes, sans moteur de policies, registre ou hiérarchie supplémentaire. Le header est distinct des shares ; Details et Weights Shareholder partagent volontairement leur témoin.

**Adapters :** `JpaPotContextAdapter` conserve le début du header et les débuts Shareholder depuis les entités déjà lues. `JpaExpenseContextAdapter` conserve l’entité du header au lieu de perdre son début dans `toDomain`, et charge les débuts de toutes les shares à V pour UpdateExpenseShares et DeleteExpense. Le lookup initial Expense sert uniquement à découvrir le Pot propriétaire ; lifecycle, relations, droits et débuts sont relus au V fixé.

Les requêtes temporelles existantes conservent exactement la sémantique `started_at_version <= V AND (ended_at_version IS NULL OR V < ended_at_version)`. Les nouvelles validations n’utilisent pas une lecture des seules lignes ouvertes. Une preuve PostgreSQL charge volontairement des lignes fermées à V+1 et vérifie qu’elles restent celles retenues à V.

**Services :** seuls les appels au champ de commande deviennent `observedVersion()`. Les services ne consultent et ne comparent aucun début temporel. Autorisation, chargement des agrégats à V, CAS, mutation et publication gardent leur ordre existant.

**Writer des shares :** inchangé. Il ferme toutes les shares courantes et recrée tout le nouveau jeu, y compris les parts dont le bénéficiaire subsiste. Le test PostgreSQL A+B → B vérifie que B recommence à N, que les deux anciennes lignes sont fermées à N et que le jeu historique à V conserve A+B. La validation examine le jeu courant complet, indépendamment du nouveau payload.

**Collisions :** `PotShareholders.addShareholder` utilise `putIfAbsent`, avec génération injectable uniquement au niveau du package pour une preuve déterministe. Les adapters de création refusent une identité Pot existante, une identité Expense ayant déjà un historique et, par une recherche groupée, toute identité Shareholder ajoutée ayant déjà un historique, même supprimé. Une collision est une erreur technique, jamais une obsolescence. Ces lectures ne créent pas de garantie SQL universelle face à une collision simultanée entre Pots : aucune nouvelle contrainte d’identité métier n’a été introduite.

## Lectures PostgreSQL et mutualisation

Les deltas suivants proviennent du code, **pas d’un benchmark ni d’un comptage SQL instrumenté** :

| Chemin | Delta de lecture explicite |
|---|---|
| UpdatePotDetails, DeletePot, mises à jour Shareholder, UpdateExpenseDetails | 0 pour l’obsolescence ; témoins conservés depuis les lectures existantes |
| AddPotShareholders | +1 recherche groupée d’existence historique lorsque le lot ajouté n’est pas vide |
| CreateExpense | +1 recherche d’existence historique de l’identité Expense |
| CreatePot | +1 recherche de l’identité Pot avant création |
| UpdateExpenseShares | +1 lecture du jeu courant à V pour fournir ses témoins au contexte |
| DeleteExpense | +1 lecture du jeu courant à V, y compris lorsque O = V |

Mutualisation effectuée pour les headers et les Shareholders : pas de requête par cible, ni de chargement du Pot complet pour la policy. Pour les shares, le contexte charge les faits avant la validation, tandis que le port d’agrégat fournit ensuite l’objet mutable utilisé par le service ; le writer garde aussi sa relecture de cardinalité. Une mutualisation de ces résultats a été examinée, mais demanderait de changer le résultat du port d’agrégat ou de transporter l’agrégat mutable dans le contexte. Cette livraison garde les responsabilités actuelles et expose explicitement ce coût d’une lecture bornée de Q(E). Aucun cache transversal de décision ou de droits n’est introduit.

## Vérification exécutée

Les commandes réellement exécutées depuis `app/` sont :

```bash
./mvnw -pl engine-write-pot,engine-consume-command,infra-persistence-primary-jpa -am test-compile -DskipTests
./mvnw -pl engine-write-pot,engine-consume-command -am test
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=CommandObsolescenceContextsTest,CommandObsolescencePostgresTest,UpdateExpenseSharesConcurrencyPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=CommandObsolescenceContextsTest,CommandObsolescencePostgresTest,UpdateExpenseSharesConcurrencyPostgresTest,ShareholderIdentityCollisionTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker,runtime-web-api -am test
```

Les premières compilations/tests ont identifié les call sites à adapter et les assertions de l’ancienne égalité stricte ; ces échecs de développement ont été corrigés. Les tests PostgreSQL n’ont pas été remplacés par des mocks. Résultats finaux et inventaire exact des fichiers : voir ci-dessous.

Couverts : O nul/négatif/futur ; cible modifiée ; modification indépendante ; lot ciblé/non ciblé ; conflit Details/Weights ; indépendance des deux composantes Expense ; retrait de bénéficiaire par remplacement complet ; jeu vide ; suppression après changement des shares ; DeletePot ancien ; objet supprimé/absent ; références et relations d’autorisation à V ; conservation du payload O ; rejet définitif sans retry ; versions réellement chargées et bornes temporelles ; collisions locales et historiques ; régressions existantes de perte CAS, rollback et concurrence.

Contrôles statiques : `git diff --check`, examen des fichiers de production modifiés, absence de migration, de lecture historique de BusinessEvents, de changement des dépendances Maven et de modification des autorités transactionnelles. Les exemples Bruno et les champs des helpers k6 sont alignés ; leurs scénarios HTTP ne sont pas revendiqués comme exécutés.

## Limites transactionnelles et activation

**L’étape 2 transactionnelle n’est pas engagée.** L’ordre effectif des fences Binding/Pot/Consumption, l’owner, la séquence et la Discovery des Events restent inchangés. Les producteurs existants continuent de publier un seul BusinessEvent Pot par succès ; l’Event terminal de commande est une famille distincte. La nouvelle preuve runtime contrôle aussi l’unicité de l’Event Pot à la version de réussite et l’absence d’Event supplémentaire au rejet.

**Blocage d’activation de bout en bout :** les pertes du CAS global et les erreurs de cardinalité temporelle lèvent encore `VersionConflictException`. `AbstractPotCommandUseCaseAdapter.executeAdapted` la convertit en Rejected, tandis que `CommandConsumptionTechnicalFailureClassifier` ne la classe pas explicitement transient. Selon le proxy transactionnel traversé, l’essai peut être rollback-only et finir en échec technique. Le rollback puis retry sur un nouveau V avec O inchangé n’est donc pas garanti. Ce chemin n’a pas été transformé en `OBSOLETE_COMMAND` et aucun retry local n’a été improvisé.

L’obsolescence métier livrée est distincte : `BusinessRuleViolationException("OBSOLETE_COMMAND", ...)` avant mutation, résultat terminal Rejected. L’activation complète nécessite un chantier ultérieur sur la perte d’autorité. Les gardes d’adjacence/overflow et l’assertion globale d’un Event unique restent aussi celles de l’existant ; cette mission ne prétend pas les avoir renforcées universellement.

## Résultats et livraison

La dernière commande canonique COMMAND + WEB termine en **BUILD SUCCESS** le 18 octobre 2026 à 21:04:19 +02:00 : **949 tests dans 198 classes, 0 échec, 0 erreur, 0 ignoré**. La première passe canonique avait également réussi ; la seconde couvre la version finale des tests ajoutés après sa compilation.

Synthèse conservée dans [la preuve de vérification](../testing/evidence/Pot_Command_Obsolescence_2026-10-18.json).

| Preuve ciblée incluse dans la passe finale | Tests | Résultat |
|---|---:|---|
| CommandObsolescenceContextsTest | 10 | PASS |
| CommandObsolescencePostgresTest | 10 | PASS, PostgreSQL réel |
| UpdateExpenseSharesConcurrencyPostgresTest | 15 | PASS, PostgreSQL réel |
| CommandConsumptionRuntimePostgresTest | 9 | PASS, PostgreSQL réel, dont le nouveau scénario O/V et rejet définitif |
| ShareholderIdentityCollisionTest | 1 | PASS |

Surefire a signalé un délai d’arrêt de JVM forkée après `System.exit(0)` ; le build termine néanmoins en succès, avec les rapports de tests ci-dessus sans échec. Cette observation de nettoyage du processus n’est pas présentée comme une régression métier corrigée.

`git diff --check` est réussi. Aucun franchissement inattendu de slice. Le seul fichier de `architecture-tests` adapté contient un exemple JSON de commande désormais `observedVersion` ; aucune règle d’architecture n’a changé et le gate global n’a pas été exécuté. Les modules de support compilés/testés par `-am` n’élargissent pas l’impact production.

Livraison : un commit contenant code, tests, exemples et ce rapport, poussé sans force sur `origin/feat/consumption-transaction-protocol` après ces vérifications. Le SHA final et l’état distant constaté après push sont donnés dans le compte rendu de livraison ; ce fichier ne peut pas contenir le hash de son propre commit. Aucun travail préexistant n’est inclus.

Inventaire exact des fichiers livrés :


```text
app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandCompletionE2EPostgresTest.java
app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/aggregate/PotShareholders.java
app/domain-pot/src/test/java/com/kartaguez/pocoma/domain/pot/aggregate/ShareholderIdentityCollisionTest.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/dispatch/PotCommandInputMapper.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/AddPotShareholdersCommand.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/CreateExpenseCommand.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/DeleteExpenseCommand.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/DeletePotCommand.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdateExpenseDetailsCommand.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdateExpenseSharesCommand.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdatePotDetailsCommand.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdatePotShareholdersDetailsCommand.java
app/engine-consume-command/src/main/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdatePotShareholdersWeightsCommand.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/decode/PotCommandPayloadDecodersTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/dispatch/PotCommandUseCaseAdapterTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/AddPotShareholdersCommandTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/CreateExpenseCommandTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/DeleteExpenseCommandTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/DeletePotCommandTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdateExpenseDetailsCommandTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdateExpenseSharesCommandTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdatePotDetailsCommandTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdatePotShareholdersDetailsCommandTest.java
app/engine-consume-command/src/test/java/com/kartaguez/pocoma/engine/consume/command/pot/intent/UpdatePotShareholdersWeightsCommandTest.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/AddPotShareholdersContext.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/CreateExpenseContext.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/DeleteExpenseContext.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/DeletePotContext.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdateExpenseDetailsContext.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdateExpenseSharesContext.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdatePotDetailsContext.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdatePotShareholdersDetailsContext.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/context/UpdatePotShareholdersWeightsContext.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/AddPotShareholdersInput.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/CreateExpenseInput.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/DeleteExpenseInput.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/DeletePotInput.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdateExpenseDetailsInput.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdateExpenseSharesInput.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdatePotDetailsInput.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdatePotShareholdersDetailsInput.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/input/UpdatePotShareholdersWeightsInput.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/port/persistence/ExpenseContextPort.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/AddPotShareholdersService.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/CreateExpenseService.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/DeleteExpenseService.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/DeletePotService.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdateExpenseDetailsService.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdateExpenseSharesService.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotDetailsService.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotShareholdersDetailsService.java
app/engine-write-pot/src/main/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotShareholdersWeightsService.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/context/AddPotShareholdersContextTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/context/CommandObsolescenceContextsTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/context/DeletePotContextTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/context/TerminalPotDeleteExpenseContextsTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/context/UpdateExpenseSharesContextTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/context/UpdatePotDetailsContextTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/AddPotShareholdersServiceTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/CreateExpenseServiceTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/DeleteExpenseServiceTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/DeletePotServiceTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/UpdateExpenseDetailsServiceTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/UpdateExpenseSharesServiceTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotDetailsServiceTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotShareholdersDetailsServiceTest.java
app/engine-write-pot/src/test/java/com/kartaguez/pocoma/engine/write/pot/service/UpdatePotShareholdersWeightsServiceTest.java
app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/JpaPotGlobalVersionAdapter.java
app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/JpaExpenseContextAdapter.java
app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/JpaPotContextAdapter.java
app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/core/JpaExpenseHeaderAdapter.java
app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/core/JpaPotShareholdersAdapter.java
app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/core/JpaExpenseHeaderRepository.java
app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/repository/core/JpaShareholderRepository.java
app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/CommandObsolescencePostgresTest.java
app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/context/UpdateExpenseSharesConcurrencyPostgresTest.java
app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/core/JpaExpenseHeaderAdapterTest.java
app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/consumption/CommandConsumptionRuntimePostgresTest.java
docs/README.md
docs/architecture/Pot_Command_Obsolescence_Implementation_2026-10-18.md
docs/architecture/Pot_Command_Versioning_Obsolescence_Design_2026-10-18.md
docs/testing/evidence/Pot_Command_Obsolescence_2026-10-18.json
scripts/bruno/00 Pot E2E/04 Update Pot Details.bru
scripts/bruno/01 Pots Commands/02 Update Pot Details.bru
scripts/bruno/01 Pots Commands/03 Add Pot Shareholders.bru
scripts/bruno/01 Pots Commands/04 Link Pot Shareholders To Users.bru
scripts/bruno/01 Pots Commands/05 Update Pot Shareholders Weights.bru
scripts/bruno/03 Expenses Commands/01 Create Expense.bru
scripts/bruno/03 Expenses Commands/02 Update Expense Details.bru
scripts/bruno/03 Expenses Commands/03 Update Expense Shares.bru
scripts/bruno/99 Cleanup/01 Delete Expense.bru
scripts/bruno/99 Cleanup/02 Delete Pot.bru
scripts/k6/lib/pocoma-client.js
scripts/k6/lib/workflows.js
```
