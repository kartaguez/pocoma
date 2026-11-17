# L4/I3–I7 — rapport d’exécution consolidée du 3 novembre 2026

**Implémentation terminée. Qualification locale filtrée PASS, avec requalifications diagnostiquées.** Les six participants restent inactifs dans les compositions productives. Ni clôture canonique I3–I7, ni certification SPEC-10, ni acceptation globale L4.

Branche `feat/v3-architecture`, SHA de départ `3b7a3115723a3848a84e7c7b88031ecd6306b187`. SHA applicatif livré : commit portant ce rapport (`git log -1 --format=%H -- docs/v3/L4_I3_I7_Execution_Report.md`). La conversation de livraison donne le SHA final et l’état distant constatés après publication. Aucun ancien résultat n’est réécrit.

La dernière précision humaine conserve **I2 CLOSED — LOCAL BOUNDED PASS / I2-QUAL-01 RESOLVED**. I1 demeure **CLOSED — LOCAL BOUNDED PASS WITH EXPLICIT RESERVATIONS**. La dérogation devenue inutile à la précondition I2 ne rouvre pas I2. [Décision](L4_I3_I7_Consolidated_Execution.md).

## Périmètre livré

| Participant | Autorité préparée | Effets de sa transaction READ L2 |
|---|---|---|
| MaterializePotVersion | POT_EVENT publié, métadonnées WRITE@V et seuls contenus historiques manquants | SubobjectVersion + PotVersion + CurrentPot atomiques |
| MaterializePotBalance | POT_EVENT publié et finances complètes WRITE@V | PotBalance(P,V) rationnelle exacte ; aucune ligne à D |
| MaterializePotVisibility | POT_EVENT publié, créateur/rattachements actifs WRITE@V | Garde par Pot, visibles et tombstones ; navigation keyset |
| MaterializeCurrentBinding | IDENTITY_EVENT EUI publié, EUI@V exacte ; User filtré avant enrôlement | CurrentBinding EUI monotone, actif/inactif et token protégé |
| MaterializeCommandResult | COMMAND_COMPLETION_EVENT minimal + demande/terminal WRITE | Résultat immuable, requesterEui original, CHANGED/UNCHANGED→COMPLETED, REJECTED avec motif |
| MaterializeIdentityRequestResult | IDENTITY_COMPLETION_EVENT minimal + demande/terminal WRITE et cible EUI historique | Résultat immuable Registration/Link/Unlink, requester distinct de cible, aucun UNCHANGED artificiel |

Chaque entrée de la fabrique inerte `V3ReadParticipants` utilise l’identifiant canonique distinct, sa Consumption, ses slots/checkpoints et sa progression ; aucun bean/worker productif ne l’active. Aucune dépendance entre projections ni calcul métier partagé. Les helpers communs portent uniquement SQL, codecs existants, ownership/guard et L2. Les producteurs WRITE, Discovery, leases, fencing et moteur L2 n’ont pas changé.

**Frontière déclarée : PROJECTION principale, BINDING secondaire ; COMMAND utilisé pour les producteurs de fixture.** Deux dépendances fonctionnelles ajoutées à `infra-persistence-read-jdbc` vers les contrats `engine-read-command-result` et `engine-read-registration-result`. Architecture : REQUIRED mais NOT RUN, report humain borné à I8. Full reactor : NOT RUN, report au jalon I8. Aucune nouvelle traversée non couverte vers une autorité ou un runtime. Le port/store I1 reçoit uniquement une lecture typée des preuves corrompues, qualifiée fraîchement par conformité I3 ; aucune modification de son protocole d’écriture ni de l’owner transactionnel.

Migrations READ additives **V3 à V7** dans `db/v3-read-business/migration` : PotVersion/CurrentPot, PotBalance, Visibility, CurrentBinding, deux résultats terminaux. V2/I1 reste inchangée. Cibles admin/database/schema vérifiées, permissions runtime bornées, triggers d’immuabilité et formes SQL testés. Le helper I1 historique est explicitement borné à V2 pour préserver son périmètre. Aucun changement du test renewal I2. [Inventaire technique, statuts et empreintes](L4_I3_I7_Delivery_Files.json).

## Qualification et commandes

Le texte nominatif antérieur des 32 méthodes n’était pas disponible dans la conversation ni les fichiers suivis. Le [manifeste effectivement implémenté](../testing/evidence/L4-I3-I7/Manifest.md) conserve les comptes acceptés, les sous-scénarios, stimuli et oracles, avec **32 méthodes / 37 invocations**, et enregistre leurs noms exacts. Aucun ancien nom n’est inventé comme citation du plan absent. Le [registre](../../scripts/testing/verification-campaigns.json) est exécutable ; [les commandes Maven exactes, classes, méthodes et fermetures](../testing/evidence/L4-I3-I7/Executable_Manifest.json) sont archivées. Les prévisions étaient UNKNOWN ; 60/300/900 s étaient des enveloppes indicatives, pas des mesures ni plafonds.

