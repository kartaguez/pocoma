# L2 I5 — exécution Workers

Statut courant : **I5 IMPLEMENTED — LOCAL BOUNDED PASS — INDEPENDENT AUDIT PENDING**. GO humain explicite reçu le 28 octobre 2026 : « OK tu as le go pour implémenter ce plan ». Les interdictions d’implémentation du [plan amendé historique](L2_I5_Implementation_Plan.md) concernaient le mandat documentaire précédent ; elles ne sont pas une décision de clôture I5. Ni ce rapport ni un Maven vert ne valent ACCEPTED L2 ou audit indépendant.

Baseline réelle : `73a588f3b24ca48d16573a5d757b0d0c9e50c93b`, branche `feat/v3-architecture`, distant identique aux reconnaissances initiale et avant gel. Checkout principal avec travaux étrangers préservé ; développement dans le worktree dédié `/private/tmp/pocoma-v3-postaud`. Aucun reset, clean, stash, déplacement ni push forcé.

## Périmètre déclaré avant code

COMMAND primaire, READ JDBC et MONOLITH complémentaires directs. Architecture globale requise par les nouvelles dépendances POLL→engine-consumption et COMMAND→READ ainsi que l’isolation d’activation ; full reactor requis au gel final de ce jalon majeur. Vérification DB CURRENT V3 sur PostgreSQL 17.11 jetable, WRITE/READ distincts. Aucun changement des migrations historiques ; leur replay par les tests amont existants fait partie de la fermeture Maven, pas d’une nouvelle certification historique. Aucun franchissement productif inattendu vers L3–L8, les autres runtimes, les domaines ou le protocole SQL I2/I3/I4.

Les diagnostics ciblés précèdent les gates, conservent leurs échecs et leurs corrections ; ils ne sont pas attribués à un futur SHA gelé. Les résultats définitifs, commandes, empreintes et archives sont collectés par `scripts/testing/verify-v3-l2-i5.py` à partir du [manifeste additionnel](L2_I5_Proof_Manifest.json), sans modifier les manifestes historiques I2/I3/I4.

## Assemblage livré

Quatre classes POLL : superviseur et registre, boucle Discovery, boucle Consumption et renewals. Deux rotations indépendantes C puis p ; navigation brute UUID/U distincte de l’acquisition, quantum1, progression uniquement sur `examinedCount` confirmé. Aucune transaction ou SQL dans POLL. Engines I2/I3/I4 conservent leur autorité sur claims, tickets, transactions et recovery ; une surcharge explicite raccorde les stores/owners Business et Control, sans routeur implicite, nesting ni transfert de transaction Spring entre threads.

Une seule WorkerCapacity ; exécuteur fixe sans file métier, SynchronousQueue/AbortPolicy. Barrière de démarrage ACK puis RUNNING ; L4 début et L5 drainage sur le même moniteur, sans lock local pendant Engine/SQL/executor/callback/join. Descripteurs retirés irréversiblement, retours tardifs sans upsert ; ticket réservé distinct du claim, de l’envelope, de la sortie locale et de la résolution physique. Engine.abandon/execute/settlePending seuls règlent la capacité. Un Future annulé ne prouve jamais un rollback.

Quatre pools Hikari physiques : writeBusiness/readBusiness M_W+2, writeControl/readControl M_W+6, minIdle=max ; WRITE Business JpaTransactionManager avec DS commun à l’EMF, READ Business et Control owners JDBC I2. Préflight RO avant pools/threads, vérification des singleton réels avant activation ; DB health/OSIV/migrations automatiques désactivés. Superviseur et métriques n’empruntent pas de connexion. Deux voies singleton claims et une voie leases par autorité, maintenance séparée. Claim valide renouvelable après perte de lease. Un appel renewal UNKNOWN reste attaché à sa voie/contexte, DRAINING conservateur, aucune reprise locale de l’unité même après résolution constatée seulement par l’observateur SQL.

Activation I5 explicite, profil unique + enabled + DEV + cibles TCP/DB/schéma/rôle/adresse serveur autorisées + flags fixture/probes + catalogue/droits exacts. Ancien profil pilote toujours inerte. Neuf contrats canoniques et sept familles conservés ; seul Command réel et deux UUID techniques distincts sont raccordés. Aucun HTTP métier/Legacy instancié dans cette voie. Autorité Identity fixture et READ probes ne certifient ni L3 ni L4.

## Réserves d’audit et preuves

| Réserve | Invariant et responsabilité | Preuves additionnelles |
|---|---|---|
| R-I5-01 | Registre local monotone ; même incarnation et phase courante requises ; aucune réinscription par résultat renewal, capacité seulement Engine | H1…H4 WRITE/READ, avant/après retrait, overlap settlePending et transaction encore active |
| R-I5-02 | Inventaire exhaustif A, disponibilité physique B et budget complet C distincts sur WRITE/READ ; UNKNOWN non réaffecté | r01…r12, P1…P7 ; saturation Business, maximum Control conjoint et stress distinct, TCP avant/après COMMIT, backend résiduel, S1/S2, GC/jitter |
| R-I5-03 | L4 et L5 atomiques ; refus/rejet/annulation avant L4 → abandon unique ; après L4 → résolution Engine ; callback seulement STOPPED qualifié | D1…D6, w05/w06/w11/w13/w18/w20/w21, arrêt pendant STARTING, child JVM et deadline UNKNOWN |
| R-I5-04 | Prochain bénéficiaire conservé sous saturation, une opportunité synchrone consommée une fois ; aucune équité temporelle absolue sous blocage | w19 M_W1/4, attente réelle PostgreSQL, retour et timeout, reprise C/p et durées |
| R-I5-05 | Conjonction des autorisations et catalogue/droits/beans avant threads ou mutation de coordination | c01…c05 et mono01 ; flags absents, profils interdits, UUID/metadata incohérents, droits/role hérités excessifs, factory/fixture indirectes |

R-AUD-I3-03 reste **ouverte à l’audit indépendant et à la qualification de déploiement**. Sa fermeture ne peut pas être déduite de la taille des pools ni de la seule mise en sécurité DRAINING. Les six dossiers A/B/C sont présents et qualifiés localement, sur WRITE/READ et avec leurs hypothèses mesurées ; ils restent soumis à audit indépendant.

