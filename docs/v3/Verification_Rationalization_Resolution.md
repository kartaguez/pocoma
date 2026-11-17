# Pocoma V3 — Résolution AUD-VERIFY-001 à 005

31 octobre 2026. **Verdict : CLOSED WITH RESERVATIONS.** Les cinq findings sont
**FIXED dans le périmètre démontré**. Le faux PASS majeur est reproduit avant
correction, explicitement refusé après correction et protégé par les contrôles
négatifs. Ce verdict ne certifie ni toutes les slices, ni SPEC-10 globalement.

## Références et périmètre déclaré

- Dépôt `kartaguez/pocoma`, branche `feat/v3-architecture`.
- HEAD initial et distant lus avant modification :
  **`33f02f4512af0dbafb5c482ec84171801815d82c`** ; worktree propre, tracking 0/0.
- Implémentation auditée : `d0927d9410ebcd65c221e73c5ecd04e5242fe4e9`.
- [Audit indépendant](Verification_Rationalization_Independent_Audit.md),
  [annexe des invocations](Verification_Rationalization_Test_Sets.md) et
  [preuves originales](../testing/evidence/verification-independent-audit-d0927d94/README.md)
  lus directement ; cinq fichiers publiés et 61 membres rehashés, conformes.
  L'audit, son manifeste et ses preuves ne sont pas réécrits.
- Référentiel lu : politique Reactor, dossier de conservation, SPEC-10,
  scripts/registre, POM, fixtures et références SQL concernés.
- Le SHA final est celui du commit contenant ce rapport, retrouvable par
  `git log -1 --format=%H -- docs/v3/Verification_Rationalization_Resolution.md` ;
  le reçu de publication communique sa valeur exacte après commit. Les campagnes
  portent le HEAD initial et les empreintes du delta non encore commité, pas un
  SHA final anticipé. L'empreinte du sélecteur final concorde dans les sept reçus.

Déclaration **avant implémentation** : outillage de vérification ; contribution
locale COMMAND/Identity, FOUNDATION et composition Identity monolithe.
Production : seul déplacement à octets identiques d'une source dans
`engine-consumption`, sans changement de package/import, contrat, dépendance ou
frontière. Risques : verdict/complétude, provenance SQL, build incrémental et
métriques. Gates : trois recettes Identity, FOUNDATION canonique et contre-probes
de sélection ; contrôles Python et relecture indépendante des preuves.
Impact métier, Consumption/Discovery, transactions et oracles interdit.

Franchissement inattendu : **NONE**. Gate Architecture globale : **NOT REQUIRED /
NOT RUN** (aucun déclencheur §17). Full reactor : **NOT REQUIRED / NOT RUN**
(périmètre borné ; aucun jalon global/release/main ni restructuration Maven).
DB : PostgreSQL réel des fixtures V3 sélectionnées ; pas de parcours historique
supplémentaire ni de baseline déclarée certifiée. Aucun `i5_r12` exécuté.

## Matrice de clôture et analyse causale

