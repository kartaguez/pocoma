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