## Limites à conserver

Pas de garantie de renewal pendant une panne PostgreSQL durable, blocage de relation global ou pause JVM non bornée. Budget local : attente connexion1s, verrou500ms, SQL2s, transaction3s et socket5s sur Control ; mesures de service complet et de marge SQL extérieure, hypothèse jitter/GC1s et sécurité2s. Les configurations B1/M_W4 requièrent lease90s, car12 lots leases. Les valeurs pilote ne sont pas une configuration de production certifiée.

UNKNOWN peut conserver une capacité ou une responsabilité L1 Control, même avec capacité métier à zéro, et rendre un drainage incomplet ; aucune restitution par délai ni réexécution sous le même claim. Le script stop externe termine avant le timeout Spring120s et classe une terminaison forcée CRASH. Un rejet d’exécuteur après acquisition peut laisser un claim inutilisé jusqu’à expiration ; effet sur latence admis et testé. Acquisition synchrone peut retarder les autres C ; aucun nouveau scheduler préventif.

Les métriques sont des observations locales bornées, sans UUID/token en labels ; certaines transitions physiques avec ACK perdu sous-comptent les compteurs confirmés. Les marges avant échéance proviennent des snapshots/horloges PostgreSQL de certification, jamais d’une soustraction entre horloges Worker/serveur. Mesures navigation locales (100/1000/10000 slots/p, états0/50/90%, B1/32, index walk vs PK restauré par rollback,5 échauffements/30mesures) et idle5min avant/après ne certifient pas SPEC09 intégré. L3–L8 et les 56 reports normatifs restent alloués, jamais PASS à partir des probes.

La démonstration exécutable et ses commandes figurent dans le [runbook](L2_I5_Worker_Development_Runbook.md). Elle utilise un jar réellement démarré, deux DB neuves, préparation administrative séparée, injection interne, Command réel, READ technique, health/Prometheus, arrêt qualifié et redémarrage ; aucun parcours Bruno V3 pertinent n’est livré.


## Précisions observabilité et procédure

Les noms Micrometer du plan sont raccordés aux phases réelles ; `Outcome` est un enum fermé de11 valeurs dans le code, au lieu du majorant prospectif6/8 de certains tableaux du plan. Les séries effectives restent bornées par11 Consumptions connues,2 autorités,3 voies et les enums fermés ; aucune dimension n’est alimentée par les UUID des slots/claims/Pot/users/requests. Les callbacks de capacité n’utilisent que des compteurs préenregistrés, pour ne pas prendre un verrou de registre sous celui de WorkerCapacity. Les métriques de phase et les opérations en issue UNKNOWN restent distinctes des transitions SQL confirmées.

Deux séries ne disposent volontairement d’aucune observation fabriquée : `renewal.margin` n’échantillonne pas sans horloge DB cohérente renvoyée par le port, et `takeover.observed` n’incrémente pas sur une simple acquisition. Les échéances et takeovers sont prouvés par SQL extérieur ; les gauges locales n’en sont pas une preuve durable. Cela conserve la condition explicite du plan §20, sans extension de port ni query d’exploitation dans la réserve Control.

La CLI reprend le contrat du plan, avec une construction de jar **explicite** avant prepare ; elle refuse un artefact plus ancien que les sources productives. Le psql administratif peut être celui d’un conteneur explicitement désigné, après vérification de sa publication TCP vers la cible configurée ; c’est une alternative au binaire hôte absent lors de la démonstration. Ni un installateur automatique ni un cinquième pool n’est introduit. Le premier essai DEV a refusé l’identité serveur sans suffixe CIDR avant toute mutation ; le nouvel essai utilise la représentation PostgreSQL exacte et passe. Ces ajustements de procédure ne changent pas les protocoles I2/I3/I4.


## Finding physique I5-F01 — préparation READ UNKNOWN (29 octobre)

Reproduction extérieure au banc de certification : après le commit local de préparation en lecture d’issue UNKNOWN, backend PostgreSQL encore `idle in transaction`, `settlePending()` retournait1 et la capacité retombait à0. Cette unité RO ne verrouille pas le slot ; sa présence déverrouillée ne qualifie donc pas sa résolution physique. L’erreur préexistait dans l’inspection I3, sans changement de l’owner I2 ; elle interdit de revendiquer la garantie de capacité I5 en conservant cette restitution.

Correction minimale dans les fichiers déjà concernés : un flag **local et monotone** `unresolvedPreparation` sur le handle Engine est posé pour Failure(UNKNOWN) ou sortie brute non qualifiée de la préparation. Ces handles ne sont pas réglables par inspection de slot. Pas de remise à zéro ni réarmement local, même après résolution extérieure : les ports actuels ne portent aucun témoin physique de cette unité RO. Engine garde le ticket ; le Worker passe en DRAINING sur une tentative de résolution UNKNOWN, ferme les nouvelles admissions/acquisitions et continue les claims valides. Aucune restitution Worker, owner supplémentaire, table, SQL de coordination ou API de recovery. Une préparation portant Failure(COMMITTED) a une résolution physique connue et conserve le règlement existant par inspection ; elle ne constitue toujours pas une finalisation métier.

Les tests unitaires historiques `countableRequiresKnownCauseAndRollback` et `oneShotAndCancellation`, ainsi que `ConsumptionLifecyclePostgresTest.i3_c05_j`, codifiaient la restitution fautive après UNKNOWN de préparation ; leurs assertions sont **renforcées** vers ticket retenu et zéro règlement. Le test d’abandon d’un handle confirmé reste présent dans une méthode séparée. Des tests complémentaires distinguent préparation COMMITTED et sortie brute. Les noms/allocation des preuves I3 restent conservés ; les manifestes et archives historiques ne sont pas réécrits.

Deux variantes PostgreSQL `w13-READ-PREPARATION-WRITE/READ` exécutent le vrai owner et les vrais participants dans une JVM enfant. Elles exigent unité réellement RO, PID/XID encore actifs après retour local, capacité1 et claim présent ; une terminaison SQL ciblée de ce seul backend prouve ensuite le rollback extérieur, sans autoriser Engine à inventer un témoin. Deadline reste DRAINING et callback absent ; la terminaison de la JVM est CRASH. Un nouveau Worker reprend seulement selon les échéances et fences ordinaires, sans double effet. Ceci étend les preuves D5/D6 et ferme le contre-exemple de sûreté ; sa disponibilité reste volontairement dégradée jusqu’au redémarrage.

