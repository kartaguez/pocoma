# L7-LB — plan de vérification Grafana

Mandat : `GO_L7_LB_GRAFANA_INTEGRATION`, branche `feat/l7-lb-local-playground`.
Politique normative lue : `docs/testing/Reactor_Verification_Policy.md`.

Déclaration avant implémentation :

- Impact attendu : distribution locale Python/Compose, provisioning Grafana,
  contrats JSON additifs, readiness, cycle de vie, documentation et qualification.
- Slice primaire : outillage L7-LB local (`scripts/local`, configuration générée).
- Slices Maven primaire/secondaires : aucune. WEB, COMMAND, PROJECTION,
  FOUNDATION ne changent pas ; leurs runtimes participent à la preuve Docker.
- Impact interdit : code sous `app/`, SQL métier, migrations historiques,
  frontières/modules/dépendances Maven, profils de production/Internet.
- Gate d'architecture globale : non requise ; réacteur complet : non requis.
- Bases : conservation de demo et de ses comptes/secrets ; bootstrap CURRENT
  existant uniquement pour la fixture neuve/reset explicitement nommée.
- Escalade : changement de code applicatif, contrat, schéma ou frontière →
  déclaration révisée puis commande canonique de chaque slice réellement touchée.

Preuves prévues :

1. `python3 -B -m unittest discover -s scripts/local -p 'test_*.py'` : orchestration,
   readiness négative, absence de faux READY, sécurité et propriété des volumes.
2. `python3 -B scripts/local/sync_bruno_playground.py --check` : 118 requêtes et
   236 tests nommés, cohérence de la composition livrée.
3. `python3 -B scripts/local/test_grafana_installation.py --instance demo --fixture grafana-validation --output docs/testing/evidence/L7-LB-GRAFANA/docker-qualified` :
   mise à niveau de demo, vrais neuf conteneurs, authentification/datasource/panels,
   données préservées, Bruno, pannes Grafana/Prometheus, stop/reprise, fixture
   neuve/reset/destroy et conservation des ressources extérieures.
4. Qualification complémentaire après toute adaptation Grafana révélée par la
   preuve réelle : rejouer les contrôles directement affectés avec l'empreinte finale.

Le packaging déclenché par start emploie les quatre anchors runtimes avec `-am
package -DskipTests` ; il assemble la distribution, sans verdict de test Maven.
Aucun Maven concurrent. Aucun reset/destroy sur demo. La seule fixture jetable
fait l'objet des commandes explicites RESET/DESTROY avec nom et confirmation.
Les traces sensibles restent privées ; seules les preuves expurgées sont livrées.
