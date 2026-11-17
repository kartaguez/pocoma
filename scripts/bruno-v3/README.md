> **Playground interactif de reference : [Pocoma V3 Playground](../bruno-playground/README.md).** Ce corpus est un banc de qualification specialise (fixtures incluses), pas le client interactif du playground.

# Pocoma V3 — Recette Bruno

## Installation vierge L7.2

Depuis la racine, préparer les dépendances et les quatre artefacts exécutables du même état de sources. Le packaging prépare les JARs. Les gates Maven sont exécutées séparément.

```sh
npm --prefix scripts/bruno-v3 ci --ignore-scripts --no-audit --no-fund
(cd app && ./mvnw -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am package -DskipTests)
python3 -B scripts/bruno-v3/qualify-installation.py --output /chemin/neuf/preuves-l7
```

Le lanceur utilise Docker et Java 21, les images locales PostgreSQL 17 Alpine et Keycloak 26.7.0, le bootstrap V3 actuel et les quatre JARs issus de Maven. Il crée un réseau et deux conteneurs sous un nom aléatoire, utilise des ports locaux libres, démarre les cinq processus et vérifie leur disponibilité individuellement. Le script d'authentification existant obtient trois sessions Keycloak de test par PKCE S256. Le nettoyage cible les processus et conteneurs créés pendant cette invocation. Choisir un nouveau répertoire de preuves par tentative.

L'ordre de la recette est B2 (Registration, commandes, résultats et Unlink), B4 (Invitation) puis B501–B502 (authentification, routes retirées et diagnostics). Dans B2, les numéros de séquence ordonnent le cycle de vie du Pot, puis l'idempotence, la révocation du binding et la Registration suivante. La recette fournit les URL WRITE/READ, l'issuer, les JWT et les sujets via l'environnement du sous-processus. Les identifiants de Pot, Shareholder et Expense sont générés pour chaque exécution. Les reçus HTTP excluent les corps et les en-têtes. Le lanceur vérifie l'inventaire attendu et compare les valeurs des résultats WRITE/READ avec un observateur SQL indépendant.

Les contrôles spécialisés B1 restent exécutables sur leur fixture de lecture, notamment pour les versions historiques injectées, la pagination étendue et le retard de matérialisation contrôlé. Le consolidateur B5 historique documente les 70 scénarios Q5, dont B3 exécuté avant le retrait.

## Sous-suite B1 avec fixture

B1 couvre les huit routes I1, 32 scénarios nommés B01–B32 et leurs appels secondaires. **READ_FIXTURE — NO PUBLIC ADMISSION PROOF** : les états READ sont chargés par un injecteur de test distinct. Ce banc B1 porte sur les consultations et utilise des états READ injectés. [Matrice](Coverage.md), [plan et oracles](../../docs/v3/L5_I1_Implementation_and_Qualification_Plan.md).

