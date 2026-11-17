# Pocoma V3 — L4/I2 — Rapport d’exécution

**L4/I2 — CLOSED — LOCAL BOUNDED PASS — décision définitive du 3 novembre 2026.**
Voir l’acte de clôture en fin de rapport ; les campagnes et états antérieurs sont conservés.

**I2-QUAL-01 RESOLVED — LOCAL BOUNDED PASS.** Les contrats I2 sont implémentés, la cause racine
du failure `I2-QUAL-01` est démontrée et corrigée dans le banc de test sans affaiblir l’oracle,
et les deux gates canoniques (`PROJECTION + COMMAND` et `Architecture`) passent sur les mêmes
octets applicatifs propres (`6496e6d0144ecfda9e58ec6d12e259445e7344ad`). La décision explicite de clôture du 3 novembre 2026 est enregistrée ci-dessous ;
le statut précédent restait soumis à une décision humaine.

- SHA initial du lot L4/I2 : `a9937991a75428d8718e6036da11be2382c633d0`.
- SHA initial du chantier correctif `I2-QUAL-01` : `386cf841057e477e5f1f0cadac9ea17d9c4838db`
  (commit applicatif antérieur : `23ad96d6b0ed9c075573247edf9989ed10472887`).
- Commit applicatif final qualifié : `6496e6d0144ecfda9e58ec6d12e259445e7344ad`.
- Branche : `feat/v3-architecture`, propre au début de mission ; aucun travail préexistant modifié.
- Commit de livraison : `git log -1 --format=%H -- docs/v3/L4_I2_Proof_Manifest.json`.
  Le SHA de publication et la vérification directe du distant sont donnés dans le message de livraison.

## Résolution causale de I2-QUAL-01 (Cas 2 — Défaut du banc de test)

Dans la campagne initiale [canonical-fail-renewal](../testing/evidence/L4-I2/canonical-fail-renewal/result.json)
(`1873/1/0/0`), `ConsumptionLifecycleCompositionPostgresTest#renewalsHaveReservedDatabaseAccess`
avait observé trois PID PostgreSQL en `WRITE` (`158`, `160`, `162`), mais seulement deux PID en
`READ` (`181`, `183`) à la barrière de 8 s (`expected: <3> but was: <2>`).

L’analyse causale (désassemblage `HikariCP 7.0.2`, corrélation complète des `PID 150..186` de
`raw.tar.gz` et reproduction déterministe archivée dans
[renewal-diagnostic-reproduced.tar.gz](../testing/evidence/L4-I2/development/renewal-diagnostic-reproduced.tar.gz))
établit la chaîne physique suivante :

1. **Écart entre la production L2/I5 et le banc L2/I3** : en production,
   `V3ConsumptionWorkerSettings.PoolSpec` (ligne 295) et
   `V3ConsumptionWorkerConfiguration.createPool` (lignes 407–435) imposent `minimumIdle == maximumPoolSize`
   et attendent l’établissement effectif de la réserve physique (`getIdleConnections() == spec.maximum()`,
   `"physical pool reserve not established"`). En revanche, le test `renewalsHaveReservedDatabaseAccess`
   (écrit en L2/I3 avant `V3ConsumptionWorkerConfiguration`) configurait `pool.setMinimumIdle(0)` sur
   un `HikariDataSource` de `maximumPoolSize = 8`.
2. **Mécanisme interne `HikariCP 7.0.2` (`PoolEntryCreator` + `ConcurrentBag`)** :
   `HikariPool.addConnectionExecutor` est un exécuteur mono-thread (`ThreadPoolExecutor(1, 1, ...)`).
   Chaque tâche `HikariPool$PoolEntryCreator.call()` crée au plus **une** connexion physique
   (`goto 143` / `break` à l’offset bytecode 70–73) puis exécute `quietlySleep(30L)` (30 ms) sur
   l’unique thread `Connection adder` *après* `connectionBag.add(poolEntry)`.
3. **Perte de la 3e soumission `addBagItem(waiting)` sous `minimumIdle = 0`** : après l’emprunt
   séquentiel des 5 connexions retenues (`PID 176..180`, `total = 5, active = 5, idle = 0`), le
   thread `addConnectionExecutor` est encore dans la fenêtre `quietlySleep(30L)` de la 5e connexion.
   Lorsque les trois threads de renouvellement (`claim-1`, `claim-2`, `lease`) entrent simultanément
   dans `ConcurrentBag.borrow(1000, MILLISECONDS)`, chacun incrémente `waiters` (`waiting = 1, 2, 3`),
   parcourt `sharedList` (5 entrées `STATE_IN_USE`) sans verrou, puis appelle `HikariPool.addBagItem(waiting)`
   qui teste `if (waiting - addConnectionExecutor.getQueue().size() >= 1)`. Dès que le thread ayant
   capturé `waiting = 1` évalue cette garde après que les deux autres threads (`waiting = 2` et `3`)
   ont enfilé leurs tâches `PoolEntryCreator` dans `getQueue()` (taille `2`), `1 - 2 >= 1` vaut `false` :
   la 3e création de connexion n’est pas soumise. Comme `minimumIdle == 0`, `fillPool()` en fin de
   tâche est un no-op, laissant le pool bloqué à `total = 7, active = 7, idle = 0, waiting = 1`.
