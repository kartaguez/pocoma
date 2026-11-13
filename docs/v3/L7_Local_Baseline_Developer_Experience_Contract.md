# L7-LB — Contrat proposé de l'expérience développeur locale

13 novembre 2026 · [cadrage](L7_Local_Baseline_Scoping_Report.md).
**TARGET / NON IMPLÉMENTÉ** : les commandes `./pocoma-local` ci-dessous sont une
interface à construire. Elles ne sont pas des instructions actuellement exécutables.
D1–D5 du mandat humain sont acquis.

## 1. Modèle opérationnel et données

Une seule interface à la racine orchestre les capacités existantes. Elle possède
un état local hors Git, un manifeste d'instance, un verrou exclusif et des
répertoires de preuves distincts. Proposition : `.pocoma-local/` ignoré de Git,
avec `instances/<id>/`, `runs/<run-id>/`, `private/` protégé ; l'implémentation
fixera les chemins et vérifiera symlinks, permissions et cibles physiques.
L'absence de manifeste interdit la réutilisation ou suppression d'un volume
préexistant. Aucun import automatique de la pile `pocoma-functional`.

| Mode | État et identité | Usage et frontières |
|---|---|---|
| demo (défaut start) | Projet/volumes/realm persistants propres à l'instance, jeu demo versionné | Développeur et Bruno GUI ; pas de suites destructives, ni benchmark sur ces bases |
| qualification | Nouveau projet/volume/realm et run-id pour chaque campagne ; fixtures propres aux scénarios | `verify` ; injections admises seulement si identifiées comme fixtures, jamais substituées à une admission publique dans une preuve E2E |
| performance | Projet/volume/realm de campagne distincts, snapshot/seed qualifié, mesures indépendantes | `bench` ; aucune modification d'échéance/claim/checkpoint, purge de slot ou terminalisation artificielle |

Les trois modes ne partagent **aucun stockage mutable** : ni cluster PG/volume,
ni realm, ni comptes, ni base de métriques. Ils peuvent partager des images
immuables et caches de build hors qualification à froid. Changer seulement le
nom de base ou le `-p` du Compose actuel ne suffit pas : container_name, volume
explicite, IPAM et issuer restent fixes. Il faut les paramétrer et contrôler
les labels de propriété, réseau, URL JDBC et identité des bases à chaque action.

Sur 16 Go, les stacks de qualification/performance passent **successivement**.
Si demo est active, `verify`/`bench` refuse avant effet avec diagnostic et
commande `stop` proposée ; pas d'arrêt automatique ni d'attente silencieuse.
Les volumes demo restent conservés. Un futur profil concurrent demande une
preuve explicite de ressources et d'isolation ; il n'est pas la valeur par défaut.

Jeu demo proposé : deux identités synthétiques, un Pot actif avec parts/dépenses
et soldes rationnels connus, un Pot supprimé avec histoire consultable, une
invitation obtenue par parcours réel. Identifiants métier dérivés d'un seed
versionné, dates fixes, intentions et clés d'idempotence stables. L'EUI et les
secrets d'invitation/binding proviennent du vrai Keycloak/Pocoma ; comparer un
manifeste sémantique (participants, versions, soldes), sans exiger des tokens,
timestamps ou sels cryptographiques déterministes. État attendu connu après
reset ; réapplication du seed sans nouvelle mutation si état initial intact.
Si l'utilisateur a muté ce jeu, `start` ne l'écrase pas : état modifié signalé,
reset explicite nécessaire pour retrouver le jeu canonique.

Le smoke de start possède ses propres objets techniques de démonstration,
distincts des objets de parcours. Premier démarrage : seed par API/moteur puis
contrôle complet ; redémarrage : nouvelle mutation canari sur un Pot technique
fixe (par exemple un libellé distinct par run-id), puis résultat et READ à sa
nouvelle version. La clé est stable pour reprendre la même invocation, mais
distincte entre deux start : un ancien résultat ne prouve pas la progression
actuelle des workers. Pas de nouveau Pot à chaque start ; les versions du canari
croissent de façon déclarée, hors jeu demo canonique. Aucune injection READ pour
faire réussir un smoke de pipeline. L'implémentation devra prévoir le cas
où une ancienne EUI a été Unlink : ne pas réutiliser un ancien binding ni
annoncer READY sur la seule lecture d'un ancien résultat.

