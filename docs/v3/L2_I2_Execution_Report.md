# L2 — I2 : Engine transactionnel et participation Command

**I2 — CLOSED / LOCAL BOUNDED PASS. Audit indépendant du code et des preuves à venir. Aucun GO I3, aucun PASS global L2 ni G1/G2/G3.**

## Mandat, provenance et périmètre

GO humain explicite d'implémentation et de publication sur `feat/v3-architecture`, après validation contradictoire du [plan simplifié](L2_I2_Implementation_Plan.md). Départ local du worktree de livraison et HEAD distant : **`cd4dac2a5f4d34bd337cd3f494f5fd403f238a81`** ; aucun delta postérieur au plan à l'entrée. SHA du code soumis aux quatre gates finaux : **`4f2264ae6e11e7e6e1511577064bca5e31ad1336`**. Les commits intermédiaires restent dans l’historique : `b2cb1139` implémentation, `a51c57b3` découverte Surefire, `914ae417` poisoning JPA tardif, `b9c00625` cause originale interceptée, `4528b2b7` règle d’architecture historique, `4f2264ae` poisoning des révisions autonomes JPA. Le SHA final de livraison est celui du dernier commit touchant ce rapport (`git log -1 --format=%H -- docs/v3/L2_I2_Execution_Report.md`) et est donné dans le message de livraison ; cette convention évite un SHA autoréférent inventé.

Worktree isolé `/private/tmp/pocoma-v3-i2-execution`, HEAD détaché. Le checkout utilisateur reste sur `feat/runtime-monolith` à `90a3e9bc`, avec ses modifications préexistantes. La branche locale V3 occupée ailleurs n'est pas déplacée. Aucun reset, clean, stash, force push ou transfert de travaux. Un worktree géré créé vide au départ, puis remplacé par le checkout de livraison dans la zone accessible en écriture, a été archivé sans modification. [Inventaire de préservation](../testing/evidence/L2-I2/Worktree-preservation.json).

Déclaration avant code : **COMMAND primaire ; secondaires NONE ; preuves directes `infra-tx-spring` et `runtime-monolith` ; architecture globale REQUIRED ; full reactor NOT REQUIRED** pour cette tranche bornée. Impact réalisé : `port-transaction`, `engine-consumption`, `engine-write-pot`, `infra-tx-spring`, `infra-persistence-primary-jpa`, `composition-runtime-spring` ; tests seulement dans les deux runtimes annoncés et `architecture-tests`. Aucun franchissement inattendu de slice. Les trois POM modifiés portent exactement les arêtes prévues au plan ; aucun nouveau module. Les capacités historiques consommatrices gardent leur implémentation et leur composition.

DB **CURRENT V3 neuf** : WRITE V1…V5 et contrat canonique, rôle runtime I1 inerte et rôle de test séparé ; probe READ technique issu de la fixture L0. Aucun DDL/migration de production modifié, aucune cible existante réinitialisée, aucune campagne de migrations historiques supplémentaire. Les migrations historiques effectivement exécutées par les tests transitifs des gates restent visibles dans les logs. Aucune baseline V2 TRUSTED présumée.

## Architecture réalisée et comparaison au plan

