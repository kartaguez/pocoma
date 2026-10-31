# Pocoma V3 — Contre-audit indépendant AUD-VERIFY-001

31 octobre 2026. **Verdict : CHANGES REQUIRED.** Le contre-exemple original est
bloqué et les campagnes nominales demandées réussissent. Cependant, un nouveau
**faux PASS FOUNDATION 4/28** est reproduit avec la CLI inchangée : la recopie de
24 anciens résultats avec une date de fichier renouvelée contourne la fraîcheur.
Le critère d’acceptation des rapports obsolètes n’est donc pas satisfait.

## Référence et méthode

- Dépôt `kartaguez/pocoma`, branche `feat/v3-architecture`.
- **SHA réellement audité : `6d40ed17496709ce75175c4651dbaaaedc78343e`.** HEAD local
  et distant concordants avant audit et avant préparation de publication.
- Implémentation initiale : `d0927d9410ebcd65c221e73c5ecd04e5242fe4e9` ; audit initial :
  `33f02f4512af0dbafb5c482ec84171801815d82c`.
- Checkout isolé : clone local partagé, détaché au SHA audité, sous
  `/private/tmp/pocoma-counter-audit-6d40ed17/checkout`, initialement sans `target`.
  Dépendances `.m2` chaudes ; Maven 3.9.14, Java 21 Temurin ; Docker 29.9.2 pour
  FOUNDATION PostgreSQL. Ce n’est pas une qualification sur un second environnement.
- Référentiel consulté directement : [audit initial](Verification_Rationalization_Independent_Audit.md),
  son contre-exemple et ses XML, [résolution](Verification_Rationalization_Resolution.md),
  **diff exact `33f02f45..6d40ed17`**, scripts/registre/tests concernés,
  [politique normative Reactor](../testing/Reactor_Verification_Policy.md) et
  [SPEC-10](SPEC-10_Test_Certification.md). Les 61 membres de l’archive initiale et
  les 110 membres de résolution sont rehashés contre leurs manifestes.
- La déclaration préalable porte sur **FOUNDATION canonique**, la contribution
  **FAST Identity de COMMAND**, et les contrôles du collecteur. Production interdite ;
  impact observé **NONE** ; franchissement inattendu **NONE**. Architecture globale
  **NOT REQUIRED / NOT RUN** ; full reactor **NOT REQUIRED / NOT RUN**, aucun jalon
  d’intégration globale. DB : fixtures FOUNDATION courantes uniquement, aucun
  parcours historique supplémentaire ni suite PostgreSQL lourde.

Les campagnes sont vérifiées par un lecteur indépendant qui n’importe pas le
sélecteur : hashes des archives/membres, reconstruction XML des identités et
multiplicités, compteurs, sources actuelles et résumés Maven. Les altérations
utilisent les vrais XML de la nouvelle campagne FOUNDATION ; aucune preuve
historique ni source productive n’est modifiée. Les pilotes sont des pièces
**documentaires** conservées dans l’archive, hors `scripts/`.

## Résultats C1 à C5

| Contrôle | Résultat indépendant |
|---|---|
| **C1 — contre-exemple exact** | **Conforme** : `_JAVA_OPTIONS='-Dtest=SpringTransactionRunnerTest -Dsurefire.failIfNoSpecifiedTests=false'` provoque le code **2**, diagnostic `_JAVA_OPTIONS is set`, avant création du dossier de campagne et avant Maven. Les quatre XML historiques, confrontés aux 28 obligations actuelles, donnent INCONCLUSIVE avec 24 identités absentes. |
| **C2 — FOUNDATION canonique** | **Conforme** : **28 attendus / 28 exécutés**, zéro failure/error/skip, **PASS**, `issues=[]`. Maven : **12,423 s**. Identités, XML, sources, hashes, manifeste d’artefacts et résumé Maven concordent. |
| **C3 — comparaison des identités** | **Solide sur les formes actuelles, réserves ci-dessous** : clés `(FQCN, méthode/invocation)` avec `Counter`, exactement une occurrence par obligation ; identités supplémentaires et doublons refusés. Une substitution conservant le total est rejetée. La fraîcheur et l’attribution des rapports présentent cependant le finding majeur C4. |
| **C4 — altérations** | **Non conforme** : suppression, ajout, doublon, rapport tronqué/absent et ancien mtime sont rejetés ; **une recopie d’anciens XML avec nouveau mtime obtient PASS**, y compris par la CLI sans interception Python. |
| **C5 — FAST Identity** | **Conforme** : **65 attendus / 65 exécutés**, zéro failure/error/skip, **PASS**, `issues=[]`. Maven : **3,726 s**. Les neuf classes et les deux invocations boolean restent présentes ; retirer l’une des invocations est rejeté. |

