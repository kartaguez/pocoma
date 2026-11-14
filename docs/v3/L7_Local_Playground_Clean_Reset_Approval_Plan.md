> **Exécution après confirmation : [demo disponible avec écart de nettoyage documenté](L7_Local_Playground_Clean_Reset_Report.md).** Le texte ci-dessous conserve le plan et l’attribution de la première phase ; les sept volumes Prometheus annoncés comme exclus n’ont pas été préservés.

# L7-LB — plan exact de clean reset soumis à confirmation

**AUCUNE DESTRUCTION EXÉCUTÉE.** Ce plan exige votre confirmation explicite (§6.2 du mandat).

Qualification finale : [PASS](../testing/evidence/L7-LB-KEYCLOAK-RESET/release/qualification.json).
Inventaire : [13 instances et ressources exactes](../testing/evidence/L7-LB-KEYCLOAK-RESET/inventory.json).
Dry-runs : [argv et résultats](../testing/evidence/L7-LB-KEYCLOAK-RESET/dry-runs.json).

## Cibles exclusives

| Instance | État physique | Projet Compose exact | Conteneurs | Volume présent | Réseau présent |
| --- | --- | --- | ---: | --- | --- |
| demo | STOPPED | pocoma-lb-demo-a4951783 | 8 | pocoma-lb-demo-a4951783-data | pocoma-lb-demo-a4951783_default |
| gui-test | STOPPED | pocoma-lb-gui-test-a39eabe4 | 8 | pocoma-lb-gui-test-a39eabe4-data | pocoma-lb-gui-test-a39eabe4_default |
| i3-bruno | STOPPED | pocoma-lb-i3-bruno-e72e2740 | 8 | pocoma-lb-i3-bruno-e72e2740-data | pocoma-lb-i3-bruno-e72e2740_default |
| i3-final | STOPPED | pocoma-lb-i3-final-1f5dfefa | 8 | pocoma-lb-i3-final-1f5dfefa-data | pocoma-lb-i3-final-1f5dfefa_default |
| i3-workshop | STOPPED | pocoma-lb-i3-workshop-325b57b9 | 8 | pocoma-lb-i3-workshop-325b57b9-data | pocoma-lb-i3-workshop-325b57b9_default |
| j1-a-0bab6b8c | AUCUN OBJET DOCKER | pocoma-lb-j1-a-0bab6b8c-a28342a9 | 0 | aucun | aucun |
| j1-a-2ad2a840 | AUCUN OBJET DOCKER | pocoma-lb-j1-a-2ad2a840-ba43381e | 0 | aucun | aucun |
| j1-a-cbaf923f | AUCUN OBJET DOCKER | pocoma-lb-j1-a-cbaf923f-9288f629 | 0 | aucun | aucun |
| j1-bootstrap-2ad2a840 | AUCUN OBJET DOCKER | pocoma-lb-j1-bootstrap-2ad2a840-b88f9eea | 0 | aucun | aucun |
| keycloak-release-validation | RUNNING | pocoma-lb-keycloak-release-validation-0837ce8e | 8 | pocoma-lb-keycloak-release-validation-0837ce8e-data | pocoma-lb-keycloak-release-validation-0837ce8e_default |
| keycloak-validation | STOPPED | pocoma-lb-keycloak-validation-b80171fb | 2 | pocoma-lb-keycloak-validation-b80171fb-data | pocoma-lb-keycloak-validation-b80171fb_default |
| keycloak-validation-final | STOPPED | pocoma-lb-keycloak-validation-final-8dcc274e | 8 | pocoma-lb-keycloak-validation-final-8dcc274e-data | pocoma-lb-keycloak-validation-final-8dcc274e_default |
| lb-one | AUCUN OBJET DOCKER | pocoma-lb-lb-one-83c444ca | 0 | aucun | aucun |

Total : **58 conteneurs, 8 volumes PG (WRITE/READ/Keycloak), 8 réseaux**. Les cinq instances sans objet Docker ont encore des fichiers générés privés. Les UUID de propriété, IDs Docker, montages, chemins de répertoires et états des manifestes sont conservés dans inventory.json.

## Pertes et conservation