4. **Exception de la 3e lane et absence de 3e backend PostgreSQL** : la 3e lane n’obtient jamais de
   backend PostgreSQL (le `PID 184` n’est ouvert que 6 s plus tard par le test suivant
   `readAuthorityCannotExecuteCommandTask`), expire après `1000 ms` sur `handoffQueue.poll` avec
   `SQLTransientConnectionException: HikariPool - Connection is not available, request timed out after 1000ms (total=7, active=7, idle=0, waiting=0)`,
   convertie par `PlatformTransactionManagerAdapter.executeControl` en
   `AttemptTransactions.Failure(Resolution.UNKNOWN)`, et n’atteint jamais `entered.countDown()`.
5. **Correction minimale et contre-oracle** : aucun code de production n’est modifié. Dans
   `ConsumptionLifecycleCompositionPostgresTest`, le pool du test établit désormais sa réserve
   physique comme en production (`pool.setMinimumIdle(8)` + `awaitPhysicalPoolReserve(pool, 8)`),
   en conservant strictement `maximumPoolSize = 8`, `5` connexions retenues, `3` lanes concurrentes,
   `connectionTimeout = 1000 ms`, la barrière de `8 s`, l’instrumentation diagnostique des `Future`
   et toutes les assertions. Un contre-oracle (`assertUnreservedRenewalFailsWhenSixHeld`, plus la
   preuve dédiée [renewal-unreserved-counteroracle-fail.tar.gz](../testing/evidence/L4-I2/development/renewal-unreserved-counteroracle-fail.tar.gz))
   vérifie à chaque exécution (`WRITE` et `READ`) que si 6 connexions non-renouvellement sont retenues
   sur 8 (ne laissant que 2 connexions réservées pour 3 renouvellements), la 3e lane échoue en ~1 s
   avec `AttemptTransactions.Failure(Resolution.UNKNOWN)` causé par `SQLTransientConnectionException`
   (`total=8, active=8, idle=0, waiting=0`).

## AUD-I2-08/09/10 et passage A → B

| Finding | Correction du plan et contrat réalisé |
|---|---|
| AUD-I2-08 | §4.5 : sept catégories d’erreur, poison Unit/Guard, propagation et cause originale, résolution physique indépendante, classement/budget L2 et capacité. Une exception pure absorbée hors port gardé n’est pas prétendument détectable. |
| AUD-I2-09 | §4.6 : callback actif puis expiré, synchronisations, barrière pré-doCommit après callbacks, notification physique et accès tardif. Catch ne réarme pas Guard ; COMMITTED confirmé reste COMMITTED. |
| AUD-I2-10 | §4.7 : UUID global unique, jointures non filtrantes position/enveloppe/compteur, validation famille/position/sujet, vraie absence bornée au mapping, incohérence positive et indisponibilité séparées ; producteurs réels et contre-lookups. |

Avant modification Java, les contrats ont été contrôlés contre Engine, Spring, ports,
compositions, mappings et migrations. La source locale Spring 7.0.6 confirme l’ordre des
synchronisations, du listener d’exécution `beforeCommit`, de `doCommit` et des notifications.
Les six critères A → B étaient satisfaits : contrats sans contradiction ; décisions fondamentales
inchangées ; aucun mécanisme architectural majeur supplémentaire ; aucune migration/POM/structure
runtime requise ; contrats codables/testables ; invariants I1/L2/L3 préservés par conception.
Le passage automatique B suit le mandat humain du 2 novembre 2026 ; il ne vaut pas acceptation du code.

## Frontière déclarée et portée réellement exécutée

PROJECTION principale, COMMAND secondaire ; Architecture globale REQUIRED (§17) et MONOLITH
obligatoire. Aucun franchissement inattendu de slice de production. BINDING ne change pas et
n’utilise pas directement ces contrats V3 dans sa racine/supra ; sa gate autonome n’est pas requise.
Aucune slice FOUNDATION L2 n’est créée. Les owners Spring sont qualifiés par les consommateurs.

La commande Architecture prescrite sélectionne **60/60 modules enfants** du POM actuel par
`-am`, POM inchangés : le reactor complet est donc couvert transitivement par cette gate globale
obligatoire. Aucune commande autonome `./mvnw test` n’a été lancée. La déclaration initiale
« full reactor NOT REQUIRED » visait l’absence d’escalade autonome ; la fermeture réelle est
exposée ici. Cette couverture Maven ne constitue pas une certification SPEC-10/production.

