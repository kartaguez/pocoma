# Pocoma V3 — L2 / I3 : corrections post-audit indépendant

**27 octobre 2026 — EN COURS : corrections et reproductions ciblées effectuées, gates frais à exécuter.**

Mandat : R-AUD-I3-01 et R-AUD-I3-02 exclusivement. R-AUD-I3-03 reste reporté à I5, **INCONCLUSIVE**. Aucun I4/I5, Worker actif, déploiement, ACCEPTED L2 ou franchissement G1/G2/G3.

## Références et périmètre déclaré avant implementation

SHA initial distant vérifié par `git ls-remote origin refs/heads/feat/v3-architecture` : **`222e06c21a5f0b0ebe35befc693db02d9b919cd2`**. Code historique I3 : `1d7cf51c13671c7c85a5c9c1281f526be2b2abfb`. Ces résultats historiques ne sont pas utilisés comme preuves des corrections. Worktree de livraison isolé `/private/tmp/pocoma-v3-i3-audit-fixes`, HEAD détachée sur la lignée distante ; destination exclusive `origin/feat/v3-architecture`, sans déplacement de la branche locale occupée dans un worktree préexistant.

Sources consultées : [politique normative de vérification](../testing/Reactor_Verification_Policy.md), [plan I3](L2_I3_Implementation_Plan.md), [rapport I3](L2_I3_Execution_Report.md), [R1–R10](L2_R1_R10_Normative_Consolidation.md), [oracles indépendants](L2_Independent_Test_Oracles.md), [couverture L2](L2_Certification_Coverage_Manifest.md), [SPEC-05](SPEC-05_Consumption_Discovery.md), [SPEC-07](SPEC-07_Transverse_Requirements.md), [SPEC-10 CON-03/04/05/06](SPEC-10_Test_Certification.md), [plan I2](L2_I2_Implementation_Plan.md), [rapport I2](L2_I2_Execution_Report.md), contrats `AttemptTransactions`, `RecoveryReadContext`, `ConsumptionParticipant`, completion et implémentations Spring/JDBC/composition/tests existants.

| Élément | Déclaration prospective conservée |
|---|---|
| Impact productif attendu | Sortie exceptionnelle d'Engine ; classification composite dans la composition existante seulement |
| Slice primaire | **COMMAND** : `./mvnw -pl runtime-command-consumption-worker -am test` |
| Slices secondaires | **NONE** |
| Preuves directes | `./mvnw -pl infra-persistence-read-jdbc -am test` ; `./mvnw -pl runtime-monolith -am test` |
| Architecture globale | **REQUIRED** par le mandat explicite de clôture : `./mvnw -pl architecture-tests -am test` |
| Full reactor | **NOT REQUIRED / NON EXÉCUTÉ** : correction bornée, aucune restructuration Maven ni clôture majeure L2/intégration main |
| Base | **CURRENT**, PostgreSQL V3 neuf WRITE/READ des fixtures existantes ; aucune migration ajoutée/modifiée |
| Impact interdit / escalade préalable | Owner/suffixe/finalisation I2, schéma, graphe Maven, frontières WRITE/READ, autre comportement runtime, I4/I5 |

## R-AUD-I3-01 : analyse contradictoire

**Défaut confirmé.** La restitution était uniquement sur le chemin normal après `classify`. Une exception du classificateur, un `Error` ou son résultat `null` empêchait cette restitution malgré le rollback déjà certain. Le `finally` rendait seulement le handle inspectable (`localDone=true`). Une reprise manuelle pouvait ensuite libérer le ticket ; aucune nécessité de sûreté ne justifiait cette attente lorsque la dernière unité était déjà résolue.

Les reproductions ajoutées à `ConsumptionFailureClassificationTest` échouent avant correction sur `worker=1`, attendu `0` : deux échecs, zéro erreur/skip. Aucun échec n'est supprimé des archives.

### Chemins et preuves de résolution

| Chemin réel | Certitude exploitable par Engine | Traitement du ticket après sortie locale |
|---|---|---|
| Préparation/finalisation retourne normalement et finalisation COMMITTED | Commit confirmé par le port owner | Restituer |
| `Failure(ROLLED_BACK)` de préparation/finalisation, claim perdu inclus | Rollback confirmé ; classification seulement pour ROLLED_BACK comptabilisable | Restituer après classification et éventuelle cession résolue |
| `Failure(COMMITTED)` de finalisation, diagnostic de cleanup | Commit confirmé ; protocole I2 conserve le diagnostic | Restituer, aucune cession |
| Préparation vide ou `Failure(COMMITTED/UNKNOWN)` de préparation | Pas une preuve de finalisation métier | Garder l'incertitude I2, observation puis inspection existante |
| Finalisation UNKNOWN, même observation COMPLETED | ACK physique historique UNKNOWN conservé | Pas de restitution automatique nouvelle ; inspection existante |
| `classify` lève RuntimeException/Error ou retourne null après rollback certain | Aucune nouvelle unité n'a démarré ; rollback certain conservé | Propager la faute, restituer après sortie du callback |
| Cession séparée retourne normalement ou `Failure(COMMITTED/ROLLED_BACK)` | Dernière unité physique résolue | Restituer ; ne jamais répéter la cession |
| Cession `Failure(UNKNOWN)` | Rollback métier certain insuffisant pour résoudre la cession | Conserver le ticket ; inspection seulement, aucun nouvel effet |
| Exception brute du port owner, de début/cleanup, ou Error sans `Failure` exploitable | Aucune résolution prouvée dans Engine | Conserver le ticket, inspection existante après fin locale |
| Annulation/interruption/expiration/timeout | Aucune preuve physique en soi | Aucun droit supplémentaire de restitution ou de comptabilisation |