L’écart au plan est déclaré avant la nouvelle qualification, sans nouveau franchissement de slice : deux fichiers de tests existants supplémentaires dans ENGINE/PRIMARY sont nécessaires à la correction des assertions historiques. Les invariants transactionnels, l’owner I2 et le SQL I3/I4 ne sont pas redessinés. L’architecture validée et les quatre pools sont conservés. Le placement du crash w14 est aussi corrigé après le **DELETE réel** via le callback Completion, avec observateur extérieur ; le test de réserve Control exerce7 emprunteurs artificiels identifiés plus les3 voies S1/S2/lease, soit10/10 connexions réellement louées, séparément du maximum conjoint naturellement atteignable. La première campagne C interrompue reste un diagnostic du gel8e6a3601, jamais une preuve finale.


## Portée exacte des mesures par rapport au plan

`r05` mesure quatre configurations à un Worker : B32/M_W1 WRITE retenu, B32/M_W1 READ retenu, B32/M_W4 mixte et B1/M_W4 mixte. Le complément `r12` ferme l’allocation explicite du plan à **deux Workers simultanément chargés**, dans les quatre combinaisons B1/B32 × M_W1/4. Leurs candidatures sont0|1 et2|3, avec les douze couples collectivement possédés ; `w02` conserve séparément la compétition et le transfert d’ownership sur candidatures chevauchantes. Chaque Worker garde sa propre capacité et ses quatre pools physiques. La calibration ne prétend pas certifier un déploiement au-delà des paramètres/charge/pauses mesurés.

`w16` conserve intégralement les deux fenêtres de cinq minutes et la source isolée entre les deux. La compétition soutenue et asymétrique C/p relève des scénarios de rotation `w03/w19`, pas d’une mesure de performance intégrée. Les maxima et distributions de navigation décrivent les configurations effectivement exécutées ; ils ne valent ni SLO global SPEC-09 ni preuve à 10M événements.


## Diagnostic de fermeture des fixtures historiques

La fermeture du fork PRIMARY de la campagne F émet `Surefire is going to kill self fork JVM ... 30 seconds after System.exit(0)` après 556 méthodes sans échec/erreur. Le dump frais du 29 octobre 07:28:52 montre `SpringApplicationShutdownHook` dans Hibernate `SchemaDropperImpl.DelayedDropAction` en attente d’emprunt Hikari ; aucun thread `v3-*` n’est présent dans ce dump. Maven poursuit les modules suivants. Ce diagnostic de teardown de contextes JPA historiques est conservé dans l’archive de diagnostics de cette exécution écartée avec le log ; il n’est ni un callback STOPPED I5 ni une preuve de drain réussi. Aucune modification hors périmètre des fixtures historiques n’a été faite pour supprimer ce message. Le résultat du gate reste conditionné au code de sortie Maven et à la qualification complète des preuves, pas au seul résumé des 556 méthodes.


## Inventaire applicatif gelé

Les chemins suivants sont ceux du diff entre la baseline et le gel applicatif ; `A` signifie CREATE et `M` MODIFY. Les documents de résultat et archives ajoutés après gel sont indexés séparément dans le dossier de preuves. Aucun fichier supprimé.

```text
M	app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3ConsumptionEngineArchitectureTest.java
M	app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3ConsumptionFoundationArchitectureTest.java
M	app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/command/v3/V3PotCommandComposition.java
A	app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/MicrometerV3ConsumptionObservation.java
M	app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3BootstrapGuard.java
M	app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionDiscoveryComposition.java
M	app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionLifecycleComposition.java
A	app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java
A	app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerSettings.java
A	app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/dev/V3FixturePotActorAuthority.java
A	app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/dev/V3TechnicalReadParticipant.java
M	app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionEngine.java
M	app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/WorkerCapacity.java
A	app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/ConsumptionObservation.java
A	app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/consumption/v3/port/SlotKeyNavigation.java
M	app/engine-consumption/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/ConsumptionFailureClassificationTest.java
M	app/engine-consumption/src/test/java/com/kartaguez/pocoma/engine/consumption/v3/WorkerCapacityTest.java
A	app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/consumption/JdbcSlotKeyNavigation.java
M	app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/ConsumptionLifecyclePostgresTest.java
A	app/infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/SlotNavigationPostgresTest.java
M	app/infra-persistence-read-jdbc/src/test/java/com/kartaguez/pocoma/infra/persistence/read/jdbc/v3/consumption/ReadDiscoveryPrivilegesPostgresTest.java
M	app/orchestrator-poll-consumption/pom.xml
A	app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionLoop.java
A	app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionWorker.java
A	app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3DiscoveryLoop.java
A	app/orchestrator-poll-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3RenewalService.java
A	app/orchestrator-poll-consumption/src/test/java/com/kartaguez/pocoma/orchestrator/poll/consumption/v3/V3ConsumptionWorkerTest.java
M	app/runtime-command-consumption-worker/pom.xml
M	app/runtime-command-consumption-worker/src/main/java/com/kartaguez/pocoma/PocomaCommandConsumptionWorkerApplication.java
A	app/runtime-command-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/command/v3/dev/V3WorkerDevelopmentTools.java
A	app/runtime-command-consumption-worker/src/main/resources/application-v3-consumption-worker-dev.properties
A	app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerCompositionPostgresTest.java
A	app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerMetricsTest.java
A	app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerPostgresTest.java
A	app/runtime-command-consumption-worker/src/test/java/com/kartaguez/pocoma/runtime/command/v3/V3WorkerResourcesPostgresTest.java
M	app/runtime-monolith/src/main/java/com/kartaguez/pocoma/PocomaRuntimeMonolithApplication.java
A	app/runtime-monolith/src/main/resources/application-v3-consumption-worker-dev.properties
A	app/runtime-monolith/src/test/java/com/kartaguez/pocoma/V3WorkerMonolithPostgresTest.java
M	docs/v3/L2_I5_Implementation_Plan.md
A	docs/v3/L2_I5_Proof_Manifest.json
A	scripts/testing/test_i5_evidence.py
A	scripts/testing/verify-v3-l2-i5.py
A	scripts/v3/bootstrap-l2-worker-dev.sql
A	scripts/v3/grant-l2-worker-dev.sql
A	scripts/v3/worker-dev.py
```


