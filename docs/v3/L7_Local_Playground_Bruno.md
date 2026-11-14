# Pocoma V3 Playground — Bruno GUI 4.2.1 et CLI 4.3.0

Ouvrir **scripts/bruno-playground**, collection **Pocoma V3 Playground**.
Elle contient 171 requêtes visibles : 51 par domaine, 2 connexions OAuth et
118 étapes réparties en trois parcours. Les collections scripts/bruno-v3
et scripts/bruno servent aux qualifications spécialisées et à l'historique.
[Couverture des 25 opérations métier / 12 routes](L7_Local_Playground_Bruno_API_Coverage.csv).

## Démarrer et choisir l'environnement

Depuis un checkout neuf, avec Java21, Node24 et Docker disponibles :

~~~sh
./pocoma-local start
./pocoma-local gui
~~~

Dans Bruno, **Open Collection** → dossier scripts/bruno-playground, puis le
sélecteur en haut à droite → **Instance-demo**. La commande gui prépare uniquement
les URLs publiques ; elle ne connecte aucun compte et ne modifie aucune donnée.
**Local** est réservé à la CLI, qui injecte ses URLs.

Pour une instance nommée, ajouter --instance i3-bruno aux commandes
et choisir **Instance-i3-bruno**. Si une ancienne installation refuse
PRE-INPUT-DRIFT, conserver ses volumes et utiliser une nouvelle instance
après arrêt explicite de l'ancienne ; ou effectuer le reset décrit plus bas.
Ne pas contourner ce garde.

## Authentifier les deux identités

1. **00 - Getting Started / E00 Environment Check → Send** : réponse200,
   tests **E00 transport and privacy** et **E00 contract and variables**.
2. **00 Login Owner → Auth → Get Access Token**. Dans la fenêtre Keycloak,
   compte **third**, mot de passe du champ users.third dans le fichier privé
   .pocoma-local/instances/<instance>/secrets.json.
3. **01 Login Guest → Auth → Get Access Token**. Compte **guest**, mot de passe
   users.guest du même fichier. prompt=login demande une connexion distincte.
4. Revenir à Bruno. Les connexions ont les Token IDs distincts
   **playground-owner** et **playground-guest**. Leur bouton **Send** consulte
   le binding propre (200 ou 404) ; il ne fait pas d'inscription.

Conserver **Use system browser for OAuth** décoché pour utiliser le navigateur
intégré. **Grant Type = Authorization Code**, **Use PKCE** coché, scope openid,
client public pocoma-functional-tests, Client Secret vide, State vide
(Bruno génère et vérifie son état aléatoire). Callback exacte
http://127.0.0.1:8765/callback, interceptée par Bruno : aucun serveur Python
supplémentaire n'est requis.

Les sessions restent dans le cache OAuth natif. Aucune copie/importation de
JWT n'est nécessaire. Les requêtes métier lisent ce cache, vérifient
fraîcheur/issuer/audience/client et utilisent le bearer seulement en header.
Les API valident la signature et les autorisations réelles. openid ne fournit
pas preferred_username dans ce realm : les sujets séparent les sessions.
Owner et Guest désignent ici les deux contextes de développement ; ce ne sont
pas des rôles OAuth ajoutés au produit.

Dans la barre de collection, bouton **</> / JavaScript Sandbox** →\n**Developer Mode**. Ce mode est nécessaire aux modules JavaScript locaux,
comme dans la collection précédente. Garder TLS et les protections PKCE/state
actifs. Ne pas exporter cache OAuth, timelines, réponses Binding/invitation
ou variables runtime sensibles.

## Lancer un parcours

Dans **90 - End-to-End Journeys**, clic droit sur le dossier choisi →
**Run** ; dans **Collection Runner**, vérifier la sélection, puis **Run**. Ne pas lancer toute
la collection : les dossiers par domaine sont un atelier, pas une séquence
globale. Authentifier les deux comptes avant de lancer un parcours.