Base CURRENT V3 fraîche, sans baseline TRUSTED invoquée. Les migrations historiques entraînées
par les suites obligatoires sont conservées ; aucune nouvelle migration ni chaîne historique
supplémentaire n’est ajoutée. Les nouveaux bancs utilisent PostgreSQL 17.11 aarch64, image I1
immuable `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`.

## Contrats effectivement implémentés

Un seul `ConsumptionEngine` reçoit les owners Business source/effets et Control existants.
`PreparationReads` ouvre des scopes RR read-only successifs et attend chaque retour physique.
La session privée lie ces unités et la finale RC à une Guard pure, monotone, privée de L2.
L’expiration ferme les ouvertures ; elle conserve le lien de poison des anciens contextes.
Aucun manager/Unit n’est piloté depuis un thread étranger. Une violation gardée interceptée reste
bloquante aux barrières du callback et au listener I1 pré-doCommit. Après commit confirmé,
une faute tardive refuse le SQL et n’annule pas rétroactivement le commit.

Les enveloppes `observed` couvrent contexte, SQL, décodage et comparaison. `participating`
préserve les lecteurs L1 autonomes et refuse une autorité étrangère lorsqu’un Unit est actif.
La première cause RuntimeException/Error est conservée ; SQL/JPA ne devient pas une faute
structurelle du seul fait de la garde. `PreparationReads.Invalid` est NON_COUNTABLE. Les règles
physiques L2 ne changent pas : notification de rollback, chemin handedToCommit, rejet serveur,
transport et COMMITTED confirmé restent distincts. Une sortie brute d’owner conserve sa propagation
et sa rétention ; une faute Java détachée après lectures résolues ne fabrique pas un read UNKNOWN.

Command et Identity extraient le corps de préparation existant dans un seul `prepareInSnapshot`
source. Aucune décision EUI ne quitte les verrous de la finale RC Identity. Les probes techniques
existantes conservent un scope préparatoire READ `effects`, puis leur seule finale RC READ ;
leur recovery distingue aussi un effet présent mais divergent. Aucun participant métier I3–I7
ni activation READ I8 n’est livré. Aucun POM, migration, runtime structurel, pool, grant de
production, Worker, fencing, renouvellement, quota ou protocole durable n’est modifié.

Recovery READ : source exacte PRIMARY résolue, puis comparaison différée et **un** snapshot READ
pour slot, checkpoint C/p et effets. Slot présent + effet identique préexistant reste NOT_COMPLETED ;
COMPLETED exige source éligible, couverture suffisante, slot absent et preuve complète satisfaite.
Absence/corruption positive et indisponibilité technique restent séparées. Recovery WRITE :
demande/résultat/historique/publications et slot dans WRITE, sans consultation de checkpoint.
Ni reçu SQL, ni UNKNOWN durable, ni restitution depuis une simple observation n’est ajouté.

Le lookup exact est ancré sur l’UUID demandé, avec LEFT JOIN de la position **sans filtre de famille**,
de l’enveloppe mappée et du compteur. Les PK/UNIQUE existantes bornent la cardinalité. Le lookup
vérifie les deux identités, la famille, la position et le sujet ; absence réelle et incohérence
positive ne disparaissent pas dans une jointure filtrante. Les quatre producteurs livrés sont
exercés via publications Command et Identity réelles ; Invitation reste refusée.

## Fichiers créés et modifiés

L’[inventaire applicatif](../testing/evidence/L4-I2/Implementation_Files.json) contient les chemins,
CREATE/MODIFY et SHA-256 : **54 fichiers applicatifs, cinq créations et 49 modifications**.
Créations : `PreparationReads`, son test, le fixture PostgreSQL interautorités et ses deux suites
préparation/recovery. Modifications : Engine/port participant/transaction/source, owner Spring,
lecteurs JDBC, Command/Identity, compositions et adaptateurs/instrumentations de tests existants.

Le plan amendé, ce rapport, le [manifeste](L4_I2_Proof_Manifest.json), les index de preuves et
archives complètent la livraison. Aucun POM, migration, ressource runtime, pool ou grant de
production modifié ; aucun participant métier I3–I7, activation READ I8, second moteur ou reçu durable.
Les fixtures READ historiques utilisent une source SQL analogue explicite ; elles ne sont pas
présentées comme les producteurs PRIMARY des nouveaux bancs à deux bases.

## Gates et résultats effectifs

Comptes : **tests / failures / errors / skips**. Les invocations sont contrôlées par identité,
avec provenance isolée et zéro skip, sans reprise de XML partagés de `target/`.

