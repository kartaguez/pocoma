# Observabilité locale L7-LB — Grafana

Mandat `GO_L7_LB_GRAFANA_INTEGRATION`. Cette configuration appartient uniquement
à `pocoma-local` ; elle ne change pas les profils Internet ou production ni le
Compose historique L6.

## Accès sans configuration manuelle

```sh
./pocoma-local start --instance demo
./pocoma-local status --instance demo
```

Le JSON conserve ses champs existants et ajoute :

```json
{
  "grafana": {
    "url": "http://127.0.0.1:<port>/",
    "username": "admin",
    "dashboard": "Pocoma runtime",
    "dashboardUrl": "http://127.0.0.1:<port>/d/pocoma-runtime"
  }
}
```

Ouvrir `grafana.url`, se connecter avec **admin / pocoma**, puis **Dashboards →
Pocoma**. La connexion anonyme et les inscriptions sont désactivées. Ces
identifiants publics sont une convention de développement local exclusivement.
Le port hôte est choisi sur loopback et conservé dans le manifeste de l'instance.
Les secrets techniques PG/Keycloak/Pocoma restent distincts et aléatoires.

La datasource `Prometheus` (UID exact sensible à la casse) est provisionnée vers
`http://prometheus:9090` sur le réseau Docker privé. Le `localhost` de Grafana
n'est pas utilisé pour joindre Prometheus. Les cinq jobs sont `http-write`,
`http-read`, `command-worker`, `results-worker`, `business-read-worker` ; ils
exposent leurs métriques sur `/actuator/prometheus` et sont scrappés toutes les 2 s.

## Dashboards L6 réutilisés

Les fichiers sources restent dans `docker/grafana/`. Le playground génère des
copies adaptées dans son répertoire privé : même UID, titres, panels et requêtes,
avec correction du filtre de jobs du dashboard runtime, des labels du débit de
projection (`PotVersion`, `PotBalance`, etc., plutôt que les noms d'assemblage
`Materialize…`) et normalisation de tous les UID de datasource (y compris ceux
des targets V3). Aucun nouveau dashboard.
Les profils historiques ne reçoivent aucune modification.

- **Pocoma runtime** : cinq disponibilités `up=1`, débit HTTP, mémoire JVM et CPU.
  Commencer par disponibilité et mémoire pour valider la collecte. Une valeur CPU
  nulle est une série présente ; un panel sans série constitue une observation différente.
- **Pocoma V3 - Architecture & Observability Overview** : admission, READ,
  exécution Command, discovery/claims, projections, quota SQL, Hikari, leases,
  JVM et maintenance READ. Les compteurs et histogrammes sont activés selon le
  trafic ; exécuter `./pocoma-local bruno --instance demo` pour produire du trafic
  métier puis attendre plusieurs scrapes. Choisir une fenêtre récente (5–15 min).

Les limites L6 sont conservées et explicites : les rates nécessitent au moins
deux échantillons ; les quantiles sans observation peuvent valoir `NaN` ;
certaines séries ne sont créées qu'après utilisation. Les labels de métriques
suivent le catalogue (`PotBalance`), pas les noms de participants
(`MaterializePotBalance`). Les panels de reconstruction READ n'ont pas à être actifs pendant
une utilisation normale ; leur absence ne prouve pas un défaut de collecte.
Aucun zéro artificiel (`or vector(0)`) n'est ajouté pour masquer une absence.
Le rapport de qualification précise les états réellement observés de chaque
requête ; un dashboard simplement chargé ne suffit pas à donner READY.

## Readiness et preuves

Grafana est obligatoire dans le playground :

| Contrôle | Preuve exigée | Diagnostic |
| --- | --- | --- |
| `GRAFANA-HTTP` | `/api/health` avec base OK et authentification effective d'admin via `/api/user` | `GRAFANA_START_TIMEOUT` |
| `GRAFANA-DATASOURCE` | configuration/UID/URL interne corrects, health OK, requêtes **via Grafana** vers Prometheus ; `up=1`, mémoire JVM, CPU et métriques Pocoma des cinq jobs | `GRAFANA_DATASOURCE_UNAVAILABLE` |
| `GRAFANA-DASHBOARD` | les deux UID provisionnés, panels conformes aux sources adaptées et exécution de toutes les requêtes PromQL | `GRAFANA_DASHBOARD_NOT_PROVISIONED` ou `GRAFANA_PROMQL_UNAVAILABLE` |

Un échec donne `NOT_READY`, code1. `business` garde son sens métier : il peut
rester `AVAILABLE` si Grafana seul est indisponible. `grafanaDiagnostics` fournit
les codes exploitables. Start attend les contrôles jusqu'à 60 s chacun ; status
les effectue sans attente de démarrage et ne soumet aucune commande métier.
La fraîcheur du canari reste limitée à 300 s.

