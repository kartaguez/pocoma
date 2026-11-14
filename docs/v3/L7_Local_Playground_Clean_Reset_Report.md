# L7-LB — clean reset exécuté, demo disponible, écart conservé

Verdict : **DEMO_READY_WITH_CLEANUP_DEVIATION**.
Le 14 novembre 2026 (Europe/Paris), après réponse humaine explicite « oui »
au [plan exact](L7_Local_Playground_Clean_Reset_Approval_Plan.md).
Départ : 47a0c094f803f019e32c91709e171791cde67f2e, arbre initial propre,
branche feat/l7-lb-local-playground.
[Preuves et reçus](../testing/evidence/L7-LB-CLEAN-RESET/README.md).

## Résultat opérationnel

Les 13 anciennes instances ont été retirées avec les confirmations ciblées
`DESTROY:<instance>` de la façade. Les 58 conteneurs, 8 volumes nommés PG
et 8 réseaux répertoriés sont absents. Aucun prune ni suppression globale.
Les six environnements publics périmés du plan ont été retirés. Les anciens
secrets, manifestes et configurations ont été effacés ; les diagnostics runs
et les verrous sont conservés. Douze répertoires ne sont plus que des archives
sans manifeste ; demo réutilise son répertoire avec une nouvelle identité.

Seule **demo** possède un manifeste et des ressources exécutables :
`pocoma-lb-demo-33dd8c1d`, UUID 33dd8c1d-4dbd-4310-9011-50b0c6de18be.
Huit conteneurs tournent : cinq JVM, PostgreSQL WRITE/READ/Keycloak, Keycloak
et Prometheus. Un volume nommé PG, un réseau et un nouveau volume anonyme
Prometheus sont associés à cette installation. Aucun ancien ID conservé,
aucun orphelin de projet constaté. Les trois réseaux système sont inchangés.

Commandes réellement exécutées pour la référence :

```sh
./pocoma-local start --instance demo
./pocoma-local status --instance demo
./pocoma-local gui --instance demo
./pocoma-local bruno --instance demo
```

Résultats : READY, READY, GUI_CONFIGURED, BRUNO_PASS. Le démarrage neuf prend
50,52 s ; les quatre comptes sont activés, sans action initiale et authentifiés
réellement. Le canari et les cinq scrapes passent. Bruno CLI exécute les
118 requêtes, 236 tests, 731 assertions (222 appels HTTP avec polling).
La validation visuelle complète des connexions et du Runner GUI reste NOT RUN.

Console : **http://localhost:54612/admin/**, realm **master**, **admin / pocoma**.
Bruno : ouvrir scripts/bruno-playground, sélectionner **Instance-demo**,
puis Owner **third / pocoma** et Guest **guest / pocoma**, Auth → Clear Cache
si une ancienne session est présente → Get Access Token. Le compte de
compatibilité **bruno / pocoma** est dans le realm pocoma. Les secrets
techniques restent aléatoires ; seuls les ports loopback sont publiés.

## Écart irréversible par rapport au plan approuvé

Les **sept volumes sans labels annoncés comme exclus ont été supprimés**
par les `Compose down --volumes` de la version 47a0c094. L’inventaire les
classait à tort comme ressources sans lien avec le playground : ses propres
reçus de montage les rattachent exclusivement aux sept Prometheus des anciens
projets. L’image v3.5.0 déclare un volume `/prometheus`. L’aide locale de
Compose confirme que `--volumes` enlève aussi les volumes anonymes attachés.

Le contrôle après suppression a refusé de conclure à leur préservation.
La création de demo a d’abord été bloquée par cette assertion, l’utilisateur
a été informé, puis la cause a été établie et corrigée avant la nouvelle demo.
Les noms, conteneurs, services et projets concernés sont dans
`cleanup-deviation.json`. Les données historiques Prometheus de ces volumes
ne sont pas sauvegardées ni restaurées ; leur contenu exact n’a pas été relevé.
L’absence de lien avec un autre projet est fondée sur l’inventaire des montages,
pas sur une hypothèse de volume vide. Le respect parfait du périmètre approuvé
n’est **pas** revendiqué malgré le résultat fonctionnel final.

## Correction et preuve de protection des volumes

