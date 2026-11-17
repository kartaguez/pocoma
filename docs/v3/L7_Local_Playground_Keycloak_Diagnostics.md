# L7-LB — Keycloak et remise à zéro contrôlée

> Navigation produit : voir aussi [Démarrage rapide](../getting-started/quickstart.md) et [Diagnostic et résolution des incidents locaux](../operations/troubleshooting.md).

Depuis Git : Docker Desktop actif, JDK21, Node24 et Python≥3.11, puis
`./pocoma-local start --instance demo` et `./pocoma-local gui --instance demo`.
La sortie donne les URLs et le dossier `evidence`. Admin : URL
`keycloakAdmin.url`, realm master, **admin / pocoma**. Bruno : ouvrir
scripts/bruno-playground, choisir Instance-demo ; connecter Owner
**third / pocoma** puis Guest **guest / pocoma** avec Auth → Get Access Token.
Le compte de compatibilité est **bruno / pocoma** dans le realm pocoma.
Ces mots de passe sont exclusivement locaux ; PostgreSQL et les clés internes
restent aléatoires. Aucun port PostgreSQL publié, autres ports sur 127.0.0.1.

## Mécanisme et preuves

Keycloak **26.7.0** crée le compte master par `KC_BOOTSTRAP_ADMIN_USERNAME`
et `KC_BOOTSTRAP_ADMIN_PASSWORD`, seulement à la création du realm master.
L’import `--import-realm` initialise pocoma mais ne met pas à jour un realm
existant. Aucune réparation silencieuse des anciennes données.
[Guide versionné 26.7.0](https://www.keycloak.org/docs/26.7.0/server_admin/index.html),
[configuration officielle](https://www.keycloak.org/server/all-config),
[sémantique du compte temporaire](https://www.keycloak.org/server/bootstrap-admin-recovery).
La bannière d’admin temporaire est attendue pour cette installation jetable.
Ce mécanisme ne constitue aucune configuration de production ou de L8.

`start` avant READY et `status` exécutent : discovery issuer/S256 ; connexion
du client natif security-admin-console en master par Authorization Code+S256 ;
lecture des comptes master/admin et pocoma/{third,guest,bruno} par l’API REST
admin avec ce jeton ; activation et absence d’actions obligatoires ; connexion
Code+S256 de chaque utilisateur via pocoma-functional-tests, comparaison du
sujet authentifié au compte, refus des challenges absents/plain. Les jetons
restent en mémoire. Les preuves `keycloak-auth.json` ne contiennent que des
contrôles, horodatages et diagnostics autorisés. Aucune modification du realm,
aucun client spécifique d’administration et aucun password grant. Sur 26.7.0,
le client console fournit un access token allégé sans sub ; le contrôle lit
le sujet de l’ID token OIDC reçu du même endpoint, vérifie issuer/audience/
expiration, puis le compare au compte admin lu avec l’access token.

| Diagnostic | Contrôle / cause établie ou probable | Action |
| --- | --- | --- |
| KEYCLOAK_OIDC_DISCOVERY_UNAVAILABLE | Endpoint inaccessible ou réponse discovery invalide | Démarrer Docker ; consulter keycloak-up.log et containers.log dans evidence |
| KEYCLOAK_REALM_NOT_READY | HTTP404 discovery, mauvais issuer ou S256 absent | Vérifier port, realm importé et logs ; ne pas contourner le garde d’empreinte |
| KEYCLOAK_ADMIN_AUTH_FAILED | Connexion console master refusée ou sujet incohérent | Vérifier master/admin ; sur ancien volume, bootstrap ne recrée pas l’admin |
| KEYCLOAK_ADMIN_NOT_PROVISIONED | Compte absent/désactivé/action initiale ou inspection REST refusée | Lire keycloak-auth.json ; préserver l’ancien volume avant nouvelle instance/reset |
| KEYCLOAK_USER_GUEST_NOT_PROVISIONED | Compte guest absent/désactivé/action initiale | Vérifier le realm pocoma et le compte dans la console |
| KEYCLOAK_USER_GUEST_AUTH_FAILED | Identifiants attendus, sujet ou PKCE refusés | Choisir la bonne Instance dans Bruno, Clear Cache puis guest/pocoma ; lire la preuve |

Les mêmes codes USER existent pour THIRD et BRUNO. Chaque diagnostic précise
le composant, le contrôle, la cause probable et l’action recommandée ; le reçu
indique le dossier des preuves. Un échec de transport OIDC n’est pas présenté
comme un simple URLError ni assimilé à un mauvais mot de passe.

## Arrêter, reprendre, réinitialiser

`./pocoma-local stop --instance demo` conserve données/volumes. Après arrêt
Docker, redémarrer Docker puis `./pocoma-local start --instance demo`, `status`
et `gui`. Le TTL nominal des baux runtime est désormais de `30 s` (`lease-duration-ms=30000`), tandis que `pocoma-local` conserve un budget d'observation de récupération borné jusqu'à `270 s` (historiquement lié aux `240 s`).
Si PRE-INPUT-DRIFT apparaît, utiliser une instance neuve après arrêt de l’autre,
ou confirmer volontairement un reset ; ne pas modifier le manifeste à la main.

Reset **détruit puis recrée** l’instance. Prévisualiser avant confirmation :

```sh
./pocoma-local reset --instance demo --confirm RESET:demo --dry-run
./pocoma-local reset --instance demo --confirm RESET:demo
./pocoma-local gui --instance demo
```

Pour retirer une instance **sans la recréer**, la façade fournit un destroy
distinct, protégé par instance explicite, UUID/labels et confirmation exacte :

```sh
./pocoma-local destroy --instance ancienne --confirm DESTROY:ancienne --dry-run
# Seulement après décision humaine de perdre ces données :
./pocoma-local destroy --instance ancienne --confirm DESTROY:ancienne
```

Destroy arrête proprement, retire les conteneurs et le réseau de ce seul
projet Compose **sans `--volumes`**, puis retire explicitement le seul volume
nommé dont le nom, le projet et le label UUID ont été validés. Les volumes
sans propriété explicite sont conservés, même attachés à un conteneur possédé ;
leur liste figure dans `preservedUnownedVolumes` et `volume-removal.json`.
Cette même protection s’applique au reset. Destroy efface les fichiers générés (secrets, configuration,
JAR, manifeste, demo/canary). Les diagnostics runs et le verrou sont conservés.
Un start ultérieur crée une nouvelle identité. La commande refuse toute
propriété étrangère ou Compose manquant. Aucune commande prune globale.
Le reset et le destroy n’effacent pas les copies/répertoires étrangers.

Inventaire en lecture seule, y compris ressources orphelines potentielles :

```sh
python3 -B scripts/local/inventory.py --output /tmp/pocoma-inventory-new.json
```

Le fichier de sortie doit être neuf. Un nom ressemblant à pocoma-lb ne suffit
jamais à autoriser sa suppression. Les objets sans autorité de propriété
cohérente restent conservés pour investigation. Avant tout nettoyage de
campagne, qualifier une instance neuve, relire l’inventaire/dry-run, puis
obtenir la confirmation humaine exacte des cibles. Ne pas lancer en boucle
les resets : ils recréent des instances et empêchent le retour à une seule demo.

`unownedAttachedVolumes` distingue les volumes montés par le playground mais
sans labels de propriété (notamment le volume anonyme `/prometheus` de l’image).
Un montage prouve son usage, pas l’autorisation de le supprimer. Les volumes
détachés conservés restent listés sans attribution ; consulter les reçus
`volume-removal.json` précédents avant toute décision manuelle. Ne pas utiliser
`Compose down --volumes` pour retirer un volume dont la propriété n’est pas prouvée.

La preuve automatisée des protocoles et du moteur Bruno ne remplace pas la
recette GUI : ouverture, connexion rendue, Get Access Token, Send et Runner
doivent être observés séparément. Voir le [guide Bruno](L7_Local_Playground_Bruno.md).

Un fichier Finder `.DS_Store` placé directement dans `instances/` peut être
pris pour une entrée d’instance et provoquer PRE-PRIVATE-PERMISSIONS.
Vérifier qu’il s’agit bien d’un fichier régulier sans symlink, puis le conserver
sous un nom unique dans le dossier privé `.pocoma-local/finder-metadata/`.
Ne pas rendre les répertoires publics ni contourner les contrôles UUID/labels.

## Grafana local

`GRAFANA-HTTP`, `GRAFANA-DATASOURCE` et `GRAFANA-DASHBOARD` sont obligatoires pour
READY. Le JSON `grafanaDiagnostics` et le fichier privé `runs/<id>/grafana.json`
permettent de distinguer une panne Grafana/datasource d'un défaut métier.
Voir [observabilité locale et mise à niveau sans reset](L7_Local_Playground_Observability.md).