| Finding | Statut | Cause et correction démontrée |
|---|---|---|
| AUD-VERIFY-001 MAJOR | **FIXED** | `_JAVA_OPTIONS` injectait `test` et `surefire.failIfNoSpecifiedTests` dans les JVM, sans argv déclaré. Le canonique avait `expected={}` ; un ensemble non vide sans erreur suffisait. Refus des canaux implicites avant tout sous-processus, inventaire effectif Maven + identités de sources et comparaison exhaustive des invocations pour tous les verdicts. Contre-exemple avant : faux PASS 4/28 ; après : refus code 2 ; nominal FOUNDATION 28/28, deux fois. |
| AUD-VERIFY-002 MINOR | **FIXED** | Le chemin `engine/exception` ne correspondait pas au package `engine.write.pot.exception`. Le compiler recherchait une sortie à un chemin différent : 55 sources constamment recompilées, puis 10 chez registration. Déplacement au bon chemin, octets inchangés ; aucun flag/cache/plugin modifié. Deux mesures chaudes isolées : 65 → 0 annonces de recompilation. Mutation de bytecode compilée et restauration exacte prouvées. |
| AUD-VERIFY-003 MINOR | **FIXED** | `app_inputs()` omettait les SQL chargés depuis `../../scripts/v3/`. Traçage borné des tests/helpers sélectionnés et de leurs noms SQL littéraux ; SHA-256 avant/après, fichier absent refusé. Six entrées CHANGE, sept INTEGRATION ; modification pertinente détectée, SQL hors recette ignoré. |
| AUD-VERIFY-004 MINOR | **FIXED** | Le parseur historique comptait seulement `source files`, perdant le singulier. Parseur commun `source files?`, contexte module/goal et séparation présence/configuration/recompilation/réemploi. Relecture des logs existants : 59 annonces / 757 sources et 22 / 452, protégée par un test sur ces archives. |
| AUD-VERIFY-005 OBSERVATION | **FIXED** | Expansion Python réservée à Identity. Expansion cumulative commune, recettes explicites dans `recipes` du même registre ; FOUNDATION FAST/CHANGE exécutés, canonique exécuté deux fois, mêmes identités contrôlées. Aucune généralisation de couverture aux autres slices. |

### 001 — mécanisme et contrôles de fiabilité

La JVM consomme `_JAVA_OPTIONS` à son démarrage et l'environnement est hérité par
les sous-processus Maven/Surefire. `-Dtest=SpringTransactionRunnerTest` devient une
propriété système utilisée par Surefire ; `failIfNoSpecifiedTests=false` permet
aux modules amont sans correspondance de réussir. Le plan sans `-Dtest` apparaissait
canonique, mais aucun inventaire n'exigeait les 24 autres méthodes PG. Le total 4,
sans failure/error/skip, était donc abusivement considéré complet.

Le refus inclut maintenant `_JAVA_OPTIONS` aux côtés des quatre canaux déjà
interdits ; il intervient avant `java -version`, Maven et Docker. Les fichiers
`.mvn/maven.config`, `jvm.config`, `extensions.xml` non vides nécessitent également
une revue. Les filtres autorisés restent les deux arguments explicites générés
par les recettes. Leur `failIfNoSpecifiedTests=false` ne dispense jamais de
l'inventaire global des obligations de la recette.

Chaque exécution appelle d'abord `help:effective-pom` avec la même sélection.
Maven fournit la fermeture du reactor ; le collecteur confronte les racines de
tests aux modules réels, inspecte les configurations Surefire effectives et
inventorie les identités des sources, indépendamment des XML historiques. Il
vérifie les ancêtres de fixture, y compris les classes auxiliaires dans le même
fichier. Le modèle et le log de découverte sont archivés. Le dry-run canonique
reste un plan à découverte différée, jamais une preuve de complétude.

Un PASS nécessite un inventaire non vide et **exactement une invocation fraîche
réussie de chaque identité attendue**, sans identité supplémentaire/dupliquée,
compteurs incohérents ni skip. Les anciens XML sont déplacés avant Maven et ne
contribuent jamais ; un rapport recopié avec ancienne date n'est pas frais.
Les deux invocations boolean sont distinguées (`(boolean)[1]`, `[2]`). Les formes
non démontrées — nouveaux providers, tests dynamiques, répétés, composés, hérités
ou générés — sont refusées ou INCONCLUSIVE. Elles ne sont pas supposées couvertes.
Échec Maven/oracle : FAIL ; preuve ou découverte insuffisante : INCONCLUSIVE ;
configuration implicite : refus explicite, code 2.

Le contrôle porte sur les sources du run. Une suppression volontaire de test
avant planification change les obligations et l'empreinte des inputs : elle reste
soumise à revue de source et n'autorise pas la réutilisation de l'ancienne preuve.
Aucun second catalogue normatif de méthodes ni historique XML auto-apprenant n'est
introduit.

