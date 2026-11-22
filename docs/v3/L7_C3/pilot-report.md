# L7-C3 — Rapport d’exécution du pilote CommandResult

> [!NOTE]
> Qualification historique du pilote isolé. Le [contrat livré](../../reference/read-availability.md) couvre les huit familles.

Les exécutions I1–I6 et l’état actuel du lot figurent dans le [rapport de généralisation](generalization-report.md).

Le mandat sur `feat/v3-l7` au HEAD `d7b4b6d73963cab7efeb658ef67478d52de03c32` ratifie [C3-R1 et C3-R2](arbitrages.md) et autorise uniquement l’[incrément 1](plan.md#1-pilote-historique-avec-commandresult). Le pilote publie `COMMAND_RESULT_AVAILABLE` avec la clé de demande, une position dense et un UUID indépendant. La transaction finale READ conserve le fencing et le propriétaire V3.

## Modification et périmètre

- La V5 pilote crée deux tables READ, le compteur et le journal. Les contraintes différées croisent allocation, événement et résultat à la validation du commit. La mutation du compteur est limitée à son pas de un dans la transaction et le xid est attribué par le trigger. Le journal est immuable. Les clés logiques, positions et UUID sont uniques. Une V4 contenant déjà des projections, des slots ou des checkpoints avancés est refusée. [Migration V5](../../../app/infra-postgres-read/src/main/resources/db/read/pilot/migration/V5__command_result_availability.sql).
- Le résultat terminal est inséré ou comparé à l’existant. `requireCompletionReady` vérifie la preuve métier et l’absence attendue ou la présence exacte de sa publication. Le finalisateur ferme la publication après le DELETE du slot, puis attribue la position et écrit l’événement dans le callback déjà gardé. La récupération lit résultat et publication dans le même snapshot que slot et checkpoint. [Port](../../../app/port-projection/src/main/java/com/kartaguez/pocoma/port/projection/CommandTerminalResultStore.java) · [Adaptateur](../../../app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/JdbcCommandTerminalResultStore.java) · [Participant](../../../app/engine-materialize-results/src/main/java/com/kartaguez/pocoma/engine/materialize/results/MaterializeCommandResult.java).
- L’emplacement Flyway pilote et la fabrique JDBC explicite rendent le parcours activable dans la fixture intégrée. Le bootstrap opérationnel V4 et L6/I2 sont en dehors de l’activation du pilote. Installer V5 dans le répertoire canonique aurait déjà imposé ses contraintes aux chargeurs V4 et à des seeds HTTP existants. La correction minimale retenue est l’installation explicite de V5 et l’actualisation de l’inventaire SQL. La promotion dans le chemin canonique et la garde de bootstrap restent à l’incrément 4. [Inventaire d’architecture](../../../app/architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/l7/L7ReactorArchitectureTest.java#L164).
- Impact déclaré et constaté dans **PROJECTION**, plus assertion d’ownership déclarée dès le départ. Aucune tranche secondaire ni traversée inattendue. L’écriture READ du pilote utilise le port existant. Le lecteur HTTP garde la même opération `find` et le même accès. `ConsumptionEngine`, `SlotCompletion` et `SpringAttemptTransactions` sont intacts. Aucun autre matérialiseur C3 activé.

## Preuves ciblées exécutées

Sur PostgreSQL **17.11** en conteneur, **11 cas C3 et 3 cas existants exécutés, zéro échec et zéro skip**, avec rapports Surefire isolés. Les scénarios intégrés utilisent le véritable `DiscoveryEngine` et le véritable `ConsumptionEngine`, les autorités WRITE et READ séparées et les stores JDBC. Les tests d’égalité concurrente et de mesure utilisent directement l’adaptateur dans son owner physique. [Tests intégrés](../../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/CommandResultAvailabilityPostgresTest.java) · [Reçu et mesures brutes](../../testing/evidence/L7-C3/command-result-pilot-targeted.json) · [Journaux, XML et traces](../../testing/evidence/L7-C3/command-result-pilot-targeted.tar.gz).

| Obligation | Observation |
| --- | --- |
| Atomicité et idempotence | Résultat, DELETE du slot, pas de compteur et événement observés sous le même `(pid,xid)`. Le rejeu d’un slot de test à effet égal conserve l’UUID, la clé et la position. |
| Densité et concurrence | Deux demandes sur le compteur produisent une attente PostgreSQL observée. Après commit, positions `1,2`. Après rollback de l’allocataire, le suivant obtient `1`. Aucun témoin partiel observable pendant l’attente. Deux transactions sur une même demande se sérialisent sur la clé et produisent un seul événement. Cas supplémentaires `REJECTED`, `UNCHANGED` et `INVITATION_CREATED` couverts avec le vrai store. |
| Rollback après DELETE | Un suffixe manquant fait échouer le commit via SQLSTATE `23514`. Une faute PostgreSQL injectée après allocation, même capturée par le participant décoré, fait restaurer le slot et annule résultat, compteur et événement. |
| Fencing | Après expiration provoquée et takeover, le premier token est refusé. Le token actif seul écrit le résultat et la position. |
| Commit de résultat inconnu | Proxy PostgreSQL réel avec coupure avant livraison de la commande COMMIT ou après la réponse physique COMMIT. Les deux retours JDBC sont `UNKNOWN`. Avant commit, `NOT_COMPLETED` puis reprise réussie à la position 1 après expiration. Après commit, `COMPLETED` avec slot absent, aucun nouveau claim et même événement. Dans les deux cas, aucune cession supposée. Retirer l’événement dans la fixture sous autorité admin transforme la récupération en `INCONSISTENT`. |
| Contraintes et migration | Refus du pas de deux, de l’allocation seule et de l’événement sans effet, ainsi que de l’UPDATE du xid ou du TRUNCATE par le worker. V5 vérifiée sur V4 vide, refus d’une V4 avancée et absence d’installation partielle. Les trois cas historiques de store incluent leur vérification de la fixture de droits de migration archivée. |

## Coût observé

Microbenchmark A/B sur le même serveur PostgreSQL **17.11**, quatre paires de schémas neufs, ordre alterné, cinq échauffements par schéma, puis **100 finalisations mesurées par variante**. Timing de l’unité JDBC READ jusqu’au commit, connexion directe comprise. Le lot mesure le résultat seul, hors WRITE, Discovery, claims, contention et topologie distribuée. Les percentiles cumulent les 100 valeurs. Le WAL est le delta d’insertion WAL du cluster pendant les fenêtres sérielles et non une attribution par relation. [Échantillons et décompte](../../testing/evidence/L7-C3/command-result-pilot-targeted.json).

| Mesure | V4 | V5 pilote | Surcoût |
| --- | ---: | ---: | ---: |
| Instructions JDBC préparées par résultat neuf | 3 | 6 | +3 |
| Latence p50 | 3,83 ms | 6,17 ms | +2,33 ms |
| Latence p95 | 4,16 ms | 6,89 ms | +2,73 ms |
| WAL moyen par finalisation | 289 octets | 826 octets | +537 octets |
| Relations nouvelles après 30 résultats par schéma, index inclus | 0 octet | 98 304 octets | +98 304 octets |

Le delta JDBC est une lecture de preuve et deux écritures du suffixe. Les 98 304 octets incluent les pages initiales des deux relations et de leurs index. Ils ne sont pas un coût par ligne. Les chiffres caractérisent le pilote sériel local. Les comparaisons sous les profils C2 et la contention multi-familles restent propres aux futurs incréments. [Protocoles de qualification ultérieurs](plan.md#comparabilité-des-mesures).

## Journal de vérification

Les commandes sont exécutées depuis `app/`. La qualification choisit les deux tests de résultats pour le retour ciblé et les deux ancres non filtrées pour la gate. Les paramètres d’identification et d’emplacement des rapports figurent dans les reçus. Ils isolent les exécutions courantes des anciens XML.

- `./mvnw -pl runtime-worker-materialize -am -Dtest=CommandResultAvailabilityPostgresTest,TerminalResultsReadStorePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test`. Import incorrect corrigé après le premier essai. Un essai intermédiaire a exercé 10 cas C3 et trois cas existants. Après ajout du dernier cas et des assertions de reprise, le checkout partagé a fourni une classe incohérente lors de la découverte JUnit. Compilation et exécution finale sur une copie isolée des sources, avec 14 cas réussis. [Reçu ciblé](../../testing/evidence/L7-C3/command-result-pilot-targeted.json).
- `./mvnw -pl runtime-worker-materialize,architecture-tests -am test`. Trois tentatives de gate sur copie partielle ont révélé successivement le vecteur de routage L2, le corpus de performance L2/I4, puis les contrats temporels L2/I5 manquants. Ces essais ont échoué ou été arrêtés une fois le manque d’entrée établi. Les erreurs de préparation sont conservées et ne constituent aucune preuve de régression C3.
- `./mvnw --fail-at-end -pl runtime-worker-materialize,architecture-tests -am test`. Relance intégrale avec toutes les sources documentaires suivies. La fermeture calculée contient les 42 modules enfants et l’agrégateur. Elle inclut les suites historiques transitives, dont WRITE. L’ancre d’architecture est requise pour l’inventaire de ressources. Il n’y a pas d’exécution sans sélection d’ancres.
- `git diff --check`, `git diff --cached --check` et vérification Python des liens, ancres, empreintes d’archive et entrées gelées.

La base de vérification du pilote est **CURRENT**, PostgreSQL jetable avec READ V1–V4 et V5 explicitement ajoutée. L’historique joué provient des fixtures existantes des suites de non-régression, sans migration d’une base en service. Aucun changement de branche, commit ou push. Le HEAD de départ et de fin reste `d7b4b6d7`.

## Résultat des gates et décision

| Preuve | Résultat exécuté |
| --- | --- |
| Tranche canonique PROJECTION, incluse dans la commande composée `--fail-at-end` | Les **35 modules** de la fermeture PROJECTION ont terminé avec succès. **900 invocations**, dont **128** dans `runtime-worker-materialize` et les **11 cas C3**, zéro échec, erreur ou skip. [Reçu de la gate composée](../../testing/evidence/L7-C3/command-result-pilot-gates.json). |
| Gate globale d’architecture non filtrée, requise et tentée | **BUILD FAILURE** après 1 h 23. La commande composée a exécuté **1 111 invocations**, zéro assertion en échec et **deux erreurs**. L’exécutable `scripts/bruno-v3/node_modules/.bin/bru`, installé localement et ignoré par Git, manquait dans la copie isolée. `AdmissionFlowPostgresTest.brunoB2PublicProtocol` et `InvitationHttpTest.brunoB4StandaloneCorpus` ont échoué au lancement du processus. Maven a ignoré `architecture-tests` à cause de l’échec du Monolith. [Journal et XML](../../testing/evidence/L7-C3/command-result-pilot-gates.tar.gz). |
| Assertions d’architecture exécutées séparément | **52 invocations**, zéro échec, erreur ou skip, dont `migrationBytesAndResourceOwnershipAreConserved`. Commande `./mvnw -pl architecture-tests -am -Dtest=<les 10 classes du module> -Dsurefire.failIfNoSpecifiedTests=false test`. La sélection et les 52 identités sont dans le [reçu complémentaire](../../testing/evidence/L7-C3/command-result-pilot-followup.json). |
| Rejeu des deux cas Bruno | **2 invocations**, zéro échec, erreur ou skip, après copie dans la fixture de l’installation locale Bruno CLI **4.3.0** sous Node **24.15.0**. Commande `./mvnw -pl runtime-monolith -am -Dtest=AdmissionFlowPostgresTest#brunoB2PublicProtocol,InvitationHttpTest#brunoB4StandaloneCorpus -Dsurefire.failIfNoSpecifiedTests=false test`. [Journaux et XML du rejeu](../../testing/evidence/L7-C3/command-result-pilot-followup.tar.gz). |

**Verdict.** Le pilote CommandResult est qualifié sur les propriétés demandées, avec tranche PROJECTION complète et assertion d’ownership exécutée. **La généralisation reste sans feu vert formel**. Le rejeu ciblé confirme la cause environnementale des deux erreurs et établit les preuves manquantes séparément. Il ne clôture pas l’exécution non filtrée de la gate globale. La condition restante est une gate composée non filtrée dans un environnement incluant dès le départ les fixtures suivies et le CLI Bruno provisionné.

La latence et le WAL caractérisent un store sériel. Les nouveaux verrous en présence de plusieurs familles, la charge comparable à C2 et l’activation par reconstruction C3-R2 demeurent des obligations des incréments suivants. Les arbitrages ratifiés ne changent pas. Réacteur complet sans sélection non lancé. La gate d’architecture a calculé et entraîné une fermeture de 43 modules comme attendu, sans traversée de tranche de production.
