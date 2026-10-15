# DEBT-TEST-01 — Horloge contrôlée et preuve directe du fencing

Statut de validation : **NOT_EXECUTED** pour les scénarios PostgreSQL. Correctif de tests livré pour revue, sans validation d'exécution ni de compilation de la classe modifiée. Diagnostic historique : **INCONCLUSIVE**. Statut recommandé de la dette : **OPEN**, en attente de preuves exécutées.

Référence : [audit complet](Audit_2026-10-15.md), commit `eef30128b21222c74a20f85d9145cf62167d20a7`. [Diff exact des tests](fix-20261015/Tests.patch).

## Baseline et périmètre déclaré

- Checkout initial propre : `audit/takeover-flakiness`, HEAD `eef30128`, upstream `origin/audit/takeover-flakiness`, divergence `0/0`.
- `git ls-remote origin refs/heads/feat/runtime-monolith refs/heads/audit/takeover-flakiness` confirme respectivement `2919a7c68895d65c96ee89dd44bfea82385a28de` et `eef30128b21222c74a20f85d9145cf62167d20a7`.
- La branche d'audit ne contient que les quatre fichiers documentaires au-dessus du HEAD de développement `2919a7c6`. Aucun changement de `app/` depuis le périmètre audité.
- Branche dédiée : `test/takeover-fencing`, créée à `eef30128`. Elle reprend donc exactement le code de `feat/runtime-monolith`, avec la traçabilité de l'audit dans son ascendance ; aucun merge ni cherry-pick nécessaire.
- Politique normative, audit intégral et dette lus avant modification. Aucun changement local préexistant écrasé.
- Java du wrapper : Eclipse Adoptium 21 ; Maven 3.9.14. [Sortie complète](fix-20261015/maven-version.log).

### Verification scope

Déclaration faite avant modification : preuve locale du consommateur CommandResult et des primitives Consumption, dans le module hébergeant le test `architecture-tests`. Il n'existe pas de slice canonique dédiée au runtime de résultat direct dans la politique actuelle. Aucun comportement du worker COMMAND, des projections ou d'un autre runtime n'est modifié.

- Impact attendu et observé en production : **NONE**.
- Slices secondaires : **NONE** ; voisins ciblés dans `infra-persistence-primary-jpa` pour les mêmes garanties.
- Seul fichier applicatif modifié : [CommandResultConsumptionChainPostgresTest.java](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandResultConsumptionChainPostgresTest.java), exclusivement sous `src/test/`.
- Interdits : code runtime, schéma, contrats et sémantique des leases/fences, désactivation/affaiblissement des tests.
- Gate global d'architecture : **NOT REQUIRED**, non exécuté. Reactor complet : **NOT REQUIRED**, non exécuté et exclu par la mission.
- La commande canonique COMMAND `./mvnw -pl runtime-command-consumption-worker -am test` n'est pas lancée : elle ne sélectionne pas la classe demandée et aucun code de cette capacité n'est modifié. Le module `architecture-tests` est utilisé avec une sélection explicite ; aucun `-am`, `-amd` ou ensemble global de tests d'architecture.
- DB : schéma courant par le bootstrap existant ; aucune migration exécutée dans cette session, aucune modification SQL de schéma. La politique indique toujours `Trusted baseline: NONE`.
- Escalade : si une anomalie runtime apparaît, la documenter séparément et arrêter l'élargissement. Aucune anomalie runtime observée ; aucun crossing inattendu.

## Baseline originale préservée

Avant toute édition du test, une tentative isolée du scénario original a été effectuée depuis `app/` :

```sh
./mvnw -v
./mvnw -o -pl architecture-tests \
  -Dtest='CommandResultConsumptionChainPostgresTest#takeoverFencesStaleWorkerAndConvergesToOneResult' \
  -Dsurefire.rerunFailingTestsCount=0 \
  -Dsurefire.reportsDirectory=/tmp/pocoma-takeover-fix-20261015/baseline-001 \
  surefire:test
```

[Résultat individuel baseline-001](fix-20261015/baseline-001.log) : sortie 1, `DependencyResolutionException` avant Surefire. Quatre artefacts internes `0.0.1-SNAPSHOT` manquent dans le cache : `pocoma-runtime-command-result-consumption-worker`, `pocoma-runtime-registration-consumption-worker`, `pocoma-runtime-registration-result-consumption-worker`, `pocoma-runtime-binding-consumption-worker`. Le mode offline et l'invocation directe Surefire évitent téléchargement et recompilation pendant cette tentative.

