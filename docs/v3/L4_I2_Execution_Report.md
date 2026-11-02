# Pocoma V3 — L4/I2 — Rapport d’exécution

**IMPLEMENTED — QUALIFICATION INCOMPLETE.** Les contrats I2 sont implémentés et les gates
requises ont été exécutées. La gate canonique complète reste FAIL sur un renouvellement READ
non expliqué ; son replay diagnostique et la gate Architecture réussis ne résolvent pas cet échec.
I2 n’est pas CLOSED. L’acceptation du code reste soumise à l’audit indépendant et à la décision humaine.

- SHA initial audité : `a9937991a75428d8718e6036da11be2382c633d0`.
- Commit applicatif final : `23ad96d6b0ed9c075573247edf9989ed10472887`.
- Branche : `feat/v3-architecture`, propre au début de mission ; aucun travail préexistant modifié.
- Commit de livraison : `git log -1 --format=%H -- docs/v3/L4_I2_Proof_Manifest.json`.
  Le SHA de publication et la vérification directe du distant sont donnés dans le message de livraison.

## Réserve bloquante I2-QUAL-01

`ConsumptionLifecycleCompositionPostgresTest#renewalsHaveReservedDatabaseAccess` échoue dans
la gate canonique à la barrière de trois lanes physiques (attente inchangée de 8 s).
Les traces montrent trois PID WRITE, puis deux PID READ. Le journal initial ne capture pas
l’exception de la troisième tâche ; la cause physique demeure **non établie**.

Le diagnostic ajouté au test enregistre les exceptions par lane et, en cas de barrière manquée,
l’état Hikari et `pg_stat_activity`. La configuration reste pool 8, cinq emprunts retenus,
connexion 1 s, attentes 8 s ; toutes les assertions sont conservées. Le replay 1/0/0/0 ne reproduit
pas le failure. Aucun préchauffage, retry caché, exclusion ou délai augmenté n’est introduit.
La gate Architecture ultérieure passe aussi, mais ne constitue pas une correction démontrée de
ce failure. Les pièces originales restent dans
[canonical-fail-renewal](../testing/evidence/L4-I2/canonical-fail-renewal/result.json).

La requalification complète des propriétés L2 de disponibilité/renouvellement reste donc
incomplète. Cette réserve est distincte des réserves historiques r05/r12 et ne leur est pas attribuée.
Elle doit être expliquée et corrigée, puis qualifiée aux mêmes oracles avant tout PASS global I2.

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
| PROJECTION + COMMAND | 51 | 1873 / 1 / 0 / 0 | FAIL, I2-QUAL-01 | 3135,850 s |
| Architecture obligatoire | 60 | 2137 / 0 / 0 / 0 | PASS | 3263,208 s |
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
| `ConsumptionLifecycleCompositionPostgresTest` | 5/1/0/0 | 5/0/0/0 |

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

Les contributions C03/C04 nouvelles passent, ainsi que Worker/ressources ; **la famille de
qualification capacité/renouvellement reste toutefois incomplète** à cause du failure obligatoire
I2-QUAL-01. Cette matrice ne transforme pas la campagne canonique FAIL en PASS.

## Requalification L2, Command/Identity et I1

Fresh : owners JDBC/JPA et callbacks tardifs ; stockage/atomicité/codec I1 ; lifecycle,
Discovery/navigation, budget/cession et lost ACK L2 ; Command, Identity/EUI/locks/publications ;
Worker, quatre pools, saturation, renewals, fencing, drainage ; compositions autonomes et MONOLITH.
Les anciens totaux ne sont pas réutilisés comme nouveaux PASS. Les décisions Identity restent
sous verrous EUI dans la finale RC ; Command conserve un seul snapshot source et son contrôle final.
Le poisoning tardif I1 est conservé, y compris après callback Java et avant commit Spring.

La requalification L2 est **partielle**, malgré les contributions réussies, puisque l’accès
réservé d’une lane READ n’est pas expliqué dans le run canonique. Aucune libération de capacité
n’est déduite d’une observation métier ou d’un backend désormais absent après UNKNOWN préparatoire.

| Campagne | Réserve historique | Observation locale | Durée de méthode |
|---|---|---|---:|
| Canonique | `i5_r05` | PASS | 42.552 s |
| Canonique | `i5_r12` | PASS | 51.005 s |
| Architecture | `i5_r05` | PASS | 42.513 s |
| Architecture | `i5_r12` | PASS | 49.883 s |

Les réserves historiques I1/r05/r12 et leurs attributions sont conservées dans les documents
originaux. Le registre reste `QUALIFIED, active=false` ; aucun timeout/oracle/quarantaine n’est
modifié. Ces succès locaux ne lèvent pas les réserves et n’expliquent pas I2-QUAL-01.
L4/I1 reste CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS selon la décision humaine.

## Provenance des preuves et octets livrés

[Manifeste de livraison](L4_I2_Proof_Manifest.json),
[index physique](../testing/evidence/L4-I2/Physical_Index.json),
[couverture MONOLITH](../testing/evidence/L4-I2/Monolith_Coverage.json),
[notice/opérateur](../testing/evidence/L4-I2/README.md).

