# Sécurité, authentification et gestion des secrets

L'accès aux API `/api/v3` repose sur une authentification OIDC déléguée à Keycloak et sur une séparation nette entre identité OIDC, liaison applicative (`Binding`) et gestion locale des secrets.

## Authentification OIDC (Keycloak, Code + PKCE S256)

L'infrastructure locale déploie **Keycloak `26.7.0`** (`quay.io/keycloak/keycloak:26.7.0`, limite `896m` / `1.5` CPU, heap `-Xms128m -Xmx512m`) importé avec le royaume [`docker/keycloak/pocoma-realm.json`](../../docker/keycloak/pocoma-realm.json).

- **Flux utilisateur et Bruno GUI** : Authorization Code + PKCE (`S256`). Depuis la correction OAuth documentée dans [L7_Local_Playground_GUI_Auth_Fix_Report.md](../v3/L7_Local_Playground_GUI_Auth_Fix_Report.md), le parcours `./pocoma-local gui` / `./pocoma-local login` réalise l'authentification sans copie manuelle de JWT.
- **Comptes provisionnés pour le playground local uniquement** :
  - Console d'administration Keycloak : `admin` / `pocoma`
  - Utilisateurs applicatifs de test : `third`, `guest`, `bruno` (mot de passe `pocoma`)

> [!WARNING]
> Les identifiants ci-dessus concernent exclusivement l'environnement de développement local géré par `docker/keycloak/pocoma-realm.json` et ne doivent jamais être réutilisés dans un environnement partagé ou exposé.

## Autorisation applicative et périmètres HTTP

Côté runtimes web, la configuration de sécurité (`HttpSecurityConfiguration`, `AdmissionHttpConfiguration`, `ReadHttpConfiguration`) applique les contrôles suivants :

1. **Authentification Bearer JWT** : contrôle des jetons émis par Keycloak sur les routes applicatives `/api/v3`.
2. **Liaison applicative (`Binding`)** : association explicite entre le sujet authentifié et les identifiants métier manipulant les ressources/cagnottes (voir [L7_Local_Playground_Public_API.md](../v3/L7_Local_Playground_Public_API.md)).
3. **Validation avant sémaphore sur `http-read`** : la validation du protocole HTTP et de l'accès s'effectue **avant** l'acquisition des permis de concurrence (`Q=8`), ce qui évite qu'une rafale de requêtes invalides ou non autorisées ne sature les connexions SQL de lecture.

## Idempotence et protection contre la relecture

Les opérations d'écriture exposées sur `/api/v3` utilisent des mécanismes d'idempotence (clé d'idempotence et contrôle d'empreinte HMAC côté admission) afin de garantir qu'une soumission rejouée à l'identique ne produise pas d'effets dupliqués et qu'un écart de charge utile soit rejeté selon les statuts contractuels documentés dans [L7_Local_Playground_Public_API.md](../v3/L7_Local_Playground_Public_API.md).

## Secrets locaux et rédaction dans les journaux

Lors de la création d'une instance par `./pocoma-local start`, des secrets locaux (mots de passe techniques de base de données, secrets clients/sessions propres à l'instance) sont générés dans le répertoire privé de l'instance sous `.pocoma-local/instances/<instance>/` (et non versionnés dans Git).

- **Protection au redémarrage** : si une instance existante est redémarrée sans ses secrets locaux attendus, `./pocoma-local` échoue avec `PRE-SECRETS-MISSING` plutôt que de régénérer silencieusement des secrets incohérents avec PostgreSQL ou Keycloak.
- **Rédaction dans les journaux** : les utilitaires d'exploitation et les rapports de diagnostic masquent les secrets et jetons dans les journaux conservés sur disque. Consultez [L7_Local_Playground_Keycloak_Diagnostics.md](../v3/L7_Local_Playground_Keycloak_Diagnostics.md) pour les diagnostics liés à Keycloak.
