# Corrections transverses L1/L2 avant L3 — 30 octobre 2026

**Verdict : CLOSED WITH EXPLICIT RESERVATIONS.** Les deux findings confirmés sont
**FIXED** ; Discovery : **NO CHANGE JUSTIFIED**. Gate composée réussie sur son
périmètre autorisé, avec deux campagnes temporelles NOT RUN et un SKIP historique.
Mandat humain unique et borné ; aucune réalisation L3 ni nouvelle certification I5.

## Provenance et vérification déclarée avant modification

Branche `feat/v3-architecture`, dépôt `kartaguez/pocoma`. HEAD initial et distant
vérifié par `git ls-remote` : `9780ac88bcea8d87081bce9c7589c8f323806934`,
identique au SHA audité. Aucun delta distant à reprendre. Worktree propre réutilisé :
`/private/tmp/pocoma-v3-postaud`. Checkout principal à `90a3e9bc`, branche
`feat/runtime-monolith`, avec modifications étrangères préservées. Aucun reset,
clean, stash, déplacement de worktree ni force push.

Sources : AGENTS, politique Reactor avec clarification proportionnée, canon,
plan directeur, SPEC-01/05/07/09/10, contrats B/C, dossiers L1/F/F2, rapports et
contrats L2/I1–I5 et clôture documentaire L2. Rapport Codex retrouvé et lu :
`/private/tmp/Pocoma_V3_Audit_L1L2_9780ac88.md` (30 octobre, 9780ac88).
Contre-audit Gemini transmis dans la conversation
`6ae22226-1b40-83ed-a5ce-2350c5933719` : confirmation des deux findings et
observation de timeout consultées ; la restitution du connecteur est tronquée
à 20 000 caractères, donc aucune lecture exhaustive de ce rapport n'est revendiquée.
Les audits conceptuels et les contre-audits L2 antérieurs ne sont pas réattribués.

- Impact productif attendu/observé : deux fichiers JDBC du lecteur historique et
  du helper partagé avec les préconditions WRITE. Aucun contrat/port/POM modifié.
- Slice primaire : **COMMAND**, secondaires canoniques : **NONE**.
- Preuves locales : lecteur historique, finances, préconditions WRITE, intégrité,
  snapshot et rollback sur PostgreSQL réel ; témoins ArchUnit confinés aux tests.
- Architecture globale : **REQUIRED** (dépendances interdites).
- Full Reactor : **NOT REQUIRED / NOT RUN** (delta borné, aucune restructuration,
  intégration main ou nouvelle baseline globale).
- DB : CURRENT V3 fraîche, bootstrap existant ; aucune migration nouvelle.
  Les tests historiques transitifs des gates canoniques restent exécutés ; baseline
  V2 TRUSTED toujours NONE, aucune campagne historique supplémentaire.
- Impact interdit : L3, domaine, Engine/Worker/Renewal, retry/quarantaine,
  claims/leases, UNKNOWN/drainage, protocole Discovery, pools et budgets physiques.
- Escalade : nouveau comportement d'un autre consommateur/contrat/autorité impose
  déclaration préalable. Franchissement inattendu constaté : **NONE**.

Code applicatif final : `6f282b1dfbde30044fefb89b772661c9bd9f5466`.
SHA final testé, incluant le fichier des exclusions humaines :
`3dd15a666e782df653507fcbf19c07178dfc9069`.
`fe7b101c` porte les corrections ; `f42efc09` corrige uniquement la normalisation
locale de l'assertion négative JDBC. `6f282b1d` adapte uniquement la capture des
tableaux libérés pour le replay EXPLAIN du harnais F2, sans changer ses oracles.
Le SHA documentaire publié sera celui du
commit qui contient ce rapport ; il n'est pas inventé par auto-référence.

## Findings → changements → preuves

| Finding | Changement / décision | Preuves | Résultat |
|---|---|---|---|
| AUD-L1L2-001 | Quatre prédicats d'identités en `= any(:ids)` avec `SqlArrayValue("uuid", …)` ; participants en tableau `bigint` | Trois nouveaux tests PG, oracles V1–V9 existants, finances, writers ; frontière 65 536 et ancien IN négatif | **FIXED** |
| AUD-L1L2-002 | Interdiction `domain.useridentity..`, `domain.identity..`, tous `engine.write..` pour Engine et poll worker | Huit tests ArchUnit ciblés, dont nouveaux témoins Identity réel/moteur Pot évalués séparément contre les deux règles ; gate globale | **FIXED** |
| OBS-L1L2-001 | Aucun changement de code : borne physique déjà héritée des pools ; seuil local plus court non justifié | Analyse des ressources, owners et appels réels, détaillée ci-dessous | **NO CHANGE JUSTIFIED** |

