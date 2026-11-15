# Pocoma V3 Playground — collection interactive de référence

Ouvrir **ce dossier** dans Bruno GUI 4.2.1 ou 4.3.0 : 171 requêtes HTTP visibles,
dont 51 requêtes par domaine, 2 connexions OAuth et 118 étapes de parcours.
Les corpus ../bruno-v3 (qualification spécialisée avec fixtures) et ../bruno
(historique) ne sont pas les points d'entrée du playground.

Depuis la racine du dépôt :

    ./pocoma-local start
    ./pocoma-local gui

Dans Bruno : **Open Collection** → ce dossier → environnement **Instance-demo**.
**00 - Getting Started / 00 Login Owner → Auth → Get Access Token** (third),
puis **01 Login Guest** (guest). Mot de passe **pocoma** pour les deux comptes.
Le compte de compatibilité **bruno / pocoma** reste disponible dans le realm
**pocoma**. Console admin : URL `keycloakAdmin.url` fournie par start/gui,
realm **master**, **admin / pocoma**. Convention exclusive au playground
L7-LB local, jamais pour L8/production ; les secrets techniques PostgreSQL
restent aléatoires. Les anciens volumes ne changent pas implicitement.
Saisir ces identifiants dans Keycloak, sans copier de JWT.

Premier parcours : **90 - End-to-End Journeys / J01 Complete Ledger and Invitation**,
**Run**. Les 66 étapes sont des requêtes explicites, éditables.
J02 traite erreurs/confidentialité/idempotence ; J03 traite Unlink et réinscription.
Pour conserver un Pot d'exploration, utiliser les dossiers par domaine ; J01/J02
suppriment leur Pot à la fin. La CLI exécute exactement ces trois dossiers :

    ./pocoma-local bruno

Arrêt sans perte : `./pocoma-local stop`. Après redémarrage Docker :
`./pocoma-local start`, `status`, puis `gui`. Reset volontaire : consulter
le [guide de diagnostic et nettoyage contrôlé](../../docs/v3/L7_Local_Playground_Keycloak_Diagnostics.md)
avant de confirmer la destruction des données d’une instance.

[Guide complet, variables, requêtes isolées et recette GUI humaine](../../docs/v3/L7_Local_Playground_Bruno.md).
[Contrat consommateur](../../docs/v3/L7_Local_Playground_Public_API.md).
[Matrice exhaustive](../../docs/v3/L7_Local_Playground_Bruno_API_Coverage.csv).

Les GET asynchrones attendent seulement leur propre ressource, au plus 30 s,
avant le GET affiché. Les POST ne déclenchent aucune autre mutation.
Aucun token dans les environnements. Binding, invitation et replay sont des
variables runtime sensibles : ne pas exporter les variables, réponses ou timelines.

En exploration manuelle : **P01 Create Pot → 05 - Results / R01 Command Result →
P02 Update Pot Details → R01 → P04 Read Current Pot**. P01 répond 202 et laisse
`potVersion` à 0 ; R01 récupère le résultat métier et renseigne sa version positive.
Sans cette lecture, `observedVersion: 0` entraîne `INVALID_INPUT` sur la modification.
Le script bloque désormais cet envoi et indique le prérequis manquant.

Maintenance : modifier les .bru par domaine, puis composer les parcours :

    python3 -B scripts/local/sync_bruno_playground.py
    python3 -B scripts/local/sync_bruno_playground.py --check

journeys.json décrit les séquences et leurs assertions supplémentaires ;
catalogue.json indexe les contrats et préconditions. Le générateur n'est
jamais appelé par les requêtes GUI. Les modifications d'une copie Jxx sont libres
pour explorer ; reporter une correction durable dans la source par domaine.
