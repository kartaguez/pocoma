# DEBT-TEST-01 — Validation PostgreSQL avec le reactor Maven

Validation PostgreSQL : **PASS sur le périmètre déclaré**. Baseline originale : **100/100**. Parcours corrigé locator/orchestrator : **100/100**. Scénario direct de fencing et rollback : **100/100**. Classe corrigée complète et voisins persistence : **21/21** supplémentaires. Aucun échec, erreur ou skip. Diagnostic historique maintenu : **INCONCLUSIVE** ; DEBT-TEST-01 reste **OPEN**.

## Baseline et environnement

Checkout initial propre : `test/takeover-fencing`, HEAD `fa9be0a161680869eb032a2379f283605334eeb9`, upstream `origin/test/takeover-fencing`, divergence `0/0`. Aucun changement du test ou du runtime pendant cette validation. La baseline originale `eef30128b21222c74a20f85d9145cf62167d20a7` est extraite par `git archive` dans `/tmp/pocoma-takeover-validation-20261015/baseline`, sans modifier le checkout courant.

Docker Desktop a été démarré à la demande de l'utilisateur. Docker Engine 29.4.0, Docker Desktop 4.70.0, Linux aarch64 ; JDK Eclipse Adoptium 21 et wrapper Maven 3.9.14 du projet. PostgreSQL effectif : **17.11** ; image `postgres:17-alpine`, digest `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`.

Les références précédentes restent consultables : [audit](Audit_2026-10-15.md), [correctif et premiers blocages](Implementation_2026-10-15.md), [diff exact du test](fix-20261015/Tests.patch). Le statut NOT_EXECUTED de la première intervention décrit ces tentatives antérieures ; ce rapport apporte les nouvelles observations.

## Périmètre déclaré et résolution Maven

Preuve locale : CommandResult/Consumption, plus les deux classes persistence voisines ; aucune slice fonctionnelle secondaire modifiée, aucun impact de production attendu ou observé. Gate global d'architecture non requis et non exécuté. Aucun reactor complet exécuté, aucune modification de schéma ou contrat, aucun crossing inattendu. Le bootstrap existant des tests a appliqué les 29 migrations jusqu'à V29 sur les bases temporaires ; aucun gate historique autonome ni baseline improvisée, la politique indiquant toujours `Trusted baseline: NONE`.

L'utilisateur a explicitement demandé `-pl … -am` et interdit l'installation artificielle des quatre SNAPSHOT manquants. La fermeture `-pl architecture-tests -am` comprend **60 projets sur les 61** déclarés par le POM : seul `runtime-monolith` est exclu. La compilation est donc presque globale du fait du graphe du module hébergeant le test. **Les tests restent explicitement ciblés**, aucun test d'architecture global ni suite complète des modules de la fermeture n'est lancé.

`-Dsurefire.failIfNoSpecifiedTests=false` autorise seulement les modules amont qui n'hébergent aucun test correspondant à la sélection. Il n'ignore pas les échecs des scénarios sélectionnés. Tous les lancements utilisent `-Dsurefire.rerunFailingTestsCount=0` ; aucun `install`, `install-file`, retry automatique, sleep ou parallélisme des répétitions.

Commandes exécutées depuis le dossier `app/` de la version considérée :

```sh
# Graphe du checkout courant, puis préparation unique de la copie baseline.
./mvnw -o -pl architecture-tests -am validate
./mvnw -o -pl architecture-tests -am -DskipTests \
  -Dspring-boot.repackage.skip=true package

# Tentative directe après package : échec de résolution, avant test.
./mvnw -o -pl architecture-tests -am \
  -Dtest='CommandResultConsumptionChainPostgresTest#takeoverFencesStaleWorkerAndConvergesToOneResult' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.rerunFailingTestsCount=0 surefire:test

# Cycle test : résout les dépendances entre projets du reactor sans install.
./mvnw -o -pl architecture-tests -am \
  -Dtest='CommandResultConsumptionChainPostgresTest#takeoverFencesStaleWorkerAndConvergesToOneResult' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.rerunFailingTestsCount=0 test

# Compilation unique de la version corrigée, sans tests en parallèle de la baseline.
./mvnw -o -pl architecture-tests -am -DskipTests test

# Classe corrigée complète et voisins persistence : cycle test sans options de skip compilation.
./mvnw -o -pl architecture-tests -am \
  -Dtest=CommandResultConsumptionChainPostgresTest,JpaConsumptionLifecycleAdapterPostgresTest,JdbcCommandResultStorePostgresTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.rerunFailingTestsCount=0 test
```

