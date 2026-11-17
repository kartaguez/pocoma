# L7.4 — Architecture du banc et inventaire d'instrumentation

Le [plan de vérification](L7_I4_Technical_Execution_and_Verification_Plan.md) déclare
un impact productif nul. [Matrice contractuelle](L7_I4_SPEC09_Contract_Matrix.csv).
Le banc local livré valide E1 et le scénario E3 après admission ; les adaptateurs
historiques F01-E/F/G et k6 restent disponibles, sans déclaration de banc E2 complet
ou d'admissibilité à la certification.

## Composition et remise à zéro

`run-l7-spec09-e1.py` crée un nom `pocoma-spec09-<UUID>` inédit, un réseau Compose
privé et PostgreSQL sur tmpfs neuf. Il ne monte aucun volume existant et n'expose
aucun port PostgreSQL. Les seules entrées SQL sont celles du bootstrap CURRENT
`setup-v3-database.py` : schémas V3 WRITE/READ, routage, privilèges et maintenance.
Ce bootstrap ne constitue pas une baseline TRUSTED ni une preuve de migration V2.
La remise à zéro consiste à supprimer le seul projet créé, puis à en créer un autre.
Il n'y a ni purge de slots, ni terminalisation artificielle, ni mutation de checkpoint.

Cinq processus exécutent les quatre JAR du packaging courant : WEB WRITE, WEB READ,
Command/Identity, Materialize résultats et Materialize métier. L'image existante
Monolith est utilisée par son ID immuable **uniquement comme support Java 21** ;
son ancien JAR n'est pas exécuté. L'entrypoint vise `/app/e1.jar`, montage en lecture
seule du JAR courant de chaque module. Empreintes des JAR et sources dans chaque reçu.
L'empreinte de l'image n'est pas présentée comme le SHA applicatif.

PostgreSQL : 1 CPU, limite 640 Mio, tmpfs données 512 Mio ; cinq Runtimes : chacun
0,5 CPU, limite 448 Mio, `-Xms64m -Xmx256m -XX:+UseSerialGC`. Deux petits Pots
5/20 initialisés par HTTP, puis contrôlés HTTP et SQL ; seed déterministe commun
`l7-spec09-e1-small-2` à partir d'E1-03. Le premier essai passant E1-02 avait un seed
nommé par projet ; il n'est pas une répétition à snapshot identique d'E1-03.
Le tmpfs exclut toute conclusion sur crash/durabilité du stockage PostgreSQL.
Les conteneurs antérieurs continuent à partager les ressources Docker Desktop :
les mesures locales ne permettent aucune conclusion sur les ressources isolées A/B.

L'émetteur RSA de test ne publie que JWKS ; les JWT sont remis dans un fichier
0600 sous workspace 0700 hors Git. Il atteste `iss/sub/aud/iat/auth_time/exp`.
Pocoma conserve validation JWT et AuthZ. `auth_time` manquait dans l'ancien émetteur ;
la correction porte sur le banc uniquement. Aucun token/binding n'entre dans les reçus.
Le JWKS public écoute sur le port 28090 pour l'accès `host.docker.internal` depuis
les conteneurs. Les autres ports publiés sont limités à loopback (28000/28002 et
28080/28082/28083/28084/28085). Aucun Keycloak existant n'est reconfiguré.

## Déroulement et oracles indépendants

- E1 : 12 échéances ouvertes à 1/s sur deux Pots, exécutées par deux threads ; aucun
  retry d'admission. L'observation conserve retard d'offre, admissions, résultats,
  versions et non-succès. Un Pot ne peut porter deux mutations simultanées dans E1.
- Horloge : `monotonic_ns` côté générateur, UTC pour corrélation des collecteurs.
  Le commit d'admission est encadré par envoi et réception 202 : deux bornes de
  première lecture complète. Percentiles E1 par rang supérieur ; à 12 points le p95
  et le p99 sont le maximum, sans estimation de population contractuelle.
- Sûreté HTTP : identités exactes Pot/Shareholders/Expense, version unique suivante,
  balance calculée indépendamment avec `fractions.Fraction` depuis la balance initiale
  et les poids. Une somme nulle seule ne suffit pas. Pot courant et balance exacte V
  doivent tous deux correspondre aux valeurs attendues.
- Sûreté SQL extérieure : IDs et versions des requêtes HTTP rapprochés des résultats
  et publications WRITE puis résultats/PotVersion/Balance READ ; slots drainés, sources
  terminales, positions de publications denses et compteurs cohérents ; checkpoints
  stables ou croissants, état maintenance READY, aucun nouveau deadlock.
- E3, option séparée : pause du processus avant admission ; lecture PENDING sans
  résultat/événement/complétion ; SIGKILL et état Docker exit137/PID0 ; redémarrage de
  génération différente ; Command et Registration terminent avec effets uniques.
  Ce n'est ni un crash pendant préparation/calcul, ni une expiration de claim acquis.

