# L7-LB — qualification Zero Hidden Steps depuis un clone propre

Date : 15 novembre 2026, Europe/Paris. Mandat :
`GO_L7_LB_CLEAN_CLONE_QUALIFICATION`.

**Verdict : `L7_LB_CLEAN_CLONE_QUALIFIED`.** La distribution distante
`0a3246b9bc58bb813163caf22c6a2108c6f88db9` fonctionne sans les dix changements
Bruno du checkout historique. Aucun correctif n'a été nécessaire.

Les [preuves expurgées](evidence/l7-lb-clean-clone/2026-11-15/README.md)
accompagnent ce rapport. Leur manifeste SHA-256 permet de vérifier chaque fichier.
Cette qualification ne prononce ni la clôture formelle L7-LB ni la clôture L7.

## 1. Prérequis

Le [quickstart publié](L7_Local_Playground_Quickstart.md) a été lu dans le clone,
avec le [guide Bruno](L7_Local_Playground_Bruno.md). Les exigences annoncées
correspondent aux outils effectivement nécessaires.

| Prérequis annoncé | Observation sur l'hôte |
| --- | --- |
| macOS ARM64 | macOS 27.0, build 26A428, arm64 |
| Docker Desktop démarré, VM ≥6 Gio | client/serveur 29.9.2, Compose 5.6.1 ; VM 8 319 504 384 octets, 10 CPU |
| JDK 21 | Temurin 21+35, Java 21 effectif pour le Wrapper |
| Python ≥3.11 | 3.14.7 |
| Node 24 et npm | Node 24.15.0, npm 12.0.2 |
| Git | 2.54.0 (Apple Git-157) |
| Maven | Wrapper publié 3.9.14 ; installation Maven système non nécessaire |
| Bruno CLI | 4.3.0, installé par `npm ci` depuis le lock publié |
| Disque | 14 681 100 288 octets libres au préflight ; seuil de ce démarrage : 2 Gio |

Aucun outil hôte supplémentaire n'a été installé. La façade a installé les
dépendances npm prévues. Les caches hôte Maven/Wrapper et les images Docker
existaient déjà ; les JAR Pocoma, les sorties `target/`, l'état de l'instance et
ses bases étaient absents du clone. `resources.json` atteste `buildCached=false`.
Les quatre JAR ont été reconstruits depuis les sources du SHA testé avec `-am`.
Les versions et digests natifs des cinq images sont dans `manifest-public.json`.

Les exigences de version sont explicites dans la documentation ; ce constat
n'affirme pas que la façade impose elle-même toutes ces versions hôte. Le
résolveur système `/usr/libexec/java_home -V` ne trouve pas ce JDK SDKMAN ;
`JAVA_HOME`, `java` et le Wrapper utilisent néanmoins Java 21 sans intervention.

## 2. Procédure réellement exécutée

Le checkout historique était sur `60b12fc3cb81f42a89db0688b0406a4a986f6ffc`.
Les dix changements Bruno y étaient déjà commités, mais non publiés. Son seul
fichier non suivi était `docs/v3/V3_Command_Result_Contract_Consistency_Audit.md`.
Cet état a été préservé pendant la qualification ; aucun fichier de ce checkout
n'a alimenté l'installation propre.

Un vrai clone réseau, sans worktree ni référence Git locale, a été créé dans
`/private/tmp/pocoma-l7-clean-clone-qualification/source` :

```sh
git clone --single-branch --branch feat/l7-lb-local-playground \
  https://github.com/kartaguez/pocoma.git \
  /private/tmp/pocoma-l7-clean-clone-qualification/source
cd /private/tmp/pocoma-l7-clean-clone-qualification/source
git rev-parse HEAD
git branch --show-current
git status --porcelain=v1 --untracked-files=all
git ls-files --others --ignored --exclude-standard
```

