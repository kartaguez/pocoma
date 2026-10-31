# Pocoma V3 — Résolution P0 de la provenance AUD-VERIFY-001-R1

31 octobre 2026. **Verdict : CLOSED WITH RESERVATIONS**, dans le périmètre
FOUNDATION et des recettes Identity démontrées. Le faux PASS indépendant est
reproduit avant correction et refusé après correction ; provenance et complétude
sont contrôlées conjointement. Les réserves bornent la portée, sans autoriser
la réutilisation de résultats historiques dans un nouveau PASS.

## Références, portée et publication

- Dépôt `kartaguez/pocoma`, branche `feat/v3-architecture`.
- SHA initial local et distant : **`b8df79a2c5067bffa92b52e3a08a65e929ce716b`**.
  Worktree initial propre ; fetch réel, divergence 0/0.
- Implémentation auditée : `6d40ed17496709ce75175c4651dbaaaedc78343e`.
- Références directement consultées : [contre-audit indépendant](Verification_AUD_VERIFY_001_Independent_Counter_Audit.md),
  son README, summary, manifeste et archive, [audit initial](Verification_Rationalization_Independent_Audit.md),
  [résolution précédente](Verification_Rationalization_Resolution.md), scripts,
  registre, tests, POM et modèles effectifs ; politique Reactor, dossier de
  conservation et SPEC-10. Vérification avant exploitation : **4 fichiers publiés
  et 124 membres** du contre-audit conformes en taille et SHA-256 ; les **61 membres**
  de l'audit et **110 membres** de la résolution sont également vérifiés.
- Déclaration préalable : outillage/production des rapports et validation ; slice
  primaire **FOUNDATION canonique**, contributions secondaires **COMMAND/Identity**
  et composition monolithe Identity, campagnes FAST/CHANGE/INTEGRATION.
  Aucun changement métier, transactionnel, SQL, oracle ou contrat applicatif autorisé.
- Impact productif attendu/observé : **NONE**. Seul changement sous `app/` : profil
  Maven opt-in des rapports de tests ; aucun graphe de dépendance modifié.
  Franchissement inattendu : **NONE**. Architecture globale **NOT REQUIRED / NOT RUN**
  (§17 non déclenché). Full reactor **NOT REQUIRED / NOT RUN** : pas de clôture Wave,
  release, intégration main ni impact applicatif non borné. Aucun `clean install`.
- DB : fixtures PostgreSQL courantes des sélections V3 ; aucune nouvelle campagne
  de migrations historiques, aucune baseline déclarée certifiée.
- SHA final : celui du commit portant ce rapport, communiqué au reçu après push et
  vérification distante ; reproductible par
  `git log -1 --format=%H -- docs/v3/Verification_AUD_VERIFY_001_Provenance_Resolution.md`.
  Les reçus portent le HEAD initial et les hashes du delta exécuté ; ils ne prétendent
  pas avoir exécuté un SHA final encore inexistant. Les hashes du code livré concordent.

## Analyse causale avant correction

Reproduction dans un clone partagé **détaché au SHA 6d40ed17**, sous
`/private/tmp/pocoma-provenance-resolution/counter-audit/checkout`. Le pilote
`cli-replay-probe.py.txt` de l'auditeur est exécuté **sans modification**, après
vérification du manifeste et retrait des seuls anciens dossiers de ses deux runs
pour permettre de nouvelles créations. Les cinq XML historiques proviennent de
`counter-audit/baseline-xml/`, identiques aux pièces publiées ; ils restent des
résultats historiques utilisés comme attaque, jamais de nouveaux PASS.

1. **Invocation Maven** : la CLI prépare 28 obligations FOUNDATION. Un
   `MAVEN_BASEDIR` externe fournit `.mvn/jvm.config` avec
   `-Dtest=SpringTransactionRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`.
   L'argv apparent reste canonique ; ce canal échappe au préflight précédent.
2. **Exécution Surefire** : le Maven réel ne lance que les **4 H2**, retourne 0
   et annonce BUILD SUCCESS. Failsafe n'est pas lié au lifecycle actuel `test`.
