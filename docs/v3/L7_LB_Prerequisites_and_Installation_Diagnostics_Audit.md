# L7-LB — Audit des prérequis et du diagnostic d’installation

Date : 15 novembre 2026 (Europe/Paris). Mandat : `GO_L7_LB_PREREQUISITES_AUDIT`.
Mode : analyse statique et documentation uniquement, aucune implémentation.

**Verdict : `L7_LB_PREREQUISITES_CORRECTIONS_REQUIRED`.** Le parcours qualifié
fonctionne sur l’hôte compatible documenté. En revanche, plusieurs prérequis
essentiels ne disposent pas d’un refus précoce et exploitable sur un autre poste.
Les corrections proposées restent locales à la façade de distribution. Cet audit
ne retire pas `L7_LB_CLEAN_CLONE_QUALIFIED` et ne prononce aucune clôture L7-LB.

## 1. Sources, méthode et limites

Base de distribution : `0a3246b9bc58bb813163caf22c6a2108c6f88db9` ; publication
des preuves : `91604558adb90bac7d39a02716f5cd58e29f3ec0`. Le
[rapport clean-clone](L7_Local_Playground_Clean_Clone_Qualification_Report.md)
et ses [preuves](evidence/l7-lb-clean-clone/2026-11-15/README.md) établissent le
succès antérieur, sur macOS ARM64, avec caches hôte. Ils ne constituent pas des
tests des prérequis absents. Les versions observées y sont notamment Python
3.14.7, JDK 21, Node 24.15.0/npm 12.0.2, Docker 29.9.2 et Compose 5.6.1.
Ce sont des observations historiques, pas des minima déduits.

Le checkout initial de cet audit était
`60b12fc3cb81f42a89db0688b0406a4a986f6ffc`, avec le seul fichier non suivi
`docs/v3/V3_Command_Result_Contract_Consistency_Audit.md`. Son correctif Bruno
préexistant et ce document sont préservés. Le commit d’audit est préparé dans
un worktree isolé, directement sur `91604558`, et ajoute uniquement ce rapport.
Les fichiers de lancement, Python, Maven, npm et Docker cités ci-dessous sont
identiques entre le SHA applicatif qualifié et cette base de publication ; les
changements Bruno locaux ne sont ni utilisés comme preuve ni publiés.

Les résultats ci-dessous sont des **déductions du code**, sauf les observations
historiques expressément attribuées. Aucun scénario d’échec n’a été provoqué,
aucune commande de façade ni interrogation du daemon n’a été exécutée pendant
cet audit. Aucun état privé ni secret d’instance n’a été lu. Les codes de sortie
décrits sont ceux attendus d’après les branches du code, pas de nouvelles mesures.

Périmètre déclaré avant rédaction : impact productif **NONE** ; slices Maven
primaire/secondaires **NONE** ; preuve documentaire et références seulement ;
gate globale d’architecture **NOT REQUIRED** ; full reactor **NOT REQUIRED / NOT
RUN** ; vérification base de données **NONE**. La
[politique normative, §34](../testing/Reactor_Verification_Policy.md)
autorise ce périmètre documentaire. Aucune campagne Maven, migration, installation,
recette Bruno ou opération Docker n’est nécessaire à cette mission.

## 2. Chemin réel d’installation et frontière des mutations

