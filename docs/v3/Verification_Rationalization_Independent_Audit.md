# Pocoma V3 — Audit indépendant des campagnes et de la rationalisation Maven

Audit du 31 octobre 2026. **Verdict global : CHANGES REQUIRED.** Un faux PASS
canonique est reproduit, sans modification d'un input applicatif. Les campagnes
Identity livrées sont, en revanche, confirmées dans leur périmètre borné.
Aucune correction n'est engagée par cet audit.

## 1. Références, indépendance et périmètre

- Dépôt : `kartaguez/pocoma`, branche `feat/v3-architecture`.
- Référence initiale : `8bc2eca60390efe1da2c82f3af7f4d78e93ddfea`.
- **SHA audité : `d0927d9410ebcd65c221e73c5ecd04e5242fe4e9`.**
- Le `git ls-remote` réel confirme ce SHA sur la branche distante, avant audit
  et avant publication. Divergence locale/tracking initiale : **0/0** ; worktree propre.
- Diff exact : **46 fichiers**, 36 070 insertions, aucun changement sous `app/`.
  Les 46 chemins, tailles et hashes sont recomposés dans `git-and-files.json`.
  Les 45 empreintes non autoréférentes du manifeste de livraison concordent ;
  son entrée propre a explicitement une empreinte nulle, sans anomalie.
- Reproduction dans le worktree détaché neuf
  `/private/tmp/pocoma-verification-audit-d0927d94`, créé depuis le SHA audité.
  Aucun `target` préalable ; les dépendances Maven locales restent disponibles.
  Aucun worktree préexistant n'est modifié.
- Publication : uniquement les nouveaux documents et preuves listés au §10.
  Le SHA du commit contenant ce rapport est communiqué dans le reçu de livraison
  et retrouvable par `git log -1 --format=%H -- docs/v3/Verification_Rationalization_Independent_Audit.md`.
  Un SHA autoréférent n'est pas inscrit fictivement dans son propre contenu.

Référentiel confronté : [politique Reactor](../testing/Reactor_Verification_Policy.md),
[dossier de conservation](../testing/Certification_Evidence_Rationalization.md),
[SPEC-10](SPEC-10_Test_Certification.md), [canon](V3_Canon_and_Roadmap.md),
[plan directeur](V3_Implementation_Master_Plan.md),
[rapport de livraison](../testing/Verification_Optimization_Report.md), ses JSON,
les neuf nouvelles archives, les quatre fichiers sous `scripts/testing/`,
les POM et les sources des tests sélectionnés et des scénarios I5.
Le diff complet est conservé dans la preuve indépendante, pas seulement son résumé.

**Déclaration préalable de vérification :** audit d'outillage et de documentation,
contribution locale COMMAND/Identity pour FAST, contrôles statiques des
contributions FOUNDATION/monolithe. FOUNDATION est ajouté explicitement pour le
contre-exemple du collecteur ; aucune évolution de production ni frontière
architecturale n'est autorisée. Architecture globale **NOT REQUIRED / NOT RUN** ;
full reactor **NOT REQUIRED / NOT RUN par l'audit**. DB : aucune migration ni
campagne PostgreSQL complète relancée. Un premier probe FOUNDATION filtré échoue
à l'accès Docker ; il reste conservé comme erreur environnementale, sans PASS déduit.

## 2. Résultats indépendants et limites probatoires

Les fichiers cités par leur nom court ci-dessous sont dans l'[archive indépendante](../testing/evidence/verification-independent-audit-d0927d94/audit-evidence.tar.gz),
indexée et rehashée par son [manifeste](../testing/evidence/verification-independent-audit-d0927d94/manifest.json).
Les preuves originales sont également toujours accessibles dans
[verification-optimization](../testing/evidence/verification-optimization/).

| Contrôle | Résultat indépendant |
|---|---|
| Intégrité des 46 fichiers livrés | Hashes conformes ; JSON lisibles ; aucun input applicatif changé dans le diff |
| Cinq archives de campagnes | Tous les membres annoncés rehashés ; XML reparsés avec un lecteur distinct du collecteur livré |
| FAST / CHANGE / INTEGRATION | Respectivement 65 / 127 / 162, zéro failure/error/skip ; aucune méthode manquante, inattendue ou dupliquée |
| Plans livrés → plans reconstruits depuis les sources publiées | Identiques pour les cinq campagnes |
| Diagnostic complet archivé | 1 867 / 1 failure / 0 error / 1 skip ; hashes XML concordants |
| Artefacts physiques du diagnostic | **1 287/1 287** membres conformes au manifeste, aucun membre supplémentaire |
| Archives restantes | Les neuf TAR gzip ouverts, fichiers lus et rehashés ; aucun membre de fichier dupliqué par nom |
| Inventaire historique régénéré | Identique au JSON livré : 286 suites, 1 822 invocations ; 75 sources sans observation, helpers compris |
| Tests du sélecteur relancés | **13 PASS**, 0,292 s pour unittest |
| FAST depuis checkout neuf | **65 PASS**, zéro skip/écart ; processus Maven **3,676 s**, temps interne Maven 3,096 s |
| Probe canonique FOUNDATION avec filtre JVM implicite | **Faux PASS reproduit : 4/28 tests, zéro skip, aucune issue déclarée** ; AUD-VERIFY-001 |
| Compilation locale inchangée, Maven `-X` | Recompilation indue identifiée nominativement ; AUD-VERIFY-002 |

