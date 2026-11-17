# L1-E — Intégration interne et checkpoint final d'audit L1

Mandat humain : campagne autonome D puis E depuis `9dfe5a8d55d95bba29bb6a07e5c387c4ae8b0164`, GO après revues indépendantes A/B/C confirmé. Aucun lot suivant autorisé. **D et E : PASS technique interne borné ; A-02 close automatiquement pour L1 après publication.** Le présent dossier propose un statut technique ; il ne remplace pas l'audit indépendant final L1.

## Périmètre déclaré et réalisation

COMMAND primaire ; PROJECTION et EVENT secondaires. E ajoute exclusivement des tests d'intégration/composition, le manifeste, les scripts de preuve et la documentation. Production A/B/C inchangée ; production D dans domain-pot/engine-write-pot/infra-persistence-primary-jpa. Aucun POM, migration, HTTP, READ, Identity, Discovery, moteur générique ou runtime de production modifié. Aucun franchissement inattendu. Gates G-E, architecture globale et full reactor requis pour la clôture du jalon majeur L1. Base CURRENT V3 V1+V2 ; suites historiques transitives conservées sans campagne séparée ni certification BASELINE V2.

Quatre nouvelles méthodes PostgreSQL E assemblent les vrais composants A/B/C/D :

- e01 : fixture normative V1..V9 par demandes durables C, oracle indépendant de chacune des huit composantes (identités, valeurs, versions locales et activeFrom), balances 75/−75 puis −30/+30 puis −60/+60, zéros après DeleteExpense et absence à D. Reconstruction en ordre seed 731, anciennes valeurs inchangées après D. Correspondance révisions/changedComponents et positions d'événements denses.
- e02 : D01-M via C, Header seul supprimé, détails/poids conservés, poids ancien reconstructible, no-op et égalité obsolète, mutation après suppression refusée ; aucun POT_EVENT supplémentaire pour no-op/rejet.
- e03 : no-op Financials, reprise de la même demande terminale, modification effective, égalité devenue obsolète, no-op valide et suppression refusée sur référence ; exactement quatre POT_EVENT et huit complétions, seul ExpenseFinancials f2 publié à V4, anciennes balances intactes.

- e04 : création Shareholder/Expense depuis observedVersion ancienne après mutation sans lien ; lot mixte ne révisant que Weight A ; rejet atomique de la dernière cible obsolète pourtant égale ; DeleteExpense refusée après modification Financials et admise après modification Weight indépendante, anciennes balances exactes.

Le test de factory `V3PotCommandComposition.assemble` est étendu aux créations Shareholders/Expense, révision de défaut et calcul historique exact avec le vrai JpaTransactionManager. L'autorité physique JPA/JDBC et le refus d'un manager incohérent sont conservés. Toutes les preuves critiques C (24 PG, 6 unitaires, composition, architecture) et A/B sont réexécutées avec D dans les gates ; les courses financières et quatre cuts de rollback D s'y ajoutent. Les valeurs d'autorité Identity, l'admission et l'acquisition initiales restent des fixtures PostgreSQL explicitement nommées.

## Provenance A-02

Le collecteur part d'un commit H propre incluant tous sources/tests/ressources/SQL/POM/scripts/manifeste. Il compare automatiquement l'intégralité des entrées non exclues à l'arbre Git H avant et après chaque gate, y compris les modifications de mode des fichiers ; aucune entrée non suivie n'est acceptée. Targets supprimés avant chaque commande : compilation/XML frais, inventaire des classes/ressources et SQL générés. Les classpaths proviennent exclusivement de sorties Maven fraîches et de JARs identifiés.

Avant/après : empreintes JDK (modules/binaires/bibliothèques natives/configuration), distribution Maven, configurations externes, cache JAR/POM et image PostgreSQL. Toute entrée modifiée ou nouvellement résolue impose une nouvelle preuve fraîche. Les reçus d'artefacts couvrent XML, snapshots, logs et métadonnées. Sept tests adversariaux (dont plusieurs variantes) prouvent les refus : source sale, fichier non suivi, SQL supprimé, mode modifié, commit exécutable postérieur, exécutable caché sous evidence, dépendance modifiée/ajoutée et tête distante différente.

