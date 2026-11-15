# Documentation Pocoma

Bienvenue dans la documentation produit de **Pocoma**. Elle permet d'installer, comprendre, utiliser et exploiter Pocoma en local sans connaissance préalable de l'historique de construction.

## Prise en main

- [Vue d'ensemble prise en main](getting-started/README.md)
- [Installation et prérequis](getting-started/installation.md) — outillage, ressources Docker, démarrage initial, vérification `READY`, arrêt et suppression.
- [Premiers pas](getting-started/first-steps.md) — parcours autonome : démarrer, s'authentifier, soumettre une commande, lire son résultat et consulter la projection.

## Guides pratiques

- [Index des guides](guides/README.md)
- [Authentification et liaison (`Binding`)](guides/authentication.md) — OIDC PKCE S256 via Keycloak, `Registration`, `Unlink` et `bindingToken`.
- [Commandes et résultats asynchrones](guides/commands.md) — admission `202 Accepted`, `Idempotency-Key`, polling du résultat et payloads métier.
- [Requêtes READ et projections](guides/queries.md) — lecture à version minimale, historique exact, soldes, pagination et règles de confidentialité (`404`).
- [Développement local et Bruno](guides/local-development.md) — gestion des instances `--instance`, Bruno GUI/CLI, Grafana/Prometheus et workflow Maven par slices.

## Architecture

- [Index architecture](architecture/README.md)
- [Vue d'ensemble](architecture/overview.md) — concepts métier, séparation WRITE/READ, flux asynchrone et topologie des 9 conteneurs.
- [Runtimes JVM et parallélisme](architecture/runtimes.md) — les 5 runtimes, 36 partitions, baux SQL (`30 s`), `renewBatch` et fencing.
- [Persistance PostgreSQL](architecture/persistence.md) — bases `pocoma_write` / `pocoma_read`, baselines V1 et tables de coordination.
- [Sécurité](architecture/security.md) — OIDC, validation pré-sémaphore, idempotence HMAC, secrets locaux et rédaction des journaux.

## Références

- [Index des références](reference/README.md)
- [CLI `./pocoma-local`](reference/cli.md) — syntaxe complète, 9 sous-commandes, options et codes de sortie.
- [Configuration des runtimes](reference/configuration.md) — profils Spring, pools Hikari, baux, polling (ms), conteneurs et limites.
- [API publique `/api/v3`](reference/api.md) — routes WRITE/READ, en-têtes, payloads, statuts HTTP et format d'erreur.

## Exploitation

- [Index exploitation](operations/README.md)
- [Diagnostic et dépannage](operations/troubleshooting.md) — états `operationalState` / `qualificationState`, codes `PRE-*` / `DB-*` / `KEYCLOAK_*` / `GRAFANA-*` et limites connues.
- [Reprise après panne et cycle de vie](operations/recovery.md) — reprise après arrêt brutal, coupure PostgreSQL, `reset`, `destroy` et `upgrade-grafana`.

---

## Documentation interne et mémoire de construction

Les documents d'ingénierie internes, politiques de vérification actives, spécifications historiques, rapports de construction et preuves de qualification sont regroupés dans l'espace secondaire :

- [Documentation interne et historique](internal/README.md)
- [Politique de vérification Maven par slices](testing/Reactor_Verification_Policy.md) *(normative pour les modifications sous `app/`)*
- [Intégration continue (CI)](development/ci.md)