| Dossier | Étapes | Résultat attendu |
| --- | ---: | --- |
| **J01 Complete Ledger and Invitation** | 66 | Inscription/binding, Pot, invitation Guest, toutes les commandes, dépense10/3 puis20/3, soldes exacts ±5/3 puis ±10/3, histoire, départ volontaire, suppressions |
| **J02 Errors Privacy and Idempotency** | 31 | Replay même requestId, conflit409, pagination, 401/400/404 masqué, refus métier FORBIDDEN et INVALID_INVITATION |
| **J03 Unlink Re-register and Historical Ownership** | 21 | Unlink de Guest, binding inactif, nouvelle inscription, ancien binding INVALID_BINDING, ancien résultat toujours propre au même requester |

Les 118 étapes correspondent à **236 tests nommés**. Chaque admission montre
son vrai POST et son JSON ; l'étape résultat est un vrai GET séparé. Une
réponse202 signifie admission durable, jamais succès métier. Les GET
asynchrones répètent seulement **le même endpoint** dans leur pre-request
au plus30s, puis l'appel affiché doit vérifier le contrat attendu. Aucun
POST caché, accès SQL, fixture ou helper de qualification externe.

J01 et J02 nettoient leurs Pots. J03 supprime son Pot **avant** de désaffilier
Guest, puis vérifie le résultat historique. Les parcours génèrent de nouveaux
UUID et peuvent être rejoués ; une Registration déjà active est explicitement
vérifiée comme REJECTED / TARGET_ALREADY_BOUND. Les opérations des résultats
Identity se nomment REGISTRATION/UNLINK, leurs admissions Registration/Unlink.

## Explorer une requête isolée et garder son Pot

Après les connexions, envoyer dans cet ordre :

~~~text
01 - Identity and Registration
  I01 Register Owner → I02 Owner Identity Result → I03 Owner Binding
02 - Pots
  P01 Create Pot
05 - Results
  R01 Command Result
02 - Pots
  P04 Read Current Pot
~~~

Le Pot reste disponible. Modifier le JSON de **P02 Update Pot Details**,
**Send**, puis **R01 Command Result**, **P04 Read Current Pot**. Le dernier
résultat actualise potVersion. Une modification de payload reçoit une nouvelle
clé d'idempotence ; **A04 Replay Same Intention** reprend explicitement
la dernière intention mémorisée avec son ancienne clé.

Pour inviter : préparer Guest avec **I04 → I05 → I06**, ajouter les places
avec **S01 → R01 → P04**, puis **V01 → R01 → V02 → V03 → V04 → R01**.
**V02 Retrieve Private Invitation** conserve le secret en mémoire ; il n'est
jamais placé dans une URL d'API. **V03** ne révèle que les places éligibles.
**S05 Guest Voluntary Departure DETACH** libère sa place ; après **R01**,
Guest perd l'accès courant et conserve l'accès historique autorisé.

Pour modifier les poids ou les dépenses, lire l'onglet **Docs** de la requête
(via le chevron des onglets si masqué). Chaque requête indique ses variables
prérequises, son identité et le résultat attendu. Modifier corps et chemin
est permis. Pour forcer une variable d'exploration, ajouter avant l’appel prepare
dans **Script / Pre Request**, par exemple :

~~~js
bru.setVar('historyVersion', 4);
~~~

Les références Expense sont recopiées automatiquement de P04/H01 :
ne pas reconstruire ces objets opaques.

| Variables | Nature / alimentation |
| --- | --- |
| writeUrl, readUrl, issuerUrl, managementReadUrl | URLs publiques d'environnement, préparées par gui |
| actor | Paramètre public : owner par défaut, guest possible dans l'environnement pour les requêtes par domaine marquées actor ; les parcours fixent leurs identités |
| pageLimit, invitationTtlSeconds | Réglages initiaux runtime,20 et600 ; modifiables dans le pre-request ou dans l'environnement avant une nouvelle session |
| potId, shareholderOwnerId, shareholderGuestId, expenseId | UUID de scénario ; renouvelés par P01 |
| commandRequestId, identityOwnerRequestId, identityGuestRequestId, invitationRequestId | Capturés des admissions ; R01 choisit l'identité qui a admis la commande |
| potVersion, readVersion, historyVersion, deletedVersion, expenseReferences, nextCursor | État de scénario en mémoire ; résultat, Pot, histoire et navigation alimentent les étapes suivantes |
| bindingOwner, bindingGuest, bindingActor, staleGuestBinding, invitationToken, replayAdmission | **Sensibles**, mémoire runtime seulement ; aucune sauvegarde d'environnement |
| Sessions Owner/Guest | **Sensibles**, cache OAuth natif GUI ou environnement du seul processus CLI ; jamais des variables bearer à créer |