Commande opérateur depuis la racine : `python3 -B scripts/testing/verify.py <niveau> READ-<campagne> --output /chemin/absolu/nouveau`. L’opérateur exécute dans `app/` le Maven indiqué dans le manifeste, avec un invocationId et un répertoire Surefire neufs. Les argv réellement exécutés sont dans chaque reçu. La séquence explicite `python3 -B scripts/testing/verify.py --sequence read` est disponible, s’arrête au premier non-PASS et ne relance rien ; elle n’a pas été exécutée en supplément.

| Campagne | Fermeture (modules enfants) | Tests/F/E/skip | Maven réel (s) | Total campagne (s) | Enveloppe (s) | Écart (s) | Reçu retenu |
|---|---:|---|---:|---:|---:|---:|---|
| FAST-1 | 18 | 2/0/0/0 | 2.097 | 4.306 | 60 | -55.694 | [final-fast-1](../testing/evidence/L4-I3-I7/runs/final-fast-1/result.json) |
| FAST-3 | 11 | 1/0/0/0 | 2.397 | 4.654 | 60 | -55.346 | [fast3-01](../testing/evidence/L4-I3-I7/runs/fast3-01/result.json) |
| FAST-4 | 18 | 3/0/0/0 | 2.695 | 5.394 | 60 | -54.606 | [fast4-01](../testing/evidence/L4-I3-I7/runs/fast4-01/result.json) |
| CHANGE-1 | 36 | 4/0/0/0 | 9.057 | 11.743 | 300 | -288.257 | [final-change-1](../testing/evidence/L4-I3-I7/runs/final-change-1/result.json) |
| CHANGE-2 | 36 | 4/0/0/0 | 7.854 | 10.575 | 300 | -289.425 | [final-change-2](../testing/evidence/L4-I3-I7/runs/final-change-2/result.json) |
| CHANGE-3 | 36 | 5/0/0/0 | 7.850 | 10.466 | 300 | -289.534 | [final-change-3](../testing/evidence/L4-I3-I7/runs/final-change-3/result.json) |
| CHANGE-4 | 36 | 4/0/0/0 | 7.580 | 10.168 | 300 | -289.832 | [final-change-4](../testing/evidence/L4-I3-I7/runs/final-change-4/result.json) |
| CHANGE-5 | 36 | 4/0/0/0 | 9.393 | 12.105 | 300 | -287.895 | [final-change-5](../testing/evidence/L4-I3-I7/runs/final-change-5/result.json) |
| INTEGRATION-1 | 50 | 10/0/0/0 | 59.854 | 63.043 | 900 | -836.957 | [integration-01](../testing/evidence/L4-I3-I7/runs/integration-01/result.json) |

Le POM racine s’ajoute aux modules enfants comptés. FAST-1 : ancrages domain-projection/infra-persistence-read-jdbc, fermeture 18 ; FAST-3 : engine-consume-projection-task, 11 ; FAST-4 : trois modules propriétaires, 18. Les cinq CHANGE sélectionnent les propriétaires `infra-persistence-primary-jpa,infra-persistence-read-jdbc`, fermeture **36**, sans runtime systématique. INTEGRATION utilise runtime-task-consumption-worker, fermeture50, nécessaire à la composition L2 réelle. Aucun install/clean ni -amd. Les sources historiques coûteuses non sélectionnées sont nominativement conservées dans chaque plan ; les tests adjacents ne sont pas implicitement exécutés.

INTEGRATION-1 a été exécutée **une seule fois au complet : 10/0/0/0 en 63,043 s campagne**. Après l’audit, la correction de classification des preuves corrompues a entraîné un [rejeu de sept invocations](../testing/evidence/L4-I3-I7/runs/final-integration-recovery/result.json) : 6 conformités + UNKNOWN, PASS, **40,780 s**. L’assertion supplémentaire sur le filtre Discovery User inchangé a entraîné un [rejeu des six conformités](../testing/evidence/L4-I3-I7/runs/final-integration-filter/result.json), PASS, **39,467 s**. Les trois méthodes indépendance/résultats/capacité ne sont pas rejouées : leurs corps et comportements pertinents n’ont pas changé ; la correction n’affecte que la lecture de recovery. Ce sont des preuves composées attribuées, pas une prétendue seconde exécution intégrale de dix tests au code final.

