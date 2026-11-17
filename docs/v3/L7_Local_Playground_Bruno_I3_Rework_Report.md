# L7 Local Playground — reprise I3 Bruno

Mandat `GO_L7_LB_I3_BRUNO_REWORK`, 14 novembre 2026 (Europe/Paris).
Branche `feat/l7-lb-local-playground`, départ propre
`7221d139ca86a630bacc97dd40a5021d6db08050`. Le SHA de référence fourni
`f1ccd0dc` est son ancêtre ; les commits `e54cadd4` et `7221d139` ont déjà
introduit puis documenté les connexions natives GUI. Ils sont conservés.

## Audit préalable et périmètre déclaré

Sources examinées avant implémentation : les trois contrôleurs publics
`V3AdmissionController`, `V3ReadController`, `V3InvitationController`, les
décodeurs `CommandAdmissionInput`/`IdentityAdmissionInput`, `V3ReadDtos`, la
configuration HTTP/AuthN, les transitions et autorisations Pot/Shareholder,
le contrôleur technique StageHandoff, les propriétés des cinq runtimes,
`scripts/bruno-v3`, `scripts/bruno-playground`, les helpers/fixtures B1/B2/B4,
le lanceur `scripts/local/runtime.py`, les guides et qualifications existants.

Inventaire : **12 routes métier HTTP**, dont deux admissions multiplexant
**13 Command et 2 IdentityRequest**. Cela représente **25 opérations métier**
en distinguant chaque intention admise (15 admissions + 10 consultations).
La matrice CSV distingue ces opérations des endpoints OIDC, techniques,
internes et retirés. Il n'existe pas de route publique Link, de CRUD User,
de lecture Expense autonome ni de balance courante indépendante de Pot@V.

La collection initiale confondait preuve automatisée et client interactif :
PG01 était un GET déclenchant tous les POST dans un helper. La CLI injectait
deux JWT absents du GUI ; le correctif précédent a corrigé l'auth native,
mais n'a pas rendu les opérations métier navigables. Le corpus bruno-v3
contient des oracles utiles, mais repose largement sur fixtures et appels
secondaires ; sa démonstration Observability attend encore `CHANGED` pour
Registration, alors que le DTO courant expose `COMPLETED`/`REJECTED`.
Il ne peut donc pas être promu tel quel en client de développement.

Frontière déclarée avant les modifications : **BRUNO CLIENT / LOCAL
INTEGRATION**, slices Maven modifiées **NONE** ; WEB, COMMAND et PROJECTION
exercés seulement par HTTP contre les cinq JVM existantes. Interdit : toute
modification sous `app/`, contrat public, architecture, invariant métier,
profil de sécurité, durée de token, migration historique et campagne SPEC09.
Gate global architecture **NOT REQUIRED** ; réacteur complet **NOT REQUIRED**.
Base **CURRENT V3**, bootstrap local existant. Escalade : tout défaut exigeant
une modification sous `app/` doit être documenté et arbitré avant code.

## Conception retenue

Point d'entrée unique : `scripts/bruno-playground`, **Pocoma V3 Playground**.
Les corpus historiques `scripts/bruno-v3` et `scripts/bruno` restent des
corpus de qualification/archives, avec redirection documentaire explicite.
Les requêtes par domaine sont les sources éditables ; les parcours visibles
sont composés de ces fichiers par `scripts/local/sync_bruno_playground.py`.
Le contrôle `--check` empêche une divergence silencieuse. Aucun générateur
n'est nécessaire pendant l'utilisation GUI ou CLI.

Les helpers partagent uniquement auth, variables, attente bornée du même GET
et assertions. Chaque mutation est le POST visible avec JSON éditable.
Réutilisation : payloads actuels B202–B212/B4, masquage B10/B29/B402,
idempotence B213/B214, référence Expense B15, soldes exacts B09/PG01,
histoire après suppression B13/PG01, départ volontaire B401 et révocation
de binding B220. Les fixtures, accès SQL et injections ne sont pas repris.



## Livraison et couverture

**Verdict proposé : I3_BRUNO_PARTIAL.** Le client et ses preuves automatisées
sont livrés ; la connexion complète et les parcours rendus dans le GUI restent
à valider humainement. Aucune acceptation humaine n'est prononcée.

