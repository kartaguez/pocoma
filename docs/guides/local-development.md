# Développement local et Bruno

Ce guide rassemble les outils quotidiens pour travailler en local sur Pocoma : gestion d'instances isolées, tests interactifs et automatisés avec Bruno, observabilité Grafana et compilation Maven ciblée.

## 1. Gérer plusieurs instances locales

Par défaut, `./pocoma-local` cible l'instance `demo`. Vous pouvez créer des instances isolées (noms conformes à `[a-z][a-z0-9-]{0,30}`) pour tester une branche ou repartir d'une base vierge sans toucher à `demo` :

```bash
./pocoma-local start --instance dev-feature
./pocoma-local status --instance dev-feature
./pocoma-local stop --instance dev-feature
```

Chaque instance stocke ses métadonnées, empreintes, secrets locaux et journaux d'exécution sous `.pocoma-local/instances/<instance>/`.

> [!NOTE]
> Si vous modifiez du code Java, des fichiers `.properties` ou des scripts SQL entre deux démarrages d'une même instance, `./pocoma-local start` refuse de démarrer sur des volumes devenus incompatibles (`PRE-INPUT-DRIFT` / `DB-INPUT-DRIFT`). Utilisez `reset --confirm RESET:<instance>` ou créez une nouvelle instance `--instance <nom>`.

## 2. Tester avec Bruno (GUI et CLI)

La collection produit se trouve dans `scripts/bruno-playground/` (**Pocoma V3 Playground**, `171` requêtes visibles dont `118` étapes réparties en 3 parcours de bout en bout).

### Mode interactif (Bruno GUI)

```bash
./pocoma-local gui --instance demo
```

Dans Bruno :
1. Ouvrez le dossier `scripts/bruno-playground`.
2. Sélectionnez l'environnement **`Instance-demo`** en haut à droite (l'environnement `Local` est réservé à la CLI).
3. Activez **`Developer Mode`** dans le menu `</>` (*JavaScript Sandbox*).
4. Exécutez `00 - Getting Started / E00 Environment Check`, puis connectez `00 Login Owner` (`third` / `pocoma`) et `01 Login Guest` (`guest` / `pocoma`) via **Auth → Get Access Token**.
5. Lancez un parcours complet dans `90 - End-to-End Journeys` (**Run**) ou envoyez des requêtes isolées par domaine.

### Mode automatisé (Bruno CLI 4.3.0)

```bash
./pocoma-local bruno --instance demo
```

Exécute les **3 parcours (`J01`, `J02`, `J03`)** — soit **118 requêtes et 236 tests nommés** :
- **`J01 Complete Ledger and Invitation`** (`66` étapes) : inscription, création de Pot, invitation Guest, dépenses en rationnels (`10/3`, `20/3`), soldes exacts, historique, départ volontaire et suppressions.
- **`J02 Errors Privacy and Idempotency`** (`31` étapes) : rejeu idempotent, conflit `409`, pagination, rejets `400`/`401`/`404` opaques et refus métier.
- **`J03 Unlink Re-register and Historical Ownership`** (`21` étapes) : désaffiliation `Unlink`, invalidation de l'ancien `bindingToken`, ré-inscription et conservation des résultats historiques.

## 3. Observabilité locale (Prometheus & Grafana)

- Prometheus collecte `/actuator/prometheus` sur les 5 runtimes (`:18080` à `:18085`) toutes les `2 s`.
- Grafana (`12.3.2`, identifiants `admin` / `pocoma`, URL affichée par `./pocoma-local status`) expose deux dashboards en lecture seule :
  - **`Pocoma runtime`** : santé JVM, pools HikariCP, concurrence HTTP et état des workers (`draining`, baux).
  - **`Pocoma V3 - Architecture & Observability Overview`** : flux WRITE/READ, latences et compteurs de partitions.
- Pour rafraîchir uniquement les dashboards Grafana sans réinitialiser vos données :

```bash
./pocoma-local upgrade-grafana --instance demo
```

## 4. Modifier et tester le code Java (`app/`)

Le réacteur Maven sous `app/` contient **42 modules** et un agrégateur. Conformément à la politique normative [Reactor_Verification_Policy.md](../testing/Reactor_Verification_Policy.md), **ne lancez pas `./mvnw test` sur tout le réacteur par défaut** ; exécutez la slice correspondant à votre changement depuis `app/` :

| Slice | Commande canonique (depuis `app/`) | Périmètre |
| :--- | :--- | :--- |
| **WEB** | `./mvnw -pl runtime-web-write,runtime-web-read -am test` | Admission WRITE, requêtes READ, sécurité HTTP |
| **COMMAND** | `./mvnw -pl runtime-worker-process -am test` | Exécution commandes/identités, fencing, reprise |
| **PROJECTION** | `./mvnw -pl runtime-worker-materialize -am test` | Matérialisation résultats et vues READ |
| **FOUNDATION** | `./mvnw -pl infra-tx-spring -am test` | Contextes et isolation transactionnelle |

Pour les règles d'intégration continue et le gate d'architecture (`architecture-tests`), consultez [Intégration continue (CI)](../development/ci.md).