Les campagnes de livraison portent le HEAD initial avec outillage non encore
commité. Les hashes du sélecteur/configuration concordent avec le SHA final ;
les entrées `app/` sont inchangées entre les deux commits. Dans le nouveau
checkout, seul `app/mvnw.cmd` diffère en octets des reçus : conversion LF→CRLF de
checkout, exactement réversible et conforme au blob Git initial/final. Les autres
1 261 hashes applicatifs concordent directement. Le wrapper Windows n'est pas
exécuté par ces commandes. Ce détail n'est pas une divergence de code testé.

**Non exécuté par l'audit :** CHANGE/INTEGRATION PostgreSQL complets, I5, full
reactor, gate Architecture, packaging, installation de distribution, Bruno/k6 et
protocoles SPEC-10 externes. Les XML, logs, hashes et sources suffisent pour établir
ce qui a été exécuté dans la livraison, ses ensembles et ses verdicts. Ils ne
reproduisent pas ses comportements PostgreSQL sur un second environnement et ne
qualifient pas la stabilité temporelle ni des performances statistiques. La
confiance est forte sur la reconstruction et sur FAST ; elle reste celle d'une
contre-inspection de preuves pour les autres exécutions applicatives.

## 3. Couverture et ensembles effectivement exécutés

L'[annexe des ensembles](Verification_Rationalization_Test_Sets.md) énumère les
**162 identifiants classe#méthode**, chaque classe, sa source et son premier niveau.
`sets.json` et les trois `*-xml-reconstruction.json` donnent les listes exactes
provenant des XML, avec les deux invocations boolean distinguées.

| Ensemble | Classes | Invocations | Différence exacte |
|---|---:|---:|---:|
| FAST, F | 9 | 65 | — |
| CHANGE, C | 18 | 127 | C∖F : 62 ; F∖C : **0** |
| INTEGRATION, I | 26 | 162 | I∖C : 35 ; C∖I : **0** |

**F ⊂ C ⊂ I est démontré par les identifiants réellement exécutés**, pas par les
seuls compteurs. Chaque méthode apparaît une fois par campagne. Leur union vaut
162 invocations distinctes, et non 354. Le diagnostic initial contient également
ces mêmes identifiants ; cela ne rend pas ses garanties interchangeables.

### FAST

Les neuf classes ne sont pas neuf smoke tests : elles portent 65 invocations,
dont transitions strictes Registration/Link/Unlink, conservation de l'identité,
versions et états historiques, rejet des tokens obsolètes, renouvellement,
Unlink de soi/de la dernière EUI, perte d'autorité après premier Unlink croisé,
ordre UTF-8 canonique, clés opaques et formes invalides. Les contrats de requête
vérifient intention/provenance immuables et résultat fermé ; le client ne choisit
pas le User et le résultat ne divulgue pas son token/User.

Les gardes architecturales sont **locales** : réflexion sur opérations scellées,
interfaces et composants publics des records, notamment
`IdentityRequestContractTest:161–180`. Ce ne sont pas les règles globales ArchUnit
de dépendances, d'autorités ou de publication. Les invariants Pot adjacents
incluent droits, transitions, auto-rattachement et collision de Shareholder.

La sélection positive exclut tous les PG, génération physique du token,
transactions SQL, runtimes et architecture globale. `BindingTokenGeneratorTest`
est inclus dès CHANGE. Les anciennes migrations/adapters Identity V2 et
`V3IdentityAuthorityArchitectureTest` restent disponibles hors recette. Aucun test
Identity V3 existant pertinent au périmètre **pur** annoncé n'a été trouvé omis.
Un nouvel invariant dans une nouvelle classe exige néanmoins son ajout manuel ;
FAST ne constitue aucune certification complète ID-01/02 ou sécurité externe.

### CHANGE

Aux 65 invocations s'ajoutent génération cryptographique/encodage du token,
autorité/histoire, concurrence, schéma, privilèges, exécution, Discovery,
publication et composition réelle du Worker : **62 invocations supplémentaires**.

Les sources contiennent des oracles concrets : courses Registration/Link et
Unlink croisés, absence d'orphelin, collisions et unicité SQL ; verrouillage
canonique jusque dans les clés Unicode ; contraintes invalides, FK et vérifications
différées ; rollback aux différentes frontières ; fencing, horloge au verrou final,
commit inconnu et observation corrompue. Les privilèges effectifs et les refus
READ indirects, logging/JPA/autorités auxiliaires sont testés, au-delà d'un mock
d'autorisation. Les fixtures sont PostgreSQL réelles, avec leurs XML et traces.

