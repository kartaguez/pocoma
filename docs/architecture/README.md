# Architecture

Cette section présente progressivement l'architecture de Pocoma :

1. **[Vue d'ensemble](overview.md)** — concepts métier, séparation WRITE/READ, flux asynchrone et topologie des 9 conteneurs.
2. **[Runtimes JVM et parallélisme](runtimes.md)** — les 5 runtimes JVM, 36 partitions, baux SQL (`30 s`), renouvellement groupé (`renewBatch`) et fencing.
3. **[Persistance PostgreSQL](persistence.md)** — bases `pocoma_write` et `pocoma_read`, baselines SQL V1, contrôle de bootstrap et tables de coordination.
4. **[Sécurité](security.md)** — authentification OIDC (Keycloak PKCE S256), liaison (`Binding`), validation pré-sémaphore, idempotence HMAC et gestion des secrets locaux.
