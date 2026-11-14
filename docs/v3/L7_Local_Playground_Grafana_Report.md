# L7-LB — intégration Grafana : implémentation et qualification

Mandat `GO_L7_LB_GRAFANA_INTEGRATION`, 14 novembre 2026.
Branche : `feat/l7-lb-local-playground`. Implémentation : `8b1675d2` ;
correctif d'oracle de qualification : `4ff17849`.

Verdict : **L7_LB_GRAFANA_READY**.

État final observé : neuf conteneurs actifs, `start` et `status` READY/code0,
canari V12. Grafana : `http://127.0.0.1:61347/`, **admin / pocoma**.
Demo reste en fonctionnement, les deux fixtures de qualification sont détruites.

## Résultat livré

Grafana OSS 12.3.2 est le neuvième conteneur du playground : port dynamique lié
à 127.0.0.1, image officielle résolue au digest natif, 384 Mio / 0,5 CPU,
réseau/labels privés de l'instance. L'image publiée expose ARM64 et AMD64 ;
l'exécution réelle est ARM64 native. Aucun profil Internet/production modifié.

La connexion locale **admin / pocoma** est vérifiée par l'API authentifiée,
avec compte administrateur effectif. `start`/`status` conservent leurs champs et
ajoutent `grafana.url`, `username`, `dashboard`, `dashboardUrl`.
La datasource UID `Prometheus` rejoint `http://prometheus:9090` sur le réseau
Docker interne. Les deux dashboards L6 sont provisionnés depuis les sources du
dépôt, avec trois adaptations locales : jobs des cinq JVM, casse des UID de
datasource et labels de Consumption du débit des projections. Les sources
historiques `docker/grafana/` restent inchangées.

Grafana stocke ses données en tmpfs : aucun volume persistant ou anonyme Grafana.
Son provisioning est déclaratif, monté en lecture seule. Sessions et modifications
UI sont éphémères ; stop/start reconstruit la configuration depuis les fichiers.
Les commandes stop/reset/destroy incluent Grafana et gardent les garanties de
propriété et de conservation des volumes étrangers.

`GRAFANA-HTTP`, `GRAFANA-DATASOURCE`, `GRAFANA-DASHBOARD` sont obligatoires pour
READY. Une panne donne NOT_READY/code1, avec `grafanaDiagnostics` et preuve privée
`grafana.json`. `business` reste indépendant d'une panne Grafana seule.
Les contrôles vont jusqu'aux vraies métriques obtenues **à travers Grafana**,
aux deux dashboards provisionnés et à l'exécution de toutes leurs requêtes.

## Conservation de demo et mise à niveau

La commande explicite `upgrade-grafana` exige l'arrêt pour un changement d'entrées.
Elle prouve l'équivalence des sources protégées à la révision enregistrée du
build, des JAR, images, services, réseaux, volumes et fichiers générés existants.
Elle ajoute Grafana ou rafraîchit son provisioning ; à empreinte identique, elle
reprend start. Un changement applicatif, SQL, authentification, JAR, image ou
configuration existante reste refusé. Le start ordinaire conserve son contrôle
strict `PRE-INPUT-DRIFT`.

L'ancienne empreinte de demo ne correspondait déjà pas au checkout à l'arrivée.
Aucun simple remplacement manuel de cette empreinte n'a été effectué. La recette
d'équivalence a permis la migration explicite. L'UUID/projet et les ports
existants sont conservés ; aucun reset/destroy de demo. Le bootstrap normal
reconnaît ses marqueurs CURRENT et ne recrée pas les comptes/bases.

Les preuves avant/après montrent :

- mêmes conteneurs PostgreSQL et Keycloak, comptes, credentials, realms et clients ;
- mêmes secrets techniques, mots de passe SQL et checkpoint de démonstration ;
- mêmes lignes des Pots existants hors canari, mêmes projections READ hors canari ;
- conservation de tous les Events antérieurs, avec ajout normal du nouveau canari ;
- reprise avec une nouvelle version du même Pot canari, sans duplication du seed.

Voir les reçus de conservation dans `docker-qualified/preservation.json` et
`final/preservation.json`. Les valeurs et hashes de credentials individuels ne
sont pas exportés : seuls les résultats de comparaison et comptes sont livrés.

## Qualification et preuves

