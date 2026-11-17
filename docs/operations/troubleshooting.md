# Diagnostic et dépannage

Ce guide recense les vérifications de premier niveau, les états opérationnels et de qualification, les codes d'erreur et les limites connues de la distribution locale [`./pocoma-local`](../reference/cli.md).

## 1. Commande de diagnostic et codes de sortie

Commencez toujours par interroger l'état de l'instance :

```bash
./pocoma-local status --instance demo
```

Si une commande antérieure a expiré et vous a fourni un identifiant `--run` (`[0-9]{10}-[0-9a-f]{8}`) :

```bash
./pocoma-local status --instance demo --run <run-id>
```

| Code | Signification | Action immédiate |
| :---: | :--- | :--- |
| `0` | `READY` / commande réussie | Instance opérationnelle et canari validé |
| `1` | `NOT_READY` / `STOPPED` / `STALE` / erreur runtime | Consulter le message retourné, `operationalState` / `qualificationState` et les journaux sous `.pocoma-local/instances/<instance>/` |
| `2` | Erreur d'usage, refus de prérequis (`PRE-*`), dérive ou schéma incompatible (`DB-*` / `INCOMPATIBLE-*`) | Corriger le poste ou choisir `reset` / `destroy` / nouveau `--instance` |
| `3` | Conflit de verrou (`PRE-INSTANCE-LOCKED`, `PRE-LIFECYCLE-LOCKED`, `PRE-BUILD-LOCKED`) | Attendre la fin de l'opération concurrente en cours |
| `4` | `DEGRADED` | API métier et canari OK, mais Prometheus indisponible ; inspecter `prometheus` / `grafana` |
| `130` | Interruption clavier (`Ctrl+C`) | Relancer `status` pour vérifier l'état résultant |

## 2. États opérationnels et états de qualification

`./pocoma-local status` distingue explicitement la santé courante des services (`operationalState`) de la fraîcheur de la preuve canari (`qualificationState`) :

| Champ | Valeurs | Signification |
| :--- | :--- | :--- |
| `operationalState` | `OPERATIONAL` | Les 9 conteneurs, PostgreSQL, Keycloak, les 5 JVM (sans `draining` ni capacité épuisée) et Prometheus répondent |
| `operationalState` | `DEGRADED_OBSERVABILITY` | Cœur applicatif sain, mais Prometheus indisponible (`code 4`) |
| `operationalState` | `UNAVAILABLE` | Au moins un service critique (PostgreSQL, Keycloak, runtime web ou worker en `draining`) est indisponible |
| `qualificationState` | `QUALIFIED` | Preuve canari fraîche (`≤ 300 s`) et génération de conteneurs cohérente |
| `qualificationState` | `STALE` | Services opérationnels, mais preuve canari expirée (`> 300 s`, `code 1`) ; relancez `./pocoma-local start` pour rafraîchir |
| `qualificationState` | `GENERATION_MISMATCH` | Redémarrage partiel détecté entre générations de conteneurs ; relancez `./pocoma-local start` |
| `qualificationState` | `UNQUALIFIED` | Aucune preuve canari valide disponible |

## 3. Refus de prérequis (`PRE-*`)

| Symptôme / Code | Cause vérifiée | Résolution |
| :--- | :--- | :--- |
| Échec prérequis toolchain | Docker arrêté ou version hors contrat ([`scripts/local/toolchain.json`](../../scripts/local/toolchain.json) : Python ≥ `3.11`, JDK `21`, Node `24`, npm ≥ `7`, Bruno `4.3.0`) | Démarrer Docker ; ajuster JDK/Node/Python dans le shell courant |
| Variable globale détectée (`PRE-*`) | Présence de `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` ou `_JAVA_OPTIONS` | `unset MAVEN_ARGS MAVEN_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS` |
| Fichier `.mvn` interdit (`PRE-*`) | Présence de `maven.config`, `jvm.config` ou `extensions.xml` dans `app/.mvn/` | Retirer le fichier local non autorisé de `app/.mvn/` |
| `PRE-*-LOCKED` | Opération concurrente ou verrou actif (`code 3`) | Patienter jusqu'à la libération du verrou avant de relancer |
| `PRE-SECRETS-MISSING` | Répertoire d'instance existant sans ses secrets privés attendus | Réinitialiser (`reset --confirm RESET:<instance>`) ou supprimer (`destroy`) l'instance |
| `PRE-INPUT-DRIFT` | Code source, schémas SQL ou configuration modifiés depuis la création de l'instance | Utiliser un autre `--instance <nom>` ou réinitialiser avec `reset` |

## 4. Incidents de base de données et de dérive

| Code | Cause | Résolution recommandée |
| :--- | :--- | :--- |
| `INCOMPATIBLE-LEGACY-FLYWAY-HISTORY` | L'instance contient un historique Flyway hérité incompatible avec les baselines V1 actives | Ne pas lancer d'anciennes migrations ; recréer via `reset --confirm RESET:<instance>` ou créer une nouvelle instance |
| `INCOMPATIBLE-EXISTING-SCHEMA` | Schéma PostgreSQL existant dépourvu du marqueur `public.pocoma_local_bootstrap` attendu | Utiliser `reset` ou `destroy` (avec `--dry-run` préalable) ou un autre `--instance` |
| `DB-INPUT-DRIFT` | Les scripts SQL ou paramètres d'initialisation ont changé par rapport au bootstrap enregistré en base | Réinitialiser explicitement avec `./pocoma-local reset --instance <nom> --confirm RESET:<nom>` |
| `DB-INIT-TIMEOUT` | PostgreSQL reste injoignable au démarrage | Vérifier Docker et la santé du conteneur `postgres` avant de relancer `start` |

## 5. Incidents Keycloak, Bruno et observabilité

- **Authentification / Keycloak (`KEYCLOAK_*`)** : vérifiez l'état du conteneur `keycloak` dans `status` et renouvelez la session locale avec `./pocoma-local login --instance demo`. Sur macOS, assurez-vous qu'aucun fichier `.DS_Store` parasite ne perturbe les montages locaux si vous manipulez le répertoire d'instance. Pour l'authentification Bruno, consultez [Développement local et Bruno](../guides/local-development.md).
- **Grafana / Prometheus** :
  - `GRAFANA-HTTP` : Grafana ne répond pas sur son point d'accès HTTP ou santé attendu.
  - `GRAFANA-DATASOURCE` : la source de données Prometheus n'est pas joignable ou mal provisionnée.
  - `GRAFANA-DASHBOARD` : un des deux dashboards obligatoires (*Pocoma runtime*, *Pocoma V3 - Architecture & Observability Overview*) est absent ou invalide.
  - Pour réappliquer la configuration Grafana sans perdre vos données : `./pocoma-local upgrade-grafana --instance demo`.

## 6. Limites connues de la distribution locale

- **Enveloppe de charge nominale (`25 cmd/s`)** : avec `pocoma.v3.http.admission.capacity=4`, l'admission WRITE soutient `50 cmd/s` sans rejet `503` ni perte de données, mais la file d'attente de `business-read-worker` entraîne une saturation de la latence READ visible à `50 cmd/s` continus (`p95 ~ 25,2 s`). **`25 cmd/s`** constitue l'enveloppe nominale soutenable sur la distribution locale.
- **Topologie mono-worker et PostgreSQL partagé** : chaque rôle dispose d'un conteneur worker unique partageant l'instance PostgreSQL locale ; le basculement multi-nœuds haute disponibilité est hors périmètre de la distribution locale.
- **Journaux** : les secrets et jetons sont masqués dans les journaux conservés sous `.pocoma-local/instances/<instance>/`.
