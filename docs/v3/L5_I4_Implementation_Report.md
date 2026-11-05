# L5/I4 — Invitation et désaffiliation volontaire

État : EN COURS — aucune clôture ni certification anticipée.

## Mandat et baseline

Le 5 novembre 2026, la demande humaine « Implémente ce plan » autorise la réalisation du plan opérationnel I4 fourni dans la conversation. Elle remplace son statut de proposition sans GO. Aucun commit, push ou déploiement n'est demandé.

Branche initiale : `feat/v3-architecture`. HEAD initial : `ca21034f7873ed5c16f296665ae2aaf63d4090d7`, identique à la référence ; arbre initial propre.

Arbitrages complémentaires explicitement approuvés dans la conversation :

- Trousseau AES-GCM dédié, configuration Spring externe existante, identifiant de clé dans le chiffré ; sauvegarde hors Git avec les bases et conservation des anciennes clés pour restauration. Aucun gestionnaire de secrets nouveau. Les opérateurs des environnements restent responsables de fournir et sauvegarder le matériel réel ; aucun secret de production n'est généré dans le dépôt.
- `expiresAt` UTC explicite fourni par le client ; aucun TTL/plafond métier ajouté. Lien headless `pocoma://invitation/<token>` ; restitution READ par creationRequestId, consultation des places par POST avec token dans le corps.

## Portée déclarée avant code

Impact prévu : invitations WRITE, contrats/admission/résultats Command, publication Invitation, source historique et projection READ, consultations HTTP, composition et qualification ciblée. Désaffiliation : qualification du chemin `UpdateShareholderDetails`, aucune nouvelle commande.

Slice primaire WEB ; secondaires COMMAND et PROJECTION ; ancre complémentaire `runtime-monolith`. Niveau INTEGRATION pour les preuves transactionnelles. Bases CURRENT V3 WRITE et READ séparées ; migrations additives seulement, pas de baseline V2 TRUSTED supposée.

Interdits : nouvelle autorité Identity, changement des transitions Identity, moteur/module/worker nouveau, modification des algorithmes L2 Consumption/Discovery/lease/recovery, relèvement M/pools/lease, mutation Pot fictive, secret en événement/résultat/log, récupération HTTP synchrone WRITE.

Référence T4, depuis `app/` :

```sh
./mvnw -pl runtime-web-api,runtime-command-consumption-worker,runtime-task-consumption-worker,runtime-monolith -am test -Dtest=V3InvitationWritePostgresTest,V3InvitationReadPostgresTest,V3InvitationHttpTest,V3InvitationTopologyPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

Les assertions de départ volontaire rejoignent `V3AdmissionFlowPostgresTest` et leur sélection complémentaire sera déclarée avant exécution. Classes/méthodes manquantes et skips interdisent un PASS T4.

Canoniques : WEB `./mvnw -pl runtime-web-api -am test` ; COMMAND `./mvnw -pl runtime-command-consumption-worker -am test` ; PROJECTION `./mvnw -pl runtime-task-consumption-worker -am test`. Leur gate non filtrée et Architecture globale restent REQUIRED, NOT RUN, différées à Q5 selon le mandat. Full natif unique/G5, B5 et certification SPEC-10 restent REQUIRED, NOT RUN. Aucun full par défaut.

Escalade : nouvelle frontière productive, modification d'owner/algorithme L2, règle métier supplémentaire ou impossibilité de preuve atomique → suspendre le composant concerné et redéclarer la portée avant poursuite.

## Préflight observé

- Contrat public existant : `UpdateShareholderDetails.payload.targets[].attachment = DETACH`. Le mapping d'exécution produit `Optional.empty()` et le domaine autorise l'intéressé ou le créateur ; aucune garde interdisant le dernier affilié.
- Six participants READ présents, catalogue et routage Invitation déjà réservés ; source et matérialiseur Invitation absents.
- Quatre classes T4 Invitation absentes à la baseline ; dossier B4 absent. Leur absence n'est pas une preuve.
- CLI Bruno local verrouillé `@usebruno/cli` 4.3.0 ; aucun `bru` global. Java actif Temurin 21, cible POM 21.
- Accès Docker initial refusé dans le sandbox (`permission denied` sur le socket) ; aucune campagne PostgreSQL lancée à ce stade.

## Exécution

À compléter avec les commandes réellement exécutées, résultats et preuves attribuées aux entrées modifiées.

Franchissement inattendu : aucun à ce stade. Architecture globale : REQUIRED / NOT RUN / Q5. Full reactor : NOT RUN / Q5. Qualification globale : NOT ACQUIRED.