## Provenance des campagnes écartées

Une première campagne C au gel `8e6a3601` a été interrompue pour corriger le placement du crash de test et le finding I5-F01. Au gel `ac9f7642`, un lancement M a ensuite été démarré par erreur depuis le checkout déjà utilisé par F, malgré la création d’un checkout distinct. Ce lancement a été arrêté ; les rapports amont ayant pu être remplacés, **cette campagne F entière est écartée**, ainsi que ce lancement M. Les dossiers sont marqués DISCARDED/NOT QUALIFIED et conservés en diagnostics. Les deux gates sont relancées depuis des destinations neuves, M dans `/private/tmp/pocoma-i5-gate-monolith` et F dans `/private/tmp/pocoma-v3-postaud`. Aucune réutilisation de leur verdict ni réduction de campagne. Les sources app/scripts n’ont pas changé pendant cet incident ; les checkouts C/A sont séparés et n’ont pas été affectés. Les dates/empreintes des seuls résultats finaux qualifiés figurent dans l’index.


## Corrections des oracles après les premières gates complètes

Au gel `ac9f7642`, C a effectivement exécuté1628 méthodes puis échoué sur `w08`, `w18` et `r02`. A a échoué sur `w18/r02` et n’a donc pas atteint ses tests d’architecture ; ce n’est pas une preuve globale. M a passé1351 méthodes. F a été interrompue après identification des corrections de test nécessaires, son résultat reste FAIL/incomplet ; les tests non exécutés restent MISSING. Tous ces dossiers restent des diagnostics, sans transfert de verdict au gel final.

Les corrections suivantes sont **exclusivement dans deux fichiers de tests**, sans changement productif depuis I5-F01 :

- `w18` D1 retenait correctement une acquisition Consumption confirmée, mais affirmait à tort qu’aucune Discovery indépendante ne pouvait encore sortir de son L1. L’oracle exige maintenant1…2 opérations engagées, la capacité1/claim présent/source PENDING sans résultat, puis zéro handle/ticket et STOPPED seulement après libération de la barrière. D4 et les autres variantes restent requises.
- `w08` a dépassé son watchdog90s dans C, alors que le même cas a passé dans A et dans un replay ciblé. Aucune cause productive n’est déduite de cette seule non-reproduction. Le scénario porte65 Commands réelles et leurs publications/probes, des baux et des claims abandonnés attendant leur échéance ordinaire ; il ne prouve pas une latence R10 absolue. Le profil de ce cas est rendu explicite M_W1/claim10s/lease20s/renewal1s, conforme au calcul des marges. Son watchdog de vérification est180s ; toutes assertions de Q, softCap64, persistance, source/effets/publications/slots et réactivation restreinte sont conservées. Un échec enregistre maintenant l’état local et les comptes SQL, sans token. M_W4 reste couvert par les scénarios de capacité, de ressources et de lenteur.
- `r02` exigeait de nouvelles admissions READ après la perte volontaire d’ACK qui déclenche désormais DRAINING. Les huit effets READ des publications préparées sont maintenant exigés **avant** ce stimulus ; puis le véritable Command doit être TERMINAL, posséder un seul résultat, deux publications et aucun slot. Le journal doit toujours voir tous les emprunteurs, cession et R9 compris, sans violation ; les maxima conjoints et les trois voies renouvelantes restent exercés sur les deux autorités. Aucun nouveau dispatch READ n’est inféré pendant le drain.

Deux campagnes ciblées des trois scénarios corrigés passent, dont la dernière avec les assertions de publications renforcées. Le gel est refait avant les cinq gates finales : aucune preuve de l’ancienne révision n’est promue parce que seuls des tests ont changé. Ces corrections et leurs limites sont aussi inscrites dans `verification_adjustments` du manifeste.


## Identité du jar de démonstration

Le jar courant, reconstruit après F04/F05 au gel `7296e11c`, porte le SHA256 `75cb7c59f222c74f6173a68ab888397f860fe069bedca72e5203552f81f6a697`. La démonstration réelle passe : deux Commands, probes READ, health/Prometheus, deux arrêts qualifiés et redémarrage conservant le premier résultat. Les empreintes des sources productives/scripts sont conservées. L’[équivalence compilée](../testing/evidence/L2-I5/Development-compiled-equivalence.json) est PASS : les 888 classes internes du jar sont byte-identiques au checkout propre 9a315e16. Les changements de tests T04/T05 ne modifient donc aucun octet productif démontré ; le reçu de développement seul ne constitue pas cette preuve. Les jars antérieurs F01/F02/F03 restent historiques et séparés, sans transfert de verdict. MONOLITH possède sa propre preuve de racine Spring, distincte du jar COMMAND.


## Finding I5-F02 — sélection des leases et lots partiels

La revue contradictoire interne a révélé un défaut local d’ordonnancement : après une opération lente, le premier ensemble du registre peut être de nouveau dû et être choisi avant les autres, indéfiniment. Le reproducer local non modifié retourne `rounds=4 candidates=2 distinctRenewed=1` ; le nouvel oracle `u08` échoue avant correction. La preuve PostgreSQL négative `r10` le confirme sans mock d’owner : B1, lease90s/cadence10s, `pg_sleep(1.5)` **dans la vraie transaction Control**, sous les timeouts SQL2s/transaction3s. WRITE avance ; sur READ, une partition garde son échéance initiale tandis que les sept autres avancent. Après le watchdog75s suivant le premier renouvellement dû, l’oracle all-leases échoue ; PID, xact_start, PgSleep et échéances SQL extérieures sont conservés. Ce n’est pas une panne PostgreSQL durable ni une saturation Business.

Correction minimale dans `V3RenewalService` : choisir l’entrée due dont le `renewedAt` local est le plus ancien, et appliquer cet ordre au lot de cette Binding. Il s’agit d’un choix sur la liste déjà bornée, sans nouvelle queue, état durable, interface ou thread. Un ancien candidat ne peut plus être dépassé à répétition par un candidat récemment servi. Les unités Engine/SQL, les quatre pools et les droits des claims après perte de partition ne changent pas. Ce timestamp volatil ne prouve jamais la propriété ni une échéance DB.

