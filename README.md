# Pocoma

[![Pocoma CI](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml)

Pocoma est une plateforme transactionnelle asynchrone bâtie sur le modèle **CQRS** (*Command Query Responsibility Segregation*) en Java 21 / Spring Boot et PostgreSQL 17. Elle sépare strictement l'admission HTTP des commandes, leur exécution asynchrone, la matérialisation des projections de lecture et l'exposition HTTP en lecture seule, avec authentification **OIDC** (Keycloak) et observabilité **Prometheus / Grafana**.

## Capacités principales

- **Admission HTTP idempotente (`http-write`)** : expose l'API d'écriture `/api/v3`, vérifie les jetons OIDC et la liaison applicative (`Binding`), contrôle l'idempotence et accuse réception en `202 Accepted`.
- **Exécution asynchrone partitionnée (`command-worker`)** : traite les commandes et demandes d'identité en JDBC pur sur PostgreSQL, avec baux de partition (`30 s`), renouvellement groupé (`renewBatch`) et fencing strict.
- **Matérialisation des projections (`results-worker`, `business-read-worker`)** : projette de manière asynchrone les résultats terminaux et les vues métier (cagnottes, membres, dépenses, soldes exacts en rationnels, invitations) vers la base de lecture.
- **Lecture HTTP protégée (`http-read`)** : valide les requêtes et jetons OIDC **avant** l'acquisition des permis de concurrence (`Q=8`), puis sert les vues matérialisées avec confidentialité opaque (`404`).
- **Sécurité et observabilité** : authentification OIDC (Authorization Code + PKCE S256 via Keycloak), secrets locaux isolés et masqués dans les journaux, métriques Actuator collectées toutes les `2 s` par Prometheus vers Grafana.

## Architecture en bref

L'orchestrateur local [`./pocoma-local`](docs/reference/cli.md) déploie **9 conteneurs** (5 runtimes JVM et 4 services d'infrastructure) :

```text
Client / Bruno ──(OIDC PKCE S256)──▶ Keycloak (26.7.0)
      │
      ├── HTTP écriture (/api/v3) ──▶ http-write (:8080 interne) ──▶ PostgreSQL WRITE (pocoma_write)
      │                                                                       ▲
      │                                                      command-worker ──┘ (8 partitions)
      │                                                                       │
      │                              results-worker (8 partitions) ◀──────────┤ (source WRITE → READ)
      │                              business-read-worker (20 partitions) ◀───┘
      │                                                                       ▼
      └── HTTP lecture  (/api/v3) ──▶ http-read  (:8082 interne) ──▶ PostgreSQL READ  (pocoma_read)

Métriques Actuator (:18080–:18085) ──▶ Prometheus (v3.5.0, 2 s) ──▶ Grafana (12.3.2)
```

## Prérequis essentiels

Vérifiés automatiquement par `./pocoma-local` ([`scripts/local/toolchain.json`](scripts/local/toolchain.json)) :

- **Docker Engine / Docker Desktop** démarré (≥ 8 Go de RAM recommandés pour les 9 conteneurs)
- **Python ≥ 3.11**
- **JDK 21** (`java` 21)
- **Node 24** et **npm ≥ 7** (pour Bruno CLI `4.3.0`)
- Environnement propre : aucune variable globale `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` ou `_JAVA_OPTIONS`, et aucun fichier `maven.config` / `jvm.config` / `extensions.xml` dans `app/.mvn/`

Pour les détails et la checklist complète : [Installation](docs/getting-started/installation.md).

## Démarrage rapide

Créer, démarrer et vérifier l'instance locale par défaut (`demo`) :

```bash
./pocoma-local start --instance demo
./pocoma-local status --instance demo
```

Exécuter les parcours automatisés ou ouvrir Bruno avec authentification OIDC :

```bash
./pocoma-local bruno --instance demo
./pocoma-local gui --instance demo
```

Arrêter proprement l'instance en conservant les données :

```bash
./pocoma-local stop --instance demo
```

Pour réaliser votre premier appel API de bout en bout : [Premiers pas](docs/getting-started/first-steps.md).

## Documentation

| Besoin | Point d'entrée |
| :--- | :--- |
| Portail de la documentation | [docs/README.md](docs/README.md) |
| Installation et prérequis | [docs/getting-started/installation.md](docs/getting-started/installation.md) |
| Premiers pas (authentification → commande → lecture) | [docs/getting-started/first-steps.md](docs/getting-started/first-steps.md) |
| Guides pratiques (auth, commandes, requêtes, dev local) | [docs/guides/README.md](docs/guides/README.md) |
| Architecture (CQRS, runtimes, persistance, sécurité) | [docs/architecture/overview.md](docs/architecture/overview.md) |
| Références (CLI, configuration, API `/api/v3`) | [docs/reference/README.md](docs/reference/README.md) |
| Exploitation (dépannage, reprise après panne) | [docs/operations/README.md](docs/operations/README.md) |

## Contribution, sécurité et licence

Les contributions ciblées, testées et respectant les frontières modulaires documentées sont les bienvenues. Consultez [CONTRIBUTING.md](CONTRIBUTING.md) et respectez le [Code of Conduct](CODE_OF_CONDUCT.md).

Merci de ne pas divulguer publiquement une vulnérabilité exploitable : suivez les instructions de [SECURITY.md](SECURITY.md) pour tout signalement privé.

Pocoma est distribué sous licence [Apache License 2.0](LICENSE). Voir [NOTICE](NOTICE) pour les informations d'attribution.
