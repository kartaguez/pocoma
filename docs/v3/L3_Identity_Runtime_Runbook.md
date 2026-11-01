# L3 — Runbook DEV Identity et protocole Q-ID

**1er novembre 2026 — QUALIFICATION IN PROGRESS — CLOSURE PENDING HUMAN ACCEPTANCE.**

Ce document rectifie le runbook Gemini `0ba18ec1`, conservé aux octets d’origine
dans Git et indexé dans Historical_Rectification.json. Q-ID a réellement exécuté
préparation, Identity seule, sept demandes, SQL indépendant, deux drains et
restart. Les argv, configurations non secrètes, sources opérateur et diagnostics
réels sont dans `docs/testing/evidence/L3-I5/Q-ID-public.tar.gz`. Les exemples
généralisés ci-dessous sont contrôlés statiquement ; ils ne sont pas présentés
comme un second rejeu. Monolithe et Identity+Command reposent sur les preuves
historiques I4 ; leurs jars n’ont pas été redémarrés ici.

## Frontière et prérequis

DEV jetable, deux bases neuves WRITE/READ technique, schémas non publics,
quatre rôles distincts non administrateurs : WRITE, READ, réactivation, injecteur.
Ne jamais utiliser une base V2 ou un serveur partagé. Administrateur PostgreSQL,
propriétaires et provisionneur sont de confiance. Les compositions livrées
utilisent JpaTransactionManager/SpringAttemptTransactions. La composition
manuelle DataSourceTransactionManager avec holder prélié reste hors qualification.

Outils existants : `scripts/v3/worker-dev.py`, classe Java
`V3WorkerDevelopmentTools`. CONTROLLED_INTERNAL ne certifie ni AuthN externe ni
consentement ; aucun endpoint HTTP Identity n’est utilisé ou défini ici.
Conserver logs, configuration, intentions, tokens et lignes SQL exactes en privé
(répertoire 0700, fichiers 0600), sans shell tracing. Export public : champs
autorisés et tokens null/présence/SHA-256 seulement.

## Construction et nouvelle instance

Depuis la racine, après déclaration COMMAND et préflight Git :

```sh
(cd app && ./mvnw -pl runtime-command-consumption-worker -am clean package -DskipTests)
QID_JAR="$PWD/app/runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar"
export QID_JAR
umask 077
QID_PRIVATE=$(mktemp -d /private/tmp/pocoma-qid.XXXXXX)
export QID_PRIVATE
python3 -B - <<'PY'
import os, secrets, shlex, uuid
from pathlib import Path
p = Path(os.environ['QID_PRIVATE'])
v = {'QID_CONTAINER':'pocoma-qid-'+uuid.uuid4().hex[:10],
     'POCOMA_I5_ADMIN_USER':'postgres',
     'POCOMA_I5_REACTIVATION_ROLE':'qid_react',
     'POCOMA_I5_INJECTOR_ROLE':'qid_inject'}
for k in ['POCOMA_I5_ADMIN_PASSWORD','POCOMA_I5_REACTIVATION_PASSWORD',
          'POCOMA_I5_INJECTOR_PASSWORD','QID_WRITE_PASSWORD','QID_READ_PASSWORD']:
    v[k] = secrets.token_hex(24)
v['POSTGRES_PASSWORD'] = v['POCOMA_I5_ADMIN_PASSWORD']
f = p/'credentials.env'
f.write_text(''.join('export '+k+'='+shlex.quote(x)+'\n' for k,x in v.items()))
f.chmod(0o600)
PY
. "$QID_PRIVATE/credentials.env"
docker run --detach --name "$QID_CONTAINER" -e POSTGRES_PASSWORD \
  -p 127.0.0.1::5432 \
  postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73 \
  -c 'log_line_prefix=%m [%p] xid=%x state=%e '
docker exec "$QID_CONTAINER" pg_isready -U postgres
QID_CLIENT_PORT=$(docker port "$QID_CONTAINER" 5432/tcp | cut -d: -f2)
export QID_CLIENT_PORT
export POCOMA_I5_ADMIN_URL="jdbc:postgresql://127.0.0.1:$QID_CLIENT_PORT/postgres"
export POCOMA_I5_PSQL_CONTAINER="$QID_CONTAINER"
```

Attendre la disponibilité dans une borne explicite de 30s ; en cas d’échec,
arrêter avant préparation. Le packaging compile sans exécuter les tests et ne
produit aucun PASS de test. Identifier SHA code/jar/outils et toolchain ; refuser
un jar plus ancien que les sources ou des artefacts d’un autre SHA.