Il n’existe pas d’installateur distinct obligatoire dans le quickstart :
[`./pocoma-local start`](L7_Local_Playground_Quickstart.md#démarrer-depuis-git)
prépare les dépendances et construit les artefacts. L’enchaînement est :

| Phase | Opérations et contrôles réels | Effets déjà possibles |
| --- | --- | --- |
| A — entrée | Bash exécute `python3 -B …/runtime.py` ; imports Python, dont `fcntl`, avant `main` ([lanceur](../../pocoma-local#L1), [imports](../../scripts/local/runtime.py#L3)). | Aucun état d’instance si l’interpréteur ou un import échoue. Pas de diagnostic JSON dans ce cas. |
| B — identité | ID, permissions/symlinks, verrous ; création du dossier, ports éphémères, Git/SHA/hash, manifeste NEW et dossier de preuves ([verrou](../../scripts/local/runtime.py#L102), [manifeste](../../scripts/local/runtime.py#L146), [dispatch](../../scripts/local/runtime.py#L1117)). | Répertoire et fichiers privés, avant le contrôle des outils. Un échec Git peut laisser le dossier/verrou sans manifeste. |
| C — tentative | Historique de démarrage ; empreinte/éventuel refresh du tooling ; photographie des générations, puis `preflight` Docker/mémoire/disque/architecture ([start](../../scripts/local/runtime.py#L688), [distribution](../../scripts/local/runtime.py#L723)). | Écriture de preuves ; un refresh peut copier les JAR/cache et actualiser le manifeste après preuve d’équivalence. Aucun `up` à ce stade. Sur une instance avec Compose, `generations` appelle déjà Compose avant `preflight`. |
| D — dépendances | Refus d’autre playground actif, propriété, secrets ; `npm ci` si CLI absente, version Bruno ; inspection/pull des six images ; relevés Docker/Compose/Node/Java ([ordre exact](../../scripts/local/runtime.py#L730)). | Secrets, `node_modules`, cache npm, téléchargements d’images. Les contrôles de Java/Compose arrivent après ces effets. |
| E — build/config | Refus des variables/configurations Maven non revues, verrou/cache/build Wrapper ; rendu des fichiers et du Compose ([package](../../scripts/local/runtime.py#L333), [render](../../scripts/local/runtime.py#L244)). | Caches Wrapper/Maven, `app/**/target`, copies JAR et fichiers privés. Toujours aucun conteneur créé par ce nouveau `start`. |
| F — activation | `up postgres`, initialisation SQL, propriétés ; `up keycloak`, discovery/auth ; `up` des cinq JVM/Prometheus/Grafana ; readiness, partitions, seed et canari ([activation](../../scripts/local/runtime.py#L753)). | Réseau, volumes, conteneurs et données ; une erreur de port/configuration tardive peut laisser une installation partielle. Aucun rollback destructif automatique. |

« Avant mutation » doit donc être précisé : les contrôles de ressources sont
avant l’activation Docker, mais après l’écriture de l’état local. Les contrôles
de versions hôte ne précèdent pas tous la préparation des dépendances.

Le bootstrap utilise le catalogue SQL CURRENT et le transport `docker exec psql` ;
il ne lance ni Flyway CLI, ni client PostgreSQL hôte, ni migrations historiques V2.
Il remplace les transports du helper existant ([bootstrap, lignes 25–73](../../scripts/local/bootstrap.py#L25),
[transport SQL](../../scripts/local/runtime.py#L205)). L’atomicité concerne chaque
base avec son marqueur ; création des bases/rôles et `ALTER SYSTEM` précèdent ces
transactions ([helper, lignes 56–106](../../scripts/operations/setup-v3-database.py#L56)).
Il ne faut pas présenter l’installation entière comme une seule transaction.

## 3. Inventaire et matrice de couverture

Légende : **S** = démarrage par la façade actuelle, **B** = construction froide,
**T** = parcours/tests Bruno, **O** = option développeur. « Partiel » distingue
explicitement une invocation qui échoue d’un contrôle diagnostique satisfaisant.

| Prérequis réel / obligation | Vérifié ? Où et quand ? | Diagnostic actuel et écart |
| --- | --- | --- |
| **Python ≥3.11 (S)** selon le [quickstart, lignes 11–17](L7_Local_Playground_Quickstart.md#L11). Interpréteur réel : `python3` résolu par le PATH du lanceur ; sous-processus Python de synchronisation : `sys.executable`. | Pas de comparaison `sys.version_info`. `sys.version` seulement consigné tardivement dans `m.toolchain` ([runtime:747](../../scripts/local/runtime.py#L747)). | Absence : message du shell. Version ancienne : peut fonctionner partiellement ou échouer au parsing/import/usage ; aucun « Python ≥3.11 requis, X détecté ». Le minimum annoncé n’est pas prouvé par une simple lecture statique. |
| **Modules Python (S)** : bibliothèque standard et modules locaux ; `fcntl` indispensable aux verrous. | Imports directs ([runtime:3–28](../../scripts/local/runtime.py#L3), [auth](../../scripts/local/auth.py#L1), [bootstrap](../../scripts/local/bootstrap.py#L6), [helper OIDC](../../scripts/functional-auth-proof.py#L4)). Pas de pip/venv obligatoire ni paquet tiers dans cette chaîne. | Échec d’import avant le handler : traceback technique, notamment système sans `fcntl`. Ne pas demander `pip install` pour corriger une incompatibilité OS. |
| **JDK 21 (B)** ; `java` hôte reste exigé par **S**, même avec build en cache. JRE 21 séparé dans les conteneurs. | `java --version` depuis PATH seulement relevé avant `package`, sans comparaison ; `javac` non sondé par la façade. Le [POM:79](../../app/pom.xml#L79) déclare Java 21 ; [runtime:279](../../scripts/local/runtime.py#L279) fixe la JRE d’image. | Java absent : `FileNotFoundError`, code 1. Java 17 peut passer le relevé puis échouer au build, ou ne pas être utilisé si cache. Pas de contrôle du couple `java`/`javac` ni du JDK effectif de Maven. |
| **Maven Wrapper 3.9.14 (B)** ; Maven système non nécessaire. | Wrapper suivi et URL épinglée ([properties](../../app/.mvn/wrapper/maven-wrapper.properties#L1)); cache de JAR validé par hashes, sinon appel direct à `app/mvnw` ([package:348–367](../../scripts/local/runtime.py#L348)). | Wrapper manquant/non exécutable : exception générique. Wrapper/build en échec : `COMMAND-FAILED:<chemin>/app/mvnw` et `package.log`. Ne pas recommander l’installation de `mvn` système. |
| **JDK effectif du Wrapper (B)** : `JAVA_HOME` ou résolution de Maven ; le sondage PATH n’établit pas cette identité. | Le [Wrapper:45–79](../../app/mvnw#L45) contrôle java/javac pour son fallback de téléchargement Java. Ce contrôle n’est pas inconditionnel : cache Wrapper → exécution directe ([153–156](../../app/mvnw#L153)), ou téléchargement curl/wget. | Des messages utiles existent dans le Wrapper, mais sont enfouis dans `package.log`. `JAVA_HOME=JDK17` avec `java` PATH en 21 n’est pas refusé explicitement avant préparation. |
| **Node 24 (S/T)** : profil documenté. Pas nécessaire aux JVM ; nécessaire à Bruno, que `start` impose malgré l’absence d’exécution des collections à cette étape. | CLI exécutée avant le relevé `node --version`, sans garde Node ([runtime:738–749](../../scripts/local/runtime.py#L738)). | Une CLI défaillante peut devenir `COMMAND-FAILED:…/bru`. Le [package racine](../../scripts/bruno-v3/package.json#L1) n’a pas d’`engines`. Le lock inclut des exigences `node >=22.0.0` ([Azure:656–672](../../scripts/bruno-v3/package-lock.json#L656), [identity:807–829](../../scripts/bruno-v3/package-lock.json#L807)) : cela n’établit pas la qualification de Node22. Conserver Node24 comme profil supporté tant qu’un autre profil n’est pas validé. |
| **npm (S/T à la préparation)** : nécessaire seulement si `scripts/bruno-v3/node_modules/.bin/bru` est absent. | `npm ci --prefix …/scripts/bruno-v3`, lock v3 et CLI 4.3.0 épinglée ([start:738](../../scripts/local/runtime.py#L738), [bruno:1051](../../scripts/local/runtime.py#L1051)). Aucun `npm --version` ni minimum propre déclaré. | npm absent : `FileNotFoundError`. npm incompatible/registry/cache/permissions : `COMMAND-FAILED:npm`, `npm.log` si processus terminé. Une CLI présente mais cassée n’est pas réinstallée automatiquement. L’environnement npm peut omettre les devDependencies ; celles-ci contiennent la CLI. |
| **Bruno CLI exactement 4.3.0 (S/T)**, installation locale ; CLI globale inutile. | Comparaison exacte de `bru --version`, au start et à bruno ([741](../../scripts/local/runtime.py#L741), [1054](../../scripts/local/runtime.py#L1054)). | `PRE-BRUNO-VERSION`, code 2, mais sans version observée/attendue ni action. CLI non exécutable : exception ou échec de commande avant la comparaison. Le verrouillage est réel. |
| **Docker CLI, accès daemon et permissions (S)**, endpoint capable d’images Linux et de montages du checkout. | `docker info` au préflight ; Compose peut intervenir auparavant sur reprise ; nombreuses inspections ([preflight:584](../../scripts/local/runtime.py#L584), [generations:370](../../scripts/local/runtime.py#L370)). Aucune sonde préalable distinguant CLI/socket/daemon/contexte. | Absent : `FileNotFoundError`. Daemon arrêté/socket interdit/mauvais contexte : `COMMAND-FAILED:docker`. `docker info` sans paramètre `log` perd son stderr ; « see private diagnostics » peut ne désigner aucun log utile. Pas de minimum Engine déclaré ou imposé. |
| **Plugin `docker compose` (S)** ; le binaire historique `docker-compose` seul ne suffit pas. | Commande du plugin partout, relevé `version --short` avant build ; pas de comparaison ni validation `compose config` ([compose:194](../../scripts/local/runtime.py#L194), [747](../../scripts/local/runtime.py#L747)). | Plugin absent : échec opaque Docker avant tout `up`, mais après npm/images sur installation neuve. JSON Compose, limites mémoire/CPU, healthcheck, bind mounts et tmpfs doivent être acceptés. Minimum Compose non établi ; 5.6.1 observé ne signifie pas minimum 5.6.1. |
| **Git et métadonnées du dépôt (S/T)**, encore requis après clonage. | `rev-parse HEAD`, `ls-files`, status, diff de provenance ([manifeste:146](../../scripts/local/runtime.py#L146), [source_hash:73](../../scripts/local/runtime.py#L73), [refresh:413](../../scripts/local/runtime.py#L413)). | Pas de contrôle explicite. Archive source sans `.git` insuffisante : `COMMAND-FAILED:git`. Git absent : `FileNotFoundError`, parfois sans dossier de preuves. Pas de minimum Git déclaré. |
| **Architecture daemon ARM64 ou AMD64 (S)** ; images natives obligatoires. | Mapping aarch64/x86_64, refus hors liste avant pull ; inspection architecture et digest de chaque image après disponibilité ([600–603](../../scripts/local/runtime.py#L600), [223–241](../../scripts/local/runtime.py#L223)). | `PRE-ARCHITECTURE`, `PRE-IMAGE-ARCH`, `PRE-IMAGE-NON-NATIVE`, `PRE-IMAGE-DIGEST`. Couverture réelle ; diagnostic sans valeurs/action. Mesure l’architecture daemon, pas l’OS/CPU de l’hôte. |
| **OS (S)** : macOS ARM64 annoncé/qualifié ; POSIX requis par Bash, chmod et `fcntl`. Linux/AMD64 : voies plausibles du code, non qualifiées par cet audit. | Pas de garde OS. [Quickstart:11](L7_Local_Playground_Quickstart.md#L11), [imports](../../scripts/local/runtime.py#L3), [verrous](../../scripts/local/runtime.py#L102). | Windows natif n’est pas couvert par la façade ; la présence de `mvnw.cmd` ne change pas cela. WSL, Linux, AMD64 ou daemon distant demandent une qualification séparée. Les bind mounts exigent que les chemins générés soient accessibles au daemon. |
| **Mémoire Docker ≥6 Gio (S)** ; profil hôte 16 Go, VM 8 Gio observée. | `docker info.MemTotal < 6*1024**3` refuse avant dépendances ; CPU seulement enregistré ([584–603](../../scripts/local/runtime.py#L584)). | `PRE-RESOURCES: Docker >=6GiB`, code 2. Contrôle satisfaisant du seuil alloué ; valeur détectée et action absentes. `resources.json` n’est pas écrit si la mémoire échoue. Ne mesure ni RAM hôte libre ni mémoire encore disponible après autres stacks. Limites cumulées 5760 Mio ([quickstart:47–52](L7_Local_Playground_Quickstart.md#L47)). |
| **Stockage (S/B)** : ≥4 Gio si image absente, sinon ≥2 Gio build froid ou ≥256 Mio cache build. Davantage pour clone/caches/données. | `disk_usage(ROOT)`, seuil selon cache/images ; `resources.json` avant refus disque ([589–599](../../scripts/local/runtime.py#L589)). | `PRE-DISK: requiredBytes=…, availableBytes=…`, code 2 : comparatif exploitable. Le filesystem du checkout ne prouve pas l’espace de la VM Docker, de `.m2`, du cache npm ou de TMPDIR. Le seuil n’est pas une estimation garantie de l’installation froide complète. |
| **Réseau (B/préparation S)** : Maven Central/Wrapper (`repo.maven.apache.org`), npm (`registry.npmjs.org`), Docker Hub et Quay ; GitHub pour clone/publication. Résolution, TLS, proxy et loopback opérationnels. | Téléchargements seulement quand nécessaires ([Wrapper:194](../../app/mvnw#L194), [images:223](../../scripts/local/runtime.py#L223), [npm:740](../../scripts/local/runtime.py#L740), [lock](../../scripts/bruno-v3/package-lock.json#L2313)). Pas de prétest réseau dédié. | Erreurs récupérables de pull/npm/build avec logs lorsqu’ils sont produits ; pas de distinction DNS/proxy/certificats/quota. Les sondes HTTP Python utilisent urllib ; leur comportement réseau/proxy peut différer du daemon, de Java et de npm. Un cache complet réduit les besoins, sans constituer une garantie hors ligne. |
| **Ports loopback (S)** : dix ports (WRITE/READ/issuer, cinq management, Prometheus, Grafana), aucun port PG hôte. | Allocation initiale par bind simultanés sur port0, sockets ensuite libérés ; ports conservés dans manifeste ([132–150](../../scripts/local/runtime.py#L132), [render](../../scripts/local/runtime.py#L268)). | Évite une collision déjà présente à l’allocation ; ne réserve pas jusqu’au `up` et ne recontrôle pas les ports conservés à la reprise. Collision tardive : `COMMAND-FAILED:docker` avec log du `up`, activation partielle possible. |
| **Secrets/configuration (S)** : aucune clé ni `.env` à fournir pour installation neuve ; secrets techniques générés. Sources suivies SQL/realm/propriétés/Grafana requises. | Génération/lecture de `secrets.json`, permissions/symlinks, identité manifeste, empreintes et propriété ; contrôles auth réels après activation ([private:211](../../scripts/local/runtime.py#L211), [render:244](../../scripts/local/runtime.py#L244), [auth:85](../../scripts/local/keycloak.py#L85)). | Pas de validation agrégée des fichiers/clés/formats avant effets. JSON incomplet → `KeyError`/`JSONDecodeError`; fichier absent → `FileNotFoundError`. **Secrets absents sur instance déjà déployée : `private()` en régénère**, sans vérifier la présence d’anciennes données ; pas de garantie de cohérence avec les mots de passe existants. |
| **Droits filesystem, shell et utilitaires (S/B)** : checkout/état privé/caches/tmp inscriptibles, exécutables autorisés, Bash et outils POSIX usuels. Wrapper froid : unzip ou tar/gzip ; wget ou curl ou fallback JDK. | Erreurs à l’usage ; garde des permissions privées/symlinks explicite ([safe_tree:86](../../scripts/local/runtime.py#L86), [Wrapper:163–257](../../app/mvnw#L163)). | `PRE-PRIVATE-PERMISSIONS`/`PRE-SYMLINK` explicites mais sans action ; autres droits/montages : `PermissionError` ou log Wrapper/Docker. curl et wget ne sont pas deux prérequis obligatoires. Pas de somme SHA Wrapper configurée dans properties : sha256sum/shasum ne sont donc pas une exigence actuelle de ce chemin. |
| **Bruno GUI/navigateur (O)** : nécessaires à l’usage GUI choisi, pas à `start`, au seed/canari ni à la CLI normale. | Guide GUI 4.2.1/4.3.0 ; sandbox Developer, connexions OAuth natives Owner/Guest ([guide](L7_Local_Playground_Bruno.md#L1)). La façade `gui` ne sonde pas l’application. | Instructions et erreurs de session côté collection ; pas un contrôle d’installation de l’app GUI. `bruno --interactive` ouvre un navigateur et écoute le port8765 ([auth:43–73](../../scripts/local/auth.py#L43)), sans prétest de collision ; login CLI normal n’ouvre pas ce listener. |

PostgreSQL, Keycloak, Prometheus, Grafana, `psql` et `pg_dump` sont fournis par les
images ; aucune installation hôte de ces services n’est nécessaire. k6 et les
outils de qualifications spécialisées présents ailleurs dans le dépôt ne sont
pas appelés par ce parcours. Le build utilise `-DskipTests` (pas
`-Dmaven.test.skip=true`) : il peut compiler les tests, sans les exécuter ; aucun
prérequis d’une campagne Maven/architecture globale ne doit être ajouté au
quickstart sur cette seule base.

## 4. Lisibilité et cohérence des diagnostics

Le handler principal ([runtime:1171–1186](../../scripts/local/runtime.py#L1171))
renvoie un JSON `NOT_READY`, `exit`, `control`, `instance`, `evidence` et
`environment=PRESERVED`. Un `Refusal` sort généralement en 2, les exceptions
génériques en 1, le verrou en 3, l’interruption en 130. Ce handler ne couvre
ni le lancement Python ni les imports. Le
[tableau quickstart des codes](L7_Local_Playground_Quickstart.md#L134) annonçant
« usage/prérequis … 2 » ne décrit donc pas toutes les erreurs de prérequis réelles.

`run` ([lignes 52–59](../../scripts/local/runtime.py#L52)) capture stdout/stderr,
mais ne les sauvegarde que si `log` est fourni. Il remplace tout retour non nul
par `COMMAND-FAILED:<exécutable> (see private diagnostics)`. Le fichier manquant
n’est pas nommé dans le JSON d’un `FileNotFoundError` : le handler conserve
uniquement le nom de sa classe. Un timeout devient `TimeoutExpired`, sans
sauvegarde du résultat par cette fonction. Ces échecs techniques **ne sont pas**
des contrôles de prérequis satisfaisants.

Des logs utiles existent pour npm, pull, package et les trois étapes Compose
(`npm.log`, `pull-<role>.log`, `package.log`, `postgres-up.log`, `keycloak-up.log`,
`runtimes-up.log`). Ils n’existent pas nécessairement si le processus n’a pas pu
démarrer ou s’il a expiré. Les diagnostics conteneurs ne commencent que si
`compose.json` existe ; leur propre échec est signalé séparément
([runtime:570–581](../../scripts/local/runtime.py#L570)). Docker absent/arrêté sur
installation neuve n’a donc pas forcément de log explicatif malgré le message.

Le diagnostic Keycloak montre qu’un format utile existe déjà : composant,
contrôle, cause probable et action recommandée, sans jeton ni secret
([KeycloakFailure:19–30](../../scripts/local/keycloak.py#L19),
[guide des diagnostics](L7_Local_Playground_Keycloak_Diagnostics.md#L38)). Les
contrôles de ressources/propriété/drift constituent également des acquis à
conserver. `environment=PRESERVED` signifie rétention de l’environnement,
**pas absence de mutations antérieures** ou rollback global.

## 5. Scénarios d’échec demandés — analyse statique

La colonne « avant effets » distingue état local, préparation (npm/images/build)
et activation des conteneurs. Pour chaque ligne, la validation dynamique reste
**NOT RUN** ; seuls des tests isolés ultérieurs peuvent confirmer les sorties et
l’absence effective d’appels mutateurs.

| Scénario | Comportement actuel déduit / moment | Avant effets ? Lisibilité et correction minimale |
| --- | --- | --- |
| Java absent | À `java --version` si les étapes antérieures passent ; `FileNotFoundError`, ou échec du stub Java système. | Avant activation, après secrets/npm/images. Insuffisant. Sonder disponibilité et version, nommer l’exécutable et indiquer JDK21/PATH/JAVA_HOME. |
| Java incompatible | `java --version` accepte sa sortie ; compilation Java21 peut refuser un JDK17. `JAVA_HOME` peut sélectionner un autre JDK que le PATH ; cache build évite cette compilation. | Après préparation, avant activation ; `package.log` peut expliquer, mais garde explicite absente. Vérifier le JDK effectif de build et `javac`, avec attendu/détecté. Ne pas prétendre que JDK>21 est nécessairement incompatible : c’est un profil non qualifié à définir. |
| Python incompatible/absent | Avant main si parsing/import impossible ; sinon aucun refus sur numéro de version. | Échec immédiat possible sans effet, mais sans contrat de diagnostic ; version ancienne potentiellement acceptée. Petite garde au lanceur avant imports, minimum3.11 et système POSIX explicites. |
| Docker absent | Premier accès Docker : info sur neuf, éventuellement Compose sur reprise. `FileNotFoundError`. | État local déjà créé ; aucun `up` de cette tentative. Insuffisant. Tester CLI puis accès serveur avec message dédié. |
| Docker présent, daemon arrêté | Retour non nul de `docker info` ou `compose ps` → `COMMAND-FAILED:docker`. Permission/contexte inaccessibles donnent la même catégorie. | Avant activation ; stderr de ces sondes non retenu par `run`. Indiquer endpoint inaccessible, suggérer démarrer Docker/vérifier contexte et droits, sans attribuer arbitrairement la cause. |
| Compose indisponible | Reprise : photographie des générations ; neuf : relevé de version après npm/images. Même `COMMAND-FAILED:docker`. | Avant activation, pas avant toute préparation. Tester `docker compose version` avant génération/installation ; préciser plugin requis et profil testé. |
| Maven indisponible quand requis | Absence de Maven système : aucun défaut. Wrapper suivi absent/non exécutable ou distribution impossible à récupérer : exception générique ou `package.log`. | Avant activation, après préparation ; le Wrapper peut déjà écrire son cache. Vérifier fichiers/exécutabilité et prérequis conditionnels du Wrapper. Installer Maven système n’est pas la correction. |
| Node absent/incompatible | Neuf : npm peut échouer avant l’installation ; CLI déjà présente : `bru --version` peut échouer avant le relevé Node. | Avant activation ; diagnostics génériques. Vérifier Node24 et npm lorsque nécessaire avant npm/bru ; ne pas considérer un simple `bru --version` comme preuve complète de compatibilité Node. |
| Mémoire Docker <6 Gio | `PRE-RESOURCES: Docker >=6GiB`, exit2. | Après état local, avant dépendances/activation. Déjà satisfaisant sur seuil total ; ajouter détecté et action serait utile. Aucun contrôle de pression mémoire réelle n’est promis. |
| Port occupé | Occupation initiale évitée par allocation port0. Occupation ultérieure ou port conservé réutilisé : échec du `up` correspondant. Port8765 interactif : `OSError`. | Le `up` peut avoir créé réseau/volume/PG ou démarré d’autres services avant échec. Recontrôle avant activation des ports à publier ; accepter les ports effectivement possédés par les conteneurs conservés. Garder un diagnostic de bind final car la course ne peut être totalement supprimée. |
| Configuration obligatoire absente | Pas de `.env` utilisateur requis. Realm/provisioning : lecture au rendu ; propriétés runtime : seulement après PG/bootstrap ; helper/SQL manquants : import/lecture pendant bootstrap. Manifeste absent au `start` → tentative de nouvelle identité ; au statut → refus. Secrets absents → génération, même si manifeste existant. | Certains manques précèdent activation, d’autres surviennent après PG/SQL. `FileNotFoundError`/`KeyError` peu utiles. Inventaire borné des fichiers nécessaires, validation de forme des secrets/manifeste ; sur instance déployée, refuser les secrets perdus sans régénération ni reset implicite. |

Ces cas sont généralement récupérables en restaurant l’outillage/configuration
puis en relançant explicitement. La rétention et les gardes de propriété
réduisent les risques ; elles ne rendent pas une erreur générique exploitable.
Une perte de secrets d’une instance existante peut exiger une restauration de
sauvegarde ou une décision humaine sur ses données ; aucune régénération ne
doit être recommandée comme réparation transparente.

## 6. Commandes de diagnostic et intérêt de doctor

**Il n’existe pas de commande `doctor`** : liste fermée dans
[`main:1101`](../../scripts/local/runtime.py#L1101). `status` contrôle une instance
connue : propriété, JVM, bases/catalogue, authentification, génération/canari,
Prometheus et Grafana ([status:843–910](../../scripts/local/runtime.py#L843)). Il
écrit un reçu et ouvre des sessions d’authentification ; il ne s’agit ni d’un
diagnostic d’hôte vierge ni d’un préflight sans dépendance à Docker. Il ne
rejoue pas les contrôles Python/JDK/Node/npm/disque de start. Les deux commandes
partagent les contrôles de fonctionnement `current`, l’authentification et
l’observabilité, avec l’attente/seed/canari supplémentaires au démarrage.

`inventory.py` inventorie propriété, ressources attachées et orphelines potentielles
([source](../../scripts/local/inventory.py#L10)) ; il n’analyse pas la toolchain.
`gui` génère l’environnement Bruno et vérifie la discovery ; `bruno` vérifie
synchronisation/version et exécute réellement les parcours. Ni l’un ni l’autre
n’est une sonde de prérequis d’hôte vierge. Les scripts `test_*_installation.py`
sont des campagnes explicites avec fixtures, pas des commandes doctor sans effet.

La priorité est un **préflight automatique de start**, partagé si nécessaire avec
la préparation Bruno. Ajouter `./pocoma-local doctor` peut ensuite exposer les
mêmes résultats sans manifeste ni activation ; son intérêt est le diagnostic
explicite avant installation. Il reste **P3, facultatif avant clôture** : aucune
étape manuelle supplémentaire ne doit devenir obligatoire. Ne pas dupliquer
les seuils/versions entre doctor et start, ni construire un framework de provisioning.

## 7. Écarts et recommandations minimales

Aucun P0 de sûreté ou de dommage irréversible n’est démontré par l’analyse.
Les lacunes essentielles avec échec récupérable sont classées P1 conformément
au mandat. La qualification positive existante ne les exerce pas. Les tests
proposés ci-dessous sont des obligations **futures**, non exécutées sous ce mandat.

| ID / priorité | Défaut et correction minimale | Impact et tests nécessaires après décision humaine | Avant clôture L7-LB ? |
| --- | --- | --- | --- |
| **PRE-01 / P1** | Absence de garde Python et de diagnostic avant imports. Dans le lanceur, détecter `python3`, exiger ≥3.11 avant chargement runtime et signaler système POSIX/module `fcntl` requis. | Faible : lanceur et message uniquement. Tests de sous-processus avec interpréteurs simulés/fixtures isolées : absent, trop ancien, compatible, import non disponible ; aucun dossier d’instance ni appel Docker/npm. | **Oui**, pour le principe « savoir immédiatement si le poste est compatible ». |
| **PRE-02 / P1** | Préflight incomplet et tardif des outils : Git, Docker CLI/daemon, plugin Compose, Node, npm conditionnel, Java/JDK/javac effectifs. Placer les sondes sans mutation avant npm/pull/build/rendu et avant toute branche mutatrice de start ; vérifier Java utilisé par le Wrapper, pas seulement PATH. Nommer l’outil, attendu/détecté et correction ; refuser avec catégorie de prérequis cohérente. | Faible à modéré : fonctions locales et ordre d’appel. Garder JDK21/Node24 comme profils documentés ; ne pas inventer un minimum Engine/Compose. Tests unitaires simulant absence/retour non nul/versions, divergence PATH–JAVA_HOME, cache froid/chaud et plugin absent ; vérifier qu’aucun npm/pull/package/up ne suit le refus. CLI globale et Maven système absents doivent rester acceptables. | **Oui**, noyau du contrat de diagnostic ; aucune nécessité de déplacer Bruno hors de start dans cette correction. |
| **PRE-03 / P1** | Contrôle agrégé absent des entrées distribuées et de la cohérence secrets/instance. Vérifier fichiers indispensables, exécutabilité Wrapper, schéma minimal et encodage/longueur des clés attendues avant activation ; distinguer état NEW d’un état déployé. Si secrets manquants/incomplets dans ce dernier, refuser en préservant données et fichiers. | Modéré mais borné : façade/configuration privée ; pas de changement des secrets applicatifs ni du SQL. Fixtures temporaires : fichier absent, JSON invalide, clé absente/invalide, instance neuve vs existante ; prouver l’absence de génération/remplacement/up en cas incohérent. Ne pas développer un validateur complet du SQL. | **Oui**, particulièrement le refus de régénération pour un état déjà déployé et le diagnostic des fichiers manquants. |
| **PRE-04 / P2** | Recontrôle des ports conservés absent ; bind tardif laisse une activation partielle. Avant `up`, identifier ports déjà possédés par les conteneurs conservés et sonder les autres ; ajouter port/adresse et action à l’échec de publication. Ne pas réallouer silencieusement l’issuer d’une instance existante. | Modéré : prudence pour ne pas refuser sa propre instance. Tests isolés avec sockets/inspections simulées : port libre, étranger, propre, course après sonde, callback8765 interactif. Une fixture Docker séparée est nécessaire pour qualifier la course finale. | Non indispensable si réserve explicite : logs `*-up.log` actuels permettent l’investigation et données conservées. |
| **PRE-05 / P2** | Messages de refus existants sans attendu/détecté/action et messages `COMMAND-FAILED` parfois sans log. Enrichir seulement les sondes d’outillage, pointer les fichiers réellement produits ; compléter mémoire/architecture/Bruno/permissions et corriger la promesse « vérifie les versions » du quickstart. | Faible. Tests des champs autorisés et des codes ; vérifier qu’aucun stderr brut sensible, bearer, secret, URL OAuth ou environnement complet n’est publié. Le défaut Docker critique est traité dans PRE-02, sans exposer aveuglément tous les stderr. | Partie documentaire décrivant la couverture réelle : **oui avec PRE-01/02** ; enrichissement des refus déjà explicites : non. |
| **PRE-06 / P2** | Espace disque mesuré seulement dans ROOT ; RAM totale ne prouve pas la mémoire libre, CPU sans seuil. Documenter exactement la portée et les caches/VM distincts, conserver le profil16Go/VM8Gio et les réserves de capacité. | Faible pour la documentation. Pas de nouveau seuil arbitraire, de benchmark ni de scanner Docker coûteux. Si une nouvelle sonde est décidée : tests sur filesystem distinct et mode indisponible ; qualification de capacité séparée. | Non : réserves explicites suffisantes pour ce jalon ; le seuil6Gio actuel reste acquis. |
| **PRE-07 / P2** | OS/versions Engine–Compose et réseau minimal non précisément contractés. Distinguer profil qualifié macOS ARM64 et compatibilité potentielle Linux/AMD64 ; documenter registres/caches/proxy et refus du daemon inaccessible. | Faible documentaire ; tests sur autres plateformes uniquement si leur support est annoncé. Ne pas imposer un ping réseau à chaque reprise avec cache ni fixer un minimum à partir du seul hôte observé. | Mention du profil qualifié : à conserver. Extension du support et sondes réseau : non. |
| **PRE-08 / P3** | Pas de vue explicite avant installation : doctor peut afficher les mêmes contrôles, versions et corrections, sans npm/pull/build/up ni état obligatoire. | Faible si réutilisation de PRE-02 ; tests assurant partage des règles et absence d’effets. Aucun doctor obligatoire, gestionnaire d’installation ou orchestration nouvelle. | **Non**. |

Le lot minimal recommandé avant clôture regroupe PRE-01, PRE-02, PRE-03 et la
mise en cohérence documentaire associée. Les vérifications ne doivent pas se
limiter aux messages : elles doivent prouver qu’un refus précède les opérations
de préparation/activation correspondantes. Les écritures nécessaires de preuves
privées peuvent être conservées, à condition de ne pas annoncer « aucun effet ».

Après autorisation d’implémenter, déclarer à nouveau le périmètre selon la
politique : tests du lanceur/tooling sur répertoires temporaires et mocks, puis
preuve positive sur fixture distincte si l’ordre de démarrage change. Les
[tests locaux existants](../../scripts/local/test_local.py#L15) couvrent notamment
propriété, verrous/destruction, bootstrap, canari et Bruno ; ils ne constituent
pas une preuve des nouvelles gardes proposées. Les
[campagnes installation](../../scripts/local/test_installation.py#L23) créent des
instances : elles ne doivent pas être exécutées ici par simple commodité.
Aucune campagne Maven globale n’est induite par cet audit documentaire ; la
vérification d’une future correction devra suivre son impact réel.

## 8. Vérification documentaire et publication

Commandes réellement utilisées pour cet audit : lectures `cat`, `sed`, `nl`,
recherches `rg`/`rg --files`, inventaires `ls`/`wc` ; `git status --short` et
`--porcelain=v1`, `git branch --show-current`, `git log`, `git remote -v`,
`git rev-parse HEAD`, `git show`, `git diff --name-only`/`--stat` ;
`git ls-remote origin refs/heads/feat/l7-lb-local-playground`,
`git fetch origin feat/l7-lb-local-playground`, et création d’un worktree détaché
sur la base distante. Une lecture Python `python3 -B -` du seul package-lock
a inventorié `engines`, versions/bin et domaines `resolved` ; aucun module
exécutable du projet n’a été importé. La première lecture du SHA91604558 a échoué
car l’objet n’était pas local ; fetch a permis de consulter les preuves publiées.
La première création du worktree a été refusée par le sandbox, puis autorisée
avec le même périmètre. Aucun de ces incidents n’est un résultat d’échec du produit.

Contrôles de livraison : vérification Python des chemins/numéros de ligne des
liens du rapport, `git diff --check`, inspection du diff indexé et contrôle du
commit pour garantir **un seul fichier ajouté**, puis commit et push explicite
de `HEAD` vers `refs/heads/feat/l7-lb-local-playground` depuis le worktree isolé.
L’identifiant du commit publié est fourni avec le compte rendu de livraison ;
il ne peut pas être inscrit dans son propre contenu sans modifier cet identifiant.

Slices déclarées **NONE** ; franchissement inattendu **NONE** ; gate globale
**NOT REQUIRED** ; full reactor **NOT RUN**, car documentation seule et campagne
Maven interdite par mandat ; bases/migrations **NONE** ; tests dynamiques
**NOT RUN**. Aucune action `start/status/stop/reset/destroy/bruno/gui`, aucun
déploiement, aucune installation de dépendance et aucune mutation de `demo`.

## 9. Impact sur la clôture et verdict motivé

La qualification clean-clone prouve le chemin positif avec des outils compatibles.
Elle ne prouve pas la détection des incompatibilités. Les gardes ressources,
images natives, propriété, empreintes, auth/readiness et canari sont réelles et
restent valides. Aucun défaut applicatif COMMAND/RESULT n’est introduit ou corrigé
par ce document ; les réserves historiques restent attribuées à leurs rapports.

Pour le contrat de distribution demandé, `FileNotFoundError` et
`COMMAND-FAILED:docker` sans outil/cause/action ne permettent pas de savoir
immédiatement quoi corriger. La simple collecte de versions n’impose ni Python
≥3.11 ni le JDK21 effectif ni Node24. Enfin, la lecture tardive des entrées et la
régénération possible de secrets d’un état existant méritent un refus explicite
avant activation. Ces écarts P1 justifient le petit lot préalable décrit ci-dessus ;
ils dépassent un simple confort doctor.

**`L7_LB_PREREQUISITES_CORRECTIONS_REQUIRED`**

La clôture L7-LB reste une décision humaine. Aucune correction n’est implémentée
sous ce mandat ; attendre la décision humaine avant tout lot correctif.