SHA technique final qualifié propre : **7d951a34754c67d8fa88be669c3929ea7c7b2d04**.
Le SHA final de publication est la tête de branche annoncée dans la livraison :
les commits suivants ne publient que rapports et preuves, sans changer les
entrées opérationnelles qualifiées.

**25/25 opérations métier, 12/12 routes**. Aucun endpoint métier actuel exclu.
La matrice possède 36 lignes : 25 métier, 3 OIDC, 4 techniques, 4 historiques/
internes explicitement distinguées. Les opérations derrière /commands ne sont
pas comptées comme de nouvelles routes.

| Domaine fonctionnel | Opérations publiques couvertes |
| --- | ---: |
| Identité (Registration, Unlink, résultat, binding) | 4/4 |
| Pots (Create, Update, Delete, courant, histoire exacte) | 5/5 |
| Actionnaires (Add, Details avec KEEP/SELF/DETACH, Weights, Delete) | 4/4 |
| Dépenses (4 mutations et batch de références) | 5/5 |
| Résultat Command | 1/1 |
| Balances exactes | 1/1 |
| Navigation paginée | 1/1 |
| Invitations (Create, Accept, lien privé, places) | 4/4 |

**171 fichiers de requête** : 51 requêtes par domaine, 2 connexions OAuth,
118 étapes composées. Cela représente **53 entrées sources**, et non 171
endpoints distincts. J01=66 étapes, J02=31, J03=21. Les mêmes fichiers Jxx
sont exécutés par la CLI et lisibles/modifiables dans Bruno GUI. Le contrôle
de synchronisation refuse les copies divergentes.

Fichiers principaux : collection complète sous scripts/bruno-playground,
lib/workspace.js et lib/session.js, catalogue.json, journeys.json, inventory.json ;
scripts/local/runtime.py (sélection récursive et validation exacte),
sync_bruno_playground.py, test_bruno_gui.js, test_gui_auth_protocol.py,
test_bruno_qualification.py et test_bruno_playground_installation.py.
Le guide, le contrat consommateur et le quickstart sont actualisés ; les
README historiques renvoient vers l'unique collection interactive. PG01 et
son helper de mutations opaques sont retirés.

## Authentification et exécution individuelle

GUI : Authorization Code + PKCE S256 natif 4.2.1, client public existant,
scope openid, redirect exacte, state généré/vérifié par Bruno. Deux Token IDs
indépendants, prompt=login et login_hint third/guest. Aucun secret client,
password grant, JWT statique, copier-coller de JWT ou changement de protection.
Les API demeurent l'autorité de signature et d'autorisation.

CLI : mêmes requêtes, sessions obtenues au vrai Keycloak par formulaire HTTP
et Code + S256, puis privées au processus. La requête Owner seule n'exige pas de
session Guest. Les contextes ne peuvent pas partager le même sujet.
Un changement d'issuer efface les variables de scénario sensibles ; les
sessions périmées/mal ciblées indiquent la connexion à renouveler.

Binding, identifiants de commandes/résultats/Pot/Expense/actionnaires et secret
d'invitation sont propagés par les réponses. Les tokens restent dans le cache
OAuth ou l'environnement du processus ; les autres références sensibles ne
sont que des variables runtime. Le guide décrit les quatre catégories :
configuration publique, réglages, scénario, secrets.

Chaque POST est visible et éditable ; aucune mutation cachée dans les helpers.
L'attente est limitée au même GET asynchrone, au plus30s, sans remplacement de
la réponse affichée par une réponse fabriquée. Les résultats sont vérifiés
séparément du202. Le parcours isolé I01→I02→I03→P01→R01→P04 laisse un Pot
explorable ; il est documenté avec les noms exacts des requêtes.

## Qualification automatisée A/B

Preuves finales : [index](../testing/evidence/L7-LB-I3-REWORK/README.md),
[reçu](../testing/evidence/L7-LB-I3-REWORK/7d951a34/qualification.json),
[commandes](../testing/evidence/L7-LB-I3-REWORK/7d951a34/commands.json).
Empreinte opérationnelle : f2fb534fd7f5b91a858c2d8df5d2982505ece2797ef7bbfeca7cd26db04d10ff, identique avant/après campagne.