| Gate | Modules | Compte | Résultat | Durée Maven |
|---|---:|---|---|---:|
| PROJECTION + COMMAND | 51 | 1873 / 0 / 0 / 0 | PASS | 3127,319 s |
| Architecture obligatoire | 60 | 2137 / 0 / 0 / 0 | PASS | 3344,163 s |
| MONOLITH inclus dans Architecture | 1, sous-ensemble | 14 / 0 / 0 / 0 | PASS, quatre suites exactes | incluse |

Commandes exécutées depuis `app/` :

```sh
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am test
./mvnw -pl architecture-tests -am test
```

Les argv complets ajoutent seulement batch/no-transfer-progress et les deux propriétés de
provenance ; ils sont conservés dans les plans/reçus. Aucune exclusion, `-Dtest`, skip ni changement
opportuniste de gate n’est utilisé pour ces deux gates. MONOLITH couvre nominativement
`ConsumptionPilotIsolationTest` (3), `RuntimeMonolithPostgresTest` (3),
`V3IdentityRequestMonolithPostgresTest` (7), `V3WorkerMonolithPostgresTest` (1), avec correspondance
exacte des méthodes attendues/observées. Sa gate autonome n’est donc pas nécessaire.

| Contribution | Canonique | Architecture |
|---|---|---|
| `PreparationReadsTest` | 12/0/0/0 | 12/0/0/0 |
| `AttemptTransactionsPostgresTest` | 13/0/0/0 | 13/0/0/0 |
| `SubobjectAtomicityPostgresTest` | 12/0/0/0 | 12/0/0/0 |
| `SubobjectStorePostgresTest` | 11/0/0/0 | 11/0/0/0 |
| `SubobjectMigrationPostgresTest` | 6/0/0/0 | 6/0/0/0 |
| `ConsumptionLifecyclePostgresTest` | 45/0/0/0 | 45/0/0/0 |
| `DiscoveryPostgresTest` | 49/0/0/0 | 49/0/0/0 |
| `SlotNavigationPostgresTest` | 8/0/0/0 | 8/0/0/0 |
| `L2ManifestProof` | 142/0/0/0 | 142/0/0/0 |
| `CommandExecutionPostgresTest` | 31/0/0/0 | 31/0/0/0 |
| `IdentityRequestExecutionPostgresTest` | 13/0/0/0 | 13/0/0/0 |
| `IdentityConcurrencyPostgresTest` | 8/0/0/0 | 8/0/0/0 |
| `IdentityPotAuthorityPostgresTest` | 11/0/0/0 | 11/0/0/0 |
| `InterAuthorityPreparationPostgresTest` | 15/0/0/0 | 15/0/0/0 |
| `InterAuthorityRecoveryPostgresTest` | 10/0/0/0 | 10/0/0/0 |
| `V3WorkerPostgresTest` | 21/0/0/0 | 21/0/0/0 |
| `V3WorkerResourcesPostgresTest` | 13/0/0/0 | 13/0/0/0 |
| `V3IdentityRequestCompositionPostgresTest` | 10/0/0/0 | 10/0/0/0 |
| `V3IdentityPotCommandPostgresTest` | 9/0/0/0 | 9/0/0/0 |
| `ConsumptionLifecycleCompositionPostgresTest` | 5/0/0/0 | 5/0/0/0 |

Les autres suites et identités effectives sont dans
[Suite_Contributions.json](../testing/evidence/L4-I2/Suite_Contributions.json) et les reçus.
Les comptes des deux gates se chevauchent : ils ne s’additionnent pas en nombre de propriétés distinctes.

## Scénarios discriminants P/W/R/C

Les 15 tests de préparation et dix de recovery interautorités passent dans les deux gates.
Les témoins utilisent de vraies bases/rôles/connexions PostgreSQL, observations externes,
PID/snapshots/RO/isolation/locks et proxies d’ACK. Les contre-oracles incorrects sont nommés et
conservés. Aucun mock n’est présenté comme preuve d’une propriété physique.