`docker version`, confirmé hors sandbox, échoue aussi : socket `/Users/julien.guezennec/.docker/run/docker.sock` absent dans le contexte `desktop-linux`. [Sonde conservée](fix-20261015/docker-version.log). Aucun démarrage de Docker ni serveur PostgreSQL de substitution. Le premier palier de 20 n'a donc pas pu débuter ; multiplier ce même blocage 100 fois n'apporterait aucune observation du scénario.

L'original reste accessible à `eef30128`. Le correctif ne réécrit pas l'audit ni la dette et ne transforme pas les anciennes réussites en preuves de cette intervention.

## Modifications et nouvelles assertions

Les références de lignes suivantes concernent le [test livré](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandResultConsumptionChainPostgresTest.java). Elles décrivent des **preuves encodées à exécuter**, pas des résultats acquis.

### Horloge et autorité avant takeover

Lignes 73, 91–99 et 330–340 : remplacement explicite du bean **nommé** `commandResultClock` par `@TestBean(enforceOverride=true)` ; aucune modification de configuration de production. Cette exigence fait échouer le contexte si le bean attendu disparaît. La même horloge alimente les injections qualifiées existantes acquire/execute/failure/locator. L'instant est conservé dans un `AtomicReference`, pour que les deux workers du test concurrent existant lisent la même valeur. `withZone` partage cette valeur. Réinitialisation à `t0` avant chaque méthode.

Lignes 236–247 : acquisition vérifiée à `t0`, lease à `t0+30s`, égalité avec le claim persistant ; avancement explicite à `t0+31s`, lease expirée, claim toujours ouvert et toujours installé dans le slot. Aucun UPDATE artificiel de lease, aucune attente de durée réelle. Le seed utilise cette même horloge et un instant représentable exactement à la microseconde (lignes 302–319).

`expiredClaimCanStillFinalizeBeforeTakeover` (156–165) va au-delà d'une lecture du claim courant : après expiration, C1 exécute le chemin transactionnel normal et finalise avec succès, puis une nouvelle acquisition retourne `AlreadyDone`. Il vérifie que l'expiration seule ne retire pas l'autorité.

### Parcours réel locator/orchestrator conservé

`takeoverFencesStaleWorkerAndConvergesToOneResult` (135–154) conserve le parcours réel. Son retour doit être `Idle`, avec exactement un candidat et une exécution. Les deux claims doivent être distincts, tentative 1 puis 2, C1 invalidé `TAKEN_OVER`, C2 détenu par `takeover`, avec dates de claim et lease attendues. La finalisation et le contenu complet du résultat sont contrôlés.

L'assertion précise `DataIntegrityViolationException` du rejeu identique est **conservée**, avec comparaison du slot et des claims avant/après. Elle reste une vérification de la contrainte indépendante de provenance ; elle n'est plus utilisée comme seule justification du fencing. Le scénario distinct ci-dessous doit réussir indépendamment de cette assertion. Aucune exception générique n'est acceptée à sa place.

### Preuve directe : C2 installé, slot encore PENDING

`staleClaimReachesFenceAndRollsBackBeforeWinnerFinalizes` (167–212) impose cet ordre, sans concurrence artificielle ni sleep :

1. C1 acquis à `t0`, expiration par horloge, acquisition committée de C2 à `t0+31s`.
2. Relecture persistante : même slot **PENDING**, `current_claim_id=C2`, revision 2, deux claims, C1 invalidé, C2 ouvert. Les résultats et inputs sont encore absents.
3. C1 appelle le vrai `ExecuteConsumptionUseCase`. Son callback matérialise le vrai CommandResult de la commande ; **dans la transaction**, le test vérifie la présence et le contenu exact du résultat inséré.
4. Le callback fournit la provenance normale de l'événement. Comme C2 n'a encore rien exécuté et que les inputs étaient vides, aucune unicité de provenance préalable ne doit arrêter ce chemin.
5. Le test exige `LostClaimException`, avec les IDs exacts de C1 et du slot. Un `@MockitoSpyBean` sur le repository vérifie l'appel **réel** à `terminalize(slot, C1, SUCCESS, null, instant contrôlé)` ; aucun retour ni comportement n'est stubé.
6. Après rollback : aucun CommandResult, input ou consumption result ; le snapshot complet du slot et des deux claims est identique à celui suivant le takeover. C2 reste donc courant et ouvert.
7. C2 exécute le chemin normal ; son appel au CAS est également observé. Un seul résultat exact, une seule provenance, un seul slot DONE/SUCCESS sans claim courant, revision 3, C2 clos SUCCESS. Une réacquisition `AlreadyDone` ne modifie pas le slot.