Pour chaque volume présent : perte irréversible des bases WRITE/READ et Keycloak, comptes, inscriptions/bindings, Pots/dépenses/invitations et historique local. Pour les treize répertoires .pocoma-local/instances/<nom> : suppression des fichiers générés, secrets, JAR, configuration, manifestes et checkpoints demo/canary. Les diagnostics runs/ et le fichier lock sont conservés ; ils ne constituent plus une instance utilisable sans manifeste. Les données modifiées manuellement sont perdues, pas seulement le seed.

Sont exclus : les **7 volumes sans autorité de propriété L7-LB**, les réseaux système bridge/host/none, images et caches, fichiers Git et autres projets. Aucun orphelin potentiel L7-LB observé. Aucun docker system prune, aucune suppression Docker globale.

## Commandes après confirmation seulement

Avant mutation : refaire l’inventaire et les contrôles UUID/labels ; toute nouvelle ressource, changement de propriétaire ou cible non répertoriée suspend le nettoyage pour examen. Chaque destroy arrête proprement, exécute Compose down --volumes pour son projet, vérifie l’absence de ressources résiduelles de ce projet et seulement alors retire les fichiers générés. Un arrêt forcé ou Compose manquant bloque la commande.

```sh
./pocoma-local destroy --instance demo --confirm DESTROY:demo
./pocoma-local destroy --instance gui-test --confirm DESTROY:gui-test
./pocoma-local destroy --instance i3-bruno --confirm DESTROY:i3-bruno
./pocoma-local destroy --instance i3-final --confirm DESTROY:i3-final
./pocoma-local destroy --instance i3-workshop --confirm DESTROY:i3-workshop
./pocoma-local destroy --instance j1-a-0bab6b8c --confirm DESTROY:j1-a-0bab6b8c
./pocoma-local destroy --instance j1-a-2ad2a840 --confirm DESTROY:j1-a-2ad2a840
./pocoma-local destroy --instance j1-a-cbaf923f --confirm DESTROY:j1-a-cbaf923f
./pocoma-local destroy --instance j1-bootstrap-2ad2a840 --confirm DESTROY:j1-bootstrap-2ad2a840
./pocoma-local destroy --instance keycloak-release-validation --confirm DESTROY:keycloak-release-validation
./pocoma-local destroy --instance keycloak-validation --confirm DESTROY:keycloak-validation
./pocoma-local destroy --instance keycloak-validation-final --confirm DESTROY:keycloak-validation-final
./pocoma-local destroy --instance lb-one --confirm DESTROY:lb-one
```

Retirer ensuite seulement les environnements publics générés correspondant aux instances ci-dessus, après contrôle de fichier régulier sans symlink :

```sh
rm -- scripts/bruno-playground/environments/Instance-demo.bru
rm -- scripts/bruno-playground/environments/Instance-gui-test.bru
rm -- scripts/bruno-playground/environments/Instance-i3-final.bru
rm -- scripts/bruno-playground/environments/Instance-i3-workshop.bru
rm -- scripts/bruno-playground/environments/Instance-keycloak-release-validation.bru
rm -- scripts/bruno-playground/environments/Instance-keycloak-validation-final.bru
```

Aucun autre environnement n’est retiré. Les tokens natifs déjà en cache Bruno ne sont pas exportés ; Clear Cache sera nécessaire sur Owner/Guest pour le nouvel issuer.

Puis créer uniquement la référence demo :

```sh
./pocoma-local start --instance demo
./pocoma-local status --instance demo
./pocoma-local gui --instance demo
./pocoma-local bruno --instance demo
python3 -B scripts/local/inventory.py --output /tmp/pocoma-inventory-after-clean-reset.json
```

Vérifier : seule demo a un manifeste et des ressources Docker ; les 58 anciens IDs, 8 anciens volumes et 8 anciens réseaux sont absents ; les 7 volumes exclus et réseaux système sont préservés. Les diagnostics anciens restent conservés. demo atteint READY, canari et quatre comptes fonctionnent, Bruno pointe vers Instance-demo. Admin : URL imprimée, realm master, admin/pocoma ; utilisateurs pocoma third/guest/bruno, mot de passe pocoma.

**Confirmation demandée : autoriser la perte des données des 13 cibles de cette table, retirer leurs environnements publics listés et recréer une seule demo selon ces commandes.**