- `AttemptTransactions` est le seul port transactionnel I2 ; `SpringAttemptTransactions` configure deux `TransactionTemplate` REQUIRED : RR réellement RO et RC RW. Le manager WRITE est le `JpaTransactionManager` existant, avec même datasource/EMF, timeout explicite et nesting interdit. Le manager JDBC est utilisé par les fixtures/probes techniques.
- `ConsumptionEngine` prépare puis ferme le snapshot, ouvre l'unité finale, protège le slot canonique, fournit une completion liée au claim/thread/callback, vérifie completion et publication fermée, puis interprète la résolution physique. Il ne connaît aucun type Pot, Identity, événement métier ou projection.
- `ExecutePotCommand` est le participant : revalidation de l'intention, protection Identity puis Pot, décisions et effets L1, résultat terminal, `complete()`, publication métier. Il n'a plus de dépendance à un owner transactionnel, de recovery, de RetryPolicy ou de cession.
- `JdbcConsumptionSlotStore` verrouille `(consumption_id,consumable_id)` avant une seconde instruction `clock_timestamp()`, vérifie token/état/échéance, puis supprime exactement cette clé/token. Aucune revalidation temporelle tardive et aucun verrou de partition.
- `requireCompletionReady` contrôle le résultat réel et l'intention ; Changed vérifie l'ownership xid8 et l'égalité exacte des composantes des huit tables avec la proposition immuable. No-op/rejet refusent une candidate de cette tentative par une sonde de dernière version bornée par Pot. Le contrôle ne reconstruit aucune chaîne historique.
- `JdbcPotCommandStore.publish` conserve les événements, le flush, les 18 FK + quatre complétudes forcées avant suffixe, puis ferme et positionne POT → COMMAND_COMPLETION. Les trois contraintes terminales restent différées au commit.
- `JdbcPotCommandObservation` observe les faits métier ; Engine les combine avec le slot dans le même contexte RR RO. La fabrique explicite assemble ces objets sans déclarer de bean Engine actif. Les profils et privilèges I1 restent inertes.

Les deux owners spécifiques Pot ont été retirés de production. Aucun `FinalizationUnit`, `ReadUnit`, `PublicationControl`, `PhysicalWriteGuard`, second manager productif ou framework de phases. Les quatre nouvelles classes concrètes du plan sont celles effectivement livrées ; les types de données et la closure privée ne deviennent pas des services injectables. Une garde s'exécute avant chaque opération publique inventoriée et une vérification à la sortie du callback ; aucun contrôle systématique après chaque SQL/JPA.

Écarts minimaux explicites : `prepare` retourne `Optional<Prepared>` pour exprimer le cas déjà terminal, plutôt qu'une sentinelle Prepared ; les preuves I2 sont dans le package de test `v3`, afin de réutiliser les fixtures historiques sans élargir leur API publique ; les méthodes nommées du manifeste exécutent les scénarios et assertions historiques applicables, pas des corps vides. Les répétitions ne multiplient pas le nombre de garanties distinctes.

## Trois protections bloquantes

**Poisoning.** Les gardes communes conservent le `TransactionStatus` Spring et marquent rollback-only avant de relancer toute faute applicative, JDBC ou JPA. Une interception ultérieure ne restaure pas la committabilité. Les douze entrées de révision gardent leur garde ; la compatibilité autonome B ne s'applique qu'en l'absence de toute tentative Engine, y compris sur une autre autorité.

**Transactions intérieures.** Le probe initial a démontré un défaut réel de la seule comparaison des holders : sous owner JPA, un manager JDBC intérieur peut réutiliser le holder, exécuter un SQL et committer. Le diagnostic antérieur à correction indique `reached=true effects=1 failure=null`. Correction minimale : conserver l'identité de la synchronization Spring de l'owner et vérifier sa présence **avant** l'opération, en complément des holders. Spring suspend cette synchronization lors de l'ouverture intérieure, même quand ce deuxième manager réutilise le holder. Les quatre variantes JDBC/JPA × effet/DELETE refusent ensuite avant SQL, condamnent l'extérieur et laissent zéro effet indépendant. Pas de proxy de connexions ni nouveau manager. Le refus de NESTED, des mauvaises sources/EMF/managers et de l'ambiance est également exercé physiquement.

Un second contre-exemple physique a été vérifié après un premier gate COMMAND passant : dans `beforeCommit`, une interception du refus du writer laisse Spring dépasser son contrôle du rollback-only local ; JPA peut alors committer le préfixe (`failure=null`, ligne de préfixe durable). Correction minimale dans la même garde : marquer aussi l'`EntityTransaction` active rollback-only, comme `JpaTransactionManager.doSetRollbackOnly`. Aucun commit/rollback n'est exécuté manuellement. Les deux variantes propagée/interceptée sont désormais incluses dans le test owner, et le probe avant/après conserve l'oracle extérieur zéro ligne après correction. Les gates antérieurs à cette correction ne certifient pas le SHA final.