Le champ `performance=INCONCLUSIVE` et `contractual=NOT_RUN` demeure même lorsqu'E1
et ses oracles locaux passent. E3 a son verdict indépendant et n'entre pas dans la
population de latence E1. Le runner conserve les interruptions et ne relance pas
une campagne automatiquement. Chaque reprise après correction a un nouvel identifiant.

## Collecte observée et limites

| Domaine | Existant réutilisé / collecte | Disponibilité et limite |
|---|---|---|
| Admission | `pocoma_v3_admission_requests_total`, capacity, histogramme HTTP, horodatages client | Observés WEB WRITE ; séparer acceptation HTTP et traitement terminal |
| Discovery/Engine | discovery duration/references, claim operations/acquisition, attempt/phase duration/outcomes, worker cycles/state, lease/renewal/takeover | Observés sur les trois Workers, par Consumption ; timers agrégés, pas preuve de frontière individuelle pour chaque requête |
| Charge persistante | `physical_pending`, SQL sources PENDING, slots par Consumption/état/attemptCount, K par partition | Les slots seuls ne mesurent pas tout le backlog source ; âges de toutes familles et séries longues restent à compléter pour E2 |
| READ | read HTTP/query histogrammes, quota permits/rejections, version/balance via HTTP | API complète W≥V contrôlée ; E1 réserve chaque Pot, donc W=V ; W>V non exercé |
| Reconstruction | read_rebuild state/checkpoints/slots/duration/errors | Séries exposées ; aucune reconstruction injectée dans cette mission |
| JVM/pools | jvm_memory, GC, process_cpu, hikaricp active/idle/pending, threads | Scrapes bruts des cinq processus ; chaque absence indispensable arrête le banc |
| PostgreSQL | pg_stat_activity sans texte SQL, pg_blocking_pids, pg_stat_database, pg_stat_wal, fsync/synchronous_commit, taille DB | Deux transactions READ ONLY distinctes et bornées ; pas de snapshot atomique WRITE+READ prétendu |
| Ressources | docker stats CPU/mémoire/BlockIO/NetIO/PIDs des seuls conteneurs du projet | Échantillons espacés d'attente2 s + coût effectif du collecteur ; aucune intégrale CPU ni pic RSS continu déduit |
| Verrous | PIDs et arêtes de blocage observées | Graphe à des instants échantillonnés ; aucune preuve universelle d'absence de cycle ; E3 n'injecte pas contention des compteurs |
| Coûts Grand Pot | Requêtes/JDBC, EXPLAIN ANALYZE BUFFERS, fractions, allocations/IO, JSON brut/compressé | Non exécutés ; bruts Micrometer ne remplacent pas AUD-V3-013 |

Le collecteur ajoute seulement des observations externes : pas d'instrumentation
Java nouvelle. Les coûts muraux de chaque collecte figurent dans `collectorMs` ;
ils mesurent le travail d'observation, pas sa pénalité causale sur la latence métier.
Aucune comparaison contrôlée avec/sans collecteur n'a été exécutée. Son coût et la
précision des sondes à 25 ms doivent encore être calibrés sur A/B avant E2.
La balance exacte est sondée après le premier Pot complet dans E1 : sa mesure est
une borne supérieure retardée, pas une qualification indépendante du SLO Balance.

## Protections et utilisation

Le collecteur refuse tout conteneur sans label `pocoma.spec09.disposable=true`.
SQL est encadré par `BEGIN READ ONLY` et `statement_timeout=3 s` ; aucune requête
ni mot de passe n'est exporté. Arrêt sur oracle faux, collecteur indispensable absent,
retard injecteur >100 ms, mémoire conteneur >92%, observation E1 >8 s. Ces valeurs
protègent le banc ; elles ne sont pas des seuils SPEC-09. E3 suit la reprise jusqu'à
l'échéance SQL persistée plus 60 s, hors statistiques performance. L'espace du workspace est contrôlé
avant création des conteneurs (au moins1 Gio). Chaque sortie doit être neuve.

```sh
# Depuis app : packaging, sans PASS de tests Maven
./mvnw --batch-mode --no-transfer-progress -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am package -DskipTests

# Depuis la racine ; deux chemins absolus neufs ; images locales épinglées
python3 -B scripts/performance/run-l7-spec09-e1.py \
  --workspace /private/tmp/pocoma-spec09-new-private-workspace \
  --output /private/tmp/pocoma-spec09-new-evidence \
  --java-image sha256:be342f85ced5a78363529b23c3712b159bda24ef9ffb6a9dc533cde4543af6b5 \
  --postgres-image sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73 \
  --crash-after-admission
```

Pour E2 : provisionner les deux machines A, préparer/restaurer snapshots vérifiés,
figer protocole/mix/offre/régression/timeout/sondes avant mesure, compléter le collecteur
à distance et les jalons Balance/DeletePot/âges/Grand Pot. Le lanceur E1 local n'est
pas un ordonnanceur E2 prêt pour toutes les 13 familles. Ne pas exécuter les anciens
R9/R10/R11 ni générateur historique désactivés par L73-ADMIN-01. La comparaison
historique A/B nécessite son propre banc admissible ; elle reste NOT_RUN.
