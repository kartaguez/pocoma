# Preuves L7-LB clean-clone — 15 novembre 2026

Distribution testée : `0a3246b9bc58bb813163caf22c6a2108c6f88db9`, branche
`feat/l7-lb-local-playground`, vrai clone réseau depuis
`https://github.com/kartaguez/pocoma.git`.

[Rapport de qualification](../../../L7_Local_Playground_Clean_Clone_Qualification_Report.md).

## Lire et vérifier les preuves

Depuis ce dossier :

```sh
shasum -a 256 -c SHA256SUMS
```

Les empreintes garantissent l'intégrité des octets conservés, sans prétendre
authentifier cryptographiquement leur producteur. Les chemins absolus et temps
Unix des reçus désignent l'hôte et l'invocation effectivement observés.

| Fichier ou dossier | Objet |
| --- | --- |
| [clone-initial.json](clone-initial.json) | Clone distant, branche/SHA, arbre propre sans fichier ignoré, config Git locale et empreintes des guides |
| [clone-after-run.json](clone-after-run.json) | Source resté propre après tout le cycle, avant ajout du rapport et des preuves |
| [host-prerequisites.json](host-prerequisites.json), [maven-wrapper-version.txt](maven-wrapper-version.txt) | Versions réellement disponibles ; Maven système et Wrapper distingués |
| [host-cache-context.json](host-cache-context.json), [resources.json](resources.json) | Caches préexistants, réglages hôte sous forme d'empreinte, mémoire et disque |
| [commands.json](commands.json) | Commandes de façade avec cwd, argv, exit, durée et résultat JSON |
| [manifest-public.json](manifest-public.json), [package.txt](package.txt) | Provenance du build, commande Maven, empreintes des JAR et digests d'images ; espaces de fin de ligne retirés du log lisible |
| [package.raw.txt.gz](package.raw.txt.gz), [packaging-evidence-encoding.json](packaging-evidence-encoding.json) | Octets originaux du log Maven conservés sans perte, hashes et roundtrip vérifié |
| [source-input-hashes.json](source-input-hashes.json) | Empreintes des sources applicatives, SQL et outils examinés |
| [clean-start](clean-start/receipt.json), [clean-status](clean-status/receipt.json) | READY, contrôles techniques, canari, génération et outils disponibles |
| [worker-recovery.json](clean-start/worker-recovery.json) | 36 partitions : métriques locales et propriétés SQL ; génération du premier démarrage |
| [keycloak-auth.json](clean-start/keycloak-auth.json) | Admin et trois comptes locaux, comptes activés et Code+PKCE |
| [grafana.json](clean-start/grafana.json) | Santé/admin, datasource, cinq JVM, dashboards et requêtes réelles des panels |
| [clean-gui-config](clean-gui-config/receipt.json) | Environnement public généré pour l'instance, sans session importée |
| [Bruno receipt](clean-bruno/bruno-receipt.json), [JUnit](clean-bruno/bruno.xml), [scénarios](clean-bruno/scenarios.jsonl) | 118 requêtes, 236 tests nommés, 731 assertions et traces méthode/chemin/statut |
| [clean-stop](clean-stop/receipt.json) | Arrêt gracieux des JVM, exit 143 sans SIGKILL, volumes préservés |
| [docker-after-clean-stop.json](docker-after-clean-stop.json) | Neuf conteneurs clean-clone arrêtés |
| [docker-before.json](docker-before.json), [docker-after.json](docker-after.json) | Inventaires en lecture seule de l'ensemble des ressources ; les plans de destruction imprimés par l'inventaire n'ont pas été exécutés |
| [docker-initial-snapshot.json](docker-initial-snapshot.json), [docker-final-snapshot.json](docker-final-snapshot.json) | Identités et états Docker avant/après, sans export des environnements de conteneurs |
| [preservation.json](preservation.json), [historical-before.json](historical-before.json) | Checkout historique intact pendant le cycle, ressources retenues, empreintes des seuls fichiers privés (sans leur contenu) |
| [demo-before-status.json](demo-before-status.json), [demo-stop](demo-stop/receipt.json), [demo-restored](demo-restored/receipt.json) | État initial avec canari périmé, arrêt, puis READY avec canari V18 et récupération des baux |
| [demo-final-status.json](demo-final-status.json) | Dernier status de contrôle après restauration |
| [verification.json](verification.json), [tests hôte](distribution-tests-host.txt), [tests sandbox](distribution-tests.txt) | 77/77 tests hôte ; six erreurs de bind en sandbox conservées séparément |
| [redaction-check.json](redaction-check.json) | Contrôle d'absence des secrets techniques générés dans l'export allowlisté |
| [qualification-orchestration.py.txt](qualification-orchestration.py.txt) | Orchestration de collecte réellement utilisée, hors source ; pas un prérequis d'installation |

Les fichiers `*.stdout.txt`/`*.stderr.txt` conservent les sorties de la façade.
Ils ne contiennent pas la console Bruno, volontairement écartée par le lanceur
publié. Aucun secret technique, JWT, body HTTP, état privé d'instance, dump de
base, configuration contenant des credentials ou log brut de conteneur n'est
exporté. La convention des comptes locaux, déjà publique, reste dans les reçus.

## Reproduire

Cloner réellement origin dans un nouveau répertoire, identifier le SHA voulu
(`0a3246b9` pour ce constat), vérifier l'arbre initial puis appliquer les commandes
du rapport. L'installation hôte suit les prérequis du quickstart ; aucun JAR
Pocoma ne doit être copié. Lire les nouvelles URLs dans la sortie de `start`.
Les noms/ports/UUID ci-dessus sont propres à l'invocation conservée.

Le collecteur possède des chemins absolus pour l'audit de cette exécution. Il
ne faut pas le relancer sur les mêmes instances conservées. Les commandes
publiées suffisent au développeur ; la collecte et les tests supplémentaires
n'ont réparé aucun prérequis ou état du produit.

Les caches de dépendances/images étaient présents. Aucun téléchargement à
froid sur un second hôte, rendu GUI ou gate Maven global n'est revendiqué.
Le commit contenant ces preuves et le rapport ajoute seulement de la
documentation ; il ne remplace pas le SHA de distribution effectivement testé.