Le slot est encore **PENDING** au moment du refus de C1 : le scénario distingue ainsi le contrôle d'identité du claim d'un simple refus d'un slot déjà DONE. L'insertion SQL réussie avant le refus, la vérification du repository, l'exception précise et les snapshots persistants évitent de confondre rejet métier et violation d'unicité indépendante.

Le nombre de lignes affectées par le CAS n'est pas intercepté ni fabriqué par le test. Dans le code réel, `JpaConsumptionLifecycleAdapter.tryTerminalize` retourne false uniquement pour `updated == 0` ([adapter](../../../app/infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/adapter/consumption/JpaConsumptionLifecycleAdapter.java), 154–163), et `ExecuteConsumptionService` lève alors `LostClaimException` ([service](../../../app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/service/consumption/ExecuteConsumptionService.java), 59–61). La conjonction des assertions est destinée à prouver cette chaîne réelle.

Les tests de succès, perte du claim, exception après insert et deux workers sont conservés. Les helpers renforcent les assertions persistantes : slot, revision, dates, raison terminale, historique complet des claims, propriétaire du résultat, commande, Pot, version résultante 7, date de résolution et provenance exacte (lignes 250–289).

## Invariants : comparaison avant/après

| Invariant | Avant | Preuve ajoutée / conservée après |
|---|---|---|
| INV-1 Fencing | Rejeu après DONE pouvait être arrêté par l'unicité des inputs avant CAS. | Nouveau scénario sur slot PENDING appartenant à C2 ; INSERT C1 observable, appel réel CAS C1, `LostClaimException` précise, rollback et snapshots inchangés. |
| INV-2 Takeover | Résultat final et statut DONE seulement. | Horloge contrôlée ; dates C1, expiration, autorité conservée avant reprise ; C2 Acquired distinct, tentative 2, installation persistante et invalidation exacte C1. Parcours orchestrator conservé. |
| INV-3 Atomicité | Tests d'exception après insert et perte de claim. | Ces tests sont conservés ; nouveau rollback du résultat et de la provenance lors du refus du fence, sans altérer l'acquisition C2 déjà committée. |
| INV-4 Unicité | Comptage d'un résultat, plus test à deux workers. | Comptage conservé et contenu exact/propriétaire contrôlés ; finalisation unique via revision 3 et réacquisition idempotente. Test à deux workers conservé, sans prétendre imposer tous ses interleavings. |
| INV-5 Recovery | Rollback constaté sans reprise CommandResult ultérieure dans ce scénario. | Après l'exécution stale rollbackée, C2 publie et finalise normalement. Cela couvre cette reprise valide, pas un crash/restart du processus. |

Toutes ces preuves nouvelles sont **NOT_EXECUTED**. Aucun nouveau constat dynamique de conformité des invariants n'est revendiqué. Une course avec C1 déjà engagé avant le takeover n'est pas ajoutée ici : le périmètre demandé est la tentative de C1 après perte de son claim, tout en conservant la concurrence existante.

## Vérification réellement tentée après modification

Depuis `app/` :

```sh
./mvnw -o -pl architecture-tests \
  -Dtest=CommandResultConsumptionChainPostgresTest \
  -Dsurefire.rerunFailingTestsCount=0 \
  -Dsurefire.reportsDirectory=/tmp/pocoma-takeover-fix-20261015/corrected-001 test

./mvnw -o -pl infra-persistence-primary-jpa \
  -Dtest=JpaConsumptionLifecycleAdapterPostgresTest,JdbcCommandResultStorePostgresTest \
  -Dsurefire.rerunFailingTestsCount=0 \
  -Dsurefire.reportsDirectory=/tmp/pocoma-takeover-fix-20261015/neighbors-001 test
```

