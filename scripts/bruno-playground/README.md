# Pocoma V3 Playground — collection interactive de référence

Ouvrir **ce dossier** dans Bruno GUI 4.2.1 : 171 requêtes HTTP visibles,
dont 51 requêtes par domaine, 2 connexions OAuth et 118 étapes de parcours.
Les corpus ../bruno-v3 (qualification spécialisée avec fixtures) et ../bruno
(historique) ne sont pas les points d'entrée du playground.

Depuis la racine du dépôt :

    ./pocoma-local start
    ./pocoma-local gui

Dans Bruno : **Open Collection** → ce dossier → environnement **Instance-demo**.
**00 - Getting Started / 00 Login Owner → Auth → Get Access Token** (third),
puis **01 Login Guest** (guest). Mots de passe synthétiques locaux dans
.pocoma-local/instances/demo/secrets.json, champ users ; les saisir
uniquement dans le formulaire Keycloak, sans copier de JWT.

Premier parcours : **90 - End-to-End Journeys / J01 Complete Ledger and Invitation**,
**Run Folder**. Les 66 étapes sont des requêtes explicites, éditables.
J02 traite erreurs/confidentialité/idempotence ; J03 traite Unlink et réinscription.
Pour conserver un Pot d'exploration, utiliser les dossiers par domaine ; J01/J02
suppriment leur Pot à la fin. La CLI exécute exactement ces trois dossiers :

    ./pocoma-local bruno

[Guide complet, variables, requêtes isolées et recette GUI humaine](../../docs/v3/L7_Local_Playground_Bruno.md).
[Contrat consommateur](../../docs/v3/L7_Local_Playground_Public_API.md).
[Matrice exhaustive](../../docs/v3/L7_Local_Playground_Bruno_API_Coverage.csv).

Les GET asynchrones attendent seulement leur propre ressource, au plus 30 s,
avant le GET affiché. Les POST ne déclenchent aucune autre mutation.
Aucun token dans les environnements. Binding, invitation et replay sont des
variables runtime sensibles : ne pas exporter les variables, réponses ou timelines.

Maintenance : modifier les .bru par domaine, puis composer les parcours :

    python3 -B scripts/local/sync_bruno_playground.py
    python3 -B scripts/local/sync_bruno_playground.py --check

journeys.json décrit les séquences et leurs assertions supplémentaires ;
catalogue.json indexe les contrats et préconditions. Le générateur n'est
jamais appelé par les requêtes GUI. Les modifications d'une copie Jxx sont libres
pour explorer ; reporter une correction durable dans la source par domaine.
