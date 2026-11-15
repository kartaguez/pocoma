# Vue d'ensemble de l'architecture

Pocoma met en œuvre une architecture **CQRS** (*Command Query Responsibility Segregation*) découpée en runtimes spécialisés et adossée à deux bases PostgreSQL distinctes (WRITE et READ).

> [!NOTE]
> La matrice normative complète des modules du réacteur (`app/`) est conservée dans [Architecture et matrice normatives V3](../v3/L7_Target_Modular_Architecture_and_Migration_Plan.md). Ce document décrit l'architecture produit courante de la distribution locale.

## Séparation CQRS : écriture, orchestration et lecture

Le système sépare strictement quatre responsabilités exécutées dans des processus distincts :

1. **Admission HTTP des commandes (`http-write`)** : valide le jeton OIDC, l'identité/liaison (`Binding`) et les clés d'idempotence HMAC sur les routes d'écriture `/api/v3`, puis persiste l'état d'admission dans `pocoma_write`.
2. **Traitement métier asynchrone (`command-worker`)** : acquiert des baux sur 8 partitions d'écriture (`Command` et `IdentityRequest`), exécute la logique transactionnelle en JDBC pur et enregistre les décisions et résultats dans `pocoma_write`.
3. **Matérialisation des vues (`results-worker` et `business-read-worker`)** : lit de façon bornée les résultats/événements source sur `pocoma_write` (via des droits stricts en lecture seule `write-source-read`) et met à jour les projections dans `pocoma_read` sur 28 partitions dédiées.
4. **Exposition HTTP en lecture (`http-read`)** : valide la requête HTTP et le jeton OIDC **avant** de consommer un permis de concurrence (`Q=8`), puis interroge uniquement `pocoma_read`.

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

## Modèle de déploiement local (9 conteneurs, 5 runtimes JVM)

L'orchestrateur [`./pocoma-local`](../reference/commands.md) déploie **9 conteneurs** Docker isolés pour chaque instance :

| Rôle | Conteneur | Technologie / Image | Rôle principal |
| :--- | :--- | :--- | :--- |
| Base de données | `postgres` | `postgres:17.6-alpine` | Héberge `pocoma_write` (`v3_write`) et `pocoma_read` (`v3_read`), sans port hôte publié |
| Identité | `keycloak` | `quay.io/keycloak/keycloak:26.7.0` | Royaume local PKCE S256 et comptes de test |
| Métriques | `prometheus` | `prom/prometheus:v3.5.0` | Collecte `/actuator/prometheus` toutes les `2 s` |
| Tableaux de bord | `grafana` | `grafana/grafana:12.3.2` | 2 dashboards provisionnés en lecture seule (`Pocoma runtime`, `Pocoma V3 - Architecture & Observability Overview`) |
| HTTP WRITE | `http-write` | `runtime-web-write` (JDK 21) | Admission des commandes `/api/v3` |
| HTTP READ | `http-read` | `runtime-web-read` (JDK 21) | Requêtes HTTP de lecture `/api/v3` |
| Worker WRITE | `command-worker` | `runtime-worker-process` (JDK 21) | Exécution des commandes en JDBC pur (sans JPA/Hibernate) |
| Worker READ résultats | `results-worker` | `runtime-worker-materialize` (JDK 21) | Projections `CommandResult` et `IdentityRequestResult` |
| Worker READ métier | `business-read-worker` | `runtime-worker-materialize` (JDK 21) | Projections `PotVersion`, `PotBalance`, `PotVisibility`, `CurrentBinding`, `Invitation` |

Le réacteur Java sous `app/` regroupe **42 modules Maven** (`domain-*`, `contract-*`, `port-*`, `engine-*`, `orchestrator-*`, `supra-*`, `infra-*`, `compose-*`, `runtime-*`, `architecture-tests`) vérifiables par slices selon [Reactor_Verification_Policy.md](../testing/Reactor_Verification_Policy.md).

## API publique et parcours de bout en bout

- Les routes exposées par les runtimes web suivent le contrat `/api/v3`, décrit dans [L7_Local_Playground_Public_API.md](../v3/L7_Local_Playground_Public_API.md).
- Les parcours fonctionnels visibles (`J01`, `J02`, `J03`, soit 118 requêtes et 236 tests nommés) sont exécutables via `./pocoma-local bruno` ou `./pocoma-local gui`.
- Les réserves connues sur certains comportements ou scénarios non encore clos sont consignées dans [L7_Local_Playground_Reservations.md](../v3/L7_Local_Playground_Reservations.md).

## Observabilité intégrée

- Chaque runtime JVM expose ses sondes et métriques Actuator sur un port dédié (`18080` à `18085`).
- Prometheus collecte l'ensemble des runtimes toutes les `2 s`.
- Grafana est accessible avec `admin` / `pocoma` et provisionne deux tableaux de bord dédiés décrits dans [Observabilité locale](../v3/L7_Local_Playground_Observability.md).
- En cas d'indisponibilité non bloquante de Prometheus, `./pocoma-local status` remonte `DEGRADED` (`code 4`) tout en maintenant l'accès aux services métier.

## Références techniques normatives

- [Runtimes JVM et parallélisme](runtimes.md)
- [Persistance PostgreSQL et baselines SQL](persistence.md)
- [Sécurité, authentification et gestion des secrets](security.md)
- [Politique de vérification Maven par slices](../testing/Reactor_Verification_Policy.md)