`r05` est renforcé : il exige toutes les échéances des4 leases WRITE et8 READ avancées, en plus des claims, et une marge extérieure DB>2s. Le calcul empirique compte les clés candidates de l’autorité la plus chargée, sans supposer des lots pleins : `N × max(durée complète observée) + polling initial≤1s + jitter/JVM budgété1s + N × minPoll`. Avec la cadence et la sécurité2s, ce budget doit tenir dans la durée du lease ; le service réellement observé et toutes les échéances SQL restent des assertions indépendantes. Le contrôle nominal des paramètres Spring est explicitement une condition initiale nécessaire, pas cette preuve C. Une nouvelle enveloppe de latence exige requalification ; un plafond de timeout isolé n’est pas une durée de service démontrée.

`u08` et `r10` passent après correction ; r10 montre les douze leases renouvelés avant les anciennes échéances avec marge>2s, sous le même SQL lent. Les campagnes22362595 sont interrompues et conservées comme diagnostics pour refaire un gel commun avec ces deux nouveaux oracles et la calibration renforcée. Les échecs de compilation d’un premier helper de test sont distingués du vrai contre-exemple PostgreSQL négatif. Aucune preuve de l’ancien gel ne devient une preuve du nouveau ; R-AUD-I3-03 reste ouverte à l’audit indépendant.

## Commandes et environnement

Les cinq gates finales utilisent exactement les commandes sans filtre du plan, depuis `app/` ; leurs codes de sortie, horaires, décomptes et XML figurent dans le reçu de preuves.

```sh
cd app
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-persistence-read-jdbc -am test
./mvnw -pl runtime-monolith -am test
./mvnw -pl architecture-tests -am test
./mvnw test
```
 Les commandes ciblées ci-dessous sont des diagnostics d’itération, **pas** des remplacements des gates :

```sh
# Depuis app/ : reproduction w08/w18.
./mvnw -pl runtime-command-consumption-worker -am \
  -Dtest='V3WorkerPostgresTest#i5_w08+i5_w18' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Depuis la racine : oracles de drain/quarantaine/inventaire.
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am \
  -Dtest='V3WorkerPostgresTest#i5_w08+i5_w18,V3WorkerResourcesPostgresTest#i5_r02' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Contre-exemples F02 avant correction : échecs attendus et conservés.
./app/mvnw -f app/pom.xml -pl orchestrator-poll-consumption -am \
  -Dtest='V3ConsumptionWorkerTest#i5_u08' -Dsurefire.failIfNoSpecifiedTests=false test
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am \
  -Dtest='V3WorkerResourcesPostgresTest#i5_r10' -Dsurefire.failIfNoSpecifiedTests=false test

# Qualification ciblée après correction, puis calibration renforcée.
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am \
  -Dtest='V3ConsumptionWorkerTest,V3WorkerResourcesPostgresTest#i5_r05+i5_r10' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Qualification ciblée F03 : deux autorités, deux boucles et ressources complètes.
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am \
  -Dtest='V3ConsumptionWorkerTest,V3WorkerPostgresTest#i5_w20,V3WorkerResourcesPostgresTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Régressions F04 avant gel : I3 physique, races de drain et ressources.
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am \
  -Dtest='ConsumptionFailureClassificationTest,V3ConsumptionWorkerTest,ConsumptionLifecyclePostgresTest,V3WorkerResourcesPostgresTest,V3WorkerPostgresTest#i5_w11+i5_w13+i5_w17+i5_w18+i5_w20+i5_w21' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# F05 et service complet, après négatifs u10/r11 conservés.
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am \
  -Dtest='V3ConsumptionWorkerTest,V3WorkerResourcesPostgresTest#i5_r05+i5_r10+i5_r11' \
  -Dsurefire.failIfNoSpecifiedTests=false test
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am \
  -Dtest='ConsumptionFailureClassificationTest,V3ConsumptionWorkerTest,V3WorkerResourcesPostgresTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Complément prévu à deux Workers chargés, quatre variantes physiques.
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am \
  -Dtest='V3WorkerResourcesPostgresTest#i5_r12' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Construction explicite du jar démontré ; ne constitue pas une preuve de tests.
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am package -DskipTests

PYTHONDONTWRITEBYTECODE=1 python3 scripts/testing/test_i5_evidence.py
PYTHONDONTWRITEBYTECODE=1 python3 docs/v3/check_l2_manifest.py
git diff --check
```

Les diagnostics I2/I3/I4, wire, composition, métriques et crashes ont aussi été exécutés pendant l’assemblage ; leurs résultats intermédiaires ne sont pas attribués au gel final. Le dossier conserve notamment les échecs de collecte/compilation/interleaving et les corrections utiles à l’audit. Les reproductions extérieures F01/F02/F03 utilisent `javac/java` avec les fixtures du dépôt ; seule leur contribution expressément PostgreSQL prouve les propriétés physiques.

Environnement constaté : Maven wrapper3.9.14, Java21 Eclipse Adoptium, Python3.14.7, Git2.54.0, macOS27/aarch64 ; hôte10 cœurs logiques/16GiB, VM Docker environ8GiB. Les mesures sont locales à cet environnement et à ses hypothèses de charge. Les gates peuvent être simultanées dans des checkouts distincts ; un répertoire Maven n’est jamais partagé entre deux exécutions qualifiées actives. Les durées ne sont pas des SLO de production.

## Avancement des étapes du plan

| Étape | Travail livré / preuve requise | État de livraison |
|---|---|---|
| I5-0 | Reconnaissance, normes, périmètres, paramètres et allocations | Réalisé sous GO explicite ; baseline réelle conservée |
| I5-1 | Navigation R10 / n01…n08 | Implémentée et qualifiée dans les gates finales |
| I5-2 | Owners/stores Business/Control explicites / r08 | Implémentés, owner I2 et SQL réutilisés |
| I5-3 | Superviseur, boucles, capacité, registre, barrière / u,w,H,D | Implémentés ; oracles T01 corrigés sans changer les invariants |
| I5-4 | Pools, renewals, maintenance / A,B,C,P,F02/F05 | Implémentés ; tous leases ajoutés à C ; R-AUD-I3-03 reste ouverte à audit |
| I5-5 | Drain/restart/crash/takeover | Implémentés ; UNKNOWN physique conserve ses ressources selon F01/F03/F04 |
| I5-6 | Activation DEV, Command réel/probes, métriques, outils/runbook | Jar reconstruit et démontré après F04/F05 ; pas d’HTTP métier/Bruno V3 |
| I5-7 | Gel9a315e16, cinq gates, archives, empreintes | PASS local : cinq gates qualifiées au même gel, archives et empreintes concordantes |
| I5-8 | Audit indépendant et décision de clôture | À FAIRE PAR PASSAGE INDÉPENDANT ; aucun audit de l’auteur substitué |