Une première exécution C2 sous sandbox a rencontré `SocketException: Operation not
permitted` sur Docker : **FAIL**, neuf cas rapportés dont cinq erreurs de fixture.
Elle est conservée sous `c2-foundation/`, sans être assimilée à une validation.
Le run `c2-foundation-docker/`, avec l’accès Docker nécessaire, est le run nominal
qualifiant. FAST n’exige pas Docker ; l’échec de la capture Docker dans son reçu
ne touche pas ses tests purs.

Les **30 tests de non-régression de l’auteur passent** en complément. Leur scénario
stale utilise un ancien mtime ; il ne prouve pas le rejet d’une copie ordinaire
qui renouvelle ce mtime.

## C4 — preuves négatives discriminantes

Toutes les variantes ci-dessous attendent un non-PASS ; le PASS indésirable est
un **succès de reproduction du défaut**, pas un succès de campagne obligatoire.

| Altération | Observation |
|---|---|
| Supprimer un testcase et réajuster les compteurs XML | INCONCLUSIVE, 27 résultats, obligation manquante |
| Ajouter une méthode inconnue avec compteurs cohérents | INCONCLUSIVE, 29 résultats, identité inattendue |
| Remplacer un testcase par un doublon, total conservé à 28 | INCONCLUSIVE, obligation absente et multiplicité 2 détectées |
| Tronquer un XML / supprimer tous les rapports | INCONCLUSIVE dans les deux cas |
| Exécuter seulement les quatre H2, sans restauration | INCONCLUSIVE, 24 obligations absentes |
| Restaurer cinq XML PostgreSQL anciens avec `copy2`, mtime conservé | INCONCLUSIVE, anciens XML exclus, quatre résultats comptés |
| Restaurer les mêmes octets avec `copyfile`, mtime renouvelé | **Faux PASS**, 28 résultats comptés pour quatre tests réellement exécutés |
| Injecter chacun des cinq canaux JVM/Maven explicitement interdits | Code 2 avec diagnostic nominatif, aucun dossier de campagne |
| Injecter le filtre via un `MAVEN_BASEDIR` externe | Non détecté au préflight ; **INCONCLUSIVE 4/28** sans restauration, **faux PASS 4/28** avec restauration |

### AUD-VERIFY-001-R1 — MAJOR — Ancien XML recopié accepté comme exécution fraîche

**Défaut démontré sur la campagne obligatoire FOUNDATION au SHA audité.**

1. Le nominal réel produit six XML, dont cinq couvrent les **24 tests PostgreSQL**.
   Les cinq fichiers sont conservés hors checkout, avec leurs hashes et ancien mtime.
2. Dans un répertoire temporaire extérieur au dépôt, `.mvn/jvm.config` contient
   `-Dtest=SpringTransactionRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`.
   Le launcher Maven le lit via `MAVEN_BASEDIR`, canal absent de la vérification
   d’environnement. L’inventaire effectif du collecteur conserve bien **28 obligations**.
3. La **CLI originale non modifiée** exécute `CHANGE FOUNDATION`. Son log Maven
   affiche uniquement `SpringTransactionRunnerTest`, **Tests run: 4**, BUILD SUCCESS.