Résultat : SHA `0a3246b9bc58bb813163caf22c6a2108c6f88db9`, branche attendue,
aucune modification, aucun fichier non suivi ou ignoré. `clone-initial.json`
conserve la provenance et les empreintes des documents suivis.

L'inventaire a identifié seulement `demo` comme playground actif. L'exclusivité
documentée a donc été respectée par cette séquence, chaque commande de façade
étant enregistrée avec son répertoire, son code de sortie et sa durée dans
`commands.json` :

```sh
# Checkout historique : préservation et suspension seulement.
./pocoma-local status --instance demo
./pocoma-local stop --instance demo

# Clone distant propre : procédure de distribution publiée.
./pocoma-local start --instance clean-clone
./pocoma-local gui --instance clean-clone
./pocoma-local bruno --instance clean-clone
./pocoma-local status --instance clean-clone
./pocoma-local stop --instance clean-clone

# Checkout historique : restauration non destructive.
./pocoma-local start --instance demo
```

Le `status` initial de `demo` retournait 1 uniquement pour un canari âgé de plus
de 300 s ; tous ses contrôles techniques, Keycloak et Grafana passaient. Ce
constat précède la qualification et n'a pas été masqué par un nouveau démarrage.

Les commandes d'installation lancées automatiquement par `start` étaient :

```sh
npm ci --prefix /private/tmp/pocoma-l7-clean-clone-qualification/source/scripts/bruno-v3
/private/tmp/pocoma-l7-clean-clone-qualification/source/app/mvnw \
  -f /private/tmp/pocoma-l7-clean-clone-qualification/source/app/pom.xml \
  -pl runtime-web-read,runtime-web-write,runtime-worker-materialize,runtime-worker-process \
  -am package -DskipTests
```

Le packaging a réussi en 8,857 s. C'est une preuve d'assemblage, pas un PASS de
tests Maven. Le log lisible retire seulement les espaces de fin de ligne ;
`package.raw.txt.gz` conserve les octets originaux avec roundtrip et SHA-256
vérifiés dans `packaging-evidence-encoding.json`. La commande Bruno exacte et ses options figurent dans
`clean-bruno/bruno-receipt.json` : moteur verrouillé 4.3.0, environnement `Local`,
sandbox `developer`, parcours récursifs et rapport JUnit frais.

Les collecteurs supplémentaires ont uniquement relevé les versions, inventorié
Docker, calculé des empreintes et copié une liste explicite de reçus publics.
L'orchestration conservée comme preuve n'est pas une dépendance du produit.

## 3. Résultats

| Critère | Résultat et preuve |
| --- | --- |
| Clone réellement distant et propre | PASS — `clone-initial.json`, puis `clone-after-run.json` |
| Démarrage | READY, exit 0, 63,067 s — `clean-start/receipt.json` |
| Cinq JVM | Les cinq contrôles `JVM-*` passent ; conteneurs et génération consignés |
| PostgreSQL WRITE/READ | `DB-CURRENT=PASS`, bootstrap CURRENT neuf et grants contrôlés |
| Couverture des partitions | 8/8 command, 8/8 results, 20/20 business-read ; métriques locales corroborées par SQL — `clean-start/worker-recovery.json` |
| Génération qualifiée | `fd4e943dc5ef1dbd68847ff71bde9a60ced934aeeaf9bc7ea60ae186d8ed5052`, identique à celle du canari et du status |
| Canari | Nouvelle version V2, `runId=1794740465-d1773951`, puis status READY et preuve fraîche |
| Keycloak | Discovery, admin master, comptes activés et Code+PKCE S256 pour third/guest/bruno : PASS — `clean-start/keycloak-auth.json` |
| Environnement GUI | `GUI_CONFIGURED`, URLs de la seule instance clean-clone ; aucune session historique importée |
| Bruno J01/J02/J03 | BRUNO_PASS : 118 requêtes, **236 tests nommés**, 731 assertions, 221 requêtes HTTP physiques avec polls ; zéro échec/erreur/skip |
| Prometheus | Cinq targets UP et métriques réelles JVM/Pocoma disponibles ; `qualificationTools=AVAILABLE` |
| Grafana | Authentification admin, datasource provisionnée et saine, deux dashboards provisionnés, requêtes PromQL exécutées : PASS — `clean-start/grafana.json` |
| Status après Bruno | READY, exit 0 ; même génération qualifiée et canari frais |
| Arrêt clean-clone | STOPPED, exit 0 ; cinq JVM exit 143, aucun SIGKILL ; neuf conteneurs arrêtés, données et volumes conservés |

