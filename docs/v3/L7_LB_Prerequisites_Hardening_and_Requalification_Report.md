# L7-LB — Durcissement des prérequis et requalification

Date : 15 novembre 2026 (Europe/Paris). Mandat :
`GO_L7_LB_PREREQUISITES_HARDENING`. Base :
`cd9b48351a59d60be6aedb225a50e78cecaf9382`.

**Verdict : `L7_LB_PREREQUISITES_HARDENED_AND_QUALIFIED`.**
SHA corrigé publié puis qualifié depuis un clone réseau neuf :
`da7912e3707a72c801c39c08858f65e801a508cb`.
Les [preuves expurgées](evidence/l7-lb-prerequisites/2026-11-15/README.md)
accompagnent ce rapport. La publication finale ajoute uniquement ces preuves et
la mise à jour documentaire, sans changement des entrées exécutables qualifiées.
La qualification historique `L7_LB_CLEAN_CLONE_QUALIFIED` reste acquise ; aucune
clôture L7-LB n'est prononcée.

## Périmètre et corrections

Les trois écarts P1 de l'[audit](L7_LB_Prerequisites_and_Installation_Diagnostics_Audit.md)
sont traités localement :

- **PRE-01** : le lanceur détecte Python absent ; `entrypoint.py`, lisible par
  un ancien Python3, vérifie la version active avant import du runtime et contrôle
  `fcntl`. Le minimum 3.11 provient du contrat de distribution déjà annoncé au
  quickstart, maintenant centralisé dans `toolchain.json`. Il s'agit d'un minimum
  supporté, pas d'une affirmation que toute ligne du runtime échouerait en 3.10.
- **PRE-02** : contrôles sans écriture de Git, Docker CLI, accès au daemon,
  plugin et capacités Compose, Node24, npm conditionnel, Java21 et JDK/javac
  effectifs si build requis. Les ressources sont contrôlées avant création de
  l'état local ; les commandes sans construction ou Bruno n'exigent pas ces outils.
- **PRE-03** : validation des fichiers distribués et des références SQL du helper
  CURRENT, des manifestes/ports et des secrets ; vérification des artefacts et
  configurations de reprise (images, issuer, ports, mots de passe et clés).
  Une instance partielle est refusée, ses fichiers restent intacts.

Fichiers exécutables modifiés/ajoutés : `pocoma-local`,
`scripts/local/{entrypoint,prerequisites,runtime}.py`, `scripts/local/toolchain.json`.
Tests : nouveau `test_prerequisites.py`, adaptations des créations explicites dans
`test_grafana.py` et `test_installation.py`, et refus d'une configuration incomplète
dans `test_keycloak.py`. Documentation : quickstart et ce rapport.
Aucun changement sous `app/`, Docker, Bruno ou dans les contrats métier.