4. Un pilote externe recopie les cinq anciens XML dès la création de `maven.log`,
   donc après le déplacement des anciens rapports et avant la collecte finale.
   **Aucun contenu XML n’est réécrit** ; `shutil.copyfile` renouvelle seulement le
   mtime. Il n’y a ni second Maven concurrent, ni changement de source/config du dépôt.
5. Le collecteur produit **PASS, code 0, 28/0/0/0, `issues=[]`**, avec
   `filtered=false` et `normative_gate=true`. Les 24 résultats recopiés sont
   **identiques octet par octet au nominal précédent**, pas issus de ce Maven.
   Les inputs applicatifs et du collecteur concordent avant/après et entre runs.

**Cause :** `verify.py:447–473` déplace les anciens XML présents au départ puis
accepte tout rapport dont `st_mtime_ns >= started`. Il ne lie pas le contenu du
rapport au processus Maven courant. `assess`, lignes 357–409, vérifie correctement
la population déclarée dans les XML ; elle ne peut distinguer ces anciens succès
recopiés. Le désaccord manifeste **résumé Maven 4 / XML 28** n’est pas contrôlé.
Les SHA-256 d’archivage authentifient les octets collectés, pas leur exécution fraîche.

**Portée de la preuve :** injection contrôlée d’un filtre externe et restauration
contrôlée d’artefacts dans les `target` du clone isolé. Elle ne démontre pas une
restauration spontanée dans les campagnes historiques. Elle démontre que le
rejet des rapports obsolètes promis par §35 et exigé par C4 est contournable sans
modifier la CLI, les tests, les POM ou la configuration versionnée. Le seul filtre
externe reste correctement sanctionné par l’inventaire ; **le défaut déterminant
est le réemploi de résultats anciens sous une nouvelle date de fichier**.

Preuves : `cli-replay-results.json`, `c4-cli-replay/{plan,provenance,result}.json`,
`c4-cli-replay/maven.log`, `raw.tar.gz`, `receipt-verification.json` et
`cli-replay-probe.py.txt` dans l’archive. Le pilote consigne chaque transfert,
son hash, ses dates et ses 24 testcases. Une première reproduction par injection
à la frontière du sous-processus est également conservée ; la preuve décisive
ci-dessus **n’utilise aucun monkeypatch ni code retour Maven simulé**.

## C3 — limites d’identité et de découverte

- Les sources des modules effectifs définissent les obligations ; les anciens XML
  ne servent pas d’inventaire. FOUNDATION retrouve deux modules Java et six classes.
  Les formes boolean sont développées en `[1]` / `[2]`. Les doublons de FQCN trouvés
  pendant la découverte sont refusés ; les formes dynamiques/providers non revus
  ne sont pas supposés couverts.
- L’identité comparée n’inclut **pas le module Maven**. Déplacer les chemins des
  rapports FOUNDATION vers `port-transaction` dans l’entrée d’`assess` laisse PASS.
  C’est une limite d’attribution ; aucun conflit de FQCN actuel n’est démontré dans
  les ensembles 28/65. Elle renforce la faiblesse de provenance du finding R1.
- Une sonde source hors dépôt avec `@Test void visible()` et
  `@org.junit.jupiter.api.Test void hidden()` retourne seulement `visible`, sans
  refus. Cette forme qualifiée n’apparaît pas dans les sources des campagnes
  28/65 vérifiées. C’est une réserve de généricité du parseur restreint, **pas une
  preuve d’omission actuelle** ; ne pas généraliser la certification de découverte.
- Le verrou protège les exécutions du sélecteur, pas les restaurations de fichiers.
  Aucun passage à PASS n’est observé pour un rapport absent, tronqué, un total
  incohérent ou une invocation manquante sans ancien rapport substitué.

## Commandes réellement exécutées

Base temporaire `B=/private/tmp/pocoma-counter-audit-6d40ed17` ; `E=$B/evidence`.
Commandes de campagne depuis `$B/checkout` :