### Cardinalité : inventaire et invariants

`PotHistoryRows.identities` : sélection explicite d'Expenses dans le lecteur,
identités ciblées et références financières dans `JdbcPotRevisionAdapter` ; pas de
limite métier. `component` : reconstruction complète/financière, autorisation,
usage exhaustif et composants sélectionnés. `openComponent` : préconditions des
writers Pot/enfants et références financières groupées. `checkedComponent` : audit
historique explicite `verifyHistory`, uniquement. Tous ces ensembles peuvent
croître ; les identités supprimées sont conservées.

`JdbcPotHistoryAdapter.financials` : toutes les révisions Financials applicables
et leurs participants, y compris ceux d'Expenses supprimées. L'array garde la
sélection exacte et le contrôle d'appartenance `Mixed participant owner` ; une
jointure ne réduit pas ici le travail sans réexprimer la sélection déjà validée.

La suppression pure du filtre d'identités (cas A) a été écartée : `pot_id` et
l'intervalle seuls peuvent inclure des révisions orphelines ou des identités dont
`created_at > V`, absentes de la map `births`. Cela change l'exclusion/diagnostic
actuel et peut notamment provoquer une déréférence null au lieu du contrôle
existant. Les tableaux préservent **exactement** l'ensemble filtré, pour sélection
complète comme partielle. `created_at`, `active_from`, bornes, unicité/existence,
chaînes locales, références et diagnostics Java restent inchangés. Aucune
pagination, limite métier, suppression d'histoire ou infrastructure de batching.
Même nombre d'allers-retours ; snapshot et frontières transactionnelles conservés.
`SqlArrayValue` Spring fournit création typée et libération JDBC sans wrapper ajouté.

Seul autre IN dynamique dans le sous-arbre V3 : `JdbcConsumptionSlotStore.lockKeys`,
lots explicites de candidats/claims (acquisition, renouvellement et inspection),
bornés par les paramètres/capacités Consumption et sans énumération d'histoire Pot.
Ces lots configurables ne sont pas une collection historique non bornée ; ils ne
sont pas modifiés et aucune capacité arbitraire de configuration n'est certifiée.

La nouvelle preuve de frontière utilise 65 536 identités/révisions réelles dans
des tables temporaires SQL de mécanique, générées par `generate_series`, avec
index local ; elle exerce les quatre méthodes du helper, lecture historique et
courante, puis un tableau BIGINT de même taille. Elle ne certifie pas un agrégat
métier complet de 65 536 Expenses ni les triggers d'une telle population.
Le témoin de l'ancienne expansion est refusé par le vrai driver à 65 536 paramètres.
Les tests métier séparés utilisent le vrai schéma/contraintes et des oracles
littéraux indépendants, couvrant actifs/supprimés, participants et rollback.
Les requêtes émises ont au plus cinq paramètres ; leur arité ne croît plus avec
les collections. Coûts mémoire/temps, taille maximale des valeurs PostgreSQL et
volume des résultats restent finis et non certifiés.

### Discovery : huit vérifications préalables et décision

Composition examinée : `V3ConsumptionWorkerConfiguration.Resources`, factories
`V3ConsumptionDiscoveryComposition`, `DiscoveryEngine`, les trois adaptateurs
signalés et `SpringAttemptTransactions`. Aucun timeout modifié.

| Chemin réel | Pool / options PostgreSQL physiques | Owner Spring |
|---|---|---|
| Références Command, checkpoint/admission WRITE | write-control : lock 500 ms, statement 2 000 ms, transaction 3 000 ms, socket 5 s | writeTechnical, 3 s |
| Checkpoint/admission READ | read-control : mêmes options | readTechnical, 3 s |
| Fenêtre d'événements source WRITE pour les probes READ | write-business : statement 60 000 ms, transaction 65 000 ms, socket 70 s | writeEffects JPA, 60 s |

