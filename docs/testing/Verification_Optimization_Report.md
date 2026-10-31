# Vérification proportionnée et cycle Maven — rapport de livraison

Mandat du 31 octobre 2026, branche exclusive `feat/v3-architecture`.
SHA initial local et distant : `8bc2eca60390efe1da2c82f3af7f4d78e93ddfea`.
Worktree initial propre. Les verdicts et décisions historiques L1/L2/L3 restent
inchangés. Le SHA final publié identifie le commit contenant ce rapport ; il sera
fourni avec la vérification de tête distante dans le reçu de livraison du chat.

## Vérification déclarée avant implémentation

- Impact productif attendu : NONE. Commandes/scripts de sélection et de collecte,
  inventaire et politique ; aucun changement Java, SQL, POM, dépendance, assertion,
  annotation de désactivation ou timeout. Une mutation temporaire de commentaire
  Identity est permise uniquement pour mesurer une vraie recompilation, avec
  restauration exacte avant les preuves finales.
- Slice primaire de la campagne représentative : COMMAND / Identity. Secondaires
  canoniques : NONE. Contributions supplémentaires : FOUNDATION et monolithe dans INTEGRATION Identity, déclarés par le plan.
- Risques du dispositif : sélection accidentelle, perte/contamination de preuves,
  confusion entre feedback filtré, gate canonique et certification.
- Niveau : contrôles d'outillage + FAST / CHANGE / INTEGRATION représentatifs ;
  exécution indépendante nominative de `i5_r05` et `i5_r12`.
- Architecture globale : NOT REQUIRED, aucun graphe/frontière modifié. Les contrôles structurels locaux de contrats Identity restent dans FAST ; aucun ajout du module architecture-tests.
- Full reactor : une mesure `clean install` explicitement demandée par l'extension
  de diagnostic, pas une nouvelle exigence de certification de ce chantier.
  Aucune relance intégrale pour obtenir un succès.
- DB : CURRENT pour les preuves sélectionnées ; tests V2/Flyway historiques
  éventuellement exécutés transitivement dans la mesure complète. Baseline TRUSTED
  toujours NONE ; aucune migration contournée, supprimée ou réécrite.
- Franchissement inattendu : à consigner si constaté ; impact fonctionnel interdit.

## Inventaire, provenance et coûts

Extraction reproductible, sans réexécution de tests pour mesurer l'histoire :

```sh
python3 -B scripts/testing/inventory-verification.py --output /private/tmp/pocoma-test-inventory.json
```

L'[inventaire](evidence/verification-optimization/inventory.json) contient les
classes et méthodes, résultats, durées observées, membres et hashes de l'archive
L3/I2 `canonical-final.tar.gz`, SHA testé
`49ae298d879a41b29d1caf14e7597dffb947713a`. Les sources actuelles et leurs hashes/
dépendances techniques sont un inventaire distinct, sans équivalence présumée
avec ce SHA historique. Les 75 fichiers de test actuels sans observation dans
cette archive restent **sans mesure**, pas PASS. Cette liste inclut des helpers ;
elle n'est pas une liste de 75 classes JUnit exécutables manquantes.

L'archive donne **286 suites, 1 822 tests**, 1 failure, 0 error, 1 skip ; somme des
durées de suites **2 676,157 s**. La gate complète documentée a pris **45 min 27 s**.
La somme des suites et le temps mural sont des mesures différentes.

| Classe historique | Durée de suite observée | Invocations |
|---|---:|---:|
| `V3WorkerPostgresTest` | 1 177,621 s | 21 |
| `DiscoveryPostgresTest` | 505,648 s | 49 |
| `V3WorkerResourcesPostgresTest` | 381,585 s | 12 |
| `L2ManifestProof` | 111,615 s | 142 |
| `SlotNavigationPostgresTest` | 57,300 s | 8 |
| `ConsumptionLifecyclePostgresTest` | 56,295 s | 45 |
| `EventSequencingAdministrationPostgresTest` | 47,973 s | 23 |
| `V3WorkerMetricsTest` | 36,922 s | 3 |

