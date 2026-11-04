# Pocoma V3 — B1 Identity and READ

B1 couvre les huit routes I1, 32 scénarios nommés B01–B32 et leurs appels secondaires. **READ_FIXTURE — NO PUBLIC ADMISSION PROOF** : les états READ sont chargés par un injecteur de test distinct. Aucun parcours public Registration, Link ou Invitation n'est livré. [Matrice](Coverage.md), [plan et oracles](../../docs/v3/L5_I1_Implementation_and_Qualification_Plan.md).

Outillage verrouillé : **Bruno CLI 4.3.0**, Node **24.15.0** observé à la préparation, installation `npm ci --ignore-scripts --no-audit --no-fund` depuis ce dossier et `node_modules/.bin/bru --version`. `package-lock.json` fixe les dépendances et leurs intégrités. Aucun `latest`. Les scripts emploient les [API officielles de Bruno](https://github.com/usebruno/bruno-docs/blob/main/testing/script/javascript-reference.mdx) avec le sandbox `developer`, nécessaire pour le reçu de requêtes et le signal de publication local B30.

Depuis ce dossier :

```sh
node_modules/.bin/bru run -r "01 Identity and READ" --env Local --sandbox developer --noproxy --reporter-json /absolute/new/bruno.json --reporter-skip-all-headers --reporter-skip-body
```

Un seul environnement par campagne. `--env Qualification` remplace Local lorsqu'un environnement autorisé et le fournisseur réel sont disponibles. `qualification.invalid` est un emplacement volontairement non opérationnel, jamais une URL de service présumée. Exécution d'un parcours autonome : `node_modules/.bin/bru run -r "01 Identity and READ/01 Own Binding" --env Local --sandbox developer`. Elle ne qualifie pas B1 entier.

Le propriétaire du banc injecte hors Git les variables de processus : `POCOMA_I1_BASE_URL`, `POCOMA_I1_bearerOwner`, `POCOMA_I1_bearerOther`, `POCOMA_I1_bearerSameUser`, `POCOMA_I1_bearerInactive`, `POCOMA_I1_bearerAbsent`, `POCOMA_I1_bearerForged`, `POCOMA_I1_bindingOwner`, `POCOMA_I1_bindingOther`, `POCOMA_I1_REQUEST_RECEIPTS` (nouveau chemin JSONL) et `POCOMA_I1_PUBLISH_SIGNAL` (nouveau chemin de handshake B30). Absence = erreur, aucun skip. Aucun bearer/token en URL ou argument CLI. Les environnements suivis ne contiennent que des paramètres publics. Les headers/corps sont exclus du reporter et les fichiers produits sont contrôlés contre tous les secrets injectés avant archivage.

Le banc reproductible livré est `V3ReadHttpFixture.main` dans les tests WEB : PostgreSQL 17 épinglé par digest, schéma/role jetables, injecteur et observateur administratifs distincts du rôle HTTP SELECT seul, JWKS/RSA éphémère, vrai Resource Server et vrai serveur HTTP. Le propriétaire de campagne le lance après T1 sur le classpath compilé conservé dans `runtime-web-api/target/i1-classpath.txt`, avec `java -Xmx512m`. Les clés privées/JWT ne sont jamais écrits. Le processus enfant Bruno reçoit les secrets uniquement dans son environnement. La fixture est détruite après la fin du CLI et l'arrêt du serveur.

Corpus : EUI `owner/other/same/inactive/absent` sous issuer local ; owner et same partagent le même User, leurs tokens/résultats restent distincts. UUID `...0300` Pot V1/V2, 200 Expenses et soldes ±1/3 ; `...0301` interdit ; `...0302` V1 lisible puis D=2 terminal sans Balance D ; `...0303` retard de Balance ; `...0304` Pot légitime trop gros refusé sans troncature ; navigation 205 IDs `...10001` à `...10205`, premier Pot ouvrable avec ses propres droits. Résultats `...0401/402` Command succès/rejet, `...0403/404` Registration succès/rejet, `...0407` Unlink réussi. Les fixtures ne dépendent d'aucune admission précédente ou V2.

B30 : premières observations réelles 404 des résultats Command `...0405` et Identity `...0406`, puis signal local dans un répertoire temporaire réservé au banc ; l'injecteur séparé publie les résultats Command `...0405` et Identity `...0406`. Aucun endpoint de contrôle. Polling limité à quatre appels par résultat, délais 1/2/4 s, 5 s maximum par appel, échéance totale de 20 s vérifiée par horloge monotone. 404/503/200 restent distincts, arrêt sur refus déterminant, timeout = échec/inconclusif. Qualification distante exige un mécanisme de publication contrôlée équivalent administré hors HTTP ; une absence de ce mécanisme ne devient pas PASS.

Les assertions approfondies des scripts sont exécutées et peuvent faire échouer la requête même hors du bloc `test`. Chaque scénario écrit son reçu seulement après toutes ses assertions ; l'union exacte B01–B32, les erreurs de scripts et le code CLI sont contrôlés avec le reporter. Le nombre de scénarios, de tests nommés et de requêtes physiques est distingué. Les exemples sont fictifs ; aucune réponse de binding réelle n'est exportée.

Limites initiales : batch 50, corps 65 536 octets, sortie 4 194 304 octets, navigation maximum 100/défaut 20, SQL Q=2 et HTTP en vol=2. Un Pot légitime >4 Mio peut être temporairement indisponible ; aucun manifeste n'est tronqué. B1 Local ne qualifie aucun fournisseur AuthN réel, consentement ou capacité de production.

La version 4.3.0 exige `-r` pour les sous-dossiers : sans cette option elle peut sortir 0 avec zéro requête. Le banc refuse ce résultat et tout inventaire différent de B01–B32. Chaque requête principale a `settings.timeout=5000`.

WEB autonome : activer uniquement `v3-http-read`, désactiver Flyway, OSIV et DB health ; fournir `pocoma.v3.http.issuer`, `.audience`, `.jwk-set-uri` et `.read.url`, `.read.username`, `.read.password` par configuration/secrets hors Git. La composition désactive les assemblages automatiques JDBC/JPA hérités, possède un seul pool READ de deux connexions et ne démarre aucun Worker. Monolithe : paire exacte `v3-read-worker,v3-http-read`, autorisations DEV/cibles existantes, limites READ explicitement 50/100/16000000 ; HTTP partage son propriétaire READ Business et Q=2. Les autres mélanges de profils et HTTP sur PROJECTION sont refusés. AuthN réel et production restent non qualifiés.

Le banc B1 ferme chaque connexion HTTP (`Connection: close`, requêtes principales et secondaires). Cela évite que les agents distincts des appels scriptés retiennent les 16 connexions transport en idle. Ce réglage de client ne relève aucune borne serveur et ne modifie pas le timeout de 5 s.