Les adaptateurs SQL et le participant peuvent lever des erreurs applicatives/JDBC/JPA. Le véritable `SpringAttemptTransactions` transforme les RuntimeException du callback/begin/commit/rollback en `Failure` selon les preuves physiques, sauf son refus d'ambiance préalable ; un Error peut s'échapper sans résolution qualifiée. Les ports injectés n'imposent pas de clause checked et peuvent aussi lever une exception brute ; Engine ne peut pas lui inventer une résolution. `observe` et l'inspection indisponibles restent conservateurs. La classification est un port injecté sans owner transactionnel : ses exceptions ne sont pas automatiquement des `Failure` transactionnelles.

### Modification minimale

Le booléen **local à l'appel** `resolved` commence à `false`, reçoit la résolution I2 puis est remis à `false` **avant** d'appeler l'opération de cession. Seul un retour certain ou un `Failure` portant une résolution certaine le rétablit. Le `finally` marque la sortie locale puis restitue **conditionnellement à cette certitude**. Il ne libère donc jamais systématiquement. La remise à zéro avant cession empêche de réutiliser abusivement le rollback métier si ce nouveau port lève une exception brute.

Aucun état ajouté au handle, nouvelle machine à états, manager de recovery, transaction ou scheduler. `pending` et `Ticket.release()` restent les mécanismes existants. La suppression atomique de `pending` dans `settlePending` et la restitution idempotente du ticket empêchent les doubles décréments, y compris face à la sortie locale.

Le test historique `i3_c05_k` simulait un arrêt par `AssertionError` dans le classificateur après un vrai rollback de préparation. Ses deux assertions prescrivaient l'immobilisation fautive puis la reprise manuelle. Elles exigent désormais **zéro place occupée immédiatement et zéro restitution supplémentaire**. Ses oracles métier restent intacts : rollback constaté, aucun effet/budget/cession, token conservé, expiration puis nouvelle acquisition et succès. `i3_capacity_uncertainty` conserve toutes ses assertions ; son helper est réutilisé pour ajouter la variante cession physique ouverte.

## R-AUD-I3-02 : classification effective et corrections limitées

**La priorité conservatrice est correcte et conservée.** Le graphe est parcouru par identité avec protection contre cycles. Cause exclue (SQLSTATE `08xxx`/`40003`, configuration Spring reconnue) > faute SQL/JPA > faute RuntimeException/Error technique > NON_COUNTABLE sans preuve. Une annulation/interruption simple est neutre : elle ne suffit pas à compter et n'efface pas une véritable faute comptabilisable présente ailleurs dans le graphe. Les vraies collisions/faits incohérents sont spécialisés par la composition Command **seulement après** le filtre commun, sans connaissance métier dans Engine.

Deux lacunes supplémentaires ont été **reproduites avant correction** :

1. `TransactionSystemException` peut conserver une exception applicative via `initApplicationException`, distincte de `getCause`. Avec cause SQLSTATE 23514 et exception originale 08006, le classificateur rendait SQL_OR_JPA. Ajouter le lien `getOriginalException()` au même parcours, déjà utilisé par l'owner I2, rétablit NON_COUNTABLE. Cela corrige le classificateur pur ; l'owner I2 avait déjà sa propre protection transport et aucune comptabilisation après UNKNOWN n'est revendiquée comme défaut observé de cet owner.
2. `CompletionException(CancellationException)` était traitée elle-même comme faute technique sans autre preuve. Rendre les enveloppes standard `CompletionException`/`ExecutionException` neutres, en parcourant toujours leur cause. Une cause SQL ou un vrai bug enveloppé reste comptabilisable ; une simple annulation/interruption reste NON_COUNTABLE.

Le test de reproduction composite contient dix méthodes, dont deux échouent avant correction (NON_COUNTABLE attendu, TECHNICAL ou SQL_OR_JPA observé). Aucun changement de priorité, aucun déplacement de logique SQL/Spring dans Engine. Les exceptions métier et la lambda de classification Command ne sont pas modifiées.

### Nouvelles preuves exécutables