| IDs | Contributions et oracles |
|---|---|
| P01 | `p01ConsecutiveAuthoritiesPhysicalSnapshotsAndAtomicFinalization` : source→effects→source→finale, deux bases, rôles/PID/snapshots, isolation serveur, borrow/return sans chevauchement, témoins effet/DELETE dans le même XID RC |
| P02 | `p02CaughtNestedAndForeignScopesNeverOpenAnotherAuthority` + `PreparationReadsTest` : refus imbriqué/étranger, compteur de begin physique, catch sans réarmement ; owner réel pour nesting/ambient |
| P03 | `p03ExpiredSourceContextPoisonsEffectsAndFinalEvenWhenCaught` et contre-forwarder : slot/préfixe rollbackés ; ancien contexte depuis thread étranger avec garde transmise, puis contre-exemple qui perd la garde et laisse committer, détecté par le même oracle |
| P04 | `p04AndP07PhysicalReadAckLossNeverAllowsNextScopeOrCapacitySettlement` : proxy filaire avant COMMIT/après réponse serveur, aucun scope suivant/finaliseur/recovery, ticket conservé malgré slot accessible |
| P05 | Six lecteurs publics d’historique étrangers interceptés (rollback et zéro borrow source), SQL invalide, décodage Java après SELECT, mauvaise datasource et writer de mauvaise autorité : première cause, SQL_OR_JPA/TECHNICAL, budget une fois, refus avant SQL PRIMARY et rollback effects/slot |
| P06 | Interruption virtual thread → rollback ACK perdu/UNKNOWN ; Future annulé pendant vrai lock PostgreSQL sur source platform thread → callback encore actif, renewal réel, aucune restitution avant déblocage/sortie physique, flag conservé |
| P07 | ROLLED_BACK certain, read COMMITTED avec cleanup perdu et résultat écarté, ACK UNKNOWN et Error brut après ACK réel : responsabilités et restitution distinctes ; aucun read ne devient commit métier |
| P08 | `p08DetachedValuesClosedCursorAndNoSourceAccessDuringFinalization`, `p08RetainedSourceSupplierCannotRunInsideReadFinalization`, contexte expiré, API de capacité expirée et règles DTO/architecture : résultat matérialisé, curseur fermé, pas de connexion/SQL source pendant finale READ ; pas prétention à un type linéaire Java |
| W01 | Nouveau test Command : mutation d’autorité entre load et observe/history, trois lectures même PID **et snapshot**, ancienne autorité cohérente, protection RC constate le changement et collision sans décision hybride |
| W02 | `IdentityRequestExecutionPostgresTest`, `IdentityConcurrencyPostgresTest`, `IdentityPotAuthorityPostgresTest` : décisions sous locks EUI, races Registration/Link/Unlink, attente réelle, token périmé et ordre canonique |
| W03 | Tests nouveaux de Guard JDBC/JPA dans `AttemptTransactionsPostgresTest` et `w03CaughtSpringCallbacks...` : beforeCommit/beforeCompletion interceptés empêchent commit ; afterCompletion(COMMITTED) refuse accès sans réécrire le résultat durable |
| W04 | Régression Command/Identity complète : terminal au prepare, rejected/unchanged, historique/publications et collisions ; recovery burnable sans checkpoint |
| R01 | Checkpoint exactement égal à la position ou supérieur grâce à une deuxième publication réelle ; présence du slot avec effet absent, ensemble partiel conforme, complet identique préexistant : NOT_COMPLETED, jamais reçu implicite interslots |
| R02 | Position source 2 réellement publiée ; checkpoint absent/0/1 insuffisant, mauvais C ou p, autres checkpoints plus hauts : INCONSISTENT, même si effet exact existe |
| R03 | Slot absent + couverture + contenu exact : COMPLETED ; absence/partiel/divergence : INCONSISTENT, témoins SQL indépendants |
| R04 | Quatre familles réelles, UUID absent, famille erronée, position/enveloppe orpheline, compteur manquant/régressé, sujet invalide et droits indisponibles ; inéligibilité Identity POCOMA_USER ; contre-lookup filtrant/INNER JOIN détecté |
| R05 | Recovery suspendu après ancien slot absent, admission et finale concurrentes : même snapshot garde ancien K/effets et refuse le faux COMPLETED ; contre-reader mixant ancien slot et nouveaux K/effets le produit et est distingué |
| R06 | ACK final filaire perdu avant/après COMMIT : effet/slot extérieurs et réponse serveur indépendants, observation business séparée de UNKNOWN historique et de la restitution par inspection |
| R07 | Comparaison calcule SATISFIED mais ACK du read final perdu : UNAVAILABLE, exclusion Engine monotone, pas de nouvelle observation/règlement optimiste |
| R08 | 18 cas de représentations techniques : absence conforme de balance supprimée, exactitude de version/contenu, pointeur dépassé cohérent ou corrompu, résultat partiellement égal ; aucun participant métier futur livré |
| R09 | Huit cellules WRITE Command pending/terminal × slot présent/absent × préfixe sain/corrompu ; spy checkpoint interdit + observations L1/L3 réelles |
| C01/C02 | P04/P06/P07/R06/R07 + audits L2 : callback actif vs revenu, LOCKED/PRESENT_UNLOCKED/ABSENT, UNKNOWN préparatoire non réglable par le slot, pas de faux COMPLETED |
| C03/C04 | Nouveau Worker réel : M_W/M_C saturés, scope source puis effects longs, renewals Control observés et échéance SQL avancée, drain au-delà du budget sans fermeture ni STOPPED, terminaison après vraie sortie ; campagnes Worker quatre pools dans gates |
| C05 | `ConsumptionFailureClassificationTest` et `ConsumptionLifecyclePostgresTest` complets, notamment `i3_c05_*`, `i3_c03_j`, `i3_capacity_uncertainty`, audits classifier/cession : cause, budget une fois, cession distincte et jamais répétée sur ACK perdu |

