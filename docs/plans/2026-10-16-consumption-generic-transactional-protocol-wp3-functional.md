# WP3 — Validation fonctionnelle des huit familles

Date : 2026-10-16. Baseline `004a601f20870534e5747389e39cfb6966624374`, branche `feat/runtime-monolith`. [Plan canonique](2026-10-16-consumption-generic-transactional-protocol.md), [contrats WP1](2026-10-16-consumption-generic-transactional-protocol-wp1-contracts.md), [preuves WP2](2026-10-16-consumption-generic-transactional-protocol-wp2-implementation.md).

## Scope déclaré

Tests uniquement. COMMAND primaire ; EVENT/PROJECTION/BINDING/LKV secondaires ; compléments Registration, Command Result, Registration Result. Forbidden production impact : métier, schéma, dépendances/modules, WEB. Aucun gate global requis ; full reactor non requis (étape bornée, hors intégration main). PostgreSQL réel `postgres:17-alpine`, Java Temurin 21 ; bootstrap existant conservé car politique Trusted baseline NONE. Aucune campagne historique supplémentaire, installation SNAPSHOT ou retry expérimental.

## Matrice fonctionnelle

Les classes sont recherchables sous `app/` ; chaque suite ci-dessous appartient à la fermeture ALL8 et doit apparaître dans le manifeste de résultats. Les preuves sont bornées aux scénarios nommés, sans prétention d'exhaustivité des entrées métier.

| Famille | Scénarios | Tests | Résultat | Garantie |
|---|---|---|---|---|
| Command | SUCCESS/REJECTED/FAILED, exact Binding, recovery positive/négative, détachement/réattachement, outcome/event uniques, SQL/provenance annulés stale | `CommandConsumptionPostgresTest`, `CommandConsumptionRuntimePostgresTest`, `TransactionalConsumptionExecutionPostgresTest` | PASS | Atomicité commune ; relecture exacte et absence de rejeu Pot en recovery |
| Registration | Acquisition Binding, User/UserCreatedFact, winner/rejet et restart, retry sans outcome, takeover avant/pendant callback | `RegistrationRuntimePostgresTest`, `RegistrationAuthorityPostgresTest` | PASS | Un seul User/fact d'acquisition ; rollback de l'initializer et du résultat perdant |
| Binding | ATTACHED/DETACHED/réattachement, reconstruction, monotonie, stale/replay/divergence même révision, retry, rollback READ perdant | `BindingRuntimePostgresTest`, `CurrentBindingPersistencePostgresTest` | PASS | Révision monotone, tombstone et source exacts ; aucune résurrection par ancien fait |
| Event | Task idempotente par clé, slots indépendants, invocation ensure stale, erreur avant commit, reprise | `ProjectionMaterializationConsumptionPostgresTest`, `EventConsumptionRuntimePostgresTest` | PASS | Task/slot/claim atomiques ; callback provisoire autorisé, zéro Task durable perdante |
| Projection Task | Root/artifacts, first wins, failure, Temporary/retry, exact historique, rollback publication/failure stale | `CanonicalProjectionTaskRuntimePostgresTest`, `JdbcProjectionStoreAdapterPostgresTest`, `ProjectionTaskConsumptionServiceTest`, `ProjectionTaskConsumptionOrchestratorTest` | PASS | Publication atomique ; aucun terminal indu sur Temporary ; first published wins conservé |
| Command Result | Sources APPLIED/REJECTED/FAILED, owner/visibilité, replay identique/divergent, retry, SQL écrit avant perte CAS, provenance Event | `CommandResultRuntimePostgresTest`, `JdbcCommandResultStorePostgresTest` | PASS | Résultat immutable ; stale JDBC et provenance rollbackés ; gagnant préservé |
| Registration Result | REGISTERED/REJECTED, owner historique après rebind, replay/divergence, retry, stale | `RegistrationResultRuntimePostgresTest` et suites store/materializer correspondantes | PASS | Pas de nouveau fence Binding pour résultat historique ; immutable et provenance outcome |
| Latest Known Version | 10/12/11, restart, source Event absente/invalide, retry/FAILED, rollback upsert/provenance, observation afterCommit | `LatestKnownVersionRuntimePostgresTest`, `MeteredAdvanceLatestKnownVersionUseCaseTest` | PASS | UPSERT monotone ; succès métrique différé, exceptions d'observation isolées |

## Diff et méthode

Deux caractérisations Command Result ajoutées : écriture JDBC réellement constatée dans le callback stale puis absence durable après LostClaim, token gagnant intact puis publication avec un input Event ; replay strictement identique puis owner divergent refusé sans remplacement. Aucun changement de sémantique pour rendre les tests verts.

Trois attentes `Thread.sleep` Registration/Binding remplacées par expiration SQL contrôlée de la lease acquise, suivant la fixture Registration Result existante. Les latches de concurrence demeurent ; aucune contrainte supprimée. Les tests de polling processus existants ne constituent pas une barrière de race ni une preuve de progression bornée.

## Résultats

Campagne et manifeste : [archive WP3](2026-10-16-consumption-generic-transactional-protocol-wp3-verification.txt). **PASS WP3** : ALL8 BUILD SUCCESS, 951 tests distincts / 951 exécutions, 203 classes, zéro failure/error/skip. Deux nouveaux tests, les 949 précédents réexécutés une fois ; aucun échec intermédiaire. L’avertissement de shutdown Surefire à 30 s déjà présent WP0/WP2 reste archivé, sans échec de test ni build. Production observée NONE, crossing inattendu NONE ; gate global et reactor complet non exécutés car non requis. Huit familles couvertes, aucune anomalie fonctionnelle TARGET connue, aucun protocole spécial ajouté. WP4 autorisé après commit de ces livrables.

PROVEN : uniquement assertions exécutées. GUARANTEED BY CODE/SCHEMA : suffixe commun et contraintes conservés, sous même ressource transactionnelle. INCONCLUSIVE : aucune conclusion universelle de deadlock/fairness. NOT TESTED : pannes distribuées réelles, charge, callbacks futurs, DDL concurrent. Les approfondissements de files PostgreSQL relèvent de WP4, sans report d'un défaut fonctionnel connu.

Git initial : remote HTTPS `https://github.com/kartaguez/pocoma.git`, upstream `origin/feat/runtime-monolith`, serveur et HEAD identiques, divergence 0/0. Empreintes préexistantes Keycloak/Bruno conservées dans `/tmp/pocoma-wp3-preexisting.json` et contrôlées à la livraison ; staging explicite uniquement. Aucun changement de branche/stash/reset/merge/push.
