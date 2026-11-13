# L7-LB / Jalon 1 — I1a et déclaration de vérification

Référence : `2a4e7fd8a79d798b4bcb983a0d5f9466bc5241aa`, arbre initial propre,
branche `feat/l7-lb-local-playground`. Le distant de cadrage pointe sur cette
référence au début de mission. Mandat : GO_L7_LB_LOCAL_PLAYGROUND_IMPLEMENTATION.

## I1a — constats et plan avant implémentation

- Compose historique : noms et volume globaux, IPAM fixe, PG publié, mots de
  passe de fixture et images flottantes. Ne pas le réutiliser comme instance.
- Bootstrap `setup-v3-database.py` : réutiliser ses listes SQL CURRENT V3 et
  grants, mais remplacer le transport, les mots de passe et les transactions.
  La présence d'une table seule ne prouve pas un bootstrap complet. Aucun
  baseline TRUSTED disponible ; pas de chaîne V2 historique.
- Cinq rôles JVM déjà distribuables par quatre JAR exec Java 21. Réutiliser les
  propriétés, participants, pools, baux et contrôles de cible DEV sans changer
  le graphe Maven ou les règles d'isolation du runtime.
- Keycloak 26.7.0 : vrai client public S256 existant, sans password grant.
  Réutiliser le protocole de `functional-auth-proof.py`, secrets aléatoires
  privés par instance ; GUI via callback loopback et navigateur utilisateur.
- Bruno 4.3.0 verrouillé existe ; les anciens corpus B1 utilisent des fixtures.
  Livrer une collection playground autonome, sans injection READ, oracles
  explicites et inventaire strict non vide, sans token dans argv/rapports.
- ARM64 : observer les architectures des images, versions/digests et limites
  Docker ; cible 16 Go, cinq heaps bornés, pas de campagne concurrente.

Fichiers prévus : façade `pocoma-local`, `scripts/local/{runtime,bootstrap,
public_api,auth,test_local}.py`, Dockerfile JRE et collection Bruno playground ;
guides local/Bruno/API, rapports, réserves, trois documents de cadrage et index.
État privé ignoré de Git ; verrou POSIX par instance ; labels UUID sur toutes
les ressources ; pas de suppression implicite, reset explicitement confirmé.

## Verification scope

Expected production impact: NONE (Java, POM, migrations, contrats inchangés).
Primary slice: LOCAL DISTRIBUTION / orchestration et bootstrap CURRENT V3.
Secondary slices: public protocol consumers WEB / COMMAND / PROJECTION exercised
by real five-JVM public journeys; no Maven source change anticipated.
Forbidden production impact: business/security contracts, dependencies, runtime
isolation rules, leases, slots, SPEC thresholds, historical database evolution.
Global architecture gate: NOT REQUIRED (no dependency/boundary/rule change).
Full reactor: NOT REQUIRED (Jalon 1 only, no main integration or L7/I6 closure).
Database verification: CURRENT V3 migrations in new isolated PG; transactional
schema bootstrap, checksum marker, restricted runtime principals, no V2 chain.
Operational level: INTEGRATION distribution proof, not global certification.

Commands planned: Python unittest discovery for local safety/protocol controls;
`./mvnw -pl runtime-web-write,runtime-web-read,runtime-worker-process,runtime-worker-materialize -am package -DskipTests`
from `app/` to build the actual distribution (not a test PASS); two fresh
instances, start/status, fresh canary on restart, stopped critical worker,
Bruno actual inventory, stop/reset ownership and data isolation checks.

Escalation: before any product defect correction declare owning canonical
slices and run their unfiltered commands: WEB
`./mvnw -pl runtime-web-write,runtime-web-read -am test`, COMMAND
`./mvnw -pl runtime-worker-process -am test`, PROJECTION
`./mvnw -pl runtime-worker-materialize -am test`. Change to module boundaries or
runtime isolation rules adds `./mvnw -pl architecture-tests -am test`.
No silent escalation and no full reactor by precaution. I1b (522 obligations)
is deferred to I4/I6, without certifying or waiving any historical reservation.