Les contributions C03/C04 nouvelles, les campagnes Worker/ressources et le scénario de réserve
des renouvellements `ConsumptionLifecycleCompositionPostgresTest#renewalsHaveReservedDatabaseAccess`
(en `WRITE` et en `READ`, avec son contre-oracle de non-réservation) passent dans les deux gates
canoniques (`canonical` et `architecture`).

## Requalification L2, Command/Identity et I1

Fresh : owners JDBC/JPA et callbacks tardifs ; stockage/atomicité/codec I1 ; lifecycle,
Discovery/navigation, budget/cession et lost ACK L2 ; Command, Identity/EUI/locks/publications ;
Worker, quatre pools, saturation, renewals, fencing, drainage ; compositions autonomes et MONOLITH.
Les anciens totaux ne sont pas réutilisés comme nouveaux PASS. Les décisions Identity restent
sous verrous EUI dans la finale RC ; Command conserve un seul snapshot source et son contrôle final.
Le poisoning tardif I1 est conservé, y compris après callback Java et avant commit Spring.

La requalification des propriétés L2 (dont l’accès réservé des trois lanes de renouvellement
en `WRITE` et `READ` sous saturation de 5 connexions retenues sur 8) est complète dans les deux
gates (`3,856 s` en canonique, `4,024 s` en Architecture). Aucune libération de capacité
n’est déduite d’une observation métier ou d’un backend désormais absent après UNKNOWN préparatoire.

| Campagne | Réserve historique | Observation locale | Durée de méthode |
|---|---|---|---:|
| Canonique | `i5_r05` | PASS | 42.294 s |
| Canonique | `i5_r12` | PASS | 51.108 s |
| Architecture | `i5_r05` | PASS | 43.043 s |
| Architecture | `i5_r12` | PASS | 50.390 s |

Les réserves historiques I1/r05/r12 et leurs attributions sont conservées dans les documents
originaux. Le registre reste `QUALIFIED, active=false` ; aucun timeout/oracle/quarantaine n’est
modifié. Ces succès locaux ne lèvent pas les réserves historiques.
L4/I1 reste CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS selon la décision humaine.
Une observation hors périmètre I2 est également documentée sans modification : lors d’une tentative
intermédiaire de requalification (archivée dans
[requalification-l3-waiterpid-cme-fail.tar.gz](../testing/evidence/L4-I2/development/requalification-l3-waiterpid-cme-fail.tar.gz)),
le helper de test L3/I2 `IdentityPotAuthorityPostgresTest#waiterPid` (ligne 425, introduit au commit
`145f42731a4e35488e9aaa56a7de3c3f453a9231`) a levé une `ConcurrentModificationException` sur
`CopyOnWriteArrayList$COWSubList.getArrayChecked` parce qu’il appelle `lockCalls.subList(mark, lockCalls.size()).stream()`
dans une boucle active pendant que le thread `waiter` ajoute un élément à `lockCalls`. Conformément
au mandat borné à `I2-QUAL-01`, ce fichier L3/I2 n’a pas été touché.

## Provenance des preuves et octets livrés

[Manifeste de livraison](L4_I2_Proof_Manifest.json),
[index physique](../testing/evidence/L4-I2/Physical_Index.json),
[couverture MONOLITH](../testing/evidence/L4-I2/Monolith_Coverage.json),
[notice/opérateur](../testing/evidence/L4-I2/README.md).

Les sources ont été qualifiées dans le worktree détaché isolé
`/private/tmp/pocoma-l4-i2-qualification` au commit propre
`6496e6d0144ecfda9e58ec6d12e259445e7344ad` (`dirty = false`). **Les 1288 blobs applicatifs du
commit `6496e6d0144ecfda9e58ec6d12e259445e7344ad` correspondent exactement aux empreintes testées
par la campagne canonique (`canonical/`) et par la campagne Architecture (`architecture/`).**
SQL externes, sélecteur, registre, settings et opérateur sont contrôlés avant/après ; les mêmes
octets XML évalués sont archivés avec SHA-256.

La [comparaison des entrées](../testing/evidence/L4-I2/Canonical_To_Delivery_Input_Comparison.json)
confirme **1288 entrées identiques sur 1288** (`0` différence) entre la campagne canonique, la
campagne Architecture et les octets livrés. L’opérateur étend seulement l’attribution nominative du
second test top-level existant `CurrentTransactionManagerPostgresTest` à son fichier source, comme
pour I1. Le refus initial du sélecteur standard reste archivé (INCONCLUSIVE, zéro test), sans
affaiblir découverte/verdict.