## Finding I5-F03 — arrêt annoncé pendant une opération Control encore physique

Le reproducer extérieur contre `3efcb8cc` utilise le vrai Worker, PostgreSQL et l’owner livré : après perte de résolution locale d’un commit Control de lease, `state=RUNNING capacity=0`, puis `operations=0` alors que le PID reste en transaction ; `stopped=true activeAtPoolClose=true`. Ce contre-exemple invalide l’arrêt qualifié de cette révision. Les quatre gates vertes précédentes restent historiques ; elles ne couvrent pas ce cas. C y avait également échoué sur l’oracle r05. Aucun de ces résultats ne sera transféré au nouveau gel.

Le correctif conserve le compteur L1 existant sur UNKNOWN ou sortie brute non qualifiée d’une opportunité Discovery/Consumption et passe en DRAINING. Seuls retour normal ou Failure physiquement qualifiée COMMITTED/ROLLED_BACK décrémentent cette responsabilité. Ni nouveau système de tickets, ni owner, pool, état durable ou protocole SQL. Le Worker n’utilise pas le témoin SQL extérieur des tests pour inventer un recovery : même après rollback observé par le parent, l’instance garde L1, pools ouverts et callback absent jusqu’à sa terminaison CRASH. Les tentatives déjà démarrées et leurs claims conservent les règles Engine.

`u09` couvre les sorties UNKNOWN/brutes des deux boucles et le rollback connu ; `w20` démarre un processus enfant réel WRITE puis READ, capture PID/XID/locks, constate le backend actif après retour local, force sa résolution extérieure et vérifie encore L1=1/capacité0/close0/STOPPED0 après la deadline. La terminaison du parent est CRASH, puis un successeur normal produit un seul résultat Command et les deux effets techniques READ. Ces deux méthodes et variantes sont ajoutées au manifeste, sans retirer aucune obligation héritée.

Les oracles de ressources sont précisés : r01 prouve d’abord les renewals WRITE/READ sous MW4 RUNNING, ferme les nouvelles opportunités, puis épuise artificiellement les deux Business pendant que les claims démarrés continuent de se renouveler en DRAINING. Les effets réels Command/publications et trois traitements READ retenus doivent finir ; aucun nouvel emprunt source pendant DRAINING n’est demandé. r05 capture les seuls IDs effectivement démarrés et retenus, exige leur renewal et celui des douze leases ; les claims inutilisés abandonnés ne doivent pas être renouvelés. Le premier oracle considérait à tort tous les claims SQL comme actifs. Les snapshots de diagnostic restent conservés et les droits initiaux de lease exigent une échéance PostgreSQL future.

Qualification ciblée complète après correction : neuf unitaires POLL, w20 (WRITE/READ) et les dix méthodes de ressources, zéro échec/erreur/skip ; commande et log `pocoma-i5-F03-complete-resources.log` conservés. Cette réussite ciblée ne remplace pas les cinq gates finales non filtrées au nouveau gel ni leur collecte indépendante.


## Contrôles documentaires et d’allocation

Contrôles effectivement exécutés après le gel final `9a315e16` : `git diff --check` ; collecteur I5 (trois tests unitaires Python PASS) ; `check_l2_manifest.py` (253 IDs normatifs,380 lignes de preuve,324 obligations L2,56 reports,33 oracles distincts,783 références, aucun oracle orphelin,858 liens relatifs et63 ancres). Vérification directe des66 signatures de méthodes obligatoires et de leurs chemins Java,75 identifiants de variantes physiques uniques, trois manifestes hérités byte-identiques à la baseline,30 sections numérotées du plan conservées et profils DEV des deux racines identiques. Le code et les scripts restent propres au gel. Ces résultats sont mécaniques ; ils ne promeuvent aucune exigence métier différée et ne remplacent pas la revue contradictoire sémantique.


## Finding I5-F04 — incertitude de l’inspection de recovery elle-même

Le contre-test contre `c8b0815b` exécute un vrai Command, perd son ACK après le commit effectif puis maintient ouverte la transaction d’inspection Control suivante. Le premier essai sans réveil supplémentaire n’expose pas la course : le timeout PostgreSQL3s termine le backend avant le tour de maintenance suivant. Avec des demandes `requestDrain()` répétées, parfaitement autorisées, une inspection ultérieure restitue le ticket et l’ancien Worker ferme les pools alors que le PID/XID de la première inspection est encore actif : `stopped=true activeAtPoolClose=true`. Aucune durée DB ni politique SQL n’a été modifiée. Ce contre-exemple est conservé ; l’essai initial et le mauvais cwd du helper sont des diagnostics distincts.

Correction dans Engine/POLL existants : un flag local monotone `unresolvedRecovery` distingue l’issue physique incertaine d’une inspection ou observation RO. `hasUnresolvedRecovery()` est une vue RO supplémentaire nécessaire car le compteur retourné par `settlePending()` ne transporte pas cette information. L’Engine exclut les inspections/observations suivantes et conserve le ticket d’un handle lié à une unité RO incertaine, y compris si la résolution métier antérieure était connue. La voie unique de maintenance n’est pas réutilisée ; le superviseur passe en DRAINING et refuse STOPPED. Pas de nouveau transaction manager, owner, pool, état durable, scheduler ou système de tickets. Un rollback/commit physiquement qualifié reste distingué d’UNKNOWN ; les claims valides restent renouvelables indépendamment des leases et de cette voie de maintenance. Aucun clear local, même après rollback observé extérieurement ; terminaison CRASH puis reprise normale uniquement.