| Contrôle exécuté au SHA propre | Résultat |
| --- | --- |
| Contrôles Python locaux | 27/27 PASS |
| Synchronisation sources/parcours | 118 requêtes et 236 tests attendus, CHECK PASS |
| Inventaire contrôleurs/décodeurs contre collection | 12 routes, 13 Command et 2 IdentityRequest, PASS |
| Installation i3-final neuve | READY, 50.75s ; bases/realm/volumes neufs |
| CLI sur instance neuve | 118 requêtes, 236 tests, 731 assertions, 220 appels HTTP physiques, PASS |
| Moteurs installés Bruno 4.2.1, cache natif et HTTP réels | 118 requêtes, 236 tests, 730 assertions ; 193 contrôles incluant parsing des171 fichiers, PASS |
| Requêtes techniques livrées | 4 requêtes, 8 tests, 14 assertions, PASS |
| Reset de la seule fixture i3-final | READY, 49.96s ; nouveau projet/volume/realm |
| CLI après reset | 118 requêtes, 236 tests, 731 assertions, 221 appels HTTP physiques, PASS |
| Assertions/erreurs/skips JUnit | Identités exactes ; zéro failure/error/skip ; code0 |
| Sécurité de la publication | Reçus allowlistés, pas de reporter JSON de bodies ; scan credentials connus/JWT et hashes archivés |

Les compteurs distinguent requêtes visibles, tests nommés, assertions réelles
et appels de polling physiques. Les différences d'appels viennent de la
convergence asynchrone. Les assertions de soldes sont calculées indépendamment :
10/3→±5/3 puis20/3→±10/3, somme rationnelle exacte nulle.

L'installation est neuve pour ses données et son realm, sur le même Mac avec
images, Maven/npm et compilation hôte déjà disponibles. Ce n'est ni un nouvel
hôte ni une campagne tous caches froids. Le package réel de la distribution
est conservé ; aucun JAR préinstallé manuel n'est requis. La première campagne
complète au SHA587b729a est conservée séparément ; le dernier rejeu inclut la
correction des libellés GUI Run / Collection Runner / JavaScript Sandbox.
Les deux campagnes sont attribuées à leurs propres SHA, sans additionner leurs
tests pour gonfler les résultats finaux.

## Observations GUI C et réserve humaine

[Observations effectivement faites](../testing/evidence/L7-LB-I3-REWORK/7d951a34/gui-observations.json) :
Bruno 4.2.1 a affiché la collection organisée ; Instance-demo a été sélectionné ;
E00 a reçu200 et affiché Tests2 / Passed2 / Failed0. Les configurations Auth
Owner et Guest, PKCE, leurs deux Token IDs et Client Secret vide ont été lus.
La fenêtre Keycloak Owner s'est ouverte avec login_hint third, S256 et un
state généré ; elle a été fermée avant saisie du mot de passe.

L'ouverture Guest a d'abord été refusée par la revue automatique, faute
d'autorisation directe reconnue. L'utilisateur a ensuite explicitement
autorisé les connexions locales third/guest. Après les campagnes, le contrôle
de fenêtre échoue avec cgWindowNotFound, y compris après fin du processus
natif de test ; l'API de lancement alternatif est indisponible. Aucune
tentative de contournement par shell/AppleScript.

**Non exécutés dans le GUI** : connexion native terminée Owner/Guest, requête
métier authentifiée affichée, parcours Runner complet, changement d'identité
après login et reprise GUI après reset. Les moteurs natifs ne sont pas une
preuve de rendu ou de login GUI. AC3 est implémenté et contrôlé au niveau
natif/protocole, mais sa confirmation en interface et AC10 restent dus.
Les noms Run, Collection Runner et JavaScript Sandbox/Developer Mode ont été
vérifiés dans le bundle installé 4.2.1 ; ils ne sont pas revendiqués comme des
menus tous manipulés dans la fenêtre devenue indisponible.

Le [guide](L7_Local_Playground_Bruno.md) fournit la recette humaine complète.
AC1/2/4/5/6/7/8/9 ont des preuves adaptées de collection/protocole ; cette
qualification ne remplace pas la validation GUI des mêmes usages. Le verdict
reste **I3_BRUNO_PARTIAL**, et non READY déduit de BRUNO_PASS.