Observer l’adresse physique via le **même JDBC TCP** avant l’allowlist. Q-ID a
obtenu `172.17.0.2/32`, port serveur 5432. Le simple IP Docker sans `/32` a été
refusé avant DDL. Ne pas confondre port client publié et port physique serveur.
L’observateur RO local archivé n’affiche que adresse/port/user/standard strings :

```sh
tar -xOf docs/testing/evidence/L3-I5/Q-ID-public.tar.gz \
  operator/ObserveTarget.java.txt > "$QID_PRIVATE/ObserveTarget.java"
python3 -B - <<'PY'
import os, zipfile
from pathlib import Path
with zipfile.ZipFile(os.environ['QID_JAR']) as z:
    name = next(n for n in z.namelist() if n.startswith('BOOT-INF/lib/postgresql-'))
    (Path(os.environ['QID_PRIVATE'])/'postgresql.jar').write_bytes(z.read(name))
PY
java --class-path "$QID_PRIVATE/postgresql.jar" "$QID_PRIVATE/ObserveTarget.java" \
  > "$QID_PRIVATE/observed-target.txt"
```

## Configuration complète Identity seule

Le profil réel est **`v3-consumption-worker-dev` dans les deux racines** ;
`v3-monolith-dev` n’existe pas. `dev-targets` est obligatoire : exactement deux
tuples host,clientPort,database,schema,role,serverAddress,serverPort. Business et
Control ont la même cible et les mêmes credentials par autorité ; WRITE et READ
diffèrent. Pour M_W=4, pools **Business=6 / Control=10**, minimum-idle égal au
maximum. La valeur 4 de l’ancien runbook est refusée par les settings livrés.

```sh
python3 -B - <<'PY'
import os
from pathlib import Path
p = Path(os.environ['QID_PRIVATE'])
address, server_port, user, standard = (p/'observed-target.txt').read_text().strip().split('|')
assert user == os.environ['POCOMA_I5_ADMIN_USER'] and standard == 'on'
port = int(os.environ['QID_CLIENT_PORT'])
v = {
 'pocoma.v3.worker.enabled':'true', 'pocoma.v3.worker.environment':'DEV',
 'pocoma.v3.worker.worker-name':'l3-identity',
 'pocoma.v3.worker.identity-authority':'real',
 'pocoma.v3.worker.identity-request':'true',
 'pocoma.v3.worker.fixture-identity':'false',
 'pocoma.v3.worker.command-enabled':'false',
 'pocoma.v3.worker.technical-probes':'false',
 'pocoma.v3.worker.worker-limit':'4',
 'pocoma.v3.worker.consumption-limits':'IdentityRequest=4',
 'pocoma.v3.worker.partitions':'IdentityRequest=0|1|2|3',
 'pocoma.v3.worker.dev-targets':
    f'127.0.0.1,{port},qid_write,qid,qid_w,{address},{server_port};'
    f'127.0.0.1,{port},qid_read,qid,qid_r,{address},{server_port}',
 'server.port':'0', 'management.server.port':'0'}
for authority, role, secret in [('write','qid_w','QID_WRITE_PASSWORD'),
                                ('read','qid_r','QID_READ_PASSWORD')]:
    for kind, size in [('business','6'),('control','10')]:
        prefix = f'pocoma.v3.worker.{authority}-{kind}.'
        v.update({prefix+'url':f'jdbc:postgresql://127.0.0.1:{port}/qid_{authority}?currentSchema=qid',
                  prefix+'username':role, prefix+'password':os.environ[secret],
                  prefix+'maximum-pool-size':size, prefix+'minimum-idle':size})
    for key, value in [('database','qid_'+authority),('schema','qid'),('username',role)]:
        v[f'pocoma.v3.pilot.{authority}.{key}'] = value
f = p/'runtime.properties'
f.write_text(''.join(k+'='+value+'\n' for k,value in v.items()))
f.chmod(0o600)
PY
QID_CONFIG="$QID_PRIVATE/runtime.properties"
export QID_CONFIG
```

Budgets livrés inchangés : lease 30s, claim 60s, renewal 10s, drain 90s ; checks
de marge conservés. Cela ne qualifie pas 30s sous charge. Ports HTTP éphémères,
sans endpoint métier utilisé. Une évolution des rôles/logging/manager/cible ou
de ces hypothèses impose son diagnostic/requalification.

## Préparation administrative unique

```sh
python3 -B scripts/v3/worker-dev.py prepare --config "$QID_CONFIG"
```

La CLI crée rôles et bases : **ne pas les précréer**, elle refuse les identités
existantes avant DDL. Elle installe pgcrypto/public, WRITE CURRENT V1–V6, READ
technique V1, sans migrations historiques V2 ni baseline TRUSTED supposée.
WRITE réel commence par `configure-l3-identity-logging.sql` ; chaque autorité
enchaîne `bootstrap-l2-routing.sql`, `grant-l2-lifecycle.sql`,
`grant-l2-discovery.sql`, `bootstrap-l2-worker-dev.sql`, `grant-l2-worker-dev.sql`.
Les rôles auxiliaires et `identity_read_role` sont explicitement certifiés.