`V3IdentityRequestCompositionPostgresTest:433` démarre la racine Worker et fait
passer des demandes durables par les boucles réelles, vérifie leurs effets,
draine puis redémarre sans remise à zéro. CHANGE n'est donc pas seulement un
ensemble de tests de repository. Il ne couvre toutefois ni tout COMMAND, ni
l'ensemble Discovery/Worker L2, ni les parcours HTTP/consentement L5.

### INTEGRATION

Les **35 invocations supplémentaires** sont : monolithe Identity (5), composition
Worker (5), composition Pot Command (1), AttemptTransactions (9), transactions
foundation (6), intervalles (4), isolation de schéma (2), composition foundation (3).

La racine monolithe exécute effectivement Registration/Link/Unlink et la reprise
(`V3IdentityRequestMonolithPostgresTest:432`), avec les refus de configuration et
d'exposition. Les compositions voisines raccordent le participant Pot et le
manager WRITE ; les tests de transaction observent pid/xid physiques, invisibilité
avant commit, poisoning après erreur attrapée, rollback JPA/JDBC, interdiction
REQUIRES_NEW/nested/autorité étrangère et distinction commit confirmé/inconnu.
Ce sont des garanties supplémentaires d'assemblage et de frontières physiques,
pas seulement 35 unités sans interactions.

La composition Pot utilise encore une fixture Identity pour son contrat adjacent ;
elle ne prouve pas à elle seule le futur raccord R-ID-POT réel I4. Les projections
métier L4, assemblages distribués qualifiés, exec jar de release, charge/récupération
L2 exhaustive et disponibilité temporelle I5 restent hors de cette recette.

### Exclusions et passage au niveau supérieur

Aucune exclusion permanente de POM ni annotation n'est ajoutée. `-Dtest` est une
liste positive et `failIfNoSpecifiedTests=false` autorise les modules amont sans
classe correspondante ; pour les recettes Identity, la comparaison aux méthodes
attendues compense ce masquage Maven. Ce garde-fou est incomplet sur les commandes
canoniques sans inventaire : voir AUD-VERIFY-001.

Les plans énumèrent les sources non sélectionnées de **tout le dépôt**, pas un
inventaire sémantique des tests applicables à chaque risque. Parmi les reports
explicites : `DiscoveryPostgresTest`, `ConsumptionLifecyclePostgresTest`,
`V3WorkerPostgresTest`, `V3WorkerResourcesPostgresTest`, `V3WorkerMetricsTest` et
`L2ManifestProof`, les PG READ/projections et l'architecture globale. Leur absence
de la recette n'est justifiée que si leur contrat reste inchangé.

Pour la suite L3 : transition pure → FAST ; comportement Identity interne →
CHANGE et gates de lot applicables ; raccords/transaction/composition partagés →
INTEGRATION avec les **consommateurs réellement affectés**, sans supposer que la
recette Identity suffit. Claims, leases, recovery, publication transverse ou SQL
partagé imposent leurs entrelacements propres, y compris I5 si son contrat change.
Dépendances/modules/frontières/isolation modifiés → gate Architecture non filtrée.
Clôture majeure/intégration main/release → full et protocoles normatifs applicables.

## 4. Généricité : acquis démontrés et extension future

| Famille | Dispositif réellement présent | Niveau de démonstration |
|---|---|---|
| FOUNDATION | Ancre `infra-tx-spring`, CHANGE/INTEGRATION natifs ; 24 invocations sélectionnées dans I, 4 H2 présents au canonique | Sélection reconstruite ; contre-exemple canonique réellement exécuté, pas une qualification complète de slice |
| COMMAND hors Identity | Ancre native, fermeture Maven ; composition Pot incluse dans I | Commande non filtrée reconstruite ; aucune recette FAST générale démontrée |
| Consumption / Discovery | COMMAND/EVENT et autres consommateurs composables ; matrice risques leases/claims/recovery | Généricité de commande démontrée ; suites spécifiques à choisir par revue, pas de recette filtrée Discovery livrée |
| READ / Projections | PROJECTION et propriétaires READ à ajouter selon le changement | Ancres disponibles ; aucune couverture L4 inférée de Identity |
| MONOLITH | `runtime-monolith` dans INTEGRATION Identity ; ancre native possible | Assemblage Identity observé ; pas d'identifiant CLI `MONOLITH` canonique |
| ARCHITECTURE | Commande native `-pl architecture-tests -am test` conservée | Disponible, volontairement hors registre des slices locales et hors recette ; non exécutée ici |