`w21` couvre quatre processus enfants : inspection CONTROL et observation READ, chacune sur WRITE puis READ. Parent/child vérifient le vrai effet déjà committé, slot absent, PID/XID/verrous actifs après retour local, demandes de drain répétées, ticket1/callback0/close0, puis résolution SQL extérieure et deadline sans règlement ni réarmement. Le successeur conserve un seul résultat Command et exactement deux effets techniques READ. L’unitaire Engine distingue UNKNOWN/brut et rollback confirmé, et compte les appels pour interdire toute réutilisation de la voie. Première qualification ciblée : neuf méthodes Engine, neuf POLL et w20/w21, zéro échec/erreur/skip ; régressions physiques élargies avant le nouveau gel.

Au même gelc8b0815b, R et M avaient passé ; F a échoué sur l’oracle unitaire `u01`, C/A ont été interrompues après identification F04 et restent incomplètes. `u01` observait le compteur d’effet avant la fin du wrapper puis demandait un dispatch unique au thread encore occupé : le rejet AbortPolicy était correct et le scénario ne réessayait pas. L’oracle attend désormais capacité0, activeCount0 **et** thread réellement en attente sur SynchronousQueue avant chaque assertion de dispatch unique. Aucune politique productive n’est changée pour ce test. Ce témoin local ne qualifie aucune transaction PostgreSQL. Tous résultats du gelc8b0815b sont archivés comme superseded, sans transfert de PASS.


## Finding I5-F05 — lease valide orpheliné après omission qualifiée

La campagne élargie F04 passe les45 méthodes I3 et les six scénarios Worker ciblés, mais r05 échoue encore dans M_W1/READ : le claim retenu et onze leases avancent, une partition READ conserve son ancienne échéance malgré un Worker RUNNING. Les nouveaux snapshots établissent que ce défaut n’est pas seulement le problème d’oracle des claims inutilisés décrit en T02. Un retour normal SKIP LOCKED de renewal pouvait retirer immédiatement l’entrée locale alors que son lease restait valide ; acquisition refusée jusqu’à échéance puis reprise.

Le négatif déterministe `r11` sur3599e770 reproduit le problème avec une connexion PostgreSQL distincte verrouillant la ligne de lease. Le vrai renewal omet la cible sans attente, PID/verrous/`pg_blocking_pids={}` sont enregistrés ; le bloqueur commite, mais la cible n’est pas renouvelée dans les5s de vérification avant son échéance20s. L’unitaire u10 échoue lui aussi avant correction. Les deux premières compilations du helper, distinctes du vrai négatif PostgreSQL, restent des diagnostics.

Correction limitée à POLL : conserver l’entrée existante inutilisable et proposer des revalidations normales pendant une fenêtre locale bornée par renewalBudget après le premier retour qualifié. Chaque nouvel appel commence après la résolution physique connue du précédent, minPoll borne la cadence et le vieillissement conserve l’accès des autres leases ; aucun UNKNOWN n’est réarmé. Un CAS positif sur la même incarnation encore inscrite rétablit son usage ; l’épuisement du budget retire seulement l’entrée locale. Aucun owner SQL, pool, ticket, scheduler ou état durable supplémentaire. Les timestamps `retryDue/retryUntil` servent respectivement à conserver le retard/service complet de l’opportunité initiale et à borner les nouvelles occasions, sans inférer l’achèvement d’une unité en cours ni la propriété DB. Une contention persistante peut encore conduire à attendre l’expiration et réacquérir normalement.

Première campagne après correctif : dix unitaires POLL et r05/r10/r11 PASS, zéro échec/erreur/skip ; les deux autorités r11 conservent le même token et renouvellent avant l’ancienne échéance avec marge2s. Aucun watchdog r05 n’est allongé, aucune assertion d’échéance n’est supprimée. La version finale conserve aussi le temps avant première omission dans les mesures de service, et instrumente la suspension locale hors moniteur ; ressources complètes vérifiées avant le nouveau gel.

Qualification F05 complète avant gel : neuf unitaires Engine, dix POLL et les onze méthodes de ressources PASS. Le dernier ciblage u/r05/r10/r11 passe également après ajout de l’assertion de service complet : attente PostgreSQL contrôlée100ms sur le premier appel, puis seconde durée conservant la même due ; le budget C prend le maximum entre coût conservatif des candidats et service complet effectivement mesuré avec marge JVM, confronté au TTL fixé avant test et aux anciennes échéances DB. Gel applicatif intermédiaire `7296e11caaca33c58392880091aabd491a1ae188` ; ses campagnes ont ensuite été remplacées par le gel final T04/T05, sans transfert de résultat.


## Complément I5-T04 — deux Workers chargés

La revue finale du plan a identifié une allocation encore manquante : w02 prouvait la concurrence et r05 le budget d’un Worker, sans couvrir la charge simultanée explicitement demandée au §22. `r12` complète cette preuve avec deux vrais assemblages, quatre configurations B1/B32 × M_W1/4. Les préparations sont retenues dans les vrais contextes RO ; PID/XID extérieurs, capacités distinctes, M_C/M_W,40/64 connexions physiques des rôles et quatre pools par Worker sont vérifiés. PostgreSQL annonce100 connexions et16 sont réservées hors maxima Worker.

Les douze échéances de leases et les2×M_W claims retenus avancent sur WRITE/READ avant leurs anciennes échéances, marge2s. Le calcul inclut coût des candidats, durée complète depuis due initiale, polling, cleanup et pause JVM mesurée avec borne1s, contre les durées préalablement déclarées (lease30s B32/90s B1, claim60s/cadence10s). L’arrêt des deux instances est qualifié : zéro connexion des rôles après clôture, effets Command réels et READ techniques exactement une fois, absence de purge des slots incomplets. La calibration répartit les candidatures pour assurer la charge de chacun ; la concurrence sur mêmes partitions reste dans w02.

Le ciblage r12 passe les quatre variantes, sans échec/erreur/skip. Changement exclusivement de test depuis7296e11c : aucune source productive, owner, pool, POM, script ou profil n’est modifié. Les campagnes7296e11c sont interrompues et conservées comme incomplètes, puis les cinq gates sont refaites au nouveau gel commun avec r12 obligatoire. Le jar démontré reste valable seulement sous contrôle des empreintes productives et de l’équivalence compilée complète avec ce nouveau gel ; aucune identité de SHA de tests n’est supposée.