Le contrôle de publication exige les trois gates E retenus exécutés sur H et leurs reçus/entrées before/after, refuse tout skip nouveau et vérifie H→P par liste stricte d'exclusions documentaires/probatoires. Après push, il lit la tête distante, exige P et vérifie à nouveau les mêmes entrées. Aucun simple digest déclaré ne clôt A-02. Les contrôles locaux puis distants sont exécutés ci-dessous ; A-02 est close techniquement pour L1, avec répétition après le suivi documentaire final.

## Preuves et certification

Le manifeste est fixé avant gates. La matrice finale est générée depuis SPEC-10, les méthodes attendues et les XML frais par `scripts/testing/build-l1-matrix.py` : scénario normatif, lot, variantes, oracle, méthode/source, gate/SHA, preuve et limite. « Contribution interne exécutée » ne signifie jamais PASS global d'une exigence dont Bruno/READ/Worker ou d'autres familles manquent.

La couverture préparatoire des finances PRJ-02 et les calculs hors ordre ne certifient aucune balance READ durable/immuable. D03-L est une composition de primitives B à une V, sans nouvelle Command combinée. Les parcours Identity/Invitation, Consumption/Discovery, READ, sécurité HTTP/Bruno, k6 et performance restent reportés. Mesures D de temps/allocation/requêtes indicatives seulement ; coûts d'historiques longs et de verrous non qualifiés.

A-01 est close techniquement sur les gardes réellement réexécutées ; A-02 dispose des contrôles finaux automatiques, avec constat distant ci-dessous ; I-01 est complété pour tous les writers/lecteurs/calculs L1, avec suivi transversal futur ouvert. L1 n'est pas définitivement accepté.

## Résultats frais et SHAs

Toutes les entrées exécutables finales sont figées dans **`9899a2943760660893768fa4ba9452732ee17d50`**. Aucun code, test, ressource, SQL, POM, script ou configuration n'a changé après ces gates. Les seuls changements suivants appartiennent aux exclusions documentaires/probatoires vérifiées automatiquement.

| Livraison | SHA / rôle |
|---|---|
| Départ distant exact | `9dfe5a8d55d95bba29bb6a07e5c387c4ae8b0164` |
| L1-D production/tests | `69acce7842364d5ee3881d41b8a70257fd09b81d` |
| D rapport/preuves | `6b2ed036723d40af34caa217c4869cc103c9f6e9` |
| L1-E intégration/provenance initiale | `5a954a20eb4ebd2c6a36a677a675985a75b34e51` |
| L1-E renforcement et code final testé | `9899a2943760660893768fa4ba9452732ee17d50` |

| Gate retenu | SHA testé | Tests / failures / errors / skips | Obligatoires PASS | Collecteur global |
|---|---|---|---|---|
| d-arch | `69acce7842364d5ee3881d41b8a70257fd09b81d` | 1490 / 0 / 0 / 1 | 87 | INCONCLUSIVE |
| d-consumers | `69acce7842364d5ee3881d41b8a70257fd09b81d` | 1255 / 0 / 0 / 1 | 79 | INCONCLUSIVE |
| e-arch | `9899a2943760660893768fa4ba9452732ee17d50` | 1494 / 0 / 0 / 1 | 91 | INCONCLUSIVE |
| e-consumers | `9899a2943760660893768fa4ba9452732ee17d50` | 1289 / 0 / 0 / 1 | 83 | INCONCLUSIVE |
| e-full | `9899a2943760660893768fa4ba9452732ee17d50` | 1494 / 0 / 0 / 1 | 91 | INCONCLUSIVE |

Tous les gates retenus terminent **BUILD SUCCESS, code Maven 0**. Les méthodes obligatoires sont présentes exactement une fois, fraîches et sans failure/error/skip. E-ARCH et E-FULL couvrent 91 méthodes obligatoires uniques : 14 A, 19 B, 34 C (dont composition/architecture), cinq gardes A-01, 15 D, quatre E. Les répétitions entre gates ne sont pas additionnées comme tests uniques.