## 2. Commandes et codes de sortie

Codes proposés communs : `0` action réussie ; `1` contrôle/incident réel ou
NOT_READY ; `2` usage, précondition ou cible non autorisée ; `3` verrou/conflit
d'instance ; `130` interruption contrôlée. Une campagne non concluante ou NOT_RUN
requise sort non zéro (`1`) avec verdict détaillé, jamais succès de certification.
Les codes sont un contrat cible à vérifier, pas le comportement des anciens scripts.

| Commande | Préconditions et actions/contrôles | Données et idempotence | Artefacts, échecs et protection |
|---|---|---|---|
| `start` | Git/ref et toolchain déclarés ; Docker disponible, ports/réseau/disque/mémoire contrôlés ; récupérer/construire images du même SHA, PG/IdP prêts, bootstrap unique, grants validés, cinq JVM, observabilité, seed puis smoke | Initialise seulement l'instance neuve possédée ; conserve demo existante compatible ; nouveau start réutilise l'instance et revalide, sans reset ni qualification exhaustive | READY/NOT_READY + JSON ; contrôle manquant identifié ; `1` au défaut, `2` drift incompatible/cible étrangère, `3` concurrence. Préserver environnement et diagnostics en échec |
| `status` | Lire manifeste puis observations PG/IdP/JVM/maintenance/metrics ; pas de migration/build/admission | Aucune mutation métier ; idempotent sur état stable ; contrôle de l'âge du dernier smoke | `0` READY courant (smoke encore attribuable et contrôles courants valides), `1` STOPPED/NOT_READY/UNKNOWN ; `2` propriété inconnue. Rapport par contrôle, pas ancien verdict vert recopié |
| `stop` | Manifeste/propriété concordants ; drainage borné puis arrêt ordonné des cinq JVM et auxiliaires possédés ; enregistrer slots/leases/arrêt | Volumes et preuves conservés ; réexécution sur déjà arrêté = `0` ; aucune suppression | Journal durée/état/PID ; `1` drainage/arrêt échoué, `2` cible étrangère. Si SIGKILL nécessaire, tracer le recours et l'état incomplet ; ne pas purger le travail |
| `reset --instance <id> --confirm RESET:<id>` | Deux facteurs : ID exact et confirmation exacte ; verrou ; prévisualiser ressources ; vérifier labels/UUID/chemins/DB/loopback ; arrêter seulement instance concernée, remplacer son état complet puis appliquer le pipeline bootstrap/seed/smoke de start | Destruction explicite du seul état demo sélectionné (WRITE+READ+realm) ; preuves préservées ; reset demandé de nouveau ramène au même état sémantique canonique, nouveau run-id technique distinct | Plan avant/après + hashes ; `0` seulement si état initial reconstitué READY ; refus (`2`) si mismatch, partagé, label absent ou symlink ; aucune commande globale prune/down sur projet implicite ; état partiel devient NOT_READY (`1`) sans réutilisation aveugle |
| `verify --profile <nom>` | Demo arrêtée ; plan des contrôles et slices avant effet, projet qualification neuf ; bootstrap, tests déclarés, Bruno automatique, sécurité/reprise selon profil | Peut détruire seulement ses fixtures identifiées ; jamais données demo ; un nouveau run, aucun écrasement d'artefact ; garde l'environnement au défaut | Inventaire attendu/exécuté, XML/HTTP sans secrets, SQL physique, reçus ; `1` FAIL/PARTIAL/INCONCLUSIVE/NOT_RUN requis ; `2` précondition ; aucun cleanup automatique en échec |
| `bench --profile <nom> --seed <id>` | Demo arrêtée ; profil local préenregistré, offre et observateurs qualifiés, images/hash/config fixés ; seed, warmup, mesure, drainage et rapprochement | Nouveau contexte par répétition ; aucun endpoint d'administration caché ni reset de checkpoint ; l'état final est archivé | Bruts/client/SQL/metrics/ressources et limites ; `1` échec/mesure invalide ; `2` mauvais profil/cible ; arrêt de charge sur invariant/ressource dangereuse, services conservés |