Un troisième contre-exemple concerne B autonome, sans unité Engine : un `JpaTransactionManager` appelant ignore le rollback-only du seul holder JDBC, et committe un préfixe après une erreur applicative interceptée. Le probe `AutonomousJpaPoisonProbe.java` échoue avant correction (`failure=null`, préfixe durable), puis passe avec zéro ligne extérieure. La même garde marque désormais aussi la transaction JPA Spring liée à la datasource concernée, uniquement sur le chemin d’erreur. Le test obligatoire `autonomousRevisionCaughtFailurePoisonsJpaAndJdbcCallers` couvre les appelants JDBC et JPA, avec préfixe JDBC ou ORM réellement flushé. Aucun owner ou gestionnaire supplémentaire.

**Mutation ORM tardive.** Le flush seul ne suffit pas si une référence demeure managée. Après le dernier flush et les contraintes pré-suffixe, la fermeture détache le contexte JPA par `clear()`. Une référence conservée, modifiée directement ou par callback beforeCommit, ne produit plus d'UPDATE tardif ; un callback ou writer qui réentre par la garde est refusé avant SQL et condamne l'unité. Le graphe de production interdit aux participants/writers de créer des managers, connexions/EM indépendants, synchronizations ou transactions cachées. La garantie porte sur ces entrées inventoriées et composées, pas sur du code arbitraire contournant tous les ports avec une connexion brute.

## R9 et résolution physique

| Résolution / observation | Interprétation I2 |
|---|---|
| COMMITTED confirmé | Succès seulement après résolution Spring ; diagnostic de cleanup conservé séparément. |
| ROLLED_BACK confirmé | Cause conservée ; restitution sans cession ni retry. |
| UNKNOWN | Observation RR RO ; aucune déduction de rollback à partir de NOT_COMPLETED. |
| COMPLETED | Source terminale, résultat, complétion positionnée, événements/effets historiques cohérents et slot absent. |
| NOT_COMPLETED | Source PENDING, sans publication/résultat attribuable, slot présent ; peut être observé pendant une transaction non résolue. |
| INCONSISTENT | Contradiction des preuves métier/techniques ; aucune réparation ni purge. |
| UNAVAILABLE | Lecture impossible ou contexte invalide ; incertitude conservée. |

La résolution d'origine reste UNKNOWN quand une observation COMPLETED résout l'issue métier. Causes, suppressed, `nextException` JDBC et exception originale Spring sont parcourues sans boucle ; la cause du callback est capturée avant qu'un rollback échoué puisse la masquer. La première cause d’un writer intercepté est aussi conservée dans les métadonnées de l’unité, puis reliée à l’échec de résolution ; une vraie division SQL par zéro conserve ainsi son SQLSTATE 22012. Ce diagnostic n’est pas un état de protocole supplémentaire.

Un manager ayant totalement perdu une cause de commit ne permet pas de fabriquer une certitude : UNKNOWN. Une erreur de cleanup après **préparation** committée ne devient jamais un succès métier ; un test dédié couvre cette distinction.

## Preuves, commandes et résultats

