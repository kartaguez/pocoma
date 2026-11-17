# L7-LB Jalon 1 — rapport d'implémentation

Implémentation I1a/I2/I3 sous GO_L7_LB_LOCAL_PLAYGROUND_IMPLEMENTATION.
Référence initiale `2a4e7fd8a79d798b4bcb983a0d5f9466bc5241aa`, arbre propre,
branche `feat/l7-lb-local-playground`. Le [plan avant modification](L7_Local_Playground_Implementation_Plan.md)
consigne les prérequis, risques et frontières.

Livré : façade `pocoma-local` start/status/stop/reset, login et Bruno ; Compose
par instance généré hors Git avec labels UUID, volume/réseau isolés et aucun
port PG exposé ; quatre JAR en cinq JVM, images ARM64/version/digest observés,
ressources bornées ; bootstrap SQL CURRENT V3 réutilisé avec transactions par
base, marqueur d'entrées et empreinte de catalogue ; secrets aléatoires privés,
verrous, reprise du seed, canari frais et diagnostics conservés. Collection
Bruno publique distincte, test nommé/JUnit/inventaire strict, deux identités
réelles, oracles rationnels et histoire. Guides local/Bruno/contrat BFF et D6–D9.

Aucun fichier sous `app/` modifié : ni contrat métier, ni POM, ni migration, ni
règle d'isolation, ni bail. Défauts corrigés dans **le nouvel outillage** : date
d'invitation normalisée UTC Z ; délimiteurs SQL BEGIN/COMMIT composés sans
commit intermédiaire ; Registration déjà liée vérifiée comme refus métier
explicite ; reprise d’un worker arrêté : attente de la fenêtre physique
de bail observée (≤240 s), sans mutation d’échéance et sans modifier le budget
de polling public de 30 s ; garde disque différenciée entre installation
(images absentes4Gio, images présentes2Gio) et reprise en cache256Mio ; un reçu de scénario seul ne suffit pas sans JUnit non vide.

Déclaration de vérification : LOCAL DISTRIBUTION / orchestration et bootstrap
CURRENT V3 ; consommateurs publics WEB/COMMAND/PROJECTION exercés par les cinq
JVM, sans changement de code produit. Niveau INTEGRATION distribution. Aucun
franchissement inattendu de slice. Gate globale architecture NOT REQUIRED,
réacteur complet NOT RUN / NOT REQUIRED (pas clôture L7/I6 ni intégration main).
Base CURRENT V3, pas de baseline TRUSTED ni chaîne historique V2. Le build
`./mvnw -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am package -DskipTests`
assemble la distribution ; il ne constitue pas un test PASS. La façade utilise
le même ensemble de modules avec `-f app/pom.xml` et ordre alphabétique.

Les commandes réellement exécutées, SHA figé, résultats et artefacts sont
consignés dans le [rapport de qualification](L7_Local_Playground_Qualification_Report.md).
Les campagnes canoniques Maven ne sont pas déclarées closes par le smoke externe.
Les gates des consommateurs deviendraient requises avant toute correction
produit ; aucune n'a été nécessaire sous ce mandat.

## Fichiers et publication

Créés : `pocoma-local` ; `scripts/local/{runtime,bootstrap,auth,public_api}.py` ;
`test_local.py`, `test_installation.py`, `test_interactive_protocol.py` ; collection
`scripts/bruno-playground` (environnement, PG01, helper, inventaire, guide) ;
sept documents `L7_Local_Playground_*` et preuves sous
`docs/testing/evidence/L7-LB-J1`.
Modifiés : `.gitignore`, README racine et index docs, trois documents de cadrage
L7_Local_Baseline (plan, contrat développeur, jalons). Aucun fichier `app/`.
La distribution utilise directement une image JRE pinnée avec montures JAR,
sans ajouter de Dockerfile ou modifier celui du produit.

Résultats :15 contrôles locaux PASS dans les deux checkouts ; deux installations
indépendantes READY, Bruno1/1 à chaque exécution, reprise physique246,7s,
isolation/reset et bootstrap interrompu PASS. Les empreintes opérationnelles
restent identiques entre le SHA technique69f327f6 et la publication documentaire.
Verdict proposé **LOCAL_PLAYGROUND_PARTIAL** : seule la validation rendue dans
Bruno GUI4.2.1 avec login humain reste non exécutée au Jalon1. Le helper
interactif/PKCE/callback a été vérifié par protocole réel automatisé ; il ne
remplace pas cette revue GUI. Les autres obligations I1b/I4–I6 restent distinctes.
La décision humaine et tout tag de référence sont hors publication actuelle.

Les commits d'implémentation sont8012fb93,81a73e78,69f327f6 ; le commit de
publication descendant contient ces rapports et preuves, sans changement des
entrées runtime. La branche distante est contrôlée avant push, publication
sans force. Le SHA final et l'égalité distant/local sont vérifiés à la livraison.

## Correctif du14 novembre

Le [rapport GUI OAuth](L7_Local_Playground_GUI_Auth_Fix_Report.md) remplace la
procédure de copie manuelle de JWT par deux connexions natives Bruno et
getOauth2CredentialVar. Preuves distinctes e54cadd4 :17 tests,23 contrôles
installés4.2.1 dont PG01 réel, CLI1/1. Slice LOCAL DISTRIBUTION, aucun app/,
franchissement NONE, gates globales/full non requis et non exécutés. Incident
d’environnement décrit séparément ; validation humaine GUI et statut PARTIAL
maintenus. Le rapport initial ci-dessus garde son attribution historique.