| Contre-vérification exécutée | Résultat | Preuve |
|---|---|---|
| Contre-exemple exact avant correction, mêmes variables/argv que l'audit | **Défaut reproduit : faux PASS 4/28**, Maven 1,699 s | `resolution/before-counterexample/` |
| Même contre-exemple après correction | **Refus code 2**, `_JAVA_OPTIONS is set`, aucun test exécuté | `resolution/counterexample-final.json`, durée exacte dans ce JSON |
| XML bruts du contre-exemple original de l'audit confrontés aux 28 attendus | INCONCLUSIVE, 24 méthodes absentes | test `test_audit_four_test_counterexample_is_not_complete` |
| Identités différentes avec total égal ; inventaire vide ; méthode attendue absente ; invocation boolean manquante/dupliquée | non-PASS dans chaque cas | `resolution/selector-tests-final.log` |
| Rapports absents, anciens seuls, partiels, recopiés avec ancienne date | INCONCLUSIVE dans le pipeline `run` | même log, `test_freshness_pipeline_old_absent_partial_and_current_reports` |
| Rapport complet frais, sans incertitude | PASS dans le contrôle synthétique ; nominal réel ci-dessous | même log et campagnes réelles |
| Configuration Surefire non revue ; annotation composée ; découverte de méthode disparue ; provider non supporté | non-PASS/refus | tests `DiscoveryTests` et `AuditRegressionTests` |
| SQL modifié pendant le run simulé, rapports pourtant complets | INCONCLUSIVE | test du pipeline ci-dessus |

Ces contrôles Python utilisent des fixtures déterministes et des sous-processus
simulés pour les erreurs de collecte. Ils ne sont pas présentés comme des tests
PostgreSQL. Les invocations Maven réellement exécutées sont séparées ci-dessous.

## Campagnes fraîches et couverture effective

Commande depuis la racine :
`python3 -B scripts/testing/verify.py <NIVEAU> <scope> --output /private/tmp/pocoma-verify-resolution/<nom>`.
Les argv Maven exacts, exclusions, identités attendues/exécutées, SHA et hashes sont
dans chaque plan/provenance/result. Les commandes Maven sont lancées depuis `app/`.

| Nom de preuve / niveau / scope | Tests | Durée processus Maven | Découverte modèle | Verdict |
|---|---:|---:|---:|---|
| `foundation-filtered-fast` / FAST / `foundation` | 4 | 1,700 s | 1,111 s | PASS filtré, H2 |
| `foundation-canonical-final` / CHANGE / `FOUNDATION` | 28 | 11,403 s | 1,116 s | PASS canonique |
| `foundation-canonical-repeat` / CHANGE / `FOUNDATION` | 28 | 11,264 s | 1,217 s | PASS canonique, mêmes identités |
| `foundation-filtered-change` / CHANGE / `foundation` | 28 | 11,253 s | 1,214 s | PASS filtré, même ensemble que le canonique |
| `fast-final` / FAST / `identity` | 65 | **3,554 s** | 1,325 s | PASS |
| `change-final` / CHANGE / `identity` | 127 | **121,005 s** | 2,011 s | PASS |
| `integration-final` / INTEGRATION / `identity` | 162 | **215,805 s** | 1,997 s | PASS |

Zéro failure/error/skip et zéro différence d'identité dans ces sept campagnes.
Le contrôle indépendant reparcourt les archives/XML sans importer le sélecteur :
7 campagnes, hashes conformes, **F ⊂ C ⊂ I**, ensembles Identity identiques aux
65/127/162 historiques, FOUNDATION répété identique. Commande :
`python3 -B /private/tmp/pocoma-verify-resolution/independent-checks.py`, **0,072 s**.
Source et résultat conservés dans l'archive.

Comparaison historique à couverture égale : FAST **6,686 → 3,554 s** ; CHANGE
**144,100 → 121,005 s** ; INTEGRATION **216,263 → 215,805 s**. Ce sont des observations
unitaires, sensibles à l'environnement, pas des garanties ni une causalité
statistique. La découverte et l'archivage étaient hors des durées historiques ;
la découverte supplémentaire est donc affichée séparément. Les temps muraux
complets du sélecteur sont dans `final-campaigns.json`.