Racine de preuve : [L7-LB-GRAFANA](../testing/evidence/L7-LB-GRAFANA/).
Les phases de la campagne finale et leurs commandes exactes sont dans
[final/commands.json](../testing/evidence/L7-LB-GRAFANA/final/commands.json).
Le verdict final est dans
[completion/qualification.json](../testing/evidence/L7-LB-GRAFANA/completion/qualification.json).
La reprise du seul oracle d'inventaire et du start/status final est explicitement
tracée ; les reçus de l'oracle précédent restent inchangés et non PASS.

| Preuve | Résultat |
| --- | --- |
| Tests Python locaux | 62 tests PASS, zéro failure/error/skip |
| Composition Bruno | 118 requêtes, 236 tests nommés, 3 parcours |
| Authentification Grafana | admin effectif et health/database OK |
| Datasource | URL Docker interne, health OK, cinq jobs UP et métriques JVM/Pocoma collectées |
| Dashboards | deux UID provisionnés ; panels et datasources conformes aux sources adaptées |
| Requêtes après trafic | **31/31 finies : 24 PRESENT, 7 ZERO, aucune ABSENT/NONFINITE** ; six labels de projection présents |
| Bruno CLI sur demo | 118 requêtes/236 tests nommés, 730 assertions PASS |
| Bruno installé | 4.3.0, protocole natif cache/VM/transport, 118 requêtes/236 tests, 730 assertions PASS ; 197 contrôles de scripts |
| Arrêt Grafana et Prometheus | échecs obligatoires attendus, NOT_READY/code1 ; métier conservé si Grafana seul tombe |
| Reprise de demo | arrêt gracieux, attente des baux existants, canari neuf et status READY |
| Fixture neuve/reset/destroy | tous les codes attendus ; nouveau projet au reset, suppression du seul volume possédé au destroy |
| Ressources extérieures | 8 conteneurs et 4 volumes extérieurs initiaux inchangés ; volume PG de demo identique pendant le cycle de la fixture |

Les séries absentes au tout premier scrape ou les quantiles sans observations
sont distingués des zéros. Le trafic CLI/natif permet une preuve plus forte :
toutes les requêtes disposent ensuite de séries finies. Les données réelles des
projections et les décomptes sont dans
[final/metrics.json](../testing/evidence/L7-LB-GRAFANA/final/metrics.json).
Un panel vide n'a pas été artificiellement remplacé par zéro.

Les reçus de qualification gardent l'empreinte finale de distribution :
`711336711899a0aa04074515ab11cc9bc11d79c910ba9975a47c61f4eab85e4a`.
La qualification porte sur le checkout local avec les modifications Bruno déjà
présentes au début de la mission. Celles-ci sont conservées, non intégrées aux
commits Grafana. Les preuves indiquent explicitement `dirty=true` ; aucun
verdict de checkout propre distant ou de certification globale n'est implicite.

## Écarts découverts et résolus

1. `docker/` conserve le premier refus non destructif : chemins macOS `/dev/`
   et `/Dev/` désignant le même dossier. La migration vérifie désormais leur
   identité physique et conserve la graphie déployée, pour éviter de recréer
   PostgreSQL/Keycloak.
2. `docker-qualified/` conserve la campagne intermédiaire. Le filtre L6 du débit
   des projections utilisait des noms d'assemblage `Materialize…`, tandis que les
   métriques exportent `PotVersion`, `PotBalance`, etc. Correction locale qualifiée
   sur données réelles ; tous les panels sont exploités dans la campagne finale.
3. La campagne intermédiaire s'est arrêtée sur un oracle trop strict exigeant
   l'égalité exacte de tout l'inventaire Docker. Les images Prometheus créent des
   volumes anonymes que la politique existante préserve lors de reset/destroy.
   La campagne suivante a sauvegardé l'inventaire initial immédiatement, mais
   classait encore la TSDB Prometheus anonyme **attachée à demo** comme extérieure :
   ses octets changent normalement pendant les scrapes. L'analyse des montures
   et dates de création prouve que cette seule différence est attachée à un
   conteneur possédé préexistant ; les 8 conteneurs et 4 volumes réellement
   extérieurs sont identiques. L'oracle corrigé distingue présence du volume
   attaché et invariance des volumes extérieurs, avec tests négatifs de modification
   et disparition. Deux volumes anonymes ajoutés par la fixture finale sont
   conservés conformément à la politique ; aucun volume Grafana créé.
   `completion/` termine ce contrôle et le démarrage final sur la **même empreinte
   productive**, sans réexécuter les phases déjà réussies ni réécrire leurs reçus.
