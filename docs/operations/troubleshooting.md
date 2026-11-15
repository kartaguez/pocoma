# Diagnostic et résolution des incidents locaux

Ce guide recense les vérifications de premier niveau et les codes d'erreur rencontrés lors de l'exploitation locale avec [`./pocoma-local`](../reference/commands.md).

## Commandes de diagnostic et codes de retour

Commencez toujours par interroger l'état de l'instance :

```bash
./pocoma-local status --instance demo
```

Si une commande antérieure a expiré et vous a fourni un identifiant `--run` :

```bash
./pocoma-local status --instance demo --run <run-id>
```

Codes de retour de `./pocoma-local` :

| Code | Signification | Action immédiate |
| :---: | :--- | :--- |
| `0` | `READY` / commande réussie | Instance opérationnelle et canari validé |
| `1` | `NOT_READY` / `STOPPED` / `STALE` / erreur runtime | Consulter le message retourné et les logs d'exécution sous `.pocoma-local/instances/<instance>/` |
| `2` | Erreur d'usage, refus de prérequis (`PRE-*`), dérive ou schéma incompatible (`DB-*` / `INCOMPATIBLE-*`) | Corriger le poste ou choisir `reset` / `destroy` / nouveau `--instance` |
| `3` | Conflit de verrou (`PRE-INSTANCE-LOCKED`, `PRE-LIFECYCLE-LOCKED`, `PRE-BUILD-LOCKED`) | Attendre la fin de l'opération concurrente en cours |
| `4` | `DEGRADED` | API métier et canari OK, mais Prometheus indisponible ; inspecter `prometheus` / `grafana` |
| `130` | Interruption clavier (`Ctrl+C`) | Relancer `status` pour vérifier l'état résultant |

## Refus de prérequis (`PRE-*`)

| Symptôme / Code | Cause vérifiée | Résolution |
| :--- | :--- | :--- |
| Échec prérequis toolchain | Docker arrêté ou version hors contrat ([`scripts/local/toolchain.json`](../../scripts/local/toolchain.json) : Python ≥ `3.11`, JDK `21`, Node `24`, npm ≥ `7`, Bruno `4.3.0`) | Démarrer Docker ; ajuster JDK/Node/Python dans le shell courant |
| Variable globale détectée (`PRE-*`) | Présence de `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` ou `_JAVA_OPTIONS` | `unset MAVEN_ARGS MAVEN_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS` |
| Fichier `.mvn` interdit (`PRE-*`) | Présence de `maven.config`, `jvm.config` ou `extensions.xml` dans `app/.mvn/` | Retirer le fichier local non autorisé de `app/.mvn/` |
| `PRE-INSTANCE-LOCKED` / `PRE-LIFECYCLE-LOCKED` / `PRE-BUILD-LOCKED` | Opération concurrente ou verrou actif (code `3`) | Patienter jusqu'à la libération du verrou avant de relancer |
| `PRE-SECRETS-MISSING` | Répertoire d'instance existant sans ses secrets privés attendus | Réinitialiser (`reset --confirm RESET:<instance>`) ou supprimer (`destroy`) l'instance |
| `PRE-INPUT-DRIFT` | Code source, schémas SQL ou configuration modifiés depuis la création de l'instance | Utiliser un autre `--instance <nom>` ou réinitialiser avec `reset` |

## Incidents de base de données et de dérive d'entrée

| Code | Cause | Résolution recommandée |
| :--- | :--- | :--- |
| `INCOMPATIBLE-LEGACY-FLYWAY-HISTORY` | L'instance contient un historique Flyway hérité incompatible avec les baselines V1 actives | Ne pas lancer d'anciennes migrations. Recréer via `reset --confirm RESET:<instance>` (après `--dry-run`) ou créer une nouvelle instance |
| `INCOMPATIBLE-EXISTING-SCHEMA` | Schéma PostgreSQL existant dépourvu du marqueur `public.pocoma_local_bootstrap` attendu | Utiliser `reset` ou `destroy` (avec `--dry-run` préalable) ou un autre `--instance` |
| `DB-INPUT-DRIFT` | Les scripts SQL ou paramètres d'initialisation ont changé par rapport au bootstrap enregistré en base | Réinitialiser explicitement avec `./pocoma-local reset --instance <nom> --confirm RESET:<nom>` |

## Incidents Keycloak et authentification Bruno

Pour les erreurs de bootstrap ou d'état Keycloak (codes `KEYCLOAK_*`) ainsi que pour la gestion des fichiers `.DS_Store` sur macOS dans l'espace local, consultez :

- [Diagnostics Keycloak et remise à zéro contrôlée](../v3/L7_Local_Playground_Keycloak_Diagnostics.md)
- [Correction OAuth natif Bruno GUI](../v3/L7_Local_Playground_GUI_Auth_Fix_Report.md)
- [Guide Bruno](../v3/L7_Local_Playground_Bruno.md)

Pour renouveler la session OIDC en ligne de commande avant une série d'appels Bruno :

```bash
./pocoma-local login --instance demo
```

## Incidents d'observabilité (Grafana / Prometheus)

Codes de diagnostic Grafana documentés ([L7_Local_Playground_Observability.md](../v3/L7_Local_Playground_Observability.md)) :

- **`GRAFANA-HTTP`** : Grafana ne répond pas sur son point d'accès HTTP ou santé attendu.
- **`GRAFANA-DATASOURCE`** : la source de données Prometheus n'est pas joignable ou mal provisionnée.
- **`GRAFANA-DASHBOARD`** : un des deux dashboards obligatoires (*Pocoma runtime*, *Pocoma V3 - Architecture & Observability Overview*) est absent ou invalide.

Pour réappliquer la configuration Grafana sur une instance existante sans perdre vos données :

```bash
./pocoma-local upgrade-grafana --instance demo
```

## État `DEGRADED`, verrous et journaux par exécution

- **`DEGRADED` (`code 4`)** : les 5 runtimes métier et le canari fonctionnel sont opérationnels, mais Prometheus n'est pas sain (sans échec bloquant Grafana). Vérifiez l'état du conteneur Prometheus et ses métriques sur `:18080`–`:18085`.
- **Preuve périmée (`operationalState=OPERATIONAL`, `qualificationState=STALE`)** : après `300 s` (`MAX_AGE`), `status` retourne `NOT_READY` (`code 1`) pour signaler l'expiration de la preuve canari sans masquer la santé opérationnelle des services ; relancez `./pocoma-local start --instance <nom>` pour rafraîchir le canari.
- **Récupération après coupure PostgreSQL (`draining` / capacité d'admission épuisée)** : lorsque PostgreSQL redevient joignable, exécutez `./pocoma-local start --instance <nom>`. Le CLI détecte les workers en état `draining` (`/actuator/health` à `503`) et l'éventuelle saturation résiduelle du jeton d'admission `http-write`, effectue un redémarrage ciblé et borné, puis attend la réacquisition des 36 baux et le canari. Si PostgreSQL reste indisponible, `start` échoue proprement sur `DB-INIT-TIMEOUT` sans redémarrer les workers.
- **Reprise après arrêt brutal** : un redémarrage après crash Docker ou arrêt forcé peut demander jusqu'à `270 s` d'observation pour converger (`30 s` de bail SQL + scrutation et éventuel `claim-duration-ms=60000`). Attendez la convergence et vérifiez avec `status`.
- **Journaux** : inspectez les journaux produits sous `.pocoma-local/instances/<instance>/` à l'aide du `run-id` indiqué dans la sortie terminal ; les secrets et jetons y sont rédigés/masqués.