Outillage verrouillé : **Bruno CLI 4.3.0**, Node **24.15.0** observé à la préparation, installation `npm ci --ignore-scripts --no-audit --no-fund` depuis ce dossier et `node_modules/.bin/bru --version`. `package-lock.json` fixe les dépendances et leurs intégrités. Aucun `latest`. Les scripts emploient les [API officielles de Bruno](https://github.com/usebruno/bruno-docs/blob/main/testing/script/javascript-reference.mdx) avec le sandbox `developer`, nécessaire pour le reçu de requêtes et le signal de publication local B30.

Depuis ce dossier :

```sh
node_modules/.bin/bru run -r "01 Identity and READ" --env Local --sandbox developer --noproxy --reporter-json /absolute/new/bruno.json --reporter-skip-all-headers --reporter-skip-body
```

Un seul environnement par campagne. `--env Qualification` remplace Local lorsqu'un environnement autorisé et le fournisseur réel sont disponibles. `qualification.invalid` est un emplacement volontairement non opérationnel, jamais une URL de service présumée. Exécution d'un parcours autonome : `node_modules/.bin/bru run -r "01 Identity and READ/01 Own Binding" --env Local --sandbox developer`. Elle ne qualifie pas B1 entier.

Le propriétaire du banc injecte hors Git les variables de processus : `POCOMA_I1_BASE_URL`, `POCOMA_I1_bearerOwner`, `POCOMA_I1_bearerOther`, `POCOMA_I1_bearerSameUser`, `POCOMA_I1_bearerInactive`, `POCOMA_I1_bearerAbsent`, `POCOMA_I1_bearerForged`, `POCOMA_I1_bindingOwner`, `POCOMA_I1_bindingOther`, `POCOMA_I1_REQUEST_RECEIPTS` (nouveau chemin JSONL) et `POCOMA_I1_PUBLISH_SIGNAL` (nouveau chemin de handshake B30). Absence = erreur, aucun skip. Aucun bearer/token en URL ou argument CLI. Les environnements suivis ne contiennent que des paramètres publics. Les headers/corps sont exclus du reporter et les fichiers produits sont contrôlés contre tous les secrets injectés avant archivage.

Le banc reproductible livré est `V3ReadHttpFixture.main` dans les tests WEB : PostgreSQL 17 épinglé par digest, schéma/role jetables, injecteur et observateur administratifs distincts du rôle HTTP SELECT seul, JWKS/RSA éphémère, vrai Resource Server et vrai serveur HTTP. Le propriétaire de campagne le lance après T1 sur le classpath compilé conservé dans `runtime-web-read/target/i1-classpath.txt`, avec `java -Xmx512m`. Les clés privées/JWT ne sont jamais écrits. Le processus enfant Bruno reçoit les secrets uniquement dans son environnement. La fixture est détruite après la fin du CLI et l'arrêt du serveur.

Corpus : EUI `owner/other/same/inactive/absent` sous issuer local ; owner et same partagent le même User, leurs tokens/résultats restent distincts. UUID `...0300` Pot V1/V2, 200 Expenses et soldes ±1/3 ; `...0301` interdit ; `...0302` V1 lisible puis D=2 terminal sans Balance D ; `...0303` retard de Balance ; `...0304` Pot légitime trop gros refusé sans troncature ; navigation 205 IDs `...10001` à `...10205`, premier Pot ouvrable avec ses propres droits. Résultats `...0401/402` Command succès/rejet, `...0403/404` Registration succès/rejet, `...0407` Unlink réussi. Les fixtures ne dépendent d'aucune admission précédente ou V2.

B30 : premières observations réelles 404 des résultats Command `...0405` et Identity `...0406`, puis signal local dans un répertoire temporaire réservé au banc ; l'injecteur séparé publie les résultats Command `...0405` et Identity `...0406`. Aucun endpoint de contrôle. Polling limité à quatre appels par résultat, délais 1/2/4 s, 5 s maximum par appel, échéance totale de 20 s vérifiée par horloge monotone. 404/503/200 restent distincts, arrêt sur refus déterminant, timeout = échec/inconclusif. Qualification distante exige un mécanisme de publication contrôlée équivalent administré hors HTTP ; une absence de ce mécanisme ne devient pas PASS.

Les assertions approfondies des scripts sont exécutées et peuvent faire échouer la requête même hors du bloc `test`. Chaque scénario écrit son reçu seulement après toutes ses assertions ; l'union exacte B01–B32, les erreurs de scripts et le code CLI sont contrôlés avec le reporter. Le nombre de scénarios, de tests nommés et de requêtes physiques est distingué. Les exemples sont fictifs ; aucune réponse de binding réelle n'est exportée.

Limites initiales : batch 50, corps 65 536 octets, sortie 4 194 304 octets, navigation maximum 100/défaut 20, SQL Q=2 et HTTP en vol=2. Un Pot légitime >4 Mio peut être temporairement indisponible ; aucun manifeste n'est tronqué. B1 Local ne qualifie aucun fournisseur AuthN réel, ou capacité de production.

La version 4.3.0 exige `-r` pour les sous-dossiers : sans cette option elle peut sortir 0 avec zéro requête. Le banc refuse ce résultat et tout inventaire différent de B01–B32. Chaque requête principale a `settings.timeout=5000`.

WEB autonome : activer uniquement `v3-http-read`, désactiver Flyway, OSIV et DB health ; fournir `pocoma.v3.http.issuer`, `.audience`, `.jwk-set-uri` et `.read.url`, `.read.username`, `.read.password` par configuration/secrets hors Git. La composition désactive les assemblages automatiques JDBC/JPA hérités, possède un seul pool READ de deux connexions et ne démarre aucun Worker. Monolithe : paire exacte `v3-read-worker,v3-http-read`, autorisations DEV/cibles existantes, limites READ explicitement 50/100/16000000 ; HTTP partage son propriétaire READ Business et Q=2. Les autres mélanges de profils et HTTP sur PROJECTION sont refusés. AuthN réel et production restent non qualifiés.

Le banc B1 ferme chaque connexion HTTP (`Connection: close`, requêtes principales et secondaires). Cela évite que les agents distincts des appels scriptés retiennent les 16 connexions transport en idle. Ce réglage de client ne relève aucune borne serveur et ne modifie pas le timeout de 5 s.

L5/I2 ajoute `02 Admission and Commands` (B201–B224). La fixture complète est lancée par `python3 -B scripts/testing/verify.py INTEGRATION l5-i2` depuis la racine, après son dry-run ; elle fournit JWT/JWKS, autorités SQL distinctes et le handshake interne L3. Les résultats publics ne sont pas injectés. Voir la [recette d’admission](../../docs/v3/L5_I2_Admission_Operations.md) et [Coverage](Coverage.md). B1 conserve sa fixture et ses variables existantes ; les variables I2 ne sont activées que pour B2.