Inventaire figé avant gates : [manifeste I2](L2_I2_Proof_Manifest.json). Le collecteur [verify-v3-l2-i2.py](../../scripts/testing/verify-v3-l2-i2.py) exécute exactement depuis `app/` :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-tx-spring -am test
./mvnw -pl runtime-monolith -am test
./mvnw -pl architecture-tests -am test
```

| Gate final | SHA testé | Tests | Échecs/erreurs | Skips | Méthodes obligatoires contrôlées | Verdict |
|---|---|---:|---:|---:|---:|---|
| COMMAND | 4f2264ae | 1444 | 0 | 1 | 196 | PASS |
| TRANSACTIONS | 4f2264ae | 28 | 0 | 0 | 9 | PASS |
| MONOLITH | 4f2264ae | 1218 | 0 | 0 | 198 | PASS |
| ARCHITECTURE | 4f2264ae | 1707 | 0 | 1 | 211 | PASS |

Les quatre commandes canoniques ont été rejouées sur le même SHA final après la correction du poisoning des révisions autonomes JPA. [Provenance des SHA](../testing/evidence/L2-I2/Proof-provenance.json). Aucun code app/scripts n’a été modifié entre ces gates.

[Archives et empreintes](../testing/evidence/L2-I2/Gate-index.json), [couverture par ID](../testing/evidence/L2-I2/Coverage-by-ID.json), [inventaire du code](../testing/evidence/L2-I2/Changed-code-files.json), [liste complète de livraison](../testing/evidence/L2-I2/Delivery-files.txt). Les mêmes tests réexécutés dans plusieurs gates ne sont pas additionnés. Le skip conservé est `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, essai exploratoire historique désactivé sans propriété `step3b.measure` ; il ne vaut pas PASS et ne correspond à aucune méthode obligatoire I2. Les 142 méthodes I2 et les neuf méthodes owner sont présentes et passent sans skip.

Les logs des gates conservent également un avertissement Surefire de terminaison tardive d’un JVM de tests historiques, après livraison de ses XML et `System.exit(0)` ; le fork est arrêté au timeout de sortie. Aucun timeout de scénario ou échec d’assertion ne lui est assimilé. Maven retourne zéro et le collecteur contrôle séparément toutes les preuves obligatoires fraîches. Aucun réglage de timeout ni test historique n’est neutralisé.

Aucun test absent, skip obligatoire, méthode prévisionnelle ou ancienne archive ne vaut PASS. Le premier gate architecture global a identifié une règle historique interdisant tous les types de port-transaction dans Command, donc aussi le contexte RO pur prévu par I2. La correction autorise **uniquement** RecoveryReadContext, garde les owners et dépendances techniques interdits, et ajoute des exemples positifs/négatifs. Cette adaptation reste limitée au module architecture-tests et à son inventaire documentaire. L’essai global échoué est conservé. Après la correction supplémentaire du poisoning autonome JPA, les quatre gates sont intégralement rejoués sur le même SHA final.

Les quatre tests du collecteur refusent présence manquante, skip et doublon XML, et distinguent les états XML réels. Les compilations et sélections de diagnostic antérieures restent archivées séparément ; elles ne remplacent aucune commande canonique ci-dessus.

Commandes complémentaires de preuve effectivement utilisées (depuis `app/`, sauf Python) :

```sh
./mvnw -pl infra-tx-spring -am test -Dtest=AttemptTransactionsPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=WriteRevisionPerformancePostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Df2.variant=i2-before
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=WriteRevisionPerformancePostgresTest -Dsurefire.failIfNoSpecifiedTests=false -Df2.variant=i2-after
python3 -B -m unittest scripts/testing/test_i2_evidence.py
python3 -B docs/v3/check_l2_manifest.py
```

Le profil `i2-before` est exécuté exclusivement dans le worktree de référence `cd4dac2a`. Les probes supplémentaires utilisent javac/java et le classpath de l’XML PRIMARY, avec sources, SHA et commandes exactes dans [Supplement-provenance.json](../testing/evidence/L2-I2/Supplement-provenance.json) et le [lanceur reproductible](../testing/evidence/L2-I2/run-supplement.py).