3. **Production XML** : les quatre H2 sont produits dans le `target/surefire-reports`
   partagé du module ; les cinq anciens XML PostgreSQL portent **24 autres cas**.
4. **Collecte** : la CLI a déplacé les rapports présents au départ. Dès la création
   de `maven.log`, le pilote recopie ces cinq XML dans le même `target`, par
   `copyfile`, octets inchangés et **mtime renouvelé**. Ils entrent dans le glob de
   collecte final ; la comparaison `mtime >= started` les accepte.
5. **Validation** : les identités anciennes comblent exactement les 24 obligations
   absentes. La comparaison exhaustive est correcte sur la population XML déclarée,
   mais ne sait pas quel processus l'a produite.
6. **Verdict** : `assess` puis `run` de la CLI déclarent **PASS 28/28, code 0,
   issues=[]**, alors que le journal Maven ne contient que 4 tests. La reproduction
   sans recopie reste INCONCLUSIVE 4/28. Le faux PASS reproduit dure **1,584 s**
   côté processus Maven ; ce n'est pas un résultat de qualification FOUNDATION.

Les hashes des octets collectés ne prouvaient que leur intégrité, pas leur
production pendant l'invocation. Déplacer/nettoyer les seuls rapports ou déplacer
la collecte dans un dossier neuf ne corrige pas cette chaîne causale.

## Contrat et solution minimale retenue

**P1/P2 — espace neuf et producteur isolé.** Sous le verrou du worktree, la CLI
crée le dossier d'évidence inexistant, un ID d'invocation et `reports/`, avant
Maven. Elle ajoute deux propriétés explicites à la commande canonique :
`-Dpocoma.verification.id=<ID>` et
`-Dpocoma.verification.reports=<output>/reports`. Le profil parent
`verification-provenance`, activé uniquement par l'ID, configure directement
Surefire et Failsafe :

```
<output>/reports/<project.artifactId>/surefire-reports/
<output>/reports/<project.artifactId>/failsafe-reports/
```

Le résumé Failsafe suit aussi ce répertoire. `pluginManagement` ne crée aucune
nouvelle exécution Failsafe. Les sous-répertoires de chaque module du modèle
Maven effectif sont créés avant les tests ; une collision est une erreur.
La configuration effective Surefire, y compris celle de `default-test`, doit
correspondre au contrat exact. Un override vers le target partagé est refusé
**avant les tests**. Aucun ancien XML n'alimente le producteur isolé par copie.

**P3 — corrélation vérifiée.** Le profil transmet aux JVM JUnit les propriétés
`pocoma.verification.id`, `.module`, `.producer`, présentes dans chaque XML.
La CLI exige exactement une occurrence de chaque valeur attendue, l'emplacement
producteur autorisé et le module de la source de la classe. Liens symboliques,
liens physiques multiples et emplacements étrangers sont refusés. Une copie
historique même injectée directement dans le répertoire isolé garde son ancien
ID ou n'en a aucun : elle est rejetée **avant comptage**. Aucun mtime ne prouve
la provenance des XML. Les octets XML évalués sont exactement ceux archivés.

**P4/P5 — complétude et prudence.** La comparaison exhaustive des identités et
multiplicités est conservée, avec attribution au module en supplément. Absence,
ajout, doublon, compteurs incohérents, skip, XML invalide, découverte incertaine,
inputs modifiés, erreur de collecte ou provenance non démontrée empêchent PASS.
Un échec Maven demeure FAIL même avec tous les XML. Un diagnostic explicite est
persisté pour les interruptions et erreurs après création du dossier ; les
refus de préflight sortent 2 sans dossier de campagne.

**P6 — propriétaire unique.** Le `mkdir` atomique de `app/.verification.lock`
refuse une seconde CLI avant Maven. Les Maven sont lancés dans un groupe de
processus distinct. SIGTERM/SIGHUP et interruption clavier déclenchent l'arrêt
du groupe et l'attente du processus avant libération du verrou. SIGKILL laisse
un verrou bloquant : inspecter les processus avant retrait manuel. Les commandes
Maven natives doivent respecter la même exclusion ; aucun verrou coopératif ne
contrôle un écrivain externe qui le contourne volontairement.