Les méthodes individuelles les plus coûteuses comprennent `i5_w16` (607,202 s),
`i4_p01` (287,755 s), `i5_w14` (149,914 s) et `i5_r12` (95,825 s).
La source de `i5_w16` impose deux fenêtres idle de cinq minutes, avec observations
CPU/pools : son coût de dix minutes est intentionnel. `i4_p01` parcourt les volumes
100/1 000/10 000 et les proportions locales 0/25/100 sur les familles physiques,
avec plans SQL et oracles de coût. Ce sont des preuves capacité/performance à
conserver aux gates qui les exigent, sans les payer pour une transition Identity.

Les trois premières concentrent **77,16 %** du temps des suites de cette archive.
Ces campagnes restent utiles : Discovery/récupération, disponibilité/renouvellement
et séparation des ressources. Leur coût ne démontre aucune défaillance.
Les classes PostgreSQL démarrent des containers/fixtures isolés et plusieurs
classes Spring initialisent leurs contextes ; les journaux frais permettent de
compter ces démarrages, sans inventer une décomposition CPU/SQL/startup non profilée.
Aucun partage de contexte/container n'est introduit entre oracles indépendants.

Le [graphe statique](evidence/verification-optimization/module-graph.json) recense
60 modules. Son parcours des arêtes directes, y compris test/optional, couvre 50
modules depuis COMMAND/monolithe et 60 depuis architecture-tests. L'ordre réel
Maven dans chaque journal demeure l'autorité, notamment pour les dépendances
optionnelles. Cela explique pourquoi ajouter systématiquement architecture-tests
ne constitue pas une vérification locale bon marché. Aucune dépendance n'a été
retirée pour gagner du temps. Maven signale une déclaration dupliquée préexistante
de runtime-task-consumption-worker dans le POM architecture-tests. Elle n'est pas
corrigée ici : aucun goulot mesuré ne justifie une restructuration du graphe et
la gate structurelle supplémentaire qu'elle imposerait.

## Diagnostic du cycle de construction

Les POM du projet ne configurent ni générateur de sources spécifique ni chemins de
processeurs d'annotations à optimiser. Les goals observés de compilation et de
ressources sont Maven Compiler 3.14.1 et Resources 3.3.1 ; leurs descripteurs locaux
déclarent les goals utilisés thread-safe, relevé dans
[compile-plugin-safety.json](evidence/verification-optimization/compile-plugin-safety.json).
L'essai `-T 2` reste limité à `compile`, jamais aux campagnes PostgreSQL.

Spring Boot repackage est activé pour les exécutables au packaging. Il est utile
pour une distribution, superflu pendant une transition métier locale. De même,
`install` écrit le dépôt local sans être nécessaire à une fermeture `-pl ... -am`.
L'optimisation choisie est d'arrêter le lifecycle à la phase utile et de garder le
Reactor incrémental ; aucun contrôle de packaging n'est supprimé d'une certification.
Les exécutions `package/install -DskipTests` sont des mesures de construction sans
preuve d'exécution ; l'essai `maven.test.skip=true` retire aussi la compilation des
tests et reste identifié comme tel.

Les ancres multiples d'une campagne partagent une seule fermeture : les modules
amont ne sont pas exécutés une fois par ancre. Aucune redondance d'oracle n'est
supprimée sur la seule ressemblance des noms ou du SQL. Les démarrages de fixtures
et les variantes physiques gardent leur isolation. Aucun nouveau cache, artefact
préinstallé d'un autre SHA ou contournement de migration n'est introduit.

## Découpage et garanties