Le contrat npm≥7 correspond au format lock v3 disponible depuis npm7 ; ce n'est
pas une qualification de toutes ces versions. La [documentation npm v8](https://docs.npmjs.com/cli/v8/configuring-npm/package-lock-json/)
décrit ce format ; le profil d'installation éprouvé reste Node24/npm12. Les
cinq images existantes restent inchangées (la mention « six » dans la phase D de
l'audit est une erreur de comptage documentaire).

## Invariants de sûreté

`prepare` vérifie la configuration, les outils, les ressources et la propriété
avant les répertoires, locks, manifeste, dépendances, images ou build de l'instance.
La configuration est relue sous verrou avant écriture. Un refus de prévalidation
renvoie exit2 sans dossier de preuves ; aucune dépendance n'est installée pour
« réparer » ce refus. Les états NEW interrompus avec secrets valides peuvent être
repris ; les secrets perdus ne sont pas recréés.

`private()` lit et valide par défaut. Seules les branches explicites de création
et de reset confirmé peuvent demander `create=True` ; la création utilise une
ouverture exclusive et refuse un état déjà déployé. Les chemins de reprise,
Bruno et collecte de logs n'ont aucune autorisation de création. Un échec de
démarrage ne remplace pas les secrets existants. L'arrêt reste disponible sans
secrets ; les confirmations et protections du reset/destroy sont conservées.

Les dix changements Bruno locaux et le document non suivi préexistants restent
dans le checkout historique `60b12fc3`, sans être publiés. Implémentation et
publication sont isolées dans le worktree basé sur le SHA de référence.

## Diagnostics et configurations

Python absent ou incompatible : stderr explicite, version requise/détectée et
action, exit2, avant import du runtime. Les autres refus gardent la façade JSON :

```json
{
  "state": "NOT_READY",
  "exit": 2,
  "control": "PRE-SECRETS-MISSING",
  "evidence": null,
  "environment": "PRESERVED",
  "diagnostic": {
    "control": "PRE-SECRETS-MISSING",
    "required": "Original secrets.json for this instance",
    "detected": "secrets absent",
    "recommendedAction": "Restore the original secrets from backup; preserve data. Secrets will not be regenerated."
  }
}
```

| Code | Cause et correction |
| --- | --- |
| `PRE-TOOL-MISSING` / `PRE-TOOL-UNAVAILABLE` | Outil absent/non exécutable ou sonde expirée ; installer/sélectionner l'outil nommé. |
| `PRE-TOOL-FAILED` | Sonde en échec ; `required` distingue daemon, plugin ou capacités Compose. Vérifier Docker démarré, contexte et droits ; le stderr brut n'est pas publié. |
| `PRE-TOOL-VERSION` / `PRE-NPM-VERSION` | Version incompatible ou illisible ; attendu/détecté et sélection du profil supporté. |
| `PRE-WRAPPER` / `PRE-TOOLCHAIN-ENV` / `PRE-UNREVIEWED-MAVEN-CONFIG` | Wrapper non exécutable ou environnement/configuration Maven non revue ; restaurer le Wrapper ou retirer les options non contractuelles. |
| `PRE-CONFIG-FILE` / `PRE-PARTIAL-INSTANCE` | Fichier absent, JSON invalide ou déploiement incomplet ; restaurer les entrées d'origine, conserver les données. |
| `PRE-MANIFEST-IDENTITY` / `PRE-CONFIG-PORTS` / `PRE-CONFIG-ISSUER` | Identité ou endpoints incohérents ; ne pas réécrire le manifeste pour contourner le garde. |
| `PRE-SECRETS-MISSING` / `PRE-SECRETS-INVALID` / `PRE-CONFIG-SECRETS` | Secrets absents, invalides ou différents des configurations consommées ; restauration des originaux, jamais régénération implicite. |
| `PRE-CONFIG-JAR` / `PRE-CONFIG-BUILD` / `PRE-CONFIG-COMPOSE` | Artefact, provenance ou topologie incohérents ; préserver l'instance, inspecter les sources et sauvegardes. |

Les contrôles ne publient ni valeurs de secrets, ni stdout/stderr non filtré des
sondes, ni environnement complet. Les refus de ressources et propriété déjà
contractés restent conservés. Aucune commande `doctor` n'est ajoutée.

## Tests et vérification déclarée

Impact productif applicatif attendu/observé : **NONE**. Slice principale :
**LOCAL DISTRIBUTION** (outillage hors reactor) ; slices Maven primaire/secondaires :
**NONE**. Niveau opérationnel : tests locaux puis INTEGRATION distribution.
Gate globale d'architecture : **NOT REQUIRED**. Full reactor : **NOT REQUIRED / NOT
RUN**. Franchissement inattendu : **NONE**. Interdits : contrats COMMAND/RESULT,
code métier, graphe Maven, leases/fencing et migrations historiques.
Qualification base : CURRENT V3 uniquement dans une instance neuve lors de B.

Commande A : `python3 -B -m unittest discover -s scripts/local -p 'test_*.py'`.
Résultat : **120 tests, zéro failure/error/skip**, dont **40 nouveaux**. Les cas
négatifs utilisent uniquement mocks, sockets temporaires ou répertoires isolés.
Ils vérifient : ancien/nouveau Python, absence d'interpréteur, aide sans sondes,
versions Java/Node/npm, différence PATH/JAVA_HOME, daemon/plugin/capacités Compose,
CLI locale et cache sans dépendances inutiles, fichiers/ports/manifeste invalides,
création initiale, réutilisation exacte et refus de régénération des secrets,
préservation des octets et modes après refus, ordre avant écritures et mutateurs.

La première passe de régression, avant ajout de la nouvelle suite, a eu six erreurs
sur 80 tests : sockets loopback interdits par le sandbox. La suite a été exécutée
avec cet accès ; ce n'est pas un défaut produit. Une passe intermédiaire 113 tests
a réussi, puis119 et 120 tests ont réussi. Un ResourceWarning non bloquant
provient de la fixture HTTPError d'un test Keycloak existant. Les sondes réelles
`prerequisites.sources` / `prerequisites.tools(..., 'start')` passent sur l'hôte,
sans installation ni activation. `bash -n pocoma-local` et `git diff --check`
complètent la revue du périmètre et des fichiers à publier.

## Qualification B — clone propre du SHA publié

Commande réelle, après publication des corrections :

```sh
git clone --depth 1 --single-branch --branch feat/l7-lb-local-playground \
  https://github.com/kartaguez/pocoma.git \
  /private/tmp/pocoma-l7-lb-hardening-qualification/source
```

Le clone pointe sur `da7912e3707a72c801c39c08858f65e801a508cb`, branche attendue,
aucun fichier modifié/non suivi/ignoré avant lancement. Aucun `.pocoma-local`,
`node_modules` ou `target/` initial. Les caches hôte Maven/Wrapper et images sont
partagés ; `resources.json` atteste `buildCached=false`, environ 8,41 Gio libres
sur le filesystem du checkout et VM Docker de 8 319 504 384 octets / 10 CPU.

Depuis ce clone, les commandes de façade ont été :

```sh
./pocoma-local start --instance hardening-qualified
./pocoma-local bruno --instance hardening-qualified
./pocoma-local status --instance hardening-qualified
./pocoma-local stop --instance hardening-qualified
```

| Preuve réelle | Résultat |
| --- | --- |
| Installation/start | Exit0, **READY**, business/technical AVAILABLE, 62,86 s ; dépendances npm verrouillées installées et quatre JAR construits. |
| Build | Wrapper 3.9.14 ; `-f <clone>/app/pom.xml -pl runtime-web-read,runtime-web-write,runtime-worker-materialize,runtime-worker-process -am package -DskipTests`. Assemblage réussi, aucun PASS de tests Maven revendiqué. Log gzip sans perte vérifié par SHA-256. |
| Couverture | `RECOVERED` en 1,13 s après readiness ; **8 / 8 / 20** baux locaux utilisables et 36 partitions corroborées par SQL. |
| Canari/auth/outillage | Canari actuel lié à la génération ; Keycloak/PKCE, Prometheus et Grafana disponibles, tous les contrôles PASS. |
| Bruno J01/J02/J03 | Exit0, **236 tests nommés**, **731 assertions**,220 requêtes HTTP, inventaire exact et JUnit frais ; durée 10,43 s. |
| Status final du clone | Exit0, **READY**, preuve actuelle ; 3,52 s. |
| Arrêt | Exit0, **STOPPED**, cinq JVM arrêtées (codes 143), aucune mort forcée ; 3,20 s ; volumes conservés. |
| Source après parcours | Toujours au même SHA, statut Git suivi/non suivi vide. Seuls les artefacts générés ignorés attendus existent. |

Les commandes, reçus, générations, inventaire Bruno et ressources sont archivés
avec le [manifeste de preuves](evidence/l7-lb-prerequisites/2026-11-15/SHA256SUMS).
Le collecteur n'est pas une dépendance de l'installation. Les valeurs techniques
réelles des deux instances et les formes JWT ont été recherchées dans les preuves
avant publication ; aucune fuite détectée. Aucun log de configuration, secret,
body HTTP privé ou ligne SQL sensible n'est publié.

## Préservation de demo

La VM ne permet pas deux playgrounds simultanés selon le contrat existant.
L'humain a explicitement autorisé la suspension temporaire et la reprise de
`demo`. Son Compose existant a arrêté les cinq JVM avec timeout 45 s, puis les
dépendances ; il a ensuite redémarré **les mêmes conteneurs**, PG, Keycloak puis
runtimes/outillage. Aucun `pocoma-local start` sur demo, aucun reset/destroy/down,
aucune suppression de volume, aucun rendu de configuration ou secret généré.

La reprise a attendu les baux existants sans les modifier : couverture de 36 partitions
rétablie en 149,82 s d'observation. Les 9 conteneurs sont RUNNING, avec mêmes IDs,
images et volumes. Les 25 fichiers privés hors runs/lock sont identiques en octets
et modes, dont secrets, configurations, JAR, manifeste et checkpoints.

Les comparaisons SQL ont gardé les empreintes en mémoire : 4 utilisateurs,
4 credentials,2 realms,14 clients,29 identités Pot,29 Pots READ,154 événements et
l'empreinte agrégée des rôles SQL identiques. Les volumes préexistants sont tous
retenus. Il ne s'agit pas d'une comparaison exhaustive de toutes les tables.
Le checkout historique et ses fichiers non suivis sont restés identiques pendant
la campagne ; ses dix changements Bruno n'ont pas alimenté le clone.

**État final de demo :** 9 conteneurs actifs, 36 partitions, `technical=AVAILABLE`,
`qualificationTools=AVAILABLE`, tous les contrôles techniques/auth/Grafana PASS.
Avant campagne, son canari était déjà périmé : `NOT_READY / 1`,
`business=UNPROVEN`, `proofFreshness=STALE_OR_MISSING`.
Le même verdict de fraîcheur est conservé après reprise ; la génération a changé,
donc `generationQualified=false`. Le canari et les données n'ont pas été avancés
pour fabriquer un nouveau READY. Ce manque de preuve métier fraîche est explicite,
sans l'assimiler à une indisponibilité technique ni à un échec d'installation du
clone. Les reçus avant/après et les comparaisons figurent dans
[preservation.json](evidence/l7-lb-prerequisites/2026-11-15/preservation.json).

## Vérification et publication finales

Commandes exécutées : suite Python, sondes réelles en lecture seule des sources
et outils, `bash -n pocoma-local`, `git diff --check`, inventaire du périmètre
indexé, commit/push du SHA corrigé, clone réseau ci-dessus, quatre commandes de
façade, suspension/reprise Compose autorisées, requêtes SQL de lecture/comparaison,
validation des reçus et du gzip, contrôles de secrets/JWT et des SHA-256.
Le seul Maven exécuté est le packaging automatique du start du clone ; aucun
reactor de tests, `architecture-tests`, migration V2 historique ou gate globale.

Les slices Maven déclarées restent **NONE**, primaire/secondaires **NONE** ;
impact applicatif attendu/observé **NONE**, franchissement inattendu **NONE** ;
gate globale **NOT REQUIRED**, full reactor **NOT RUN**, car seules la façade
et ses contrôles locaux changent. Le bootstrap CURRENT et les API publiques ont
été exercés physiquement dans la fixture neuve ; cela ne ferme pas les campagnes
globales historiques. Le commit final de preuves est distinct du SHA exécutable
qualifié et son identifiant est fourni dans la restitution.

## Réserves et décision

La validation ciblée n'est ni un validateur universel de propriétés/SQL/YAML,
ni une garantie de capacité sous pression, ni une qualification Windows/Linux/AMD64.
Les réserves P2 de l'audit (ports, espace de la VM/caches, confort des diagnostics)
et P3 doctor restent hors du lot. Les contraintes réseau et caches restent celles
du parcours qualifié ; aucun test négatif d'outillage n'est effectué sur demo.

Le parcours corrigé et les invariants P1 sont implémentés, testés et qualifiés sur
le SHA indiqué. Les versions incompatibles sont testées par simulation ; aucune
qualification sur un vrai binaire Python 3.11 ni sur un autre OS n'est revendiquée.

**`L7_LB_PREREQUISITES_HARDENED_AND_QUALIFIED`**
La décision de clôture L7-LB reste distincte et humaine.