Ce choix utilise les mécanismes existants de
[Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html)
et [Failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html),
sans framework d'exécution ni listener Java ajouté. L'ID est un marqueur de
corrélation, **pas une authentification cryptographique** : un processus disposant
des mêmes droits peut volontairement falsifier les marqueurs et le contenu.
La propriété démontrée est le rejet des anciens rapports recopiés, avec maîtrise
coopérative du producteur et des sorties ; aucune signature ne prétend résoudre
une compromission de ces droits.

## Non-régression sur la CLI réelle

Les tests livrés créent un clone jetable et appellent la CLI par subprocess.
Un wrapper d'injection documentaire délègue au **vrai wrapper Maven**, sans
monkeypatch du sélecteur ni faux succès Maven. Les fautes sont appliquées dans
ce clone seulement. Les anciens XML indépendants sont rehashés avant utilisation.

| Test | Observation exigée et démontrée |
|---|---|
| T1 — exact | L'ancien `MAVEN_BASEDIR` est refusé au préflight. Deux variantes exécutent réellement 4 H2 et recopient les mêmes cinq XML/24 cas avec mtime renouvelé : dans target partagé → INCONCLUSIVE 4/28 ; dans producteur isolé → INCONCLUSIVE 4/28 avec `Provenance mismatch`. |
| T2 — nominal | FOUNDATION canonique : PASS, 28/28, zéro failure/error/skip. |
| T3 — historique | Six XML historiques préparés dans target avant le run ; FAST foundation n'utilise que ses quatre résultats courants. Les six anciens chemins sont explicitement recensés comme non comptés. |
| T4 — incomplet | Après quatre tests réels, retrait d'un testcase et ajustement du total XML : INCONCLUSIVE 3/4, identité attendue absente. |
| T5 — interruption/échec | SIGTERM pendant l'invocation Maven : FAIL explicite ; les anciens rapports complets ne sauvent pas le verdict. Maven non nul après quatre vrais résultats frais : FAIL 4/4. |
| T6 — successif | FAST 4 puis canonique 28 ; le XML réellement produit par la première est recopié dans le producteur de la seconde : provenance refusée, zéro résultat ancien compté. IDs et espaces distincts. |
| T7 — concurrence | Première CLI propriétaire suspendue au point d'exécution ; seconde CLI refusée avant dossier/Maven. Interruption du propriétaire : non-PASS et libération après arrêt du groupe. |
| T8 — JVM implicite | `_JAVA_OPTIONS` exact de l'audit initial : refus code 2, diagnostic nominatif et aucun dossier. |
| T9 — sélection autorisée | Recette foundation filtrée 28/28 avec `forkCount=2`, `reuseForks=false` : six commandes JVM distinctes, même ID et attribution correcte. Override du producteur vers target partagé refusé avant tests. Les recettes Identity ci-dessous restent valides. |
| T10 — XML invalides | Rapport absent, tronqué, doublon à total égal, méthode inconnue, module/ID incorrects, ID absent et lien symbolique : tous non-PASS. |

**Suite CLI finale : 10 tests PASS, 83,819 s.** Les 30 tests rapides existants
restent PASS (0,726 s) après adaptation des seuls mocks de
l'exécuteur et du pipeline aux sorties isolées. Les résultats finaux détaillés,
les sorties de mise au point conservées séparément et les commandes sont dans
le dossier de preuves. Une suite précédente déjà verte a été renforcée pour
T6, les six forks, l'override et le lien symbolique ; seule la suite finale fait foi.

## Campagnes et contre-vérification des preuves

Depuis la racine, préfixe : `python3 -B scripts/testing/verify.py` ; destinations
sous `/private/tmp/pocoma-provenance-resolution/`. Les argv Maven complets avec
propriétés, sélections positives et ancres figurent dans chaque `plan.json`.

