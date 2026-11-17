# Vue d'ensemble de l'architecture

Pocoma est une plateforme transactionnelle asynchrone bâtie sur le modèle **CQRS** (*Command Query Responsibility Segregation*) : elle sépare strictement l'admission des intentions d'écriture, leur exécution métier, la matérialisation des vues de lecture et l'exposition HTTP en lecture seule.

## 1. Concepts métier

Pocoma manipule deux domaines principaux :

- **Identité et liaison (`User`, `ExternalIdentity`, `Binding`)** : une identité externe authentifiée par OIDC (Keycloak) est enregistrée (`Registration`) pour créer un utilisateur interne et un jeton de liaison applicatif (`bindingToken`). Un utilisateur peut se désaffilier (`Unlink`), ce qui révoque son `bindingToken` courant tout en conservant la traçabilité historique de ses commandes.
- **Cagnotte (`Pot`, `Shareholder`, `Expense`, `Invitation`)** : une cagnotte versionnée regroupe des participants (`Shareholder` avec poids rationnels), des dépenses (`Expense` sans flottants, calculées en rationnels exacts) et des soldes dont la somme est toujours nulle. L'accès à une cagnotte repose sur l'appartenance active ou historique et sur des invitations privées (`pocoma://invitation/<secret>`).

## 2. Séparation WRITE / READ et flux asynchrone

Le système découpe le traitement en quatre étapes exécutées par des processus distincts :

1. **Admission HTTP (`http-write`)** : vérifie le jeton OIDC, l'en-tête `Idempotency-Key` et le `bindingToken`, persiste l'admission dans `pocoma_write` et répond immédiatement `202 Accepted` avec un `requestId`.
2. **Traitement métier asynchrone (`command-worker`)** : acquiert des baux sur 8 partitions WRITE (`Command` et `IdentityRequest`), exécute la logique transactionnelle en JDBC pur (sans JPA/Hibernate) et enregistre les décisions et résultats dans `pocoma_write`.
3. **Matérialisation des projections (`results-worker` et `business-read-worker`)** : lit les événements et résultats depuis `pocoma_write` (avec un rôle en lecture seule `pocoma_write_source_read`) et met à jour les vues matérialisées dans `pocoma_read` sur 28 partitions dédiées.
4. **Exposition HTTP en lecture (`http-read`)** : valide la requête HTTP et le jeton OIDC **avant** d'acquérir un permis de concurrence (`Q=8`), puis interroge exclusivement `pocoma_read`.

```text
                        ┌────────────────────────────────────┐
                        │        Keycloak 26.7.0             │
                        │    OIDC Auth Code + PKCE S256      │
                        └─────────────────▲──────────────────┘
                                          │ validation JWT
        POST / commands (/api/v3)         │         GET / queries (/api/v3)
      ┌───────────────────────────────────┴───────────────────────────────────┐
      ▼                                                                       ▼
┌───────────┐                                                           ┌───────────┐
│http-write │                                                           │ http-read │
│ :8080     │                                                           │  :8082    │
└─────┬─────┘                                                           └─────┬─────┘
      │ SQL écriture                                              SQL lecture │ (Q=8)
      ▼                                                                       ▼
┌───────────────────────────┐   lecture source    ┌───────────────────────────┐
│  PostgreSQL WRITE         │ ──────────────────▶ │  PostgreSQL READ          │
│  base: pocoma_write       │  results-worker (8) │  base: pocoma_read        │
│  schéma: v3_write         │  business-read (20) │  schéma: v3_read          │
└─────────────▲─────────────┘                     └─────────────▲─────────────┘
              │ baux 30s + renewBatch                           │ baux 30s + renewBatch
        ┌─────┴──────────┐                         ┌────────────┴──────────────┐
        │ command-worker │                         │ results-worker            │
        │  8 partitions  │                         │ business-read-worker      │
        └────────────────┘                         └───────────────────────────┘
```

## 3. Les cinq runtimes et la topologie locale (9 conteneurs)

L'orchestrateur [`./pocoma-local`](../reference/cli.md) déploie **9 conteneurs** Docker isolés par instance :

| Rôle | Conteneur | Technologie / Module | Responsabilité principale |
| :--- | :--- | :--- | :--- |
| Base de données | `postgres` | `postgres:17.6-alpine` | Héberge `pocoma_write` et `pocoma_read` (sans port hôte publié) |
| Identité | `keycloak` | `quay.io/keycloak/keycloak:26.7.0` | Royaume local OIDC PKCE S256 et comptes de test |
| Métriques | `prometheus` | `prom/prometheus:v3.5.0` | Collecte `/actuator/prometheus` toutes les `2 s` |
| Tableaux de bord | `grafana` | `grafana/grafana:12.3.2` | 2 dashboards provisionnés en lecture seule |
| HTTP WRITE | `http-write` | `runtime-web-write` (JDK 21) | Admission des commandes et demandes d'identité (`/api/v3`) |
| HTTP READ | `http-read` | `runtime-web-read` (JDK 21) | Requêtes HTTP de lecture seule (`/api/v3`) |
| Worker WRITE | `command-worker` | `runtime-worker-process` (JDK 21) | Exécution des commandes en JDBC pur (sans JPA/Hibernate) |
| Worker READ résultats | `results-worker` | `runtime-worker-materialize` (JDK 21) | Projections `CommandResult` et `IdentityRequestResult` |
| Worker READ métier | `business-read-worker` | `runtime-worker-materialize` (JDK 21) | Projections `PotVersion`, `PotBalance`, `PotVisibility`, `CurrentBinding`, `Invitation` |

## 4. Partitions, baux, fencing et sécurité

- **Partitionnement et baux** : le travail asynchrone est réparti sur **36 partitions** (`8` WRITE, `8` résultats, `20` projections métier). Chaque partition est protégée par un bail SQL (`30 s`), renouvelé par lots (`renewBatch` toutes les `10 s`) avec **fencing strict** : un worker ayant perdu un bail retire immédiatement la partition de son ensemble actif sans jamais écraser un nouveau détenteur.
- **Sécurité** : authentification Bearer JWT (Keycloak), liaison `Binding` obligatoire, idempotence HMAC à l'admission, validation pré-sémaphore sur `http-read` et confidentialité opaque (`404` non discriminant).

Pour approfondir chaque couche :
- [Runtimes JVM et parallélisme](runtimes.md)
- [Persistance PostgreSQL](persistence.md)
- [Sécurité](security.md)
- [Référence API `/api/v3`](../reference/api.md)