Le seul skip est `com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, propriété exploratoire V2 `step3b.measure` absente. Sa qualification demeure **SKIP**, et la couverture globale complète **INCONCLUSIVE**. Aucun nouveau skip obligatoire. Le wrapper du collecteur retourne 1 pour cette couverture INCONCLUSIVE (0 réservé à un PASS complet), tandis que Maven retourne 0 ; ces résultats restent distincts. La réussite obligatoire interne et la réussite Maven ne sont jamais présentées comme PASS intégral de SPEC-10/V3.

Commandes canoniques réellement exécutées depuis `app/` :

```bash
./mvnw -pl runtime-command-consumption-worker,runtime-task-consumption-worker -am test
./mvnw -pl runtime-command-consumption-worker,runtime-event-consumption-worker,runtime-task-consumption-worker -am test
./mvnw -pl architecture-tests -am test
./mvnw test
```

G-D utilise la première, G-E la deuxième, architecture D/E la troisième. **Full reactor exécuté une fois à E**, requis pour le jalon majeur L1, pas comme feedback ni réponse à un test local en échec. Architecture globale requise D (contrat financier) puis E (clôture structurelle). Aucun franchissement inattendu de slice ; aucune production EVENT/PROJECTION/WEB/BINDING/LKV modifiée. Leur présence dans une fermeture Maven ne constitue pas un changement de production.

Diagnostics/support supplémentaires : `./mvnw -pl domain-pot -am test` en D ; sélection PG `-Dtest=PotFinancePostgresTest` ; sélection E `-Dtest=L1IntegrationPostgresTest,V3PotCommandCompositionPostgresTest` sur l'ancre runtime-command-consumption-worker puis `-Dtest=L1IntegrationPostgresTest` sur infra-persistence-primary-jpa ; diagnostic connexion `-Dtest=CanonicalProjectionTaskRuntimePostgresTest` sur runtime-task-consumption-worker. Toutes les sélections utilisent `-am test -Dsurefire.failIfNoSpecifiedTests=false` et ne remplacent aucun gate. Les invocations depuis la racine utilisent le wrapper équivalent `./app/mvnw -f app/pom.xml`. Sept tests `test_l1_provenance.py` et sept tests `test_summarize_v3.py` PASS, via `python3 -B -m unittest discover -s scripts/testing -p <nom>` ; `git diff --check` exécuté.

## Exécutions remplacées et incident conservé

Le premier G-E sur `5a954a20...` était BUILD SUCCESS (1288/0/0/1, 82 obligatoires PASS), puis a été remplacé après ajout d'e04 et du contrôle de chaque commit H..P. Aucun full reactor n'avait alors été lancé.

Le premier G-E sur H final est **FAIL** (1065/0/14/0) : Flyway n'a pas pu établir la connexion PostgreSQL du test historique `CanonicalProjectionTaskRuntimePostgresTest`, SSL/SQLSTATE `08P01`, puis 14 erreurs de contexte ; la composition située après l'arrêt du réacteur n'a pas été exécutée. Diagnostic isolé : 14/0/0/0. Relance entière de G-E sur le même H : succès retenu ci-dessus, sans changement de source, seuil ou configuration. Incident de connexion non reproduit ; aucune cause de production L1 démontrée. Cette exécution reste FAIL et n'est pas effacée. Les diagnostics D de banc et la collecte corrigée restent également conservés.

## Environnement, preuves et inventaire

Environnement des gates : PostgreSQL 17.11, `postgres:17-alpine`, digest `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`, Maven 3.9.14, Temurin 21+35, Docker 29.8.2, macOS aarch64 ; versions/options exactes dans chaque environment.json. Base métier V3 neuve V1+V2 explicitement sélectionnée. Les suites transitives PRIMARY V1–V31 et READ V1–V14 sont réellement exécutées dans la fermeture non filtrée, avec logs ; aucun gate HISTORICAL séparé ni baseline V2 TRUSTED présumée.

Le contrôle complémentaire [compilation et SQL](../testing/evidence/L1-DE/Compiler-and-SQL-provenance.json) vérifie 103 inventaires Maven, 994 entrées de compilation et 47 ressources SQL de production contre Git H ; aucune source/ressource ignorée dans app/*/src.

Les trois gates E vérifient 1491 entrées Git avant/après, digest `3b109d557f6d772a8d146713cb2647dc1ff8a9fac36e8d58b47ea10ce2145c17`. Inventaire externe stable de 1406 entrées ; JARs effectivement utilisés et classpaths détaillés par gate. Les classes/ressources/SQL générés sont issus des targets supprimés avant chaque commande et inventoriés. Les reçus couvrent toutes les pièces brutes. Les exclusions n'autorisent aucune source, migration, test, POM ou script exécutable, même sous evidence ; tous les commits après H sont inspectés, y compris les changements puis rétablissements.

Production effectivement modifiée : **six fichiers dans trois modules**, `domain-pot` (composition financière, calculateur, éligibilité pure), `engine-write-pot` (port financier historique, service de calcul), `infra-persistence-primary-jpa` (lecteur groupé). Tests nouveaux/modifiés dans domain-pot, infra-persistence-primary-jpa et runtime-command-consumption-worker. Aucune production composition/runtime ni règle SPEC modifiée. Le [rapport D](L1_D_Execution_Report.md) décrit les algorithmes et consommateurs historiques V2 laissés au lot READ ; [inventaire de fichiers](../testing/evidence/L1-DE/Changed-files-final.json) et [worktrees préservés](../testing/evidence/L1-DE/Worktree-preservation-verdict.json).

Preuves accessibles hors target :

- [Index de tous les gates retenus et remplacés](../testing/evidence/L1-DE/Gate-index.json).
- [Archives brutes D](https://github.com/kartaguez/pocoma/blob/3c6315cfd52dfee9d764a3cb00c16248793cdd8a/docs/testing/evidence/L1-DE/D-gates-raw.tar.gz), [E finales](https://github.com/kartaguez/pocoma/blob/3c6315cfd52dfee9d764a3cb00c16248793cdd8a/docs/testing/evidence/L1-DE/E-gates-raw.tar.gz), [E remplacées/FAIL](https://github.com/kartaguez/pocoma/blob/3c6315cfd52dfee9d764a3cb00c16248793cdd8a/docs/testing/evidence/L1-DE/E-superseded-raw.tar.gz) et SHA-256 adjacents.
- [Diagnostics D](https://github.com/kartaguez/pocoma/blob/3c6315cfd52dfee9d764a3cb00c16248793cdd8a/docs/testing/evidence/L1-DE/D-diagnostics.tar.gz), [diagnostics E](https://github.com/kartaguez/pocoma/blob/3c6315cfd52dfee9d764a3cb00c16248793cdd8a/docs/testing/evidence/L1-DE/E-diagnostics.tar.gz).
- [Matrice SPEC-10](../testing/evidence/L1-DE/SPEC10-matrix.json), [populations architecture](../testing/evidence/L1-DE/Architecture-populations.json), [reproduction et extraction](../testing/evidence/L1-DE/README.md).

Les snapshots PG bruts, catalogues B, intervalles, tables métier/résultats/événements/slots, chronologies, PID/XID et fautes réseau C sont dans `app/*/target/v3-pot-evidence/` des archives. D ajoute les valeurs attendues/observées à chaque V, neuf requêtes pour une comme pour 21 Expenses sans Details/N+1, coûts indicatifs et rollback financier. E ajoute les oracles huit composantes, les parcours financiers avec la factory Spring/JPA et les vérifications des événements durables. Les données sensibles sont exclues des exports ; aucune fixture d'autorité n'est assimilée au cycle Identity réel.

## Couverture applicable, réserves et verdict

La matrice contient 336 identifiants de scénarios : **107 avec contribution interne exécutée** et **229 reportés hors L1**, plus PRJ-04 retiré/non applicable. Il ne s'agit pas de 107 scénarios globalement certifiés. Chaque ligne relie variantes/oracle normatifs, lot, méthode/source, exécution/gate/SHA, preuve et limite. Aucun scénario bloqué ou inconclusif n'est caché dans un PASS interne final ; les exécutions FAIL/remplacées restent à l'index.

Contributions DOM-01 D01-A..M, DOM-02 D02-A..S (dont les huit variantes PostgreSQL D02-S), DOM-03 D03-A..O dans leur portée calcul/histoire interne : exactitude de chaque clé/fraction, zéros, overrides/défauts à V, modifications Financials/Weight, références, droits, suppressions, reconstruction indépendante, no-op/rejets, rollback/reprises et événements atomiques. D03-L est prouvé par primitives B composées à une V ; aucun nouveau contrat Command combiné. DOM-03 D03-N et PRJ-02 restent partiels pour l'immutabilité **en stockage READ**, qui n'existe pas dans L1. Contributions EVT/CON limitées à Command Pot, POT/COMMAND_COMPLETION et aux autorités physiques effectivement exercées.

Restent explicitement reportés : autres familles Identity/Invitation, fournisseurs réels d'autorité, consentement, admission/idempotence HTTP, acquisition/Discovery/Engine V3 complet, matérialisations READ immuables/hors ordre et leurs slots, droits API historiques, Bruno, k6, reconstruction READ exclusive et budgets V3-7. Les populations et coûts D sont indicatifs ; l'allocation cumulée Grand Pot observée (≈826 Mo par calcul dans G-ARCH D) n'est ni mémoire résidente ni résultat de SLO. Coût des historiques longs, verrous et limites physiques à qualifier. Aucun algorithme d'arrondi, compensation, cache durable ou seuil métier ajouté.

**A-01 : close techniquement**, cinq gardes et trois gardes C réexécutées sans nouvelle exception ; audit final indépendant requis. **I-01 : inventaire complet dans le périmètre L1**, suivi transversal ouvert pour les futurs writers/Identity/Consumption/READ et retrait V2. **A-02 : CLOSE TECHNIQUEMENT POUR L1.** Les trois gates finaux, leurs reçus/entrées et tous les commits après H sont vérifiés automatiquement ; tête distante publiée et identité des 1491 entrées concordent. [Contrôle de publication](../testing/evidence/L1-DE/Publication.json), [préflight](../testing/evidence/L1-DE/Publication-preflight.json) et [état/divergence](../testing/evidence/L1-DE/Publication-state.json).

**Verdict technique D : PASS interne borné. Verdict technique E : PASS interne borné, preuves obligatoires/gates et A-02 démontrés. Proposition globale L1 : noyau interne vérifié, à soumettre à audit indépendant final, sans acceptation définitive.**

## Publication et checkpoint

La livraison code + rapports + archives **`05aa9f37c158fd7ee58f026072110fa640eafd0a`** a été poussée sans force sur `feat/v3-architecture`, après vérification de la tête distante initiale exacte. La relecture distante retourne exactement ce SHA ; divergence **0 / 0**. Le contrôle automatique H→publication est PASS, digest des 1491 entrées inchangé ; les trois gates E sont reconnus sur H et leurs reçus sont intègres. [Sortie du push](../testing/evidence/L1-DE/Push.log), [préflight du worktree](../testing/evidence/L1-DE/Worktree-inputs-preflight.json). Aucun source ou index exécutable sale ; seuls répertoires de preuves brutes/extractions documentaires non suivis dans le worktree de livraison. Les worktrees préexistants et leurs index sont préservés, sans reset/stash/déplacement.

Le présent constat est conservé par un suivi documentaire, sans aucune entrée exécutable modifiée. Le dernier hash d'un commit ne peut pas figurer dans son propre contenu ; le contrôle H→tête distante est répété après ce dernier commit, avec état du worktree et divergence finale, et son SHA/verdict est communiqué dans la livraison. Reproduction : `python3 -B scripts/testing/verify-v3-l1.py --tested 9899a2943760660893768fa4ba9452732ee17d50 --published <HEAD-publié> --remote --proof-index docs/testing/evidence/L1-DE/Gate-index.json` après extraction des archives. Il vérifie les trois gates et chaque commit intermédiaire, puis l'identité distante exacte.

**STOP après publication D/E. Aucun lot suivant, aucune acceptation définitive de L1 ni activation. Prochaine étape exclusive : audit indépendant final L1 du code, de l'architecture, des finances, de la persistance, des transactions, des tests, des preuves et de la provenance.**