FAST-3 et les valeurs pures FAST-4 ont été exécutées avant certains ajouts d’intégration. Leurs entrées pertinentes sont comparées byte à byte aux sources finales. Les stores CurrentBinding/Results ont été rejoués à leur état final après ajout de la fermeture de publication. Les historiques initiaux et les fichiers ajoutés/modifiés entre runs restent explicites dans [Input_Comparisons.json](../testing/evidence/L4-I3-I7/Input_Comparisons.json).

## Mesures, corrections et limites

La somme de la **première campagne complète réussie de chaque groupe** est **148,391 s (2 min 28,391 s)**, dont **123,377 s Maven**. Il s’agit de neuf exécutions distinctes pendant le développement, pas d’une mesure d’un unique lancement séquentiel. La somme des reçus retenus du tableau est **132,453 s**, hors les deux rejeux d’intégration. Ne pas présenter ce dernier total comme le coût complet payé.

Les 25 runs instrumentés représentent **343,379 s (5 min 43,379 s)**. En plus des neuf premiers PASS (148,391 s), les échecs/diagnostics et requalifications consomment **194,988 s**, comptés séparément. Tous sont dans le [manifeste de preuves](L4_I3_I7_Proof_Manifest.json). Les six commandes Maven natives de développement (cinq test-compile, une sélection CurrentBinding unitaire) sont archivées séparément : durées murales externes UNKNOWN ; les Total time Maven sont conservés, jamais additionnés à une durée murale comme mesure équivalente. Le temps d’édition, diagnostic, audit et publication n’est pas mesuré comme coût de campagne.

25 campagnes instrumentées × 3 subprocessus Maven (effective-pom, version, test) = **75 processus Maven** documentés, plus six appels natifs = **81 appels documentés**. La passe nominale de neuf campagnes contient neuf Maven test et 18 appels de découverte/version. Une compilation native n’est ni une gate ni un test PASS.

| Diagnostic | Correction / attribution | Requalification |
|---|---|---|
| FAST-1, sorties ECJ/éditeur avec signatures non résolues dans le target de travail ; codec strict long/int également normalisé | Qualification déplacée vers clone isolé sans target hérité, copies de sources hachées ; aucun artefact d’un autre SHA revendiqué | FAST-1 isolée puis finale PASS |
| Premier CHANGE-1 sans accès Docker dans le sandbox | Relance explicite avec accès Docker autorisé, erreur initiale conservée | CHANGE-1 |
| Fixture READ avec nested transactions laissées actives | Même réglage strict que l’owner V3, sans modifier l’owner de production | CHANGE-1 |
| Imports Java ambigus / inférence JUnit dans nouveaux tests | Corrections de compilation locales ; deux compilations répétées avant correction réellement copiée sont conservées comme travail sans preuve supplémentaire | test-compile puis scopes concernés |
| Plan Visibility utilise un autre index partiel valide | Oracle vérifie index partiel réel et absence de scan historique complet, sans imposer un nom d’index non contractuel | CHANGE-3 |
| Double contrainte unique lors de l’initialisation CurrentBinding concurrente | INSERT sans cible de conflit, SELECT FOR UPDATE séparé puis mise à jour monotone et comparaison complète | CHANGE-4 puis conformité L2 |
| Preuve métier JSON mal formée devenait UNAVAILABLE par poisoning de lecture | Classification typée DIVERGENT à l’intérieur de l’observation, SQL indisponible reste indisponible ; assertions root/subobject/balance | CHANGE-1/2 + 7 invocations d’intégration |
| Audit du filtre User | Discovery filtre déjà POCOMA_USER ; production inchangée, assertion de zéro slot et refus d’un faux recovery ajoutée | 6 conformités |

Aucune relance automatique, aucune extension vers un gate non filtré, aucun timeout métier augmenté. Aucun dépassement de prévision dans les runs sains ; cette observation n’est pas une garantie de coût futur. Compilation incrémentale, image Docker déjà disponible, fixtures fraîches et machine locale expliquent les temps courts. Cold checkout/cache, téléchargement d’image/dépendances, contention et environnements différents restent **UNKNOWN**. Un délai mur sur la base de 43 min n’existe pas.

Les phases mesurées sont inventaire, découverte du modèle, subprocessus Maven et collecte. Les XML donnent les durées JUnit ; fixtures et compilation restent incluses dans Maven. Leur ventilation murale indépendante est UNKNOWN, les événements de recompilation ne sont pas des durées. [Reçus et bruts](../testing/evidence/L4-I3-I7/README.md).

## Outillage

