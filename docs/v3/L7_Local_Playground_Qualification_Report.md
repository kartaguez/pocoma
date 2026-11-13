# L7-LB / Jalon 1 — qualification du playground local

Mise à jour du14 novembre : le [correctif OAuth natif GUI](L7_Local_Playground_GUI_Auth_Fix_Report.md) possède ses propres preuves au SHA e54cadd4. Le rapport ci-dessous décrit la **publication initiale f1ccd0dc du13 novembre** ; ses comparaisons d’entrées ne sont pas une certification des changements GUI ultérieurs. LOCAL_PLAYGROUND_PARTIAL reste inchangé.

13 novembre 2026, Europe/Paris. Verdict proposé : **LOCAL_PLAYGROUND_PARTIAL**.
Le runtime local, les deux installations et les parcours publics CLI sont
qualifiés dans le périmètre ci-dessous. Le **parcours rendu dans Bruno GUI
4.2.1 avec connexion humaine n'a pas été exécuté** : cette preuve reste due
avant de proposer LOCAL_PLAYGROUND_READY_FOR_HUMAN_ACCEPTANCE sans cette réserve.
L'acceptation humaine n'est pas prononcée. Aucun tag, aucune certification V3.

## Références et attribution

- SHA initial propre : `2a4e7fd8a79d798b4bcb983a0d5f9466bc5241aa`.
- Branche dédiée : `feat/l7-lb-local-playground`.
- SHA technique testé propre : `69f327f6707635cea8a74f329f7d2d9c3ce5d3d6`.
- Empreinte des entrées opérationnelles :
  `7d9158a2062dbe41df1a635d5179110eb024842e11f74ddd2aede92bae250e18`.
- La publication descendante ajoute rapports/preuves et la copie exacte du
  contrôle de callback exécuté. Elle ne change ni app, runtime, bootstrap,
  auth, canari, configuration ou oracle Bruno. La
  [comparaison des entrées](../testing/evidence/L7-LB-J1/69f327f6/publication-input-comparison.json)
  l'atteste ; les résultats restent attribués au SHA exécuté. Le SHA final de
  publication est celui de la tête de branche, communiqué dans la livraison,
  sans inventer dans un commit son propre SHA futur.

Preuves durables : [index et hashes](../testing/evidence/L7-LB-J1/README.md),
[reçu de qualification](../testing/evidence/L7-LB-J1/69f327f6/qualification.json),
[commandes exactes](../testing/evidence/L7-LB-J1/69f327f6/commands.json),
[configuration expurgée](../testing/evidence/L7-LB-J1/69f327f6/manifest-expurgated.json).
Les secrets/configurations privées et les volumes restent hors Git.

## Environnement et installations

Mac **Apple M4, 16 Gio**, macOS27.0/26A428 ; Docker ARM64, 10 CPU,
8 319 504 384 octets de mémoire VM. JDK hôte Temurin21, JRE conteneur21.0.8,
Python3.14.7, Node24.15.0, Maven Wrapper3.9.14, Bruno CLI4.3.0.
Images natives et digests identifiés dans le manifeste ; PG17.6-alpine,
Keycloak26.7.0, Temurin21.0.8_9-jre, Prometheus3.5.0. Pas d'émulation amd64.

| Installation | État préalable | Résultat observé |
|---|---|---|
| A `j1-a-2ad2a840` | Instance neuve, aucun volume/realm/donnée associé ; checkout auteur avec compilation chaude | READY/code0, 42,1 s ; cinq JVM, PKCE, seed public et canari V2 |
| B `j1-b-2ad2a840` | Checkout Git indépendant sparse, **aucun target, JAR ni état local préalable**, volumes/realm neufs | READY/code0, 51,7 s ; build neuf de l'application, canari V2 ; PG01 PASS |
| B après reset | Confirmation exacte, arrêt et destruction du seul volume B possédé ; nouveau UUID d'instance | READY/code0, 41,9 s ; état demo sémantique restauré, canari V2 |
| `demo` laissée disponible | Nouvelle instance dans le checkout de livraison | READY/code0 et PG01 PASS ; URLs dans son reçu et `./pocoma-local start` |