Reset et destroy retirent désormais les conteneurs/réseau via Compose **sans
`--volumes`**, puis suppriment explicitement le seul volume nommé dont le nom,
le projet Compose et le label UUID concordent. Les volumes non possédés restent
présents et sont listés dans `preservedUnownedVolumes`/`volume-removal.json`.
Un montage ne vaut pas autorisation de suppression. Toute propriété incohérente
ou ressource résiduelle bloque l’effacement des fichiers privés.

L’inventaire distingue maintenant `unownedAttachedVolumes` des ressources
sans lien constaté. Il conserve les destinations et services des montages.
Sur la nouvelle demo, il identifie ainsi son volume anonyme `/prometheus` ;
ce volume n’est ni adopté ni supprimé implicitement.

**43 tests Python passent**, dont refus d’un propriétaire modifié et
préservation des montages anonymes/d’un autre projet. Une fixture Docker
neuve a réellement créé un volume étiqueté et un volume anonyme avec marqueur :
le retrait supprime uniquement le volume étiqueté ; le volume anonyme et son
contenu restent lisibles. L’inventaire le classe correctement. Seules les
ressources synthétiques créées par ce test ont ensuite été retirées.
Les reçus sont dans volume-proof/. Cette preuve ne prétend pas restaurer
les sept volumes perdus.

Un fichier Finder `.DS_Store` apparu dans la racine des instances a provoqué
un refus PRE-PRIVATE-PERMISSIONS de l’inventaire : il attend des répertoires
d’instances à cet endroit. Ce fichier régulier a été conservé dans l’archive
privée `.pocoma-local/finder-metadata/`, sans chmod global ni contournement
du garde. Un autre `.DS_Store` de métadonnées Finder reste dans l’archive
gui-test ; aucun manifeste, secret ou état métier ne subsiste dans ces archives.

## Verification scope et livraison

Déclaré avant action : opérations locales L7-LB, aucun changement app/,
SQL, workers ou contrats V3. Révisé avant correction : sûreté des volumes
et inventaire dans cette même distribution ; tests unitaires et preuve Docker.
Slice principale : LOCAL DISTRIBUTION / lifecycle et inventaire.
Slices secondaires Maven : NONE. Franchissement de slice Maven : NONE.
Écart de ressources : les sept volumes explicités ci-dessus.
Gate globale d’architecture : NOT REQUIRED, non exécutée.
Réacteur complet : NOT REQUIRED, non exécuté ; ni clôture globale ni main.
Base : CURRENT V3, PG neuf ; aucun historique V2 ni baseline TRUSTED supposé.
Certification et verdicts historiques L7 : inchangés, aucune recertification.

Commandes de vérification exécutées :

```sh
python3 -B -m unittest discover -s scripts/local -p 'test_*.py'
python3 -B scripts/local/inventory.py --output docs/testing/evidence/L7-LB-CLEAN-RESET/before.json
python3 -B scripts/local/inventory.py --output docs/testing/evidence/L7-LB-CLEAN-RESET/after-destroy.json
python3 -B scripts/local/inventory.py --output docs/testing/evidence/L7-LB-CLEAN-RESET/after-demo.json
docker compose down --help
docker image inspect prom/prometheus:v3.5.0 --format '{{json .Config.Volumes}}'
git diff --check
```

Les treize argv destroy et leurs reçus/durées sont dans destroy-commands.json ;
les argv de la référence dans demo-commands.json. La fixture de sûreté utilise
le helper réel remove_project, Compose up/down, inspect, une lecture par
conteneur sans réseau sur montage readonly, puis le retrait de son seul volume
synthétique. Aucun conteneur d’un autre projet n’a été ciblé.

Le start a assemblé les quatre runtimes depuis app/ :
`./mvnw -pl runtime-web-read,runtime-web-write,runtime-worker-materialize,runtime-worker-process -am package -DskipTests`
(wrapper et `-f` absolus dans l’invocation physique). Ce package n’est pas un
PASS de tests Maven. Empreinte finale :
`c6325dc490b0383a85c7baa855dc2687b1e0bcb7062400ffa1f18b035ae5735e`.
Sources/configurations/tests figés avant demo, identiques à la livraison ;
preuves, comparaison et sommes SHA256 conservées. Le SHA final, le push et
l’alignement du distant sont donnés dans le compte rendu de livraison.
