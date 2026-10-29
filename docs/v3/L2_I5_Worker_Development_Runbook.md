# L2 I5 — Worker de développement

Statut : implémentation sous GO humain du 28 octobre 2026 ; preuves et limites dans [le rapport](L2_I5_Execution_Report.md) et [le manifeste](L2_I5_Proof_Manifest.json). Le [plan amendé](L2_I5_Implementation_Plan.md) reste le document historique de conception. Ce guide ne constitue pas une distribution L8.

Le runtime utilise le vrai `ExecutePotCommand` et une autorité Identity **fixture** (`i5-dev/actor`). Les deux UUID `a5000000-0000-4000-8000-000000000001` et `...0002` désignent seulement des probes READ, jamais des projections L4. Les neuf contrats métier restent déclarés ; seuls Command et les probes explicitement autorisées sont exécutables. Aucun parcours Bruno V3 métier, aucune nouvelle API HTTP d’injection. Health et Prometheus sont des endpoints d’exploitation locaux.

## Préparer une cible jetable neuve

Prérequis : JDK21, Python3, Docker, Maven wrapper ; `psql` local **ou** `POCOMA_I5_PSQL_CONTAINER` explicitement désigné pour le même PostgreSQL. Les scripts refusent les identités/bases préexistantes et ne nettoient rien automatiquement. Ne pas utiliser une base d’utilisateur. La préparation administrative, séparée du Worker, crée quatre rôles distincts et deux bases distinctes ; WRITE/READ ont leurs migrations V3 actuelles. Elle révoque TEMP de PUBLIC dans ces seules bases de développement.

Depuis la racine du dépôt :

```bash
./app/mvnw -f app/pom.xml -pl runtime-command-consumption-worker -am package -DskipTests
export POCOMA_I5_ADMIN_USER=postgres
# Définir ces secrets dans l’environnement, sans les écrire dans Git ni les logs :
# POCOMA_I5_ADMIN_PASSWORD, POCOMA_I5_WRITE_PASSWORD, POCOMA_I5_READ_PASSWORD,
# POCOMA_I5_REACTIVATION_PASSWORD, POCOMA_I5_INJECTOR_PASSWORD.
export POCOMA_I5_REACTIVATION_ROLE=i5_demo_reactivation
export POCOMA_I5_INJECTOR_ROLE=i5_demo_injector
export POCOMA_I5_PSQL_CONTAINER=pocoma-i5-demo
# Le mot de passe est transmis par l’environnement Docker, sans valeur dans argv.
export POSTGRES_PASSWORD="$POCOMA_I5_ADMIN_PASSWORD"
docker run -d --name "$POCOMA_I5_PSQL_CONTAINER" -e POSTGRES_PASSWORD \
  -p 127.0.0.1:55435:5432 \
  postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73
# Attendre explicitement pg_isready avant prepare.
docker exec "$POCOMA_I5_PSQL_CONTAINER" pg_isready -U postgres
export POCOMA_I5_ADMIN_URL=jdbc:postgresql://localhost:55435/postgres
server_ip=$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$POCOMA_I5_PSQL_CONTAINER")
export PGPASSWORD="$POCOMA_I5_ADMIN_PASSWORD"
# Utiliser la représentation PostgreSQL exacte (par exemple 172.17.0.2/32).
export POCOMA_I5_SERVER_ADDR="$(docker exec -e PGPASSWORD "$POCOMA_I5_PSQL_CONTAINER" \
  psql -X -h "$server_ip" -U postgres -d postgres -Atc 'select inet_server_addr()::text')"
```

Créer un fichier privé, hors dépôt, dont les secrets restent des références d’environnement :

```bash
export POCOMA_I5_CONFIG=/private/tmp/pocoma-i5-demo.properties
python3 - <<'PY'
import os
from pathlib import Path
p='pocoma.v3.worker.'
v={p+'enabled':'true',p+'environment':'DEV',p+'fixture-identity':'true',
   p+'technical-probes':'true',p+'worker-name':'i5-demo',p+'worker-limit':'4',
   p+'consumption-limits':'Command=1,I5ReadPotProbe=2,I5ReadCompletionProbe=3',
   p+'partitions':'Command=0|1|2|3;I5ReadPotProbe=0|1|2|3;I5ReadCompletionProbe=0|1|2|3'}
targets=[]
for a in ('write','read'):
    db='i5_demo_'+a; role='i5_demo_'+a; schema='worker_v3'
    for k,x in [('database',db),('schema',schema),('username',role)]:
        v['pocoma.v3.pilot.'+a+'.'+k]=x
    for pool in ('business','control'):
        key=p+a+'-'+pool+'.'
        v[key+'url']='jdbc:postgresql://localhost:55435/'+db+'?currentSchema='+schema
        v[key+'username']=role
        v[key+'password']='${POCOMA_I5_'+a.upper()+'_PASSWORD}'
    targets.append(','.join(['localhost','55435',db,schema,role,os.environ['POCOMA_I5_SERVER_ADDR'],'5432']))
v[p+'dev-targets']=';'.join(targets)
path=Path(os.environ['POCOMA_I5_CONFIG'])
fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f:
    f.write(''.join(k+'='+x+'\n' for k,x in v.items()))
PY
python3 scripts/v3/worker-dev.py prepare --config "$POCOMA_I5_CONFIG"
python3 scripts/v3/worker-dev.py validate --config "$POCOMA_I5_CONFIG"
```