Les sept ancres sont centralisées ; la fermeture découle de Maven et plusieurs
ancres partagent un reactor. Les dry-runs CHANGE/INTEGRATION de toutes les ancres
et d'une composition COMMAND/EVENT sont recomposés sans filtre. C'est une
infrastructure de **commandes générique**, avec une **seule famille de recettes
filtrées démontrée : Identity**. Les noms `identity`, ses modules et son expansion
cumulative sont codés dans Python ; une deuxième recette suppose actuellement
une évolution du sélecteur, pas seulement une entrée JSON. Cette limite est
explicitement annoncée et n'impose pas d'implémenter toutes les slices maintenant.

La matrice des onze risques fournit les bons déclencheurs transverses. Elle
n'analyse pas automatiquement un diff, et ne peut remplacer le raisonnement
sur les consommateurs. L'absence de `-amd` automatique est appropriée.

## 5. Maven, fraîcheur et performances

Le diff ne change aucun POM, plugin, profil, dépendance ni compiler. Il introduit
un choix de phases, des sélections et des mesures. `-pl` choisit les ancres,
`-am` reconstruit leurs producteurs ; les consommateurs ne sont inclus que par
leurs ancres explicites. Il n'y a aucun `-amd`, `clean` ou `install` implicite.
FAST reconstruit 11 projets parent inclus et 225 sources productives depuis
checkout neuf ; il ne dépend pas de classes déjà présentes dans ce checkout.
Le dépôt Maven de dépendances est chaud : **aucun build à cache réseau vierge
n'est démontré**.

Le graphe statique indépendant retrouve 60 modules enfants, fermeture COMMAND
50 et Architecture 60 ; les journaux réels restent l'autorité pour les scopes
et dépendances optional. Maven Compiler 3.14.1, Resources 3.3.1 et Surefire 3.5.5
sont visibles dans les logs. Aucun branchement Failsafe spécifique n'est ajouté
par le projet ; la phase `test` n'est pas une preuve de futurs tests attachés à
`integration-test/verify`. Spring Boot repackage demeure au packaging des exécutables.

Choisir `compile`, `test-compile`, `test`, `package` selon la propriété à prouver
est correct. `install` n'est utile qu'au besoin d'un build séparé ; l'expérience
initiale `clean install` était un diagnostic explicite, pas une dépendance des
nouvelles commandes. Les mesures `-DskipTests` et `maven.test.skip=true` ne sont
pas présentées comme des PASS de tests. Le full ayant échoué, son installation
n'est évidemment pas un jeu complet d'artefacts qualifiés.

Les sorties incrémentales peuvent conserver des classes supprimées/déplacées.
La politique prescrit alors un nettoyage borné, notamment après changement de
branche ; c'est approprié. Ni `-am`, ni le seul déplacement des anciens XML ne
certifient l'absence de bytecode obsolète. La reproduction FAST neuve est une
preuve supplémentaire bornée ; les campagnes PG livrées utilisent des sorties
réchauffées après les builds mesurés. Aucun usage effectif d'un mauvais artefact
n'a été trouvé. Les producteurs des ancres sélectionnées sont dans le reactor ;
aucun install préalable n'est requis pour cette fermeture.

Les logs étayent les temps publiés : complet propre `compile` 13,133 s,
chaud 8,886 s ; COMMAND propre 10,365 s, chaud 7,734 s, après commentaire 7,768 s ;
local propre 4,113 s, chaud 3,166 s. « Propre » porte sur `target`, pas sur `.m2`.
Ces observations uniques et prises dans des sessions différentes n'établissent
ni causalité statistique ni accélération intrinsèque du compilateur. Le `-T 2`
unique économise 0,469 s sur son témoin et n'est pas activé par défaut.

Le gain réel vient de **réduire le périmètre approprié et s'arrêter à la phase
utile**. Comparer 51 minutes de tests complets à FAST ne mesure pas la même
couverture. Les sommes de durées des mêmes suites sont bien distinguées des
temps muraux. Les totaux de sources doivent être corrigés (AUD-VERIFY-004),
et la recompilation à vide garde une cause simple non traitée (AUD-VERIFY-002).
Aucun gain futur après correction n'est chiffré par cet audit.

## 6. Quarantaine I5 et certification normative

Les méthodes exactes sont dans `V3WorkerResourcesPostgresTest` :
`i5_r05:1947` → `campaign(PG,1,1)` ; `i5_r12:895` → `campaign(PG,2,32,true)`.
Les commandes QUARANTINE sélectionnent chacune **une méthode**, sans ses sœurs.
Les XML indépendamment reparsés donnent :

| Observation livrée | i5_r05 | i5_r12 |
|---|---:|---:|
| Exécution indépendante | PASS, méthode 55,678 s ; Maven 66,345 s | **FAIL**, méthode 96,974 s ; Maven 107,702 s |
| Diagnostic full | PASS, méthode 53,569 s | **FAIL**, méthode 94,931 s |