1. Les options sont appliquées lors de la création de chaque connexion physique
   Hikari, avant toute unité ; emprunt 1 s, connexion 2 s. La validation des URL
   n’autorise que `currentSchema`, sans override `options` dans l’URL.
2. L'owner configure la transaction avant le callback. JDBC démarre l'unité
   serveur au premier échange transactionnel (BEGIN du driver avec autocommit
   désactivé) ; `read()` exécute `SET TRANSACTION READ ONLY` **avant** l'adaptateur.
   Un éventuel travail JPA préalable peut démarrer plus tôt, jamais après ces SQL.
3. `set_config(..., true)` ne vaut que pour la transaction courante ; les deux
   réglages locaux ne remettent pas `transaction_timeout` à zéro.
4. Acquisition/initialisation et code avant l'adaptateur ne sont pas une durée
   nulle garantie ; après BEGIN, la borne de pool existe déjà, y compris avant
   le premier réglage local. Aucun nouveau seuil local ne doit ignorer cet âge.
5. Fenêtre événement : compteur puis enveloppes dans le même snapshot, après les
   réglages. Checkpoint/upperPending/page : unités read distinctes. Admission :
   plusieurs contrôles, insertions et fencing dans une unité write.
6. En write, `protect()` appelle d'abord `ownership.bound`, qui configure aussi
   une borne transactionnelle de coordination ; les méthodes d'admission gardées
   l'héritent. Les lectures préparatoires gardent la borne physique du pool.
7. Ajouter mécaniquement statement+1 s à la lecture événement peut abaisser une
   unité physique 65 s multi-instructions à une borne sans justification. Aucun
   bénéfice de sûreté démontré, avec risque de terminaison prématurée.
8. WRITE et READ explicités ci-dessus ; la source d'événements est bien WRITE,
   même lorsque l'effet final est READ. Aucun changement du protocole.