4. Le lancement direct de `test_bruno_gui.js` avec Node hôte ne lit pas les modules
   `app.asar` d'Electron ; cette invocation a échoué. Le lanceur supporté
   `test_gui_auth_protocol.execute`, utilisant Bruno installé avec
   `ELECTRON_RUN_AS_NODE=1`, passe réellement dans la campagne finale.

Les preuves intermédiaires ne valent pas verdict final. Leurs échecs restent
visibles ; ils n'ont donné lieu à aucun reset de demo ni contournement de contrôle.

## Préparation de la publication

Le contrôle automatique d'approbation a refusé une première commande groupant
commit/push des preuves et journaux Maven bruts, à cause du risque de divulgation
de chemins personnels ou détails sensibles. Cette commande n'a pas été exécutée.
Le dépôt distant a ensuite été vérifié via le connecteur GitHub :
`kartaguez/pocoma`, droits admin/push, conforme au dépôt et à la branche du mandat.

Les copies publiques ont été expurgées des préfixes de chemins personnels ; les
originaux sont conservés dans l'arbre privé. Les logs Maven bruts restent privés :
seuls leur SHA-256, résultat et commande de packaging sont publiés dans
`package-summary.json`. L'audit de 145 fichiers ne trouve aucun secret technique
de demo, JWT ou chemin personnel absolu. La transformation et les hashes
avant/après sont consignés dans `publication-audit.json` ; aucun verdict, code
de sortie ou résultat de test n'est modifié par cette expurgation.

## Périmètre normatif et commandes exécutées

Déclaration préalable : [plan de vérification](L7_Local_Playground_Grafana_Verification_Plan.md).
Slice primaire : outillage/distribution L7-LB ; slices Maven : **aucune**,
slices secondaires : **aucune**. Aucun fichier `app/`, SQL ou frontière Maven
modifié. Aucun franchissement inattendu de slice.

Commandes principales effectivement exécutées :

```sh
python3 -B -m unittest discover -s scripts/local -p 'test_*.py'
python3 -B scripts/local/sync_bruno_playground.py --check
docker buildx imagetools inspect grafana/grafana:12.3.2
docker pull grafana/grafana:12.3.2
python3 -B scripts/local/test_grafana_installation.py --instance demo --fixture grafana-validation --output docs/testing/evidence/L7-LB-GRAFANA/docker
python3 -B scripts/local/test_grafana_installation.py --instance demo --fixture grafana-validation --output docs/testing/evidence/L7-LB-GRAFANA/docker-qualified
python3 -B scripts/local/test_grafana_installation.py --instance demo --fixture grafana-final-validation --output docs/testing/evidence/L7-LB-GRAFANA/final
python3 -B scripts/local/test_grafana_installation.py --instance demo --fixture grafana-final-validation --resume-cleanup docs/testing/evidence/L7-LB-GRAFANA/final --output docs/testing/evidence/L7-LB-GRAFANA/completion
```

La campagne appelle start/status/stop/upgrade-grafana/bruno et les commandes
RESET/DESTROY **sur la fixture seulement**, avec nom et confirmation explicites.
Les argv, codes et résultats figurent dans `commands.json` ; l'appel natif
Bruno est dans `native-installed.json`. Les inspections Docker et captures SQL
sont en lecture seule, hormis le démarrage/arrêt borné de PG pour les observations.

Packaging réellement déclenché par start, commande équivalente depuis `app/` :

```sh
./mvnw -pl runtime-web-read,runtime-web-write,runtime-worker-materialize,runtime-worker-process -am package -DskipTests
```

C'est un assemblage réussi, **pas une preuve de test Maven**. Gate d'architecture
globale : **non requise, non exécutée**. Réacteur complet : **non requis, non
exécuté**, car aucun changement métier/de dépendances/module et aucun mandat
de certification globale. Bases : CURRENT conservé sur demo ; bootstrap CURRENT
sur fixture neuve/reset, aucune migration historique rejouée par défaut.

Limites : pas d'acceptation humaine des interfaces Grafana/Bruno rendues ; preuve
réelle des API, métriques, sessions et protocole natif. Pas de qualification
production/Internet, performance SLA, I1b/I4–I6 ou certification globale V3.