Dans `.pocoma-local/instances/<id>/runs/<run>/grafana.json` : version, résultat de
l'authentification (sans mot de passe/session), datasource, jobs observés,
dashboards et résultats de panels : `PRESENT`, `ZERO`, `ABSENT`, `NONFINITE`.
Une erreur de transport/PromQL échoue au contrôle ; une série d'activité absente
est distinguée des métriques structurelles obligatoires. Les logs conteneurs
expurgés restent dans le même dossier après échec. Ne pas publier l'arbre privé.

Pour diagnostiquer : lire `grafanaDiagnostics`, puis `grafana.json` et
`containers.log`. Vérifier que Prometheus répond et collecte les cinq cibles.
Relancer `start` après correction ; ne pas réinitialiser les bases pour résoudre
une panne Grafana. Un `status` après arrêt est normalement NOT_READY.

## Anciennes instances : mise à niveau contrôlée

```sh
./pocoma-local stop --instance demo
./pocoma-local upgrade-grafana --instance demo
./pocoma-local status --instance demo
```

La commande exige une ancienne instance arrêtée, son manifeste et ses secrets.
Elle refuse les ressources étrangères, une provenance Git absente, une différence
applicative/SQL/authentification par rapport à la révision enregistrée du build,
un JAR modifié, des digests différents, une topologie ou configuration générée
inattendue. Les fichiers sources non suivis dans les chemins protégés sont aussi
bloquants. Le contrôle compare la topologie reconstruite en staging à la
configuration déployée avant toute modification de celle-ci.

Elle conserve UUID/projet, volume PostgreSQL, ports existants, comptes Keycloak,
secrets et checkpoints de démonstration. Les chemins macOS de casse différente
ne sont acceptés que s'ils désignent physiquement le même dossier ; leur graphie
initiale est conservée pour éviter une recréation inutile par Compose.
Elle ajoute le port et le service Grafana, enregistre le lien entre anciennes
et nouvelles empreintes dans `grafana-upgrade.json`, puis démarre normalement
avec un nouveau canari. Le packaging éventuel est borné aux quatre runtimes,
avec tests Maven sautés ; ce n'est pas une preuve Maven.

Une interruption après mise à jour du manifeste se reprend avec `start` si les
entrées n'ont pas changé. Les sauvegardes privées du manifeste et du Compose
antérieurs se trouvent dans le run de mise à niveau. En cas d'interruption entre
les écritures de Compose et du manifeste, conserver ces preuves et faire
réconcilier explicitement la paire de fichiers ; aucun reset ni rollback de base
n'est automatique. Ne pas modifier seulement `inputs` dans le manifeste.

Si `GRAFANA_UPGRADE_PRODUCTION_DRIFT`, `GRAFANA_UPGRADE_JAR_DRIFT`,
`GRAFANA_UPGRADE_COMPOSE_DRIFT` ou `GRAFANA_UPGRADE_CONFIG_DRIFT` apparaît, conserver
l'instance intacte. Retrouver la révision/build et les fichiers déployés pour
une migration revue. Une instance neuve, sous un autre nom, permet de qualifier
le nouveau code sans effacer l'ancienne (arrêter l'autre playground avant start).
Les modifications ultérieures d'entrées continuent de déclencher
`PRE-INPUT-DRIFT` ; la mise à niveau n'est pas un contournement général.

## Persistance et nettoyage

Grafana OSS est épinglé à `grafana/grafana:12.3.2`, puis résolu au digest natif
comme les autres images. Les manifestes publiés ARM64 et AMD64 ont été vérifiés.
[Image officielle](https://grafana.com/grafana/download/12.3.2?edition=oss&platform=docker),
[provisioning officiel](https://grafana.com/docs/grafana/latest/administration/provisioning/).

Limites : 384 Mio / 0,5 CPU, données Grafana en tmpfs de 128 Mio, UID472 ;
provisioning et dashboards en montures en lecture seule. Aucun volume persistant
Grafana, aucun volume anonyme Grafana. Le réseau et les labels de propriétaire
sont ceux de l'instance. Les copies lisibles par le conteneur restent sous la
racine d'instance privée 0700 ; les secrets techniques restent 0600.

Les sessions et modifications UI Grafana sont perdues à la recréation ou à
l'arrêt : datasource et dashboards sont reconstruits depuis le dépôt. Stop
conserve les bases PostgreSQL/Keycloak ; start reconstruit Grafana. Reset/destroy
incluent le service Grafana et conservent les protections UUID/labels ainsi que
les volumes étrangers non possédés. Destroy retire aussi les copies Grafana
générées et conserve les runs de diagnostic. Aucune commande globale prune.