Une requête Owner n'exige pas de session Guest. Les deux sujets utilisés
doivent être distincts. Un changement d'issuer vide le contexte runtime ;
une session d'une autre instance est refusée avec la connexion à renouveler.
Aucune variable bearerOwner n'est attendue.

## Réinitialiser et reprendre

Pour supprimer les données d'une instance **explicitement choisie** :

~~~sh
./pocoma-local reset --instance demo --dry-run --confirm RESET:demo
./pocoma-local reset --instance demo --confirm RESET:demo
./pocoma-local gui --instance demo
~~~

Le reset détruit seulement son volume possédé et recrée comptes/données
synthétiques ; les mots de passe peuvent changer. Sélectionner à nouveau
l'environnement, puis sur **les deux connexions**, **Auth → Clear Cache →
Get Access Token** avec les nouveaux mots de passe. Relancer J01.
Les UUID/bindings/invitations d'avant reset ne sont pas réutilisables.

## CLI et diagnostic

~~~sh
./pocoma-local bruno --instance demo
python3 -B scripts/local/sync_bruno_playground.py --check
~~~

La CLI4.3.0 exécute **90 - End-to-End Journeys** récursivement, les mêmes
fichiers que le GUI. Le lanceur obtient les sessions au vrai Keycloak par
formulaire HTTP + Authorization Code/PKCE S256 ; aucun password grant ni
cache GUI nécessaire. --interactive conserve le login navigateur du helper
CLI pour Owner ; ce n'est pas la connexion native GUI.

BRUNO_PASS exige exactement les118 requêtes, les236 identités de tests,
des assertions non nulles, zéro erreur/échec/skip, des routes/méthodes/statuts
conformes et des fichiers de preuve frais. Les reçus comptent aussi les
polls physiques. La console CLI est écartée pour éviter les bodies sensibles
en cas d'échec ; JUnit garde les assertions booléennes et les reçus seulement
méthodes/chemins/statuts/comptages. PASS CLI ne valide pas l'expérience GUI.

| Symptôme | Action |
| --- | --- |
| Session owner/guest absente, expirée ou invalide | Connexion nommée → Auth → Get Access Token ; revenir à la requête |
| Deux identités distinctes requises | Guest → Clear Cache → Get Access Token, compte guest |
| Prerequis absent | Lire Docs ; effectuer admission/résultat/binding indiqué ou choisir un objet existant |
| 404 résultat puis timeout | Conserver requestId, consulter status ; ne pas réadmettre aveuglément |
| REJECTED / OBSOLETE ou INVALID_BINDING | Relire binding/Pot ; comprendre le refus avant une nouvelle intention |
| B03 sans nextCursor | Fin de pagination normale ; pas de page suivante inexistante |
| Onglet Not Found après mise à jour | Ancienne requête PG01 retirée ; fermer cet onglet, ouvrir les nouveaux dossiers |
| Ports erronés / issuer différent | gui, bonne Instance puis nouvelles connexions |
| Réponse503 / timeout | status et diagnostics ; ne pas augmenter silencieusement les attentes |

## Recette humaine GUI encore requise

Consigner version4.2.1, instance et SHA, puis : ouverture/Instance, les deux
connexions natives terminées, un P02 modifié envoyé seul avec résultat/Pot,
J01/J02/J03 dans le Runner et tests affichés, passage Owner→Guest, reset
explicite puis J01. Ne partager ni JWT ni réponses sensibles. Reporter
PASS/FAIL pour chaque étape et des diagnostics expurgés.

Le [rapport I3](L7_Local_Playground_Bruno_I3_Rework_Report.md) distingue les
observations GUI effectives, les moteurs natifs sans rendu et l'acceptation
humaine ; aucun résultat de cette recette n'est présumé.