Le JUnit conserve exactement 66/31/21 requêtes et 132/62/42 tests respectivement
pour J01/J02/J03. Les reçus vérifient aussi les identités de tests, leurs
assertions non nulles, les méthodes, chemins, statuts et la fraîcheur des preuves.
Le SHA de la collection exécutée est `0a3246b9`, avec `dirty=false`.

Les URLs retournées et effectivement exercées pendant cette qualification sont :

| Service | URL observée |
| --- | --- |
| WRITE | `http://127.0.0.1:59247` |
| READ | `http://127.0.0.1:59248` |
| Issuer | `http://localhost:59249/realms/pocoma` |
| Console Keycloak | `http://localhost:59249/admin/` |
| Prometheus | `http://127.0.0.1:59255` |
| Grafana | `http://127.0.0.1:59256/` |
| Dashboard runtime | `http://127.0.0.1:59256/d/pocoma-runtime` |

Ces ports appartiennent à l'instance arrêtée ; une autre installation doit lire
ses propres URLs dans la sortie de la façade. Aucun port PostgreSQL n'est exposé.

## 4. Écarts documentaires

**Aucun écart bloquant ou étape de réparation du quickstart.** Le clone nommé et
`--instance clean-clone` appliquent les variantes documentées ; la suspension de
`demo` applique le contrat d'exclusivité et le mandat de préservation.

Les restrictions de l'agent ont nécessité une exécution hors sandbox pour le
réseau Git, Docker et les connexions loopback. Ce sont des contraintes de
l'environnement de qualification, pas des dépendances supplémentaires du produit.
Une première tentative de clone a échoué sur la résolution DNS en sandbox ;
elle a été remplacée par le même vrai clone distant hors sandbox.

Le contrôle supplémentaire de 77 tests Python a d'abord rencontré six erreurs
`PermissionError` sur les ports loopback en sandbox. Le même ensemble a ensuite
passé hors sandbox : 77 tests, zéro échec/erreur/skip. Les deux sorties sont
conservées ; aucune assertion ni attente n'a été changée.

## 5. Corrections et publication

Aucune correction du quickstart, du packaging, des scripts, des collections ou
de l'application. La publication contient exclusivement ce rapport et son dossier
de preuves, sur `feat/l7-lb-local-playground`, dans un commit descendant direct
du SHA qualifié. Aucun composant exécutable n'est modifié ; une requalification
d'un correctif est donc sans objet.

Le SHA qualifié désigne la distribution testée ; le SHA de publication désigne
le commit documentaire contenant ce rapport. Ce dernier est donné par le reçu
de push et par `git log -1 --format=%H -- docs/v3/L7_Local_Playground_Clean_Clone_Qualification_Report.md`.
Les dix changements locaux de `60b12fc3` ne sont pas inclus dans cette publication.
Le checkout historique reste sur son HEAD initial ; sa branche diverge donc
intentionnellement de la branche distante documentaire, sans rebase ni merge.

## 6. Non-régressions et périmètre de vérification

Périmètre déclaré avant exécution : **distribution locale et documentation**.
Slices Maven primaires/secondaires : **NONE** ; assemblage distribué des racines
WEB, COMMAND et PROJECTION via la commande de packaging ci-dessus. Impact
productif attendu et observé : aucun. Impact interdit : `app/`, contrats
COMMAND/RESULT, moteur asynchrone, leases et fencing.

