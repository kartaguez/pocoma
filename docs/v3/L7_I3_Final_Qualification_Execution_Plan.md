# L7.3 — Qualification complémentaire des 31 obligations

Mandat `GO_L7_I3_FINAL_QUALIFICATION`. Référence locale et distante `c4f5f0c11114921cdc3a735f1fdaeb32c33537c7`, branche `feat/v3-architecture`, worktree initial propre. Les trois rapports Completion ont été lus. [Matrice opérationnelle individuelle](../testing/evidence/L7-I3-final/execution-plan.json).

## Frontière déclarée avant implémentation

Impact produit attendu : aucun. Tranche primaire COMMAND pour Identity/publication ; secondaire PROJECTION pour les participants et reprise ; composition Monolith pour Invitation. Aucun changement de production, dépendance, privilège ou migration prévu. Un défaut avéré imposera une reproduction et une révision de périmètre avant correction. Les arbitrages de schéma/contrat sont soumis à décision humaine.

Feedback ciblé, depuis `app/` : `./mvnw --batch-mode --no-transfer-progress -pl runtime-monolith -am '-Dtest=V3InvitationReadPostgresTest#invitationPublicationRejectsMissingUnknownAndWrongRequests' -Dsurefire.failIfNoSpecifiedTests=false test`, puis sélection explicite des nouveaux scénarios dans `runtime-worker-process` et `runtime-worker-materialize`. Rapports isolés et identifiant neuf par exécution, campagnes PostgreSQL en série. CURRENT jetable Testcontainers, sans réinitialisation utilisateur. Gate Architecture et full reactor non déclenchés par ces seuls ajouts de tests ; réemploi limité des preuves inchangées. Aucun Bruno, SPEC-09, L7.4 ou L7.5.

## Scénarios groupés

- INV-SQL : principal runtime réel, droits effectifs, insertion/publication avec création_request_id NULL, UUID inexistant ou mauvaise opération existante. NOT NULL/FK/contrainte différée déterminent les refus attendus. Comparaison extérieure des Invitations, événements, positions et compteurs, rollback de l'unité. Aucun état métier altéré pour contourner les contraintes.
- ID-PUB : deux Registrations concurrentes et deux événements par lot ; T1 retenue après une position, T2 attestée en attente PostgreSQL. Observation du préfixe publié avant résolution ; commit et rollback T1 ; séquences denses et aucun lot intercalé après résolution. Même fenêtre de références que Discovery réelle.
- READ : participants réels, échec de deux branches pendant réussite d'une troisième, quarantaine V puis V+1 et reprise tardive. Les garanties exactes de version, solde et visibilité restent distinctes.
- ADMIN : sources, DDL, scripts et gardes ; distinguer Runtime contraint, propriétaire SQL et pouvoir d'un administrateur externe. Aucun contournement des refus de purge ajoutés.
- RECONCILE : lire les oracles indiqués et leurs variantes exactes avant tout test supplémentaire. Une ancienne trace partielle ne devient pas complète au seul motif qu'elle est retrouvée.

Chaque ligne garde son exigence, condition de clôture, dépendance et blocage dans la matrice. Les 31 lignes sont bloquantes pour le stimulus restant ; aucune réserve non bloquante ni reclassification de facilité n'est présumée.

Complément COMMAND R3-10/R5-11 : deux assemblages du même catalogue partagent les schémas avec partitions candidates Command disjointes, limites Worker 1/4 et polling différents. Vérifier les bindings/limites réellement assemblés, la terminalisation des deux commandes et leurs publications. Pour la configuration invalide, partir d'une vraie demande PENDING et exiger refus du démarrage, vecteur de coordination intact et absence de résultat ou budget consommé. Ces tests ne certifient pas un débit ou un délai de disponibilité.