Compléments nécessaires sans nouvelle façade métier : `verify --plan` (lecture
du scope sans campagne), `reset --dry-run` (plan sans effet), export diagnostic
et nettoyage **explicite par run-id** des environnements jetables après analyse.
Pas de reconstruction READ implicite dans reset/start : la maintenance existante
reste une opération distincte derrière son autorité, confirmation et oracle.
Un changement de SHA/config/migration ne relance pas automatiquement une mise
à niveau destructive : compatibilité contrôlée ou refus et procédure documentée.

## 3. Readiness réelle et diagnostic

READY global est un résultat opérationnel daté et attribuable à un manifeste,
pas une propriété déduite du nombre de conteneurs UP. Séquence cible :

| ID de contrôle | Oracle obligatoire | Exemple de NOT_READY précis |
|---|---|---|
| PRE-* | Toolchain, hôte/architecture, ports, projet/volumes, mémoire/disque, verrou | `PRE-PORT-8082`, `PRE-FOREIGN-VOLUME`, `PRE-ARCH-MISMATCH` |
| DB-* | Deux bases/schémas attendus, manifeste complet de DDL/checksums/contraintes/grants/routage ; principaux runtime réels et refus d'accès indu | `DB-WRITE-SCHEMA-INCOMPLETE`, pas simple to_regclass |
| IDP-* | Endpoint découverte/JWKS et issuer exact ; auth réelle d'une identité synthétique PKCE S256, audience/claims ; JWT sans preuve refusé | `IDP-PKCE-CALLBACK`, `IDP-AUDIENCE` ; ne pas désactiver AuthN |
| JVM-* | Cinq processus exacts, health et état Worker/participants/pools attendus ; DB réellement accessible par leurs rôles | `JVM-BUSINESS-READ-INACTIVE`, même si Actuator 200 |
| READ-* | Maintenance utilisable, pas FAILED/reconstruction en cours ; checkpoints/catalogue cohérents avec bornes de smoke | `READ-MAINTENANCE`, `READ-BINDING-LAG` |
| SMOKE-* | Registration → résultat → binding → `/me/pots` ; CreatePot → résultat → Pot complet cohérent et balance ; refus sans bearer/tiers | `SMOKE-COMMAND-RESULT`, `SMOKE-POT-CONTENT` ; ni 202 seul ni 404 seul ne suffisent |
| OBS-* | Scrape des cinq rôles et métriques indispensables présentes ; source indisponible = inconnue | `OBS-MISSING-RESULTS-WORKER`, jamais métrique absente convertie en zéro |

Le drainage total des slots n'est pas requis à chaque `status` sur données
interactives en mouvement. La readiness du smoke rapproche ses demandes et ses
bornes ; une campagne de reconstruction applique son oracle plus strict à la
barrière B. Ne pas copier un nombre global de checkpoints sans comparer le
catalogue actif (4 partitions, 7 participants READ actuellement).

Délais séparés build/bootstrap/IdP/JVM/smoke, mesurés par horloge monotone.
Au dépassement : NOT_READY, contrôle exact, dernière observation, code non nul.
Pas d'augmentation automatique de délai ni retry métier non déclaré. Un start
interrompu conserve son manifeste « incomplet » et un reçu ; reprise sous
verrou après vérification de chaque étape déjà effectuée.