| Méthodes | Garantie et oracle |
|---|---|
| Engine : `classifierExceptionAfterConfirmedRollbackReturnsCapacity`, `nullClassificationAfterConfirmedRollbackReturnsCapacity` | Deux reproductions de fuite, aucune cession, handle non réutilisable, capacité immédiatement rendue sans inspection |
| PostgreSQL : `i3_audit_classifier_exits` | WRITE/JPA réel et READ technique ; faute après effets/publication puis rollback réel ; classifier RuntimeException/Error/null ; slot identique, zéro effet/budget/cession, nouvelle acquisition et commit |
| PostgreSQL : `i3_audit_classifier_active` | Barrière dans classifier après rollback ; deux `settlePending` concurrents rendent zéro tant que callback actif ; capacité bloquée puis disponible à la sortie effective |
| PostgreSQL : `i3_audit_cession_failure` | Vrai UPDATE de cession puis RuntimeException, rollback physique confirmé ; variante `Failure(ROLLED_BACK)` rend immédiatement ; variante owner de test masquant sa résolution conserve le ticket jusqu'à inspection ; deux inspections rendent au total une place, jamais deux ; nouvelle acquisition réelle |
| PostgreSQL : `i3_audit_cession_unknown_active` | Coupure réelle au commit/rollback de cession, connexion physique maintenue ouverte ; PID/XID et `pg_stat_activity.xact_start` observés ; ticket conservé après retour local et expiration, verrou sauté ; rollback physique explicite puis deux inspections concurrentes : une restitution ; zéro budget durable |
| `ConsumptionCompositeClassificationTest` : dix méthodes | Causes isolées, sept codes 08 et 40003, cause/suppressed/nextException mixtes dans les deux ordres, cycles, Spring/JPA composite et originalException, enveloppes de cancellation/interruption seules et avec vraie faute, Collision/InconsistentPotFactsException avec/sans cause exclue |
| Composition Command réelle : `compositionUsesOneWriteManagerAndExplicitAuthority` enrichie | Inspecte le classificateur réellement injecté par la fabrique (réflexion exclusivement en test, aucun nouveau point d'entrée productif) : Collision/faits × aucune cause/08/40003/configuration, directs et enveloppés ; NON_COUNTABLE ne peut pas être surchargé par le type métier |

Les scénarios historiques frais `i3_c05_h`, `i3_capacity_uncertainty`, les 142 méthodes I2, owner, suffixe, gardes, R9 et régressions L1 sont requis par le manifeste de clôture, sans réemploi des anciens résultats. Le nouveau manifeste est un sur-ensemble des obligations I3 ; les manifests historiques restent intacts. Les variantes répétées dans plusieurs gates ne sont pas additionnées en garanties nouvelles.

## Invariants et limites

Fin locale effective **et** dernière résolution physique connue, ou inspection technique suffisante, restent nécessaires. Une transaction physique encore ouverte garde son ticket ; un slot absent après tentative exige R9 COMPLETED cohérent. Aucun UNKNOWN, timeout ou signal de cancellation n'est converti en rollback. Aucune relance de tentative, cession ou publication après issue incertaine. Aucun incrément sans rollback certain et cause comptabilisable. M_C/M_W, restitution idempotente, guards/poisoning/ordre/suffixe et toutes les contraintes I2 restent inchangés.

Les ressources globales d'un Worker actif, tous ses emprunteurs et exécuteurs, ses cadences/accès réservés restent **I5 INCONCLUSIVE**. Le dimensionnement et le harnais de saturation existants restent inchangés. Pas de nouveau pool, thread de renouvellement, scheduler ou admission globale. READ reste un effet technique de fixture, Identity une fixture ; aucune projection/producteur absent n'est certifié.

## Gates frais, provenance et livraison

**À compléter après gel du code et exécution des quatre gates.** Aucun PASS de clôture n'est présumé à ce stade.

Collecteur réutilisé sans modification : [verify-v3-l2-i3.py](../../scripts/testing/verify-v3-l2-i3.py), via [run-gate.py](../testing/evidence/L2-I3-post-audit/run-gate.py) et le [manifeste post-audit](L2_I3_Post_Audit_Proof_Manifest.json). Commandes Maven exactes depuis `app/`, sans filtre ni exclusion :

```sh
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-persistence-read-jdbc -am test
./mvnw -pl runtime-monolith -am test
./mvnw -pl architecture-tests -am test
```

Le gel app/scripts est vérifié avant chaque gate. XML et traces doivent être frais, méthodes obligatoires présentes une seule fois et sans skip, sous-variantes physiques présentes. Un skip historique éventuel reste un skip. Aucun test absent, omis ou interrompu ne vaut PASS. Les archives conserveront aussi les échecs de reproduction.

**Verdict à ce stade : certification finale en attente.** Les limites globales restent inchangées, aucun G1/G2/G3 ni ACCEPTED L2. Arrêt impératif après ces corrections.