| Arguments CLI / dossier | Tests frais | Maven (s) | Découverte (s) | CLI totale (s) | Verdict |
|---|---:|---:|---:|---:|---|
| `CHANGE FOUNDATION --output …/foundation` | 28/28 | 11,196 | 1,152 | 13,232 | PASS |
| `FAST identity --output …/fast` | 65/65 | 2,293 | 1,309 | 4,432 | PASS |
| `CHANGE identity --output …/change` | 127/127 | 122,198 | 1,913 | 125,336 | PASS |
| `INTEGRATION identity --output …/integration` | 162/162 | 214,545 | 1,927 | 217,748 | PASS |

Toutes : zéro failure/error/skip, aucun écart d'identité ou de provenance.
FOUNDATION conserve `-pl infra-tx-spring -am test`. FAST conserve ses deux ancres,
CHANGE l'ancre COMMAND et INTEGRATION son ajout monolithe. Aucune gate n'est
remplacée par un niveau filtré ; les deux propriétés supplémentaires ne changent
ni phase, ni sélection, ni compilation.

Un lecteur distinct, `independent-checks.py`, **n'importe pas le collecteur**.
Il rehash les archives et tous leurs membres, reparcourt les XML et propriétés,
reconstruit `(module, FQCN, invocation)` et les multiplicités, compare plans/reçus,
puis les résumés Maven réels. Il confirme **F ⊂ C ⊂ I**, **62** puis **35** identités
supplémentaires, et l'égalité exacte aux ensembles précédemment publiés. Il ne
réutilise aucun de leurs succès pour les nouveaux verdicts. Cette relecture
indépendante de l'algorithme de collecte n'est pas un nouvel audit par une autre personne.

## Performance, Failsafe et compilation

Les références historiques étaient FAST 3,554 s, CHANGE 121,005 s,
INTEGRATION 215,805 s et FOUNDATION environ 11 s. Les nouvelles mesures sont
respectivement 2,293 / 122,198 / 214,545 / 11,196 s. Ces observations unitaires
sur des sessions différentes ne permettent pas d'attribuer leurs écarts à
l'isolation.

Une mesure à périmètre égal alterne **trois paires FAST Identity 65 tests**,
après réchauffement, dans le même clone, sans autre campagne Maven simultanée.
Le témoin exécute le sélecteur original 6d40ed17 ; l'autre le code corrigé, mêmes
sources, JDK, dépendances et classes compilées. Résultats individuels conservés :

| Mesure | Avant (s), 3 runs | Après (s), 3 runs | Écart des médianes |
|---|---|---|---|
| Processus Maven | 2,055 / 2,051 / 2,099 | 2,101 / 2,093 / 2,113 | **+0,046 s** |
| CLI totale | 3,887 / 3,882 / 3,935 | 3,981 / 3,959 / 3,961 | **+0,074 s** |
| Découverte modèle | 1,170 / 1,161 / 1,165 | 1,208 / 1,193 / 1,174 | +0,028 s |

C'est une estimation locale du coût supplémentaire, pas une borne statistique.
Aucune recompilation annoncée dans ces huit runs (deux warm-ups compris), ni
dans les quatre campagnes principales. La création/nettoyage des rapports ne
nettoie ni `target/classes`, ni `target/test-classes`, ni les artefacts Maven.
Aucun changement de compiler, cache supplémentaire, `clean`, `install` ou `-T`.

Dans le clone uniquement, ajout temporaire d'une constante à `BindingId.java` :
**44 entrées sources recompilées annoncées**, marqueur effectivement présent dans
le `.class`, FAST 65 PASS (Maven 2,689 s). Après restauration exacte de la source :
44 entrées annoncées, marqueur absent, **hash du bytecode identique à l'initial**,
FAST 65 PASS (2,714 s). Run chaud suivant : **0**, FAST 65 PASS (2,129 s).
Les 44 incluent main/test selon les événements détaillés, pas un nombre inventé
de fichiers réemployés. Le réemploi exact reste UNKNOWN dans les métriques.