Sélections inchangées : FAST → `engine-consume-registration,domain-pot -am` ;
CHANGE Identity → `runtime-command-consumption-worker -am` ; INTEGRATION ajoute
`runtime-monolith`. FOUNDATION → `infra-tx-spring -am`, fermeture effective de deux
modules Java avec `port-transaction`, parent exclu des métriques Java.
Les recettes filtrées utilisent les listes positives du registre ; les tests
non sélectionnés restent NOT RUN, pas PASS. Aucun test I5 ni Architecture globale
ajouté. INTEGRATION `foundation` a un plan égal à CHANGE `foundation`, vérifié
statiquement ; cette invocation supplémentaire n'a pas été exécutée.

Une première relance CHANGE (`resolution/change-identity/`) a exécuté les 127 tests
sans erreur mais est restée **INCONCLUSIVE** : le nouveau garde de découverte
ne résolvait pas encore les fixtures auxiliaires Identity et traitait la répétition
effective des includes hérités comme inconnue. Ce défaut du collecteur a été
corrigé et protégé par tests avant le nouveau run `change-final`. Le reçu original
INCONCLUSIVE est conservé ; il n'est pas renommé PASS.

## Compilation, mesures et absence d'artefact obsolète

Depuis `app/`, préfixe exact :
`./mvnw --batch-mode --no-transfer-progress -pl engine-consume-registration,domain-pot -am`.
Les mesures avant/après qualifiées utilisent deux copies isolées des sources :
`before-checkout` extrait de `git archive HEAD app`, `after-checkout` copie des
sources corrigées sans `target`. Dépendances `.m2` chaudes dans les deux cas ;
« froid » signifie **sorties target propres**, pas cache réseau vierge.

| Mesure / arguments après préfixe | Avant : processus / interne Maven | Après : processus / interne Maven | Sources annoncées avant → après |
|---|---:|---:|---:|
| À froid : `clean compile` | 2,190 / 1,444 s | 2,105 / 1,340 s | 225 → 225 |
| Chaud inchangé 1 : `compile` | 1,716 / 1,040 s | 1,042 / 0,390 s | 65 → 0 |
| Chaud inchangé 2 : `compile` | 1,655 / 0,991 s | 1,035 / 0,387 s | 65 → 0 |
| Incrémental, commentaire contrôlé BindingId : `compile` | 1,806 / 1,111 s | 1,492 / 0,805 s | 93 → 38 |
| Restauration exacte : `compile` | 1,796 / 1,099 s | 1,487 / 0,814 s | 93 → 38 |
| Nouveau chaud inchangé : `compile` | 1,582 / 0,924 s | 1,059 / 0,402 s | 65 → 0 |

Pilote : `python3 -B /private/tmp/pocoma-verify-resolution/measure.py <nom> <checkout>`.
Logs et mesures : `resolution/before-delayed/` et `after-isolated/`. Chaque commande,
code retour, durée et hash de restauration est conservé. Pas de changement de
phases par défaut, aucun `install`, `-T`, cache supplémentaire, invalidation
contournée ni désactivation de compilation.

Une preuve supplémentaire modifie temporairement une constante statique **dans
ces copies isolées uniquement** : `incremental-proof.py` vérifie que le marqueur
apparaît dans le `.class`, puis disparaît et retrouve le hash initial après
restauration. Mutation : **1,938 s avant / 1,673 s après** ; restauration :
**2,091 / 1,608 s**. Le `.class` restauré est identique dans les deux copies ; les
**314 fichiers .class** de la fermeture restaurée sont également identiques
(`class-output-comparison.json`). Le déplacement de source lui-même conserve
exactement les octets du blob initial.

Les premières mesures exploratoires dans le checkout actif sont conservées dans
`before-builds/`, `after-builds/`, `after-delayed/`. Les mutations de commentaire
après correction n'y donnaient pas d'annonce de compilation attribuable à Maven ;
elles ne sont donc **pas** utilisées comme preuve incrémentale. L'intervalle
explicite et l'isolation, puis le marqueur de bytecode, lèvent cette insuffisance
sans modifier les paramètres du compiler ni supposer sa cause externe.

### 003 — périmètre SQL et invalidation