## Commandes exécutées et frontière de preuve

Depuis la racine, commandes de qualification finales :

~~~sh
python3 -B -m unittest discover -s scripts/local -p 'test_*.py'
python3 -B scripts/local/sync_bruno_playground.py --check
python3 -B scripts/local/test_bruno_playground_installation.py --instance i3-final --output /tmp/pocoma-i3-published
~~~

Le dernier outil a réellement exécuté start, gui, bruno, reset dry-run, reset,
gui et bruno (argv et durées dans commands.json), ainsi que :

~~~sh
python3 -B scripts/local/test_gui_auth_protocol.py --instance i3-final --output /tmp/pocoma-i3-published/native-421.json
~~~

Cette fonction a été appelée en Python par le collecteur ; ses argv Electron
réels sont dans native-421.json. Les autres exécutions préparatoires ont utilisé
demo, i3-bruno et i3-workshop. La CLI métier exacte est :

~~~sh
scripts/bruno-v3/node_modules/.bin/bru run '90 - End-to-End Journeys' -r --env Local --sandbox=developer --bail --reporter-junit <preuve-neuve>/bruno.xml
~~~

La sélection technique réelle est le dossier '80 - Technical' avec les mêmes
options, reportée dans technical/receipt.json. Les sessions sont uniquement
dans l'environnement privé ; aucun secret dans argv. Le contrôle installé
sans HTTP a également été exécuté par ELECTRON_RUN_AS_NODE=1 et le binaire
/Applications/Bruno.app/Contents/MacOS/Bruno, script scripts/local/test_bruno_gui.js.

Package de distribution réellement exécuté, sans preuve de test Java :

~~~sh
./app/mvnw -f app/pom.xml -pl runtime-web-read,runtime-web-write,runtime-worker-materialize,runtime-worker-process -am package -DskipTests
~~~

Le reçu conserve les chemins absolus des argv réels et les hashes des JAR.
**Slices Maven modifiées : NONE** ; consommateurs HTTP exercés : WEB, COMMAND,
PROJECTION. Aucun changement sous app/. Franchissement inattendu : **NONE**.
Gate architecture global : **NOT REQUIRED / NOT RUN**. Réacteur complet :
**NOT REQUIRED / NOT RUN**, car aucune frontière/module/architecture/closure
L7 ou intégration main n'est modifiée. Tests Java canoniques : **NOT RUN**,
aucune slice Maven n'est déclarée close. DB **CURRENT V3**, aucune migration
historique ; aucun nouveau baseline TRUSTED. SPEC09, I4–I6 et certification
globale : **NOT RUN**, hors mandat. Le succès de package ne vaut pas test PASS.

## Diagnostics conservés et état laissé

Les [tentatives préparatoires](../testing/evidence/L7-LB-I3-REWORK/587b729a/attempts)
conservent leurs verdicts : claim preferred_username absent du scope openid,
casse REGISTRATION/UNLINK du résultat Identity, préfixe /realms/pocoma oublié
dans l'oracle de route OIDC, et collecteur initial ne lisant pas le préambule
du bootstrap. Ce dernier a échoué alors que le runtime avait atteint READY.
Les corrections ont été suivies de nouvelles exécutions ; aucun échec inchangé
n'a été converti artificiellement en succès. La normalisation LF de la matrice
et les corrections documentaires sont des commits distincts.

Instance disponible : **i3-final**, environnement généré **Instance-i3-final**.
L'ancienne demo est arrêtée, volumes préservés ; i3-bruno et i3-workshop sont
également arrêtées avec leurs preuves conservées. Le reset n'a visé que les
fixtures nouvellement créées par cette mission. Aucun tag et aucune fusion
main. La publication demandée utilise un push normal de la branche dédiée.

Pour essayer la livraison :

~~~sh
./pocoma-local start --instance i3-final
./pocoma-local gui --instance i3-final
./pocoma-local bruno --instance i3-final
~~~

Dans Bruno : Open Collection → scripts/bruno-playground → Instance-i3-final ;
les deux connexions du dossier00, puis clic droit J01 → Run → Collection Runner
→ Run. Les mots de passe actuels se lisent dans le fichier privé de cette
instance ; ne jamais publier le fichier ni recopier de JWT.