Adaptation limitée à verify.py/registre et tests Python : sélection exacte par méthode et invocations paramétrées, mesures monotoniques avec écart non bloquant, PID/PGID, TERM puis KILL sur interruption, rétention des XML partiels et status opérationnel PASS/FAIL/INCOMPLETE/NOT RUN. Les verdicts historiques FAIL/INCONCLUSIVE sont préservés et ne sont pas réécrits. La séquence ne fait ni retry ni élargissement. Le diagnostic de blocage exige des faits techniques, pas un chronomètre de prévision ; aucun watchdog de 60/300/900 s n’est installé. Le script de rejeu archivé exige un motif et ne permet que des méthodes du manifeste d’origine.

Deux suites Python sans Maven/PostgreSQL réels : **31 + 8 = 39 PASS**, 0,825 s et 0,012 s observés. Les subprocessus sont simulés dans ces suites. Un test existant attendu à tort à 28 a été corrigé à **32 invocations FOUNDATION présentes** ; la recette Foundation elle-même et les historiques restent inchangés. Le journal de départ de l’adaptation préexistante (31 tests, deux échecs) est conservé. [Logs finaux](../testing/evidence/L4-I3-I7/tooling/).

La gestion d’interruption cible le groupe Maven/forks qui héritent du groupe. SIGKILL du superviseur et processus qui se détachent volontairement restent hors garantie ; verrou et preuves doivent alors être inspectés. Ce chantier n’a pas injecté un blocage réel d’une campagne Maven : le contrôle d’interruption est qualifié par Python ciblé. Aucun PASS de gate global d’outillage n’est revendiqué.

## Réutilisation, reports et certification

[Attribution explicite](../testing/evidence/L4-I3-I7/Reuse_Attribution.json) : les entrées des modules domain-consumption/engine-consumption/infra-tx-spring sont inchangées depuis le SHA accepté I2 `6496e6d0`. Les preuves L1 du calcul rationnel et de la reconstruction financière restent attribuées au code inchangé, avec nouveaux oracles source/store/participant. Aucun ancien test exécuté n’est compté comme nouvelle qualification. Les réserves L1/L2/L3, paramètres productifs 30 s, UNKNOWN et drainage conservateur, confiance admin PostgreSQL, demeurent.

- **I8** : G-I3…G-I7 non filtrées regroupées, gate Architecture des deux dépendances Results, activation six participants dans les racines productives, services de lecture complets P01-H/I/P02-K/P03-M/READ-01/SEC/S-01/S-02, ressources/drainage/restart et gate majeure G-I8/Architecture/full reactor. Pas de suppression de ces obligations.
- **L5** : HTTP/OIDC/consentement, anti-énumération externe et polling, invitation ; aucun 200/401/404/503 certifié ici.
- **L6** : reconstruction/reset administratifs, profils exhaustifs Grand Pot/histoire longue, charge/PERF/OPS/SLO. Les compteurs SQL et plans locaux restent des contributions, pas une certification de performance.

Coût de certification différée : **UNKNOWN**, distinct des 43 min indicatives. Repères seulement : I2 a mesuré 3127,319 s pour PROJECTION+COMMAND et 3344,163 s pour Architecture (6471,482 s soit 107 min 51,482 s pour ces deux exécutions historiques). Le full diagnostic précédent de 3079,751 s était en échec. Ces périmètres se recouvrent ; ils ne sont ni un budget I8 ni des coûts à additionner automatiquement. Sources : [manifeste I2](L4_I2_Proof_Manifest.json), [politique et mesures](../testing/Reactor_Verification_Policy.md#35-operational-verification-and-build-cycle--fast--change--integration--certification).

## Comparaison et décision de livraison

| Indicateur | Ancien cadrage | Livré |
|---|---:|---:|
| Nouvelles méthodes | 114 | 32 |
| Invocations nouvelles | 139 | 37 |
| Régressions historiques prévues/rejouées | 20 | 0 (preuves attribuées, pas recertifiées) |
| Campagnes définies | 10 | 9 (3 FAST, 5 CHANGE, 1 INTEGRATION) |
| Fermeture maximale CHANGE | 52 modules | 36 modules enfants (+ racine) |
| Plafond cumulé | 44 min | aucun ; enveloppes indicatives 43 min |
| Nominal avant mesure | UNKNOWN | UNKNOWN ; premiers neuf PASS mesurés 148,391 s |

Aucune anomalie fonctionnelle locale connue après les corrections et l’audit. Restent les limites probatoires citées, la non-activation et les gates reportées. L’implémentation est livrée ; **qualification locale PASS avec réserves et obligations I8/L5/L6**, sans clôture canonique I3–I7.