Les sources ont été qualifiées dans le worktree détaché isolé
`/private/tmp/pocoma-l4-i2-qualification`. **Les 1288 blobs applicatifs du commit
`23ad96d6b0ed9c075573247edf9989ed10472887` correspondent aux empreintes testées par Architecture.**
Le tested HEAD était `a9937991…`, avec overlay dirty explicitement haché ; le SHA de base seul
n’est pas présenté comme le code I2 testé. SQL externes, sélecteur, registre, settings et opérateur
sont contrôlés avant/après ; les mêmes octets XML évalués sont archivés avec SHA-256.

Le run canonique et la livraison diffèrent uniquement sur le test instrumenté après son FAIL :
[comparaison des entrées](../testing/evidence/L4-I2/Canonical_To_Delivery_Input_Comparison.json),
1287 entrées identiques sur 1288. Production, SQL, configuration, setup, seuils et assertions
restent identiques ; aucune équivalence applicative totale ni PASS n’est transféré de ce FAIL.
L’opérateur étend seulement l’attribution nominative du second test top-level existant
`CurrentTransactionManagerPostgresTest` à son fichier source, comme pour I1. Le refus initial
du sélecteur standard reste archivé (INCONCLUSIVE, zéro test), sans affaiblir découverte/verdict.

L’intégrité SHA-256 des archives et de tous leurs membres référencés a été vérifiée.
L’index contient **2642 pièces physiques** sur les deux campagnes. Les identifiants sont
`acdf677077654bd58bfc0866a761ead0` (canonique) et
`7a236bcea8744166b5719859b52f1e78` (Architecture).

## Diagnostics conservés et corrections locales

Les [archives de développement](../testing/evidence/L4-I2/development/) gardent les FAIL et
contre-preuves ; elles ne certifient pas les octets finaux. Les principaux diagnostics sont :

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
- `canonical-fail-renewal` : 1873/1/0/0, I2-QUAL-01 non expliqué ; diagnostic ciblé 1/0/0/0 et
  Architecture 2137/0/0/0 ne sont pas transformés en preuve de correction de ce failure.

Feedbacks récents exécutés (filtrés, non canoniques) :

```sh
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am -Dtest=SlotNavigationPostgresTest,V3WorkerPostgresTest,V3WorkerResourcesPostgresTest,I5PhysicalWindowTest,I5TemporalV2Test,I5TemporalBudgetTest,V3IdentityRequestCompositionPostgresTest,V3PotCommandCompositionPostgresTest,V3IdentityPotCommandPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am '-Dtest=InterAuthorityPreparationPostgresTest#p05AllPublicHistoryReadsPoisonCaughtForeignAuthorityAccess' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am '-Dtest=InterAuthorityPreparationPostgresTest,InterAuthorityRecoveryPostgresTest,SubobjectAtomicityPostgresTest,AttemptTransactionsPostgresTest,V3WorkerPostgresTest#i5_w21,V3WorkerResourcesPostgresTest#i5_r08' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-task-consumption-worker,runtime-command-consumption-worker -am -Dtest=SubobjectStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl runtime-command-consumption-worker -am '-Dtest=ConsumptionLifecycleCompositionPostgresTest#renewalsHaveReservedDatabaseAccess' -Dsurefire.failIfNoSpecifiedTests=false test
```

Les compilations/test-compile et autres feedbacks natifs antérieurs restent dans les logs archivés,
sans PASS transféré aux octets livrés. Préflight standard refusé :
`python3 -B scripts/testing/verify.py CHANGE PROJECTION,COMMAND` (zéro test).

## Limites, publication et suite requise

L’absence de participant métier I3–I7 et d’activation I8 limite les preuves à l’infrastructure I2,
aux probes et aux représentations techniques prévues au plan. Le contrat générique Java ne
prétend pas être un type linéaire ; une exception pure absorbée sans interaction gardée reste
inobservable. Aucune certification globale PRJ/READ/SEC/OPS/PERF/production n’est prononcée.

Publication autorisée sur `feat/v3-architecture`, en deux ensembles cohérents code/plan puis
rapports/preuves, sans force. Le worktree initial était propre. Les empreintes et `git diff --check`
sont vérifiés ; le message de livraison rapporte les SHA local/distant directement comparés,
la synchronisation et la propreté après publication. Les documents historiques restent inchangés.

L’étape restante de qualification est d’expliquer I2-QUAL-01 avec la lane manquante observée,
de corriger un défaut local s’il est démontré, puis de rejouer les gates avec les mêmes exigences.
Le replay non reproducteur ne justifie ni assouplissement ni clôture. L’audit indépendant du code
et la décision humaine d’acceptation restent nécessaires.

**IMPLEMENTED — QUALIFICATION INCOMPLETE** : implémentation livrée et Architecture réussie,
mais une campagne canonique obligatoire comporte encore un failure non expliqué. I2 n’est pas CLOSED.
