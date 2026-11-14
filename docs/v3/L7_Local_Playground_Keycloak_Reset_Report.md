# L7-LB — correction Keycloak et préparation du clean reset

Mandat GO_L7_LB_KEYCLOAK_BOOTSTRAP_AND_CLEAN_RESET, 14 novembre 2026 (Europe/Paris).
Départ propre : 80792858d6aac9c648ebcfac2fb2d84ccf80154e,
branche feat/l7-lb-local-playground.
[Plan et périmètre déclaré](L7_Local_Playground_Keycloak_Reset_Plan.md).

## Corrections

Le générateur local provisionne admin/master via les variables officielles
KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD de Keycloak 26.7.0. Third, guest et bruno
sont activés, sans action initiale ni mot de passe temporaire ; les quatre
comptes utilisent pocoma uniquement en L7-LB. Passwords.keycloak reste le
secret PostgreSQL aléatoire et distinct. Aucun fichier partagé de realm,
Java, POM, SQL, worker, L8 ou configuration de production n’est modifié.

Start et status prouvent les quatre comptes, au lieu de se contenter de la
discovery : connexion console master par Code+S256 et ID token OIDC ; API
admin pour l’activation et les actions ; Code+PKCE S256 réel via le client
Bruno pour third/guest/bruno, refus sans PKCE/plain et sujet exact.
Les jetons restent en mémoire ; les reçus conservent seulement contrôles,
horodatages et diagnostics structurés. Start/gui donnent URL admin et
convention de connexion, sans secret technique.

Destroy complète reset pour retirer une instance sans la recréer. Il exige
nom explicite et DESTROY:<instance>, vérifie UUID/labels, arrête proprement
puis utilise Compose down --volumes sur le seul projet. Il conserve runs
et le verrou, retire les fichiers générés dont le manifeste. Une absence
de Compose ou une propriété étrangère bloque l’opération. Seuls les tests
unitaires emploient cette fonction sur des dossiers temporaires simulés ;
aucune destruction Docker réelle n’est exécutée pendant cette phase.

## Diagnostic établi et incidents conservés

Avant arrêt de gui-test, third/guest/bruno réussissaient tous Code+PKCE avec
leurs anciens secrets privés. L’erreur Guest humaine n’est pas reproduite ;
aucune cause exacte n’est revendiquée. L’absence de provisionnement explicite
d’admin est établie dans le code initial. La nouvelle convention locale et
les preuves systématiques éliminent les étapes implicites de connexion.

Premier essai sur keycloak-validation : admin a été créé et connecté, mais
le contrôle exigeait sub dans son access token. Le client natif console
26.7.0 émet un jeton allégé sans sub ; le contrôle a été corrigé pour lire
le sujet dans l’ID token OIDC du même échange, puis le comparer au compte.
Ce premier environnement a été arrêté, jamais supprimé ; nouvelle instance
vierge pour la qualification corrigée. Les reçus FAIL restent conservés.

Sur keycloak-validation-final, READY et les trois parcours CLI ont réussi.
Le contrôle moteur GUI a ensuite refusé le logiciel installé avant tout
parcours : il exigeait 4.2.1 alors que /Applications/Bruno.app est4.3.0.
Le contrôle accepte désormais ces deux versions revues et consigne la version
effective. Le moteur installé 4.3.0 a ensuite passé les193 contrôles et les
118 requêtes/236 tests/730 assertions. Aucun résultat GUI rendu n’est inventé.

Le cycle stop/start intermédiaire a réussi physiquement, mais la comparaison
finale d’empreinte a refusé la preuve agrégée : les derniers gardes de destroy,
le reçu d’échec discovery et le README Bruno avaient changé pendant ce cycle.
Cet essai n’est pas crédité comme qualification des sources finales. Une
dernière campagne entière sur **keycloak-release-validation**, sources figées
dans frozen-inputs.json, est la seule retenue pour le verdict final.

## Vérification et limites

Slice principale : LOCAL DISTRIBUTION / Keycloak / Bruno ; slices Maven :
NONE. Consommateurs WEB/COMMAND/PROJECTION exercés par les parcours publics.
Franchissement imprévu : NONE ; adaptation du contrôle à la version Bruno
effectivement installée, dans le périmètre outillage déclaré.
Gate globale d’architecture : NOT REQUIRED, non exécutée.
Réacteur complet : NOT REQUIRED, non exécuté (aucune clôture globale/main).
Base : CURRENT V3 sur installations neuves possédées ; aucune migration
historique V2, aucun baseline TRUSTED supposé. Certification L7/V3 : NOT RUN.