La [politique §35](Reactor_Verification_Policy.md#35-operational-verification-and-build-cycle--fast--change--integration--certification)
porte les niveaux, la matrice des onze risques, l'élargissement des slices, les
commandes et la convention de rapport. Le registre unique des recettes et de la
quarantaine est [verification-campaigns.json](../../scripts/testing/verification-campaigns.json).
Aucun nouveau profil Maven ni tag JUnit, aucun second référentiel SPEC-10.

| Recette Identity | Classes prévues | Invocations prévues | Justification |
|---|---:|---:|---|
| FAST | 9 | 65 | Modules locaux engine-consume-registration/domain-pot ; transitions/valeurs Identity, contrat/exécution applicative et invariants Pot/invitation adjacents, sans PG |
| CHANGE | 18 | 127 | FAST + autorité/concurrence/schéma/privilèges/exécution/Discovery/publication Identity + vraie composition Worker |
| INTEGRATION | 26 | 162 | CHANGE + monolithe, composition Worker/Pot et transactions/fondations PG |

Ces nombres sont ceux du plan contrôlé par sources, y compris les deux variantes
boolean d'IdentityTransitionsTest ; les résultats frais seront comparés aux
méthodes attendues. Une méthode nouvellement ajoutée dans une classe sélectionnée
est incluse ; une nouvelle classe requiert revue et ajout explicite. Une forme
JUnit non prise en charge arrête la sélection plutôt que masquer ses variantes.

Ces recettes couvrent le mécanisme Identity interne actuellement livré. Elles ne
certifient ni le consentement externe ID-02, ni les parcours HTTP futurs, ni L4,
ni les invariants de capacité/recovery L2 inchangés. Les changements partagés
transactionnels, sécurité, publication, schéma, claims/fencing/recovery ou
architecture élargissent les preuves selon le contrat réel. Les gates non filtrées
exigées par un lot restent dues, même après un PASS de recette.

CHANGE/INTEGRATION sur les identifiants canoniques uppercase construisent les
commandes non filtrées historiques. CERTIFICATION construit exactement le full
`./mvnw --batch-mode --no-transfer-progress test`. Aucun POM n'étant changé, la
population Surefire historique, notamment les deux méthodes quarantainées et
`L2ManifestProof`, est conservée. La certification externe SPEC-10 reste distincte
de ce seul build Maven.

## Diagnostic nominatif de quarantaine

Les deux méthodes sont dans
`runtime-command-consumption-worker/.../v3/V3WorkerResourcesPostgresTest.java`.
`i5_r05` appelle `I5TemporalV2Postgres.campaign(PG, 1, 1)` ; `i5_r12` appelle
`campaign(PG, 2, 32, true)`. Ce sont des campagnes temporelles et de ressources,
avec leurs vrais seuils/oracles inchangés, pas deux assertions équivalentes.

I1 a observé les deux FAIL ; I2 a observé `i5_r05` PASS en 52,451 s et `i5_r12`
FAIL en 95,825 s. Pour ce dernier, la signature conservée est
`runtime condition deadline ==> expected: <true> but was: <false>`, via
`V3WorkerPostgresTest.Runtime.close:553` et `I5TemporalV2Postgres.campaign:947`.
Les gates I3 ultérieures ont également conservé ce FAIL. L'analyse établit le
chemin de fermeture concerné, pas sa cause physique définitive ; aucune causalité
Identity ni simple erreur de test n'est déduite.

Le mandat présent autorise leur isolation nominative opérationnelle. Les commandes
QUARANTINE les exécutent séparément ; les recettes Identity ne sélectionnent pas
les autres campagnes Worker/Discovery car leurs contrats ne sont pas modifiés.
Aucune exclusion de classe n'est installée, ni pour les frères de ces méthodes,
ni pour la certification. Une nouvelle entrée et toute réintégration demandent
la décision explicite et les preuves décrites dans la politique. Un PASS isolé ne
lève pas les réserves historiques de disponibilité L2.

## Mesures de compilation et de lifecycle

Toutes les durées suivantes sont des temps muraux du **processus Maven**, en
secondes ; elles ne comprennent pas la préparation/archivage du collecteur.
« Propre » signifie sorties `target` nettoyées, avec cache de dépendances Maven
local déjà disponible ; aucun cache réseau vierge n'est revendiqué. Les mesures
initiales ont eu lieu avant la pause, les compléments après reprise : ne pas
attribuer leurs écarts à un changement de compilateur ou à une charge identique.
JDK Temurin 21, Maven Wrapper 3.9.14, macOS aarch64 ; versions effectives dans
chaque provenance, PostgreSQL 17.11 dans les journaux des fixtures.

Préfixe commun exact depuis `app/` :
`./mvnw --batch-mode --no-transfer-progress`. `C` dans ce tableau représente les
arguments exacts `-pl runtime-command-consumption-worker -am` ; `L` représente
`-pl engine-consume-registration,domain-pot -am`. Ces alias sont seulement
rédactionnels, pas de nouveaux modules ou profils.

| Arguments après le préfixe | Contexte | Durée | Résultat |
|---|---|---:|---|
| `clean install` | Reconstruction complète initiale, avec tests | 3 079,751 s | FAIL `i5_r12` ; 1 867 tests, 1 failure, 0 error, 1 skip |
| `compile` | Complet, chaud après la reconstruction interrompue par FAIL | 5,756 s | SUCCESS |
| `test-compile` | Complet, chaud | 10,454 s | SUCCESS, pas d'exécution de tests |
| `package -DskipTests` | Complet, compilation tests permise | 11,419 s | SUCCESS, aucun PASS de tests |
| `install -DskipTests` | Complet, installation locale | 11,985 s | SUCCESS, aucun PASS de tests |
| `C compile` | COMMAND/Identity, chaud | 4,796 s | SUCCESS |
| `C test-compile` | COMMAND/Identity, chaud | 8,315 s | SUCCESS |
| `C compile` | Nouvelle mesure sans modification | 4,872 s | SUCCESS |
| `-T 2 C compile` | Même périmètre, compilation seulement | 4,403 s | SUCCESS ; essai unique, option non activée par défaut |
| `clean compile` | Complet, sorties propres, après reprise | 13,133 s | SUCCESS |
| `compile` | Complet chaud, après reprise | 8,886 s | SUCCESS |
| `C clean compile` | Fermeture COMMAND propre | 10,365 s | SUCCESS |
| `C compile` | Fermeture COMMAND chaude sans changement | 7,734 s | SUCCESS |
| `C compile` | Après ajout contrôlé d'un commentaire Identity | 7,768 s | SUCCESS, 22 modules / 452 sources recompilés (recompte AUD-VERIFY-004) |
| `C compile` | Après restauration exacte des octets source | 8,244 s | SUCCESS |
| `package -Dmaven.test.skip=true` | Complet, sans compilation ni exécution des tests | 12,017 s | SUCCESS, pas une preuve de tests |
| `C test-compile` | Fermeture COMMAND après nettoyage | 14,993 s | SUCCESS |
| `L clean compile` | Feedback local propre | 4,113 s | SUCCESS, 10 modules Java / 225 sources |
| `L compile` | Feedback local chaud sans changement | 3,166 s | SUCCESS |

La compilation propre complète a annoncé 59 compilations de modules Java et
757 sources en entrée du compilateur. Ces totaux corrigés incluent les 14 lignes
singulières `Compiling 1 source file` omises par l'agrégat initial de 743 ; le
recompte COMMAND après mutation corrige également 448 en 452. Les JSON et logs
historiques restent conservés comme observations originales ; leurs anciens
agrégats sont remplacés par le [recompte de résolution](../v3/Verification_Rationalization_Resolution.md).
Ces annonces ne dénombrent ni les sources présentes ni le réemploi exact de chaque fichier. La mutation de
mesure, son hash original/modifié/restauré et les commandes figurent dans
[incremental-mutation.json](evidence/verification-optimization/incremental-mutation.json),
[compilation-observations.json](evidence/verification-optimization/compilation-observations.json),
[baseline-builds.json](evidence/verification-optimization/baseline-builds.json) et
[followup-builds.json](evidence/verification-optimization/followup-builds.json).
L'identité des hashes original et restauré et le diff final app/ nul sont vérifiés.

À commandes et périmètres chauds égaux, `compile` complet passe de 5,756 à 8,886 s,
et `C compile` sans modification de 4,872 à 7,734 s. **Aucune accélération intrinsèque
Maven n'est démontrée ni revendiquée.** L'essai parallèle unique économise 0,469 s
sur son témoin immédiat, sans établir un gain stable ; aucun `-T` n'est imposé.
Le gain quotidien exploitable est le choix de phase et de fermeture : feedback
local propre à 4,113 s au lieu de reconstruire des modules indépendants, et tests
Identity sélectionnés au lieu de la robustesse/capacité L2 inchangée. Ces preuves
ne sont pas interchangeables.

## Vérifications fraîches et commandes exécutées

Les deux pilotes de mesure réellement exécutés sont conservés byte-for-byte dans
[measurement-drivers.tar.gz](evidence/verification-optimization/measurement-drivers.tar.gz) :

```sh
python3 -u /private/tmp/pocoma-measure-build.py
python3 -u /private/tmp/pocoma-measure-followup.py
python3 -B -m unittest scripts/testing/test_verification.py
python3 -B scripts/testing/verify.py CERTIFICATION --dry-run
```

Les pilotes gardent les chemins exacts de l'expérience. Pour les reproduire,
adapter racine et destinations vers de nouveaux répertoires ; pour l'usage normal,
préférer les commandes de la politique. Les reçus `plan.json` donnent toutes les
sélections Maven sans abréviation, `provenance.json` les entrées et outils,
`result.json` les méthodes réellement exécutées, diagnostics et durées, `raw.tar.gz`
les XML/logs/traces. Chaque campagne a sa propre sortie : aucune addition de
comptes dupliqués entre FAST, CHANGE et INTEGRATION ne devient une couverture unique.

| Campagne | Tests / failures / errors / skips | Temps Maven | Verdict |
|---|---|---:|---|
| [FAST Identity](evidence/verification-optimization/fast/result.json) | 65 / 0 / 0 / 0 | 6,686 s | PASS |
| [CHANGE Identity](evidence/verification-optimization/change-identity/result.json) | 127 / 0 / 0 / 0 | 144,100 s | PASS |
| [INTEGRATION Identity](evidence/verification-optimization/integration-identity/result.json) | 162 / 0 / 0 / 0 | 216,263 s | PASS |

FAST construit 11 projets Maven parent inclus. CHANGE utilise COMMAND ;
INTEGRATION compose COMMAND et monolithe en une seule invocation. Toutes les
méthodes attendues sont présentes exactement une fois, aucun test inattendu ni
skip dans ces trois campagnes. Les tests de contrats locaux vérifient notamment
les opérations scellées, les interfaces et les composants publics des records.
Architecture globale reste NOT REQUIRED ; aucun module architecture-tests ajouté.
Les campagnes L2 lourdes non sélectionnées sont nominativement traçables dans les
plans et restent NOT RUN dans ces recettes, jamais PASS.

### Avant/après à périmètre de tests comparable

Les ensembles exacts classe/méthode de chaque recette ont été retrouvés dans la
mesure complète initiale, avec mêmes sources applicatives. Voici la **somme des
durées de leurs suites**, distincte du temps mural de la gate complète :

| Même ensemble de méthodes | Dans la reconstruction initiale | Dans la recette sélectionnée |
|---|---:|---:|
| FAST, 65 invocations | 0,268 s | 0,444 s |
| CHANGE, 127 invocations | 139,796 s | 127,693 s |
| INTEGRATION, 162 invocations | 221,485 s | 204,606 s |

[Comparaison contrôlée](evidence/verification-optimization/comparable-test-observations.json).
Ordre, échauffement JVM/contextes et charge hôte peuvent différer : ces observations
ne prouvent pas une accélération des tests eux-mêmes. Le temps mural de 51 min 20 s
et celui d'une recette de 6,686 s ne sont **pas** deux mesures de la même garantie.

### Contrôles V1–V7

- V1 : PASS de sélection pour les trois recettes, comptes exacts et zéro écart ;
  les identifiants canoniques construisent les commandes non filtrées, contrôlées
  par les tests du sélecteur.
- V2 : FAST réellement exécuté, 65/0/0/0, 6,686 s.
- V3 : CHANGE Identity réellement exécuté, 127/0/0/0 ; droits, atomicité,
  publication et vraie composition présents, robustesse L2 non déclenchée.
- V4 : INTEGRATION réellement exécuté, 162/0/0/0 ; transactions PostgreSQL,
  monolithe et compositions voisines. Gardes structurelles locales applicables
  dans FAST ; gate architecture globale non requise par ce diff d'outillage.
- V5 : sélections nominatives indépendantes, résultats réels consignés ci-dessous.
- V6 : préservation démontrée par POM/tests inchangés, sélection CERTIFICATION sans
  filtre avec deux méthodes obligatoires, et présence effective de `i5_r05`/
  `i5_r12` dans le diagnostic complet. La commande du script CERTIFICATION est
  NOT RUN : aucune seconde exécution globale uniquement pour ce chantier.
- V7 : aucun Java/POM/SQL/test applicatif/timeout modifié dans le diff final ;
  hashes de 1 262 entrées applicatives initiaux comparés, restauration exacte de
  la seule mutation temporaire, aucune désactivation ni assouplissement d'assertion.

Les **13 tests d'outillage PASS** sont conservés dans
[selector-tests.txt](evidence/verification-optimization/selector-tests.txt) :
absence/ambiguïté de source, mauvais scope, sélection cumulative, quarantaine
précise, full non filtré, méthodes/XML absents, inattendus ou dupliqués, variantes
paramétrées, XML mal formé/compteurs incohérents, skip et vrai FAIL. Le collecteur
préserve les anciens XML hors comptage ; leurs noms restent dans les résultats.
Les preuves physiques sont conservées, sans prétendre que le succès du collecteur
certifie automatiquement toutes les variantes normatives.

## Quarantaine exécutée et statut de certification

| Scénario indépendant | Tests / failures / errors / skips | Durée méthode | Temps Maven | Verdict |
|---|---|---:|---:|---|
| [i5_r05](evidence/verification-optimization/quarantine-i5_r05/result.json) | 1 / 0 / 0 / 0 | 55,678 s | 66,345 s | PASS |
| [i5_r12](evidence/verification-optimization/quarantine-i5_r12/result.json) | 1 / 1 / 0 / 0 | 96,974 s | 107,702 s | FAIL |

Chaque sélection a exécuté exactement sa méthode, sans test inattendu. `i5_r12`
conserve `runtime condition deadline ==> expected: <true> but was: <false>` dans
le chemin de fermeture. Aucun retry, timeout, assertion ou writer modifié. Ces
exécutions valident l'isolation technique ; elles ne lèvent aucune réserve L2.
Le diagnostic physique approfondi et la correction de cette disponibilité restent
à réaliser séparément avant réintégration qualifiée.

Le [diagnostic intégral](evidence/verification-optimization/baseline-summary.json)
`clean install` est **FAIL**, 1 867/1/0/1 en 3 079,751 s ; `i5_r05` y est PASS en
53,569 s et `i5_r12` FAIL en 94,931 s. Le skip exploratoire est
`TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness` :
`System property [step3b.measure] does not exist`. Architecture est SKIPPED après
l'échec COMMAND. Ce skip n'est pas transformé en PASS et aucun PASS canonique
Architecture n'est revendiqué. Les temps de tous les 61 projets Maven, parent
inclus, sont conservés dans `baseline-summary.json` ; PRIMARY a pris 18:19 et
COMMAND 29:44 dans ce diagnostic.

**Gate Maven intégrale diagnostique : FAIL. Certification SPEC-10/release :
NOT RUN.** La commande du nouveau script CERTIFICATION est NOT RUN ; sa sélection
[certification-selection.json](evidence/verification-optimization/certification-selection.json)
est contrôlée sans filtre et exige les deux scénarios dans les résultats frais.
Les campagnes normatives non applicables à ce delta d'outillage ne sont pas
exécutées pour fabriquer une certification. Aucun jalon G0/G1/G2/G3 ni statut
historique L1/L2/L3 n'est modifié.

## Conservation, contrôles finaux et publication

Les archives fraîches ont toutes été ouvertes et leurs membres rehashés contre
les reçus : [archive-checks.json](evidence/verification-optimization/archive-checks.json).
Les cinq archives contiennent respectivement 18, 123, 151, 5 et 5 artefacts
XML/texte/traces cibles, en plus du journal Maven. Les XML/logs du diagnostic
complet sont dans `baseline-builds.tar.gz`, les traces physiques séparées dans
`baseline-physical.tar.gz` avec leur inventaire ; aucune trace postérieure à la
fin du diagnostic ne s'y est glissée. Les logs des mesures de compilation sont
dans `followup-builds.tar.gz`. Les copies de travail de logs et anciens XML ont
été déplacées hors checkout après vérification de leur conservation ; aucun
artefact officiel unique n'a été supprimé. Aucun encodage historique n'est modifié.

La liste **exacte** des fichiers livrés et leurs empreintes est
[delivery-files.json](evidence/verification-optimization/delivery-files.json).
Elle inclut la politique mise à jour, ce rapport, les quatre fichiers de sélection/
inventaire/tests et les preuves nouvelles ; aucune modification étrangère au
périmètre. Les checks finaux et l'identité des entrées sont dans
[delivery-checks.json](evidence/verification-optimization/delivery-checks.json).
Le manifeste ne s'auto-attribue pas une empreinte de lui-même.

Commandes finales de contrôle/publication :

```sh
git diff --check
git diff --cached --check
git status --short --branch
git diff --cached --stat
git commit -m "build: add proportionate verification campaigns and Maven measurements"
git push origin feat/v3-architecture
git ls-remote origin refs/heads/feat/v3-architecture
git rev-list --left-right --count HEAD...origin/feat/v3-architecture
```

Publication autorisée par le mandat, sans force. Le SHA réellement créé et la
comparaison distante après push sont fournis par le reçu final du chat ; aucun
SHA autoréférent n'est inventé dans ce rapport.

Bilan d'implémentation : impact productif attendu/observé **NONE** ; slices de
preuves déclarées **COMMAND/Identity**, contributions **FOUNDATION/monolithe** ;
franchissement inattendu **NONE**. Architecture globale **NOT REQUIRED / NOT RUN**,
module global SKIPPED dans le diagnostic complet. Full reactor **RUN une fois**
pour le diagnostic explicitement demandé, **FAIL**, pas de relance globale.
DB **CURRENT**, avec migrations historiques exécutées transitivement dans le
seul diagnostic non filtré ; baseline TRUSTED **NONE**. Les scripts de sélection
ne remplacent pas les collecteurs de lots ni leurs traces/oracles physiques.

Réserves résiduelles : `i5_r12` FAIL non résolu ; historique `i5_r05` non effacé ;
acceptations et disponibilité L2 inchangées ; aucun certificat global ni
qualification ID-02 externe/L4/HTTP/performance de release. Les mesures sont des
observations uniques, pas des garanties statistiques. Le gain démontré est une
boucle de feedback proportionnée et mesurée, sans affaiblissement normatif.