Chaque action écrit `plan.json`, `receipt.json`, logs par rôle, diagnostics
réseau/PG/health, SHA, dirty state, configuration publique, versions et durées.
Champs de reçu : run-id, instance-id, mode, startedAt/endedAt, contrôles
attendus/exécutés, statut/durée/message par contrôle, verdict et exitCode.
Journal de console concis avec chemin des preuves et URLs non sensibles.
Ne jamais afficher bearer, refresh token, code OIDC, bindingToken ou invitation.
Secrets hors Git, permissions restrictives ; reporter Bruno sans headers/corps,
recherche de fuite avant archivage et conservation des diagnostics expurgés.

## 4. Bruno et Keycloak réels

Réutiliser [realm](../../docker/keycloak/pocoma-realm.json),
[helper d'authentification](../../scripts/functional-auth-proof.py),
[recette vierge](../../scripts/bruno-v3/qualify-installation.py) et bibliothèques
[B2](../../scripts/bruno-v3/lib/i2-admission.js)/[B4](../../scripts/bruno-v3/lib/i4-invitation.js).
Le client public a Standard Flow, PKCE S256, Direct Access Grants et service
accounts désactivés. Les claims `iss/sub/iat/exp/auth_time`, audience
`pocoma-api` et permissions demandées restent contrôlés par le Resource Server.
`bindingToken` et access token OIDC ont des fonctions distinctes ; Unlink
invalide le binding matérialisé, un nouveau JWT ne recrée pas ce binding.

| Usage | Solution à qualifier en I3 | Preuve attendue |
|---|---|---|
| Bruno interactif | Environnement local avec WRITE/READ/issuer/client/callback, variables non secrètes exportées depuis manifeste ; flux navigateur Authorization Code PKCE S256 si la version Bruno verrouillée le permet ; sinon helper local ouvrant navigateur et callback loopback, session privée importée par mécanisme local documenté | Développeur ouvre la collection, login et consentement réels, obtient token en stockage privé ; pas de formulaire de mot de passe injecté dans une variable Git. Vérifier comportement GUI, state, callback et nonce si utilisé |
| Bruno automatisé | Réutiliser l'automate PKCE du banc, comptes synthétiques du realm isolé ; tokens transmis par environnement/fichier privé éphémère ; validation state déjà existante ; contrôler claims/durée et renouvellement explicite | B2 puis B4 puis installation, inventaire exact de scénarios et requêtes, assertions et sortie CLI ; test négatif mauvais issuer/audience/signature/expiré, sans aucun fallback password grant |
| Expiration/reprise | Re-login ou refresh si configuration et helper le qualifient ; pas de login à chaque commande ; ancien binding traité selon contrat | Aucun token en argv/log/URL métier, interruption propre et reprise idempotente après auth |

Le CLI reste verrouillé par `package-lock.json` (4.3.0 actuellement). L'usage
récursif `-r` et le rejet de zéro requête sont indispensables. La GUI doit
être qualifiée explicitement ; le succès CLI ne suffit pas. Les groupes B1
à READ injecté restent un profil de fixture ; les parcours demo s'appuient
sur de vraies admissions. B3 Link historique et routes retirées restent retirés.
La suite `05 Observability Demo` n'est pas directement réutilisable comme gate :
le runner courant passe des secrets en argv, peut ignorer l'absence/échec Bruno
et utilise un seul baseUrl. Ces points doivent être consolidés avant intégration.

## 5. Paquet de contrat public pour un BFF ou client tiers

Source effective : [contrôleur admission](../../app/supra-web-write/src/main/java/com/kartaguez/pocoma/supra/web/write/v3/V3AdmissionController.java),
[contrôleur READ](../../app/supra-web-read/src/main/java/com/kartaguez/pocoma/supra/web/read/v3/V3ReadController.java),
[DTO](../../app/supra-web-read/src/main/java/com/kartaguez/pocoma/supra/web/read/v3/V3ReadDtos.java),
[invitations](../../app/supra-web-read/src/main/java/com/kartaguez/pocoma/supra/web/read/v3/V3InvitationController.java),
[sécurité](../../app/infra-security-spring/src/main/java/com/kartaguez/pocoma/infra/security/spring/v3/V3HttpSecurityConfiguration.java).
Cette liste est un inventaire statique, pas une nouvelle validation HTTP.

| Surface `/api/v3` | Hôte logique et rôle |
|---|---|
| `POST /commands`, `POST /identity/requests` | WRITE ; admission durable puis 202 et requestId |
| `GET /commands/{requestId}/result`, `GET /identity/requests/{requestId}/result` | READ ; propriétaire EUI historique, sans binding exigé pour consulter son résultat |
| `GET /me/binding`, `GET /me/pots` | READ ; binding courant puis navigation paginée |
| `GET /pots/{potId}`, `GET /pots/{potId}/versions/{version}` | READ ; courant avec minVersion ou histoire exacte |
| `GET /pots/{potId}/versions/{version}/balance` | READ ; balance à version explicite |
| `POST /pots/{potId}/versions/{version}/expenses/query` | READ ; détails des références du manifeste, lots bornés |
| `GET /invitations/by-creation-request/{requestId}`, `POST /invitations/available-shareholders/query` | READ ; restitution et sélection sous règles de confidentialité |

À livrer en I3, dans une référence unique OpenAPI **ou documentation équivalente** :

- Schémas précis et exemples expurgés des onze opérations Pot, Registration,
  Unlink et invitation ; enums, champs obligatoires/interdits, résultats
  COMPLETED/UNCHANGED/REJECTED selon opération. Aucun Link public réintroduit.
- Deux base URLs sans dépendance aux modules internes ; Location relative des
  admissions à résoudre vers le service READ pour le polling. Idempotency-Key
  unique par intention, scope EUI, replay identique et conflit 409 ; ne pas
  resoumettre une nouvelle clé à chaque timeout d'observation.
- JWT réel, permissions/capacités et `X-Pocoma-Binding-Token` sur lectures
  concernées ; binding dans le corps des commandes. Résultats historiques par
  EUI, refus 404 indiscernable et révocation READ asynchrone explicitée.
- Erreurs `{code,message,traceId}`, 400/401/404/409/413/415/503 par endpoint ;
  410 seulement là où le contrat concerné le prévoit. Pas d'ancienne règle
  générique « projection absente = 409 ». Les bornes corps/sortie viennent du
  profil réel ; les 4 Mio du guide B1 et 16 000 000 du Compose courant demandent
  réconciliation documentaire, sans inventer une limite métier.
- Versions positives, UUID canoniques, rationnels `{n,d}` en chaînes sans
  conversion flottante ; références Expense exactes, batch 1–50, navigation
  défaut 20/max 100 et curseur opaque nextCursor (encodage versionné interne).
- 202 ≠ effet, résultat disponible ≠ Pot prêt, 404 résultat peut être provisoire,
  503 après autorisation peut indiquer retard/maintenance. Polling borné avec
  backoff et budget publiés, refus déterminant distinct du timeout.
- Courant : première lecture complète W≥V à minVersion=V, balance cohérente à W ;
  histoire : exacte V. DeletePot : courant et D en 404, histoire V<D conservée ;
  aucune déduction de complétion à partir de 404 seul. Visibilité/navigation
  indépendantes de la disponibilité de lecture directe du Pot.
- Version d'API `/api/v3` et politique de compatibilité du tag local ; un tag
  n'engage pas une stabilité illimitée des futures versions. CORS/proxy pour
  client navigateur et stockage tokens à documenter si annoncé ; BFF serveur
  ne doit pas nécessiter d'accès SQL/JWKS privé ou endpoint de contrôle interne.

Critère Q9 : un consommateur suivant uniquement ce paquet et les URLs publiques
réalise login → Registration → création → résultat → consultation → invitation
→ mutations → suppression/histoire. Aucun nom de table ni connaissance du
découpage interne requis ; sondes SQL restent des moyens de qualification.