Commande ciblée supplémentaire effectivement exécutée depuis le clone :

```sh
python3 -B -m unittest scripts/local/test_local.py scripts/local/test_recovery.py \
  scripts/local/test_keycloak.py scripts/local/test_grafana.py \
  scripts/local/test_bruno_qualification.py
```

Résultat hôte : **77/77 PASS**. `verification.json` conserve la commande, le
résultat initial empêché par la sandbox et le résultat du rejeu hôte.
`source-input-hashes.json` consigne les empreintes des sources examinées ;
`manifest-public.json` celles des JAR, entrées de distribution et images.

- Franchissement inattendu de slice : **NONE**.
- Gate d'architecture globale : **NOT REQUIRED**, aucune frontière ni dépendance modifiée.
- Réacteur Maven complet : **NOT RUN / NOT REQUIRED**, aucune clôture globale sous ce mandat.
- Base de données : **CURRENT**, bootstrap V3 publié sur nouvelles bases ; aucune migration historique rejouée ni baseline TRUSTED supposée.
- Tests Maven : **NOT RUN** ; `-DskipTests` reste explicitement une étape de packaging.

`preservation.json` prouve que les 26 conteneurs, 10 volumes et 6 réseaux
préexistants restent présents. Les 17 conteneurs étrangers à `demo` gardent leurs
identités et états. Les neuf nouveaux conteneurs clean-clone sont arrêtés ; son
volume de données et son volume Prometheus restent conservés. Aucun reset,
destroy, down, clean Git ou nettoyage Docker global n'a été exécuté.

La restauration de `demo` a retrouvé les 36 partitions après **134,503 s** et
109 observations dans le budget inchangé de 270 s, sans contourner les baux.
Ses conteneurs, son réseau et son volume de données gardent leurs identités.
Ses fichiers `secrets.json`, `demo.json` et `compose.json` gardent leurs
empreintes initiales. Le manifeste reflète le nouveau démarrage ; le même Pot
canari avance normalement de V17 à V18. État observé après restauration :
**READY**, génération qualifiée, Keycloak/Prometheus/Grafana disponibles.
La préservation est établie par rétention des ressources et checkpoints, sans
prétendre à une comparaison exhaustive de chaque ligne des bases historiques.

## 7. Réserves et limites

Aucune réserve bloquante propre au clone et aucune dépendance aux dix changements
Bruno historiques n'a été constatée. L'exécution complète a gardé le source
propre jusqu'à l'ajout final de ce rapport et des preuves.

Les caches partagés ne constituent pas une qualification de deux hôtes neufs ou
de tous les téléchargements à froid. La présente mission prouve un clone Git
indépendant sur l'hôte documenté, sans artefact Pocoma préconstruit ni volume
historique. L'environnement GUI a été généré ; aucun nouveau rendu GUI ni
nouvelle recette humaine n'est revendiqué. L'acceptation humaine antérieure de
la collection reste attribuée à sa campagne.

Le report des incohérences COMMAND/RESULT reste inchangé, comme les autres
[réserves historiques](L7_Local_Playground_Reservations.md). `fullyQualifiable`
reste false conformément au jalon : les campagnes I4–I6, la capacité exhaustive,
les quarantaines et les gates globales ne sont pas closes par ce rapport.

## 8. Verdict

Tous les critères de la mission clean-clone sont satisfaits : source distant
identifié et propre, quickstart suffisant, READY, 36 partitions, canari et
génération qualifiés, 236 tests Bruno réussis, authentification et observabilité
opérationnelles, arrêt non destructif, `demo` restaurée et ressources préservées.
Les preuves reproductibles et expurgées sont publiées avec le rapport.

**L7_LB_CLEAN_CLONE_QUALIFIED**
