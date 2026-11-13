# Playground — Bruno GUI 4.2.1 et CLI 4.3.0

Le correctif GUI est décrit dans le [rapport dédié](L7_Local_Playground_GUI_Auth_Fix_Report.md).
Le jalon reste **LOCAL_PLAYGROUND_PARTIAL** jusqu'à validation humaine. La
collection est `scripts/bruno-playground`, distincte des corpus historiques.

## Préparer les URLs de l'instance

Pour une instance déjà démarrée :

```sh
./pocoma-local gui --instance demo
```

Dans un checkout neuf, démarrer d'abord sa nouvelle instance avec
`./pocoma-local start --instance demo`. La préparation GUI n'effectue aucun
reset, migration ou redémarrage et conserve la garde de compatibilité de start.
Le correctif peut donc se préparer sur l'instance existante déjà en service.

La commande écrit `environments/Instance-demo.bru` dans la collection. Ce fichier
local ignoré de Git contient **uniquement** `writeUrl`, `readUrl`, `issuerUrl`,
issus du manifeste possédé et de la discovery réelle. Dans Bruno, ouvrir
`scripts/bruno-playground`, puis choisir **Instance-demo** dans le sélecteur
d'environnement. `Local` reste l'environnement de la CLI, dont les URLs sont
injectées par la façade ; ne pas supposer les ports8080/8081/8082 dans le GUI.
`./pocoma-local login` est désormais un alias de cette préparation : il n'exporte
plus de JWT et ne revendique pas une authentification déjà accomplie.

## Se connecter dans Bruno, sans copier de JWT

Utiliser le navigateur **intégré** de Bruno pour ce parcours local. Dans les
préférences OAuth, garder « Use system browser for OAuth » désactivé. Les flux
système/custom-scheme ne sont pas qualifiés par ce guide. Le sandbox Developer
est requis par les helpers locaux déjà utilisés par PG01 ; ne pas désactiver
la vérification TLS, modifier PKCE ou élargir les permissions du client.

1. Ouvrir **00 Login Owner**, onglet **Auth**. La configuration livrée affiche
   OAuth2 / Authorization Code, client `pocoma-functional-tests`, scope `openid`,
   **Use PKCE activé**, Token ID `playground-owner`. Cliquer **Get Access Token**
   et se connecter dans la fenêtre Keycloak avec le compte synthétique **third**.
2. Ouvrir **01 Login Guest**, onglet **Auth**, cliquer **Get Access Token** et se
   connecter avec **guest**. Son Token ID est **playground-guest**. `prompt=login`
   force un nouveau formulaire pour éviter la réutilisation silencieuse du compte
   propriétaire. Le login_hint suggère le compte mais ne remplace pas la saisie.
3. Les mots de passe existants sont dans
   `.pocoma-local/instances/demo/secrets.json`, champ `users`. Les saisir
   uniquement dans le formulaire de cette instance Keycloak locale. Aucun
   client secret n'est nécessaire et aucun JWT ne doit être copié dans Bruno.
4. Ouvrir **PG01 Public Journey** et cliquer **Send**. Le panneau Tests doit
   afficher le test **PG01 Registration binding Pot invitation mutations balance
   delete history** réussi. Les demandes de connexion ont leurs propres tests
   simples ; elles ne constituent pas à elles seules la preuve métier PG01.

Bruno stocke les sessions dans son cache OAuth natif ; les scripts les lisent
via `bru.getOauth2CredentialVar('$oauth2.<Token ID>.access_token')`. Ils ne les
copient ni dans les environnements `.bru`, ni dans les runtime variables, ni
dans les logs. Ne pas partager/exporter le cache OAuth, les timelines/debugs
OAuth ou les bodies des réponses Binding/invitation. Aucun `GUI-private.bru`
n'est à importer : l'ancienne procédure de copie manuelle de tokens est retirée.