POM, assertions, annotations et délais sont inchangés. Les méthodes restent dans
les commandes non filtrées, le diagnostic full et les deux méthodes obligatoires
du plan CERTIFICATION. La quarantaine ne transforme pas le FAIL en PASS. Le registre
nomme les cas, leur décision et leurs références ; la politique exige diagnostic,
correction, reproduction de la condition originale, gates pertinents et décision
revue pour la sortie. Un seul succès de r05 ne ferme pas sa réserve historique.

Pour r12, le XML montre exactement `runtime condition deadline`, via `await:79`,
`Runtime.close:555`, `I5TemporalV2Postgres.campaign:947`, `i5_r12:896`.
Les mentions `close:553` dans certains textes désignent une localisation ancienne.
La source attend STOPPED pendant 15 secondes après `requestDrain` ; le timeout
est celui du helper du banc. La trace finale contient bien le marqueur
`V3 QUALIFICATION PASS W2-B32-MW4 ... no 30s certificate` **avant la fermeture**.
Ce marqueur intermédiaire n'est pas le résultat du test ; la fermeture en `finally`
échoue ensuite et le résultat officiel reste FAIL.

Cela localise l'échec observé dans l'arrêt/drain du runtime. Les traces conservées
ne donnent pas l'état précis des threads/attentes lors de l'expiration permettant
d'attribuer une cause unique. Une anomalie de disponibilité productive, une
sensibilité à la charge, un problème environnemental, une synchronisation du
banc ou une limite de son oracle restent des hypothèses. Aucun défaut de sûreté
ni simple « flakiness de test » n'est démontré par cette seule signature. Aucune
augmentation de délai ni retry opportuniste n'est recommandé.

Le diagnostic complet est **FAIL**, 1 867/1/0/1, 3 079,751 s de processus
(51 min 20 s arrondies ; le log Maven interne affiche 51:18).
Le skip est `TerminalSequencingPostgresTest#zExploratoryContentionSeparateFromCorrectness`,
conditionné par la propriété absente `step3b.measure`. Architecture est **SKIPPED**
après COMMAND ; ce n'est ni un test ignoré supplémentaire ni une gate Architecture
PASS. Le reactor n'a donc pas achevé toutes ses preuves globales.

**SPEC-10 : NOT RUN**, distinct du diagnostic `clean install` et de la commande
nommée CERTIFICATION du nouveau script, elle-même seulement contrôlée en dry-run.
Aucun changement de SPEC-10, canon, plan directeur, POM, test ou seuil n'est dans
le diff. Les gates canoniques historiques et obligations de lot restent dues ;
les niveaux opérationnels ne valent pas G0/G1/G2/G3. Aucune contradiction normative
nouvelle n'est trouvée dans cette séparation. Le problème AUD-VERIFY-001 concerne
la fiabilité du reçu automatisé, pas une autorisation de dispenser des preuves.

L'absence d'une exécution séparée d'Architecture est justifiée pour ce delta
d'outillage : aucun graphe/frontière/règle de dépendance n'est modifié. Le module
restant SKIPPED dans le diagnostic, aucun PASS global n'est acquis. Une modification
future du graphe ou une gate de lot/main déclenche sa commande canonique entière.
Les réserves humaines L2/L3 et le GO limité de préparation I4 restent inchangés.

## 7. Findings classés

### AUD-VERIFY-001 — MAJOR — Une commande canonique peut produire un faux PASS

- **Nature : défaut avéré, reproduit.**
- **Localisation :** `scripts/testing/verify.py:101–108` (plan canonique sans
  inventaire), `:165–173` (complétude), `:203–205` (liste des options implicites).
- **Fait :** `_JAVA_OPTIONS` est accepté. Il peut injecter un filtre Surefire
  sans apparaître dans `plan.command`. Pour CHANGE/INTEGRATION canoniques,
  `expected={}` ; toute population non vide sans erreur/skip peut alors devenir PASS.
- **Preuve :** exécution réelle dans le checkout isolé :

  ```sh
  env _JAVA_OPTIONS='-Dtest=SpringTransactionRunnerTest -Dsurefire.failIfNoSpecifiedTests=false' \
    python3 -B scripts/testing/verify.py CHANGE FOUNDATION \
    --output /private/tmp/pocoma-verification-audit-evidence/foundation-hidden-filter-h2
  ```

  Plan : `filtered=false`, `normative_gate=true`, commande canonique native.
  Résultat : **PASS, quatre tests H2, zéro skip et `issues=[]`**, alors que les
  sources de la fermeture FOUNDATION comportent **28 tests**, dont 24 PG omis.
  Voir le [contre-exemple autonome](../testing/evidence/verification-independent-audit-d0927d94/counterexample.json)
  et les plan/provenance/result/log/XML dans l'archive indépendante.
- **Risque concret :** perte silencieuse de garanties PostgreSQL/transaction sous
  l'étiquette d'une gate non filtrée. Les settings/propriétés effectives constituent
  aussi une surface à contrôler ; le seul hash d'un settings n'en valide pas la
  sémantique. Aucun cas de contamination des trois recettes livrées n'est affirmé.
