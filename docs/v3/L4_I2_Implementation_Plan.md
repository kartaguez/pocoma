# Pocoma V3 — L4/I2 — Plan d’implémentation : préparation et recovery interautorités

**Date :** 2 novembre 2026. **Branche cible :** `feat/v3-architecture`. **Statut :** PLAN DOCUMENTAIRE — AUCUN GO D’IMPLÉMENTATION.

## 1. Autorité, portée et préconditions

L4/I1 est **CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS** par décision humaine du 2 novembre 2026 ([acte](L4_I1_Acceptance_Decision.md), [rapport](L4_I1_Execution_Report.md), [manifest](L4_I1_Proof_Manifest.json)). Référence de clôture communiquée : `fd02a1978012b979137db7d9043aea5c69144cfd`. I1 reste acquis ; ses quatre réserves, notamment la régression du poisoning tardif, sont conservées. L4/I2 ne livre ni participant métier I3–I7, ni activation I8, ni certification globale L4 ou SPEC-10.

**État observé dans le code :** `ConsumptionParticipant.prepare(UUID)` est invoqué par `ConsumptionEngine.executeAttempt` dans `transactions.read(context -> ...)`; `observe(UUID, RecoveryReadContext)` voit seulement le contexte effets ; `observeOperation` croise l’observation métier avec le slot et ne consulte pas dans cette même décision le checkpoint de la Consumption. `AttemptTransactions` expose `read`, `write`, `poison`, `requirePublicationClosed` et `Failure(Resolution)`. `SpringAttemptTransactions` possède les unités physiques et le poisoning tardif. Ce plan ne présente pas les signatures proposées ci-dessous comme déjà implémentées.