Gel commun de qualification corrigé : `9a315e16567042d0b9bd017486d9043216b3ecd7`. Ce gel cumule les compléments de tests T04/T05 et leurs allocations/documentation. Les sources productives, scripts et POM restent byte-identiques à7296e11c, SHA de construction du jar démontré. L’équivalence des classes avec le checkout propre au gel final est vérifiée et archivée ; elle permet de raccorder la démonstration au code qualifié sans attribuer au SHA de construction les nouveaux tests T04/T05.


## Correction d’oracle I5-T05 — faute attachée au traitement visé

Au gele257c381, C1637, R378, M1355 et A1905 sont PASS ; F échoue après1747 tests sur w13, avant le module architecture (ses méthodes y restent MISSING). Les douze méthodes de ressources, y compris r12 et ses quatre variantes, passent dans les campagnes qui les ont exécutées. Le défaut est dans le banc de faute : les deux probes READ partagent la même RetainingSource ; après armement dans le participant POT visé, le commit d’un autre thread Completion pouvait prendre la faute. L’effet POT devenait alors normalement visible, tandis que la capacité globale retenue appartenait au sibling. L’assertion existante «effet cible0» a correctement détecté cette course.

Correction uniquement du helper de test : référence de l’objet Thread de la tentative ciblée, predicate exact lors du commit, sélection de son envelope/AcquiredClaim, attente de sa sortie locale sous le moniteur et `engine.retainsCapacity(targetHandle)` hors moniteur. Aucune transaction/callback/SQL sous ce lock ; aucune relaxation des assertions d’effets, slot, expiry, deadline ou callback. Le ciblage w11/w13/w17 WRITE/READ passe après correction. Aucun fichier productif, POM, script ou profil n’est modifié. Les cinq gates sont refaites sur un nouveau gel de tests commun ; toutes celles d’e257c381 restent historiques, sans transfert de verdict.


## Résultats finaux au gel 9a315e16

Les cinq commandes canoniques sans filtre sont PASS au même gel applicatif `9a315e16567042d0b9bd017486d9043216b3ecd7`. Le [reçu des gates](../testing/evidence/L2-I5/Gate-index.json) donne commandes, empreintes, horaires, XML et archives. Le [manifeste figé](../testing/evidence/L2-I5/Qualification-manifest.json) conserve exactement les allocations lors de l’exécution ; les statuts du manifeste courant sont uniquement la restitution des résultats.

| Gate | Tests JUnit collectés | Méthodes obligatoires avec héritages | Durée totale secondes | Résultat |
|---|---:|---:|---:|---|
| C | 1637 | 396 | 3813.044 | PASS |
| R | 378 | 17 | 41.796 | PASS |
| M | 1355 | 334 | 1991.647 | PASS |
| A | 1905 | 420 | 3933.894 | PASS |
| F | 1905 | 420 | 3933.802 | PASS |

Les nombres par gate recouvrent en partie les mêmes régressions : ne pas les additionner comme des preuves distinctes. Aucun skip obligatoire I5/héritage ; l’éventuel exploratoire historique non normatif reste identifié dans les reçus. 66 méthodes I5 et 75 allocations physiques requises, dont quatre variantes à deux Workers chargés. Les cinq problèmes F01…F05 sont corrigés et prouvés localement ; les essais négatifs, interrompus et écartés restent dans le [dossier de diagnostics](../testing/evidence/L2-I5/diagnostics/Findings-index.json).

COMMAND primaire, READ JDBC et MONOLITH complémentaires ; aucune nouvelle frontière productive non déclarée. Le gate architecture global et le full reactor ont été exécutés parce que ce jalon modifie le graphe/composition et constitue une intégration majeure. Ils n’ont pas remplacé les diagnostics ciblés ni les oracles PostgreSQL. Base CURRENT V3 ; les migrations historiques rejouées par la fermeture Maven restent distinguées d’une nouvelle certification historique.

Les dossiers [WRITE-A](../testing/evidence/L2-I5/resources/WRITE-A.json), [WRITE-B](../testing/evidence/L2-I5/resources/WRITE-B.json), [WRITE-C](../testing/evidence/L2-I5/resources/WRITE-C.json), [READ-A](../testing/evidence/L2-I5/resources/READ-A.json), [READ-B](../testing/evidence/L2-I5/resources/READ-B.json) et [READ-C](../testing/evidence/L2-I5/resources/READ-C.json) référencent des fichiers bruts vérifiés dans l’archive F. C couvre un et deux Workers chargés, toutes C/p collectives, B1/B32 et M_W1/4 ; w02 conserve la compétition sur candidatures chevauchantes. Les pauses/latences, effectifs et tailles de pools sont ceux du banc déclaré. Au-delà de cette enveloppe, une requalification reste nécessaire ; aucune promesse pendant panne durable, blocage global de relation ou pause JVM non bornée.

L’[inventaire exact](../testing/evidence/L2-I5/Changed-files.json) distingue CREATE/MODIFY et les catégories de fichiers. Les propriétaires I2, stores/protocoles SQL I3/I4 et manifestes hérités sont réutilisés ; les protections Engine supplémentaires sont explicitement décrites dans F01/F04. Aucun fichier de domaine, migration historique ou travail du checkout principal n’a été modifié. Le [contrôle d’intégrité](../testing/evidence/L2-I5/Integrity-check.json) vérifie les artefacts, les sources et la compilation, y compris les modules sans XML JUnit. Les observations brutes sont conservées sans normalisation ; certaines sont compressées avec empreinte décompressée.

Livraison d’implémentation pour audit indépendant, pas clôture globale du programme : **R-AUD-I3-03 reste OPEN, I5 audit indépendant PENDING, L2 global INCONCLUSIVE**. Identity réelle, projections L4, HTTP/Bruno métier, reconstruction/performance intégrée et distribution finale restent différés. Aucun désaccord normatif ni arbitrage humain ordinaire bloquant ; les inconnues physiques conservées peuvent imposer CRASH/reprise au lieu de STOPPED. La branche de publication est exclusivement feat/v3-architecture, par push normal après revérification distante ; le SHA final de publication et la synchronisation sont retournés dans le rapport de livraison de la conversation.