L’intégrité SHA-256 des archives et de tous leurs membres référencés a été vérifiée
([Integrity_Verification.json](../testing/evidence/L4-I2/Integrity_Verification.json)).
L’index contient **2650 pièces physiques** sur les deux campagnes (`1321` en canonique, `1329` en
Architecture). Les identifiants d’invocation sont `a51dbdc33d474f6da594a415230a9925` (canonique,
`raw.tar.gz` SHA-256 `53c08f5f98934d332744be7553a5c7d3cb16254e13b22eeedaabd96b3cf56f0b`) et
`db612392a6a143cda26be68bc2f5a209` (Architecture, `raw.tar.gz` SHA-256
`d9dfb3c626c3801d7294fd80faa8598f654b591ae199fdd44e4d251b648d00c0`).

## Diagnostics conservés et corrections locales

Les [archives de développement](../testing/evidence/L4-I2/development/) gardent les FAIL,
reproductions causales et contre-preuves ; elles ne certifient pas les octets finaux. Les principaux
diagnostics sont :

- Propagation brute d’owner et rétention corrigées ; refus de mauvaise datasource intercepté
  désormais exigé empoisonnant, sans supprimer les assertions internes.
- Virtual-thread interruption réellement UNKNOWN sur rollback ACK perdu : oracle conservateur
  renforcé, FAIL initial conservé. Annulation Future platform thread distinguée de la sortie réelle.
- Stubs `Unresolved compilation problem` dans les targets partagés, démontrés par `javap` ;
  producteur non observé. Qualification déplacée dans un checkout sans targets repris.
- Refus de setup Identity réel conservés ; paramètres existants `identity_authority=real` et
  `identity_read_role` distinct fournis, sans modifier les grants ni les contrôles de droits.
- Campagne volontairement interrompue après 1296 observations partielles sans failure/error/skip :
  renforcement de K=position, initialement masqué par seed K=17. Le trigger a refusé la baisse 17→1 ;
  ce FAIL est conservé. I2 insère maintenant le vrai K dès le seed, avec égalité et dépassement
  testés par publications réelles ; le seed historique I1 reste 17.
- `canonical-fail-navigation` : 1438/0/5/0, anciens constructeurs READ ; adaptation explicite,
  checkpoints SQL et huit tests de navigation rejoués. Le refus de compilation par import absent
  est conservé séparément.
- `worker-integration-fail` : 70/1/1/0. Datasource checkpoint du banc de faute restée ancienne,
  alignée avec le proxy ; source/references READ alignées lors du remplacement WRITE. `r08`
  exige le rollback après refus intercepté. Aucun seuil/runtime/garantie de drainage changé.
- Contre-preuve réelle `componentsAtVersion` non gardé : 1/1/0/0, COMMITTED au lieu de ROLLED_BACK.
  Wrapper corrigé, test couvrant les six lecteurs publics et témoins externes. Le groupe physique
  corrigé termine 52/0/0/0 (Spring 13, I1 atomicité 12, I2 25, deux cas Worker).
- `canonical-fail-store-owner` : 1553/0/1/0. Retour sain ancien après refus intercepté dans
  le stockage I1 ; refus internes conservés et rollback/cause/zéro version durable exigés.
  Replay intégral stockage 11/0/0/0.
- `canonical-fail-renewal` : 1873/1/0/0, échec initial de `I2-QUAL-01` conservé intégralement ;
  reproduction causale déterministe dans `development/renewal-diagnostic-reproduced.tar.gz`,
  contre-oracle d’absence de réserve dans `development/renewal-unreserved-counteroracle-fail.tar.gz`,
  validation ciblée dans `development/renewal-fixed-targeted-pass.tar.gz`, et requalification
  canonique complète réussie dans `canonical/` (`1873/0/0/0`) et `architecture/` (`2137/0/0/0`).

Feedbacks récents exécutés (filtrés, non canoniques) :