CHANGE Identity charge les six SQL : `bootstrap-l2-routing.sql`,
`bootstrap-l2-worker-dev.sql`, `configure-l3-identity-logging.sql`,
`grant-l2-discovery.sql`, `grant-l2-lifecycle.sql`, `grant-l2-worker-dev.sql`.
INTEGRATION ajoute conservativement `reactivate-l2-slot.sql` parce que le helper
Worker référencé contient aussi ce chargement. FAST Identity et FOUNDATION :
aucun SQL externe. Le POM ne génère pas de ressource à partir d'un SQL externe ;
les migrations/fixtures sous `app/` étaient déjà hashées.

`sql-proof.py` modifie une copie réelle de `bootstrap-l2-routing.sql` : empreinte
CHANGE différente ; modifier `reactivate-l2-slot.sql` laisse cette même empreinte
CHANGE inchangée ; restauration exacte confirmée. Commande, durée et quatre
inventaires dans `sql-input-proof.json`. Le test du pipeline démontre aussi le
verdict INCONCLUSIVE si l'input change pendant l'exécution. Une preuve historique
dont l'input SQL diffère n'est pas équivalente/réutilisable ; aucun cache de
résultats n'est ajouté.

Le traçage est déterministe, borné au loader littéral actuel `scripts/v3/` et
conservateur au niveau des fichiers helpers. Il ne prétend pas analyser tous les
chemins Java dynamiques. Ajouter un nouveau loader/nonliteral exige revue du
collecteur ; cette limite est explicite dans la politique.

### 004 — sémantique des métriques et correction historique

- **Présentes** : fichiers Java main/test/generated observés sur disque.
- **Dans le périmètre** : modules Maven effectifs et fichiers de leurs racines
  configurées, inventoriés séparément. Les inventaires générés peuvent recouper
  une racine ; ils ne s'additionnent pas artificiellement.
- **Recompilation annoncée** : entrées des lignes `Compiling N source file(s)`,
  avec module, goal `compile`/`testCompile`, destination et ligne du journal.
  Ce n'est ni le nombre de sorties `.class` ni une mesure indépendante des lectures
  effectives de javac.
- **Réemploi exact par fichier** : UNKNOWN/null lorsque Maven ne l'expose pas.
  Les messages de no-op restent dans les logs. Copier une ressource n'est pas
  compiler une source Java ; aucune métrique de ressources n'est inventée.

Les 19 logs historiques de build sont reparcourus, sans relancer les benchmarks
complets. **Full propre : 59 annonces / 757**, dont 14 singuliers auparavant omis.
**COMMAND après mutation : 22 / 452**, dont quatre singuliers omis. Le rapport
opérationnel est corrigé ; les anciens JSON/bruts sont conservés et leurs anciens
agrégats sont explicitement remplacés par `summary.json.historical_compiler_recount`
et le détail `resolution/historical-compiler-recount.json`. Les durées historiques
et décisions de certification ne changent pas. Les tests protègent singulier,
pluriel, main/test, inventaire généré et les deux agrégats réels archivés.

## Commandes de contrôle et conservation

- `python3 -B -m unittest -v scripts/testing/test_verification.py` : **30 tests PASS,
  0,740 s**, log `resolution/selector-tests-final.log`. Les sept sous-cas du
  pipeline sont détaillés dans le log ; ce total est celui des méthodes unittest.
- Sept commandes de campagne du tableau : argv et durées exactes dans
  `summary.json.final_campaign_commands`, reçus et archives brutes sous
  `resolution/<nom>/`.
- Contre-exemple avant/après : variable et commande exactes de l'audit ; reçus
  `before-counterexample/` et `counterexample-final.json`. L'avant était un défaut,
  jamais une validation FOUNDATION.
- Mesures de compilation : `measure.py`, `incremental-proof.py`, tous leurs logs,
  tableaux et empreintes ; aucun PASS de tests déduit d'un `compile` réussi.
- Probe SQL statique : `python3 -B /private/tmp/pocoma-verify-resolution/sql-proof.py`,
  résultat/durée `sql-input-proof.json`, aucune nouvelle exécution PG par ce probe.
