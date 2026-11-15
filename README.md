# Pocoma

[![Pocoma CI](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml)

Pocoma est une plateforme transactionnelle asynchrone bâtie sur le modèle **CQRS** (*Command Query Responsibility Segregation*) en Java 21 / Spring Boot et PostgreSQL 17. Elle sépare strictement l'admission HTTP des commandes, leur traitement asynchrone, la matérialisation des vues de lecture et l'exposition HTTP en lecture seule, avec authentification **OIDC** (Keycloak) et observabilité **Prometheus / Grafana**.

## Grandes responsabilités du système

- **Admission HTTP et idempotence (`http-write`)** : expose l'API `/api/v3`, vérifie les jetons OIDC et la liaison applicative (`Binding`), applique le contrôle d'idempotence et enregistre les commandes entrantes côté écriture.
- **Traitement transactionnel asynchrone (`command-worker`)** : exécute la logique métier sur les partitions d'écriture en JDBC pur (sans JPA/Hibernate) et gère les baux de partition avec renouvellement groupé (`renewBatch`) et fencing strict.
- **Matérialisation des projections (`results-worker`, `business-read-worker`)** : consomme les résultats et états d'écriture pour mettre à jour les modèles de lecture dédiés sans couplage synchrone avec l'écriture.
- **Lecture HTTP protégée (`http-read`)** : valide les requêtes et jetons **avant** acquisition des sémaphores de concurrence (`Q=8`), puis sert les vues matérialisées depuis le store de lecture.
- **Sécurité et observabilité** : authentification OIDC (Code + PKCE S256 via Keycloak), secrets locaux chiffrés/rédigés dans les journaux et métriques Actuator collectées toutes les 2 s par Prometheus vers Grafana.

## Architecture générale

L'orchestrateur local [`./pocoma-local`](scripts/local/entrypoint.py) déploie une topologie complète de **9 conteneurs** (5 runtimes JVM et 4 services d'infrastructure) :

```text
Client / Bruno ──(OIDC PKCE S256)──▶ Keycloak (26.7.0)
      │
      ├── HTTP écriture (/api/v3) ──▶ http-write (:8080 interne) ──▶ PostgreSQL WRITE (pocoma_write / v3_write)
      │                                                                       ▲
      │                                                      command-worker ──┘ (8 partitions WRITE)
      │                                                                       │
      │                              results-worker (8 partitions READ) ◀─────┤ (lecture source WRITE → READ)
      │                              business-read-worker (20 partitions) ◀───┘
      │                                                                       ▼
      └── HTTP lecture  (/api/v3) ──▶ http-read  (:8082 interne) ──▶ PostgreSQL READ (pocoma_read / v3_read)

Métriques Actuator (:18080–:18085) ──▶ Prometheus (v3.5.0, 2 s) ──▶ Grafana (12.3.2)
```

> Les bases de données ne publient aucun port hôte vers l'extérieur ; l'accès et l'isolation réseau sont gérés par la topologie locale de [`./pocoma-local`](docs/operations/local-instances.md).

## Prérequis principaux

Vérifiés automatiquement au lancement de `./pocoma-local` ([`scripts/local/toolchain.json`](scripts/local/toolchain.json)) :

- **Docker Engine / Docker Desktop** démarré et accessible via le socket Docker
- **Python ≥ 3.11**
- **JDK 21** (`java` 21)
- **Node 24** et **npm ≥ 7** (pour les outils Bruno locaux)
- Environnement propre : aucune variable globale `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` ou `_JAVA_OPTIONS`, et aucun fichier `maven.config` / `jvm.config` / `extensions.xml` dans `app/.mvn/`

Pour les détails d'installation et la préparation du poste, voir [Prérequis](docs/getting-started/prerequisites.md).

## Installation et démarrage rapide

Créer et démarrer l'instance locale par défaut (`demo`) depuis la racine du dépôt :

```bash
./pocoma-local start --instance demo
```

Vérifier que l'instance est `READY` et que le canari fonctionnel a réussi :

```bash
./pocoma-local status --instance demo
```

Démarrer Bruno en mode interface graphique ou exécuter les parcours automatisés :

```bash
./pocoma-local gui --instance demo
./pocoma-local bruno --instance demo
```

Arrêter proprement l'instance tout en conservant les volumes et données PostgreSQL / Keycloak :

```bash
./pocoma-local stop --instance demo
```

## Documentation

| Besoin | Document |
| :--- | :--- |
| Index complet de la documentation | [docs/README.md](docs/README.md) |
| Prérequis système et toolchain | [docs/getting-started/prerequisites.md](docs/getting-started/prerequisites.md) |
| Parcours complet de première utilisation | [docs/getting-started/quickstart.md](docs/getting-started/quickstart.md) |
| Architecture produit et flux CQRS | [docs/architecture/overview.md](docs/architecture/overview.md) |
| Runtimes JVM, parallélisme et baux SQL | [docs/architecture/runtimes.md](docs/architecture/runtimes.md) |
| Bases WRITE/READ et baselines SQL V1 | [docs/architecture/persistence.md](docs/architecture/persistence.md) |
| Sécurité OIDC, PKCE et secrets | [docs/architecture/security.md](docs/architecture/security.md) |
| Gestion des instances locales | [docs/operations/local-instances.md](docs/operations/local-instances.md) |
| Diagnostic et codes d'erreur | [docs/operations/troubleshooting.md](docs/operations/troubleshooting.md) |
| Référence CLI `./pocoma-local` | [docs/reference/commands.md](docs/reference/commands.md) |
| Référence de configuration des runtimes | [docs/reference/configuration.md](docs/reference/configuration.md) |
| Politique de vérification Maven par slices | [docs/testing/Reactor_Verification_Policy.md](docs/testing/Reactor_Verification_Policy.md) |
| Archives historiques L1–L7 et migrations | [docs/archive/README.md](docs/archive/README.md) |

## Contribution, sécurité et licence

Les contributions ciblées, testées et respectant les frontières modulaires documentées sont les bienvenues. Consultez [CONTRIBUTING.md](CONTRIBUTING.md) et respectez le [Code of Conduct](CODE_OF_CONDUCT.md).

Merci de ne pas divulguer publiquement une vulnérabilité exploitable : suivez les instructions de [SECURITY.md](SECURITY.md) pour tout signalement privé.

Pocoma est distribué sous licence [Apache License 2.0](LICENSE). Voir [NOTICE](NOTICE) pour les informations d'attribution.
