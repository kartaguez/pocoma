# L7-LB — correction OAuth natif Bruno GUI4.2.1

14 novembre 2026, Europe/Paris. **LOCAL_PLAYGROUND_PARTIAL maintenu**.
Départ propre `f1ccd0dc1ab76d94eb0d73402e9b5178ebf771b6` ; branche
`feat/l7-lb-local-playground` ; SHA technique qualifié propre
**`e54cadd40b6595f017927a1ae4cccd48511af1fe`**.
[Plan préalable et adaptation de scope](L7_Local_Playground_GUI_Auth_Fix_Plan.md).

## Cause et correction

L'erreur a été reproduite dans Bruno GUI4.2.1 : `Local` ne définissait que les
URLs, mais le script de collection exigeait `bearerOwner` et `bearerGuest`
avant toute requête. Aucune configuration OAuth native n'était fournie.
La CLI réussissait parce que son lanceur injectait ces tokens par environnement.
Le guide demandait de recopier les JWT de `GUI-private.bru`, ce qui ne répondait
pas au parcours d'authentification attendu dans Bruno.

Le correctif livre :

- **00 Login Owner** et **01 Login Guest**, auth native Authorization Code,
  PKCE activé, state laissé vide pour génération aléatoire Bruno4.2.1, redirect
  exacte `http://127.0.0.1:8765/callback`, scope `openid`, client public existant.
  Token IDs distincts `playground-owner` et `playground-guest` ; `prompt=login`
  et login_hint third/guest pour éviter une réutilisation silencieuse du compte.
- Un script de collection qui prépare seulement les URLs. PG01 lit ensuite les
  deux tokens via **getOauth2CredentialVar**, contrôle fraîcheur, issuer,
  audience, client et sujets distincts, puis utilise les sessions en mémoire et
  dans les headers. Les API restent l'autorité de signature, AuthN/AuthZ/binding.
  Absence/expiration/mauvaise instance indique la connexion à rouvrir.
- **`./pocoma-local gui --instance demo`**, qui vérifie propriété/discovery et
  écrit l'environnement local `Instance-demo`, composé uniquement de trois URLs
  publiques. Aucun reset/migration/redémarrage/JWT/password lu ou exporté par
  cette commande. `login` devient son alias explicite, sans faux AUTHENTICATED.
  Les environnements générés sont ignorés de Git et exclus des entrées de build.
- La CLI sélectionne explicitement **PG01 Public Journey.bru**, injecte aussi
  l'issuer et conserve ses sessions Code+PKCE privées ; elle n'exécute pas les
  deux requêtes GUI. Aucun changement au mode `bruno --interactive`.

Le realm et ses restrictions sont inchangés : publicClient=true, grant password
interdit, S256 imposé, une redirect exacte, audience pocoma-api. Aucun secret
client, wildcard, nouvelle permission, désactivation TLS ou prolongation de token.
Le cache natif Bruno chiffre les credentials selon son implémentation installée ;
aucun token n'est recopié dans les fichiers d'environnement/runtime variables.
Le [guide courant](L7_Local_Playground_Bruno.md) retire la copie manuelle et donne
les étapes **gui → Instance-demo → Owner Auth/Get Access Token → Guest Auth/Get
Access Token → PG01 Send**, avec expiration et changement d'instance.

## Vérification sur SHA propre

[Preuves, hashes et limites](../testing/evidence/L7-LB-GUI-AUTH/README.md).
Empreinte des entrées opérationnelles qualifiées :
`87bb54dd3a0e9fb122a0655c5ff4aa79cf4afe0317b8f69cac431504e420c8b6`.

| Contrôle | Résultat |
|---|---|
| Unitaires locaux | **17/17 PASS** ; ajout préparation sans lecture de secrets/auth, contenu public seul et refus de symlink |
| Modules réellement installés Bruno4.2.1 | **23/23 PASS** :20 contrôles de cache/claims/identités/priorité CLI/parsing,2 sur la vraie VM de scripts,1 PG01 HTTP réel |
| Vraie VM4.2.1 | Scripts collection + pre-request PG01 exécutés ; header Authorization attendu ; variables runtime limitées aux trois URLs, aucun env persistant modifié ni log de session ; cache absent refusé avant transport avec action de connexion |
| OIDC réel | Keycloak Authorization Code+S256 avec vrai formulaire ; absence/plain PKCE refusés ; tokens transmis par environnement du seul processus de test puis lus par l'API de cache native4.2.1 |
| PG01 via modules4.2.1 | Parcours public complet PASS : Registration/binding, Pot et replay, invitation confidentielle/acceptation, dépenses, balances rationnelles exactes, suppression et histoire |
| CLI4.3.0 | **BRUNO_PASS**, inventaire PG01 exact, JUnit frais **1/1**, zéro failure/error/skip ; sélection de fichier explicite, sans connexion GUI |
| gui et alias login | GUI_CONFIGURED/code0, seules URLs publiques ; aucun JWT à copier, aucun ancien GUI-private.bru produit |
| Sources serveur | Diff vide sous app/ et docker/keycloak/pocoma-realm.json ; aucune modification SQL/POM/contrat/bail |
| Publication | Secrets connus et formes JWT recherchés dans les artefacts expurgés ; hashes SHA256 vérifiés. Entrées opérationnelles identiques entre e54cadd4 et les commits documentaires descendants |