- Relecture indépendante fraîche : `independent-checks.py`, **PASS des contrôles**,
  0,072 s ; il lit hashes, XML, ensembles et sorties de probes, sans exécuter Maven.
- Contrôles statiques supplémentaires : déplacement Java à octets identiques,
  registre quarantine inchangé, source SPEC-10 et audit inchangés, `git diff --check`,
  revue du périmètre et intégrité de la publication des preuves.

Preuves durables :
[summary.json](../testing/evidence/verification-resolution-33f02f45/summary.json),
[manifest.json](../testing/evidence/verification-resolution-33f02f45/manifest.json),
[archive de résolution](../testing/evidence/verification-resolution-33f02f45/resolution-evidence.tar.gz).
L'archive contient **110 fichiers**, dont sources des pilotes, reçus, logs,
archives XML/physiques, reconstruction et snapshot de l'implémentation. Le
manifeste donne les tailles et SHA-256 des membres et des deux fichiers publiés.
Il n'invente pas de hash autoréférent.

Restaurer dans une destination neuve :

```sh
mkdir /private/tmp/pocoma-verification-resolution-review
tar -xzf docs/testing/evidence/verification-resolution-33f02f45/resolution-evidence.tar.gz -C /private/tmp/pocoma-verification-resolution-review
```

Les constantes `base`/`root` des pilotes conservés identifient leur exécution
originale ; les adapter à l'extraction/checkout pour une relecture sur une autre
machine. La validation d'intégrité et la reconstruction XML ne requièrent pas
Docker. Les anciennes observations erronées ou INCONCLUSIVE restent identifiées.

## Fichiers et publication

Liste exacte des chemins modifiés/créés/supprimés :

1. **Supprimé par déplacement** : `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/exception/MissingTerminalConsumptionFailureException.java`.
2. **Créé par déplacement** : `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/write/pot/exception/MissingTerminalConsumptionFailureException.java`.
3. `scripts/testing/verify.py`.
4. `scripts/testing/verification-campaigns.json`.
5. `scripts/testing/test_verification.py`.
6. `docs/testing/Reactor_Verification_Policy.md` (§35 seulement).
7. `docs/testing/Verification_Optimization_Report.md` (comptes historiques corrigés).
8. `docs/v3/Verification_Rationalization_Resolution.md` (ce rapport distinct).
9. `docs/testing/evidence/verification-resolution-33f02f45/summary.json`.
10. `docs/testing/evidence/verification-resolution-33f02f45/manifest.json`.
11. `docs/testing/evidence/verification-resolution-33f02f45/resolution-evidence.tar.gz`.

Publication sur `feat/v3-architecture`, commit et push normal sans force après
revue du diff, contrôle d'intégrité et `git diff --check`. Le reçu final indique
le SHA obtenu, la tête distante réellement relue et le statut du worktree.
Aucun travail préexistant n'était présent dans le diff initial ; les autres
worktrees sont préservés.

## Réserves conservées

- Généricité démontrée sur **Identity et FOUNDATION**, pas sur toutes les slices,
  ni sur toutes les formes JUnit. Les autres commandes canoniques restent
  disponibles, mais une découverte non démontrée ne peut obtenir PASS automatisé.
- SQL : analyse actuelle bornée et conservatrice par helper, pas analyse générale
  des dépendances Java. Réemploi exact de chaque source compilée : non observable.
- Durées : observations locales uniques, `.m2` chaude, aucune garantie statistique
  de performance ou de second environnement PostgreSQL.
- **I5 inchangé** : CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE ;
  quarantaine nominative et FAIL historique `i5_r12` conservés, aucune sortie de
  quarantaine, aucun certificat 30 s ni garantie globale 90 s acquis. Aucun I5
  relancé dans cette mission.
- **SPEC-10 inchangée ; certification NOT RUN**, aucun PASS global V3/release ou
  homologation production. Les réserves historiques L2/L3 restent en vigueur.

L'adoption opérationnelle bornée des recettes démontrées dispose maintenant de la
contre-vérification majeure requise. Cela ne dispense d'aucune gate normative
non filtrée applicable au prochain changement.