B partage les **images de dépendances et le cache Maven** du Mac. Ce n'est pas
un second hôte ni une qualification tous caches froids. Le clone local partage
les objets Git immuables ; son arbre de travail et ses builds sont distincts.
Le checkout sparse évite les archives historiques volumineuses, sans retirer
aucune source nécessaire. La tentative préalable de clone complet a rempli le
disque ; seule cette copie temporaire incomplète a été retirée. Après collecte,
les seuls outputs de build/caches redondants créés par cette mission ont été
libérés ; tous les volumes et diagnostics d'instance sont conservés.

## Contrôles et parcours

| Contrôle | Observation / verdict |
|---|---|
| Tests locaux de sûreté | **15/15 PASS**, root et checkout neuf ; puis15/15 après ajout de la copie du test callback. Verrous, symlink, cible inconnue/étrangère, reset, canari ancien, balance inexacte, inventaire Bruno vide, transaction et reprise bootstrap |
| Bruno A et B | **1/1 test nommé PG01 à chaque exécution**, zéro failure/error/skip ; inventaire exact PG01, respectivement53 et56 requêtes publiques observées. Les polls expliquent la différence de nombres |
| Parcours | Registration réelle (ou refus TARGET_ALREADY_BOUND contrôlé), binding, CreatePot/replay, résultats, Pot/balance, invitation confidentielle et acceptation, Expense10/3 puis20/3, soldes ±5/3 puis ±10/3, UpdatePot, Delete terminalV7 et histoireV6 |
| Restart sans duplication | A : même nombre de Pots ; canari **V2→V3**, nouvelle intention/contenu lu dans READ ; start7,0s |
| Worker critique arrêté | status **NOT_READY/code1**, JVM-results-worker FAIL ; aucun ancien canari ne masque la panne |
| Reprise réelle | Fenêtre PG capturée **236,188409s**, attendue sans mutation ; start **READY en246,7s**, nouveau canariV4. Aucun slot purgé ni bail raccourci |
| Observabilité arrêtée | status **DEGRADED/code4**, business AVAILABLE ; cinq scrapes sont exigés pour l'état outillage AVAILABLE |
| Status après stop | **NOT_READY/code1**, business UNAVAILABLE, canari STALE_OR_UNPROVEN ; contrôles actuels et diagnostics conservés |
| Concurrence inter-instance | B refusée/code2 tant que A active ; aucune fermeture automatique d'A |
| Reset sûr | Mauvaise confirmation refusée2, dry-run0, reset explicite0 ; labels/UUID et cible vérifiés |
| Isolation physique | Hash de **tous les fichiers du volume A arrêté** identique avant/après reset B : `200860e952c13341b4660659bfb046c44a8e2bed0ec3c84aa407d1b0597b4b1e` ; conteneurs externes préexistants conservés |
| Bootstrap interrompu | Fixture PG distincte `j1-bootstrap-2ad2a840` ; division par zéro injectée avant marqueur, absence physique du schéma Command/marqueur après rollback, relance réussie et marqueur unique ; assertions exécutées dans le test d'installation |
| PKCE et callback | Missing/plain refusés par Keycloak ; Code+S256 réel. Helper interactif testé avec session navigateur **HTTP automatisée**, state forgé→400, callback réelle→200, token accepté par ressource→200 ; aucun rendu GUI/humain revendiqué |
| Secrets | Scan des preuves publiées contre les credentials/clés de toutes les fixtures et les formes JWT : PASS ; pas de bearer/password dans argv, aucun reporter JSON stockant les bodies |