- [corrected-001](fix-20261015/corrected-001.log) : sortie 1, mêmes quatre artefacts absents ; arrêt **avant compilation et Surefire**. Les sept méthodes de la classe n'ont pas été exécutées. L'observation du CAS gagnant a ensuite été ajoutée lors de la revue statique ; le blocage de résolution demeure, aucun nouveau lancement identique n'est présenté comme preuve.
- [neighbors-001](fix-20261015/neighbors-001.log) : sortie 1 ; Maven considère les classes du module voisin à jour et atteint Surefire 3.5.5. Testcontainers 2.0.4 ne trouve pas Docker : `Could not find a valid Docker environment`, puis `Previous attempts to find a Docker environment failed. Will not retry.` Deux erreurs de démarrage de classes ; **aucun corps de scénario PostgreSQL exécuté**. Le message de Testcontainers décrit son état interne, pas un retry automatique ajouté par l'intervention.
- Le paramètre `surefire.reportsDirectory` passé sur la ligne de commande n'a pas déplacé les rapports des voisins avec la configuration effective. Les rapports utiles ont été copiés depuis `infra-persistence-primary-jpa/target/surefire-reports` : [lifecycle TXT](fix-20261015/com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionLifecycleAdapterPostgresTest.txt), [store TXT](fix-20261015/com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JdbcCommandResultStorePostgresTest.txt). Les XML bruts correspondants sont conservés sous `/tmp/pocoma-takeover-fix-20261015/`. Aucun rapport de classe modifiée n'a été produit.
- Revue statique et `git diff --check` sur le test et ce rapport ; vérification des liens locaux et de la correspondance du patch au diff. Ces contrôles ne remplacent ni compilation ni exécution Spring/PostgreSQL. Les logs et le patch sont conservés bruts, y compris les espaces de sortie Maven et les lignes de contexte du patch.

### Comptage des campagnes

| Campagne | Objectif | Lancements Maven | Succès de scénarios | Échecs métier observés | Scénarios exécutés | Blocage |
|---|---|---:|---:|---:|---:|---|
| Baseline originale | 20, puis 100 | 1 | 0 | 0 | **0** | Résolution Maven avant Surefire |
| Classe corrigée | Voisins de classe puis 20/100 répétitions | 1 | 0 | 0 | **0** | Résolution Maven avant compilation |
| Voisins persistence | Deux classes ciblées | 1 | 0 | 0 | **0** | Deux erreurs d'initialisation Testcontainers |

Les chiffres Surefire des voisins (`Tests run: 2, Failures: 0, Errors: 2, Skipped: 0`) concernent deux erreurs au démarrage des classes. Ils ne signifient pas que deux scénarios métier ont tourné. Taux de flakiness avant/après : **non estimable**. Aucun retry automatique, aucune répétition parallèle, aucun reactor complet.

## Suite nécessaire et statut de la dette

Sur un environnement Docker fonctionnel avec les artefacts du HEAD préparés :

1. Réexécuter la baseline à `eef30128` dans un checkout séparé, premier palier 20 puis jusqu'à 100 ; conserver chaque résultat individuel, même en présence d'échecs.
2. Compiler une fois la classe corrigée avec le Maven/JDK du projet, puis exécuter ses sept méthodes et les voisins persistence ciblés. Vérifier notamment le remplacement effectif du bean et le spy du repository dans le contexte Spring.
3. Répéter en série les deux scénarios de takeover (parcours orchestrator et preuve directe du CAS), chacun 20 puis jusqu'à 100 fois. Utiliser directement `surefire:test` après compilation ; aucun retry. Copier les rapports du chemin réellement utilisé par le plugin après chaque passage dans un dossier numéroté, sans compter des erreurs d'initialisation comme exécutions métier.
4. Conserver les logs/rapports et, en cas d'échec, les snapshots avant nettoyage du container. Comparer baseline et correction sans conclure à un défaut runtime à partir d'une erreur d'infrastructure.

Le correctif supprime la dépendance au temps réel du scénario et ajoute une preuve conçue pour discriminer le fence de l'unicité SQL. Il n'établit pas la cause historique et n'est pas encore validé dynamiquement. **DEBT-TEST-01 doit rester OPEN** ; aucune modification de `Debt.md` dans cette livraison. Aucun correctif runtime, schéma ou contrat. Branche de livraison : `test/takeover-fencing` ; commit et push consignés dans le message de livraison, sans merge.