La première tentative directe `surefire:test -am` ne permet pas à Maven de résoudre les quatre artefacts dans cette nouvelle invocation, même après `package`. Elle est conservée comme échec de lancement, pas comme échec du scénario. Le cycle `test -am` réussit sans dépôt local artificiellement rempli.

### Répétitions sans recompilation

Les neuf premiers passages baseline ont déclenché 48 invocations de compilation chacun dans la copie temporaire, malgré l'absence de changement de sources. Pour arrêter cette cascade, les deux propriétés suivantes ont été ajoutées aux options de lancement **après compilation complète des sources** :

```sh
-Dmaven.main.skip=true -Dmaven.compiler.useIncrementalCompilation=false
```

Dans la copie baseline uniquement, elles sont d'abord fournies par un `.mvn/maven.config` temporaire à partir du passage 10 ; elles sont ensuite explicites dans le lanceur de campagne. Aucune configuration Maven du repository n'est modifiée. Les passages suivants conservent le cycle `test -am` pour la résolution entre modules, mais les logs doivent montrer **zéro compilation**. Le compilateur de tests vérifie les classes à jour ; les sources ne changent pas pendant la campagne. Les propriétés ne désactivent pas Surefire.

Sélection corrigée des répétitions :

```sh
./mvnw -o -pl architecture-tests -am \
  -Dtest='CommandResultConsumptionChainPostgresTest#takeoverFencesStaleWorkerAndConvergesToOneResult+staleClaimReachesFenceAndRollsBackBeforeWinnerFinalizes' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.rerunFailingTestsCount=0 \
  -Dmaven.main.skip=true -Dmaven.compiler.useIncrementalCompilation=false test
```

Chaque lancement est séquentiel, dans un nouveau processus Maven/Surefire, avec un nouveau conteneur PostgreSQL. Les deux méthodes corrigées sont sélectionnées ensemble dans chaque lancement, sans parallélisme de répétition. Les rapports XML/TXT et le log Maven sont copiés dans un dossier numéroté après chaque passage, avant le suivant ; les anciens rapports de la classe sont retirés du dossier de sortie pour éviter une lecture périmée. Les résultats CSV exigent le nombre attendu de tests, aucun échec/erreur/skip et un code Maven nul pour compter PASS.

## Résultats

| Campagne | Premier palier | Résultat final | Échecs / erreurs / skips |
|---|---:|---:|---:|
| Baseline `eef30128` : scénario original | 20/20 | **100/100** | 0 / 0 / 0 |
| `fa9be0a1` : parcours locator/orchestrator corrigé | 20/20 | **100/100** | 0 / 0 / 0 |
| `fa9be0a1` : scénario direct du CAS et rollback | 20/20 | **100/100** | 0 / 0 / 0 |
| Classe corrigée complète, passe séparée | — | **7/7** | 0 / 0 / 0 |
| `JpaConsumptionLifecycleAdapterPostgresTest` | — | **12/12** | 0 / 0 / 0 |
| `JdbcCommandResultStorePostgresTest` | — | **2/2** | 0 / 0 / 0 |

Total : **321 exécutions de test réussies**. Les deux méthodes corrigées ont également été exécutées dans la passe complète de sept tests : celle-ci est comptée séparément des 100 répétitions. La tentative directe Surefire bloquée avant test n'entre pas dans ces nombres. Les anciennes erreurs d'environnement de la première livraison ne sont ni effacées ni requalifiées en succès.

Les 100 lignes de chaque CSV ont été contrôlées : numéros 1 à 100 sans trou ni doublon, codes Maven nuls, nombre exact de tests, aucun échec/erreur/skip. Les XML ont été relus pour vérifier les noms des méthodes et leur résultat. Zéro compilation pour les 100 passages corrigés, et pour les passages baseline 10 à 100. Empreintes des sources identiques avant/après ; aucun changement dans `app/` depuis `fa9be0a1`. Les quatre répertoires SNAPSHOT locaux ne contiennent toujours ni JAR ni POM : leur installation n'a pas servi à la validation.