La [preuve callback](../testing/evidence/L7-LB-J1/69f327f6/interactive-protocol.json)
exécute le même helper que `login`, mais ne remplace pas la preuve humaine GUI.
Le simulateur HTTP initial omettait les cookies Secure Keycloak, provoquant un
timeout ; le harness a été corrigé pour reproduire les cookies du navigateur,
sans modification de `auth.py`. Ce diagnostic reste identifié dans les essais.

## Commandes exécutées et frontière de preuve

Depuis la racine, sauf Maven indiqué :

```sh
python3 -B -m unittest discover -s scripts/local -p 'test_*.py'
python3 -B scripts/local/test_installation.py --second-checkout /tmp/pocoma-lb-fresh-8012fb93 --output /tmp/pocoma-lb-evidence-69f327f6
./pocoma-local start --instance demo
./pocoma-local bruno --instance demo
./pocoma-local status --instance j1-a-2ad2a840
```

Le contrôle callback a été exécuté depuis `/tmp/pocoma-lb-interactive-protocol.py`,
puis copié **octet pour octet** dans `scripts/local/test_interactive_protocol.py`.
Commande reproductible après le parcours Bruno, depuis la racine :

```sh
python3 -B scripts/local/test_interactive_protocol.py --instance demo --output /tmp/pocoma-callback-new.json
```

Le test d'installation conserve les argv exacts de start/status/stop/reset/Bruno
et les résultats. Les builds réels de la façade sélectionnent les quatre
ancres, en ordre alphabétique :

```sh
./app/mvnw -f app/pom.xml -pl runtime-web-read,runtime-web-write,runtime-worker-materialize,runtime-worker-process -am package -DskipTests
```

La préparation initiale a aussi exécuté depuis `app/` :

```sh
./mvnw -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am package -DskipTests
```

**Build SUCCESS n'est pas test PASS**. Aucun test Java canonique n'est déclaré
clos par ce package ou par les parcours externes. Scope déclaré avant code :
LOCAL DISTRIBUTION / orchestration et bootstrap CURRENT V3 ; slices Maven
modifiées **NONE**, consommateurs WEB/COMMAND/PROJECTION exercés via cinq JVM.
Aucun changement sous `app/`, aucune dépendance ni règle d'isolation modifiée.
Franchissement inattendu : **NONE**. Gate Architecture : **NOT REQUIRED / NOT
RUN**. Réacteur complet : **NOT REQUIRED / NOT RUN**, Jalon1 ne clôturant ni
L7/I6 ni une intégration main. DB **CURRENT V3**, baseline TRUSTED NONE, aucune
chaîne historique V2 exécutée. Prochaines gates globales : I6 selon politique.

## Échecs conservés et décision

Les essais ne sont pas effacés : [registre des tentatives](../testing/evidence/L7-LB-J1/attempts/preparation.json).
Au SHA8012fb93, le polling30s ne couvrait pas le bail physique restant après
arrêt du worker ; correction d'orchestration au SHA81a73e78, sans toucher le
produit ni augmenter le polling public. Ce SHA a ensuite révélé le garde disque
uniforme4Gio appliqué abusivement à la reprise en cache ; correction69f327f6.
La campagne finale distincte ci-dessus a passé ces conditions avec les vrais
oracles. Aucune répétition inchangée d'un échec n'a été convertie en PASS.

**Aucun défaut produit démontré n'a nécessité une correction Java/SQL.** Les
contrôles ne constituent pas une absence exhaustive de défauts. I1b/522
obligations, I4, performances I5, gates/audit/acceptation I6, SPEC09 A/B et
réserves historiques restent ouverts avec leurs verdicts ; voir le
[registre nominatif](L7_Local_Playground_Reservations.md).

Pour lever la réserve Jalon1 : suivre le [guide GUI](L7_Local_Playground_Bruno.md),
exécuter login avec le navigateur puis PG01 dans Bruno, consigner version GUI,
connexion interactive et résultat du test, corriger/requalifier toute anomalie,
puis soumettre le verdict à l'humain. Cette revue ne certifiera pas
V3_REFERENCE_BASELINE_ACCEPTED ni les obligations I4–I6.