**Limite de preuve explicite** : le test réel génère des sessions IdP par le
helper Code+PKCE, les charge **en mémoire** dans les variables de cache de la
classe Bru installée, puis exécute PG01 et ses appels HTTP réels. Il ne clique
pas Get Access Token et ne prétend pas avoir rendu la fenêtre d'authentification
native ni validé son ergonomie. Les fichiers OAuth sont parsés par le parser
installé4.2.1 et l'implémentation native S256/state/callback/cache a été examinée,
avec digest app.asar conservé. Les observations UI postérieures à la reproduction
initiale ont échoué : timeout d'accessibilité, puis AppleEvent macOS -1712.
Aucune fermeture forcée de l'application ni validation humaine n'est revendiquée.

## Commandes réellement exécutées

```sh
python3 -B -m unittest discover -s scripts/local -p 'test_*.py'
./pocoma-local gui --instance demo
./pocoma-local login --instance demo
python3 -B scripts/local/test_gui_auth_protocol.py --instance demo --output /tmp/pocoma-gui-e54cadd4-protocol.json
./pocoma-local bruno --instance demo
```

Le test protocole lance réellement, avec sessions en environnement privé :

```sh
ELECTRON_RUN_AS_NODE=1 /Applications/Bruno.app/Contents/MacOS/Bruno scripts/local/test_bruno_gui.js
```

Sans sessions de test, cette dernière commande exécute les22 contrôles de cache,
parsing et VM. Le23e contrôle public est ajouté par le wrapper Python. Aucune
commande Java/Maven ni campagne de performance n'a été exécutée sous ce correctif.
Les commandes de diagnostic/reprise sont distinctes dans le registre des essais.

## Incident d'environnement et frontières de preuve

Des premiers essais ont été conservés : le harness devait fournir le contexte
TLS/proxy normal à Bru.sendRequest ; ensuite les admissions sont restées PENDING
avec pertes de connexions et pauses de threads du worker. Une reprise des seuls
workers, sans purger de slots ou modifier de baux, a ramené les pending à0.
Plus tard tous les services ont été observés arrêtés, puis le catalogue Compose
vide et un cluster contenant seulement pocoma. **L'origine de cette disparition
n'est pas attribuée.** Aucune commande down/rm/prune/reset n'a été exécutée par
ce correctif. La continuité des données antérieures n'est pas certifiée sur
cette phase ; ce n'est pas une correction de sûreté du produit.

Après déclaration de l'adaptation de scope, le bootstrap **CURRENT V3 existant**
a initialisé les deux bases absentes sur le cluster possédé observé vierge,
puis les cibles DEV ont été régénérées sur son adresse réelle. Les catalogues
attendus/observés et les contrôles des cinq JVM sont archivés. Les résultats
finaux sont attribués à cette fixture reconstruite ; ils ne masquent pas les
observations de disponibilité échouées. Une investigation de cet incident reste
séparée du correctif GUI.

Slices déclarées : **LOCAL DISTRIBUTION / consommateur Bruno** ; slices Maven
modifiées **NONE**, WEB/COMMAND/PROJECTION exercés via API publiques. Impact app/
attendu/observé **NONE**. Franchissement inattendu de slice **NONE** ; adaptation
de preuve DB CURRENT explicitée avant initialisation. Gate Architecture **NOT
REQUIRED / NOT RUN**. Full reactor **NOT REQUIRED / NOT RUN** : aucun changement
de frontière/dépendance productive, aucune clôture globale L7/I6 ni intégration
main. Aucun historique V2, baseline TRUSTED ou raccourci de sécurité supposé.

## Livraison et validation restante

Fichiers : collection/PG01 et helper journey, nouveau helper session, deux
requêtes OAuth natives, environnement Local, inventaire/README, préparation
runtime et ignore du fichier public généré, tests Python/JS et guides/rapports.
Le commit technique e54cadd4 est suivi des seules preuves/documentations.
Publication sur feat/l7-lb-local-playground, sans force et sans tag ; SHA final,
propreté et égalité distante sont contrôlés dans le compte rendu de livraison.

Le développeur doit suivre le guide corrigé dans Bruno4.2.1, obtenir les deux
sessions dans l'application et confirmer le test PG01. **L'humain décide de la
validation**. Jusqu'à cette preuve et sa revue : LOCAL_PLAYGROUND_PARTIAL,
aucune promotion vers READY_FOR_HUMAN_ACCEPTANCE ou baseline V3. I1b/I4–I6 et les
réserves historiques demeurent ouvertes ; le nouvel incident d'environnement
reste identifié séparément, sans transformation automatique en défaut produit.