- **Correction minimale recommandée :** fermer ce canal JVM implicite ; vérifier
  que les paramètres effectifs ne filtrent/sautent pas une gate annoncée canonique,
  et ajouter un contrôle de complétude pertinent pour ces commandes. Ajouter le
  contre-exemple aux tests de l'outillage. Inutile de multiplier les exécuteurs.
- **Impact global :** **correction nécessaire avant adoption standard** du reçu
  automatisé canonique. Les recettes Identity gardent leur preuve exacte par
  comparaison méthode par méthode ; le présent défaut ne réfute pas leurs XML.

### AUD-VERIFY-002 — MINOR — Recompilation systématique d'une source inchangée

- **Nature : défaut préexistant avéré ; optimisation simple laissée disponible.**
- **Localisation :** `app/engine-consumption/src/main/java/com/kartaguez/pocoma/engine/exception/MissingTerminalConsumptionFailureException.java:1`.
- **Fait :** le chemin ne correspond pas au package déclaré
  `com.kartaguez.pocoma.engine.write.pot.exception`.
- **Preuve :** commande native locale inchangée avec `-X`, après FAST propre :
  log lignes 4388–4399, `Stale source detected` pour ce fichier, compilation des
  55 sources du module ; lignes 5412–5427, nouvelle dépendance `.class` puis
  recompilation des 10 sources de registration. L'extrait est conservé avec
  numéros de lignes et hash du log local. Les logs historiques montrent également
  ces 65 sources recompilées lors du build local chaud, et une cascade plus large
  dans COMMAND.
- **Risque concret :** coût incrémental payé sans changement ; le build chaud
  n'est pas un no-op de compilation. Aucune régression de sûreté démontrée.
- **Correction minimale recommandée :** aligner le chemin de cette source sur
  son package, sans changement de package/imports ni graphe, puis mesurer deux
  compilations inchangées dans la fermeture déclarée. Ne pas désactiver les
  contrôles incrémentaux pour masquer le symptôme.
- **Impact global :** non bloquant ; aucun gain après correction mesuré ou promis.

### AUD-VERIFY-003 — MINOR — Les empreintes d'inputs omettent du SQL réellement utilisé

- **Nature : lacune de traçabilité avérée ; corruption historique non démontrée.**
- **Localisation :** `scripts/testing/verify.py:177–179,197–202,233–236` ;
  `V3IdentityRequestCompositionPostgresTest.java:233`,
  `V3IdentityRequestMonolithPostgresTest.java:232`,
  `V3PotCommandCompositionPostgresTest.java:121`.
- **Fait :** l'inventaire avant/après est limité à `app/`. Ces tests sélectionnés
  chargent des scripts `../../scripts/v3/*.sql`, hors inventaire.
- **Preuve :** `external-inputs.json` rapproche les références de sources et le
  reçu INTEGRATION : aucune des sept entrées SQL actuelles n'y est hashée.
  Le contrôle en fin de run appelle encore seulement `app_inputs()`.
- **Risque concret :** une modification de bootstrap/grants hors `app/` pendant
  l'exécution échappe au contrôle de stabilité ; une preuve réutilisée manque
  d'identité directe sur ces inputs physiques. Le diff audité et le statut initial
  ne montrent aucune telle modification dans la livraison, dont les observations
  ne sont pas invalidées par hypothèse.
- **Correction minimale recommandée :** étendre l'inventaire existant aux inputs
  externes réellement consommés, notamment ces SQL, et les comparer avant/après.
  Ne pas créer un collecteur concurrent ni hasher des secrets.
- **Impact global :** réserve sur la provenance future, non preuve d'un faux
  résultat historique ; amélioration ciblée recommandée.

### AUD-VERIFY-004 — MINOR — Les totaux de sources compilées perdent les cas singuliers

- **Nature : inexactitude de mesure avérée.**
- **Localisation :** `docs/testing/Verification_Optimization_Report.md:204,211` ;
  `evidence/verification-optimization/compilation-observations.json:88,279`
  sous `docs/testing/`.
- **Fait :** le rapport annonce 743 sources pour le complet propre et 448 après
  mutation COMMAND. Les logs contiennent aussi des lignes `Compiling 1 source file`.
- **Preuve :** recomptage indépendant des formes singulier/pluriel dans
  `followup-builds.tar.gz`, conservé dans `build-log-rechecks.json` :
  **59 compilations / 757 sources** pour `full-clean-compile`, dont 14 lignes
  singulières ; **22 / 452** pour `identity-compile-after-change`, dont quatre
  singulières. Les tableaux JSON livrés correspondent aux seules lignes plurielles.
- **Risque concret :** sous-comptage du travail de compilation et comparaisons
  quantitatives erronées ; les temps muraux et comptes de tests ne changent pas.