Décision limitée à cette composition : **NO CHANGE JUSTIFIED**. Une factory
injectée avec une DataSource arbitraire n'hérite pas magiquement de ces options ;
ce rapport ne certifie ni tous les déploiements possibles ni la disponibilité I5.
Portée transactionnelle de `set_config` et timeout couvrant l'unité serveur :
[PostgreSQL 17, configuration locale](https://www.postgresql.org/docs/17/functions-admin.html#FUNCTIONS-ADMIN-SET)
et [timeouts](https://www.postgresql.org/docs/17/runtime-config-client.html#GUC-TRANSACTION-TIMEOUT).

## Exécutions et coûts

Deux ancres COMMAND et architecture-tests composées selon la clarification
proportionnée de la politique, pour partager leur fermeture commune ; aucune
preuve d'isolation de runtimes n'en est déduite. La première tentative composée,
sans filtre ni exclusion ajoutée,
a été interrompue avant Worker (8 min 38 s, code 143), avec 969 tests XML achevés
sans échec/erreur/skip. Elle reste NOT COMPLETED, jamais PASS.

La première gate COMMAND sur `f42efc09` est **FAIL** : 1 311 tests, trois failures,
zéro error/skip avant l'arrêt Maven au module PRIMARY, 14 min 36 s. Les trois
échecs sont le même replay EXPLAIN dans f201 et ses deux entrées L2ManifestProof :
le harnais conservait un `java.sql.Array` après sa libération normale. Aucune
lecture métier défaillante, aucune assertion d'intégrité supprimée. Le compteur
capture maintenant type SQL et valeurs avant nettoyage et recrée le paramètre
pour EXPLAIN. Les trois méthodes passent ensuite en diagnostic (26,893 s), puis
sont réexécutées par la gate finale. Adaptation de test nécessaire dans le même
module/slice, aucun franchissement productif. Les timings F2 transitifs ne sont
pas une nouvelle comparaison avant/après ni une certification de performance.

Les autres diagnostics : 36 PG PASS en 27,781 s ; 8 ArchUnit PASS en 9,781 s.
Premier lancement PostgreSQL : ERROR d'environnement Docker sous sandbox,
aucune méthode métier exécutée ; relance avec accès Docker. Deux diagnostics de
l'assertion du témoin négatif sont FAIL (séparateur de milliers localisé) :
normalisation corrigée, oracle d'arité conservé. Aucun échec requalifié.
Les diagnostics sur arbre de travail sont identifiés comme tels dans le reçu,
jamais attribués artificiellement à un SHA propre.

### Contradiction de vérification découverte

Le mandat interdit explicitement de relancer les campagnes temporelles I5 et
prescrit les ancres non filtrées COMMAND/Architecture. Or
`V3WorkerResourcesPostgresTest.java:895,1947` appelle directement
`I5TemporalV2Postgres.campaign(...)` depuis `i5_r12` et `i5_r05`. Aucun profil ni
condition ne les désactive dans la fermeture Surefire actuelle. La seconde gate
a été arrêtée au module PRIMARY avant d'atteindre Worker ; aucune de ces campagnes
n'est entrée en exécution. Aucune exclusion opportuniste ni modification des tests
I5 n'est introduite. Arbitrage humain demandé : exclusion nominative conservée
NOT RUN avec réserve de gate, ou autorisation de ces seuls appels transitifs sans
nouvelle certification I5. **Arbitrage reçu : exclusion nominative des deux méthodes,
conservées NOT RUN, avec réserve de gate.** Le fichier Surefire est figé dans
`I5-temporal-exclusions.txt` avant le nouveau passage. Aucun `-Dtest` ne remplace
les includes/excludes existants ; aucune autre exclusion ajoutée. Ce résultat
n'est pas présenté comme une gate intégralement non filtrée.

Les huit méthodes architecturales sont ensuite vérifiées sur le code final :
PASS, zéro failure/error/skip, 10,087 s ; ce ciblage ne remplace pas la gate globale.
Le résolveur Surefire 3.5.5 confirme indépendamment le fichier nominatif :
exactement r05/r12 exclus, les dix autres méthodes et un témoin extérieur conservés.
La gate autorisée reprend les deux ancres et les includes existants avec ce seul
`-Dsurefire.excludesFile` ; **BUILD SUCCESS**, les deux méthodes restent NOT RUN.

### Résultat final et commandes exactes

Exécution finale sur `3dd15a666e782df653507fcbf19c07178dfc9069`, **42 min 50 s** :
**1 917 tests**, 0 failure, 0 error, **1 SKIP**, 306 suites XML fraîches.
Deux méthodes exclues restent **NOT RUN**, hors de ce total. Module PRIMARY :
559 tests PASS ; architecture-tests : **159 PASS**, dont les huit tests de la
garde Consumption ; runtime COMMAND : 294 tests dont 1 SKIP. Les autres tests
proviennent de la fermeture Maven commune. Frontière SQL 65 536 : **1 055 ms**
mesurées dans le scénario (fixture et vérifications incluses, hors bootstrap).
Aucun coût mémoire ni débit/SLO n'est certifié.

| Vérification | Résultat conservé |
|---|---|
| PostgreSQL métier ciblé (historique, finances, révisions) | PASS, 36 tests, 27,781 s, diagnostic sur arbre de travail |
| Nouveau scénario PG final | PASS, 3 tests, inclus dans la gate finale |
| Garde Consumption ciblée finale | PASS, 8 tests, 10,087 s, SHA `6f282b1d` |
| COMMAND canonique initiale | FAIL, 3 failures du replay EXPLAIN ; correctif de harnais vérifié ensuite |
| Gate composée sans exclusion | NOT COMPLETED, interruption mandat avant Worker |
| COMMAND + Architecture, arbitrage humain appliqué | PASS sur périmètre autorisé ; réserve de couverture globale |
| `i5_r05`, `i5_r12` | NOT RUN, exclusion humaine nominative |
| `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness` | SKIP historique inchangé |
| Autres gates canoniques / Full Reactor / nouvelle campagne I5 | NOT REQUIRED / NOT RUN |

Depuis `app/`, commandes effectivement exécutées (répétitions/diagnostics et SHA
précisés dans les reçus ; aucun succès d'un ciblage ne remplace une gate) :

```sh
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=PotHistoryCardinalityPostgresTest,PotHistoryPostgresTest,WriteRevisionPostgresTest,PotFinancePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl architecture-tests -am test -Dtest=V3ConsumptionEngineArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=PotHistoryCardinalityPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=PotHistoryCardinalityPostgresTest#uuidAndBigintArraysCrossProtocolBoundaryWithFixedArity -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker -am test
./mvnw -pl infra-persistence-primary-jpa -am test -Dtest=WriteRevisionPerformancePostgresTest,L2ManifestProof#l2m_l1_f2_f201+l2m_l1_f2_summary_f202 -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl runtime-command-consumption-worker,architecture-tests -am test
./mvnw -pl runtime-command-consumption-worker,architecture-tests -am test -Dsurefire.excludesFile=/private/tmp/pocoma-v3-postaud/docs/testing/evidence/L1L2-corrections/I5-temporal-exclusions.txt
```

Un warning de sortie de fork Surefire après `System.exit(0)` apparaît dans PRIMARY.
Le dump frais montre le shutdown Spring/Hibernate SchemaDropper attendant Hikari ;
il est conservé dans l'archive finale. Aucun test en échec, aucun timeout ou fixture
modifié pour le masquer ; Maven termine avec code 0. Ce warning de nettoyage n'est
pas un certificat de disponibilité.

### Fichiers et conservation des preuves

| Fichier applicatif, sous `app/` | Justification |
|---|---|
| `infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotHistoryRows.java` | Quatre tableaux UUID typés, filtres et contrôles conservés |
| `infra-persistence-primary-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/JdbcPotHistoryAdapter.java` | Tableau BIGINT des révisions financières ; contrôle des propriétaires conservé |
| `infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/PotHistoryCardinalityPostgresTest.java` | Trois preuves PG : oracles, frontière, intégrité/rollback |
| `infra-persistence-primary-jpa/src/test/java/com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/WriteRevisionPerformancePostgresTest.java` | Capture des valeurs/type avant libération du tableau pour EXPLAIN |
| `architecture-tests/src/test/java/com/kartaguez/pocoma/architecture/v3/V3ConsumptionEngineArchitectureTest.java` | Règles corrigées et témoins négatifs confinés aux tests |

Les seules autres modifications sont ce rapport, deux liens/statuts correctifs
ajoutés au canon et au plan directeur, et les preuves bornées. La liste **exacte**
de tous les fichiers livrés est dans [Changed-files.json](../testing/evidence/L1L2-corrections/Changed-files.json).
Aucun POM, schéma, migration, protocole, domaine, Engine, Worker ou API L3 modifié.

Preuves compactes : [entrées et environnement](../testing/evidence/L1L2-corrections/Inputs.json),
[reçu final et hashes des 306 XML](../testing/evidence/L1L2-corrections/approved.json),
[diagnostics](../testing/evidence/L1L2-corrections/diagnostics.json),
[FAIL initial](../testing/evidence/L1L2-corrections/command-failed.json),
[interruption](../testing/evidence/L1L2-corrections/interrupted.json),
[ArchUnit ciblé final](../testing/evidence/L1L2-corrections/architecture-targeted-final.json),
[provenance de livraison](../testing/evidence/L1L2-corrections/Delivery-checks.json).
Archives XZ associées : logs et XML frais uniquement, avec hashes vérifiés et
restauration lisible contrôlée ; pas d'archive de données volumétriques ni de
restauration des 105 archives historiques retirées. Aucun rapport historique réécrit.

## Réserves et verdict

**CLOSED WITH EXPLICIT RESERVATIONS** : AUD-L1L2-001 et AUD-L1L2-002 **FIXED**,
OBS-L1L2-001 **NO CHANGE JUSTIFIED**. Les gates finales autorisées réussissent ;
la couverture non filtrée conserve sa réserve explicite (deux NOT RUN et un SKIP).
Aucun défaut critique découvert dans ce périmètre. Aucune modification L3.
**L1 ACCEPTED**, **L2 ACCEPTED WITH EXPLICIT RESERVATIONS**, **I5 CLOSED BY HUMAN
ACCEPTANCE WITH AVAILABILITY RESERVE** inchangés. AUD-I5-01 et R-AUD-I3-03 non
certifiés ; G1/G2 INCONCLUSIVE ; G3 NOT PASSED / NOT COMPLETED ; configuration
30 secondes non certifiée ; aucune homologation production. La gate Architecture
de cette correction ne franchit pas rétroactivement G3. Performances et SLO
intégrés restent à L6/L7/L8, autorités Identity à L3, matérialisations à L4.