Les diagnostics comprennent compilations ciblées, R1/R9 direct, C/F/F2, owner READ/JPA et architecture ; leurs échecs et relances sont conservés. Le défaut de holder décrit ci-dessus est un échec de protection réellement corrigé. Le premier gate COMMAND a eu un BUILD SUCCESS (1301 tests, zéro échec, un skip exploratoire) mais le collecteur a correctement rendu **FAIL** : les 142 méthodes `L2ManifestProof` étaient absentes des XML frais, son nom n’étant pas inclus par les motifs Surefire par défaut. Le POM PRIMARY ajoute explicitement ce fichier tout en conservant les quatre motifs par défaut. Ce premier essai ne vaut aucun PASS I2 et reste archivé. Le gate suivant à `a51c57b3` exécute bien les 142 méthodes I2 (1443 tests, zéro échec, un skip exploratoire), mais précède la correction du callback JPA et reste un diagnostic de cette révision ; les quatre gates finaux sont rejoués au SHA final.

Autres corrections de harnais : proxy d'ACK réarmé à tort après observation ; fixture de rejet utilisant une version ancienne encore valide pour la composante inchangée (corrigée vers une observation future explicitement invalide) ; compatibilité du nom d'image par digest Testcontainers ; adaptation de types et prédicats ArchUnit. Aucun oracle métier n'est affaibli pour ces corrections.

## Interleavings et invariants L1

| Preuve | Méthodes et oracle physique |
|---|---|
| Une unité JDBC/JPA et toutes écritures Command | `l2m_c06_a`, `l2m_r1_09` : triggers de trace de test sur version/révisions/source/résultat/DELETE/événements/positions/probe ORM, un PID et un xid8, état observé extérieurement. |
| Completion invalide | MISSING/DOUBLE/LATE/WRONG, appel avant résultat, callback/thread antérieur, DELETE zéro, autre Consumption QUARANTINED témoin : vecteur durable complet identique après rollback. |
| Coupures | Avant premier effet, après mutation, après résultat/source, avant/après DELETE, événement Pot, flush, chaque compteur/position et après publication : aucune unité partielle. |
| Claim final | c09/c14/c15 transférés : deux finaliseurs ; attente réelle attestée par `pg_blocking_pids` ; horloge DB après attente ; validité acquise conservée après échéance ; token perdu sans effet. |
| Densité | c16 transféré, préfixe non nul, deux Pots/EUI distincts, second publisher bloqué sur compteur ; commit et rollback du premier ; positions exactes et aucune position supérieure durable pendant l'indécision. |
| Contraintes | Catalogue réel des 22 pré-suffixe et des trois terminales différées ; erreurs 23502/23503/23514, dont mauvais requestId contrôlé par le trigger de correspondance réel. |
| ACK et observations concurrentes | Proxy filaire L1 avec JPA réel avant COMMIT/après réponse serveur supprimée ; oracle extérieur zéro/un effet ; observation pendant transaction ouverte et commit entre les SELECT du snapshot RR. |
| C/F/F2 | Les scénarios historiques applicables gardent leurs variantes (dont huit courses références/suppression, cinq coupes C10, deux publications C16, deux ordres JDBC/JPA) ; préconditions locales/groupées, NotLoaded, close exact concurrent et audit historique autonome conservés. |
| Profondeur | 100/1000/10000, cinq échauffements et dix mesures par profil, SQL/ResultSet réellement comptés, EXPLAIN ANALYZE BUFFERS. Pas de SLO ni promesse générale O(1). |

Le complément exécutable `R9IndependentCorruptionProof.java`, rejoué sur les classes compilées du SHA final `4f2264ae`, vérifie huit branches : terminal sans résultat, PENDING avec résultat/événement, terminal sans complétion, Changed sans composantes/position, résultat historique après nouvelle mutation et vraie connexion refusée. Les attentes sont littérales et la photographie SQL extérieure reste identique après observation ; huit assertions PASS séparées du décompte JUnit. Le probe `LateCallbackPoisonProbe.java` conserve également les observations physiques avant/après correction. Sources, commandes et logs restent dans le dossier de preuves.