Sources de référence : [plan global L4](L4_Read_Inventory_and_Implementation_Plan.md#6-participants-transactions-et-recovery), [protocole L2/I5](L2_I5_Implementation_Plan.md), [contrat L1-C](L1_C_Transaction_Contract.md), [politique Reactor](../testing/Reactor_Verification_Policy.md) (chemin à confirmer au contrôle de liens).

## 2. Décisions architecturales

**Option retenue :** un `PreparationReads` à capacités bornées, exclusivement créé par l’Engine, avec scopes `source` et `effects` séquentiels. Rejeter (a) la préparation READ hébergée dans un seul snapshot effects, car elle bloque les lectures historiques PRIMARY ; (b) `REQUIRES_NEW`/transaction imbriquée, car les unités et owners ne sont plus disjoints ; (c) second Engine, router par `ThreadLocal`, pipeline universel ou transaction distribuée, car ces options multiplient les états et brouillent le fencing. Le seul `ConsumptionEngine` continue à contrôler réservation, classification, finalisation, observation et capacité.

Pour READ, `source=PRIMARY` et `effects=READ` ; pour WRITE, les deux capacités désignent PRIMARY sans double connexion. Une seule transaction à la fois, toujours créée et résolue par L2. La préparation n’écrit jamais durablement ; la finalisation reste `READ_COMMITTED`, sur l’owner effects et dans la même unité que la protection/DELETE du slot et la publication.

## 3. Contrats Java proposés (signatures cibles, non existantes)

Dans `engine-consumption/.../v3/PreparationReads.java` :

```java
public interface PreparationReads {
    <T> T source(Function<RecoveryReadContext, T> callback);
    <T> T effects(Function<RecoveryReadContext, T> callback);
}
```

Dans `ConsumptionParticipant.java` :

```java
Optional<Prepared> prepare(UUID consumableId, PreparationReads reads);
RecoveryPreparation prepareObservation(UUID consumableId, PreparationReads reads);
RecoveryObservation observe(UUID consumableId,
                            RecoveryPreparation prepared,
                            RecoveryFacts facts,
                            RecoveryReadContext context);
```

`RecoveryPreparation` est un type de valeur détaché, immuable, sans décision de complétion (un `record` marqueur minimal `RecoveryPreparation.None` peut servir aux participants sans source). `RecoveryFacts` est un record pur, construit par l’Engine depuis les stores techniques : `consumptionId`, `consumableId`, partition/famille/position source vérifiées, slot PRESENT/ABSENT et couverture du checkpoint (C,partition,position) avec statut `COVERED / NOT_COVERED / UNAVAILABLE`. Le type exact de position et de partition **doit réutiliser les valeurs et ports existants**, pas définir un second mapping ni les calculer depuis l’état métier. Les trois dernières signatures sont **cibles conditionnées à la vérification des ports de checkpoint/éligibilité** avant modification Java ; l’adaptateur de données ne doit pas synthétiser une couverture en l’absence d’oracle technique.

Le changement d’interface est intentionnel et compilatoire : adapter *tous* les implémenteurs et probes Command, IdentityRequest et READ, sans maintien implicite d’une ancienne méthode qui ouvrirait une transaction ambiante. Si les participants WRITE ont besoin du snapshot historique unique, ils effectuent **un seul** `reads.source(ctx -> { ... })` couvrant ensemble les faits d’autorisation et Pot. La préparation IdentityRequest peut lire la demande ; l’autorité est revalidée dans l’unité finale sous les verrous EUI. Aucun `prepare` ne doit ouvrir `AttemptTransactions` directement.

Dans l’Engine, conserver les owners `effectsTransactions` (actuel champ `transactions`) et `controlTransactions`, ajouter la référence explicite `sourceTransactions`, sans adaptateur transactionnel composite. Une surcharge de construction peut conserver les callers historiques en associant source=effects **uniquement si leur comportement historique est vérifié** ; les compositions actives doivent injecter explicitement les owners. Le contrat `AttemptTransactions` public peut rester inchangé : `read` porte déjà l’isolation RR et le contexte, `write` RC et `Failure` sa résolution. Ajouter des protections dans l’Engine / capacité et dans `SpringAttemptTransactions` seulement quand les contre-exemples le nécessitent. Aucun `RecoveryReadContext` mutable ou public fabriqué par le participant.

## 4. Capacité, durée de vie et résolution

La capacité est allouée une fois par appel `prepare` ou `prepareObservation` et expire dans le `finally` de l’Engine. Elle lie thread, tentative/claim et phase ; un scope actif interdit tout autre scope, y compris même autorité. Le callback ne peut invoquer un autre scope ; l’accès depuis un thread étranger, après expiration, pendant finalisation ou après invalidation est rejeté. L’Engine n’ouvre aucune transaction de préparation autour du callback extérieur : `source/effects` appellent leur `owner.read` *séparément* et attendent la résolution physique avant retour.

La capacité conserve un état privé monotone : `ACTIVE -> INVALID` ou `ACTIVE -> EXPIRED`; l’incertitude physique est un attribut irréversible d’INVALID, jamais un drapeau remis à zéro. Une tentative d’ouverture concurrente/imbriquée, un contexte invalide, une erreur transactionnelle interceptée, une interruption/annulation pendant le scope ou un callback non refermé entraîne l’invalidation. Les exceptions purement applicatives qui échappent au callback interdisent également de finaliser ; on n’infère pas un rollback à partir de leur type. Pour détecter une exception *interceptée* dans le callback, les accès aux ports gardés et le propriétaire doivent poisonner leur unité et la capacité via un point d’interception appartenant à L2 ; le simple `try/catch` du participant ne constitue jamais une preuve de santé. Ne pas prétendre détecter tout `catch` Java arbitraire sans passage par une garde : l’oracle est la réussite de la résolution physique et l’absence d'invalidation constatée par les owners/ports.

**Barrière de sortie :** l’Engine refuse `Prepared`, un scope suivant ou la finalisation si la capacité est INVALID, si un scope est encore actif, si une résolution n’a pas été qualifiée ou si le callback n’a pas rendu un résultat autorisé. Le type de sortie doit être copié en valeur. Rejeter explicitement `Stream`, `ResultSet`, `Connection`, `EntityManager`, références d'entités/proxies lazy et autres ressources attachées ; l’absence de ressources échappées est à garantir par les API de ports, DTO immuables, validations des résultats et tests de détachement : **une inspection générique Java ne saurait prouver le détachement d’un graphe arbitraire**. Documenter/forcer cette limite par contrat et contre-oracles.

| Issue d'un scope | Phase suivante | Finalisation | Capacité L2 |
|---|---|---|---|
| Retour et résolution COMMITTED certaine du read-only | Autorisée | Autorisée sous autres gardes | Politique normale ; aucun effet métier prouvé |
| ROLLED_BACK certain après défaut | Refusée pour cette préparation | Refusée | Restitution/cession uniquement selon classement et règles L2 |
| UNKNOWN, ACK absent ou commit read-only incertain | Refusée | Refusée | Ticket/réservation retenu ; drainage si requis |
| Exception invalidante interceptée / contexte périmé / scope non fermé | Refusée | Refusée | Pas d’optimisme ; classifier l’issue physique indépendamment |
| Exception brute sans résolution qualifiée | Refusée | Refusée | Traitement conservateur UNKNOWN, sans restitution spéculative |
| Interruption/annulation pendant unité | Refusée | Refusée | Attendre résolution certaine ; préserver renewal/fencing/drainage |

Une observation durable ultérieure peut statuer sur l’effet métier, mais **ne requalifie jamais rétroactivement** l’ACK physique UNKNOWN, ni ne libère automatiquement un ticket/une ressource. Conserver les protections L2 de responsabilité non résolue, renewals indépendants, saturation et arrêt incomplet.

## 5. Protocole de préparation nominal

Pour une matérialisation READ : (1) `source` lit l’événement exact et les références historiques PRIMARY ; (2) résolution certaine ; (3) `effects` lit les sous-versions déjà matérialisées en READ ; (4) résolution certaine ; (5) `source` charge les contenus manquants à leurs identités historiques exactes ; (6) résolution certaine ; (7) calcul détaché hors transaction ; (8) `effectsTransactions.write` finalise en READ avec `protectFinal`, écrit/vérifie les effets, `completion.complete()`, `requirePublicationClosed()`, puis résolution owner. Les phases facultatives peuvent être omises, pas fusionnées entre deux autorités. **La sélection d’une révision historique doit être ancrée à l’événement et sa version** : une lecture ultérieure de l’état courant n’est pas une source équivalente. Aucune connexion source n’existe pendant l’unité finale READ.

## 6. Recovery cohérent et sans reçu

Deux étapes **séquentielles** : `prepareObservation` résout au besoin ses références immuables PRIMARY dans `PreparationReads` ; l’Engine ouvre ensuite **un seul** `effectsTransactions.read` RR RO où l’observation technique rassemble checkpoint de **C**, partition/position/éligibilité de l’événement et présence du slot `(C,eventId)`, puis transmet ces `RecoveryFacts` et `RecoveryReadContext` au participant pour comparer les effets métier **dans la même unité et sur le même owner READ**. Aucun appel source, nouvelle transaction ou store non owner dans ce callback. L’Engine effectue le verdict final conservateur ; le participant ne peut certifier `COMPLETED` avec une simple preuve métier. Toute source inaccessible ou étape technique inconclusive retourne `UNAVAILABLE`; les erreurs physiques UNKNOWN continuent de retenir les responsabilités L2.

| Situation, source et facts cohérents | Verdict |
|---|---|
| Slot PRESENT, effets absents | NOT_COMPLETED |
| Slot PRESENT, effets identiques préexistants | NOT_COMPLETED |
| Slot PRESENT, garde/projection monotone plus récente et cohérente | NOT_COMPLETED |
| Slot ABSENT, checkpoint couvrant position admissible, preuves exigées présentes et conformes | COMPLETED |
| Slot ABSENT, checkpoint non couvrant | Jamais COMPLETED ; INCONSISTENT si enrôlement technique prouvé, sinon UNAVAILABLE jusqu’à établissement de la provenance |
| Slot ABSENT, couverture prouvée mais effet obligatoire manquant ou divergent | INCONSISTENT |
| Divergence/corruption métier véritable (y compris slot présent) | INCONSISTENT |
| Checkpoint, slot, source ou effets non observables avec certitude | UNAVAILABLE |

Les quatre résultats canoniques restent `COMPLETED / NOT_COMPLETED / INCONSISTENT / UNAVAILABLE`. `NOT_COMPLETED` ne prouve **jamais** ROLLED_BACK. Une projection antérieure identique n’établit ni l’exécution du slot ni son commit ; un pointeur monotone plus récent ne prouve pas la complétion d’un ancien événement. Cas de qualification du futur contrat : PotVersion(V) exacte et PotBalance(V) exacte obligatoires ; pour Balance(D), absence de Balance(D) conforme avec preuve technique ; Visibility/CurrentBinding dépassés nécessitent checkpoint + slot, non leur seul pointeur ; CommandResult/IdentityRequestResult exigent comparaison immuable **complète**. Aucune de ces matérialisations métier n’est réalisée en I2.

**Important pour les ports actuels :** `ConsumptionSlotStore.observe` ne fournit que PRESENT/ABSENT. Vérifier et adapter l’accès aux checkpoints à travers le port technique existant qui en est propriétaire, dans la même transaction effects ; si les checkpoints résident physiquement dans une autre autorité, **bloquer** l’implémentation du snapshot prétendument unique et amender explicitement le plan avant code, plutôt que lire deux autorités en prétendant à un seul snapshot.

## 7. Non-régressions obligatoires

**Command :** un unique callback `source` PRIMARY, un seul snapshot RR pour demande, autorisation, faits Pot ; aucune lecture effects additionnelle silencieuse. Finalisation RC, CAS, contraintes différées, publication, suffixe L1-C, fencing et fermeture inchangés.

**IdentityRequest :** lecture de demande au prepare ; locks EUI en ordre canonique et décisions d’autorité en finalisation, avec Link/Unlink concurrentiels, provenance et publication identiques à L3. Ne déplacer aucun arbitrage sous les snapshots préparatoires.

**Owner I1 :** l’exception gardée interceptée pendant `finalizeIn`, `requireCompletionReady` ou `closePublication` tardif interdit physiquement le commit : conserver le `PoisonedCommitGuard` de `SpringAttemptTransactions`. Protéger le `deleteProtected` du slot et les effets READ dans l’unité transactionnelle exacte ; `completion.complete` une seule fois.

## 8. Inventaire des fichiers (cibles à vérifier dans le checkout avant développement)

| Statut | Fichier / module | Modification prévue |
|---|---|---|
| CREATE | `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/PreparationReads.java` | Interface pure, deux scopes |
| MODIFY | `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionParticipant.java` | Prepare avec capacité et observation enrichie |
| MODIFY | `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionEngine.java` | Orchestration scopes, poisoning, recovery et branchement owner source |
| CREATE ou interne | Même package : `RecoveryFacts`, `RecoveryPreparation` | Valeurs pures minimales, préférer records imbriqués si possible |
| MODIFY si nécessaire | `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/ConsumptionSlotStore.java` et port checkpoint existant | Lecture cohérente des faits techniques |
| MODIFY si démontré | `app/port-transaction/.../AttemptTransactions.java`, `app/infra-tx-spring/src/main/java/com/kartaguez/pocoma/infra/tx/spring/v3/SpringAttemptTransactions.java` | Garde poison commune, uniquement si les invariants ne sont pas obtenus sans extension |
| MODIFY | Implémenteurs Command/IdentityRequest, probes READ et compositions V3 référencés par recherche d’implémentations | Adapter signatures et owners explicites, préserver snapshot et locks |
| MODIFY/CREATE tests | `engine-consumption` tests unitaires et `runtime-task-consumption-worker` / `runtime-command-consumption-worker` tests PostgreSQL | Adversité physique et non-régression |
| MODIFY si règles affectées | `architecture-tests` | Exiger un Engine et ownership L2, pas élargissement aveugle |

Les raccourcis `...` sont **des modules à inventaire obligatoire**, pas des chemins exacts autorisant un changement. Avant GO, sortir `rg 'implements ConsumptionParticipant|new ConsumptionEngine|ConsumptionSlotStore|checkpoint' app` et figer la liste concrète. Aucun POM, migration, script, nouveau runtime ou table SQL n’est prévu ; toute nécessité doit être explicitement révisée.

## 9. Matrice de qualification discriminante

| ID | Scénario | Oracle indépendant |
|---|---|---|
| P01 | PRIMARY source puis READ effects puis PRIMARY, puis READ final | PID/XID/snapshots séquentiels, aucune connexion simultanée |
| P02 | source/effects nested ou concurrents | Refus avant ouverture physique et poisoning |
| P03 | contexte expiré, étranger, réutilisé, stream/lazy | Refus, aucune ressource utilisable après callback |
| P04 | premier scope UNKNOWN puis exception interceptée | Aucun second scope, aucune finalisation ni restitution |
| P05 | erreur de port gardé interceptée puis nouvelle lecture | Poison irréversible |
| P06 | interruption/cancel pendant lecture | Issue qualifiée séparément, capacité conservatrice |
| P07 | ROLLED_BACK certain vs UNKNOWN | Décision de cession/capacité et drainage distinctes |
| W01 | Command : faits de demande + Pot | Même snapshot UUID/PID/xid, pas deux lectures |
| W02 | IdentityRequest : Link/Unlink concurrent | Locks EUI et autorité effectivement observés en finalisation |
| W03 | poisoning tardif I1 | Rollback observable et slot intact après faute interceptée |
| R01 | slot présent, effet absent / identique / pointeur supérieur | NOT_COMPLETED, jamais faux INCONSISTENT |
| R02 | slot absent, checkpoint insuffisant | Pas de COMPLETED |
| R03 | slot absent, checkpoint couvrant, effet divergent | INCONSISTENT |
| R04 | source PRIMARY indisponible avant observation READ | UNAVAILABLE, aucune inférence |
| R05 | ACK perdu avant/après commit | Verdict conforme à la DB, ACK physique UNKNOWN préservé |
| C01 | préparation longue, saturation/renewal/fencing/drainage | Barrières PG réelles, progression renewal indépendante et tickets non perdus |

Tests unitaires pour API, états et exceptions ; **PostgreSQL réel** pour isolation, verrouillage, transactions physiques, perte d’ACK, PID/XID, slots/checkpoints, concurrence, saturation, renouvellement et fencing. Utiliser compteurs/sessions SQL extérieurs, barrières interleaving et données sources indépendantes ; ni mocks ni H2 comme preuve des propriétés physiques. Timeout sans interleaving attesté = INCONCLUSIVE, pas PASS. Conserver le FAIL initial et les traces contradictoires.

## 10. Gates, transfert de preuves et séquence

**Feedback rapide :** compilation ciblée + tests des contrats isolés, sans full reactor à chaque édition. **Qualification locale :** `engine-consumption`, `infra-tx-spring`, acteurs Command/Identity et banc PG. **Requalification L2 :** préparation, résolution et UNKNOWN, capacity, recovery, renewal, fencing, drainage. Les preuves L2 de routage/Discovery et transitions non modifiées restent des arguments réutilisables *seulement après vérification des hypothèses et du diff*, non des substituts aux campagnes affectées. Les preuves de préparation/recovery sont **non transférables** après modification de leurs frontières. La gate FOUNDATION reste strictement L0 : les propriétés fondatrices L2 requalifiées n’autorisent pas une nouvelle slice FOUNDATION.

**Gates canoniques candidates depuis `app/` :**
```sh
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```
Déterminer via le diff et la politique Reactor si BINDING, MONOLITH ou autre gate s’ajoute. Aucun full reactor automatique ; justifier toute extension. Conserver les réserves historiques r05/r12 et l’attribution des preuves par SHA, paramètres, code et fichiers réellement exécutés. Les nombres I1 (37, 29, 1833, 2096) ne sont **pas** des résultats I2.

Séquence courte et gates de passage :

1. **Contrats et inventaire :** vérifier tous les implémenteurs, ports checkpoints et frontières propriétaires ; figer signatures exactes ; tests API/architecture compilables. **Stop** si checkpoint et slot ne sont pas lisibles ensemble sur READ.
2. **Engine et capacité :** ajouter source owner et scopes séquentiels, invalidation irréversible, résolution et détachement ; P01–P07 PostgreSQL.
3. **Compatibilité WRITE :** migrer Command/Identity et probes, vérifier W01–W03, conserver RC, CAS, verrous EUI et poisoning tardif.
4. **Recovery :** introduire `RecoveryFacts` et observation en un snapshot effects, R01–R05 et contrefactuels PG, sans reçu SQL.
5. **Concurrence et qualification :** C01, gates canoniques adaptées à l’impact, architecture, preuve attribuée et rapport de qualification.

Chaque étape exige tests verts ciblés, constat explicite d'absence d’effet durable préparatoire et conservation des preuves adverses avant la suivante. Aucun GO implicite d’étape vers la production.

## 11. Critères d’acceptation et risques résiduels

Acceptation seulement si les scopes sont physiquement séquentiels, propriétaires/thread-bound et empoisonnés sans réarmement ; READ peut lire PRIMARY puis READ sans overlap ; les préparations WRITE gardent leur snapshot unique ; recovery ne déduit jamais un commit d’un état préexistant ; l’owner READ final prouve l’atomicité slots/effets et le poisoning tardif ; les nouvelles propriétés physiques passent sous PostgreSQL et les gates prévues sont attribuées sans SKIP masqué.

**Réserves avant GO code :** (R1) confirmer, dans les ports et SQL existants, le propriétaire exact et la lecture en snapshot unique des checkpoints ; (R2) inventorier exhaustivement les implémenteurs des nouvelles interfaces et choisir la forme record imbriqué vs fichier ; (R3) préciser les types réels des positions/partitions à partir des signatures du dépôt ; (R4) déterminer l'ancre BINDING et la gate MONOLITH par impact effectif. Ces vérifications sont techniques, non un nouvel arbitrage de D1–D4. Si R1 révèle une séparation réelle des autorités, **NOT READY pour l'implémentation** jusqu’à plan amendé ; ne pas masquer cette contradiction.

**Décision documentaire : READY WITH RESERVATIONS pour revue du plan ; aucun GO d’implémentation délivré.** Aucun résultat de test ou vérification de worktree n’est revendiqué par le seul texte de ce plan.