```sh
env _JAVA_OPTIONS='-Dtest=SpringTransactionRunnerTest -Dsurefire.failIfNoSpecifiedTests=false' \
  python3 -B scripts/testing/verify.py CHANGE FOUNDATION --output "$E/c1-hidden-filter"
python3 -B scripts/testing/verify.py CHANGE FOUNDATION --output "$E/c2-foundation"
python3 -B scripts/testing/verify.py CHANGE FOUNDATION --output "$E/c2-foundation-docker"
python3 -B scripts/testing/verify.py FAST identity --output "$E/c5-fast-identity"
python3 -B -m unittest -v scripts/testing/test_verification.py
python3 -B "$B/audit-probes.py"
python3 -B "$B/cli-replay-probe.py"
python3 -B "$B/verify-receipts.py"
```

Le nominal FOUNDATION utilise exactement, depuis `app/` :
`./mvnw --batch-mode --no-transfer-progress -pl infra-tx-spring -am test`.
Chaque run fait préalablement `help:effective-pom` avec les mêmes arguments de
sélection et `-Doutput=<dossier>/effective-pom.xml`. FAST utilise les neuf FQCN
explicites de son `plan.json`, les ancres `engine-consume-registration,domain-pot`,
`-am`, `-Dtest=<liste>` et `-Dsurefire.failIfNoSpecifiedTests=false`.

`audit-probes.py` exécute trois Maven H2 réels avec filtre explicite sous injection
contrôlée et cinq refus CLI d’environnement. `cli-replay-probe.py` lance **deux CLI
originales**, avec `MAVEN_BASEDIR=$B/external-maven-base`, sans/avec restauration.
Les argv, options, retours et chemins exacts sont dans les reçus et pilotes.
Le premier contre-exemple C1 a été rejoué deux fois avec le même refus.

Isolation : `git clone --shared --no-checkout <dépôt local> "$B/checkout"`, puis
`git checkout --detach 6d40ed17496709ce75175c4651dbaaaedc78343e`.
Contrôles Git : `git rev-parse HEAD`, `git status --short --branch`,
`git diff --find-renames 33f02f45..6d40ed17`, `git diff --check 33f02f45..6d40ed17`,
`git ls-remote origin refs/heads/feat/v3-architecture` avant audit/publication.
Une première lecture distante sous sandbox a échoué en DNS ; les lectures avec
accès réseau ont confirmé le SHA attendu.

## Verdict, adoption et publication

**CHANGES REQUIRED.** C1, C2 et C5 sont confirmés indépendamment ; C4 échoue sur un
faux PASS démontré d’une campagne obligatoire. AUD-VERIFY-001 ne peut donc être
accepté comme fermé sans réserve majeure. Aucune correction n’est engagée.

**FAST / CHANGE / INTEGRATION : adoption comme procédure opérationnelle standard
fiable de gate pour Identity et FOUNDATION non validée par ce contre-audit.**
Les succès nominaux 65/65 et 28/28 autorisent une confiance bornée dans ces
exécutions ; ils ne réparent pas le collecteur commun. Les recettes peuvent
servir de feedback borné conformément à la politique, avec les gates normatives
dues, mais leur PASS automatique ne suffit pas à lever le finding R1.
Cette évaluation ne réécrit pas la politique déjà publiée.

**SPEC-10 : NOT RUN / aucune certification.** CHANGE et INTEGRATION Identity,
I5, suites PostgreSQL lourdes, full reactor et Architecture globale non relancés :
aucun doute de sélection justifiant leur coût ; les rejouer ne prouverait pas
l’impossibilité de réemploi des rapports. Aucune validation des formes JUnit
futures, des profils externes arbitraires, de Failsafe ou de robustesse temporelle.

Publication strictement documentaire : ce rapport et les quatre fichiers sous
[verification-counter-audit-6d40ed17](../testing/evidence/verification-counter-audit-6d40ed17/README.md).
Le [manifeste](../testing/evidence/verification-counter-audit-6d40ed17/manifest.json)
indexe les cinq créations et tous les membres de l’archive ; aucune preuve
historique n’est réécrite. Le SHA final de publication est celui du commit portant
ce rapport, communiqué au reçu final après push normal et relecture distante.