Sorties réelles : `I5_DEV_VALIDATED two authorities; no Worker threads or
coordination mutation`, puis `I5_DEV_PREPARED; start/restart never calls prepare`.
Le reçu s’appelle **`runtime.prepared.json`** (`Path.with_suffix`), pas
`runtime.properties.prepared.json`. Conserver configuration/reçu/logs privés.
Préparation ≠ certification. Aucun DDL/reset au démarrage ou restart.

Un grant excessif est refusé, jamais révoqué automatiquement ; l’administrateur
le retire avant requalification. Les préflights runtime ne remplacent pas les
contrôles administratifs des rôles auxiliaires. PG exige prefix SQLSTATE/PID/XID,
verbosity terse, aucun log statement/duration/paramètre sensible. Reconnecter les
sessions après installation des defaults de base ; APM/audit/production non qualifiés.

## Démarrage, arrêt et restart

```sh
java -jar "$QID_JAR" \
  --spring.profiles.active=v3-consumption-worker-dev \
  --spring.config.additional-location="file:$QID_CONFIG" \
  > "$QID_PRIVATE/worker-1.log" 2>&1 &
QID_PID=$!
```

Jar **absolu** obligatoire pour l’identification `ps -ww -p` de `stop`, qui
contrôle aussi profil/config. Observer le démarrage dans le log privé ; le seul
message Spring ne prouve pas la réussite des demandes.

```sh
python3 -B scripts/v3/worker-dev.py stop --config "$QID_CONFIG" \
  --pid "$QID_PID" --log-file "$QID_PRIVATE/worker-1.log"
```

Code 0 + `I5_DEV_STOPPED` exige le callback `I5_WORKER_STOPPED pid=...` après
SIGTERM/drain physique ; la JVM peut sortir normalement 143 sous SIGTERM.
Code 3 : sorti sans callback, INCONCLUSIVE. Code 2 : délai 105s dépassé/SIGKILL,
CRASH ; arrêt non qualifié. Aucun timeout augmenté, aucune reprise aveugle.

Restart : même java/jar/config/base, nouveau log/PID, **sans prepare**. Relire les
sept résultats, comparer Users/ancres/histoire/demandes/résultats/événements/
compteurs/positions avant arrêt et après restart ; les leases techniques peuvent
évoluer sans appartenir à ce vecteur métier. Second arrêt avec son nouveau
log/PID. Ces deux drains et l’égalité durable sont démontrés par Q-ID ; aucune
huitième demande ajoutée. Une PENDING pendant arrêt relève de la preuve H3
historique et n’est pas présentée comme une observation nouvelle Q-ID.

## Sept intentions, SQL et reçus