Les fixtures enregistrent leurs paramètres avant stimulus ; les vecteurs indépendants utilisent une connexion extérieure RR RO. Les traces de test distinguent instrumentation et DML productif. Les courses ne sont pas qualifiées par un sleep : barrières, PID, bloqueurs PostgreSQL et horloges DB établissent l'ordre. Timeouts de connexion/statement/lock bornés dans la fixture Command. Les UUID/token de campagne sont des fixtures jetables archivées ; aucune autorité Identity réelle ni credential de service n'est utilisé.

Le harnais `L1CommandCompatibilityHarness` conserve exclusivement en test les stimuli de cession/retry historiques. Les tentatives d'effets passent par le vrai nouvel Engine et le même owner ; les transitions SQL de fixture après résolution utilisent une transaction de test distincte. c12/c23 et la partie cession de c21/c24 **ne certifient pas I3**. Le succès à count=N−1 est aussi vérifié directement sans cession. Le probe READ prouve uniquement l'atomicité locale du même adaptateur sur l'autre autorité, pas PotVersion/Balance/Visibility ni Discovery.


### Profil WRITE avant/après I2

[Échantillons, commandes et plans](../testing/evidence/L2-I2/README.md), [comparaison brute](../testing/evidence/L2-I2/Performance-comparison.json). Référence `cd4dac2a` réexécutée sur cette machine, sans autre build Maven concurrent ; même population et intentions, harnais adapté aux nouveaux contrats. Médianes, ms :

| Profondeur | Préparation avant → après | Finalisation avant → après | Total avant → après | SQL avant → après | Lignes Java avant → après |
|---|---:|---:|---:|---:|---:|
| 100 | 10.638 → 10.900 | 20.445 → 21.563 | 31.106 → 32.334 | 43 → 45 | 28 → 29 |
| 1000 | 10.209 → 10.519 | 19.908 → 20.779 | 30.138 → 31.307 | 43 → 45 | 28 → 29 |
| 10000 | 9.710 → 10.740 | 19.336 → 21.644 | 28.988 → 32.701 | 43 → 45 | 28 → 29 |

N désigne la profondeur avant les cinq échauffements ; les versions initiales mesurées vont de N+5 à N+14. Les 29 lignes restent identiques aux trois profondeurs, pour toutes les observations. Le surcoût fixe de deux instructions et une ligne correspond à `SET TRANSACTION READ ONLY` et à la lecture de contrôle avant completion ; la vérification no-op/rejet est également bornée par la dernière version du Pot. Les timings décrivent ce profil local, sans seuil de SLO ni généralisation de temps constant.

## Limites, reports et arrêt

Aucune acquisition, lease, renewal, cession, retry, quarantaine administrative, Discovery, polling, worker actif, producteur Identity réel ou projection métier READ de production ajouté. Ces obligations restent I3–I5/L3–L5 selon le manifeste. Aucun G1/G2/G3 engagé, aucun ACCEPTED L2 ou PASS global CON-04/CON-06/EVT-02. La commande full reactor `./mvnw test` n’est pas requise ni exécutée séparément : impact borné confirmé, trois arêtes POM prévues sans restructuration générale, pas de clôture majeure L2 ni intégration main ; le gate architecture global obligatoire demeure distinct. Sa fermeture Maven actuelle couvre 61 projets : cette ampleur est celle du graphe imposé par architecture-tests, pas un élargissement silencieux du code modifié ; aucune invocation séparée ./mvnw test n’a été ajoutée.

**Verdict local PASS : I2 CLOSED / LOCAL BOUNDED PASS. Publication sur `feat/v3-architecture` sans force, après ces quatre gates. Arrêt après I2 ; prochain jalon : audit indépendant du code et des preuves.**


## Contrôle de livraison Git

La publication autorisée vise uniquement `origin/feat/v3-architecture`, sans force. Le SHA publié final, la concordance distante et la propreté du checkout de livraison sont vérifiés après le commit documentaire et donnés dans le message de livraison. L’historique cohérent et les SHA testés restent consultables dans ce dossier ; aucun succès de push n’est présumé par les preuves Maven.
