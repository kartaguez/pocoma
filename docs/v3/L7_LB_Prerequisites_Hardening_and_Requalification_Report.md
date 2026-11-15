# L7-LB — Durcissement des prérequis et requalification

Date : 15 novembre 2026 (Europe/Paris). Mandat :
`GO_L7_LB_PREREQUISITES_HARDENING`. Base :
`cd9b48351a59d60be6aedb225a50e78cecaf9382`.

État après tests ciblés, avant publication et qualification :
**`L7_LB_PREREQUISITES_HARDENED_NOT_REQUALIFIED`**.
Ce rapport sera complété après le clone réseau du SHA corrigé publié.
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
a réussi, puis119 et120tests ont réussi. Un ResourceWarning non bloquant
provient de la fixture HTTPError d'un test Keycloak existant. Les sondes réelles
`prerequisites.sources` / `prerequisites.tools(..., 'start')` passent sur l'hôte,
sans installation ni activation. `bash -n pocoma-local` et `git diff --check`
complètent la revue du périmètre et des fichiers à publier.

## Qualification B — en attente de publication du SHA corrigé

Après publication : clone réseau neuf, build normal de la distribution
`-pl runtime-web-read,runtime-web-write,runtime-worker-materialize,runtime-worker-process -am package -DskipTests`,
installation, start READY, couverture de 36 partitions, canari, Bruno J01/J02/J03,
arrêt propre et vérification de préservation de demo. Ce packaging est un
assemblage, aucune preuve de tests Maven.

La VM Docker dispose d'environ 8 Gio et demo est active ; une seconde stack est
refusée par le contrat de ressources. L'humain a autorisé sa suspension temporaire
et sa reprise, en conservant volumes, secrets et configurations à l'identique.
Aucun reset, destroy, suppression de volume ou corruption ne sera utilisé.

## Réserves et décision

La validation ciblée n'est ni un validateur universel de propriétés/SQL/YAML,
ni une garantie de capacité sous pression, ni une qualification Windows/Linux/AMD64.
Les réserves P2 de l'audit (ports, espace de la VM/caches, confort des diagnostics)
et P3 doctor restent hors du lot. Les contraintes réseau et caches restent celles
du parcours qualifié ; aucun test négatif d'outillage n'est effectué sur demo.

Verdict provisoire : **`L7_LB_PREREQUISITES_HARDENED_NOT_REQUALIFIED`**.
La décision de clôture L7-LB reste distincte et humaine.