- **Correction minimale recommandée :** compter `source file` et `source files`,
  republier les agrégats corrigés et vérifier leurs sommes sur les logs existants,
  sans relancer les benchmarks.
- **Impact global :** non bloquant ; aucune accélération intrinsèque ne peut être
  déduite de ces totaux, corrigés ou non.

### AUD-VERIFY-005 — OBSERVATION — Généricité filtrée limitée à Identity

- **Nature : limite déclarée et extension facultative, pas défaut de couverture prouvé.**
- **Localisation :** `scripts/testing/verify.py:75–103` ;
  `scripts/testing/verification-campaigns.json:12–52` ; politique §35.
- **Fait/preuve :** l'expansion de recettes et ses modules sont spécifiques à
  `identity`. Les autres familles n'ont que leurs commandes canoniques natives ;
  les plans reconstruits le confirment. MONOLITH/ARCHITECTURE passent par leurs
  commandes natives justifiées, pas par de nouvelles slices canoniques.
- **Risque concret :** copier la recette Identity pour un changement transverse
  oublierait des risques ; ajouter beaucoup de branches Python dupliquerait la règle.
- **Recommandation minimale :** à la première deuxième recette nécessaire,
  déplacer seulement la règle commune d'expansion vers les données, conserver
  les ancres Maven et faire revoir les méthodes/oracles. Pas de généralisation
  préventive ni d'implémentation immédiate de toutes les slices.
- **Impact global :** aucun blocage supplémentaire ; généricité de sélection
  partiellement démontrée, pas généricité de couverture universelle.

## 8. Simplicité et maintenabilité

Les 46 fichiers sont principalement des preuves : deux documents et quatre
fichiers d'outillage/registre, le reste en reçus, inventaires et archives.
Aucun profil Maven redondant, nouvelle couche de build ou liste manuelle de
modules transitifs n'est ajouté. Les quatre fichiers représentent un sélecteur
compact, son registre, ses tests et un inventaire historique distinct. La liste
positive de 26 classes et l'inventaire automatique des méthodes limitent les
mises à jour ; la forme JUnit supportée est volontairement restreinte et les formes
connues non supportées échouent. Cela reste plus maintenable qu'une liste figée
manuelle de 162 méthodes.

Un développeur peut trouver la commande, le niveau, les reports et les gates dans
§35 et examiner `--dry-run`. Les commandes natives restent accessibles. Le risque
principal est la confiance excessive dans le mot PASS lorsque le plan canonique
ne vérifie pas la complétude effective (001). Les dépendances SQL externes (003)
et les doublons entre chiffres documentés et JSON (004) sont des points précis
à améliorer, sans supprimer les preuves physiques utiles.

Le volume de reçus est élevé mais répond à la conservation des observations et
FAIL. Aucun retrait de trace indispensable ni nouveau mécanisme de cache/contexte
partagé n'est conseillé. Le log debug indépendant publié est limité aux lignes
de compilation utiles ; son dump d'environnement local est volontairement exclu.

## 9. Verdicts et décision d'usage

Les PASS de couverture ci-dessous concernent **la recette Identity interne
actuellement livrée et ses observations vérifiées**, jamais une certification
universelle de chaque famille ou du logiciel V3.

| Dimension | Verdict | Motif déterminant |
|---|---|---|
| Couverture FAST | **PASS** | 65 invocations exactes, invariants purs substantiels, reproduction neuve |
| Couverture CHANGE | **PASS** | 127 exactes ; PG, sécurité, atomicité, concurrence et Worker effectifs |
| Couverture INTEGRATION | **PASS** | 162 exactes ; véritables assemblages et transactions supplémentaires, limites L4/I4/release explicites |
| Généricité | **RESERVATIONS** | Ancres/composition génériques ; une seule famille filtrée démontrée ; 001/005 |
| Optimisation Maven | **RESERVATIONS** | Choix de phase/périmètre correct ; recompilation évitable et totaux de sources inexacts ; 002/004 |
| Quarantaine I5 | **PASS** | Isolation nominative, oracles/seuils intacts, FAIL visible et inclusion normative préservée ; cause r12 ouverte |
| Conformité SPEC-10 | **RESERVATIONS** | Norme et gates intactes, certification NOT RUN ; fiabilité du reçu canonique à corriger (001), provenance à compléter (003) |
| Simplicité / Maintenabilité | **RESERVATIONS** | Dispositif contenu ; limites de collecte/provenance et extension ciblée à traiter |

**Global : CHANGES REQUIRED — un MAJOR, trois MINOR, une OBSERVATION ; aucun BLOCKER.**