### Pièces conservées

- [Résultats individuels baseline](validation-20261015/baseline-results.csv) et [résultats individuels corrigés](validation-20261015/corrected-results.csv).
- [Synthèse vérifiée](validation-20261015/summary.json), [environnement et empreintes des sources](validation-20261015/environment.json).
- [Archive des preuves brutes](validation-20261015/raw-evidence.tar.gz) : 200 dossiers numérotés avec log Maven et rapports Surefire XML/TXT, rapports des trois classes de la passe complète, logs de préparation et de l'échec direct Surefire, lanceur séquentiel et options temporaires de compilation. Elle ne contient pas la copie des sources ou ses répertoires `target/` complets.
- [Empreintes SHA-256 des pièces](validation-20261015/SHA256SUMS).

Les originaux de travail restent sous `/tmp/pocoma-takeover-validation-20261015/`. Aucun état d'échec de scénario à collecter puisque tous les scénarios exécutés ont réussi ; les états persistants de succès sont contrôlés par les assertions référencées ci-dessous, sans prétendre disposer de dumps SQL indépendants.

## Portée de la preuve

Les [assertions du test livré](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/ccr/CommandResultConsumptionChainPostgresTest.java) sont inchangées depuis `fa9be0a1` :

| Invariant | Assertions vérifiées par les scénarios |
|---|---|
| INV-1 Fencing | Le scénario direct (lignes 167–212) garde C2 courant et le slot PENDING, constate l'INSERT SQL C1 dans sa transaction, vérifie l'appel réel au CAS de C1 et `LostClaimException` avec les IDs exacts, puis l'absence du résultat/provenance et les snapshots inchangés après rollback. Le repository n'est pas stubé. |
| INV-2 Takeover | Claim C1 à `t0`, lease à `t0+30s`, expiration contrôlée à `t0+31s` sans changement persistant ; C1 peut encore finaliser avant takeover (156–165). C2 est acquis et installé, tentative 2, C1 invalidé `TAKEN_OVER` ; le parcours locator/orchestrator reste couvert (135–154). |
| INV-3 Atomicité | Absence de résultat et provenance après refus de CAS ; tests existants de perte du claim et exception après insert conservés (115–132). |
| INV-4 Unicité | Un seul slot/résultat/provenance, contenu exact et propriétaire, revision terminale, réacquisition `AlreadyDone`, rejeu identique et concurrence existante conservés (135–154, 202–224, helpers 263–289). |
| INV-5 Recovery | C2 termine normalement après rollback des effets de C1 ; voisins vérifient également retry/réacquisition et failure stale. Cela ne constitue pas une simulation de crash du processus. |

La violation attendue `uk_consumption_inputs_identity` du rejeu identique reste visible dans les logs de tests **réussis**. Elle appartient à l'assertion précise de provenance conservée. Le scénario direct teste séparément le fence avant publication de C2, sans doublon préalable : ne pas compter cette ligne ERROR SQL attendue comme un échec Surefire.

## Diagnostic et dette

Les succès répétés de la baseline ne reproduisent pas l'échec historique. Ils ne démontrent pas sa cause ni une absence de flakiness dans toutes les conditions de CI ou de charge. Le nouveau scénario a désormais passé sa preuve du fence et du rollback 100 fois dans la campagne, mais ne reconstitue pas un worker C1 déjà engagé avant le takeover. À titre indicatif, 0 échec sur 100 essais indépendants à probabilité constante donne une borne supérieure unilatérale à 95 % d'environ 2,95 % ; ces hypothèses ne sont pas garanties pour des essais sur le même hôte.

Diagnostic historique maintenu : **INCONCLUSIVE** ; aucun défaut runtime démontré. Statut recommandé de DEBT-TEST-01 : **OPEN**, avec fiabilisation du test désormais validée sur ce périmètre et diagnostic à faire approuver. Aucun changement de `Debt.md` ni clôture automatique. Aucun merge. Livraison documentaire sur `test/takeover-fencing` ; code validé à `fa9be0a1`, commit du compte rendu et état Git final fournis dans le message de livraison.
