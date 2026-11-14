# L7-LB — provisionnement Keycloak et préparation du clean reset

Mandat : GO_L7_LB_KEYCLOAK_BOOTSTRAP_AND_CLEAN_RESET.
Départ : 80792858d6aac9c648ebcfac2fb2d84ccf80154e, arbre propre,
branche feat/l7-lb-local-playground. Aucune délégation d’agent.

## Verification scope (déclaré avant implémentation)

Expected production impact : distribution locale L7-LB, génération et preuves
Keycloak, sortie développeur, préparation d’un nettoyage ciblé.
Primary slice : LOCAL DISTRIBUTION / Keycloak / Bruno.
Secondary slices : NONE Maven ; WEB/COMMAND/PROJECTION exercés par API publiques.
Forbidden production impact : app/, Java/POM, SQL, contrats d’identité V3,
workers/baux, règles d’isolation du produit, fixtures partagées et L8/production.
Required local proof : unittest Python, composition et contrôles Bruno JS,
build de distribution et start/status/gui/bruno sur instance neuve, moteur
Bruno 4.2.1 installé, stop/start et canari renouvelé ; inventaire et dry-run.
Global architecture gate : NOT REQUIRED, aucune dépendance/frontière modifiée.
Full reactor : NOT REQUIRED, aucun jalon global ni intégration main.
Database verification : CURRENT V3 sur PG neuf possédé, bootstrap existant ;
pas de baseline TRUSTED supposée ni de migrations historiques V2.
Next mandatory global gate : I6 / intégration main selon politique inchangée.
Escalation : tout besoin de modifier app/ exige une nouvelle déclaration et
les slices canoniques touchées avant implémentation ; modification de frontière
ou de dépendance ajoute la gate d’architecture normative.

Commandes prévues :

```sh
python3 -B -m unittest discover -s scripts/local -p 'test_*.py'
python3 -B scripts/local/sync_bruno_playground.py --check
python3 -B scripts/local/test_keycloak_installation.py --instance keycloak-release-validation --output <preuves-neuves>
python3 -B scripts/local/inventory.py --output <inventaire-neuf>
```

Le start assemble depuis app/ :
`./mvnw -pl runtime-web-read,runtime-web-write,runtime-worker-materialize,runtime-worker-process -am package -DskipTests`.
Ce package n’est pas une preuve de tests Maven ni un full reactor non filtré.
La qualification ne lance **aucun** reset/destroy : même l’instance de
qualification et les essais échoués restent préservés jusqu’à confirmation.

## Hypothèses et choix

Compte admin standard au premier démarrage Keycloak 26.7.0, sans API de
provisionnement spécifique Pocoma ; admin/master et utilisateurs locaux
third/guest/bruno, tous au mot de passe local pocoma. Secrets techniques
inchangés et ports loopback. Aucun changement du fichier realm partagé :
seul le rendu L7-LB adapte les utilisateurs. Les anciens secrets/volumes
ne sont jamais modifiés implicitement.

Preuve : console master par Code+S256 ; comptes activés sans action initiale
par Admin REST ; chaque compte pocoma par le client et la callback Bruno
Code+PKCE, y compris refus sans PKCE/plain et sujet exact. Les tokens et
mots de passe ne vont jamais dans les reçus. La preuve console exploite
l’ID token OIDC pour sub : le client console 26.7.0 émet un access token
allégé sans sub (comportement vérifié physiquement lors du premier essai).

Nettoyage : inventorier tous les manifestes, UUID, projets, volumes, réseaux,
conteneurs et orphelins potentiels ; qualifier avant tout dry-run de suppression,
puis présenter les cibles exactes. La commande destroy ciblée complète reset
(qui recrée les données), avec confirmation DESTROY:<instance>, garde de
propriété et conservation de runs. **Aucune destruction sans confirmation
humaine supplémentaire**, exigence explicite §6.2 du mandat.