Les nouvelles campagnes pourront devenir la procédure opérationnelle standard
pour la suite de L3 **après correction et contre-vérification de 001**, avec
sélection explicite par risques et consommateurs. Leurs preuves Identity actuelles
restent utiles dès maintenant comme feedback borné, avec inspection du plan et
de l'environnement ; aucun reçu canonique automatisé ne doit être pris pour une
preuve exhaustive sans ces contrôles. Les points 002–004 sont des corrections
ciblées recommandées, 005 une extension seulement au besoin. Ils n'autorisent
aucune modification implicite des exigences de certification.

Réponse aux trois questions directrices : **les tests Identity choisis sont
pertinents**, mais le contrôle de campagne canonique peut mentir par omission ;
**la fermeture Maven est adaptée**, sans preuve d'artefact obsolète observé et
avec une recompilation inutile identifiée ; **la capacité normative exhaustive
reste conservée**, mais son succès technique n'est pas acquis et le collecteur
ne doit pas se substituer aux protocoles et inventaires de certification.

## 10. Commandes exécutées, preuves et publication

Commandes principales exécutées, depuis le dépôt ou le worktree isolé :

```sh
git status --short --branch
git rev-parse HEAD
git remote -v
git ls-remote origin refs/heads/feat/v3-architecture
git rev-list --left-right --count HEAD...origin/feat/v3-architecture
git diff --stat 8bc2eca6..d0927d94
git diff --name-status 8bc2eca6..d0927d94
git diff 8bc2eca6..d0927d94
git worktree list --porcelain
git worktree add --detach /private/tmp/pocoma-verification-audit-d0927d94 d0927d94
python3 -B -m unittest -v scripts/testing/test_verification.py
python3 -B scripts/testing/verify.py FAST identity --output /private/tmp/pocoma-verification-audit-evidence/fast-clean
python3 -B scripts/testing/inventory-verification.py --output /private/tmp/pocoma-verification-audit-evidence/inventory-reproduced.json
```

Deux probes `CHANGE FOUNDATION` ont été exécutés via subprocess avec
`_JAVA_OPTIONS` limité à leur processus : d'abord `V3FoundationCompositionTest`
(erreur Docker, conservée), puis `SpringTransactionRunnerTest` (contre-exemple
001). Leurs argv, overrides et codes retour exacts sont enregistrés dans
`hidden-filter-command.json` et `hidden-filter-h2-command.json` ; aucune variable
persistante de l'utilisateur n'est modifiée. La compilation native exécutée
**depuis `app/`** est :

```sh
./mvnw --batch-mode --no-transfer-progress -X -pl engine-consume-registration,domain-pot -am compile
```

Le probe indépendant, conservé en texte dans l'archive, a été exécuté ainsi :

```sh
python3 -B /private/tmp/pocoma-verification-audit-evidence/independent-checks.py.txt \
  /private/tmp/pocoma-verification-audit-d0927d94 \
  /private/tmp/pocoma-verification-audit-evidence
```

Il relit le diff, les 46 fichiers, les plans, les XML, les hashes d'inputs, les
neuf archives, le graphe et les sélections canoniques ; ses JSON sont des
**résultats de contrôles indépendants**, pas de nouvelles exécutions PG.
Les inspections `rg`, lectures ciblées et probes Python supplémentaires vérifient
les oracles, le comptage singulier/pluriel et les dépendances SQL externes.
Le [README de preuves](../testing/evidence/verification-independent-audit-d0927d94/README.md)
donne la restauration et les pièces décisives. L'annexe énumère aussi les 46 fichiers.

Fichiers créés :

1. `docs/v3/Verification_Rationalization_Independent_Audit.md` — ce rapport.
2. `docs/v3/Verification_Rationalization_Test_Sets.md` — sources, classes, méthodes et fichiers audités.
3. `docs/testing/evidence/verification-independent-audit-d0927d94/README.md`.
4. `docs/testing/evidence/verification-independent-audit-d0927d94/counterexample.json`.
5. `docs/testing/evidence/verification-independent-audit-d0927d94/audit-evidence.tar.gz`.
6. `docs/testing/evidence/verification-independent-audit-d0927d94/manifest.json`.

Vérification finale de publication : hashes/membres de l'archive et manifeste,
liens relatifs nouveaux, `git diff --check`, diff staged limité à ces nouveaux
fichiers, commit puis push normal vers `feat/v3-architecture`, comparaison réelle
de la tête distante et divergence **0/0**. Le reçu de livraison indique le SHA
obtenu et l'état final effectivement constaté.

Bilan : impact productif attendu/observé **NONE** ; slices déclarées
**COMMAND/Identity local**, contributions **FOUNDATION/monolithe** inspectées,
probe **FOUNDATION** explicitement ajouté ; franchissement inattendu **NONE**.
Gate globale **NOT REQUIRED / NOT RUN** ; full reactor **NOT REQUIRED / NOT RUN
par l'audit**, diagnostic historique FAIL recontrôlé seulement. Certification
**NOT RUN**, quarantaine et réserves L2/L3 conservées. Aucune correction de code,
script, POM, SQL, oracle ou documentation préexistante n'est incluse.