Suivre intégralement le [plan I5 §10.2](L3_I5_Qualification_and_Closure_Plan.md#102-exécution-q-id-et-préflight-statique-approfondi-résolution-r-i5-02).
Ordre : Registration A, Link A→B, Unlink A→B, Registration C, Q-UFREE A→B,
Q-UOTHER A→C, Q-REGNEW Registration A nouvel UUID après succès R1.
Un UUID distinct et created-at UTC avec au plus six décimales pour chaque demande.

Fichier Java Properties régulier 0600, symlink refusé : `request-id`, `created-at`,
`operation`, `requester.issuer`, `requester.subject`, `target.issuer`,
`target.subject`. REGISTRATION a requester==target et omet `binding-token`.
LINK/UNLINK portent le token de l’initiateur dans le fichier privé, jamais argv.
L’identité opaque suit Java Properties/ISO-8859-1, avec escapes Unicode si requis.
Les variables suivantes désignent les fichiers privés de la demande courante :

```sh
python3 -B scripts/v3/worker-dev.py inject-identity \
  --config "$QID_CONFIG" --intention-file "$QID_INTENTION_FILE"
python3 -B scripts/v3/worker-dev.py verify-identity \
  --config "$QID_CONFIG" --request-id "$QID_REQUEST_ID" --await-seconds 90
python3 -B scripts/v3/worker-dev.py identity-token \
  --config "$QID_CONFIG" --own-key-file "$QID_OWN_KEY_FILE" --token-output "$QID_TOKEN_FILE"
```

`own-key-file` contient `issuer`/`subject`, 0600 ; la sortie token est un nouveau
fichier privé exclusif 0600. Helper administratif DEV, aucun token cible tiers
nécessaire. `verify-identity` n’expose **pas** `--expected` dans le CLI Python :
il constate COMPLETED/résultat et absence de slot. Le rejet exact exige SQL.

Avant R5 : A liée UA v1/token courant, B libre v2. Avant R6 : C liée UC v1, UC≠UA.
Avant R7 : R1 TERMINAL/CHANGED, A liée v1, UUID neuf. Rejets respectifs
`TARGET_NOT_BOUND`, `TARGET_BOUND_TO_ANOTHER_USER`, `TARGET_ALREADY_BOUND` ;
cible/version du résultat nulles.

Les SQL normatifs sont dans l’archive publique : `oracle-fingerprints.sql`,
`oracle-rejected.sql`, `oracle-final.sql`. Les exécuter depuis une session
`psql -X -v ON_ERROR_STOP=1` indépendante de la JVM, observateur autorisé (admin
du banc), search_path `qid`, transactions REPEATABLE READ READ ONLY. Le rejet
utilise les variables psql `rid` et `expected_rejection`.

Comparer avant/après les quatre paires count/empreinte de toutes les lignes
Users/ancres/histoire/mutations ; IDENTITY inchangé, COMPLETION +1. Conserver les
lignes exactes privées et leurs exports expurgés. `json_agg` peut être multiligne :
décoder les valeurs JSON entières, pas une ligne physique à la fois. Tous les
booléens rejet/isolation/densité sont vrais, zéro slot ; final 11/11 vrais :
2 Users/3 ancres/4 versions, 7 terminaux, 4 CHANGED, 3 REJECTED, événements 6/7.

Archiver sept UUID, trois reçus, SQL avant/après, contexte protégé, versions,
tokens protégés, SHA code/jar/outils/config, horodatages et invocation. Contrôler
la confidentialité avant Git. Les versions opérateur archivées décrivent cette
campagne et ses diagnostics, sans remplacer le protocole pour un nouveau banc.

## Identity + Command et Monolithe

Le mode I4 réel conserve identity-authority=real, fixture-identity=false,
identity-request=true et active exactement :

```properties
pocoma.v3.worker.command-enabled=true
pocoma.v3.worker.consumption-limits=IdentityRequest=4,Command=4
pocoma.v3.worker.partitions=IdentityRequest=0|1|2|3;Command=0|1|2|3
```

M_W/pools inchangés. Command seule réelle utilise identity-request=false,
Command=4 et ses quatre partitions ; le préflight Identity réel reste obligatoire.
L’injecteur Identity réel **n’a pas** les grants d’admission Command : ne pas
présenter `inject-command` comme outil d’admission réelle dans cette configuration.
Les tests I4 `l3_registration_command_and_relink_restart` utilisent leur admission
Command interne contrôlée, distincte. A1 conserve le seul SELECT technique
anti-effets ; aucun accès métier Pot sur négatif stable.

Monolithe : module runtime-monolith, jar
`app/runtime-monolith/target/pocoma-runtime-monolith-0.0.1-SNAPSHOT-exec.jar`,
**même profil v3-consumption-worker-dev**. Sa fabrication bornée serait
`./mvnw -pl runtime-monolith -am package -DskipTests` depuis app/, sous déclaration
appropriée ; **non exécutée ici**. Les 7 cas Monolithe et 10 Worker sont attribués
à E4, pas à un nouveau démarrage jar Monolithe. READ L4/HTTP L5 restent futurs.

## Diagnostic et arrêt du scope dépendant

Violation d’invariant, fuite, rejet inattendu, oracle ambigu, drain incomplet,
perte de preuve ou besoin de correctif productif : conserver FAIL/INCONCLUSIVE
et diagnostiquer avant poursuite. Ne pas répéter une campagne inchangée, modifier
une assertion ou augmenter un timeout pour obtenir PASS.

`consumption_slot` n’a **pas de colonne last_error**. Observer sous autorisation
consumable_id/state/attempt_count/claim_token/claimed_until/retry_after, sans
exporter les tokens de claim bruts. Le SQL de réactivation incomplet Gemini est
remplacé par le script existant `scripts/v3/reactivate-l2-slot.sql`, CAS avec
expected_database/schema/admin, consumption_id/consumable_id et search_path exact.
UPDATE 1 prouve la transition ; UPDATE 0 n’est pas un succès. Le script remet
aussi claim_token/claimed_until/retry_after à null. Aucune réactivation exécutée
ou nécessaire pour Q-ID.

UNKNOWN exige l’observation durable et le fencing L2 ; ni l’échéance locale ni le
retour d’un appel ne rendent prématurément une capacité. DRAINING reste conservé
tant que la responsabilité physique demeure indéterminée. Aucune disponibilité
universelle ou certification temporelle sous charge n’est promise.
