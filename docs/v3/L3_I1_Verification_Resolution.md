# L3 / I1 — Examen de la non-clôture de vérification

**Décision ultérieure du responsable, 30-10-2026 : I1 ACCEPTED WITH EXPLICIT
RESERVATIONS — GO I2**, [arbitrage](L3_I1_Arbitration_and_I2_GO.md). Il supplante
l'arrêt procédural historique ci-dessous ; la gate FAIL, l'absence de verdict
I1 LOCAL BOUNDED PASS et toutes les limites de preuve restent inchangées.

**30 octobre 2026 — verdict conservé : I1 NOT CLOSED.**

Le CODE REVIEW PASS transmis par le responsable et les 35 tests I1 PASS restent
acquis dans leur périmètre. Aucun défaut concret I1 justifiant une modification
n'a été identifié. La gate obligatoire COMMAND + Architecture reste **FAIL** ;
`architecture-tests` y est **SKIPPED**. Le diagnostic architecture ciblé reste
une contribution positive, sans valeur de substitution. **I1 LOCAL BOUNDED PASS
n'est pas proposé. Aucun I2 commencé.**

Ce rapport clôt l'investigation demandée, pas la vérification technique I1.
Il complète le [rapport d'exécution](L3_I1_Execution_Report.md), sans réécrire ses
preuves, les verdicts L2 ni leur acceptation humaine.

## Mandat, provenance et périmètre

- Branche : `feat/v3-architecture`, Start HEAD / SHA examiné :
  `02c7bfbf85936f1f1a633e9097bc2b49f878708e`.
- Baseline immédiatement avant I1 :
  `140d13c3101a4e297bdea5141d167d189b7dcd02` ; implémentation :
  `ddffcb44c92d02d335f41b55122aa6c7d885d0e2`.
- Worktree propre au départ : `/private/tmp/pocoma-v3-postaud`.
  Le checkout principal demeure sur `feat/runtime-monolith`, `90a3e9bc`, avec ses
  travaux préexistants ; aucun checkout, reset, stash, clean ou suppression.
- L'audit indépendant CODE REVIEW PASS est une entrée du mandat humain. Aucun
  rapport indépendant complet supplémentaire n'est attribué à cette investigation.
- La politique Reactor du checkout principal a d'abord été lue ; celle de la
  branche examinée, différente, a ensuite été lue intégralement. C'est cette
  [version normative](../testing/Reactor_Verification_Policy.md), dont la seconde
  section 34 « Proportionate certification and evidence reuse », qui gouverne
  la réutilisation. Le [plan Identity](L3_Identity_Inventory_and_Implementation_Plan.md)
  et son scope I1 sont conservés.

Déclaration préalable retenue : **COMMAND primaire, slices secondaires NONE**
pour I1 ; **architecture globale REQUIRED** pour les deux arêtes Maven ajoutées.
Commande de clôture composée autorisée par la politique :

```sh
# Depuis app/ — commande déjà exécutée par I1, NON relancée ici.
./mvnw -pl runtime-command-consumption-worker,architecture-tests -am test
```

Cette mission ajoute seulement documentation, encodage sans perte et analyse de
preuves : slices Maven nouvellement exécutées **NONE** (politique §34).
Impact production attendu/observé **NONE** ; aucun changement de tests, assertions,
seuils, mécanismes L2, dépendances, configuration ou migration. Impacts interdits :
correction I1 sans défaut démontré, réouverture L2 et implémentation I2.
Franchissement inattendu **NONE**. Full reactor **NOT REQUIRED / NOT RUN** :
pas de nouvelle restructuration, clôture de Wave ou intégration main (§§19–20).
DB nouvelle **NONE** ; baseline V2 TRUSTED toujours NONE, aucun bootstrap rejoué.

## Preuves contrôlées et conservation

Le [reçu d'analyse](../testing/evidence/L3-I1-resolution/Analysis.json) et le
[script reproductible](../testing/evidence/L3-I1-resolution/analyze.py) vérifient :

- SHA-256 compressé et décompressé des quatre journaux I1, décomptes des suites
  du journal contre le reçu original, empreintes des 13 entrées I1 ;
- SHA-256 et 1 954 observations de `testcases.json.gz`, dont 35 tests I1 PASS
  dans la gate ; XML Worker survivant contre les observations scellées
  (messages exacts et durées), et messages contre le journal original ;
- SHA-256 des deux archives L2 temporelles v2/finale et lecture directe de leurs
  XML, sans extraire ni modifier les archives ;
- comparaison Git baseline→I1, comparaison des trois sources de banc avec le gel
  L2 final, configuration Business et classpaths enregistrés dans les XML.

Trois fichiers auxiliaires survivants de `target/` sont désormais conservés en
gzip sans perte : XML Worker et traces **finales** W1/v2 et W2/v3. L'[index de
capture](../testing/evidence/L3-I1-resolution/auxiliary-index.json) donne leur
origine, mtime et les deux empreintes. Ils n'étaient pas scellés par le reçu I1
initial : cette capture tardive est déclarée. L'attribution est corroborée par
les cas XML, les horodatages, le schéma W2 présent dans son `system-out` et ses
traces, les tokens et les messages du journal I1. Elle n'est pas une nouvelle
exécution ni un scellement rétroactif de l'environnement.

Les fichiers W1 `stage1/stage2` et le fichier v2 W2 encore présents dans `target/`
ne sont **pas** utilisés : leurs tailles correspondent aux anciens artefacts L2,
et W1 courant échoue avant d'écrire une trace de fin de stage1. La trace W1 finale
du `finally`, qui contient les deux vecteurs du stage1, est la seule retenue.
Aucun XML amont résiduel n'est recompté comme nouvelle gate.

| Exécution historique I1 | Tests | Failures | Errors | Skips | Résultat |
|---|---:|---:|---:|---:|---|
| Local initial | 109 | 0 | 0 | 0 | SUCCESS, 2,860 s |
| Local final | 109 | 0 | 0 | 0 | SUCCESS, 2,738 s |
| COMMAND + Architecture, sans filtre | 1 795 | 2 | 0 | 1 | FAILURE, 44 min 05 s |
| Architecture ciblée, 27 classes | 159 | 0 | 0 | 0 | SUCCESS, 44,147 s ; feedback |

Le skip reste
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness` :
`System property [step3b.measure] does not exist`. Aucun skip I1, aucune exclusion
nouvelle. La somme des deux dernières exécutions explique les 1 954 observations.

## `i5_r05` — absence du témoin indépendant de disponibilité

Appel inchangé : `I5TemporalV2Postgres.campaign(PG, 1, 1)`, donc
`controlled=false`, W1/B1/M_W4. Contrat v2 : disponibilité calculée par exclusion
des enveloppes de transactions conflictuelles, pas par la fenêtre contrôlée v3.

Échec exact, après **19,009 s** :

```text
B missing independent available window 98f7268d-d340-581c-b83d-010926223e14/1 ==> expected: <true> but was: <false>
```

La pile conduit à `Journal.verify:520`, appelé par `campaign:778` au premier
cycle nominal. Les vecteurs SQL avant/après ont déjà été contrôlés ; le test
échoue sur l'existence d'au moins une fenêtre indépendante pour chaque candidat.
Pour la clé WRITE `/1`, la seule tentative du cycle contient :

| Fait du journal | Valeur |
|---|---|
| Intervalle store, horloge monotone ns | `2518166614111458..2518166615034958` |
| Résultat de tentative | `CONFIRMED`, transaction `COMMITTED` |
| Token candidat et retourné | `3aaf77f4-700e-4916-b6a0-bca2e313ffa5` |
| Ancienne échéance | `2026-10-30T11:44:09.542521Z` |
| Nouvelle échéance | `2026-10-30T11:44:19.546830Z` |
| Fenêtre indépendante | `physicalFree=false` |
| Enveloppe Discovery sur la même clé | `2518166613998583..2518166615125083`, PID `6806`, XID `5834`, `COMMITTED` |

L'enveloppe Discovery recouvre l'intervalle store : le prédicat `free(...)`
refuse donc le témoin. La transaction renewal elle-même est distincte (PID
`6807`, XID `5833`) et est exclue des conflits par `t != attempt.tx`.
La lease a effectivement été prolongée avec le même token. Cela explique la
différence entre effet SQL positif et assertion de disponibilité négative.

**Cause établie de l'assertion : couverture indépendante absente pour cette
fenêtre nominale.** Le recouvrement d'enveloppes ne prouve pas qu'un verrou de
ligne était détenu à l'instant exact du CAS. Ni défaut du CAS, ni expiration de
lease, ni violation de sûreté ne sont démontrés par cette assertion. Le test
reste FAIL et n'est pas qualifié de faux positif. Les phases suivantes n'ont
pas été atteintes ; le succès SQL ne valide pas toute la campagne.

## `i5_r12` — durée physique des préparations et fermeture

Appel inchangé : `campaign(PG, 2, 32, true)`, W2/B32/M_W4, huit préparations
Business retenues, 64 sessions de rôles, partitions disjointes. Les fenêtres
contrôlées v3 utilisent le helper PostgreSQL existant.

Échec exact, après **94,762 s** :

```text
runtime condition deadline ==> expected: <true> but was: <false>
```

La pile situe l'échec dans `Runtime.close:553`, via `await:73/79` (15 s), appelé
par `campaign:947`, après le `finally` de la campagne. Il s'agit de l'attente de
STOPPED après demande de drain ; pas d'un timeout générique de Maven.

La trace finale contient les vecteurs des deux phases positives WRITE/READ,
quatre témoins `C-EXCESS ... removed=true suspended=true`, la prolongation des
claims après retrait local, puis `JOURNAL SEALED` et le marqueur interne
`V3 QUALIFICATION PASS`. Ces deux derniers précèdent explicitement le nettoyage.
Ils ne valent pas réussite de la méthode JUnit.

Les huit préparations sont déjà retenues avant le premier vecteur. Borne
conservatrice prise après leur entrée : `2026-10-30T11:45:04.119207Z`.
Le dernier constat SQL est écrit **avant** `release.countDown()` :
`2026-10-30T12:46:15.496348+01:00`. La rétention est donc d'au moins
**71,377141 s** au constat, pour une configuration physique Business inchangée :
`transaction_timeout=65000`, `statement_timeout=60000`, socket 70 s.
Le calcul utilise les horodatages PostgreSQL observés, avec conversion explicite
du fuseau JDBC +01:00 ; il ne prétend pas qualifier des horloges arbitraires.

À `12:46:15.504/509+01:00`, le `system-out` de **ce cas** contient des connexions
READ Business rompues, SQLSTATE `08006`, erreurs commit/rollback et
`FATAL: terminating connection due to transaction timeout`. Le journal I1
original contient aussi ces erreurs. L'hypothèse de maintien physique des
préparations pendant toute la séquence n'est donc pas satisfaite.

**Cause physique établie : rétention de fixture supérieure à sa durée de vie
transactionnelle, avec timeout serveur effectivement observé.** Ensuite, la
preuve de fermeture échoue. Le maintien conservateur d'une responsabilité
UNKNOWN est compatible avec les mécanismes L2 ; faute de snapshot Worker après
libération, la responsabilité locale exacte empêchant STOPPED et l'exclusivité
de cette cause ne sont pas prouvées. Aucun défaut nouveau de sûreté n'en est
déduit. Aucun timeout ni règle de drain n'est modifié.

## Comparaison à la baseline et causalité I1

Le diff Git exhaustif sous `app/` entre la baseline immédiate et le SHA audité
contient exactement **12 ajouts Java** (9 production, 3 tests) et **un POM
modifié**. Les **1 227 autres fichiers suivis préexistants** sont identiques
octet pour octet. Cela inclut tests, SQL/migrations, Engine/Worker/Discovery,
owners, pools, lifecycle et propriétés de test. Le reçu donne l'inventaire des
changements et le digest de comparaison.

Les ajouts I1 sont des valeurs, fonctions pures et interfaces/records de contrat.
La recherche de références dans la production ne trouve les nouveaux packages
Identity que dans ces ajouts : aucun raccord runtime. Le banc construit les
Resources et la factory Active existantes explicitement par réflexion ; il
n'installe aucun participant Identity I1. Aucun chemin concret de modification
du renewal, des transactions retenues ou du drain par ces contrats n'est trouvé.

Les deux arêtes ajoutées à `engine-consume-registration` référencent
`engine-consumption` et `port-transaction`, déjà dans la fermeture Worker.
Comparaison supplémentaire avec le **vrai XML historique L2 final** : mêmes
**142 entrées JAR**, mêmes **39 chemins de modules**, dans le même ordre après
normalisation du chemin de worktree. Java 21 / Eclipse Adoptium, macOS 27.0
aarch64, langue fr, UTF-8 et PostgreSQL 17.11 concordent. Aucun changement de
version ou d'ordre de classpath n'est observé. Il s'agit de chemins/versions,
pas d'une certification des octets de chaque JAR/classe.

**Conclusion bornée : aucune causalité fonctionnelle I1 plausible n'est étayée
par les entrées et traces examinées ; aucune correction I1 autorisée.** Cela
ne constitue pas une preuve d'équivalence temporelle. Les 35 tests ajoutés
modifient le travail amont de la gate ; charge de la machine/Docker, scheduling,
JIT et état externe ne sont pas mesurés en comparaison. La campagne L2 ciblée
et la gate I1 complète n'ont pas le même historique de processus. Aucune
reproduction sur la baseline immédiate n'a été effectuée. Le même échec W1 à
ce SHA n'est donc pas proclamé préexistant ; une absence absolue de régression
temporelle n'est pas certifiée.

## Réemploi historique L2, sans transfert de verdict

| Preuve vérifiée | Résultat conservé | Usage limité ici |
|---|---|---|
| v2, gel `5eab2e94379ef40c3f9ff54c14a4d04a8f13dcbd` | W1 `i5_r05` PASS, 53,598 s ; W2 `i5_r12` FAIL, 21,747 s ; qualification FAIL | Le type d'échec « fenêtre disponible manquante » existe avant I1, mais alors sur W2/READ `/1`, pas sur W1/WRITE courant. Le PASS W1 historique reste PASS historique. |
| Finale, gel `a2133b6b43a74999374bc26a222f70c7500fadda` | `i5_r12` FAIL, 96,788 s ; INCONCLUSIVE / disponibilité | Même méthode, même message, mêmes lignes de pile de fermeture ; rétention historique >71,297318 s et timeout Business 65 s déjà documentés. |
| Corrections L1/L2, SHA testé `3dd15a666e782df653507fcbf19c07178dfc9069` | Gate réussie sur périmètre humain autorisé, r05/r12 NOT RUN | Ne couvre aucune exécution des deux méthodes ; exclusion humaine propre à ce mandat, non transférable automatiquement à I1. |

Archives v2/finale : SHA-256 respectifs
`fa510d8d477ce65dbf16c723c69c52c5569f711d0f078944da6a4c44084a7fe3` et
`3432e9c930a4a6aa0f69de590fda2b0ca0747e46a34e8d297b86a417c86a46e2`.
Voir [v2](L2_I5_Temporal_V2_Report.md), [finale](L2_I5_Temporal_Final_Report.md),
[corrections](L1L2_Targeted_Corrections_Report.md) et
[clôture humaine L2](L2_Documentary_Closure.md).

Les trois sources de banc (`V3WorkerResourcesPostgresTest`, `V3WorkerPostgresTest`,
`I5TemporalV2Postgres`) sont identiques entre gel final L2, baseline immédiate et
I1 ; la configuration Business aussi. **Tout le code L2 historique n'est pas
identique** : les corrections ultérieures ont changé `JdbcPotHistoryAdapter`,
`PotHistoryRows`, leur test de cardinalité, le harnais F2 et une garde architecture.
Le delta complet est enregistré dans le reçu. La concordance explique les
mécanismes observés ; elle n'autorise pas la réutilisation d'un PASS global
qui n'existait pas. R-L2-01…05, AUD-I5-01/02, R-AUD-I3-03 et G1/G2/G3 restent
dans leurs états historiques, sans nouvelle certification temporelle.

## Vérifications complémentaires : décision et obstacle restant

**Aucune nouvelle exécution Maven n'a été nécessaire pour établir ce constat.**
La conservation des traces, leur intégrité et la comparaison des hypothèses
apportent des faits nouveaux sans rejouer les campagnes.

- Rejouer les 35 tests purs n'expliquerait ni le témoin de fenêtre ni la durée
  Business. Leur PASS final est déjà scellé et les sources n'ont pas changé.
- Une reproduction ciblée unique sur `140d13c3` pourrait documenter le résultat
  à cette baseline, mais ni son PASS ni son FAIL ne satisferait la gate I1.
  Elle n'est pas nécessaire au maintien de NOT CLOSED ; elle n'est pas lancée.
- Répéter r05/r12 ou la gate sans changement d'hypothèse n'établirait pas une
  disponibilité indépendante ni une durée physique valide. Aucune recherche
  statistique d'un PASS n'est effectuée. Une machine plus rapide ne suffit pas
  à démontrer que la longue rétention est compatible avec le timeout.
- Une seconde contribution architecture filtrée ne lèverait pas la réserve.
  Le full reactor ajouterait du coût sans résoudre la précondition (§20).

**Obstacle précis : la gate de clôture imposée contient deux campagnes dont les
conditions de preuve échouent ; la gate globale architecture n'est pas exécutée
canoniquement jusqu'au bout.** L'intégrité des preuves et l'absence de défaut I1
identifié ne satisfont pas ces obligations. La politique §34 interdit de remplacer
une gate explicitement requise par une réutilisation historique ou un filtrage.

Arbitrage humain explicite proposé, limité à I1 :

1. **Maintenir les critères actuels : I1 NOT CLOSED**, I2 toujours arrêté.
   Une future investigation devra établir un protocole d'exécution qui satisfait
   les préconditions existantes avant toute nouvelle gate unique sans filtre.
   Si cela exige de modifier le banc L2 ou ses budgets, un mandat distinct est
   indispensable ; aucun tel changement n'est autorisé ici.
2. **Accepter I1 humainement avec réserves nominatives**, en reconnaissant
   séparément les 35 tests PASS, la revue favorable et la comparaison bornée,
   et en acceptant explicitement l'absence de gate canonique complète. Toute
   définition d'une gate dérogatoire doit être approuvée pour I1 avant exécution,
   documentée avec sa couverture manquante et un verdict distinct du PASS
   technique canonique. Aucun filtre ni skip n'est ajouté par ce rapport.

Aucune de ces décisions n'est prise à la place du responsable. L'acceptation L2
ne tranche pas I1. Aucun GO I2 n'est déduit d'une éventuelle acceptation I1.

## Commandes et état de livraison

Commandes Maven historiques réellement exécutées pour I1 (reçus conservés) :

```sh
# Depuis app/ ; première commande deux fois, gate composée une fois.
./mvnw -pl domain-user-identity,contracts-registration,engine-consume-registration -am test
./mvnw -pl runtime-command-consumption-worker,architecture-tests -am test
```

La commande architecture ciblée complète reste dans
[architecture-command.txt](../testing/evidence/L3-I1/architecture-command.txt).
Elle n'est pas relancée. Les commandes ciblées historiques L2 et leurs issues
exactes restent dans leurs reçus, également référencés par `Analysis.json`.

Commandes de cette investigation, depuis la racine du worktree cible :

```sh
python3 docs/testing/evidence/L3-I1-resolution/analyze.py --capture
python3 docs/testing/evidence/L3-I1-resolution/analyze.py
git diff --check
git diff --name-status 140d13c3101a4e297bdea5141d167d189b7dcd02 02c7bfbf85936f1f1a633e9097bc2b49f878708e -- app
git diff --stat a2133b6b43a74999374bc26a222f70c7500fadda 140d13c3101a4e297bdea5141d167d189b7dcd02 -- app
git status --short
git rev-list --left-right --count origin/feat/v3-architecture...HEAD
```

Les lectures `cat`/`sed`/`rg`, inventaires `git worktree list`, `show-ref`, `log`,
`show`, `ls-tree`, `diff --stat/--name-status`, `git grep`, décompression gzip,
lecture tar/XML et calculs SHA-256 par Python ont accompagné ces contrôles.
`analyze.py` encode leurs vérifications probantes et sort avec le code **0** ;
cela signifie intégrité/comparaison réussies, **pas gate applicative PASS**.
Une vérification Python des liens locaux du présent document et de l'inventaire
de livraison complète les contrôles documentaires.

État de livraison : HEAD inchangé au SHA audité ; aucun commit ou push effectué.
Référence locale `origin/feat/v3-architecture` identique (divergence 0/0), sans
prétendre à une nouvelle lecture réseau. Seuls ce rapport et
`docs/testing/evidence/L3-I1-resolution/` sont ajoutés dans le worktree cible.
Production observée **NONE** ; nouvelles slices **NONE** ; scope de clôture I1
**COMMAND + Architecture globale REQUIRED / non satisfaite** ; full reactor
**NOT RUN / NOT REQUIRED** ; aucune migration ni exécution DB nouvelle.
**Arrêt avant I2.**
