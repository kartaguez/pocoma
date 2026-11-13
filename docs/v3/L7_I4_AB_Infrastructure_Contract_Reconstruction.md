# L7.4 — Reconstitution du contrat d'infrastructure SPEC-09

Date : 13 novembre 2026. Mandat : `GO_L7_I4_AB_INFRASTRUCTURE_SCOPING`.
Référence analysée : `bc562aeb`, branche `feat/v3-architecture`.
Analyse et documentation uniquement. Toutes les campagnes contractuelles restent `NOT_RUN`.

## 1. Autorité, précédence et vocabulaire

La présente annexe précède les choix du [rapport de conception](L7_I4_AB_Infrastructure_Scoping_and_Design_Report.md).
Elle ne modifie aucune SPEC, décision ou preuve antérieure.
La [matrice CSV d'infrastructure](L7_I4_AB_Infrastructure_Requirements_Matrix.csv)
rapproche chacune des31 lignes contractuelles existantes des contrôles et étapes
du futur chantier, sans modifier leurs classifications ni leurs verdicts NOT_RUN.

| Source applicable | Portée retenue |
|---|---|
| [SPEC-09 intégrale](SPEC-09_Performance.md), §§1–8 | Profils, ressources, charges, budgets, C-03, DeletePot, AUD-V3-013, profondeur historique |
| [SPEC-10](SPEC-10_Test_Certification.md), PERF-01 §§24.1–24.6 et A11-L/M | Environnement, charge ouverte, protocole, admissibilité, stabilité et preuves |
| [Retrait Link](L7_I2_Link_Retirement.md) | Les clauses exclusivement Link sont historiques ; Registration/Unlink restent actives |
| SPEC-09 §3, décision du 12 novembre 2026 | F01-E : nouveau binding réellement utilisé pour `/api/v3/me/pots?limit=1`, liste vide, même EUI/JWT ; remplace le Pot préexistant de SPEC-10 §24.4.5 |
| SPEC-09 §§4–5, SPEC-10 amendement après §24.6 | C-03 à W≥V ; visibilité indépendante ; oracle interne terminal pour DeletePot |
| [Plan L7.4](L7_I4_SPEC09_Performance_Certification_Execution_Plan.md), [matrice de 31 exigences](L7_I4_SPEC09_Contract_Matrix.csv), [manifeste](../../scripts/performance/l7-spec09-campaigns.json) | Déclinaison de campagne existante ; distinguer norme, propositions et paramètres du banc |
| [Rapport technique](L7_I4_SPEC09_Technical_Qualification_Report.md), [réserves](L7_I4_Anomalies_and_Reservations_Register.md) | État actuel de preuve et manques ; priorité sur les anciennes affirmations « banc prêt » |

**A/B SPEC-09** compare deux environnements du même gel logiciel : AuthN simulée
en A, Keycloak réel isolé en B, AuthZ Pocoma réelle dans les deux.
**A/B historique de refactoring** compare les logiciels `123b3fd2` et
`779d7e3e` avec patch archivé. Il ne faut jamais les confondre.
Le nombre de cinq processus est le choix d'assemblage actuel L7, pas un nombre
de machines prescrit par SPEC-09. Deux bases WRITE/READ distinctes ne signifient
pas deux VM PostgreSQL obligatoires.

## 2. Matrice des exigences d'infrastructure transversales

N = normatif ; P = protocole proposé à ratifier ; B = paramètre de banc existant ;
D = choix de conception proposé ici. Aucun chiffre D/B/P ne devient un SLO.

| ID | Classe et source | Exigence exacte | Conséquence pour l'infrastructure / preuve de recevabilité |
|---|---|---|---|
| INF-01 | N, SPEC-09 §3 ; SPEC-10 §24.1 | PostgreSQL sur machine/VM dédiée 4 vCPU/8 Go ; API et tous les runtimes sur seconde machine/VM 4 vCPU/8 Go | Deux identités de VM, CPU/RAM effectifs, topologie et limites attestés ; deux conteneurs sur Docker Desktop ne suffisent pas |
| INF-02 | N, mêmes sources | B conserve le socle A, ajoute Keycloak réel sur ressources isolées | Ne pas partager PostgreSQL métier avec Keycloak ; mêmes ressources métier A/B |
| INF-03 | N, mêmes sources | AuthN externe contrôlée en A, AuthZ inchangée ; OIDC réel ciblé en B | JWT signés avec claims requis dont `auth_time` ; pas de profil permissif ou bypass ; login hors chemin ordinaire de Command |
| INF-04 | N, SPEC-10 §24.1 | Liaison locale ; injecteur/supervision idéalement isolés | Même réseau privé et AZ proposée ; machines auxiliaires séparées ; RTT/pertes/jitter constatés, aucun plafond réseau inventé |
| INF-05 | N, SPEC-09 §1/§5/§7 | Petit 5/20, moyen 20/200, grand 100/2 000 ; grand jusqu'à 200 000 participations | Dénombrements et contenus réels, fractions exactes ; un JSON de seed ne prouve pas sa matérialisation |
| INF-06 | N, SPEC-09 §3/§8 | 100 Pots/10 000 événements ; 10 000/1 million ; 100 000/10 millions ; profondeur 100/1 000/10 000 versions | Snapshots réellement peuplés ; compter les événements par famille, pas `estimatedEvents`, commandes ou seuls compteurs de complétion |
| INF-07 | N, SPEC-10 §24.1 | Charge ouverte 1, 10 et 50/s ; séparer indépendants et contention | Conserver prévus/déclenchés/tentés/admis/appliqués/rejetés/censurés ; retard et `dropped_iterations` invalidants, pas d'ajustement silencieux de débit |
| INF-08 | N, SPEC-10 §24.1 | Mix création/modification/suppression, multi-cibles, non financier ; isoler no-op/rejets des nouvelles V | Journal déterministe de commandes et de leurs préconditions ; E1 ou k6 CreateExpense seul ne suffit pas |
| INF-09 | N, SPEC-09 §§3–6 | p50/p95/p99 ; succès/rejets/erreurs/reprises séparés ; étapes et ressources | Séries brutes attribuées aux VM/processus/Consumption et horodatées ; absence de métrique = inconnue |
| INF-10 | N, SPEC-10 §24.3 ; qualification de statut §24.6 | ≥3 répétitions comparables ; <300 observations valides d'une population SLO interdit PASS | Conserver ces minima du plan courant ; ratifier le paquet de protocole encore proposé, sans les réduire |
| INF-11 | P, SPEC-10 §24.3/§24.6 | Warm-up/fenêtres détaillés ci-dessous, sondage initial 25 ms, modalités exactes et timeouts | Préenregistrer et ratifier avant exécution ; une valeur déjà présente dans le manifeste n'est pas une ratification |
| INF-12 | N, SPEC-09 §4 | Première lecture API autorisée complète `minVersion=V`, W≥V, sous-objets et Balance(W) cohérents | Ne pas substituer le résultat Command, une métrique interne ou une simple version courante ; exercer aussi W>V |
| INF-13 | N, SPEC-09 §4 | Balance(P,V) active exacte/durable indépendante de W ; SLO nominal distinct | Sonde READ interne indépendante et calcul de fractions ; ne pas attendre le Pot complet pour commencer sa mesure |
| INF-14 | N, SPEC-09 §5 | DeletePot(D) : première observation READ `CurrentPot→PotVersion(D).deleted=true` ; mêmes budgets C-03 | Pas de succès sur simple HTTP404 ; Balance(D) absente attendue, balances actives V<D toutes dues |
| INF-15 | N, SPEC-09 §4/§5 ; SPEC-10 §24.5 | Visibilité et complétion de toutes les Consumptions attendues indépendantes du jalon C-03 | Contrôler sources, publications, checkpoints, slots et effets, pas seulement disparition des slots Command |
| INF-16 | N, SPEC-10 §24.4 | Admission durable → premier jalon observable ; polling, non-succès et censure conservés | Client monotone, bornes send/202, précision des sondes ; aucun p95 de phases additionné |
| INF-17 | N, SPEC-10 §24.5 | Soutenu stable sans croissance continue du backlog par Consumption | Séries source ET slots, âge du plus ancien, débit terminal/projeté ; méthode/fenêtres de régression fixées avant mesure |
| INF-18 | N, SPEC-09 §5/§8 | AUD-V3-013 et profondeur historique | Références/stockage/index/WAL ; sous-versions/contenus/collisions ; lignes/requêtes JDBC et plans ; fractions/CPU/IO/RAM ; JSON brut/compressé |
| INF-19 | N, SPEC-10 §§24.1/24.3/24.6 | État initial défini, gel SHA/config/images/JVM/PG/k6/Keycloak, données et bruts | Restauration cohérente WRITE+READ ; empreintes sémantiques et manifestes, paramètres effectifs et journal d'arrêt |
| INF-20 | N, SPEC-10 §24.5 et sûreté L7.3 | Aucun effet double/perdu/partiel ; exactitude métier/sécurité | Oracle de sûreté extérieur aux résultats de latence ; aucune purge de quarantaine ni terminalisation fabriquée pour finir une campagne |

La notation « 8 Go » des SPEC et « 8 GiB » du manifeste n'est pas une permission
de sous-allouer. Le choix proposé est une VM annoncée à 8 GiB, avec `MemTotal`
effectif conservé (réserve noyau explicitée), sans réduire l'enveloppe à 8 milliards
d'octets par conversion implicite. CPU dédiés proposés pour réduire la variabilité ;
la SPEC prescrit les vCPU et la machine dédiée au rôle, pas un modèle de CPU.

## 3. Les treize familles du manifeste, sans changement de contrat

Les dix F01 et A11-L/M portent les obligations contractuelles ; ENDURANCE et
SATURATION complètent leur exploration dans le manifeste. « Treize familles »
ne signifie pas treize nouveaux SLO. Les fenêtres P ci-dessous sont hors seed,
restauration, préparation et collecte finale. Les trois répétitions ne fusionnent
jamais leurs percentiles. Le warm-up ne compte pas dans les 300 observations.

| Famille | Environnement, population et stimulus | Warm-up / fenêtre par répétition du plan courant | Acceptation / métriques distinctives |
|---|---|---|---|
| F01-A | A, moyens indépendants, 1/s ; parc B de 10 Pots | P : 180 s + 1 200 s, 1 200 offres évaluées | N : deux p95 ≤500 ms, Pot C-03 et Balance(P,V) indépendante |
| F01-B | A, moyens indépendants, 10/s ; parc B de 100 Pots | P : 300 s + 900 s, 9 000 offres | N : p95 Pot ≤1 000 ms ; absence de croissance continue source/slots/âge par Consumption |
| F01-C | A, moyens indépendants, 50/s ; parc B de 250 Pots | P : 300 s de base soutenue +180 s de pointe, puis ≥600 s à1/s ; 9 000 offres de pointe | N : p95 ≤3 000 ms sur la population de pointe, y compris achèvements tardifs ; résorption mesurée, pas nouveau seuil de drain |
| F01-D | A, grands indépendants 100/2 000, jusqu'à200 000 participations, 1/s ; parc B de10 | P : 180 s +1 200 s | N : p95 Pot ≤2 000 ms ; coût recalcul complet ; aucun budget Grand à10/50/s adopté |
| F01-E | A, Registration, EUI toutes fraîches, 1/s | P : 180 s +1 200 s ; 1 380 sujets inédits/répétition | N : p95 ≤500 ms jusqu'à HTTP200 navigation vide avec nouveau binding et même JWT ; aucun Pot préalable requis |
| F01-F | B, OIDC Authorization Code/PKCE S256, Registration/Unlink, refus mauvais/ancien token | P : 180 s +≥600 s par parcours ; script actuel compose un parcours Registration/Unlink à1/s (780 sujets) | N : sûreté, coûts login/échange séparés et comparaison Registration appariée A/B ; aucun SLO B numérique. Un parcours composé ne ferme pas seul tous les parcours ciblés |
| F01-G | A, 10 000 Commands initiales admises PENDING sur moyens distincts ; Workers traitement/Discovery stoppés ; reprise sous1 nouvelle/s | N : chrono au redémarrage, convergence initiales ≤300 s ; préstock hors chrono | N : initiales terminales, résultats et toutes projections/Consumptions attendues terminées ; quarantaines incomplètes ; nouvelles demandes suivies séparément |
| F01-H | A, un moyen, commandes concurrentes, retour nominal | P : 180 s +≥600 s puis drainage ; concurrence documentée | N : aucun double/perte/partiel, décisions conformes au métier courant, terminal ou échec technique diagnostiqué, backlog résorbé ; aucun p95 cible |
| F01-I | A, trois volumes globaux réels et profondeurs100/1 000/10 000 ; trois tailles locales ; AUD-V3-013 | Durée/échantillons non prescrits : grille D stratifiée et répétée à figer au pilote | N : coût réel SQL/JDBC/histoire/index/WAL/IO/CPU/RAM/JSON ; transfert d'histoire Java indépendant de profondeur à mutation identique ; pas temps constant absolu |
| F01-J | A, repos puis première commande, moyen | B : repos600 s ; commande et suivi ensuite, durée non prescrite | N : coût à vide SQL/CPU/réveils et réactivité Discovery/Engine/E2E ; aucun nouveau SLO |
| A11-L/M | A, suppressions appariées aux quatre profils A/B/C/D | D : cohortes propres par palier, fenêtres appariées ; ≥300 valides par population SLO retenue | N : oracle terminal interne, p95 respectivement500/1 000/3 000/2 000 ms ; balances V<D et visibilité/complétions distinctes |
| ENDURANCE | A, moyens,100 Pots,10/s | B : 3 600 s évaluées ; warm-up D300 s, hors fenêtre | Même sûreté, progression et stabilité ;3600 s n'est pas un SLO ajouté |
| SATURATION | A, moyens,250 Pots ;1/5/10/20/35/50/s | B :900 s par palier (5 400 s) ; warm-up D300 s avant série | Débit utile réellement atteint, ressources et frontière injecteur/serveur ; pas extrapolation ni seuil de clôture nouveau |

## 4. Reprise et équivalence

F01-G vérifie d'abord exactement les 10 000 admissions durables avant redémarrage.
Le travail dérivé READ peut ne pas encore exister à cet instant. L'inventaire des
effets attendus doit découler des résultats/événements réels, puis être rapproché
des trois Consumptions directes métier et des résultats, bindings/invitations si
applicables. Pas de jalon de convergence fondé sur un seul compteur agrégé.

F01-H doit rendre observables collisions CAS, reprises complètes, rejets légitimes
et limitations par partition. La résilience de processus E3 demeure distincte :
la preuve du run06 n'exerce pas un crash pendant calcul ni un claim acquis.
Aucun « délai de reprise 60 s » n'est contractuel ; les leases240 s et claims60 s
sont des réglages de recette. Leur expiration doit être observée, jamais avancée.

L'équivalence A/B porte sur les mêmes ressources métier, build, schémas/ACL,
populations sémantiques initiales, offre/intentions, oracles, instrumentation,
warm-up et régime de cache. Elle autorise uniquement les différences nécessaires
d'issuer/JWKS/JWT et d'état IdP documentées. Les EUI peuvent être neuves par run :
elles doivent être appariées par rang/scénario, avec distributions et routage
comparables, pas prétendues identiques en octets après changement de credentials.
Un intervalle descriptif de différence ou de ratio de p95 ne constitue pas un
test d'équivalence accepté. Aucun seuil numérique d'équivalence A/B n'a été trouvé.
Sans marge contractuelle, publier l'écart et son incertitude, sans verdict
« équivalent à ±x% ». Cela ne bloque pas la construction de l'infrastructure.

## 5. Conditions d'invalidation et décisions encore nécessaires

| Situation | Traitement prévu |
|---|---|
| Topologie4/8+4/8 absente, SHA/image/seed non attribuable, état initial dérivé | Campagne non admissible / INCONCLUSIVE, ou NOT_RUN avant lancement |
| Offre non tenue, famine de Pots/VU, pertes de journaux, dropped iterations, retard de collecte non borné | Conserver toutes les tentatives ; invalider la preuve de charge, ne pas interpréter le faible débit comme capacité serveur |
| Source/slot confondus, sonde Balance séquentielle après Pot, simple404 Delete, W/V incohérents | Oracle insuffisant ; aucune qualification du SLO concerné |
| <300 valides par population SLO, répétitions manquantes, censure éliminée, horloge inadéquate, observation perturbante | Aucun PASS ; conserver les censures et diagnostics, corriger avant nouvelle campagne identifiée |
| Seuil dépassé avec environnement/mesure admissibles | FAIL contractuel ; aucune modification de CPU/RAM, timeout ou seuil pour le transformer en PASS |
| Invariant critique violé, secret exporté ou cible inconnue | Arrêt, conservation des traces accessibles, qualification séparée de l'incident |

Ambiguïtés résolues par analyse : deux sens de A/B ; deux bases dans une VM PG ;
cinq processus sur la VM applicative ; retrait Link ; F01-E amendé ; pas de barrière
de visibilité pour C-03 ; pas de balance terminale D ; paramètres locaux ≠SLO.
Le tableau « cluster3 nœuds/8–16 workers/NVMe » du rapport R11 final n'est pas
le contrat SPEC-09 et ne dimensionne pas la cible.

Restent à ratifier explicitement au titre de SPEC-10 §24.6 : le paquet de protocole
(fenêtres, répétitions, minimum d'échantillons conservés, sondage calibré, censure,
grille/mix et méthode de stabilité). Les valeurs techniques seront fixées dans
le reçu de préqualification ; aucune nouvelle décision humaine par timeout,
pool, CIDR ou version d'outil n'est demandée. Le budget et le GO d'implémentation
constituent une seconde décision de portée. Aucun SLO n'est ambigu au point
d'empêcher de construire le socle proposé.

## 6. Contraintes de clôture conservées

Selon le [plan L7.1 §10](L7_I1_Certification_Coverage_and_Execution_Plan.md),
L7.5 attend le gel, les certifications L7.2/L7.3/L7.4, les gates applicables,
les preuves externes non substituables, l'audit indépendant et l'acte humain.
L7.3 reste NOT_CERTIFIED, ses15 obligations officiellement partielles restent
inchangées, dont une candidate à clôture. L7.5 reste BLOCKED ; L7 reste OPEN.
Le présent cadrage ne ratifie ni n'exécute ces gates et n'engage pas L8.