Résultats attendus : migrations V3, scripts administratifs WRITE/READ réussis, `I5_DEV_VALIDATED` puis `I5_DEV_PREPARED`. Un refus peut laisser une préparation partielle ; inspecter cette cible jetable, ne pas relancer en supposant un reset. `prepare` est exclusivement une première installation. Restart n’appelle jamais prepare ni Flyway.

## Démarrer, injecter, observer et arrêter

```bash
export POCOMA_I5_JAR="$(pwd)/app/runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar"
java -jar "$POCOMA_I5_JAR" \
  --spring.profiles.active=v3-consumption-worker-dev \
  --spring.config.additional-location="file:$POCOMA_I5_CONFIG" \
  > /private/tmp/pocoma-i5-demo.log 2>&1 &
worker_pid=$!
curl --fail http://127.0.0.1:18081/actuator/health
curl --fail http://127.0.0.1:18081/actuator/prometheus
request_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
python3 scripts/v3/worker-dev.py inject-command --config "$POCOMA_I5_CONFIG" --request-id "$request_id"
python3 scripts/v3/worker-dev.py verify --config "$POCOMA_I5_CONFIG" --request-id "$request_id" --await-seconds 90
python3 scripts/v3/worker-dev.py inspect --config "$POCOMA_I5_CONFIG"
python3 scripts/v3/worker-dev.py stop --config "$POCOMA_I5_CONFIG" --pid "$worker_pid" \
  --log-file /private/tmp/pocoma-i5-demo.log
wait "$worker_pid"
```

L’injection est un outil SQL interne avec son propre rôle, distinct du Worker ; ce n’est pas une admission HTTP métier. `verify` exige résultat CHANGED/V1, publications POT/COMPLETION, version réelle, slot Command absent et, si activées, deux traces READ techniques avec slots absents. Les métriques ne remplacent pas ces assertions SQL. Aucun token de fencing n’est un label. Le health décrit seulement le cycle local du Worker et ne fait aucun emprunt JDBC ; UP ne certifie pas la joignabilité courante de PostgreSQL. Les erreurs de boucle/renewal et les observations SQL séparées complètent ce signal.

Le script stop vérifie PID, commande, jar et fichier de configuration. Succès uniquement après le marqueur frais `I5_WORKER_STOPPED pid=...` émis après résolution physique et callback Worker. Une sortie du processus sans marqueur est INCONCLUSIVE. Après105s, avant le timeout Spring120s, une terminaison forcée est annoncée CRASH, jamais STOPPED. Les pools ne sont pas volontairement fermés pendant une transaction non qualifiée ; UNKNOWN peut maintenir DRAINING. Cela couvre aussi une opportunité Discovery/Consumption dont une unité Control est revenue sans résolution physique : même avec capacité métier à zéro, sa responsabilité L1 reste en vol. Les ports actuels ne permettent pas de la régler localement ; après l’échéance externe, une terminaison est CRASH puis reprise fenced normale, jamais un arrêt réussi ni une restitution par délai.

Redémarrer avec la même commande java et le même fichier ; `verify` sur le même request_id doit constater les mêmes résultats sans nouvelle mutation. Injecter ensuite une nouvelle UUID pour vérifier la reprise. La propriété durable reste PostgreSQL ; aucune remise à zéro de K, claim, quarantaine ni compteur d’essai. En cas de panne, attendre les échéances et le fencing I3. Une panne PostgreSQL durable n’autorise aucune promesse de renewal.

## Ressources et limites

Une seule WorkerCapacity ; M_W=4, M_C=1/2/3 par défaut du guide. Quatre Hikari physiques : Business M_W+2 chacun, Control M_W+6 chacun, minIdle=max. Pas de cinquième pool. Deux boucles, exécuteur fixe sans file (`SynchronousQueue`, `AbortPolicy`), deux voies claims singleton et une voie leases par autorité, maintenance séparée. `LifecycleSettings.pilot()` est un point de départ mesuré, jamais une certification de production. Les limites de verrou/SQL/transaction/socket/borrow et les marges sont validées ensemble au démarrage ; changer ces hypothèses exige de rejouer les preuves A/B/C sur les deux autorités.

Acquisitions synchrones : elles peuvent retarder les autres Consumptions. La rotation conserve le prochain bénéficiaire sous saturation ; aucune borne temporelle absolue sous blocage durable. Un rejet après acquisition appelle seulement Engine.abandon ; le claim durable attend son expiration, ce qui peut ajouter jusqu’à sa durée à la démonstration. Les renouvellements de claims valides continuent après perte du lease de partition. Un lot renewal UNKNOWN n’est jamais réaffecté localement, même après résolution DB constatée extérieurement.