Commandes réalisées et reçus : voir
[dossier de preuves](../testing/evidence/L7-LB-KEYCLOAK-RESET/README.md).
Les 40 tests Python et le contrôle de composition 118/236 passent.
Build de distribution lancé par start, depuis app/ :
`./mvnw -pl runtime-web-read,runtime-web-write,runtime-worker-materialize,runtime-worker-process -am package -DskipTests`.
L’invocation physique ajoute les chemins absolus du wrapper et de `-f app/pom.xml`.
Il s’agit de package des dépendances nécessaires, pas d’une preuve de tests
Maven. Les journaux de package, versions, digests et empreintes sont conservés.

La recette visuelle Bruno (login complet, Send, Runner, bascule et reprise)
reste humaine ; la commande gui ne fait que générer les URLs. La bannière
d’admin bootstrap temporaire est normale dans cet environnement jetable.
Les anciens volumes gardent leurs anciennes identités jusqu’au nettoyage
explicitement confirmé. Les verdicts historiques L7 et leurs réserves restent
inchangés. Les tests de reset réel ne sont pas rejoués : ils détruiraient des
données avant la confirmation demandée par le mandat.

Commandes de vérification exécutées :

```sh
python3 -B -m unittest discover -s scripts/local -p 'test_*.py'
python3 -B scripts/local/sync_bruno_playground.py --check
python3 -B scripts/local/test_gui_auth_protocol.py --instance keycloak-validation-final --output docs/testing/evidence/L7-LB-KEYCLOAK-RESET/native-430.json
python3 -B scripts/local/test_keycloak_installation.py --instance keycloak-release-validation --output docs/testing/evidence/L7-LB-KEYCLOAK-RESET/release
git diff --check
git diff --quiet -- app docker
```

Le dernier script exécute réellement start/status/gui/bruno/stop/start/status,
le protocole natif installé, les contrôles réseau, le renouvellement du
canari et la conservation des checkpoints. Ses commandes physiques,
sorties JSON et durées restent dans release/commands.json. Les arrêts
préalables de gui-test et des deux essais sont conservés séparément.

## Résultat final et nettoyage soumis à confirmation

**READY_FOR_CLEAN_RESET**. La campagne release/qualification.json passe sur
keycloak-release-validation ; empreinte identique avant/après :
999c85a83150de0611375397a9bab97760f04b44490b824a34f6a509ad33fc58.
Les fichiers figés sont détaillés dans frozen-inputs.json. Les preuves
portent sur HEAD initial + diff de mission, sans attribution automatique au
SHA de publication. Les sources/configurations/tests publiés sont comparés
à ce manifeste ; le SHA final et le push sont donnés dans le compte rendu
Git de livraison. L’arbre de livraison doit être propre et le distant aligné.

Résultats : fresh-start READY en 44,65 s ; status READY ; GUI_CONFIGURED ;
CLI 118 requêtes/236 tests/731 assertions (220 appels HTTP avec polling) ;
moteur GUI4.3.0 :193 contrôles,118 requêtes/236 tests/730 assertions ;
stop gracieux sans kill ; restart READY en 255,43 s, dont 232,28 s d’attente des
baux capturés sans mutation ; canari V2→V3 et checkpoint demo inchangé ;
restart-status READY et quatre comptes à nouveau prouvés. Keycloak physique
26.7.0, cinq JVM et cinq scrapes disponibles, ports loopback, PG non publié.
Console : http://localhost:53125/admin/, master, admin/pocoma.

Inventaire effectué seulement après ce PASS : **13 instances**, **58
conteneurs**, **8 volumes** et **8 réseaux** possédés ; **aucun orphelin
potentiel L7-LB**. Une seule instance est active : keycloak-release-validation.
Les 7 volumes sans attribution L7-LB et les 3 réseaux système sont exclus.
Les 13 dry-runs destroy ciblés passent et sont conservés dans dry-runs.json.
Aucune suppression réelle n’a été exécutée. Les données de toutes les anciennes
instances et des essais restent présentes.

Le [plan exact soumis à confirmation](L7_Local_Playground_Clean_Reset_Approval_Plan.md)
énumère les 13 noms/projets/volumes/réseaux, les pertes, les commandes destroy
et 6 environnements publics à retirer, puis start/status/gui/bruno de la seule
demo à recréer. Les diagnostics historiques sont conservés. La confirmation
humaine supplémentaire est exigée par le §6.2 du mandat ; elle est le seul
prérequis à la seconde phase destructive. Le reset global n’est pas engagé
et une instance demo neuve n’est pas annoncée avant cette confirmation.