La callback exacte autorisée est `http://127.0.0.1:8765/callback`. Le navigateur
intégré intercepte la redirection ; **aucun serveur Python n'a à écouter ce port**
pour l'auth native Bruno. Laisser le champ State vide : Bruno4.2.1 génère un
state aléatoire et vérifie son retour. PKCE envoie un challenge S256 et échange
le code avec le verifier. Le realm conserve publicClient=true,
directAccessGrantsEnabled=false et son unique redirect exacte. Pas de password
grant, wildcard redirect, secret partagé ou allongement de durée des tokens.

## Expiration et diagnostics

| Symptôme | Action |
|---|---|
| `Session owner ... 00 Login Owner` | Auth > Get Access Token sur la connexion propriétaire, puis relancer PG01 |
| `Session guest ... 01 Login Guest` | Auth > Get Access Token sur la connexion invité |
| `Deux identites distinctes requises` | Guest : Auth > Clear Cache, puis Get Access Token et compte guest ; pas third |
| Mauvais issuer après changement d'instance | Choisir le bon environnement, Clear Cache/Get Access Token sur les deux connexions |
| Erreur de discovery ou de transport | Vérifier l'instance et ses URLs avec status/gui ; ne pas remplacer le JWT ni désactiver TLS/PKCE |
| Pre-request inconnu après mise à jour des fichiers | Recharger la collection dans Bruno ; conserver les changements non sauvegardés avant de fermer l'application |

Les deux connexions doivent être obtenues **avant PG01** : l'auto-fetch natif
Bruno s'exécute après le pre-request, alors que PG01 doit disposer des deux
identités avant ses requêtes internes. Si un token expire, PG01 refuse avec
l'action à effectuer ; renouveler via les connexions natives, sans durée étendue.
Le contrôle de claims côté script est un garde de cache/ergonomie, pas une
validation de signature : les API restent l'autorité AuthN/AuthZ/binding.

## CLI automatisée préservée

```sh
./pocoma-local bruno --instance demo
```

CLI verrouillée4.3.0, Node24. Le lanceur authentifie third et guest auprès du
vrai Keycloak par formulaire + Authorization Code + PKCE S256, puis transmet
les sessions au seul processus Bruno par environnement. La CLI sélectionne
explicitement **PG01 Public Journey.bru**, afin de ne pas ouvrir les connexions
natives GUI. Aucune dépendance à un cache GUI ou à une session humaine préalable.
La variante `./pocoma-local bruno --instance demo --interactive` conserve son
login propriétaire interactif via le helper Python, puis lance cette même CLI.

L'inventaire attendu est PG01 exactement une fois et un JUnit frais avec1 test
nommé PASS, zéro failure/error/skip. Une collection vide ou une session absente
ne peut pas produire BRUNO_PASS. Les reçus contiennent méthodes/routes/statuts,
jamais les bodies ou les tokens ; aucun reporter JSON de réponses n'est activé.
Ne pas imprimer l'environnement de processus ou lancer le shell avec tracing.

## Parcours et attentes

PG01 exerce Registration/résultat/binding, CreatePot et replay idempotent,
lecture V1, deux actionnaires, refus d'accès du tiers avant invitation,
création/récupération confidentielle de l'invitation, consultation des places,
acceptation et visibilité guest, dépense10/3 puis correction20/3, balances
indépendantes ±5/3 puis ±10/3, histoire exacteV4 et batch Expense référencé,
UpdatePotDetails, Delete terminalV7, courant/V7 en404 et histoireV6 conservée.
Chaque réponse est no-store ; l'absence de bearer donne401. Les autres opérations
sont décrites au [contrat consommateur](L7_Local_Playground_Public_API.md).

Source officielle du comportement natif : [Bruno Authorization Code](https://docs.usebruno.com/auth/oauth2-2.0/authorization-code),
complétée par l'inspection des modules réellement installés4.2.1 identifiés dans
les preuves. La documentation courante ne remplace pas ce contrôle de version.