```sh
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am -Dtest=SlotNavigationPostgresTest,V3WorkerPostgresTest,V3WorkerResourcesPostgresTest,I5PhysicalWindowTest,I5TemporalV2Test,I5TemporalBudgetTest,V3IdentityRequestCompositionPostgresTest,V3PotCommandCompositionPostgresTest,V3IdentityPotCommandPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am '-Dtest=InterAuthorityPreparationPostgresTest#p05AllPublicHistoryReadsPoisonCaughtForeignAuthorityAccess' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am '-Dtest=InterAuthorityPreparationPostgresTest,InterAuthorityRecoveryPostgresTest,SubobjectAtomicityPostgresTest,AttemptTransactionsPostgresTest,V3WorkerPostgresTest#i5_w21,V3WorkerResourcesPostgresTest#i5_r08' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am -Dtest=SubobjectStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-command-consumption-worker -am '-Dtest=ConsumptionLifecycleCompositionPostgresTest#renewalsHaveReservedDatabaseAccess' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-command-consumption-worker -am -Dtest=ConsumptionLifecycleCompositionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Les compilations/test-compile et autres feedbacks natifs antérieurs restent dans les logs archivés,
sans PASS transféré aux octets livrés. Préflight standard refusé :
`python3 -B scripts/testing/verify.py CHANGE PROJECTION,COMMAND` (zéro test).

## Limites, publication et suite requise

L’absence de participant métier I3–I7 et d’activation I8 limite les preuves à l’infrastructure I2,
aux probes et aux représentations techniques prévues au plan. Le contrat générique Java ne
prétend pas être un type linéaire ; une exception pure absorbée sans interaction gardée reste
inobservable. Aucune certification globale PRJ/READ/SEC/OPS/PERF/production n’est prononcée.

Publication sur `feat/v3-architecture`, en deux ensembles cohérents correctif de test puis
rapports/preuves, sans force. Le worktree initial était propre. Les empreintes et `git diff --check`
sont vérifiés ; le message de livraison rapporte les SHA local/distant directement comparés,
la synchronisation et la propreté après publication. Les documents historiques restent inchangés.

### État historique avant décision de clôture

La réserve bloquante `I2-QUAL-01` est résolue et qualifiée sur les deux gates canoniques.
L’audit indépendant du code et des preuves ainsi que la décision humaine d’acceptation restent
nécessaires avant toute clôture de L4/I2.

**I2-QUAL-01 RESOLVED — LOCAL BOUNDED PASS** : implémentation livrée, cause de `I2-QUAL-01`
démontrée et corrigée dans le banc avec contre-oracle, et gates canoniques `PROJECTION + COMMAND`
(`1873/0/0/0`) et `Architecture` (`2137/0/0/0`) réussies sur les mêmes octets applicatifs.
I2 n’est pas déclaré automatiquement CLOSED.


## Clôture définitive du 3 novembre 2026

**L4/I2 — CLOSED — LOCAL BOUNDED PASS.**

La décision humaine explicite du 3 novembre 2026 acte la clôture définitive bornée de I2.
`I2-QUAL-01` est RESOLVED ; aucun défaut de production n’a été identifié dans sa résolution
et aucune réserve bloquante I2 ne subsiste. Les deux gates canoniques déjà acceptées sont
PROJECTION + COMMAND : **1873/0/0/0**, Architecture : **2137/0/0/0** (tests/failures/errors/skips),
au commit applicatif `6496e6d0144ecfda9e58ec6d12e259445e7344ad`, avec publication des preuves
`2a81d49ebeb595cc2c46f14b3d850cbf92863f93`. Ces résultats sont acquis et ne sont pas rejoués.
La clôture I2 est indépendante du correctif du helper L3/I2 `IdentityPotAuthorityPostgresTest#waiterPid`.
Les échecs, corrections, contre-oracles et réserves historiques restent attribués à leurs campagnes.
Cette décision ne certifie ni SPEC-10 global ni la clôture finale de L4 ; L4 reste ouvert.

Le mandat humain courant supplante l’attente procédurale de clôture conservée ci-dessus ;
aucun nouvel audit indépendant n’est revendiqué. Le manifeste publié au commit `2a81d49…`
reste une pièce historique inchangée : sa mention « I2 is not CLOSED » décrit l’état avant
cette décision. Les empreintes et campagnes du manifeste restent liées au SHA qualifié,
sans réattribution globale au commit corrigeant le helper.

La présente mission ne modifie aucun code de production et ne relance aucune gate COMMAND,
PROJECTION, Architecture, ni full reactor. Le [rapport ciblé du helper](L3_I2_WaiterPid_Fix_Report.md)
porte séparément commande, inventaire, résultat, durée et provenance de sa seule classe de test.
La [campagne globale finale](L4_Read_Inventory_and_Implementation_Plan.md#qualification-globale-reportée-à-la-clôture-l4)
est reportée à la clôture du lot L4 après achèvement des incréments restants.

La [liste exacte des 21 fichiers livrés](L4_I2_Closure_Delivery_Files.json) couvre le helper,
son rapport/provenance et les documents de clôture ; aucune preuve brute historique I2
n’est modifiée. Les contrôles documentaires exécutés sont les lectures Git/rg/sed,
la validation Python des liens, empreintes d’archives et entrées testées, et `git diff --check`.
Le seul Maven exécuté dans cette mission est celui du rapport ciblé lié ci-dessus.