L3 Identity, L4 projections métier, L5 HTTP/Bruno/Invitation, L6 charge/reconstruction intégrées, L7 convergence historique et L8 distribution restent différés. Un jar exécutable et un Command réel ne ferment pas ces lots.

Une inspection de recovery ou observation RO en issue UNKNOWN exclut aussi la voie de maintenance : aucune nouvelle inspection ne peut servir de preuve de résolution de cette unité ; le ticket concerné et l’arrêt restent conservateurs jusqu’à terminaison/reprise. Une omission de lease SKIP LOCKED physiquement qualifiée suit une autre règle : usage local suspendu, revalidation bornée par la voie existante et réouverture seulement sur CAS SQL positif de la même incarnation. Le budget local ne prouve ni expiration DB, ni takeover, ni fin d’une transaction.

## Qualification corrective temporelle et tours Discovery

Le correctif AUD-I5-01 utilise exclusivement l'override expérimental existant :

```properties
pocoma.v3.worker.lifecycle.lease-duration-ms=90000
```

Le défaut productif de 30 secondes reste inchangé et **NON CERTIFIÉ** par le modèle conservateur. Un résultat à 90 secondes ne qualifie ni production, ni toutes les charges/concentrations de Workers, ni panne PostgreSQL/réseau durable, ni pause JVM non bornée. Le takeover après crash peut attendre l'échéance de 90 secondes : les délais de reprise des anciens essais à 30 secondes ne sont pas transférés.

Le contrat versionné [I5-C-90-v1](../testing/evidence/L2-I5-audit-remediation/temporal-contract-v1.properties) est archivé avant campagne et identifié par SHA-256 dans chaque preuve. Les seuils fixes sont T=5s pour l'opération complète (emprunt, unité physique, retour/nettoyage), δ=5s de vieillissement, J=1s de retard cumulé hors temps actif, I=10s de cadence, P=1s d'attente inactive, q=10ms par passage et S=2s de sécurité. C(N,R)=(N+R)×T+P+(N+R)×q+J ; δ+I+C+S doit être strictement inférieur au TTL. Le maximum N=8/R=1 donne 64,09s<90s ; le modèle nominal N=8 refuse 30s. Les claims singleton gardent 60s, H≤M_W et une voie disponible pour les autres handles non en vol.

Enveloppe : W=1 ou 2 ; M_W=1 ou 4 ; M_C Command=1/ProbePot=2/ProbeCompletion=3 ; B=1 ou32 ; 4 partitions par C, réparties 0|1 et2|3 en W2. Chaque Worker conserve ses quatre pools Business=M_W+2 et Control=M_W+6 par autorité. W2 établit 40 ou64 connexions de rôles sur un serveur de100, avec16 réservées aux autres connexions. Les variantes gardent des préparations RO réelles et injectent un GC. Deux cycles sont exigés : nominal, puis une omission SKIP LOCKED transitoire par Worker/autorité, libérée au premier retour omis. **R=1 est une limite contrôlée de ces scénarios**, aucune garantie générale du runtime. Toute omission supplémentaire est conservée et invalide l'enveloppe.

Les vecteurs extérieurs PostgreSQL couvrent toutes les12 leases et tous les claims retenus, avec mêmes tokens, nouvelles échéances et horloge DB. Une métrique JVM ne remplace jamais ce constat. Les maxima mesurés restent descriptifs ; ils ne révisent aucun seuil. Une calibration est NON CERTIFIANTE ; un dépassement admis est FAIL ; un constat manquant ou un run interrompu est INCONCLUSIVE.

`pocoma.v3.rotation.tours{consumption="Command",loop="discovery"}` compte une seule fois chaque tour burnable réellement terminé après retour transactionnel normal et conservation de `RequestProgress.next()`. Une page intermédiaire, une rotation C/p, une admission, CAPACITY, absence d'ownership, exception ou résultat perdu ne crée aucun tour. Une source vide ou un tour sans nouvelle admission compte après confirmation protégée. Les sources Event non burnables suivent leur checkpoint et ne produisent aucun tour burnable. Les métriques locales sont volatiles ; une panne d'observer peut perdre l'observation sans modifier la transaction ni le progrès.

État du dossier correctif : **qualification temporelle arrêtée, aucun certificat90s acquis**. La première variante W2 a montré une omission READ supplémentaire non injectée : six appels pour quatre candidats, au-delà de N+R=5 avec R=1. Le contrat v1 et l'échantillon défavorable sont conservés. Voir [le rapport correctif](L2_I5_Audit_Remediation_Report.md) ; une décision distincte et une nouvelle validation sont nécessaires avant reprise de la qualification. Le défaut30s reste non certifié.