L'expérience Failsafe directe exécute `test-compile failsafe:integration-test
failsafe:verify` sur les ancres FAST, avec `-Dit.test=<neuf FQCN>` et les propriétés
de provenance : **65 tests, neuf XML, trois modules producteurs**, identités
exactes et marqueurs corrects, résumé dans les répertoires isolés ; 2,424 s.
La commande complète est dans `failsafe-probe/result.json`. Cela démontre le
comportement réel du producteur multi-modules, sans prétendre que la CLI `test`
certifie un futur lifecycle Failsafe. Surefire est, lui, démontré dans toutes les
campagnes et avec six forks séparés.

Une première tentative de mesure utilisait encore le wrapper d'injection des
tests CLI : après les 65 vrais tests, il attendait une propriété absente du
sélecteur historique et retournait 1. Cette mesure **FAIL est conservée et exclue**.
Les mesures retenues restaurent le wrapper Maven original avant toutes les
alternances ; aucune campagne applicative échouée n'est transformée en succès.

## Preuves, limites et décisions conservées

[README](../testing/evidence/verification-provenance-resolution-b8df79a2/README.md),
[summary](../testing/evidence/verification-provenance-resolution-b8df79a2/summary.json),
[manifest](../testing/evidence/verification-provenance-resolution-b8df79a2/manifest.json),
[archive](../testing/evidence/verification-provenance-resolution-b8df79a2/provenance-resolution-evidence.tar.gz).
Commandes, stdout/stderr, plans, provenance, XML courants, manifestes et empreintes,
pilotes et snapshots du code y sont conservés : **2 224 membres**, archive
30 636 225 octets. Une extraction neuve et une relecture hors checkout original
retrouvent exactement les quatre résultats indépendants ; le snapshot des sources
et références suffit à cette relecture hors ligne. Avant toute extraction, vérifier
les fichiers publiés et chaque membre contre le manifeste. Le manifeste est
exclu de son propre hash ; son commit en fixe les octets.

Réserves non bloquantes pour ce périmètre : un environnement macOS/Java 21/Maven
3.9.14/Docker, caches de dépendances chauds ; aucune qualification statistique ou
sur seconde machine. Découverte JUnit volontairement restreinte aux formes
actuellement revues, pas garantie universelle pour les providers futurs ou la
forme qualifiée signalée par le contre-audit. Une nouvelle liaison Failsafe est
refusée par la CLI tant que son lifecycle/inventaire n'est pas revu. Les espaces
complets sont conservés après succès et échec ; l'auditeur archive avant nettoyage.
Aucun cache de résultats, aucune réutilisation de succès antérieurs.

**I5 inchangé : CLOSED BY HUMAN ACCEPTANCE WITH AVAILABILITY RESERVE.**
Quarantaine nominative intacte, FAIL historique i5_r12 inchangé ; aucun I5 relancé,
aucun certificat 30 s ou garantie globale 90 s, aucune homologation production.
**SPEC-10 : NOT RUN**, obligations/oracles/statuts intacts. Aucun PASS global
V3, release ou certification complète n'est revendiqué. L'adoption opérationnelle
bornée des recettes démontrées dispose de la correction et contre-vérification
requises sur la provenance ; les autres gates normatives restent dues.

Fichiers exacts de cette publication :

1. `app/pom.xml`.
2. `scripts/testing/verify.py`.
3. `scripts/testing/test_verification.py`.
4. `scripts/testing/test_verification_cli.py`.
5. `docs/testing/Reactor_Verification_Policy.md` (description technique §35 uniquement).
6. `docs/v3/Verification_AUD_VERIFY_001_Provenance_Resolution.md`.
7. `docs/testing/evidence/verification-provenance-resolution-b8df79a2/README.md`.
8. `docs/testing/evidence/verification-provenance-resolution-b8df79a2/summary.json`.
9. `docs/testing/evidence/verification-provenance-resolution-b8df79a2/manifest.json`.
10. `docs/testing/evidence/verification-provenance-resolution-b8df79a2/provenance-resolution-evidence.tar.gz`.

Publication autorisée sur `feat/v3-architecture`, commit et push sans force après
`git diff --check`, relecture du périmètre, intégrité des preuves et tests. Le
reçu final confirme le SHA local/distant, la divergence et le worktree ; aucune
preuve ni conclusion du contre-audit initial n'est réécrite.
